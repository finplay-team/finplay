# Tasks: 조회 — PORT-003 주문 목록 (GitHub 이슈 #21)

> 이 tasks.md는 `spec.md`의 5개 요구사항 중 PORT-003(주문 목록, 이슈 #21)만 다룬다. ACCT-002·ACCT-003·PORT-001·PORT-002는 별도 계획 대상이다(`plan.md` 상단 범위 안내 참고).
> 기존 `order` 도메인(이슈 #13, PR #88)에 이어 붙이는 작업이라 새 엔티티·마이그레이션·도메인 패키지가 필요 없다.

- [x] **Repository: 사용자별 최신순 주문 조회 쿼리 메서드**
  - `OrderRepository`에 `findAllByUserIdOrderByRequestedAtDescIdDesc(Long userId)` 추가 (`instrument` `JOIN FETCH` 포함 JPQL, plan.md 참고).
  - `@DataJpaTest` 슬라이스 테스트: 본인 주문만 반환, `requestedAt` 내림차순·동시각 `id` 내림차순 정렬, 주문 없는 사용자는 빈 목록.

- [x] **응답 DTO: `OrderListItemResponse`**
  - `order/dto/response/OrderListItemResponse.java` record 추가 — `orderId`·`market`·`instrumentId`·`side`·`orderType`·`status`·`quantity`·`requestedAt` 8개 필드, 정적 팩토리 `from(Order order)`.
  - 체결 전용 필드(`tradeId`·`price`·`amount`·`fee`·`executedAt`)를 포함하지 않는지 코드 리뷰 관점에서 스스로 재확인(spec 요구사항).

- [x] **Service: `OrderService.getMyOrders`**
  - 기존 `OrderService`에 `@Transactional(readOnly = true) getMyOrders(Long userId)` 추가 — repository 결과를 `OrderListItemResponse.from(...)`으로 매핑.
  - 단위 테스트(`OrderServiceTest`, Mockito): 매핑 필드 정확성, 빈 목록 처리. 응답 객체를 mock으로 만들지 않고 실제 값으로 검증.

- [x] **Controller: `GET /api/orders`**
  - 기존 `OrderController`에 `@GetMapping` 메서드 추가 — `@AuthenticationPrincipal AuthenticatedUser`에서 `userId`를 얻어 서비스 호출, 200 응답.
  - `@WebMvcTest` 슬라이스 테스트(`OrderControllerTest`): 200 성공 시 `jsonPath`로 8개 필드 값 검증(+ 체결 전용 필드 부재 확인), 인증 실패 401.

- [x] **통합 테스트: 매수 파이프라인 기반 시나리오**
  - Testcontainers 기반 기존 통합 테스트 파일(또는 인접 파일)에 시나리오 추가: 이슈 #13 매수 API로 실제 주문 데이터 생성(여러 건, 가능하면 타인 주문 포함) → `GET /api/orders` 호출 → 본인 범위·최신순·필드 계약·타인 주문 제외·체결 필드 미노출을 한 시나리오에서 검증.
  - 주문이 없는 신규 사용자에 대한 200 빈 배열 케이스 포함.

- [x] **문서 동기화: `docs/api-routes.md` · `docs/api-contracts.md`**
  - `api-routes.md` 라우트 표에 `GET /api/orders` 행 추가(Spec 컬럼에 `006 PORT-003, Issue #21` 표기).
  - `api-contracts.md`의 `## order` 절에 "내 주문 목록 조회" 표 추가 — 요청 없음, 성공 200 예시(`OrderListItemResponse[]`), 오류 401만.
  - 같은 커밋에서 두 문서를 함께 갱신(CLAUDE.md 규칙 7).
