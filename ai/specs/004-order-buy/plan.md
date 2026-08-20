# Plan: 시장가 매수 (검증 · 수수료 · 멱등성 · lot 생성)

## 관련 문서

- Spec: `./spec.md`
- 근거: GitHub 이슈 #13
- 선행 spec: `ai/specs/011-order-ledger-schema/`(엔티티 5종·마이그레이션 V10 완료, dev에 merge됨), `ai/specs/003-market-data/`(`PriceQueryService`·`StockPriceProvider` 완료)
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

스키마 변경 없음 — `ai/specs/011-order-ledger-schema/`의 V10 마이그레이션·엔티티 5종을 그대로 사용한다. 이번 spec에서 신규 Repository 메서드만 추가한다(위 설계 노트 4·7).

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

`OrderController` 추가로 `ai/api-routes.md`·`docs/api-contracts.md`를 이번 tasks 마지막 항목에서 같은 커밋으로 갱신한다(동기화 모드, planner 재투입 또는 /feature 마무리 단계).

---

## 이슈 #22 — ORD-006 멱등성 재요청 응답 재현

### 관련 문서

- 근거: GitHub 이슈 #22 (본문 요약: 두 번째 요청에 최초 응답을 그대로 반환, 같은 키·다른 본문은 409, 동시 경합으로 유니크 제약에 걸려도 409, 새 마이그레이션 금지, 매도(#41)까지 포함)
- spec.md ORD-006, 위 "미확정·PRD 불일치" 절의 "Idempotency-Key 범위 축소" 항목(이번 이슈로 해소)
- 선행 구현: 이슈 #13(`OrderService.createOrder`, `Order.idempotencyKey`/`requestHash` 저장만), 이슈 #12(011, `V10__create_order_ledger_tables.sql`의 `uk_orders_user_idempotency UNIQUE (user_id, idempotency_key)` — 이미 존재, 재사용), 이슈 #41(SELL 병합됨, `OrderResponse.of`가 매도 응답도 이미 조립)
- 관련 ADR: ADR-0002(도메인 서비스 분리·self-invocation 회피를 위한 클래스 분리 근거), ADR-0004(신규 마이그레이션 없음 확인)
- 참고 기존 패턴: `AuthService.changeNickname`/`confirmEmailChange`(`DataIntegrityViolationException` catch 후 즉시 다른 예외로 변환) — 이번 이슈가 이 패턴을 그대로 재사용하지 않는 이유는 아래 "왜 기존 패턴을 재사용하지 않는가" 참조.

### 새 마이그레이션 없음 확인

