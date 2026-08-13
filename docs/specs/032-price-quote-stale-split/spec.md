# Spec: 코인 시세 표시와 체결 판정의 stale 기준 분리

> GitHub 이슈 #355. 팀 확정 방향: 이슈 코멘트에서 제시된 1/2/3안 중 **1안**(표시용 판정과 체결용 판정 분리, yxejxnn 확정) — 재논의하지 않는다.
> 선행 근거: `docs/specs/003-market-data/spec.md` MKT-003·MKT-004, `docs/adr/0002-architecture.md`.
> 요구사항 ID는 이 spec 전용 네임스페이스 `PRICE-STALE-*`를 쓴다 — `docs/prd.md`의 EDU-PRACTICE-*·COIN-PRICE-RUNTIME-*·FEED-*와 같은 패턴으로, PRD 본문에 요구사항 절을 새로 추가하지 않고 이 spec이 정본이다. PRD §3 "구현 현황"에는 완료 시 행을 추가한다(CLAUDE.md 규칙 10).

## 개요

`GET /api/instruments/{instrumentId}/price`는 코인 종목의 최신 틱 수신시각이 10초를 넘으면 무조건 409 `PRICE_UNAVAILABLE`을 반환한다. 이 10초 기준(MKT-004)은 원래 "체결을 막아야 하는가"를 판정하려고 만든 fail-closed 규칙인데, 지금 코드는 같은 판정 결과를 **화면 조회에도 그대로 재사용**하고 있다. 그 결과 웹소켓 연결 자체는 멀쩡한데 체결이 뜸한 저유동성 코인은 화면에서 가격을 아예 볼 수 없다.

이 spec은 "체결을 막을지"와 "화면에 보여줄지"를 서로 다른 질문으로 분리한다. **연결이 끊겼거나 시세를 한 번도 받은 적이 없으면** 화면도 체결도 똑같이 막는다(보여줄 가격 자체가 없다). 반면 **연결은 살아있는데 최신 틱만 10초를 넘겼으면**, 체결은 계속 막되(MKT-004 그대로) 화면은 마지막으로 받은 진짜 가격을 "지연됨" 표시와 함께 계속 보여준다.

## 사용자 시나리오

- 회원이 체결이 뜸한 저유동성 코인(예: 웹소켓 연결은 정상이지만 10초 넘게 신규 체결이 없는 종목)의 현재가를 조회하면, 409 대신 200과 함께 마지막으로 받은 실제 가격·수신시각·`status: "STALE"`을 받는다.
- 같은 종목의 웹소켓 연결이 끊기거나 서버 기동 이후 그 종목의 시세를 한 번도 받지 못했으면, 회원은 여전히 409 `PRICE_UNAVAILABLE`을 받는다 — "보여줄 가격이 아예 없는 상태"는 이번 완화 대상이 아니다.
- 회원이 그 코인을 실제로 매수·매도 주문하면, 최신 틱이 10초를 넘긴 상태에서는 화면 조회 성공 여부와 무관하게 여전히 주문이 거부된다(MKT-004 fail-closed 유지) — "화면에 가격이 보이니 체결도 될 것"이라고 오해하지 않도록 화면에 지연 표시가 남아 있다.
- 회원이 코인 SSE 스트림(`GET /api/cryptos/stream`)을 구독 중일 때도 같은 완화가 적용된다 — snapshot이 저유동성 종목을 `UNAVAILABLE`로 비우지 않고 마지막 가격을 `STALE`로 보여준다.
- 회원이 보유자산 화면(`GET /api/holdings`, 평가손익)을 볼 때는 이번 완화가 적용되지 않는다 — stale인 코인 보유분은 지금처럼 `priceStatus: "UNAVAILABLE"`로 평가불가 처리된다(아래 "완화·엄격 판단" 참조).
- 운영자는 `CryptoCandleAndPriceIndependenceTest`(캔들-현재가 독립성 계약)가 이번 변경으로 회귀하지 않는 것을 확인한다.

## 요구사항

### PRICE-STALE-001 표시 경로 stale 완화

