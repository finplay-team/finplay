# Spec: 코인 시세 STALE 판정 완전 제거

> GitHub 이슈 #379. 팀 확정 방향: 마지막으로 기록된 가격은 경과 시간과 무관하게 항상 `AVAILABLE`로 취급한다 — 재논의하지 않는다.
> 선행 근거: `docs/specs/003-market-data/spec.md` MKT-003·MKT-004, `docs/specs/032-price-quote-stale-split/spec.md`, `docs/specs/034-crypto-price-rest-backup/spec.md`.
> 요구사항 ID는 이 spec 전용 네임스페이스 `PRICE-NOSTALE-*`를 쓴다 — `PRICE-STALE-*`(032)·`PRICE-REST-*`(034)와 같은 패턴으로, PRD 본문에 요구사항 절을 새로 추가하지 않고 이 spec이 정본이다. PRD §3 "구현 현황"에는 완료 시 행을 추가·갱신한다(CLAUDE.md 규칙 10).

## 이 spec이 되돌리는 것 — 032·034 결정과의 관계 (필수 선행 확인)

이 변경은 `docs/prd.md` §3에 "완료"로 기록된 두 spec의 결정을 뒤집는다. 각 요구사항별로 무엇이 되돌려지고 무엇이 그대로인지 명시한다.

| 원본 요구사항 | 내용 | 이 spec의 처리 |
|---|---|---|
| PRICE-STALE-001 (032) | 표시 경로에서 연결 유지+10초 초과(stale)면 409 대신 200+`STALE` 반환 | **되돌림.** 연결 유지+수신 이력 있음이면 경과 시간과 무관하게 항상 `AVAILABLE`. `STALE` 분기 자체를 제거한다 |
| PRICE-STALE-002 (032) | `PriceStatus`에 `STALE` 추가(3값 enum) | **되돌림.** `PriceStatus`를 `AVAILABLE`·`UNAVAILABLE` 2값으로 되돌린다 — 아래 "설계 결정" 참조 |
| PRICE-STALE-003 (032) | `CryptoCandleAndPriceIndependenceTest` 두 테스트 회귀 없음 | **그대로.** 이 spec과 무관한 별개 계약이며 회귀 없어야 한다는 제약은 유지된다 |
| PRICE-STALE-004 (032) | 코인 SSE snapshot도 같은 완화(`STALE` 노출) | **되돌림.** `PriceQueryService.getPriceQuote`를 그대로 재사용하는 구조라 001이 되돌려지면 자동으로 함께 되돌려진다 — SSE 쪽 코드 변경은 없다 |
| PRICE-STALE-005 (032) | `HoldingValuationService`·`SyntheticPriceService`·`PracticePriceSessionService`·`PracticeHoldingObservationService`는 STALE도 엄격하게(또는 완화된 대로) 취급 | **무의미해짐(코드 변경 없음).** `STALE`이 더 이상 발생하지 않으므로 이 소비자들의 STALE 분기는 그냥 도달하지 않는다. `HoldingValuationService`의 `quote.status() != AVAILABLE` 조건은 그대로 두되(2값 enum에서도 여전히 정확함), STALE 케이스를 검증하던 테스트는 정리한다 |
| PRICE-REST-001~003 (034) | 관측시각/체결시각 분리, REST 폴링 백업, 웹소켓 실시간성 보존 | **그대로, 코드 변경 없음.** 이 인프라(`PriceStore.observedAt`·`recordObservation`·`BithumbRestTickerPoller`)는 `CryptoPriceSnapshotService`가 쓰는 `PriceStore.isPriceAvailable()`(변동 카드 재료용, 이번 spec과 무관)을 위해 계속 필요하다 — 아래 "범위 제외" 참조 |
| PRICE-REST-004 (034) | 체결 경로도 STALE 허용(표시와 동일 규칙) | **도달 불가능해져 무의미해짐(코드 변경 없음).** `getOrderExecutionPrice`의 코인 분기는 이미 `getCryptoDisplayPriceQuote`에 위임하는 구조다(034가 이렇게 통합해 놓았다). 표시 경로가 다시는 `STALE`을 만들지 않으므로 이 위임 구조를 그대로 둬도 체결은 항상 `AVAILABLE`만 받는다 — "STALE이어도 체결 허용"이라는 문장 자체가 의미를 잃는다 |
| PRICE-REST-005·006 (034) | fail-closed 잔여선(연결 끊김·수신 이력 없음), 스케줄러 풀 크기 | **그대로, 코드 변경 없음.** 이번 spec의 대상이 아니다 |

