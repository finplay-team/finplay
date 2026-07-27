# Issue #23 커뮤니티 게시물 작성 API 구현 계획

**Goal:** 인증 사용자가 `POST /api/community/posts`에 텍스트 제목과 본문을 제출하면 서버가 Access Token의 사용자만 작성자로 저장하고, 생성된 게시물을 201로 반환한다.

**관련 정본:** GitHub Issue #23, PRD `COM-001`·`COM-003`와 §5 공통 오류표·§6 데이터 모델, `spec.md`, ADR-0002·0003·0004, `docs/conventions.md`

**선행:** Bearer 인증 기반과 `AuthenticatedUser(userId, role)`, `users` 테이블이 구현되어 있다. `/api/community/**`는 공개 화이트리스트에 없으므로 기존 `anyRequest().authenticated()`에 의해 보호된다.

---

## 요구사항 ID와 수용 기준

### 대상 요구사항

- PRD `COM-001 게시물`
  - 로그인 사용자는 텍스트 제목과 본문으로 게시물을 작성한다.
- PRD `COM-003 권한`
  - 게시물 조회는 인증 사용자에게만 허용한다.
- PRD §5 공통 오류
  - 형식 오류는 400 `VALIDATION_ERROR`.
  - 인증 없음·만료는 401 `UNAUTHORIZED`.
- PRD §6 데이터 모델
  - `community_posts`는 작성자, 제목, 본문, 생성·수정시각을 저장한다.
- `docs/specs/008-community/spec.md`
  - 제목과 본문은 공백만으로 이뤄질 수 없다.
  - 비로그인 접근은 401이다.
- GitHub Issue #23 계약
  - `POST /api/community/posts`.
  - 정상 201, 공백 입력 400 `VALIDATION_ERROR`, 비로그인 401.
  - 인증 사용자를 작성자로 사용해 작성자 위조를 막는다.

### 수용 시나리오

1. Given 사용자 A의 유효한 Access Token과 유효한 제목·본문이 있다.
2. When `POST /api/community/posts`를 호출한다.
3. Then 201과 생성된 게시물 응답을 반환하고, DB 행의 작성자는 사용자 A다.
4. And 요청 본문에는 작성자 ID·닉네임 등 작성자 지정 필드를 받지 않는다.
5. Given 제목 또는 본문이 누락·빈 문자열·공백뿐이거나 확정 최대 길이를 넘는다.
6. When 같은 API를 호출한다.
7. Then 400 `VALIDATION_ERROR` 공통 오류 형식이며 게시물 행은 생기지 않는다.
8. Given Bearer Access Token이 없거나 만료·변조됐거나 Refresh Token이다.
9. When 같은 API를 호출한다.
10. Then Controller 진입 전 401 `UNAUTHORIZED` 공통 오류 형식이며 게시물 행은 생기지 않는다.

---

## 범위와 제외

### 포함

- `POST /api/community/posts`
- `Authorization: Bearer <accessToken>` 필수
- `title`, `content` 요청 검증
- 인증 주체 ID로 실제 `User`를 조회해 작성자로 연결
- 게시물 생성과 저장의 단일 트랜잭션
- 생성된 게시물의 201 응답
- `community_posts` 신규 Flyway 마이그레이션
- Service 단위, Repository/MySQL 슬라이스, Controller 슬라이스, 핵심 통합 테스트
- 구현 뒤 실제 Controller 매핑 기준 `docs/api-routes.md` 동기화

### 제외

- 게시물 목록·단건 조회·수정·삭제
- 댓글 작성·조회·삭제
- 수익 인증 분류·거래 첨부·좋아요·신고
- 댓글 수정·대댓글
- 이미지·파일 업로드
- 관리자 기능·게시물 검색
- 클라이언트가 지정한 작성자 ID·닉네임을 신뢰하거나 저장하는 기능
- 다른 커뮤니티 API를 위한 선행 Controller·Service·DTO
- 삭제 정책과 댓글 외래키 정책(댓글 구현 이슈에서 확정)

---

## API 계약

| 항목 | 내용 |
|---|---|
| Method / Path | `POST /api/community/posts` |
| 인증 | `Authorization: Bearer <유효한 Access JWT>` |
| 요청 | `{"title":"게시물 제목","content":"게시물 본문"}` (`CommunityPostCreateRequest`) |
| 작성자 입력 | 없음. `@AuthenticationPrincipal AuthenticatedUser.userId()`만 사용 |
| 성공 | 201 + `CommunityPostResponse` |
| 제목·본문 누락·빈 값·공백·최대 길이 초과 | 400 `VALIDATION_ERROR` 공통 오류 형식 |
| Access Token 없음·만료·변조·잘못된 타입 | 401 `UNAUTHORIZED` 공통 오류 형식 |

### 요청 검증

