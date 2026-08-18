# Run Log: 045-community-likes-sort

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `ls db/migration` (V38 최신 확인 후 V41 사용) | plan.md "데이터 모델" 절, ADR-0004 |
| - | implementer | `.\gradlew.bat compileJava` | plan.md 엔티티·리포지토리 설계 |
| - | implementer | `.\gradlew.bat spotlessApply` | docs/conventions.md 포맷 규칙 |
| - | implementer | `.\gradlew.bat compileJava` (좋아요 API 추가 후) | plan.md "API 설계"·"패키지·클래스 설계" 절 |
| - | implementer | `.\gradlew.bat compileJava` (인기순 정렬 추가 후) | plan.md "패키지·클래스 설계"(findPosts 시그니처 변경) |
| - | implementer | `.\gradlew.bat compileTestJava` (findPosts/getPosts 시그니처 변경에 맞춰 기존 테스트 호출부 수정 후) | 오케스트레이터 지시 — 컴파일 깨짐 재현 확인 |
| - | implementer | `.\gradlew.bat compileJava`·`compileTestJava` (게시물 응답 확장 후) | plan.md "패키지·클래스 설계"(CommunityPostResponse.from(post, likedByMe), 배치 조회) |
| - | implementer | `.\gradlew.bat compileTestJava` (통합 테스트 신규 작성 후) | spec.md "완료 조건" 절(10개 시나리오), ADR-0003(핵심 시나리오는 Testcontainers 통합 테스트) |
| - | implementer | `.\gradlew.bat compileJava compileTestJava` (tester 실행 결과 13건 중 3건 실패 수정 후) | tester 보고 — decrementLikeCount flush 누락, 테스트 nickname 50자 초과 |
| - | implementer | `.\gradlew.bat compileJava compileTestJava spotlessCheck` (동시성 방어 구현 후) | PR #442 2차 리뷰 차단 2건(데드락 500·like_count 음수), spec.md LIKE-001 멱등·SORT-001, docs/agent-mistakes.md(CRLF·spotless) |
| - | tester | `docker info` (PowerShell 툴) — Docker Desktop 기동 후 서버 29.4.2 확인 | docs/agent-mistakes.md 2026-08-03 행(환경 관련 로그는 현재 사실로 인용하기 전에 1회 확인) |
| - | tester | `.\gradlew.bat test --tests "*CommunityPostLikeConcurrencyIntegrationTest"` (수정 적용 상태) | ADR-0003(핵심 시나리오 Testcontainers 통합 테스트), PR #442 2차 리뷰 "mock으로는 재현되지 않는다" |
| - | tester | 같은 명령 — `CommunityPostLikeService`를 `git checkout HEAD --`로 되돌리고 `decrementLikeCount` 하한 가드를 제거한 상태 | 새 테스트가 실제로 버그를 재현하는지 실측(수정 전 3/3 실패: 데드락 `CannotAcquireLockException` 2건, `ObjectOptimisticLockingFailureException` 1건) |
| - | tester | `.\gradlew.bat test --tests "*CommunityPostRepositoryTest.decrementLikeCountLeavesZeroUntouchedInsteadOfGoingNegative"` (하한 가드 제거 상태) | 가드 없는 감소가 `like_count`를 -1로 내리는지 실측(expected 0 but was -1) |
| - | tester | `.\gradlew.bat spotlessApply` 후 `test --tests` 6개 클래스 일괄 실행 | docs/agent-mistakes.md 2026-08-01 행(Write 도구 신규 Java 파일 CRLF), 이번 항목 관련 회귀 확인 |
| - | tester | `.\gradlew.bat compileJava compileTestJava spotlessCheck` (마무리) | 이 머신에서 전체 `build` 미실행(agent-mistakes 2026-08-11 행) |

