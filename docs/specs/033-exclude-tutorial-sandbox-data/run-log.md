# Run Log: 033-exclude-tutorial-sandbox-data

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `HoldingRepository`/`BuyTradeJournalRepositoryImpl`/`SellTradeJournalRepositoryImpl` 조건절 수정 후 `.\gradlew.bat compileJava compileTestJava` | plan.md 1·2번, spec SANDBOX-EXCL-001·002 |

## 모니터링 (사람용 요약)
- 포트폴리오·투자일기 조회 필터(tasks.md 항목 1) 구현, `@DataJpaTest`에 샌드박스 제외·`findHoldingId`/`findHoldingForOwner` 회귀 테스트 추가, 컴파일 통과.
