# Plan: 코인 시세 표시와 체결 판정의 stale 기준 분리

## 관련 문서

- Spec: `./spec.md`
- 관련 ADR: `ai/adr/0002-architecture.md`(레이어 규칙 — 판정 로직은 service, key 조립·원자적 조회는 PriceStore 컴포넌트)
- 선행 spec: `ai/specs/003-market-data`(MKT-003·MKT-004 원문), `ai/specs/027-crypto-tick-candle-cache`(같은 패턴으로 "원문은 유지, 각주로 대체 사실만 남긴다"는 선례), `ai/specs/028-crypto-card-sse-push`(코인 SSE snapshot 계약)

## 표현 방식 결정 — `PriceStatus`에 `STALE` 추가 (boolean 플래그 방식 기각)

두 방식을 검토했다.

- **안 A (채택): `PriceStatus`에 `STALE`을 추가**해 기존 2값(`AVAILABLE`·`UNAVAILABLE`) enum을 3값으로 넓힌다. `PriceQuoteDto`·`PriceResponse` 구조는 그대로 둔다.
- **안 B (기각): `PriceResponse`에 `stale: boolean` 필드를 추가**하고 `status`는 `AVAILABLE`·`UNAVAILABLE` 2값을 유지한다(stale이어도 `status="AVAILABLE"`, `stale=true`로만 구분).

**안 A를 고른 근거** — 실제 소비자 코드(`SyntheticPriceService`·`PracticePriceSessionService`)를 읽어 보면 `quote.status() == PriceStatus.AVAILABLE`로 "진짜 신선한 값만" 걸러 쓰고, `HoldingValuationService`는 `quote.status() == PriceStatus.UNAVAILABLE`로 "가격 없음만" 걸러 나머지를 available로 취급한다. enum을 3값으로 넓히면:

- `==AVAILABLE`로 검사하는 소비자(교육 서비스 2곳)는 **코드 한 줄도 안 고쳐도** STALE을 자동으로 "AVAILABLE 아님"으로 걸러내 기존처럼 엄격하게 동작한다.
- `==UNAVAILABLE`로 검사하는 소비자(`HoldingValuationService`)는 그대로 두면 STALE을 "UNAVAILABLE 아님"으로 오인해 **의도치 않게 완화**된다 — 이건 이 소비자가 실제로 안고 있던 잠재 결함이라 이 spec에서 명시적으로 고친다(아래 "HoldingValuationService" 절).
- 컨트롤러의 `requireAvailable()`(`quote.status() == UNAVAILABLE`일 때만 예외)도 코드를 고치지 않아도 STALE을 통과시킨다 — 이게 바로 `/price`가 자동으로 완화되는 지점이다.

즉 안 A는 "값을 하나 늘리는 것"만으로 여러 소비자가 **자기가 이미 쓰던 이분법(`==AVAILABLE` vs `==UNAVAILABLE`)에 따라 서로 다른(그리고 각자 원하는) 방향으로 자동 분기**된다. 안 B였다면 `HoldingValuationService`·교육 서비스·SSE snapshot 전부 `stale` 필드를 추가로 읽어 각자 새로 분기 코드를 넣어야 했다 — 변경 파일이 더 많고 "엄격 유지"로 결론 낸 소비자까지 손대야 하므로 conventions.md의 최소 변경 원칙에 어긋난다.

**back-compat 관점**: 안 A는 프론트가 `status`를 정확히 두 값으로 매칭하는 코드가 있다면 깨질 수 있다. 하지만 이 기능의 목적 자체가 프론트에 "지연된 가격"이라는 새 상태를 보여주는 것이므로, 어느 안을 택하든 프론트 변경은 어차피 필요하다 — 안 B의 "필드 추가는 무해하다"는 이점이 실질적으로 없다.

## API 설계

엔드포인트 URL·메서드는 바뀌지 않는다 — 응답 바디의 `status` 값 범위만 넓어진다.

| Method | URL | 응답 변화 | 설명 |
|---|---|---|---|
| GET | /api/instruments/{instrumentId}/price | `status`에 `"STALE"` 값이 추가됨. `STALE`일 때 `price`·`sourceTime`은 non-null(마지막 유효가) | PRICE-STALE-001 |
| GET | /api/cryptos/stream (`snapshot` 이벤트) | `prices[].status`에 `"STALE"` 값이 추가됨 | PRICE-STALE-004 |

## 입력 명세

이번 spec은 새 요청 파라미터·요청 DTO를 추가하지 않는다(응답 상태값 확장만).

## 코드 변경 설계

