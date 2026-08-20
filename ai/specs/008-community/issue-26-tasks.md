# Tasks: Issue #26 커뮤니티 게시물 수정 API

- [x] **0. 도메인·서비스 기본 골격 (완료됨, 커밋 `7fe687d`, `3537e0f`, `5468767`)**
  - `CommunityPost.update(title, content, now)` 도메인 메서드 구현 완료.
  - `CommunityPostService.updatePost(authenticatedUserId, postId, title, content)` 구현 완료 — 게시물 미존재 404 `NOT_FOUND`, 소유자 불일치 403 `FORBIDDEN` 처리 포함.

- [x] **1. 요청 DTO 추가와 서비스 트랜잭션 확인**
  - `CommunityPostUpdateRequest`(`dto/request/`)를 신설하고 `title`·`content`에 `@NotBlank` + `@Size(max = 100 / 5000)`을 `CommunityPostCreateRequest`와 동일하게 적용한다.
  - `CommunityPostService.updatePost`에 `@Transactional`이 있는지 확인하고, 없으면 추가한다 (실패 시 미변경 보장). 그 외 기존 404/403 로직은 변경하지 않는다.

- [x] **2. PATCH Controller 엔드포인트 추가**
  - `CommunityPostController`에 `@PatchMapping("/{postId}")`를 추가해 `@AuthenticationPrincipal AuthenticatedUser`, `@Valid @RequestBody CommunityPostUpdateRequest`, `postId` 경로 변수를 받아 `communityPostService.updatePost(...)`를 호출하고 200으로 응답한다.

- [x] **3. Service 단위 테스트와 Controller MVC 슬라이스 테스트**
  - Service 단위: 본인 수정 성공(6필드+`updatedAt` 갱신 검증), 존재하지 않는 `postId`의 `NOT_FOUND`, 타인 수정 시 `FORBIDDEN` 및 엔티티 미변경을 검증한다.
  - Controller MVC 슬라이스: 정상 200/6필드, 제목·본문 누락·공백·길이 초과의 400 `VALIDATION_ERROR`(서비스 미호출 포함), 404·403 공통 오류 형식, 비로그인 401(서비스 미호출)을 검증한다.

- [x] **4. 실제 DB 수정 통합 테스트** (최초 작성 시점엔 이 세션 환경에 Docker 미가용이었으나, Docker Desktop 기동 후 재실행하여 5건 전체 통과 확인함. `ai/agent-mistakes.md` 2026-07-27 참고)
  - `@SpringBootTest` + MySQL Testcontainers에서 사용자와 게시물을 저장한 뒤 소유자 PATCH로 제목·본문·`updatedAt` 갱신을 실제 DB 재조회로 검증한다.
  - 타인 PATCH의 403과 DB 미변경, 존재하지 않는 `postId`의 404, 공백 제목/본문의 400과 DB 미변경, 비로그인의 401을 각각 검증한다.

- [x] **5. 문서 동기화와 완료 게이트**
  - `ai/api-routes.md`의 커뮤니티 게시물 PATCH 엔드포인트 반영은 이 항목에서 직접 하지 않는다 — **동기화 모드에서 처리**(planner의 별도 실행 단계, `/feature` 마무리 단계). → planner 동기화 모드에서 라우트 표와 상세 절 반영 완료.
  - `spotlessApply`, 대상 테스트, `.\gradlew.bat build --no-daemon --max-workers=1`, `git diff --check`를 실행하고 결과를 기록한다. (오케스트레이터가 이미 실행·확인함)
