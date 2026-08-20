# Issue #28 커뮤니티 게시물 댓글 작성 API 구현 계획

**Goal:** 인증 사용자가 `POST /api/community/posts/{postId}/comments`에 댓글 본문을 제출하면, 서버가 Access Token의 사용자를 작성자로 연결한 평면형 댓글을 저장하고 201을 반환한다.

**관련 정본:** GitHub Issue #28, PRD `COM-002`·`COM-003`와 §5 공통 오류표·§6 데이터 모델, `spec.md`, ADR-0002·0003·0004, `docs/conventions.md`

**선행:** Issue #23·#25 구현으로 `community_posts`, `CommunityPost`·Repository·Service·Controller, `UserQueryService`가 존재한다. `/api/community/**`는 공개 경로가 아니므로 기존 `anyRequest().authenticated()` 정책으로 보호된다.

---

## 요구사항 ID와 수용 기준

### 대상 요구사항

- PRD `COM-002 댓글`
  - 로그인 사용자는 게시물에 평면형 댓글을 작성한다.
- PRD `COM-003 권한`
  - 댓글 조회는 인증 사용자에게 허용한다.
- PRD §5 공통 오류
  - 본문 검증 실패는 400 `VALIDATION_ERROR`.
  - 인증 없음·만료는 401 `UNAUTHORIZED`.
  - 대상 게시물 없음은 404 `NOT_FOUND`.
- PRD §6 데이터 모델
  - `post_comments`는 게시물, 작성자, 본문, 생성시각을 저장한다.
- `ai/specs/008-community/spec.md`
  - 댓글 본문은 공백만으로 이뤄질 수 없다.
  - 댓글은 평면형이며 수정·대댓글은 범위에서 제외한다.
- GitHub Issue #28 계약
  - `POST /api/community/posts/{postId}/comments`.
  - 유효한 본문은 201.
  - 빈 값·공백 본문은 400 공통 오류.
  - 존재하지 않는 게시물은 404.
  - 작성자는 인증 사용자로 결정한다.

### 수용 시나리오

1. Given 존재하는 게시물, 사용자 A의 유효한 Access Token, 유효한 댓글 본문이 있다.
2. When `POST /api/community/posts/{postId}/comments`를 호출한다.
3. Then 201과 생성 댓글의 `commentId`, `authorNickname`, `content`, `createdAt`을 반환하고, 댓글 행은 대상 게시물과 사용자 A를 참조한다.
4. And 요청에는 작성자나 부모 댓글을 지정하는 필드를 받지 않는다.
5. Given 본문이 누락·빈 문자열·공백뿐이다.
6. When 같은 API를 호출한다.
7. Then 400 `VALIDATION_ERROR` 공통 오류 형식이며 댓글 행은 생기지 않는다.
8. Given `postId`에 해당하는 게시물이 없다.
9. When 유효한 인증과 본문으로 호출한다.
10. Then 404 `NOT_FOUND` 공통 오류 형식이며 댓글 행은 생기지 않는다.
11. Given Access Token이 없거나 만료·변조됐거나 Refresh Token이다.
12. When 같은 API를 호출한다.
13. Then Controller 진입 전 401 `UNAUTHORIZED` 공통 오류 형식이며 댓글 행은 생기지 않는다.

---

## 범위와 제외

### 포함

- `POST /api/community/posts/{postId}/comments`
- `Authorization: Bearer <accessToken>` 필수
- 댓글 본문 필수·공백 검증
- 대상 게시물 존재 확인
- 인증 주체 ID로 작성자를 조회하고 댓글에 연결
- 평면형 `post_comments` 엔티티·Repository·새 Flyway 마이그레이션
- 댓글 생성과 저장의 단일 트랜잭션
- 정상 201 응답
- Service 단위, Repository/MySQL 슬라이스, Controller 슬라이스, 핵심 통합 테스트
- 구현 뒤 실제 Controller 매핑 기준 `ai/api-routes.md` 동기화

### 제외

- 댓글 목록 조회·삭제
- 댓글 수정·대댓글 및 `parent_comment_id`
- 게시물 목록·작성·조회·수정·삭제 변경
- 댓글 좋아요·신고·첨부
- 작성자 소유권 변경 규칙
- 클라이언트가 작성자 ID·닉네임을 지정하는 기능
- 게시물 삭제 시 댓글 처리 정책 확정 또는 게시물 삭제 API 구현
- 다른 커뮤니티 API를 위한 선행 Controller·Service·DTO

---

## API 계약

| 항목 | 내용 |
|---|---|
| Method / Path | `POST /api/community/posts/{postId}/comments` |
| 인증 | `Authorization: Bearer <유효한 Access JWT>` |
| 요청 | `{"content":"댓글 본문"}` (`PostCommentCreateRequest`) |
| 작성자 입력 | 없음. `@AuthenticationPrincipal AuthenticatedUser.userId()`만 사용 |
| 부모 댓글 입력 | 없음. 평면형 댓글만 저장 |
| 성공 | 201 + `PostCommentResponse` (`commentId`, `authorNickname`, `content`, `createdAt`) |
| 본문 누락·빈 값·공백·1,000자 초과 | 400 `VALIDATION_ERROR` 공통 오류 형식 |
| 게시물 미존재 | 404 `NOT_FOUND` 공통 오류 형식 |
| Access Token 없음·만료·변조·잘못된 타입 | 401 `UNAUTHORIZED` 공통 오류 형식 |

