# ADR-0026: 코인 변동 카드 확정 SSE push 및 코인 SSE 스트림을 제거한다 (ADR-0018 대체)

- 상태: 승인됨
- 날짜: 2026-08-20
- 관계: 이슈 #476. ADR-0018(`docs/adr/0018-crypto-card-sse-push.md`)을 대체(supersede)한다 — CLAUDE.md 규칙 2에 따라 ADR-0018 자체는 수정하지 않고 새 번호로만 대체한다. `docs/specs/028-crypto-card-sse-push`가 신설했던 인프라를 이번 결정으로 제거한다.

## 맥락

ADR-0018은 코인 변동 카드 확정 알림(`priceMoveCardConfirmed`)을 실시간 push하기 위해 `GET /api/cryptos/stream`을 신설했다. 다만 `SseEmitterRegistry(Market.CRYPTO)`가 이미 market 단위로 설계돼 있어, 카드 알림만 얹기보다 코인 시세(snapshot·price·status) push까지 `StockPriceStreamService`/`StockPriceSseController`와 대칭으로 함께 만드는 편을 택했다(ADR-0018 §대안 (b) 기각 사유). ADR-0018 §후속은 이 코인 시세 스트림 부분을 두고 "카드 push와 별개로 실제 트래픽에서 유용한지는 배포 후 사용률로 재검토한다"고 조건을 걸어뒀다.

배포 후 프론트(`finplay-frontend`)를 확인한 결과 `GET /api/cryptos/stream`을 구독하는 코드가 처음부터 없었다 — `useCryptoPrices.ts`는 시세를 5초 간격 폴링으로만 가져온다. 코인 시세 push뿐 아니라 이 ADR이 원래 존재 이유로 삼았던 카드 확정 알림(`priceMoveCardConfirmed`)조차 실제로 소비하는 프론트 코드가 없다 — ADR-0018이 "재조회로는 대체할 수 없다"고 판단했던 이벤트 자체가 배포 후 아무도 구독하지 않았다. 재검토 대상은 코인 시세 스트림 부분으로 예고돼 있었지만, 실측 결과 카드 알림을 포함한 스트림 전체가 미사용이었으므로 재검토 범위를 전체로 넓힌다.

## 결정

**`GET /api/cryptos/stream`과 그 전용 인프라를 전부 제거한다.** 코인 변동 카드는 여전히 `CryptoPriceMoveWatcher`가 매 분 감시해 확정·저장하지만(변경 없음), 그 확정을 실시간으로 알리는 별도 경로는 더 이상 없다 — 클라이언트는 `GET /api/instruments/{instrumentId}/price-moves` 재조회(폴링)로만 새 카드를 안다. ADR-0018 §맥락이 뒤집었던 "코인은 전용 SSE 스트림을 두지 않는다"(`docs/prd.md` MKT-008, `docs/specs/003-market-data/plan.md` 2026-07-30 결정)가 예외 없이 다시 적용된다.

1. **삭제 대상**: `CryptoPriceSseController`(`GET /api/cryptos/stream` 컨트롤러), `CryptoPriceStreamService`(snapshot 구성·price/status push), `CryptoCardPushSubscriber`(Redis 구독→SSE 팬아웃 리스너), `CryptoPriceMoveCardPublisher`(Redis 발행), `RedisPubSubConfig`(`RedisMessageListenerContainer` 빈 등록). 이 다섯은 이 기능 전용이라 다른 곳에 영향이 없다. 이들만 참조하던 `PriceMoveCardConfirmedEvent` DTO(Redis 메시지·SSE payload 겸용)도 함께 제거한다 — 남겨두면 아무도 참조하지 않는 죽은 코드가 된다.
2. **`CryptoPriceMoveWatcher`는 발행 호출 한 줄만 제거한다**: `watchOne()`이 `PriceMoveCardWriter.persist(card, sources)` 직후 호출하던 `cryptoPriceMoveCardPublisher.publish(...)`(및 그 try-catch 방어)만 삭제한다. 카드 확정 탐지·쿨다운·근거 매칭·서술·저장 로직 자체는 이 결정과 무관해 전혀 바뀌지 않는다 — ADR-0018 §결정 3이 "발행은 부가 기능이며 발행 실패가 카드 생성을 막지 않는다"고 이미 선언했던 그대로, 이번에는 그 부가 기능을 통째로 걷어낸다.
3. **`CryptoPriceUpdatedEvent`·`PriceStore`는 건드리지 않는다**: 이 이벤트는 지정가 체결·손절익절 트리거(`LimitOrderTriggerListener`)가 발행·구독하는 별개 인프라다. `CryptoPriceStreamService.onPriceUpdated`가 같은 이벤트를 구독해 SSE로 재전송했을 뿐 발행 측이 아니었으므로, 그 구독자(`CryptoPriceStreamService`)를 지워도 발행자와 다른 구독자(`LimitOrderTriggerListener`)는 영향받지 않는다.
4. **`GET /api/stocks/stream`(`StockPriceSseController`/`StockPriceStreamService`)은 무관하다**: `SseEmitterRegistry`·`MarketSnapshotEvent`/`MarketPriceEvent`/`MarketStatusEvent` DTO는 주식 스트림과 공유하는 인프라라 그대로 유지한다 — 이번 삭제 대상은 `Market.CRYPTO` 전용 컨트롤러·서비스·구독자·발행자뿐이다.
5. **문서 동기화**: `docs/api-routes.md`·`docs/api-contracts.md`에서 `GET /api/cryptos/stream` 관련 행·절을 제거하고, `docs/prd.md` §3 "코인 변동 카드 확정 SSE push" 행 상태를 완료에서 미채택(제거됨)으로 바꾼다(CLAUDE.md 규칙 7·10).

