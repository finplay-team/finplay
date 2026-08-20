# Tasks: Issue #28 커뮤니티 게시물 댓글 작성 API

- [x] **1. 확정 API 계약을 DTO·스키마에 고정**
  - 댓글 본문은 `@NotBlank`, `@Size(max = 1000)`, `VARCHAR(1000)`으로 일치시킨다.
  - 201 응답은 `commentId`, `authorNickname`, `content`, `createdAt`만 반환한다.
  - 댓글 수정과 수정시각 저장이 범위에 없으므로 `updatedAt`을 엔티티·스키마·응답에 추가하지 않는다.

- [x] **2. `post_comments` 영속 모델과 실제 MySQL 검증**
  - 다음 가용 버전의 새 Flyway 마이그레이션, 평면형 `PostComment` 엔티티, `PostCommentRepository`를 추가한다.
  - 게시물·작성자 FK, 본문 1,000자, 생성시각과 후속 오래된 순 조회용 `(post_id, created_at, id)` 인덱스를 반영한다.
  - 게시물 삭제 정책이 확정되지 않았으므로 cascade를 추가하지 않고, 부모 댓글 필드도 만들지 않는다.
  - `@DataJpaTest` + MySQL Testcontainers로 저장·조회, 두 FK, 본문 길이 제약을 검증한다.

- [x] **3. 게시물·인증 작성자 기반 생성 Service와 단위 테스트**
  - 같은 community 도메인의 `CommunityPostRepository`로 게시물을 조회하고 미존재 시 `NOT_FOUND`를 던진다.
  - 기존 `UserQueryService`로 인증 사용자만 작성자로 얻어 `PostComment.create(...)`로 저장하는 단일 `@Transactional` 유스케이스를 구현한다.
  - 주입된 `Clock`을 사용하고 정상 저장, 게시물 미존재 404, 작성자 미존재 401, 실패 시 비저장을 단위 테스트한다.

- [x] **4. POST Controller·DTO와 보안/검증 슬라이스 테스트**
  - `POST /api/community/posts/{postId}/comments`, `PostCommentCreateRequest`, 네 필드 `PostCommentResponse`를 추가한다.
  - `postId`, `@AuthenticationPrincipal AuthenticatedUser.userId()`, 검증된 본문만 Service에 전달하고 201 및 `commentId`, `authorNickname`, `content`, `createdAt`을 반환한다.
  - 본문 누락·빈 문자열·공백·1,000자 초과의 400, 게시물 미존재 404, 비로그인·만료·변조·Refresh Bearer의 401을 `@WebMvcTest`로 검증한다.
  - 댓글 경로를 Security 공개 화이트리스트에 추가하지 않는다.

- [x] **5. 생성 통합 시나리오·문서 동기화·전체 게이트**
  - `@SpringBootTest` + MySQL Testcontainers에서 게시물·인증 작성자 연결, 평면 구조, 400·404·401 시 DB 비변경을 검증한다.
  - 실제 Controller 매핑과 본문 최대 1,000자·201 응답 네 필드·400·401·404 계약을 `ai/api-routes.md`에 동기화한다.
  - 댓글 조회·삭제·수정·대댓글과 다른 커뮤니티 API를 구현하지 않았는지 확인한다.
  - `spotlessApply`, 대상 테스트, `.\gradlew.bat build --no-daemon --max-workers=1`, `git diff --check`를 순차 실행한다.
  - 문서 반영 전 HEAD `dde3e3e`의 전체 build는 407개 테스트·JaCoCo·SpotBugs·Spotless를 포함해 4분 29초에 통과했다. 문서 커밋 후 최종 전체 build는 별도로 재실행한다.
