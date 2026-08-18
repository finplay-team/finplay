# Plan: 튜토리얼 attempt 전용 주문 조회

## 관련 문서

- Spec: `./spec.md`
- Issue: #435
- 참조(변경하지 않음): `026-market-order-practice-tutorial`(2·3단계 chain, holding 기반 진행 조회 정본),
  `033-exclude-tutorial-sandbox-data`(SANDBOX-EXCL 원칙, `OrderRepositoryImpl`의 현재 필터 위치),
  `030-coin-practice-price-runtime`(`practicePriceSessionId` 귀속, 별도 조회 경로 유지),
  `039-tutorial-flow-redesign`(`practice_attempts`·`orders.practice_attempt_id`/`practice_attempt_run_number`
  모델, `PracticeAttemptOrderAttributionService`가 이미 확립한 order↔attempt 귀속 규칙)
- 관련 ADR: ADR-0002(레이어드 경계 — 도메인 간 참조는 service 레이어를 통해서만), ADR-0003(테스트 전략)

**새 ADR은 만들지 않는다.** 이 spec은 기존 `orders` 테이블의 이미 존재하는
`practice_attempt_id`/`practice_attempt_run_number` 컬럼을 조회하는 새 read-only 쿼리 하나와, 그것을
노출하는 컨트롤러 하나를 추가할 뿐이다. 스키마 변경이 없고 ADR-0002가 이미 규정한 "도메인 간 참조는
service 레이어를 통해서만"이라는 경계 안에서 끝난다 — `education` 서비스가 `order.service.OrderService`를
직접 호출하는 것은 이미 `PracticeAttemptEvidenceService`가 `order.service.TradeService`를 호출하는
선례와 동일한 패턴이다(`summarizePracticeRun`).

## 도메인 경계

- **order 도메인이 새 조회 쿼리를 소유한다.** `OrderRepository`에 attemptId·runNumber로 주문을 찾는
  비잠금 조회 메서드를 추가하고, `OrderService`에 그 결과를 `OrderListItemResponse`로 매핑하는 공개
  메서드를 추가한다. 이 메서드는 `education` 패키지의 어떤 클래스도 import하지 않는다 — attemptId·
  runNumber라는 scalar 값만 파라미터로 받는다(039의 `PracticeRunRestartOrderService.cleanupCurrentRun`,
  `TradeService.summarizePracticeRun`과 같은 경계 설계).
- **education 도메인이 새 컨트롤러·서비스를 소유한다.** `education.marketpractice.controller`에
  `PracticeAttemptOrderController`(GET 1개)를 추가하고, `education.marketpractice.service`에
  `PracticeAttemptOrderQueryService`를 추가한다. 이 서비스는 자신의 `PracticeAttemptRepository`로 현재
  attempt를 찾고, `order.service.OrderService`(새 메서드)를 호출해 목록을 받는다 — 다른 도메인의
  repository를 직접 주입하지 않는다(ADR-0002).
- 이 흐름은 기존 `PracticeAttemptRestartService`가 `PracticeRunRestartOrderService`(order 도메인 서비스)를
  호출하는 것과 같은 모양이다 — 새로운 종류의 도메인 간 호출을 도입하지 않는다.

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | `/api/education/practice/attempts/{market}/orders` | path `market`(`STOCK`\|`CRYPTO`), 쿼리·본문 없음 | 200 `List<OrderListItemResponse>` | 인증 사용자 본인의 `market` 튜토리얼 attempt 현재 run에 귀속된 주문을 `id` 오름차순으로 순수 조회 |

- 기존 `com.finplay.api.order.dto.response.OrderListItemResponse`(11필드: `orderId`·`market`·
  `instrumentId`·`side`·`orderType`·`status`·`quantity`·`limitPrice`·`requestedAt`·`practiceAttemptId`·
  `practiceAttemptRunNumber`)를 그대로 재사용한다 — 새 응답 DTO를 만들지 않는다. 이 요청·응답 필드는
  attempt 조회이므로 `practiceAttemptId`·`practiceAttemptRunNumber`가 이 목록의 모든 행에서 항상
  non-null이다(정의상 attempt 귀속 주문만 반환하므로).
- `GET /api/holdings`·`GET /api/education/practice/attempts/{market}/chart`와 같은 순수 조회 전용
  엔드포인트 패턴을 따라 커서 페이지네이션 없는 직접 배열 응답을 쓴다(`OrderListResponse`의
  `content`/`nextCursor`/`hasNext` 래퍼를 쓰지 않는다) — spec "범위 제외"에서 확정한 대로 페이지네이션이
  필요 없는 규모이기 때문이다.
- 정렬은 `id` 오름차순(=`requestedAt` 오름차순과 동일, 주문은 순차 생성)이다 — `GET /api/orders`·
  `GET /api/orders/pending`의 최신순(내림차순)과 다르게, "이번 실행에서 무슨 일이 있었는지"를 시간
  순서대로 보여주는 것이 이 엔드포인트의 목적에 더 맞는다(039 `PracticeAttemptChartResponse`의 candle
  배열도 시간순 오름차순인 것과 같은 방향).

## 입력·오류 계약

| 조건 | HTTP / 코드 |
|---|---|
| `market` 경로 값이 `STOCK`\|`CRYPTO`가 아님(enum 매핑 실패) | 400 `VALIDATION_ERROR`(기존 `PUT /api/education/practice/attempts/{market}`와 동일한 Spring enum 경로변수 매핑 실패 처리 재사용, 신규 코드 없음) |
| attempt 없음(사용자가 이 시장 튜토리얼에 진입한 적 없음) | 오류 아님 — 200 빈 배열 `[]` |
| 인증 실패 | 401 `UNAUTHORIZED` |