### 입력 명세

| 필드 | 필수 | 검증 |
|---|---|---|
| `content` | 필수 | `@NotBlank`, `@Size(max = 1000)` |

- 검증 메시지는 한글과 마침표를 사용한다.
- DTO에는 `authorId`, `userId`, `authorNickname`, `parentCommentId`를 두지 않는다.
- 경로 변수 `postId`의 양수 검증이나 잘못된 숫자 형식에 대한 별도 계약은 PRD와 Issue #28에 없으므로 새 정책을 만들지 않는다.

### 성공 응답

201 `PostCommentResponse`는 다음 필드만 반환한다.

| 필드 | 형식·의미 |
|---|---|
| `commentId` | 생성된 댓글 ID |
| `authorNickname` | 인증 사용자의 닉네임 |
| `content` | 저장된 댓글 본문 |
| `createdAt` | ISO-8601 생성시각 |

작성자 내부 ID와 게시물 ID는 응답에 노출하지 않는다. PRD의 `post_comments` 데이터 모델은 생성시각만 저장하고 댓글 수정도 범위에서 제외하므로 `updatedAt`은 저장하거나 반환하지 않는다.

---

## 설계 결정

### D1. 댓글은 게시물 바로 아래의 평면형 모델이다

`PostComment`는 `CommunityPost post`, `User author`, `String content`, `LocalDateTime createdAt`만 가진다. 부모 댓글 연관이나 수정시각은 PRD 데이터 모델과 1차 범위에 없으므로 추가하지 않는다.

### D2. 작성자는 인증 주체로만 결정한다

Controller는 `postId`, `AuthenticatedUser.userId()`, 검증된 본문만 Service에 전달한다. Service는 기존 `UserQueryService.getUser(userId)`를 사용하며 커뮤니티 도메인에서 `UserRepository`를 직접 주입하지 않는다.

### D3. 게시물 존재 확인과 저장은 Service 트랜잭션 안에서 수행한다

요청 흐름은 `PostCommentController → PostCommentService → PostCommentRepository`로 둔다. Service는 같은 커뮤니티 도메인의 `CommunityPostRepository`로 게시물을 조회하고, 없으면 `BusinessException(ErrorCode.NOT_FOUND)`를 던진 뒤 댓글을 저장한다.

- Controller: HTTP 매핑, `@Valid`, 경로·인증 주체 추출, 201 반환.
- Service: 게시물·작성자 조회, 현재 시각 생성, 댓글 생성·저장, `@Transactional`.
- Repository: 댓글 저장과 실제 DB 매핑.

게시물과 댓글은 같은 community 도메인이므로 `PostCommentService`가 `CommunityPostRepository`를 사용하는 것은 ADR-0002의 도메인 간 repository 직접 참조 금지와 충돌하지 않는다.

### D4. 시간과 엔티티 생성

- 기존 방식과 같이 `Clock`을 주입하고 `LocalDateTime.now(clock)`을 사용한다.
- `PostComment.create(post, author, content, now)` 정적 팩토리로 완전한 상태를 만든다.
- setter를 두지 않고 DTO를 엔티티에 전달하지 않는다.

### D5. DB 스키마

머지된 V1~V3는 수정하지 않고 다음 가용 번호의 새 Flyway 마이그레이션을 추가한다.

```sql
CREATE TABLE post_comments (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    post_id    BIGINT       NOT NULL,
    author_id  BIGINT       NOT NULL,
    content    VARCHAR(1000) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_post_comments_post
        FOREIGN KEY (post_id) REFERENCES community_posts (id),
    CONSTRAINT fk_post_comments_author
        FOREIGN KEY (author_id) REFERENCES users (id),
    INDEX idx_post_comments_post_created_at_id (post_id, created_at, id)
);
```

- `VARCHAR(1000)`은 요청 DTO의 `@Size(max = 1000)`과 일치시킨다.
- `(post_id, created_at, id)` 인덱스는 PRD의 후속 오래된 순 목록을 안정적으로 지원하지만 목록 API는 구현하지 않는다.
- 게시물 FK의 `ON DELETE` 동작은 게시물 삭제 정책이 아직 확정되지 않았으므로 이번 이슈에서 임의로 cascade를 선택하지 않는다. MySQL 기본 제한 동작을 사용한다.

### D6. 오류와 보안

- Bean Validation 실패는 기존 `GlobalExceptionHandler`를 통해 400 `VALIDATION_ERROR`.
- 게시물 미존재는 기존 공통 `BusinessException(ErrorCode.NOT_FOUND)`로 404.
- 비로그인·잘못된 Bearer는 기존 Security entry point를 통해 401 `UNAUTHORIZED`.
- 댓글 경로를 공개 화이트리스트에 추가하지 않는다.