### `PriceStatus` (market/service/PriceStatus.java)

```java
public enum PriceStatus {
    AVAILABLE,
    STALE,       // 신규 — 연결 유지 + 마지막 수신 틱이 10초 초과. price·sourceTime은 non-null.
    UNAVAILABLE
}
```

### `PriceStore` (market/store/PriceStore.java) — **변경 없음**

`isStale(receivedAt)`·`getConnectionStatus()`·`getLatestPrice(symbol)`은 이미 공개 메서드이고 이 spec이 필요로 하는 조합(연결 여부·최신가 유무·신선도)을 그대로 제공한다. `isPriceAvailable(symbol)`(연결+신선도 동시 판정)도 체결 경로가 그대로 쓰므로 손대지 않는다. 이 클래스에 새 메서드를 추가하지 않는다 — "판정 로직 조합"은 조회 목적(표시 vs 체결)에 따라 달라지는 **service 레이어의 정책**이라 `PriceQueryService`에 둔다(conventions.md "PriceStore는 key 문자열을 조립하는 저장소 컴포넌트", 판정 정책은 소비 목적을 아는 service의 책임).

### `PriceQueryService` (market/service/PriceQueryService.java)

기존 private 메서드 `getCryptoPriceQuote(Instrument)`(단건)·`getCryptoPriceQuotes(List<Instrument>)`(배치)는 지금 **표시와 체결 양쪽에서 공유**되고 있다(`getPriceQuote(Instrument)`가 단건을, `getOrderExecutionPrice(Instrument)`가 단건을, `getPriceQuotes(List)`가 배치를 각각 호출). 이걸 그대로 두고 안쪽 판정만 바꾸면 체결 경로까지 함께 완화되어 PRICE-STALE-003(체결 무변경)을 어긴다. 그래서 이름과 책임을 분리한다.

- **`getCryptoExecutionPriceQuote(Instrument)`** — 기존 `getCryptoPriceQuote(Instrument)`를 이름만 바꾼다. **로직은 한 글자도 바꾸지 않는다**(`priceStore.isPriceAvailable(symbol)` 기반, 지금과 동일하게 `AVAILABLE`·`UNAVAILABLE`만 반환). `getOrderExecutionPrice(Instrument)`에서만 호출한다.
- **`getCryptoDisplayPriceQuote(Instrument)`** — 신규. 표시 경로 전용.
  ```java
  private PriceQuoteDto getCryptoDisplayPriceQuote(Instrument instrument) {
      String symbol = instrument.getSymbol();
      if (priceStore.isPriceAvailable(symbol)) {
          // 연결 유지 + fresh — 기존 AVAILABLE 경로와 완전히 동일한 조회 순서·race 가드
          return priceStore.getLatestPrice(symbol)
              .map(p -> new PriceQuoteDto(p.price(), p.receivedAt(), PriceStatus.AVAILABLE, null))
              .orElseGet(() -> new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null));
      }
      if (priceStore.getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
          return new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null); // 연결 끊김 — 완화 대상 아님
      }
      // 연결은 유지되지만 isPriceAvailable()이 false였다 → 신선도만 초과(stale)였거나, 애초에 시세를 받은 적이 없다.
      return priceStore.getLatestPrice(symbol)
          .map(p -> new PriceQuoteDto(p.price(), p.receivedAt(), PriceStatus.STALE, null))
          .orElseGet(() -> new PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, null)); // 받은 적 없음 — 완화 대상 아님
  }
  ```
  - `isPriceAvailable(symbol)`을 **먼저** 검사해 그 값이 `true`인 기존 성공 경로를 그대로 재사용한다 — 이 부분이 `PriceQueryServiceTest`의 기존 "isPriceAvailable=true" 계열 테스트(예: `getPriceReturnsAvailableQuoteWhenCryptoPriceStoreHasLatestPrice`)를 회귀 없이 통과시키는 이유다.
  - `isPriceAvailable(symbol)`이 `false`일 때만 `getConnectionStatus()`로 "연결 끊김"과 "연결 유지+stale"을 갈라 재조회한다 — Redis 호출이 늘지만(최악의 경우 `isPriceAvailable` 내부 조회 + 이 재조회로 `getLatestPrice`가 최대 2번) 가격 조회는 요청당 1회이므로 성능에 영향이 없다.
