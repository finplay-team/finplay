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

## COM-005 대댓글 (이슈 #247) — 다음 이슈 착수 시 작성

## COM-006 사진 첨부 (이슈 #248) — 다음 이슈 착수 시 작성
