# Plan: 지정가 체결 청크 내 벌크 락 최적화

## 관련 문서
- Spec: `./spec.md`
- 관련 ADR: [ADR-0024](../../adr/0024-limit-order-fill-executor.md)(종목별 직렬화 실행기·잠금 순서), [ADR-0025](../../adr/0025-limit-order-fill-batch-commit.md)(청크 배치 커밋·원자성), [ADR-0028](../../adr/0028-holdings-insert-deadlock-mitigation.md)(holdings INSERT 데드락 완화 — §후속에 이 spec을 이미 예고해뒀다)
- 기존 계약: `ai/specs/015-limit-order/spec.md` LMT-002(잠금 순서 order→account→holding 확정), `ai/specs/037-limit-order-async-fill`(실행기·배치 커밋 구현) — 둘 다 이 spec이 바꾸지 않는다.
- 근거 자료: `docs/loadtest/limit-order-fill-lock-vs-write-phase-benchmark-result.md`(락 구간이 청크 처리 시간의 55~59%, BUY는 과소평가된 값)

## API 설계
해당 없음 — controller·API 계약 변경 없음. `ai/api-routes.md`·`docs/api/` 갱신 대상 아님.

## 입력 명세
해당 없음 — 신규 요청 DTO 없음. 아래 "데이터 모델·인터페이스 설계"가 이 spec의 실질적 설계다.

## 배경 — 현재 청크 처리 흐름과 왕복 지점

`LimitOrderTriggerListener.submitInBatches`는 **하나의 가격 틱 = 하나의 종목(instrumentId)**에서 나온 후보만 `batchSize`(기본 50) 단위로 잘라 `LimitOrderFillService.fillBatch(List<Long> orderIds)`에 청크 1개씩 제출한다. 즉 **청크 하나 안의 모든 주문은 항상 같은 instrumentId를 공유한다** — 이 전제가 아래 holding 벌크 조회 설계를 단순하게 만드는 핵심 근거다.

`fillBatch`는 지금 `orderIds`를 순회하며 매 주문마다 `fillOnePending(orderId, pricedAt)`을 호출하고, 그 안에서:

1. `orderRepository.findByIdForUpdate(orderId)` — 주문 1건 락 (order.getStatus() != PENDING이면 skip)
2. `accountService.getAccountByIdForUpdate(order.getAccount().getId())` — 계좌 1건 락
3. BUY: `portfolioBuyService.applyBuyTrade(...)` 내부에서 `holdingRepository.findByAccountIdAndInstrumentIdForUpdate(...).orElseGet(Holding.create(...))` — holding 1건 락(또는 신규 생성)
   SELL: `portfolioSellService.getHoldingForUpdate(...)` → 내부에서 같은 `findByAccountIdAndInstrumentIdForUpdate` — holding 1건 락(SELL은 항상 기존 행이 있어야 하고, 없으면 `IllegalStateException`)

`batchSize=50`이면 청크 하나가 이 3단계를 최대 50회 반복 왕복한다는 뜻이다.

## 데이터 모델·인터페이스 설계

### 1. `OrderRepository` — 벌크 order 락

```java
// 청크 전체를 한 번에 잠근다(054-limit-order-fill-bulk-lock). ID 오름차순으로 반환해 서로 다른 종목의
// 청크(=서로 다른 파티션 워커)가 겹치는 자원을 다른 순서로 잠그는 상황을 예방한다(위험 요소 1).
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT o FROM Order o WHERE o.id IN :ids ORDER BY o.id ASC")
List<Order> findByIdInForUpdate(@Param("ids") List<Long> ids);
```

- 호출부는 `orderIds`를 오름차순으로 정렬한 **복사본**을 이 쿼리에 넘긴다 — 원래 `orderIds`(처리 순서, `requestedAt asc, id asc`)는 손대지 않는다.
- 존재하지 않는 ID는 결과 집합에서 조용히 빠진다(개별 `findByIdForUpdate`처럼 그 자리에서 예외를 던지지 않는다) — 처리 루프가 이 차이를 메운다(아래 "3. `fillBatch` 재구성" 참고).

