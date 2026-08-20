# Plan: 주식 시세·차트 마지막 재생 상태 유지

## 관련 문서

- Spec: `./spec.md`
- 이슈: [#384](https://github.com/finplay-team/finplay-backend/issues/384) (결정 사항 4건의 정본)
- 상위 요구사항: `docs/prd.md` MKT-002(재생 규칙)·MKT-004(시세 장애)·MKT-009(집계 캔들), `docs/specs/003-market-data`
- 관련 ADR: ADR-0002(레이어 구조), ADR-0003(테스트 전략)
- 인접 spec: `035-stock-collector-reliability`(장중 수집 결손은 그쪽 영역), `036-remove-crypto-stale-status`(코인의
  같은 성격 문제를 먼저 해결한 선례 — 판정을 없애 마지막 값을 유지)

## 배경 — 지금 어디서 비는가

시세·캔들 조회 3경로가 전부 `StockReplayService.findReadySession(오늘)`([StockReplayService.java:322](../../../src/main/java/com/finplay/api/market/service/StockReplayService.java))에서 시작하고, 비어 있으면 각각 이렇게 끝난다.

| 경로 | 세션 없을 때 | 코드 |
|---|---|---|
| `getCurrentPrices` | 전 종목 `price=null` → `PriceQueryService`가 `UNAVAILABLE`로 변환 | `StockReplayService.java:65`, `PriceQueryService.java:147` |
| `getRevealedCandles` | 빈 목록 | `StockReplayService.java:95` |
| `getRevealedAggregatedCandles` | 빈 목록 | `StockReplayService.java:129` |

세션을 만드는 배치는 평일에만 돈다 — `@Scheduled(cron = "0 40 8 * * MON-FRI")`([StockReplaySessionScheduler.java:43](../../../src/main/java/com/finplay/api/market/service/StockReplaySessionScheduler.java)). 여기에 09:01 이전에는
어떤 분봉도 공개하지 않는 컷오프(`findRevealedCandle`:348 / `resolveRevealCutoff`:368)가 겹쳐, 실제 공백은 이렇게 된다.

| 시각 (KST) | 오늘 세션 | 지금 가격 | 지금 캔들 |
|---|---|---|---|
| 평일 00:00~08:40 | 행 없음 | 없음 | 없음 |
| 평일 08:40~09:00 | READY | 없음 | 없음 |
| 평일 09:00~09:01 | READY | 오늘 첫 분봉 **시가** | 없음(미마감) |
| 평일 09:01~15:30 | READY | 오늘 마지막 마감 분봉 | 오늘 공개분 |
| 평일 15:30~24:00 | READY | 오늘 15:30 분봉 | 오늘 하루치 |
| 토·일·공휴일 | 행 없음 | 없음 | 없음 |

즉 **평일 15:30~24:00은 이미 정상**이고, 자정을 넘기는 순간 깨진다. 금요일 자정 ~ 월요일 09:01 = 약 57시간.

## 설계 핵심 — 기존 판정을 고치지 않고, 빈 자리에만 끼워 넣는다

> **폴백 규칙**: `marketStatus == CLOSED` **이고** 오늘 세션 기준 응답이 비어 있으면, **서비스 날짜가 오늘보다 이전인
> 마지막 READY 세션**의 원본 거래일을 **하루치 전부 공개**로 응답한다.

이 한 문장에서 나머지가 전부 따라 나온다.

- **`CLOSED`일 때만** — 장중에는 폴백이 아예 동작하지 않으므로, 장중 수집 결손(#370)이 옛 값으로 가려지지 않고
  표시 가격과 체결 가격이 갈라질 여지도 없다.
- **오늘 세션은 폴백 대상이 아님** — 오늘 세션의 원본 거래일에는 아직 재생되지 않은 오후가 들어 있다. 08:50에
  오늘 세션을 하루치 공개하면 그날 종가가 개장 전에 새어 나간다. 이것이 이 spec에서 가장 조심해야 할 실패 모드다.
- **과거 서비스 날짜의 READY 세션은 하루치가 이미 공개된 상태** — 그 서비스 날짜의 15:30에 재생이 끝났으므로,
  하루치 전부 공개는 새로운 노출이 아니라 이미 공개됐던 것의 연장이다.
- **기존 컷오프 로직을 건드리지 않는다** — 09:00~09:01 첫 분봉 계약, 미마감 분봉 비노출, 재생거래일 상한 클램프가
  전부 그대로다.

### 적용 후 시각별 판정

| 시각 (KST) | 오늘 세션 | 가격 | 캔들 | `marketStatus` | 주문 |
|---|---|---|---|---|---|
| 평일 00:00~08:40 | 행 없음 | **폴백** | **폴백** | CLOSED | 거부(무변경) |
| 평일 08:40~09:00 | READY | **폴백**(직전 재생일) | **폴백** | CLOSED | 거부(무변경) |
| 평일 09:00~09:01 | READY | 오늘 첫 분봉 시가(무변경) | 빈 배열(무변경) | OPEN | 허용(무변경) |
| 평일 09:01~15:30 | READY | 오늘(무변경) | 오늘(무변경) | OPEN | 허용(무변경) |
| 평일 09:01~15:30, 그 종목만 분봉 없음 | READY | `UNAVAILABLE`(무변경) | 빈 배열(무변경) | OPEN | 409 `PRICE_UNAVAILABLE`(무변경) |
| 평일 15:30~24:00 | READY | 오늘 15:30(무변경) | 오늘 하루치(무변경) | CLOSED | 거부(무변경) |
| 평일, 세션 FAILED·PREPARING | 있음 | **폴백** | **폴백** | CLOSED | 거부(무변경) |
| 토·일·공휴일 | 행 없음 | **폴백** | **폴백** | CLOSED | 거부(무변경) |

**금요일 23:59 → 토요일 00:00 전환은 값이 바뀌지 않는다.** 금요일 세션(서비스 날짜=금)의 원본 거래일 마지막 분봉이
금요일 밤의 값이고, 토요일의 폴백이 찾는 "오늘 이전 마지막 READY 세션"이 바로 그 금요일 세션이기 때문이다. 같은
세션 → 같은 원본 거래일 → 같은 마지막 분봉 → **가격·`sourceTime`·`sourceTradingDate`가 전부 동일**하다. SSE가
전환 시점에 이벤트를 쏘지 않는 것도 이 동일성에서 따라 나온다(QUOTE-HOLD-007을 위한 별도 장치가 필요 없다).

## 구성요소 변경

### 1. `StockReplaySessionRepository` — 폴백 세션 조회 (신규 메서드 1개)

```java
Optional<StockReplaySession> findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(
    LocalDate serviceDate, PreparationStatus preparationStatus);
```

- `serviceDate < 오늘` + `READY` 중 서비스 날짜 최댓값 1건. 날짜 하한을 두지 않는다(QUOTE-HOLD-006).
- 기존 `findFirstByOrderByServiceDateDesc()`를 재사용하지 않는다 — 그쪽은 상태·날짜 조건이 없어 오늘 세션이나
  `FAILED` 세션을 집어 온다.
- 스키마 변경 없음. `service_date`에 `UNIQUE` 제약이 있어 인덱스를 그대로 탄다.

### 2. `StockReplayService` — 폴백 적용 (이 spec의 유일한 로직 변경 지점)

폴백 세션은 **요청당 최대 1회만** 조회한다(기존 "전역 상태는 요청당 1회" 방침 — `getCurrentPrices` 주석 참고).

- `getCurrentPrices` — 오늘 세션이 없거나, 있어도 그 종목의 공개 분봉이 없을 때, `marketStatus == CLOSED`이면
  폴백 세션의 **마지막 분봉**(`findFirstByInstrumentIdAndTradingDateOrderByCandleTimeDesc`, 기존 메서드 재사용)의
  종가로 응답을 채운다.
- `getRevealedCandles` — 오늘 세션이 없거나 컷오프가 없거나 조회 결과가 비었을 때, `CLOSED`이면 폴백 세션의 원본
  거래일 하루치를 반환한다. 시간 범위(`from`·`to`의 `LocalTime`)는 요청이 준 값을 그대로 적용한다.
- `getRevealedAggregatedCandles` — 같은 조건에서 `sourceTradingDate`를 폴백 세션의 원본 거래일로 바꾸고, 그
  거래일은 **컷오프 없이 하루치 전부**를 집계 입력에 넣는다(현재 코드의 "재생거래일 당일은 컷오프까지만" 분기가
  폴백일 때는 `LocalTime.MAX`가 된다). 200개 캡·선두 partial 버킷 필터·`narrowRangeStart`는 그대로 재사용한다.

**폴백 시세 DTO의 형태 (QUOTE-HOLD-005의 실행 수단)**

```java
new StockReplayPriceDto(
    false,                    // sessionReady — 오늘 세션은 준비되지 않았다(사실 그대로)
    StockMarketStatus.CLOSED, // marketStatus — 폴백은 CLOSED일 때만 만들어진다
    폴백세션.getSourceTradingDate(),
    마지막분봉.getClose(),
    LocalDateTime.of(폴백거래일, 마지막분봉.getCandleTime()),
    null)                     // replaySession — 체결 원장에 폴백 세션을 남기지 않는다
```

`sessionReady=false`·`replaySession=null`이 체결 경로의 안전장치다. `PriceQueryService.getOrderExecutionPrice`는
`marketStatus == CLOSED`와 `replaySession == null`을 **가격을 보기 전에** 검사하므로(`PriceQueryService.java:50-55`)
폴백 값이 체결가로 쓰이는 경로가 구조적으로 존재하지 않는다. `local` 프로필의 강제 OPEN 데코레이터도
`quote.sessionReady()`가 `false`면 시장상태를 덮어쓰지 않는다(`LocalForcedOpenStockPriceProvider.java:74`).

### 3. 변경하지 않는 것 (의도적)

- **`StockPriceProvider` 인터페이스·`KisHistoricalReplayPriceProvider`·`LocalForcedOpenStockPriceProvider`** — 표시
  전용 메서드를 새로 추가하지 않는다. 폴백이 `CLOSED`에서만 생기고 DTO가 `sessionReady=false`를 유지하는 이상,
  체결 경로 분리를 위해 메서드를 늘릴 이유가 없다(대안 (a) 참고).
- **`PriceQueryService`** — `isPriceAvailable()`이 `price != null`이므로 폴백 시세는 그대로 `AVAILABLE`로 매핑된다.
  한 줄도 바뀌지 않는다.
- **`CandleQueryService`·`StockPriceStreamService`·`HoldingValuationService`** — 호출부 변경 없음.
- **게이트 우회 메서드** — `getFullDayCandles`·`getPreviousTradingDayClose`·`getCurrentReplaySession`·
  `getSourceTradingDate`에는 폴백을 넣지 않는다(spec 비즈니스 규칙). 특히 `getSourceTradingDate(serviceDate)`는
  피드백(012)의 스포일러 게이트가 "그날 실제로 재생한 거래일"로 쓰는 값이라, 폴백이 섞이면 재생하지 않은 날짜를
  그날의 정답으로 돌려주게 된다.

## API 설계

**신규·변경 엔드포인트 없음.** 기존 응답의 값만 달라진다(`docs/api-routes.md` 갱신 대상 아님).

| Method | URL | 지금(주말) | 이 spec 적용 후(주말) |
|---|---|---|---|
| GET | `/api/instruments/{id}/price` | 409 `PRICE_UNAVAILABLE` | 200 `PriceResponse` — `status=AVAILABLE`, `price`·`sourceTime`·`sourceTradingDate`는 마지막 재생일 값 |
| GET | `/api/instruments/{id}/candles?interval=1m\|1d\|1w\|1M` | `200 []` | 200, 마지막 재생일 기준 봉 목록 |
| GET | `/api/stocks/stream` (`snapshot`) | 전 종목 `UNAVAILABLE`, `sourceTradingDate=null` | 전 종목 `AVAILABLE`, `sourceTradingDate`=마지막 재생일 |
| GET | `/api/stocks/stream` (`price`) | (해당 없음) | 값이 멈춰 있으므로 이벤트 없음 |
| GET | `/api/accounts/{...}/summary` 등 평가 경로 | `costBasis` 대체(원금만) | 실제 평가금액·수익률 |
| POST | `/api/orders` (주식) | 409 `MARKET_CLOSED` | **409 `MARKET_CLOSED` (무변경)** |

## 입력 명세

해당 없음 — 요청 파라미터 변경이 없다.

## 데이터 모델

해당 없음 — 새 테이블·컬럼·Flyway 마이그레이션이 없다. 기존 `stock_replay_sessions`(`service_date` UNIQUE,
`preparation_status`)와 `stock_candles`(`UNIQUE(instrument_id, trading_date, candle_time)`)를 그대로 읽는다.

## 대안 검토

- **(a) 표시 전용 메서드를 인터페이스에 추가해 체결 경로와 물리적으로 분리** — 폴백을 `CLOSED` 조건 없이 넣을
  거라면 필요했다. 폴백을 `CLOSED`로 한정한 지금은 체결 경로가 폴백 값을 볼 수 없어, 인터페이스 2개 확장 +
  데코레이터 + 호출부 재배선이 순수 비용이다. 채택하지 않는다.
- **(b) 주말에도 재생세션을 만든다(cron을 매일로)** — 주말에 새 거래일이 흐르게 되어 "마지막 상태 유지"라는 요청
  자체와 어긋난다. 영업일 판정·수집 배치·`market_data_imports` 계약까지 번지므로 범위 밖(spec 범위 제외).
- **(c) 폴백 세션을 `N영업일 이내`로 제한** — 연휴가 길면 화면이 다시 비어 이 spec이 없애려는 문제가 재발한다.
  이슈 #384에서 "제한 없음"으로 확정.
- **(d) 응답에 `frozen`(멈춤) 플래그 추가** — `marketStatus`와 `sourceTradingDate`로 이미 판별 가능해 계약을 넓히지
  않는다. 프론트가 문구를 갈라야 할 필요가 실제로 생기면 그때 별도 이슈로 추가한다.
- **(e) 마지막 값을 Redis·인메모리에 캐시** — 폴백 조회는 세션 1건 + 분봉 1건(가격) / 하루치(캔들)이며 기존 조회와
  같은 인덱스를 탄다. 캐시를 두면 "언제 무효화하는가"가 새 문제로 들어온다. 필요해지면 별도 이슈.

## 테스트 계획

- **단위** (`StockReplayServiceTest`, 고정 `Clock`) — 시각별 판정표의 각 행. 특히
  ① 토요일·일요일·공휴일 → 폴백,
  ② 평일 00:30(세션 행 없음) / 08:50(오늘 READY) → 폴백이고 **오늘 세션의 원본 거래일이 아님**,
  ③ 평일 09:00~09:01 → 가격은 오늘 시가, 캔들은 빈 배열(회귀),
  ④ 평일 09:01·15:31 → 오늘 재생분(회귀),
  ⑤ `OPEN`인데 그 종목만 분봉 없음 → 폴백 없이 `UNAVAILABLE`,
  ⑥ 오늘 세션 `FAILED`·`PREPARING` → 폴백,
  ⑦ 폴백 세션의 원본 거래일에 분봉이 없음 → `UNAVAILABLE`·빈 배열,
  ⑧ 폴백 시세의 `sessionReady=false`·`replaySession=null`.
- **단위** (`LocalForcedOpenStockPriceProviderTest`) — 폴백 시세(`sessionReady=false`)를 강제 OPEN이 덮어쓰지 않는다.
- **슬라이스** (`@DataJpaTest`) — 새 리포지토리 메서드: 오늘 세션 제외, `READY`만 선택, 서비스 날짜 최댓값 1건,
  후보가 없으면 `Optional.empty()`.
- **통합** (Testcontainers, ADR-0003 "핵심 시나리오") — 금요일 장 마감 후 → 토요일 조회(가격·캔들 유지) → 월요일
  08:50(여전히 금요일 재생일) → 월요일 09:01(오늘 재생분으로 전환)까지 한 시나리오로 확인한다. 같은 테스트에서
  토요일 주문이 409 `MARKET_CLOSED`임을 확인한다.
- **통합/슬라이스** (`StockPriceStreamServiceTest` 또는 `StockPriceStreamIntegrationTest`) — 값이 멈춘 동안 매분
  스케줄이 돌아도 `price` 이벤트가 발생하지 않고, 그 시점 `snapshot`에는 멈춘 값이 실린다.
- **회귀** — 기존 테스트 중 "세션 없으면 `UNAVAILABLE`·빈 배열"을 단정하는 케이스는 이 spec이 의도적으로 바꾸는
  동작이므로, 삭제하지 말고 **`OPEN`인 경우로 조건을 옮기거나 폴백 후보가 없는 상태로 고쳐** 원래 검증 의도를
  살린다(`StockReplayServiceTest`·`CandleQueryServiceTest`·`PriceQueryServiceTest`·`MarketDataPipelineIntegrationTest`
  주변을 먼저 훑는다).

## 문서 갱신

- `docs/api-contracts.md` — ① 주식 가격·캔들 절의 장외 동작 서술, ② SSE 절의 "장 마감 후에도 마지막 값을 유지한다"
  문단(자정을 넘겨도 유지된다는 사실을 추가), ③ `costBasis` 대체 문단(주말에는 더 이상 이 경로로 빠지지 않으며,
  이 처리는 폴백 후보가 없을 때를 위해 남는다는 단서).
- `docs/prd.md` §3 "구현 현황" — `QUOTE-HOLD-001~007` 행 추가(근거 칸에 이 spec과 PR 번호).
- `docs/api-routes.md` — 갱신 대상 아님(라우트 무변경).
