# Run Log: 014-ranking

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 15:53 | implementer | `./gradlew compileJava compileTestJava` | plan.md "신규 이벤트·인프라 설계"(1~4), ADR-0002 |
| 16:01 | implementer | `./gradlew compileJava compileTestJava` | plan.md "5) RankingService"·"6) 순위 보정 흐름"·"7) 랭킹 대상 제외" |
| 16:09 | implementer | `./gradlew compileJava compileTestJava` | plan.md "API 설계"·"입력 명세", tasks.md 3번, docs/conventions.md API 규칙 |

## 모니터링 (사람용 요약)
- 15:53 — RealizedPnlUpdatedEvent·RankingStore·RankingEventListener·RankingService(스텁) 신설, OrderExecutionService 이벤트 발행 추가, 컴파일 통과.
- 16:01 — RankingService 실제 구현(refreshScore·getRankings·순위 보정), AccountRepository.findAllByIdInFetchUser 추가, RankingServiceTest 신설, 컴파일 통과.
- 16:09 — RankingListResponse/Item 정식화(스텁 주석 제거, 필드는 기존과 동일 확인), RankingController 신설(limit 미검증·서비스로 그대로 전달), RankingControllerTest 신설, api-routes.md·api-contracts.md에 GET /api/rankings 계약 추가, 전체 컴파일 통과.
