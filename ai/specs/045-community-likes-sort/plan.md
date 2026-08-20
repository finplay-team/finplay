# Plan: 커뮤니티 좋아요·인기순 정렬

## 관련 문서

- Spec: `./spec.md`
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(레이어드, 도메인 간 참조는 service 레이어만), [ADR-0004](../../adr/0004-flyway-migrations.md)(신규 테이블·컬럼은 Flyway로만, 머지된 마이그레이션은 수정하지 않고 새 버전 추가)
- 선행 구현 참고
  - `022-community-enhancement`: `CommunityPost`(`author`·`instrument`·`image` 연관, protected 생성자 + 정적 팩토리), `CommunityPostResponse.from(entity)` 패턴, COM-006의 "게시물 삭제 시 연관 데이터 함께 제거" 선례, `CommunityPostRepositoryImpl`의 QueryDSL 목록 조회·페이지네이션 구조.

## 기존 코드 현황 (계획 시점 확인한 사실)

- `CommunityPost`(`src/main/java/com/finplay/api/community/domain/CommunityPost.java`)는 `author`·`instrument`·`image`·`title`·`content`·`createdAt`·`updatedAt`만 가진다. 좋아요 관련 필드·연관이 없다.
- `CommunityPostResponse.from(CommunityPost post)`는 인자를 엔티티 하나만 받는다 — 요청자(viewer) 컨텍스트를 모른다. `likedByMe`를 추가하려면 이 팩토리에 인증 사용자 ID(또는 그 결과인 boolean)를 추가로 넘겨야 한다.
- `CommunityPostController.getPost`·`getPosts`는 현재 `@AuthenticationPrincipal`을 전혀 받지 않는다 — 인증은 필터 단에서 걸리지만 컨트롤러가 principal을 쓰지 않았다. `likedByMe`를 위해 두 메서드 모두 `@AuthenticationPrincipal AuthenticatedUser principal`을 추가해야 한다(기존 `createPost`/`updatePost`/`deletePost`가 이미 쓰는 패턴 그대로 재사용).
- `CommunityPostRepositoryImpl.findPostsOrderByCreatedAtDesc(Pageable, Long instrumentId)`는 이름 자체가 "생성일 내림차순"을 박아뒀다 — 인기순을 추가하면 이 이름이 더 이상 정확하지 않으므로 `findPosts(Pageable, Long instrumentId, String sort)`로 이름·시그니처를 바꾼다(호출부는 `CommunityPostService.getPosts` 한 곳뿐).
- 착수 시점 `dev` 최신은 `V38`이었다. `V39`·`V40`은 착수 시점에 이미 다른 PR이 선점해 사용 중이었으나 그 PR이 나중에 `V43`·`V44`로 번호를 옮겨 결번이 됐다 — 재사용하지 않는다. 착수 시점에 `ls src/main/resources/db/migration`으로 실제 최신 번호를 확인하고 그 다음 빈 번호부터 쓴다(specs/README.md 번호 규칙). 이 spec은 다음 빈 번호인 **`V41`**부터 썼다.
- `ErrorCode`에 좋아요 전용 코드는 없다 — `NOT_FOUND`(게시물 없음)·`VALIDATION_ERROR`(잘못된 정렬 값)만으로 충분하다. 신규 `ErrorCode` 추가 없음.

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | /api/community/posts/{postId}/likes | - | `CommunityPostLikeResponse` | 인증 사용자가 게시물에 좋아요 표시. 신규 생성 시 201, 이미 좋아요한 상태면 200(현재 상태 그대로 반환, 멱등). 본인 게시물도 허용. `postId` 미존재 404 `NOT_FOUND` |
| DELETE | /api/community/posts/{postId}/likes | - | - | 좋아요 취소(204, 본문 없음). 좋아요한 적 없어도 오류 없이 204(멱등). `postId` 미존재 404 `NOT_FOUND` |
| GET | /api/community/posts?page=&size=&instrumentId=&sort= | - | `CommunityPostListResponse`(항목에 필드 추가) | `sort=latest`(기본값, 생략 시 기존과 동일)\|`popular`. `popular`는 `likeCount desc, createdAt desc, id desc`. 그 외 값은 400 `VALIDATION_ERROR`. `instrumentId`와 함께 사용 가능 |
| GET | /api/community/posts/{postId} | - | `CommunityPostResponse`(필드 추가) | `likeCount`·`likedByMe` 필드 추가 |