**PR #377(이슈 #376, 방금 병합)과는 무관하다.** 그 PR은 `BithumbRestTickerPoller`의 Jackson 파싱 버그를 고쳐 "10초 안에 관측이 갱신되게" 만든 것이고, 이 spec은 "10초 기준 자체를 없애는" 별개 작업이다. 둘 다 반영된 뒤에도 이 spec의 변경은 그대로 유효하다.

## 개요

코인 현재가는 지금 마지막 체결(또는 REST 관측) 시각이 10초를 넘으면 연결이 살아있어도 `PriceStatus.STALE`로 표시된다(032·034). 이 spec은 이 STALE 판정 자체를 없앤다 — **연결이 유지되고 있고 그 심볼의 가격을 한 번이라도 받은 적이 있으면, 마지막 가격을 경과 시간과 무관하게 항상 `AVAILABLE`(정상 현재가)로 취급한다.** 몇 초·몇 분·몇 시간이 지났든 마지막 가격을 그대로 현재가로 띄운다.

빗썸 웹소켓 연결이 끊기는 상황은 이번 범위가 아니다 — 연결이 항상 유지된다고 가정하고, 다음 두 판정은 그대로 유지한다.

- 연결 끊김(`getConnectionStatus() != CONNECTED`) → `UNAVAILABLE`
- 이 심볼의 가격을 한 번도 받은 적 없음 → `UNAVAILABLE`

없애는 건 "연결은 유지되는데 마지막 관측이 오래됐다"는 이유로 `STALE`을 붙이는 로직 하나뿐이다.

## 사용자 시나리오

- 회원이 체결이 뜸한 코인(웹소켓 연결은 정상, 마지막 체결이 10초·10분·몇 시간 전이어도 무관)의 현재가를 조회하면, 항상 200과 `status: "AVAILABLE"`로 마지막 가격을 받는다 — `"STALE"`은 더 이상 나타나지 않는다.
- 같은 코인을 매수·매도 주문해도 변화가 없다 — 034 이후 체결은 이미 이 마지막 가격으로 이뤄지고 있었고, 이 spec은 그 사실을 표시 판정과 다시 일치시킬 뿐이다.
- 회원이 코인 SSE 스트림(`GET /api/cryptos/stream`)을 구독 중일 때도 snapshot에서 `"STALE"`이 더 이상 나타나지 않는다 — 연결 유지 중인 종목은 전부 `"AVAILABLE"`이다.
- 회원이 보유자산 화면(`GET /api/holdings` 등)을 볼 때, 웹소켓 연결이 살아있는 한 평가금액·수익률이 더 이상 "-"로 깜빡이지 않고 계속 값을 유지한다 — 지금까지의 깜빡임은 `HoldingValuationService`가 STALE을 UNAVAILABLE과 동일하게 평가불가 처리했기 때문이었다(032 PRICE-STALE-005).
- 회원이 그 코인의 웹소켓 연결이 끊기거나, 서버 기동 이후 그 심볼의 시세를 한 번도 받지 못했으면, 여전히 409 `PRICE_UNAVAILABLE`(주문)·`priceStatus: "UNAVAILABLE"`(보유자산)을 받는다 — 이 두 판정은 이번 변경의 대상이 아니다.

## 요구사항

### PRICE-NOSTALE-001 표시·체결 판정에서 stale 분기 제거

- [ ] `PriceQueryService.getCryptoDisplayPriceQuote(Instrument)`는 연결이 `CONNECTED`이고 그 심볼의 마지막 가격을 받은 적이 있으면, 그 가격의 관측 시각이 얼마나 오래됐든 항상 `PriceStatus.AVAILABLE`을 반환한다. `priceStore.isStale(...)` 호출·분기를 제거한다.
- [ ] `PriceQueryService.getCryptoDisplayPriceQuotes(List<Instrument>)`도 동일하게 배치 판정에서 stale 분기를 제거한다.
- [ ] `PriceQueryService.getOrderExecutionPrice(Instrument)`의 코인 분기는 코드를 바꾸지 않는다 — 이미 `getCryptoDisplayPriceQuote`에 위임하는 구조이므로 위 변경만으로 체결 경로도 자동으로 항상 `AVAILABLE`만 받는다.
- [ ] 연결이 `DISCONNECTED`이거나, 연결은 `CONNECTED`이지만 그 심볼의 시세를 한 번도 받은 적이 없으면(최신가 자체가 없음) 기존과 동일하게 `PriceStatus.UNAVAILABLE`이다 — 이번 변경의 대상이 아니다.
- [ ] 주식은 이번 변경의 대상이 아니다 — 주식 가격 조회 경로는 무변경이다.

