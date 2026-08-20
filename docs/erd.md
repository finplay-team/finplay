# ERD (Entity Relationship Diagram)

FinPlay 백엔드의 JPA 엔티티와 실제 DB 테이블 구조를 도메인별로 정리한 문서다.

- 컬럼·연관관계는 `@Entity` 클래스 코드를 기준으로 정리하고, 유니크 제약은 `db/migration/*.sql`의 실제 `UNIQUE` 제약과 대조해 검증했다.

## 엔티티 목록

| 도메인 | 클래스 | 테이블 | 비고 |
|---|---|---|---|
| account | `Account` | `accounts` | |
| account | `TutorialAccount` | `tutorial_accounts` | |
| auth | `User` | `users` | 도메인 전반의 중심 엔티티 |
| auth | `SocialAccount` | `social_accounts` | |
| auth | `RefreshToken` | `refresh_tokens` | |
| auth | `ReauthToken` | `reauth_tokens` | |
| auth | `EmailVerification` | `email_verifications` | User와 FK 없음 (이메일 문자열만) |
| auth | `EmailChangeVerification` | `email_change_verifications` | |
| auth | `PasswordResetVerification` | `password_reset_verifications` | User와 FK 없음 (이메일 문자열만) |
| market | `Instrument` | `instruments` | 여러 도메인이 참조하는 허브 |
| market | `StockCandle` | `stock_candles` | |
| market | `StockDailyCandle` | `stock_daily_candles` | 일봉 3년 아카이브(MKT-011). `stock_candles`와 보관 정책이 반대(3년 누적 vs 20영업일 롤링) |
| market | `StockReplaySession` | `stock_replay_sessions` | |
| market | `MarketDataImport` | `market_data_imports` | 배치/수집 파이프라인 전용 |
| order | `Order` | `orders` | |
| order | `Trade` | `trades` | |
| order | `ExitPlan` | `exit_plans` | |
| order | `ExitPlanCondition` | `exit_plan_conditions` | |
| order | `ExitPlanIdempotencyKey` | `exit_plan_idempotency_keys` | |
| portfolio | `Holding` | `holdings` | |
| portfolio | `HoldingLot` | `holding_lots` | |
| portfolio | `TradeAllocation` | `trade_allocations` | |
| journal | `BuyTradeJournal` | `buy_trade_journals` | |
| journal | `SellTradeJournal` | `sell_trade_journals` | |
| education | `PracticeProgress` | `practice_progresses` | |
| education | `PracticeAttempt` | `practice_attempts` | 핵심 엔티티, 컬럼 다수 |
| education | `PracticeCompletion` | `practice_completions` | |
| education | `PracticeMarketObservation` | `practice_market_observations` | |
| education | `PracticeMarketReflection` | `practice_market_reflections` | |
| education | `PracticeRiskSnapshot` | `practice_risk_snapshots` | |
| education | `PracticePriceSession` | `practice_price_sessions` | User/Instrument와 JPA 매핑 없음 (DB FK는 있음) |
| feedback | `InstrumentNewsSummary` | `instrument_news_summaries` | |
| feedback | `MarketBriefing` | `market_briefings` | `market`은 값 타입 enum, 엔티티 아님 |
| feedback | `MarketNewsItem` | `market_news_items` | |
| feedback | `PriceMoveEvent` | `price_move_events` | |
| feedback | `PriceMoveEventSource` | `price_move_event_sources` | Event↔News의 M:N 조인 엔티티 |
| feedback | `PriceMovePeerStat` | `price_move_peer_stats` | |
| feedback | `TradeFeedback` | `trade_feedbacks` | |
| community | `CommunityPost` | `community_posts` | |
| community | `CommunityPostImage` | `community_post_images` | |
| community | `CommunityPostLike` | `community_post_likes` | |
| community | `PostComment` | `post_comments` | 자기참조(대댓글 1단계) |
| watchlist | `WatchlistItem` | `watchlist_items` | User와 JPA 매핑 없음 (DB FK는 있음) |

## 관계 표기 범례

