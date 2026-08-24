# Plan: 주식 일봉 3년치 아카이브 수집

## 관련 문서

- Spec: `./spec.md`
- 요구사항: `ai/prd.md` MKT-011 (인접 — MKT-005 수집·보관, MKT-009 일·주·월봉, MKT-002 재생)
- ADR: `0002-architecture`(레이어), `0003-testing-strategy`(테스트), `0004-flyway-migrations`(마이그레이션), `0020-managed-service-deployment`(RDS)
- 기존 구현 참고: `KisHistoricalCandleClientImpl`, `KisHistoricalCandleCollector`, `KisHistoricalCandleImportWriter`

## 설계 요지 — 기존 KIS 배관을 그대로 쓴다

1분봉 수집기가 이미 해결해 둔 것들을 다시 만들지 않는다. 실제로 시간이 드는 부분은 대부분 여기에 있다.

| 이미 있는 것 | 위치 | 이 spec에서 |
|---|---|---|
| KIS 인증 토큰 발급·캐싱(약 24시간) | `KisHistoricalCandleClientImpl` | 그대로 재사용 |
| 레이트리밋 재시도 — 실측 성공률 약 67%(2026-07-30)라 재시도 없이는 종목당 전체 성공률이 30%까지 떨어진다 | 〃 | 그대로 재사용 |
| 역방향 페이징 루프 + 무한루프 안전 상한 | 〃 | 커서를 **시각 → 날짜**로 바꿔 같은 모양으로 |
| 다중 인스턴스 중복 수집 방지 락 | `StockCollectionLock` | 그대로 재사용 |
| 수집 이력 기록 | `MarketDataImport` | 그대로 재사용 |
| 트랜잭션 경계 분리(수집 ↔ 저장) | `KisHistoricalCandleImportWriter` | 같은 패턴으로 신설 |

## 결정 1 — 전량 적재와 증분을 한 배치로 합친다

최초 3년 적재용 일회성 절차를 따로 두지 않는다. 배치가 종목마다 **"있어야 할 구간(3년 전 ~ 직전 영업일) − 이미 저장된 구간"**을 계산해 빈 구간만 조회한다.

- 첫 실행 → 저장된 게 없으므로 3년 전량을 채운다.
- 정상 운영 → 직전 영업일 1건만 채운다.
- 재실행 → 채울 구간이 없어 KIS를 호출하지 않고 끝난다.

**대안(기각): 전량 적재를 운영자 수동 1회 절차로 분리.** 배포 환경마다 사람이 한 번씩 실행해야 하고, 빠뜨리면 차트 깊이가 환경별로 달라진다. 위 방식은 같은 코드가 두 경우를 모두 덮으면서 재실행이 안전하다.

**안전 밸브**: 한 번의 실행이 종목당 호출할 수 있는 페이지 수에 상한(`MAX_PAGES_PER_SYMBOL=12`)을 둔다. 3년치는 최소 8페이지면 충분해 현재 파라미터로는 이 상한에 걸리지 않는다.

**정정(2026-08-20, 코드 리뷰 지적)**: 상한에 실제로 걸리는 경우(보관 기간 확장, KIS 페이지당 반환 건수 축소 등) "다음 실행이 이어서 채운다"는 **성립하지 않는다.** `StockDailyCandleCollector.computeRangeStart`는 **가장 최근 저장 거래일**만 보고 다음 조회 시작점을 정하므로(위 결정 1), 상한에 막혀 못 채운 **더 과거 구간**은 이후 어떤 재실행으로도 다시 채워지지 않는다 — 매 실행이 항상 "최신 저장일+1"부터만 전진하기 때문이다. `KisDailyCandleClientImpl.fetchDailyCandles`는 이 상황을 감지하면(요청한 `from`에 도달하지 못하고 페이지 상한으로 종료) 경고 로그를 남기지만, 자동으로 그 구간을 다시 채우는 gap-fill 로직은 없다. 현재 파라미터로는 도달 확률이 낮아 차단 사유가 아니라고 판단해 이번 spec에서는 로그만 남기고, gap-fill 자체는 후속 이슈로 남긴다.

## 결정 2 — 저장은 별도 테이블