## 모니터링 (사람용 요약)
- 데이터 모델 항목(V41 마이그레이션, CommunityPostLike 엔티티, likeCount 필드, 원자적 증감·배치 조회 리포지토리) 구현, compileJava 통과.
- 좋아요 API 항목(CommunityPostLikeService·Controller·Response, POST/DELETE 멱등) 구현, api-routes.md·api-contracts.md 갱신, compileJava 통과.
- 인기순 정렬 항목(findPostsOrderByCreatedAtDesc → findPosts(sort) 변경, 컨트롤러 sort 파라미터·검증) 구현, api-routes.md·api-contracts.md 갱신, compileJava 통과.
- 시그니처 변경으로 깨진 기존 테스트 3개(CommunityPostRepositoryTest·CommunityPostServiceTest·CommunityPostControllerTest) 호출부를 새 시그니처(`findPosts(..., "latest")`/`getPosts(..., "latest")`)로 수정, compileTestJava 통과.
- 게시물 응답 확장 항목(CommunityPostResponse/ListResponse에 likeCount·likedByMe 추가, getPost/getPosts에 @AuthenticationPrincipal·목록 배치 조회 반영) 구현, api-routes.md·api-contracts.md 갱신, 시그니처 변경으로 깨진 기존 테스트 2개(CommunityPostControllerTest·CommunityPostServiceTest) 호출부 수정, compileJava·compileTestJava 통과.
- 통합 테스트 항목(`CommunityPostLikeSortIntegrationTest`, spec.md 완료 조건 12개 불릿을 13개 테스트로 커버 — 좋아요 표시/취소 멱등, 본인 게시물 허용, 404, 인기순 정렬·동률 처리, 하위 호환, 종목 필터 조합, 잘못된 sort 400, 삭제 시 좋아요 정리) 작성, compileTestJava 통과. 실행은 tester가 수행.
- tester 실행 결과 13건 중 3건 실패 수정: `CommunityPostRepository.incrementLikeCount`/`decrementLikeCount`에 `flushAutomatically = true` 추가(clearAutomatically가 같은 트랜잭션의 미반영 좋아요 행 save/delete를 flush 없이 버리던 버그), `CommunityPostLikeSortIntegrationTest.createUser`의 nickname을 50자로 절단(길이 초과 시 `DataIntegrityViolationException`). compileJava·compileTestJava 통과.
- 동시성 방어 항목(PR #442 2차 리뷰 차단 2건): `CommunityPostRepository.findByIdForUpdate`(비관적 쓰기 락, `@EntityGraph` 미적용)를 추가하고 `likePost`/`unlikePost` 둘 다 트랜잭션 첫 문장으로 호출해 락 획득 순서를 통일(데드락 → 500 제거), `decrementLikeCount`에 `like_count > 0` 하한 가드 추가. 기존 `DataIntegrityViolationException` catch는 안전망으로 유지. compileJava·compileTestJava·spotlessCheck 통과. `CommunityPostLikeServiceTest`가 `existsById`/`getReferenceById`를 스텁하고 있어 tester의 수정이 필요하다.
- tester 동시성 검증: `CommunityPostLikeServiceTest`의 스텁을 `findByIdForUpdate` 기준으로 수리하고(단위 테스트 2건 추가 — 락 획득 순서 InOrder, 유니크 제약 안전망), `CommunityPostRepositoryTest`에 하한 가드·`findByIdForUpdate` 테스트 2건, 실제 스레드 2~5개로 동시 요청을 재현하는 `CommunityPostLikeConcurrencyIntegrationTest` 3건을 신규 작성. **수정 전/후 실측**: 수정을 되돌린 상태에서 동시성 3건 전부 실패(같은 사용자 동시 좋아요 → `CannotAcquireLockException: Deadlock found ... insert into community_post_likes`, 다른 사용자 5명 동시 좋아요 → `CannotAcquireLockException: Deadlock found ... update community_posts set like_count=like_count+1`, 동시 취소 → `ObjectOptimisticLockingFailureException: Unexpected row count (expected 1 but was 0) [delete from community_post_likes]`), 하한 가드만 제거하면 `like_count`가 -1(`expected 0L but was -1L`). 수정 복구 후 6개 클래스 전부 통과.
