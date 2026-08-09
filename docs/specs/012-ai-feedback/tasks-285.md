# Tasks: 012 AI 피드백 — 이슈 #285 (코인 변동 카드 근거 기사 온디맨드 수집)

> **이 문서는 이슈 #285의 범위만 담는다.** `./tasks.md`는 이슈 #225 전용, `./tasks-244.md`는 이슈 #244 전용, `./tasks-275.md`는 이슈 #275 전용이고, 이 이슈도 `plan.md` §진행 상태 8개 분할 표 **밖의 별도 이슈**다(선례 #198·#244·#275). **어디에 구현할지는 이미 결정돼 있다** — `docs/adr/0016-crypto-news-ondemand-collection.md`(승인됨)가 정본이다. 이 문서는 그 결정을 그대로 작업 항목으로 쪼갠다.
>
> **완료 조건의 정본은 이슈 #285 본문**이다(선례 #275와 같은 형태 — `spec.md`에 이 이슈 전용 완료 조건 절을 새로 만들지 않는다). 값(크론·임계값·`matchBeforeMinutes`·`dailyLimit`·`watchLockTtlSeconds`)의 정본은 `./spec.md` §C-7이고, 이 이슈는 그 값을 바꾸지 않는다.
>
> **작성 규칙(`docs/specs/README.md`)** — 항목 하나 = implementer 1회 투입 = 커밋 1개. `run-log.md`에 시각·에이전트·실행 명령·근거를 1줄씩 남긴다(장문 금지). 테스트 레벨은 ADR-0003을 따른다.

## 배경 — 결정은 끝났다, 남은 것은 구현뿐이다

- **왜 필요한가**: 뉴스 수집(`feedback.news.collect-cron`, 30분 주기)이 코인 감시(`feedback.batch.crypto-watch-cron`, 매 분)보다 30배 느리다. 변동이 터진 직후에는 근거 기사가 DB에 없는 것이 정상이고, `NewsMatcher.matchCrypto`가 0건을 돌려주는 순간 `CryptoPriceMoveWatcher.watchOne()`이 카드를 버린다. 근거창(`crypto.match-before-minutes`)을 넓혀도 "수집 이후 발행된, 변동을 실제로 설명하는 기사"는 여전히 안 들어온다.
- **어디에 두는가**(ADR-0016 §결정 1): `NewsMatcher`는 손대지 않는다("이미 저장된 것만 읽는다" 선언 유지). `CryptoPriceMoveWatcher.watchOne()`이 첫 매칭이 비면 온디맨드 수집을 직접 트리거하고 1회 재매칭한다.
- **락 범위**(ADR-0016 §결정 4, ADR-0014 갱신): 락 구간(쿨다운 확인 → 일일 상한 확인 → 근거 매칭 → LLM → 저장)의 "근거 매칭"과 "LLM" 사이에 "필요 시 온디맨드 수집 → 재매칭"이 추가된다. **TTL(45초)은 바꾸지 않는다** — 네이버 API 타임아웃(최악 15초)이 더해져도 LLM 타임아웃(20초) 기준 여유 안에 든다.
- **실패·중복 방지는 새 메커니즘이 필요 없다**(ADR-0016 §결정 5·6): `NaverNewsCollector.collect`는 실패 시 예외 대신 빈 목록을 돌려주는 기존 계약을 그대로 따르고, `watch()`의 종목별 `try/catch(RuntimeException)`(기존 코드, `CryptoPriceMoveWatcher.java` 96~103행)이 이미 그 종목만 건너뛰는 것을 보장한다. `market_news_items`의 `UNIQUE(instrument_id, url)` 제약과 `NewsCollectionService.save()`의 기존 URL 대조 로직이 중복 저장을 그대로 막는다.

## 이 이슈 전체에 걸리는 제약