`stock_candles`에 넣지 않는다. 두 테이블은 **보관 정책이 정반대**다(20영업일 삭제 vs 3년 누적). 같은 테이블에 두면 1분봉 정리 작업(이슈 #83)이 아카이브를 삭제한다. 자세한 대조는 spec의 "두 데이터의 구분" 표.

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | /api/dev/stock-daily-imports | 없음 | `StockDailyImportTriggerResponse` | **(local 프로필 전용)** 일봉 아카이브 배치를 즉시 1회 실행. 기존 `/api/dev/stock-replay-imports`와 같은 성격의 개발 도구 |

**회원용 API는 이 spec에서 늘지 않는다.** 캔들 조회(`GET /api/instruments/{instrumentId}/candles`)의 계약·동작은 그대로다 — 아카이브를 조회 경로에 연결하는 것은 후속 spec이다(spec §범위 제외).

## 데이터 모델

### `stock_daily_candles` (신설)

| 컬럼 | 타입 | 비고 |
|---|---|---|
| `id` | BIGINT PK AUTO_INCREMENT | |
| `instrument_id` | BIGINT NOT NULL, FK → `instruments` | |
| `trading_date` | DATE NOT NULL | 실제 달력 거래일 (재생의 `source_trading_date`와 무관) |
| `open`·`high`·`low`·`close` | DECIMAL(18,4) NOT NULL | `stock_candles`와 동일 정밀도 (C-003) |
| `volume` | BIGINT NOT NULL | |
| `data_source` | VARCHAR(50) NOT NULL | `KIS_DAILY` |
| `collected_at` | DATETIME NOT NULL | |

- `UNIQUE(instrument_id, trading_date)` — 멱등성의 근거(STOCK-DAILY-006). 1분봉의 `UNIQUE(instrument_id, trading_date, candle_time)`에서 `candle_time`이 빠진 형태다.
- 이 유니크 인덱스가 "종목의 최신 저장 거래일 조회"와 "기간 범위 조회"를 모두 커버하므로 별도 인덱스를 두지 않는다.
- 규모: 주식 16종 × 약 750영업일 ≈ **1.2만 행**. `db.t4g.micro`(ADR-0020) 기준 저장 부담이 없는 규모라 파티셔닝 등 별도 저장 전략을 도입하지 않는다(1분봉이 12.5만 행에서 기본 설정으로 충분하다고 판단한 것과 같은 근거).

### 마이그레이션

`V54__create_stock_daily_candles.sql` — **작성 직전에 `git ls-tree origin/dev src/main/resources/db/migration/`로 번호 선점을 다시 확인한다.** 병렬 브랜치가 V53을 먼저 쓰면 번호 역전으로 배포가 막힌다(ADR-0004, 예외 처리는 ADR-0027).

**실제로 발생함(2026-08-21)**: PR #509(`049-tutorial-order-basics-script`)가 이 PR의 리뷰가 끝난 뒤 `dev`에 먼저 머지되면서 같은 V53을 `V53__add_scenario_script_id_to_practice_attempts.sql`로 선점했다. 파일명이 달라 git 병합 충돌은 나지 않았지만 Flyway는 버전 번호로 마이그레이션을 식별하므로 그대로 두면 기동 시 "V53 중복" 오류가 난다. 이 PR(나중에 생성된 쪽)을 `origin/dev` 머지 후 V54로 옮기고 아래 참조를 전부 갱신했다(ADR-0004 "충돌을 발견하면 나중에 생성된 폴더를 다음 빈 번호로 옮긴다" 규칙 그대로).

## Decision Gate 해소 — 실제 KIS API 호출로 확인한 사실 (2026-08-20)

**이 절은 Fake·모의 검증이 아니라 실제 KIS Open API(모의투자 도메인, `https://openapivts.koreainvestment.com:29443`)를 curl로 직접 호출해 확인한 결과다 (C-005).** 대상 종목은 `instruments` 시드의 삼성전자(`005930`, `market=STOCK`, `V7__create_instruments.sql`). 인증은 `KisHistoricalCandleClientImpl`과 같은 `/oauth2/tokenP`(`client_credentials`) 흐름을 재사용했다. 원본 응답 JSON은 시크릿을 포함하지 않으므로 발췌만 남긴다.

### 1. `output2` 필드명 확정

`GET /uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice`(`tr_id=FHKST03010100`, `FID_PERIOD_DIV_CODE=D`, `FID_INPUT_ISCD=005930`, `FID_INPUT_DATE_1=20260801`, `FID_INPUT_DATE_2=20260820`)를 호출한 실제 응답 발췌:

```json
{
  "stck_bsop_date": "20260820",
  "stck_clpr": "271000",
  "stck_oprc": "257000",
  "stck_hgpr": "273000",
  "stck_lwpr": "252500",
  "acml_vol": "26095919",
  "acml_tr_pbmn": "6961393123500",
  "flng_cls_code": "00",
  "prtt_rate": "0.00",
  "mod_yn": "N",
  "prdy_vrss_sign": "2",
  "prdy_vrss": "23500",
  "revl_issu_reas": ""
}
```

확정 매핑 (`RawDailyCandleDto` → 이 필드들만 사용):

| 내부 의미 | `output2` 필드 | 형식 |
|---|---|---|
| 거래일 | `stck_bsop_date` | `yyyyMMdd` (8자리 문자열, `DateTimeFormatter.BASIC_ISO_DATE`로 파싱) |
| 시가 | `stck_oprc` | 문자열 정수 → `BigDecimal` |
| 고가 | `stck_hgpr` | 〃 |
| 저가 | `stck_lwpr` | 〃 |
| 종가 | `stck_clpr` | 〃 |
| 거래량 | `acml_vol` | 문자열 정수 → `long`(누적거래량, 1분봉의 `cntg_vol`과 달리 "당일 누적"이라는 이름이지만 일봉에서는 그날 하루 총 거래량과 같다) |

`acml_tr_pbmn`(거래대금)·`flng_cls_code`·`prtt_rate`·`mod_yn`·`prdy_vrss*`·`revl_issu_reas`는 쓰지 않는다 — C-006(정규화된 필드만 저장)에 따라 `RawDailyCandleDto`에 매핑하지 않는다.

응답은 **최신 날짜가 먼저 오는 내림차순**이다(위 예시에서 `output2[0]`이 `20260820`, 이후 과거로 감). 날짜 커서 역방향 페이징(STOCK-DAILY-002)이 이 순서와 일치한다.

**1회 호출 응답 건수 상한은 100행이다** — 2023-08-20~2026-08-20(3년) 구간을 한 번에 요청했더니 `output2`가 정확히 100건만 오고 가장 오래된 행이 `20260326`이었다(3년 전체가 아니라 최근 100영업일만). 종목당 3년(약 750영업일)을 채우려면 **페이지당 100행 기준 최소 8회 호출**이 필요하다 — `MAX_PAGES_PER_SYMBOL` 산정의 근거.

### 2. 수정주가 옵션(`FID_ORG_ADJ_PRC`) 의미 확정

같은 종목(005930)의 2018년 50:1 액면분할(2018-05-04) 전후 구간(`FID_INPUT_DATE_1=20180420`~`FID_INPUT_DATE_2=20180515`)에 `FID_ORG_ADJ_PRC=0`과 `FID_ORG_ADJ_PRC=1`을 각각 호출해 종가(`stck_clpr`)를 대조했다:

| 거래일 | `FID_ORG_ADJ_PRC=0` 종가 | `FID_ORG_ADJ_PRC=1` 종가 |
|---|---|---|
| 20180504 (분할 후 첫 거래일) | 51,900 | 51,900 |
| 20180503 (분할 전 마지막 거래일) | 53,000 | **2,650,000** |
| 20180420 | 51,620 | **2,581,000** |

**결론**: `FID_ORG_ADJ_PRC=0`은 **수정주가**(액면분할 경계에서 연속적 — 51,900 ↔ 53,000처럼 자연스러운 등락) 이고, `FID_ORG_ADJ_PRC=1`은 **원주가**(액면분할 경계에서 50배 단절 — 53,000 → 2,650,000)다. spec의 우려(원주가로 저장하면 차트에 인위적 급등락 발생)가 그대로 재현됐다.

**결정: 수집·저장에는 `FID_ORG_ADJ_PRC=0`(수정주가)을 쓴다.** 이유:
- 3년 구간에 액면분할·병합이 들어와도 연속된 차트를 얻을 수 있다 — spec 우려사항 해소.
- MKT-005가 분봉에서 정한 선례(원본 그대로, 단 이쪽은 액면분할이 문제되지 않는 짧은 재생 구간이라 원주가를 그대로 씀)와 달리, 일봉 아카이브는 3년 누적이라 성격이 다르다 — 같은 값을 그대로 따르지 않고 이 spec에 맞는 값을 새로 확정한 것이다.
- `KisDailyCandleClientImpl` 구현 시 `FID_ORG_ADJ_PRC=0`을 상수로 고정한다(사용자 입력 없음).

## 구성요소

| 클래스 | 역할 |
|---|---|
| `StockDailyCandle` (엔티티) | 일봉 1행. `market.domain` |
| `StockDailyCandleRepository` | 종목별 최신 저장 거래일 조회, 기간 조회, 벌크 저장 |
| `KisDailyCandleClient` (인터페이스) | 종목·기간을 받아 정규화된 일봉 목록 반환 |
| `KisDailyCandleClientImpl` | `inquire-daily-itemchartprice`(`FHKST03010100`) 호출 + **날짜 커서 역방향 페이징** + 레이트리밋 재시도. 토큰 캐싱은 기존 구현과 공유 |
| `FakeKisDailyCandleClient` | 자동 테스트용 (C-005 — Fake 통과를 실연동 성공으로 보고하지 않는다) |
| `StockDailyCandleCollector` | 배치 진입점. 종목별 빈 구간 계산 → 클라이언트 호출 → writer 위임. 락·실패 격리·이력 기록 |
| `StockDailyCandleImportWriter` | 저장 트랜잭션 경계. 종목 하나의 실패가 다른 종목 트랜잭션을 말아 올리지 않게 분리 |
| `RawDailyCandleDto` | KIS 응답 → 내부 모델 사이의 원시 표현 |

**배치 스케줄: 평일 08:25 KST로 확정** (2026-08-20, task 4 구현 시). 기존 시각과 겹치지 않는지 전수 확인했다 — 1분봉 정규 배치 08:10(`KisHistoricalCandleCollector.collect`), 1분봉 재시도 08:15/08:30/08:45(`market.stock.retry-cron`="0 15,30,45 8-10 * * MON-FRI"), 재생세션 확정 배치 08:40(`StockReplaySessionScheduler`). 08:25는 이 중 어디와도 겹치지 않으면서 1분봉 정규 배치가 끝날 여유(통상 수 분 이내 종료)를 둔다.

**다중 인스턴스 락(STOCK-DAILY-011)에 대한 설계 메모**: `StockDailyCandleCollector`는 `StockCollectionLock`을 그대로 재사용하며, 락 키의 유일한 변수인 날짜값으로 1분봉 배치와 동일한 `targetEndDate`(직전 영업일)를 넘긴다. `StockCollectionLock`의 키는 `market:stock-collect:lock:{date}`로 날짜만으로 결정되므로, 이 값이 1분봉 배치와 같으면 **Redis 락 키도 같다** — 클래스를 수정하지 않고 그대로 재사용하는 이상 배치 종류를 구분하는 별도 네임스페이스가 없다. 실제로는 문제가 되지 않는다: 스케줄이 15분 이상 떨어져 있고 락 TTL이 600초(10분)이므로 1분봉 배치가 정상 종료됐다면 08:25에는 이미 풀려 있다. 드물게 못 얻더라도 COLLECT-STAB-001과 같은 방식으로 조용히 스킵하고, 다음 실행이 빈 구간을 그대로 이어서 채우므로(결정 1) 데이터 유실은 없다 — 재검토가 필요하면 `StockCollectionLock`에 배치 식별자를 추가하는 리팩터링을 후속 이슈로 고려한다.

## 입력 명세

회원 입력이 없다(배치·개발용 트리거뿐). KIS 응답에 대한 검증은 아래와 같다.

| 항목 | 규칙 | 위반 시 |
|---|---|---|
| 거래일 | 요청 구간 안의 날짜여야 한다 | 해당 행 폐기, 종목 부분성공으로 기록 |
| OHLC | 모두 존재하고 양수여야 한다 | 〃 |
| 고가·저가 | `low ≤ open·close ≤ high` | 〃 |
| 거래량 | 0 이상 정수 | 〃 |
| 페이지 진행 | 다음 커서가 직전보다 과거여야 한다 | 루프 중단 (무한루프 방지) |

**거래일 수가 적다는 사실 자체는 오류가 아니다** — 거래정지·신규상장으로 정상적으로 적을 수 있다. 개수만으로 실패 판정하지 않는다(MKT-005가 분봉에서 정한 것과 같은 원칙).

## 테스트 계획 (ADR-0003)

- **단위**: 빈 구간 계산(최초·정상·재실행·부분 보유), 날짜 커서 페이징 종료 조건, 응답 검증 규칙, 페이지 상한 도달 시 중단.
- **슬라이스**: `@DataJpaTest` — `UNIQUE(instrument_id, trading_date)` 위반, 종목별 최신 거래일 조회, 기간 조회 정렬.
- **통합(Testcontainers)**: 최초 전량 적재 → 증분 1회 → 재실행 순서로 실행해 행 수가 각각 (3년치) → (+1) → (변화 없음)이 되는지. 한 종목 실패 시 나머지 종목 저장·실패 이력 기록.
- **회귀**: 일봉 배치 실행이 `stock_candles` 행 수를 바꾸지 않는지(STOCK-DAILY-005), 기존 캔들 조회 API 응답이 그대로인지.
- **외부 스모크(자동 테스트와 구분 보고)**: 실제 KIS `inquire-daily-itemchartprice` 1회 호출로 **응답 필드명과 수정주가 옵션 의미 확인** — spec §Decision Gate 두 항목이 여기서 해소된다. **2026-08-20 실제 호출로 해소 완료** — 결과는 위 "Decision Gate 해소" 절 참고.
