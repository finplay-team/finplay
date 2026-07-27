# Issue #24 커뮤니티 게시물 목록 조회 API 구현 계획

**Goal:** 인증 사용자가 `GET /api/community/posts?page=&size=`를 호출하면 서버가 전체 게시물을 작성시각 최신순으로 페이지네이션하여 반환한다.

**관련 정본:** GitHub Issue #24, PRD `COM-001`·`COM-003`와 §5 1차 API 계약, `spec.md`, ADR-0002·0003, `docs/conventions.md`(QueryDSL 사용 기준), 선행 `issue-23-plan.md`

**선행:** Issue #23(`POST /api/community/posts`)이 `dev`에 병합되어 `CommunityPost` 엔티티·`community_posts` 테이블(V3, `(created_at, id)` 인덱스 포함)·`CommunityPostResponse`가 이미 존재한다. Bearer 인증 기반은 기존 `anyRequest().authenticated()`로 이미 보호된다.

---

## 요구사항 ID와 수용 기준

### 대상 요구사항

- PRD `COM-001 게시물`
  - 목록은 최신순 페이지네이션(page·size)을 제공한다.
- PRD `COM-003 권한`
  - 게시물 조회는 인증 사용자에게만 허용한다(비로그인 401, 공개 조회 없음).
- PRD §5 1차 API 계약
  - `GET /api/community/posts?page=&size=`
- `docs/specs/008-community/spec.md`
  - 비로그인 접근은 401이다.
- GitHub Issue #24 계약
  - 페이지 경계에 중복·누락이 없고 빈 목록은 빈 `content`다.
  - Method·Path가 계약과 일치한다.
  - 공통 오류 형식을 따른다.

### 수용 시나리오

1. Given 게시물이 여러 건 서로 다른 생성시각으로 존재한다.
2. When 유효한 Access Token으로 `GET /api/community/posts?page=0&size=10`을 호출한다.
3. Then 200과 최신순(내림차순)으로 정렬된 게시물 목록, 페이지 메타데이터를 반환한다.
4. Given 마지막 페이지 경계를 넘는 `page` 값을 요청한다.
5. When 같은 API를 호출한다.
6. Then 200과 빈 `content` 배열을 반환하며 오류가 아니다.
7. Given `page`가 음수이거나 `size`가 1 미만·50 초과다.
8. When 같은 API를 호출한다.
9. Then 400 `VALIDATION_ERROR` 공통 오류 형식이다.
10. Given Bearer Access Token이 없거나 만료·변조됐거나 Refresh Token이다.
11. When 같은 API를 호출한다.
12. Then Controller 진입 전 401 `UNAUTHORIZED` 공통 오류 형식이다.

---

## 범위와 제외

### 포함

- `GET /api/community/posts?page=&size=`
- `Authorization: Bearer <accessToken>` 필수(기존 보호 경로 상속, 별도 principal 사용 안 함)
- `page`·`size` 쿼리 파라미터 검증과 기본값
- 작성자를 포함한 최신순(동시각 `id` 보조 정렬) 페이지 조회 — 다중 조인이므로 QueryDSL 사용(컨벤션 기준)
- Repository 슬라이스, Service 단위, Controller 슬라이스, 핵심 통합 테스트
- 구현 뒤 `docs/api-routes.md` 동기화

### 제외

