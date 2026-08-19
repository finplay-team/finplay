# Plan: 커뮤니티 배지 (참여형 + 수익형)

> **⚠️ 철회됨 (2026-08-20, 이슈 #481). 이 계획은 실행하지 않는다.** 사유와 정리 범위는 `spec.md` 머리말 참고.

## 관련 문서

- Spec: `./spec.md`
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(레이어드, 도메인 간 참조는 service/event로만), [ADR-0004](../../adr/0004-flyway-migrations.md)(신규 테이블은 Flyway로만)
- 선행 구현 참고
  - `014-ranking`: `RealizedPnlUpdatedEvent`(`account.event`, accountId만 싣고 리스너가 재조회하는 패턴), `RankingEventListener`(`@TransactionalEventListener(AFTER_COMMIT)`), `AccountService.findByIdOrEmpty` — BADGE-004가 그대로 재사용한다.
  - `022-community-enhancement`: `CommunityPost`(`author` FK, `instrument`·`image` nullable 연관), `CommunityPostResponse.from(entity)` 정적 팩토리 패턴, COM-006의 "이미지 선업로드-후참조"·"게시물 삭제 시 연관 데이터 함께 제거" 선례.
  - `026-market-order-practice-tutorial`/`039-tutorial-flow-redesign`: `PracticeAttempt`(`status`, `complete()` 메서드), `PracticeMarketReflection`(튜토리얼당 1회 완료 복기).

## 기존 코드 현황 (계획 시점 확인한 사실)

- `PracticeAttempt.complete(LocalDateTime)`가 상태를 `COMPLETED`로 바꾸는 유일한 지점이다(`PracticeAttemptRepository`로 `userId`별 `COMPLETED` 카운트 조회 가능, market 컬럼 존재하나 BADGE-001은 시장 합산이므로 필터하지 않는다).
- `PracticeMarketReflection.create(...)`가 복기 생성의 유일한 지점이다. 엔티티 주석대로 "튜토리얼당 1회"라 attempt 완료 여러 번(재시작 포함)에 걸쳐 여러 행이 쌓인다 — `userId`별 전체 카운트가 곧 BADGE-002 기준이다.
- `RealizedPnlUpdatedEvent(Long accountId)`(`com.finplay.api.account.event`)가 매도 체결 후 커밋 시점에 이미 발행되고 있다(014). `Account.getMarket()`·`Account.getRealizedPnl()`로 시장·누적 실현손익을 즉시 얻을 수 있다 — BADGE-004는 신규 이벤트를 만들지 않고 이 이벤트를 그대로 구독한다.
- `CommunityPost.getAuthor()`는 `User` 엔티티(내부 PK 보유)를 반환하지만 `CommunityPostResponse`·`PostCommentResponse`는 `authorNickname`(String)만 노출하고 `userId`를 응답에 싣지 않는다(랭킹과 동일한 최소 노출 원칙, 014 plan.md 33행). 배지 요약을 응답에 실으려면 서비스 계층에서 `author.getId()`로 배치 조회한 뒤 DTO에 반영해야 한다 — 컨트롤러·응답 계약에는 `userId`를 노출하지 않는다.
- "배웠어요" 반응·배지 저장 테이블은 존재하지 않는다(둘 다 신규).
- Flyway 최신 버전은 `V38`(`ls src/main/resources/db/migration` 확인) — 다음 버전은 `V39`.
  - **정정(2026-08-18)**: 작성 후 PR #442(`V41`)가 먼저 `dev`에 머지돼 이 spec의 마이그레이션은 `V43`·`V44`로 올렸다. CI의 마이그레이션 번호 역전 검사가 base(`origin/dev`)의 최고 번호 이하를 막는다. `V42`를 건너뛴 것은 열려 있는 PR #446(`feat/shared-trade-card`)이 이미 그 번호를 쓰고 있어서다 — **번호는 로컬 `dev`만 보지 말고 열려 있는 모든 PR 브랜치를 확인하고 정한다.**

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | /api/community/posts/{postId}/reactions/learned | - | `PostLearnedReactionResponse` | 인증 사용자가 게시물에 "배웠어요" 표시(201). 본인 게시물이면 400 `VALIDATION_ERROR`. 이미 표시했으면 그대로 현재 상태 반환(멱등, 209 재요청 오류 아님 — 아래 "멱등성" 참고) |
| DELETE | /api/community/posts/{postId}/reactions/learned | - | - | 본인이 표시한 "배웠어요" 취소(204). 표시한 적 없어도 오류 없이 204(멱등) |
| GET | /api/badges/me | - | `MyBadgeListResponse` | 인증 사용자 본인의 배지 카테고리별 현재 등급·진행률 조회 |
| GET | /api/community/posts/{postId} | (변경 없음) | `CommunityPostResponse`(필드 추가) | `learnedCount`·`learnedByMe`·`authorBadges` 필드 추가 |
| GET | /api/community/posts?page=&size=&instrumentId= | (변경 없음) | `CommunityPostListResponse`(항목에 필드 추가) | 위와 동일한 필드를 각 항목에 추가 |
| GET | /api/community/posts/{postId}/comments | (변경 없음) | `List<PostCommentResponse>`(필드 추가) | `authorBadges` 필드 추가(댓글은 "배웠어요" 대상이 아니므로 `learnedCount`류는 추가하지 않는다) |

**멱등성**: "배웠어요" 표시(POST)를 이미 표시한 상태에서 다시 호출하면 새 오류 코드를 만들지 않고 기존 상태(`PostLearnedReactionResponse`)를 그대로 200으로 반환한다 — COM-005의 tombstone 재삭제 멱등 결정(022 plan.md 402행)과 같은 판단 기준(새 오류 코드 비용 대비 클라이언트가 구분해야 할 실사용 요구가 없음). 단, 신규 생성 시에는 201을 유지한다(존재 여부에 따라 상태코드가 갈리는 것은 기존 관례에 없으므로, 존재 확인 후 생성이면 201, 이미 존재하면 200으로 구현한다).

## 입력 명세

### `POST /api/community/posts/{postId}/reactions/learned`

| 필드 | 필수 | 검증 |
|---|---|---|
| postId (path) | 필수 | 존재하지 않으면 404 `NOT_FOUND`. 존재하되 요청자 본인 소유 게시물이면 400 `VALIDATION_ERROR`("본인 게시물에는 배웠어요를 표시할 수 없습니다.") |

### `GET /api/badges/me`

요청 파라미터 없음(인증 컨텍스트의 userId만 사용).

## 데이터 모델

### `V43__create_community_post_learned_reactions.sql`

```sql
-- 커뮤니티 게시물에 대한 "배웠어요" 반응(회원당 게시물 1회)을 저장한다.

CREATE TABLE community_post_learned_reactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    post_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT fk_learned_reactions_post
        FOREIGN KEY (post_id) REFERENCES community_posts (id) ON DELETE CASCADE,
    CONSTRAINT fk_learned_reactions_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_learned_reactions_post_user UNIQUE (post_id, user_id)
);
```

- `post_id`는 `ON DELETE CASCADE` — 게시물 삭제 시 반응도 함께 제거한다(COM-006 이미지 삭제와 동일한 "고아 데이터 방지" 원칙).
- `uk_learned_reactions_post_user`가 "회원당 게시물 1회" 제약을 DB 레벨에서 강제한다. 취소(DELETE)는 이 행을 실제로 지운다(soft delete 불필요 — tombstone처럼 원문 보존이 필요한 대상이 아니다).

### `V44__create_member_badges.sql`

```sql
-- 회원별 배지 카테고리마다 현재 달성한 최고 등급 하나만 저장한다(하락 없음, 이력 없음).

CREATE TABLE member_badges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    badge_type VARCHAR(30) NOT NULL,
    tier VARCHAR(20) NOT NULL,
    achieved_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT fk_member_badges_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_member_badges_user_type UNIQUE (user_id, badge_type)
);
```

- `badge_type`: `TUTORIAL_COMPLETION` | `REFLECTION_COUNT` | `LEARNED_RECEIVED` | `REALIZED_PNL_STOCK` | `REALIZED_PNL_CRYPTO`(자바 enum, CHECK 제약 없음 — 코드베이스 전례대로 앱 계층 검증).
- `tier`: `BRONZE` | `SILVER` | `GOLD` | `PLATINUM`.
- 회원이 아직 어떤 등급도 달성하지 못한 카테고리는 행 자체가 없다(0등급을 별도 값으로 저장하지 않는다) — RANK-001이 "매도 이력 없으면 ZSET에 member가 없다"로 처리한 것과 같은 원칙.
- 등급 재계산 시 `upsert`하되 **새로 계산된 등급이 기존 저장값보다 낮으면 갱신하지 않는다**(하락 없음 규칙의 실제 구현 지점).

## 패키지·클래스 설계

### 신규 도메인 `com.finplay.api.badge`

```
com.finplay.api.badge
├── controller/BadgeController.java              # GET /api/badges/me
├── service/BadgeService.java                    # 등급 재계산·upsert, 진행률 계산, 배치 조회(authorBadges용)
├── domain/MemberBadge.java                      # user_id, badge_type, tier, achieved_at, updated_at
├── domain/BadgeType.java                        # enum: TUTORIAL_COMPLETION, REFLECTION_COUNT, LEARNED_RECEIVED, REALIZED_PNL_STOCK, REALIZED_PNL_CRYPTO
├── domain/BadgeTier.java                        # enum: BRONZE, SILVER, GOLD, PLATINUM (ordinal로 등급 비교)
├── repository/MemberBadgeRepository.java         # findByUserId, findAllByUserIdIn(배치), upsert용 findByUserIdAndBadgeType
├── listener/BadgeEventListener.java              # @TransactionalEventListener(AFTER_COMMIT) x4 (아래)
└── dto/response/
    ├── MyBadgeListResponse.java                  # record(List<MyBadgeItemResponse>)
    ├── MyBadgeItemResponse.java                  # record(BadgeType, currentTier(nullable), currentCount, nextTier(nullable), nextTierThreshold(nullable))
    └── AuthorBadgeSummaryResponse.java            # record(BadgeType, BadgeTier) — 커뮤니티 응답에 임베드되는 최소 형태
```

### 이벤트 발행 지점 (기존 파일 수정 3곳 + 이벤트 1종 신규)

| 발행 시점 | 발행 위치 | 이벤트 |
|---|---|---|
| 튜토리얼 attempt 완료 | `PracticeAttemptService`(또는 `complete()`를 호출하는 서비스 지점 — 구현 착수 시 실제 호출부 확인) | `TutorialAttemptCompletedEvent(Long userId)`(신규, `education.marketpractice.event`) |
| 복기 작성 | `PracticeHoldingReflectionService.createReflection(...)`(또는 실제 생성 서비스) | `MarketReflectionCreatedEvent(Long userId)`(신규, `education.marketpractice.event`) |
| "배웠어요" 추가/취소 | `PostLearnedReactionService`(신규, `community.service`) | `PostLearnedReactionChangedEvent(Long postAuthorUserId)`(신규, `community.event`) — 추가·취소 공통, 리스너가 항상 재조회하므로 개수를 싣지 않는다(014의 `RealizedPnlUpdatedEvent` 패턴과 동일) |
| 매도 체결(실현손익 갱신) | 기존 `RealizedPnlUpdatedEvent`(재사용, 신규 아님) | - |

- 이벤트 타입은 "사실이 발생한 도메인"에 둔다(014의 `RealizedPnlUpdatedEvent`가 `account`에 있는 것과 동일 원칙) — `TutorialAttemptCompletedEvent`는 `education.marketpractice`, `MarketReflectionCreatedEvent`도 `education.marketpractice`, `PostLearnedReactionChangedEvent`는 `community`. `badge` 도메인은 이 이벤트 타입들을 구독(import)만 한다 — 각 도메인의 repository를 직접 주입하지 않는다(ADR-0002).
- `BadgeEventListener`(`badge.listener`)가 4개 이벤트를 각각 구독해 `BadgeService`의 재계산 메서드를 호출한다. 모든 리스너 메서드는 최상위에서 예외를 삼킨다(014 `RankingEventListener`와 동일 원칙 — 배지 갱신 실패가 원래 요청의 성공을 막으면 안 된다).

### `BadgeService` 재계산 메서드 설계 (스케치)

```java
@Transactional
public void recalculateTutorialCompletion(Long userId) {
    long count = practiceAttemptRepository.countByUserIdAndStatus(userId, PracticeAttemptStatus.COMPLETED);
    upsertIfHigher(userId, BadgeType.TUTORIAL_COMPLETION, tierFor(TUTORIAL_COMPLETION_THRESHOLDS, count));
}
// recalculateReflectionCount, recalculateLearnedReceived, recalculateRealizedPnl(market)도 동일한 형태
```

- `upsertIfHigher(userId, badgeType, newTier)`: `newTier`가 `null`(어느 등급 임계값도 못 넘음)이면 아무 것도 하지 않는다. 기존 저장 등급이 없거나 `newTier.ordinal() > existing.ordinal()`이면 저장/갱신, 아니면 그대로 둔다.
- 임계값 상수는 각 재계산 메서드가 속한 `BadgeService`에 `private static final` 배열/맵으로 둔다(컨벤션의 "매직 넘버는 상수로" 규칙).

### 커뮤니티 응답 확장

- `PostLearnedReactionService`(신규)가 게시물의 `learnedCount`(카운트 쿼리)·`learnedByMe`(요청자 존재 여부)를 계산해 `CommunityPostService`/`CommunityPostRepositoryImpl`이 응답 조립 시 함께 채운다.
- `authorBadges`는 게시물·댓글 목록 조회 시 등장하는 모든 작성자 `userId`를 모아 `BadgeService.getTopTiersByUserIds(List<Long> userIds)`(배치, `MemberBadgeRepository.findAllByUserIdIn`)로 한 번에 조회한 뒤 각 응답에 매핑한다 — N+1 방지(014의 `findAllByIdInFetchUser` 배치 조회와 동일 원칙).
- `CommunityPostService`·`PostCommentService`는 `BadgeService`를 주입해 호출한다(ADR-0002 — 도메인 간 참조는 service 레이어를 통해서만).

## 테스트 계획

- 단위
  - `BadgeServiceTest`(Mockito): 4개 카테고리 각각 임계값 경계(직전 값·정확히 임계값·초과)에서 등급이 올바르게 매핑되는지, 기존 등급보다 낮은 재계산 결과는 무시되는지(하락 없음), 배치 조회(`getTopTiersByUserIds`)가 배지 없는 userId를 빈 목록으로 처리하는지.
  - `PostLearnedReactionServiceTest`: 본인 게시물 반응 시도 시 400 전파, 중복 표시 시 멱등 동작(기존 상태 반환), 취소 시 존재하지 않아도 예외 없음.
  - `BadgeEventListenerTest`: 각 리스너가 하위 서비스에서 예외가 나도 밖으로 전파하지 않는지(014 `RankingEventListenerTest`와 동일 패턴).
- 슬라이스
  - `MemberBadgeRepositoryTest`(`@DataJpaTest`): `uk_member_badges_user_type` 유니크 제약, `findAllByUserIdIn` 배치 조회.
  - `CommunityPostLearnedReactionRepositoryTest`(`@DataJpaTest`): `uk_learned_reactions_post_user` 유니크 제약, 게시물 삭제 시 `ON DELETE CASCADE`로 반응도 함께 삭제되는지.
  - `BadgeControllerTest`(`@WebMvcTest`): `GET /api/badges/me` 응답 필드 계약, 인증 없이 요청 시 401.
  - `CommunityPostControllerTest`/`PostCommentControllerTest`(기존 파일 확장): 응답에 `learnedCount`·`learnedByMe`·`authorBadges` 필드 계약.
- 통합 (`@SpringBootTest` + Testcontainers, ADR-0003) — spec.md "완료 조건" 시나리오를 그대로 구현:
  1. 튜토리얼 attempt를 임계값 횟수만큼 완료 → `GET /api/badges/me`에서 해당 등급 확인.
  2. 복기를 임계값 횟수만큼 작성 → 등급 확인.
  3. 다른 회원 게시물에 "배웠어요" 표시/취소 반복 → 게시물 응답 카운트·`learnedByMe` 갱신, 누적 수신 임계값 도달 시 작성자 등급 확인.
  4. 본인 게시물에 "배웠어요" 시도 → 400.
  5. 매도 체결 반복으로 시장별 누적 실현손익이 임계값을 넘김 → 해당 시장 배지 등급 확인, 다른 시장 배지는 영향 없음 확인.
  6. 등급 달성 후 반응 취소·손실 발생으로 지표가 낮아져도 등급이 유지되는 회귀.
  7. 커뮤니티 목록 조회 시 여러 작성자의 `authorBadges`가 정확히 매핑되는지(N+1 발생 여부는 쿼리 카운트로 확인).
