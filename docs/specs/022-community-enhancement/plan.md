# Plan: COM-004 게시물 종목 기준 분류 (이슈 #246)

## 관련 문서

- Spec: `./spec.md` "COM-004 종목 기준 분류" 절, "Decision Gate"(이 그룹 해당 없음), "완료 조건 COM-004"
- 선행 구현: `008-community`(`CommunityPost`·`CommunityPostService`·`CommunityPostController`·`CommunityPostRepository`, COM-001~003), `013-instrument-master`류(`Instrument`·`InstrumentService`·`InstrumentRepository`, MKT-001), `021-watchlist`(`WatchlistService`가 `InstrumentService.getInstrumentEntity`를 거쳐 cross-domain 참조하는 선례)
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(레이어드, 도메인 간 참조는 service 레이어만 — `community`가 `market`의 `InstrumentRepository`를 직접 주입하지 않는다), [ADR-0004](../../adr/0004-flyway-migrations.md)(신규 컬럼은 Flyway로만)
- PRD 근거: `docs/prd.md` COM-004(신설 2차 MVP, 상세는 이 spec이 정본 — spec.md 머리말 참고)

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
| PATCH | /api/community/posts/{postId} | `CommunityPostUpdateRequest`(instrumentId 필드 추가) | `CommunityPostResponse` | 수정 시에도 title·content와 동일하게 매 요청 전체를 교체(부분 패치 아님) — instrumentId를 생략/`null`로 보내면 태그를 해제한다 |
| GET | /api/community/posts?page=&size=&instrumentId= | - | `CommunityPostListResponse`(각 항목에 태그 필드 추가) | `instrumentId` 지정 시 그 종목이 태그된 게시물만, 미지정 시 기존 COM-001과 동일 |
| GET | /api/community/posts/{postId} | - | `CommunityPostResponse` | 응답에 태그 필드 추가 |

- `PATCH`가 title·content처럼 매번 전체를 교체하는 기존 관례를 그대로 따른다 — `instrumentId`만 선택적으로 유지하는 부분 패치 API를 새로 만들지 않는다. 즉 "태그를 그대로 두고 제목만 바꾸고 싶다"면 클라이언트가 기존 `instrumentId`를 그대로 다시 보내야 한다(기존 title·content도 동일한 제약).
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
- PRD 근거: `docs/prd.md` COM-005(신설 2차 MVP, 상세는 이 spec이 정본)

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
  4. (완료 조건 외 회귀) 부모 댓글 삭제 시 그 자식 대댓글도 함께 삭제되는지(`ON DELETE CASCADE`) 확인 — spec.md 완료 조건에 명시적 항목은 없지만 Decision Gate에서 확정한 정책이므로 통합 테스트로 검증한다.

## COM-006 사진 첨부 (이슈 #248)

## 관련 문서

- Spec: `./spec.md` "COM-006 사진 첨부" 절, "Decision Gate"(이미지 저장 방식·허용 형식/크기 — 이 plan에서 확정), "완료 조건 COM-006"
- 선행 구현: `008-community`(`CommunityPost`·`CommunityPostService`·`CommunityPostController`), COM-004(`V24`, `Instrument` cross-domain 참조 패턴), COM-005(`V25`, self-referencing FK + `ON DELETE CASCADE` 패턴) — 다음 마이그레이션 버전은 `V26`
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(레이어드, 도메인 간 참조는 service 레이어만, `common`은 전역 예외·오류 응답·공통 설정만 — 신규 인프라를 성급하게 `common`에 두지 않는다), [ADR-0004](../../adr/0004-flyway-migrations.md)(신규 테이블·컬럼은 Flyway로만)
- PRD 근거: `docs/prd.md` COM-006(신설 2차 MVP, 상세는 이 spec이 정본)

## Decision Gate 확정

### 이미지 저장 방식: 로컬 파일시스템

- 이 프로젝트는 `compose.yaml`에 MySQL·Redis만 정의돼 있고 S3·MinIO 등 객체 스토리지 인프라가 없다. 팀 규모(로컬 개발 중심)에서 새 인프라(오브젝트 스토리지 계정·자격증명·SDK 의존성)를 지금 도입하는 비용이 이 기능이 주는 가치보다 크다 — 로컬 파일시스템 저장을 채택한다.
- **ADR을 새로 만들지 않는다.** ADR은 "아키텍처 수준" 결정(스택 선택, 레이어 구조, 도메인 간 참조 규칙처럼 여러 기능에 걸쳐 반복 적용되는 규칙)을 기록하는 문서다(ADR-0002·0004가 그 예). 이번 결정은 COM-006 한 기능의 구현 세부사항(파일을 어디에 저장하는가)이라 이 plan.md 기록으로 충분하다고 판단한다 — CLAUDE.md 규칙2는 "기존 ADR과 어긋나면 새 ADR"을 요구하지, 모든 인프라 선택에 ADR을 요구하지 않는다. 이 판단이 틀렸다고 리뷰에서 지적되면 그때 ADR로 승격한다.
- **과설계 금지 — 인터페이스로만 추상화, 클라우드 구현체는 지금 만들지 않는다.** `FileStorageService` 인터페이스 + `LocalFileStorageService` 구현체 하나만 둔다. `S3FileStorageService` 등은 실제로 필요해지는 시점(운영 배포 논의)에 새로 추가한다 — 지금은 인터페이스 시그니처가 로컬이 아닌 저장소로도 자연스럽게 구현 가능한 형태(바이트 스트림 입출력, 저장소 종속 경로 개념 노출 안 함)인지만 확인한다.
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

- `stored_filename`은 서비스 계층에서 `UUID.randomUUID()` + 원본 확장자로 생성한다(충돌 방지, 사용자 입력 파일명을 그대로 경로에 쓰지 않아 경로 조작 방지). `original_filename`은 표시·다운로드 시 사용자에게 보여줄 용도로 별도 저장한다(spec.md 힌트 그대로).
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
| `LocalFileStorageService` | `community.storage` | `FileStorageService` 구현체. `finplay.community.image-storage.base-directory`를 `@Value`로 주입받아 `Path`로 다룬다. `store`는 `Files.copy(file.getInputStream(), baseDir.resolve(storedFilename))`, `load`는 `UrlResource`로 파일 반환, `delete`는 `Files.deleteIfExists` — `IOException`은 잡아서 `log.warn`만 남기고 던지지 않는다(삭제 실패로 게시물 삭제 전체가 실패하면 안 됨) |
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
