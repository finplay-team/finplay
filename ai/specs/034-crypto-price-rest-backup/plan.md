# Plan: 코인 시세 신선도 보강과 체결 가용성 확대

## 관련 문서

- Spec: `./spec.md`
- 대체 대상: `ai/specs/032-price-quote-stale-split/spec.md` PRICE-STALE-003(체결 경로 불변)
- 선행 spec: `ai/specs/003-market-data/spec.md`(MKT-003·MKT-004)
- 관련 ADR: `ai/adr/0002-architecture.md`(레이어), `ai/adr/0003-testing-strategy.md`(테스트), `ai/adr/0021-continuous-deployment.md`(dev 머지 = 즉시 배포)
- 이슈: #369(본건), #107(폴러 최초 도입), #355(저유동성 조회 차단), #242(웹소켓 채널 실측)

## 두 변경의 관계

| | 무엇을 바꾸나 | 없으면 생기는 일 |
|---|---|---|
| **A** REST 폴링 백업 | 지연 판정이 **발생하는 빈도**를 줄인다 | B만 있으면 오래된 가격 체결이 일상이 된다 |
| **B** 체결 경로 STALE 허용 | 지연이 나도 **매매가 막히지 않게** 한다 | A만 있으면 웹소켓·REST가 동시에 조용한 순간에 여전히 막힌다 |

B가 사용자 문제를 직접 해결하고, A가 B의 부작용(오래된 가격 체결) 노출을 구조적으로 줄인다. **B는 코드 변경이 작고 A는 크다** — 순서는 A(토대) → B(판정) 로 진행하되, B가 이 spec의 사용자 가치 대부분을 담당한다는 점을 리뷰에서 놓치지 않는다.

## A — 핵심 설계: 왜 타임스탬프를 둘로 나누는가

**아무 생각 없이 구현하면 반드시 밟는 함정이다.**

현재 `PriceStore.saveTick`은 과거 틱이 최신을 덮어쓰지 못하게 막고 있다(MKT-003):

```java
if (existingReceivedAt.isPresent() && !receivedAt.isAfter(existingReceivedAt.get())) {
    return;   // 저장된 값보다 이르거나 같으면 무시
}
```

두 경로가 넣는 시각의 **의미가 다르다**:

| 경로 | 넘기는 시각 | 성격 |
|---|---|---|
| 웹소켓 ticker | `content.date + content.time` | 거래소가 준 **체결 시각** (전송 지연만큼 과거) |
| 웹소켓 transaction | `contDtm` | 거래소가 준 **체결 시각** (전송 지연만큼 과거) |
| REST 폴러 | `LocalDateTime.now(clock)` | 우리가 **폴링한 시각** (항상 현재) |

그래서 폴러를 지금 구조 그대로 운영에 켜면:

```
22:30:00.000  REST 폴링 → receivedAt = 22:30:00.000 저장
22:30:00.800  웹소켓 체결 도착 (contDtm = 22:29:59.900, 실제 체결)
              → 22:29:59.900.isAfter(22:30:00.000) == false
              → 이 진짜 체결이 그대로 버려진다
```

REST가 항상 "지금"을 쓰므로 웹소켓의 실제 체결이 거의 매번 탈락하고, 가격이 3초 주기로만 갱신되어 **BTC 같은 활발한 종목의 실시간성이 오히려 나빠진다.**

**해결: 값을 하나 더 둔다.**

| 필드 | 의미 | 누가 쓰나 | 소비처 |
|---|---|---|---|
| `price` | 마지막 체결가 | 웹소켓(체결), REST(값이 다를 때만) | 표시·체결 가격 |
| `receivedAt` | 그 가격의 **체결 시각** | 웹소켓만 | API 응답 `sourceTime`, MKT-003 과거틱 가드 |
| `observedAt` **(신규)** | 그 가격이 최신임을 **마지막으로 확인한 시각** | 웹소켓·REST 둘 다 | `isStale` 판정 |

