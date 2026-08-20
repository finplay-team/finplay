# Issue #27 커뮤니티 게시물 삭제 API 구현 계획

**Goal:** 인증 사용자가 `DELETE /api/community/posts/{postId}`로 본인 게시물을 삭제하면 204를 받는다. 타인 게시물은 403, 없는 게시물은 404이며 어떤 실패 경로도 데이터를 바꾸지 않는다. 댓글이 달린 게시물도 삭제가 성공해야 한다.

**관련 정본:** GitHub Issue #27, PRD `COM-001`·`COM-003`와 §5 공통 오류표, `spec.md`, ADR-0002, ADR-0004, `docs/conventions.md`

**선행:** Issue #23(작성)·#25(단건 조회)·#26(수정, PR #61로 dev 머지 완료)이 존재해 `CommunityPost`·`CommunityPostRepository`·`CommunityPostService`·`CommunityPostController`가 있다. Issue #28(댓글 작성, dev 머지 완료)로 `PostComment`·`PostCommentRepository`(현재 빈 `JpaRepository` 인터페이스)·`post_comments` 테이블(`V4__create_post_comments_table.sql`)이 존재한다.

아직 없는 것: Controller의 DELETE 엔드포인트, Service의 삭제 로직, 댓글 처리(설계 결정 D1 참고), `ai/api-routes.md` 반영, 관련 테스트.

## 요구사항 ID와 수용 기준

- PRD `COM-001`: 게시물 본인 삭제를 제공한다.
- PRD `COM-003`: 삭제는 소유자만 가능하다. 다른 사용자의 콘텐츠 삭제는 403 `FORBIDDEN`.
- PRD §5: 인증 없음·만료는 401 `UNAUTHORIZED`, 대상 없음은 404 `NOT_FOUND` 공통 오류 형식이다.
- `docs/conventions.md`: 본문 없는 성공은 204.

수용 시나리오는 다음과 같다.

1. 댓글이 없는 본인 게시물을 삭제하면 204를 반환하고 게시물이 DB에서 사라진다.
2. 댓글이 달린 본인 게시물을 삭제해도 204를 반환하고 게시물과 그 댓글이 함께 사라진다 (부모-자식 정합성 유지, 고아 댓글 미허용).
3. 타인 게시물을 삭제하려 하면 403 `FORBIDDEN`을 반환하고 게시물·댓글은 변경되지 않는다.
4. 존재하지 않는 `postId`를 삭제하려 하면 404 `NOT_FOUND`를 반환한다.
5. 인증 없이 호출하면 Controller 진입 전 401 `UNAUTHORIZED`를 반환한다.

## 범위와 제외

### 포함

- `DELETE /api/community/posts/{postId}`
- `CommunityPostService`에 `deletePost(authenticatedUserId, postId)` 추가 — 404/403 판정은 기존 `updatePost`와 동일 패턴 재사용
- 댓글이 있는 게시물도 삭제되도록 처리 (설계 결정 D1)
- `PostCommentRepository`에 게시물 기준 일괄 삭제 메서드 추가 (D1이 (b)로 결정될 경우)
- Controller/Service/Repository 계층 분리 유지 (ADR-0002)
- Service 단위, Controller MVC 슬라이스, 실제 DB 통합 테스트 (댓글 없음/있음·타인 삭제·없는 ID·204 케이스)
- 구현 후 실제 Controller 매핑 기준 `ai/api-routes.md` 동기화

### 제외

- 투자일기, AI, 랭킹, 알림, 지정가, Kafka, 동시성 제어 (오케스트레이터 지시)
- 게시물 목록·작성·수정, 댓글 조회·작성·삭제 API 자체 (댓글 삭제는 이번 이슈에서 게시물 삭제의 부수 효과로만 다룸)
- 요청 DTO 신설 — DELETE 요청은 본문이 없으므로 불필요 (경로 변수 `postId`만 사용)
- 삭제된 게시물의 소프트 삭제(논리 삭제) — PRD·Issue #27에 요구 없음, 물리 삭제로 처리
- `community_posts` 테이블 자체의 FK·스키마 변경

## API 계약

| 항목 | 내용 |
|---|---|
| Method / Path | `DELETE /api/community/posts/{postId}` |
| 인증 | `Authorization: Bearer <유효한 Access JWT>` 필수 |
| 요청 본문 | 없음 |
| 성공 | 204, 본문 없음 |
| 게시물 미존재 | 404 `NOT_FOUND` 공통 오류 형식 |
| 소유자 아님 | 403 `FORBIDDEN` 공통 오류 형식, 게시물·댓글 미변경 |
| 인증 없음·만료·변조·잘못된 토큰 타입 | 401 `UNAUTHORIZED` 공통 오류 형식 |

