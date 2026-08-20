# Issue #26 커뮤니티 게시물 수정 API 구현 계획

**Goal:** 인증 사용자가 `PATCH /api/community/posts/{postId}`로 본인 게시물의 제목·본문을 수정하면 200과 수정된 게시물을 받는다. 타인 게시물은 403, 없는 게시물은 404, 잘못된 입력은 400이며 어떤 실패 경로도 기존 데이터를 바꾸지 않는다.

**관련 정본:** GitHub Issue #26, PRD `COM-001`·`COM-003`와 §5 공통 오류표, `spec.md`, ADR-0002, `docs/conventions.md`

**선행:** Issue #23(작성)·#25(단건 조회)가 merge되어 `CommunityPost`·Repository·Service·Controller와 `CommunityPostResponse`가 존재한다. 현재 `feat/26-community-post-update` 브랜치에 다음이 이미 구현돼 있다 (커밋 `7fe687d`, `3537e0f`, `5468767`).

- `CommunityPost.update(title, content, now)` 도메인 메서드
- `CommunityPostService.updatePost(authenticatedUserId, postId, title, content)` — 게시물 미존재 404, 소유자 불일치 403 처리 포함
- `CommunityPostRepository.findById` 재사용 (신규 조회 메서드 불필요)

아직 없는 것: Controller의 PATCH 엔드포인트, 요청 DTO(및 title·content 자체 검증), `ai/api-routes.md` 반영, 관련 테스트.

## 요구사항 ID와 수용 기준

- PRD `COM-001`: 게시물 본인 수정을 제공한다.
- PRD `COM-003`: 수정은 소유자만 가능하다. 타인 콘텐츠 변경은 403 `FORBIDDEN`.
- PRD §5: 인증 없음·만료는 401 `UNAUTHORIZED`, 대상 없음은 404 `NOT_FOUND`, 검증 실패는 400 `VALIDATION_ERROR` 공통 오류 형식이다.
- spec.md 비즈니스 규칙: 제목·본문은 공백만으로 이뤄질 수 없다 (400 `VALIDATION_ERROR`).

수용 시나리오는 다음과 같다.

1. 본인 게시물을 유효한 제목·본문으로 수정하면 200과 갱신된 `CommunityPostResponse`(새 `updatedAt` 포함)를 반환한다.
2. 타인 게시물을 수정하려 하면 403 `FORBIDDEN`을 반환하고 게시물은 변경되지 않는다.
3. 존재하지 않는 `postId`를 수정하려 하면 404 `NOT_FOUND`를 반환한다.
4. 제목·본문이 없거나 공백뿐이거나 최대 길이(제목 100자, 본문 5,000자)를 초과하면 400 `VALIDATION_ERROR`를 반환하고 대상 게시물은 변경되지 않는다.
5. 인증 없이 호출하면 Controller 진입 전 401 `UNAUTHORIZED`를 반환한다.

## 범위와 제외

### 포함

- `PATCH /api/community/posts/{postId}`
- 신규 `CommunityPostUpdateRequest` (제목·본문 `@NotBlank` + `@Size`, `CommunityPostCreateRequest`와 동일한 제약)
- 기존 `CommunityPostService.updatePost`, `CommunityPost.update` 재사용 — 이미 구현된 404/403 로직 변경 없음
- 기존 `CommunityPostResponse` 재사용
- Service 단위, Controller MVC 슬라이스, 실제 DB 통합 테스트
- 구현 후 실제 Controller 매핑 기준 `ai/api-routes.md` 동기화

### 제외