- MKT-003 과거틱 가드는 `receivedAt`끼리만 비교하므로 **웹소켓 경로가 전혀 영향받지 않는다**(PRICE-REST-003).
- `isStale`은 `observedAt` 기준이 되므로 체결이 없어도 REST 폴링만으로 신선함이 유지된다(PRICE-REST-001).
- `sourceTime`(화면의 "22:24 기준")은 계속 체결 시각이라 의미가 안 바뀐다.

## B — 핵심 설계: 체결 판정을 표시 판정과 일치시킨다

032는 코인 경로를 둘로 나눴다.

```java
// 표시 — 연결 유지 + 수신 이력 있으면 stale이어도 마지막 가격을 STALE로 반환
private PriceQuoteDto getCryptoDisplayPriceQuote(Instrument instrument) { ... }

// 체결 — isPriceAvailable(연결 + 신선도)이 false면 UNAVAILABLE (fail-closed)
private PriceQuoteDto getCryptoExecutionPriceQuote(Instrument instrument) { ... }
```

**B는 체결 판정을 표시 판정과 같게 만든다.** 즉 `getOrderExecutionPrice`의 코인 분기가 표시용 판정을 그대로 쓰도록 바꾸고, `getCryptoExecutionPriceQuote`는 제거한다(규칙을 두 벌로 유지하지 않는다 — PRICE-REST-004).

**이 변경이 자동으로 성립하는 이유**: `requireAvailable`은 `status == UNAVAILABLE`일 때만 예외를 던진다. 표시 판정은 stale에 대해 `STALE`(가격·시각 non-null)을 돌려주므로, 같은 quote가 `requireAvailable`을 그대로 통과한다. **새 분기를 만들 필요가 없고, 판정 메서드 하나를 지우는 방향의 변경이다.**

```java
// 변경 후 — 코인은 표시·체결이 같은 판정을 쓴다
if (instrument.getMarket() == Market.CRYPTO) {
    return new OrderExecutionPriceDto(requireAvailable(getCryptoDisplayPriceQuote(instrument)), null);
}
```

여전히 거부되는 경우는 표시 판정이 `UNAVAILABLE`을 주는 두 경우뿐이다 — **연결 끊김**, **수신 이력 없음**(PRICE-REST-005).

**`PriceStore.isPriceAvailable`의 운명**: 코인 체결 경로가 이 메서드를 더 이상 쓰지 않는다. 다만 `CryptoPriceSnapshotService`·코인 변동 감시 계열이 직접 호출하고 있으므로(032 PRICE-STALE-005) **메서드는 남긴다.** 사용처가 줄었다는 사실만 주석에 반영한다.

## 컴포넌트 설계

### `PriceStore` (`market/store/PriceStore.java`)

Redis 해시 `price:crypto:{symbol}`에 필드 하나 추가. **새 키를 만들지 않는다.**

```
price       : "89812000"
receivedAt  : "2026-08-13T22:24:31.120"   (기존)
observedAt  : "2026-08-13T22:30:04.007"   (신규)
```

| 메서드 | 변경 |
|---|---|
| `saveTick(symbol, price, receivedAt)` | 기존 가드 유지. 통과 시 `observedAt = now(clock)`도 함께 기록. |
| `recordObservation(symbol, price, observedAt)` **(신규)** | REST 폴러 전용. `observedAt`은 **항상** 갱신. `price`는 저장값과 **다를 때만** 갱신하며, `receivedAt`은 건드리지 않는다. |
| `isStale(...)` | 판정 기준을 `observedAt`으로 변경. 임계값 10초 그대로. |
| `getLatestPrice(symbol)` | `observedAt`도 함께 읽어 반환(`CryptoPriceDto` 확장). |
| `isPriceAvailable(symbol)` | 로직 무변경. 코인 체결 경로가 더 이상 호출하지 않는다는 사실만 주석 반영. |

**`observedAt`이 없는 기존 데이터** — 배포 직후 Redis에는 `observedAt`이 없는 해시가 남아 있다. 없으면 `receivedAt`을 관측 시각으로 간주한다(= 배포 전과 동일 동작). 마이그레이션이나 캐시 비우기를 요구하지 않는다.