`src/main/resources/db/migration/V10__create_order_ledger_tables.sql`에 이미 `CONSTRAINT uk_orders_user_idempotency UNIQUE (user_id, idempotency_key)`가 있다. 이번 spec은 이 제약을 그대로 재사용하고 신규 컬럼·마이그레이션을 추가하지 않는다(ADR-0004, 이슈 #22 명시 제외범위).

### 아키텍처 결정 — "애플리케이션 선제조회"와 "유니크 제약 위반 캐치"를 함께 쓴다 (양자택일 아님)

두 메커니즘을 순서대로 배치해 서로 다른 상황을 담당하게 한다.

1. **애플리케이션 레벨 선제 조회(주 경로)**: `OrderService.createOrder` 시작부에서 `(userId, idempotencyKey)`로 기존 `Order`를 먼저 조회한다. DB 유니크 제약 위반에 기대지 않고, 순차적으로 들어오는 절대다수의 재요청(네트워크 재시도 등)을 이 경로에서 처리한다. 여기서 다른 본문(해시 불일치)이면 **기존 체결·검증 로직을 전혀 타지 않고** 즉시 409 `IDEMPOTENCY_CONFLICT`를 던진다.
2. **유니크 제약 위반 캐치(동시성 폴백)**: 두 요청이 진짜로 동시에 들어와 1번 조회 시점엔 둘 다 기존 Order를 찾지 못해 그대로 검증·체결을 진행하다, INSERT 단계에서 유니크 제약에 부딪히는 극히 드문 경합만 여기서 잡는다. 이는 spec.md 범위 제외 "분산락 기반 동시성 제어(2차)" 이전의 최선 대응이며, 정합성(중복 체결 금지)은 어차피 DB 유니크 제약이 최종 보증한다 — 이번 폴백은 "그 경우에도 최초 응답을 반환할 수 있으면 반환한다"는 사용자 경험 개선일 뿐이다.

이슈 #22는 두 경로 모두에서 "최초 응답 재구성 시도 → 실패하면 409"를 요구한다("이 경우도 재요청과 동일하게 최초 응답 반환 시도, 그래도 안되면 409"). 이 요구가 클래스 분리를 강제한다 — 아래 참조.

### 왜 기존 `AuthService` 패턴(단일 클래스, catch 후 즉시 변환)을 재사용하지 않는가

`AuthService.changeNickname`/`confirmEmailChange`는 `DataIntegrityViolationException`을 같은 `@Transactional` 메서드 안에서 잡아 **즉시 다른 예외로 변환만 하고 그대로 종료**한다(추가 조회 없음). JPA/Hibernate는 flush·insert 실패 이후 해당 영속성 컨텍스트를 rollback-only로 전환하므로, 그 세션으로 추가 조회를 시도하는 것은 안전하지 않다 — `AuthService`의 두 메서드는 "즉시 변환 후 종료"만 하기 때문에 이 문제를 피해간다.

이번 이슈는 실패 직후 **`Order`+`Trade`를 다시 읽어 응답을 재구성**해야 한다. 이는 깨진 영속성 컨텍스트가 아니라 **트랜잭션이 완전히 롤백된 뒤 새로 여는 조회**가 필요하다는 뜻이다. 같은 클래스 안에서 `@Transactional` 메서드를 `this.xxx(...)`로 호출하면 Spring 프록시를 우회해(self-invocation) 트랜잭션이 아예 시작되지 않는 문제가 있어, 같은 클래스에 트랜잭션 경계를 두 단계로 두는 시도 자체가 위험하다. 따라서 **새 서비스 빈으로 분리**해 프록시 경계를 명확히 만든다(기존 `portfolio` 도메인이 `PortfolioBuyService`/`PortfolioSellService`로 책임을 나눈 전례와 같은 방식).

### 클래스 분리

- **신규** `com.finplay.api.order.service.OrderExecutionService` — 기존 `OrderService`의 검증→가격조회→체결→계좌/보유 갱신 로직(`createOrder`(현재 진입점)·`createBuyOrder`·`createSellOrder`·`validateOrderType`·`getValidatedInstrument`·`validateQuantityFormat`·`getAccountFor`·`priceOrder`·`validateMinOrderAmount`·`OrderPricing` record) 전체를 **그대로 이동**한다. public 메서드는 하나만 남긴다:

  ```java
  @Transactional
  public OrderResponse execute(
      Long userId, String idempotencyKey, String requestHash, OrderCreateRequest request)
  ```

  현재 `createOrder`를 이름만 `execute`로 바꾸고, 시그니처에 `requestHash`를 추가로 받는다(이 클래스 내부에서는 재계산하지 않는다). `calculateRequestHash` 메서드는 이 클래스에서 제거하고 `OrderService`로 옮긴다(아래 참조 — 선제 조회에도 필요해 단일 소스로 유지).
- 기존 `OrderService`는 얇은 오케스트레이터로 축소한다. 유지: `getMyOrders`(변경 없음). 신규/이동: `createOrder`(아래 확정 로직), `findReplayResponse`(private, 신규), `calculateRequestHash`(이동, 로직 변경 없음).

### `OrderService.createOrder` 확정 로직

```java
public OrderResponse createOrder(Long userId, String idempotencyKey, OrderCreateRequest request) {
    String requestHash = calculateRequestHash(request);

    Optional<OrderResponse> replay = findReplayResponse(userId, idempotencyKey, requestHash);
    if (replay.isPresent()) {
        return replay.get();
    }

    try {
        return orderExecutionService.execute(userId, idempotencyKey, requestHash, request);
    } catch (DataIntegrityViolationException concurrentDuplicate) {
        return findReplayResponse(userId, idempotencyKey, requestHash)
            .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT));
    }
}

// 기존 Order를 찾으면 본문 해시를 비교해 응답을 재구성하거나(일치) 즉시 409(불일치)를 던진다.
// 찾지 못하면 빈 Optional — 호출부가 신규 생성 경로로 진행한다.
private Optional<OrderResponse> findReplayResponse(Long userId, String idempotencyKey, String requestHash) {
    return orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
        .map(existingOrder -> {
            if (!existingOrder.getRequestHash().equals(requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            }
            Trade existingTrade = tradeRepository.findByOrderId(existingOrder.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT));
            return OrderResponse.of(existingOrder, existingTrade);
        });
}
```

확정 사항(구현자가 임의로 바꾸지 않는다):

- `createOrder`에는 `@Transactional`을 걸지 않는다 — 트랜잭션 경계는 `OrderExecutionService.execute`만 소유한다. `findReplayResponse`의 두 조회도 Spring Data 리포지토리가 각각 개별적으로 트랜잭션을 여는 단순 조회라 별도 애노테이션이 필요 없다.
- 같은 키에 다른 본문(해시 불일치)이면 **검증·체결을 전혀 시도하지 않고** 즉시 409를 던진다 — 재검증 결과가 최초 요청과 달라질 수 있는 경합(예: 그 사이 장이 닫힘)을 피하기 위함이다.
- 동시성 폴백 경로(`catch` 블록)에서 재조회까지 실패하면(이론상 도달하지 않음 — 유니크 제약 위반은 반드시 경쟁 상대의 행이 이미 커밋되었음을 의미) 방어적으로 409 `IDEMPOTENCY_CONFLICT`를 던진다.
- `OrderController`는 변경하지 않는다 — 재구성 응답이든 신규 생성 응답이든 컨트롤러 입장에서는 동일한 `OrderResponse`라 구분 없이 201로 감싼다("최초 응답을 그대로 반환"에는 상태코드도 포함되므로, 원래 성공이 201이었던 이상 재현 응답도 201로 나가는 것이 맞다).

### `execute` 내부에서 유니크 제약 위반이 발생하는 시점

`Order.id`는 `GenerationType.IDENTITY`라 `orderRepository.save(order)` 호출 시점에 즉시 INSERT가 실행된다(배치·지연 없음 — Hibernate가 생성된 키를 즉시 확보해야 하기 때문). 따라서 유니크 제약 위반은 `Trade` 저장·`account.deductCash`·`PortfolioBuyService`/`PortfolioSellService` 호출보다 **먼저** 발생하며, 그 시점까지 해당 트랜잭션은 다른 어떤 부작용도 만들지 않은 채로 롤백된다 — 부분 커밋·정합성 훼손 우려 없음.

### 신규 리포지토리 메서드

```java
// OrderRepository — instrument는 지연로딩 연관관계이고 OrderResponse.of가 order.getInstrument()에 바로 접근하므로
// 세션이 닫히기 전에 JOIN FETCH로 즉시 초기화해야 한다(기존 findAllByUserIdOrderByRequestedAtDescIdDesc와 동일 패턴).
@Query("SELECT o FROM Order o JOIN FETCH o.instrument WHERE o.user.id = :userId AND o.idempotencyKey = :idempotencyKey")
Optional<Order> findByUserIdAndIdempotencyKey(
    @Param("userId") Long userId, @Param("idempotencyKey") String idempotencyKey);
```

```java
// TradeRepository — OrderResponse.of가 사용하는 Trade 필드는 전부 스칼라 컬럼이라 지연로딩 문제 없음. 파생 쿼리로 충분.
Optional<Trade> findByOrderId(Long orderId);
```

`trades.order_id`에 `uk_trades_order UNIQUE (order_id)` 제약이 이미 있어(V10) 주문 1건당 체결이 최대 1건임을 스키마가 보장한다 — `findByOrderId`가 항상 0~1건만 반환한다.

### 다른 사용자가 같은 키를 쓰는 경우

`findByUserIdAndIdempotencyKey`가 `userId`로 스코프를 좁히고, DB 유니크 제약도 `(user_id, idempotency_key)` 복합키이므로 서로 다른 사용자의 요청은 애초에 같은 행을 두고 경합하지 않는다 — 서비스 레벨에서 별도 분기를 추가하지 않는다. 통합 테스트로 "서로 간섭 없음"만 확인한다(신규 코드 없음, 회귀 확인 목적).

### 테스트 계획

- 기존 `OrderServiceTest`(494줄, 검증·수수료·성공·실패 케이스 전체)는 **클래스명을 `OrderExecutionServiceTest`로 변경**하고 대상을 `OrderExecutionService`로 바꾼다. 테스트 케이스 내용은 그대로 유지 — `orderService.createOrder(...)` 호출부만 `orderExecutionService.execute(userId, idempotencyKey, requestHash, request)` 호출로 바꾸고, 각 테스트가 넘기는 `requestHash`는 임의 고정값(예: `"test-hash"`)이면 충분하다(이 레이어는 해시를 비교하지 않고 그대로 저장만 한다).
- 새 `OrderServiceTest`(슬림, `OrderService` 대상 — `OrderExecutionService`·`OrderRepository`·`TradeRepository` 3개만 mock):
  - 재요청(같은 키+같은 본문) → 기존 `Order`+`Trade` mock으로 `OrderResponse.of`와 동일한 값 반환, `orderExecutionService.execute` **미호출** 검증(`verifyNoInteractions`/`never()`).
  - 같은 키+다른 본문(해시 불일치) → 409 `IDEMPOTENCY_CONFLICT`, `execute` 미호출 검증.
  - 신규 키(기존 Order 없음) → `orderExecutionService.execute` 1회 호출, 반환값 그대로 전달 검증.
  - `execute`가 `DataIntegrityViolationException`을 던짐 + 재조회 시 기존 Order/Trade 발견 → 재구성된 응답 반환(추가 체결 없음 — `execute` 호출이 1회뿐임을 검증).
  - `execute`가 `DataIntegrityViolationException`을 던짐 + 재조회해도 못 찾음(방어적 케이스) → 409 `IDEMPOTENCY_CONFLICT`.
- `@DataJpaTest`: 기존 `OrderRepositoryTest`에 `findByUserIdAndIdempotencyKey` 케이스 추가(존재/미존재/다른 사용자 동일 키 조회 안 됨). 신규 `TradeRepositoryTest`(슬라이스)로 `findByOrderId` 존재/미존재 검증.
- 통합(Testcontainers): 기존 `OrderBuyIntegrationTest`/`OrderSellIntegrationTest`에 케이스 추가하거나 신규 `OrderIdempotencyIntegrationTest` — 동일 키+동일 본문 재요청(매수·매도 각 1케이스) 시 `orders`/`trades`/`holdings`/`holding_lots` 행 수가 늘지 않고 응답이 최초와 동일함을 검증, 동일 키+다른 본문은 409 `IDEMPOTENCY_CONFLICT`, 서로 다른 사용자가 같은 키를 써도 각자 정상 체결됨을 확인.

### 문서 갱신

- `docs/api-contracts.md`의 `POST /api/orders` 행(현재 222·224행) — "이번 구현은 `Idempotency-Key` 헤더 존재 검증까지만 하며 ... #22에서 구현 예정" 문구를 제거하고, 재요청 재현(동일 응답 반환)과 다른 본문 409 `IDEMPOTENCY_CONFLICT` 계약을 명시한다. 근거 열에 Issue #22 추가.
- `ai/api-routes.md`의 `POST /api/orders` 행(현재 36행) — "존재 검증만, 재현 방지는 #22" 문구를 재현·충돌 판정 포함으로 갱신.
- 두 문서는 같은 커밋에서 함께 갱신한다(동기화 모드, planner 재투입).
