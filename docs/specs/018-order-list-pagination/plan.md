# Plan: 주문 목록 market 필수 · 커서 페이지네이션

## 관련 문서

- Spec: `./spec.md`
- PRD: `../../prd.md` — PORT-003(2026-08-04 확정, 이슈 #177)
- 관련 ADR: ADR-0002(레이어드 아키텍처, 도메인 간 참조는 service만), ADR-0003(테스트 전략), ADR-0004(Flyway — 이번 작업은 스키마 변경 없음)
- 형제 API(패턴 원본): `GET /api/trades` — `TradeController`·`TradeService`·`TradeCursor`·`TradeRepositoryCustom`/`TradeRepositoryImpl`·`TradeListResponse`
- 이슈: #182 (`gh issue view 182`), 선행 이슈 #177(PR #178, 정책 확정)

## market enum 주의

프로젝트에 동명 enum이 두 개 있다 — 반드시 계좌 도메인 것을 쓴다.

- 사용할 것: `com.finplay.api.account.domain.Market` (`AccountService.getAccountFor(Long userId, Market market)`, `Order.account`, `Trade`/`TradeController`가 이미 이 타입을 씀)
- 쓰지 않을 것: `com.finplay.api.market.domain.Market` (시세·종목 도메인의 동명 enum — `Instrument.market`과는 다른 타입이므로 import 시 혼동하지 않는다)

## Trade → Order 대응표

형제 API `GET /api/trades`의 구현체를 그대로 이식한다. 클래스별 대응은 다음과 같다.

| Trade (원본) | Order (이식 대상) | 비고 |
|---|---|---|
| `TradeCursor` (`record(LocalDateTime executedAt, Long id)`, `{ISO_LOCAL_DATE_TIME}_{id}` 포맷) | `OrderCursor` (`record(LocalDateTime requestedAt, Long id)`, `{ISO_LOCAL_DATE_TIME}_{id}` 포맷) | `parse(String)`·`encode(Order)` 정적 메서드, 파싱 실패는 `BusinessException(ErrorCode.VALIDATION_ERROR)` |
| `TradeRepositoryCustom`/`TradeRepositoryImpl` (QueryDSL, `QTrade`, `trade.account.id.eq` + 커서 이전 조건 + `executedAt desc, id desc` + `instrument` fetchJoin) | `OrderRepositoryCustom`/`OrderRepositoryImpl` (QueryDSL, `QOrder`, `order.account.id.eq` + 커서 이전 조건 + `requestedAt desc, id desc` + `instrument` fetchJoin) | `QOrder`는 이미 `build/generated/.../order/domain/QOrder.java`에 생성돼 있다 — QueryDSL apt가 프로젝트 전체 `@Entity`에 적용되므로 `build.gradle` 추가 설정 불필요. `OrderRepository extends JpaRepository<Order, Long>, OrderRepositoryCustom` |
| `TradeListResponse` (`content`/`nextCursor`/`hasNext`, 항목은 `TradeListItemResponse`) | `OrderListResponse` (`content`/`nextCursor`/`hasNext`, 항목은 기존 `OrderListItemResponse` 재사용 — 신규 DTO 아님) | `OrderListItemResponse`는 필드·`from(Order)` 그대로 유지, 감싸는 wrapper만 신설 |
| `TradeService.getMyTrades(userId, market, cursor, limit)` | `OrderService.getMyOrders(userId, market, cursor, limit)` | 시그니처가 `getMyOrders(Long userId)`에서 4개 파라미터로 변경 — breaking change. `AccountService.getAccountFor(userId, market)` 재사용으로 소유권+시장 스코프 선검증 |
| `TradeController.getMyTrades` (`@RequestParam Market market`, `@RequestParam(required=false) String cursor`, `@RequestParam(defaultValue="20") int limit`, `validateLimit`로 1~100 검증) | `OrderController.getMyOrders` | 동일한 3개 `@RequestParam`과 컨트롤러 로컬 `validateLimit(int)`(`MIN_LIMIT=1`, `MAX_LIMIT=100`, `DEFAULT_LIMIT=20`)를 그대로 이식 |

**스키마 변경 없음** — `Order` 엔티티(`src/main/java/com/finplay/api/order/domain/Order.java`)는 이미 `@ManyToOne private Account account`를 가지고 있다. `Trade`와 마찬가지로 `account.id` 기준 커서 조회가 가능하므로 Flyway 마이그레이션이 필요 없다(ADR-0004 — 스키마 변경이 없으면 마이그레이션도 없다).

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | `/api/orders?market=&cursor=&limit=` | `market`(필수), `cursor`(선택), `limit`(선택, 기본 20) | `OrderListResponse` | 인증 사용자 본인의 `market` 계좌 주문을 최신순 커서 페이지네이션으로 조회 |

`POST /api/orders`(주문 생성)는 이 변경의 영향을 받지 않는다 — `OrderController.createOrder`·`OrderService.createOrder`·`OrderResponse`는 그대로 유지한다.

## 입력 명세

| 필드 | 필수 | 검증 |
|---|---|---|
| `market` | 필수 | `STOCK`\|`CRYPTO` 리터럴만 파싱 성공(Jackson enum 바인딩). 누락·미지원 리터럴은 400 `VALIDATION_ERROR` |
| `cursor` | 선택 | `{ISO_LOCAL_DATE_TIME}_{id}` 형식 문자열. 생략 시 첫 페이지. 구분자 없음·날짜 파싱 실패·id 파싱 실패는 400 `VALIDATION_ERROR`(`OrderCursor.parse`) |
| `limit` | 선택 | 기본 20, 정수. 1~100 범위 밖은 400 `VALIDATION_ERROR`(클램핑 없음, `OrderController.validateLimit`) |

계좌 소유권: `market`으로 조회한 계좌가 인증 사용자 본인 소유가 아니거나 존재하지 않으면 `AccountService.getAccountFor`가 `BusinessException(ErrorCode.NOT_FOUND)`를 던진다 — `GET /api/trades`와 동일한 처리이며 별도 오류 코드를 추가하지 않는다.

## 데이터 모델

신규 테이블·컬럼·인덱스 없음. `Order` 조회는 기존 컬럼(`account_id`·`requested_at`·`id`)으로 충분하다. `orders` 테이블에 `(account_id, requested_at, id)` 복합 인덱스가 없다면 커서 조회 성능을 위해 검토할 수 있으나, `trades` 테이블도 별도 전용 인덱스 없이 동작 중이므로 이번 spec에서는 인덱스 추가를 필수 항목으로 두지 않는다(필요해지면 별도 성능 이슈로 판단).

## 테스트 계획

- 단위(`OrderServiceTest` — 기존 파일 갱신): `getMyOrders(userId, market, cursor, limit)`가 `AccountService.getAccountFor`로 계좌를 선조회하는지, 커서 파싱·`limit+1` fetch로 `hasNext` 판정, 마지막 페이지에서 `nextCursor=null`인지 — `TradeServiceTest`(`src/test/java/com/finplay/api/order/service/TradeServiceTest.java`) 대응 패턴.
- 단위(`OrderCursorTest` — 신규): 유효 커서 파싱, 구분자 없음·날짜 파싱 실패·id 파싱 실패 각각의 400 `VALIDATION_ERROR` — `TradeCursorTest`(`src/test/java/com/finplay/api/order/service/TradeCursorTest.java`) 그대로 이식.
- 슬라이스(`@WebMvcTest OrderControllerTest` — 기존 파일 갱신): `market` 생략/미지원 리터럴 400, `limit` 범위 밖 400, 정상 요청 200과 `content`/`nextCursor`/`hasNext` 필드, 기존 8개 응답 필드 회귀 없음, 인증 실패 401 — `TradeControllerTest`(`src/test/java/com/finplay/api/order/controller/TradeControllerTest.java`) 대응 패턴.
- 슬라이스(`@DataJpaTest OrderRepositoryTest` — 기존 파일 갱신): `OrderRepositoryImpl.findByAccountIdWithCursor`류 쿼리가 `account_id` 필터·`requestedAt desc, id desc` 정렬·커서 이전 조건을 만족하는지 — `TradeRepositoryTest`(`src/test/java/com/finplay/api/order/repository/TradeRepositoryTest.java`) 대응 패턴.
- 통합(`OrderListIntegrationTest` — 기존 파일 갱신, 이미 존재): 여러 계좌·여러 주문을 시드한 뒤 `market` 필터링, 첫 페이지→`nextCursor`로 다음 페이지 이어받기(중복·누락 없음), 마지막 페이지 `hasNext=false`, 타 사용자 계좌 조회 거부를 Testcontainers로 검증 — `TradeIntegrationTest`(`src/test/java/com/finplay/api/order/service/TradeIntegrationTest.java`) 대응 패턴.