- 게시물 단건조회·수정·삭제(Issue #25 이후)
- 댓글 작성·조회·삭제
- 검색·필터링·정렬 옵션 변경
- 새 Flyway 마이그레이션(V3의 `(created_at, id)` 인덱스로 충분)

---

## API 계약

| 항목 | 내용 |
|---|---|
| Method / Path | `GET /api/community/posts?page=&size=` |
| 인증 | `Authorization: Bearer <유효한 Access JWT>` |
| `page` | 선택, 기본 `0`, `0` 이상. 그 외 400 `VALIDATION_ERROR` |
| `size` | 선택, 기본 `10`, `1`~`50`. 범위 밖은 400 `VALIDATION_ERROR` |
| 성공 | 200 + `CommunityPostListResponse` |
| Access Token 없음·만료·변조·잘못된 타입 | 401 `UNAUTHORIZED` 공통 오류 형식 |

### 성공 응답

`CommunityPostListResponse`:

| 필드 | 형식·의미 |
|---|---|
| `content` | `CommunityPostResponse[]` — 요청한 페이지의 게시물, 작성시각 내림차순 |
| `page` | 요청한(0-base) 페이지 번호 |
| `size` | 요청한 페이지 크기 |
| `totalElements` | 전체 게시물 수 |
| `totalPages` | 전체 페이지 수 |
| `hasNext` | 다음 페이지 존재 여부 |

`content`의 각 항목은 Issue #23의 `CommunityPostResponse`(`postId`, `authorNickname`, `title`, `content`, `createdAt`, `updatedAt`)를 그대로 재사용한다 — 필드가 완전히 동일해 별도 `~ListItemResponse`를 새로 만들면 매핑 로직만 중복된다.

---

## 설계 결정

### D1. `page`/`size` 기본값·상한은 이번 이슈에서 확정한다

PRD·Issue #24 모두 정확한 기본값·상한을 명시하지 않는다. 다음으로 고정한다.

- `page` 기본 `0`, 최소 `0`.
- `size` 기본 `10`, 최소 `1`, 최대 `50`(과도한 조회로 인한 부하 방지).
- 검증은 Controller에서 `page`/`size`를 확인해 범위를 벗어나면 `BusinessException(ErrorCode.VALIDATION_ERROR)`를 직접 던진다. `@RequestParam` 메서드 파라미터에 `@Min`/`@Max` + `@Validated`를 붙이는 방식은 Spring 버전에 따라 `ConstraintViolationException`이 아니라 `HandlerMethodValidationException`을 던질 수 있어(`GlobalExceptionHandler`가 후자를 처리하지 않음) 이번 이슈에서는 채택하지 않는다. `BusinessException` 경로는 Issue #23의 `UserQueryService` 실패 처리와 동일하게 이미 검증된 매핑이다.

### D2. 정렬은 `(created_at, id)` 내림차순으로 고정한다

동시각에 생성된 게시물이 있어도 페이지 경계에서 중복·누락이 없도록 `created_at DESC, id DESC`로 2차 정렬한다. V3 마이그레이션의 `idx_community_posts_created_at_id` 인덱스가 이 정렬을 지원한다(내림차순 스캔이라도 인덱스를 활용할 수 있다).

### D3. 목록 조회는 QueryDSL로 작성자를 fetch join한다

`docs/conventions.md`의 "다중 조인 목록 조회에 QueryDSL을 쓴다" 기준에 해당한다. `CommunityPost.author`는 `LAZY` 조회라 `Page<CommunityPost>`를 단순 `findAll`로 가져오면 응답 매핑 시 항목마다 작성자 조회가 추가로 발생한다(N+1). `CommunityPostRepository`에 `CommunityPostRepositoryCustom`을 추가하고 `CommunityPostRepositoryImpl`에서 `JPAQueryFactory`로 `author`를 `fetchJoin`하여 목록 쿼리 한 번, count 쿼리 한 번으로 페이지를 구성한다.

```java
Page<CommunityPost> findPostsOrderByCreatedAtDesc(Pageable pageable)
```

Repository가 정렬·조인 세부사항을 갖고, Service는 `Pageable`만 만들어 전달한다(레이어 규칙 준수).

### D4. Controller는 인증 주체를 파라미터로 받지 않는다

목록 조회는 작성자 필터링이나 소유권 판단이 없는 전체 피드다. 인증 여부는 `SecurityConfig`의 `anyRequest().authenticated()`가 Controller 진입 전에 강제하므로, Controller가 `@AuthenticationPrincipal`을 선언하지 않아도 비로그인 요청은 401로 차단된다. 불필요한 매개변수를 추가하지 않는다(YAGNI).

### D5. 응답 DTO는 `Page<CommunityPost>`에서 직접 변환한다

`CommunityPostListResponse.from(Page<CommunityPost> page)` 정적 팩토리가 `page.getContent()`를 `CommunityPostResponse::from`으로 매핑하고 페이지 메타데이터를 함께 구성한다. Service는 Repository가 반환한 `Page`를 그대로 DTO 팩토리에 전달한다.

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/community/repository/CommunityPostRepositoryCustom.java`
- `src/main/java/com/finplay/api/community/repository/CommunityPostRepositoryImpl.java`
- `src/main/java/com/finplay/api/community/dto/response/CommunityPostListResponse.java`

### Production files to modify

- `src/main/java/com/finplay/api/community/repository/CommunityPostRepository.java` (`CommunityPostRepositoryCustom` 상속)
- `src/main/java/com/finplay/api/community/service/CommunityPostService.java` (`getPosts(int page, int size)` 추가)
- `src/main/java/com/finplay/api/community/controller/CommunityPostController.java` (`GET` 매핑 추가, `@Validated`)

### Test files to create

- `src/test/java/com/finplay/api/community/repository/CommunityPostRepositoryTest.java`에 목록 조회 테스트 추가(기존 파일 확장)
- `src/test/java/com/finplay/api/community/service/CommunityPostServiceTest.java`에 목록 조회 단위 테스트 추가(기존 파일 확장)
- `src/test/java/com/finplay/api/community/controller/CommunityPostControllerTest.java`에 목록 조회 슬라이스 테스트 추가(기존 파일 확장)
- `src/test/java/com/finplay/api/community/CommunityPostListIntegrationTest.java` (신규 — 핵심 시나리오)

### Documentation files to modify after implementation

- `docs/api-routes.md`

### 만들거나 수정하지 않을 파일

- `src/main/resources/db/migration/*` (신규 마이그레이션 불필요 — V3 인덱스로 충분)
- `docs/specs/008-community/spec.md`
- 다른 커뮤니티 API(단건조회·수정·삭제·댓글)용 production/test 파일

---

## 테스트 계획

### Service 단위 (`CommunityPostServiceTest` 확장)

- `page`·`size`로 만든 `Pageable`을 그대로 repository에 전달하고, repository가 반환한 `Page`를 `CommunityPostListResponse`로 변환해 반환한다.
- repository가 빈 `Page`를 반환하면 빈 `content`와 `totalElements=0`을 반환한다.

### Repository 슬라이스 (`CommunityPostRepositoryTest` 확장, `@DataJpaTest` + MySQL Testcontainers)

- 서로 다른 `createdAt`으로 저장된 게시물이 최신순으로 조회된다.
- 같은 `createdAt`의 게시물은 `id` 내림차순으로 안정적으로 정렬된다(경계 중복·누락 방지 검증).
- 페이지 크기만큼 자르고 `totalElements`·`totalPages`가 실제 저장 건수와 일치한다.
- 저장된 게시물이 없으면 빈 `content`를 반환한다.
- 작성자가 fetch join되어 추가 쿼리 없이 닉네임에 접근할 수 있다(Hibernate 통계 또는 지연 로딩 예외 부재로 검증).

### Controller 슬라이스 (`CommunityPostControllerTest` 확장, `@WebMvcTest`)

- 유효 인증과 기본 파라미터(`page`·`size` 생략)는 200과 service의 기본값(`0`, `10`) 호출을 검증한다.
- `page`·`size`를 명시하면 그 값 그대로 service에 전달된다.
- `page<0`, `size<1`, `size>50`은 400 `VALIDATION_ERROR`이고 service를 호출하지 않는다.
- Access Token 없음·만료·변조·Refresh 타입은 401 `UNAUTHORIZED`이고 service를 호출하지 않는다.

### 통합 (`CommunityPostListIntegrationTest`, `@SpringBootTest` + MySQL Testcontainers)

- 서로 다른 작성자로 여러 게시물을 만든 뒤 목록을 조회하면 최신순으로 정확히 반환되고 페이지 경계에 중복·누락이 없다(1페이지+2페이지 합쳐서 전체 집합과 비교).
- 게시물이 없는 상태에서 조회하면 200과 빈 `content`를 반환한다.
- 비로그인 요청은 401이다.

---

## 미확정 사항

- 없음. `page`/`size` 기본값·상한(D1), 정렬 규칙(D2), N+1 방지를 위한 QueryDSL 채택(D3)을 구현 계약으로 확정했다.

---

## 완료 조건

- [ ] `GET /api/community/posts?page=&size=` 기본값(`0`/`10`)과 상한(`size<=50`)이 Controller·테스트·API 문서에서 일치한다.
- [ ] 최신순 정렬이 동시각 케이스에서도 페이지 경계 중복·누락 없이 안정적이다.
- [ ] 빈 목록은 오류가 아니라 200 + 빈 `content`다.
- [ ] `page`/`size` 검증 실패는 400 `VALIDATION_ERROR`다.
- [ ] 비로그인 요청은 401 `UNAUTHORIZED`다.
- [ ] 작성자 조회가 fetch join으로 N+1 없이 처리된다.
- [ ] 실제 Controller 매핑을 `docs/api-routes.md`에 동기화한다.
- [ ] `./gradlew build`를 새로 실행해 통과한다.
