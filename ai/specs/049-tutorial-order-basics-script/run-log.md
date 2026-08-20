# Run Log: 049-tutorial-order-basics-script

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 21:20 | implementer | `.\gradlew.bat spotlessApply compileJava compileTestJava` | tasks.md 1번, plan.md §1(로더 구조·basePrice·generateHistory 분기), ADR-0002 |

## 모니터링 (사람용 요약)
- 21:20 — `basePrice`를 대본 파일 필드로 옮기고 `TutorialScenarioScriptId`로 대본 2개를 등록, 041 대본의 canonical 가격 120분·과거 29봉×18조합을 커밋 전후 값으로 대조해 전부 동일함을 확인, 컴파일 통과.