- [ ] `GET /api/instruments/{instrumentId}/price`는 코인 종목에서 빗썸 연결상태가 `CONNECTED`이고 그 심볼의 최신 틱을 한 번이라도 받은 적이 있으면, 최신 틱 수신시각이 10초를 넘겨도(stale) 409를 던지지 않고 200과 함께 그 마지막 가격·수신시각·`status: "STALE"`을 반환한다.
- [ ] 연결상태가 `DISCONNECTED`이거나, 연결은 `CONNECTED`이지만 그 심볼의 시세를 한 번도 받은 적이 없으면(최신가 자체가 없음) 기존과 동일하게 409 `PRICE_UNAVAILABLE`이다 — 이번 완화 대상이 아니다.
- [ ] 최신 틱이 10초 이내(fresh)면 기존과 동일하게 `status: "AVAILABLE"`이다 — 이 경로의 기존 성공 케이스는 한 글자도 달라지지 않는다.
- [ ] 주식은 이번 변경의 대상이 아니다 — 주식 가격 조회(`StockPriceProvider` 경로)는 무변경이다.

### PRICE-STALE-002 상태값 확장

- [ ] `PriceStatus`에 `STALE`을 추가한다(`AVAILABLE`·`UNAVAILABLE`에 이어 3번째 값). `PriceQuoteDto`·`PriceResponse`의 필드 구조는 바꾸지 않는다 — 이미 상태를 문자열로 그대로 직렬화하는 기존 구조가 새 값을 그대로 실어 나른다.
- [ ] `STALE` 상태의 `PriceQuoteDto`는 `price`·`sourceTime`이 **null이 아니다** — 마지막으로 받은 실제 값을 담는다(`UNAVAILABLE`은 지금처럼 둘 다 null).

### PRICE-STALE-003 체결 경로 불변

- [ ] `PriceQueryService.getOrderExecutionPrice`(주문 체결 전용 진입점)와 그 뒤의 `requireAvailable` fail-closed 판정은 이번 변경으로 동작이 달라지지 않는다 — stale 상태의 코인은 이번 변경 이후에도 여전히 주문이 409 `PRICE_UNAVAILABLE`로 거부된다. `OrderExecutionServiceTest`·`PriceQueryServiceTest`의 기존 체결 경로 테스트는 회귀 없이 통과해야 한다.
- [ ] `CryptoCandleAndPriceIndependenceTest`의 두 테스트(`candleQueryStillSucceedsWhenPriceStoreIsEmptyOrDisconnected`, `priceQueryStillSucceedsWhenCryptoCandleProviderFails`)는 기존 단정을 그대로 유지한 채 통과한다 — 이 두 테스트를 깨지 않는 것이 이번 spec의 명시적 제약이다(2026-08-13 GitHub Actions 자동 구현 시도가 이 중 하나를 깼던 이력이 있다).

### PRICE-STALE-004 표시 소비자 확장 — 코인 SSE snapshot

- [ ] 코인 SSE 스트림(`GET /api/cryptos/stream`, `docs/specs/028-crypto-card-sse-push`)의 `snapshot` 이벤트도 같은 완화를 적용받는다 — 연결이 살아있고 최신 틱을 받은 적이 있는 종목은 stale이어도 `price`·`sourceTime`이 채워지고 `status: "STALE"`로 노출된다(빈 값 `UNAVAILABLE`로 떨어뜨리지 않는다). `price` 이벤트(빗썸 틱 push 그 자체)는 원래 항상 신선한 값만 발생하므로 영향이 없다.

### PRICE-STALE-005 엄격 유지 소비자 고정

다음 소비자는 이번 완화 대상이 **아니며**, stale을 여전히 "가격 없음"과 동일하게 취급한다. 이유는 각 항목에 적는다.