| 필드 | 필수 | 검증 |
|---|---|---|
| `title` | 필수 | `@NotBlank`, `@Size(max = 100)` |
| `content` | 필수 | `@NotBlank`, `@Size(max = 5000)` |

- 검증 메시지는 컨벤션에 따라 한글과 마침표를 사용한다.
- JSON에 `authorId`, `userId`, `authorNickname` 같은 알 수 없는 필드가 들어와도 작성자 결정에 사용하지 않는다.
- 작성자 위조 방지를 API 계약으로 더 강하게 고정하려면 unknown property 거부 정책이 필요하지만, 현재 전역 Jackson 정책에는 근거가 없다. 필수 수용 기준은 “작성자 입력 필드가 DTO에 없고, 인증 주체만 사용한다”로 고정한다.

### 성공 응답

201 `CommunityPostResponse`는 다음 필드만 반환한다.

| 필드 | 형식·의미 |
|---|---|
| `postId` | 생성된 게시물 ID |
| `authorNickname` | 인증 사용자의 닉네임 |
| `title` | 저장된 제목 |
| `content` | 저장된 본문 |
| `createdAt` | ISO-8601 생성시각 |
| `updatedAt` | ISO-8601 수정시각. 생성 직후 `createdAt`과 같음 |

작성자 내부 ID는 응답에 노출하지 않는다.

---

## 설계 결정

### D1. 작성자는 인증 주체로만 결정한다

Controller는 `@AuthenticationPrincipal AuthenticatedUser`와 검증된 요청 DTO만 서비스에 전달한다. 요청 DTO에는 작성자 관련 필드가 없다.

```java
CommunityPostResponse createPost(Long authenticatedUserId, String title, String content)
```

Service는 `authenticatedUserId`로 `User`를 조회하고 해당 엔티티를 `CommunityPost.create(author, title, content, now)`에 전달한다. 클라이언트 본문 값이나 별도 경로·쿼리 파라미터로 작성자를 선택하지 않는다.

### D2. 레이어와 트랜잭션 경계

요청 흐름은 `CommunityPostController → CommunityPostService → CommunityPostRepository`로 둔다.

- Controller: 매핑, `@Valid`, 인증 주체 추출, 201 생성.
- Service: 작성자 조회, 현재 시각 생성, 엔티티 생성·저장, `@Transactional`.
- Repository: JPA 저장·조회.

ADR-0002의 도메인 간 repository 직접 참조 금지를 지키기 위해 커뮤니티 Service에서 `UserRepository`를 직접 주입하지 않는다. auth 도메인에 최소 사용자 조회 service인 `UserQueryService`를 두고 `User getUser(Long userId)`로 작성자 엔티티를 제공한다. 해당 ID의 사용자 행이 없으면 `BusinessException(ErrorCode.UNAUTHORIZED)`을 던진다. 범용 Manager/Facade나 커뮤니티 전용 auth 어댑터는 만들지 않는다.

### D3. 시간과 엔티티 생성

- `Clock`을 주입하고 `LocalDateTime.now(clock)`을 사용한다.
- `CommunityPost`는 정적 팩토리 `create(User author, String title, String content, LocalDateTime now)`로만 생성한다.
- 생성 시 `createdAt`과 `updatedAt`을 같은 `now`로 설정한다.
- setter를 두지 않고 API DTO를 엔티티에 전달하지 않는다.

### D4. DB 스키마

머지된 V1·V2는 수정하지 않고 다음 가용 번호의 새 Flyway 마이그레이션으로 생성한다.

```sql
CREATE TABLE community_posts (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    author_id  BIGINT       NOT NULL,
    title      VARCHAR(100)  NOT NULL,
    content    VARCHAR(5000) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_community_posts_author
        FOREIGN KEY (author_id) REFERENCES users (id),
    INDEX idx_community_posts_created_at_id (created_at, id)
);
```

- `title VARCHAR(100)`과 `content VARCHAR(5000)`은 DTO의 `@Size` 최대 길이와 동일하다.
- `(created_at, id)` 인덱스는 후속 최신순 목록의 안정적인 정렬을 지원하되, 목록 API나 QueryDSL 구현은 이번 범위가 아니다.
- 삭제 방식과 `post_comments` 외래키는 이번 이슈에서 만들지 않는다.

### D5. 오류와 보안