### 2. `AccountRepository` — 벌크 account 락

```java
// 청크가 필요로 하는 계좌 전체를 한 번에 잠근다. 계좌 ID 오름차순 고정이 필수다 — Account는 종목에 묶이지
// 않으므로(한 계좌가 여러 종목을 보유), 서로 다른 종목의 청크(=서로 다른 파티션 워커)가 같은 계좌를 동시에
// 필요로 할 수 있다(위험 요소 1의 핵심 사례).
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC")
List<Account> findByIdInForUpdate(@Param("ids") List<Long> ids);
```

### 3. `HoldingRepository` — 벌크 holding 락(기존 행만)

```java
// 청크가 참조하는 계좌 목록 + 청크가 공유하는 단일 종목으로 "이미 존재하는" holding만 한 번에 잠근다.
// 신규 생성(첫 매수) 대상은 이 쿼리에 나타나지 않는다 — fillBatch가 인메모리 맵으로 별도 처리한다(위험 요소 2).
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT h FROM Holding h WHERE h.account.id IN :accountIds AND h.instrument.id = :instrumentId ORDER BY h.id ASC")
List<Holding> findByAccountIdInAndInstrumentIdForUpdate(
	@Param("accountIds") List<Long> accountIds, @Param("instrumentId") Long instrumentId);
```

- "청크 하나는 항상 단일 종목"이라는 전제 덕분에 `(accountId, instrumentId)` 튜플 IN 조건이 아니라 `accountId IN (...) AND instrumentId = 고정값`으로 정확히 필요한 조합만 가져온다(교차곱으로 무관한 holding까지 과잉 잠그지 않는다).
- 기존 `findByAccountIdAndInstrumentIdForUpdate(Long, Long)`는 그대로 둔다 — `fillIfPending`(단건 경로)·시장가 매수·OCO 등 다른 호출부가 계속 쓴다.

### 4. `LimitOrderFillService.fillBatch` 재구성

```java
@Transactional(isolation = Isolation.READ_COMMITTED) // ADR-0028, 기존 그대로 유지
public void fillBatch(List<Long> orderIds) {
	LocalDateTime pricedAt = LocalDateTime.now(clock);

	// 1) order 벌크 락 — ID 오름차순으로 잠그되, 처리 순서(orderIds 원래 순서)는 그대로 보존한다.
	List<Long> sortedOrderIds = orderIds.stream().sorted().toList();
	Map<Long, Order> ordersById = orderRepository.findByIdInForUpdate(sortedOrderIds).stream()
		.collect(Collectors.toMap(Order::getId, Function.identity()));

	// PENDING 대상만 추려 이후 단계의 계좌·종목 조회 범위를 좁힌다. 존재하지 않는 ID는 여기서 걸러지지
	// 않는다 — 처리 루프(4번)에서 명시적으로 예외를 던져야 기존 원자성 테스트의 메시지·전체 롤백이 유지된다.
	List<Order> pendingOrders = orderIds.stream()
		.map(ordersById::get)
		.filter(o -> o != null && o.getStatus() == OrderStatus.PENDING)
		.toList();

	// 2) account 벌크 락 — 계좌 ID 오름차순.
	List<Long> accountIds = pendingOrders.stream().map(o -> o.getAccount().getId()).distinct().sorted().toList();
	Map<Long, Account> accountsById = accountRepository.findByIdInForUpdate(accountIds).stream()
		.collect(Collectors.toMap(Account::getId, Function.identity()));

	// 3) holding 벌크 락(기존 행만) — 청크는 단일 종목이므로 instrumentId 하나로 충분하다.
	Long instrumentId = pendingOrders.isEmpty() ? null : pendingOrders.get(0).getInstrument().getId();
	Map<Long, Holding> holdingsByAccountId = instrumentId == null
		? Map.of()
		: new HashMap<>(holdingRepository.findByAccountIdInAndInstrumentIdForUpdate(accountIds, instrumentId).stream()
			.collect(Collectors.toMap(h -> h.getAccount().getId(), Function.identity())));
	// holdingsByAccountId는 가변 맵이어야 한다 — 4번 루프에서 신규 생성된 holding을 즉시 반영해야 같은 청크의
	// 다음 주문이 재사용할 수 있다(위험 요소 2).

	// 4) 원래 처리 순서(requestedAt asc, id asc)로 순회 — 여기서 order→account→holding 맵을 조회해 쓴다.
	for (Long orderId : orderIds) {
		Order order = ordersById.get(orderId);
		if (order == null) {
			// 벌크 조회가 조용히 빠뜨린 존재하지 않는 ID 처리 — 기존 findByIdForUpdate의 orElseThrow와
			// 동일한 예외 타입·메시지. @Transactional이 청크 전체를 롤백한다(ADR-0025 §결정 3, 변경 없음).
			throw new IllegalStateException("체결 대상 주문을 찾을 수 없습니다. orderId=" + orderId);
		}
		if (order.getStatus() != OrderStatus.PENDING) {
			continue;
		}
		Account account = accountsById.get(order.getAccount().getId());
		fillOnePendingWithLockedResources(order, account, holdingsByAccountId, pricedAt);
	}
}
```