- **`NewsMatcher`를 건드리지 않는다.** `match()`(주식)·`matchCrypto()`(코인) 두 메서드 다 변경 없음. 주식 경로는 이 이슈의 영향을 받지 않는다.
- **재시도는 1회만.** 재매칭 후에도 0건이면 그 자리에서 종료한다(FEED-003 그대로) — 반복 수집·반복 재매칭을 만들지 않는다(ADR-0016 §결정 3).
- **온디맨드 수집은 게이트를 전부 통과한 뒤에만 일어난다.** z-score 게이트(`|r5|/σ24 < k`) → 락 획득 → 쿨다운 → 일일 상한 → **첫 매칭**까지 지난 뒤에만 트리거된다. 어느 하나라도 걸리면 외부 호출(`NewsCollector.collect`)이 발생하면 안 된다.
- **새 마이그레이션이 없다.** 쓰기 테이블은 기존 그대로 `price_move_events`·`price_move_event_sources`·`market_news_items` 셋뿐이다.
- **새 컨트롤러·엔드포인트가 없다.** `docs/api-routes.md`·`docs/api-contracts.md`는 대상이 아니다(이슈 본문이 이미 명시).
- **본문 미저장·공시 미매칭은 기존 계약이 이미 지킨다.** `NewsCollector.collect`가 돌려주는 `CollectedNewsDto`에 애초에 본문 필드가 없고(C-004), 온디맨드 수집도 `MarketNewsItemType.NEWS`만 저장하며 `matchCrypto`도 `NEWS`만 조회한다 — 새로 막을 것이 없다.
- **`feedback.crypto.watch-lock-ttl-seconds`(45)·`match-before-minutes`(35)·`daily-limit`(6) — 어느 것도 이 이슈에서 조정하지 않는다.** TTL이 왜 그대로 충분한지는 ADR-0016 §결정 4에 이미 계산돼 있다.
- **네이버 API 일일 호출량 재확인** (이슈 §선행/충돌 주의) — 기존 배치는 종목 28(주식+코인) × 48회/일 = 1,344회/일(§외부 API 호출 상세). 온디맨드는 최악의 경우 코인 종목 12 × `daily-limit`(6) = 72회/일이 더해져 **합계 최악 1,416회/일**로, 네이버 한도(25,000회/일)에 크게 못 미친다. 4번 항목에서 이 계산을 문서에 남긴다.

## 작업 항목

- [x] **1. `NewsCollectionService.collectForInstrument(Instrument)` 신설**

  `src/main/java/com/finplay/api/feedback/service/NewsCollectionService.java`. 종목 하나만 수집하는 공개 메서드를 추가한다(ADR-0016 §결정 2).

  ```java
  public int collectForInstrument(Instrument instrument) {
      List<Instrument> sameMarket = instrumentService.getInstrumentEntities(instrument.getMarket());
      List<String> sameMarketNames = sameMarket.stream().map(Instrument::getName).toList();
      List<CollectedNewsDto> collected = newsCollector.collect(instrument, sameMarketNames);
      return save(instrument, MarketNewsItemType.NEWS, collected, LocalDateTime.now(clock));
  }
  ```

  - **기존 `save(...)` private 메서드를 그대로 재사용한다** — 중복 무시(`UNIQUE(instrument_id, url)` 대조)·`created_at`=수집 시각 규칙이 새 코드 없이 상속된다.
  - **`sameMarketNames` 조립은 `collectNews()`와 같은 규칙(같은 시장 안에서만 제목 필터)이지만 별도로 계산한다** — `collectNews()`는 시장당 한 번만 `getInstrumentEntities`를 불러 루프 밖에서 재사용하는 반면, 이 메서드는 온디맨드로 드물게(게이트를 다 통과한 뒤, 첫 매칭이 빈 경우에만) 호출되므로 매번 다시 조회해도 비용이 무시할 만하다. **`collectNews()`의 기존 루프를 이 메서드를 호출하도록 리팩터링하지 않는다** — 그러면 시장당 1회이던 `getInstrumentEntities` 호출이 종목당 1회로 늘어 기존 30분 배치의 호출 패턴이 바뀐다(불필요한 변경, 규칙 3 "surgical changes").
  - **타입은 `MarketNewsItemType.NEWS`로 고정한다.** 공시(`DisclosureCollector`)는 이 메서드가 아예 모른다 — 코인은 공시가 없다(§C-3).
  - 새 트랜잭션을 열지 않는다 — 클래스 상단 Javadoc의 "이 메서드들은 트랜잭션을 열지 않는다"(외부 HTTP 호출 중 DB 커넥션을 점유하지 않기 위해)를 그대로 따른다.
  - Javadoc에 ADR-0016 §결정 2를 인용해 "이 메서드의 유일한 소비자는 `CryptoPriceMoveWatcher`다. 코인 온디맨드 수집 전용이며, 배치 진입점(`collectNews`)과 별개로 언제든 호출될 수 있다"를 남긴다.
  - **`NewsCollectionServiceTest`(`src/test/java/com/finplay/api/feedback/service/NewsCollectionServiceTest.java`)에 단위 테스트를 추가한다.** 기존 `setUp()`의 mock 배선을 그대로 쓴다(`samsung`·`bitcoin` 픽스처, `instrumentService.getInstrumentEntities(Market.CRYPTO)` 이미 스텁됨).
    - `collectForInstrument(bitcoin)` 호출 시 `newsCollector.collect(bitcoin, List.of("비트코인"))`로 불리는지(같은 시장 종목명만 넘기는지).
    - 저장된 `MarketNewsItem`의 `createdAt`이 `clock` 기준 수집 시각인지(발행 시각이 아님) — 기존 `savesCollectedNewsWithCollectionTimeAsCreatedAt`과 같은 단정 패턴.
    - 이미 저장된 URL이면 다시 저장하지 않는지(`findExistingUrls` 스텁 재사용).
    - 반환값이 실제 저장 건수와 같은지.
    - **`holdsNoRepositoryOtherThanMarketNewsItemRepository` 테스트가 계속 통과하는지 확인한다** — 이 메서드는 새 리포지토리를 주입받지 않으므로 그대로 통과해야 한다(회귀 확인, 코드 추가 아님).
  - 검증 — 단위(ADR-0003 "서비스 로직은 단위").

