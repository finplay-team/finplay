# Tasks: 관심목록 (Watchlist)

- [x] `V23__create_watchlist_items.sql` 마이그레이션 + `WatchlistItem` 엔티티 + `WatchlistItemRepository`(+ `@DataJpaTest`: unique 제약, market 필터, 정렬 확인)
- [x] `ErrorCode.WATCHLIST_ITEM_NOT_FOUND` 추가 + `WatchlistService`(등록/조회/해제, 중복 등록 예외 변환) + 단위 테스트
- [x] `WatchlistController` + `WatchlistItemCreateRequest`/`WatchlistItemResponse`/`WatchlistItemListResponse` (+ `@WebMvcTest`)
- [x] 통합 테스트 (등록 → 목록 조회 → 해제 → 목록 제외 확인, Testcontainers)
- [x] 문서 갱신 — `ai/api-routes.md`·`docs/api-contracts.md`에 `/api/watchlist-items` 3개 라우트 반영 (동기화 모드 planner 또는 같은 커밋에서)
