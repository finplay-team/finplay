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

**안전 밸브**: 한 번의 실행이 종목당 호출할 수 있는 페이지 수에 상한을 둔다. 첫 실행이 과도하게 길어지면 그 실행은 상한까지만 채우고, 다음 실행이 이어서 채운다(멱등이므로 이어붙이기가 성립한다).

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

`V53__create_stock_daily_candles.sql` — **작성 직전에 `git ls-tree origin/dev src/main/resources/db/migration/`로 번호 선점을 다시 확인한다.** 병렬 브랜치가 V53을 먼저 쓰면 번호 역전으로 배포가 막힌다(ADR-0004, 예외 처리는 ADR-0027).

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

배치 스케줄은 1분봉 수집(08:10)과 겹치지 않는 시각에 둔다 — 두 배치가 같은 KIS 레이트리밋을 나눠 쓰기 때문이다. 정확한 시각은 구현 시 확정한다.

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
- **외부 스모크(자동 테스트와 구분 보고)**: 실제 KIS `inquire-daily-itemchartprice` 1회 호출로 **응답 필드명과 수정주가 옵션 의미 확인** — spec §Decision Gate 두 항목이 여기서 해소된다.