**이벤트 발행** — `recordObservation`은 **가격이 실제로 바뀌었을 때만** `CryptoPriceUpdatedEvent`를 발행한다. 근거: 소비자가 `LimitOrderTriggerListener`(지정가 체결 후보 조회)와 `CryptoPriceStreamService`(SSE push) 둘인데, **가격이 같으면 두 소비자 모두 결과가 같다** — 같은 값으로 `findPendingLimitOrdersToFill`을 3초마다 다시 도는 것과 SSE로 같은 값을 재전송하는 것은 순수한 낭비다. `saveTick`(웹소켓)의 발행 조건은 **건드리지 않는다.**

### `PriceQueryService` (`market/service/PriceQueryService.java`)

- `getOrderExecutionPrice`의 코인 분기가 `getCryptoDisplayPriceQuote`를 쓰도록 변경.
- `getCryptoExecutionPriceQuote` 제거.
- 주식 분기·튜토리얼 샘플 분기는 무변경.

### `BithumbRestTickerPoller` (`market/feed/BithumbRestTickerPoller.java`)

| 항목 | 현재 | 변경 후 |
|---|---|---|
| 프로필 | `@Profile("!prod & crypto-real")` | `@Profile("prod \| crypto-real")` |
| 주입 대상 | `FakeBithumbFeedClient.emitTick()` | `PriceStore.recordObservation()` **직접** |
| 주기 | 3초 | 그대로 |
| 실패 처리 | 회차 스킵 + 로그 | 그대로 |

**주입 대상을 바꿔야 하는 이유**: `FakeBithumbFeedClient`는 `@Profile("!prod")`라 **운영에는 빈 자체가 없다.** 프로필만 넓히면 운영 기동 시 의존성 해결에 실패한다. 또한 `emitTick`은 내부적으로 `saveTick`을 부르므로 위 타임스탬프 충돌을 그대로 재현한다.

**프로필 표현식**: `crypto-real` 없이 로컬을 띄우면 여전히 폴러가 뜨지 않고 시뮬레이터가 동작한다(기존 로컬 개발 경험 무변경).

### 연결상태 소유권 — 변경 없음

`saveConnectionStatus`는 계속 `BithumbWebSocketFeedClient`만 호출한다. REST 폴러는 연결상태를 쓰지도 읽지도 않는다(spec §남은 알려진 공백).

### 스케줄러 풀

운영 프로필에 `@Scheduled` 하나가 추가되므로 `spring.task.scheduling.pool.size`를 **16 → 17**로 올린다. `application-crypto-real.yml`의 주석이 개수를 13으로 적고 있어 이미 어긋나 있으므로 두 주석을 실제 값으로 함께 맞춘다(PRICE-REST-006).

## API 설계

**새 엔드포인트 없음. 요청·응답 필드 구조 변경 없음.**

`GET /api/instruments/{instrumentId}/price`:

| 상황 | 변경 전 | 변경 후 |
|---|---|---|
| 연결 정상 + 10초 내 체결 | `AVAILABLE` | `AVAILABLE` (무변경) |
| 연결 정상 + 체결 10초 초과 + **REST 폴링 정상** | `STALE` | **`AVAILABLE`** |
| 연결 정상 + 관측도 10초 초과 | `STALE` | `STALE` (무변경) |
| 연결 끊김 / 수신 이력 없음 | 409 | 409 (무변경) |

주문(`POST /api/orders`, `POST /api/orders/limit`):

| 상황 | 변경 전 | 변경 후 |
|---|---|---|
| 연결 정상 + 관측 신선 | 체결 | 체결 (무변경) |
| 연결 정상 + 관측 10초 초과(`STALE`) | 409 `PRICE_UNAVAILABLE` | **체결** (마지막 가격) |
| 연결 끊김 / 수신 이력 없음 | 409 `PRICE_UNAVAILABLE` | 409 (무변경) |

`sourceTime`은 계속 **체결 시각**이다.

## 데이터 모델

**MySQL 변경 없음 — Flyway 마이그레이션 없음.** Redis 해시에 필드 하나(`observedAt`)만 추가한다.

## 테스트 계획