**멱등성**: 좋아요 표시(POST)를 이미 표시한 상태에서 다시 호출하면 새 오류 코드를 만들지 않고 현재 상태(`CommunityPostLikeResponse`)를 그대로 200으로 반환한다 — 새 오류 코드 비용 대비 클라이언트가 구분해야 할 실사용 요구가 없다는 판단이며, COM-005 tombstone 재삭제 멱등 결정과도 같은 계열이다. 신규 생성 시에는 201을 유지한다.

## 입력 명세

### `POST /api/community/posts/{postId}/likes`

| 필드 | 필수 | 검증 |
|---|---|---|
| postId (path) | 필수 | 존재하지 않으면 404 `NOT_FOUND`. 본인 소유 게시물이어도 거부하지 않는다(비즈니스 규칙 — 본인 게시물 좋아요 허용) |

### `DELETE /api/community/posts/{postId}/likes`

| 필드 | 필수 | 검증 |
|---|---|---|
| postId (path) | 필수 | 존재하지 않으면 404 `NOT_FOUND`. 좋아요한 적 없어도 오류 없이 204 |

### `GET /api/community/posts` 쿼리 파라미터 추가분

| 필드 | 필수 | 검증 |
|---|---|---|
| sort | 선택 | 생략 시 `latest`. `latest` 또는 `popular` 외 값이면 400 `VALIDATION_ERROR`("sort는 latest 또는 popular만 지정할 수 있습니다.") — 기존 `validatePageAndSize`와 같은 방식으로 컨트롤러가 형식을 검증한다(포맷 검증이지 비즈니스 판단이 아니므로 컨벤션상 컨트롤러 책임) |

## 데이터 모델

### `V41__create_community_post_likes_and_like_count.sql`

```sql
-- 게시물 좋아요(회원×게시물 유일)를 저장하고, 인기순 정렬을 위해
-- 게시물에 좋아요 수 비정규화 컬럼을 추가한다.

CREATE TABLE community_post_likes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_community_post_likes_post
        FOREIGN KEY (post_id) REFERENCES community_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_community_post_likes_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_community_post_likes_post_user UNIQUE (post_id, user_id)
);

ALTER TABLE community_posts
    ADD COLUMN like_count BIGINT NOT NULL DEFAULT 0 AFTER content,
    ADD INDEX idx_community_posts_like_count_created (like_count, created_at, id);
```

- `post_id`는 `ON DELETE CASCADE` — 게시물 삭제 시 좋아요도 함께 제거한다(spec.md 비즈니스 규칙, COM-006과 동일 원칙).
- `uk_community_post_likes_post_user`가 "회원당 게시물 1개당 최대 1회" 제약을 DB 레벨에서 강제한다. 취소(DELETE)는 이 행을 실제로 지운다 — tombstone처럼 원문 보존이 필요한 대상이 아니다(COM-005 대댓글과 달리 "누가 좋아요했었는지" 이력을 남길 요구가 spec에 없다).
- `community_posts.like_count`는 **비정규화 카운터**로 둔다(매 조회마다 `COUNT(*)` 서브쿼리를 하지 않는다). 트레이드오프:
  - **COUNT 방식(채택 안 함)**: 정합성이 항상 보장되지만, 인기순 목록 조회마다 정렬·페이지네이션에 집계(`GROUP BY` 또는 상관 서브쿼리)가 필요해 `CommunityPostRepositoryImpl`의 기존 offset 페이지네이션 구조와 맞물리기 복잡해지고, 게시물이 많아질수록 목록 조회 비용이 커진다.
  - **비정규화 카운터(채택)**: `ORDER BY like_count`가 단순 컬럼 정렬이라 위 복합 인덱스로 바로 처리된다. 대신 카운터 갱신이 게시물 행 갱신과 별도 시점에 일어나므로 드리프트 가능성이 이론상 있다 — 아래 "동시성" 항목에서 그 위험을 원자적 UPDATE로 좁힌다.
  - 커뮤니티 게시물 조회는 이 프로젝트에서 가장 자주 호출되는 목록 API 중 하나이고 정렬 기준으로 즉시 쓰이므로, 조회 비용을 낮추는 비정규화 쪽이 이 spec의 목적(인기순 정렬)에 더 맞는다고 판단했다.
