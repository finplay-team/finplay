# Run Log: 021-general-risk-management-oco

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 14:39 | implementer | `.\gradlew.bat spotlessApply compileJava` | plan.md 데이터 모델·오류 코드, ADR-0004, ADR-0002 |
| 14:59 | implementer | `.\gradlew.bat spotlessApply compileJava spotbugsMain` | plan.md 일반 경로 3~9단계·잠금 순서, 019 계산 정책, 015 예약 원장 |

## 모니터링 (사람용 요약)
- 14:39 — exit_plans/conditions/idempotency_keys V34 migration + 엔티티·Repository·오류 코드 2건 추가, 컴파일 통과.
- 14:59 — ExitPricePolicy(019 공식)+ExitPlanCreationService(holding 잠금·PENDING 1건·예약·저장) 추가, 컴파일·SpotBugs 통과.