- Bean Validation 실패는 기존 `GlobalExceptionHandler`를 통해 400 `VALIDATION_ERROR`.
- 비로그인·잘못된 Bearer는 기존 Security entry point를 통해 401 `UNAUTHORIZED`.
- `/api/community/posts`를 공개 경로에 추가하지 않는다.
- 작성자 조회 실패는 정상적으로 발급된 토큰의 subject와 DB가 불일치한 인증 상태이므로 401 `UNAUTHORIZED`를 반환한다.

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/community/domain/CommunityPost.java`
- `src/main/java/com/finplay/api/community/repository/CommunityPostRepository.java`
- `src/main/java/com/finplay/api/community/service/CommunityPostService.java`
- `src/main/java/com/finplay/api/community/controller/CommunityPostController.java`
- `src/main/java/com/finplay/api/community/dto/request/CommunityPostCreateRequest.java`
- `src/main/java/com/finplay/api/community/dto/response/CommunityPostResponse.java`
- `src/main/resources/db/migration/V{NEXT}__create_community_posts_table.sql`

### Production files to create in the auth service boundary

- `src/main/java/com/finplay/api/auth/service/UserQueryService.java`
  - ADR-0002를 지키며 `User getUser(Long userId)`로 작성자를 제공하고, 미존재 시 401 `UNAUTHORIZED`를 던지는 최소 조회 service.

### Test files to create

- `src/test/java/com/finplay/api/community/repository/CommunityPostRepositoryTest.java`
- `src/test/java/com/finplay/api/community/service/CommunityPostServiceTest.java`
- `src/test/java/com/finplay/api/community/controller/CommunityPostControllerTest.java`
- `src/test/java/com/finplay/api/community/CommunityPostCreateIntegrationTest.java`

### Documentation files to modify after implementation

- `docs/api-routes.md`
- `docs/specs/008-community/issue-23-tasks.md`

### 만들거나 수정하지 않을 파일

- `src/main/resources/db/migration/V1__init.sql`
- `src/main/resources/db/migration/V2__create_auth_account_tables.sql`
- 다른 커뮤니티 API용 production/test 파일
- `docs/specs/008-community/spec.md`

---

## 테스트 계획

### Service 단위

- 인증 사용자 ID, 제목, 본문, 고정 Clock으로 올바른 작성자와 시각의 게시물을 저장한다.
- 요청에 작성자 인수가 없어 인증 사용자 외 작성자를 선택할 수 없다.
- 작성자 조회 실패는 401 `UNAUTHORIZED`이고 게시물을 저장하지 않는다.

### Repository 슬라이스 (`@DataJpaTest` + MySQL Testcontainers)

- 실제 V{NEXT} 마이그레이션으로 `community_posts`가 생성되고 작성자 FK, 제목·본문, 마이크로초 시각을 저장·조회한다.
- 존재하지 않는 작성자 FK 저장을 DB가 거부한다.
- 제목 100자·본문 5,000자 컬럼 제약이 DTO 계약과 일치한다.

### Controller 슬라이스 (`@WebMvcTest`)

- 유효 Access 인증 주체와 요청은 service에 인증 사용자 ID만 전달하고 201, 확정 응답의 모든 필드를 반환한다.
- 제목·본문 각각 누락·빈 문자열·공백·최대 길이 초과는 400 `VALIDATION_ERROR`이고 service를 호출하지 않는다.
- Access Token 없음·만료·변조·Refresh 타입은 401 `UNAUTHORIZED`이고 service를 호출하지 않는다.
- 본문에 작성자처럼 보이는 추가 필드를 넣어도 service에 전달되는 사용자 ID는 Access Token의 subject다.

### 통합 (`@SpringBootTest` + MySQL Testcontainers)

- 사용자 A로 게시물을 만들면 201 응답과 DB 작성자 FK가 A로 일치한다.
- 사용자 A의 요청 본문에 사용자 B의 작성자처럼 보이는 값을 넣어도 DB 작성자는 A다.
- 공백 입력 400과 비로그인 401에서 게시물 행 수가 증가하지 않는다.

---

## 미확정 사항

- 없음. Issue #23의 제목 100자, 본문 5,000자, 201 응답 필드, 사용자 미존재 401, `UserQueryService` 경계를 구현 계약으로 확정했다.

---

## 완료 조건

- [ ] 제목 100자·본문 5,000자와 201 응답 6개 필드가 DTO·DB·테스트·API 문서에서 일치한다.
- [ ] 정상 요청은 인증 사용자를 작성자로 저장하고 201을 반환한다.
- [ ] 클라이언트 입력으로 작성자를 위조할 수 없다.
- [ ] 제목·본문 공백과 기타 검증 실패는 400 `VALIDATION_ERROR`이며 DB 변경이 없다.
- [ ] 비로그인 요청은 401 `UNAUTHORIZED`이며 DB 변경이 없다.
- [ ] 실제 MySQL에서 마이그레이션·FK·저장 흐름을 검증한다.
- [ ] 다른 커뮤니티 API를 구현하지 않는다.
- [ ] 실제 Controller 매핑을 `docs/api-routes.md`에 동기화한다.
- [ ] `.\gradlew.bat build --no-daemon --max-workers=1`을 새로 실행해 통과한다.
