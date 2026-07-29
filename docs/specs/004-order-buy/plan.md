# Plan: 시장가 매수 (검증 · 수수료 · 멱등성 · lot 생성)

## 관련 문서

- Spec: `./spec.md`
- 근거: GitHub 이슈 #13
- 선행 spec: `docs/specs/011-order-ledger-schema/`(엔티티 5종·마이그레이션 V10 완료, dev에 merge됨), `docs/specs/003-market-data/`(`PriceQueryService`·`StockPriceProvider` 완료)
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(도메인 간 참조는 service 레이어만), [ADR-0004](../../adr/0004-flyway-migrations.md)(이번 spec은 스키마 변경 없음 — V10 재사용)
- PRD 근거: §4 ORD-001~004·006, §5 API 계약·공통 오류표, §6 데이터 모델(011에서 이미 반영), §7 트랜잭션 경계("시장가 매수: 주문+체결+현금차감+holding+lot 생성 원자 처리")

## 미확정·PRD 불일치 (구현 착수 전 사람 확인 권장)

- **Idempotency-Key 범위 축소**: `spec.md`의 ORD-006 완료조건은 "동일 키 재요청이 체결을 추가하지 않는 테스트"·"다른 본문 409 테스트"까지 요구하지만, 이슈 #13 본문은 "헤더 필수 검증까지만, 재현 방지(dedup)·`IDEMPOTENCY_CONFLICT` 판정은 #22에서 구현"이라고 명시한다. 이 plan은 **이슈 #13 지시를 따라 헤더 존재 검증까지만** 구현하고, `orders.idempotency_key`/`request_hash` 컬럼에는 값을 정직하게 저장해 #22가 그대로 재사용할 수 있게 한다. 재요청 시 기존 응답을 반환하는 동작과 `IDEMPOTENCY_CONFLICT` 판정은 이번 tasks에 포함하지 않는다. **spec.md의 ORD-006 완료조건 중 "동일 키 재요청 무추가 체결"·"다른 본문 409" 테스트는 이번 tasks에서 충족되지 않는다 — spec.md 자체 수정은 이 세션 범위 밖이라 건드리지 않았다.**
- **side=SELL 거부 시 오류 코드 미정**: PRD 공통 오류표에 "매도 미지원"에 대응하는 코드가 없다. 이 plan은 임시로 `VALIDATION_ERROR`(400)를 쓴다 — `orderType`처럼 "요청 자체는 파싱 가능하지만 이 API가 처리하지 않는 값"이라는 점에서 `UNSUPPORTED_ORDER_TYPE`(422)과 성격이 비슷하지만, PRD ORD-001 문구가 이 코드를 명시적으로 "지정가"에만 연결하고 있어 재사용하지 않았다. 사람 확인 후 필요하면 새 오류 코드를 추가한다.

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | /api/orders | `OrderCreateRequest` + Header `Idempotency-Key` | `OrderResponse` (201) | 시장가 매수 즉시 전량 체결. side=SELL은 400 VALIDATION_ERROR로 임시 거부(위 미확정 항목 참조) |

- 요청 예시 (PRD §5): `{"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET","quantity":"10"}`
- 응답은 주문 결과와 체결 결과를 함께 반환한다(PORT-002/003 목록 API는 006에서 별도 구현 — 이번 엔드포인트는 생성 직후 결과만 반환).

## 입력 명세