경로 변수 `postId`는 `Long`으로 전달한다. 요청 본문이 없으므로 신규 DTO는 만들지 않는다.

## 설계 결정

### D1. 댓글이 있는 게시물의 삭제 처리 — 방안 (b) 채택: Service에서 댓글을 먼저 삭제한다

**검토한 두 방안**

- (a) 새 `V5` 마이그레이션으로 `fk_post_comments_post`를 `DROP` 후 `ON DELETE CASCADE`로 재생성.
- (b) `CommunityPostService.deletePost`가 같은 트랜잭션 안에서 `PostCommentRepository`로 해당 게시물의 댓글을 먼저 삭제한 뒤 게시물을 삭제한다 (`PostCommentRepository`에 `deleteByPost_Id` 추가).

**결정: (b)**

**근거**

1. **ADR-0004와의 정합성.** ADR-0004는 "머지된 마이그레이션 파일은 절대 수정하지 않는다"고만 금지하며 새 버전 파일 추가 자체는 허용한다. 따라서 (a)도 규칙 위반은 아니다. 다만 이번 이슈의 요구는 "댓글이 달린 게시물 삭제 성공"이라는 애플리케이션 동작이지 스키마 자체의 결함 수정이 아니다. FK를 스키마 차원에서 영구히 CASCADE로 바꾸는 것은 이 요구를 넘어서는 변경이며, 향후 댓글 삭제 정책(예: 댓글 신고·소프트 삭제 도입 시)이 스키마 제약에 암묵적으로 묶이게 된다.
2. **ADR-0002 레이어드 아키텍처와의 정합성.** ADR-0002는 `controller → service → repository` 흐름과 트랜잭션 경계를 서비스 계층 책임으로 둔다. "게시물을 지우면 그 댓글도 함께 지운다"는 것은 도메인 규칙(비즈니스 규칙)이며, 이를 DB 제약(CASCADE)이 아니라 서비스 로직으로 표현하면 이 규칙이 코드에서 명시적으로 드러나고 테스트(Service 단위 테스트)로 직접 검증할 수 있다. CASCADE는 DB가 암묵적으로 처리하므로 애플리케이션 코드만 보고는 부수 효과를 알 수 없다.
3. **변경 범위가 작고 가역적이다.** (b)는 `PostCommentRepository`에 메서드 하나(`deleteByPost_Id` 또는 Spring Data 파생 쿼리)를 추가하고 `CommunityPostService.deletePost`에서 호출 순서를 정하는 것으로 끝난다. 스키마·마이그레이션 이력에 영향이 없고, 롤백도 코드 리버트만으로 가능하다. (a)는 FK 재생성이 필요해 운영 반영 시 락 범위·다운타임을 추가로 고려해야 한다.
4. **테스트 전략(ADR-0003)과의 정합성.** "댓글 있음" 삭제가 성공하는지는 Service 단위 테스트로 `PostCommentRepository` 호출 여부까지 검증할 수 있어야 하는데, (b)는 이 검증이 자연스럽다. (a)를 택하면 이 동작이 순수 DB 제약에 의존하게 되어 Service 단위 테스트로는 검증할 수 없고 Testcontainers 통합 테스트에서만 확인 가능해, 테스트 피라미드의 하위 레이어(단위 테스트)가 이 요구사항을 놓치게 된다.

**반영**

- `PostCommentRepository`에 `void deleteByPost_Id(Long postId)` (Spring Data JPA 파생 삭제 쿼리)를 추가한다.
- `CommunityPostService.deletePost`는 `@Transactional` 안에서 (1) 게시물 조회 및 404/403 판정 → (2) `postCommentRepository.deleteByPost_Id(postId)` → (3) `communityPostRepository.delete(post)` 순서로 처리한다. 댓글 삭제를 게시물 삭제보다 먼저 실행해 FK 제약(부모 행이 남아있는 상태에서 자식 먼저 삭제) 위반을 피한다.
- `post_comments` 테이블·`V4` 마이그레이션은 변경하지 않는다.

### D2. 서비스 판정 로직은 기존 `updatePost`와 동일 패턴을 따른다

`communityPostRepository.findById(postId)`로 조회해 없으면 `BusinessException(NOT_FOUND)`, 작성자 불일치면 `BusinessException(FORBIDDEN)`을 던진다. 이 판정은 댓글 삭제·게시물 삭제보다 먼저 실행되어, 실패 시 어떤 데이터도 변경되지 않는다.

### D3. Controller는 기존 GET/POST/PATCH 옆에 DELETE 매핑만 추가한다

