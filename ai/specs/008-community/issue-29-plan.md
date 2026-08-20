# Issue #29 커뮤니티 게시물 댓글 목록 조회 API 구현 계획

**Goal:** 인증 사용자가 `GET /api/community/posts/{postId}/comments`를 호출하면 대상 게시물의 평면 댓글만 오래된 순으로 조회하고, 댓글이 없으면 200과 빈 배열을 반환한다.

**관련 정본:** GitHub Issue #29, PRD `COM-002`·`COM-003`와 §5 공통 오류, `spec.md`, Issue #28 댓글 생성 계약, ADR-0002·0003·0004, `docs/conventions.md`

**선행:** Issue #28 구현으로 `post_comments`, `PostComment`·Repository·Service·Controller와 `PostCommentResponse`가 존재한다. `/api/community/**`는 기존 `anyRequest().authenticated()` 정책으로 보호된다.

---

## 요구사항 ID와 수용 기준

### 대상 요구사항

- PRD `COM-002 댓글`
  - 게시물 상세에서 댓글 목록을 오래된 순으로 조회한다.
- PRD `COM-003 권한`
  - 댓글 조회는 인증 사용자에게 허용한다.
- PRD §5 공통 오류
  - 인증 없음·만료는 401 `UNAUTHORIZED`.
  - 대상 게시물 없음은 404 `NOT_FOUND`.
- GitHub Issue #29 계약
  - `GET /api/community/posts/{postId}/comments`.
  - 대상 게시물의 댓글만 오래된 순으로 반환한다.
  - 댓글이 없으면 200과 빈 목록을 반환한다.
  - 존재하지 않는 게시물과 비로그인 요청은 공통 오류 계약을 따른다.

### 수용 시나리오

1. Given 게시물 A와 B에 서로 다른 작성자의 댓글이 섞여 있고, 게시물 A의 댓글 생성시각 일부가 같다.
2. When 인증 사용자가 게시물 A의 댓글 목록을 조회한다.
3. Then 200과 게시물 A의 댓글만 `createdAt ASC, commentId ASC` 순서로 반환한다.
4. And 각 항목은 `commentId`, `authorNickname`, `content`, `createdAt`만 포함한다.
5. Given 존재하지만 댓글이 없는 게시물이다.
6. When 인증 사용자가 댓글 목록을 조회한다.
7. Then 200과 `[]`를 반환한다.
8. Given `postId`에 해당하는 게시물이 없다.
9. When 인증 사용자가 호출한다.
10. Then 404 `NOT_FOUND` 공통 오류 형식을 반환한다.
11. Given Access Token이 없거나 만료·변조됐거나 Refresh Token이다.
12. When 같은 API를 호출한다.
13. Then Controller 진입 전 401 `UNAUTHORIZED` 공통 오류 형식을 반환한다.

---

## 범위와 제외

### 포함

- `GET /api/community/posts/{postId}/comments`
- `Authorization: Bearer <accessToken>` 필수
- 대상 게시물 존재 확인
- 대상 게시물 댓글 격리 조회
- 생성시각 오름차순, 같은 생성시각은 댓글 ID 오름차순 안정 정렬
- 작성자 닉네임을 포함한 댓글 응답 목록
- 빈 목록 200, 게시물 미존재 404, 인증 실패 401
- Service 단위, Repository/MySQL 슬라이스, Controller 슬라이스, 통합 테스트
- 구현 뒤 실제 Controller 매핑 기준 `ai/api-routes.md` 동기화

### 제외

- 댓글 페이지네이션·커서·개수 제한
- 댓글 작성·삭제 계약 변경
- 댓글 수정·대댓글
- 게시물 상세 응답에 댓글을 내장하는 변경
- 다른 게시물·댓글 API
- 스키마·엔티티·Flyway 마이그레이션 변경
- 작성자 내부 ID, 게시물 ID, 수정시각 노출

---

## API 계약

| 항목 | 내용 |
|---|---|
| Method / Path | `GET /api/community/posts/{postId}/comments` |
| 인증 | `Authorization: Bearer <유효한 Access JWT>` |
| 요청 | 경로 변수 `postId`; query/body 없음 |
| 성공 | 200 + `PostCommentResponse` JSON 배열 |
| 정렬 | `createdAt ASC`, 동률은 `commentId ASC` |
| 댓글 없음 | 200 + `[]` |
| 게시물 미존재 | 404 `NOT_FOUND` 공통 오류 형식 |
| Access Token 없음·만료·변조·잘못된 타입 | 401 `UNAUTHORIZED` 공통 오류 형식 |

### 성공 응답