| 필드 | 필수 | 검증 |
|---|---|---|
| `market` | 필수 | `com.finplay.api.market.domain.Market`(`STOCK`\|`CRYPTO`)로 직접 역직렬화. 그 외 문자열은 Jackson 역직렬화 실패 → `HttpMessageNotReadableException` → 기존 `GlobalExceptionHandler`가 400 `VALIDATION_ERROR`로 매핑(코드 추가 불필요) |
| `instrumentId` | 필수 | `@NotNull`. 서비스에서 `InstrumentRepository.findById` 미존재 시 404 `NOT_FOUND` |
| `side` | 필수 | `com.finplay.api.order.domain.OrderSide`(`BUY`\|`SELL`)로 직접 역직렬화(둘 다 유효한 리터럴이라 파싱은 항상 성공). 서비스에서 `side != BUY`면 400 `VALIDATION_ERROR`(미확정 항목 참조, SELL은 005 범위) |
| `orderType` | 필수 | **String으로 받는다** (`OrderType` enum으로 직접 바인딩하지 않음 — enum에 `MARKET`만 존재해 `"LIMIT"` 같은 유효 리터럴이 JSON 파싱 단계에서 400으로 죽어버려 PRD가 요구하는 422 `UNSUPPORTED_ORDER_TYPE`을 낼 수 없다). `@NotBlank`. 서비스에서 `"MARKET".equals(orderType)`이 아니면 422 `UNSUPPORTED_ORDER_TYPE` |
| `quantity` | 필수 | `BigDecimal`, `@NotNull`. 서비스 검증: 0보다 커야 함(그 외 400 VALIDATION_ERROR), `market==STOCK`이면 정수(소수부 0, 그 외 400), `market==CRYPTO`이면 소수점 8자리 이하(그 외 400) |
| Header `Idempotency-Key` | 필수 | 컨트롤러 `@RequestHeader("Idempotency-Key")`(required, blank 불가). 누락·공백 시 400 `VALIDATION_ERROR`(아래 "GlobalExceptionHandler 보강" 참조). 값 자체는 저장만 하고 중복판정은 하지 않음(#22) |

## 설계 노트 (구현자가 임의로 해석하지 않도록 확정)

### 1. 두 `Market` enum 사이의 다리

`account.domain.Market`과 `market.domain.Instrument`가 쓰는 `market.domain.Market`은 011 plan.md 결정에 따라 서로 다른 타입이다(도메인마다 자체 enum 복제, 공유하지 않음). `OrderCreateRequest.market`은 인스트루먼트 조회·요청-종목 시장 일치 검증에 바로 쓰는 `market.domain.Market`을 재사용한다(이미 `InstrumentController`가 같은 타입을 API 계약 타입으로 쓰고 있어 API 표면에서는 이미 공개된 타입). 계좌 조회 시에만 `com.finplay.api.account.domain.Market.valueOf(request.market().name())`으로 값 기반 변환을 1회 수행한다 — 이 변환 지점을 `OrderService` 한 곳으로 제한한다.

### 2. "다른 시장 계좌·종목 조합" 검증

`instrument.getMarket().name().equals(request.market().name())`이 아니라, `instrument.getMarket() == request.market()`로 직접 비교한다(같은 타입 재사용이므로). 불일치면 400 `VALIDATION_ERROR`.

### 3. 주문 가능 시간 — `PriceQueryService` 확장

기존 `PriceQueryService`(003, 이미 구현됨)는 `getPrice(Instrument)`가 주식·코인 모두 "가격 없음"을 뭉뚱그려 `PRICE_UNAVAILABLE`로 던진다. 그러나 ORD-002는 **주식 장외**는 `MARKET_CLOSED`(409), **코인 시세 무효**는 `PRICE_UNAVAILABLE`(409)로 구분해야 한다. `PriceQueryService`에 다음 메서드를 추가한다(다른 경로로 가격을 읽지 않는다는 spec 제약을 지키기 위해 주문 서비스가 이 메서드만 거쳐야 한다):

```java
// PriceQueryService
public void assertOrderable(Instrument instrument) {
    if (instrument.getMarket() == Market.STOCK
        && stockPriceProvider.getMarketStatus() == StockMarketStatus.CLOSED) {
        throw new BusinessException(ErrorCode.MARKET_CLOSED);
    }
}
```

`OrderService`는 `priceQueryService.assertOrderable(instrument)` → `priceQueryService.getPrice(instrument)` 순서로 호출한다. 주식이 `OPEN`인데도 특정 종목의 분봉이 없는 경우(데이터 손상 등)는 `assertOrderable`을 통과한 뒤 `getPrice`가 `PRICE_UNAVAILABLE`을 던져 자연히 구분된다. 코인은 `assertOrderable`이 항상 통과하고 `getPrice`의 stale 판정이 `PRICE_UNAVAILABLE` 여부를 결정한다.

`stockPriceProvider`는 `PriceQueryService`의 기존 private 필드를 그대로 쓴다 — 새 의존성 주입 없음.

### 4. 계좌 조회

`AccountRepository`에 조회 메서드가 없다(011 스코프 제외). 추가한다:

```java
Optional<Account> findByUserIdAndMarket(Long userId, Market market);
```

`AccountService`(기존 `createAccountsFor`만 있음)에 다음을 추가한다:

```java
@Transactional(readOnly = true)
public Account getAccountFor(Long userId, Market market) {
    return accountRepository.findByUserIdAndMarket(userId, market)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
}
```

가입 시 두 계좌가 항상 생성되므로(002) 정상 흐름에서는 발생하지 않는 방어적 분기다.

### 5. 금액 계산 — 반올림 규칙 확정

- `amount`(거래금액) = `price.multiply(quantity)`를 **원 단위로 내림**한다: `amount.setScale(0, RoundingMode.FLOOR)` → `long`. PRD는 수수료의 원 미만 내림만 명시하지만, `trades.amount` 컬럼이 `BIGINT`(원 단위)이므로 저장 전 반드시 정수화해야 한다 — 이 spec에서 내림으로 확정한다(반올림·올림이 아님. 수수료 내림과 방향 일치, double 없이 `BigDecimal`+`RoundingMode.FLOOR`만 사용).
- `fee`(수수료) = `amount(BigDecimal, 내림 전 아님 — 위에서 내림한 amount의 BigDecimal 표현) × feeRate`, 마찬가지로 `setScale(0, RoundingMode.FLOOR)` → `long`. `feeRate`는 `market==STOCK`이면 `new BigDecimal("0.00015")`, `market==CRYPTO`면 `new BigDecimal("0.0005")` — `private static final BigDecimal` 상수로 선언(컨벤션: 매직 넘버 금지).
- `cashRequired = amountLong + feeLong`(원 단위 `long` 덧셈, 오버플로 우려 없는 규모).
- 코인 최소주문금액 검증은 **내림 전** `price.multiply(quantity)`(BigDecimal)와 `instrument.getMinOrderAmount()`를 비교한다 — 내림으로 인해 4,999.9원이 4,999원이 되어 거부되는 경계 오차를 피한다. `market == CRYPTO`일 때만 적용(PRD ORD-003, 주식은 최소금액 규칙 없음).

### 6. 엔티티 상태 변경 메서드 추가 (setter 금지 컨벤션 준수)

- `Account`: `deductCash(long amount)` 추가 — `cashBalance -= amount`. 호출 전 서비스가 이미 충분성 검증을 마치므로 방어적 `IllegalStateException`만 둔다(음수 잔고 방지 목적, 정상 흐름에서 도달하지 않음).
- `Holding`: `applyBuy(BigDecimal quantity, BigDecimal price, LocalDateTime now)` 추가 — 가중평균 단가 갱신:
  `newAveragePrice = (oldQuantity * oldAveragePrice + quantity * price) / (oldQuantity + quantity)` (최초 매수는 `oldQuantity=0`이므로 결과가 `price`와 같음), `quantity += quantity`, `isActive = true`, `updatedAt = now`.
- `HoldingLot`·`Trade`·`Order`는 기존 정적 팩토리만으로 충분(불변 원장 — 상태 변경 메서드 불필요).

### 7. holding 조회/생성

`HoldingRepository`에 조회 메서드가 없다(011 스코프 제외). 추가한다: `Optional<Holding> findByAccountIdAndInstrumentId(Long accountId, Long instrumentId)`. 없으면 `Holding.create(account, instrument, now)`로 생성 후 `applyBuy(...)` 호출(최초 매수도 "생성 후 0으로 채우고 buy 적용"의 동일 경로를 타 특별 분기 불필요).

### 8. requestHash 계산

`orders.request_hash`(`CHAR(64)`, NOT NULL)를 채우기 위해 `market:instrumentId:side:orderType:quantity` 형식 문자열을 SHA-256 hex로 해시한다. 이번 spec은 이 값을 **저장만** 하고 조회·비교에 쓰지 않는다(#22 책임). `MessageDigest`(JDK 표준)만 사용 — 새 의존성 추가 없음(C-002).

### 9. GlobalExceptionHandler 보강

`@RequestHeader("Idempotency-Key")`(required 기본값 true) 누락 시 Spring이 던지는 `MissingRequestHeaderException`이 현재 `GlobalExceptionHandler`의 어느 핸들러에도 잡히지 않아(현재 목록: `HttpMessageNotReadableException`·`MissingServletRequestParameterException`·`ConstraintViolationException`·`MethodArgumentTypeMismatchException`) 500으로 새 나간다. `MissingRequestHeaderException`을 기존 400 처리 핸들러의 `@ExceptionHandler` 목록에 추가한다(`common/GlobalExceptionHandler.java` — order 도메인 파일 아님, 공통 파일 수정).

## 패키지 구성

| 구성 요소 | 위치 | 역할 |
|---|---|---|
| `OrderCreateRequest` | `order.dto.request` | 요청 DTO (market, instrumentId, side, orderType(String), quantity) |
| `OrderResponse` | `order.dto.response` | 주문+체결 결과 응답 DTO. 정적 팩토리 `of(Order, Trade)` |
| `OrderController` | `order.controller` | `POST /api/orders` — `@AuthenticationPrincipal AuthenticatedUser`, `@RequestHeader("Idempotency-Key")`, `@Valid @RequestBody` |
| `OrderService` | `order.service` | 검증→가격조회→체결→계좌·보유·lot 갱신을 하나의 `@Transactional`로 처리. `UserQueryService`(auth)·`AccountService`(account)·`PriceQueryService`(market)·`InstrumentRepository`(market)·`HoldingRepository`(portfolio) 등 타 도메인 서비스/리포지토리를 조합 |
| `PriceQueryService.assertOrderable(Instrument)` | `market.service`(기존 파일 수정) | 위 설계 노트 3 |
| `AccountRepository.findByUserIdAndMarket` / `AccountService.getAccountFor` | `account.repository`/`account.service`(기존 파일 수정) | 위 설계 노트 4 |
| `Account.deductCash` | `account.domain`(기존 파일 수정) | 위 설계 노트 6 |
| `HoldingRepository.findByAccountIdAndInstrumentId` | `portfolio.repository`(기존 파일 수정) | 위 설계 노트 7 |
| `Holding.applyBuy` | `portfolio.domain`(기존 파일 수정) | 위 설계 노트 6 |
| `GlobalExceptionHandler`(기존 파일 수정) | `common` | 위 설계 노트 9 |

`OrderService`가 `HoldingRepository`(portfolio 도메인)를 직접 주입하는 것은 ADR-0002상 "다른 도메인의 repository를 직접 주입하지 않는다"에 저촉될 수 있다. 그러나 011 plan.md가 이미 "004/005의 서비스 로직이 Holding/HoldingLot을 갱신하려면 order 도메인 서비스가 portfolio 도메인 서비스를 같은 트랜잭션 안에서 호출해야 한다"고 명시했으므로, 이 spec에서는 **portfolio 도메인에 최소 서비스 `HoldingService`(또는 `PortfolioBuyService`)를 새로 만들어 `OrderService`가 이를 통해서만 `Holding`/`HoldingLot`을 갱신**한다 — repository 직접 주입 금지 원칙을 지킨다. `TradeAllocationRepository`는 이번 spec 범위(매도 전용, 005)라 사용하지 않는다.

- `PortfolioBuyService`(`portfolio.service`, 신규): 파라미터로 `Account`·`Instrument`·`Trade`(매수 체결)·수량·단가·`LocalDateTime now`를 받아 `Holding` 조회/생성 → `applyBuy` → `HoldingLot.create` 저장까지 한 메서드(`applyBuyTrade(...)`)에서 처리하고 `@Transactional`은 걸지 않는다(트랜잭션 경계는 `OrderService`가 소유 — PRD §7 "주문+체결+현금차감+holding+lot 생성 원자 처리"를 하나의 트랜잭션으로 묶기 위해 전파는 `REQUIRED` 기본값에 맡긴다).

## 데이터 모델

스키마 변경 없음 — `docs/specs/011-order-ledger-schema/`의 V10 마이그레이션·엔티티 5종을 그대로 사용한다. 이번 spec에서 신규 Repository 메서드만 추가한다(위 설계 노트 4·7).

## 테스트 계획

- 단위(Mockito, `OrderServiceTest`):
  - BUY 성공 시 계산값 검증 — 주식 0.015%·코인 0.05% 수수료, 원 미만 내림, 현금차감액=거래금액+수수료(경계값 포함: 나눠떨어지지 않는 수수료).
  - `side=SELL` → 400 VALIDATION_ERROR(위 미확정 항목 반영), `orderType != "MARKET"` → 422 UNSUPPORTED_ORDER_TYPE.
  - 수량 형식 위반(주식 소수, 코인 8자리 초과, 0 이하) → 400 VALIDATION_ERROR.
  - 코인 주문금액 5,000원 미만 → 400 VALIDATION_ERROR.
  - 요청 시장≠종목 시장 → 400 VALIDATION_ERROR.
  - 주식 장외(`assertOrderable`이 MARKET_CLOSED 던짐) → 409 MARKET_CLOSED. 코인/주식 가격 무효(`getPrice`가 PRICE_UNAVAILABLE 던짐) → 409 PRICE_UNAVAILABLE.
  - 현금 부족 → 409 INSUFFICIENT_CASH.
  - 위 실패 케이스 전부 `orderRepository.save`/`tradeRepository.save`/`accountRepository`/`holdingRepository` 등 저장 계열 mock이 **한 번도 호출되지 않음**을 검증(spec.md "검증 실패 시 흔적 없음").
- 단위(`PortfolioBuyServiceTest`): 신규 보유 생성(0→최초수량, 평균단가=체결가), 기존 보유에 추가 매수 시 가중평균 재계산, lot 1건 생성(최초수량=잔여수량=체결수량).
- 슬라이스 `@WebMvcTest`(`OrderControllerTest`): 요청 검증(계약), `Idempotency-Key` 누락 시 400, 성공 시 201과 응답 필드(`jsonPath`)까지 검증. `OrderService`는 mock.
- 슬라이스 `@DataJpaTest`: 011에서 이미 `OrderLedgerSchemaTest`로 커버됨 — 이번 spec은 신규 리포지토리 메서드(`findByUserIdAndMarket`, `findByAccountIdAndInstrumentId`)만 추가 슬라이스 검증.
- 통합(Testcontainers, `OrderBuyIntegrationTest`, 핵심 시나리오): 주식 매수 성공 시 주문·체결·현금차감·holding(신규)·lot이 한 트랜잭션에 저장되는지, 실패(현금부족) 시 4개 테이블 모두 흔적 없는지, 같은 계좌에 동일 종목 재매수 시 평균단가 재계산과 lot 2건 생성.

## 문서 갱신

`OrderController` 추가로 `docs/api-routes.md`·`docs/api-contracts.md`를 이번 tasks 마지막 항목에서 같은 커밋으로 갱신한다(동기화 모드, planner 재투입 또는 /feature 마무리 단계).