---

## 예상 변경 파일

### Production

- `src/main/java/com/finplay/api/community/domain/PostComment.java`
- `src/main/java/com/finplay/api/community/repository/PostCommentRepository.java`
- `src/main/java/com/finplay/api/community/service/PostCommentService.java`
- `src/main/java/com/finplay/api/community/controller/PostCommentController.java`
- `src/main/java/com/finplay/api/community/dto/request/PostCommentCreateRequest.java`
- `src/main/java/com/finplay/api/community/dto/response/PostCommentResponse.java`
- `src/main/resources/db/migration/V{NEXT}__create_post_comments_table.sql`

### Test

- `src/test/java/com/finplay/api/community/repository/PostCommentRepositoryTest.java`
- `src/test/java/com/finplay/api/community/service/PostCommentServiceTest.java`
- `src/test/java/com/finplay/api/community/controller/PostCommentControllerTest.java`
- `src/test/java/com/finplay/api/community/PostCommentCreateIntegrationTest.java`

### Documentation

- `ai/api-routes.md`
- `ai/specs/008-community/issue-28-tasks.md`

### 만들거나 수정하지 않을 파일

- 머지된 Flyway 마이그레이션 V1~V3
- `ai/specs/008-community/spec.md`
- 다른 커뮤니티 API용 production/test 파일

---

## 테스트 계획

### Service 단위

- 존재하는 게시물과 인증 사용자를 연결해 본문·고정 시각이 일치하는 댓글을 저장한다.
- 게시물 미존재는 `NOT_FOUND`이고 사용자 조회·댓글 저장을 수행하지 않는다.
- 작성자 조회 실패는 기존 `UserQueryService` 계약의 401 `UNAUTHORIZED`이고 댓글을 저장하지 않는다.

### Repository 슬라이스 (`@DataJpaTest` + MySQL Testcontainers)

- 실제 새 마이그레이션으로 `post_comments`가 생성되고 게시물 FK, 작성자 FK, 본문, 마이크로초 생성시각을 저장·조회한다.
- 존재하지 않는 게시물 또는 작성자 FK 저장을 실제 DB가 거부한다.
- 본문 최대 1,000자와 DB 컬럼 길이가 일치한다.

### Controller 슬라이스 (`@WebMvcTest`)

- 인증 사용자의 유효한 요청은 Service에 `postId`, 인증 사용자 ID, 본문만 전달하고 201 및 응답 네 필드를 반환한다.
- 본문 누락·빈 문자열·공백 및 1,000자 초과는 400 `VALIDATION_ERROR`이고 Service를 호출하지 않는다.
- Service의 게시물 미존재 예외는 404 `NOT_FOUND`.
- Access Token 없음·만료·변조·Refresh 타입은 401이고 Service를 호출하지 않는다.

### 통합 (`@SpringBootTest` + MySQL Testcontainers)

- 사용자 A가 존재하는 게시물에 댓글을 작성하면 201 응답의 `commentId`, `authorNickname`, `content`, `createdAt`과 DB의 게시물·작성자 FK·본문·생성시각이 일치한다.
- 공백 본문 400, 게시물 미존재 404, 비로그인 401에서 댓글 행 수가 증가하지 않는다.
- 요청에 작성자 또는 부모 댓글처럼 보이는 추가 필드가 있어도 저장된 작성자는 인증 사용자이고 평면 구조가 유지된다.

---

## 미확정 사항

- 없음. 댓글 본문은 최대 1,000자이며, 201 응답은 `commentId`, `authorNickname`, `content`, `createdAt` 네 필드로 확정했다. 댓글 수정과 수정시각 저장이 범위에 없으므로 `updatedAt`은 포함하지 않는다. ADR 충돌은 없다.

---

## 완료 조건

- [ ] 댓글 본문 최대 1,000자와 201 응답 네 필드가 DTO·DB·테스트·API 문서에서 일치한다.
- [ ] 정상 요청은 대상 게시물과 인증 사용자를 연결한 평면형 댓글을 저장하고 201 및 `commentId`, `authorNickname`, `content`, `createdAt`을 반환한다.
- [ ] 클라이언트 입력으로 작성자나 부모 댓글을 지정할 수 없다.
- [ ] 누락·빈 값·공백 및 1,000자 초과는 400 `VALIDATION_ERROR`이며 DB 변경이 없다.
- [ ] 대상 게시물 미존재는 404 `NOT_FOUND`이며 DB 변경이 없다.
- [ ] 비로그인 요청은 401 `UNAUTHORIZED`이며 DB 변경이 없다.
- [ ] 실제 MySQL에서 마이그레이션·두 FK·저장 흐름을 검증한다.
- [ ] 댓글 조회·삭제·수정·대댓글 등 다른 API를 구현하지 않는다.
- [ ] 실제 Controller 매핑을 `ai/api-routes.md`에 동기화한다.
- [ ] `.\gradlew.bat build --no-daemon --max-workers=1`을 새로 실행해 통과한다.