- [x] **2. `CryptoPriceMoveWatcher.watchOne()`에 온디맨드 수집·재매칭 통합**

  `src/main/java/com/finplay/api/feedback/service/CryptoPriceMoveWatcher.java`. 155~159행 근방(`newsMatcher.matchCrypto` 호출 → 빈 목록이면 종료)을 다음 순서로 바꾼다(ADR-0016 §결정 3).

  ```
  sources = newsMatcher.matchCrypto(instrumentId, now)
  if (sources.isEmpty()) {
      newsCollectionService.collectForInstrument(instrument)
      sources = newsMatcher.matchCrypto(instrumentId, now)   // 재매칭은 1회만
      if (sources.isEmpty()) {
          return false   // FEED-003 그대로 — 수집 후에도 없으면 카드 미생성
      }
  }
  ```

  - **새 의존성 `NewsCollectionService`를 주입한다.** `@RequiredArgsConstructor`이므로 필드 선언 순서가 생성자 파라미터 순서다 — **`newsMatcher` 필드 바로 다음(72행 근방)에 추가한다.** 이 클래스를 `new CryptoPriceMoveWatcher(...)`로 직접 생성하는 테스트가 있으므로 **아래 3번 항목에서 그 호출부를 반드시 함께 고친다.**
  - **락 범위는 바뀌지 않는다** — 이 로직 전체가 이미 `try { ... } finally { cryptoWatchLock.unlock(...) }` 블록(150~171행) 안에 있으므로 그대로 감싸진다. 새 `try/catch`를 추가하지 않는다 — 수집 실패(예외)는 `watch()`의 종목별 `try/catch(RuntimeException)`(96~103행, 기존 코드)이 이미 처리하고, `finally`의 `unlock`도 그 예외 경로에서 그대로 호출된다(`LockReleaseGuarantee` 테스트가 이미 이 패턴을 검증하고 있다).
  - **첫 매칭에 근거가 있으면 `collectForInstrument`를 호출하지 않는다** — 불필요한 외부 호출을 만들지 않는다(§완료 조건 "게이트를 전부 통과한 뒤에만").
  - 111행 근방의 큰 주석("의사코드 순서를 그대로 따른다...")과 155행 근방의 주석("근거가 하나도 없으면 카드를 생성하지 않는다")을 갱신해 온디맨드 수집·재매칭 1회를 반영한다. ADR-0016을 인용한다.
  - 클래스 상단 Javadoc(1~44행)의 "탐지·쿨다운·근거 매칭·서술·저장을 전부 담는다" 선언에 "필요 시 온디맨드 수집 트리거"를 덧붙인다(ADR-0016 §결정 1이 이 선언의 자연스러운 연장이라고 판단한 근거).
  - 검증 — 단위(3번 항목이 이 클래스의 기존 단위 테스트 파일에 케이스를 더한다).