신규 오류 코드를 추가하지 않는다. attempt 조회는 잠금 없이 `PracticeAttemptRepository.
findByUserIdAndMarket`(기존 메서드, 비잠금)을 그대로 쓴다 — 새 리포지터리 메서드가 필요 없다.

## 데이터 모델

신규 컬럼·테이블 없음. 기존 `orders.practice_attempt_id`·`orders.practice_attempt_run_number`(039,
`V38`)와 기존 `practice_attempts`(039, `V38`)만 조회한다.

### `OrderRepository`에 추가할 비잠금 조회 (order 도메인)

```java
// 튜토리얼 attempt 전용 주문 조회(GET .../attempts/{market}/orders, 043) — 잠금 없는 순수 조회.
// findPracticeRunOrdersForUpdate(재시작 정리용, 039)와 조건은 동일하되 FOR UPDATE를 걸지 않고
// instrument를 fetch join한다(OrderListItemResponse가 instrument.market을 읽으므로).
@Query("""
	select o from Order o
	join fetch o.instrument
	where o.practiceAttemptId = :attemptId
	  and o.practiceAttemptRunNumber = :runNumber
	order by o.id asc
	""")
List<Order> findPracticeRunOrders(
	@Param("attemptId") Long attemptId, @Param("runNumber") long runNumber);
```

### `OrderService`에 추가할 공개 메서드 (order 도메인)

```java
// 043 — 튜토리얼 education 도메인이 호출하는 attempt 전용 조회. attemptId·runNumber만 받고 education의
// 어떤 타입도 알지 않는다(TradeService.summarizePracticeRun과 동일 경계).
@Transactional(readOnly = true)
public List<OrderListItemResponse> getPracticeRunOrders(Long attemptId, long runNumber) {
	return orderRepository.findPracticeRunOrders(attemptId, runNumber).stream()
		.map(OrderListItemResponse::from)
		.toList();
}
```

### `PracticeAttemptOrderQueryService` (education 도메인, 신규)

```java
@Transactional(readOnly = true)
public List<OrderListItemResponse> getCurrentRunOrders(Long userId, Market market) {
	return practiceAttemptRepository.findByUserIdAndMarket(userId, market)
		.map(attempt -> orderService.getPracticeRunOrders(attempt.getId(), attempt.getRunNumber()))
		.orElseGet(List::of);
}
```

- `PracticeAttemptOrderController`는 `PracticeAttemptController`(`ensureAttempt`/`selectInstrument`)와
  같은 `@RequestMapping("/api/education/practice/attempts")` 베이스 위에 `GET /{market}/orders` 하나만
  추가한다. 기존 컨트롤러에 메서드를 얹지 않고 별도 클래스로 분리하는 이유는 `PracticeAttemptChartController`
  (`chart`/`tick`)·`PracticeAttemptRestartController`(`restart`)가 이미 관심사별로 컨트롤러를 나눈
  선례를 따르기 위함이다 — 주문 조회는 "attempt 상태 변경"이 아니라 "attempt 소속 다른 도메인 데이터
  조회"라는 별개 관심사다.

## 테스트 계획

- 단위: `OrderService.getPracticeRunOrders`(빈 결과·복수 상태 혼재 매핑), `PracticeAttemptOrderQueryService.
  getCurrentRunOrders`(attempt 없음 → 빈 목록, attempt 있음 → `OrderService` 위임 호출 검증, Mockito).
- 슬라이스: `OrderRepository.findPracticeRunOrders`(`@DataJpaTest` — 같은 attempt의 다른 run 제외, 다른
  attempt 제외, `practiceAttemptId=null` 일반 주문 제외, `id` 오름차순 정렬), `PracticeAttemptOrderController`
  (`@WebMvcTest` — 200 응답 스키마, 인증 없음 401, 잘못된 `market` 리터럴 400).
- 통합(Testcontainers): 샘플 종목 지정가 매수 생성 → 이 엔드포인트 조회 시 `PENDING` 노출 → tick 체결 →
  재조회 시 같은 주문이 `FILLED`로 노출 → 재시작 → 재조회 시 이전 run 주문이 빠지고 빈 목록(새 run은 아직
  주문 없음)임을 확인. `GET /api/orders`·`GET /api/orders/pending`의 기존 샌드박스 제외 테스트가 이 변경
  이후에도 회귀 없이 통과하는지 함께 확인(순수 추가라 회귀 위험은 낮지만 명시적으로 재확인).

## 문서 동기화

controller 구현과 같은 커밋에서 실제 mapping을 `docs/api-routes.md`에 추가하고, 이 절의 요청·응답·오류를
`docs/api-contracts.md`의 `education`(또는 튜토리얼 attempt) 절에 동기화한다(CLAUDE.md 규칙 7). 이
엔드포인트는 PRD에 등재된 공식 요구사항 ID가 아니므로(기존 TUTORIAL-FLOW-003·007 order 귀속 계약의
읽기 gap을 메우는 버그성 보강) `docs/prd.md` §3에 새 행을 추가할지는 구현 완료 시점에 오케스트레이터가
판단한다 — WATCHLIST(023) 선례처럼 PRD 미등재 신규 조회 기능으로 행을 추가하는 것이 합리적이나, 최종
판단은 계획 문서가 아니라 구현 완료 커밋에서 내린다.