- `fillOnePendingWithLockedResources`는 기존 `fillOnePending`의 attempt-preflight·`isTriggered`·`fillBuy`/`fillSell` 분기 로직을 그대로 재사용하되, "order·account를 조회해서 잠그는" 부분만 이미 잠긴 `Order`/`Account` 인자를 받는 형태로 바뀐다. **practice attempt preflight(`findPracticeFillAttribution`)는 그대로 주문별로 남긴다** — spec.md "범위 제외"에 적은 이유(항상 빈 결과, 인덱스 조회 1건일 뿐).
- SELL 분기는 `holdingsByAccountId.get(account.getId())`가 없으면 기존과 동일하게 `IllegalStateException("체결 대상 holding을 찾을 수 없습니다...")`를 던진다(SELL은 신규 생성이 없으므로 맵에 없으면 실제로 없는 것).
- BUY 분기는 `holdingsByAccountId.get(account.getId())`가 없으면 `PortfolioBuyService`의 새 오버로드에 `null`(또는 아직 저장 안 된 신규 `Holding.create(...)`)을 넘기고, 반환된(저장된) `Holding`을 **`holdingsByAccountId.put(account.getId(), holding)`으로 즉시 반영**한다 — 이게 위험 요소 2의 핵심 수정이다.

### 5. `PortfolioBuyService.applyBuyTrade` — 호출부가 이미 잠근 holding을 받는 오버로드 추가

```java
// 기존 시그니처 — 단건 호출부(시장가 매수, fillIfPending 단건 경로)는 그대로 이걸 쓴다. 내부에서
// find-or-create를 직접 한다(변경 없음).
public Holding applyBuyTrade(
	Account account, Instrument instrument, Trade buyTrade, BigDecimal quantity, BigDecimal price, long fee,
	LocalDateTime now) {
	Holding holding = holdingRepository
		.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId())
		.orElseGet(() -> Holding.create(account, instrument, now));
	return applyBuyTrade(account, instrument, buyTrade, quantity, price, fee, now, holding);
}

// 신규 — 벌크 락 호출부(LimitOrderFillService.fillBatch)가 이미 잠갔거나(기존 행) 아직 저장 전인 신규
// Holding을 그대로 넘긴다. 이 메서드 자체는 holdingRepository를 조회하지 않는다 — 중복 SELECT를 피하는 것이
// 이 오버로드를 추가하는 이유다.
public Holding applyBuyTrade(
	Account account, Instrument instrument, Trade buyTrade, BigDecimal quantity, BigDecimal price, long fee,
	LocalDateTime now, Holding holding) {
	holding.applyBuy(quantity, price, now);
	holdingRepository.save(holding);

	HoldingLot holdingLot = HoldingLot.create(holding, buyTrade, quantity, price, fee, buyTrade.getExecutedAt(), now);
	holdingLotRepository.save(holdingLot);
	return holding;
}
```

