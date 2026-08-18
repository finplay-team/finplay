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

## 모니터링 (사람용 요약)
- 데이터 모델 항목(V41 마이그레이션, CommunityPostLike 엔티티, likeCount 필드, 원자적 증감·배치 조회 리포지토리) 구현, compileJava 통과.
- 좋아요 API 항목(CommunityPostLikeService·Controller·Response, POST/DELETE 멱등) 구현, api-routes.md·api-contracts.md 갱신, compileJava 통과.
- 인기순 정렬 항목(findPostsOrderByCreatedAtDesc → findPosts(sort) 변경, 컨트롤러 sort 파라미터·검증) 구현, api-routes.md·api-contracts.md 갱신, compileJava 통과.
- 시그니처 변경으로 깨진 기존 테스트 3개(CommunityPostRepositoryTest·CommunityPostServiceTest·CommunityPostControllerTest) 호출부를 새 시그니처(`findPosts(..., "latest")`/`getPosts(..., "latest")`)로 수정, compileTestJava 통과.
- 게시물 응답 확장 항목(CommunityPostResponse/ListResponse에 likeCount·likedByMe 추가, getPost/getPosts에 @AuthenticationPrincipal·목록 배치 조회 반영) 구현, api-routes.md·api-contracts.md 갱신, 시그니처 변경으로 깨진 기존 테스트 2개(CommunityPostControllerTest·CommunityPostServiceTest) 호출부 수정, compileJava·compileTestJava 통과.