### PRICE-NOSTALE-002 `PriceStatus`를 2값으로 되돌림

- [ ] `PriceStatus`에서 `STALE`을 제거하고 `AVAILABLE`·`UNAVAILABLE` 2값으로 되돌린다(PRICE-STALE-002 원복).
- [ ] `PriceStatus.STALE`을 참조하는 프로덕션·테스트 코드 전부를 이 되돌림에 맞게 정리한다 — 컴파일 에러 없이 `./gradlew build`가 통과해야 한다.

### PRICE-NOSTALE-003 하위 소비자 회귀 없음

- [ ] `HoldingValuationService.buildValuation`의 `quote.status() != PriceStatus.AVAILABLE` 조건은 코드 변경 없이 그대로 둔다 — 2값 enum에서도 여전히 정확하다(`UNAVAILABLE`만 걸러낸다). 연결 유지 중인 코인 보유분은 경과 시간과 무관하게 항상 평가금액·수익률이 채워지는 것을 회귀 테스트로 확인한다("-" 깜빡임 해소).
- [ ] `SyntheticPriceService`·`PracticePriceSessionService`(`quote.status() == PriceStatus.AVAILABLE`로 판정)는 코드 변경 없이 그대로 동작한다 — 이제 연결 유지+수신 이력 있음이면 항상 이 조건을 통과하므로, 예전에 stale로 fallback 가격을 쓰던 경로가 사라지고 항상 실시세를 쓰게 된다. 이 동작 변화를 의도된 것으로 문서화하고 회귀 테스트를 갱신한다.
- [ ] `PracticeHoldingObservationService`(세션 귀속 체결이 없을 때 `PriceQueryService.getPrice`를 그대로 호출)는 코드 변경 없이 그대로 동작한다 — STALE 특수 취급이 사라지므로 관련 테스트를 단순화한다.
- [ ] `CryptoPriceStreamService`(SSE snapshot)는 코드 변경 없이 그대로 동작한다 — `PriceQueryService.getPriceQuote`가 다시는 `STALE`을 주지 않으므로 snapshot도 자동으로 `STALE`을 노출하지 않는다.
- [ ] 연결 끊김·수신 이력 없음(`UNAVAILABLE`) 판정에는 위 어느 소비자에서도 회귀가 없다.

### PRICE-NOSTALE-004 이번 spec이 손대지 않는 것

- [ ] `PriceStore`의 `isStale(LocalDateTime)`·`STALE_THRESHOLD`·`observedAt`/`receivedAt` 분리·`recordObservation(...)`은 코드 변경이 없다 — `CryptoPriceSnapshotService.recordSnapshot(...)`가 여전히 `PriceStore.isPriceAvailable(symbol)`을 직접 호출해 "동결된 최신 틱"으로 변동 카드를 만들지 않도록 걸러내는 데 쓴다. 이 게이트는 화면 표시·체결과 무관한 별개 목적(AI 피드백 변동 카드의 재료 신뢰도)이라 이번 spec의 대상이 아니다.
- [ ] `BithumbRestTickerPoller`(REST 폴링 백업, 034 PRICE-REST-002)는 코드 변경이 없다 — 여전히 3초 주기로 관측 시각을 갱신하며, `CryptoPriceSnapshotService`의 신뢰도 게이트에 계속 기여한다.
- [ ] 빗썸 웹소켓 연결이 끊기는 시나리오의 처리·복구 로직은 손대지 않는다.

## 비즈니스 규칙

- "연결 끊김/시세를 한 번도 받은 적 없음"과 "연결은 유지되지만 오래됨"은 더 이상 서로 다른 상태가 아니다 — 후자는 이 spec으로 전자와 구별되는 별도 상태에서 빠지고 정상(`AVAILABLE`)에 합쳐진다. 전자만이 "보여줄 가격 자체가 없는 상태"로 남는다.
- 코인 시세 정본은 여전히 Redis 최신 틱이다 — 이 spec은 새 Redis 키나 저장 항목을 만들지도, 기존 것을 지우지도 않는다.
- 표시·체결에 쓰는 가격은 여전히 "실제로 수신된 마지막 체결가"다 — 임의값·보간값으로 대체하지 않는다.

