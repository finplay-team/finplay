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

## 모니터링 (사람용 요약)
- 데이터 모델 항목(V41 마이그레이션, CommunityPostLike 엔티티, likeCount 필드, 원자적 증감·배치 조회 리포지토리) 구현, compileJava 통과.
- 좋아요 API 항목(CommunityPostLikeService·Controller·Response, POST/DELETE 멱등) 구현, api-routes.md·api-contracts.md 갱신, compileJava 통과.
- 인기순 정렬 항목(findPostsOrderByCreatedAtDesc → findPosts(sort) 변경, 컨트롤러 sort 파라미터·검증) 구현, api-routes.md·api-contracts.md 갱신, compileJava 통과.
- 시그니처 변경으로 깨진 기존 테스트 3개(CommunityPostRepositoryTest·CommunityPostServiceTest·CommunityPostControllerTest) 호출부를 새 시그니처(`findPosts(..., "latest")`/`getPosts(..., "latest")`)로 수정, compileTestJava 통과.
- 게시물 응답 확장 항목(CommunityPostResponse/ListResponse에 likeCount·likedByMe 추가, getPost/getPosts에 @AuthenticationPrincipal·목록 배치 조회 반영) 구현, api-routes.md·api-contracts.md 갱신, 시그니처 변경으로 깨진 기존 테스트 2개(CommunityPostControllerTest·CommunityPostServiceTest) 호출부 수정, compileJava·compileTestJava 통과.
- 통합 테스트 항목(`CommunityPostLikeSortIntegrationTest`, spec.md 완료 조건 12개 불릿을 13개 테스트로 커버 — 좋아요 표시/취소 멱등, 본인 게시물 허용, 404, 인기순 정렬·동률 처리, 하위 호환, 종목 필터 조합, 잘못된 sort 400, 삭제 시 좋아요 정리) 작성, compileTestJava 통과. 실행은 tester가 수행.
- tester 실행 결과 13건 중 3건 실패 수정: `CommunityPostRepository.incrementLikeCount`/`decrementLikeCount`에 `flushAutomatically = true` 추가(clearAutomatically가 같은 트랜잭션의 미반영 좋아요 행 save/delete를 flush 없이 버리던 버그), `CommunityPostLikeSortIntegrationTest.createUser`의 nickname을 50자로 절단(길이 초과 시 `DataIntegrityViolationException`). compileJava·compileTestJava 통과.