- [x] **3. `CryptoPriceMoveWatcherTest` 단위 테스트 — 온디맨드 수집 오케스트레이션**

  `src/test/java/com/finplay/api/feedback/service/CryptoPriceMoveWatcherTest.java`. 2번 항목이 바꾼 생성자 시그니처에 맞춰 **먼저 테스트 배선을 고친다.**

  - `private final NewsCollectionService newsCollectionService = mock(NewsCollectionService.class);` 필드를 추가하고(다른 mock 필드들과 같은 위치, `newsMatcher` 근처), `watcher(...)` 팩터리 메서드(112~117행)의 `new CryptoPriceMoveWatcher(...)` 인자에 순서에 맞춰 끼워 넣는다.
  - `mock(NewsCollectionService.class)`의 `collectForInstrument`는 스텁하지 않으면 기본으로 `0`을 반환한다(Mockito 기본값) — 기존 테스트들(이미 `stubOneMatchedSource`로 첫 매칭이 채워져 있음)은 온디맨드 경로를 타지 않으므로 **수정 없이 그대로 통과해야 한다.** 이 사실을 커밋 전에 실제로 확인한다(회귀 없음).

  새 `@Nested` 클래스(예: `OnDemandCollection`)를 추가해 다음을 검증한다.

  - **첫 매칭이 비면 수집 후 재매칭한다**: `newsMatcher.matchCrypto(...)`를 첫 호출은 빈 목록, 이후 호출은 근거 1건을 반환하도록 순서 스텁(Mockito `thenReturn(List.of()).thenReturn(matched)`)하고, `watch()` 실행 후 `verify(newsCollectionService).collectForInstrument(INSTRUMENT)`와 `verify(priceMoveCardWriter).persist(any(), eq(matched))`를 확인한다.
  - **재매칭도 비면 카드를 만들지 않고, 수집·재매칭은 정확히 1회씩만 일어난다**: `matchCrypto`가 항상 빈 목록이면 `verify(newsCollectionService, times(1)).collectForInstrument(any())`(반복 수집 없음), `verify(newsMatcher, times(2)).matchCrypto(any(), any())`(첫 매칭 + 재매칭, 그 이상 없음), `verify(priceMoveCardWriter, never()).persist(any(), any())`.
  - **첫 매칭에 근거가 있으면 수집을 호출하지 않는다**: 기존 `Evidence.persistsTheMatchedSourcesAsIsWhenEvidenceExists`류 시나리오에 `verify(newsCollectionService, never()).collectForInstrument(any())` 단정을 추가(새 테스트 또는 기존 테스트 확장 — 기존 테스트를 수정한다면 최소 diff로).
  - **게이트를 통과하지 못하면 수집이 호출되지 않는다** — 기존 `ZScoreThreshold.skipsWhenScoreIsBelowK`, `Cooldown.skipsWithinCooldownEvenWithAStrongSignal`, `DailyLimit.skipsWhenDailyLimitIsReached`, `WatchLockAcquisitionFailure.skipsCooldownEvidenceNarrativeAndPersistWhenLockAcquisitionFails` 네 테스트에 각각 `verify(newsCollectionService, never()).collectForInstrument(any())` 한 줄씩 추가한다(완료 조건 "온디맨드 수집이 게이트를 전부 통과한 뒤에만 일어난다"를 코드로 고정).
  - **수집이 예외를 던져도 락이 해제된다**: `LockReleaseGuarantee` 네스티드 클래스에 새 테스트를 추가 — `newsMatcher.matchCrypto(...)`가 빈 목록을 반환하고 `newsCollectionService.collectForInstrument(...)`가 `RuntimeException`을 던지도록 스텁, `watch()`가 예외를 던지지 않는지(`assertThatCode(...).doesNotThrowAnyException()`)와 `verify(cryptoWatchLock).unlock(INSTRUMENT.getId(), "test-lock-token")`을 확인한다 — 기존 `unlocksEvenWhenNewsMatcherThrowsAfterLockIsAcquired`와 같은 패턴이다.
  - 검증 — 단위.