- Controller: `@DeleteMapping("/{postId}")`, `@AuthenticationPrincipal AuthenticatedUser`, `postId` 경로 변수 → `communityPostService.deletePost(principal.userId(), postId)` 호출, `ResponseEntity.noContent().build()` (204) 응답.
- 요청 DTO 없음 — `docs/conventions.md`의 DTO 접미사 규칙을 적용할 대상 자체가 없다.
- 흐름은 ADR-0002에 따라 `CommunityPostController → CommunityPostService → CommunityPostRepository`(및 `PostCommentRepository`)를 유지한다.

## 예상 변경 파일

### Production

- `src/main/java/com/finplay/api/community/controller/CommunityPostController.java` (DELETE 매핑 추가)
- `src/main/java/com/finplay/api/community/service/CommunityPostService.java` (`deletePost` 추가)
- `src/main/java/com/finplay/api/community/repository/PostCommentRepository.java` (`deleteByPost_Id` 추가)

### Test

- `src/test/java/com/finplay/api/community/service/CommunityPostServiceTest.java` (deletePost 케이스 추가)
- `src/test/java/com/finplay/api/community/controller/CommunityPostControllerTest.java` (deletePost MVC 슬라이스 추가)
- `src/test/java/com/finplay/api/community/CommunityPostDeleteIntegrationTest.java` (신규, Testcontainers)

### Documentation

- `ai/api-routes.md` (동기화 모드에서 처리)
- `ai/specs/008-community/issue-27-tasks.md`

## 테스트 계획

### Service 단위

- 댓글이 없는 본인 게시물 삭제 시 `communityPostRepository.delete(post)`가 호출되고 예외 없이 끝난다.
- 댓글이 있는 본인 게시물 삭제 시 `postCommentRepository.deleteByPost_Id(postId)`가 게시물 삭제 이전에 호출된다 (순서 검증 또는 두 호출 모두 발생 검증).
- 존재하지 않는 `postId`는 `BusinessException(NOT_FOUND)`를 던지고 어떤 repository의 삭제 메서드도 호출하지 않는다.
- 소유자가 아닌 사용자가 삭제하면 `BusinessException(FORBIDDEN)`을 던지고 어떤 repository의 삭제 메서드도 호출하지 않는다.

### Controller MVC 슬라이스

- 인증된 소유자의 요청은 서비스에 `userId`·`postId`를 전달하고 204(본문 없음)를 반환한다.
- 서비스의 `NOT_FOUND`는 404, `FORBIDDEN`은 403 공통 오류 형식으로 반환한다.
- 비로그인 요청은 401이고 서비스를 호출하지 않는다.

### 통합 (`@SpringBootTest` + MySQL Testcontainers)

- 댓글이 없는 게시물을 저장한 뒤 소유자로 DELETE를 호출하면 204이고 게시물이 재조회 시 없음을 검증한다.
- 댓글이 있는 게시물(댓글 1건 이상 저장)을 소유자로 DELETE 하면 204이고 게시물과 해당 댓글이 모두 재조회 시 없음을 검증한다 (FK 제약 위반 없이 성공).
- 다른 사용자로 DELETE를 호출하면 403이고 게시물·댓글이 DB에 그대로 남아있음을 재조회로 검증한다.
- 존재하지 않는 `postId`는 404를 반환한다.
- 비로그인 요청은 401임을 검증한다.

## 미확정 사항

- 게시물 삭제 시 좋아요·신고 등 다른 연관 데이터 처리는 1차 범위(PRD COM-001)에서 좋아요·신고 자체가 제외되어 있으므로 다루지 않는다.
- 소프트 삭제(논리 삭제) 여부는 PRD·Issue #27에 명시가 없어 물리 삭제로 처리한다. 필요해지면 별도 이슈로 제안한다.

## 완료 조건

- [ ] 댓글이 없는 본인 게시물 삭제가 204를 반환하고 게시물이 DB에서 사라진다.
- [ ] 댓글이 있는 본인 게시물 삭제도 204를 반환하고 게시물과 댓글이 함께 사라진다.
- [ ] 타인 게시물 삭제는 403 `FORBIDDEN`이며 게시물·댓글이 변경되지 않는다.
- [ ] 존재하지 않는 게시물 삭제는 404 `NOT_FOUND`를 반환한다.
- [ ] 비로그인 요청은 401 `UNAUTHORIZED`를 반환한다.
- [ ] `V4` 마이그레이션과 `post_comments` FK 제약을 변경하지 않는다.
- [ ] 실제 Controller 매핑을 `ai/api-routes.md`에 동기화한다 (동기화 모드에서 처리).
- [ ] 대상 테스트와 `.\gradlew.bat build --no-daemon --max-workers=1`을 새로 실행해 통과한다.
