# Tasks: 튜토리얼 전용 샘플 종목·항시 시세·매도 단계·5분 제한 (Sandbox 실습 확장)

- [x] **migration + 샘플 종목 데이터 모델 + 커뮤니티 태그 제외** — 착수 시 `origin/dev` 최신 `V{N}` 재확인 후
  `V{N}__add_tutorial_sample_instruments.sql`(plan.md 잠정 번호 `V32`)에서 `instruments.is_tutorial_sample
  BOOLEAN NOT NULL DEFAULT FALSE` 컬럼 추가 + `SANDBOX_STK_1~3`/`SANDBOX_COIN_1~3` 6행 시드(1번째만
  `tradable=TRUE`). `Instrument` 엔티티에 `isTutorialSample` 필드·getter 추가. `InstrumentResponse`에
  `isTutorialSample` 필드 추가(`InstrumentController`/`InstrumentService` 응답 매핑 갱신). 커뮤니티 태그
  제외: `InstrumentService.getTradableInstrumentEntity`(또는 실제 호출부, 착수 시 코드 재확인)에
  `!instrument.isTutorialSample()` 조건을 추가해 `tradable=true`인 샘플 종목도 커뮤니티 게시물 종목 태그
  대상에서 제외한다. 테스트: `@DataJpaTest`(신규 컬럼·시드 6행 조회, 기존 16종 주식·12종 코인이 전부
  `is_tutorial_sample=false`로 남는지), `@WebMvcTest`(`GET /api/instruments`가 `isTutorialSample` 필드를
  포함해 6행을 반환), `InstrumentService`/`CommunityPostService` 단위 테스트(샘플 종목 태그 시도 시 거부,
  `tradable=false` 실제 종목 거부는 회귀 확인). (spec SANDBOX-001, plan 1번·"리뷰 권장사항 반영")

- [x] **`TutorialSampleInstrumentPriceService` + `PriceQueryService` 4개 메서드 분기** — 신규 클래스
  `com.finplay.api.market.service.TutorialSampleInstrumentPriceService`에 결정적 가격 알고리즘(basePrice
  50,000/10,000, amplitude 0.03, periodSeconds 180, phase = `(instrument.getId() % 7) * (π/7)`, 기존 `Clock`
  빈 주입, scale 8 HALF_UP)을 구현한다. `PriceQueryService.getPriceQuote(Instrument)`,
  `getOrderExecutionPrice(Instrument)`, `getPriceQuotes(List<Instrument>)` 3개 메서드 각각에
  `instrument.isTutorialSample()` 분기를 추가해 `stockPriceProvider`/`priceStore`를 호출하지 않고 이
  서비스로 위임한다. `getPriceQuotes`는 샘플/실제 종목을 분할 처리 후 **원래 `instruments` 순서로 병합**
  반환하고, market 혼재 방어 검사는 실제 종목 부분집합에만 적용한다. `getOrderExecutionPrice`는 STOCK도
  `replaySession=null`을 반환하도록 한다(`getPrice`는 `getPriceQuote(Instrument)`를 거치므로 별도 분기
  불필요, 체인만 재확인). 테스트: 단위(가격 알고리즘 결정성·±3% 범위·scale 8 반올림, 같은 `(instrument,
  now)` 재현), Mockito `verifyNoInteractions(stockPriceProvider, priceStore)`, `getOrderExecutionPrice`가
  `MARKET_CLOSED`를 던지지 않고 `replaySession=null`을 반환, `getPriceQuote`가 항상 `PriceStatus.AVAILABLE`.
  슬라이스: `getPriceQuotes` 배치 조회 순서 보존(샘플·실제 혼재 리스트). (spec SANDBOX-002·003·004, plan 2번)

- [x] **매도 chain 해석 확장** — `TradeService`에 `findEarliestFilledSellTradeAfter(userId, instrumentId,
  after)`(`findEarliestFilledBuyTradeMatching`과 대칭, `executedAt ASC, tradeId ASC`, 수량 일치 불요) 추가.
  `MarketPracticeChainResolutionService`에 매도 조회를 연결해 `buyTrade.executedAt` 이후 첫 FILLED SELL
  trade를 찾는다. `ResolvedPracticeChainDto`에 `sellTradeId`, `sellTradeExecutedAt` 2필드 추가(기존
  10필드 유지, `empty()`류 정적 팩토리는 `null`). 테스트: `TradeService` 단위(수량 무관 매도 선택,
  `executedAt` 동순위 시 `tradeId` 순), `MarketPracticeChainResolutionService` 단위(매도 없음/있음/여러
  건 중 최이른 선택), `026`의 기존 2단계 chain 해석 회귀 테스트 통과 확인. (plan 3번 "매도 chain 조회")

