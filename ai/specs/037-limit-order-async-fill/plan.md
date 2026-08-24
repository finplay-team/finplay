# Plan: 지정가 체결 비동기 실행기 + 배치 커밋 도입

## 관련 문서
- 관련 ADR: [ADR-0024](../../adr/0024-limit-order-fill-executor.md)(종목별 직렬화 실행기), [ADR-0025](../../adr/0025-limit-order-fill-batch-commit.md)(배치 커밋)
- 기존 계약: `ai/specs/015-limit-order/spec.md` LMT-002 — 이 작업은 이 계약을 바꾸지 않는다.

## 배경 — 이 spec이 다루는 것

`ai/specs/015-limit-order`의 LMT-002 체결 트리거는 이미 완료돼 있다. 이 spec은 새 사용자 시나리오나 API 계약을 추가하지 않는다 — `LimitOrderTriggerListener.onPriceUpdated`가 가격 피드 스레드 위에서 지정가 체결을 동기·순차 처리하는 현재 구현을, ADR-0024·ADR-0025가 이미 결정한 종목별 직렬화 실행기 + 배치 커밋 방식으로 dev 최신 코드 기준으로 교체하는 구현 작업이다. "왜"는 두 ADR이 정본이고, 여기서는 "무엇을 dev에 반영하는가"만 다룬다.

## 변경 대상 파일

| 파일 | 변경 |
|---|---|
| `src/main/java/com/finplay/api/order/config/LimitOrderFillExecutorProperties.java` | 신규 — `enabled`·`partitionCount`·`queueCapacityPerPartition`·`batchSize` |
| `src/main/java/com/finplay/api/order/config/LimitOrderFillExecutorConfig.java` | 신규 — 프로퍼티 빈 등록 |
| `src/main/java/com/finplay/api/order/service/LimitOrderFillExecutorRouter.java` | 신규 — 종목별 파티션 라우팅 |
| `src/main/java/com/finplay/api/order/listener/LimitOrderTriggerListener.java` | 변경 — 후보를 배치로 잘라 실행기에 제출, `enabled=false`면 기존 동기 경로 유지 |
| `src/main/java/com/finplay/api/order/service/LimitOrderFillService.java` | 변경 — `fillBatch(List<Long>)` 추가, spec 033 샌드박스 현금 조정 로직(`addSandboxCashAdjustment`)은 그대로 보존 |
| `src/main/resources/application.yml` | 변경 — `order.limit-fill-executor.*` 기본값(파티션 8·대기열 200·배치 50) |

## 설정값 — 바꾸지 않는다

`partition-count: 8`, `queue-capacity-per-partition: 200`, `batch-size: 50`. ADR-0024·ADR-0025가 이미 근거를 담고 있다(동시성·백프레셔·배치 원자성 테스트 통과 + 500건 단일 종목 몰림 재현에서 배치 50이 배치 10보다 빠름, 4,418ms vs 6,309ms).

## 테스트 계획

- 단위: `LimitOrderFillExecutorPropertiesTest`(`@DefaultValue`·검증 규칙), `LimitOrderFillExecutorRouterTest`(파티션 라우팅·백프레셔 드롭), `LimitOrderTriggerListenerTest`(배치 분할 제출·`enabled=false` 폴백), `LimitOrderFillServiceTest`(`fillBatch` 단위 동작)
- 슬라이스: `LimitOrderFillExecutorPropertiesYamlTest`(`application.yml` 바인딩)
- 통합(Testcontainers, `ai/adr/0003-testing-strategy.md` 기준 핵심 시나리오): `LimitOrderAsyncFillConcurrencyIntegrationTest`(종목별 순서 보장·동시 틱 처리), `LimitOrderFillBatchAtomicityIntegrationTest`(청크 중간 실패 시 통째 롤백)

## 문서 동기화

- `ai/api-routes.md`·`docs/api-contracts.md` — controller 변경 없음, 갱신 대상 아님(구현 중 재확인).
- `ai/prd.md` §3 — LMT-002는 이미 "완료"이고 이 작업은 요구사항 ID 상태·API 계약을 바꾸지 않으므로 갱신 대상 아님.
