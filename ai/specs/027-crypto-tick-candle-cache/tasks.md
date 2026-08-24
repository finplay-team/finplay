# Tasks: 코인 체결 집계와 1분봉 Redis 캐싱

> 이슈 #242. spec `./spec.md`, plan `./plan.md`.
>
> 항목 굵기는 **커밋 1개 = 응집된 기능 조각** 기준이다(`ai/specs/README.md`). 순서대로 진행한다 — ①이 없으면 ②를 검증할 수 없고, ②가 없으면 ④가 붙을 데가 없다.

- [ ] **① 착수 전 실측 (코드 없음)** — PRD §10 Decision Gate 충족. ⓐ 현재 빗썸 REST 캔들 호출량(측정 조건: 동시 사용자 수·차트 재조회 주기·측정 시간을 기록하고 after에서 동일하게 재현), ⓑ 캔들 응답 시간, ⓒ **종목별 "1분 안에 체결이 있는 비율"**(plan "남은 위험" 첫 항목 — 이 값이 낮으면 캐시 효과 자체가 줄어 설계 전제가 흔들린다). 결과를 `run-log.md`에 기록한다. **실측 없이 다음 항목으로 넘어가지 않는다.**

- [ ] **② `BithumbTransactionMessageParser` + `CryptoTrade`** (`market/feed`) — `transaction` 메시지를 체결 목록으로 파싱하는 순수 함수. `content.list`의 **모든 원소** 반환, `type`이 transaction이 아니면 빈 목록, 어떤 파싱 예외도 밖으로 던지지 않고 로그만 남기고 건너뜀(연결 유지 — `BithumbTickerMessageParser`와 동일 정책), `contDtm`(밀리초) 파싱, 심볼 `_KRW` 제거. record 컬렉션은 `List.copyOf` 방어적 복사(`ai/agent-mistakes.md` 2026-07-29 패턴). Jackson3(`tools.jackson`) import 규칙 준수.
  - (+ 단위 테스트: 단건 정상 파싱, **다건 `list` 전부 반환**, 비-transaction 메시지 무시, 필드 누락·잘못된 JSON에서 예외 미전파, 밀리초 파싱, 심볼 변환)

- [ ] **③ `CryptoCandleStore` — Redis 저장소 + Lua 원자 갱신** (`market/store`) — 키 조립을 이 클래스에서만 한다(`PriceStore` 원칙). `recordTrade`(Lua로 open/high/low/close/volumeScaled 원자 갱신 + EXPIRE), `getCandles(symbol, fromMinute, toMinute)`(파이프라인 1회 일괄 `HGETALL`), `getSince`/`touchSince`. **거래량은 `×10^8` `long`으로 `HINCRBY` 누적**(소수 9자리 이상은 집계 제외 + 로그), 가격은 원본 문자열 저장·`tonumber` 비교. TTL 4시간(200봉 상한에서 역산 — 임의 숫자 아님). 시각 변환은 `clock.getZone()` 기준(`PriceStore`와 동일).
  - (+ **Testcontainers 실제 Redis 통합 테스트 — 이 작업의 핵심 검증, mock 대체 금지**: 스레드 다수가 같은 심볼·같은 분에 동시 체결 주입 시 `volumeScaled` 합계 정확·**유실 0건**, high/low가 전체 최대·최소와 일치, 갱신 중 조회에서 중간 상태 미노출, 짧은 TTL 주입해 만료 확인, 수량 8자리 왕복 정확성·9자리 제외)

- [ ] **④ `BithumbWebSocketFeedClient` — `transaction` 구독 추가** — 같은 세션에 구독 메시지 2건 전송(연결 추가 없음). `handleTextMessage`를 `type`으로 분기해 ticker는 기존 경로, transaction은 `CryptoCandleStore.recordTrade` + **`PriceStore.saveTick`(현재가도 갱신 — plan "왜 transaction도 현재가를 갱신하는가")** 양쪽 호출. `afterConnectionEstablished`에서 `touchSince`. **두 구독 중 하나라도 실패하면 기존과 같은 실패 경로**(소켓 정리 → DISCONNECTED → 재연결 예약)로 간다 — 반쪽 상태로 두지 않는다. `@PreDestroy` 중복 등록 금지(이슈 #104 리뷰 지적 유지).
  - (+ 단위 테스트: 구독 메시지 2건 전송, type 분기, 다건 list 전부 `recordTrade` 호출, transaction이 `saveTick`도 호출, 구독 실패 시 재연결 예약, 늦게 온 체결 무시. mock `CryptoCandleStore`·`PriceStore`로 검증 — 외부 네트워크 없음)
  - (+ 회귀: 기존 ticker 경로·`CryptoPriceUpdatedEvent` 발행 규칙(과거 틱 무시)이 두 채널 동시 유입에서도 유지)

- [ ] **⑤ `CachedCryptoCandleProvider` — 조회 경로 연결** (`market/service`) — `CryptoCandleProvider` 데코레이터. `@Profile({"prod","crypto-real"})` + `@Primary`로 `BithumbRestCandleProvider`를 감싼다(프로필 상호배타 유지, Fake 프로필은 무영향). `interval != 1m`이면 즉시 위임. `since` 기준으로 위임 구간·캐시 구간 분할 → 캐시는 파이프라인 1회, 위임은 빗썸 1회 → 시각 오름차순 병합(겹치면 **캐시 채택**). **Redis 예외는 삼키고 전량 위임**(단일 장애점 방지). 빗썸 실패 시 위임 구간이 있으면 502, 없으면 200.
  - (+ 단위 테스트: 구간 분할, `interval != 1m` 즉시 위임, 겹치는 `sourceTime`에서 캐시 채택, Redis 예외 시 전량 위임(위임 mock 호출 검증), 캐시가 전 구간 커버 시 **빗썸 미호출** 검증)
  - (+ Testcontainers 통합: 캐시+위임 이어붙이기 `sourceTime` 중복·누락 없음, 빗썸 실패 시 502/200 분기)
  - (+ `@WebMvcTest` 회귀: `interval` 4종 계약(200/400/404/401/502)이 기존과 동일 — 경로가 바뀌어도 계약은 그대로)

- [ ] **⑥ 문서 동기화 + after 실측** — `docs/api-contracts.md` 코인 캔들 절의 "저장·캐시하지 않는다" 서술 정정(같은 커밋), `ai/prd.md` §3 구현 현황 MKT-010 행 갱신(근거 칸에 이 PR 번호), `ai/specs/003-market-data`·`013-candle-interval`의 "코인은 저장·캐시 없음" 서술에 이력 표시. **①과 동일 조건으로 after 실측**(호출량·응답시간·캐시 적중률)해 `run-log.md`에 before/after 대조 기록. 외부 스모크(실제 빗썸 transaction 수신·동시 구독 유지·`contQty` 실제 자릿수)는 자동 테스트와 **구분해** 보고(C-005). `./gradlew build` 통과.

## 하지 않는 것

`ai/api-routes.md` 갱신 — **신규·변경 엔드포인트가 없다**(계약 무변경, 내부 경로만 변경). Flyway 마이그레이션 — **신규 테이블 없음**(ADR-0004 대상 아님).