## 결과

**좋은 점**

- 아무도 구독하지 않는 SSE 엔드포인트·Redis pub/sub 채널·전용 리스너 스레드(`RedisMessageListenerContainer`)를 걷어내 유지보수 대상이 줄어든다 — 이 저장소의 첫 Redis pub/sub 사례였던 것도 함께 사라져, 다른 Redis 용도(key-value: `PriceStore`·`RankingStore`·락 2종·`FeedbackQueryCache`)만 남는다.
- `CryptoPriceMoveWatcher`의 핵심 책임(탐지·근거 매칭·서술·저장)에서 발행이라는 부가 관심사가 빠져 코드가 더 단순해진다.
- 실측(사용률 0)에 근거한 제거라 ADR-0018 §후속이 예고한 재검토를 그대로 이행한 것이다 — 추측이 아니라 배포 후 관찰로 판단했다.

**받아들이는 대가**

- 코인 변동 카드가 확정된 순간을 클라이언트가 즉시 알 방법이 없다 — 다음 폴링 주기까지 최대 5초(`useCryptoPrices.ts` 간격) 지연될 수 있다. 다만 이 지연은 애초에 프론트가 감수하고 있던 값이다(SSE를 구독한 적이 없으므로).
- 다중 인스턴스 배포로 전환된 뒤 "카드를 만든 인스턴스와 클라이언트가 연결된 인스턴스가 다르면 알림을 못 받는다"는 문제(ADR-0018 §맥락)를 다시 풀어야 할 수 있다 — 다만 이는 이 알림 기능 자체가 재도입될 때의 문제이지, 폴링으로는 애초에 발생하지 않는다(폴링은 인스턴스 무관하게 매번 DB를 직접 조회한다).
- 코인 시세 실시간 push라는 대칭 기능도 함께 사라진다 — ADR-0018이 "카드 push의 대칭 부산물"로 만들었을 뿐 이슈 #286 자체의 요구는 아니었으므로, 이 부분을 잃는 것은 원래 범위 밖이었던 것을 되돌리는 것에 가깝다.

## 대안과 기각 사유

**(a) 코인 시세 push(`CryptoPriceStreamService`)만 제거하고 카드 확정 알림(`priceMoveCardConfirmed`)은 유지한다**

ADR-0018 §후속이 예고한 재검토 범위는 원래 시세 push였다. 그러나 실측 결과 카드 알림 쪽도 구독자가 0이었다 — `GET /api/cryptos/stream` 자체를 아무도 열지 않으므로 두 이벤트 종류를 분리 유지할 이유가 없다. 컨트롤러·서비스가 같은 엔드포인트를 공유해 시세만 잘라내도 카드 알림 인프라(Redis pub/sub·리스너·컨트롤러)가 그대로 남아 절반만 정리되는 셈이다. 기각.

**(b) 엔드포인트는 유지하고 프론트 구독을 붙인다(재활용)**

인프라를 다시 살리려면 프론트에 새 작업이 필요하고, 이미 폴링(5초)으로 충분히 동작 중인 기능을 SSE로 바꿀 만한 근거(지연 문제 보고, 트래픽 증가 등)가 이슈 #476 시점에 없다. 필요해지면 그때 다시 만드는 편이 지금 미사용 코드를 계속 유지보수하는 것보다 낫다. 기각.

**(c) 코드는 남기고 컨트롤러만 비활성화한다(feature flag)**

사용하지 않는 기능을 플래그 뒤에 숨기지 않는다는 이 팀의 판단과 어긋난다. 죽은 코드를 플래그 뒤에 숨기면 다음 사람이 그 플래그가 왜 있는지, 다시 켤 계획이 있는지 판단할 수 없다. 필요해지면 git 히스토리에서 복원하는 편이 더 명확하다. 기각.

## 후속

- 프론트가 실제로 실시간 알림을 필요로 하게 되면(예: 지연이 사용자 경험을 해친다는 신호), 그 시점의 실제 요구사항(다중 인스턴스 여부·이벤트 종류)을 근거로 새 ADR을 작성해 다시 설계한다 — 이 ADR과 ADR-0018을 그대로 되살리지 않는다. Redis pub/sub 팬아웃이 여전히 유효한 패턴인지도 그때 재검토한다(이 저장소가 실제로 다중 인스턴스로 전환된 뒤의 관측이 아직 없다는 ADR-0018 §후속의 한계는 이번에도 검증되지 않은 채 남는다).
