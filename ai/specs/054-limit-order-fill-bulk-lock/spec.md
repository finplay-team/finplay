# Spec: 지정가 체결 청크 내 벌크 락 최적화

## 개요
`ai/specs/037-limit-order-async-fill`(ADR-0024·ADR-0025)가 도입한 종목별 직렬화 실행기 + 청크 배치 커밋은 청크 "간" 트랜잭션 경계를 최적화했지만, 청크 "안"에서는 여전히 주문마다 order→account→holding을 개별 `SELECT ... FOR UPDATE`로 순차 왕복한다. 사전 실측(`docs/loadtest/limit-order-fill-lock-vs-write-phase-benchmark-result.md`, 청크 크기 10/50/100 공통)에서 이 락 구간이 청크 처리 시간의 55~59%를 차지했고, BUY 경로는 holding 락이 "쓰기 구간" 쪽으로 잘못 집계돼 있어 실제 비중은 더 높을 것으로 추정된다. 이 spec은 청크 안의 순차 왕복을 `WHERE id IN (:ids) FOR UPDATE` 형태의 벌크 조회로 묶어, 기존 계약(잠금 순서·청크 원자성·종목 내 선착순 체결)은 그대로 둔 채 왕복 횟수만 줄이는 리팩터링이다. 이슈 [#501](https://github.com/finplay-team/finplay-backend/issues/501).

이 spec은 새 사용자 시나리오나 API 계약을 추가하지 않는다. `ai/specs/015-limit-order`의 LMT-002(체결 트리거) 계약은 한 글자도 바뀌지 않는다 — "왜 이 방식으로 잠그는가"는 LMT-002·ADR-0024·ADR-0025·ADR-0028이 정본이고, 여기서는 "청크 내부 구현을 어떻게 바꾸는가"만 다룬다.

## 사용자 시나리오
- (사용자 시나리오 없음 — 이 spec은 내부 구현 리팩터링이며 API 응답·체결 결과는 리팩터링 전후로 동일하다.)

## 요구사항
- [ ] `OrderRepository`에 청크의 주문 ID 목록 전체를 한 번에 `PESSIMISTIC_WRITE`로 잠그는 벌크 조회를 추가한다. 반환 순서(또는 잠금 획득 순서)는 **주문 ID 오름차순으로 고정**한다.
- [ ] `AccountRepository`에 청크에서 필요한 계좌 ID 목록 전체를 한 번에 `PESSIMISTIC_WRITE`로 잠그는 벌크 조회를 추가한다. **계좌 ID 오름차순 고정**은 필수 — 서로 다른 종목(=서로 다른 파티션 워커)의 청크가 같은 계좌를 서로 다른 순서로 잠그면 데드락이 난다(ADR-0028이 이미 겪은 것과 같은 종류의 함정, 아래 "위험 요소 1" 참고).
- [ ] `HoldingRepository`에 청크의 계좌 ID 목록 + 종목 ID(청크 하나는 항상 단일 종목이므로 고정값) 조합으로 기존 holding 행을 한 번에 `PESSIMISTIC_WRITE`로 잠그는 벌크 조회를 추가한다. **holding ID 오름차순 고정**.
- [ ] `LimitOrderFillService.fillBatch(List<Long>)`가 위 세 벌크 조회를 order→account→holding 순서로 호출하도록 재구성한다. 주문별 순차 개별 조회(`findByIdForUpdate`·`getAccountByIdForUpdate`·`findByAccountIdAndInstrumentIdForUpdate`)를 청크 안에서 반복 호출하지 않는다.
- [ ] `fillIfPending(Long)`/`fillIfPending(Long, LocalDateTime)`(주문 1건 단독 체결 경로, `PracticeOrderSettlementService`가 호출)는 이번 변경의 대상이 아니다 — 기존 순차 개별 조회 방식을 그대로 유지한다.
- [ ] 청크 안에서 신규 생성된 holding(그 청크에서 처음 매수해 아직 DB에 없던 행)은 처리 직후 인메모리 맵에 반영해, 같은 청크의 다음 주문이 같은 (계좌, 종목) 조합이면 그 맵을 재사용한다 — 두 번째 INSERT를 시도하지 않는다(아래 "위험 요소 2").
- [ ] `PortfolioBuyService.applyBuyTrade`를 호출부가 이미 잠근(또는 이미 생성한) holding을 넘겨받는 형태로 확장한다 — BUY 경로의 holding 락이 이 메서드 내부(`findByAccountIdAndInstrumentIdForUpdate`)에서 걸리므로, 벌크 락으로 묶으려면 이 서비스도 함께 바꿔야 한다(아래 "위험 요소 3"). 기존 단일 호출부(시장가 매수 등)의 시그니처·동작은 그대로 유지한다.

## 비즈니스 규칙
- **잠금 순서는 바뀌지 않는다**: attempt(해당 시) → order → account → holding. 벌크 조회로 바뀌는 것은 "각 단계 안에서 몇 번 왕복하는가"이지 "어느 단계를 먼저 잠그는가"가 아니다.
- **청크 원자성은 바뀌지 않는다**(ADR-0025 §결정 3): 청크 안의 한 건이라도 실패하면 청크 전체가 롤백되고, 롤백된 주문은 여전히 `PENDING`으로 남아 다음 가격 틱이 다시 후보로 집어낸다. 벌크 조회로 존재하지 않는 주문 ID는 결과 집합에서 조용히 빠지므로(개별 `findByIdForUpdate`처럼 그 자리에서 예외를 던지지 않는다), **처리 루프가 원래 주문 ID 목록을 순회하며 조회 결과 맵에 없는 ID를 만나면 기존과 동일한 예외 메시지("체결 대상 주문을 찾을 수 없습니다. orderId=...")로 직접 던져야 한다** — 그래야 기존 `LimitOrderFillBatchAtomicityIntegrationTest`의 "존재하지 않는 orderId 포함 시 청크 전체 롤백" 시나리오가 그대로 성립한다.
- **종목 내 선착순 체결 계약(spec 015 LMT-002)은 바뀌지 않는다**: 처리(체결) 순서는 `fillBatch`에 전달된 주문 ID 목록의 원래 순서(`requestedAt asc, id asc`)를 그대로 따른다. 벌크 락 획득을 위한 "ID 오름차순 정렬"은 락을 거는 순간의 정렬일 뿐이며, 락을 다 잡은 뒤의 실제 체결 처리 순서와는 별개다 — 이 둘을 혼동해 체결 처리 자체를 ID 오름차순으로 바꾸면 LMT-002 계약을 깨뜨린다.
- **청크 하나는 항상 단일 종목이다**: `LimitOrderTriggerListener.submitInBatches`가 종목 하나의 가격 틱에서 나온 후보만 청크로 자르므로, `fillBatch` 안의 모든 주문은 같은 `instrumentId`를 공유한다. holding 벌크 조회가 "계좌 ID 목록 + 고정 종목 ID"로 충분한 것은 이 전제 때문이며, 이 전제가 깨지면(예: 여러 종목을 한 청크에 섞는 변경) holding 벌크 조회 설계를 다시 검토해야 한다.
- **실행기 폴백 경로(`order.limit-fill-executor.enabled=false`)는 영향받지 않는다** — 그 경로는 `fillIfPending`(단건)을 그대로 호출하며, 이번 변경 대상이 아니다.

## 범위 제외
- `partition-count`·`queue-capacity-per-partition`·`batchSize` 파라미터 재검토 — 별도 이슈.
- 청크 "간" 트랜잭션 경계 최적화 — 이미 ADR-0025 영역이며 이 spec은 건드리지 않는다.
- `LimitOrderFillService`의 practice(튜토리얼) attempt 귀속 preflight(`findPracticeFillAttribution`)를 벌크로 묶는 것 — `fillBatch`의 후보는 `findPendingLimitOrdersToFill`이 `practiceAttemptId is null`로 이미 걸러낸 것들이라 이 preflight는 항상 빈 결과로 짧게 끝난다(PK 조건의 인덱스 조회). 이번 최적화가 다루는 "order→account→holding" 왕복에 포함되지 않는다.
- `LimitOrderFillService.fillBuy`가 샌드박스(튜토리얼 샘플) 종목에 쓰는 `TutorialAccountService.getOrCreateForUpdate` 락 — 이슈 #501 완료 조건이 명시한 저장소는 `OrderRepository`·`AccountRepository`·`HoldingRepository` 셋뿐이며, `TutorialAccount` 락은 이번 벌크화 대상이 아니다. 청크 안에 튜토리얼 샘플 종목 주문이 섞여 있어도 그 계좌 조회는 기존처럼 주문별로 개별 처리된다.
- 시장가 매수(`OrderExecutionService`)·`LimitOrderCancelService`·`LimitOrderModifyService`의 단건 락 경로 — 이 spec은 `fillBatch`만 바꾼다.

## 완료 조건
- [ ] `OrderRepository`·`AccountRepository`·`HoldingRepository`에 청크 전체를 ID 오름차순으로 한 번에 잠그는 벌크 `FOR UPDATE` 조회가 추가돼 있다.
- [ ] `LimitOrderFillService.fillBatch`가 이 벌크 조회를 쓰도록 재구성돼 있고, 잠금 순서(order→account→holding)·청크 원자성·종목 내 선착순 체결 계약이 유지된다.
- [ ] 기존 `LimitOrderFillBatchAtomicityIntegrationTest`(존재하지 않는 orderId 포함 시 청크 전체 롤백 시나리오 포함)가 수정 없이 그대로 통과한다.
- [ ] 같은 계좌가 같은 청크 안에서 같은 종목에 지정가를 2건 이상 걸어둔 시나리오(둘 다 신규 매수, holding 미존재)를 검증하는 신규 테스트가 통과한다 — `uk_holdings_account_instrument` 유니크 제약 위반 없이 두 번째 주문이 첫 번째가 만든 holding을 그대로 이어받아 체결된다.
- [ ] 서로 다른 종목(=서로 다른 파티션)의 청크가 같은 계좌 집합을 동시에 잠그는 상황에서 데드락이 나지 않음을 검증하는 신규 테스트가 통과한다.
- [ ] 같은 재현 조건(계좌풀 소량·단일 종목·단일 가격 지정가 500건)으로 개선 전후 처리 시간을 실측 비교한 결과가 `docs/loadtest/`에 남아 있다.
- [ ] `ai/adr/0024-limit-order-fill-executor.md`·`ai/adr/0025-limit-order-fill-batch-commit.md`의 "후속" 절에 이 최적화가 그 위에 쌓인 것임을 기록한다.
- [ ] `./gradlew build` 통과.
