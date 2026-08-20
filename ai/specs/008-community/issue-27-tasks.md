# Tasks: Issue #27 커뮤니티 게시물 삭제 API

- [x] **1. Repository·Service 삭제 로직 구현**
  - `PostCommentRepository`에 `void deleteByPost_Id(Long postId)` (Spring Data JPA 파생 삭제 쿼리)를 추가한다.
  - `CommunityPostService`에 `deletePost(authenticatedUserId, postId)`를 추가한다. `@Transactional` 안에서 (1) `communityPostRepository.findById(postId)`로 조회해 없으면 `BusinessException(NOT_FOUND)`, (2) 작성자 불일치면 `BusinessException(FORBIDDEN)`, (3) `postCommentRepository.deleteByPost_Id(postId)`로 댓글을 먼저 삭제, (4) `communityPostRepository.delete(post)`로 게시물을 삭제한다 (요청 DTO 없음 — 본문 없는 DELETE).

- [x] **2. DELETE Controller 엔드포인트 추가**
  - `CommunityPostController`에 `@DeleteMapping("/{postId}")`를 추가해 `@AuthenticationPrincipal AuthenticatedUser`, `postId` 경로 변수를 받아 `communityPostService.deletePost(...)`를 호출하고 `ResponseEntity.noContent().build()`(204)로 응답한다.

- [x] **3. Service 단위 테스트와 Controller MVC 슬라이스 테스트**
  - Service 단위: 댓글 없는 본인 게시물 삭제 성공(리포지토리 삭제 호출 검증), 댓글 있는 본인 게시물 삭제 시 댓글 삭제가 게시물 삭제보다 먼저 호출됨, 존재하지 않는 `postId`의 `NOT_FOUND`(삭제 미호출), 타인 삭제 시 `FORBIDDEN`(삭제 미호출)을 검증한다.
  - Controller MVC 슬라이스: 정상 204(본문 없음), 404·403 공통 오류 형식, 비로그인 401(서비스 미호출)을 검증한다.

- [x] **4. 실제 DB 삭제 통합 테스트 (댓글 없음/있음·타인 삭제·없는 ID 포함)**
  - `@SpringBootTest` + MySQL Testcontainers에서 댓글이 없는 게시물을 저장한 뒤 소유자 DELETE로 204와 게시물 삭제(재조회 시 없음)를 검증한다.
  - 댓글이 1건 이상 달린 게시물을 저장한 뒤 소유자 DELETE로 204와 게시물·댓글이 모두 삭제됨(재조회 시 둘 다 없음, FK 위반 없이 성공)을 검증한다.
  - 다른 사용자로 DELETE 시 403과 게시물·댓글이 DB에 그대로 남아있음을 재조회로 검증한다.
  - 존재하지 않는 `postId`의 404, 비로그인 요청의 401을 각각 검증한다.

- [x] **5. 문서 동기화와 완료 게이트**
  - `docs/api-routes.md`의 커뮤니티 게시물 DELETE 엔드포인트 반영은 이 항목에서 직접 하지 않는다 — **동기화 모드에서 처리**(planner의 별도 실행 단계, `/feature` 마무리 단계).
  - `spotlessApply`, 대상 테스트, `.\gradlew.bat build --no-daemon --max-workers=1`, `git diff --check`를 실행하고 결과를 기록한다.
