# Plan: 요약·브리핑 조회 캐시 (이슈 #245)

## 관련 문서

- Spec: `./spec.md`
- **설계 정본: `docs/adr/0015-feedback-query-cache.md`** (상태: 승인됨) — 이 plan은 그 8개 결정의 구현 배치도이며, 여기서 설계를 다시 정하지 않는다.
- ADR-0014(코인 감시 Redis 락) — `RedisLock` 추출의 출처. §후속이 "재사용이 실제로 필요해지면(예: #245) 그때 추출한다"고 예고한 그 시점이다.
- ADR-0002(레이어드 아키텍처) — `com.finplay.api.feedback` 안에서 해결한다.
- ADR-0003(테스트 전략) — 서비스 로직 단위, Redis 관련은 Testcontainers 통합.
- ADR-0011 / PRD C-005 — 자동 테스트로 실제 외부 API를 호출하지 않는다.
- 상위 spec: `docs/specs/012-ai-feedback` §C-2·§C-4·§C-5·§C-9(조회 계약), FEED-008·FEED-009.

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| — | — | — | — | **엔드포인트 추가·변경·삭제가 없다** |

**controller를 건드리지 않는다.** 캐시는 `InstrumentNewsQueryService`·`MarketBriefingService`의 내부에 들어가고, 두 조회 API(`GET /api/instruments/{id}/news`, `GET /api/market/briefing`)의 요청·응답·오류 계약은 한 글자도 바뀌지 않는다(ADR-0015 §결과). 따라서 **`docs/api-routes.md`·`docs/api-contracts.md` 수정이 이 spec 범위에 없다**(CLAUDE.md 규칙 7의 대상은 controller 변경이며 여기 해당 없음). 마무리 동기화 모드에서 재확인한다.

## 입력 명세

사용자 입력(요청 필드)이 새로 생기지 않는다. 새로 도입되는 것은 **설정값**이므로 같은 형식으로 명세한다.

### `feedback.query-cache.*` (신설 — `FeedbackQueryCacheProperties`)

