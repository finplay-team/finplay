# ADR-0018: 코인 변동 카드 확정 push는 신설 코인 SSE 스트림 위에 Redis pub/sub 팬아웃으로 얹는다

- 상태: 승인됨
- 날짜: 2026-08-10
- 관계: 이슈 #286. `docs/specs/028-crypto-card-sse-push`의 정본. ADR-0002(레이어 구조), ADR-0014(코인 감시 Redis 락 — 같은 다중 인스턴스 전제를 공유), ADR-0015(조회 캐시 Redis 락 — `RedisLock` 추출 선례)를 따른다. `docs/specs/003-market-data/plan.md`의 "코인은 전용 SSE 스트림을 두지 않는다"(2026-07-30) 결정을 이 ADR이 뒤집는다 — 상세는 §맥락.

## 맥락

`CryptoPriceMoveWatcher`는 매 분 코인 변동 카드를 확정·저장하지만(이슈 #225, PR #236), 클라이언트는 `GET /api/instruments/{id}/price-moves`를 폴링해야만 새 카드를 안다. `SseEmitterRegistry`(이슈 #18)는 `Market.STOCK`·`Market.CRYPTO` 둘 다를 키로 갖는 `EnumMap`으로 설계돼 있지만, 실제로 배선된 것은 `StockPriceStreamService`/`StockPriceSseController`(`/api/stocks/stream`) 뿐이다 — `Market.CRYPTO` 버킷은 존재하되 아무도 채우거나 구독하지 않는 빈 자리다.

### 이 결정은 기존 문서 결정을 뒤집는다

`docs/prd.md` MKT-008과 `docs/specs/003-market-data/plan.md`(2026-07-30)는 "코인 전용 SSE 스트림은 두지 않는다 — `/api/cryptos/stream` 엔드포인트는 만들지 않는다"를 명시적으로 확정했었다. 근거는 "코인 캔들 API가 진행 중 분봉을 포함해 반환하므로 프론트가 짧은 주기로 재조회하는 것만으로 충분하다"였다. 이 ADR은 그 결정을 코인 변동 카드 확정 이벤트에 한해 뒤집는다 — **가격 재조회로는 대체할 수 없는 이벤트**(카드가 생겼다는 사실 자체)가 생겼기 때문이다. 코인 캔들 재조회로 가격은 알 수 있어도 "지금 막 카드가 확정됐다"는 사실은 알 수 없다. `docs/prd.md`·`docs/specs/003-market-data/plan.md`의 해당 문구 갱신은 이 spec의 tasks.md 항목으로 별도 처리한다 — 이 ADR 자체는 갱신 대상이 아니다(CLAUDE.md 규칙 2 — ADR은 새 번호로만 대체한다).

### 왜 코인 시세 스트림을 통째로 신설하는가 — 카드 이벤트만 얹을 수는 없는가

카드 확정 이벤트만 위한 별도 초경량 엔드포인트(예: `GET /api/instruments/price-move-notifications`)를 만드는 대안도 있었다. 그러나 `SseEmitterRegistry`는 market 단위로 emitter를 묶는 설계라 카드 이벤트만을 위한 새 market 축이나 별도 레지스트리를 만들면 이슈 #18의 기존 계약(retry·heartbeat·연결 종료 정리)을 새로 복제해야 한다. 반면 `Market.CRYPTO` 버킷은 이미 있고 비어 있으므로, 코인 시세 스트림(`GET /api/cryptos/stream`, `StockPriceStreamService`/`StockPriceSseController`와 대칭 구조)을 신설해 그 위에 새 이벤트 종류(`priceMoveCardConfirmed`)를 하나 얹는 것이 기존 인프라를 그대로 재사용하는 길이다. **주식 전용 계약(`/api/stocks/stream`)은 전혀 건드리지 않는다** — 새 컨트롤러·새 서비스이므로 이벤트 이름·페이로드·heartbeat 간격이 한 글자도 바뀌지 않는다.

### 왜 Redis pub/sub인가 — 로컬 리스트만으로는 인스턴스 로컬이라 팬아웃 불가

`SseEmitterRegistry`의 구독자 집합은 `CopyOnWriteArrayList`(인스턴스 로컬 메모리)다. 다중 인스턴스로 전환되면 카드를 만든 인스턴스(A)와 클라이언트가 실제로 연결된 인스턴스(B)가 다를 수 있다 — `CryptoPriceMoveWatcher.watch()`는 모든 인스턴스에서 각자 크론으로 돌지만(락으로 중복 확정만 막는다, ADR-0014), 카드를 실제로 확정한 인스턴스가 A라면 A의 로컬 리스트에만 있는 emitter만 push를 받는다. B에 연결된 클라이언트는 카드가 생겼다는 사실을 영원히 못 받는다.

이 저장소는 "다중 인스턴스 전환이 예정돼 있다"는 같은 전제로 이미 두 번 Redis를 새 용도로 확장했다(ADR-0014의 감시 락, ADR-0015의 조회 캐시 락). 이번에도 같은 전제가 적용된다 — **인스턴스 경계를 넘는 팬아웃에는 인스턴스 간 공유 상태가 필요하고, Redis pub/sub이 이미 있는 인프라(Redis)로 그 요구를 정확히 채운다.** Redis Pub/Sub은 발행 시점에 연결된 모든 구독 인스턴스에 메시지를 전달하므로, 각 인스턴스가 자신의 로컬 `SseEmitterRegistry(Market.CRYPTO)` 구독자에게 다시 뿌리면 인스턴스 경계와 무관하게 전체 구독자가 push를 받는다.

### 왜 카드 id만 발행하는가 — payload 계약 분리

Redis 메시지·SSE 이벤트 payload에는 카드의 `instrumentId`·`priceMoveEventId`만 담고 `narrative`·`changeRate`·`detectionScore` 같은 카드 본문은 담지 않는다. 클라이언트는 알림을 받으면 기존 `GET /api/instruments/{id}/price-moves`로 재조회한다.

- **계약을 이중으로 유지하지 않는다.** 카드 본문까지 SSE로 실어 보내면 `PriceMoveListResponse`(REST 응답)와 SSE payload 두 형태를 항상 같이 맞춰야 한다 — 서술 문구가 나중에 바뀌면(예: `NarrativeService` 개선) 두 경로를 다 고쳐야 한다.
- **원장 재조회가 이미 있다.** `PriceMoveQueryService.getPriceMoves`가 노출 게이트 판정까지 포함한 정본 조회 경로다 — SSE가 그 판정을 우회해 카드 내용을 먼저 흘리면(코인은 게이트가 없어 지금은 문제가 안 되지만) 이 알림 메커니즘이 나중에 노출 게이트가 있는 다른 카드 종류에 재사용될 때 사고가 난다. "카드 id만"은 이 재사용을 안전하게 만드는 선택이다.
- **`instrumentId`도 함께 담는다.** "카드 id만"의 취지는 카드 본문(서술·수치)을 제외한다는 것이지, 클라이언트가 재조회 엔드포인트를 호출하는 데 필요한 최소 라우팅 정보(`instrumentId`)까지 빼자는 것은 아니다 — `GET /api/instruments/{instrumentId}/price-moves`는 경로 파라미터로 `instrumentId`를 요구하므로 이것이 없으면 재조회 자체가 불가능하다. `priceMoveEventId`는 클라이언트가 여러 알림 중 어느 카드인지 식별(예: 하이라이트)하는 데 쓴다.

## 결정

**`CryptoPriceMoveWatcher.watchOne()`이 카드를 저장(`PriceMoveCardWriter.persist`)한 직후, Redis pub/sub 채널로 `{instrumentId, priceMoveEventId}`를 발행한다. 신설하는 코인 SSE 스트림(`GET /api/cryptos/stream`)의 구독자들에게는 각 인스턴스의 Redis 구독 리스너가 이 메시지를 받아 로컬 `SseEmitterRegistry(Market.CRYPTO)`로 다시 뿌린다.**

1. **발행 위치**: `PriceMoveCardWriter.persist(card, sources)` 호출이 예외 없이 반환한 뒤에만 발행한다 — 저장이 실패하면(예외로 트랜잭션 롤백) 발행 자체가 일어나지 않으므로 "저장 실패한 카드는 나가지 않는다"가 코드 순서로 보장된다.
2. **발행 컴포넌트는 market 도메인이 소유한다**: `CryptoPriceMoveCardPublisher`(`com.finplay.api.market.service`, 신설)가 Redis 채널 이름 상수와 `StringRedisTemplate.convertAndSend` 호출을 한 곳에 가둔다. `CryptoPriceMoveWatcher`(feedback)는 이 서비스만 의존한다 — feedback이 Redis를 직접 만지지 않는다(ADR-0002 "도메인 간 참조는 service 레이어를 통해서만 한다"와 같은 원칙을 feedback→market 방향에도 적용한다. feedback은 이미 `CryptoPriceSnapshotService`·`InstrumentService`(둘 다 market의 service)를 이런 방식으로 의존하고 있어 새 패턴이 아니다).
3. **발행 실패는 카드 생성을 실패시키지 않는다**: `CryptoPriceMoveCardPublisher`의 발행 메서드 내부에서 `RuntimeException`을 삼키고 로그만 남긴다(WARN — Redis 장애는 감시 카드 생성 자체보다는 덜 치명적이지만 조용히 넘기면 다음 사람이 "왜 push가 하나도 안 왔지"를 못 찾는다). `CryptoPriceMoveWatcher.watchOne()`은 발행 호출의 성공 여부와 무관하게 `return true`한다 — 원장(`price_move_events`)이 이미 커밋됐으므로 push는 부가 기능이다.
4. **구독 측(팬아웃)**: `RedisMessageListenerContainer` 빈(신설, market 패키지의 `@Configuration`)이 위 채널을 구독한다. 리스너(`CryptoCardPushSubscriber`, 신설)는 메시지를 받으면 페이로드를 역직렬화해 `SseEmitterRegistry.getEmitters(Market.CRYPTO)`를 순회하며 `priceMoveCardConfirmed` SSE 이벤트로 push한다 — `StockPriceStreamService.broadcastPriceEvent`와 같은 개별 emitter try/catch 패턴을 재사용해, 느린 구독자 하나의 전송 실패가 나머지 구독자의 push를 막지 않는다.
5. **감시 틱을 지연시키지 않는 것은 구조상 자연히 따라온다**: `RedisMessageListenerContainer`는 자체 스레드(기본 `SimpleAsyncTaskExecutor`)에서 리스너를 실행한다. `CryptoPriceMoveWatcher.watch()`가 도는 `@Scheduled` 스레드는 `convertAndSend`(Redis에 메시지를 발행하는 것 자체는 빠른 명령 1회) 이상을 기다리지 않는다 — 실제 SSE 전송(느릴 수 있는 블로킹 I/O)은 전혀 다른 스레드에서 일어나므로, 느린 구독자 연결이 다음 종목 감시(`watchOne` 루프)를 막지 못한다. 별도의 비동기 처리 코드(`@Async` 등)를 추가하지 않고도 이 요구가 충족된다.
6. **직렬화**: `ObjectMapper`(기존 빈 재사용)로 JSON 직렬화한다 — `FeedbackQueryCache`(ADR-0015 §3)가 이미 쓰는 패턴과 같다.
7. **채널 이름**: `feedback:price-move:crypto-confirmed`. 이 문자열은 `CryptoPriceMoveCardPublisher`에 `public static final` 상수 하나로만 존재하고, 구독 측 설정(`RedisMessageListenerContainer` 빈 등록)이 그 상수를 참조한다 — 두 곳에 문자열을 중복하지 않는다(`docs/conventions/code.md` "Redis key 문자열이 여러 클래스에 흩어진다" 금지 원칙을 pub/sub 채널에도 그대로 적용한다). 기존 Redis 키 접두사(`price:crypto:`·`ranking:`·`feedback:crypto-watch:lock:`·`feedback:query-cache:`·`candle:crypto:`) 어느 것과도 겹치지 않는다.
8. **주식 카드는 이 경로를 타지 않는다**: 발행 호출은 오직 `CryptoPriceMoveWatcher.watchOne()` 안에만 존재한다. 주식 카드를 확정하는 `PriceMoveCardService`(개장 전 배치)는 이 publisher를 호출하지 않는다 — 이것이 "주식 카드는 push되지 않는다"(노출 게이트 우회 방지)를 코드 구조로 보장하는 지점이다. 주식 카드는 `revealTime`이 지나야 노출되는 게이트가 있는데(§C-5), SSE push를 걸면 그 판정을 우회하게 되므로 애초에 이 경로에 배선하지 않는다.
9. **스키마 변경 없음**: Redis pub/sub과 기존 SSE 인프라만 재사용한다. 새 MySQL 테이블·컬럼이 없으므로 Flyway 마이그레이션이 없다(ADR-0004 무관).

## 결과

**좋은 점**

- 인스턴스가 몇 대든 카드를 만든 인스턴스와 무관하게 모든 구독자가 push를 받는다 — ADR-0014·0015가 다중 인스턴스 전제를 이미 두 번 반영해 온 것과 같은 방향의 확장이다.
- 발행(`convertAndSend`, 빠른 명령 1회)과 실제 전송(별도 스레드의 블로킹 I/O)이 분리돼, 별도 비동기 처리 코드 없이 "느린 구독자가 감시 틱을 막지 않는다"가 구조적으로 보장된다.
- 기존 `/api/stocks/stream` 계약을 전혀 건드리지 않는다 — 새 컨트롤러·새 서비스이므로 회귀 위험이 없다.
- `SseEmitterRegistry`의 retry·heartbeat·연결 종료 정리(이슈 #18)를 그대로 재사용해 새 스트림에서 같은 코드를 복제하지 않는다.
- "카드 id만" payload로 REST 응답과 SSE payload 계약이 분리돼, 카드 서술 형식이 바뀌어도 SSE 계약은 안 바뀐다.

**받아들이는 대가**

- `docs/prd.md` MKT-008·`docs/specs/003-market-data/plan.md`의 "코인 SSE 스트림을 두지 않는다" 결정을 뒤집는다 — 두 문서 모두 이 spec의 tasks.md에서 갱신해야 실제 정합이 맞는다(이 ADR만으로는 문서 정합이 끝나지 않는다).
- 이 저장소의 첫 Redis **pub/sub** 사례가 된다(기존 Redis 사용은 전부 key-value: `PriceStore`·`RankingStore`·락 2종·`FeedbackQueryCache`) — `RedisMessageListenerContainer`라는 새 인프라 개념이 하나 늘어난다.
- 코인 시세 실시간 스트림(snapshot·price·status)까지 함께 신설하는 것은 이슈 #286의 완료 조건(카드 push)보다 넓은 범위다 — `SseEmitterRegistry(Market.CRYPTO)`가 이미 있고 카드 이벤트만 얹기보다 대칭 구조로 완성하는 편이 인프라 중복을 피한다고 판단했지만, 이 확장 자체가 별도 검증(다중 인스턴스 fan-out을 단일 JVM 테스트로 흉내내는 방식의 한계 등)을 요구한다.
- Redis가 죽어 있으면 그 순간의 카드 확정은 아무 인스턴스에도 push되지 않는다(원장에는 영향 없음 — 부가 기능이므로 허용 범위). 클라이언트는 다음 폴링이나 재연결 시 `snapshot`이 아니라 REST 재조회로만 그 카드를 알게 된다(놓친 이벤트 재전송은 기존 SSE 계약대로 미지원).

## 대안과 기각 사유

**(a) 기존 `/api/stocks/stream`에 코인도 함께 태운다(`market` 쿼리 파라미터 추가)**

`StockPriceStreamService`는 매분 스케줄 기반(주식 재생 특성)인데 코인은 틱 기반 실시간이라 폴링 주기 자체가 다르다. 한 서비스에 두 갱신 주기를 섞으면 "기존 `/api/stocks/stream` 계약이 한 글자도 안 바뀐다"는 완료 조건을 지킬 수 없다. 기각.

**(b) 카드 확정 이벤트만을 위한 별도 초경량 엔드포인트를 만들고 코인 시세 스트림은 신설하지 않는다**

`SseEmitterRegistry`가 이미 market 단위 설계라 새 축을 추가하려면 retry·heartbeat·연결 종료 정리를 새로 복제하거나 `SseEmitterRegistry` 자체를 변경해야 한다. `Market.CRYPTO` 버킷이 이미 비어 있으므로 코인 스트림을 정식으로 신설하는 편이 기존 설계를 그대로 재사용한다. 다만 이 확장이 이슈 범위보다 넓다는 점은 §결과에 대가로 남긴다.

**(c) DB 아웃박스 패턴 — `price_move_events`에 `pushed` 플래그를 두고 별도 폴러가 주기적으로 스캔해 push**

새 인프라(Redis pub/sub) 없이 기존 DB만으로 다중 인스턴스 팬아웃을 흉내낼 수 있다는 장점이 있다. 그러나 폴링 주기만큼 지연이 생기고("실시간"이라는 요구와 어긋난다), 다중 인스턴스가 같은 행을 동시에 집어 중복 push하지 않으려면 결국 락(행 잠금 또는 또 다른 Redis 락)이 필요해 복잡도가 Redis pub/sub보다 낮지 않다. 기각.

**(d) 카드 전체 내용을 payload에 담는다**

클라이언트 왕복이 한 번 줄어드는 장점이 있다. 그러나 §맥락의 "왜 카드 id만 발행하는가"에서 설명한 계약 이중 유지 문제와 노출 게이트 우회 위험이 있어 기각했다.

**(e) 로컬 `CopyOnWriteArrayList`만 유지하고 다중 인스턴스 문제를 지금은 무시한다(단일 인스턴스 가정)**

새 인프라가 전혀 없어 가장 단순하다. 그러나 ADR-0014·0015가 이미 "다중 인스턴스 전환이 예정돼 있다"는 같은 전제로 Redis 확장을 두 번 결정한 저장소에서, 이번만 다른 전제를 쓰면 판단 기준이 흔들린다. 완료 조건 문구("다중 인스턴스 대응")도 이 전제를 명시하고 있어 기각.

## 후속

- `docs/prd.md` MKT-008·`docs/specs/003-market-data/plan.md`의 "코인 SSE 스트림 없음" 문구는 이 spec(`028-crypto-card-sse-push`)의 tasks.md에서 갱신한다 — 이 ADR 자체는 갱신 대상이 아니다.
- 코인 시세 실시간 스트림(snapshot·price·status)이 카드 push와 별개로 실제 트래픽에서 유용한지는 배포 후 사용률로 재검토한다 — 지금은 카드 push 인프라의 대칭 부산물로 함께 만든다.
- 다중 인스턴스 배포 후 실제 Redis pub/sub 팬아웃 지연·유실률을 관측한 적이 없다 — 단일 Redis 인스턴스 기준이며, Redis 자체를 다중화(센티널/클러스터)하는 시점에 ADR-0014·0015와 같은 조건(페일오버 순간 유실 가능성)을 재검토한다.
