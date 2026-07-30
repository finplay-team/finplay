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

- 이 API는 예외를 던지지 않는다(`PriceQueryService.getPrice`의 throw 변형을 쓰지 않음 — `HoldingValuationService.evaluateHolding`이 이미 비throw 변형만 사용하므로 자동으로 보장된다). 한 종목의 시세 무효가 전체 계좌 요약 조회를 막지 않는다.
- 통합 테스트에서 "시세 무효 종목 보유 상황"을 반드시 검증한다(아래 테스트 계획).

> **정책 수정 (PR #96 리뷰 차단 반영, 2026-07-30)**: 최초 구현은 "시세 무효 종목은 원가까지 포함해 합산에서 완전히 제외(0 기여)"였다. 그러나 주식 시세가 재생(replay) 기반이라 장 마감 시간대(평일 09:01 이전·주말·공휴일 — 하루 대부분)엔 **전 종목이 동시에 `UNAVAILABLE`**이 되고, 이 경우 원가까지 제외하면 실제 손실이 없는데도 `holdingsValue=0`·`totalValue=현금만`·수익률 대폭 마이너스로 보이는 오류가 발생한다(QA 재현: 00:52 KST, 현금+보유 10주 계좌가 수익률 -7%로 응답). 이를 반영해 다음과 같이 정책을 바꾼다.
>
> - **`AVAILABLE`**: 기존과 동일 — `evaluationAmount`를 `holdingsValue`에, `unrealizedPnl`을 `unrealizedPnl` 합계에 가산.
> - **`UNAVAILABLE`**: `evaluationAmount`(`null`) 대신 **`HoldingValuationDto.costBasis`(보유수량 × 평균단가, 시세와 무관하게 항상 채워짐)를 `holdingsValue`에 가산**하고, `unrealizedPnl` 합계에는 **0만 가산**(손익을 알 수 없으니 "원금만큼 있다"로 취급, 손익 자체는 표시하지 않음).
> - 근거: 휴장 중에도 보유자산이 "없어진 것처럼" 보이면 사용자 신뢰를 해친다. 원가는 시세와 무관하게 항상 신뢰 가능한 값이므로 이를 폴백으로 쓰면 최소한 "원금만큼의 자산이 있다"는 사실은 보존되고, 손익만 "알 수 없음(0 표시)"으로 남는다. 이 방식이 "완전 제외"보다 실제 상태를 덜 왜곡한다.
> - `AccountService.getAccountSummary` 구현·`AccountServiceTest`(시세 무효 혼합 케이스)·`docs/api-contracts.md` `## account` 절을 이 정책으로 갱신했다.

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

### 후속 검토 사항 (PR #96 리뷰 권장, 이번 PR 범위 아님)

- **시세 조회 N+1 성격**: `HoldingValuationService.evaluateHolding`이 보유 1건마다 `PriceQueryService.getPriceQuote`를 호출한다 — 주식은 종목마다 재생세션·분봉 조회 2회, 코인은 종목마다 Redis 연결상태 조회가 반복된다. MVP 규모(계좌당 보유 수 적음)에서는 문제없지만, 합산 포트폴리오(#51)가 두 계좌(STOCK·CRYPTO)를 동시에 처리하며 호출 수가 배로 늘어난다. **#51 착수 전에 재생세션·연결상태 조회를 계좌(또는 요청) 단위로 배치화할지 검토한다.**
  - 응답값이 어떤 테이블에도 저장되지 않는지(평가값 미저장 요구사항) 간접 확인 — 동일 조회를 반복 호출해도 매번 최신 계산 결과가 나오는지(가격 변경 시나리오로 확인 가능하면 포함).

---

## 이슈 #52: 시장별 보유 종목 조회 API 구현 (PORT-001)

> **범위 안내**: 이 섹션은 `spec.md`의 PORT-001이 요구하는 **실제 조회 API**만 설계한다. 평가금액·미실현손익 계산 자체는 이슈 #47(`HoldingValuationService`, 병합됨)을 그대로 재사용한다 — 이번 이슈에서 계산식·반올림 규칙을 다시 결정하지 않는다. ACCT-003(합산 포트폴리오, #51)·PORT-002(거래내역, #82)는 각자 착수될 때 별도로 설계한다.

### 관련 문서

- Spec: `./spec.md` PORT-001 절
- PRD 근거: `docs/prd.md` PORT-001 (이번 이슈에서 현재가·수익률 필드를 추가해 갱신 — 문서 동기화 절 참고)
- 선행 절: 이 문서의 "이슈 #47" 절(`HoldingValuationService`/`HoldingValuationDto` 시그니처·반올림 규칙), "이슈 #81" 절 특히 "정책 수정 (PR #96 리뷰 차단 반영, 2026-07-30)" — 시세 무효 보유를 `costBasis`로 폴백하는 정책의 실제 판단 근거와 `AccountService.getAccountSummary` 구현 전례
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md) — 도메인 간 참조는 service 레이어를 통해서만. `order` 도메인이 이미 `account.service.AccountService`를 주입하는 전례(`OrderExecutionService`)가 있어, `portfolio` 도메인 서비스가 `AccountService`를 주입하는 것도 동일 패턴이다(양방향이지만 순환 아님 — 근거는 아래 "Service 설계" 참고).
- 선행 이슈: #12(원장 스키마, 병합됨), #47(평가 계산, 병합됨), #13·#41(매수·매도, 병합됨), #81(계좌 요약, 병합됨 — 시세 무효 정책 전례)

### 기존 구조 확인

| 대상 | 현재 상태 | 이번 변경 |
|---|---|---|
| `com.finplay.api.portfolio.repository.HoldingRepository` | `findAllByAccountIdAndIsActiveTrue(Long accountId)`(instrument `JOIN FETCH`) 이미 존재(#81) | 변경 없음 — 그대로 재사용. **전량 매도 종목 제외는 이 메서드의 `isActive = true` 조건으로 이미 해결되어 있다(추가 필터링 불필요)** |
| `com.finplay.api.portfolio.service.HoldingValuationService` | `evaluateHolding(Holding)`(단건), `evaluateActiveHoldingsForAccount(Long accountId)`(계좌 단위 목록, `HoldingValuationDto` 리스트만 반환 — `Holding` 엔티티 자체는 반환하지 않음) | `HoldingValuationDto`에 `currentPrice` 필드 추가(아래 "DTO 확장" 절) — 계산 로직·반올림 규칙은 변경 없음 |
| `com.finplay.api.account.service.AccountService` | `getAccountFor(userId, market)`(소유권 검증 포함, `BusinessException(NOT_FOUND)`) | 변경 없음 — 그대로 재사용 |
| `com.finplay.api.market.domain.Instrument` | `symbol`(String), `name`(String, 종목명) 필드 이미 존재(`@Getter`) | 변경 없음 |
| `com.finplay.api.portfolio.controller`, `com.finplay.api.portfolio.service.HoldingService` | 패키지·클래스 없음 | 신규 생성 |

### DTO 확장: `HoldingValuationDto.currentPrice` (기존 #47/#81 코드에 영향)

PORT-001·이슈 #52 본문이 요구하는 6개 값(수량·평균단가·**현재가**·평가금액·미실현손익·수익률) 중 "현재가"는 `HoldingValuationDto`에 아직 없다(계산에 쓰인 `PriceQuoteDto.price()`가 `evaluateHolding` 내부 지역 변수로만 존재하고 반환되지 않음). 두 방법을 검토했다.

1. `HoldingService`가 `evaluateHolding` 밖에서 `PriceQueryService.getPriceQuote`를 **한 번 더** 호출해 현재가를 별도로 구한다 — 보유 1건당 시세 조회가 2회로 늘어난다. plan.md "후속 검토 사항"이 이미 시세 조회 N+1을 우려하고 있는데(#51 대비), 이 방식은 그 부담을 #52에서 먼저 2배로 만든다.
2. **(채택)** `HoldingValuationDto`에 `currentPrice`(`BigDecimal`, nullable) 필드를 추가해 `evaluateHolding`이 이미 조회한 `quote.price()`를 그대로 실어 반환한다. 시세 조회 횟수는 그대로 1회다.

**변경 범위** (계산식·반올림·시세 무효 정책은 전혀 바꾸지 않는다 — 필드 1개 추가 노출뿐):

```java
public record HoldingValuationDto(
    BigDecimal quantity,
    BigDecimal averagePrice,
    long costBasis,
    PriceStatus priceStatus,
    BigDecimal currentPrice,   // 신규 — priceStatus == UNAVAILABLE이면 null, 아니면 quote.price() 그대로
    Long evaluationAmount,
    Long unrealizedPnl,
    BigDecimal returnRate) {
}
```

- `HoldingValuationService.evaluateHolding`의 두 `new HoldingValuationDto(...)` 호출(UNAVAILABLE 분기·AVAILABLE 분기) 모두 `currentPrice` 인자를 추가해야 컴파일된다(UNAVAILABLE 분기는 `null`, AVAILABLE 분기는 `quote.price()`).
- **이미 병합된 `src/test/java/com/finplay/api/account/service/AccountServiceTest.java`가 `new HoldingValuationDto(...)`를 4곳에서 직접 호출한다** — 필드 추가로 이 4곳 모두 인자를 하나씩 추가해야 컴파일이 깨지지 않는다(값은 테스트 의도에 맞게 임의로 채우거나 `null` — 이 테스트는 `currentPrice`를 검증하지 않으므로 아무 값이나 컴파일만 통과하면 된다). **implementer는 이 파일 수정을 빠뜨리지 않아야 한다.**
- `AccountService.getAccountSummary` 프로덕션 코드는 `valuation.priceStatus()`·`evaluationAmount()`·`unrealizedPnl()`·`costBasis()`만 named accessor로 읽으므로 이 확장에 영향받지 않는다(회귀 없음 — `./gradlew build`로 확인).

### 시세 무효 종목의 개별 필드 표현 정책 확정 (spec.md 미결 사항 — 이 이슈에서 결정, #81과 다른 결론)

`HoldingValuationDto`가 `priceStatus == UNAVAILABLE`일 때 `currentPrice`·`evaluationAmount`·`unrealizedPnl`·`returnRate`를 이미 `null`로 반환한다(#47 정책, 변경 없음). 이 4개 필드를 **개별 종목 목록 응답에서 어떻게 노출할지**를 결정한다.

- **채택: `null` 그대로 노출한다.** `#81`(계좌 요약)의 "시세 무효 보유는 `costBasis`를 폴백값으로 합산에 포함" 정책을 이 API의 개별 필드에는 그대로 적용하지 않는다.
- **근거 (계산 재사용과 표현 정책은 별개 결정이다)**:
  - `#81`의 폴백 정책은 **집계값(단일 숫자) 왜곡**을 막기 위한 것이었다 — 장 마감 중 전종목이 동시에 `UNAVAILABLE`이 되면 "총 보유자산이 사라진 것처럼" 보이는 문제(PR #96 QA 재현)를 막는 게 목적이었고, 그 문제는 "여러 값을 하나로 합칠 때" 발생한다.
  - `#52`는 **종목 단위 목록**이다. 만약 특정 종목의 `evaluationAmount`를 `costBasis`로, `unrealizedPnl`을 `0`으로 채워 넣으면, 화면은 "이 종목은 매수가 대비 손익이 정확히 0원(수익률 0%)"이라는 **구체적이고 틀린 사실**을 하나의 종목에 대해 단정하게 된다. 이는 `#81`이 막으려던 "자산이 사라진 것처럼 보이는" 왜곡보다 더 나쁘다 — 집계 왜곡은 흐릿하게 섞이지만, 종목별 단정은 특정 종목의 실제 성과에 대한 명시적 거짓 신호가 된다.
  - `#47` plan 절이 이미 "무효 종목을 목록에서 어떻게 표시할지는 각 API의 책임"이라고 위임했다 — `#81`은 집계 문맥에서 결정했고, `#52`는 종목별 문맥에서 별도로 결정하는 것이 그 위임 취지에 맞는다.
- **`priceStatus`를 응답에 그대로 노출한다** (`String`, `"AVAILABLE"`\|`"UNAVAILABLE"`, 신규 필드) — 프론트가 "평가금액 0원"(실제 0)과 "시세 조회 불가로 알 수 없음"(`null`)을 구분해 표시하게 하기 위함이다. 이 필드가 없으면 프론트가 `null`을 임의로 0 취급해 결국 `#81`이 막으려던 것과 동일한 왜곡(이번엔 종목 단위)을 스스로 만들 위험이 있다.
- `quantity`·`averagePrice`·`costBasis`는 시세와 무관하게 항상 채워진다(원장 값·계산이므로) — 시세 무효 여부와 상관없이 그대로 노출한다. **다만 이번 응답 DTO는 `costBasis`(원가, 원단위)를 별도 필드로 노출하지 않는다** — spec·이슈 #52 본문이 요구한 6개 값(수량·평균단가·현재가·평가금액·미실현손익·수익률)에 원가가 포함되지 않고, `costBasis`는 `averagePrice`·`quantity`로 프론트에서 재계산 가능한 파생값이라 중복 노출하지 않는다(응답 필드 최소화).

### 경계 케이스 표 (spec 완료 조건 근거)

| 케이스 | quantity/averagePrice | currentPrice | evaluationAmount | unrealizedPnl | returnRate | priceStatus |
|---|---|---|---|---|---|---|
| 시세 유효, 정상 보유 | 값 있음 | 값 있음 | 값 있음 | 값 있음(양수/음수) | 값 있음 | `AVAILABLE` |
| 시세 무효(장 마감 등) | 값 있음 | `null` | `null` | `null` | `null` | `UNAVAILABLE` |
| 전량 매도(`isActive=false`) | — (목록에서 아예 제외, `HoldingRepository.findAllByAccountIdAndIsActiveTrue`가 DB 단에서 필터) | — | — | — | — | — |
| 보유 종목 없음 | — | — | — | — | — | 200 빈 배열 |
| 타인 보유 | — | — | — | — | — | 응답에 나타나지 않음(소유권 검증) |

### API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | /api/holdings?market={STOCK\|CRYPTO} | 인증만(Access Bearer), 쿼리 파라미터 `market` 필수 | `HoldingListItemResponse[]` | 인증 사용자 본인의 해당 시장 계좌가 보유한 활성 종목 목록(수량·평균단가·현재가·평가금액·미실현손익·수익률 + 종목 표시 정보) 반환 |

- 인증: `@AuthenticationPrincipal AuthenticatedUser`에서 `userId`를 얻는다. 경로·쿼리에 계좌·보유 식별자를 받지 않는다 — `#81`(`GET /api/accounts/summary?market=`)과 동일하게 "요청에서 대상을 받지 않는" 패턴이라 타인 보유 조회 자체가 불가능한 구조다.
- 보유 종목이 없으면 예외 없이 200과 빈 배열을 반환한다(spec 완료 조건, 이슈 #52 수용 기준).

### 입력 명세

| 파라미터 | 위치 | 타입 | 필수 | 검증 | 근거 |
|---|---|---|---|---|---|
| `market` | 쿼리 | `com.finplay.api.account.domain.Market`(enum: `STOCK`\|`CRYPTO`) — **`market.domain.Market`이 아니다**(#81 plan.md "Market 타입 주의" 절과 동일 이유: `accountService.getAccountFor(userId, market)` 호출부와 타입을 맞춰야 컴파일된다) | 필수 | Spring이 쿼리 파라미터를 enum으로 바인딩 — 생략 시 `MissingServletRequestParameterException`, `STOCK`\|`CRYPTO`가 아닌 문자열은 `MethodArgumentTypeMismatchException`. **둘 다 이미 `GlobalExceptionHandler.handleBadRequest`가 400 `VALIDATION_ERROR`로 매핑한다** — 컨트롤러에 별도 400 처리 코드를 추가하지 않는다(#81과 동일 근거·동일 기존 테스트로 이미 검증된 공통 동작) | 이슈 #52 수용 기준 "market 누락·잘못된 값 400" |

- 요청 본문 없음.

### 응답 DTO 설계

`com.finplay.api.portfolio.dto.response.HoldingListItemResponse` (record, 목록 항목 응답 접미사 규칙 준수).

| 필드 | 타입 | 근거 |
|---|---|---|
| instrumentId | Long | `holding.getInstrument().getId()` |
| symbol | String | `holding.getInstrument().getSymbol()` — 종목 표시 정보(이슈 #52 "화면이 추가 조회를 하지 않게" 요구사항) |
| name | String | `holding.getInstrument().getName()` — 종목명 |
| quantity | BigDecimal | `valuation.quantity()` |
| averagePrice | BigDecimal | `valuation.averagePrice()` |
| currentPrice | BigDecimal (nullable) | `valuation.currentPrice()` — 시세 무효 시 `null` |
| evaluationAmount | Long (nullable) | `valuation.evaluationAmount()` — 시세 무효 시 `null` |
| unrealizedPnl | Long (nullable) | `valuation.unrealizedPnl()` — 시세 무효 시 `null` |
| returnRate | BigDecimal (nullable) | `valuation.returnRate()` — 시세 무효 시 `null` |
| priceStatus | String | `valuation.priceStatus().name()` — `"AVAILABLE"`\|`"UNAVAILABLE"`, 위 "시세 무효 종목의 개별 필드 표현 정책" 근거 |

- 정적 팩토리 `HoldingListItemResponse.from(Holding holding, HoldingValuationDto valuation)` (인자 2개 조합이므로 `of(...)`가 아니라 컨벤션의 `from(entity)` 확장 형태 — 엔티티+계산결과 조합이라 `from`이 자연스럽다. 팀 컨벤션이 `from`/`of` 이름을 엄격히 구분하지 않으므로 이 이름을 확정한다).
- `market` 필드는 포함하지 않는다 — 이미 요청 쿼리 파라미터로 필터링된 단일 값이라 행마다 반복할 필요가 없다(프론트가 요청한 `market` 값을 이미 알고 있음). `OrderListItemResponse`가 `market`을 포함하는 이유(주문은 필터링 없이 여러 시장이 섞여 반환됨)와 이 API는 상황이 다르다.
- `costBasis`는 노출하지 않는다(위 "시세 무효 종목의 개별 필드 표현 정책" 절 마지막 문단 근거).

### Service 설계

신규 `com.finplay.api.portfolio.service.HoldingService` — `HoldingValuationService`(계산, portfolio 도메인)와 별개로 둔다. `HoldingValuationService`는 "보유 1건 평가"라는 순수 계산 책임만 유지하고(#47 원 설계 의도), "본인 계좌 조회 + 활성 보유 조회 + 응답 조립"이라는 이번 API 전용 유스케이스는 새 서비스에 둔다(`AccountService`가 `HoldingValuationService`를 감싸 `getAccountSummary`라는 API 전용 유스케이스를 만든 것과 동일한 패턴).

```java
@Service
@RequiredArgsConstructor
public class HoldingService {

    private final AccountService accountService;
    private final HoldingRepository holdingRepository;
    private final HoldingValuationService holdingValuationService;

    @Transactional(readOnly = true)
    public List<HoldingListItemResponse> getHoldings(Long userId, Market market) {
        Account account = accountService.getAccountFor(userId, market);
        return holdingRepository.findAllByAccountIdAndIsActiveTrue(account.getId()).stream()
            .map(holding -> HoldingListItemResponse.from(holding, holdingValuationService.evaluateHolding(holding)))
            .toList();
    }
}
```

- **왜 `evaluateActiveHoldingsForAccount`(#81이 추가한 계좌 단위 배치 메서드)를 재사용하지 않는가**: 그 메서드는 `List<HoldingValuationDto>`만 반환하고 원본 `Holding`(즉 `instrument`의 `symbol`·`name`)을 함께 반환하지 않는다. `#52`는 종목 표시 정보가 필수라 `Holding` 엔티티 자체가 필요하므로, 이미 존재하는 두 원시 메서드(`findAllByAccountIdAndIsActiveTrue`+`evaluateHolding`)를 직접 조합한다 — `evaluateActiveHoldingsForAccount`를 억지로 재사용하려고 그 메서드의 반환 타입을 바꾸면(예: `Holding` 동반 반환) `#81`의 기존 호출부·테스트에도 영향을 준다. 현재 조합이 기존 코드에 대한 영향을 최소화한다(수정 대상은 `HoldingValuationDto` 필드 추가 하나뿐).
- 두 원시 메서드 모두 이미 병합된 코드이고 시그니처가 바뀌지 않으므로 `#81`에 대한 회귀 위험이 없다.
- **`portfolio` 도메인이 `account` 도메인의 `AccountService`를 주입하는 것에 대해**: ADR-0002가 금지하는 것은 "다른 도메인의 **repository**를 직접 참조"뿐이고 service 간 참조는 허용된다. `order` 도메인의 `OrderExecutionService`가 이미 `AccountService`를 주입하는 기존 전례가 있다(`import com.finplay.api.account.service.AccountService`). `account` 도메인도 `HoldingValuationService`(portfolio)를 주입하므로(#81) 두 도메인이 서로 다른 서비스 쌍으로 양방향 참조하지만, `AccountService`가 `HoldingService`를 참조하지 않고 `HoldingService`가 `AccountService`를 참조하는 단방향 빈 그래프라 Spring 순환 빈 의존성은 발생하지 않는다.
- 소유권 검증: `accountService.getAccountFor(userId, market)`가 이미 `BusinessException(NOT_FOUND)`를 던지는 소유권 검증을 포함하므로 별도 분기 없이 재사용한다(#81과 동일 근거).

### Controller 설계

```java
package com.finplay.api.portfolio.controller;

import com.finplay.api.account.domain.Market; // market.domain.Market이 아님
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.portfolio.dto.response.HoldingListItemResponse;
import com.finplay.api.portfolio.service.HoldingService;

@RestController
@RequestMapping("/api/holdings")
@RequiredArgsConstructor
public class HoldingController {

    private final HoldingService holdingService;

    @GetMapping
    public ResponseEntity<List<HoldingListItemResponse>> getHoldings(
        @AuthenticationPrincipal AuthenticatedUser principal,
        @RequestParam Market market) {
        return ResponseEntity.ok(holdingService.getHoldings(principal.userId(), market));
    }
}
```

- 새 패키지 `com.finplay.api.portfolio.controller`를 만든다(portfolio 도메인에 컨트롤러가 없었음).
- `@RequestParam`에 기본값을 두지 않는다 — 생략 시 400이 나와야 하므로 `required = true`(기본값)를 그대로 둔다.

### 데이터 모델

없음 — 신규 컬럼·마이그레이션 불필요. 기존 `holdings`·`instruments` 테이블을 조회만 한다. 평가값(`currentPrice`·`evaluationAmount`·`unrealizedPnl`·`returnRate`)은 어디에도 저장하지 않는다(spec 비즈니스 규칙).

### 문서 동기화

같은 커밋에서 갱신(CLAUDE.md 규칙 7 + 이슈 #52 본문 요구):

- `docs/prd.md` PORT-001 절에 현재가·수익률 필드를 추가한다 — 현재 "평가금액과 미실현손익은 최신 시세로 계산한다." 문장에 현재가·수익률도 반환 대상임을 포함하도록 갱신.
- `docs/api-routes.md`: 라우트 표에 `GET | /api/holdings?market= | portfolio | ... | 006 PORT-001, Issue #52` 행 추가.
- `docs/api-contracts.md`: 새 `## portfolio` 절 신설(이 API가 portfolio 도메인 최초 컨트롤러) — 요청(쿼리 `market` 필수), 성공 200 예시(`HoldingListItemResponse[]`, 시세 유효/무효 각 1건 포함), 오류(400 `VALIDATION_ERROR`, 401 `UNAUTHORIZED`) 표. 시세 무효 항목의 4개 필드가 `null`이고 `priceStatus`로 구분됨을 본문에 명시(위 "표현 정책" 근거를 요약 인용).

### 테스트 계획 (ADR-0003 기준)

- **단위 — `HoldingValuationServiceTest`(기존 파일)**: `currentPrice` 필드 확장에 대한 회귀 테스트 추가 — 이익/손실 케이스에서 `currentPrice == quote.price()`인지, `UNAVAILABLE` 케이스에서 `currentPrice == null`인지 검증(기존 4개 계산 필드 검증과 함께).
- **단위 — `AccountServiceTest`(기존 파일)**: `new HoldingValuationDto(...)` 4곳의 컴파일 수정만 필요(값 추가) — 기존 검증 로직·기대값은 변경하지 않는다(회귀 확인 목적, 새 테스트 케이스 추가 아님).
- **단위 — `HoldingServiceTest`(신규)**: Mockito로 `AccountService.getAccountFor`·`HoldingRepository.findAllByAccountIdAndIsActiveTrue`·`HoldingValuationService.evaluateHolding`을 stub.
  - 활성 보유 2건(시세 유효 1건 + 무효 1건 혼합) → 각각 `HoldingListItemResponse` 필드가 정확히 매핑되는지, 무효 건은 4개 필드가 `null`이고 `priceStatus="UNAVAILABLE"`인지 실제 값으로 검증(mock 응답 객체 금지 컨벤션).
  - 활성 보유 없음 → 빈 리스트 반환.
  - `getAccountFor`가 `BusinessException(NOT_FOUND)`를 던지면 그대로 전파되는지(계좌 없음 극단 케이스 회귀).
- **슬라이스 API — `HoldingControllerTest`(신규, `@WebMvcTest`)**: `AccountControllerTest` 패턴(`@Import(SecurityConfig.class)`, `MockitoBean JwtTokenProvider`) 재사용.
  - `market=STOCK`·`market=CRYPTO` 각각 200과 `jsonPath`로 10개 필드 계약 검증(보유 1건 이상 stub).
  - 보유 없음 stub → 200 빈 배열.
  - `market` 쿼리 파라미터 누락 → 400 `VALIDATION_ERROR`.
  - `market=FOREX`(미지원 리터럴) → 400 `VALIDATION_ERROR`.
  - 인증 실패(Authorization 헤더 없음) → 401 `UNAUTHORIZED`.
- **통합 — Testcontainers(신규 `HoldingIntegrationTest` 또는 기존 매수 통합 테스트 파일 인접)**:
  - 매수 API로 2종목 매수 후 그중 1종목을 매도 API로 전량 매도 → `GET /api/holdings?market=` 호출 → 전량 매도한 종목이 목록에서 제외되고 남은 1종목의 6개 값(수량·평균단가·현재가·평가금액·미실현손익·수익률)이 원장·최신 시세 기준으로 정확한지 검증(spec 완료 조건 "보유 종목별 여섯 값이 원장·최신 시세 기준 정확", "전량 매도한 종목이 목록에 안 나타남"의 직접 구현).
  - 보유 종목이 없는 신규 계좌 → 200 빈 배열.
  - 타인 계좌에 매수 후 본인 계좌 조회 시 타인 보유가 섞이지 않는지 확인.
  - `market` 누락·잘못된 값 400, 비로그인 401 (컨트롤러 슬라이스와 별개로 통합 레벨에서 최소 1건 확인 — 나머지 조합은 슬라이스 테스트가 촘촘히 커버).
  - (가능하면) 시세가 무효한 종목을 보유한 상황에서도 목록 조회 자체가 실패하지 않고 해당 종목만 4개 필드가 `null`로 응답되는지 확인 — 어렵다면 `HoldingServiceTest`의 무효 케이스 단위 검증으로 대체 가능(통합 테스트에서 시세를 인위적으로 무효화하기 어려우면 단위 테스트로 충분).

---

## 이슈 #82: 시장별 체결 내역 조회 API 구현 (PORT-002)

> **범위 안내**: 이 섹션은 `spec.md`의 PORT-002가 요구하는 **실제 조회 API**만 설계한다. 시세 조회·평가 계산(`PriceQueryService`·`HoldingValuationService`)은 이 API와 무관하다 — 체결 원장(`trades` 테이블)을 그대로 읽어 반환할 뿐 "지금 얼마짜리인지"를 계산하지 않는다. ACCT-003(합산 포트폴리오, #51)은 이 이슈와 무관하며 각자 착수될 때 별도로 설계한다.

### 관련 문서

- Spec: `./spec.md` PORT-002 절
- PRD 근거: `docs/prd.md` PORT-002 (커서 포맷·정렬 기준은 이미 "2026-07-28 팀 결정"으로 고정, 이번 이슈는 실제 구현만 확정)
- 선행 절: 이 문서의 "이슈 #21(PORT-003 주문 목록)" 절 — `OrderListItemResponse` 필드 계약과 "체결 전용 필드는 거래내역이 소유한다"는 분리 원칙의 상대편 문서, "이슈 #81"·"이슈 #52" 절 — `account` 도메인 `AccountService.getAccountFor(userId, market)`를 재사용해 계좌 소유권+시장 스코프를 해결하는 전례
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(도메인 간 참조는 service 레이어만 — `order` 도메인의 신규 `TradeService`가 `account` 도메인의 `AccountService`를 주입하는 것은 `OrderExecutionService`가 이미 쓰는 전례와 동일한 패턴), [ADR-0003](../../adr/0003-testing-strategy.md)
- 선행 이슈: #12(원장 스키마, 병합됨), #13·#41(매수·매도로 `trades` 실데이터 생성, 병합됨), #21(주문 목록, 병합됨 — 필드 분리 기준), #81·#52(계좌 요약·보유 종목, 병합됨 — `AccountService.getAccountFor` 재사용 전례)

### 기존 구조 확인

| 대상 | 현재 상태 | 이번 변경 |
|---|---|---|
| `com.finplay.api.order.domain.Trade` | `id`·`order`(FK)·`account`(FK)·`instrument`(FK)·`side`·`price`(precision 18 scale 8)·`quantity`(precision 30 scale 8)·`amount`(long)·`fee`(long)·`realizedPnl`(Long, nullable)·`executedAt`(`LocalDateTime`)·`createdAt` 이미 존재(#13·#41). `fillRealizedPnl`이 매도 체결 시 한 번만 채운다 | 변경 없음 — 그대로 조회만 한다 |
| `db/migration/V10__create_order_ledger_tables.sql` | `trades.executed_at DATETIME(6)`(마이크로초 정밀도, 6자리 소수점) — **머지된 마이그레이션이라 컬럼 자체는 수정 불가(ADR-0004)** | 변경 없음. 아래 "커서 설계" 절에서 이 정밀도를 그대로 근거로 쓴다 |
| `com.finplay.api.order.repository.TradeRepository` | `JpaRepository` 상속 + `findByOrderId(Long)`만 존재(멱등성 재조회용) | `TradeRepositoryCustom`/`TradeRepositoryImpl`(QueryDSL) 추가 — 계좌 단위 커서 조회 |
| `com.finplay.api.community.repository.CommunityPostRepositoryImpl`, `com.finplay.api.common.QuerydslConfig` | 이 코드베이스의 유일한 QueryDSL 커스텀 리포지토리 전례(`JPAQueryFactory` 직접 생성자 주입 방식, `CommunityPostRepositoryCustom` 인터페이스 분리) | 이번 `TradeRepositoryImpl`도 동일 전례(생성자에서 `new JPAQueryFactory(entityManager)`)를 따른다 — `docs/conventions.md` "QueryDSL 사용 기준"의 "커서 페이지네이션·다중 조인 목록 조회"에 정확히 해당 |
| `com.finplay.api.account.service.AccountService.getAccountFor(Long userId, Market market)` | 소유권 검증 포함, `BusinessException(NOT_FOUND)` (#81) | 변경 없음 — 그대로 재사용. `TradeService`가 이 메서드로 먼저 `Account`를 얻은 뒤 `account.getId()`로 `trades`를 필터링한다(시장 스코프와 소유권 검증을 한 번에 해결) |
| `com.finplay.api.order.service.OrderService` | 주문 생성 + `getMyOrders`(#21) 담당, 이미 `TradeRepository`를 주입(멱등성 재조회용) | **신규 `TradeService`를 별도 클래스로 분리한다** — 아래 "Service 설계" 근거 참고. `OrderService`는 변경하지 않는다 |
| `com.finplay.api.order.controller` | `OrderController`(`POST`·`GET /api/orders`)만 존재 | 신규 `TradeController`(`GET /api/trades`) 추가 — 별도 리소스이므로 `OrderController`에 메서드를 얹지 않는다 |
| `com.finplay.api.community.controller.CommunityPostController` | `page`/`size` 쿼리를 컨트롤러 상수(`DEFAULT_SIZE`·`MIN_SIZE`·`MAX_SIZE`)로 직접 검증하고 범위를 벗어나면 `BusinessException(VALIDATION_ERROR)`를 던짐(클램핑하지 않음) | 이번 `limit` 검증도 동일 전례를 따른다(아래 "limit 기본값·상한값 확정" 절) |

### 커서 설계 및 근거 확정 (사람이 이미 정한 포맷을 실제 값으로 구체화)

사람이 이미 확정한 것: 커서는 `{체결시각}_{id}` 평문 문자열이고, 정렬·필터 조건은 "`executedAt < cursor시각` 또는 (`executedAt = cursor시각` 그리고 `id < cursor id`)"다. 아래는 이번에 확정해야 하는 부분이다.

1. **정밀도 확인**: `V10__create_order_ledger_tables.sql`의 `trades.executed_at`은 `DATETIME(6)` — MySQL이 지원하는 최대 소수점 자리수인 마이크로초(6자리)까지 저장한다. 엔티티 필드는 `LocalDateTime`(나노초 필드를 갖지만) 이미 DB에 저장된 값을 다시 읽어온 것이므로 실제로는 마이크로초 이하 자리는 항상 0이다 — DB 왕복을 거친 값이라 정밀도 손실 위험은 "커서 문자열로 왕복할 때"가 아니라 "저장할 때" 이미 결정돼 있고, 이 이슈는 그 이후(저장된 값을 다시 문자열로 내보내고 파싱하는) 단계만 다룬다.
2. **포맷 확정**: `java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME`을 명시적으로 사용한다(`LocalDateTime.toString()`의 암묵적 동작에 기대지 않는다). 근거:
   - `ISO_LOCAL_DATE_TIME`은 초 단위까지는 항상 고정 자리, 나노초 부분은 0이 아닐 때만 가변 자리(0~9자리)로 출력한다 — 예시 `2026-07-18T10:00:00`(나노초 0일 때, 오케스트레이터 지시의 예시 커서 형식과 정확히 일치), 나노초가 있으면 `2026-07-18T10:00:00.123456`처럼 실제 자리수만큼 출력.
   - `format()`과 `parse()`가 같은 포매터라 왕복이 정확히 역함수 관계다 — 어떤 자리수로 출력되든 같은 포매터로 다시 파싱하면 원래 `LocalDateTime` 값과 100% 동일하게 복원된다(1번 근거로 이미 저장된 값이 마이크로초 이하로 확정돼 있으므로 정보 손실이 발생할 여지가 없다).
   - `toString()`도 이 예시에서는 같은 결과를 내지만, `toString()`은 "3자리/6자리/9자리 그룹" 규칙이 코드에 명시돼 있지 않고 자바 버전에 따라 문서화된 세부 동작을 매번 확인해야 하는 반면, `ISO_LOCAL_DATE_TIME`은 API 계약으로 명시된 표준 포매터라 이 커서 포맷의 안정성을 코드 밖에서도 근거를 댈 수 있다.
3. **문자열 분리**: `{체결시각}_{id}`를 마지막 `_`(`lastIndexOf('_')`) 기준으로 분리한다 — ISO 8601 날짜·시간 표현은 숫자·`-`·`:`·`.`·`T`만 사용하고 `_`를 포함하지 않으므로 이 분리가 항상 안전하다.
4. **손상된 커서**: 사람이 이미 결정한 대로 400 `VALIDATION_ERROR`. 분리 실패(구분자 없음·구분자가 맨 끝), 날짜 파싱 실패(`DateTimeParseException`), id 파싱 실패(`NumberFormatException`) 세 가지 모두 이 하나의 오류로 수렴한다(내부 원인을 구분해 노출하지 않음 — PRD 공통 오류 계약이 코드·메시지만 규정).
5. **커서 없음(첫 페이지)**: `cursor` 쿼리 파라미터 자체가 없거나 빈 문자열이면 필터 조건 없이(계좌 스코프만 적용) 최신순 첫 페이지를 반환한다 — 파싱 실패(400)와 다른 경로다.

### `limit` 기본값·상한값 확정 (spec.md 미결 사항 — 이 이슈에서 결정)

- **기본값 20, 최솟값 1, 최댓값 100**으로 확정한다.
  - 기본값 20: 계좌 요약·보유 종목 화면과 달리 거래내역은 스크롤/무한 로딩 화면에 쓰일 가능성이 높은 목록이라 한 페이지 항목 수를 조금 더 넉넉히 잡는다(커뮤니티 게시물 `DEFAULT_SIZE=10`보다 크게).
  - 최댓값 100: 커뮤니티 목록의 `MAX_SIZE=50`보다 넉넉히 잡는다 — 거래내역은 텍스트가 아니라 숫자 필드 위주라 행당 응답 크기가 작고, 프론트가 기간별 다운로드처럼 한 번에 더 많이 가져오는 사용 패턴을 배제하지 않기 위함. 다만 무제한은 아니다(원장이 계속 쌓이는 테이블이라 상한 없이 전체를 반환하면 응답 크기·쿼리 비용이 무한정 커짐 — PORT-003처럼 페이지네이션을 아예 생략할 수 없는 이유이기도 하다).
- **상한 초과·최솟값 미만 처리 방식: 클램핑하지 않고 400 `VALIDATION_ERROR`.** 근거: 이 코드베이스의 유일한 기존 전례인 `CommunityPostController.validatePageAndSize`가 이미 범위를 벗어난 `size`를 클램핑 대신 명시적으로 거부한다(조용히 다른 개수로 바꿔치기하면 클라이언트가 "요청한 개수와 실제 받은 개수가 다른" 상황을 스스로 감지해야 해서 더 혼란스럽다). 이번 `limit`도 같은 코드베이스 관례를 따라 컨트롤러에서 동일한 방식(상수 + 명시적 검증 메서드 + `BusinessException(VALIDATION_ERROR)`)으로 구현한다.

### API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | /api/trades?market=&cursor=&limit= | 인증만(Access Bearer), 쿼리 `market` 필수, `cursor`·`limit` 선택 | `TradeListResponse` | 인증 사용자 본인의 해당 시장 계좌 체결내역을 `executedAt` 내림차순(동시각은 `id` 내림차순)으로 커서 페이지네이션 조회 |

- 인증: `@AuthenticationPrincipal AuthenticatedUser`에서 `userId`를 얻는다. 경로·쿼리에 계좌·체결 식별자를 받지 않는다 — `#81`·`#52`와 동일하게 "요청에서 대상을 받지 않는" 패턴이라 타인 체결내역 조회 자체가 불가능한 구조다.
- 체결내역이 없으면(신규 계좌 등) 예외 없이 200과 빈 배열(`content: []`, `hasNext: false`, `nextCursor: null`)을 반환한다(spec 완료 조건).

### 입력 명세

| 파라미터 | 위치 | 타입 | 필수 | 검증 | 근거 |
|---|---|---|---|---|---|
| `market` | 쿼리 | `com.finplay.api.account.domain.Market`(enum: `STOCK`\|`CRYPTO`) — **`market.domain.Market`이 아니다**(`#81`·`#52`와 동일 이유: `accountService.getAccountFor(userId, market)` 호출부와 타입을 맞춰야 컴파일된다) | 필수 | 생략·미지원 리터럴 모두 Spring이 `MissingServletRequestParameterException`·`MethodArgumentTypeMismatchException`을 던지고 기존 `GlobalExceptionHandler`가 이미 400 `VALIDATION_ERROR`로 매핑한다(컨트롤러에 별도 코드 불필요, `#81`·`#52`와 동일 근거) | spec PORT-002 "market은 필수 파라미터" |
| `cursor` | 쿼리 | `String` | 선택(생략 시 첫 페이지) | 생략·빈 문자열은 첫 페이지로 처리. 값이 있는데 `{ISO_LOCAL_DATE_TIME}_{id}` 형식으로 파싱 실패하면 `TradeCursor.parse`가 `BusinessException(VALIDATION_ERROR)`를 던진다(서비스 계층에서 처리 — 위 "커서 설계" 절 근거) | 사람이 이미 결정: 손상된 커서는 400 |
| `limit` | 쿼리 | `int` | 선택(기본값 20) | 1~100 범위를 벗어나면 컨트롤러의 `validateLimit`이 `BusinessException(VALIDATION_ERROR)`를 던진다(클램핑 없음 — 위 "limit 기본값·상한값 확정" 절 근거) | 이번 이슈에서 확정 |

- 요청 본문 없음.

### 응답 DTO 설계

두 개의 record — `com.finplay.api.order.dto.response.TradeListItemResponse`(목록 항목)와 `com.finplay.api.order.dto.response.TradeListResponse`(목록 + 페이지 메타, `CommunityPostListResponse` 전례와 동일하게 컨텐츠+메타를 한 응답으로 감싼다).

**`TradeListItemResponse`**

| 필드 | 타입 | 근거 |
|---|---|---|
| tradeId | Long | `Trade.id` |
| instrumentId | Long | `Trade.instrument.id` |
| side | String | `Trade.side.name()` — 매수·매도 구분(아래 "필드 대조표"에서 중복이 아님을 근거와 함께 설명) |
| price | BigDecimal | `Trade.price` — 체결가격 |
| quantity | BigDecimal | `Trade.quantity` — 체결수량 |
| amount | long | `Trade.amount` — 거래금액 |
| fee | long | `Trade.fee` — 수수료 |
| realizedPnl | Long (nullable) | `Trade.realizedPnl` — 매도만 값 존재, 매수는 `null`(엔티티가 이미 그렇게 채워짐, #41) |
| executedAt | LocalDateTime | `Trade.executedAt` — 체결시각 |

- 정적 팩토리 `TradeListItemResponse.from(Trade trade)`.

**`TradeListResponse`**

| 필드 | 타입 | 근거 |
|---|---|---|
| content | List\<TradeListItemResponse\> | 이번 페이지 항목 |
| nextCursor | String (nullable) | 다음 페이지 요청에 그대로 붙일 커서 문자열, 마지막 페이지면 `null` |
| hasNext | boolean | 다음 페이지 존재 여부 |

- 정적 팩토리 `TradeListResponse.of(List<TradeListItemResponse> content, String nextCursor, boolean hasNext)`.
- `CommunityPostListResponse`처럼 컴팩트 생성자에서 `content = List.copyOf(content)`로 방어적 불변화.

### 필드 대조표 — `OrderListItemResponse`(#21)와 중복 여부 확인 (spec 요구사항 "필드 절대 중복 금지" 근거)

| `OrderListItemResponse`(#21) 필드 | `TradeListItemResponse`(#82)에 있는가 | 판단 |
|---|---|---|
| orderId | 없음 | 이번 API는 요구되지 않은 상관관계 식별자를 추가하지 않는다(스펙 미요구, 응답 최소화) |
| market | 없음 | `#52`(보유 종목)와 동일 근거 — 쿼리로 이미 단일 시장 필터링됐으므로 행마다 반복하지 않는다 |
| instrumentId | **있음** | 중복이 아니라 필수 식별자 — "종목의 요청 정보"(주문)와 "종목의 체결 정보"(거래)는 서로 다른 리소스이고 각자 "어느 종목 얘기인지"를 밝혀야 해석 가능하다. 값이 같은 종목을 가리켜도 두 리소스가 독립적으로 자기 식별자를 노출하는 것은 정상 설계(외래키 성격의 식별자는 스펙이 금지한 "체결 정보 중복"의 대상이 아니다) |
| side | **있음** | 위와 동일 근거(분류 정보) — 매수·매도 구분 없이는 `realizedPnl`이 왜 `null`인지 프론트가 해석할 수 없다 |
| orderType | 없음 | "어떤 유형으로 주문했는가"는 요청 정보이자 `#21`의 책임. 체결 결과와 무관 |
| status | 없음 | 거래내역의 각 행은 이미 "체결 완료"를 의미하므로 별도 상태 필드가 불필요(1차 범위는 즉시 전량 체결만 존재) |
| quantity | **이름은 같지만 의미가 다름** | `#21.quantity`는 "요청수량"(주문 시점), `#82.quantity`는 "체결수량"(원장 값, `Trade.quantity`). 1차 MVP는 시장가 즉시 전량 체결이라 두 값이 항상 같지만, 개념적으로 서로 다른 시점의 값이라 별도 필드로 각자의 리소스가 갖는 것이 옳다(하나가 다른 하나의 파생값이 아님 — 지정가·부분체결이 2차에 도입되면 값이 달라질 수 있는 구조) |
| requestedAt | 없음(대신 `executedAt`) | "요청시각"(주문)과 "체결시각"(거래)은 서로 다른 시점의 값 — spec이 명시적으로 분리를 요구한 필드 쌍이며 이름도 의도적으로 다르다 |
| (없음) | price·amount·fee·realizedPnl | `#21`이 "체결 전용 필드는 포함하지 않는다"고 명시적으로 제외한 필드들 — 이 API가 유일한 소유자다. **이 4개 + executedAt이 이번 이슈가 존재하는 핵심 이유다** |

결론: `instrumentId`·`side`는 두 API에 공통으로 나타나지만 "체결 정보"(가격·금액·수수료·손익·체결시각)의 중복이 아니라 각 리소스가 자기 자신을 식별·분류하기 위한 최소 정보다. spec이 금지하는 중복은 후자(체결 정보)이며, 이번 설계는 그 다섯 필드(price·amount·fee·realizedPnl·executedAt)를 `#82`만 갖도록 했다.

### 페이지 연속성(중복·누락 없음) 근거 (spec 완료 조건 "페이지를 넘겨도 중복·누락이 없어야 한다")

- 정렬 기준과 커서 필터 조건이 정확히 같은 축(`executedAt` 내림차순, 동시각 `id` 내림차순)이고, 필터 조건("이 시각보다 이전이거나 같은 시각이면서 이 id보다 작은 것")이 "정렬 순서상 커서 이후(더 과거)의 모든 행"과 정확히 일치하는 여집합 관계다 — 즉 이전 페이지의 마지막 행 바로 다음 순번부터 정확히 이어받는다. 경계에서 겹치거나(중복) 건너뛰는(누락) 지점이 수학적으로 없다.
- `trades` 테이블은 append-only 원장이다(#41 설계) — 한 행이 생성된 뒤 정렬·필터에 쓰이는 두 컬럼(`executed_at`, `id`)은 어떤 이후 연산으로도 변경되지 않는다(`Trade.fillRealizedPnl`은 `realized_pnl` 한 컬럼만 한 번 채우고, `executed_at`·`id`는 건드리지 않는다). 따라서 페이지를 여러 번 나눠 조회하는 동안 이미 반환된 행의 정렬 위치가 바뀌어 순서가 흔들릴 여지가 없다.
- 페이지 조회 도중 새 체결이 쌓여도(동시 매수·매도) 새 행은 항상 "지금 시각"에 생성되므로 이미 지나간(더 과거로 페이지가 진행된) 커서보다 항상 최신 쪽(정렬상 앞쪽)에 추가된다 — 사용자가 과거로 페이지를 넘기는 동안에는 영향받지 않는다(첫 페이지를 다시 조회하면 새 행이 보이는 것은 정상 동작이며 spec이 금지하는 "중복·누락"이 아니다).

### Repository 설계 (`order` 도메인, QueryDSL)

`docs/conventions.md` "QueryDSL 사용 기준"의 "커서 페이지네이션·다중 조인 목록 조회"에 정확히 해당한다(동적 조건: 첫 페이지 vs 커서 있는 페이지, 조인: `instrument`).

```java
public interface TradeRepositoryCustom {
	List<Trade> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorExecutedAt, Long cursorId, int fetchSize);
}
```

```java
public class TradeRepositoryImpl implements TradeRepositoryCustom {

	private final JPAQueryFactory queryFactory;

	public TradeRepositoryImpl(EntityManager entityManager) {
		this.queryFactory = new JPAQueryFactory(entityManager);
	}

	@Override
	public List<Trade> findByAccountIdWithCursor(
		Long accountId, LocalDateTime cursorExecutedAt, Long cursorId, int fetchSize) {
		QTrade trade = QTrade.trade;

		BooleanBuilder condition = new BooleanBuilder(trade.account.id.eq(accountId));
		if (cursorExecutedAt != null && cursorId != null) {
			condition.and(
				trade.executedAt.lt(cursorExecutedAt)
					.or(trade.executedAt.eq(cursorExecutedAt).and(trade.id.lt(cursorId))));
		}

		return queryFactory
			.selectFrom(trade)
			.join(trade.instrument).fetchJoin()
			.where(condition)
			.orderBy(trade.executedAt.desc(), trade.id.desc())
			.limit(fetchSize)
			.fetch();
	}
}
```

```java
public interface TradeRepository extends JpaRepository<Trade, Long>, TradeRepositoryCustom {

	Optional<Trade> findByOrderId(Long orderId); // 기존 메서드, 변경 없음
}
```

- `CommunityPostRepositoryImpl`과 동일 전례: 생성자에서 `EntityManager`로 `JPAQueryFactory`를 직접 만든다(`QuerydslConfig`가 제공하는 빈 주입도 가능하지만 기존 전례를 그대로 따른다).
- `instrument`를 `fetchJoin()`한다 — 응답이 `trade.getInstrument().getId()`를 읽으므로 N+1 방지(`#21`의 `Order` `JOIN FETCH` 전례와 동일 이유).
- `fetchSize`는 서비스가 "요청 limit + 1"을 넘긴다 — 리포지토리는 "다음 페이지 존재 여부" 계산 책임이 없고 순수 조회만 담당한다(레이어 규칙: repository는 비즈니스 판단을 하지 않는다).
- 새 컬럼·마이그레이션 불필요 — `account_id`는 이미 FK라 MySQL이 자동 생성한 인덱스가 있다. `(account_id, executed_at, id)` 복합 인덱스를 추가하면 대량 데이터에서 정렬 비용을 줄일 수 있지만, `#52`가 `symbol` 정렬에도 새 인덱스를 추가하지 않은 것과 같은 이유로 이번에도 추가하지 않는다(조기 최적화 지양 — `docs/conventions.md` "새 계층·공통화는 문제를 실제로 줄일 때만 추가"와 같은 원칙을 인덱스에도 적용). 데이터 증가로 실측 성능 문제가 확인되면 별도 마이그레이션으로 추가한다.

### `TradeCursor` 값 객체 설계 (`order.service` 패키지, 커서 파싱·인코딩 전담)

`SellAllocationDto`·`PriceQuoteDto`처럼 service 간 전달용이 아니라 서비스 내부 전용 값 객체이지만, 같은 전례(도메인 서비스 패키지에 직접 record를 두고 `dto/` 하위에 두지 않음)를 따른다 — 이 값 객체는 API 계약(`TradeListItemResponse`)이 아니라 커서 문자열 파싱이라는 내부 구현 세부이기 때문이다.

```java
public record TradeCursor(LocalDateTime executedAt, Long id) {

	private static final DateTimeFormatter EXECUTED_AT_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

	public static TradeCursor parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return null; // 커서 없음 = 첫 페이지
		}
		int separatorIndex = raw.lastIndexOf('_');
		if (separatorIndex <= 0 || separatorIndex == raw.length() - 1) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다.");
		}
		try {
			LocalDateTime executedAt = LocalDateTime.parse(raw.substring(0, separatorIndex), EXECUTED_AT_FORMAT);
			Long id = Long.parseLong(raw.substring(separatorIndex + 1));
			return new TradeCursor(executedAt, id);
		} catch (DateTimeParseException | NumberFormatException e) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다.");
		}
	}

	public static String encode(Trade lastTrade) {
		return lastTrade.getExecutedAt().format(EXECUTED_AT_FORMAT) + "_" + lastTrade.getId();
	}
}
```

### Service 설계 — 왜 `OrderService`에 얹지 않고 `TradeService`를 새로 만드는가

`OrderService`는 이미 주문 생성(멱등성 판정+위임)과 `getMyOrders`(#21) 두 책임을 갖고 있고, `TradeRepository`도 이미 주입돼 있다(멱등성 재조회용). 여기에 커서 파싱·페이지네이션 조립까지 더하면 "주문 생성/조회/체결내역 조회"가 한 클래스에 섞여 `docs/conventions.md` 금지 패턴("하나의 service가 여러 책임을 몰아넣기")에 가까워진다. `portfolio` 도메인이 계산(`HoldingValuationService`)과 API 유스케이스(`HoldingService`)를 분리한 전례(#52)와 같은 방향으로, `order` 도메인도 주문 유스케이스(`OrderService`)와 체결내역 조회 유스케이스(`TradeService`)를 분리한다.

```java
@Service
@RequiredArgsConstructor
public class TradeService {

	private final AccountService accountService;
	private final TradeRepository tradeRepository;

	@Transactional(readOnly = true)
	public TradeListResponse getMyTrades(Long userId, Market market, String cursor, int limit) {
		Account account = accountService.getAccountFor(userId, market);
		TradeCursor parsedCursor = TradeCursor.parse(cursor);

		List<Trade> fetched = tradeRepository.findByAccountIdWithCursor(
			account.getId(),
			parsedCursor == null ? null : parsedCursor.executedAt(),
			parsedCursor == null ? null : parsedCursor.id(),
			limit + 1);

		boolean hasNext = fetched.size() > limit;
		List<Trade> page = hasNext ? fetched.subList(0, limit) : fetched;
		String nextCursor = hasNext ? TradeCursor.encode(page.get(page.size() - 1)) : null;

		List<TradeListItemResponse> content = page.stream().map(TradeListItemResponse::from).toList();
		return TradeListResponse.of(content, nextCursor, hasNext);
	}
}
```

- 소유권+시장 스코프 검증: `accountService.getAccountFor(userId, market)`가 이미 `BusinessException(NOT_FOUND)`를 던지는 검증을 포함하므로 별도 분기 없이 재사용한다(`#81`·`#52`와 동일 근거). `Trade`는 `user`가 아니라 `account`로 소유자를 추적하므로(엔티티 구조상 자연스러운 선택), 계좌를 먼저 얻어 `account.getId()`로 필터링하는 것이 `Trade` 엔티티 구조와도 맞는다.
- "요청 limit + 1"을 조회해 `hasNext`를 판정하는 것은 흔한 커서 페이지네이션 기법이며 이 판단(다음 페이지 존재 여부)은 비즈니스 규칙이라 service가 담당한다(repository는 순수 조회만).
- `order` 도메인이 `account` 도메인의 `AccountService`를 주입하는 것은 ADR-0002가 금지하는 "다른 도메인의 repository 직접 참조"가 아니라 "service 레이어를 통한 참조"이며, `OrderExecutionService`가 이미 쓰는 전례와 동일하다.
- 이 서비스는 `PriceQueryService`·`HoldingValuationService`를 전혀 참조하지 않는다 — 체결 원장은 이미 확정된 값(가격·수량·금액·수수료·실현손익)만 담고 있어 "지금 시세로 재계산"할 대상이 없다(spec.md 비즈니스 규칙 "조회는 원장과 최신 시세만 읽는다"의 앞부분, 원장만 해당).

### Controller 설계

```java
@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class TradeController {

	private static final int DEFAULT_LIMIT = 20;
	private static final int MIN_LIMIT = 1;
	private static final int MAX_LIMIT = 100;

	private final TradeService tradeService;

	@GetMapping
	public ResponseEntity<TradeListResponse> getMyTrades(
		@AuthenticationPrincipal AuthenticatedUser principal,
		@RequestParam Market market,
		@RequestParam(required = false) String cursor,
		@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
		validateLimit(limit);
		return ResponseEntity.ok(tradeService.getMyTrades(principal.userId(), market, cursor, limit));
	}

	private void validateLimit(int limit) {
		if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "limit은 1~100 사이여야 합니다.");
		}
	}
}
```

- `com.finplay.api.account.domain.Market`을 import한다(`market.domain.Market`이 아님 — `#81`·`#52`와 동일 주의).
- 새 컨트롤러 클래스(`com.finplay.api.order.controller.TradeController`) — `OrderController`에 메서드를 추가하지 않는다(별도 리소스, 별도 서비스이므로 컨트롤러도 분리하는 것이 일관적이다).
- `limit` 범위 검증은 `CommunityPostController.validatePageAndSize`와 동일한 방식(상수 + private 메서드 + `BusinessException`)으로 컨트롤러에 둔다 — 요청 파라미터 자체의 형식 범위 체크이지 도메인 규칙이 아니라 `#24`(커뮤니티 목록) 전례를 그대로 따른다. `cursor` 파싱(도메인 지식이 필요한 형식 해석)은 그와 달리 서비스(`TradeCursor.parse`)가 담당한다 — 둘 다 400이지만 후자는 커서 포맷이라는 도메인 개념을 이해해야 하는 반면 전자는 단순 정수 범위 비교라 배치를 다르게 한다.

### 데이터 모델

없음 — 신규 컬럼·마이그레이션 불필요. 기존 `trades` 테이블(V10)을 조회만 한다. 응답값을 어떤 테이블에도 저장하지 않는다. 복합 인덱스 추가 여부는 위 "Repository 설계" 절 근거로 이번엔 보류한다.

### 문서 동기화

같은 커밋에서 갱신(CLAUDE.md 규칙 7):

- `docs/api-routes.md`: 라우트 표에 `GET | /api/trades?market=&cursor=&limit= | order | ... | 006 PORT-002, Issue #82` 행 추가.
- `docs/api-contracts.md`: `## order` 절에 "내 체결 내역 조회" 표 추가(기존 "내 주문 목록 조회" 다음) — 요청(쿼리 `market` 필수, `cursor`·`limit` 선택), 성공 200 예시(매수 1건 `realizedPnl:null` + 매도 1건 `realizedPnl` 값 있음, `nextCursor`·`hasNext` 포함), 오류(400 `VALIDATION_ERROR` — `market` 누락/오류, 손상된 `cursor`, `limit` 범위 초과 세 경우 모두 포함, 401 `UNAUTHORIZED`).

### 테스트 계획 (ADR-0003 기준)

- **단위 — `TradeCursorTest`(신규, 순수 JUnit, Mockito 불필요)**: 정상 커서 파싱(날짜·id 정확히 분리), `null`·빈 문자열 → `null` 반환(첫 페이지), 구분자 없음·날짜 파싱 실패·id 파싱 실패 각각 `BusinessException(VALIDATION_ERROR)`, `encode(Trade)` → `parse(...)` 왕복 시 원래 `executedAt`·`id`와 동일(라운드트립 검증).
- **단위 — `TradeServiceTest`(신규, Mockito)**: `AccountService.getAccountFor`·`TradeRepository.findByAccountIdWithCursor`를 stub.
  - `limit`건 이하 반환 시 `hasNext=false`·`nextCursor=null`.
  - `limit+1`건 반환 시(다음 페이지 있음) `hasNext=true`이고 `nextCursor`가 페이지의 마지막(= `limit`번째) 항목 기준으로 생성되는지 검증.
  - 매수 행(`realizedPnl=null`)·매도 행(`realizedPnl` 값 있음) 매핑 정확성을 실제 값으로 검증(mock 응답 객체 금지 컨벤션).
  - 손상된 `cursor` 문자열 전달 시 `TradeCursor.parse`가 던진 예외가 그대로 전파되는지.
  - 체결내역 없음 → `content=[]`, `hasNext=false`.
- **슬라이스 Repository — `TradeRepositoryTest`(신규, `@DataJpaTest`)**:
  - 다른 계좌의 체결을 제외하는지.
  - `executedAt` 내림차순·동시각 `id` 내림차순 정렬.
  - 커서를 넘겨 두 번 연속 조회했을 때 두 결과 집합에 중복·누락이 없는지(전체 데이터를 커서 없이 한 번에 조회한 결과와 "1페이지+2페이지 이어붙인 결과"가 같은 순서로 일치하는지 직접 비교).
  - `instrument` 지연 로딩 예외 없이 접근 가능(`fetchJoin()` 확인).
- **슬라이스 API — `TradeControllerTest`(신규, `@WebMvcTest`, `OrderControllerTest`/`AccountControllerTest` 패턴 재사용)**:
  - `market=STOCK`·`market=CRYPTO` 200과 `jsonPath`로 `TradeListResponse`·`TradeListItemResponse` 필드 계약 검증(매수·매도 각 1건 이상 stub, 체결 전용 필드가 응답에 있고 `OrderListItemResponse` 전용 필드는 없음을 함께 확인).
  - `market` 누락·`market=FOREX` → 400 `VALIDATION_ERROR`.
  - `limit=0`·`limit=101` → 400 `VALIDATION_ERROR`. `limit` 생략 시 기본값 20이 서비스에 전달되는지.
  - 손상된 `cursor`(예: `"garbage"`) → 서비스가 던진 `BusinessException(VALIDATION_ERROR)`이 400으로 매핑되는지(전역 예외 핸들러 회귀 확인).
  - 인증 실패(Authorization 헤더 없음) → 401 `UNAUTHORIZED`.
- **통합 — Testcontainers(신규 `TradeIntegrationTest` 또는 매수·매도 통합 테스트 파일 인접)**:
  - 매수 API로 여러 건 체결 생성 후 그중 일부를 매도 API로 체결 → `GET /api/trades?market=` 호출 → 매수 건은 `realizedPnl=null`, 매도 건은 FIFO 실현손익 값이 원장과 일치하는지 검증(spec 완료 조건 "거래내역 최신순·커서 페이지네이션·매도 실현손익 포함").
  - `limit`을 데이터 건수보다 작게 설정해 여러 페이지로 나눠 `nextCursor`를 따라가며 전체를 수집 → 커서 없이 한 번에 조회(테스트 편의상 `limit`을 데이터 건수 이상으로 크게 준 별도 호출)한 결과와 항목 집합·순서가 정확히 일치하는지 비교(중복·누락 없음의 최종 근거).
  - 타인 계좌의 체결이 본인 조회에 섞이지 않는지 확인.
  - 손상된 `cursor` 400, `market` 누락 400, 비로그인 401 최소 1건씩 확인(나머지 조합은 슬라이스 테스트가 촘촘히 커버).
  - 체결내역이 없는 신규 계좌 → 200 빈 배열(`content=[]`, `hasNext=false`, `nextCursor=null`).
