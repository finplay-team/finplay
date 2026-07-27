# Tasks: Issue #29 커뮤니티 게시물 댓글 목록 조회 API

- [x] **1. 게시물 격리·안정 정렬·N+1 방지 Repository 조회**
  - `PostCommentRepository`에 대상 `postId`만 필터링하고 `createdAt ASC, id ASC`로 정렬하는 목록 쿼리를 추가한다.
  - `PostCommentResponse.from`이 사용하는 작성자를 fetch join해 댓글 수에 따른 추가 쿼리를 방지한다.
  - V4의 `(post_id, created_at, id)` 인덱스를 재사용하며 엔티티·스키마·마이그레이션은 변경하지 않는다.
  - `@DataJpaTest` + MySQL Testcontainers로 다른 게시물 격리, 생성시각/ID tie-breaker, 작성자 로딩을 검증한다.

- [x] **2. 게시물 존재를 구분하는 read-only Service와 단위 테스트**
  - `CommunityPostRepository.existsById(postId)`로 무댓글 게시물과 미존재 게시물을 구분한다.
  - 존재하면 댓글 목록을 조회해 기존 `PostCommentResponse`로 변환하고, 없으면 `NOT_FOUND`를 던지는 `@Transactional(readOnly = true)` 유스케이스를 추가한다.
  - 정상 순서 보존, 빈 목록, 게시물 미존재 시 댓글 쿼리 미실행을 단위 테스트한다.

- [x] **3. GET Controller와 인증·응답 슬라이스 테스트**
  - 기존 `PostCommentController`에 `GET /api/community/posts/{postId}/comments`를 추가하고 200과 `List<PostCommentResponse>`를 반환한다.
  - 성공 배열은 `commentId`, `authorNickname`, `content`, `createdAt`만 포함하고, 빈 결과는 200 `[]`로 반환한다.
  - 정상 순서·빈 목록·게시물 미존재 404와 비로그인·만료·변조·Refresh Bearer 401을 `@WebMvcTest`로 검증한다.
  - 댓글 경로를 Security 공개 화이트리스트에 추가하지 않는다.

- [x] **4. 실제 MySQL 통합 시나리오**
  - 새 `PostCommentListIntegrationTest`에서 여러 게시물·작성자·동시각 댓글을 준비한다.
  - 대상 게시물 격리, `createdAt ASC, commentId ASC`, 정확한 응답 필드와 존재하는 무댓글 게시물의 200 `[]`를 검증한다.
  - 게시물 미존재 404와 비로그인 401을 검증하고 조회 전후 댓글 DB 상태가 불변인지 확인한다.

- [x] **5. API 문서 동기화와 전체 게이트**
  - 실제 Controller 매핑을 기준으로 `docs/api-routes.md`에 GET 라우트, 인증, 배열 응답, 안정 정렬, 빈 목록 200, 404·401 계약을 반영한다.
  - 댓글 페이지네이션·작성·삭제·수정·대댓글 및 다른 API를 변경하지 않았는지 확인한다.
  - `.\gradlew.bat spotlessApply --no-daemon --max-workers=1`, 대상 테스트, `.\gradlew.bat build --no-daemon --max-workers=1`, `git diff --check`를 같은 worktree에서 겹치지 않게 순차 실행한다.
