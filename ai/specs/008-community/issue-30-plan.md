# Issue #30 본인 댓글 삭제 API 구현 계획

**Goal:** 인증 사용자가 `DELETE /api/community/comments/{commentId}`를 호출하면, 서버가 해당 댓글의 작성자가 요청 사용자 본인인지 확인한 뒤 삭제하고 204를 반환한다.

**관련 정본:** GitHub Issue #30, PRD `COM-002`·`COM-003`와 §5 공통 오류표, `spec.md`, ADR-0002·0003·0004, `docs/conventions.md`

**선행:** Issue #28 구현으로 `post_comments`, `PostComment`·`PostCommentRepository`(JpaRepository만 상속)·`PostCommentService.createComment`·`PostCommentController`(`@RequestMapping("/api/community/posts/{postId}/comments")`, POST만 존재)가 있다. Issue #26 `CommunityPostService.updatePost`가 `findById → 소유자 비교 → 예외/수정` 패턴의 기존 선례다. Issue #29(댓글 목록 조회)는 아직 구현되지 않았다.

---

## 요구사항 ID와 수용 기준

### 대상 요구사항

- PRD `COM-002 댓글`
  - 본인 댓글만 삭제할 수 있다.
- PRD `COM-003 권한`
  - 수정·삭제는 소유자만 가능하다. 타인 콘텐츠 변경은 403 `FORBIDDEN`.
- PRD §5 공통 오류
  - 인증 없음·만료는 401 `UNAUTHORIZED`.
  - 대상 없음은 404 `NOT_FOUND`.
  - 소유자 아님은 403 `FORBIDDEN`.
- `ai/specs/008-community/spec.md`
  - 사용자는 본인 댓글을 삭제한다.
  - 사용자가 남의 댓글을 고치려 하면 거부당한다.
- GitHub Issue #30 계약
  - `DELETE /api/community/comments/{commentId}`.
  - 소유권 확인 후 본인 댓글만 삭제.
  - 성공 시 204.
  - Controller/Service/Repository 계층 분리, 공통 오류 응답 형식 준수.

### 수용 시나리오

1. Given 사용자 A가 작성한 댓글과 사용자 A의 유효한 Access Token이 있다.
2. When `DELETE /api/community/comments/{commentId}`를 호출한다.
3. Then 204(본문 없음)를 반환하고, `findById(commentId)`로 재조회하면 댓글이 존재하지 않는다.
4. Given 사용자 A가 작성한 댓글과 사용자 B(작성자 아님)의 유효한 Access Token이 있다.
5. When 같은 API를 호출한다.
6. Then 403 `FORBIDDEN` 공통 오류 형식이며 댓글은 삭제되지 않는다.
7. Given `commentId`에 해당하는 댓글이 없다.
8. When 유효한 인증으로 호출한다.
9. Then 404 `NOT_FOUND` 공통 오류 형식이다.
10. Given Access Token이 없거나 만료·변조됐거나 Refresh Token이다.
11. When 같은 API를 호출한다.
12. Then Controller 진입 전 401 `UNAUTHORIZED` 공통 오류 형식이며 댓글은 삭제되지 않는다.

---

## 범위와 제외

### 포함

- `DELETE /api/community/comments/{commentId}`
- `Authorization: Bearer <accessToken>` 필수
- 대상 댓글 존재 확인, 작성자 본인 확인
- `PostCommentService`에 `deleteComment(authenticatedUserId, commentId)` 추가 (도메인당 서비스 1개 유지)
- 성공 204(본문 없음) 응답
- Service 단위, Controller 슬라이스, 핵심 통합 테스트
- 구현 뒤 실제 Controller 매핑 기준 `ai/api-routes.md` 동기화(동기화 모드에서 처리)

### 제외