- [x] **4. Testcontainers 통합 테스트 — 카드 생성 성공률 상승·중복 방지·종목별 실패 격리**

  `src/test/java/com/finplay/api/feedback/service/CryptoPriceMoveWatcherIntegrationTest.java`에 케이스를 추가한다. `NewsCollectionIntegrationTest`의 선례(`@MockitoBean private NewsCollector newsCollector;` — `FakeNewsCollector`는 빈 목록이 계약이라 저장 경로를 태울 수 없으므로 실제 협력자 대신 mock으로 갈아끼운다)를 그대로 따른다.

  - `@MockitoBean private NewsCollector newsCollector;` 필드를 추가한다.
  - **카드 생성 성공률 상승 확인 (완료 조건의 핵심)**: 근거 기사를 사전에 저장하지 않은 상태(`givenMatchingNews()` 호출 없음)에서 `newsCollector.collect(...)`가 매칭되는 기사 1건을 반환하도록 스텁하고 `watch()`를 실행한다. **온디맨드 수집으로 `market_news_items`에 그 기사가 새로 저장되고**, 그 기사를 근거로 카드 1건이 생성되며, `price_move_event_sources`가 그 기사를 가리키는지 확인한다. 이 테스트가 "근거가 DB에 없는 상태에서도 카드가 만들어진다"(이슈 완료 조건)를 직접 증명한다.
  - **수집 후에도 0건이면 카드를 만들지 않는다**: `newsCollector.collect(...)`가 빈 목록을 반환하도록 스텁(또는 근거창 밖의 발행 시각인 기사)하고 `watch()` 실행 후 카드가 0건인지 확인한다. `verify(newsCollector).collect(any(), any())`로 수집 시도 자체는 있었는지도 함께 확인한다(완전히 건너뛴 것이 아니라 "찾아봤지만 없었다"임을 구분).
  - **중복 저장 없음**: 위 "카드 생성" 시나리오로 `watch()`를 실행해 온디맨드로 기사를 저장한 뒤, 같은 `newsCollector` 스텁을 유지한 채 `NewsCollectionService.collectNews()`(30분 배치)를 이어서 호출한다. `market_news_items`에서 그 URL의 행이 여전히 1건인지 확인한다 — "30분 배치가 나중에 같은 기사를 다시 가져와도 중복 행이 되지 않는다"(완료 조건).
  - **종목별 실패 격리**: 코인 종목 2개(예: 기존 `instrument` + 새로 저장한 두 번째 크립토 종목)를 두고, `newsCollector.collect(...)`가 한 종목의 심볼에서만 `RuntimeException`을 던지도록 스텁한다. `watch()`가 예외 없이 끝나고, 실패한 종목은 카드가 없지만 건강한 종목 쪽 로직은 영향받지 않는지 확인한다(단, 두 종목 다 근거가 매칭돼야 카드가 생기므로 픽스처 설계에 주의 — 최소한 "예외가 `watch()` 밖으로 새지 않는다"와 "실패한 종목의 스냅샷 조회·매칭 시도가 있었는지"를 확인하는 것으로 충분하다).
  - **원장·읽기전용 테이블 검증 갱신**: 기존 `neverWritesOutsideThePriceMoveTables` 테스트는 그대로 둔다(그 테스트는 근거 기사를 사전에 저장해 두므로 온디맨드 수집이 트리거되지 않아 `market_news_items`가 여전히 read-only로 남는 것이 맞다 — 손대지 않는다). **새 테스트를 추가**해 온디맨드 수집이 실제로 `market_news_items`에 쓰는 시나리오에서 `LEDGER_TABLES`(주문·체결·계좌·잔액·보유·손익)만은 여전히 불변인지 확인한다 — "쓰기는 `price_move_events`·`price_move_event_sources`·`market_news_items` 뿐이다"(완료 조건)를 직접 고정한다.
  - 검증 — Testcontainers(MySQL) + 실제 Redis(`TestcontainersConfiguration`) 통합, 고정 `Clock`(`TestClockConfig`).

