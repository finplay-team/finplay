# Tasks: 커뮤니티 좋아요·인기순 정렬

- [x] 데이터 모델 — `V41__create_community_post_likes_and_like_count.sql`(착수 시 `ls db/migration` 재확인 후 필요하면 번호 조정), `CommunityPostLike` 엔티티, `CommunityPost.likeCount` 필드, `CommunityPostRepository`의 원자적 증감 메서드(`incrementLikeCount`/`decrementLikeCount`), `CommunityPostLikeRepository`(`existsByPost_IdAndUser_Id`·`findByPost_IdAndUser_Id`·`findLikedPostIds` 배치 조회) (+ `@DataJpaTest`)
- [x] 좋아요 API — `CommunityPostLikeService`(멱등 표시/취소, 본인 게시물 허용), `CommunityPostLikeController`(`POST`/`DELETE /api/community/posts/{postId}/likes`), `CommunityPostLikeResponse` (+ 단위·`@WebMvcTest`)
- [x] 인기순 정렬 — `CommunityPostRepositoryCustom`/`Impl`을 `findPosts(Pageable, Long instrumentId, String sort)`로 변경(popular/latest 분기), `CommunityPostController.getPosts`에 `sort` 쿼리 파라미터·검증 추가 (+ 단위·`@WebMvcTest`, 기존 최신순 회귀 확인)
- [x] 게시물 응답 확장 — `CommunityPostResponse`/`CommunityPostListResponse`에 `likeCount`·`likedByMe` 추가, `getPost`/`getPosts`/`createPost`/`updatePost`에 인증 사용자 컨텍스트 반영(`getPost`·`getPosts`에 `@AuthenticationPrincipal` 추가 포함), 목록 조회는 배치 조회로 N+1 방지 (+ `@WebMvcTest`)
- [x] 통합 테스트 — spec.md 완료 조건 10개 시나리오(좋아요 표시/취소 멱등, 본인 게시물 허용, 404, 인기순 정렬·동률 처리, 종목 필터 조합, 하위 호환, 잘못된 sort 400, 게시물 삭제 시 좋아요 정리)
- [ ] 문서 갱신 — `docs/api-routes.md`·`docs/api-contracts.md`(신규 엔드포인트 2개 + 기존 게시물 목록/단건 응답 계약 변경), `docs/prd.md`(`LIKE-001`·`LIKE-002`·`SORT-001` 신규 행 추가는 착수 전 별도 확정 필요 — plan.md 머리말 참고)
