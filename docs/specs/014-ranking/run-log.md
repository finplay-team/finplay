# Run Log: 014-ranking

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 15:53 | implementer | `./gradlew compileJava compileTestJava` | plan.md "신규 이벤트·인프라 설계"(1~4), ADR-0002 |
| 16:01 | implementer | `./gradlew compileJava compileTestJava` | plan.md "5) RankingService"·"6) 순위 보정 흐름"·"7) 랭킹 대상 제외" |
| 16:09 | implementer | `./gradlew compileJava compileTestJava` | plan.md "API 설계"·"입력 명세", tasks.md 3번, docs/conventions.md API 규칙 |
| 16:24 | implementer | `./gradlew compileJava compileTestJava` | plan.md "테스트 계획" 통합테스트 절, tasks.md 4번, agent-mistakes.md 2026-07-30(비-@Transactional 커밋 오염), OrderListIntegrationTest.java 주석(nickname VARCHAR(50)) |
| 16:47 | implementer | `./gradlew test --tests RankingServiceTest --tests AccountRepositoryTest` | docs/conventions.md(도메인 간 참조는 service만), ADR-0002, review-187 차단 지적 |

## 모니터링 (사람용 요약)
- 15:53 — RealizedPnlUpdatedEvent·RankingStore·RankingEventListener·RankingService(스텁) 신설, OrderExecutionService 이벤트 발행 추가, 컴파일 통과.
- 16:01 — RankingService 실제 구현(refreshScore·getRankings·순위 보정), AccountRepository.findAllByIdInFetchUser 추가, RankingServiceTest 신설, 컴파일 통과.
- 16:09 — RankingListResponse/Item 정식화(스텁 주석 제거, 필드는 기존과 동일 확인), RankingController 신설(limit 미검증·서비스로 그대로 전달), RankingControllerTest 신설, api-routes.md·api-contracts.md에 GET /api/rankings 계약 추가, 전체 컴파일 통과.
- 16:24 — RankingIntegrationTest 신설(비-@Transactional, CRYPTO 시장만 사용해 시드 테이블 오염 회피), 6개 시나리오(체결반영·이벤트순서역전·커밋전실패·Redis장애·매도이력없음제외·동점공동순위) 작성, 컴파일 통과.
- 16:33 — tester가 RankingIntegrationTest 실행 중 ObjectMapper 버전 불일치 버그 발견·수정(com.fasterxml → tools.jackson, LocalDateTime 역직렬화 실패). 수정 후 6/6 통과, 기존 TradeIntegrationTest·OrderListIntegrationTest도 회귀 없음 확인.
- 16:47 — 리뷰 차단(RankingService가 AccountRepository 직접 주입) 반영: AccountService에 findByIdOrEmpty·findAllByIdInFetchUser 위임 메서드 추가, RankingService는 AccountService만 주입하도록 변경, plan.md ADR-0002 서술 정정, AccountRepositoryTest에 findAllByIdInFetchUser 슬라이스 테스트 추가. RankingServiceTest 8/8·AccountRepositoryTest 4/4 통과.