- `A ||--o{ B` : 1:N, `B`가 FK로 `A`를 참조 (일반적인 `@ManyToOne`)
- `A ||--o| B` : 1:0..1, FK 컬럼에 `UNIQUE` 제약이 걸려 있어 사실상 1:1에 가까움
- `A ||..o{ B` (점선) : `B`가 `A`의 PK 값을 컬럼에 저장하지만 `@ManyToOne` 같은 **JPA 연관관계 매핑은 없는** 참조. **DB에는 실제 `FOREIGN KEY` 제약이 걸려 있다** — 없는 것은 JPA 매핑뿐이지 참조 무결성이 아니다(유일한 예외는 `practice_market_observations.instrument_id`로, 이 컬럼만 FK 제약 자체가 없다). `education` 도메인 다수 엔티티의 `user_id`, `CommunityPost.sharedTradeId`, `WatchlistItem.userId`, `Order`/`ExitPlan`의 `practice_attempt_id`(및 `Order.practicePriceSessionId`)가 이 패턴이다.
- 조인 엔티티(`price_move_event_sources`)로 구현된 M:N은 두 개의 `||--o{` 관계로 풀어서 표시한다.

각 다이어그램의 속성 목록은 실제 컬럼 전체가 아니라 ERD 이해에 필요한 주요 컬럼 위주로 추렸다. `PK`/`FK`/`UK` 표시는 각각 기본키, 외래키, (단일 또는 복합)유니크 제약을 뜻하며, 복합 유니크는 컬럼 옆 주석으로 함께 적었다. `users`, `instruments`, `trades`, `accounts`, `holdings`, `orders`는 여러 다이어그램에 허브로 반복 등장하는 동일 엔티티다. `practice_attempts`, `practice_price_sessions`도 5번(교육) 다이어그램에서 상세히 다루는 동일 엔티티가 3·4번 다이어그램에 참조 대상으로 등장한다.

---

## 0. 전체 통합 개요

42개 테이블 전체 관계를 하나의 다이어그램에 담은 개요다. 컬럼은 생략했고 관계선 표기(실선/점선, `|o` optional)는 위 범례를 그대로 따른다. 컬럼·제약 상세는 아래 도메인별 1~7번 다이어그램을 참고한다. 다른 엔티티를 참조하지 않는 독립 테이블(`email_verifications`, `password_reset_verifications`, `market_data_imports`, `market_briefings`)은 관계선 없이 박스만 표시했다.

```mermaid
erDiagram
    %% 1. 인증 · 계정
    users ||--o{ accounts : has
    users ||--o{ tutorial_accounts : has
    users ||--o{ social_accounts : links
    users ||--o{ refresh_tokens : issues
    users ||--o{ reauth_tokens : issues
    users ||--o{ email_change_verifications : requests

    %% 2. 시세 · 상품
    instruments ||--o{ stock_candles : has

    %% 3. 주문 · 체결 · 포트폴리오
    users ||--o{ orders : places
    accounts ||--o{ orders : has
    instruments ||--o{ orders : has
    practice_attempts |o..o{ orders : "optional, JPA 매핑 없음"
    practice_price_sessions |o..o{ orders : "optional, JPA 매핑 없음"
    orders ||--o| trades : fills
    accounts ||--o{ trades : has
    instruments ||--o{ trades : has
    stock_replay_sessions |o--o{ trades : "optional, 주식만"
    accounts ||--o{ holdings : holds
    instruments ||--o{ holdings : has
    holdings ||--o{ holding_lots : "FIFO 매입 lot"
    trades ||--o| holding_lots : "매수 체결 1건 = lot 1건"
    trades ||--o{ trade_allocations : "매도 체결이 소비"
    holding_lots ||--o{ trade_allocations : consumed_by

    %% 4. 손절/익절 예약 (Exit Plan)
    users ||--o{ exit_plans : has
    holdings ||--o{ exit_plans : has
    instruments ||--o{ exit_plans : has
    trades |o--o{ exit_plans : "매수 체결(선택)"
    orders |o--o{ exit_plans : "트리거된 주문(선택)"
    stock_replay_sessions |o--o{ exit_plans : "optional"
    practice_attempts |o..o{ exit_plans : "optional, JPA 매핑 없음"
    exit_plans ||--o{ exit_plan_conditions : has
    users ||--o{ exit_plan_idempotency_keys : has
    exit_plans ||--o{ exit_plan_idempotency_keys : has

    %% 5. 투자 교육 · 튜토리얼
    users ||--o{ practice_progresses : has
    users ||..o{ practice_attempts : "user_id (JPA 매핑 없음)"
    instruments |o--o{ practice_attempts : optional
    users ||..o{ practice_completions : "user_id (JPA 매핑 없음)"
    practice_market_reflections ||--o{ practice_completions : finalizes
    users ||..o{ practice_market_observations : "user_id (JPA 매핑 없음)"
    holdings ||--o{ practice_market_observations : observed_via
    users ||..o{ practice_market_reflections : "user_id (JPA 매핑 없음)"
    holdings ||--o{ practice_market_reflections : reflected_via
    practice_attempts ||--o{ practice_risk_snapshots : has
    trades ||--o{ practice_risk_snapshots : "매수 체결로 진입"
    users ||..o{ practice_price_sessions : "user_id (JPA 매핑 없음)"
    instruments ||..o{ practice_price_sessions : "instrument_id (JPA 매핑 없음)"

    %% 6. 피드백 · AI 내러티브
    instruments ||--o{ instrument_news_summaries : has
    instruments ||--o{ market_news_items : has
    instruments ||--o{ price_move_events : has
    price_move_events ||--o{ price_move_event_sources : has
    market_news_items ||--o{ price_move_event_sources : has
    price_move_events ||--o{ price_move_peer_stats : has
    trades ||--o| trade_feedbacks : has

    %% 7. 매매일지 · 커뮤니티 · 관심종목
    trades ||--o| buy_trade_journals : has
    trades ||--o| sell_trade_journals : has
    users ||--o{ community_posts : authors
    instruments |o--o{ community_posts : optional
    trades ||..o{ community_posts : "shared_trade_id (JPA 매핑 없음)"
    community_posts |o--o| community_post_images : has
    users ||--o{ community_post_images : uploads
    community_posts ||--o{ community_post_likes : liked_by
    users ||--o{ community_post_likes : likes
    community_posts ||--o{ post_comments : has
    users ||--o{ post_comments : writes
    post_comments ||--o{ post_comments : replies_to
    instruments ||--o{ watchlist_items : watched_as
    users ||..o{ watchlist_items : "user_id (JPA 매핑 없음), UK(user_id, instrument_id)"

    %% 관계 없는 독립 테이블
    email_verifications {
        bigint id PK
    }
    password_reset_verifications {
        bigint id PK
    }
    market_data_imports {
        bigint id PK
    }
    market_briefings {
        bigint id PK
    }
```