- 게시물 목록·작성·삭제
- 댓글 조회·작성·삭제
- 응답 필드 추가 또는 별도 상세 응답 DTO
- 엔티티·DB 스키마·Flyway 마이그레이션 변경 (제목·본문 컬럼 제약은 Issue #23에서 이미 확정)
- 부분 필드 수정(제목만/본문만) — 이번 계약은 제목·본문 모두 필수인 전체 교체로 한정한다 (PRD·Issue #26에 부분 수정 요구 없음)

## API 계약

| 항목 | 내용 |
|---|---|
| Method / Path | `PATCH /api/community/posts/{postId}` |
| 인증 | `Authorization: Bearer <유효한 Access JWT>` 필수 |
| 요청 본문 | `{"title": "...", "content": "..."}` — `title` 필수·공백 불가·최대 100자, `content` 필수·공백 불가·최대 5,000자 |
| 성공 | 200 + 갱신된 `CommunityPostResponse` (6필드, `updatedAt` 갱신) |
| 검증 실패 | 400 `VALIDATION_ERROR` 공통 오류 형식, 대상 게시물 미변경 |
| 게시물 미존재 | 404 `NOT_FOUND` 공통 오류 형식 |
| 소유자 아님 | 403 `FORBIDDEN` 공통 오류 형식, 대상 게시물 미변경 |
| 인증 없음·만료·변조·잘못된 토큰 타입 | 401 `UNAUTHORIZED` 공통 오류 형식 |

경로 변수 `postId`는 `Long`으로 전달한다. `@Valid` 검증이 Controller 메서드 진입 시점에 실패하면 서비스가 호출되지 않으므로 검증 실패와 존재하지 않는 게시물이 동시에 해당하는 요청은 400으로만 응답한다 (PRD·Issue #26에 순서 요구 없음, 기존 생성 API와 동일한 처리 순서 유지).

## 설계 결정

### D1. 요청 DTO는 생성 DTO와 동일한 제약으로 신설한다

`docs/conventions.md`의 접미사 표에 따라 `CommunityPostUpdateRequest`(수정 요청 접미사 `~UpdateRequest`)를 `dto/request/`에 추가한다. `title`·`content` 필드 제약은 `CommunityPostCreateRequest`와 동일하게 `@NotBlank` + `@Size(max = 100 / 5000)`을 적용해 공백만 있는 값과 길이 초과를 Controller 진입 시점에서 걸러낸다. 별도 검증 로직을 서비스에 추가하지 않는다 — Bean Validation으로 충분하고, 컨벤션상 요청 검증은 Controller 책임이다.

### D2. 서비스·도메인은 이미 구현된 것을 그대로 쓴다

`CommunityPostService.updatePost`와 `CommunityPost.update`는 이번 계획 시점에 이미 병합 대상 브랜치에 존재하며 404(`NOT_FOUND`)·403(`FORBIDDEN`) 처리가 요구사항과 일치한다. 이번 이슈에서 서비스·도메인 코드를 변경하지 않는 것을 우선하고, 구현 단계에서 리뷰 중 실제 문제(예: 트랜잭션 경계 누락)가 발견되면 그때만 최소 수정한다.

### D3. 실패 시 미변경은 트랜잭션 경계로 보장한다

`CommunityPostService.updatePost`는 `@Transactional`이 이미 붙어 있다 (기존 `createPost`와 동일 패턴 확인 필요 — 구현 단계에서 어노테이션 존재를 확인하고 없으면 추가한다). 404·403 분기는 `post.update(...)` 호출 전에 예외를 던지므로 엔티티 상태 변경이 없고, `@Transactional` 덕분에 부분 커밋도 없다. 400 검증 실패는 Controller `@Valid`에서 서비스 호출 전에 걸러지므로 데이터 변경 자체가 발생하지 않는다.

### D4. Controller는 기존 GET/POST 옆에 PATCH 매핑만 추가한다

- Controller: `@PatchMapping("/{postId}")`, `@AuthenticationPrincipal AuthenticatedUser`, `@Valid @RequestBody CommunityPostUpdateRequest`, `postId` 경로 변수 → `communityPostService.updatePost(principal.userId(), postId, request.title(), request.content())` 호출, 200 응답.
- 흐름은 ADR-0002에 따라 `CommunityPostController → CommunityPostService → CommunityPostRepository`를 유지한다.

## 예상 변경 파일

### Production

- `src/main/java/com/finplay/api/community/controller/CommunityPostController.java` (PATCH 매핑 추가)
- `src/main/java/com/finplay/api/community/dto/request/CommunityPostUpdateRequest.java` (신규)
- `src/main/java/com/finplay/api/community/service/CommunityPostService.java` (구현 단계에서 `@Transactional` 확인, 필요시만 보강 — 로직 변경 없음이 기본 전제)

### Test

- `src/test/java/com/finplay/api/community/service/CommunityPostServiceTest.java` (updatePost 케이스 추가 — 기존 파일 있으면 확장)
- `src/test/java/com/finplay/api/community/controller/CommunityPostControllerTest.java` (updatePost MVC 슬라이스 추가)
- `src/test/java/com/finplay/api/community/CommunityPostUpdateIntegrationTest.java` (신규, Testcontainers)

### Documentation

- `ai/api-routes.md` (동기화 모드에서 처리)
- `ai/specs/008-community/issue-26-tasks.md`

## 테스트 계획

### Service 단위

- 본인 게시물 수정 시 제목·본문·`updatedAt`이 갱신된 응답을 반환한다.
- 존재하지 않는 `postId`는 `BusinessException(NOT_FOUND)`를 던진다.
- 소유자가 아닌 사용자가 수정하면 `BusinessException(FORBIDDEN)`을 던지고 엔티티는 변경되지 않는다 (수정 전 필드값과 비교).

### Controller MVC 슬라이스

- 인증된 소유자의 유효 요청은 서비스에 `userId`·`postId`·`title`·`content`를 전달하고 200과 갱신된 6필드를 반환한다.
- 제목·본문 누락/공백/길이 초과는 서비스 호출 없이 400 `VALIDATION_ERROR`를 반환한다.
- 서비스의 `NOT_FOUND`는 404, `FORBIDDEN`은 403 공통 오류 형식으로 반환한다.
- 비로그인 요청은 401이고 서비스를 호출하지 않는다.

### 통합 (`@SpringBootTest` + MySQL Testcontainers)

- 사용자와 게시물을 실제 DB에 저장한 뒤 소유자로 PATCH를 호출해 제목·본문·`updatedAt`이 실제로 갱신됨을 검증한다.
- 다른 사용자로 PATCH를 호출하면 403이고 DB의 게시물 값은 그대로임을 재조회로 검증한다.
- 존재하지 않는 `postId`는 404, 공백 제목/본문은 400이며 두 경우 모두 DB 값이 변경되지 않음을 검증한다.
- 비로그인 요청은 401임을 검증한다.

## 미확정 사항

- 제목만 또는 본문만 부분 수정하는 시나리오는 PRD·Issue #26에 명시가 없어 이번 계획에서 다루지 않는다. 필요해지면 별도 이슈로 제안한다.
- 검증 실패와 소유권 위반이 동시에 성립하는 경우의 응답 우선순위(400 vs 403)는 PRD에 명시가 없다. Controller `@Valid`가 서비스 호출보다 먼저 실행되는 Spring MVC 기본 동작을 그대로 따르며 별도 정책을 만들지 않는다.

## 완료 조건

- [ ] 본인 게시물 수정이 200과 갱신된 6필드 응답을 반환한다.
- [ ] 타인 게시물 수정은 403 `FORBIDDEN`이며 게시물이 변경되지 않는다.
- [ ] 존재하지 않는 게시물 수정은 404 `NOT_FOUND`를 반환한다.
- [ ] 제목·본문 누락·공백·길이 초과는 400 `VALIDATION_ERROR`이며 게시물이 변경되지 않는다.
- [ ] 비로그인 요청은 401 `UNAUTHORIZED`를 반환한다.
- [ ] 목록·삭제·댓글 등 다른 API와 엔티티·스키마를 변경하지 않는다.
- [ ] 실제 Controller 매핑을 `ai/api-routes.md`에 동기화한다 (동기화 모드에서 처리).
- [ ] 대상 테스트와 `.\gradlew.bat build --no-daemon --max-workers=1`을 새로 실행해 통과한다.
