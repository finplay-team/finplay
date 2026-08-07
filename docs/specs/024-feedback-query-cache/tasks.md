# Tasks: 요약·브리핑 조회 캐시 (이슈 #245)

항목 1개 = 커밋 1개. 설계 근거는 전부 `docs/adr/0015-feedback-query-cache.md`이며 구현 중 그와 어긋나는 필요가 생기면 구현하지 말고 새 ADR을 제안한다(CLAUDE.md 규칙 2).

**대조군이 항목마다 다르다 — 섞으면 무엇 덕인지 분리되지 않는다.**

| 항목 | 증명하려는 것 | 대조군(무력화 대상) |
|---|---|---|
| 3·4 | **캐시**가 있어서 DB 조회가 주는가 | `enabled=false` — 캐시를 끈다 |
| 6 | **락**이 있어서 동시 요청에도 원본이 1회인가 | mock `RedisLock` — **캐시는 켠 채 락만** 무력화한다 |

항목 6에서 캐시까지 끄면 락 효과가 분리되지 않는다 — 캐시가 없으면 원본이 N회인 것은 당연하고, 그것은 락에 대해 아무것도 말해 주지 않는다. #244 PR #254 리뷰에서 받은 지적이 정확히 이 구분이다.

- [x] **1. `RedisLock` 추출 + `CryptoWatchLock`이 그 위에 얹힌다** (ADR-0015 §4)
  - `RedisLock`(`com.finplay.api.feedback.service`) 신설 — `tryLock(String key, Duration ttl)` / `unlock(String key, String token)`. SET NX PX + Lua check-then-delete를 `CryptoWatchLock`에서 그대로 옮기고, 키 조립과 TTL은 소비자가 정한다. Redis 예외는 삼켜 "획득 실패"로 처리하고 로그 레벨 관례(정상 경합 `DEBUG`, 장애 `WARN`)를 유지한다.
  - `CryptoWatchLock`은 클래스로 남고 내부에서 `RedisLock`을 부른다 — **공개 시그니처를 바꾸지 않는다.**
  - 검증: `CryptoWatchLockConcurrencyIntegrationTest`를 포함한 **#244의 기존 테스트가 한 줄도 수정 없이 통과**한다. `RedisLock` 자체의 상호 배제(같은 키 두 번째 `tryLock`이 실패, 토큰이 다르면 `unlock`이 아무것도 지우지 않음, TTL 만료 후 재획득)를 Testcontainers Redis로 확인한다.
  - **(2026-08-07 정정, PR #257 리뷰)** 위 검증 줄이 실제 결과와 다르다. 리뷰 1라운드에서 `CryptoWatchLock`이 `RedisLock`을 주입받도록 바뀌며 **생성자 시그니처가 달라져**, 그 생성자를 직접 부르는 `CryptoWatchLockTest`·`CryptoWatchLockIntegrationTest`의 **조립 두 줄을 고쳤다**(단정은 무수정). **`CryptoWatchLockConcurrencyIntegrationTest`는 무수정 통과가 맞다.** 애초에 이 항목이 지키려던 것은 동작 회귀 금지였고, 생성자 인자를 고정하려던 것이 아니다 — 그 구분을 남기지 않으면 다음 사람이 "전부 무수정"을 사실로 믿는다.

- [x] **2. `FeedbackQueryCacheProperties`·`FeedbackQueryCacheConfig` + `FeedbackQueryCache` 신설** (ADR-0015 §2·§3·§5·§6·§7)
  - `feedback.query-cache.*` 프로퍼티 4개(`enabled`·`lock-ttl-millis`·`wait-millis`·`poll-millis`) — yml과 `@DefaultValue` 양쪽에 값을 두고, 조용히 방어를 무력화하는 값은 기동 실패로 막는다. **`enabled`는 운영 킬 스위치다** — 첫 조회 경로 캐시를 재배포 없이 되돌리는 수단이며, 폴백(DB 직행)이 이미 검증된 기존 경로다.
  - `FeedbackQueryCache`(`com.finplay.api.feedback.store`) — 키 조립(`feedback:query-cache:`)·TTL 계산(`MarketSessionTimes` 상수와 `Clock` 사용)·직렬화(주입받은 `ObjectMapper`)·락 게이트(캐시 확인 → 락 → 로더 → 저장 / 실패 시 폴링 대기 → 타임아웃이면 fail-open, **이 경로는 캐시에 쓰지 않는다** — 느린 로더가 나중에 깨어나 새 값을 덮어쓸 수 있다)를 전부 갖는다. **`@EnableCaching`·`@Cacheable`·`spring-boot-starter-cache`를 쓰지 않는다.**
  - 브리핑 `items` 키에 **목록 절단 상한(`max-items-per-briefing`)을 넣는다** — 설정을 바꾸면 키가 자연히 갈려 옛 길이 목록이 남지 않는다.
  - 검증(단위, `Clock.fixed`): 코인 TTL이 다음 정시 05분, 주식 `PRE_MARKET`은 오늘 15:30·`FULL`은 익일 09:00. 로더가 "없음"을 반환하면 저장하지 않는다. `enabled=false`면 캐시를 아예 접촉하지 않는다. Redis가 예외를 던져도 로더 결과가 그대로 나오고 예외가 새지 않는다. 역직렬화 실패는 캐시 미스로 처리한다. 프로퍼티 바인딩 실패는 `ApplicationContextRunner` 슬라이스로 확인한다.

- [x] **3. 요약 조회 배선 — `InstrumentNewsQueryService`** (ADR-0015 §1)
  - 주식 요약 텍스트를 `getOrLoadStockSummaryText`로, 코인 요약 텍스트를 `getOrLoadCryptoSummaryText`로 감싼다. **`items` 수집 경로(주식 `collectVisibleItems`·코인 24시간 창)는 건드리지 않는다** — 그것이 §C-5 노출 게이트다.
  - §C-4 판정 순서(1~6번)를 바꾸지 않는다. 캐시 적중은 곧 `READY`이고, 미적중이면 지금 로직 그대로 DB에서 `EMPTY`/`UNAVAILABLE`을 가른다.
  - 검증(통합, `@MockitoSpyBean`으로 repository 호출 횟수 카운트) — **대조 대상은 캐시다**: `enabled=false`(대조군)에서 같은 조회 N번 → 요약 행 조회 **N회**, `enabled=true`에서 **1회**. 기존 조회 테스트가 전부 그대로 통과한다(계약 불변).

- [x] **4. 브리핑 조회 배선 — `MarketBriefingService`** (ADR-0015 §1)
  - 주식 브리핑 텍스트와 **`items`**(시각 비의존인 유일한 목록)를 각각 캐시하고, 코인 브리핑 텍스트를 캐시한다. **코인 `items`(`collectRollingItems`)는 건드리지 않는다.**
  - **생성 경로(`generateStockBriefing`)는 캐시를 보지 않는다** — 절단 상한이 조회(`max-items-per-briefing`)와 다르다(§C-7).
  - 검증(통합, 호출 횟수 카운트) — **대조 대상은 캐시다**: `enabled=false`(대조군)에서 주식 브리핑 조회 1건당 **DB 4건**, `enabled=true` 적중 시 **0건**. 코인 브리핑도 같은 방식으로 텍스트 조회 감소를 대조한다. 절단 상한을 바꾼 뒤 조회하면 **새 길이 목록**이 나온다(키가 갈린다).

- [x] **5. 코인 배치 무효화 2곳** (ADR-0015 §3)
  - `InstrumentNewsSummaryService.refreshCryptoSummary`·`MarketBriefingService.refreshCryptoBriefing`이 **실제 갱신에 성공했을 때만**(반환이 `Optional.empty()`가 아닐 때만) 해당 캐시 키를 지운다. 주식은 무효화하지 않는다(한 번 생긴 값이 그날 안 바뀐다).
  - 검증(통합, `NarrativeService`는 `@MockitoBean`): 조회 → 배치 갱신 → 재조회에서 **새 값**이 나온다. 갱신을 건너뛴 배치 실행(새 기사 없음) 뒤에는 캐시가 그대로 남아 원본이 다시 불리지 않는다. 음성 결과(요약 행 없음) 상태로 조회한 뒤 배치가 행을 만들면 그다음 조회가 새 값을 본다.

- [x] **6. 만료 쏠림 방어 대조 통합 테스트** (ADR-0015 §4, 완료 조건의 핵심)
  - `CryptoWatchLockConcurrencyIntegrationTest`(#244)의 대조 구조를 그대로 따라 **한 클래스에 대조군과 방어군을 나란히 둔다.**
  - **대조 대상은 락이다 — 여기서는 `enabled=false`를 쓰지 않는다.** 양쪽 모두 캐시를 켠 채로 두고 **락만** 무력화한다(위 표 참고). 캐시까지 끄면 원본이 N회인 것이 캐시 부재 탓인지 락 부재 탓인지 분리되지 않는다.
  - **대조군(락 무력화, 캐시는 켬)**: `tryLock`이 호출마다 서로 다른 토큰으로 매번 성공하는 mock `RedisLock`으로 별도 `FeedbackQueryCache`를 조립하고(상호 배제를 전혀 하지 않으면서 호출부에는 "내가 락을 얻었다"로 보이는 상태), **로더 안 `CyclicBarrier(N)`**로 N개 스레드가 모두 원본에 진입한 상태를 결정론적으로 만든다 → 원본 **N회**. 캐시가 켜져 있는데도 N회인 것이 요지다 — 만료 찰나에는 캐시가 비어 있어 캐시만으로는 쏠림을 막지 못한다.
  - **방어군(락 켬, 캐시도 켬)**: 스프링 빈(진짜 Redis 락) → 원본 **1회**. **로더 안에 배리어를 두지 않는다** — 상호 배제가 동작하면 로더에 1개만 들어와 `CyclicBarrier(N)`가 영원히 대기해 데드락이다. 겹침은 `getOrLoad` 호출 직전의 진입 배리어로 강제한다.
  - 테스트 프로퍼티로 `wait-millis`를 넉넉히 올린다 — 기본 300ms는 운영 추정치라 느린 CI에서 fail-open이 먼저 터지면 방어가 아니라 타이밍 때문에 단정이 깨진다.
  - **결정론적 보조 단정 2개**: 캐시를 미리 채우면 원본 **0회**. 테스트 스레드가 락을 먼저 쥐고 있으면 대기 후 **fail-open으로 원본 1회 + 응답 정상**(락 보유 중에도 조회가 실패하지 않는다).
  - `@Transactional`을 쓰지 않고 `saveAndFlush` + `@AfterEach` 정리, **이 테스트가 쓴 캐시 키도 `@BeforeEach`에서 지운다**(공유 Redis 싱글턴이라 잔여 값이 있으면 "N회" 단정이 조용히 무력해진다).

- [x] **7. 경계 조건 통합 테스트 — 범위 전환·코인 주기·Redis 장애·원장 불변**
  - **주식 범위 전환**: `Clock`을 15:29/15:31로 고정한 두 조회가 서로 다른 요약을 본다(같은 종목·같은 거래일). `scope`가 키에 있어 자동으로 갈리는 것을 확인한다.
  - **코인 매시 주기**: 저장된 키의 실제 TTL(`getExpire`)이 **다음 정시 05분을 넘지 않는다.**
  - **Redis 장애**: Redis에 닿지 못하는 상태에서 요약 조회·브리핑 조회가 **200과 정상 응답 본문**을 낸다(캐시 미스로 취급).
  - **원장 불변**: 조회 전후로 주문·체결·계좌·잔액·보유·손익이 변하지 않는다.
  - 마무리로 `./gradlew build` 통과를 확인한다(테스트 + 포맷 + 커버리지).
