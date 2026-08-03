# Plan: 캔들 조회 기간 확장 — 일봉/주봉/월봉 (1d · 1w · 1M)

## 관련 문서

- Spec: `./spec.md`
- 선행 spec: `../003-market-data/spec.md`, `../003-market-data/plan.md`
- PRD: `docs/prd.md` MKT-009 (선행 MKT-002·MKT-008, 정책 C-005·C-006)
- 관련 ADR
  - **ADR-0002 (레이어드 아키텍처)** — `controller → service → repository`. 집계 로직은 service 계층에 두고 controller·repository에 넣지 않는다.
  - **ADR-0003 (테스트 전략)** — 집계 로직은 단위, 신규 쿼리는 `@DataJpaTest`, API 계약은 `@WebMvcTest`, 핵심 시나리오는 Testcontainers 통합.
  - **ADR-0004 (Flyway)** — **이번 작업은 스키마 변경이 없다.** 신규 마이그레이션을 만들지 않고 기존 `V8__create_stock_candles.sql`도 수정하지 않는다.
- 코드 컨벤션: `docs/conventions.md`(DTO 규칙, 레이어 규칙, API 규칙, 예외 처리)
- **문서 동기화 상태**: 아래 API 설계·입력 명세는 `docs/api-contracts.md`·`docs/api-routes.md`에 반영됐다(2026-08-03, 이슈 #143 동기화).

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | /api/instruments/{instrumentId}/candles?interval=1m\|1d\|1w\|1M&from=&to= | 쿼리 | `CandleResponse[]` | 기존 엔드포인트. **URL·응답 스키마 변경 없음, `interval` 허용값만 확장**. 주식은 `stock_candles` 분봉 집계, 코인은 빗썸 일/주/월봉 위임 |

- 신규 엔드포인트는 없다. `interval`별 URL을 나누지 않는다(`/candles/daily` 같은 경로 추가 금지).
- 응답 예: `[{"sourceTime":"2026-07-20T00:00:00","open":71000,"high":72400,"low":70100,"close":72000,"volume":4821330}, ...]` (시각 오름차순)

## 입력 명세

| 필드 | 필수 | 검증 |
|---|---|---|
| `instrumentId` (path) | 필수 | 존재하지 않으면 404 `NOT_FOUND` (기존과 동일, `interval` 검증 **이후**에 조회) |
| `interval` (query) | 필수 | `1m`·`1d`·`1w`·`1M` 중 하나. **대소문자 구분**(`1M`=월봉, `1m`=분봉). 정규화·트림하지 않는다. 그 외 값·누락·빈 문자열은 400 `VALIDATION_ERROR` |
| `from` (query) | 선택 | ISO-8601 `LocalDateTime`(`2026-07-27T09:00:00`). 파싱 실패는 기존 `@DateTimeFormat` 경로대로 400. `1m`은 **시각 성분만**, `1d`·`1w`·`1M`은 **날짜 성분만** 사용 |
| `to` (query) | 선택 | 위와 동일. `from > to`면 400 `VALIDATION_ERROR` — 비교 기준은 `1m` 주식=시각, `1d`·`1w`·`1M` 주식=날짜, 코인=전체 `LocalDateTime`(기존 코인 규칙 유지) |

반환 개수 상한: 모든 조합에서 **200개**. 초과 시 `to`(생략 시 공개 상한/현재) 기준 최신 200개.

## 데이터 모델

**신규 테이블 없음. 신규 컬럼 없음. 신규 마이그레이션 없음.**

기존 `stock_candles`를 읽기만 한다.

```
stock_candles (V8)
  PK id
  UNIQUE uk_stock_candles_instrument_date_time (instrument_id, trading_date, candle_time)
  open/high/low/close DECIMAL(18,4), volume BIGINT
```

- 신규 범위 조회는 `(instrument_id, trading_date, candle_time)` UNIQUE 인덱스의 선두 컬럼을 그대로 타므로 **인덱스 추가가 필요 없다.**
- 집계 결과는 기존 `StockCandleDto`(`tradingDate`·`candleTime`·`open`·`high`·`low`·`close`·`volume`)를 재사용한다 — 버킷을 `tradingDate = 버킷 시작일`, `candleTime = 00:00`으로 표현하면 기존 `CandleResponse.from(StockCandleDto)`가 `sourceTime = 버킷시작일T00:00:00`을 그대로 만들어 준다. **DTO를 새로 만들지 않는다.**
- `volume` 합계는 `long`이다 — 종목 하나의 월간 거래량 규모에서 오버플로가 발생하지 않는다.

## 구성요소 설계

실측한 현재 코드 기준이다. 아래 클래스명은 모두 실존한다(신규 표시된 것 제외).

### 1) `CandleInterval` (기존, `market/service`) — 확장

```
ONE_MINUTE("1m"), ONE_DAY("1d"), ONE_WEEK("1w"), ONE_MONTH("1M")
```

- `from(String)`의 매칭은 지금도 `equals`라 대소문자를 구분한다 — **그대로 둔다**(`equalsIgnoreCase`로 바꾸면 `1M`/`1m`이 충돌한다). 이 이유를 코드 주석으로 남긴다.
- 오류 메시지를 `"지원하지 않는 캔들 간격입니다. interval=1m만 지원합니다."` → 4개 값을 나열하는 문구로 갱신한다.
- 집계 여부 판정 도우미(`isAggregated()` 등)를 열거형에 두어 서비스의 `if` 나열을 막는다.

### 2) `CandleQueryService` (기존) — 반환값을 버리지 않는다

현재 코드는 `CandleInterval.from(interval)`의 반환값을 의도적으로 버리고 검증 용도로만 호출한다. 이제 파싱한 값을 아래로 전달한다.

- `from > to` 판정 분기를 정리한다.
  - 코인: 기존대로 `LocalDateTime` 전체 비교.
  - 주식 `1m`: 기존대로 `toLocalTime()` 비교 (PR #87 확정 계약, 회귀 금지).
  - 주식 `1d`·`1w`·`1M`: `toLocalDate()` 비교.
- 시장 분기(`Market.CRYPTO` 여부)는 기존 구조를 유지하고 `CandleInterval`만 함께 넘긴다.

### 3) `StockPriceProvider` (기존 인터페이스) — 시그니처 확장

```
List<StockCandleDto> getCandles(Long instrumentId, CandleInterval interval, LocalDateTime from, LocalDateTime to);
```

구현체 2개를 함께 고친다.

- `KisHistoricalReplayPriceProvider` — `StockReplayService`로 위임.
- `LocalForcedOpenStockPriceProvider` (`local` 프로필 `@Primary` 데코레이터) — 캔들은 그대로 delegate에 넘기므로 시그니처만 따라간다.

### 4) `StockCandleAggregator` (**신규**, `market/service`) — 순수 집계

- 입력: 공개된 분봉 목록(`List<StockCandleDto>`, `(tradingDate, candleTime)` 오름차순) + `CandleInterval`
- 출력: 버킷 목록(`List<StockCandleDto>`, 시작일 오름차순)
- 버킷 키
  - `1d` → `tradingDate`
  - `1w` → `tradingDate.with(DayOfWeek.MONDAY)` (ISO-8601 주 시작)
  - `1M` → `tradingDate.withDayOfMonth(1)`
- 버킷 값: `open`=최초 분봉 open, `high`=max, `low`=min, `close`=최종 분봉 close, `volume`=합
- 출력 원소는 `new StockCandleDto(버킷시작일, LocalTime.MIDNIGHT, ...)`
- **Spring 빈 의존이 없는 순수 클래스**로 만든다(시계·리포지토리 주입 금지) — 단위 테스트에서 그대로 검증 가능해야 한다.
- `1m`은 이 클래스를 거치지 않는다(기존 경로 유지).

### 5) `StockReplayService` (기존) — 공개 상한 + 집계 조회

- 기존 `getRevealedCandles(instrumentId, from, to)`는 **손대지 않는다**(1분봉 경로, 회귀 방지).
- 신규 메서드: `getRevealedAggregatedCandles(Long instrumentId, CandleInterval interval, LocalDate fromDate, LocalDate toDate)`

절차(확정):

1. `now = LocalDateTime.now(clock)`, `findReadySession(now.toLocalDate())`. 비어 있으면 `List.of()`.
2. `재생거래일 = session.getSourceTradingDate()`, `cutoff = resolveRevealCutoff(now.toLocalTime())` (기존 private 규칙 재사용).
3. 조회 범위 확정
   - `rangeEnd = min(toDate ?: 재생거래일, 재생거래일)` — 재생거래일을 절대 넘지 않는다(방어 규칙).
   - `rangeStart = fromDate ?: lookbackFloor(interval, rangeEnd)`
     - `lookbackFloor`: `1d` → `rangeEnd.minusDays(400)`, `1w` → `rangeEnd.minusWeeks(200)`, `1M` → `rangeEnd.minusMonths(200)`
     - 200개 버킷을 채우고도 남는 여유이면서 무제한 스캔을 막는 상한이다. 이 범위 안에 200개가 없으면 있는 만큼만 반환한다.
   - `rangeStart > rangeEnd`면 `List.of()`.
4. **분봉 조회는 2회로 나눈다** — 미공개 분봉을 애초에 메모리에 올리지 않기 위해서다(한 번에 읽고 나중에 걸러내면 필터를 빠뜨렸을 때 그대로 유출된다).
   - (a) 과거 거래일: `pastEnd = min(rangeEnd, 재생거래일 − 1일)`. `rangeStart <= pastEnd`이면
     `findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(instrumentId, rangeStart, pastEnd)` (**신규 쿼리**)
   - (b) 재생거래일: `rangeStart <= 재생거래일 <= rangeEnd` **이고** `cutoff`가 존재하면
     `findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(instrumentId, 재생거래일, LocalTime.MIN, cutoff)` (**기존 쿼리 재사용**)
5. (a)+(b)를 이어 붙여 `StockCandleAggregator`에 넘긴다.
6. 결과가 200개를 넘으면 **뒤에서 200개**(최신)만 남긴다.

### 6) `StockCandleRepository` (기존) — 쿼리 1개 추가

```java
List<StockCandle> findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
    Long instrumentId, LocalDate from, LocalDate to);
```

- 파생 쿼리로 충분하다(QueryDSL 불필요 — `docs/conventions.md` QueryDSL 사용 기준).
- 기존 6개 메서드는 그대로 둔다.

### 7) `CryptoCandleProvider` (기존 인터페이스) — 시그니처 확장

```
List<CryptoCandleDto> getCandles(String symbol, CandleInterval interval, LocalDateTime from, LocalDateTime to);
```

### 8) `BithumbRestCandleProvider` (기존) — 엔드포인트·count 분기

- 엔드포인트 상수를 `interval`별로 나눈다.

| interval | URL |
|---|---|
| `1m` | `https://api.bithumb.com/v1/candles/minutes/1` (기존, 변경 없음) |
| `1d` | `https://api.bithumb.com/v1/candles/days` |
| `1w` | `https://api.bithumb.com/v1/candles/weeks` |
| `1M` | `https://api.bithumb.com/v1/candles/months` |

- 쿼리 파라미터는 4개 모두 동일하다: `market`(=`KRW-{symbol}`), `count`(≤200), `to`(선택, **UTC** ISO-8601 — 기존 `resolveToParam`이 KST→UTC 변환을 이미 한다. 그대로 재사용).
  - `days`의 선택 파라미터 `convertingPriceUnit`은 **사용하지 않는다**(원화 마켓만 다루므로 환산이 불필요하고, 응답에 `converted_trade_price`가 붙어 계약이 넓어진다).
- `count` 산출(기존 `resolveCount` 일반화): `from`이 없으면 200. 있으면 `range = to ?: now(clock)` 기준으로

| interval | 단위 | 계산 |
|---|---|---|
| `1m` | 분 | `ChronoUnit.MINUTES.between(from, rangeEnd) + 1` (기존) |
| `1d` | 일 | `ChronoUnit.DAYS.between(from.toLocalDate(), rangeEnd.toLocalDate()) + 1` |
| `1w` | 주 | 양끝을 각각 그 주 월요일로 정렬한 뒤 `ChronoUnit.WEEKS.between(...) + 1` |
| `1M` | 월 | 양끝을 각각 그 달 1일로 정렬한 뒤 `ChronoUnit.MONTHS.between(...) + 1` |

  결과는 `max(1, ...)` 후 `min(200, ...)`으로 캡한다(기존 로직과 동일).
- 응답 파싱: 기존 `BithumbCandleItem` 레코드를 **그대로 재사용한다.** `@JsonIgnoreProperties(ignoreUnknown = true)`가 붙어 있어 일/주/월봉에만 있는 `prev_closing_price`·`change_price`·`change_rate`·`first_day_of_period` 등은 자동으로 무시된다. `unit` 필드는 일/주/월봉 응답에 없어 `null`이 되지만 코드가 쓰지 않으므로 무해하다 — 새 레코드를 만들지 않는다.
- 정렬 반전(`Collections.reverse`)·필드 매핑(`trade_price`→`close`, `candle_acc_trade_volume`→`volume`)·필수 필드 null 검사·타임아웃·`providerError()` 502 경로는 전부 기존 그대로 공유한다.
- `sourceTime`은 빗썸 `candle_date_time_kst`를 **파싱만 하고 보정하지 않는다.** 버킷 경계 재계산 금지(spec의 "빗썸 경계를 그대로" 규칙).

### 9) `FakeCryptoCandleProvider` (기존, `!prod & !crypto-real`) — interval별 시드

- 내부 맵을 `Map<String, List<CryptoCandleDto>>` → `Map<CandleInterval, Map<String, List<CryptoCandleDto>>>`(또는 키를 `symbol + interval`로) 확장한다.
- 테스트용 `setCandles(symbol, candles)`는 **`1m` 시드로 남겨 기존 테스트를 깨지 않고**, `setCandles(symbol, interval, candles)` 오버로드를 추가한다.
- `simulateFailure()`·`reset()`은 그대로.

### 영향 받는 기존 테스트 (회귀 확인 대상)

`CandleQueryServiceTest`, `CandleQueryServiceIntegrationTest`, `CandleIntervalTest`, `BithumbRestCandleProviderTest`, `FakeCryptoCandleProviderTest`, `StockReplayServiceTest`, `StockCandleRepositoryTest`, `InstrumentControllerTest`, `LocalForcedOpenStockPriceProviderTest`, `CryptoCandleAndPriceIndependenceTest`, `MarketDataPipelineIntegrationTest`

## 테스트 계획

- **단위**
  - `StockCandleAggregator` — 1d/1w/1M 버킷 키, 여러 거래일이 한 주·한 달로 묶임, 주 시작이 월요일(일요일·토요일 아님), 월 경계(말일→다음달 1일), OHLCV 산출(최초 open·최대 high·최소 low·최종 close·합계 volume), 분봉 0개면 버킷 미생성, 거래일 없는 주·월이 결과에 없음, `sourceTime`이 버킷 시작일 `T00:00`.
  - `StockReplayService.getRevealedAggregatedCandles` — `READY` 세션 없으면 빈 목록, 09:01 이전이면 재생거래일 버킷 미생성(과거 거래일만), 09:01 이후면 컷오프까지의 분봉만 진행 중 버킷에 반영, `재생거래일` 이후 `trading_date`가 결과에 없음, 200개 캡(최신 쪽 유지), lookback floor 적용.
  - `CandleInterval` — 4값 허용, `1M`≠`1m`, `1D`·`1W`·`5m`·빈 문자열·`null` 거부 시 `VALIDATION_ERROR`.
  - `CandleQueryService` — interval별 `from > to` 판정 기준(주식 1m=시각, 주식 집계=날짜, 코인=전체), 시장 분기, `interval` 검증이 종목 조회보다 먼저.
  - `BithumbRestCandleProvider` — `MockRestServiceServer`로 interval별 호출 URL(`/days`·`/weeks`·`/months`) 검증, `count` 단위별 계산·200 캡, `to`의 KST→UTC 변환, 내림차순→오름차순 반전, 일/주/월봉 전용 필드가 섞인 JSON 파싱 성공, `trade_price`→`close`·`candle_acc_trade_volume`→`volume` 매핑, 타임아웃·에러 상태·파싱 불가 시 502(빈 배열 아님), MySQL·Redis 미기록.
- **슬라이스**
  - `@DataJpaTest` — `findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc`의 경계 포함 여부·정렬(거래일 asc → 분봉시각 asc)·다른 종목 미포함.
  - `@WebMvcTest`(`InstrumentControllerTest`) — `interval=1d`·`1w`·`1M` 200, 잘못된 `interval` 400, `from > to` 400, 없는 종목 404, 코인 실패 502, 미인증 401.
- **통합** (Testcontainers)
  - 서로 다른 3개 이상 거래일(같은 주·다른 주·다른 달이 섞이도록) 분봉과 `READY` 재생세션을 시드하고 `1d`·`1w`·`1M` 응답의 개수·`sourceTime`·OHLCV를 검증한다.
  - 같은 테스트에서 `interval=1m` 응답이 기존 계약 그대로임을 확인한다(회귀).
  - 코인은 `FakeCryptoCandleProvider`로 interval별 시드 후 200 응답을 확인한다.
- **외부 스모크(자동 테스트와 구분 보고 — C-005)**
  - `SPRING_PROFILES_ACTIVE=crypto-real ./gradlew bootRun` 후 `interval=1d`·`1w`·`1M`을 실제로 호출해 빗썸 `candle_date_time_kst`의 **실제 버킷 경계값**을 관측하고, 관측한 그대로 `docs/api-contracts.md`에 기록한다. 추측으로 적지 않는다.