- 댓글 목록 조회(Issue #29) — 미구현 상태이므로 "삭제 후 목록에서 제거" 수용 기준은 이 스펙에서 목록 API로 검증하지 않는다. 대신 **삭제 후 `PostCommentRepository.findById(commentId)` 재조회 시 존재하지 않음**으로 대체 검증한다. 목록 API가 구현되면 해당 스펙에서 목록 노출 여부를 별도로 검증한다.
- 댓글 작성·수정·대댓글
- 게시물 CRUD 변경
- 댓글 삭제에 따른 게시물 통계(댓글 수 등) 갱신 — 현재 스펙·엔티티에 그런 필드가 없다.
- 관리자에 의한 타인 댓글 삭제

---

## 설계 결정

### D1. Controller 구조 — 기존 `PostCommentController`에 이어붙이지 않고 새 `CommentController`를 만든다

**결정:** (a) 새 컨트롤러 `CommentController`(`@RequestMapping("/api/community/comments")`)를 추가하고 DELETE 하나만 둔다. 기존 `PostCommentController`(`@RequestMapping("/api/community/posts/{postId}/comments")`)는 그대로 두고 변경하지 않는다.

**근거:**
- `docs/conventions.md`의 API 규칙은 URL을 복수 명사 리소스로 다루되, 실제 코드 컨벤션(레이어 규칙·네이밍)은 컨트롤러 하나가 일관된 요청 매핑 루트를 갖는 것을 전제로 한다. `@RequestMapping`의 클래스 레벨 값은 그 컨트롤러가 다루는 리소스 경로의 공통 prefix를 의미하는데, `PostCommentController`의 클래스 레벨 값은 `/api/community/posts/{postId}/comments`(게시물 하위 컬렉션)이고 이번 DELETE 대상 경로는 `/api/community/comments/{commentId}`(댓글 자체를 최상위 리소스로 다루는 경로)로 구조가 다르다. 메서드에 클래스 매핑과 무관한 전체 경로를 그대로 얹으면(옵션 b) 클래스명·클래스 레벨 매핑이 실제 라우트를 설명하지 못하게 되어 "요청 흐름이 controller에서 한눈에 읽혀야 한다"는 원칙(리뷰 체크 질문 1번)에 어긋난다.
- REST API에서 "생성은 부모 경로 하위, 개별 리소스에 대한 조회/수정/삭제는 리소스 자체의 최상위 경로"로 나누는 것은 흔한 패턴이며(GitHub API 등), Issue #29(댓글 목록 조회, 게시물 하위 경로 예정)와 이번 삭제(댓글 자체 경로)가 애초에 다른 경로 루트를 갖도록 설계된 것으로 보인다.
- 새 컨트롤러를 두어도 서비스는 기존 `PostCommentService` 하나를 그대로 재사용하므로 "도메인당 서비스 1개" 원칙은 유지된다. 컨트롤러 분리는 계층 규칙 위반이 아니라 HTTP 매핑 계층의 리소스 경로 분리일 뿐이다.
- 네이밍: 이 컨트롤러는 댓글 자체를 리소스로 다루는 첫 컨트롤러이므로 `CommentController`로 명명한다(기존 `PostCommentController`와 클래스명이 겹치지 않게).

**대안 기각 사유 (b):** 메서드 레벨에 `@DeleteMapping("/api/community/comments/{commentId}")`처럼 클래스 매핑과 무관한 전체 경로를 지정하면 컴파일·동작은 문제없지만, 클래스명(`PostCommentController`)과 클래스 레벨 매핑(`/api/community/posts/{postId}/comments`)이 이 메서드의 실제 경로를 설명하지 못해 코드를 읽는 사람이 실제 라우트를 컨트롤러 선언부만 보고 파악할 수 없다. 향후 댓글 자체 리소스에 대한 조회 등 API가 추가될 때도 같은 문제가 반복된다.

### D2. Service — 기존 `updatePost`와 동일한 `findById → 소유자 비교 → 예외/수행` 패턴을 그대로 따른다

`PostCommentService.deleteComment(Long authenticatedUserId, Long commentId)`를 추가한다.

```java
@Transactional
public void deleteComment(Long authenticatedUserId, Long commentId) {
	PostComment comment = postCommentRepository.findById(commentId)
		.orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
	if (!comment.getAuthor().getId().equals(authenticatedUserId)) {
		throw new BusinessException(ErrorCode.FORBIDDEN);
	}
	postCommentRepository.delete(comment);
}
```

- `CommunityPostService.updatePost`(Issue #26)가 이미 같은 패턴(`findById` → 소유자 비교 → `BusinessException(ErrorCode.FORBIDDEN)`)을 쓰고 있으므로 일관성을 위해 그대로 재사용한다.
- **Repository에 소유자 검증 포함 삭제 메서드(예: `deleteByIdAndAuthorId`)를 추가하지 않는다.** 이유: (1) 기존 패턴과의 일관성을 우선하라는 지시, (2) `deleteByIdAndAuthorId` 방식은 대상이 존재하지 않는 경우와 소유자가 다른 경우를 구분하지 못해(둘 다 영향받은 행 0건) 404와 403을 분리해서 응답할 수 없다 — 이번 수용 기준은 404와 403을 별도로 요구하므로 Service에서 먼저 조회해 구분해야 한다. `PostCommentRepository`는 `JpaRepository`가 제공하는 `findById`/`delete`만으로 충분하며 이번 이슈로 커스텀 메서드를 추가하지 않는다.
- `PostComment` 엔티티에는 이미 `getAuthor()`가 있으므로 엔티티 변경이 필요 없다. `delete` 전용 상태 변경 메서드(soft delete 등)는 PRD·spec에 명시되지 않았으므로 만들지 않고 `repository.delete(entity)`로 실제 행을 삭제한다(하드 삭제).

### D3. Controller

```java
@DeleteMapping("/{commentId}")
public ResponseEntity<Void> deleteComment(
	@AuthenticationPrincipal AuthenticatedUser principal,
	@PathVariable Long commentId) {
	postCommentService.deleteComment(principal.userId(), commentId);
	return ResponseEntity.noContent().build();
}
```

- Controller는 HTTP 매핑, 인증 주체·경로 변수 추출, Service 호출, 204 반환만 담당한다. 소유권 판단은 Service에 둔다(레이어 규칙).
- 요청 본문 없음. 응답 본문 없음(204).

### D4. 오류와 보안

- 존재하지 않는 댓글은 Service의 `BusinessException(ErrorCode.NOT_FOUND)` → 기존 `GlobalExceptionHandler`가 404로 변환.
- 소유자가 아니면 `BusinessException(ErrorCode.FORBIDDEN)` → 403.
- 비로그인·잘못된 Bearer는 기존 Security entry point가 Controller 진입 전 401로 처리. 이 경로를 공개 화이트리스트에 추가하지 않는다.

---

## 예상 변경 파일

### Production

- `src/main/java/com/finplay/api/community/controller/CommentController.java` (신규)
- `src/main/java/com/finplay/api/community/service/PostCommentService.java` (`deleteComment` 추가)

### Test

- `src/test/java/com/finplay/api/community/service/PostCommentServiceTest.java` (`deleteComment` 케이스 추가)
- `src/test/java/com/finplay/api/community/controller/CommentControllerTest.java` (신규, `@WebMvcTest`)
- `src/test/java/com/finplay/api/community/PostCommentDeleteIntegrationTest.java` (신규, `@SpringBootTest` + MySQL Testcontainers)

### Documentation

- `ai/api-routes.md` (동기화 모드에서 처리 — 이번 계획 모드 범위 아님)
- `ai/specs/008-community/issue-30-tasks.md`

### 만들거나 수정하지 않을 파일

- `PostCommentRepository` — 커스텀 소유자 검증 삭제 메서드를 추가하지 않는다(D2 근거).
- `PostComment` 엔티티 — soft delete 필드·상태 변경 메서드를 추가하지 않는다.
- `ai/specs/008-community/spec.md`
- Flyway 마이그레이션 — 스키마 변경 없음.
- 다른 커뮤니티 API용 production/test 파일.

---

## 테스트 계획

### Service 단위 (`PostCommentServiceTest`)

- 본인 댓글은 `findById`로 조회 후 `repository.delete(comment)`가 호출되고 예외가 발생하지 않는다.
- 타인 댓글은 `FORBIDDEN` 예외를 던지고 `repository.delete`가 호출되지 않는다.
- 존재하지 않는 `commentId`는 `NOT_FOUND` 예외를 던지고 소유자 비교·삭제를 수행하지 않는다.

### Controller 슬라이스 (`@WebMvcTest`, `CommentControllerTest`)

- 인증 사용자의 유효한 요청은 Service에 `commentId`, 인증 사용자 ID를 전달하고 204(본문 없음)를 반환한다.
- Service가 `FORBIDDEN`을 던지면 403 공통 오류 형식으로 변환된다.
- Service가 `NOT_FOUND`를 던지면 404 공통 오류 형식으로 변환된다.
- Access Token 없음·만료·변조·Refresh 타입은 401이고 Service를 호출하지 않는다.

### 통합 (`@SpringBootTest` + MySQL Testcontainers, `PostCommentDeleteIntegrationTest`)

- 사용자 A가 본인이 작성한 댓글을 삭제하면 204를 반환하고, `postCommentRepository.findById(commentId)`가 빈 `Optional`을 반환한다(실제 DB 행 삭제 확인).
- 사용자 B(작성자 아님)가 사용자 A의 댓글 삭제를 시도하면 403이고, `findById(commentId)`로 재조회 시 댓글이 여전히 존재한다.
- 존재하지 않는 `commentId`는 404다.
- 비로그인 요청은 401이고 댓글 행 수가 줄지 않는다.

---

## 미확정 사항

- 없음. 이번 계획은 PRD `COM-002`·`COM-003`, spec.md, Issue #30 계약과 일치한다. "삭제 후 목록에서 제거" 수용 기준은 댓글 목록 조회(Issue #29)가 아직 구현되지 않아 목록 API로 직접 검증할 수 없으므로, 위 범위와 제외 절에 명시한 대로 `findById` 재조회 부재로 대체 검증한다. 목록 API 구현 후 필요하면 별도 스펙에서 목록 노출 검증을 추가한다.

---

## 완료 조건

- [ ] 본인 댓글 삭제는 204(본문 없음)를 반환하고 실제 DB 행이 삭제된다.
- [ ] 삭제 후 `findById(commentId)` 재조회 시 댓글이 존재하지 않는다.
- [ ] 타인 댓글 삭제 시도는 403 `FORBIDDEN`이며 댓글은 삭제되지 않는다.
- [ ] 존재하지 않는 `commentId`는 404 `NOT_FOUND`다.
- [ ] 비로그인 요청은 401 `UNAUTHORIZED`이며 댓글은 삭제되지 않는다.
- [ ] Controller/Service/Repository 계층 분리를 지키고, 소유권 판단은 Service에 있다.
- [ ] 오류 응답은 공통 형식(`{"error":{"code":...,"message":...,"requestId":...}}`)을 따른다.
- [ ] 실제 Controller 매핑을 `ai/api-routes.md`에 동기화한다(동기화 모드).
- [ ] `.\gradlew.bat build --no-daemon --max-workers=1`을 새로 실행해 통과한다.
