# Tasks: 주식 시세·차트 마지막 재생 상태 유지

순서대로 진행한다. 1번이 나머지 전부의 입력이고, 2·3번은 서로 독립이지만 같은 클래스를 건드리므로 순차로 둔다.

- [x] **1. 폴백 세션 조회 메서드 + @DataJpaTest**
  `StockReplaySessionRepository`에 `findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc`를 추가한다.
  슬라이스 테스트로 오늘 세션 제외·`READY`만 선택·서비스 날짜 최댓값 1건·후보 없음(`Optional.empty()`)을 확인한다.
  스키마 변경 없음(Flyway 마이그레이션을 만들지 않는다).

- [x] **2. 가격 표시 경로 폴백 (`StockReplayService.getCurrentPrices`) + 단위 테스트**
  `marketStatus == CLOSED`이고 오늘 세션 기준 공개 분봉이 없을 때만 폴백 세션의 마지막 분봉 종가로 응답을 채운다.
  폴백 시세는 `sessionReady=false`·`replaySession=null`·`marketStatus=CLOSED`를 유지한다(QUOTE-HOLD-005).
  폴백 세션 조회는 요청당 1회. 단위 테스트는 plan.md "테스트 계획"의 ①②⑤⑥⑦⑧을 덮는다.

- [x] **3. 캔들 표시 경로 폴백 (`getRevealedCandles`·`getRevealedAggregatedCandles`) + 단위 테스트**
  같은 조건에서 폴백 세션의 원본 거래일을 **하루치 전부 공개**로 조회한다(집계 경로는 그 거래일의 컷오프를
  `LocalTime.MAX`로 둔다). 200개 캡·선두 partial 버킷 필터·`narrowRangeStart`는 그대로 재사용한다.
  단위 테스트는 ①②③④⑦을 덮는다 — 특히 **09:00~09:01 빈 배열 회귀**와 **08:50에 오늘 세션의 거래일이 새지
  않는 것**을 반드시 포함한다.

- [x] **4. 회귀 정리 + 강제 OPEN 데코레이터 확인**
  "세션 없으면 `UNAVAILABLE`·빈 배열"을 단정하던 기존 테스트를 삭제하지 말고 원래 검증 의도가 살아 있는 형태로
  옮긴다(`StockReplayServiceTest`·`CandleQueryServiceTest`·`PriceQueryServiceTest`·`MarketDataPipelineIntegrationTest`
  주변). `LocalForcedOpenStockPriceProviderTest`에 폴백 시세를 강제 OPEN이 덮어쓰지 않는 케이스를 추가한다.

- [ ] **5. 통합 테스트 (핵심 시나리오)**
  Testcontainers로 금요일 장 마감 → 토요일 조회(가격·캔들 유지, 값이 금요일과 동일) → 월요일 08:50(여전히 금요일
  재생일) → 월요일 09:01(오늘 재생분으로 전환)을 한 시나리오로 확인하고, 같은 테스트에서 토요일 주문이 409
  `MARKET_CLOSED`임을 확인한다. SSE 쪽은 값이 멈춘 동안 `price` 이벤트가 없고 `snapshot`에 멈춘 값이 실리는 것을
  확인한다.

- [ ] **6. 문서 동기화**
  `docs/api-contracts.md`(가격·캔들 장외 동작, SSE "마지막 값 유지" 문단, `costBasis` 대체 문단)와
  `docs/prd.md` §3 "구현 현황"의 `QUOTE-HOLD-001~007` 행을 갱신한다(CLAUDE.md 규칙 10).
  `docs/api-routes.md`는 라우트 변경이 없어 대상이 아니다.

- [ ] **7. `./gradlew build` 통과 확인**
