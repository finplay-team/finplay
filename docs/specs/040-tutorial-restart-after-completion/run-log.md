# Run Log: 040-tutorial-restart-after-completion

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| implementer | `.\gradlew.bat compileJava compileTestJava` | plan.md "소스 변경 대상과 근거" #1, spec.md TUTORIAL-RESTART-001 |

## 모니터링 (사람용 요약)
- `PracticeAttempt.restart()`의 COMPLETED 가드와 `PracticeAttemptRestartService.restart()`의 완료 단락 분기 제거, 단위/슬라이스 테스트 추가·갱신, 컴파일 통과.
