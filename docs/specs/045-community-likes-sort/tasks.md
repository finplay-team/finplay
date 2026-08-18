# Tasks: 커뮤니티 좋아요·인기순 정렬

- [x] 데이터 모델 — `V41__create_community_post_likes_and_like_count.sql`(착수 시 `ls db/migration` 재확인 후 필요하면 번호 조정), `CommunityPostLike` 엔티티, `CommunityPost.likeCount` 필드, `CommunityPostRepository`의 원자적 증감 메서드(`incrementLikeCount`/`decrementLikeCount`), `CommunityPostLikeRepository`(`existsByPost_IdAndUser_Id`·`findByPost_IdAndUser_Id`·`findLikedPostIds` 배치 조회) (+ `@DataJpaTest`)
- [x] 좋아요 API — `CommunityPostLikeService`(멱등 표시/취소, 본인 게시물 허용), `CommunityPostLikeController`(`POST`/`DELETE /api/community/posts/{postId}/likes`), `CommunityPostLikeResponse` (+ 단위·`@WebMvcTest`)
- [x] 인기순 정렬 — `CommunityPostRepositoryCustom`/`Impl`을 `findPosts(Pageable, Long instrumentId, String sort)`로 변경(popular/latest 분기), `CommunityPostController.getPosts`에 `sort` 쿼리 파라미터·검증 추가 (+ 단위·`@WebMvcTest`, 기존 최신순 회귀 확인)
- [x] 게시물 응답 확장 — `CommunityPostResponse`/`CommunityPostListResponse`에 `likeCount`·`likedByMe` 추가, `getPost`/`getPosts`/`createPost`/`updatePost`에 인증 사용자 컨텍스트 반영(`getPost`·`getPosts`에 `@AuthenticationPrincipal` 추가 포함), 목록 조회는 배치 조회로 N+1 방지 (+ `@WebMvcTest`)
- [x] 통합 테스트 — spec.md 완료 조건 10개 시나리오(좋아요 표시/취소 멱등, 본인 게시물 허용, 404, 인기순 정렬·동률 처리, 종목 필터 조합, 하위 호환, 잘못된 sort 400, 게시물 삭제 시 좋아요 정리)
- [x] 문서 갱신 — `docs/api-routes.md`·`docs/api-contracts.md`(신규 엔드포인트 2개 + 기존 게시물 목록/단건 응답 계약 변경), `docs/prd.md`(`LIKE-001`·`LIKE-002`·`SORT-001` 신규 행 추가는 착수 전 별도 확정 필요 — plan.md 머리말 참고)
- [x] 동시성 방어 (PR #442 2차 리뷰 차단 2건) — 동시 좋아요 시 `CannotAcquireLockException`(데드락)으로 500이 나고, 동시 취소 시 `like_count`가 음수까지 내려가는 문제를 고친다. `CommunityPostLikeService.likePost`/`unlikePost`가 게시물 행을 비관적 락(`SELECT ... FOR UPDATE`)으로 먼저 잡아 락 획득 순서를 통일하고, `CommunityPostRepository.decrementLikeCount`에 `like_count > 0` 하한 가드를 둔다 (+ `CountDownLatch`로 실제 동시 요청을 재현하는 Testcontainers 통합 테스트)
- [x] 문서 후속 (PR #442 2차 리뷰 권장) — `docs/prd.md` §4 정본 목록에 `LIKE-001`·`LIKE-002`·`SORT-001`을 "spec 045가 정본"으로 등재(§3 구현 현황 표와 어긋난 상태 해소), `docs/specs/045-community-likes-sort/run-log.md`에 2차·3차 리뷰 반영 이력 기록
