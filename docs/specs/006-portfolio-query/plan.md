# Plan: 조회 (계좌 요약 · 합산 포트폴리오 · 보유자산 · 주문 목록 · 거래내역)

> **범위 안내**: `spec.md`는 ACCT-002, ACCT-003, PORT-001, PORT-002, PORT-003 5개 요구사항을 묶어 담고 있다. 이 plan.md·tasks.md는 그중 **PORT-003 주문 목록(GitHub 이슈 #21)만** 설계한다 — 세션 지시가 이슈 #21 구현 범위로 한정됐기 때문이다. ACCT-002·ACCT-003·PORT-001·PORT-002(거래내역, 이슈 #82 등)는 각 이슈가 착수될 때 이 문서에 이어서 보강하거나 별도 spec으로 분리해야 한다 — 지금은 미확정이며 임의로 설계하지 않는다.

## 관련 문서

- Spec: `./spec.md` (PORT-003 절)
- 선행 spec: `docs/specs/011-order-ledger-schema/plan.md` (`orders` 테이블 컬럼·엔티티 정의), `docs/specs/004-order-buy/`(#13, 이미 구현된 매수 파이프라인 — 통합 테스트가 실제 주문 데이터를 만드는 데 사용)
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md) (controller → service → repository, 도메인 간 참조는 service만), [ADR-0003](../../adr/0003-testing-strategy.md) (테스트 레벨별 전략)
- PRD 근거: PORT-003, C-002(추측 구현 금지), §5 공통 오류표

## 기존 구조 확인 (구현 재사용)

이슈 #13(매수, PR #88)이 이미 `order` 도메인을 구현해뒀다. 이번 작업은 새 도메인을 만들지 않고 기존 파일에 추가한다.

| 파일 | 현재 상태 | 이번 변경 |
|---|---|---|
| `src/main/java/com/finplay/api/order/controller/OrderController.java` | `POST /api/orders`만 존재 | `GET /api/orders` 메서드 추가 |
| `src/main/java/com/finplay/api/order/service/OrderService.java` | `createBuyOrder(...)`만 존재 | `getMyOrders(Long userId)` 메서드 추가 |
| `src/main/java/com/finplay/api/order/repository/OrderRepository.java` | `JpaRepository` 상속만 (쿼리 메서드 없음) | 사용자별 최신순 조회 쿼리 메서드 추가 |
| `src/main/java/com/finplay/api/order/domain/Order.java` | PK, user/account/instrument FK, side/orderType/status, quantity, idempotencyKey, requestHash, requestedAt — PORT-003가 요구하는 필드(구분·종목·요청수량·주문유형·상태·요청시각) 전부 이미 존재 | 변경 없음 (엔티티·마이그레이션 추가 불필요) |
| `src/main/java/com/finplay/api/order/dto/response/OrderResponse.java` | 주문+체결 통합 응답(생성 API 전용) | 그대로 둠 — 목록 API는 별도 DTO(`OrderListItemResponse`) 사용, 체결 필드 재노출 금지(spec 요구사항) |

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | /api/orders | 없음(인증만, `Idempotency-Key` 불필요) | `OrderListItemResponse[]` | 인증 사용자 본인 주문을 `requestedAt` 내림차순(동시각은 `id` 내림차순 안정 정렬)으로 전체 반환. 페이지네이션 없음(MVP, spec PORT-003 확정) |

- 인증: 기존 `POST /api/orders`와 동일하게 `@AuthenticationPrincipal AuthenticatedUser`에서 `userId`를 얻는다. 경로·쿼리에 대상 식별자를 받지 않는다(`/api/auth/me`와 동일한 "요청에서 대상을 받지 않는다" 패턴 — 타인 주문 조회 자체가 불가능한 구조).
- 조회 대상이 0건이어도 예외 없이 200과 빈 배열을 반환한다(커뮤니티 게시물 목록과 동일 계약).
- 오류: Access 인증 실패 401 `UNAUTHORIZED` 외에 이 API가 별도로 발생시키는 오류는 없다(입력 파라미터가 없어 400 케이스 없음).

## 입력 명세

없음 — 요청 본문·경로 변수·쿼리 파라미터가 없다. 인증 사용자만 서비스 입력이다.

## 응답 DTO 설계

`com.finplay.api.order.dto.response.OrderListItemResponse` (record, 목록 항목 응답 접미사 규칙 준수 — `docs/conventions.md` DTO 접미사 표).

| 필드 | 타입 | 근거 |
|---|---|---|
| orderId | Long | `Order.id` |
| market | String | `Order.instrument.market.name()` — 기존 `OrderResponse.market`과 동일 표현 방식 |
| instrumentId | Long | `Order.instrument.id` |
| side | String | `Order.side.name()` |
| orderType | String | `Order.orderType.name()` |
| status | String | `Order.status.name()` |
| quantity | BigDecimal | `Order.quantity` |
| requestedAt | LocalDateTime | `Order.requestedAt` |

- 정적 팩토리 `OrderListItemResponse.from(Order order)`.
- **의도적으로 제외**: `tradeId`·`price`·`amount`·`fee`·`executedAt`(체결 전용 필드) — spec PORT-003 "체결가격·체결금액·수수료·실현손익·체결시각은 중복 반환하지 않는다"의 직접 반영. 이 필드들은 `GET /api/trades`(PORT-002, 별도 이슈) 책임이다.
- `symbol`·`name`(종목명) 등 `Instrument`의 표시용 필드는 포함하지 않는다 — 기존 `OrderResponse`(생성 API)도 `market`+`instrumentId`만 노출하는 동일 패턴이라 이번 목록 응답도 그 전례를 따른다. 프론트가 종목명 표시가 필요하면 `GET /api/instruments/{instrumentId}`로 별도 조회한다(이미 존재하는 계약, 신규 조인 불필요).

## Repository 설계

`Order.user`가 `@ManyToOne`이라 Spring Data 파생 쿼리로 충분하다(단순 조회 — `docs/conventions.md` "QueryDSL 사용 기준": 동적 조건·커서 페이지네이션·다중 조인 목록에만 QueryDSL을 쓴다. 이 조회는 조건 1개·정렬 1개뿐이라 QueryDSL 대상이 아니다). `spec.md` 개요의 "QueryDSL 기반 목록 조회" 언급은 006 spec 전체(특히 PORT-002 커서 페이지네이션) 기준이며 PORT-003에는 해당하지 않는다.

N+1 방지를 위해 `instrument`를 `JOIN FETCH`한다(응답이 매 행마다 `instrument.market`을 읽으므로).

```java
@Query("SELECT o FROM Order o JOIN FETCH o.instrument WHERE o.user.id = :userId ORDER BY o.requestedAt DESC, o.id DESC")
List<Order> findAllByUserIdOrderByRequestedAtDescIdDesc(@Param("userId") Long userId);
```

## Service 설계

```java
@Transactional(readOnly = true)
public List<OrderListItemResponse> getMyOrders(Long userId) {
    return orderRepository.findAllByUserIdOrderByRequestedAtDescIdDesc(userId).stream()
        .map(OrderListItemResponse::from)
        .toList();
}
```

- 소유권 검증은 별도 분기 없이 리포지토리 쿼리 조건(`user.id = :userId`) 자체로 타인 주문을 원천 배제한다 — `POST /api/orders`가 계좌를 조회할 때와 동일하게 "요청에서 대상을 받지 않는" 구조라 403/404 분기가 필요 없다(spec의 "위반 시 403 또는 404"는 006 spec 공통 문구이며, 이 엔드포인트는 애초에 타인 리소스를 식별할 입력이 없어 위반 자체가 발생하지 않는다).
- 비즈니스 규칙 판단 없음(현금·시세 등 불필요) — 조회 전용이라 `@Transactional(readOnly = true)`만 붙인다.

## Controller 설계

```java
@GetMapping
public ResponseEntity<List<OrderListItemResponse>> getMyOrders(
    @AuthenticationPrincipal AuthenticatedUser principal) {
    return ResponseEntity.ok(orderService.getMyOrders(principal.userId()));
}
```

- 기존 `OrderController`(POST 핸들러가 이미 있는 클래스)에 메서드만 추가한다. 새 컨트롤러 클래스를 만들지 않는다.

## 데이터 모델

없음 — 011 스키마(`orders` 테이블)를 그대로 조회만 한다. 새 컬럼·마이그레이션 불필요.

## 문서 동기화

같은 커밋에서 갱신(CLAUDE.md 규칙 7):
- `docs/api-routes.md`: 라우트 표에 `GET | /api/orders | order | ...` 행 추가.
- `docs/api-contracts.md`: `## order` 절에 "내 주문 목록 조회" 표 추가 — 요청 없음, 성공 200 배열 예시(`OrderListItemResponse` 필드), 오류는 401만.

## 테스트 계획 (ADR-0003 기준)

- **단위**: `src/test/java/com/finplay/api/order/service/OrderServiceTest.java`(기존 파일)에 `getMyOrders` 테스트 추가 — Mockito로 `OrderRepository`를 stub해 반환된 `Order` 목록이 `OrderListItemResponse` 필드로 정확히 매핑되는지, 빈 목록일 때 빈 리스트를 반환하는지 검증. `mock(OrderListItemResponse.class)` 금지 — 실제 값으로 검증(컨벤션).
- **슬라이스 Repository**: `@DataJpaTest` — `findAllByUserIdOrderByRequestedAtDescIdDesc`가 (1) 다른 `user_id`의 주문을 제외하고 (2) `requestedAt` 내림차순·동시각 `id` 내림차순으로 정렬해 반환하는지 검증. 새 테스트 클래스 또는 기존 `OrderLedgerSchemaTest` 근처에 배치.
- **슬라이스 API**: `src/test/java/com/finplay/api/order/controller/OrderControllerTest.java`(기존 파일, `@WebMvcTest`)에 `GET /api/orders` 테스트 추가 — 200 응답의 `jsonPath`로 필드 계약 검증(체결 전용 필드가 응답에 없음도 함께 확인), 인증 실패 401.
- **통합**: `src/test/java/com/finplay/api/order/service/OrderBuyIntegrationTest.java`(기존 Testcontainers 파일) 또는 인접한 `OrderIntegrationTest`에 시나리오 추가 — #13 매수 파이프라인으로 실제 주문 2건 이상(가능하면 서로 다른 사용자 포함) 생성 후 `GET /api/orders` 호출 → 본인 주문만 최신순으로 반환, 필드 계약 일치, 타인 주문 제외, 체결 전용 필드 미노출을 한 시나리오에서 검증. 주문이 없는 신규 사용자에 대해 200 빈 배열도 검증.

---

## 이슈 #47: 보유 평가 계산 공통 구현 (ACCT-002 · PORT-001 선행, API 없음)

> **범위 안내**: 이 섹션은 `spec.md`의 ACCT-002·PORT-001이 언급하는 "평가금액·미실현손익 계산"의 **공통 계산 진입점만** 다룬다. 계좌 요약 API(#81), 보유 종목 API(#52), 합산 포트폴리오 API(#51), 거래내역(PORT-002/#82)은 각 이슈가 착수될 때 별도로 설계한다 — 지금은 미확정이며 임의로 설계하지 않는다. **이 이슈는 controller·API를 추가하지 않는다.**

### 관련 문서

- Spec: `./spec.md` ACCT-002·PORT-001 절, 비즈니스 규칙 5번째 항목("시세가 유효하지 않은 종목의 평가값 처리 방식은 plan에서 확정한다")
- PRD 근거: ACCT-002, PORT-001, [C-003](../../prd.md)(금액 double/float 금지, BigDecimal/원단위 BIGINT만 사용)
- 반올림 전례: `docs/specs/004-order-buy/plan.md` 섹션 5 "금액 계산 — 반올림 규칙 확정"(`amount = price.multiply(quantity).setScale(0, RoundingMode.FLOOR)` 확정 — 이 계산도 동일 방향으로 통일한다)
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(도메인 간 참조는 service 레이어를 통해서만 — 이번 서비스는 `portfolio` 도메인에 두고 이후 `account` 도메인 서비스가 이를 호출하는 방식으로 재사용한다)
- 선행 이슈: #12(원장 스키마, 병합됨), #16(현재가 조회 및 공통 가격 계약, 병합됨)

### 기존 구조 확인

| 대상 | 현재 상태 | 이번 변경 |
|---|---|---|
| `com.finplay.api.portfolio.domain.Holding` | `quantity`(`BigDecimal`, precision 30 scale 8), `averagePrice`(`BigDecimal`, precision 18 scale 8), `instrument`(FK) 이미 보유 | 변경 없음 — 계산 입력으로 그대로 사용 |
| `com.finplay.api.market.service.PriceQueryService` | `getPrice(...)`(시세 무효 시 `BusinessException(PRICE_UNAVAILABLE)` throw, 409) / `getPriceQuote(...)`(시세 무효 시 throw 없이 `PriceQuoteDto(null, null, PriceStatus.UNAVAILABLE, ...)` 반환) 두 변형이 이미 존재(#16, #18) | 변경 없음 — 이 계산 진입점은 반드시 **`getPriceQuote(Instrument)`(비throw 변형)만** 사용한다 |
| `com.finplay.api.portfolio.service.SellAllocationDto`, `com.finplay.api.market.service.PriceQuoteDto` | service 간 내부 전달 DTO를 record로 해당 `service` 패키지에 직접 두는 전례(`dto/` 하위가 아님) | 이번 `HoldingValuationDto`도 동일 전례를 따른다 |
| `com.finplay.api.order.service.OrderExecutionService.priceOrder(...)` | 원단위 금액을 `BigDecimal.multiply(...).setScale(0, RoundingMode.FLOOR).longValueExact()`로 확정(004-order-buy 섹션 5) | 이번 평가금액·원가 계산도 동일 규칙 재사용 |

### 공통 진입점 설계

- 위치: `com.finplay.api.portfolio.service.HoldingValuationService`(신규) — `Holding`이 portfolio 도메인 엔티티이므로 이 계산도 portfolio 도메인에 둔다. `account` 도메인(#81)이 필요하면 자신의 service에서 이 service를 주입해 호출한다(ADR-0002: 도메인 간 참조는 service 레이어를 통해서만, repository 직접 참조 금지).
- 시그니처:

```java
@Service
@RequiredArgsConstructor
public class HoldingValuationService {

    private final PriceQueryService priceQueryService;

    @Transactional(readOnly = true)
    public HoldingValuationDto evaluateHolding(Holding holding) { ... }
}
```

- 입력: `Holding` 엔티티 1건(계좌·종목별로 이미 로딩된 것을 그대로 받는다 — 이 메서드는 리스트 조회·필터링을 하지 않는다. 활성 보유만 넘길지, 전량 매도 종목까지 넘길지는 호출부(#81/#52/#51)의 책임이며 PORT-001의 "전량 매도한 종목 제외"는 이 계산 진입점이 아니라 각 API의 목록 조회 단계에서 처리한다).
- 출력: `HoldingValuationDto`(신규, `portfolio.service` 패키지, record):

| 필드 | 타입 | 설명 |
|---|---|---|
| quantity | BigDecimal | `holding.getQuantity()` 그대로 |
| averagePrice | BigDecimal | `holding.getAveragePrice()` 그대로 |
| costBasis | long | 보유수량 × 평균단가, 원단위 내림 (시세와 무관하게 항상 계산 가능) |
| priceStatus | `com.finplay.api.market.service.PriceStatus` | `AVAILABLE`/`UNAVAILABLE` — 기존 enum 재사용(신규 enum 만들지 않음) |
| evaluationAmount | Long (nullable) | `priceStatus == UNAVAILABLE`이면 `null`, 아니면 보유수량 × 최신가 원단위 내림 |
| unrealizedPnl | Long (nullable) | `priceStatus == UNAVAILABLE`이면 `null`, 아니면 `evaluationAmount - costBasis` |
| returnRate | BigDecimal (nullable) | `priceStatus == UNAVAILABLE`이면 `null`, `costBasis == 0`이면 `BigDecimal.ZERO`, 아니면 `unrealizedPnl ÷ costBasis`(scale 4, `RoundingMode.HALF_UP`) |

### 계산 규칙 확정 (반올림·스케일)

PRD C-003에 따라 전 구간 `BigDecimal`/`long`만 사용한다(`double`/`float` 금지).

1. **원가(costBasis, 원단위 `long`)** = `holding.getQuantity().multiply(holding.getAveragePrice())` → `.setScale(0, RoundingMode.FLOOR).longValueExact()`. 시세 유효 여부와 무관하게 항상 계산한다.
2. **평가금액(evaluationAmount, 원단위 `long`, nullable)** = `holding.getQuantity().multiply(quote.price())` → `.setScale(0, RoundingMode.FLOOR).longValueExact()`. `004-order-buy`에서 확정한 거래금액 내림 규칙과 방향을 통일한다(반올림·올림이 아님).
3. **미실현손익(unrealizedPnl, 원단위 `long`, nullable)** = `evaluationAmount - costBasis`(정수 뺄셈, 추가 반올림 없음 — 두 값이 이미 원단위로 확정돼 있으므로 재변환하지 않는다).
4. **수익률(returnRate, `BigDecimal`, nullable)** = `BigDecimal.valueOf(unrealizedPnl).divide(BigDecimal.valueOf(costBasis), 4, RoundingMode.HALF_UP)`. 소수 4자리(예: `0.1523` = 15.23%)의 **비율 값**을 반환한다 — 퍼센트 표시(×100, `%` 접미사)는 이 계산의 책임이 아니라 소비하는 API/화면 계층의 책임이다.
   - `costBasis == 0`(보유수량 0 또는 평균단가 0)이면 0으로 나누는 상황이라 `ArithmeticException`을 피하기 위해 **`returnRate = BigDecimal.ZERO`로 확정**한다(수익률 "정의 불가"를 별도 값으로 표현하지 않는다 — 어차피 원금이 0이면 미실현손익도 0이므로 0%가 합리적인 표현이다).

### 시세 무효 종목 처리 규칙 확정 (spec.md 미결 사항)

- **이 계산 진입점은 예외를 던지지 않는다.** `PriceQueryService.getPriceQuote(Instrument)`(비throw 변형)만 사용하고, 시세가 무효(`PriceStatus.UNAVAILABLE`)면 `HoldingValuationDto`의 `evaluationAmount`/`unrealizedPnl`/`returnRate`를 `null`로 채워 정상 반환한다.
- 근거: 이 서비스는 계좌 요약(#81)·보유 종목(#52)·합산 포트폴리오(#51) 3개 API가 공유하는 하위 계산이다. 만약 여기서 예외를 던지면 세 호출부가 각자 try-catch로 "한 종목의 시세 무효가 전체 조회를 막지 않는다"는 동일 처리를 중복 구현해야 한다 — 그 중복을 막는 것이 #47의 목적이다. 대신 `priceStatus` 필드로 상태를 알려주고, "무효 종목을 목록에서 어떻게 표시할지"(마지막 유효가 표기, `null` 그대로 노출, 평가액 합산에서 제외 등 최종 표현)는 각 API 자신의 spec/plan에서 결정한다.
- `costBasis`는 시세와 무관하게 항상 값을 갖는다 — 보유수량·평균단가는 원장에만 의존하므로 시세 무효와 관계없이 계산 가능하다.

### 경계 케이스 처리표

| 케이스 | costBasis | evaluationAmount | unrealizedPnl | returnRate |
|---|---|---|---|---|
| 정상(시세 유효, 이익) | 값 있음 | 값 있음 | 양수 | 값 있음 |
| 정상(시세 유효, 손실) | 값 있음 | 값 있음 | 음수 | 음수 값 있음 |
| 시세 무효(`PriceStatus.UNAVAILABLE`) | 값 있음 | `null` | `null` | `null` |
| 보유수량 0 | `0` | 시세 유효 시 `0`, 무효 시 `null` | 시세 유효 시 `0`, 무효 시 `null` | `BigDecimal.ZERO` |
| 평균단가 0 | `0` | 시세 유효 시 값 있음(보유수량 × 최신가), 무효 시 `null` | 시세 유효 시 evaluationAmount와 동일값(원가 0), 무효 시 `null` | `BigDecimal.ZERO` |

### 데이터 모델

없음 — 신규 컬럼·마이그레이션·저장소 변경 없음. 평가값은 어디에도 저장하지 않는다(spec 비즈니스 규칙, PRD ACCT-002).

### 문서 동기화

해당 없음 — controller 변경이 없어 `docs/api-routes.md`·`docs/api-contracts.md` 갱신 대상이 아니다(CLAUDE.md 규칙 7은 controller 변경 시에만 적용). 세 소비 API(#81/#52/#51)가 각자 구현될 때 그 API의 계약 문서에서 이 계산 결과 필드를 노출한다.

### 테스트 계획 (ADR-0003 기준)

- **단위**: `src/test/java/com/finplay/api/portfolio/service/HoldingValuationServiceTest.java`(신규) — Mockito로 `PriceQueryService.getPriceQuote(Instrument)`를 stub.
  - 정상 케이스: 이익(평가금액 > 원가)·손실(평가금액 < 원가) 각각에서 `evaluationAmount`·`unrealizedPnl`·`returnRate` 계산값을 실제 수치로 검증(mock 응답 객체 금지 컨벤션).
  - 경계 케이스: 시세 무효(`PriceStatus.UNAVAILABLE`) → 세 필드 모두 `null`이고 예외가 발생하지 않음. 보유수량 0 → `costBasis=0`, `returnRate=BigDecimal.ZERO`. 평균단가 0 → `costBasis=0`, `returnRate=BigDecimal.ZERO`.
- Repository·API 슬라이스·Testcontainers 통합 테스트는 이 이슈 범위에 없다(API가 없으므로) — #81/#52/#51 각 이슈가 자신의 통합 테스트에서 이 서비스를 통해 검증한다.

---

## 이슈 #81: 시장별 계좌 요약 조회 API 구현 (ACCT-002)

> **범위 안내**: 이 섹션은 `spec.md`의 ACCT-002가 요구하는 **실제 조회 API**만 설계한다. 평가금액·미실현손익 계산 자체는 이슈 #47(`HoldingValuationService`, 위 절)이 이미 구현·완료했고 이번 이슈는 이를 재사용만 한다. ACCT-003(합산 포트폴리오, #51)·PORT-001(보유 종목 목록, #52)·PORT-002(거래내역, #82)는 각자 착수될 때 별도로 설계한다 — 지금은 미확정이며 임의로 설계하지 않는다.

### 관련 문서

- Spec: `./spec.md` ACCT-002 절
- PRD 근거: `docs/prd.md` ACCT-002 (이번 이슈에서 수익률 필드를 추가해 갱신 — 문서 동기화 절 참고)
- 선행 절: 이 문서의 "이슈 #47" 절(`HoldingValuationService`/`HoldingValuationDto` 시그니처·반올림 규칙·시세 무효 처리 규칙 — 이번 이슈가 그대로 재사용)
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md) — **도메인 간 참조는 service 레이어를 통해서만, 다른 도메인의 repository를 직접 주입하지 않는다.** `Holding`은 `portfolio` 도메인 엔티티이므로 `account` 도메인의 `AccountService`가 `HoldingRepository`(portfolio)를 직접 주입하면 ADR 위반이다 — 아래 "Service 설계"에서 이를 피하는 구조를 명시한다.
- 선행 이슈: #12(원장 스키마, 병합됨), #47(평가 계산, 병합됨), #13·#41(매수·매도, 병합됨)
- 후속: #51(합산 포트폴리오)이 이 이슈의 수익률 계산식(`(총평가액 − 시드머니) ÷ 시드머니`)을 그대로 재사용한다 — #51에서 새 계산식을 만들지 않는다.

### 기존 구조 확인

| 대상 | 현재 상태 | 이번 변경 |
|---|---|---|
| `com.finplay.api.account.domain.Account` | `cashBalance`(long)·`seedMoney`(long, 생성 시 `INITIAL_SEED_MONEY`=10,000,000 고정, setter 없음)·`realizedPnl`(long) 필드 이미 존재 | 변경 없음 — 세 값 모두 그대로 읽기만 한다 |
| `com.finplay.api.account.repository.AccountRepository` | `findAllByUserId`, `findByUserIdAndMarket` 존재 | 변경 없음 |
| `com.finplay.api.account.service.AccountService` | 명시적 2-인자 생성자(`accountRepository`, `clock`), `createAccountsFor`·`getAccountFor(userId, market)`만 존재 | `getAccountSummary(Long userId, Market market)` 추가. 생성자 주입 대상이 늘어나므로 **명시적 생성자를 `@RequiredArgsConstructor`로 교체**(컨벤션 — service는 Lombok 생성자 주입, 기존 파일이 예외적으로 수기 생성자였을 뿐 이번에 맞춘다) |
| `com.finplay.api.account.controller` | 컨트롤러 없음(패키지 자체가 없음) | `AccountController` 신규 생성 |
| `com.finplay.api.portfolio.repository.HoldingRepository` | `findByAccountIdAndInstrumentId`만 존재, 계좌 전체 보유 목록 조회 메서드 없음 | 계좌별 활성 보유 목록 조회 메서드 추가 (아래 "Repository 설계") |
| `com.finplay.api.portfolio.service.HoldingValuationService` | `evaluateHolding(Holding holding)`(단건)만 존재, `PriceQueryService`만 주입받음 | 계좌 단위로 활성 보유를 조회+평가까지 묶는 메서드 추가 — `HoldingRepository`를 이 서비스에 새로 주입(같은 `portfolio` 도메인이라 ADR-0002 위반 아님) |
| `com.finplay.api.auth.token.AuthenticatedUser` | `record(Long userId, String role)` | 변경 없음 — `principal.userId()`로 재사용 |

### Market 타입 주의 (임의 해석 금지)

이 코드베이스에는 이름이 같은 `enum Market`이 **두 개** 존재한다 — `com.finplay.api.account.domain.Market`(계좌가 속한 시장, `Account.market` 타입)과 `com.finplay.api.market.domain.Market`(종목이 속한 시장, `Instrument.market` 타입). `OrderCreateRequest.market`은 후자(종목 시장과 직접 비교하기 위해)를 쓰지만, 이번 API는 **계좌를 조회하는 것이 목적**이므로 `AccountService.getAccountFor(Long userId, Market market)`의 시그니처와 동일한 **`com.finplay.api.account.domain.Market`** 을 컨트롤러 쿼리 파라미터 타입으로 써야 한다. `market.domain.Market`을 import하면 `getAccountFor` 호출부에서 타입이 맞지 않아 컴파일이 실패한다.

### API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | /api/accounts/summary?market={STOCK\|CRYPTO} | 인증만(Access Bearer), 쿼리 파라미터 `market` 필수 | `AccountSummaryResponse` | 인증 사용자 본인의 해당 시장 계좌 요약(현금잔고·보유평가액·총평가액·실현손익·미실현손익·수익률) 반환 |

- 인증: `@AuthenticationPrincipal AuthenticatedUser`에서 `userId`를 얻는다. 경로·쿼리에 계좌 식별자(`accountId`)를 받지 않는다 — `POST /api/orders`·`GET /api/orders`와 동일하게 "요청에서 대상을 받지 않는" 패턴이라 타인 계좌 조회 자체가 불가능한 구조다.
- 보유 종목이 없어도(신규 가입 직후 등) 예외 없이 200과 0으로 채운 `AccountSummaryResponse`를 반환한다(spec 완료 조건, PRD ACCT-002).

### 입력 명세

| 파라미터 | 위치 | 타입 | 필수 | 검증 | 근거 |
|---|---|---|---|---|---|
| `market` | 쿼리 | `com.finplay.api.account.domain.Market`(enum: `STOCK`\|`CRYPTO`) | 필수 | Spring이 쿼리 파라미터를 enum으로 바인딩 — 생략 시 `MissingServletRequestParameterException`, `STOCK`\|`CRYPTO`가 아닌 문자열(예: `FOREX`)은 `MethodArgumentTypeMismatchException`. **둘 다 이미 `GlobalExceptionHandler.handleBadRequest`가 400 `VALIDATION_ERROR`로 매핑한다**(`GlobalExceptionHandlerTest.mapsMissingRequiredRequestParamToValidationErrorCode`·`mapsEnumQueryParamTypeMismatchToValidationErrorCodeInsteadOfInternalError`로 이미 검증된 공통 동작) — **컨트롤러에 별도 400 처리 코드를 추가하지 않는다**. `@RequestParam Market market`으로 선언하는 것만으로 이슈 #81의 "market 누락/잘못된 값 400" 수용 기준이 충족된다 |

- 요청 본문 없음.

### 응답 DTO 설계

`com.finplay.api.account.dto.response.AccountSummaryResponse` (record, 단건 응답 접미사 `~Response`).

| 필드 | 타입 | 근거 |
|---|---|---|
| cashBalance | long | `Account.cashBalance` 그대로 |
| holdingsValue | long | 활성 보유 중 시세 유효(`AVAILABLE`)한 항목의 `evaluationAmount` 합산(원단위) |
| totalValue | long | `cashBalance + holdingsValue` (완료 조건 "총평가액 = 현금잔고 + 보유평가액 항상 성립"의 직접 구현) |
| realizedPnl | long | `Account.realizedPnl` 그대로(계좌 원장 값, 재계산 없음 — 이슈 #81 요구사항) |
| unrealizedPnl | long | 활성 보유 중 시세 유효한 항목의 `unrealizedPnl` 합산(원단위) |
| returnRate | BigDecimal | `(totalValue - seedMoney) / seedMoney`, scale 4 `RoundingMode.HALF_UP`. `seedMoney == 0`이면 `BigDecimal.ZERO`(방어적 — 현재 `Account`는 항상 `INITIAL_SEED_MONEY`로 생성되어 실질적으로 발생하지 않지만 0-나눗셈 예외를 피하기 위해 `HoldingValuationService.returnRate`와 동일한 관례를 따른다) |

- 정적 팩토리 `AccountSummaryResponse.of(long cashBalance, long holdingsValue, long totalValue, long realizedPnl, long unrealizedPnl, BigDecimal returnRate)` — 단일 엔티티에서 바로 매핑하는 것이 아니라 여러 계산값을 조합하므로 컨벤션의 `of(...)` 규칙(인자 2개 이상 조합)을 따른다.
- `returnRate`는 비율 값(예: `0.0523` = 5.23%)이며 `%` 변환·표시 포맷은 이 응답의 책임이 아니다(`HoldingValuationService.returnRate`와 동일 관례, 소비 화면이 처리).

### 시세 무효 종목 합산 정책 확정 (spec.md 미결 사항 — 이 이슈에서 결정)

이슈 #47의 `HoldingValuationService.evaluateHolding`은 시세가 무효(`PriceStatus.UNAVAILABLE`)면 `evaluationAmount`·`unrealizedPnl`·`returnRate`를 `null`로 반환하고 예외를 던지지 않는다("무효 종목을 목록에서 어떻게 표시할지는 각 API가 결정" — #47 plan 절). 계좌 요약은 단일 숫자(`holdingsValue`·`unrealizedPnl`)를 반환해야 하고 spec 완료 조건이 "보유 종목이 없어도 0으로 채운 정상 응답"을 요구하므로, 이 API는 다음과 같이 확정한다.

- **시세 무효 종목은 합산에서 제외한다** (기여분 0으로 처리) — 즉 `priceStatus == PriceStatus.AVAILABLE`인 보유만 `holdingsValue`·`unrealizedPnl` 합계에 더한다. `UNAVAILABLE`인 보유는 두 합계에 아무 것도 더하지 않는다(원가도 더하지 않는다 — 원가만 부분 반영하면 `totalValue`·`returnRate`가 왜곡되므로 해당 종목을 아예 없는 것처럼 취급).
- 이 API는 예외를 던지지 않는다(`PriceQueryService.getPrice`의 throw 변형을 쓰지 않음 — `HoldingValuationService.evaluateHolding`이 이미 비throw 변형만 사용하므로 자동으로 보장된다). 한 종목의 시세 무효가 전체 계좌 요약 조회를 막지 않는다.
- 통합 테스트에서 "시세 무효 종목 보유 상황"을 반드시 검증한다(아래 테스트 계획).

### Repository 설계 (`HoldingRepository`, portfolio 도메인)

```java
@Query("SELECT h FROM Holding h JOIN FETCH h.instrument WHERE h.account.id = :accountId AND h.isActive = true")
List<Holding> findAllByAccountIdAndIsActiveTrue(@Param("accountId") Long accountId);
```

- `isActive = true`만 조회한다 — 전량 매도한 종목(`quantity = 0`, PORT-001 규칙과 동일 전례)은 애초에 합산 대상이 아니므로 DB 단에서 제외해 불필요한 `PriceQueryService` 호출을 만들지 않는다.
- `instrument`를 `JOIN FETCH`한다 — `HoldingValuationService.evaluateHolding`이 `holding.getInstrument()`로 시세를 조회하므로 N+1을 피한다(PORT-003 절의 `Order` `JOIN FETCH` 전례와 동일).
- 계좌 하나에 여러 보유가 있을 수 있어 단순 파생 쿼리로는 `JOIN FETCH`를 못 쓰므로 JPQL `@Query`를 쓴다(단순 조건 1개+조인 1개라 QueryDSL 대상은 아님 — `docs/conventions.md` QueryDSL 기준).

### Service 설계

**`HoldingValuationService`(portfolio 도메인)에 계좌 단위 조회+평가 메서드 추가** — `account` 도메인이 `HoldingRepository`(portfolio)를 직접 참조하지 않고 이 서비스를 통해서만 보유 평가 결과를 얻게 하기 위함(ADR-0002).

```java
// HoldingValuationService에 HoldingRepository 필드 추가 후:
@Transactional(readOnly = true)
public List<HoldingValuationDto> evaluateActiveHoldingsForAccount(Long accountId) {
    return holdingRepository.findAllByAccountIdAndIsActiveTrue(accountId).stream()
        .map(this::evaluateHolding)
        .toList();
}
```

**`AccountService`(account 도메인)** — `HoldingValuationService`만 주입받는다(`HoldingRepository`는 주입하지 않는다 — ADR-0002).

```java
@Transactional(readOnly = true)
public AccountSummaryResponse getAccountSummary(Long userId, Market market) {
    Account account = getAccountFor(userId, market); // 기존 메서드 재사용 — 소유권 검증이 이미 포함됨

    List<HoldingValuationDto> valuations = holdingValuationService.evaluateActiveHoldingsForAccount(account.getId());
    long holdingsValue = 0L;
    long unrealizedPnl = 0L;
    for (HoldingValuationDto valuation : valuations) {
        if (valuation.priceStatus() == PriceStatus.AVAILABLE) {
            holdingsValue += valuation.evaluationAmount();
            unrealizedPnl += valuation.unrealizedPnl();
        }
    }

    long cashBalance = account.getCashBalance();
    long totalValue = cashBalance + holdingsValue;
    long realizedPnl = account.getRealizedPnl();
    long seedMoney = account.getSeedMoney();
    BigDecimal returnRate = seedMoney == 0
        ? BigDecimal.ZERO
        : BigDecimal.valueOf(totalValue - seedMoney)
            .divide(BigDecimal.valueOf(seedMoney), 4, RoundingMode.HALF_UP);

    return AccountSummaryResponse.of(cashBalance, holdingsValue, totalValue, realizedPnl, unrealizedPnl, returnRate);
}
```

- 소유권 검증: 별도 분기 없이 `getAccountFor(userId, market)`가 `findByUserIdAndMarket(userId, market)`로 조회하므로 타인 계좌를 조회할 입력 자체가 없다(PORT-003과 동일 근거 — "위반 시 403 또는 404"는 006 spec 공통 문구이며, 이 엔드포인트엔 타인 리소스를 식별할 입력이 없어 위반이 발생하지 않는다). 계좌가 존재하지 않는 극단적 케이스만 기존 로직 그대로 `BusinessException(NOT_FOUND)`.
- `AccountService`의 생성자를 `@RequiredArgsConstructor`로 교체(`accountRepository`, `holdingValuationService`, `clock` 3개 `final` 필드) — 컨벤션 위반이던 기존 수기 생성자를 이 기회에 정리한다.

### Controller 설계

```java
package com.finplay.api.account.controller;

import com.finplay.api.account.domain.Market; // market.domain.Market이 아님 — 위 "Market 타입 주의" 참고
import com.finplay.api.account.dto.response.AccountSummaryResponse;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.token.AuthenticatedUser;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @GetMapping("/summary")
    public ResponseEntity<AccountSummaryResponse> getAccountSummary(
        @AuthenticationPrincipal AuthenticatedUser principal,
        @RequestParam Market market) {
        return ResponseEntity.ok(accountService.getAccountSummary(principal.userId(), market));
    }
}
```

- 새 패키지 `com.finplay.api.account.controller`를 만든다(기존에 컨트롤러가 없었음).
- `@RequestParam`에 기본값을 두지 않는다 — 생략 시 400이 나와야 하므로(수용 기준) `required = true`(기본값)를 그대로 둔다.

### 데이터 모델

없음 — 신규 컬럼·마이그레이션 불필요. 기존 `accounts`·`holdings` 테이블을 조회만 한다. 평가값(`holdingsValue`·`unrealizedPnl`·`returnRate`)은 어디에도 저장하지 않는다(spec 비즈니스 규칙).

### 문서 동기화

같은 커밋에서 갱신(CLAUDE.md 규칙 7 + 이슈 #81 본문 요구):

- `docs/prd.md` ACCT-002 절에 수익률 필드를 추가한다 — 현재 "현금잔고, 보유평가액, 총평가액, 실현손익, 미실현손익을 시장별로 반환한다." 문장에 수익률을 포함하도록 갱신(`(총평가액 − 시드머니) ÷ 시드머니` 계산식 근거 명시, #51이 동일 계산식을 재사용함을 각주로 남긴다).
- `docs/api-routes.md`: 라우트 표에 `GET | /api/accounts/summary?market= | account | ... | 006 ACCT-002, Issue #81` 행 추가.
- `docs/api-contracts.md`: 새 `## account` 절 신설(이 API가 계좌 도메인 최초 컨트롤러이므로 절 자체가 없음) — 요청(쿼리 `market` 필수), 성공 200 예시(`AccountSummaryResponse` 6개 필드 값 포함), 오류(market 누락/잘못된 값 400 `VALIDATION_ERROR`, 인증 실패 401 `UNAUTHORIZED`) 표 추가.

### 테스트 계획 (ADR-0003 기준)

- **단위 — `HoldingValuationServiceTest`(기존 파일)**: `evaluateActiveHoldingsForAccount` 추가 — `HoldingRepository.findAllByAccountIdAndIsActiveTrue`를 Mockito로 stub(활성 보유 2건 이상, 시세 유효/무효 혼합)해 각 `Holding`이 `evaluateHolding`과 동일한 매핑 결과로 반환되는지 검증. 활성 보유 없음 → 빈 리스트.
- **슬라이스 Repository — `HoldingRepositoryTest`(신규, `@DataJpaTest`)**: `findAllByAccountIdAndIsActiveTrue`가 (1) 다른 `account_id`의 보유를 제외하고 (2) `isActive = false`(전량 매도) 보유를 제외하며 (3) `instrument`를 지연 로딩 예외 없이 접근 가능한지(`JOIN FETCH` 확인) 검증.
- **단위 — `AccountServiceTest`(기존 파일)**: `getAccountSummary` 추가 — `HoldingValuationService`를 Mockito로 stub.
  - 시세 유효 보유만 있는 케이스: `holdingsValue`·`unrealizedPnl`이 정확히 합산되고 `totalValue = cashBalance + holdingsValue`, `returnRate` 계산식이 정확한지 실제 수치로 검증.
  - 시세 무효 보유가 섞인 케이스: 해당 보유가 `holdingsValue`·`unrealizedPnl` 합계에서 제외되는지(0 기여) 검증, 예외가 발생하지 않음을 확인.
  - 활성 보유 없음(빈 리스트) 케이스: `holdingsValue=0`·`unrealizedPnl=0`이고 `cashBalance`·`realizedPnl`은 계좌 값 그대로, `totalValue = cashBalance`.
  - 계좌 없음 케이스: `getAccountFor`가 이미 검증된 대로 `BusinessException(NOT_FOUND)`을 던지는지(회귀 확인).
- **슬라이스 API — `AccountControllerTest`(신규, `@WebMvcTest`)**: `OrderControllerTest` 패턴(`@Import(SecurityConfig.class)`, `MockitoBean JwtTokenProvider`) 재사용.
  - `market=STOCK`·`market=CRYPTO` 각각 200과 `jsonPath`로 6개 필드 값 검증.
  - `market` 쿼리 파라미터 누락 → 400 `VALIDATION_ERROR`.
  - `market=FOREX`(미지원 리터럴) → 400 `VALIDATION_ERROR`.
  - 인증 실패(Authorization 헤더 없음) → 401 `UNAUTHORIZED`.
- **통합 — Testcontainers(기존 매수 통합 테스트 파일 인접 또는 신규 `AccountSummaryIntegrationTest`)**:
  - 회원가입 직후(매수 이력 없음) `GET /api/accounts/summary?market=STOCK` → 200, 6개 값 모두 0(단 `cashBalance`는 초기 시드머니).
  - 매수 API로 실제 매수 실행 후 조회 → `cashBalance`(차감 반영)·`holdingsValue`·`totalValue`·`unrealizedPnl`·`returnRate`가 원장·시세 기준으로 정확히 일치.
  - 시세가 무효한 종목을 보유한 상황(예: `PriceStore`에 값이 없는 코인 보유) → 예외 없이 200, 해당 종목이 합산에서 제외됐는지 확인.
  - 타인 계좌 매수 후 본인 계좌 조회 시 타인 데이터가 섞이지 않는지 확인.
  - 응답값이 어떤 테이블에도 저장되지 않는지(평가값 미저장 요구사항) 간접 확인 — 동일 조회를 반복 호출해도 매번 최신 계산 결과가 나오는지(가격 변경 시나리오로 확인 가능하면 포함).