---

## 1. 인증 · 계정

```mermaid
erDiagram
    users ||--o{ accounts : has
    users ||--o{ tutorial_accounts : has
    users ||--o{ social_accounts : links
    users ||--o{ refresh_tokens : issues
    users ||--o{ reauth_tokens : issues
    users ||--o{ email_change_verifications : requests

    users {
        bigint id PK
        varchar email UK
        varchar password_hash "OAuth 전용은 sentinel 값"
        varchar nickname UK
        varchar role
        varchar status
        datetime created_at
        datetime updated_at
    }
    accounts {
        bigint id PK
        bigint user_id FK "UK(user_id, market)"
        enum market "UK(user_id, market)"
        bigint cash_balance
        bigint reserved_cash
        bigint seed_money
        bigint realized_pnl
    }
    tutorial_accounts {
        bigint id PK
        bigint user_id FK "UK(user_id, market)"
        enum market "UK(user_id, market)"
        bigint cash_balance
        bigint reserved_cash
        bigint realized_pnl
    }
    social_accounts {
        bigint id PK
        bigint user_id FK
        enum provider "UK(provider, provider_user_id)"
        varchar provider_user_id "UK(provider, provider_user_id)"
    }
    refresh_tokens {
        bigint id PK
        bigint user_id FK
        varchar token_hash
        datetime expires_at
        datetime revoked_at
    }
    reauth_tokens {
        bigint id PK
        bigint user_id FK
        varchar token_hash UK
        datetime expires_at
        datetime consumed_at
    }
    email_change_verifications {
        bigint id PK
        bigint user_id FK
        varchar new_email
        varchar code_hash
        datetime expires_at
        datetime consumed_at
    }
    email_verifications {
        bigint id PK
        varchar email
        varchar code_hash
        varchar token_hash UK
        datetime verified_at
        datetime consumed_at
    }
    password_reset_verifications {
        bigint id PK
        varchar email
        varchar code_hash
        datetime expires_at
        datetime consumed_at
    }
```

`email_verifications`, `password_reset_verifications`는 `users`와 FK가 없다. 가입 전 이메일이나 소셜 전용 계정 이메일에 대해서도 발송 이력·거부 이력을 남겨야 해서, User row 존재 여부와 무관하게 이메일 문자열만 들고 있는 독립 엔티티다.

