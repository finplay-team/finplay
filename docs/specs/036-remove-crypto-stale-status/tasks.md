# Tasks: 코인 시세 STALE 판정 완전 제거

- [x] **1. `PriceStatus` 2값 원복 + `PriceQueryService` 표시 판정에서 stale 분기 제거**
  - `PriceStatus`에서 `STALE` 제거(`AVAILABLE`·`UNAVAILABLE` 2값으로 되돌림).
  - `PriceQueryService.getCryptoDisplayPriceQuote(Instrument)`·`getCryptoDisplayPriceQuotes(List<Instrument>)`에서 `priceStore.isStale(...)` 기반 삼항 분기를 제거하고, 연결 유지+수신 이력 있음이면 항상 `PriceStatus.AVAILABLE`을 반환하도록 고친다(연결 끊김·수신 이력 없음 분기는 그대로 유지).
  - `getOrderExecutionPrice(Instrument)`는 코드 변경 없음(이미 위 메서드에 위임) — 위 변경으로 체결 판정도 자동 반영됨을 확인.
  - `PriceQueryServiceTest`의 `PriceStatus.STALE`을 직접 단정하던 케이스 전체(단건·배치·`getOrderExecutionPrice` 계열)를 "관측 시각이 오래돼도 AVAILABLE" 케이스로 재작성. 연결 끊김·수신 이력 없음(UNAVAILABLE) 케이스는 회귀 확인만.
  - plan.md "코드 변경 설계" 참조.

- [x] **2. 하위 소비자 회귀 테스트 정리 — "-" 깜빡임 해소 확인 포함**
  - `HoldingValuationServiceTest`: 기존 STALE→UNAVAILABLE 취급 테스트를 "관측 시각이 오래돼도(과거엔 STALE) AVAILABLE로 평가금액·수익률이 채워진다" 회귀 테스트로 교체 — 이 spec 완료 조건의 핵심 확인 항목이다.
  - `SyntheticPriceServiceTest`·`PracticePriceSessionServiceTest`: STALE fallback 케이스를 제거하거나 "AVAILABLE이면 실시세 사용"으로 재작성.
  - `PracticeHoldingObservationServiceTest`: STALE을 이름에 건 테스트(`createObservationUsesLastKnownPriceWhenCryptoPriceIsStale` 등) 정리.
  - `CryptoPriceStreamServiceTest`: STALE snapshot 케이스 제거 또는 AVAILABLE로 재작성.
  - `OrderExecutionServiceTest`: STALE quote 체결 테스트를 "경과 시간 무관 AVAILABLE 체결"로 재작성.
  - `InstrumentControllerTest`: `status == "STALE"` JSON 단정 테스트 제거·교체.
  - `MarketStatusEventTest` 등 그 외 `PriceStatus.STALE` 참조 잔여분 확인·정리, 컴파일 통과 확인.
  - `CryptoCandleAndPriceIndependenceTest`는 손대지 않고 회귀만 확인.

- [ ] **3. API 계약 문서 동기화**
  - `docs/api-contracts.md`: `/price` 절, 주문 절, 코인 SSE `snapshot` 절에서 `STALE` 관련 서술 제거·갱신(plan.md "문서 동기화" 참조).
  - `docs/api-routes.md`: `/api/instruments/{instrumentId}/price` 라우트 설명에서 `STALE` 언급 제거, 근거 칸에 `036` 추가.

- [ ] **4. spec 대체 각주 + PRD §3 구현 현황 갱신**
  - `docs/specs/003-market-data/spec.md` MKT-004를 "경과 시간과 무관하게 항상 AVAILABLE"로 본문 재작성(각주가 아니라 034가 해온 대로 본문을 직접 갱신).
  - `docs/specs/032-price-quote-stale-split/spec.md`·`docs/specs/034-crypto-price-rest-backup/spec.md` 최상단에 이 spec으로 대체된 요구사항을 명시하는 한 줄 각주 추가(체크박스 원문 유지).
  - `docs/prd.md` §3 "구현 현황"의 PRICE-STALE-001~005·PRICE-REST-001~006 관련 행을 이 spec 반영해 갱신(근거: 이 spec/PR 번호, CLAUDE.md 규칙 10).
  - 이 작업 항목까지 완료된 뒤 `./gradlew build` 통과 확인.
