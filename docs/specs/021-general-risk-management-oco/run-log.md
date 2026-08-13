# Run Log: 021-general-risk-management-oco

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 14:39 | implementer | `.\gradlew.bat spotlessApply compileJava` | plan.md 데이터 모델·오류 코드, ADR-0004, ADR-0002 |
| 14:59 | implementer | `.\gradlew.bat spotlessApply compileJava spotbugsMain` | plan.md 일반 경로 3~9단계·잠금 순서, 019 계산 정책, 015 예약 원장 |
| (#348) | implementer | `& gradlew.bat compileJava` (JAVA_HOME=ms-17.0.20) | plan.md "API 설계"·"일반 경로 검증 순서"·"멱등성"·"잠금 순서" 표, docs/conventions.md 레이어 규칙, ADR-0002 |
| (#348) | implementer | `& gradlew.bat test --tests "com.finplay.api.common.ErrorCodeTest"` (JAVA_HOME=ms-17.0.20) | 오케스트레이터 지적: 새 오류 코드 2건 추가로 `ErrorCodeTest`의 소진적 개수·매핑·메시지 단정이 깨짐 |

## 모니터링 (사람용 요약)
- 14:39 — exit_plans/conditions/idempotency_keys V35 migration + 엔티티·Repository·오류 코드 2건 추가, 컴파일 통과.
- 14:59 — ExitPricePolicy(019 공식)+ExitPlanCreationService(holding 잠금·PENDING 1건·예약·저장) 추가, 컴파일·SpotBugs 통과.
- 회귀 발견·수정: `ExitPlanRepositoryTest`의 `educationalPlan` 헬퍼가 강화된 팩토리 불변식(교육 경로 buyTrade non-null)과 맞지 않아 2건 실패 → 실제 buyTrade를 생성해 전달하도록 수정(`4f3d880`). 전체 `./gradlew build` 통과.
- 리뷰(reviewer, 리뷰 모드): 차단 0건. [권장] `ExitPlanCreationService.lockHolding`이 `PortfolioSellService.getHoldingForUpdate`를 재사용하는데, 그 메서드 JavaDoc은 "이미 예약 확인된 값" 전제의 비동기 리스너 문맥용이라 동기 생성 경로와 호출 문맥이 어긋난다 — **다음 이슈(#348, 일반 경로 생성·취소)에서 전용 접근자 분리 또는 JavaDoc 갱신을 검토할 것.**
- (#348) 일반 경로 생성·취소 구현: `POST /api/exit-plans`(intentionId 생략, 있으면 400)·`DELETE /api/exit-plans/{id}` 추가. 기존 `ExitPlanCreationService`/`ExitPlan` 생성자는 손대지 않고(#347 테스트 회귀 방지), 멱등키 저장은 새 `ExitPlanIdempotentCreationService`(별도 빈, 같은 트랜잭션)로 분리. 취소는 `holding → plan` 순서로 각각 재조회+잠금해 트리거 엔진과 잠금 순서를 맞췄다. 오류 코드 `EXIT_PLAN_NOT_FOUND`·`EXIT_PLAN_NOT_PENDING` 신설. `compileJava` 통과, 테스트는 작성하지 않음.
- 회귀 발견·수정(오케스트레이터 보고): 새 오류 코드 2건 추가로 `ErrorCodeTest`의 소진적 단정(`hasSize(39)`→41, HTTP 상태 `Map.ofEntries`, `exitPlanErrorsKeepPublicDefaultMessages`)이 깨져 `EXIT_PLAN_NOT_FOUND`·`EXIT_PLAN_NOT_PENDING` 항목을 추가해 수정. `ErrorCodeTest` 단독 재실행 통과.