## 2. 시세 · 상품 (market)

```mermaid
erDiagram
    instruments ||--o{ stock_candles : has
    instruments ||--o{ stock_daily_candles : has

    instruments {
        bigint id PK
        enum market
        varchar symbol UK
        varchar name
        decimal tick_size
        bigint min_order_amount
        boolean tradable
        boolean is_tutorial_sample
    }
    stock_candles {
        bigint id PK
        bigint instrument_id FK "UK(instrument_id, trading_date, candle_time)"
        date trading_date "UK"
        time candle_time "UK"
        decimal open
        decimal high
        decimal low
        decimal close
        bigint volume
        varchar data_source
    }
    stock_daily_candles {
        bigint id PK
        bigint instrument_id FK "UK(instrument_id, trading_date)"
        date trading_date "UK, 실제 달력 거래일"
        decimal open
        decimal high
        decimal low
        decimal close
        bigint volume
        varchar data_source
    }
    stock_replay_sessions {
        bigint id PK
        date service_date UK
        date source_trading_date
        enum preparation_status
        datetime resolved_at
        varchar failure_reason
    }
    market_data_imports {
        bigint id PK
        varchar source
        date source_trading_date "UK 아님: 재수집 허용"
        enum status
        varchar failure_reason
    }
```

`stock_replay_sessions`, `market_data_imports`는 다른 엔티티를 FK로 참조하지 않는 독립 테이블이다(리플레이 세션·수집 배치 자체의 상태 기록용). `Trade`, `ExitPlan`이 `stock_replay_sessions`를 선택적으로 참조하는 것은 3번 다이어그램에서 다룬다.