- 반환 타입을 `void` → `Holding`으로 바꾼다 — 기존 단건 호출부(들)는 반환값을 무시해도 되므로 컴파일 영향 없음. 호출부가 이 반환값을 안 쓰던 곳(예: `OrderExecutionService`)이 있다면 그대로 무시하게 둔다.
- `Holding.create(...)`가 아직 `holdingRepository.save`를 거치지 않은 상태(id 없음)로 넘어와도, `applyBuyTrade` 안에서 `save`가 호출되므로(IDENTITY 전략은 즉시 INSERT) 반환되는 `holding` 객체는 항상 id가 채워진 상태다 — `fillBatch`가 이 반환값을 맵에 넣으면 된다.

## 위험 요소와 대응 설계 (사전 조사에서 확인, 수용 기준에 반영됨)

### 위험 요소 1 — 벌크 락의 MySQL 잠금 순서 미보장 → ABBA 데드락

`WHERE id IN (:ids) FOR UPDATE`는 MySQL이 그 안에서 어떤 순서로 행을 잠그는지 보장하지 않는다(쿼리 플래너·인덱스 스캔 순서에 달림). 정렬 없이 벌크 락을 걸면, **서로 다른 종목(=서로 다른 파티션 워커, 동시 실행 가능)의 청크가 같은 계좌 집합을 서로 다른 순서로 잠그다 데드락이 날 수 있다** — ADR-0028이 겪은 것과 같은 종류의 함정(그때는 holdings INSERT 갭 락, 여기는 계좌 행 잠금 자체의 순서 문제라 형태는 다르지만 "정렬 없는 동시 잠금"이라는 근본 원인은 같다).

- **Order**: 같은 종목의 청크는 파티션 단일 스레드로 이미 직렬화되고(ADR-0024 §결정 1), 주문은 종목 하나에만 속하므로 서로 다른 청크가 같은 order 행을 동시에 잠그는 일은 구조적으로 없다. 그래도 방어적으로 ID 오름차순을 강제한다(수용 기준 그대로 명시된 요구사항이고, 향후 호출부가 늘어나도 안전하게 만든다).
- **Account**: **여기가 실제 위험 지점이다.** 계좌는 종목에 묶이지 않으므로, 사용자 하나가 코인 A·코인 B를 동시에 보유하면 코인 A의 청크(파티션 X)와 코인 B의 청크(파티션 Y)가 같은 계좌를 동시에 필요로 할 수 있다. 계좌 ID 오름차순 고정이 이 경우의 유일한 방어선이다 — 모든 청크가 항상 "작은 ID → 큰 ID" 순서로만 계좌를 잠그면, 두 트랜잭션이 서로의 락을 기다리는 순환(ABBA)이 원천적으로 불가능해진다.
- **Holding**: 청크가 항상 단일 종목이므로(위 "배경" 참고) 서로 다른 청크의 holding 락 대상은 애초에 겹치는 행이 없다(instrument_id가 다르면 다른 행). 그래도 방어적으로 ID 오름차순을 강제한다.
- **검증**: `LimitOrderFillAccountLockContentionIntegrationTest`(기존, ADR-0028이 검증에 쓴 것과 같은 부류)를 참고해, 서로 다른 종목의 청크 두 개가 겹치는 계좌 집합을 동시에 체결하는 신규 통합 테스트를 추가한다 — 데드락 없이 두 청크 모두 정상 완료해야 한다.

### 위험 요소 2 — holding 벌크 preflight가 스냅숏이면 같은 청크 내 중복 INSERT가 확정적으로 발생