| 필드 | 필수 | 검증·기본값 |
|---|---|---|
| `enabled` | 선택 | `@DefaultValue("true")`. `false`면 `FeedbackQueryCache`가 항상 로더로 직행한다 — **운영 킬 스위치다.** 이 저장소의 첫 조회 경로 캐시이고, 사용자가 기다리는 읽기 앞단에 새 계층이 들어가며, 폴백(DB 직행)이 이미 검증된 기존 경로라 재배포 없이 즉시 되돌릴 수단을 둔다. 완료 조건의 "캐시를 끈 대조군"을 운영 코드 경로 그대로 재현할 수 있는 것은 **부수 효과**이지 이 스위치를 두는 이유가 아니다 |
| `lock-ttl-millis` | 선택 | `@DefaultValue("1000")`. `1` 미만이면 기동 실패 — 0·음수는 `Duration`이 Redis 명령 오류를 유발하고 그것을 `catch(RuntimeException)`이 삼켜 **락이 영구히 획득 실패**가 된다(#244 리뷰에서 `watch-lock-ttl-seconds`에 실제로 났던 문제와 같은 형태) |
| `wait-millis` | 선택 | `@DefaultValue("300")`. `0` 미만이면 기동 실패. `0`은 "대기하지 않고 즉시 DB 직행"이라 유효한 값이다 |
| `poll-millis` | 선택 | `@DefaultValue("20")`. `1` 미만이면 기동 실패 — 0이면 폴링이 바쁜 대기(busy-wait)가 되어 CPU를 태운다 |

- 값은 yml(`application.yml`)과 `@DefaultValue` 양쪽에 둔다 — `FeedbackCryptoProperties`·`FeedbackNewsProperties`와 같은 방침이다(설정 없이도 기동하는 것은 `@DefaultValue`가 보장하고, 운영 중 조정은 항상 이기는 yml만 고친다).
- **세 숫자는 실측이 아니라 추정이다**(ADR-0015 §후속). 조회 원본이 인덱스로 덮인 단일 행·구간 조회라 수 ms라는 전제에서 나왔다. **`wait-millis`가 원본 소요보다 짧으면 방어가 무력해진다**(대기 타임아웃 후 전부 DB 직행) — 이 하한을 프로퍼티 주석에 남긴다.
- 등록은 `FeedbackQueryCacheConfig`(`@Configuration(proxyBeanMethods = false)` + `@EnableConfigurationProperties`). 프리픽스마다 설정 클래스를 나누는 기존 관례(`FeedbackCryptoConfig`)를 따른다 — 바인딩 테스트가 이 클래스 하나만 올리는 슬라이스여야 "잘못된 값이면 기동 실패" 단정이 다른 이유로 통과하지 않는다.

## 데이터 모델

**스키마 변경 없음. Flyway 마이그레이션을 추가하지 않는다**(ADR-0015 §8, ADR-0004 무관). 저장소는 Redis뿐이다.

### 캐시 키와 값 (ADR-0015 §1·§7)

접두사 `feedback:query-cache:`, 락은 `feedback:query-cache:lock:` + 같은 접미사.

| 항목 | 키 | 값 | TTL(안전망) |
|---|---|---|---|
| 주식 요약 텍스트 | `stock-summary:{instrumentId}:{originTradeDate}:{scope}` | 요약 문자열 | `PRE_MARKET` → 오늘 15:30, `FULL` → 익일 09:00 |
| 코인 요약 텍스트 | `crypto-summary:{instrumentId}` | 요약 문자열 | 다음 정시 05분 |
| 주식 브리핑 텍스트 | `stock-briefing-text:{originTradeDate}` | 브리핑 문자열 | 익일 09:00 |
| 주식 브리핑 items | `stock-briefing-items:{originTradeDate}:{maxItemsPerBriefing}` | `BriefingNewsItem` 목록(JSON) | 익일 09:00 |
| 코인 브리핑 텍스트 | `crypto-briefing-text` (단일 키) | 브리핑 문자열 | 다음 정시 05분 |

- **`scope`가 키에 있는 것이 장 마감 전후 전환의 정확성을 담당한다.** TTL은 안전망이지 정확성의 근거가 아니다 — 15:30을 넘기면 조회가 `FULL` 키를 보므로 `PRE_MARKET` 값이 남아 있어도 노출되지 않는다.
- **`items` 키에 `max-items-per-briefing`(`FeedbackNewsProperties`) 값을 넣는 것도 같은 이유다.** 캐시하는 값이 절단 후 목록이므로, 설정을 바꾸고 재배포해도 키가 갈리지 않으면 Redis에 남은 옛 길이 목록이 TTL(최대 익일 09:00)까지 그대로 나간다 — **예외도 로그도 없이 화면 목록 길이만 틀리는** 종류의 결함이고, 이 저장소가 §C-7에서 특히 경계하는 형태다. 키 구성요소 하나로 막을 수 있는 것을 문서 주의로 남기지 않는다. 값을 바꾸면 키가 자연히 갈려 옛 목록은 아무도 읽지 않고 TTL로 사라진다.
- **절단 전 목록을 캐시하는 방식은 쓰지 않는다.** `collectPreMarketItems`는 시장 전체 목록이라 값이 커진다 — 절단 후 목록을 담고 상한을 키에 넣는 편이 싸다.
- **캐시하지 않는 것**: 주식 요약 `items`, 코인 요약·브리핑 `items`. 그 구간 질의가 §C-5 노출 게이트의 구현이다.
- **주식 요약 `items`가 캐시되지 않으므로 주식 요약 조회의 DB 감소는 부분적이다** — 요청당 약 5건 중 1건이다. 더 줄이려면 계약을 바꿔야 하고, 그 교환을 하지 않기로 했다(ADR-0015 §결과).

### TTL 계산 (ADR-0015 §2)

`Clock`을 주입받아 매 저장마다 "다음 경계까지 남은 시간"을 계산한다.

- 코인 → `now`보다 뒤인 **가장 가까운 정시 05분**(`feedback.batch.crypto-cron` = `0 5 * * * *`). `now`가 10:03이면 10:05, 10:07이면 11:05다.
- 주식 `PRE_MARKET` → 오늘 `MarketSessionTimes.MARKET_CLOSE_TIME`(15:30).
- 주식 `FULL`·브리핑 → 익일 `MarketSessionTimes.MARKET_OPEN_TIME`(09:00).
- 시각 상수는 **새로 만들지 않고 `MarketSessionTimes`를 그대로 본다** — 두 곳에 리터럴을 두면 한쪽만 바뀌었을 때 캐시와 조회가 조용히 갈린다.
- **계산 결과가 0 이하면 저장하지 않는다.** 정상 경로에서는 발생하지 않지만(15:30 이후에는 `scope`가 `FULL`이다) 경계 계산 버그가 음수 TTL로 Redis 명령 오류를 내는 것을 막는 방어다.

### 값 직렬화

- 텍스트 4종은 문자열 그대로 저장한다(`StringRedisTemplate`).
- 주식 브리핑 `items`는 `ObjectMapper`로 JSON 직렬화한다. `BriefingNewsItem`에 `LocalDateTime` 필드가 있으므로 **`JavaTimeModule`이 등록된 `ObjectMapper` 빈**(Boot가 이미 제공하는 것)을 주입받아 쓴다. 직접 `new ObjectMapper()`를 만들지 않는다 — 그러면 시각이 epoch 숫자로 나가 역직렬화 계약이 응답 직렬화와 갈린다.
- **역직렬화 실패는 캐시 미스로 취급한다**(형식이 바뀐 옛 값이 남은 경우). 예외를 던져 조회를 실패시키지 않는다.

## 구성요소 설계

### 신설: `RedisLock` (`com.finplay.api.feedback.service`)

`CryptoWatchLock`에서 **획득·해제 메커니즘만** 추출한다(ADR-0015 §4).

```
Optional<String> tryLock(String key, Duration ttl);   // SET NX PX, 성공 시 토큰
void unlock(String key, String token);                // Lua check-then-delete
```

- 키 조립과 TTL 결정은 **소비자가 한다** — 그것이 `CryptoWatchLock`을 그대로 재사용하지 않는 이유다(접두사 `crypto-watch`, TTL 45초가 코인 감시 전용으로 박혀 있다).
- Redis 예외는 삼키고 "획득 실패"/"해제 실패"로 처리한다. **로그 레벨 관례를 그대로 옮긴다** — 정상 경합은 `DEBUG`, Redis 장애는 `WARN`.
- `CryptoWatchLock`은 **클래스로 그대로 남고** 내부에서 `RedisLock`을 부른다. 공개 시그니처(`tryLock(Long)`·`unlock(Long, String)`)를 바꾸지 않는다 — `CryptoPriceMoveWatcher`와 기존 테스트(`mock(CryptoWatchLock.class)`를 쓰는 `CryptoWatchLockConcurrencyIntegrationTest` 포함)가 수정 없이 통과해야 한다.

### 신설: `FeedbackQueryCache` (`com.finplay.api.feedback.store`)

캐시 키 조립·TTL 계산·직렬화·락 게이트를 **전부 갖는 유일한 클래스**다(ADR-0015 §5). 조회 서비스는 `getOrLoad` 형태로만 쓴다. `PriceStore`·`RankingStore`·`CryptoWatchLock`과 같은 패턴이다 — `StringRedisTemplate`을 직접 쓰고 키 조립을 한 클래스에 가둔다(`docs/conventions.md`).

공개 메서드(키 조립을 밖으로 새지 않게 항목마다 하나씩 둔다):

```
Optional<String> getOrLoadStockSummaryText(Long instrumentId, LocalDate originTradeDate,
                                           NewsSummaryScope scope, Supplier<Optional<String>> loader);
Optional<String> getOrLoadCryptoSummaryText(Long instrumentId, Supplier<Optional<String>> loader);
Optional<String> getOrLoadStockBriefingText(LocalDate originTradeDate, Supplier<Optional<String>> loader);
List<BriefingNewsItem> getOrLoadStockBriefingItems(LocalDate originTradeDate,
                                                   Supplier<List<BriefingNewsItem>> loader);
Optional<String> getOrLoadCryptoBriefingText(Supplier<Optional<String>> loader);

void evictCryptoSummaryText(Long instrumentId);
void evictCryptoBriefingText();
```

- **로더의 반환이 "없음"이면 캐시하지 않는다**(ADR-0015 §3). 텍스트는 `Optional.empty()`, 목록은 빈 리스트가 그 신호다. `EMPTY`/`UNAVAILABLE` 상태가 여기로 매핑된다.
- **빈 목록도 음성 결과다** — ADR-0015 §3의 "음성 결과 미캐시"를 목록에 적용한 것이다. 근거는 텍스트와 같다: 브리핑 `items`가 0건인 것은 **수집 배치가 아직 그 구간을 채우지 않은 상태**일 수 있고(배포 직후 이틀은 근거 구간이 비는 것이 정상이다 — FEED-009), 그 0건을 캐시하면 수집이 뒤늦게 돌아 기사가 들어와도 익일 09:00까지 화면이 빈 목록을 본다.
- 내부 공통 경로 `getOrLoad(항목, 키접미사, ttl, 로더, 직렬화기)` 하나가 아래 순서를 수행한다.

```
1. enabled=false → 로더 직행 (캐시 접촉 없음)
2. GET → 값이 있으면 역직렬화해 반환 (원본 호출 0회)
3. RedisLock.tryLock(락키, lock-ttl-millis)
   3-a. 획득 → 로더 실행 → 양성이면 SET(값, 계산된 TTL) → unlock → 반환
   3-b. 실패 → poll-millis 간격으로 최대 wait-millis 동안 GET 재시도, 채워지면 반환
   3-c. 대기 타임아웃 → 로더 직행(fail-open). 이 경로는 캐시에 쓰지 않는다
        — 락을 쥔 요청이 곧 채우므로 락 없는 쓰기를 만들 이유가 없고, 오히려
          느린 로더가 나중에 깨어나 락을 쥔 쪽이 이미 채운 새 값을 옛 값으로
          덮어쓸 수 있다 (ADR 미기재 세부, 여기서 확정)
4. 위 어느 단계든 Redis가 예외를 던지면 삼키고 캐시 미스와 같게 처리해 로더로 내려간다
```

- **`unlock`은 `finally`에서 부른다.** 로더가 예외를 던져도 락이 TTL까지 남지 않게 한다.
- **`Thread.sleep`은 `poll-millis` 단위로만 쓰고 인터럽트를 삼키지 않는다** — `InterruptedException`을 잡으면 `Thread.currentThread().interrupt()`로 상태를 복원하고 즉시 로더로 직행한다(요청 스레드가 취소된 상황에서 계속 도는 것이 더 나쁘다).

### 변경: 조회 2곳

- **`InstrumentNewsQueryService`** — 주식 경로의 `instrumentNewsSummaryRepository.findBy...` 호출을 `getOrLoadStockSummaryText(...)`로 감싼다. 코인 경로의 `findFirstBy...`는 `getOrLoadCryptoSummaryText(...)`로 감싼다. **`items` 수집(`collectVisibleItems`·코인 24시간 창)은 그대로 둔다.**
  - 주의: 현재 코드는 `Optional<InstrumentNewsSummary>`의 **존재 여부**로 `EMPTY`(4번)와 `UNAVAILABLE`(5번)을 가른다. 캐시는 **텍스트만** 담으므로 캐시 적중은 곧 "행이 있고 서술도 있다" = `READY`다. 미적중이면 지금 로직 그대로 DB에서 판정한다 — **판정 순서를 바꾸지 않는다.**
- **`MarketBriefingService`** — 주식 조회 경로의 `items` 조립(`collectPreMarketItems` + 절단 + 매핑)을 `getOrLoadStockBriefingItems(...)`로, `marketBriefingRepository.findByMarketAndOriginTradeDate`를 `getOrLoadStockBriefingText(...)`로 감싼다. 코인 조회 경로의 `findFirstByMarket...`은 `getOrLoadCryptoBriefingText(...)`로 감싼다. **코인 `items`(`collectRollingItems`)는 그대로 둔다.**
  - 캐시 적중 시 주식 브리핑 조회의 DB는 **4건 → 0건**이 된다(뉴스 구간 스캔 + 공시 + 브리핑 행).
  - **생성 경로(`generateStockBriefing`)는 캐시를 보지 않는다.** 배치가 캐시된 목록으로 LLM을 부르면 절단 상한이 다른 값(`max-items-per-summary` vs `max-items-per-briefing`)이라 조용히 틀린다(§C-7).

### 변경: 코인 무효화 2곳 (ADR-0015 §3)

- `InstrumentNewsSummaryService.refreshCryptoSummary` — 반환이 `Optional.empty()`가 **아닐 때만** `evictCryptoSummaryText(instrumentId)`.
- `MarketBriefingService.refreshCryptoBriefing` — 반환이 `Optional.empty()`가 **아닐 때만** `evictCryptoBriefingText()`.
- 건너뛴 실행(새 기사 없음·창 안 기사 0건)은 무효화하지 않는다 — 값이 안 바뀌었는데 지우면 다음 조회가 불필요하게 DB로 간다.
- 이 무효화가 TTL 경계와 배치 실행이 겹치는 순간(경계 직후 옛 행이 다시 캐시되는 경우)을 덮는다.

## 동시성 설계

- **인스턴스 내 방어(`synchronized`·`@Cacheable(sync = true)`)를 쓰지 않는다.** 다중 인스턴스 전환이 예정돼 있고(ADR-0014 §맥락), 그때 인스턴스 수만큼 원본이 불린다. "동시 요청 N건에서 원본 1회"를 인스턴스 경계 너머까지 보장하는 것은 공유 상태를 쓰는 방식뿐이다.
- **fail-open이다** — #244(fail-closed, 그 틱을 건너뜀)와 반대이며, 부가 기능인 배치와 사용자가 기다리는 조회 응답의 차이다.
- 단일 Redis 기준 락이다 — 다중화 시 페일오버 순간의 유실 가능성은 ADR-0014와 같은 조건이며 같은 시점에 함께 재검토한다.

## 테스트 계획

ADR-0003을 따른다. **실제 외부 API(OpenAI·빗썸·KIS)를 호출하는 테스트를 만들지 않는다**(ADR-0011, PRD C-005).

### 단위 (JUnit5 + Mockito)

- `FeedbackQueryCacheTest` — TTL 경계 계산(코인 다음 정시 05분, 주식 `PRE_MARKET`/`FULL`), 음성 결과 미캐시, `enabled=false` 시 로더 직행, Redis가 예외를 던질 때 로더로 내려가고 예외가 새지 않음, 역직렬화 실패 시 캐시 미스 취급. `Clock.fixed`로 시각을 고정한다.
- `FeedbackQueryCachePropertiesTest` — 잘못된 값(`lock-ttl-millis` 0 등)이면 기동 실패. `ApplicationContextRunner` 슬라이스로 `FeedbackQueryCacheConfig` 하나만 올린다.
- 조회 서비스 두 곳의 기존 단위 테스트 — 캐시를 mock으로 넣고 **상태값 판정 순서가 그대로인지** 확인한다.

### 슬라이스

- `@DataJpaTest`·`@WebMvcTest` **신규 대상 없음** — 새 쿼리도 새 엔드포인트도 없다. 기존 슬라이스 테스트가 그대로 통과하는 것이 계약 불변의 증거다.

### 통합 (`@SpringBootTest` + Testcontainers)

`TestcontainersConfiguration`이 MySQL과 Redis를 이미 싱글턴으로 제공한다.

- **원본 호출 횟수 대조** — `@MockitoSpyBean`으로 repository를 감싸(실제 호출은 그대로 두고 횟수만 센다) 같은 조회를 N번 반복한다. `enabled=false`(대조군)에서 N회, `enabled=true`에서 1회.
- **동시 요청 대조** — 아래 "동시성 테스트 설계" 참고.
- **범위 전환** — `Clock`을 15:29/15:31로 고정한 두 조회가 서로 다른 요약을 본다.
- **코인 주기** — TTL이 다음 정시 05분을 넘지 않는다(`redisTemplate.getExpire`로 확인).
- **코인 무효화** — 배치가 갱신에 성공한 뒤 조회하면 새 값. 건너뛴 실행 뒤에는 캐시가 유지된다. `NarrativeService`는 `@MockitoBean`으로 대체한다(실 LLM 호출 없음, `CryptoFeedbackBatchIntegrationTest`와 같은 방식).
- **Redis 장애** — 조회가 200이고 응답 본문이 정상이다.

### 동시성 테스트 설계 (이 spec에서 가장 틀리기 쉬운 부분)

`CryptoWatchLockConcurrencyIntegrationTest`(#244)의 방식을 그대로 따른다 — **한 클래스 안에서 대조군과 방어군을 나란히 둔다.**

1. **겹침 강제**: 모든 스레드가 `getOrLoad` 호출 직전 `CyclicBarrier(N)`에서 만난다(진입 배리어). 순차 실행이면 아무것도 증명하지 못한다.
2. **대조군(방어 끔)**: `RedisLock`을 `tryLock`이 **호출마다 서로 다른 토큰으로 매번 성공**하도록 스텁한 mock으로 바꿔치기해 별도 `FeedbackQueryCache` 인스턴스를 조립한다(#244가 `CryptoWatchLock`에 쓴 것과 똑같은 수법 — 상호 배제를 전혀 하지 않으면서 호출부에는 "내가 락을 얻었다"로 보이는 상태). 여기서 **로더 안에 `CyclicBarrier(N)`를 하나 더** 두어 N개 스레드가 모두 원본에 진입한 상태를 결정론적으로 만든다. 기대: 원본 **N회**.
3. **방어군(방어 켬)**: 스프링 빈(진짜 Redis 락)을 쓴다. 기대: 원본 **1회**.
   - **로더 안에 배리어를 두면 안 된다** — 상호 배제가 실제로 동작하면 로더에 들어오는 스레드가 1개뿐이라 `CyclicBarrier(N)`가 영원히 대기해 테스트가 데드락에 빠진다. 방어군은 진입 배리어만 쓴다.
   - 테스트 프로퍼티로 `feedback.query-cache.wait-millis`를 넉넉히(예: 5000) 올린다. 기본값 300ms는 운영 추정치라, 느린 CI에서 원본이 그보다 오래 걸리면 대기 스레드가 fail-open으로 DB에 직행해 **방어가 아니라 타이밍 때문에** 단정이 깨진다.
4. **결정론적 보조 단정 2개** (타이밍에 의존하지 않는다, PR #254 리뷰 [권장 5]와 같은 취지):
   - 캐시를 미리 채운 뒤 조회하면 원본 호출 **0회**.
   - 테스트 스레드가 락을 먼저 쥔 채로 조회하면 `wait-millis` 대기 후 **fail-open으로 원본 1회가 불리고 응답이 정상**이다(락 보유 중에도 조회가 실패하지 않는다는 계약).
5. `@Transactional`을 쓰지 않는다 — 여러 스레드가 각자 다른 커넥션에서 다투므로 테스트 트랜잭션 안의 픽스처는 다른 스레드에 보이지 않는다. `saveAndFlush`로 커밋하고 `@AfterEach`에서 직접 정리한다(`LimitOrderConcurrencyIntegrationTest`·#244와 같은 방침).
6. **캐시 키를 테스트마다 정리한다** — 공유 Redis 싱글턴이라 다른 테스트가 남긴 값이 있으면 "원본 N회" 단정이 조용히 무력해진다. `@AfterEach`에서 이 테스트가 쓴 키를 지운다.
