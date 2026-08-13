# Tasks: 코인 시세 표시와 체결 판정의 stale 기준 분리

> 구현 순서대로 나열. 각 항목이 커밋 1개 단위다. 항목 1을 먼저 끝내지 않으면 이후 항목을 진행할 수 없다.
> **제약(모든 항목 공통)**: `CryptoCandleAndPriceIndependenceTest`의 두 테스트를 절대 깨지 않는다 — 값을 바꾸는 것도 금지, 필요하면 mock stub만 보강한다(단정은 불변).

- [x] **1. `PriceStatus.STALE` 추가 + `PriceQueryService` 표시/체결 경로 분리 (단건)**
  `PriceStatus`에 `STALE` 값을 추가한다. `PriceQueryService`의 기존 private `getCryptoPriceQuote(Instrument)`를 `getCryptoExecutionPriceQuote(Instrument)`로 이름만 바꾸고(로직 무변경) `getOrderExecutionPrice`가 계속 이 메서드를 쓰도록 유지한다. 새 private `getCryptoDisplayPriceQuote(Instrument)`(plan.md 코드 참조)를 추가하고 `getPriceQuote(Instrument)`의 크립토 분기가 이 메서드를 쓰도록 바꾼다. `PriceQueryServiceTest`에 plan.md "테스트 계획" (a)~(f) 케이스(단건)를 추가하고, 기존 크립토 테스트 중 `isPriceAvailable=false`만 stub하던 것들에 `priceStore.getConnectionStatus()`를 `FeedConnectionStatus.DISCONNECTED`로 명시적으로 stub 추가(단정은 그대로 둔다). `CryptoCandleAndPriceIndependenceTest`를 실행해 회귀가 없는지 확인하고, 필요한 경우에만 stub을 보강한다(단정 불변).

- [x] **2. `PriceQueryService` 배치 표시 경로 분리**
  기존 private 배치 `getCryptoPriceQuotes(List<Instrument>)`를 `getCryptoDisplayPriceQuotes(List<Instrument>)`로 대체한다(항목 1의 단건 판정과 동일한 규칙, 연결상태는 루프 밖에서 1회 조회). `getPriceQuotes(List<Instrument>)`의 크립토 분기가 이 메서드를 쓰도록 바꾼다. `PriceQueryServiceTest`에 배치 버전 stale/disconnected/한번도못받음 케이스를 추가한다.

- [x] **3. `HoldingValuationService` 엄격 유지 방어 수정**
  `buildValuation`의 조건을 `quote.status() == PriceStatus.UNAVAILABLE`에서 `quote.status() != PriceStatus.AVAILABLE`로 바꿔 STALE도 UNAVAILABLE과 동일하게 평가불가 처리되도록 명시적으로 고정한다. `HoldingValuationServiceTest`에 STALE `PriceQuoteDto` 입력 시 `priceStatus=UNAVAILABLE`이고 평가금액·손익·수익률이 null인 케이스를 추가한다. `docs/api-contracts.md`의 `## portfolio`·`## account` 절은 이 항목으로 인해 바뀌지 않음을 확인한다(변경 없으면 그대로 둔다).

- [x] **4. 표시/엄격 소비자 회귀 테스트 고정 (코드 변경 없음)**
  `CryptoPriceStreamServiceTest`에 `priceQueryService.getPriceQuote(instrument)`가 STALE quote를 반환할 때 `buildSnapshot()`이 그대로(price·sourceTime non-null, status=STALE) 실어 나르는 케이스를 추가한다. `SyntheticPriceServiceTest`·`PracticePriceSessionServiceTest`에 STALE 입력 시 여전히 `FALLBACK_START_PRICE`를 쓰는 케이스를 각각 추가한다. 이 항목은 프로덕션 코드를 바꾸지 않고, 이미 "우연히 맞는" 동작을 테스트로 못박아 의도로 확정하는 것이 목적이다.

- [ ] **5. Controller 슬라이스 테스트 + 문서 동기화 + 최종 빌드**
  `InstrumentControllerTest`(`@WebMvcTest`)에 `priceQueryService.getPrice(id)`가 STALE `PriceQuoteDto`를 반환할 때 `GET .../price`가 200과 `$.status=="STALE"`, `$.price`·`$.sourceTime` non-null을 반환하는 케이스를 추가한다. `docs/api-contracts.md`의 `### 종목 현재가 조회`(오류 응답 칸·성공 응답 예시)와 `### 코인 SSE 스트림`(`snapshot` 절)을 STALE 반영해 갱신한다. `docs/specs/003-market-data/spec.md` MKT-004에 이 spec으로의 구현 세분화 각주를 추가한다(원문 문구는 유지). `docs/prd.md` §3 "구현 현황"에 이 기능 행을 추가한다(근거: PR 번호 — PR 생성 후 채운다). `./gradlew build`를 실행해 전체 테스트·포맷·커버리지가 통과하는 것을 확인한다.
