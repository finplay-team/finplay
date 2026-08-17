# Run Log: 040-tutorial-restart-after-completion

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| implementer | `.\gradlew.bat compileJava compileTestJava` | plan.md "소스 변경 대상과 근거" #1, spec.md TUTORIAL-RESTART-001 |
| implementer | `.\gradlew.bat compileJava` | plan.md "소스 변경 대상과 근거" #3, spec.md TUTORIAL-RESTART-003 |

## 모니터링 (사람용 요약)
- `PracticeAttempt.restart()`의 COMPLETED 가드와 `PracticeAttemptRestartService.restart()`의 완료 단락 분기 제거, 단위/슬라이스 테스트 추가·갱신, 컴파일 통과.
- `PracticeAttemptService.ensureAttempt()`에서 completion 존재+attempt 비완료+기존 행 조합의 `PRACTICE_EVIDENCE_MISSING` 오판 제거(현재 상태 반환), 컴파일 통과. 테스트는 이번 항목 범위 아님(오케스트레이터 지시).
