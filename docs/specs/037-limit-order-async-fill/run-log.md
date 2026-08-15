# Run Log: 037-limit-order-async-fill

## AI 로그 (에이전트 참조용)
| 시각               | 에이전트 | 실행 명령 | 근거 |
|------------------|---|---|---|
| 2026-08-16 03:29 | planner | ADR-0024·ADR-0025 초안(상태: 제안됨) 작성, `plan.md`·`tasks.md` 작성 | 이슈 #383, `test/limit-order-async-fill` 브랜치의 검증된 설계를 dev 최신 코드 기준으로 재적용 |
| 2026-08-16 03:32 | implementer·tester | `LimitOrderFillExecutorProperties`(+`Config`) 작성, `LimitOrderFillExecutorPropertiesTest`·`YamlTest` 작성 후 `./gradlew test --tests LimitOrderFillExecutorPropertiesTest --tests LimitOrderFillExecutorPropertiesYamlTest` | tasks.md 항목 1 |
| 2026-08-16 03:36 | implementer·tester | `LimitOrderFillExecutorRouter` 작성, `LimitOrderFillExecutorRouterTest` 작성 후 `./gradlew test --tests LimitOrderFillExecutorRouterTest` | tasks.md 항목 2 |
| 2026-08-16 03:38 | implementer·tester | `LimitOrderFillService.fillOnePending`/`fillBatch` 분리, 단위 테스트·`LimitOrderFillBatchAtomicityIntegrationTest` 작성 후 실행 — 서로 다른 두 `Account` 목이 테스트 헬퍼의 하드코딩 id(10L)를 공유해 Mockito 스텁이 덮어써지는 버그 발견, 계좌 공유+예약금 누적 방식으로 수정 | tasks.md 항목 3·6(배치 원자성 부분) |
| 2026-08-16 03:42 | implementer·tester | `LimitOrderTriggerListener` 실행기 위임 방식 전환, `application.yml` 기본값 추가, `LimitOrderTriggerListenerTest` 갱신 후 실행 | tasks.md 항목 4·5 |
| 2026-08-16 03:44 | tester | `LimitOrderAsyncFillConcurrencyIntegrationTest` 작성 후 실행 | tasks.md 항목 6 |
| 2026-08-16 03:46 | tester | 기존 `LimitOrderFillIntegrationTest`가 "체결이 이 스레드에서 동기로 끝난다"는 옛 전제로 짜여 있어 타이밍 경합 가능성 확인, `awaitUntil` 폴링으로 수정 후 재검증 | tasks.md 항목 8 |

## 모니터링 (사람용 요약)
- ADR-0024(종목별 직렬화 실행기)·ADR-0025(배치 커밋)를 dev의 빈 번호(0024·0025)로 작성했다. spec 015(LMT-002) 계약은 바꾸지 않는 내부 실행 방식 변경이라 spec.md 없이 plan.md·tasks.md만으로 진행했고, spec 036이 `CryptoPriceUpdatedEvent`를 바꾸지 않았음을 코드로 확인해 옛 브랜치 설계를 그대로 재적용했다.
- 구현 중 두 가지를 실제로 잡았다: 배치 단위 테스트에서 두 계좌 목이 같은 하드코딩 id를 공유해 Mockito 스텁이 덮어써지는 버그(계좌 공유+예약금 누적으로 수정), 그리고 체결이 비동기로 바뀌면서 기존 `LimitOrderFillIntegrationTest`가 타이밍 경합으로 깨질 수 있던 것(`awaitUntil` 폴링으로 수정).
- ADR 상태를 구현됨으로 갱신하고 `./gradlew build`를 최종 실행해 테스트 전체·SpotBugs·커버리지·포맷검사가 통과함을 확인했다.