## 범위 제외

- 빗썸 웹소켓 연결이 실제로 끊기는 시나리오의 처리·복구 로직 — 항상 연결돼 있다고 가정한다.
- `PriceStore.isStale`·`STALE_THRESHOLD`·`observedAt`/`receivedAt` 분리·`recordObservation`·`BithumbRestTickerPoller` — `CryptoPriceSnapshotService`(변동 카드 재료 신뢰도 게이트)가 계속 이 인프라를 쓰므로 그대로 둔다. 이 게이트 자체의 정책(변동 카드에 오래된 가격을 쓸지)을 다시 여는 것은 별도 spec이다.
- `PriceStore.isPriceAvailable(symbol)`·`getLatestPrices(List)` — 코드 변경 없음. `CryptoPriceSnapshotService` 등 이 spec과 무관한 소비자가 계속 쓴다.
- 프론트엔드(`finplay-frontend`, 별도 레포)의 "지연" 배지 제거 작업 — 이 spec은 백엔드(finplay-api)만 다룬다. `Trade.tsx`가 더 이상 받지 못할 `"STALE"` 값에 어떻게 대응할지는 프론트 레포의 별도 작업이다.
- 주식 가격 경로 전체 — 이번 spec은 코인에만 해당한다.
- `PriceQuoteDto`·`PriceResponse`의 필드 구조 변경 — `status` 값 범위만 좁아질 뿐 필드는 그대로다.

## 완료 조건

- [ ] 코인 종목이 연결 유지 상태이고 마지막 가격을 받은 적이 있으면, 관측 시각이 10초를 훨씬 넘겨도(예: 수 분·수 시간) `GET /api/instruments/{instrumentId}/price`가 200과 `status: "AVAILABLE"`을 반환하는 테스트 통과.
- [ ] 코인 종목이 연결 끊김이거나 시세를 한 번도 받은 적이 없을 때는 여전히 409 `PRICE_UNAVAILABLE`을 반환하는 테스트 통과(회귀 확인).
- [ ] `PriceStatus`가 `AVAILABLE`·`UNAVAILABLE` 2값만 갖는다 — `STALE` 참조가 프로덕션·테스트 코드에 남아있지 않다.
- [ ] `getOrderExecutionPrice`의 코인 분기가 경과 시간과 무관하게 항상 마지막 가격으로 체결에 성공하는 테스트 통과.
- [ ] `HoldingValuationService`가 연결 유지 중인 코인 보유분의 평가금액·수익률을 경과 시간과 무관하게 계속 채우는 회귀 테스트 통과("-" 깜빡임 해소 확인) — `docs/specs/036-remove-crypto-stale-status/tasks.md`의 관련 항목에서 명시적으로 다룬다.
- [ ] 코인 SSE snapshot이 연결 유지 중인 종목에서 더 이상 `"STALE"`을 노출하지 않는 회귀 테스트 통과.
- [ ] `CryptoCandleAndPriceIndependenceTest`의 기존 두 테스트가 회귀 없이 통과.
- [ ] `docs/api-contracts.md`의 `/price` 절·코인 SSE snapshot 절·주문 절에서 `STALE` 관련 서술이 제거되고 이 spec을 반영해 갱신됨(같은 커밋).
- [ ] `docs/api-routes.md`의 `/price` 라우트 설명에서 `STALE` 언급이 제거됨.
- [ ] `docs/specs/003-market-data/spec.md` MKT-004가 "연결 유지+가격 있음이면 경과 시간과 무관하게 항상 정상"으로 재작성됨.
- [ ] `docs/specs/032-price-quote-stale-split/spec.md`·`docs/specs/034-crypto-price-rest-backup/spec.md` 상단에 이 spec으로 대체된 요구사항(위 표 참고)을 명시하는 각주가 추가됨(체크박스 원문은 유지, 027이 003을 대체할 때 쓴 각주 패턴).
- [ ] `docs/prd.md` §3 "구현 현황"의 PRICE-STALE-001~005·PRICE-REST-001~006 관련 행이 이 변경을 반영해 갱신됨(근거: 이 spec/PR).
- [ ] `./gradlew build` 통과.