**`stock_candles`는 1분봉이며 재생(replay)용이다** — 최근 20영업일만 보관하는 롤링 윈도우다(PRD MKT-005). 장기 차트용 **일봉**은 `stock_daily_candles`에 최근 3년치를 별도 보관한다(MKT-011, `ai/specs/050-stock-daily-archive`, 이슈 #506) — `candle_time`이 없고 `UNIQUE(instrument_id, trading_date)`만 걸려 있는 점이 `stock_candles`와의 유일한 구조 차이다. **캔들 조회 API를 이 아카이브에 연결하는 작업은 아직 범위 밖**이라(spec 050 §범위 제외) 이 테이블에 쓰는 배치·수집기는 후속 작업(tasks.md 3~5번)에서 채워진다.

## 3. 주문 · 체결 · 포트폴리오

```mermaid
erDiagram
    users ||--o{ orders : places
    accounts ||--o{ orders : has
    instruments ||--o{ orders : has
    practice_attempts |o..o{ orders : "optional, JPA 매핑 없음"
    practice_price_sessions |o..o{ orders : "optional, JPA 매핑 없음"
    orders ||--o| trades : fills
    accounts ||--o{ trades : has
    instruments ||--o{ trades : has
    stock_replay_sessions |o--o{ trades : "optional, 주식만"
    accounts ||--o{ holdings : holds
    instruments ||--o{ holdings : has
    holdings ||--o{ holding_lots : "FIFO 매입 lot"
    trades ||--o| holding_lots : "매수 체결 1건 = lot 1건"
    trades ||--o{ trade_allocations : "매도 체결이 소비"
    holding_lots ||--o{ trade_allocations : consumed_by

    orders {
        bigint id PK
        bigint user_id FK
        bigint account_id FK
        bigint instrument_id FK
        enum side
        enum order_type
        enum status
        decimal quantity
        decimal limit_price
        bigint practice_price_session_id "nullable, JPA 매핑 없음"
        bigint practice_attempt_id "nullable, JPA 매핑 없음"
        bigint practice_attempt_run_number "nullable, practice_attempt_id와 짝"
        varchar idempotency_key "UK(user_id, idempotency_key)"
        varchar request_hash
    }
    trades {
        bigint id PK
        bigint order_id FK, UK
        bigint account_id FK
        bigint instrument_id FK
        bigint stock_replay_session_id FK "nullable, 코인은 금지"
        enum side
        decimal price
        decimal quantity
        bigint amount
        bigint fee
        bigint realized_pnl
        datetime executed_at
    }
    holdings {
        bigint id PK
        bigint account_id FK "UK(account_id, instrument_id)"
        bigint instrument_id FK "UK(account_id, instrument_id)"
        decimal quantity
        decimal reserved_quantity
        decimal average_price
        boolean is_active
    }
    holding_lots {
        bigint id PK
        bigint holding_id FK
        bigint buy_trade_id FK, UK
        decimal original_quantity
        decimal remaining_quantity
        decimal unit_cost
        bigint buy_fee
        datetime executed_at
    }
    trade_allocations {
        bigint id PK
        bigint sell_trade_id FK
        bigint holding_lot_id FK
        decimal allocated_quantity
        bigint allocated_cost
        bigint allocated_buy_fee
    }
```

`orders`→`trades`, `trades`→`holding_lots`는 각각 `uk_trades_order UNIQUE(order_id)`, `uk_holding_lots_buy_trade UNIQUE(buy_trade_id)` 제약으로 실제 DB에서 1:0..1임이 보장된다(주문 1건은 체결 1건까지만, 매수 체결 1건은 lot 1건까지만).

`orders.practice_price_session_id`·`practice_attempt_id`·`practice_attempt_run_number`는 튜토리얼 주문에만 채워지는 nullable 컬럼이다. `Order` 엔티티가 `PracticeAttempt`·`PracticePriceSession`을 `@ManyToOne`으로 참조하지 않아 점선이지만, `fk_orders_practice_attempt`·`fk_orders_practice_price_session` FK는 DB에 실제로 걸려 있다(5번 다이어그램의 점선 관계와 같은 패턴). `practice_attempt_id`와 `practice_attempt_run_number`는 `chk_orders_practice_attempt_attribution` CHECK로 항상 함께 NULL이거나 함께 채워짐이 보장된다.

## 4. 손절/익절 예약 (Exit Plan)

```mermaid
erDiagram
    users ||--o{ exit_plans : has
    holdings ||--o{ exit_plans : has
    instruments ||--o{ exit_plans : has
    trades |o--o{ exit_plans : "매수 체결(선택)"
    orders |o--o{ exit_plans : "트리거된 주문(선택)"
    stock_replay_sessions |o--o{ exit_plans : "optional"
    practice_attempts |o..o{ exit_plans : "optional, JPA 매핑 없음"
    exit_plans ||--o{ exit_plan_conditions : has
    users ||--o{ exit_plan_idempotency_keys : has
    exit_plans ||--o{ exit_plan_idempotency_keys : has

    exit_plans {
        bigint id PK
        bigint user_id FK
        bigint holding_id FK
        bigint buy_trade_id FK "nullable"
        bigint instrument_id FK
        bigint triggered_order_id FK "nullable"
        bigint stock_replay_session_id FK "nullable"
        varchar intention_instance_key "UK(user_id, intention_instance_key), 교육 경로 전용"
        bigint practice_attempt_id "nullable, JPA 매핑 없음, 튜토리얼 자동 예약 전용"
        bigint practice_attempt_run_number "nullable, practice_attempt_id와 짝"
        decimal quantity
        decimal entry_price
        enum exit_price_type
        decimal stop_loss_price
        decimal take_profit_price
        enum status
        varchar request_hash
    }
    exit_plan_conditions {
        bigint id PK
        bigint exit_plan_id FK "UK(exit_plan_id, condition_type)"
        enum condition_type "UK(exit_plan_id, condition_type)"
        decimal trigger_price
        enum status
    }
    exit_plan_idempotency_keys {
        bigint id PK
        bigint user_id FK "UK(user_id, idempotency_key)"
        bigint exit_plan_id FK
        varchar idempotency_key "UK(user_id, idempotency_key)"
        varchar request_hash
    }
```

`exit_plans.practice_attempt_id`·`practice_attempt_run_number`는 튜토리얼 손절·익절 자동 예약(`ExitPlan.createPractice`)에만 채워지는 nullable 컬럼 쌍이다. `intention_instance_key`(교육 경로의 수동 예약)와는 별개 트랙이며, `chk_exit_plans_practice_attempt_attribution` CHECK로 둘이 항상 함께 NULL이거나 함께 채워짐이 보장된다. `orders`와 마찬가지로 JPA 매핑은 없지만 `fk_exit_plans_practice_attempt` FK는 DB에 걸려 있다.

## 5. 투자 교육 · 튜토리얼

```mermaid
erDiagram
    users ||--o{ practice_progresses : has
    users ||..o{ practice_attempts : "user_id (JPA 매핑 없음)"
    instruments |o--o{ practice_attempts : optional
    users ||..o{ practice_completions : "user_id (JPA 매핑 없음)"
    practice_market_reflections ||--o{ practice_completions : finalizes
    users ||..o{ practice_market_observations : "user_id (JPA 매핑 없음)"
    holdings ||--o{ practice_market_observations : observed_via
    users ||..o{ practice_market_reflections : "user_id (JPA 매핑 없음)"
    holdings ||--o{ practice_market_reflections : reflected_via
    practice_attempts ||--o{ practice_risk_snapshots : has
    trades ||--o{ practice_risk_snapshots : "매수 체결로 진입"
    users ||..o{ practice_price_sessions : "user_id (JPA 매핑 없음)"
    instruments ||..o{ practice_price_sessions : "instrument_id (JPA 매핑 없음)"

    practice_progresses {
        bigint id PK
        bigint user_id FK "UK(user_id, tutorial_key)"
        varchar tutorial_key "UK(user_id, tutorial_key)"
        enum status
        datetime started_at
        datetime completed_at
    }
    practice_attempts {
        bigint id PK
        bigint user_id "UK(user_id, market), JPA 매핑 없음"
        enum market "UK(user_id, market)"
        bigint instrument_id FK "nullable"
        bigint run_number
        enum status
        enum exit_preset
        varchar scenario_stage_id
        datetime completed_at
    }
    practice_completions {
        bigint id PK
        bigint user_id "UK(user_id, tutorial_key), JPA 매핑 없음"
        varchar tutorial_key "UK(user_id, tutorial_key)"
        bigint reflection_id FK
        datetime completed_at
    }
    practice_market_observations {
        bigint id PK
        bigint user_id "JPA 매핑 없음"
        bigint holding_id FK
        bigint instrument_id "holding에서 유도 가능한 비정규화 값, FK 없음"
        decimal current_price
        boolean closer_to_boundary
        enum evidence_type
    }
    practice_market_reflections {
        bigint id PK
        bigint user_id "UK(user_id, tutorial_key), JPA 매핑 없음"
        bigint holding_id FK
        varchar tutorial_key "UK(user_id, tutorial_key)"
        varchar answer
    }
    practice_risk_snapshots {
        bigint id PK
        bigint attempt_id FK "UK(attempt_id, run_number, entry_sequence)"
        bigint run_number "UK"
        int entry_sequence "UK"
        bigint buy_trade_id FK
        decimal entry_price
        decimal stop_loss_price
        decimal take_profit_price
    }
    practice_price_sessions {
        bigint id PK
        bigint user_id "UK(user_id, instrument_id, active_slot), JPA 매핑 없음"
        bigint instrument_id "UK, JPA 매핑 없음"
        enum status
        bigint seed
        decimal start_price
        decimal current_price
    }
```

교육 도메인의 특징: `practice_progresses.user`만 `@ManyToOne`으로 매핑돼 실선이고, 나머지 `practice_attempts`·`practice_completions`·`practice_market_observations`·`practice_market_reflections`·`practice_price_sessions`의 `user_id`(그리고 `practice_price_sessions.instrument_id`)는 순수 `Long` 컬럼이라 점선이다. **점선은 JPA 매핑이 없다는 뜻일 뿐 DB 제약이 없다는 뜻은 아니다** — 이 컬럼들에는 전부 실제 `FOREIGN KEY`가 걸려 있다(예: `fk_practice_attempts_user`, `fk_practice_price_sessions_instrument`). 유일한 예외는 `practice_market_observations.instrument_id`로, holding에서 유도 가능한 비정규화 값이라 FK 제약 자체가 없다. `practice_progresses`, `practice_attempts`, `practice_completions`, `practice_market_reflections`, `practice_price_sessions`는 여기에 더해 DB 레벨 `UNIQUE` 제약도 걸어 정합성을 지킨다 — JPA 매핑만 생략했을 뿐 무결성은 `UNIQUE`·`FOREIGN KEY` 둘 다로 지키는 설계다.

## 6. 피드백 · AI 내러티브

```mermaid
erDiagram
    instruments ||--o{ instrument_news_summaries : has
    instruments ||--o{ market_news_items : has
    instruments ||--o{ price_move_events : has
    price_move_events ||--o{ price_move_event_sources : has
    market_news_items ||--o{ price_move_event_sources : has
    price_move_events ||--o{ price_move_peer_stats : has
    trades ||--o| trade_feedbacks : has

    instrument_news_summaries {
        bigint id PK
        bigint instrument_id FK "UK(instrument_id, origin_trade_date, scope)"
        date origin_trade_date "UK"
        enum scope "UK"
        text summary
        enum narrative_source
    }
    market_briefings {
        bigint id PK
        enum market "UK(market, origin_trade_date) — 값 타입, 엔티티 아님"
        date origin_trade_date "UK"
        text summary
        enum narrative_source
    }
    market_news_items {
        bigint id PK
        bigint instrument_id FK "UK(instrument_id, url)"
        enum type
        varchar title
        varchar url "UK"
        datetime published_at
    }
    price_move_events {
        bigint id PK
        bigint instrument_id FK "UK(instrument_id, origin_trade_date, event_type, window_start)"
        enum market "instrument 값의 비정규화 사본"
        enum event_type "UK"
        date origin_trade_date "UK"
        time window_start "UK, 주식만"
        datetime occurred_at "코인만"
        decimal change_rate
        decimal detection_score
        text narrative
    }
    price_move_event_sources {
        bigint id PK
        bigint price_move_event_id FK "UK(price_move_event_id, market_news_item_id)"
        bigint market_news_item_id FK "UK"
    }
    price_move_peer_stats {
        bigint id PK
        bigint price_move_event_id FK "UK(price_move_event_id, service_date)"
        date service_date "UK"
        int holder_count
        int sold_within_30min_count
        int median_minutes_to_sell "전원 미매도 시 null"
    }
    trade_feedbacks {
        bigint id PK
        bigint trade_id FK, UK
        text narrative
        enum narrative_source
        boolean narrative_finalized
        varchar journal_fingerprint "일기 없으면 null"
    }
```

`price_move_event_sources`는 "기사 하나가 여러 카드의 근거가 되고, 카드 하나가 여러 기사를 근거로 둔다"는 N:M을 명시적 조인 엔티티로 구현한 것이다. `market_briefings.market`은 `Instrument.market`과 값이 같은 별도 enum일 뿐 엔티티 참조가 아니라서 관계선이 없다.

## 7. 매매일지 · 커뮤니티 · 관심종목

```mermaid
erDiagram
    trades ||--o| buy_trade_journals : has
    trades ||--o| sell_trade_journals : has
    users ||--o{ community_posts : authors
    instruments |o--o{ community_posts : optional
    trades ||..o{ community_posts : "shared_trade_id (JPA 매핑 없음)"
    community_posts |o--o| community_post_images : has
    users ||--o{ community_post_images : uploads
    community_posts ||--o{ community_post_likes : liked_by
    users ||--o{ community_post_likes : likes
    community_posts ||--o{ post_comments : has
    users ||--o{ post_comments : writes
    post_comments ||--o{ post_comments : replies_to
    instruments ||--o{ watchlist_items : watched_as
    users ||..o{ watchlist_items : "user_id (JPA 매핑 없음), UK(user_id, instrument_id)"

    buy_trade_journals {
        bigint id PK
        bigint buy_trade_id FK, UK
        varchar content
    }
    sell_trade_journals {
        bigint id PK
        bigint sell_trade_id FK, UK
        varchar content
    }
    community_posts {
        bigint id PK
        bigint author_id FK
        bigint instrument_id FK "nullable"
        bigint shared_trade_id "JPA 매핑 없음, nullable"
        varchar title
        varchar content
        bigint like_count
    }
    community_post_images {
        bigint id PK
        bigint uploader_id FK
        bigint post_id FK, UK "nullable — 선업로드 후 참조"
        varchar stored_filename
        varchar content_type
        bigint size_bytes
    }
    community_post_likes {
        bigint id PK
        bigint post_id FK "UK(post_id, user_id)"
        bigint user_id FK "UK(post_id, user_id)"
    }
    post_comments {
        bigint id PK
        bigint post_id FK
        bigint author_id FK
        bigint parent_comment_id FK "nullable, 자기참조"
        varchar content
        datetime deleted_at "soft delete"
    }
    watchlist_items {
        bigint id PK
        bigint user_id "UK(user_id, instrument_id), JPA 매핑 없음"
        bigint instrument_id FK "UK"
    }
```

`CommunityPost.sharedTradeId`는 order 도메인 `Trade`의 id 값만 저장하고 JPA 연관관계로 매핑하지 않는다 — community 도메인이 order 도메인 엔티티를 직접 참조하지 않게 하려는 설계다(`ADR-0002`).
