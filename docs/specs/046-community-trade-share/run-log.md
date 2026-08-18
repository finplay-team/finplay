# Run Log: 046-community-trade-share

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 12:10 | implementer | `Read PostSellFeedbackReader/ContextReader/Service` | LLM/뉴스 미포함 확인(spec 지시) |
| 12:30 | implementer | `.\gradlew.bat compileJava` | ADR-0002, ADR-0004, conventions.md |
| 12:50 | implementer | `.\gradlew.bat compileTestJava` | 기존 호출부 시그니처 회귀 확인 |
| 13:00 | implementer | `.\gradlew.bat spotlessApply` | conventions.md 포맷 규칙 |
| 13:05 | implementer | `.\gradlew.bat test --tests PostSellFeedbackServiceTest` | ADR-0003 단위 테스트 |
| 13:10 | implementer | `.\gradlew.bat test --tests CommunityPostServiceTest` | ADR-0003 단위 테스트 |
| 13:12 | implementer | `.\gradlew.bat test --tests CommunityPostControllerTest` | ADR-0003 @WebMvcTest |
| 13:16 | implementer | `.\gradlew.bat test --tests CommunityPostShareTradeIntegrationTest` | ADR-0003 Testcontainers 통합 테스트 |
| 13:22 | implementer | `.\gradlew.bat test --tests community.* --tests feedback.*` | ADR-0003 전체 회귀(181 suite, 실패 0) |
| 13:35 | implementer | `getTradeShareSummary` 재구현 — `loadContext`만 사용, `reader.read()` 호출 제거 | 리뷰 차단 2건(무거운 경로, 무트랜잭션 코인 리더가 `@Transactional` 안에서 호출됨, 이슈 #282 재현) |
| 13:40 | implementer | `.\gradlew.bat compileJava compileTestJava spotlessCheck` | 재구현 후 회귀 확인 |
| 13:42 | implementer | `.\gradlew.bat test --tests PostSellFeedbackServiceTest --tests CommunityPostServiceTest --tests CommunityPostControllerTest --tests CommunityPostShareTradeIntegrationTest` | ADR-0003, 리뷰 수정 검증 |
| 13:55 | implementer | `git merge origin/dev` — PR #442(좋아요·정렬) 충돌 해소 | 코디네이터 지시, likeCount·likedByMe·sharedTrade 셋 다 유지 |
| 14:10 | implementer | `.\gradlew.bat compileJava compileTestJava spotlessCheck` | 병합 후 회귀 확인 |
| 14:12 | implementer | `.\gradlew.bat test --tests CommunityPostServiceTest --tests CommunityPostControllerTest --tests CommunityPostLikeServiceTest` | 병합 검증(양쪽 기능 공존) |
| 14:15 | implementer | `.\gradlew.bat test --tests CommunityPostShareTradeIntegrationTest --tests CommunityPostLikeSortIntegrationTest --tests CommunityPostLikeConcurrencyIntegrationTest` | ADR-0003 Testcontainers, 병합 검증 |

## 모니터링 (사람용 요약)
- 13:22 — V45(작성 당시 V42) 마이그레이션 + community/feedback 구현 완료, 단위·슬라이스·통합 테스트 전부 통과(community+feedback 181 suite 회귀 포함), docs 3종 동기화.
- 13:42 — 리뷰 차단 2건 반영: `getTradeShareSummary`가 `PostSellFeedbackContextReader.loadContext`만 쓰도록 재구현(무거운 `reader.read()`·빗썸 REST 호출 제거), 관련 테스트 전부 재통과.
- 14:15 — PR #442(좋아요·정렬) merge 충돌 해소. `CommunityPostResponse`에 `likeCount`·`likedByMe`·`sharedTrade` 셋 다 유지, `CommunityPostService.createPost/getPost/getPosts`에 좋아요 배치 조회와 sharedTrade 개별 조회를 함께 유지. docs 4곳(작성·단건·목록·수정) 병합. 관련 테스트(서비스·컨트롤러·좋아요·sharedTrade 통합) 전부 통과.

## 배포 후속 (2026-08-18)

- `V42__add_shared_trade_id_to_community_posts.sql` → `V45__...`로 리네임. #446이 머지될 때 이미 배지 PR #439(V43·V44)가 먼저 머지돼 운영 DB가 V44까지 올라간 상태였고, 뒤늦게 들어온 V42가 과거 번호가 돼 Flyway가 `Detected resolved migration not applied to database: 42`로 기동을 거부했다(배포 실패, 라이브 색은 유지됨). V42는 검증 단계에서 막혀 **어떤 DB에도 적용된 적이 없어** 리네임이 안전하다(ADR-0004가 금지하는 "적용된 마이그레이션 수정"에 해당하지 않음).
- 교훈: 마이그레이션 번호는 열린 PR과 겹치지 않게 정하는 것만으로 부족하다 — **머지 순서가 뒤바뀌면 낮은 번호를 든 쪽이 반드시 깨진다.** 번호를 띄워 잡았으면 어느 PR이 먼저 머지돼야 하는지도 PR 본문에 남긴다.
