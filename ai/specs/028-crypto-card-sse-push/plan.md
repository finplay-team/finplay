# Plan: 코인 변동 카드 확정 SSE push 배선

## 관련 문서

- Spec: `./spec.md`
- 관련 ADR: **ADR-0018**(이 spec의 정본 — 결정 근거 전체), ADR-0002(레이어 구조 — feedback→market은 service만 의존), ADR-0014(코인 감시 Redis 락 — 같은 다중 인스턴스 전제), ADR-0015(조회 캐시 Redis 락 — `RedisLock` 추출·`ObjectMapper` 직렬화 선례)
- PRD: 대응 요구사항 ID 없음(`spec.md` "PRD 정합성" 참고). `ai/prd.md` MKT-008·`ai/specs/003-market-data/plan.md`의 "코인 SSE 없음" 결정을 이 spec이 뒤집는다.
- 선행: `003-market-data`(이슈 #18 `SseEmitterRegistry`, 이슈 #19 `StockPriceStreamService`/`StockPriceSseController`), `012-ai-feedback` 이슈 8(PR #236, `CryptoPriceMoveWatcher`·`PriceMoveCardWriter`), `024-feedback-query-cache`(`RedisLock` 추출·`ObjectMapper` 직렬화 패턴)

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | /api/cryptos/stream | Header: `Authorization: Bearer <accessToken>` (fetch 기반, 브라우저 기본 `EventSource` 미사용 — `/api/stocks/stream`과 동일 이유) | `Content-Type: text/event-stream` | 코인 전용 SSE 구독 — 신설. `StockPriceSseController`/`StockPriceStreamService`와 대칭 구조(§SSE 계약) |

기존 `GET /api/stocks/stream`은 변경하지 않는다(컨트롤러·서비스 클래스 전부 새로 만든다 — 기존 클래스에 분기를 추가하지 않는다).

## 입력 명세

| 필드 | 필수 | 검증 |
|---|---|---|
| `Authorization` 헤더 | 필수 | Bearer 토큰 누락·무효 시 401 `UNAUTHORIZED` (`/api/stocks/stream`과 동일, `SecurityConfig`의 `anyRequest().authenticated()`가 처리) |

요청 본문·쿼리 파라미터 없음.

## 데이터 모델

새 테이블·컬럼·Flyway 마이그레이션 없음(ADR-0004 무관). Redis pub/sub 채널 1개와 기존 SSE 인프라(`SseEmitterRegistry`)만 재사용한다.

### 신설 컴포넌트

| 컴포넌트 | 패키지 | 역할 |
|---|---|---|
| `CryptoPriceSseController` | `com.finplay.api.market.controller` | `GET /api/cryptos/stream` — `StockPriceSseController`와 같은 구조(emitter 생성 → snapshot 전송 → activate) |
| `CryptoPriceStreamService` | `com.finplay.api.market.service` | 코인 snapshot 구성·`price`/`status` push. `StockPriceStreamService`와 대칭이나 갱신 트리거가 다르다(§SSE 계약) |
| `PriceMoveCardConfirmedEvent` | `com.finplay.api.market.dto.sse` | Redis 메시지이자 SSE payload — `record(Market market, Long instrumentId, Long priceMoveEventId, LocalDateTime emittedAt)`. `MarketPriceEvent` 등 기존 SSE DTO와 같은 패키지 |
| `CryptoPriceMoveCardPublisher` | `com.finplay.api.market.service` | Redis 채널 이름 상수(`public static final CHANNEL`)와 `convertAndSend` 호출을 한 곳에 가둠. feedback은 이 서비스만 의존(ADR-0002 원칙, ADR-0018 §결정 2) |
| `CryptoCardPushSubscriber` | `com.finplay.api.market.sse` | `org.springframework.data.redis.connection.MessageListener` 구현체. 메시지 수신 시 `SseEmitterRegistry.getEmitters(Market.CRYPTO)`에 `priceMoveCardConfirmed` 이벤트로 개별 전송(예외 격리) |
| `RedisPubSubConfig` | `com.finplay.api.market.config` | `RedisMessageListenerContainer` 빈 신설, `CryptoCardPushSubscriber`를 `CryptoPriceMoveCardPublisher.CHANNEL`에 등록 |

### 기존 컴포넌트 변경

| 컴포넌트 | 변경 |
|---|---|
| `CryptoPriceMoveWatcher.watchOne()` | `priceMoveCardWriter.persist(card, sources)` 호출이 예외 없이 반환한 직후, `cryptoPriceMoveCardPublisher.publish(instrument.getId(), card.getId())` 호출 추가. 발행 실패(RuntimeException)는 publisher 내부에서 삼키므로 `watchOne()`은 기존과 동일하게 `return true` |
| `SseEmitterRegistry` | **변경 없음** — 이미 market 파라미터화돼 있어 `Market.CRYPTO` 인자로 그대로 재사용 |
| `PriceMoveCardWriter`·`PriceMoveCardService`(주식) | **변경 없음** — 주식 카드 확정 경로는 새 publisher를 호출하지 않는다(완료 조건 "주식 카드는 push되지 않는다") |

## SSE 계약 (신설, `GET /api/cryptos/stream`)

- **인증**: `/api/stocks/stream`과 동일 — fetch + `Authorization: Bearer` 헤더, 401은 스트림 시작 전 응답 본문으로.
- **이벤트 이름 4종**:
  - `snapshot` — 구독 시작 직후 1회. 코인 12종 전체(가격 없는 종목도 `status=UNAVAILABLE`로 포함), id 없음. `CryptoPriceStreamService.buildSnapshot()`이 `PriceQueryService.getPriceQuote(Instrument)`를 코인 종목마다 호출한다(기존 메서드 재사용 — `Market.CRYPTO` 분기는 이미 있다). `sourceTradingDate`는 코인에 해당 없음(항상 생략, 기존 `MarketPriceEvent`의 `@JsonInclude(NON_NULL)` 패턴 재사용).
  - `price` — 코인 한 종목의 가격이 갱신될 때 push. **주식은 매분 스케줄이지만 코인은 이벤트 드리븐이다** — `PriceStore.saveTick()`이 이미 발행하는 기존 `CryptoPriceUpdatedEvent`(LMT-002 체결 트리거가 이미 소비 중인 이벤트, 새 이벤트 타입 아님)를 `CryptoPriceStreamService`가 `@EventListener`로 추가 구독해 브로드캐스트한다. id는 `CRYPTO:{symbol}:{receivedAt을 yyyyMMddHHmmss로 포맷}` — 코인은 분 단위로는 틱을 구분 못 하므로 주식(`yyyyMMddHHmm`)보다 초 단위까지 포함한다.
  - `status` — `PriceStore.getConnectionStatus()`(`FeedConnectionStatus`) 변경 시 push. 코인 연결 상태 변경을 알리는 기존 이벤트가 없으므로 `CryptoPriceStreamService`가 짧은 주기(`@Scheduled(fixedRate = 5000)`, 근거는 아래 "폴링 주기" 참고)로 직전 상태와 비교해 달라졌을 때만 1회 push한다(`StockPriceStreamService.publishScheduledUpdates()`의 "직전 값과 비교해 변경 시에만" 패턴을 재사용하되 주기만 다르다).
  - `priceMoveCardConfirmed` — 코인 변동 카드가 확정되면 push. id 없음(`snapshot`·`status`와 같은 근거 — 특정 symbol 하나로 좁혀지는 연속 갱신이 아니라 순간 이벤트다). Payload: `{"market":"CRYPTO","instrumentId":7,"priceMoveEventId":142,"emittedAt":"2026-08-10T14:03:00"}` — 카드 본문(서술·변동률 등)은 담지 않는다(ADR-0018 §결정, spec.md §비즈니스 규칙). 클라이언트는 `instrumentId`로 `GET /api/instruments/{instrumentId}/price-moves`를 재조회한다.
- **폴링 주기(status, 5초)**: 코인 연결 장애는 `PriceStore.isStale`의 10초 stale 기준보다 사용자에게 빨리 드러나야 유의미하다 — 20초 heartbeat보다도 짧게 잡아 회원이 "연결이 끊겼다"를 최대 5초 이내에 안다. 이 값은 실측이 아니라 stale 기준(10초)의 절반이라는 근거로 잡은 추정값이며, 배포 후 조정 여지를 남긴다.
- **retry·heartbeat·연결 종료 정리**: 전부 `SseEmitterRegistry`(이슈 #18) 계약을 그대로 재사용한다 — 이 spec에서 재구현하지 않는다. `SseEmitterRegistry.sendHeartbeat()`는 이미 모든 market을 순회하므로 코드 변경이 필요 없다.
- **기존 `/api/stocks/stream` 계약 불변**: `StockPriceSseController`·`StockPriceStreamService`·`MarketPriceEvent`·`MarketSnapshotEvent`·`MarketStatusEvent`는 이 spec에서 한 줄도 수정하지 않는다. `PriceMoveCardConfirmedEvent`는 새 DTO이며 기존 DTO에 필드를 추가하지 않는다.

## 카드 확정 push 흐름

1. `CryptoPriceMoveWatcher.watchOne()`이 카드를 확정하기로 판정 → `priceMoveCardWriter.persist(card, sources)` 호출 → 예외 없이 반환(=DB 커밋 완료).
2. `cryptoPriceMoveCardPublisher.publish(instrument.getId(), card.getId())` 호출 — 내부에서 `PriceMoveCardConfirmedEvent`를 `ObjectMapper`로 직렬화해 `StringRedisTemplate.convertAndSend(CHANNEL, json)`.
   - `RuntimeException`은 이 메서드 내부에서 삼키고 WARN 로그 — 호출부(`watchOne`)는 이 호출의 성공 여부를 확인하지 않는다.
3. Redis가 채널 구독 중인 **모든 인스턴스**에 메시지를 전달한다.
4. 각 인스턴스의 `CryptoCardPushSubscriber.onMessage()`(별도 스레드, `RedisMessageListenerContainer`가 기본 제공하는 `SimpleAsyncTaskExecutor`에서 실행)가 메시지를 역직렬화 → 그 인스턴스의 로컬 `SseEmitterRegistry.getEmitters(Market.CRYPTO)`를 순회 → 각 emitter에 `priceMoveCardConfirmed` 이벤트로 개별 전송(전송 실패는 개별 emitter만 `completeWithError`로 종료, 나머지는 계속).
5. `watchOne()`은 2번 호출 결과와 무관하게 `return true` — 다음 종목(`watch()`의 for 루프)으로 즉시 진행한다. 3~4단계는 `watch()`의 스케줄 스레드와 완전히 분리된 스레드에서 일어나므로 감시 틱을 지연시키지 않는다(ADR-0018 §결정 5).

## 테스트 계획

- **단위**:
  - `CryptoPriceMoveCardPublisher` — 정상 발행 시 `convertAndSend`가 올바른 채널·payload로 호출됨(mock `StringRedisTemplate`). Redis가 예외를 던져도 메서드가 예외를 전파하지 않음.
  - `CryptoCardPushSubscriber` — 정상 메시지 역직렬화 후 `SseEmitterRegistry.getEmitters(Market.CRYPTO)` 순회·전송 호출 확인(mock). 역직렬화 실패(깨진 JSON)는 예외를 삼키고 로그만 남김(다른 구독자 처리에 영향 없음 — 리스너 자체가 죽지 않아야 함).
  - `CryptoPriceMoveWatcher` — `cryptoPriceMoveCardPublisher.publish(...)`가 `RuntimeException`을 던지도록 mock해도 `watchOne()`이 `true`를 반환하고 저장은 이미 끝나 있음을 확인(mock `PriceMoveCardWriter`).
- **슬라이스** (`@WebMvcTest`): `CryptoPriceSseController` — 인증 없이 호출 시 401, `Content-Type: text/event-stream` 200. 기존 `StockPriceSseController` 테스트가 그대로 통과함을 회귀로 재확인(파일 수정 없음이므로 통과가 당연하지만, 새 컨트롤러 추가로 `SecurityConfig` 매핑이 깨지지 않았는지 확인하는 의미).
- **통합** (Testcontainers MySQL+Redis):
  - **카드 확정 → push 종단 테스트**: `CryptoPriceMoveWatcher.watch()`가 카드를 확정하는 조건을 구성(기존 `012-ai-feedback`의 코인 감시 통합 테스트 셋업 재사용) → `GET /api/cryptos/stream` 구독 emitter를 테스트에서 직접 등록 → `priceMoveCardConfirmed` 이벤트 수신 확인(`instrumentId`·`priceMoveEventId` 일치).
  - **저장 실패 시 push 없음**: 근거 매칭이 0건이라 카드 생성이 취소되는 경로(기존 케이스 재사용)에서 Redis 채널에 아무 메시지도 발행되지 않음을 확인.
  - **Redis 장애에도 카드 생성 성공**: `CryptoPriceMoveCardPublisher`를 장애 발생하도록 mock(`@MockitoBean`, `convertAndSend` 호출 시 예외)한 상태에서 `watchOne()`을 실행 → `price_move_events` 행이 정상 커밋됨.
  - **구독자 0명이어도 카드 생성 성공**: 아무도 `/api/cryptos/stream`을 구독하지 않은 상태에서 카드 확정 → DB 커밋만 확인(예외 없음).
  - **감시 틱 비차단**: `SseEmitterRegistry`에 전송이 오래 걸리는 가짜 emitter(예: `send()`가 지연되도록 만든 테스트 더블)를 등록한 상태에서 `CryptoPriceMoveWatcher.watch()`가 여러 종목을 처리하는 데 걸리는 시간이, 가짜 emitter가 없을 때와 유의미하게 다르지 않음을 확인(리스너가 별도 스레드에서 도는 것의 직접 증거).
  - **다중 인스턴스 팬아웃 흉내**: 같은 Testcontainers Redis에 대해 `SseEmitterRegistry`·`CryptoCardPushSubscriber`·`RedisMessageListenerContainer` 조합을 **두 벌** 직접 조립(스프링 빈이 아니라 테스트 코드에서 `new`로 생성 — 전체 Spring Context를 두 개 띄우는 비용을 피하면서 "인스턴스 로컬 리스트가 아니라 Redis를 통해 팬아웃된다"를 검증). 한쪽 `CryptoPriceMoveCardPublisher`로 발행 → **양쪽** `SseEmitterRegistry`에 등록된 emitter가 모두 수신함을 확인. 이것이 ADR-0018의 핵심 주장(인스턴스 경계를 넘는 팬아웃)의 직접 증거다.
  - **기존 `/api/stocks/stream` 회귀**: `003-market-data`의 기존 통합 테스트가 파일 수정 없이 그대로 통과.
  - **주식 카드는 push 안 됨**: `PriceMoveCardService`(주식 확정 경로)를 호출한 뒤 Redis 채널에 메시지가 없음을 확인.

## 문서 동기화 계획 (실제 갱신은 tasks.md에서 구현 단계에 수행)

- `ai/api-routes.md` — `GET /api/cryptos/stream` 행 추가(도메인 `market`, Spec `028`).
- `docs/api-contracts.md` — market 섹션에 `/api/cryptos/stream` 계약(이벤트 4종·payload 예시) 추가. `/api/stocks/stream` 계약 절은 수정하지 않는다.
- `ai/prd.md` §3 "구현 현황" — 새 행 추가(요구사항 ID 없음 명시, 근거는 이슈 #286·spec `028`). §4 MKT-008 본문에 "코인 SSE 스트림은 두지 않는다" 서술이 카드 알림 한정으로 뒤집혔음을 각주로 추가(원문은 보존하고 갱신 이력만 남긴다 — PRD는 ADR과 달리 직접 수정하는 문서이므로 새 번호 체계는 없지만, 기존 문구를 지우지 않고 갱신 시점·사유를 남기는 이 저장소의 관례를 따른다).
- `ai/specs/003-market-data/plan.md` — 61행 "코인은 전용 스트림을 두지 않는다"·442행 "코인 SSE" 제외 문구에 "028에서 카드 알림 한정으로 뒤집힘" 각주 추가. 원문 문장 자체는 지우지 않는다(이 문서가 "이 시점의 결정"을 기록하는 관례를 따른다 — MKT-008 절의 "(2026-08-06 MKT-010으로 대체)" 각주 패턴과 동일).