- **단위 — `PriceStoreTest`**
  - `recordObservation`이 `observedAt`만 갱신하고 `receivedAt`·`price`는 그대로 두는 케이스(같은 가격).
  - `recordObservation`이 가격이 다르면 `price`도 갱신하되 `receivedAt`은 그대로 두는 케이스.
  - `observedAt`이 신선하면 `receivedAt`이 아무리 오래됐어도 `isStale`이 false.
  - `observedAt` 필드가 없는 기존 해시에서 `receivedAt`으로 폴백.
  - **`recordObservation` 직후 더 이른 `receivedAt`의 `saveTick`이 정상 반영되는 케이스** — PRICE-REST-003, 가장 깨지기 쉬운 지점.
  - 과거 체결 틱이 최신을 덮어쓰지 않는 기존 케이스 회귀(MKT-003).
  - `recordObservation`이 가격 변경 시에만 이벤트를 발행 / 관측만 갱신 시 미발행.

- **단위 — `PriceQueryServiceTest`**
  - 연결 정상 + 관측 신선 → 표시 `AVAILABLE`, 체결 통과.
  - **연결 정상 + 관측 10초 초과 → 표시 `STALE`, 체결도 예외 없이 마지막 가격 반환** (B의 핵심).
  - 연결 끊김 → 표시·체결 모두 `UNAVAILABLE` / 예외.
  - 수신 이력 없음 → 표시·체결 모두 `UNAVAILABLE` / 예외.
  - **`getOrderExecutionPriceStillThrowsPriceUnavailableWhenCryptoTickIsStale` 대체** — 같은 이름 대신 새 동작을 설명하는 이름으로 바꾸고, 032에서 이 spec으로 대체됐다는 근거를 주석에 남긴다.

- **단위 — `BithumbRestTickerPollerTest`**
  - 기존 `MockRestServiceServer` 구성 유지, 검증 대상을 `PriceStore` 목으로 전환.
  - 실패·타임아웃·비정상 상태코드·항목 누락에서 예외를 던지지 않는 기존 단정 유지.

- **슬라이스 — `BithumbRestTickerPollerConditionalTest`**
  - 프로필 조건 변경 후에도 `bithumb.feed.ticker.enabled=false`로 빈이 생성되지 않는 격리 유지(PRD C-005).

- **통합**
  - `OrderExecutionServiceTest` — stale 코인 시장가 주문이 체결까지 도달하는지(B가 상위 서비스에서 실제로 동작하는지).
  - `NewsCollectionIntegrationTest.schedulingPoolIsLargeEnoughForEveryScheduledTask` — 풀 크기 상향 반영(기존 테스트 재사용).
  - `CryptoCandleAndPriceIndependenceTest` — 캔들-현재가 독립성 계약 회귀 없음(기존 테스트 재사용).
  - 새 Testcontainers 통합 테스트는 추가하지 않는다 — 변경이 Redis 해시 필드 하나와 판정 분기 교체가 전부이고, 위 단위·슬라이스가 판정 로직을 덮는다(ADR-0003).

## 배포 시 확인 사항

`dev` 머지가 곧 배포다(ADR-0021). 머지 전에 확인한다.

1. **로컬 실데이터 재현** — `SPRING_PROFILES_ACTIVE=local,crypto-real ./gradlew bootRun`으로 띄워 (a) 저유동성 코인이 10초를 넘겨도 `AVAILABLE`을 유지하는지, (b) 폴러를 일부러 끈 상태에서 stale이 나도 주문이 체결되는지 둘 다 확인한다. 자동 테스트는 목이라 실제 빗썸 응답 형식까지는 검증하지 못한다.
2. **웹소켓 실시간성** — BTC 등 활발한 종목의 가격이 3초 주기가 아니라 **체결 도착 즉시** 갱신되는지 확인(PRICE-REST-003).
3. **레이트리밋** — 3초 주기 · 전 심볼 묶음 1회 호출이 빗썸 공개 API 정책에 저촉되지 않는지. 저촉되면 주기를 늘리되 10초보다는 반드시 짧게 유지한다.
4. **롤백 경로** — 스키마 변경이 없어 `git revert -m 1 <머지커밋>`으로 깨끗하게 되돌아간다. Redis에 남은 `observedAt` 필드는 구버전 코드가 읽지 않으므로 무해하다.