- [ ] **`HoldingValuationService`(포트폴리오 평가손익, `GET /api/holdings`·`GET /api/portfolio`·`GET /api/accounts/summary`)** — 엄격 유지. 근거: 보유자산의 평가금액·손익·수익률은 사용자가 "지금 이 가격에 팔면 얼마"라고 해석할 수 있는 금융 수치이고, 이미 손익 판단에 준하는 무게를 갖는다. 반면 `/price` 단건 조회는 "화면에 숫자가 보이느냐"만의 문제라 완화의 이익이 더 크다. 이슈 분석은 이 항목을 확정하지 않았으나, 이 spec에서 실제 코드(`HoldingValuationService.buildValuation`)를 확인한 결과 `quote.status() == PriceStatus.UNAVAILABLE`만 검사하고 else 분기를 항상 `PriceStatus.AVAILABLE`로 **하드코딩**하고 있었다 — 즉 아무 조치 없이 두면 `PriceQueryService`가 STALE을 반환하는 순간 이 서비스가 그 사실을 조용히 삼키고 "AVAILABLE"로 잘못 보고하는 잠재 결함이 있다. 이 spec은 이 결함을 막기 위해 조건을 `!= PriceStatus.AVAILABLE`로 명시적으로 고쳐 STALE도 UNAVAILABLE과 동일하게 처리되도록 고정한다(아래 plan.md 참조) — "엄격 유지"는 방치가 아니라 명시적 코드 변경으로 확정한다.
- [ ] **`SyntheticPriceService`·`PracticePriceSessionService`(교육 튜토리얼 합성 시세·코인 연습 세션 시작가)** — 엄격 유지, 코드 변경 없음. 두 서비스 모두 `quote.status() == PriceStatus.AVAILABLE`일 때만 실시세를 시작가로 쓰고, 그 외에는 이미 정해진 고정 fallback 값(`FALLBACK_START_PRICE`)을 쓴다. `STALE`은 `AVAILABLE`이 아니므로 이 조건에서 자동으로 fallback 경로를 타 기존과 동일하게 동작한다 — 별도 코드 변경이 필요 없다. `SyntheticPriceService`는 클래스 주석에도 "실제 판정에 쓰이지 않는 튜토리얼 참고용 차트"라고 명시돼 있어 완화의 실익이 없고, `PracticePriceSessionService`는 세션의 시작 앵커가 이후 교육용 지정가 체결(`buyTrade`)에 쓰이므로 체결에 준하는 신중함을 유지한다.
- [ ] **`CryptoPriceSnapshotService`(변동 카드 재료 스냅샷)·코인 변동 감시(`CryptoPriceMoveWatcher` 계열)·주문 체결 전 경로 전체** — 엄격 유지, 코드 변경 없음. 이들은 `PriceQuoteDto`/`PriceStatus`를 거치지 않고 `PriceStore.isPriceAvailable(symbol)`을 직접 호출한다 — 이 메서드 자체(연결+신선도 동시 판정)는 이번 spec에서 손대지 않으므로 자동으로 무관하다.
- [ ] **`PracticeHoldingObservationService`(실습 3단계 가격 관찰 근거값)** — 완화됨(의도적 승계, 별도 코드 변경 없음). 이 서비스는 세션 귀속 체결이 없을 때 `PriceQueryService.getPrice(instrumentId)`를 그대로 호출한다(PRICE-STALE-001의 그 메서드). `getPrice`가 완화되므로 이 서비스도 자동으로 완화를 물려받는다 — 관찰은 학생이 스스로 화면에서 본 가격을 근거로 판정 로직을 채점하는 것이라 실제 최신값(비록 10초 넘게 지연됐더라도)이 고정 fallback보다 더 정확한 근거다.

## 비즈니스 규칙

- "연결 끊김/시세 없음"과 "연결은 유지되지만 10초 초과(stale)"는 서로 다른 상태다 — 전자는 **보여줄 가격 자체가 없는 것**이고 후자는 **가격은 있지만 오래된 것**이다. 이 spec은 이 둘을 명확히 구분하는 것이 핵심이며, 어느 소비자든 이 둘을 하나의 `boolean`으로 뭉뚱그리지 않는다.
- MKT-004의 "10초 초과·연결 끊김 시 거부" 규칙은 **주문 가능 상태 판정에서는 문구 그대로 유지된다** — 이 spec은 MKT-004를 대체(supersede)하지 않는다. MKT-004의 원문도 "주문 가능 상태"·"주문을 거부한다"로 이미 체결 관점으로 좁게 쓰여 있었다(`docs/prd.md` §4 MKT-004, `docs/specs/003-market-data/spec.md` MKT-004) — 지금까지의 구현이 이 문구보다 넓게(화면 조회까지) 적용했던 것이 이슈 #355의 원인이다. 따라서 PRD·spec 003의 MKT-004 **문구 자체는 고치지 않고**, 구현이 그 문구의 의도에 맞게 좁혀지는 것이라는 각주만 남긴다.
- 코인 시세 정본은 여전히 Redis 최신 틱이며, 이 spec은 새 Redis 키나 새 저장 항목을 만들지 않는다 — `PriceStore`가 이미 갖고 있는 연결상태·최신가·수신시각·`isStale` 판정을 조합만 다르게 해서 쓴다.
- 표시 경로에서 노출하는 `STALE` 가격은 여전히 "실제로 수신된 마지막 체결가"다 — 임의값·보간값·직전 스냅샷 평균 등으로 대체하지 않는다.

