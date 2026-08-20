# Plan: COM-004 게시물 종목 기준 분류 (이슈 #246)

## 관련 문서

- Spec: `./spec.md` "COM-004 종목 기준 분류" 절, "Decision Gate"(이 그룹 해당 없음), "완료 조건 COM-004"
- 선행 구현: `008-community`(`CommunityPost`·`CommunityPostService`·`CommunityPostController`·`CommunityPostRepository`, COM-001~003), `013-instrument-master`류(`Instrument`·`InstrumentService`·`InstrumentRepository`, MKT-001), `021-watchlist`(`WatchlistService`가 `InstrumentService.getInstrumentEntity`를 거쳐 cross-domain 참조하는 선례)
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(레이어드, 도메인 간 참조는 service 레이어만 — `community`가 `market`의 `InstrumentRepository`를 직접 주입하지 않는다), [ADR-0004](../../adr/0004-flyway-migrations.md)(신규 컬럼은 Flyway로만)
- PRD 근거: `ai/prd.md` COM-004(신설 2차 MVP, 상세는 이 spec이 정본 — spec.md 머리말 참고)

## 기존 코드 현황 (구현 전 확인한 사실)

- `CommunityPost`(`src/main/java/com/finplay/api/community/domain/CommunityPost.java`)는 `author`·`title`·`content`·`createdAt`·`updatedAt`만 가진다. 종목 연관이 없다.
- `CommunityPostService.createPost`/`updatePost`는 각각 `title`·`content` 두 값만 받는다. `CommunityPostController`도 동일하게 두 값만 전달한다.
- `CommunityPostRepository.findById`는 `@EntityGraph(attributePaths = "author")`로 author를 fetch join한다. 목록 조회는 `CommunityPostRepositoryImpl.findPostsOrderByCreatedAtDesc(Pageable)`(QueryDSL)가 `author`만 fetch join하고 필터 조건이 없다.
- `Instrument`(`src/main/java/com/finplay/api/market/domain/Instrument.java`)는 `market`·`symbol`·`name`·`tradable`(boolean) 필드를 가진다. "활성 심볼" = `tradable == true`.
- 다른 도메인이 `Instrument`를 참조하는 선례는 `InstrumentService`(`src/main/java/com/finplay/api/market/service/InstrumentService.java`)를 통해서만 이루어진다(ADR-0002) — `WatchlistService`가 `instrumentService.getInstrumentEntity(instrumentId)`를 호출하는 패턴. 단, 이 메서드는 없으면 `ErrorCode.NOT_FOUND`(404)를 던진다.
- **COM-004는 "존재하지 않거나 비활성 심볼 태그 시 400 `VALIDATION_ERROR`"를 요구한다** — 기존 `getInstrumentEntity`(404 `NOT_FOUND`)를 그대로 쓸 수 없다. `InstrumentService`에 이 그룹 전용 조회 메서드를 신설한다(아래 "패키지·클래스 설계").
- `ErrorCode.VALIDATION_ERROR`(`HttpStatus.BAD_REQUEST`)는 이미 존재한다 — 신규 `ErrorCode` 추가 없음.

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | /api/community/posts | `CommunityPostCreateRequest`(instrumentId 필드 추가) | `CommunityPostResponse`(태그 필드 추가) | 게시물 작성 시 종목을 선택적으로 하나 태그 |
| PATCH | /api/community/posts/{postId} | `CommunityPostUpdateRequest`(instrumentId 필드 추가) | `CommunityPostResponse` | title·content는 매 요청 전체 교체(필수 필드). instrumentId는 JSON Merge Patch 관례 — 키 부재는 보존, 명시 `null`은 해제(**2026-08-10 Issue #276로 아래 원 결정을 대체**) |
| GET | /api/community/posts?page=&size=&instrumentId= | - | `CommunityPostListResponse`(각 항목에 태그 필드 추가) | `instrumentId` 지정 시 그 종목이 태그된 게시물만, 미지정 시 기존 COM-001과 동일 |
| GET | /api/community/posts/{postId} | - | `CommunityPostResponse` | 응답에 태그 필드 추가 |

- **원 결정(아래, 2026-08 초 작성).** `PATCH`가 title·content처럼 매번 전체를 교체하는 기존 관례를 그대로 따른다 — `instrumentId`만 선택적으로 유지하는 부분 패치 API를 새로 만들지 않는다. 즉 "태그를 그대로 두고 제목만 바꾸고 싶다"면 클라이언트가 기존 `instrumentId`를 그대로 다시 보내야 한다(기존 title·content도 동일한 제약).
- **2026-08-10 정정 (Issue #276, A안 채택).** 위 결정이 `conventions.md:117`("PATCH는 부분 수정")과 충돌하고, 저장소의 다른 nullable-필드 PATCH(`PATCH /api/orders/{orderId}`, 015 LMT-005)는 이미 부분 갱신(생략 시 유지)으로 동작해 "기존 관례"라는 근거 자체가 틀렸다는 지적을 받아들여 대체한다. `instrumentId`만 JSON Merge Patch 관례로 바꾼다 — 요청 본문에 키가 없으면 기존 태그를 보존하고, 키를 넣고 값을 `null`로 명시해야만 해제한다. `title`·`content`는 원 결정 그대로 유지한다(둘 다 필수 필드라 생략 시 400이므로 이 구분이 적용되지 않는다). 구현은 `CommunityPostUpdateRequestDeserializer`(레코드 전용 커스텀 역직렬화기, `JsonNode.has("instrumentId")`로 키 존재 여부를 직접 판별)로 처리한다 — Jackson 3(`tools.jackson`)에는 `org.openapitools:jackson-databind-nullable`(Jackson 2 대상) 같은 기성 라이브러리가 없어 새 의존성 없이 자체 구현했다.
- `GET /api/community/posts?instrumentId=`에 존재하지 않는 `instrumentId`를 넘기면 검증 오류를 던지지 않고 단순히 빈 목록을 반환한다(필터 파라미터는 조회 조건일 뿐, 생성·수정 시의 "태그 유효성 검증"과는 성격이 다르다 — 이 필터에 한해 404/400을 만들지 않는다).

## 입력 명세

### `CommunityPostCreateRequest` / `CommunityPostUpdateRequest` (동일 필드 추가)

| 필드 | 필수 | 검증 |
|---|---|---|
| title | 필수(기존) | 변경 없음 |
| content | 필수(기존) | 변경 없음 |
| instrumentId | 선택 | `null` 허용(미태그). 값이 있으면 서비스 계층에서 `InstrumentService.getTradableInstrumentEntity(instrumentId)`로 조회 — 존재하지 않거나 `tradable=false`면 400 `VALIDATION_ERROR`("존재하지 않거나 비활성인 종목은 태그할 수 없습니다."). `@NotNull` 등 Bean Validation 애너테이션은 붙이지 않는다(선택 필드이므로 형식 검증 없음, 존재 검증만 서비스에서 수행).

### `GET /api/community/posts` 쿼리 파라미터

| 필드 | 필수 | 검증 |
|---|---|---|
| instrumentId | 선택 | 지정 시 `Long`으로 파싱 가능해야 함(스프링 기본 타입 변환 실패 시 400은 기존 프레임워크 동작 그대로, 별도 커스텀 검증 없음). 존재하지 않는 값이어도 오류 아님 — 빈 목록.

## 데이터 모델

### `V24__add_instrument_tag_to_community_posts.sql`

```sql
-- 커뮤니티 게시물에 종목 태그(선택, 단일)를 추가한다.

ALTER TABLE community_posts
    ADD COLUMN instrument_id BIGINT NULL AFTER author_id,
    ADD CONSTRAINT fk_community_posts_instrument
        FOREIGN KEY (instrument_id) REFERENCES instruments (id),
    ADD INDEX idx_community_posts_instrument_created (instrument_id, created_at, id);
```

- `instrument_id`는 `NULL` 허용 — 기존(008-community 시점) 게시물은 마이그레이션으로 값을 채우지 않는다(spec.md "비즈니스 규칙" 3번, 하위 호환).
- 인덱스는 `GET ?instrumentId=`가 종목별 최신순 페이지네이션을 인덱스로 처리하도록 `(instrument_id, created_at, id)` 복합 인덱스를 둔다 — `idx_community_posts_created_at_id`(기존, 전체 목록용)와 역할이 다르므로 기존 인덱스는 그대로 둔다.
- CHECK 제약은 두지 않는다(코드베이스 전례 없음 — 앱 계층에서 `tradable` 불변식 검증, 015-limit-order plan.md와 동일 판단).

### 엔티티 변경

**`CommunityPost`**: `instrument`(nullable `ManyToOne`, `FetchType.LAZY`) 필드 추가.

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "instrument_id")
private Instrument instrument;
```

- 생성자·팩토리·수정 메서드에 `instrument` 파라미터를 추가한다(기존 `create`/`update` 시그니처를 그대로 바꾼다 — 이 spec 그룹이 착수 시점 기준 유일한 진행 중 변경이라 호출부가 `CommunityPostService` 한 곳뿐이므로 오버로드 없이 시그니처 변경으로 처리한다).

```java
public static CommunityPost create(
    User author, String title, String content, Instrument instrument, LocalDateTime now) { ... }

public void update(String title, String content, Instrument instrument, LocalDateTime now) {
    this.title = title;
    this.content = content;
    this.instrument = instrument;
    this.updatedAt = now;
}
```

## 패키지·클래스 설계

| 클래스 | 패키지 | 변경 |
|---|---|---|
| `InstrumentService` | `market.service` | 신규 메서드 `getTradableInstrumentEntity(Long instrumentId)` 추가(아래) |
| `CommunityPost` | `community.domain` | `instrument` 필드·팩토리·`update` 시그니처 변경(위) |
| `CommunityPostCreateRequest`/`CommunityPostUpdateRequest` | `community.dto.request` | `instrumentId`(nullable `Long`) 필드 추가 |
| `CommunityPostResponse` | `community.dto.response` | `instrumentId`·`instrumentSymbol`·`instrumentName`(모두 nullable) 필드 추가, `from(CommunityPost)`에서 `post.getInstrument()`가 `null`이면 세 필드 모두 `null` |
| `CommunityPostListResponse` | `community.dto.response` | 변경 없음(내부적으로 `CommunityPostResponse::from` 재사용이라 자동으로 반영) |
| `CommunityPostRepositoryCustom`/`CommunityPostRepositoryImpl` | `community.repository` | `findPostsOrderByCreatedAtDesc(Pageable, Long instrumentId)`로 시그니처 변경(nullable 파라미터) — `instrumentId != null`이면 `post.instrument.id.eq(instrumentId)` 조건 추가, QueryDSL에서 `instrument`를 `leftJoin(...).fetchJoin()`으로 함께 로딩(N+1 방지, nullable이라 inner join 불가) |
| `CommunityPostRepository.findById` | `community.repository` | `@EntityGraph(attributePaths = {"author", "instrument"})`로 확장 |
| `CommunityPostService` | `community.service` | `createPost`/`updatePost`에 `instrumentId` 파라미터 추가, `instrumentId != null`이면 `instrumentService.getTradableInstrumentEntity(instrumentId)` 호출 후 결과를 `CommunityPost.create`/`update`에 전달, `null`이면 `instrument=null` 그대로 전달. `getPosts`에 `instrumentId` 파라미터 추가해 리포지토리에 전달 |
| `CommunityPostController` | `community.controller` | `createPost`/`updatePost`가 `request.instrumentId()`를 서비스에 전달, `getPosts`에 `@RequestParam(required = false) Long instrumentId` 추가 |

### `InstrumentService.getTradableInstrumentEntity` (신규)

```java
// 커뮤니티 게시물 종목 태그(COM-004)처럼 "존재하지 않거나 비활성"을 400 VALIDATION_ERROR로
// 다뤄야 하는 도메인을 위한 조회. getInstrumentEntity(404 NOT_FOUND)와 별도로 둔다 — 두 그룹의
// 오류 계약이 다르기 때문(예: 지정가 주문은 종목 없음을 404로 다룬다).
@Transactional(readOnly = true)
public Instrument getTradableInstrumentEntity(Long instrumentId) {
    Instrument instrument = instrumentRepository.findById(instrumentId)
        .orElseThrow(() -> new BusinessException(
            ErrorCode.VALIDATION_ERROR, "존재하지 않거나 비활성인 종목은 태그할 수 없습니다."));
    if (!instrument.isTradable()) {
        throw new BusinessException(
            ErrorCode.VALIDATION_ERROR, "존재하지 않거나 비활성인 종목은 태그할 수 없습니다.");
    }
    return instrument;
}
```

- `community` 서비스는 이 메서드만 호출한다 — `InstrumentRepository`를 직접 주입하지 않는다(ADR-0002).

## 테스트 계획

- 단위: `CommunityPostServiceTest`(Mockito) — 태그 있는 생성/수정, 태그 없는 생성/수정(하위 호환), 존재하지 않는 종목 태그 시 `InstrumentService`가 던진 `VALIDATION_ERROR`가 그대로 전파되는지, 비활성 종목 태그 시 동일. `CommunityPost` 엔티티 자체 단위 테스트(선택)로 `create`/`update`가 `instrument`를 올바르게 반영하는지.
- 슬라이스: `CommunityPostRepositoryTest`(`@DataJpaTest`, Testcontainers) — `findPostsOrderByCreatedAtDesc(pageable, instrumentId)`가 `instrumentId` 지정 시 해당 종목 게시물만 반환하고 `null` 지정 시 전체 반환하는지, `instrument`가 fetch join되어 N+1이 없는지(쿼리 카운트 검증 또는 지연 로딩 예외 미발생 확인). `CommunityPostControllerTest`(`@WebMvcTest`) — 요청 JSON에 `instrumentId` 포함/생략 시 서비스 호출 인자, 응답 JSON에 태그 필드 계약, `instrumentId` 쿼리 파라미터 전달.
- 통합: `@SpringBootTest` + Testcontainers(ADR-0003) — spec.md "완료 조건 COM-004" 4개 시나리오를 그대로 구현:
  1. 종목 태그 게시물 작성 → 단건 조회 시 `instrumentId`·`instrumentSymbol`·`instrumentName` 포함.
  2. `GET /api/community/posts?instrumentId=`로 해당 종목 게시물만 반환(다른 종목·미태그 게시물은 제외).
  3. 존재하지 않는/비활성(`tradable=false`) 종목 태그 시도 시 400 `VALIDATION_ERROR`(둘 다 개별 케이스).
  4. 미태그 게시물 생성·조회·목록(기존 COM-001 시나리오) 회귀 — 모든 태그 필드가 `null`.

## COM-005 대댓글 (이슈 #247)

## 관련 문서

- Spec: `./spec.md` "COM-005 대댓글" 절, "Decision Gate"(부모 댓글 삭제 시 자식 처리 — 이 plan에서 확정), "완료 조건 COM-005"
- 선행 구현: `008-community`(`PostComment`·`PostCommentService`·`PostCommentController`·`CommentController`·`PostCommentRepository`, COM-002/003 평면 댓글), COM-004(같은 spec 그룹, `V24` 마이그레이션까지 진행됨 — 다음 버전은 `V25`)
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(레이어드, controller가 비즈니스 판단을 하지 않는다), [ADR-0004](../../adr/0004-flyway-migrations.md)(신규 컬럼·FK는 Flyway로만)
- PRD 근거: `ai/prd.md` COM-005(신설 2차 MVP, 상세는 이 spec이 정본)

## 기존 코드 현황 (구현 전 확인한 사실)

- `PostComment`(`src/main/java/com/finplay/api/community/domain/PostComment.java`)는 `post`·`author`·`content`·`createdAt`만 가진다. 계층 구조가 없다 — 완전 평면.
- `PostCommentService`(`src/main/java/com/finplay/api/community/service/PostCommentService.java`)는 `createComment(postId, authenticatedUserId, content)`, `deleteComment(authenticatedUserId, commentId)`, `getComments(postId)` 세 메서드만 가진다. `deleteComment`는 자식 유무를 고려하지 않는다(현재 자식이 없으므로).
- `PostCommentRepository.findAllByPostIdOrderByCreatedAtAscIdAsc`는 `post.id`로 전체 댓글을 `createdAt asc, id asc`로 한 번에 조회하며 `author`만 join fetch한다. `post_comments`에는 이미 `idx_post_comments_post_created_at_id (post_id, created_at, id)` 인덱스가 있다.
- `CommunityPostService.deletePost`는 `postCommentRepository.deleteByPost_Id(postId)`로 게시물의 모든 댓글을 한 번에 삭제한다(부모·자식 구분 없이 `post_id` 조건 하나로 전체 삭제 — 이 그룹 도입 후에도 그대로 유효, 아래 참고).
- `PostCommentController`(생성·목록)는 `/api/community/posts/{postId}/comments`, `CommentController`(삭제)는 `/api/community/comments/{commentId}`로 분리돼 있다 — 이 구조를 유지한다.
- 코드베이스 전례(COM-004): 참조 무결성은 앱 계층에서 검증하고 CHECK 제약은 두지 않는다. 도메인 간 참조가 아니라 같은 도메인 내 self-reference이므로 ADR-0002의 "도메인 간 참조는 service 레이어만" 제약은 해당 없음(`PostCommentRepository`가 자기 자신을 참조).

## API 설계

이슈 #247 착수 전 확정: **별도 "대댓글 작성" 엔드포인트를 신설하지 않는다.** 기존 댓글 작성 API(`POST /api/community/posts/{postId}/comments`)에 선택적 `parentCommentId` 필드를 추가해 부모 댓글 작성과 대댓글 작성을 같은 엔드포인트로 처리한다 — COM-004가 게시물 작성 API에 `instrumentId`를 선택 필드로 얹은 것과 같은 패턴이며, 댓글 조회·삭제 URL 구조를 그대로 유지할 수 있다.

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | /api/community/posts/{postId}/comments | `PostCommentCreateRequest`(`parentCommentId` 필드 추가) | `PostCommentResponse`(`parentCommentId`·`replies` 필드 추가) | `parentCommentId`가 없으면 기존과 동일하게 부모 댓글 생성. 있으면 그 댓글에 대한 대댓글 생성 — 응답의 `replies`는 항상 빈 리스트(방금 만든 댓글엔 아직 자식이 없음) |
| GET | /api/community/posts/{postId}/comments | - | `List<PostCommentResponse>` | 부모 댓글만 최상위 목록으로 오래된 순 반환, 각 항목의 `replies`에 그 부모의 자식 대댓글을 오래된 순으로 포함(중첩 구조). 대댓글 자체는 최상위 목록에 나타나지 않는다 |
| DELETE | /api/community/comments/{commentId} | - | - | 변경 없음(URL·권한 그대로) — 단, 부모 댓글을 삭제하면 자식 대댓글도 함께 삭제(아래 "부모 삭제 시 자식 처리") |
> **2026-08-11 정정 (Issue #277)**: 위 `DELETE` 행의 "부모 댓글을 삭제하면 자식 대댓글도 함께 삭제" 설명은 폐기됐다 — 이제는 부모를 지우면 자식은 보존된 채 부모만 tombstone 처리된다. 상세는 아래 "## COM-005 부모 댓글 tombstone 전환 (이슈 #277)" 절 참고.


## 입력 명세

### `PostCommentCreateRequest`

| 필드 | 필수 | 검증 |
|---|---|---|
| content | 필수(기존) | 변경 없음(`@NotBlank`, `@Size(max = 1000)`) |
| parentCommentId | 선택 | `null` 허용(부모 댓글 생성). 값이 있으면 서비스 계층에서 다음을 순서대로 검증: (1) `parentCommentId`로 조회되지 않거나 `post.id`가 경로의 `postId`와 다르면 404 `NOT_FOUND`(존재하지 않는 댓글로 취급 — 다른 게시물의 댓글 id를 넣어 존재를 추측하는 것을 막는다), (2) 조회된 부모 댓글이 이미 자식(즉 `parentComment != null`)이면 400 `VALIDATION_ERROR`("대댓글에는 답글을 남길 수 없습니다.") — 1단계 제한. `@NotNull` 등 형식 검증 애너테이션은 붙이지 않는다(선택 필드, 참조 유효성은 서비스에서 검증 — COM-004의 `instrumentId`와 동일한 패턴) |

## 데이터 모델

### `V25__add_parent_comment_to_post_comments.sql`

```sql
-- 커뮤니티 댓글에 1단계 대댓글을 위한 self-referencing 부모 댓글 참조(선택)를 추가한다.

ALTER TABLE post_comments
    ADD COLUMN parent_comment_id BIGINT NULL AFTER post_id,
    ADD CONSTRAINT fk_post_comments_parent
        FOREIGN KEY (parent_comment_id) REFERENCES post_comments (id)
        ON DELETE CASCADE,
    ADD INDEX idx_post_comments_parent (parent_comment_id);
```

- `parent_comment_id`는 `NULL` 허용 — 기존(COM-002 시점) 댓글은 마이그레이션으로 값을 채우지 않는다(하위 호환, spec.md "비즈니스 규칙" 3번과 동일 원칙).
- `ON DELETE CASCADE`를 self-referencing FK에 둔다 — "부모 댓글 삭제 시 자식 처리"를 DB 제약으로 강제해 앱 계층에서 자식을 먼저 찾아 지우는 별도 코드 없이 부모 행 삭제만으로 자식이 함께 삭제되게 한다. `CommunityPostService.deletePost`가 쓰는 `deleteByPost_Id`(게시물의 모든 댓글을 `post_id` 조건 하나로 한 번에 삭제하는 벌크 DELETE)에서도 이 CASCADE 덕분에 부모·자식 삭제 순서를 신경 쓸 필요가 없다(부모·자식 모두 같은 조건에 매치되어 함께 삭제되며, self-reference CASCADE가 순서 문제를 방지).
  - **2026-08-11 정정 (Issue #277): 이 CASCADE 결정은 폐기됐다.** 부모 댓글 삭제 시 자식까지 함께 지우는 것이 spec.md의 "남의 대댓글을 지우려 하면 거부당한다" 소유권 규칙과 충돌한다는 지적을 받아들여 tombstone(표시 변경) 방식으로 대체한다. 이 절(`V25` SQL, 엔티티, `PostCommentService`, 테스트 계획 일부)는 착수 당시 기록으로 남겨두되 아래 "## COM-005 부모 댓글 tombstone 전환 (이슈 #277)" 절이 우선한다.
- `idx_post_comments_parent`는 CASCADE 삭제 시 자식 행을 빠르게 찾기 위한 인덱스다. 기존 `idx_post_comments_post_created_at_id`(전체 댓글 조회용)는 그대로 둔다.
- CHECK 제약(예: depth 강제)은 두지 않는다 — 1단계 제한은 "부모의 부모가 없어야 한다"는 앱 로직으로만 검증 가능한 규칙이라 CHECK로 표현할 수 없다(자기 자신을 조인해야 함). COM-004와 같은 판단으로 앱 계층 검증에 맡긴다.

### 엔티티 변경

**`PostComment`**: `parentComment`(nullable self-referencing `ManyToOne`, `FetchType.LAZY`) 필드 추가.

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "parent_comment_id")
private PostComment parentComment;
```

- `create` 팩토리에 `parentComment` 파라미터를 추가한다(오버로드 없이 시그니처 변경 — COM-004와 동일 판단, 호출부가 `PostCommentService` 한 곳뿐).

```java
public static PostComment create(
    CommunityPost post, User author, String content, PostComment parentComment, LocalDateTime createdAt) {
    return new PostComment(post, author, content, parentComment, createdAt);
}

public boolean isReply() {
    return parentComment != null;
}
```

- `isReply()`는 서비스 계층의 1단계 제한 검증("부모로 지정하려는 댓글이 이미 자식인가")에 쓰는 의도 노출 메서드다 — `comment.getParentComment() != null` 산재 호출 대신 엔티티에 둔다.

## 패키지·클래스 설계

| 클래스 | 패키지 | 변경 |
|---|---|---|
| `PostComment` | `community.domain` | `parentComment` 필드·`isReply()`·`create` 시그니처 변경(위) |
| `PostCommentCreateRequest` | `community.dto.request` | `parentCommentId`(nullable `Long`) 필드 추가 |
| `PostCommentResponse` | `community.dto.response` | `parentCommentId`(nullable `Long`)·`replies`(`List<PostCommentResponse>`, 기본 빈 리스트) 필드 추가. 팩토리 두 개로 분리: `from(PostComment comment)`(단건 생성 응답용, `replies=List.of()`, `parentCommentId`는 `comment.getParentComment()`가 있으면 그 id) / `from(PostComment comment, List<PostCommentResponse> replies)`(목록 조회에서 부모 댓글에 자식 목록을 실어 반환할 때 사용) |
| `PostCommentRepository` | `community.repository` | 기존 `findAllByPostIdOrderByCreatedAtAscIdAsc`는 변경 없음(부모·자식 전체를 한 번에 `createdAt asc, id asc`로 이미 가져온다 — 이 순서 그대로 부모/자식 그룹핑에 쓴다). 신규 메서드 없음 |
| `PostCommentService` | `community.service` | `createComment`에 `parentCommentId`(nullable) 파라미터 추가 — 값이 있으면 부모 댓글 조회·검증(아래) 후 `PostComment.create`에 전달. `getComments`를 재구성: 전체 댓글을 기존 쿼리로 한 번에 가져온 뒤 `parentComment == null`인 것만 최상위로 추리고, 나머지는 `parentComment.getId()` 기준으로 그룹핑해 각 최상위 댓글에 자식 리스트를 붙여 반환(둘 다 이미 `createdAt asc, id asc` 순으로 조회됐으므로 그룹핑 후에도 순서가 보존된다 — 추가 정렬 불필요). `deleteComment`는 변경 없음(DB `ON DELETE CASCADE`가 자식 삭제를 대신하므로 서비스 로직에 자식 처리 코드를 추가하지 않는다) |
| `PostCommentController`/`CommentController` | `community.controller` | `PostCommentController.createComment`가 `request.parentCommentId()`를 서비스에 전달하도록 수정. `CommentController`는 변경 없음 |

> **2026-08-11 정정 (Issue #277)**: 위 `PostCommentService` 행의 "`deleteComment`는 변경 없음" 서술은 폐기됐다 — CASCADE가 없어지고 `deleteComment`가 tombstone 분기를 갖도록 재작성된다. 상세는 아래 "## COM-005 부모 댓글 tombstone 전환 (이슈 #277)" 절 참고.

### `PostCommentService.createComment` 대댓글 검증 (신규 로직)

```java
PostComment parentComment = null;
if (parentCommentId != null) {
    parentComment = postCommentRepository.findById(parentCommentId)
        .filter(candidate -> candidate.getPost().getId().equals(postId))
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (parentComment.isReply()) {
        throw new BusinessException(
            ErrorCode.VALIDATION_ERROR, "대댓글에는 답글을 남길 수 없습니다.");
    }
}
PostComment comment = PostComment.create(post, author, content, parentComment, now);
```

## 테스트 계획

- 단위: `PostCommentServiceTest`(Mockito) — 부모 댓글 생성(`parentCommentId=null`, 하위 호환), 대댓글 생성(정상 부모에 첫 답글), 존재하지 않는/다른 게시물 소속 `parentCommentId`로 404 전파, 이미 자식인 댓글을 부모로 지정 시 400 `VALIDATION_ERROR` 전파, `getComments`가 부모·자식을 올바르게 그룹핑해 중첩 응답을 만드는지(부모 여러 개·자식 여러 개 섞인 케이스). `PostComment` 엔티티 단위 테스트로 `isReply()` 동작 확인.
- 슬라이스: `PostCommentRepositoryTest`(`@DataJpaTest`, Testcontainers) — 마이그레이션 적용 후 `parent_comment_id` FK·`ON DELETE CASCADE`·`idx_post_comments_parent` 확인(부모 댓글 삭제 시 자식이 DB에서 함께 사라지는지 리포지토리 레벨로 검증), 기존 `findAllByPostIdOrderByCreatedAtAscIdAsc`가 부모·자식 섞인 순서를 여전히 `createdAt asc, id asc`로 반환하는지. `PostCommentControllerTest`(`@WebMvcTest`) — 요청 JSON `parentCommentId` 포함/생략 시 서비스 호출 인자, 응답 JSON `parentCommentId`·`replies` 필드 계약, 400/404 오류 매핑.
- 통합: `@SpringBootTest` + Testcontainers(ADR-0003) — spec.md "완료 조건 COM-005" 3개 시나리오를 그대로 구현:
  1. 부모 댓글 작성 → 대댓글 작성 → `GET /api/community/posts/{postId}/comments` 조회 시 부모 밑에 자식이 오래된 순으로 포함(`replies` 배열 검증).
  2. 대댓글(자식)에 다시 `parentCommentId`로 답글 시도 시 400 `VALIDATION_ERROR`.
  3. 본인 대댓글만 `DELETE /api/community/comments/{commentId}`로 삭제 가능, 타인 대댓글 삭제 시도 403 `FORBIDDEN`.
  4. (완료 조건 외 회귀, **2026-08-11 폐기**) ~~부모 댓글 삭제 시 그 자식 대댓글도 함께 삭제되는지(`ON DELETE CASCADE`) 확인~~ — 이 CASCADE 회귀 시나리오는 Issue #277로 반대 동작(tombstone, 자식 보존)을 검증하도록 대체됐다. 상세는 아래 "## COM-005 부모 댓글 tombstone 전환 (이슈 #277)" 절의 "테스트 계획 정정" 참고.

## COM-005 부모 댓글 tombstone 전환 (이슈 #277)

## 관련 문서

- Spec: `./spec.md` "COM-005 대댓글" 절, "Decision Gate"(부모 댓글 삭제 시 자식 처리 — 이 절에서 재확정), 사용자 시나리오 소유권 규칙 옆 2026-08-11 추가 각주
- 배경: Issue #277 — 위 "COM-005 대댓글 (이슈 #247)" 절에서 확정한 `ON DELETE CASCADE`(`V25`)가 부모 댓글 삭제 시 다른 회원 소유의 자식 대댓글까지 함께 지워, spec.md의 "남의 대댓글을 지우려 하면 거부당한다" 소유권 규칙과 어떻게 함께 성립하는지 근거가 부족하다는 지적을 받았다(원 근거는 plan.md의 "DB 제약으로 구현 단순화"라는 기술적 이유뿐이었다). 이 절이 그 CASCADE 결정을 대체한다.
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(레이어드, 비즈니스 판단은 service), [ADR-0004](../../adr/0004-flyway-migrations.md)(신규 컬럼·FK 변경은 Flyway로만, 머지된 마이그레이션은 수정하지 않고 새 버전을 추가)

## 확정 정책 (사용자 승인 완료, 재논의 대상 아님)

1. **CASCADE → tombstone 전환.** 부모 댓글(최상위 댓글, `parentComment == null` — 자식이 있든 없든 무관, 자식 유무로 분기하지 않는다) 삭제 시 행을 실제로 지우지 않고 `content`를 "삭제된 댓글입니다"로, 작성자 표시를 "(삭제됨)"으로 치환한다. 동작 일관성을 위해 자식 없는 부모도 동일하게 tombstone 처리한다 — hard delete로 분기하는 코드를 두지 않는다.
2. 자식(대댓글) 자신을 삭제하는 경우는 기존과 동일하게 hard delete를 유지한다 — 대댓글에는 더 하위 자식이 없으므로 보존할 대상이 없다. 즉 tombstone은 "자식을 가질 수 있는 위치"(`parentComment == null`인 최상위 댓글)의 삭제에만 적용된다.
3. 자식이 모두 나중에 개별 삭제(hard delete)되어도 tombstone된 부모 행은 정리하지 않고 영구 보존한다.
4. **spec.md 소유권 규칙과 함께 성립하는 논리**: 타인의 댓글·대댓글을 직접 삭제하려는 시도는 여전히 403 `FORBIDDEN`으로 차단된다(변경 없음). tombstone은 "삭제"가 아니라 부모 자신의 삭제 행위로 인한 "표시 변경"이므로 자식 소유자의 삭제 권한을 침해하지 않는다 — 자식 데이터(작성 사실)는 DB에 그대로 남고, 다만 부모가 사라졌다는 맥락 손실만 발생한다.

## API 설계 정정

| Method | URL | 설명 |
|---|---|---|
| DELETE | /api/community/comments/{commentId} | **요청·응답 계약은 바뀌지 않는다**(URL·204·소유권 검증 그대로) — 서버 내부 동작만 바뀐다. 소유자 검증(403) 통과 후 대상이 최상위 댓글(`parentComment == null`)이면 실제 DELETE 대신 tombstone 처리, 대댓글(자식, `parentComment != null`)이면 기존처럼 실제 DELETE |

## 데이터 모델 정정

### `V31__change_post_comments_parent_fk_to_restrict.sql` (신규 — 현재 마지막 버전 `V30`, `ls db/migration/`으로 확인한 다음 번호)

```sql
-- Issue #277: 부모 댓글 삭제가 ON DELETE CASCADE로 자식 대댓글까지 함께 지우던 것을
-- tombstone(표시 변경) 방식으로 전환한다. 삭제 표시 시각을 저장할 컬럼을 추가하고,
-- 부모 댓글은 이제 실제로 DELETE되지 않으므로(UPDATE만 발생) CASCADE가 발동할
-- 상황 자체가 없어야 정상이다 — 그럼에도 향후 실수로 부모를 하드 삭제하는 코드가
-- 생겨 자식이 조용히 함께 사라지는 회귀를 막기 위해 FK 규칙을 RESTRICT로 명시한다.

ALTER TABLE post_comments
    ADD COLUMN deleted_at DATETIME NULL AFTER content;

ALTER TABLE post_comments
    DROP FOREIGN KEY fk_post_comments_parent;

ALTER TABLE post_comments
    ADD CONSTRAINT fk_post_comments_parent
        FOREIGN KEY (parent_comment_id) REFERENCES post_comments (id)
        ON DELETE RESTRICT;
```

- MySQL은 FK의 `ON DELETE` 규칙만 단독으로 `ALTER`할 수 없다 — 표준 절차대로 기존 제약을 `DROP FOREIGN KEY`한 뒤 같은 이름으로 `ADD CONSTRAINT`해 재생성한다. `idx_post_comments_parent` 인덱스는 FK 제약과 별개로 이미 존재하므로 그대로 둔다(재생성 불필요).
- `deleted_at DATETIME NULL` — `NULL`이면 살아있는(또는 tombstone 대상이 아닌) 댓글, 값이 있으면 tombstone된 부모 댓글이다. 하드 삭제된 자식은 행 자체가 사라지므로 이 컬럼과 무관하다. 기존(마이그레이션 이전) 댓글은 모두 `NULL`로 하위 호환.
- `RESTRICT`(`NO ACTION`과 MySQL에서 동일하게 동작 — 참조 행이 남아있으면 삭제를 거부)를 택한다. `CASCADE`를 없앤 것으로 충분하지만, 명시적으로 `RESTRICT`를 선언해 "부모를 실수로 하드 삭제하면 자식이 남아있는 한 DB가 거부한다"는 안전장치를 코드로 남긴다.
- **주의 — `deleteByPost_Id` 벌크 삭제와의 상호작용 (이번 작업 범위 밖이지만 회귀 위험이 있어 기록한다)**: `CommunityPostService.deletePost`가 쓰는 `postCommentRepository.deleteByPost_Id(postId)`는 게시물의 모든 댓글(부모·자식 모두)을 단일 `DELETE` 문으로 지운다. 이 로직 자체는 이번 spec 그룹의 범위 밖이라 코드를 바꾸지 않는다. 다만 `RESTRICT` 제약 하에서 자기참조 FK를 가진 테이블의 벌크 삭제는 MySQL이 행 처리 순서를 보장하지 않으므로, 같은 문 안에서 자식보다 부모 행이 먼저 처리되면 그 시점엔 아직 자식 행이 남아있어 제약 위반으로 실패할 가능성이 이론상 있다(`CASCADE`였을 때는 이 순서 문제가 없었다 — DB가 알아서 연쇄 삭제했기 때문). 이 마이그레이션이 `deletePost`의 기존 동작을 깨뜨리지 않는지는 실제로 검증이 필요하다 — 아래 "테스트 계획 정정"의 `@DataJpaTest`에 회귀 케이스를 추가한다. 실패가 재현되면(코드 변경 없이는 통과하지 못하면) 이 spec 범위를 넘어서는 별도 이슈로 등록하고 이 plan에서 미리 코드를 고치지 않는다.

### 엔티티 변경 정정

**`PostComment`**: `deletedAt`(nullable `LocalDateTime`) 필드 추가.

```java
@Column(name = "deleted_at")
private LocalDateTime deletedAt;

public void tombstone(LocalDateTime deletedAt) {
    this.deletedAt = deletedAt;
}

public boolean isTombstoned() {
    return deletedAt != null;
}
```

- **결정: `content`·작성자 필드를 실제로 치환(overwrite)하지 않고, `deletedAt` 플래그로만 tombstone 여부를 표시한 뒤 응답 계층에서 표시 문구로 바꿔치기한다.** (content를 직접 덮어쓰는 대안과 비교한 근거)
  - 원본 `content`·`author`를 DB에 보존하면 향후 신고·감사(audit) 목적으로 필요할 때 원문을 조회할 수 있다 — 직접 치환은 되돌릴 수 없는 파괴적 연산이라 지금 그 비가역성을 선택할 이유가 없다.
  - 원본 작성자(`author` FK)를 건드리지 않으므로 `deleteComment`의 소유권 검증(`comment.getAuthor().getId().equals(authenticatedUserId)`)이 tombstone 여부와 무관하게 그대로 동작한다 — 이미 tombstone된 댓글에 같은 작성자가 삭제를 다시 요청해도(멱등하게 `deletedAt`만 갱신) 소유권 검사 로직을 분기할 필요가 없다.
  - `deletedAt`은 conventions.md의 "시간 필드는 `LocalDateTime` + `xxxAt`" 네이밍과 자연히 맞고, 별도 boolean `tombstoned` 플래그보다 "언제 tombstone됐는지"까지 기록해 정보 손실이 적다(감사 목적에 유리).
- `tombstone(LocalDateTime)`·`isTombstoned()`는 conventions.md "상태 변경은 의도가 드러나는 메서드로만", "boolean은 `is~`" 규칙을 그대로 따른다(setter 없음).

## 패키지·클래스 설계 정정 (위 "COM-005 대댓글" 절 표 중 아래 두 행을 대체)

| 클래스 | 패키지 | 변경 |
|---|---|---|
| `PostComment` | `community.domain` | `deletedAt`(nullable `LocalDateTime`) 필드, `tombstone(LocalDateTime)`, `isTombstoned()` 추가(위) |
| `PostCommentService` | `community.service` | `deleteComment`를 아래 로직으로 재작성(위 절의 "변경 없음" 서술을 대체) |
| `PostCommentResponse` | `community.dto.response` | `from(PostComment, List<PostCommentResponse>)` 내부 로직에 tombstone 분기 추가(레코드 필드는 추가하지 않음, 아래) |

### `PostCommentService.deleteComment` 재설계

```java
@Transactional
public void deleteComment(Long authenticatedUserId, Long commentId) {
    PostComment comment = postCommentRepository.findById(commentId)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    if (!comment.getAuthor().getId().equals(authenticatedUserId)) {
        throw new BusinessException(ErrorCode.FORBIDDEN);
    }
    if (comment.getParentComment() == null) {
        comment.tombstone(LocalDateTime.now(clock));
    } else {
        postCommentRepository.delete(comment);
    }
}
```

- 소유자 검증(403)은 tombstone 대상이든 hard delete 대상이든 동일하게 먼저 수행한다 — spec.md 사용자 시나리오에 남긴 "직접 삭제는 여전히 403" 근거가 이 순서로 구현에 반영된다.
- `comment.getParentComment() == null`로 "자식을 가질 수 있는 위치"를 판별한다. 기존 `PostComment.isReply()`(`parentComment != null`)의 부정과 동일한 조건이므로 `!comment.isReply()`로 표현해도 무방하다 — 구현 시점에 가독성 기준으로 택일한다.
- `tombstone()` 호출은 관리 상태(managed) 엔티티의 필드를 바꾸는 것이므로 트랜잭션 커밋 시 JPA dirty checking으로 자동 반영된다 — 명시적 `save()` 호출 불필요(`CommunityPost.update()` 등 기존 패턴과 동일).
- 이미 tombstone된 부모 댓글에 같은 작성자가 삭제를 다시 요청하면 오류 없이 `deletedAt`만 갱신되는 멱등 동작이다 — spec에 금지 조항이 없으므로 막지 않는다.

### `PostCommentResponse` 재설계

```java
private static final String TOMBSTONED_CONTENT = "삭제된 댓글입니다";
private static final String TOMBSTONED_AUTHOR_DISPLAY = "(삭제됨)";

public static PostCommentResponse from(PostComment comment, List<PostCommentResponse> replies) {
    boolean tombstoned = comment.isTombstoned();
    return new PostCommentResponse(
        comment.getId(),
        tombstoned ? TOMBSTONED_AUTHOR_DISPLAY : comment.getAuthor().getNickname(),
        tombstoned ? TOMBSTONED_CONTENT : comment.getContent(),
        comment.getCreatedAt(),
        comment.getParentComment() != null ? comment.getParentComment().getId() : null,
        replies);
}
```

- `parentCommentId`·`replies`는 tombstone 여부와 무관하게 그대로 노출한다 — tombstone은 "표시 변경"이지 자식 관계를 숨기는 것이 아니다(자식은 여전히 목록에 남아 조회된다).
- 응답 레코드에 `isTombstoned` 같은 boolean 필드를 새로 추가하지 않는다 — 클라이언트가 "삭제된 댓글입니다" 문자열이 아닌 명시적 상태를 원할 수도 있지만, spec.md 요구사항에 없는 필드를 미리 만들지 않는다(과설계 금지). 필요해지면 별도 이슈로 확장한다.

## 테스트 계획 정정 (위 "COM-005 대댓글" 절 테스트 계획의 통합 테스트 4번 항목을 대체)

- 단위: `PostCommentServiceTest`(Mockito) 추가 케이스 — 최상위 댓글(`parentComment == null`) 삭제 시 `postCommentRepository.delete()`가 호출되지 않고 `tombstone()` 경로를 타는지(엔티티 상태 또는 `deletedAt` 검증), 대댓글(자식) 삭제 시 기존처럼 `delete()`가 호출되는지, 타인 댓글·대댓글 삭제 시도 403(회귀). `PostComment` 엔티티 단위 테스트로 `tombstone()`·`isTombstoned()` 동작 확인.
- 슬라이스: `PostCommentRepositoryTest`(`@DataJpaTest`, Testcontainers) 추가 케이스 — `V31` 적용 후 `deleted_at` 컬럼 존재 확인, FK가 `RESTRICT`로 바뀐 뒤 자식이 있는 부모 댓글을 리포지토리 레벨에서 직접 `delete()` 시도하면 제약 위반 예외가 발생하는지(안전장치 검증), 위 "주의" 캐벗에서 지적한 `deleteByPost_Id`가 부모+자식이 섞인 게시물에서도 여전히 성공하는지(회귀 확인 — 실패하면 별도 이슈로 넘긴다). `PostCommentControllerTest`(`@WebMvcTest`) — tombstone된 부모 댓글 조회 시 응답 `content`가 "삭제된 댓글입니다", `authorNickname`이 "(삭제됨)"으로 나오는지, `replies`·`parentCommentId`는 영향받지 않는지.
- 통합: `@SpringBootTest` + Testcontainers(ADR-0003) — 아래 시나리오로 위 "COM-005 대댓글" 절 통합 테스트 4번(CASCADE 회귀)을 대체한다:
  1. 부모 댓글 작성 → 대댓글 작성 → 부모 삭제 → `GET /api/community/posts/{postId}/comments` 재조회 시: 부모 행이 사라지지 않고 `content`="삭제된 댓글입니다"·`authorNickname`="(삭제됨)"으로 노출, 자식은 원래 내용 그대로 `replies`에 남아있다.
  2. 자식(대댓글) 없는 부모 댓글 삭제도 동일하게 tombstone된다(하드 삭제 아님) — 삭제 후 조회 시 행이 여전히 존재하고 표시만 바뀐다.
  3. 대댓글(자식) 자신을 삭제하면 기존처럼 하드 삭제된다 — 삭제 후 조회 시 그 자식이 부모의 `replies`에서 사라진다.
  4. 타인의 부모 댓글·대댓글 삭제 시도는 여전히 403 `FORBIDDEN`(회귀 — tombstone 도입으로 소유권 규칙이 약해지지 않았음을 확인).

## tombstone된 댓글에 답글 금지 (PR #331 리뷰 참고 사항 #2 — 사용자 확정)

- **배경**: 위 tombstone 설계는 `parentCommentId`가 가리키는 부모가 살아있는지 여부를 검증하지 않았다 — `PostCommentControllerTest`·통합 테스트로 "삭제된 댓글입니다"로 표시되는 글타래에도 새 대댓글이 계속 달릴 수 있음을 확인했고, 리뷰에서 spec에 정책이 없는 사각지대로 지적됐다.
- **결정**: tombstone된 부모(`isTombstoned() == true`)에는 새 대댓글을 남길 수 없다. `PostCommentService.createComment`의 `parentCommentId` 검증 순서에 세 번째 단계를 추가한다 — (1) 존재하지 않거나 다른 게시물 소속이면 404 `NOT_FOUND`(기존), (2) 이미 자식(`isReply()==true`)이면 400 `VALIDATION_ERROR`("대댓글에는 답글을 남길 수 없습니다.", 기존, 1단계 제한), **(3) `isTombstoned()==true`면 400 `VALIDATION_ERROR`("삭제된 댓글에는 답글을 남길 수 없습니다.")(신규)**.
- **API 계약 변경**: `POST /api/community/posts/{postId}/comments`의 400 오류 사유가 하나 늘어난다(요청·응답 필드는 그대로). `docs/api-contracts.md`의 해당 엔드포인트 절에 이 400 사유를 추가한다.
- 테스트: `PostCommentServiceTest`(tombstone된 부모에 답글 시도 시 400, 정상 부모에는 여전히 허용되는 대조 케이스) + `@WebMvcTest`(400 응답 계약) + 통합 테스트(부모 tombstone 후 그 부모로 대댓글 작성 시도 → 400, 응답 본문에 새 대댓글이 반영되지 않았는지 재조회로 확인).


## tombstone된 댓글 재삭제 요청 응답 (PR #331 리뷰 참고 사항 #3 — 사용자 확정)

- **배경**: 리뷰에서 QA로 확인된 동작 — 이미 tombstone된 댓글을 소유자가 다시 `DELETE /api/community/comments/{commentId}`로 요청하면 404가 아니라 204를 반환하고 `deleted_at`만 갱신된다(`tombstone()`이 멱등적으로 `deletedAt`을 덮어쓴다). spec·api-contracts.md 어디에도 재삭제 시 동작이 명시되어 있지 않아 리뷰에서 판정을 보류했다.
- **결정**: 현행(204) 유지. 코드 변경 없음 — 이미 tombstone된 댓글을 소유자가 다시 삭제해도 204를 반환하고 `deletedAt`만 조용히 갱신된다.
- **근거**: (1) 멱등성은 HTTP 응답 코드가 아니라 서버 상태(여러 번 불러도 결과가 같음)에 관한 개념이라 204 반복과 404 전환 모두 멱등 DELETE로 인정된다. (2) 이 프로젝트에는 두 선례가 있다 — 하드 삭제 계열(관심목록 등, `api-contracts.md:1053`)은 행이 진짜로 사라지므로 재삭제 시도가 자연스럽게 404가 되고, 상태 전환으로 논리적으로 삭제되는 `DELETE /api/orders/{orderId}`(지정가 취소)는 재요청 시 409 `ORDER_ALREADY_CANCELLED`로 명시적으로 알린다 — tombstone은 후자와 구조가 가깝지만, 새 오류 코드를 만들고 spec·계약 문서를 늘리는 비용 대비 얻는 것(클라이언트가 재삭제를 구분해야 할 실사용 요구가 없다)이 적다고 판단해 현행을 유지한다. (3) `docs/api-contracts.md`의 `DELETE /api/community/comments/{commentId}` 계약에 이 재삭제 멱등 동작을 명시적으로 추가해 다음 리뷰에서 같은 질문이 나오지 않도록 한다(코드 변경 없음, 문서만 보강).




## COM-006 사진 첨부 (이슈 #248)

## 관련 문서

- Spec: `./spec.md` "COM-006 사진 첨부" 절, "Decision Gate"(이미지 저장 방식·허용 형식/크기 — 이 plan에서 확정), "완료 조건 COM-006"
- 선행 구현: `008-community`(`CommunityPost`·`CommunityPostService`·`CommunityPostController`), COM-004(`V24`, `Instrument` cross-domain 참조 패턴), COM-005(`V25`, self-referencing FK + `ON DELETE CASCADE` 패턴) — 다음 마이그레이션 버전은 `V26`
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(레이어드, 도메인 간 참조는 service 레이어만, `common`은 전역 예외·오류 응답·공통 설정만 — 신규 인프라를 성급하게 `common`에 두지 않는다), [ADR-0004](../../adr/0004-flyway-migrations.md)(신규 테이블·컬럼은 Flyway로만)
- PRD 근거: `ai/prd.md` COM-006(신설 2차 MVP, 상세는 이 spec이 정본)

## Decision Gate 확정

### 이미지 저장 방식: 로컬 파일시스템

- 이 프로젝트는 `compose.yaml`에 MySQL·Redis만 정의돼 있고 S3·MinIO 등 객체 스토리지 인프라가 없다. 팀 규모(로컬 개발 중심)에서 새 인프라(오브젝트 스토리지 계정·자격증명·SDK 의존성)를 지금 도입하는 비용이 이 기능이 주는 가치보다 크다 — 로컬 파일시스템 저장을 채택한다.
- **ADR을 새로 만들지 않는다.** ADR은 "아키텍처 수준" 결정(스택 선택, 레이어 구조, 도메인 간 참조 규칙처럼 여러 기능에 걸쳐 반복 적용되는 규칙)을 기록하는 문서다(ADR-0002·0004가 그 예). 이번 결정은 COM-006 한 기능의 구현 세부사항(파일을 어디에 저장하는가)이라 이 plan.md 기록으로 충분하다고 판단한다 — CLAUDE.md 규칙2는 "기존 ADR과 어긋나면 새 ADR"을 요구하지, 모든 인프라 선택에 ADR을 요구하지 않는다. 이 판단이 틀렸다고 리뷰에서 지적되면 그때 ADR로 승격한다.
- **과설계 금지 — 인터페이스로만 추상화, 클라우드 구현체는 지금 만들지 않는다.** `FileStorageService` 인터페이스 + `LocalFileStorageService` 구현체 하나만 둔다. `S3FileStorageService` 등은 실제로 필요해지는 시점(운영 배포 논의)에 새로 추가한다 — 지금은 인터페이스 시그니처가 로컬이 아닌 저장소로도 자연스럽게 구현 가능한 형태(바이트 스트림 입출력, 저장소 종속 경로 개념 노출 안 함)인지만 확인한다.
  - **→ 그 시점이 왔다 (2026-08-11, ADR-0020·이슈 #326).** 배포를 EC2 + 관리형 서비스(RDS·ElastiCache·S3) + 블루-그린으로 전환하기로 결정하면서 이 문서가 전제한 단일 인스턴스가 더 이상 유지되지 않는다. `S3FileStorageService` 구현과 기존 로컬 업로드 파일의 S3 이관은 **별도 이슈**로 진행한다 — 그 작업 전까지는 아래 `LocalFileStorageService` 항목의 제약(인스턴스 간 미공유, 재배포 시 유실)이 그대로 남아 있다. 위에서 확인해 둔 "로컬이 아닌 저장소로도 구현 가능한 시그니처"가 이 교체를 인터페이스 변경 없이 가능하게 한다.
    - **→ 구현 완료 (이슈 #330, 이 PR).** `S3FileStorageService`(`@Profile("prod")`)를 추가하고 `LocalFileStorageService`에 `@Profile("!prod")`를 붙여 두 구현체가 프로파일로 배타적으로 등록되도록 했다. `FileStorageService` 인터페이스는 변경하지 않았다 — 위에서 확인해 둔 시그니처 그대로 교체 가능했다.
- 저장 위치는 `application.yml`에 설정한다. 시크릿이 아니므로(conventions.md "시크릿" 절 대상 아님) yml에 기본값을 둘 수 있다.

```yaml
finplay:
  community:
    image-storage:
      base-directory: ${COMMUNITY_IMAGE_STORAGE_DIR:./data/community-images}
```

- `base-directory`는 상대 경로 기본값(`./data/community-images`, 로컬 실행 디렉터리 기준) — 운영 배포 시 `COMMUNITY_IMAGE_STORAGE_DIR` 환경변수로 절대 경로를 덮어쓴다. 이 디렉터리는 git에 커밋하지 않는다(`.gitignore`에 `data/` 추가 필요 여부 확인 — 이미 있으면 생략).

### 허용 형식·최대 크기

- 허용 형식: JPEG(`image/jpeg`), PNG(`image/png`), WEBP(`image/webp`) 세 가지 — `MultipartFile.getContentType()`이 이 셋 중 하나가 아니면 400 `VALIDATION_ERROR`("허용하지 않는 이미지 형식입니다. JPEG, PNG, WEBP만 첨부할 수 있습니다.").
- 최대 크기: 5MB(spec.md 후보 그대로 채택 — 이 프로젝트 규모에서 더 정교한 기준을 정할 근거가 없다). `spring.servlet.multipart.max-file-size=5MB`로 1차 방어하고, 이를 초과하면 Spring이 던지는 `MaxUploadSizeExceededException`을 `GlobalExceptionHandler`에서 400 `VALIDATION_ERROR`로 매핑한다(아래 "예외 처리 변경").
- 게시물당 이미지 개수 제한(최대 1장)은 별도 개수 검증 코드를 두지 않는다 — 아래 API 설계에서 요청 DTO 자체가 이미지 참조 필드를 단수(`imageId` 1개)로만 받으므로 형식상 다중 첨부가 불가능하다(COM-004가 종목 태그를 다중이 아닌 단일 `instrumentId`로 제한한 것과 동일한 방식).

### 부모 댓글 삭제 시 자식 처리는 COM-005에서 이미 확정됨 (참고용 각주)

이 항목은 COM-006과 무관하다 — COM-005 plan에 이미 확정돼 있다. 여기서는 다루지 않는다.

## 업로드 흐름 설계

**선(先)업로드 → 후(後)참조 방식을 채택한다.** 게시물 생성과 이미지 업로드를 하나의 multipart 요청으로 묶지 않고, 별도 업로드 API로 이미지를 먼저 저장한 뒤 그 결과로 받은 `imageId`를 기존 게시물 생성 요청(JSON)에 선택 필드로 포함한다.

- 근거: 기존 `POST /api/community/posts`는 `@Valid @RequestBody`(JSON)로 구현돼 있다. 이를 multipart(`@RequestPart` JSON + 파일 파트 혼합)로 바꾸면 기존 요청 계약(`Content-Type: application/json`)이 깨져 프런트엔드가 게시물 생성 경로를 두 갈래(이미지 있음/없음)로 분기해야 한다. 반면 별도 업로드 엔드포인트는 기존 생성 API의 요청 계약을 전혀 바꾸지 않고 `imageId`(nullable `Long`) 필드 하나만 추가하면 된다 — COM-004가 `instrumentId`를, COM-005가 `parentCommentId`를 추가한 것과 같은 패턴을 그대로 반복한다.
- 부가 이점: 클라이언트가 파일을 선택한 즉시 업로드해 미리보기를 보여주고, 형식·크기 오류를 게시물 작성 폼 제출 전에 알려줄 수 있다(수용 기준에 명시된 요구는 아니지만 자연스러운 결과).
- **받아들이는 한계(범위 제외로 명시)**: 이미지를 업로드했지만 게시물 생성을 끝내지 않고 이탈하면 그 이미지는 어떤 게시물에도 연결되지 않은 채(`post_id IS NULL`) 남는다. 이 orphan 이미지를 정리하는 배치·TTL은 이번 그룹의 범위가 아니다 — spec.md "범위 제외"에 명시된 항목은 아니지만 같은 성격(운영 정리 작업)이므로 여기서 범위 제외로 취급하고 향후 이슈로 남긴다.

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | /api/community/posts/images | `multipart/form-data`, 파트명 `image` | `CommunityPostImageResponse` (201) | 인증 사용자가 이미지 파일 하나를 업로드. 아직 어떤 게시물에도 연결되지 않은 상태(`post_id NULL`)로 저장 |
| GET | /api/community/posts/images/{imageId}/file | - | 이미지 바이트 스트림 (`Content-Type`은 저장된 원본 형식) | 이미지 다운로드/표시 전용 엔드포인트. 존재하지 않으면 404 `NOT_FOUND` |
| POST | /api/community/posts | `CommunityPostCreateRequest`(`imageId` 필드 추가) | `CommunityPostResponse`(`imageId`·`imageUrl` 필드 추가) | `imageId` 지정 시 그 이미지를 새 게시물에 연결. 검증 순서는 아래 "입력 명세" |
| GET | /api/community/posts/{postId}, GET /api/community/posts?... | - | 응답에 `imageId`·`imageUrl` 추가(없으면 둘 다 `null`) | 조회 응답에 첨부 이미지 노출 |
| PATCH | /api/community/posts/{postId} | 변경 없음(`imageId` 필드를 받지 않음) | 변경 없음 | **이번 그룹에서는 수정 시 이미지 교체·해제를 지원하지 않는다** — spec.md COM-006 완료 조건에 이미지 수정 시나리오가 없고, 첨부는 "게시물 작성 시" 요구사항으로만 명시돼 있다(COM-004의 `instrumentId`처럼 PATCH에서 매 요청 전체 교체 대상으로 정의된 필드가 아니다). 이미지 교체가 필요해지면 별도 이슈로 확장한다 |
| DELETE | /api/community/posts/{postId} | - | 변경 없음(204) | 게시물 삭제 시 연결된 이미지 행과 물리 파일을 함께 제거(아래 "삭제 처리") |

- 업로드 엔드포인트는 `/api/community/posts` 하위에 두되 게시물 리소스와 별개 컬렉션(`/images`)으로 분리한다 — 게시물이 아직 존재하지 않는 시점에 이미지를 만들기 때문에 `/api/community/posts/{postId}/images`처럼 특정 게시물에 종속된 경로로 만들 수 없다.

## 입력 명세

### `POST /api/community/posts/images`

| 필드 | 필수 | 검증 |
|---|---|---|
| image (multipart part) | 필수 | 비어 있으면(`isEmpty()`) 400 `VALIDATION_ERROR`("첨부할 이미지 파일이 없습니다."). `contentType`이 `image/jpeg`·`image/png`·`image/webp` 중 하나가 아니면 400 `VALIDATION_ERROR`. 크기 5MB 초과는 `MaxUploadSizeExceededException` → 400 `VALIDATION_ERROR`(위 "허용 형식·최대 크기") |

### `CommunityPostCreateRequest.imageId`

| 필드 | 필수 | 검증 |
|---|---|---|
| imageId | 선택 | `null` 허용(미첨부, 하위 호환). 값이 있으면 서비스 계층에서 순서대로 검증: (1) 존재하지 않으면 404 `NOT_FOUND`, (2) `uploaderId`가 인증 사용자와 다르면 403 `FORBIDDEN`("본인이 업로드한 이미지만 사용할 수 있습니다." — 타인이 업로드한 imageId를 추측해 도용하는 것을 막는다), (3) 이미 다른 게시물에 연결돼 있으면(`post_id IS NOT NULL`) 400 `VALIDATION_ERROR`("이미 다른 게시물에 사용된 이미지입니다.") — 이미지 하나를 여러 게시물이 공유하지 않는다. `@NotNull` 등 형식 검증 애너테이션은 붙이지 않는다(선택 필드, 참조 유효성은 서비스에서 검증 — COM-004·COM-005와 동일 패턴) |

## 데이터 모델

### `V26__create_community_post_images.sql`

```sql
-- 커뮤니티 게시물에 첨부하는 이미지(게시물당 최대 1장)를 저장한다.
-- post_id는 업로드 시점에는 NULL이고, 게시물 생성 시 연결되며 이후 변경하지 않는다(선업로드-후참조 방식).

CREATE TABLE community_post_images (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    uploader_id BIGINT NOT NULL,
    post_id BIGINT NULL,
    stored_filename VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_community_post_images_uploader
        FOREIGN KEY (uploader_id) REFERENCES users (id),
    CONSTRAINT fk_community_post_images_post
        FOREIGN KEY (post_id) REFERENCES community_posts (id)
        ON DELETE CASCADE,
    CONSTRAINT uq_community_post_images_post UNIQUE (post_id)
) ENGINE = InnoDB;
```

- `stored_filename`은 서비스 계층에서 `UUID.randomUUID()` + **서버가 검증한 `contentType`에서 결정한 확장자**로 생성한다(충돌 방지, 사용자 입력 파일명의 어떤 부분도 경로 조립에 쓰지 않아 경로 조작 방지 — PR #269 리뷰에서 원본 파일명의 확장자를 그대로 쓰던 초안이 이 방지를 실제로 만족하지 못함을 확인하고 정정했다, 저장소 계층에도 경계 검사를 이중으로 둔다). `original_filename`은 표시·다운로드 시 사용자에게 보여줄 용도로 별도 저장하며 경로 조립에는 절대 쓰지 않는다(spec.md 힌트 그대로).
- `post_id`는 업로드 시 `NULL`, 게시물 생성 트랜잭션에서 채워진다. `UNIQUE (post_id)`로 "게시물당 이미지 최대 1장"을 DB 제약으로도 강제한다(`NULL`은 유니크 제약에서 여러 행에 중복 허용되므로 업로드만 되고 미연결인 이미지가 여러 개 있어도 위반이 아니다 — 정상 상황).
- `ON DELETE CASCADE`(post_id FK)로 게시물 삭제 시 이미지 행이 DB에서 함께 삭제된다(COM-005의 self-referencing CASCADE와 같은 판단). 단, **물리 파일은 DB 트리거로 지울 수 없으므로 애플리케이션 코드가 별도로 삭제한다** (아래 "삭제 처리").
- 인덱스: `post_id`는 FK+UNIQUE 제약이 이미 인덱스를 만든다. `uploader_id`에 대한 추가 인덱스는 두지 않는다 — 현재 조회 패턴(imageId로 단건 조회 후 uploader 비교)이 PK 조회만으로 충분해 인덱스 이득이 없다(과설계 금지).

### 엔티티

**`CommunityPostImage`** (신규, `community.domain`)

```java
@Entity
@Table(name = "community_post_images")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityPostImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploader_id", nullable = false)
    private User uploader;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id")
    private CommunityPost post;

    @Column(name = "stored_filename", nullable = false, length = 255)
    private String storedFilename;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private CommunityPostImage(User uploader, String storedFilename, String originalFilename,
        String contentType, long sizeBytes, LocalDateTime createdAt) {
        this.uploader = uploader;
        this.storedFilename = storedFilename;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.createdAt = createdAt;
    }

    public static CommunityPostImage create(User uploader, String storedFilename,
        String originalFilename, String contentType, long sizeBytes, LocalDateTime now) {
        return new CommunityPostImage(uploader, storedFilename, originalFilename, contentType, sizeBytes, now);
    }

    public boolean isAssigned() {
        return post != null;
    }

    public void assignToPost(CommunityPost post) {
        this.post = post;
    }
}
```

- `isAssigned()`는 "이미 다른 게시물에 사용된 이미지" 검증에 쓰는 의도 노출 메서드다(COM-005의 `isReply()`와 동일한 패턴).
- `assignToPost`는 conventions.md의 "setter를 두지 않는다, 의도가 드러나는 메서드로만 상태 변경" 규칙을 따른 유일한 예외적 이후-변경 메서드다 — 생성 시점에 `post`를 알 수 없는(선업로드-후참조) 구조상 불가피하다.

**`CommunityPost`**: 조회 편의를 위해 역방향 `@OneToOne(mappedBy = "post")` 참조를 추가한다(쓰기는 항상 `CommunityPostImage` 쪽에서만 한다).

```java
@OneToOne(mappedBy = "post", fetch = FetchType.LAZY)
private CommunityPostImage image;
```

- `CommunityPost.create`/`update` 시그니처는 바꾸지 않는다 — 이미지 연결은 `CommunityPostService`가 `CommunityPostImage.assignToPost(post)`를 호출해 처리하며 `CommunityPost` 생성자에는 관여하지 않는다(COM-004의 `instrument`·COM-005의 `parentComment`와 달리, 이미지는 게시물 쪽이 아니라 이미지 쪽이 참조를 갖는 구조라 팩토리 시그니처 변경이 필요 없다).

## 패키지·클래스 설계

| 클래스 | 패키지 | 변경 |
|---|---|---|
| `FileStorageService` | `community.storage` (신규 패키지) | 인터페이스 신규: `String store(MultipartFile file, String storedFilename)`(저장 후 저장 경로/키 반환 — 로컬 구현은 그대로 `storedFilename` 반환), `Resource load(String storedFilename)`(다운로드용), `void delete(String storedFilename)`(best-effort, 실패 시 로그만) |
| `LocalFileStorageService` | `community.storage` | `FileStorageService` 구현체. `finplay.community.image-storage.base-directory`를 `@Value`로 주입받아 `Path`로 다룬다. `store`는 `Files.copy(file.getInputStream(), baseDir.resolve(storedFilename))`, `load`는 `UrlResource`로 파일 반환, `delete`는 `Files.deleteIfExists` — `IOException`은 잡아서 `log.warn`만 남기고 던지지 않는다(삭제 실패로 게시물 삭제 전체가 실패하면 안 됨). **로컬 파일시스템은 인스턴스 간에 공유되지 않는다 — 이 구현은 단일 인스턴스 배포를 전제하며, 다중 인스턴스 배포로 전환하려면 `FileStorageService` 구현체를 오브젝트 스토리지로 먼저 교체해야 한다** (PR #269 리뷰, ADR-0014의 코인 변동 감시 다중 인스턴스 대응과 같은 전제 위반을 여기서도 남기지 않기 위한 기록) |
| `CommunityPostImage` | `community.domain` | 신규 엔티티(위) |
| `CommunityPostImageRepository` | `community.repository` | `JpaRepository<CommunityPostImage, Long>` — 커스텀 쿼리 없음(단건 `findById`로 충분) |
| `CommunityPostImageResponse` | `community.dto.response` | 신규 record: `imageId`, `imageUrl`(`"/api/community/posts/images/" + imageId + "/file"` 조합) |
| `CommunityPostImageController` | `community.controller` | 신규: `POST /api/community/posts/images`(업로드), `GET /api/community/posts/images/{imageId}/file`(다운로드) |
| `CommunityPostImageService` | `community.service` | 신규: `uploadImage(Long authenticatedUserId, MultipartFile file)`(형식·크기 검증 → `UUID` 파일명 생성 → `fileStorageService.store` → `CommunityPostImage.create` 저장), `loadImageFile(Long imageId)`(존재하지 않으면 404), `resolveImageForPost(Long authenticatedUserId, Long imageId)`(위 "입력 명세" 3단계 검증 후 `CommunityPostImage` 반환 — `CommunityPostService`가 게시물 생성 중 호출), `deleteImageIfPresent(CommunityPost post)`(게시물의 연결 이미지가 있으면 DB 행 삭제 전 `storedFilename`을 확보해두고, 게시물 삭제 후 물리 파일 삭제) |
| `CommunityPostCreateRequest` | `community.dto.request` | `imageId`(nullable `Long`) 필드 추가 |
| `CommunityPostResponse` | `community.dto.response` | `imageId`(nullable `Long`)·`imageUrl`(nullable `String`) 필드 추가. `from(CommunityPost)`에서 `post.getImage()`가 `null`이면 둘 다 `null` |
| `CommunityPostRepository.findById` | `community.repository` | `@EntityGraph(attributePaths = {"author", "instrument", "image"})`로 확장 |
| `CommunityPostRepositoryImpl.findPostsOrderByCreatedAtDesc` | `community.repository` | `leftJoin(post.image).fetchJoin()` 추가(N+1 방지) |
| `CommunityPostService.createPost` | `community.service` | `imageId` 파라미터 추가 — `imageId != null`이면 `communityPostImageService.resolveImageForPost(authenticatedUserId, imageId)` 호출 후 게시물 저장, 저장된 `post`에 `image.assignToPost(post)` 호출(같은 트랜잭션) |
| `CommunityPostService.deletePost` | `community.service` | 기존 댓글 삭제 다음, 게시물 삭제 직후에 `communityPostImageService.deleteImageIfPresent(post)` 호출 |
| `CommunityPostController.createPost` | `community.controller` | `request.imageId()`를 서비스에 전달 |
| `application.yml` | - | `spring.servlet.multipart.max-file-size: 5MB`, `spring.servlet.multipart.max-request-size: 6MB`, `finplay.community.image-storage.base-directory` 추가 |
| `GlobalExceptionHandler` | `common` | `MaxUploadSizeExceededException` 핸들러 추가 → 400 `VALIDATION_ERROR`("첨부 파일 크기가 허용 범위를 초과했습니다.") |

- `CommunityPostImageService`를 `community.service`에 두고 `CommunityPostService`가 이를 호출하는 구조는 ADR-0002의 "도메인 간 참조는 service 레이어만" 원칙을 도메인 *내부* 협업에도 일관되게 적용한 것이다(하나의 service가 여러 책임을 다 지지 않도록 분리 — conventions.md 금지 패턴 "하나의 service가 여러 도메인을 모두 처리한다"의 취지를 게시물-이미지 책임 분리에도 적용).

## 삭제 처리 (물리 파일 정리)

`CommunityPostService.deletePost` 순서:

1. 게시물 조회·소유권 검증(기존과 동일).
2. `postCommentRepository.deleteByPost_Id(postId)`(기존과 동일).
3. `communityPostImageService.deleteImageIfPresent(post)` — 이 메서드 내부에서: `post.getImage()`가 있으면 `storedFilename`을 변수로 확보 → `communityPostImageRepository.delete(image)`(또는 뒤 4번의 게시물 삭제 시 `ON DELETE CASCADE`로 자동 삭제되므로 명시적 delete 생략 가능 — 명시적으로 지워 트랜잭션 내에서 실패를 조기에 드러낸다) → **DB 삭제 성공 후** `fileStorageService.delete(storedFilename)` 호출.
4. `communityPostRepository.delete(post)`.

- 물리 파일 삭제(`fileStorageService.delete`)는 DB 트랜잭션 커밋과 원자적으로 묶이지 않는다(파일시스템은 트랜잭션에 참여하지 않음) — DB 삭제가 롤백되면 파일 삭제도 실행되지 않도록 순서를 "DB 작업이 트랜잭션 내에서 먼저, 파일 삭제는 그 이후"로 두되, 트랜잭션 커밋 자체가 실패하는 극히 드문 경우 파일만 미리 지워지는 위험이 이론적으로 남는다. 이 프로젝트 규모에서 별도 보상 트랜잭션·아웃박스 패턴을 두는 것은 과설계로 판단하고, `fileStorageService.delete`의 실패를 로그로만 남기는 정도로 마무리한다(spec.md 완료 조건은 "정상 흐름에서 이미지가 제거되는지"까지만 요구한다).

## 테스트 계획

- 단위: `CommunityPostImageServiceTest`(Mockito) — 정상 업로드(형식·크기 통과), 허용하지 않는 형식 400, 빈 파일 400, `resolveImageForPost` 3단계 검증 각각(존재하지 않음 404, 타인 소유 403, 이미 연결됨 400), `deleteImageIfPresent`가 이미지 없는 게시물엔 아무 것도 하지 않는지. `LocalFileStorageServiceTest` — 임시 디렉터리(`@TempDir`)에 저장·로드·삭제 왕복 확인, 삭제 대상 파일이 없어도 예외를 던지지 않는지. `CommunityPostImage` 엔티티 단위 테스트로 `isAssigned()`·`assignToPost` 동작 확인.
- 슬라이스: `CommunityPostImageRepositoryTest`(`@DataJpaTest`, Testcontainers) — 마이그레이션 적용 후 FK·`UNIQUE(post_id)` 제약 확인(같은 게시물에 두 번째 이미지를 연결 시도하면 제약 위반). `CommunityPostImageControllerTest`(`@WebMvcTest`, `MockMultipartFile` 사용) — 업로드 성공/형식 오류/크기 초과 응답 계약, 다운로드 엔드포인트 존재하지 않는 imageId 404. `CommunityPostControllerTest` 보강 — 요청 JSON `imageId` 포함/생략, 응답 JSON `imageId`·`imageUrl` 계약.
- 통합: `@SpringBootTest` + Testcontainers(ADR-0003) — spec.md "완료 조건 COM-006" 4개 시나리오 그대로 구현:
  1. `POST /api/community/posts/images`로 이미지 업로드 → 반환된 `imageId`로 `POST /api/community/posts` 게시물 작성 → 단건 조회 시 `imageUrl` 포함, `GET .../file`로 실제 바이트 다운로드 확인.
  2. 허용하지 않는 형식(`text/plain` 등)·5MB 초과 파일 업로드 시도 시 각각 400 `VALIDATION_ERROR`.
  3. 이미지 첨부 게시물 삭제 후 DB에서 `community_post_images` 행이 사라지고, 저장 디렉터리에서도 물리 파일이 삭제됐는지 확인.
  4. 미첨부 게시물 생성·조회·목록(기존 COM-001 시나리오) 회귀 — `imageId`·`imageUrl` 모두 `null`.
  - 추가로(완료 조건 외 회귀): 타인이 업로드한 `imageId`로 게시물 생성 시도 403, 이미 다른 게시물에 쓰인 `imageId` 재사용 시도 400.

## COM-006 후속: 이미지 저장소를 S3로 전환 (이슈 #330)

### 관련 문서

- 위 "Decision Gate 확정" 절 — "**과설계 금지 — 인터페이스로만 추상화, 클라우드 구현체는 지금 만들지 않는다.** ... `S3FileStorageService` 등은 실제로 필요해지는 시점(운영 배포 논의)에 새로 추가한다"고 명시적으로 미뤄 둔 결정을 실행한다.
- ADR-0020(`ai/adr/0020-managed-service-deployment.md` §결정 3 "업로드 파일은 S3로 옮긴다")이 이 전환의 아키텍처 근거다. PR #329(이슈 #326)가 `dev`에 머지됐고 이 작업 브랜치는 그 위로 리베이스된 상태다.
- ADR-0002(레이어드, 도메인 간 참조는 service 레이어만) — 이번 변경은 `community.storage` 패키지 내부 구현체 교체이므로 해당 없음(위반 없음).
- ADR-0004(Flyway 마이그레이션) — **이번 작업은 스키마 변경이 없다.** `community_post_images` 테이블·`stored_filename` 컬럼 의미는 그대로다(저장 위치만 바뀐다). 신규 `V*` 마이그레이션 파일을 만들지 않는다.
- PR #329 "남은 위험/후속" 절, 이슈 #330 본문.

### 설계 원칙 — 인터페이스는 바꾸지 않는다

`FileStorageService`(`store`/`load`/`delete`)는 COM-006 설계 시점에 이미 "바이트 스트림 입출력, 저장소 종속 경로 개념 노출 안 함"을 목표로 만들어졌다. 이번 작업은 그 경계를 검증하는 작업이지, 다시 설계하는 작업이 아니다 — 시그니처를 바꾸지 않고 구현체 `S3FileStorageService` 하나를 추가한다. `CommunityPostImageService`·`CommunityPostImageController`·`CommunityPost` 엔티티·DTO는 전혀 건드리지 않는다.

### 프로파일 분기

코드베이스에 이미 있는 "실 서비스는 `prod`, 나머지는 `!prod`" 패턴(`ResendEmailSender`/`FakeEmailSender`, `KakaoOAuth*`/`FakeOAuth*`, `DartDisclosureCollector`/`FakeDisclosureCollector`)을 그대로 따른다 — 새 조건식을 발명하지 않는다.

```java
// LocalFileStorageService
@Profile("!prod")
@Service
public class LocalFileStorageService implements FileStorageService { ... }

// S3FileStorageService (신규)
@Profile("prod")
@Service
public class S3FileStorageService implements FileStorageService { ... }
```

- `crypto-real`·`oauth-real`·`news-real`처럼 로컬에서 실 서비스를 켜보는 보조 프로파일(`s3-real` 등)은 **만들지 않는다.** 저 프로파일들은 "로컬에서 외부 API 실호출을 확인하고 싶다"는 반복 수요가 있던 도메인(코인 시세·OAuth·뉴스)에만 생겼다. 이미지 저장소는 `LocalFileStorageServiceTest`(`@TempDir`)로 로컬 동작을 이미 충분히 검증하고 있고, S3 쪽은 아래 "테스트 계획"의 mock 단위 테스트로 커버한다 — 새 프로파일을 추가하면 관리할 조합만 늘어난다(과설계 금지).

### AWS SDK 의존성

AWS SDK for Java **v2**(`software.amazon.awssdk`)를 쓴다 — v1(`com.amazonaws:aws-java-sdk-*`)은 유지보수 모드로 신규 도입 대상이 아니다. `build.gradle`에 BOM으로 버전을 고정한다:

```groovy
dependencies {
    implementation platform('software.amazon.awssdk:bom:<BOM 최신 안정 버전>')
    implementation 'software.amazon.awssdk:s3'
}
```

- 정확한 BOM 버전은 구현 시점에 Maven Central에서 Boot 4.1/Java 17과 충돌 없는 최신 안정판을 implementer가 확인해 고정한다(이 문서는 설계 시점이라 버전을 못박지 않는다 — `spring-ai-starter-model-openai:2.0.0` 도입 때처럼 호환성 확인 후 확정하는 절차를 그대로 따른다).
- `software.amazon.awssdk:s3` 하나만 추가한다. `s3-transfer-manager` 등 상위 편의 모듈은 이번 규모(파일 1개 업로드/다운로드/삭제, 대용량·병렬 전송 요구 없음)에 과설계다.

### 자격 증명·리전 — 시크릿을 새로 만들지 않는다

- **정적 액세스 키를 코드·설정·환경변수 어디에도 두지 않는다.** `S3Client`는 인자 없이 `S3Client.builder().build()`로 생성해 AWS SDK 기본 자격 증명 체인(`DefaultCredentialsProvider`)에 맡긴다 — EC2 인스턴스에 붙는 **IAM 인스턴스 프로파일 역할**이 배포 환경의 자격 증명이 된다. 이슈 #330 본문의 "시크릿인 접근키는 `.env`/환경변수로만"이라는 전제 자체를 없애는 선택이다: RDS 마스터 비밀번호 로테이션이 필요했던 사례(PR #329 "남은 위험")처럼 정적 키를 도입하면 그 키도 로테이션 대상이 되므로, 로테이션할 시크릿을 아예 만들지 않는 편이 이 팀 규모에 맞는다.
  - IAM 역할에는 대상 버킷 한정 `s3:GetObject`·`s3:PutObject`·`s3:DeleteObject` 권한만 부여한다(최소 권한). 역할 생성·EC2 인스턴스 프로파일 연결은 AWS 콘솔 작업이라 코드 범위 밖이다 — ADR-0020이 보안 그룹을 다룬 것과 같은 방식으로 `deploy/README.md`에 체크리스트로 남긴다(아래 "배포 문서 갱신").
  - 리전은 커스텀 설정 키를 새로 만들지 않고 SDK 기본 리전 프로바이더 체인(`AWS_REGION` 표준 환경변수 또는 인스턴스 메타데이터)에 맡긴다 — `DB_URL`·`REDIS_HOST`처럼 이 프로젝트 전용 접두 환경변수를 또 만들 이유가 없다(과설계 금지, "이미 있는 관례를 재사용" 원칙).
- 버킷 이름만 이 프로젝트 설정으로 관리한다. **시크릿이 아니다**(존재를 알아도 IAM 권한 없이는 접근 불가) — `application-prod.yml`의 기존 fail-fast 관례(`DB_URL`처럼 기본값 없이 환경변수 참조, 누락 시 기동 실패)를 그대로 따른다.

```yaml
# application-prod.yml 추가
finplay:
  community:
    image-storage:
      s3:
        bucket: ${COMMUNITY_S3_BUCKET}
```

- `.env.example`에 `COMMUNITY_S3_BUCKET=`을 "배포(prod 프로필)에서만 필요" 절에 추가하고, 버킷은 코드가 아니라 AWS 콘솔에서 미리 만들어야 함을 주석으로 남긴다(`DB_URL` 항목의 "compose가 덮어쓴다" 식 안내와 같은 톤).
- `application.yml`(공통)의 기존 `finplay.community.image-storage.base-directory`는 그대로 둔다 — `!prod`(로컬·테스트)에서 `LocalFileStorageService`만 이 값을 읽으므로 삭제할 이유가 없다.

### `S3FileStorageService` 설계

```java
// community.storage 패키지, S3FileStorageService.java
@Profile("prod")
@Service
public class S3FileStorageService implements FileStorageService {

    private final S3Client s3Client;
    private final String bucket;

    public S3FileStorageService(
        S3Client s3Client,
        @Value("${finplay.community.image-storage.s3.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }
    // store/load/delete는 아래 서술대로 구현
}
```

- `S3Client`는 `S3FileStorageService`가 직접 `S3Client.create()`로 만들지, `@Configuration` 클래스가 `@Bean`으로 노출할지는 implementer 재량이다 — 다만 `LocalFileStorageService`가 `@Value` 필드를 생성자로 손으로 받는 이유(Lombok이 `@Value`를 생성자 파라미터로 복사하지 않음, `ai/agent-mistakes.md` 2026-07-30)와 같은 함정이 여기도 적용되므로 `@RequiredArgsConstructor`를 쓰지 않는다.
- `store(MultipartFile file, String storedFilename)`: `PutObjectRequest.builder().bucket(bucket).key(storedFilename).contentType(file.getContentType()).build()`와 `RequestBody.fromInputStream(file.getInputStream(), file.getSize())`로 업로드. 업로드 실패(`S3Exception`, `IOException`)는 `LocalFileStorageService.store`와 동일하게 `BusinessException(ErrorCode.INTERNAL_ERROR, "이미지 저장에 실패했습니다.")`로 감싼다(호출부 `CommunityPostImageService`가 저장소 구현 세부사항을 모르게 하는 기존 계약 유지).
- `load(String storedFilename)`: `GetObjectRequest`로 `ResponseInputStream<GetObjectResponse>`를 받아 `Resource`로 감싼다. 존재하지 않으면 SDK가 `NoSuchKeyException`을 던지는데, 이를 잡아 `LocalFileStorageService.load`와 동일하게 `BusinessException(ErrorCode.NOT_FOUND)`로 변환한다(호출부 `CommunityPostImageService.loadImageFile`이 저장소 종류와 무관하게 같은 예외 계약을 받는다). `InputStreamResource`를 그대로 쓰면 `contentLength()`가 정의되지 않아 `CommunityPostImageController`의 `ResponseEntity<Resource>` 직렬화 시 `Content-Length` 헤더가 빠질 수 있으므로, `GetObjectResponse.contentLength()`를 오버라이드한 얇은 `InputStreamResource` 서브클래스를 두거나 동급 처리를 한다.
- `delete(String storedFilename)`: `DeleteObjectRequest`로 삭제. `LocalFileStorageService.delete`와 같은 계약(best-effort, 실패해도 예외를 던지지 않고 `log.warn`만 남긴다) — 존재하지 않는 키를 지워도 S3는 오류를 던지지 않으므로 별도 존재 확인이 필요 없다(로컬 구현의 `deleteIfExists`와 동등한 동작이 기본으로 보장된다).
- `LocalFileStorageService`의 `resolveWithinBaseDirectory`(경로 탈출 방지, PR #269 리뷰)에 대응하는 방어는 S3에는 필요 없다 — `storedFilename`이 S3 객체 키가 될 뿐 파일시스템 경로로 해석되지 않으므로 `../` 같은 값이 들어와도 디렉터리 탈출이 성립하지 않는다. 다만 `CommunityPostImageService`가 `storedFilename`을 여전히 `UUID + 서버 결정 확장자`로만 생성하는 기존 규칙은 그대로 유지한다(키 이름 예측·충돌 방지 목적은 저장소와 무관하게 유효).

### 기존 로컬 데이터 이관 방안

- **DB 스키마·`stored_filename` 규칙은 바뀌지 않는다** — `CommunityPostImage.storedFilename`은 지금도 저장소 위치 정보를 담지 않는 순수 키(`UUID+확장자`)이므로, 이관은 "같은 키로 바이트를 로컬 디스크에서 S3로 복사"하는 것만으로 끝난다. DB 마이그레이션·엔티티 변경이 필요 없다(ADR-0004와 충돌 없음 — 애초에 스키마 변경 대상이 아니다).
- 이관 대상 데이터가 있는지 먼저 확인한다: 이 프로젝트는 아직 실사용자 트래픽 이전 단계이고(ADR-0020 "후속" 절, RDS Single-AZ를 "실사용자 트래픽 붙는 시점까지 보류"라고 명시), PR #329가 배포한 EC2가 블루-그린 전환 전 유일한 배포 스택이다. 그 인스턴스의 `${COMMUNITY_IMAGE_STORAGE_DIR}`(compose.deploy.yaml 기준 컨테이너 내부 `./data/community-images`)에 실제로 파일이 있는지 SSH로 확인하는 것이 이관 절차의 0단계다.
- **파일이 없거나 소수(운영 검증용 테스트 데이터 수준)면**: 별도 이관 스크립트를 만들지 않는다. 배포 전환(블루-그린 첫 그린 스택을 S3 프로필로 띄우는 시점) 후 기존 데이터는 폐기하고, 재현이 필요하면 사용자가 이미지를 다시 업로드하게 안내한다 — 이 팀 규모에서 1회성 이관 자동화 코드를 만드는 비용이 더 크다(spec 022 전반의 과설계 금지 원칙과 동일 판단).
- **파일이 실사용 데이터 수준으로 있으면**: 애플리케이션 코드에 넣지 않는 1회성 운영 스크립트(예: `aws s3 sync`)로 처리한다.
  ```bash
  # EC2 인스턴스 위에서, 블루-그린 전환 직전 1회 실행
  aws s3 sync /path/to/mounted/data/community-images s3://${COMMUNITY_S3_BUCKET}/ \
    --exclude "*" --include "*.jpg" --include "*.png" --include "*.webp"
  ```
  - `aws s3 sync`가 각 파일을 로컬 파일명(=`stored_filename`)을 그대로 S3 키로 사용하므로 DB의 `stored_filename` 값과 키가 자동으로 일치한다 — 애플리케이션 배포(S3 프로필 전환)는 이 동기화가 끝난 뒤에만 트래픽을 넘긴다(블루-그린 헬스체크 통과 조건에 "이관 완료 확인"을 사람이 체크하는 절차로 추가, 자동화하지 않는다).
  - 이 스크립트와 절차는 코드 저장소가 아니라 `deploy/README.md`에 문서로만 남긴다(1회성 운영 작업이므로 `src/`에 배치 코드로 만들지 않는다 — 과설계 금지).
- 이관 여부와 관계없이 **`compose.deploy.yaml`의 `app` 서비스에 이미지 볼륨을 새로 붙이지 않는다** — ADR-0020 §결정 3이 이미 이 판단을 명시했다("볼륨 마운트를 추가해 고치지 않는다 — 볼륨을 붙이면 그 파일이 다시 인스턴스에 묶여 이 ADR의 목적을 되돌린다").

### 설정 항목 요약 (신규/변경)

| 파일 | 변경 |
|---|---|
| `build.gradle` | `software.amazon.awssdk:bom` platform + `software.amazon.awssdk:s3` 추가 |
| `application-prod.yml` | `finplay.community.image-storage.s3.bucket: ${COMMUNITY_S3_BUCKET}` 추가(기본값 없음, fail-fast) |
| `.env.example` | "배포(prod 프로필)에서만 필요" 절에 `COMMUNITY_S3_BUCKET=` 추가, 버킷 사전 생성 안내 주석 |
| `deploy/README.md` | S3 버킷 생성(퍼블릭 액세스 차단 유지 — 이미지는 앱의 다운로드 엔드포인트로만 노출되고 버킷을 직접 공개하지 않는다), IAM 역할·최소 권한 정책, EC2 인스턴스 프로파일 연결 체크리스트 추가(ADR-0020이 RDS·ElastiCache 콘솔 설정을 남긴 것과 같은 형식). 이관 스크립트(`aws s3 sync`) 절차 포함 |
| `ai/adr/0020-managed-service-deployment.md` | 이미 "S3FileStorageService 구현·이관은 별도 이슈"라고 후속을 명시해 뒀으므로 **내용 수정은 필요 없다.** ADR은 새 번호로 대체(superseded)하는 것 외에 고치지 않는다(CLAUDE.md 규칙2) — 이번 구현 완료는 이 spec의 plan.md와 `ai/prd.md` §3에 기록한다. |
| `ai/specs/022-community-enhancement/plan.md` (이 문서) | 위 "Decision Gate 확정" 절의 "지금은 만들지 않는다" 문장이 실행 완료됐음을 별도 각주로 표기(구현 완료 커밋에서, PR #329가 남긴 화살표 각주 바로 아래에 이어 적는다) |
| `ai/prd.md` §3 | "커뮤니티 고도화" 행 근거에 이슈 #330/이 PR 번호 추가(기능 제공 범위 자체는 바뀌지 않음 — 저장 위치만 바뀌므로 CLAUDE.md 규칙10 "갱신 비대상"에 해당할 수 있다. 다만 §3가 이미 "완료(COM-004~006)"로 적혀 있고 그 각주가 "로컬 파일시스템"을 함의하지 않으므로, 근거 칸에 이슈 번호만 추가하고 판정 문구는 바꾸지 않는 것으로 충분하다 — 최종 판단은 implementer가 실제 diff를 보고 내린다) |

### 테스트 계획

- 단위: `S3FileStorageServiceTest`(Mockito, `S3Client` mock) — `store` 정상 호출 시 `PutObjectRequest`에 올바른 bucket/key/contentType이 실리는지, S3 예외 발생 시 `BusinessException(INTERNAL_ERROR)`로 변환되는지. `load` 정상 시 `Resource` 반환, `NoSuchKeyException` 시 `BusinessException(NOT_FOUND)`로 변환되는지. `delete` 정상 호출 검증, 예외 발생 시 던지지 않고 로그만(호출 자체는 검증 가능해도 예외 전파는 없음을 확인).
- **Testcontainers 기반 실 S3 호환 통합 테스트는 이번 그룹에 추가하지 않는다.** ADR-0003이 정의한 통합 테스트 스택은 MySQL Testcontainers 하나뿐이고, LocalStack 등 S3 호환 모듈은 이 저장소에 전례가 없다 — 이슈 #330 완료 조건의 "실 S3(or 호환 목) 통합 검증"은 위 mock 단위 테스트로 충족하는 것으로 판단한다(전례 없는 새 테스트 인프라를 이 크기의 변경 하나를 위해 들이는 것은 과설계). 이 판단이 리뷰에서 부족하다고 지적되면 LocalStack Testcontainers 모듈 도입을 별도로 검토한다.
- 배포 검증(자동화 테스트 범위 밖, 운영 확인): PR #329가 남긴 실배포 검증 방식과 동일하게, 블루-그린 전환 시 EC2에서 실제 업로드→다운로드→삭제 왕복을 한 번 수동 확인하고 `deploy/README.md`에 결과를 남긴다(이슈 #330 완료 조건 "컨테이너 재생성에도 첨부 이미지가 유실되지 않음을 확인").
- 회귀: 기존 `LocalFileStorageServiceTest`·`CommunityPostImageServiceTest`·`CommunityPostImageControllerTest`·COM-006 통합 테스트는 변경하지 않는다(인터페이스가 그대로이므로 `!prod` 경로 동작은 영향받지 않는다) — `./gradlew build`로 회귀 없음을 확인한다.