- **`getCryptoDisplayPriceQuotes(List<Instrument>)`** — 신규. 기존 배치 `getCryptoPriceQuotes(List<Instrument>)`를 대체한다(그 메서드의 유일한 호출부가 `getPriceQuotes(List)`이고 배치 전용 체결 경로가 없으므로 strict 버전을 별도로 남길 필요가 없다 — 죽은 코드 방지). 심볼별로 `getCryptoDisplayPriceQuote`와 동일한 판정을 반복하되, 연결상태만 루프 밖에서 한 번 조회해 재사용한다(PR #97 리뷰 권장사항과 같은 최적화 유지).
- **호출부 교체**: `getPriceQuote(Instrument)`의 크립토 분기가 `getCryptoDisplayPriceQuote`를 호출하도록 바꾼다. `getPriceQuotes(List<Instrument>)`의 크립토 분기가 `getCryptoDisplayPriceQuotes`를 호출하도록 바꾼다. `getOrderExecutionPrice(Instrument)`는 `getCryptoExecutionPriceQuote`를 계속 호출한다(무변경).
- `getPrice(Long)`·`requireAvailable(PriceQuoteDto)`는 코드 변경이 필요 없다 — `requireAvailable`은 이미 `status == UNAVAILABLE`일 때만 던지므로 `STALE`을 자동으로 통과시킨다.

### `HoldingValuationService` (portfolio/service/HoldingValuationService.java)

`buildValuation`의 조건을 명시적으로 바꾼다.

```java
// 변경 전
if (quote.status() == PriceStatus.UNAVAILABLE) { ... }
// 변경 후 — STALE도 UNAVAILABLE과 동일하게 평가불가 처리 (PRICE-STALE-005, 엄격 유지 확정)
if (quote.status() != PriceStatus.AVAILABLE) { ... }
```

else 분기가 이미 `new HoldingValuationDto(..., PriceStatus.AVAILABLE, ...)`로 상태를 하드코딩해서 만들고 있으므로(즉 `quote.status()`를 그대로 복사하지 않는다), 이 조건만 고치면 `HoldingListItemResponse.priceStatus`는 계속 `AVAILABLE`·`UNAVAILABLE` 2값만 노출한다 — `docs/api-contracts.md`의 `## portfolio` 절은 변경할 필요가 없다.

### `CryptoPriceStreamService`·`SyntheticPriceService`·`PracticePriceSessionService`·`PracticeHoldingObservationService` — **코드 변경 없음**

spec.md PRICE-STALE-004·005의 근거대로, 이 네 서비스는 기존 조건식이 이미 원하는 방향(완화 또는 엄격)으로 자동 분기되므로 손대지 않는다. 테스트만 추가해 그 동작을 명시적으로 고정한다(tasks.md 참조) — "우연히 맞는 동작"이 아니라 "의도했고 테스트로 고정된 동작"으로 만든다.

## 기존 테스트 영향 분석 (필수 — PRICE-STALE-003)

`PriceQueryServiceTest`의 크립토 관련 기존 테스트는 전부 `priceStore.isPriceAvailable("BTC")`만 stub하고 나머지(`getConnectionStatus()`)는 stub하지 않는다. `getCryptoDisplayPriceQuote`가 `isPriceAvailable()`을 **먼저** 검사하는 설계이므로:

- `isPriceAvailable=true` stub 테스트 → 그대로 `AVAILABLE` 분기를 타 회귀 없이 통과한다(코드 변경 없이 지금 로직 재사용).
- `isPriceAvailable=false`만 stub하고 `getConnectionStatus()`를 stub하지 않은 기존 테스트(`getPriceThrowsPriceUnavailableWhenCryptoPriceStoreReportsUnavailable` 등) → Mockito mock의 unstub된 `getConnectionStatus()`는 `null`을 반환하고, `null != FeedConnectionStatus.CONNECTED`는 `true`이므로 "연결 끊김" 분기로 떨어져 기존과 동일하게 `UNAVAILABLE`/409를 반환한다. **동작은 우연히 안 깨지지만, 이 테스트들이 "연결 끊김"과 "연결 유지+stale"을 구분하지 않는 채로 작성돼 있다는 뜻이므로, tasks.md에서 `getConnectionStatus()`를 명시적으로 `DISCONNECTED`로 stub하도록 정리한다** — Mockito null 기본값에 기대는 채로 남기지 않는다.
- `CryptoCandleAndPriceIndependenceTest.candleQueryStillSucceedsWhenPriceStoreIsEmptyOrDisconnected`도 같은 패턴(`isPriceAvailable`만 false로 stub)이라 위와 동일하게 명시적 stub 추가를 검토한다 — 단, 이 테스트는 "이번 spec으로 인해 절대 깨지면 안 되는" 명시적 제약 테스트이므로, stub을 보강하더라도 **기존 단정(assertion)은 절대 바꾸지 않는다.**
- `priceQueryStillSucceedsWhenCryptoCandleProviderFails`는 `isPriceAvailable=true` 경로라 위 첫 번째 케이스와 동일하게 무변경으로 통과해야 한다. 2026-08-13 자동 구현 시도가 이 테스트를 깼던 것은(run 31656007674) 아마 `getPrice()`의 AVAILABLE 성공 경로 자체를 건드렸기 때문으로 추정된다 — 이번 설계는 `isPriceAvailable=true` 분기 로직을 원본 그대로 재사용하므로 같은 실수가 재발하지 않는지 반드시 이 테스트로 확인한다.

## 데이터 모델

변경 없음 — 새 테이블·마이그레이션·Redis 키 없음.

## 문서 동기화

- `docs/api-contracts.md` `### 종목 현재가 조회`: 오류 응답 칸의 "코인: stale·연결 끊김) 409"를 "코인: 연결 끊김·수신 이력 없음만 409, stale은 200 status=STALE"로 정정하고, 성공 응답 예시 옆에 `STALE` 값과 예시를 추가한다.
- `docs/api-contracts.md` `### 코인 SSE 스트림`의 `snapshot` 절에 `STALE` 상태 설명을 추가한다.
- `ai/specs/003-market-data/spec.md` MKT-004 절 하단에 각주 추가 — 원문("주문 가능 상태가 거부로 바뀐다")은 유지하되, 과거 구현이 이 판정을 화면 조회에도 잘못 적용했었고 `032-price-quote-stale-split`이 이를 분리했다는 사실만 남긴다(027이 003을 대체할 때 쓴 것과 같은 각주 패턴).
- `ai/prd.md` §3 "구현 현황"에 새 행 추가(근거: 이 PR 번호). §4 MKT-004 절 본문은 고치지 않는다(spec.md "비즈니스 규칙" 참조 — 문구 자체는 이미 체결 관점으로 좁게 쓰여 있었다).

## 테스트 계획

- **단위 (`PriceQueryServiceTest`)**: `getCryptoDisplayPriceQuote`/`getPrice`/`getPriceQuote` 크립토 분기 — (a) connected+fresh→AVAILABLE(회귀), (b) connected+stale+latestPrice 있음→STALE(신규, price·sourceTime non-null 확인), (c) disconnected→UNAVAILABLE(회귀, `getConnectionStatus()` 명시적으로 DISCONNECTED stub), (d) connected+한 번도 안 받음(latestPrice empty)→UNAVAILABLE(신규), (e) `getPrice(Long)`이 (b)에서 예외를 던지지 않고 200 상당 값을 반환(신규), (f) `getOrderExecutionPrice`는 (b)에서도 여전히 `PRICE_UNAVAILABLE`을 던짐(신규, PRICE-STALE-003 고정). 배치(`getPriceQuotes`)도 동일 5ケース를 최소 1개씩 커버.
- **회귀 (`CryptoCandleAndPriceIndependenceTest`)**: 기존 두 테스트 그대로 통과 확인, 필요 시 mock stub만 보강(단정 불변).
- **단위 (`HoldingValuationServiceTest`)**: stale `PriceQuoteDto`를 넣었을 때 `priceStatus=UNAVAILABLE`, 평가금액·손익 null인 케이스 추가.
- **단위 (`SyntheticPriceServiceTest`·`PracticePriceSessionServiceTest`)**: stale `PriceQuoteDto`를 넣었을 때 여전히 `FALLBACK_START_PRICE`를 쓰는 케이스 추가.
- **단위 (`CryptoPriceStreamServiceTest`)**: `priceQueryService.getPriceQuote(instrument)`가 STALE quote를 반환하도록 stub했을 때 `buildSnapshot()`이 그 값을 그대로(price·sourceTime non-null, status=STALE) 전달하는 케이스 추가.
- **슬라이스 (`InstrumentControllerTest`, `@WebMvcTest`)**: `priceQueryService.getPrice(id)`가 STALE `PriceQuoteDto`를 반환하도록 stub하고 `GET .../price`가 200과 `$.status` == "STALE", `$.price`·`$.sourceTime` non-null인 JSON을 검증.
- **통합**: 없음 — 이 변경은 순수 판정 로직 분기이고 Testcontainers가 필요한 외부 인프라(Redis 원자성 등)를 새로 만들지 않는다. 기존 `MarketDataPipelineIntegrationTest` 등 관련 통합 테스트가 있다면 회귀만 확인한다(신규 통합 테스트 불필요).
