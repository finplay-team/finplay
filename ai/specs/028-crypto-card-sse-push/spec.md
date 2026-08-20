# Spec: 코인 변동 카드 확정 SSE push 배선

## 개요

`CryptoPriceMoveWatcher`(이슈 #225, PR #236)는 매 분 코인 변동 카드를 확정·저장하지만, 지금은 클라이언트가 `GET /api/instruments/{id}/price-moves`를 폴링해야만 새 카드를 알 수 있다. `SseEmitterRegistry`(이슈 #18)는 `Market.STOCK`·`Market.CRYPTO` 둘 다를 위해 설계돼 있는데 실제로는 `Market.STOCK`만 배선돼 있다. 이 spec은 그 `Market.CRYPTO` 빈 자리를 채워 **코인 변동 카드가 확정되면 구독 중인 클라이언트가 즉시(폴링 없이) 알림을 받게 한다.**

이슈: [#286](https://github.com/finplay-team/finplay/issues/286). PRD에는 이 기능의 요구사항 ID가 없다 — 아래 "PRD 정합성" 절 참고.

## 사용자 시나리오

- 코인 종목 화면을 보고 있는 회원은, 그 종목의 변동 카드가 서버에서 확정되는 즉시(재조회 없이) 새 카드가 생겼다는 알림을 받는다.
- 회원은 알림을 받으면 기존 `GET /api/instruments/{id}/price-moves`를 다시 호출해 카드 전체 내용(서술·수치·근거)을 얻는다 — 알림 자체에는 카드 본문이 없다.
- 주식 종목을 보고 있는 회원은 이 알림을 받지 않는다 — 주식 카드는 노출 게이트(`revealTime`)가 있어 push하면 그 게이트를 우회하게 되므로, 이번 spec은 코인 카드만 대상으로 한다.
- 서버 인스턴스가 여러 대로 늘어나도, 카드를 확정한 인스턴스와 클라이언트가 연결된 인스턴스가 달라도 알림은 정상적으로 전달된다.

## 요구사항

- [ ] 코인 변동 카드가 확정되면(저장 성공 후) 구독 중인 클라이언트에 카드를 식별할 수 있는 최소 정보(카드 id·종목 id)가 push된다.
- [ ] 저장에 실패한 카드는 push되지 않는다.
- [ ] push 실패(Redis 장애·구독자 연결 끊김 등)가 카드 생성 자체를 실패시키지 않는다 — 원장(`price_move_events`)이 정본이다.
- [ ] push가 `CryptoPriceMoveWatcher`의 매 분 감시 틱을 지연시키지 않는다 — 한 구독자의 느린 연결이 다음 종목 감시를 막지 않는다.
- [ ] 여러 서버 인스턴스로 배포돼도 카드를 확정한 인스턴스와 무관하게 모든 구독자가 알림을 받는다(다중 인스턴스 팬아웃).
- [ ] 주식 카드는 이 알림 대상이 아니다 — 노출 게이트를 우회하지 않는다.
- [ ] 코인 시세를 실시간으로 구독할 수 있는 새 스트림 엔드포인트가 생긴다(카드 알림이 그 위에 얹힌다). 이 스트림의 heartbeat·retry 힌트·연결 종료 정리는 기존 `SseEmitterRegistry`(이슈 #18) 계약을 그대로 재사용한다.
- [ ] 기존 `GET /api/stocks/stream` 계약(이벤트 이름·페이로드·heartbeat 간격)은 이 spec으로 인해 한 글자도 바뀌지 않는다.

## 비즈니스 규칙

- 카드 저장(`PriceMoveCardWriter.persist`)과 push는 같은 트랜잭션이 아니다 — push는 저장이 성공적으로 끝난 뒤에만 시도되는 부가 동작이며, push의 성공·실패가 이미 커밋된 카드에 영향을 주지 않는다.
- push는 최선형(best-effort)이다 — 도달을 보장하지 않는다. 놓친 이벤트를 서버가 다시 보내는 기능(`Last-Event-ID` 기반 재생 등)은 이번 spec에서 만들지 않는다 — 기존 `/api/stocks/stream`도 같은 정책이다(재접속 시 `snapshot` 1건만 다시 받는다).
- 알림 payload는 카드 본문(서술·변동률·탐지점수 등)을 담지 않는다 — 카드 내용은 항상 `GET /api/instruments/{id}/price-moves` 재조회로 얻는다. 재조회에 필요한 최소 라우팅 정보(종목 id)와 카드를 식별하는 정보(카드 id)만 담는다.
- 코인 카드는 노출 게이트가 없다(PR #236 기존 결정) — push도 게이트 없이 즉시 나간다. 주식 카드는 이 알림 메커니즘을 아예 타지 않는다.
- 인증되지 않은 요청은 새 스트림을 구독할 수 없다 — 기존 `/api/stocks/stream`과 동일하게 Access Bearer 토큰을 요구한다.

## 범위 제외

- 주식 카드의 push (노출 게이트 우회 문제 — §비즈니스 규칙)
- 매도 회고·뉴스 요약·개장 전 브리핑의 push
- 웹 푸시 알림(브라우저 알림) — 이슈 #261(3차 MVP 알림, NOTI-001~005)로 이동한 별도 트랙이며 이번 spec과 무관하다
- 프론트엔드 구현 — 서버 계약까지만 이 spec의 범위다
- 놓친 이벤트의 재전송(`Last-Event-ID` 기반 replay) — 기존 SSE 계약과 동일하게 미지원
- 알림 저장·목록 조회·읽음 처리 — 이 알림은 순간 이벤트이며 저장되지 않는다(3차 NOTI-003~005의 알림 저장·조회와는 다른 개념이다 — 그쪽은 지정가 체결 알림 전용이고 이번 spec과는 도메인이 다르다)
- 주문·체결·계좌·잔액·보유·손익 원장 변경 — 이 spec은 쓰기가 없다(조회·push 인프라만 추가한다)

## PRD 정합성 — 확인 필요

- **`docs/prd.md` MKT-008과 `docs/specs/003-market-data/plan.md`(2026-07-30)는 "코인 전용 SSE 스트림은 두지 않는다 — `/api/cryptos/stream` 엔드포인트는 만들지 않는다"를 명시적으로 확정한 상태다.** 이 spec은 카드 확정 알림을 위해 그 결정을 뒤집고 코인 SSE 스트림을 신설한다. 근거는 `docs/adr/0018-crypto-card-sse-push.md` §맥락에 있다 — 요약하면, 캔들 재조회로는 "가격"은 알 수 있어도 "카드가 방금 확정됐다"는 사건 자체는 알 수 없기 때문이다.
- 이 반전은 PRD에 요구사항 ID가 없는 상태에서 만드는 것이다(GitHub 이슈 #286에는 있지만 `docs/prd.md`에 대응 행이 없다). `docs/prd.md` §3 "구현 현황"에 새 행을 추가하고, MKT-008·`003-market-data/plan.md`의 "SSE 없음" 문구를 갱신하는 것을 이 spec의 tasks.md에 포함한다.
- **코인 시세 실시간 스트림(snapshot·price·status 3종)까지 함께 만드는 것은 이슈 #286의 완료 조건(카드 push)보다 넓은 범위다.** `SseEmitterRegistry(Market.CRYPTO)`가 이미 설계돼 있고 카드 알림만 얹기보다 `StockPriceStreamService`/`StockPriceSseController`와 대칭 구조로 완성하는 편이 인프라 중복(새 레지스트리·새 heartbeat 로직 등)을 피한다고 판단해 함께 포함했다 — 이 확장 판단은 오케스트레이터가 사용자와 논의해 확정한 결정이며, 이 spec 작성자(planner)가 임의로 넓힌 것이 아니다. `docs/prd.md`에는 이 실시간 스트림 자체의 요구사항 ID도 없다.

## 완료 조건

- [ ] 코인 변동 카드가 확정되면(저장 성공 후) 구독 중인 클라이언트에 카드 id·종목 id가 담긴 알림이 push된다 — 통합 테스트로 확인.
- [ ] 저장에 실패한 카드(예: 근거 매칭 실패로 카드 생성 자체가 취소된 경우)는 push되지 않는다.
- [ ] Redis 발행이 실패해도(장애 모사) 카드 생성 자체는 성공한다(`watchOne()`이 `true`를 반환하고 `price_move_events` 행이 커밋된다).
- [ ] 구독자가 0명이거나 연결이 끊겨 있어도 카드는 정상 생성된다.
- [ ] 느린 구독자(SSE 전송이 오래 걸리는 상황)를 흉내낸 상태에서도 `CryptoPriceMoveWatcher.watch()`의 나머지 종목 감시가 지연 없이 계속된다.
- [ ] 다중 인스턴스를 흉내낸 통합 테스트(같은 Redis를 구독하는 두 개의 독립된 `SseEmitterRegistry`+구독 리스너 조합)에서, 한쪽이 발행한 메시지를 양쪽 모두 수신한다.
- [ ] `GET /api/stocks/stream`의 기존 통합·슬라이스 테스트가 수정 없이 그대로 통과한다.
- [ ] 주식 카드 확정 경로(`PriceMoveCardService`)를 호출해도 새 Redis 채널에 아무 메시지도 발행되지 않는다 — 테스트로 확인.
- [ ] `deploy/nginx.conf`의 `location /api` 블록(`proxy_buffering off`·`proxy_read_timeout 3600s`)이 이미 `/api` 하위 전체에 적용되고 있어 새 엔드포인트가 `/api` 하위인 한 별도 nginx 수정이 필요 없음을 확인한다(경로가 `/api` 밖이면 새 `location`이 필요하다).
- [ ] `docs/api-routes.md`·`docs/api-contracts.md`에 신설 엔드포인트가 반영된다.
- [ ] `docs/prd.md` §3 "구현 현황"에 이 기능의 행이 추가되고, MKT-008·`docs/specs/003-market-data/plan.md`의 "코인 SSE 없음" 문구가 갱신된다.
- [ ] 기존 전체 테스트가 통과한다(`./gradlew build`).
- [ ] 주문·체결·계좌·잔액·보유·손익 원장이 이 spec으로 인해 전혀 바뀌지 않는다 — 이 spec에는 원장 쓰기가 없다.
