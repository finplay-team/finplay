# Tasks: 커뮤니티 배지 (참여형 + 수익형)

- [x] `badge` 도메인 기반 — `MemberBadge` 엔티티·`BadgeType`·`BadgeTier` enum·`MemberBadgeRepository`·`V39`/`V40` 마이그레이션 (+ `@DataJpaTest`)
- [ ] "배웠어요" 반응 — `community_post_learned_reactions` 연동, `PostLearnedReactionService`(본인 게시물 차단·멱등 표시/취소), `POST`/`DELETE /api/community/posts/{postId}/reactions/learned` (+ 단위·`@WebMvcTest`)
- [ ] 배지 이벤트·재계산 — `TutorialAttemptCompletedEvent`·`MarketReflectionCreatedEvent`·`PostLearnedReactionChangedEvent` 신규 + 기존 `RealizedPnlUpdatedEvent` 구독, `BadgeEventListener`(AFTER_COMMIT, 예외 흡수), `BadgeService` 4종 재계산 메서드(하락 없음 upsert) (+ 단위 테스트)
- [ ] `GET /api/badges/me` — `BadgeController`·`BadgeService.getMyBadges`(진행률 포함)·`MyBadgeListResponse` (+ `@WebMvcTest`)
- [ ] 커뮤니티 응답 확장 — `CommunityPostResponse`/`CommunityPostListResponse`/`PostCommentResponse`에 `learnedCount`·`learnedByMe`·`authorBadges` 추가, `BadgeService.getTopTiersByUserIds` 배치 조회로 N+1 방지 (+ `@WebMvcTest`)
- [ ] 통합 테스트 — plan.md 테스트 계획의 시나리오 1~7 (튜토리얼·복기·배웠어요·실현손익 등급 상승, 본인 게시물 차단, 등급 하락 없음, 목록 배치 매핑)
- [ ] 문서 갱신 — `docs/api-routes.md`·`docs/api-contracts.md`(신규 엔드포인트 3개 + 기존 커뮤니티 응답 필드 변경), `docs/prd.md`(BADGE-001~005 신규 행 추가 및 C-004 갱신은 착수 전 별도 확정 필요 — plan.md 머리말 참고)
