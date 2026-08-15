# Run Log: 037-limit-order-async-fill

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 2026-08-16 03:29 | planner | ADR-0024·ADR-0025 초안(상태: 제안됨) 작성, `plan.md`·`tasks.md` 작성 | 이슈 #383, `test/limit-order-async-fill` 브랜치의 검증된 설계를 dev 최신 코드 기준으로 재적용 |

## 모니터링 (사람용 요약)
- ADR-0024(종목별 직렬화 실행기)·ADR-0025(배치 커밋)를 dev의 빈 번호(0024·0025)로 작성했다. spec 015(LMT-002) 계약은 바꾸지 않는 내부 실행 방식 변경이라 spec.md 없이 plan.md·tasks.md만으로 진행했고, spec 036이 `CryptoPriceUpdatedEvent`를 바꾸지 않았음을 코드로 확인해 옛 브랜치 설계를 그대로 재적용했다.
- 구현 중 두 가지를 실제로 잡았다: 배치 단위 테스트에서 두 계좌 목이 같은 하드코딩 id를 공유해 Mockito 스텁이 덮어써지는 버그(계좌 공유+예약금 누적으로 수정), 그리고 체결이 비동기로 바뀌면서 기존 `LimitOrderFillIntegrationTest`가 타이밍 경합으로 깨질 수 있던 것(`awaitUntil` 폴링으로 수정).
- ADR 상태를 구현됨으로 갱신하고 `./gradlew build`를 최종 실행해 테스트 전체·SpotBugs·커버리지·포맷검사가 통과함을 확인했다.
