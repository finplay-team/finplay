# Tasks: Issue #30 본인 댓글 삭제 API

- [x] **1. `PostCommentService.deleteComment` 소유권 검증 삭제 로직과 단위 테스트**
  - `findById(commentId) → 없으면 NOT_FOUND` → `작성자 불일치면 FORBIDDEN` → `repository.delete(comment)` 패턴을 추가한다(`CommunityPostService.updatePost`와 동일 패턴, Repository에 커스텀 삭제 메서드는 추가하지 않는다).
  - `@Transactional`로 트랜잭션 경계를 유지한다.
  - 본인 삭제 성공, 타인 삭제 시 `FORBIDDEN`(삭제 미수행), 존재하지 않는 댓글 시 `NOT_FOUND`(비교·삭제 미수행)를 단위 테스트로 검증한다.

- [x] **2. `CommentController` 신규 추가와 보안/슬라이스 테스트**
  - `@RequestMapping("/api/community/comments")` 클래스 레벨 매핑을 갖는 새 `CommentController`를 만들고 `DELETE /{commentId}` 하나만 둔다(`PostCommentController`는 변경하지 않는다).
  - `@AuthenticationPrincipal AuthenticatedUser.userId()`, 경로 변수 `commentId`만 Service에 전달하고 204(본문 없음)를 반환한다.
  - `@WebMvcTest`로 정상 204, Service `FORBIDDEN` → 403, Service `NOT_FOUND` → 404, 비로그인·만료·변조·Refresh Bearer → 401(Service 미호출)을 검증한다.
  - 이 경로를 Security 공개 화이트리스트에 추가하지 않는다.

- [x] **3. 삭제 통합 시나리오 (Testcontainers)**
  - `@SpringBootTest` + MySQL Testcontainers로 사용자 A의 본인 댓글 삭제 → 204 → `postCommentRepository.findById(commentId)` 빈 `Optional`을 검증한다.
  - 사용자 B가 사용자 A의 댓글 삭제 시도 → 403 → 댓글이 여전히 존재함을 검증한다.
  - 존재하지 않는 `commentId` → 404, 비로그인 → 401이며 각각 댓글 행 수 불변을 검증한다.

- [ ] **4. 문서 동기화와 전체 게이트**
  - 실제 `CommentController` 매핑(`DELETE /api/community/comments/{commentId}`)과 204·403·404·401 계약을 `docs/api-routes.md`에 반영한다(동기화 모드에서 처리).
  - 댓글 목록 조회(Issue #29) 미구현으로 "삭제 후 목록에서 제거" 기준은 `findById` 재조회 부재로 대체 검증했음을 확인한다.
  - `spotlessApply`, 대상 테스트, `.\gradlew.bat build --no-daemon --max-workers=1`, `git diff --check`를 순차 실행한다.
