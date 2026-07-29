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