- **동시성**: `like_count` 증감은 엔티티를 읽어 `+1`/`-1` 한 뒤 dirty checking으로 반영하지 않는다 — 같은 게시물에 여러 요청이 동시에 들어오면 lost update가 발생할 수 있다. 대신 `CommunityPostRepository`에 원자적 `UPDATE community_posts SET like_count = like_count + 1 WHERE id = :postId` 형태의 `@Modifying @Query` 메서드를 둔다(증가·감소 각각). 별도 락(비관적 락, `@Version` 낙관적 락)은 추가하지 않는다 — 좋아요 개수는 현금·보유수량 같은 원장 데이터가 아니라 정렬용 지표라 잠깐의 경합보다 구현 단순성을 우선했다(C-002 최소 구현). 감소 쿼리에 `WHERE like_count > 0` 같은 하한 가드는 두지 않는다 — 감소는 항상 같은 트랜잭션에서 좋아요 행 존재를 먼저 확인한 뒤에만 실행되므로 음수로 내려갈 경로가 없다.
  - **정정 (PR #442 2차 리뷰, 실측으로 반박됨)**: 위 두 판단은 틀렸다. 원자적 UPDATE만으로는 부족했다 — 같은 게시물 동시 좋아요가 유니크 인덱스와 게시물 행 락을 엇갈린 순서로 잡아 InnoDB 데드락(`CannotAcquireLockException`, SQLState 40001)으로 500이 났고(재현 4/4), 동시 취소는 두 요청이 각자 좋아요 행 존재를 확인한 뒤 감소 쿼리를 실행해 `like_count`를 -1까지 떨어뜨렸다. 따라서 `CommunityPostRepository.findByIdForUpdate`(`SELECT ... FOR UPDATE`)를 추가해 `likePost`·`unlikePost` 양쪽이 트랜잭션 첫 문장으로 게시물 행을 잡아 락 획득 순서를 통일하고, 감소 쿼리에 `like_count > 0` 하한 가드를 방어 심층화로 둔다. 데드락은 InnoDB가 트랜잭션을 이미 롤백한 뒤 던지므로 catch로 사후 수습할 수 없다.
- CHECK 제약은 두지 않는다(코드베이스 전례 없음 — 022와 동일 판단).

### 엔티티 변경

**`CommunityPost`**: `likeCount`(`long`, `like_count` 컬럼) 필드 추가. 생성자·팩토리는 그대로 두고 필드만 추가한다 — 생성 시 항상 0이므로(자바 `long` 기본값) `create` 팩토리 시그니처를 바꾸지 않는다.

```java
@Column(name = "like_count", nullable = false)
private long likeCount;
```

**`CommunityPostLike`**(신규 엔티티, `community.domain`):

```java
@Entity
@Table(name = "community_post_likes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommunityPostLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    private CommunityPost post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private CommunityPostLike(CommunityPost post, User user, LocalDateTime createdAt) {
        this.post = post;
        this.user = user;
        this.createdAt = createdAt;
    }

    public static CommunityPostLike create(CommunityPost post, User user, LocalDateTime now) {
        return new CommunityPostLike(post, user, now);
    }
}
```

## 패키지·클래스 설계

| 클래스 | 패키지 | 변경 |
|---|---|---|
| `CommunityPostLike` | `community.domain` | 신규 엔티티(위) |
| `CommunityPost` | `community.domain` | `likeCount` 필드 추가(위) |
| `CommunityPostLikeRepository` | `community.repository` | 신규. `existsByPost_IdAndUser_Id`, `Optional<CommunityPostLike> findByPost_IdAndUser_Id`, 배치 조회 `@Query("SELECT l.post.id FROM CommunityPostLike l WHERE l.user.id = :userId AND l.post.id IN :postIds") List<Long> findLikedPostIds(Long userId, List<Long> postIds)`(목록 응답의 `likedByMe` N+1 방지용) |
| `CommunityPostRepository` | `community.repository` | 원자적 증감 메서드 추가 — `@Modifying @Query("UPDATE CommunityPost p SET p.likeCount = p.likeCount + 1 WHERE p.id = :postId") void incrementLikeCount(Long postId)`, 대칭되는 `decrementLikeCount` |
| `CommunityPostRepositoryCustom`/`Impl` | `community.repository` | `findPostsOrderByCreatedAtDesc(Pageable, Long instrumentId)` → `findPosts(Pageable pageable, Long instrumentId, String sort)`로 시그니처 변경. `"popular".equals(sort)`이면 `post.likeCount.desc(), post.createdAt.desc(), post.id.desc()`, 아니면 기존 `post.createdAt.desc(), post.id.desc()`로 `orderBy` 분기 |
| `CommunityPostLikeService` | `community.service` | 신규. `likePost(Long postId, Long authenticatedUserId)` — 게시물 조회(404), `communityPostLikeRepository.existsByPost_IdAndUser_Id`로 기존 상태 확인 후 있으면 현재 상태 반환(200 판단은 컨트롤러), 없으면 `CommunityPostLike.create` 저장 + `communityPostRepository.incrementLikeCount(postId)` 호출 후 신규 상태 반환(201 판단은 컨트롤러). `unlikePost(Long postId, Long authenticatedUserId)` — 게시물 조회(404), 좋아요 행 있으면 삭제 + `decrementLikeCount`, 없으면 그대로 반환(204, no-op) |
| `CommunityPostLikeController` | `community.controller` | 신규. `POST`/`DELETE /api/community/posts/{postId}/likes` — `CommentController`(삭제 전용 분리 컨트롤러)·`CommunityPostImageController`와 같은 "부속 리소스는 별도 컨트롤러" 패턴을 따른다 |
| `CommunityPostLikeResponse` | `community.dto.response` | 신규 record — `Long postId, long likeCount, boolean likedByMe` |
| `CommunityPostResponse` | `community.dto.response` | `likeCount`(long)·`likedByMe`(boolean) 필드 추가. 팩토리를 `from(CommunityPost post, boolean likedByMe)`로 변경(단일 팩토리, 기존 `from(post)` 오버로드는 남기지 않는다 — 모든 호출부에서 실제 좋아요 상태를 명시적으로 넘기게 해 "좋아요 여부를 깜빡하고 false로 하드코딩"하는 실수를 막는다) |
| `CommunityPostListResponse` | `community.dto.response` | `from(Page<CommunityPost> page, Set<Long> likedPostIds)`로 팩토리 변경 — 스트림 매핑 시 `likedPostIds.contains(post.getId())`로 `likedByMe` 결정 |
| `CommunityPostService` | `community.service` | `getPost(Long postId, Long authenticatedUserId)`로 파라미터 추가, 내부에서 `communityPostLikeRepository.existsByPost_IdAndUser_Id` 호출 후 `CommunityPostResponse.from(post, likedByMe)`. `getPosts(int page, int size, Long instrumentId, String sort, Long authenticatedUserId)`로 확장 — 조회된 페이지의 postId 목록을 모아 `findLikedPostIds`로 한 번에 조회한 뒤 `Set`으로 변환해 `CommunityPostListResponse.from`에 전달. `createPost`/`updatePost`도 각각 실제 존재 여부를 조회해 `CommunityPostResponse.from(post, likedByMe)`를 반환한다(신규 생성 직후에는 항상 `false`가 나오지만, 특수 분기를 두지 않고 동일한 조회 경로를 타 일관성을 유지한다) |
| `CommunityPostController` | `community.controller` | `getPost`·`getPosts`에 `@AuthenticationPrincipal AuthenticatedUser principal` 추가(기존 `createPost` 등과 동일 패턴). `getPosts`에 `@RequestParam(defaultValue = "latest") String sort` 추가, `validatePageAndSize` 옆에 `validateSort` 검증 추가(또는 같은 메서드로 통합 — 구현 시점에 가독성 기준으로 택일) |

**`sort`를 enum이 아닌 `String`으로 그대로 흘려보내는 이유**: 값이 `latest`/`popular` 두 개뿐이라 전용 enum을 만들면 이 spec 하나에서만 쓰는 타입이 하나 늘어난다(C-002 "한 번만 쓰는 추상화는 만들지 않는다"). 컨트롤러가 허용값 검증을 하고 나면 서비스·리포지토리는 그 값을 그대로 분기 조건으로만 쓴다. 정렬 종류가 3개 이상으로 늘어나는 시점이 오면 그때 enum 도입을 재검토한다.

## 테스트 계획

- 단위
  - `CommunityPostLikeServiceTest`(Mockito): 신규 좋아요 표시 시 저장·증가 호출, 이미 좋아요한 상태에서 재요청 시 저장·증가 호출 없이 현재 상태 반환(멱등), 취소 시 삭제·감소 호출, 좋아요한 적 없는 상태에서 취소 시 삭제·감소 호출 없음(no-op), 존재하지 않는 게시물에 좋아요/취소 시도 시 404 전파, 본인 게시물 좋아요가 차단되지 않는지(별도 검증 로직이 없음을 확인하는 회귀).
  - `CommunityPostServiceTest`(기존 파일 확장): `getPost`/`getPosts`/`createPost`/`updatePost`가 `likeCount`·`likedByMe`를 올바르게 반영하는지, `getPosts`가 `sort` 값에 따라 리포지토리에 올바른 인자를 전달하는지.
- 슬라이스
  - `CommunityPostLikeRepositoryTest`(`@DataJpaTest`, Testcontainers): `uk_community_post_likes_post_user` 유니크 제약, 게시물 삭제 시 `ON DELETE CASCADE`로 좋아요도 함께 삭제되는지, `findLikedPostIds` 배치 조회가 여러 postId 중 실제로 좋아요한 것만 반환하는지.
  - `CommunityPostRepositoryTest`(`@DataJpaTest`): `incrementLikeCount`/`decrementLikeCount`가 원자적으로 반영되는지, `findPosts(pageable, instrumentId, "popular")`가 좋아요 수 내림차순(동률은 최신순)으로 반환하는지, `sort` 생략(`"latest"`)이 기존 `findPostsOrderByCreatedAtDesc`와 동일한 순서를 반환하는지(회귀).
  - `CommunityPostLikeControllerTest`(`@WebMvcTest`): `POST` 신규 생성 시 201, 재요청 시 200, `DELETE` 204(존재/미존재 모두), 미존재 게시물 404.
  - `CommunityPostControllerTest`(기존 파일 확장): `sort` 쿼리 파라미터 전달, 잘못된 `sort` 값 400, 응답 JSON에 `likeCount`·`likedByMe` 필드 계약.
- 통합 (`@SpringBootTest` + Testcontainers, ADR-0003) — spec.md "완료 조건" 시나리오를 그대로 구현:
  1. 좋아요 표시 → 응답 `likeCount` 증가·`likedByMe` true. 같은 사용자가 재요청 → 상태 불변(멱등, 200).
  2. 좋아요 취소 → `likeCount` 감소·`likedByMe` false. 좋아요한 적 없는 상태에서 취소 → 204(오류 없음).
  3. 본인 게시물에 좋아요 표시 → 정상 처리(차단되지 않음).
  4. 존재하지 않는 게시물에 좋아요/취소 → 404.
  5. 좋아요 수가 다른 게시물 여러 개 생성 → `sort=popular` 조회 시 좋아요 많은 순.
  6. 좋아요 수가 같은 게시물들 → `sort=popular`에서 최신 게시물이 먼저 나옴(동률 처리).
  7. `sort` 생략 → 기존과 동일한 최신순(회귀).
  8. `sort=popular&instrumentId=` 조합 → 해당 종목 게시물만 좋아요 순으로 반환.
  9. 잘못된 `sort` 값 → 400.
  10. 게시물 삭제 → 좋아요 데이터도 함께 제거(리포지토리 레벨에서 고아 행 없음 확인).
