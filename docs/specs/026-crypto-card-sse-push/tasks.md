# Tasks: 코인 변동 카드 확정 SSE push 배선 (이슈 #286)

항목 1개 = 커밋 1개. 설계 근거는 `docs/adr/0018-crypto-card-sse-push.md`이며 구현 중 그와 어긋나는 필요가 생기면 구현하지 말고 새 ADR을 제안한다(CLAUDE.md 규칙 2).

- [x] **1. 코인 SSE 스트림 인프라 신설** (ADR-0018 §결정 — "왜 코인 시세 스트림을 통째로 신설하는가")
  - `CryptoPriceStreamService`(`com.finplay.api.market.service`) — `buildSnapshot()`/`sendSnapshot()`(코인 12종, `PriceQueryService.getPriceQuote` 재사용), `createEmitter()`/`activate()`(`SseEmitterRegistry(Market.CRYPTO)` 위임), `@EventListener(CryptoPriceUpdatedEvent)`로 `price` push(새 이벤트 타입 아님 — `PriceStore.saveTick`이 이미 발행), `@Scheduled(fixedRate=5000)`로 `FeedConnectionStatus` 변경 감지 후 `status` push.
  - `CryptoPriceSseController`(`com.finplay.api.market.controller`) — `GET /api/cryptos/stream`, `StockPriceSseController`와 같은 3단계 호출 순서(emitter 생성 → snapshot 전송 → activate).
  - 검증: `@WebMvcTest`(인증 없이 401, 인증 시 `text/event-stream`), 통합 테스트(구독 → snapshot 수신 → `CryptoPriceUpdatedEvent` 발생 시 `price` 수신 → `PriceStore.saveConnectionStatus` 변경 시 `status` 수신, 최대 5초 이내). **`GET /api/stocks/stream`의 기존 테스트가 파일 수정 없이 그대로 통과**함을 재확인한다(회귀 없음).

- [ ] **2. Redis pub/sub 배선** (ADR-0018 §결정 2·6·7)
  - `PriceMoveCardConfirmedEvent`(`com.finplay.api.market.dto.sse`) — `record(Market market, Long instrumentId, Long priceMoveEventId, LocalDateTime emittedAt)`.
  - `CryptoPriceMoveCardPublisher`(`com.finplay.api.market.service`) — `public static final String CHANNEL`(`feedback:price-move:crypto-confirmed`) + `publish(Long instrumentId, Long priceMoveEventId)`. `ObjectMapper` 직렬화, `StringRedisTemplate.convertAndSend`. `RuntimeException`은 내부에서 삼키고 WARN 로그(호출부에 전파하지 않음).
  - `CryptoCardPushSubscriber`(`com.finplay.api.market.sse`) — `MessageListener` 구현. 메시지 수신 시 역직렬화 → `SseEmitterRegistry.getEmitters(Market.CRYPTO)` 순회 → `priceMoveCardConfirmed` 이벤트로 개별 전송(전송 실패는 해당 emitter만 `completeWithError`, 나머지 계속 — `StockPriceStreamService.broadcastPriceEvent`와 같은 패턴). 역직렬화 실패는 예외를 삼키고 로그만(리스너 스레드 자체가 죽지 않아야 함).
  - `RedisPubSubConfig`(`com.finplay.api.market.config`, `@Configuration`) — `RedisMessageListenerContainer` 빈 등록, `CryptoCardPushSubscriber`를 `CryptoPriceMoveCardPublisher.CHANNEL`에 `addMessageListener`로 구독.
  - 검증: 단위(`CryptoPriceMoveCardPublisher` 정상 발행·Redis 예외 삼킴, `CryptoCardPushSubscriber` 정상 처리·역직렬화 실패 격리).

- [ ] **3. `CryptoPriceMoveWatcher` 배선 — 저장 성공 후에만 발행** (ADR-0018 §결정 1·3·8, 완료 조건 핵심)
  - `watchOne()`에서 `priceMoveCardWriter.persist(card, sources)`가 예외 없이 반환한 직후 `cryptoPriceMoveCardPublisher.publish(instrument.getId(), card.getId())` 호출.
  - **주식 확정 경로(`PriceMoveCardService`)는 이 publisher를 호출하지 않는다** — 새 의존성을 추가하지 않는다.
  - 검증(단위): `publish(...)`가 예외를 던지도록 mock해도 `watchOne()`이 `true`를 반환. 근거 매칭 0건으로 카드 생성이 취소되는 기존 케이스에서 `publish`가 호출되지 않음(mock 호출 검증).
  - 검증(통합): 실제 코인 감시 시나리오에서 카드 확정 → Redis 채널에 올바른 `instrumentId`·`priceMoveEventId`가 발행됨. 주식 카드 확정 경로를 호출해도 채널에 메시지가 없음.

- [ ] **4. 다중 인스턴스·비차단 검증 통합 테스트** (완료 조건 — 감시 틱 비차단, 다중 인스턴스 팬아웃)
  - **비차단**: 전송이 지연되는 가짜 emitter를 `SseEmitterRegistry(Market.CRYPTO)`에 등록한 상태에서 `CryptoPriceMoveWatcher.watch()`의 여러 종목 처리 소요시간이 가짜 emitter가 없을 때와 유의미하게 다르지 않음을 확인(publish가 별도 스레드에서 소비되는 것의 직접 증거).
  - **다중 인스턴스 팬아웃**: 같은 Testcontainers Redis에 대해 `SseEmitterRegistry`+`CryptoCardPushSubscriber`+`RedisMessageListenerContainer` 조합을 테스트 코드에서 두 벌 직접 조립(전체 Spring Context 2개를 띄우지 않음) → 한쪽 `CryptoPriceMoveCardPublisher`가 발행한 메시지를 **양쪽** 조합의 emitter가 모두 수신함을 확인.
  - 저장 실패 시 push 없음, Redis 장애에도 카드 생성 성공, 구독자 0명이어도 카드 생성 성공 — 3가지도 이 항목에서 함께 통합 테스트로 확인.

- [ ] **5. 문서 갱신** (`docs/specs/026-crypto-card-sse-push/plan.md` §문서 동기화 계획)
  - `docs/api-routes.md` — `GET /api/cryptos/stream` 행 추가(도메인 `market`, Spec `026`).
  - `docs/api-contracts.md` — market 섹션에 `/api/cryptos/stream` 계약(이벤트 4종·payload 예시) 추가. `/api/stocks/stream` 계약 절은 무수정.
  - `docs/prd.md` §3 "구현 현황" — 새 행 추가(요구사항 ID 없음을 명시, 근거는 이슈 #286·spec `026`). MKT-008 본문에 카드 알림 한정 반전 각주 추가(원문 보존).
  - `docs/specs/003-market-data/plan.md` — 61행·442행의 "코인 SSE 없음" 문구에 "026에서 카드 알림 한정으로 뒤집힘" 각주 추가(원문 보존).
  - `deploy/nginx.conf` 확인 — `location /api` 블록이 새 경로(`/api/cryptos/stream`)에 이미 적용됨을 재확인만 하고 수정하지 않는다(경로가 `/api` 밖으로 바뀌면 그때 `location` 추가가 필요하다).
  - 마무리로 `./gradlew build` 전체 통과 확인.