- [ ] **`GET /api/education/practice` 4단계 응답(샘플 종목 chain 한정)** — `InvestmentPracticeQueryService`가
  chain 해석 후 `resolvedInstrument.isTutorialSample()`로 분기해 `PracticeStepResponse` 배열을 3개(실제
  종목, `026`과 동일) 또는 4개(샘플 종목, 신규 4번째 "매도·복기")로 구성한다. step 상태 상수에 `EXPIRED`
  추가. `PracticeEvidenceResponse`에 `sellTradeId`, `sellTradeExecutedAt`, `saleDeadlineAt`(=
  `buyTradeExecutedAt + 5분`) 3필드 추가, 실제 종목 chain에서는 항상 `null`. `empty()`·`favoriteOnly()`
  정적 팩토리에도 신규 필드를 `null`로 반영. 테스트: 단위(3단계 vs 4단계 분기, `EXPIRED` 판정 시점 —
  매도 없이 5분 초과), `@WebMvcTest`(샘플 종목 chain 응답 `steps.length==4`, 실제 종목 chain
  `steps.length==3`, `saleDeadlineAt` 직렬화). 회귀: `026`의 기존 3단계 응답 테스트가 실제 종목 chain에서
  그대로 통과. (spec SANDBOX-005, plan 3번 "GET 4단계 응답")

- [ ] **5분 만료 판정 + `holding-reflections` 전제조건 확장 + `PRACTICE_SANDBOX_TIME_EXPIRED`** — 신규
  `ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED(HttpStatus.CONFLICT, ...)` 추가. `PracticeHoldingReflectionService`
  전제조건 검사를 샘플 종목 chain에 한해 확장: evidence A/B 없음 → 기존 409
  `PRACTICE_EVIDENCE_MISSING`(변경 없음), A/B는 있으나 매도 체결 없고 5분 이내 → 409
  `PRACTICE_EVIDENCE_MISSING`(신규 원인, 같은 코드), A/B는 있으나 매도 체결 없고 5분 초과 또는 매도
  `executedAt`이 `buyTrade.executedAt + 5분` 초과 → 409 `PRACTICE_SANDBOX_TIME_EXPIRED`. 실제 종목 chain은
  `026`의 기존 전제조건(evidence A/B만)을 그대로 유지(분기 필수). 5분 경계값은 매 요청 시점 재계산(snapshot
  없음), `!isAfter` 등으로 "정확히 5분" 포함 여부를 명시적으로 코드에 남긴다. 테스트: 단위(경계값 —
  4분59초/5분정확/5분1초, `!isAfter` 기준 포함/제외 확정), `@WebMvcTest`(신규 409 매핑 2종), 통합 없이 이번
  항목은 단위·슬라이스로 충분(전체 흐름 통합은 다음 항목). (spec SANDBOX-006·007·008, plan 3번·4번)

- [ ] **통합 테스트 — 샘플 종목 전체 흐름 + 만료·재도전 + 실제 종목 회귀** — Testcontainers 통합 테스트로
  (1) 빗썸 poller 미기동·주식 재생세션 미시딩 상태에서 샘플 종목 즐겨찾기→의도→매수→관찰(A 또는 B)→매도
  (`POST /api/orders` SELL MARKET)→복기 전체 흐름이 성공(이슈 #339 직접 회귀 테스트), (2) 5분 초과 후
  매도 시 `holding-reflections`가 409 `PRACTICE_SANDBOX_TIME_EXPIRED`를 반환하고 같은 종목 재매수로 만든
  새 chain이 4단계를 다시 시도할 수 있는지, (3) 실제 종목(`is_tutorial_sample=false`)의
  `PriceQueryService` 동작(`PRICE_UNAVAILABLE`/`MARKET_CLOSED` 포함)과 `026`의 기존 3단계 완료 흐름
  (이슈 #313 통합 테스트)이 이 변경 이후에도 완전히 동일한지(응답 `steps` 길이 3 유지). `026/spec.md`
  상단에 "이 spec의 4단계 확장은 `031`이 정본" 1줄 상태 참조를 추가한다(내용 수정 아님). (spec
  SANDBOX-009, plan 5번, 완료 조건)

- [ ] **문서 동기화 + `./gradlew build`** — `docs/api-routes.md`(`GET /api/instruments` 응답 필드 변경,
  `GET /api/education/practice` 4단계 응답 변경 각주), `docs/api-contracts.md`(`isTutorialSample` 필드,
  4단계 `steps` 배열, `PracticeEvidenceResponse` 3신규 필드, `holding-reflections`의 신규 409
  `PRACTICE_SANDBOX_TIME_EXPIRED` 계약) 갱신. `docs/prd.md` §3 구현 현황에 해당 요구사항 ID 행 갱신(근거:
  이 PR). 전체 재실행 `./gradlew build` `BUILD SUCCESSFUL` 확인, 실패 시 수정 후 재실행.