## 범위 제외

- MKT-004 자체의 10초 임계값을 바꾸는 것 — 이 spec은 표시/체결 판정을 분리할 뿐 임계값 숫자는 그대로 둔다.
- `PriceResponse`에 "몇 초 전 시세인지" 같은 새 파생 필드를 추가하는 것 — `sourceTime`이 이미 있으므로 클라이언트가 필요하면 직접 계산할 수 있다. 이 spec은 최소 변경만 한다.
- 주식 가격 조회·SSE·재생세션 관련 로직 전부 — 이번 이슈는 코인에만 해당한다.
- 포트폴리오 평가손익(`HoldingValuationService`)의 완화 여부를 다시 여는 것 — 이 spec은 명시적으로 엄격 유지로 확정했다(PRICE-STALE-005). 완화가 필요해지면 별도 spec으로 논의한다.
- 코인 캔들(MKT-008·MKT-010)·`CryptoCandleAndPriceIndependenceTest`가 고정하는 캔들-현재가 독립성 계약 자체를 바꾸는 것 — 이 spec은 그 계약을 건드리지 않고 오히려 회귀하지 않는지 확인만 한다.

## 완료 조건

- [ ] 코인 종목이 연결 유지 + stale(10초 초과) 상태일 때 `GET /api/instruments/{instrumentId}/price`가 200과 `status: "STALE"`, null이 아닌 `price`·`sourceTime`을 반환하는 테스트 통과.
- [ ] 코인 종목이 연결 끊김이거나 시세를 한 번도 받은 적이 없을 때는 여전히 409 `PRICE_UNAVAILABLE`을 반환하는 테스트 통과(기존 계약 유지 회귀 확인).
- [ ] 코인 종목이 fresh(10초 이내)일 때 기존과 동일하게 `status: "AVAILABLE"`을 반환하는 회귀 테스트 통과.
- [ ] `getOrderExecutionPrice`(체결 경로)는 stale 상태에서 이번 변경 이후에도 여전히 `PRICE_UNAVAILABLE`을 던지는 테스트 통과 — MKT-004 fail-closed 무변경 확인.
- [ ] `CryptoCandleAndPriceIndependenceTest`의 기존 두 테스트가 회귀 없이 통과.
- [ ] `HoldingValuationService`가 stale 코인 보유분을 여전히 `priceStatus: "UNAVAILABLE"`로 처리(평가금액·손익 null)하는 테스트 통과 — 완화가 새지 않음을 고정.
- [ ] `SyntheticPriceService`·`PracticePriceSessionService`가 stale 입력에서 여전히 고정 fallback 시작가를 쓰는 테스트 통과.
- [ ] 코인 SSE snapshot이 stale 종목을 `status: "STALE"` + 실제 마지막 가격으로 노출하는 테스트 통과.
- [ ] `docs/api-contracts.md`의 `/price` 절과 코인 SSE snapshot 절이 `STALE` 상태를 반영해 갱신됨(같은 커밋).
- [ ] `docs/specs/003-market-data/spec.md` MKT-004에 이 spec으로의 구현 세분화 각주가 남음(원문 문구는 유지).
- [ ] `docs/prd.md` §3 "구현 현황"에 이 기능의 행이 추가됨(근거: PR 번호).
- [ ] `./gradlew build` 통과.
