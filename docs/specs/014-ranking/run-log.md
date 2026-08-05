# Run Log: 014-ranking

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 15:53 | implementer | `./gradlew compileJava compileTestJava` | plan.md "신규 이벤트·인프라 설계"(1~4), ADR-0002 |
| 16:01 | implementer | `./gradlew compileJava compileTestJava` | plan.md "5) RankingService"·"6) 순위 보정 흐름"·"7) 랭킹 대상 제외" |
| 16:09 | implementer | `./gradlew compileJava compileTestJava` | plan.md "API 설계"·"입력 명세", tasks.md 3번, docs/conventions.md API 규칙 |
| 16:24 | implementer | `./gradlew compileJava compileTestJava` | plan.md "테스트 계획" 통합테스트 절, tasks.md 4번, agent-mistakes.md 2026-07-30(비-@Transactional 커밋 오염), OrderListIntegrationTest.java 주석(nickname VARCHAR(50)) |
| 16:47 | implementer | `./gradlew test --tests RankingServiceTest --tests AccountRepositoryTest` | docs/conventions.md(도메인 간 참조는 service만), ADR-0002, review-187 차단 지적 |
| 19:11 | implementer | `./gradlew test --tests AccountServiceTest --tests RankingStoreTest` | PR #196 리뷰(참고: `AccountService` 위임 테스트 부재, `countStrictlyGreater` score+1 오버플로) |
| 19:54 | implementer | `./gradlew test --tests "com.finplay.api.ranking.*"` | PR #196 리뷰(차단 2건+권장 1건), plan.md 8절(신규) |
| 20:36 | implementer | `./gradlew test --tests RankingServiceTest` | 독립 리뷰어 재검토 지적(경계 동점 없음+유령 조합 시 backfill 누락), plan.md 8-4절(신규) |
| (RANK-002 1번) implementer | `./gradlew compileJava compileTestJava` + `./gradlew test --tests RankingStoreTest --tests RankingServiceTest` | tasks.md "RANK-002" 1번, plan.md "RANK-002 설계"(score·getMyRanking 코드 스케치) |

## 모니터링 (사람용 요약)
- 15:53 — RealizedPnlUpdatedEvent·RankingStore·RankingEventListener·RankingService(스텁) 신설, OrderExecutionService 이벤트 발행 추가, 컴파일 통과.
- 16:01 — RankingService 실제 구현(refreshScore·getRankings·순위 보정), AccountRepository.findAllByIdInFetchUser 추가, RankingServiceTest 신설, 컴파일 통과.
- 16:09 — RankingListResponse/Item 정식화(스텁 주석 제거, 필드는 기존과 동일 확인), RankingController 신설(limit 미검증·서비스로 그대로 전달), RankingControllerTest 신설, api-routes.md·api-contracts.md에 GET /api/rankings 계약 추가, 전체 컴파일 통과.
- 16:24 — RankingIntegrationTest 신설(비-@Transactional, CRYPTO 시장만 사용해 시드 테이블 오염 회피), 6개 시나리오(체결반영·이벤트순서역전·커밋전실패·Redis장애·매도이력없음제외·동점공동순위) 작성, 컴파일 통과.
- 16:33 — tester가 RankingIntegrationTest 실행 중 ObjectMapper 버전 불일치 버그 발견·수정(com.fasterxml → tools.jackson, LocalDateTime 역직렬화 실패). 수정 후 6/6 통과, 기존 TradeIntegrationTest·OrderListIntegrationTest도 회귀 없음 확인.
- 16:47 — 리뷰 차단(RankingService가 AccountRepository 직접 주입) 반영: AccountService에 findByIdOrEmpty·findAllByIdInFetchUser 위임 메서드 추가, RankingService는 AccountService만 주입하도록 변경, plan.md ADR-0002 서술 정정, AccountRepositoryTest에 findAllByIdInFetchUser 슬라이스 테스트 추가. RankingServiceTest 8/8·AccountRepositoryTest 4/4 통과.
- 19:11 — PR #196 리뷰 참고 항목 반영: AccountServiceTest에 findByIdOrEmpty·findAllByIdInFetchUser 위임 단위 테스트 추가, RankingStore.countStrictlyGreater의 score+1이 Long.MAX_VALUE에서 오버플로하는 결함을 클램핑으로 방지.
- 19:54 — PR #196 리뷰 차단 2건+권장 1건 반영: calculateRanks가 DB에 없는 accountId를 필터링(NPE→500 방지), RankingStore.findAllAtScore 신설+fetchWindowResolvingBoundaryTies로 limit 경계 동점자를 userId 오름차순 정책대로 병합, refreshScore를 REQUIRES_NEW(+readOnly)로 전환해 AFTER_COMMIT 콜백의 stale 1차 캐시 문제 해결. RankingIntegrationTest에 REQUIRED로 되돌리면 실패하는 것을 확인한 회귀 테스트 추가. ranking 패키지 전체 34/34 통과(RankingServiceTest 11·RankingStoreTest 5·RankingIntegrationTest 7·RankingControllerTest 10·RankingEventListenerTest 1).
- 20:36 — 독립 리뷰어 재검토에서 나온 잔여 문제(동점 없는 경계에서 유령 계좌를 필터링하면 결과가 limit보다 적어짐) 반영: fetchWindowResolvingBoundaryTies가 해당 분기에서 limit개로 미리 자르지 않고 limit+1개를 그대로 반환, calculateRanks의 사후 필터링이 여유분으로 보충. plan.md 8-4절 신설. ranking 패키지 35/35 통과.
- (RANK-002 1번) — RankingStore.score(Market, Long) 신설(ZSCORE 단건 조회, member 없으면 null), RankingService.getMyRanking(userId, market) 신설(@Transactional(readOnly=true), AccountService.getAccountFor + RankingStore.score/countStrictlyGreater 재사용) + MyRankingResponse record 선행 추가. 컨트롤러·라우트는 이번 항목 범위 아님(다음 항목). RankingStoreTest·RankingServiceTest 확장 4건 통과, compileJava/compileTestJava 통과.