```json
[
  {
    "commentId": 1,
    "authorNickname": "first",
    "content": "첫 댓글",
    "createdAt": "2026-07-27T12:00:00"
  },
  {
    "commentId": 2,
    "authorNickname": "second",
    "content": "둘째 댓글",
    "createdAt": "2026-07-27T12:01:00"
  }
]
```

각 항목은 Issue #28에서 확정한 `PostCommentResponse`를 재사용한다.

| 필드 | 형식·의미 |
|---|---|
| `commentId` | 댓글 ID |
| `authorNickname` | 댓글 작성자의 현재 닉네임 |
| `content` | 댓글 본문 |
| `createdAt` | ISO-8601 생성시각 |

- 별도 목록 wrapper나 페이지 메타데이터를 추가하지 않는다. PRD와 Issue #29는 페이지네이션을 요구하지 않고 빈 “목록” 반환을 요구하므로 JSON 배열을 사용한다.
- 경로 변수 `postId`의 양수 검증이나 잘못된 숫자 형식에 대한 새 오류 정책은 PRD와 Issue #29에 없으므로 만들지 않는다.

---

## 설계 결정

### D1. 게시물 존재와 빈 댓글 목록을 명시적으로 구분한다

`PostCommentService.getComments(postId)`는 먼저 `CommunityPostRepository.existsById(postId)`로 게시물 존재를 확인한다. 없으면 `BusinessException(ErrorCode.NOT_FOUND)`를 던지고, 존재하면 댓글 목록 쿼리를 실행한다.

- 게시물 존재 + 댓글 0개: 200 `[]`.
- 게시물 미존재: 404 `NOT_FOUND`.
- 조회 전용 유스케이스이므로 `@Transactional(readOnly = true)`를 사용한다.
- 인증은 Security filter가 담당한다. 목록은 인증 사용자별로 달라지지 않으므로 Service에 사용자 ID를 전달하지 않는다.

### D2. 정렬은 생성시각과 ID를 모두 사용한다

Repository 쿼리는 `post.id = :postId`로 격리하고 `createdAt ASC, id ASC`로 정렬한다.

- `createdAt ASC`는 PRD의 “오래된 순”을 구현한다.
- 같은 `created_at`일 때 `id ASC`를 tie-breaker로 사용해 호출마다 순서가 바뀌지 않게 한다.
- V4의 `(post_id, created_at, id)` 인덱스가 필터와 정렬 순서를 그대로 지원하므로 새 마이그레이션은 필요 없다.

### D3. 작성자를 fetch join해 N+1을 방지한다

`PostCommentResponse.from`은 lazy 연관인 `comment.author.nickname`을 읽는다. Repository는 대상 댓글과 작성자를 한 번에 가져오는 명시적 JPQL `join fetch comment.author` 쿼리를 사용한다.

요청당 예상 DB 접근은 댓글 수와 무관하게 두 번이다.

1. 게시물 존재 확인 1회.
2. 대상 댓글과 작성자 목록 조회 1회.

게시물 연관은 응답 변환에 사용하지 않으며 `postId` 조건에만 쓰므로 fetch join하지 않는다. 댓글별 작성자 추가 조회가 발생하지 않아 N+1을 방지한다.

### D4. 기존 Controller와 응답 DTO를 확장·재사용한다

- `PostCommentController`에 `@GetMapping`을 추가한다.
- `PostCommentService`에 조회 전용 메서드를 추가한다.
- `PostCommentRepository`에 게시물 격리·정렬·작성자 fetch join 쿼리를 추가한다.
- 성공 응답은 기존 `PostCommentResponse` 목록을 반환한다.
- 댓글 생성 흐름과 공개 Security 화이트리스트는 변경하지 않는다.

### D5. 오류와 보안

- 게시물 미존재는 기존 `BusinessException(ErrorCode.NOT_FOUND)`로 404.
- 비로그인·잘못된 Bearer는 기존 Security entry point를 통해 401 `UNAUTHORIZED`.
- 댓글 조회 경로를 공개 화이트리스트에 추가하지 않는다.
- 조회 API는 DB를 변경하지 않는다.

---

## 예상 변경 파일

### Production

- `src/main/java/com/finplay/api/community/controller/PostCommentController.java`
  - GET 매핑과 200 목록 응답 추가.
- `src/main/java/com/finplay/api/community/service/PostCommentService.java`
  - 게시물 존재 확인, read-only 목록 조회와 DTO 변환 추가.
- `src/main/java/com/finplay/api/community/repository/PostCommentRepository.java`
  - 대상 게시물 필터, `createdAt/id` 오름차순, 작성자 fetch join 쿼리 추가.

### Test