청크 시작 시 holding을 한 번만 조회해 맵으로 스냅숏 뜨고, 그 맵을 갱신하지 않은 채 그대로 읽기만 하면: 같은 계좌가 같은 청크 안에서 같은 종목에 지정가를 2건 이상 걸어둔 경우(예: 분할 매수) 두 주문 모두 "맵에 없음 = 신규"로 보고 각자 `Holding.create()` + `save()`를 시도한다. 이건 레이스가 아니라 **같은 트랜잭션 안에서 순차로 일어나는 확정적 실패**다 — 두 번째 INSERT가 `uk_holdings_account_instrument` 유니크 제약을 위반해 청크 전체가 롤백된다.

- **대응**: 위 "4. `fillBatch` 재구성"의 `holdingsByAccountId`는 **가변 맵**이고, BUY 처리 직후(신규 생성이든 기존 갱신이든) `holdingsByAccountId.put(account.getId(), holding)`으로 즉시 갱신한다. 같은 청크의 다음 주문이 같은 계좌를 처리할 때는 이미 갱신된 맵에서 holding을 찾으므로 두 번째 INSERT 시도 자체가 없다.
- **검증**: spec.md 완료 조건에 명시한 신규 통합 테스트 — 같은 계좌가 같은 청크에서 같은 신규 종목에 2건 이상 매수 주문을 걸어둔 시나리오. 유니크 제약 위반 없이 둘 다 `FILLED`로 끝나야 하고, 최종 holding 수량은 두 체결의 합이어야 한다.

### 위험 요소 3 — BUY 경로의 holding 락은 `fillOnePending`이 아니라 `PortfolioBuyService.applyBuyTrade` 내부에 있다

기존 코드에서 SELL은 `LimitOrderFillService.fillSell`이 직접 `portfolioSellService.getHoldingForUpdate(...)`를 호출해 holding을 잠그지만, BUY는 `LimitOrderFillService.fillBuy`가 `portfolioBuyService.applyBuyTrade(...)`를 호출하고 그 **내부에서** `findByAccountIdAndInstrumentIdForUpdate(...).orElseGet(Holding.create(...))`가 실행된다. `LimitOrderFillService`만 고쳐서는 이 내부 호출을 벌크 락 결과로 대체할 수 없다 — `PortfolioBuyService`도 함께 바꿔야 한다.

- **대응**: 위 "5. `PortfolioBuyService.applyBuyTrade`"의 새 오버로드. `LimitOrderFillService.fillBuy`는 이제 `portfolioBuyService.applyBuyTrade(account, instrument, trade, quantity, executionPrice, fee, now, holding)`(holding은 `holdingsByAccountId`에서 찾은 기존 행 또는 새로 만든 `Holding.create(...)`)를 호출하고, 반환값을 `holdingsByAccountId`에 반영한다.
- **영향받는 다른 호출부**: `applyBuyTrade`의 기존 시그니처(6-인자)를 호출하는 다른 곳(시장가 매수 등)이 있는지 구현 착수 전에 `grep -rn "applyBuyTrade" src/main`으로 재확인한다 — 기존 시그니처는 그대로 유지되므로(내부 구현만 새 6+1-인자 오버로드로 위임) 컴파일 영향은 없어야 하지만, 반환 타입이 `void`에서 `Holding`으로 바뀌므로 기존 호출부가 반환값을 받지 않는 문(statement)으로 쓰고 있는지 확인한다.

## 영향받는 파일 목록