- [x] **5. 문서 동기화 — `spec.md`·`docs/prd.md` §3 갱신**

  **`docs/api-routes.md`·`docs/api-contracts.md`는 대상이 아니다**(컨트롤러 변경 없음, 이슈 본문이 이미 명시).

  `spec.md`에서 온디맨드 수집을 서술로 반영해야 하는 자리 넷.
  - **§탐지 알고리즘(코인)의 의사코드**(822~863행) — "근거 기사 0건이면 종료 (FEED-003과 동일)" 줄을 "근거 기사 0건이면 온디맨드 수집(ADR-0016) 후 1회 재매칭 → 그래도 0건이면 종료 (FEED-003과 동일)"로 갱신한다.
  - **§뉴스 매칭 범위**(1091행 근방) — "그래도 근거 0건이면 카드를 만들지 않으며(FEED-003), 다음 수집 때 근거가 들어와도 그 카드를 되살리지 않는다" 문장 앞에, 온디맨드 수집·1회 재매칭이 이제 먼저 시도된다는 것을 추가한다(ADR-0016 인용). "다음 수집 때 근거가 들어와도 되살리지 않는다"는 여전히 유효하다 — 온디맨드 재매칭까지 실패한 이후의 이야기다.
  - **§실패 처리 표**(1265~1292행) — "근거 기사 0건 | 카드 미생성 (설계 의도)" 행을 코인 분기가 드러나게 갱신하거나, 새 행 "코인 온디맨드 수집 실패 | 그 종목의 이번 틱만 건너뛴다. `watch()`의 기존 실패 격리와 동일 (ADR-0016)"을 추가한다.
  - **FEED-005 요구사항**(536~544행) — "근거 매칭 범위는 §C-2의 근거창(코인)이고 근거가 없으면 카드를 만들지 않는다(FEED-003과 동일)" 항목에 온디맨드 수집·1회 재매칭을 반영하는 문장을 덧붙인다(ADR-0016).

  `docs/prd.md` §3 "구현 현황" — **갱신 대상이다**(CLAUDE.md 규칙 10). 197행 "AI 피드백 — 코인 변동 감시 | FEED-005·006 코인 분기 | **완료**" 행의 근거 칸에 이 PR과 "근거 0건일 때 온디맨드 수집 후 재매칭(ADR-0016)으로 카드 생성 성공률이 오른다"를 덧붙인다. **판정은 바꾸지 않는다** — 이미 완료였던 기능의 신뢰성을 보강하는 것이 아니라(그랬다면 #244처럼 비대상), **카드가 생성되는 조건 자체가 넓어지므로**(전에는 근거가 DB에 없으면 무조건 버렸는데 이제는 그 시점에 수집을 시도한다) "제공하는 기능"이 실제로 늘어난다 — 근거 칸만 갱신 대상이고 판정은 완료 그대로 유지한다.

  `run-log.md`에 이 이슈 착수·각 커밋을 1줄씩 기록한다(다른 tasks-*.md 파일과 같은 관행).

  - 검증 — 없음(문서 대조뿐, 코드 검증 아님). 다만 `./gradlew build`는 이 항목이 마지막 커밋일 때 전체 통과를 재확인한다.

## 완료 조건 대응

정본은 **이슈 #285 본문**이다. 아래는 그 체크리스트와 작업 항목의 대응이다.

| # | 이슈 #285 완료 조건 | 항목 |
|---|---|---|
| 1 | 세 후보를 비교하고 근거와 함께 하나를 고른다 | **완료** (ADR-0016) |
| 2 | 근거 0건일 때 그 시점에 뉴스를 수집한 뒤 다시 매칭한다 | **1**(수집 메서드) + **2**(통합) |
| 3 | 수집 후에도 0건이면 카드를 만들지 않는다(FEED-003 유지) | **2** + **3**(단위) + **4**(통합) |
| 4 | 온디맨드 수집이 게이트를 전부 통과한 뒤에만 일어난다(테스트로 확인) | **3** |
| 5 | 수집이 실패해도 감시 틱 전체가 죽지 않는다 | **2**(기존 실패 격리 재사용) + **3**(예외 시 락 해제) + **4**(통합 실패 격리) |
| 6 | 수집된 기사가 기존 경로와 같은 형태로 저장되고 중복 저장이 없다 | **1**(save() 재사용) + **4**(통합 중복 확인) |
| 7 | 본문 미저장, 공시 미매칭 | **1**(기존 계약 상속, 새 코드 없음) |
| 8 | 카드 생성 성공률이 실제로 오르는 것을 테스트로 확인 | **4** |
| 9 | 기존 테스트가 전부 통과 | 전 항목(회귀 확인) |
| 10 | 쓰기는 `price_move_events`·`price_move_event_sources`·`market_news_items`뿐 | **4** |

## 이 이슈에서 하지 않는 것

- **주식 경로.** `NewsMatcher.match()`·주식 개장 전 배치는 이 이슈의 영향을 받지 않는다.
- **탐지 트리거의 실시간화.** `now`를 스냅샷 대신 최신 틱에서 읽는 것은 별도 이슈다(σ 표본 정의·`z-score-k` 재튜닝이 딸려온다).
- **30분 수집 크론 자체의 주기 변경.**
- **카드 생성 시 실시간 알림(SSE).**
- **공시(OpenDART) 온디맨드.** 코인에는 공시가 없다.
- **재시도 횟수·TTL·근거창 값 조정.** ADR-0016 §후속에 남겨 둔 실측 후 재검토 대상이다.
- **`plan.md` 갱신.** 선례(#244·#275)와 같은 이유로 이 문서(§진행 상태 8개 분할 표) 밖의 별도 이슈는 `plan.md`를 건드리지 않는다.