- `src/test/java/com/finplay/api/community/controller/PostCommentControllerTest.java`
  - 정상·빈 목록·404·401 응답과 Service 위임 검증 추가.
- `src/test/java/com/finplay/api/community/service/PostCommentServiceTest.java`
  - 격리된 정렬 결과 변환, 빈 목록, 게시물 미존재 시 쿼리 미실행 검증 추가.
- `src/test/java/com/finplay/api/community/repository/PostCommentRepositoryTest.java`
  - 실제 MySQL의 게시물 격리, 생성시각/ID 안정 정렬, 작성자 조회 검증 추가.
- `src/test/java/com/finplay/api/community/PostCommentListIntegrationTest.java`
  - 실제 인증 필터·MySQL을 연결한 격리/정렬/빈 목록/404/401 통합 시나리오 추가.

### Documentation

- `ai/api-routes.md`
  - 구현된 GET 매핑과 응답·오류 계약 동기화.
- `ai/specs/008-community/issue-29-tasks.md`
  - 구현 루프 완료 상태 기록.

### 수정하지 않을 파일

- `src/main/resources/db/migration/V4__create_post_comments_table.sql` 및 다른 머지된 마이그레이션
- `src/main/java/com/finplay/api/community/domain/PostComment.java`
- `src/main/java/com/finplay/api/community/dto/response/PostCommentResponse.java`
- `ai/specs/008-community/spec.md`
- 다른 커뮤니티 API용 production/test 파일

---

## 테스트 계획

### Service 단위

- 존재하는 게시물은 Repository 결과를 순서대로 `PostCommentResponse` 목록으로 변환한다.
- 댓글이 없으면 빈 목록을 반환한다.
- 게시물 미존재는 `NOT_FOUND`이고 댓글 목록 쿼리를 실행하지 않는다.

### Repository 슬라이스 (`@DataJpaTest` + MySQL Testcontainers)

- 게시물 A 조회에 게시물 B의 댓글이 섞이지 않는다.
- 서로 다른 생성시각은 `createdAt ASC`, 같은 생성시각은 `id ASC`로 반환한다.
- 작성자 닉네임 접근까지 persistence context를 명시적으로 비운 뒤 검증해 fetch join 계약을 확인한다.
- 기존 V4 `(post_id, created_at, id)` 인덱스와 쿼리 계약이 일치한다.

### Controller 슬라이스 (`@WebMvcTest`)

- 인증 사용자의 요청은 Service에 `postId`만 전달하고 200과 정확한 배열·필드 순서를 반환한다.
- 빈 Service 결과는 200 `[]`.
- Service의 게시물 미존재 예외는 404 `NOT_FOUND`.
- Access Token 없음·만료·변조·Refresh 타입은 401이고 Service를 호출하지 않는다.

### 통합 (`@SpringBootTest` + MySQL Testcontainers)

- 두 게시물과 여러 작성자의 댓글을 저장한 뒤 대상 게시물 댓글만 `createdAt ASC, commentId ASC`로 반환한다.
- 존재하는 무댓글 게시물은 200 `[]`.
- 존재하지 않는 게시물은 404 `NOT_FOUND`.
- 비로그인 요청은 401 `UNAUTHORIZED`.
- 모든 조회 시 DB 행 수와 내용이 바뀌지 않는다.

---

## 미확정 사항

- 없음. 기존 PRD·Issue #28 응답 계약과 Issue #29 수용 기준으로 JSON 배열, 네 응답 필드, `createdAt ASC, commentId ASC`, 빈 배열 200, 404·401 계약을 확정할 수 있다. ADR 충돌과 스키마 변경은 없다.

---

## 완료 조건

- [ ] GET은 대상 게시물의 댓글만 반환하고 다른 게시물 댓글을 노출하지 않는다.
- [ ] 목록은 `createdAt ASC, commentId ASC`로 안정 정렬된다.
- [ ] 각 항목은 `commentId`, `authorNickname`, `content`, `createdAt`만 포함한다.
- [ ] 작성자 fetch join으로 댓글 수에 따른 N+1 쿼리가 발생하지 않는다.
- [ ] 존재하는 무댓글 게시물은 200 `[]`, 게시물 미존재는 404 `NOT_FOUND`다.
- [ ] 비로그인·유효하지 않은 Access Token은 401 `UNAUTHORIZED`다.
- [ ] 댓글 페이지네이션·수정·대댓글과 다른 API를 구현하지 않는다.
- [ ] 실제 Controller 매핑을 `ai/api-routes.md`에 동기화한다.
- [ ] `spotlessApply`, 대상 테스트, `.\gradlew.bat build --no-daemon --max-workers=1`, `git diff --check`를 순차 실행해 통과한다.