| 파일 | 변경 내용 |
|---|---|
| `src/main/java/com/finplay/api/domain/order/repository/OrderRepository.java` | `findByIdInForUpdate(List<Long>)` 추가 |
| `src/main/java/com/finplay/api/domain/account/repository/AccountRepository.java` | `findByIdInForUpdate(List<Long>)` 추가 |
| `src/main/java/com/finplay/api/domain/portfolio/repository/HoldingRepository.java` | `findByAccountIdInAndInstrumentIdForUpdate(List<Long>, Long)` 추가 |
| `src/main/java/com/finplay/api/domain/order/service/LimitOrderFillService.java` | `fillBatch` 재구성(벌크 조회 + 인메모리 맵 갱신), `fillOnePending`의 공용 로직을 잠긴 리소스를 받는 형태로 분리. `fillIfPending`(단건)은 변경 없음 |
| `src/main/java/com/finplay/api/domain/portfolio/service/PortfolioBuyService.java` | `applyBuyTrade`에 잠긴/신규 `Holding`을 받는 오버로드 추가, 반환 타입을 `Holding`으로 변경 |
| `src/test/java/com/finplay/api/domain/order/service/LimitOrderFillBatchAtomicityIntegrationTest.java` | 변경 없음 — 회귀 확인용으로 그대로 통과해야 한다 |
| (신규 테스트 파일, 이름은 구현 시 확정) | 위험 요소 1·2 검증용 통합 테스트 |
| `ai/adr/0024-limit-order-fill-executor.md`·`ai/adr/0025-limit-order-fill-batch-commit.md` | "후속" 절에 이 spec 반영 기록 추가(ADR 본문 자체를 고치지 않고 후속 절에 덧붙인다 — CLAUDE.md 규칙 2) |
| `docs/loadtest/` | 개선 전후 처리 시간 비교 결과 신규 문서(파일명은 구현 시 확정, 예: `limit-order-fill-bulk-lock-benchmark-result.md`) |

## 테스트 계획

- **단위**: `OrderRepository`·`AccountRepository`·`HoldingRepository`의 벌크 조회는 `@DataJpaTest`로 ID 오름차순 반환·존재하지 않는 ID 조용히 누락을 검증한다. `PortfolioBuyServiceTest`에 새 오버로드(잠긴 holding을 받아 저장) 단위 테스트를 추가한다. `LimitOrderFillServiceTest`에 `fillBatch`가 벌크 조회 3종을 순서대로(order→account→holding) 호출하는지 mock 검증을 추가한다.
- **슬라이스**: 해당 없음(신규 controller·DTO 없음).
- **통합**(Testcontainers, ADR-0003 "핵심 시나리오"):
  - 기존 `LimitOrderFillBatchAtomicityIntegrationTest` — **수정 없이 그대로 통과**해야 한다(회귀 확인).
  - 신규: 같은 계좌·같은 청크·같은 신규 종목에 매수 주문 2건 이상 → 유니크 제약 위반 없이 둘 다 체결(위험 요소 2).
  - 신규: 서로 다른 종목의 청크 두 개가 겹치는 계좌 집합을 동시에 체결 → 데드락 없이 둘 다 완료(위험 요소 1). `LimitOrderFillAccountLockContentionIntegrationTest`의 동시성 테스트 패턴(여러 스레드로 동시 체결 유발)을 참고한다.
  - 벤치마크(영구 테스트로 남길지는 구현 시 판단): 계좌풀 소량·단일 종목·단일 가격 지정가 500건 재현 — 개선 전후 처리 시간 비교. `git show d3f5c027`(과거 `LimitOrderFillBatchInsertThroughputBenchmarkTest`, #501 사전 측정 후 범위 밖으로 제거됨)를 참고해 재현 조건을 맞춘다.

## 문서 동기화

- `ai/api-routes.md`·`docs/api/` — controller 변경 없음, 갱신 대상 아님.
- `ai/prd.md` §3 — LMT-002는 이미 "완료"이고 이 작업은 요구사항 ID 상태·API 계약을 바꾸지 않는 리팩터링이므로(CLAUDE.md 규칙 10 "갱신 비대상") 갱신 대상 아님.
- `ai/adr/0024-limit-order-fill-executor.md`·`ai/adr/0025-limit-order-fill-batch-commit.md` — "후속" 절에 이 spec이 그 위에 쌓인 최적화임을 기록(완료 조건에 명시).
