# Tasks: 012 AI 피드백 — 이슈 #225 (코인 실시간 변동 감시 구현)

> **이 문서는 이슈 #225의 범위만 담는다.** spec 012 전체(이슈 8개)의 분할은 `./plan.md`가 정본이고(§완료 조건 배정의 **8번 행**), 값·규칙은 `./spec.md` §확정값이 정본이다. 여기에 값을 다시 적지 않는다 — 크론·타임존은 §C-1, 시간 범위·근거창(코인)은 §C-2, 코인 시각 처리는 §C-9, 코인 신설 코드 요소는 §C-6, 설정값은 §C-7, 탐지 알고리즘은 §탐지 알고리즘(코인), 스냅샷 저장 형태는 §코인 가격 스냅샷이 정본이다.
>
> **이 이슈가 spec 012의 마지막 이슈다.** 1~7번(#147·#160·#167·#180·#188·#208·#212)이 전부 머지돼 주식 쪽이 완성됐고, 이 이슈가 코인 쪽(FEED-005)과 FEED-006의 코인 분기를 닫는다. `plan.md` §"8번이 마지막인 이유" — 코인 감시는 `market`의 가격 스냅샷 보관 신설이 선행돼야 성립한다.
>
> 항목 하나 = implementer 1회 투입 = 커밋 1개 (`docs/specs/README.md`의 굵기 가이드). 테스트 레벨은 ADR-0003을 따른다 — 순수 계산·서비스 로직은 단위, 리포지토리 파인더는 `@DataJpaTest`, 원장 불변은 고정 `Clock` + Testcontainers 통합이다.

## 이 이슈 전체에 걸리는 제약

- **여기서 값을 새로 정하지 않는다.** `feedback.crypto.*` 6개 키(`cooldown-minutes`·`daily-limit`·`rolling-window-minutes`·`sigma-lookback-hours`·`min-sample-count`·`match-before-minutes`)와 `market.crypto.price-snapshot-cron`의 값은 §C-7·§C-1을 그대로 옮긴다.
- **`k`(z-score 계수)는 새로 만들지 않는다.** §탐지 알고리즘(코인)의 `|r5| / σ24 < k`는 **`feedback.detection.z-score-k`를 그대로 쓴다** — §C-7의 `feedback.crypto` 블록에 별도 `z-score-k`가 없다. 주식과 코인이 같은 계수를 공유하는 것이 의도다.
- **새 마이그레이션이 필요 없음을 확인했다** (2026-08-05, planner). `V13__create_ai_feedback_tables.sql`의 `price_move_events`에 `occurred_at DATETIME(6) NULL`·`window_start`/`window_end` `TIME NULL`이 이미 있고, `idx_price_move_events_instrument_occurred_at (instrument_id, occurred_at)` 인덱스가 코인 24시간 조회와 쿨다운·일일 상한 조회 양쪽에 그대로 쓰인다. **머지된 마이그레이션은 수정 금지**(ADR-0004) — 착수 중 정말 컬럼이 부족하면 코드를 먼저 만들지 말고 오케스트레이터에게 보고한다.
- **새 외부 HTTP 호출을 추가하지 않는다.** 코인 가격 스냅샷은 이미 연결된 빗썸 피드(`PriceStore`)를 읽어 Redis에 적재할 뿐이고, 코인 감시도 이미 저장된 뉴스(`MarketNewsItemRepository`)를 읽을 뿐이다. **새 `RestClient` 빈을 만들지 않는다** — `docs/agent-mistakes.md` 2026-08-04 행(같은 타입 `RestClient` 빈을 늘려 `NoUniqueBeanDefinitionException`으로 기존 `KisHistoricalCandleClientImpl`까지 깨진 사고)이 정확히 이 실수다.
- **이미 있는 것을 다시 만들지 않는다.**

  | 이미 있는 것 | 이 이슈가 할 일 |
  |---|---|
  | `PriceMoveEvent.createCrypto(...)` | 이미 있다 (§C-9 형태). 새로 만들지 않고 그대로 호출한다 |
  | `PriceMovePromptDto`·`NarrativeService.resolvePriceMoveNarrative` | 주식·코인이 같은 프롬프트 DTO를 쓴다(주석에 이미 코인 필드 설명이 있다). `NarrativeService`를 건드리지 않는다 |
  | `PriceMoveCardWriter`(카드+근거 저장 트랜잭션 경계) | 코인 카드 저장에도 그대로 재사용한다 — 저장 대상 테이블이 같다 |
  | `NewsMatcher`, `MarketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc` | 코인 근거창 질의는 **이 기존 리포지토리 메서드를 그대로 재사용**하고 시간 범위만 다르게 넘긴다. 새 리포지토리 메서드를 만들지 않는다 |
  | `PriceStore`(`market/store`) | 최신 틱 하나만 보관한다 — 이 이슈가 여기에 스냅샷 기록·조회 메서드를 **추가**한다. 기존 `saveTick`·`getLatestPrice`·`isPriceAvailable`은 건드리지 않는다 |
  | `InstrumentService.getInstrumentEntities(Market)` | 코인 종목 목록 조회에 그대로 재사용한다(`CryptoFeedbackBatchService`가 쓰는 것과 같은 메서드) |
  | `PriceMoveController`(FEED-006 엔드포인트) | 이미 있다. 컨트롤러는 건드리지 않고 `PriceMoveQueryService`의 코인 분기만 바꾼다 |
  | `FeedbackBatchService`(개장 전 배치)·`CryptoFeedbackBatchService`(코인 요약·브리핑)·`PeerStatsBatchService` | 건드리지 않는다. 이 이슈는 새 배치 둘(스냅샷 기록·코인 감시)을 **별도로** 만든다 |
- **market 선행 → feedback 후행 순서를 지킨다** (오케스트레이터 지시). 가격 스냅샷 보관(`market`)이 없으면 코인 감시(`feedback`)가 근거로 쓸 데이터가 없다.
- **`spring.task.scheduling.pool.size`는 스케줄을 추가하는 그 커밋에서 그 시점의 실제 개수로 올린다**(§C-1 — "올릴 때마다 그 시점의 개수를 다시 센다"). 이 이슈는 스케줄을 2개(`market.crypto.price-snapshot-cron`·`feedback.batch.crypto-watch-cron`) 추가하므로 **두 항목에서 각각 +1씩** 올린다(10→11→12). `application.yml`·`application-crypto-real.yml` **두 파일의 개수 계산 주석을 그때마다 함께 갱신한다**(이슈 #125 사고 재현 금지).
- **원장에 쓰지 않는다.** 이 이슈가 쓰는 테이블은 `price_move_events`·`price_move_event_sources`뿐이고 Redis 스냅샷 기록은 DB에 닿지 않는다. 그 밖은 전부 읽기다.

## 작업 항목

- [x] **1. `market`: `CryptoPriceSnapshotService` 신설 — 가격 스냅샷 기록·조회**

  §코인 가격 스냅샷이 정본이다. 매 분 코인 전 종목의 가격을 Redis Sorted Set에 적재하고, `feedback`이 구간 조회할 수 있게 한다.
  - **Redis 키 조립은 `PriceStore` 안에서만 한다**(`docs/conventions.md`, §코인 가격 스냅샷). `PriceStore`(`market/store`)에 스냅샷 기록·구간 조회 메서드를 추가한다 — 키는 `price:crypto:{symbol}:snapshots`(Sorted Set), score는 기록 시각의 epoch millis, member는 `"{epochMillis}:{price}"`다.
  - **`CryptoPriceSnapshotService`(신설, `market/service`)는 심볼·시각만 넘기고 스케줄을 소유한다** — `PriceStore`는 `@Component`이고 심볼 목록을 모른다(§C-6). `InstrumentService.getInstrumentEntities(Market.CRYPTO)`로 종목을 얻고 `PriceStore`에 기록을 위임한다.
  - `getSnapshots(symbol, from, to)`가 `List<(시각, 가격)>`을 반환한다 — `feedback`은 이 서비스만 알고 `PriceStore`를 직접 주입하지 않는다(§C-6).
  - **기록 시 `PriceStore.isPriceAvailable(symbol)`이 `false`면 건너뛴다** — 동결된 최신 틱이 쌓이면 σ가 0에 수렴했다가 복구 첫 틱에서 허위 카드가 무더기로 생성된다.
  - **`sigma-lookback-hours`를 넘은 원소를 기록할 때마다 제거한다** — Redis가 앱과 별도 컨테이너라 재기동해도 옛 표본이 남는다. `sigma-lookback-hours`는 `feedback.crypto.*`가 아니라 이 스케줄이 알아야 하므로, 이 서비스가 직접 상수로 갖거나 `market.crypto.*` 쪽 값으로 두되 **§C-7의 `feedback.crypto.sigma-lookback-hours`와 같은 값**이 되도록 한다 — 두 곳에 서로 다른 가지치기 기간을 두면 조회 창과 보관 창이 어긋난다. 어느 쪽 소유로 둘지는 구현자가 정하되, 값이 어긋나지 않게 드리프트 테스트나 상수 공유로 막는다.
  - 크론은 **`market.crypto.price-snapshot-cron`**(§C-1, 매 분 정각)이고 `zone = "Asia/Seoul"`을 반드시 붙인다. **`market`의 기존 두 크론(`KisHistoricalCandleCollector`·`StockReplaySessionScheduler`)은 `@Scheduled`에 리터럴을 직접 박아 두지만, 이 크론은 spec §C-1 표에 named 프로퍼티로 명시돼 있으므로 `${market.crypto.price-snapshot-cron}` 참조로 단다** — 기존 두 크론과 다른 점이니 리터럴로 되돌리지 않는다.
  - `spring.task.scheduling.pool.size`를 **10 → 11**로 올리고 `application.yml`·`application-crypto-real.yml` 두 파일의 "@Scheduled 작업은 N개다" 계산 주석을 함께 갱신한다(§C-1).
  - **함정 — 수익률이 아니라 가격+시각을 저장해야 한다.** 수익률만 저장하면 나중에 "5분 전 가격"을 꺼낼 수 없고, 기록하는 쪽도 5분 전 가격을 모른다(`PriceStore`엔 최신 틱 하나뿐).
  - 검증 — 단위(기록·조회, 가지치기, `isPriceAvailable=false`일 때 스킵) + `@SpringBootTest`(Redis에 실제로 적재·조회되는지) + `FeedbackBatchScheduleTest` 계열에 이 스케줄의 `zone`·프로퍼티 참조 테스트를 추가.

- [ ] **2. `feedback`: `feedback.crypto.*` 설정 블록 신설 + `NewsMatcher` 코인 근거 매칭**

  §C-7의 6개 키를 `FeedbackCryptoProperties`(신설, `FeedbackDetectionProperties`와 같은 형태 — yml + `@DefaultValue` 양쪽에 값)로 바인딩하고, `NewsMatcher`에 코인 근거창 매칭을 더한다.
  - `NewsMatcher`에 코인 전용 매칭 메서드를 추가한다 — 근거창은 §C-2의 `근거창(코인)`: `[occurredAt − match-before-minutes, occurredAt]`(이후는 0). 기존 `match(...)`(주식, `eventType` 분기)와는 **별도 메서드**다 — 코인은 이미 절대 시각(`occurredAt`)이라 원본 거래일과 결합할 필요가 없고, 갭 카드 분기도 없다.
  - **기존 `MarketNewsItemRepository.findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc`를 그대로 재사용한다** — 새 리포지토리 메서드를 만들지 않는다. 공시는 매칭하지 않는다(§C-3 "코인 | 공시 없음").
  - 상한·절단은 기존 `NewsItemTruncator`/정렬 규칙(§뉴스 매칭 범위)과 같은 방침을 따른다 — `max-sources-per-card`는 `feedback.news.*` 소유이므로 그대로 재사용한다(코인 전용 상한이 spec에 없다).
  - 완료 조건에 직접 대응하지 않지만, 다음 항목(감시)이 "근거 기사 0건이면 종료"를 검증하려면 이 매칭이 먼저 있어야 한다.
  - 검증 — 단위(근거창 경계, 공시 미매칭, 상한 절단)와 드리프트 테스트(`FeedbackCryptoProperties`의 yml·`@DefaultValue` 일치, 기존 `FeedbackDetectionProperties`류 테스트와 같은 형태).

- [ ] **3. `feedback`: `CryptoPriceMoveWatcher` 신설 — 코인 변동 탐지·카드 확정**

  §탐지 알고리즘(코인)의 의사코드를 그대로 구현한다. 매 분 실행해 임계치를 넘는 변동을 카드로 만든다.
  - 순서는 의사코드 그대로다 — `p_now`/`p_past` 조회(1번 항목의 `CryptoPriceSnapshotService.getSnapshots`) → 표본 부족·σ=0 종료 → `|r5|/σ24 < k` 종료(`k`는 `feedback.detection.z-score-k`, 위 제약 참조) → **쿨다운** → **일일 상한** → **근거 매칭**(2번 항목, 0건이면 종료) → 서술(`NarrativeService.resolvePriceMoveNarrative`, `PriceMovePromptDto`) → `PriceMoveEvent.createCrypto(...)` → 저장(`PriceMoveCardWriter` 재사용).
  - **σ 표본은 겹치지 않는 구간으로만 만든다** — `rolling-window-minutes` 간격으로 스냅샷을 자른 로그수익률 집합이며, 매 분 슬라이딩으로 만들면 표본 수와 σ 값이 통째로 달라진다(§탐지 알고리즘(코인)의 명시적 경고). 각 표본도 양 끝 스냅샷이 있을 때만 만든다.
  - **쿨다운·일일 상한 판정을 위해 `PriceMoveEventRepository`에 조회 메서드를 더한다** — "이 종목의 가장 최근 코인 카드 생성 시각"과 "이 종목의 `origin_trade_date`(KST) 기준 오늘 생성 건수". 기존 `idx_price_move_events_instrument_occurred_at` 인덱스로 충분하다(§제약 — 새 인덱스 불필요). 리포지토리 인터페이스의 기존 주석("코인 쿨다운·일일 상한 카운트(#8)는 각자의 이슈가 추가한다")이 이 항목을 가리키고 있다.
  - 카드 저장은 `price_move_events`·`price_move_event_sources`뿐이다(원장 불변).
  - 크론은 **`feedback.batch.crypto-watch-cron`**(§C-1, 매 분 30초 — `price-snapshot-cron`과 초를 어긋내 실행 순서 문제를 피한다)이고 `zone = "Asia/Seoul"`을 반드시 붙인다. `FeedbackBatchProperties`에 `cryptoWatchCron` 필드를 더한다(파일 상단 주석이 "§C-1의 나머지 크론 1종(crypto-watch)은 그 스케줄을 실제로 더하는 이슈가 함께 추가한다"로 이 항목을 가리키고 있다).
  - `spring.task.scheduling.pool.size`를 **11 → 12**로 올리고 `application.yml`·`application-crypto-real.yml`의 계산 주석을 최종 형태(§C-1의 "7개 전부 들어온 뒤 12")로 맞춘다.
  - **함정 — 자정을 넘긴 카드.** `occurred_at` 00:03 픽스처로 `windowStart(=occurred_at - rolling-window-minutes) <= windowEnd(=occurred_at)`가 유지되는지, `origin_trade_date` 컬럼이 **그날(00:03의) KST 날짜**인지 확인한다(§C-9) — `PriceMoveEvent.createCrypto`가 이미 `occurredAt.toLocalDate()`로 이 규칙을 보장하므로 여기서는 호출부가 KST `occurredAt`을 정확히 넘기는지가 관건이다.
  - **함정 — 표본을 못 만드는 것과 σ=0인 것을 구분한다.** 기동 직후(표본 < `min-sample-count`)와 가격이 전혀 안 변한 구간(σ24=0)은 둘 다 "카드 없음"이지만 원인이 다르다 — 둘 다 예외 없이 조용히 종료해야 한다.
  - 검증 — 단위(σ 표본 겹치지 않음, lookback 경계, min-sample-count 미달, 쿨다운·일일 상한, 자정 케이스) + `FeedbackBatchScheduleTest` 계열에 이 스케줄의 `zone`·프로퍼티 참조 테스트 추가.

- [ ] **4. `feedback`: `PriceMoveQueryService`의 코인 분기를 실제 "최근 24시간 카드 조회"로 교체 (FEED-006)**

  현재 `Market.CRYPTO`면 `PriceMoveListResponse.empty()`를 반환하는 임시 분기(주석에 "plan.md 8번이 이 자리에 더한다")를 실제 조회로 바꾼다.
  - 범위는 §C-2의 `ROLLING_24H`(최근 24시간)다. 노출 게이트가 없다(§C-5 "카드(코인) — 없음") — `reveal_time`을 보지 않는다.
  - `PriceMoveEventRepository`에 시장+시간 범위 조회 메서드를 더한다(market=CRYPTO, `occurredAt between [now-24h, now]`, `instrumentId` 조건). 기존 `idx_price_move_events_instrument_occurred_at` 인덱스를 그대로 쓴다.
  - `PriceMoveItem`에 `ofCrypto` 팩토리를 더한다 — `windowEnd = occurredAt`, `windowStart = occurredAt - rolling-window-minutes`(둘 다 이미 `LocalDateTime`이라 `ofStock`의 `atOriginTradeDate` 변환이 필요 없다).
  - 응답의 `originTradeDate`는 **항상 `null`**이다(§C-2, 이미 있는 `PriceMoveListResponse.of(null, moves)`를 그대로 쓰면 된다 — 새 팩토리가 필요 없다).
  - 근거 목록은 기존 `findSources`(`PriceMoveEventSourceRepository.findAllByPriceMoveEventIdIn`)를 그대로 재사용한다.
  - `docs/api-contracts.md`의 "종목 변동 원인 카드 조회" 절 예시·정렬 설명이 코인에도 맞는지 이 항목에서 확인해 두되, 문서 수정 자체는 6번 항목에서 한다.
  - 검증 — `@WebMvcTest`(코인 `instrumentId`로 호출 시 `originTradeDate=null`, 카드 0건이면 빈 배열) + 단위(24시간 창 경계, `ofCrypto`의 `windowStart` 계산).

- [ ] **5. 원장 불변 — 8개 이슈 공통 조건의 확장**

  완료 조건(공통) — "카드 생성·조회 전후로 주문·체결·계좌·잔액·보유·손익 원장이 변하지 않는다." 기존 원장 불변 테스트 패턴(`docs/agent-mistakes.md` 2026-08-04 행 — 값까지 비교, raw JDBC 스냅샷 전 `flush()`)을 그대로 확장해 이 이슈가 새로 만드는 쓰기 경로를 덮는다.
  - `CryptoPriceSnapshotService`의 매분 기록 전후로 원장 테이블이 변하지 않는다 — 이 스케줄은 Redis에만 쓴다.
  - `CryptoPriceMoveWatcher` 실행 전후로 원장 테이블이 변하지 않는다 — 유일한 쓰기 대상은 `price_move_events`·`price_move_event_sources`다.
  - `PriceMoveQueryService.getPriceMoves`의 코인 분기 조회 전후로도 원장 테이블이 변하지 않는다.
  - 검증 — 고정 `Clock` + Testcontainers 통합. 기존 원장 불변 테스트의 스냅샷 대조 패턴을 재사용한다.

- [ ] **6. 문서 동기화 — `api-contracts.md`의 코인 미구현 안내 제거, `prd.md` §3 갱신**

  `docs/api-routes.md`는 **대상이 아니다** — 이 이슈는 새 컨트롤러·새 라우트를 만들지 않는다.
  - `docs/api-contracts.md`의 "종목 변동 원인 카드 조회" 절에서 "**코인 분기는 아직 구현되지 않았다 (2026-08-04, 이슈 #180)**" 인용 블록을 통째로 걷어낸다(그 문단 자체가 "이 단서는 8번이 들어오면 지운다"고 적어 뒀다). 걷어낸 뒤 "노출 필터"·"카드 개수" 문단이 실제 코인 동작(게이트 없음, `daily-limit`건 상한)과 여전히 맞는지 확인한다.
  - `docs/prd.md` §3 "구현 현황"의 **"AI 피드백 — 코인 변동 감시"** 행(현재 `—` / **미착수** / "`012` plan의 이슈 8 미생성")을 완료로 갱신하고 근거 칸에 이 PR 번호를 적는다(CLAUDE.md 규칙 10).
  - 두 문서는 **항상 같은 커밋에서 함께 맞춘다.**

## 완료 조건 소유

이슈 #225에 배정된 9건(`plan.md` §완료 조건 배정의 **8번 행** — 코인 탐지 7 + 배치 2)과 8개 이슈 공통 조건 1건, 합계 10건이다.

| # | 완료 조건 (`spec.md` §완료 조건) | 항목 |
|---|---|---|
| 1 | (코인 탐지) 가격 스냅샷에서 5분 전 가격을 실제로 꺼낸다 | **1**(저장 형태) + **3**(실제로 꺼내 씀) |
| 2 | (코인 탐지) σ 표본이 겹치지 않는 구간으로 만들어진다 | **3** |
| 3 | (코인 탐지) `sigma-lookback-hours` 밖 스냅샷이 표본에서 제외되고, 기록 시 제거된다 | **1**(기록 시 가지치기) + **3**(조회 시 창 필터) |
| 4 | (코인 탐지) 표본이 `min-sample-count` 미만이면 카드를 만들지 않고 예외도 나지 않는다 | **3** |
| 5 | (코인 탐지) `isPriceAvailable=false`일 때 스냅샷을 기록하지 않는다 | **1** |
| 6 | (코인 탐지) 쿨다운·일일 상한이 초과 생성을 막는다 | **3** |
| 7 | (코인 탐지) 자정을 넘긴 카드의 `windowStart <= windowEnd`·`origin_trade_date` KST 날짜, 응답 `originTradeDate=null` | **3**(저장) + **4**(응답) |
| 8 | (배치 ⑭) `cron` 기반 `@Scheduled`에 전부 `zone`이 붙어 있다 | **1**(price-snapshot) + **3**(crypto-watch) |
| 9 | (배치 ⑮) `spring.task.scheduling.pool.size`가 등록된 `@Scheduled` 개수 이상이다 | **1**(10→11) + **3**(11→12) |
| 10 | (공통) 원장 불변 | **5** |

**2번 항목은 단독 소유 조건이 없다** — 3번 항목(감시)이 근거 매칭을 쓸 수 있게 하는 선행 작업이다. **6번 항목도 단독 소유가 없다** — 문서 대조다.

## 이 이슈에서 하지 않는 것

- **LLM 서술 생성·후검증·템플릿 폴백** (#147, 머지됨). `NarrativeService`·`NarrativeGenerator`·`NarrativeValidator`·`NarrativePromptBuilder`·`NarrativeTemplateBuilder`를 건드리지 않는다. `resolvePriceMoveNarrative`를 그대로 호출한다.
- **V13·엔티티·리포지토리 골격** (#160, 머지됨). `price_move_events`의 코인 전용 컬럼(`occurred_at`·nullable `window_start`/`window_end`)과 `PriceMoveEvent.createCrypto`는 이미 있다 — 새 마이그레이션 없이 그대로 쓴다.
- **뉴스·공시 수집 파이프라인** (#167, 머지됨). `NewsCollectionService`·수집기·질의어 조립을 건드리지 않는다 — 이미 저장된 기사를 읽기만 한다.
- **주식 변동 탐지·개장 전 배치·카드 조회 API** (#180, 머지됨). `PriceMoveDetector`·`PriceMoveCardService`·`FeedbackBatchService`를 건드리지 않는다. `PriceMoveController` 자체도 건드리지 않는다 — 그 안의 서비스 코인 분기만 채운다.
- **종목 뉴스 요약·개장 전 브리핑 조회 API** (#188, 머지됨). `CryptoFeedbackBatchService`(코인 요약·브리핑 매시 갱신)를 건드리지 않는다.
- **매도 직후 피드백 조회 API** (#208, 머지됨), **반사실 시뮬레이션·집단 비교** (#212, 머지됨). `PostSellFeedbackService`·`PeerStatsBatchService`를 건드리지 않는다. **코인 매도 회고는 2차에서 주식 전용이다**(spec FEED-007 각주) — 이 이슈로 열지 않는다.
- **코인 질의어 개선** (#179, 별도 이슈). 이 이슈의 선행이 아니다 — 지금 질의어로도 근거 매칭은 동작한다.
- **탐지 나머지(주식) 8건, 노출 게이트 ①~⑮, 상태값 7건, 배치 나머지 13건, 수집 6건, 매도 회고 16건, 문구 9건, API 계약 5건** — 배정은 `plan.md` §완료 조건 배정이 정본이다.
