# Tasks: 지정가 체결 비동기 실행기 + 배치 커밋 도입

- [x] `LimitOrderFillExecutorProperties`(+`Config`) 추가 — `order.limit-fill-executor.*`(enabled/partition-count/queue-capacity-per-partition/batch-size), 단위 테스트 + yml 바인딩 테스트
- [x] `LimitOrderFillExecutorRouter` 추가 — 종목별 파티션 라우팅·백프레셔 드롭, 단위 테스트
- [x] `LimitOrderFillService.fillBatch(List<Long>)` 추가 — 청크 원자적 처리, spec 033 샌드박스 로직 보존, 단위 테스트
- [ ] `LimitOrderTriggerListener` 배치 제출로 전환 — `enabled=false` 폴백 경로 유지, 단위 테스트
- [ ] `application.yml`에 `order.limit-fill-executor.*` 기본값 추가
- [ ] 통합 테스트 — 종목별 순서 보장·동시성(`LimitOrderAsyncFillConcurrencyIntegrationTest`), 배치 원자성(`LimitOrderFillBatchAtomicityIntegrationTest`)
- [ ] ADR-0024·ADR-0025 상태를 "구현됨"으로 갱신
- [ ] `./gradlew build` 통과
