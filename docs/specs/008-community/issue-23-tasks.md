# Tasks: Issue #23 커뮤니티 게시물 작성 API

- [x] **1. 확정 API 계약을 DTO·스키마에 고정**
  - 제목은 `@Size(max = 100)`/`VARCHAR(100)`, 본문은 `@Size(max = 5000)`/`VARCHAR(5000)`으로 일치시킨다.
  - 201 응답은 `postId`, `authorNickname`, `title`, `content`, `createdAt`, `updatedAt`만 반환한다.
  - 작성자 행 미존재는 401 `UNAUTHORIZED`로 처리한다.
  - auth 도메인에 `User getUser(Long userId)` 계약의 최소 `UserQueryService`를 두고 커뮤니티에서 `UserRepository`를 직접 참조하지 않는다.

- [x] **2. `community_posts` 영속 모델과 실제 MySQL 검증**
  - [x] 다음 가용 버전의 새 Flyway 마이그레이션, `CommunityPost` 엔티티, `CommunityPostRepository`를 추가한다.
  - [x] 작성자 FK, 제목 100자·본문 5,000자 제약, 생성·수정시각과 후속 최신순 조회용 `(created_at, id)` 인덱스를 반영한다.
  - [x] `@DataJpaTest` + MySQL Testcontainers로 저장·조회, 작성자 FK, 길이 제약을 검증한다.

- [x] **3. 인증 작성자 기반 생성 Service와 단위 테스트**
  - `UserQueryService`에서 인증 사용자 ID에 해당하는 `User`를 얻고 `CommunityPost.create(...)`로 저장하는 단일 `@Transactional` 유스케이스를 구현한다.
  - 주입된 `Clock`을 사용하고 요청 값으로 작성자를 선택하는 인터페이스를 만들지 않는다.
  - 정상 저장, 작성자 매핑, 작성자 미존재 401, 실패 시 비저장을 단위 테스트한다.

- [x] **4. POST Controller·DTO와 보안/검증 슬라이스 테스트**
  - `POST /api/community/posts`, `CommunityPostCreateRequest`, `CommunityPostResponse`를 추가한다.
  - `@AuthenticationPrincipal AuthenticatedUser.userId()`만 service에 전달하고 201을 반환한다.
  - 제목·본문 누락·빈 문자열·공백·각각 100자·5,000자 초과의 400 `VALIDATION_ERROR`, 비로그인·만료·변조·Refresh Bearer의 401, 작성자 위조 방지를 `@WebMvcTest`에서 검증한다.
  - `/api/community/posts`를 Security 공개 화이트리스트에 추가하지 않는다.

- [x] **5. 생성 통합 시나리오·문서 동기화·전체 게이트**
  - [x] `@SpringBootTest` + MySQL Testcontainers에서 인증 사용자 저장, 위조 방지, 400/401 시 DB 비변경을 검증한다.
  - [x] 실제 Controller 매핑과 요청 길이, 201 응답 6개 필드, 400·401 계약을 `docs/api-routes.md`에 동기화한다.
  - [x] `spotlessApply`, 대상 테스트, `build --no-daemon --max-workers=1`을 실행한다.
  - [x] `git diff --check`를 실행한다.
