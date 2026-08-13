# Tasks: 튜토리얼 샌드박스 매매·완료 보상의 포트폴리오·투자일기·랭킹 제외

- [x] **포트폴리오·투자일기 조회 필터** — `HoldingRepository.findAllByAccountIdAndIsActiveTrue`에
  `AND h.instrument.isTutorialSample = false` 추가. `BuyTradeJournalRepositoryImpl`/
  `SellTradeJournalRepositoryImpl`의 `findByAccountIdWithCursor` `BooleanBuilder` 초기 조건에
  `journal.buyTrade.instrument.isTutorialSample.eq(false)`(매도는 `sellTrade...`) 추가. 테스트:
  `@DataJpaTest`로 실제+샌드박스 종목 holding·회고를 함께 시딩해 샌드박스 항목이 결과에서 빠지는지
  확인, `HoldingService.findHoldingId`/`findHoldingForOwner`(다른 메서드 사용)가 샌드박스 holding을
  여전히 찾는 회귀 테스트. (spec SANDBOX-EXCL-001·002, plan 1·2번)

- [x] **랭킹 대상자·status 판정 필터** — `TradeRepository.findDistinctAccountIdsBySideAndMarket`에
  `AND t.instrument.isTutorialSample = false` 추가. 기존 `existsByAccountIdAndSide`/
  `existsBySideAndAccountMarket`을 제거하고 `existsByAccountIdAndSideAndInstrument_IsTutorialSampleFalse`/
  `existsBySideAndAccountMarketAndInstrument_IsTutorialSampleFalse` 파생 쿼리로 교체, `TradeService.
  hasSellHistory`/`hasAnySellHistory` 내부 구현을 새 메서드 호출로 변경(시그니처는 그대로라
  `RankingService` 쪽 변경 없음). 테스트: `@DataJpaTest`로 샌드박스 종목만 매도한 계좌가 대상자
  목록·`existsBy...` 판정 모두에서 매도 이력 없는 계좌와 동일하게 취급되는지, 실제 종목 매도 이력이
  있는 계좌는 그대로 포함되는 회귀 확인. (spec SANDBOX-EXCL-003, plan 3-1번)

- [x] **매도 체결 쓰기 시점 필터 — `realized_pnl`** — `PortfolioSellService.finalizeSellRealizedPnl`과
  `OrderExecutionService.createSellOrder`(시장가 매도 인라인 계산) 각각에서, 매도 종목이
  `isTutorialSample()`이면 `account.addRealizedPnl(...)` 호출을 건너뛴다. `trade.fillRealizedPnl(...)`·
  `account.addCash(...)`·`RealizedPnlUpdatedEvent` 발행은 종목 종류와 무관하게 항상 수행한다(변경
  없음). 테스트: 단위(샌드박스/실제 종목 각 경로에서 `account.realizedPnl` 반영 여부, `trade.
  realizedPnl`은 항상 채워짐 — 기존 시장가·지정가 매도 단위 테스트에 케이스 추가). (spec
  SANDBOX-EXCL-004, plan 3-2번)

- [x] **`sandboxCashAdjustment` 엔티티·쓰기 시점 누적 — 현금·평가자산 배제 (신규, 사용자 확정)** —
  `Account`에 `sandboxCashAdjustment`(long) 필드·`addSandboxCashAdjustment(long amount)` 메서드 추가
  (생성자에 `= 0L` 초기화 포함, `cashBalance`·`realizedPnl`과 나란히 둔다). `cashBalance`를 실제로
  바꾸는 5개 지점 각각에 조건부(또는 무조건, 보상은 항상) 누적 호출 추가(plan.md 4-4번 표 그대로):
  (1) `OrderExecutionService.createBuyOrder`의 `deductCash` 직후, (2) `OrderExecutionService.
  createSellOrder`의 `addCash` 직후(3번 작업의 `isTutorialSample` 분기와 조건 재사용), (3)
  `PortfolioSellService.finalizeSellRealizedPnl`의 `addCash` 직후(마찬가지로 조건 재사용), (4)
  `LimitOrderFillService.fillBuy`의 `confirmReservedCash` 직후, (5) `PracticeHoldingReflectionService.
  payTutorialCompletionReward`의 `addCash` 직후(조건 없이 항상 `+5,000,000`). `cashBalance`·
  `reservedCash` 자체의 계산·검증 로직은 전혀 바꾸지 않는다. 테스트: 단위 5곳 각각(샌드박스/실제
  종목별 호출 여부, 보상은 항상 호출), 기존 매수·매도·지정가 체결·보상 지급 단위 테스트에 케이스
  추가. (spec SANDBOX-EXCL-006, plan 4-2·4-4번)

- [ ] **`totalValue`·`returnRate` 표시 시점 배제 (신규, 사용자 확정)** — `AccountService.
  getAccountSummary`의 `totalValue` 계산을 `cashBalance + holdingsValue - account.
  getSandboxCashAdjustment()`로 변경한다. `cashBalance`(응답 필드)·`realizedPnl`·`unrealizedPnl` 계산은
  변경 없음(각각 이미 원값 그대로 노출, 이미 SANDBOX-EXCL-004로 조정됨, 이미 실제 holding만 반영).
  `returnRate`는 조정된 `totalValue` 기준으로 기존과 동일한 공식을 그대로 쓴다(코드 변경 불필요,
  입력값만 바뀜). `PortfolioService.getPortfolioSummary`는 `AccountSummaryResponse.totalValue`를
  그대로 합산하므로 코드 변경이 필요 없음을 착수 시 재확인만 한다. 테스트: 단위(`AccountService.
  getAccountSummary`가 `sandboxCashAdjustment`가 있을 때 `totalValue`·`returnRate`를 조정하고
  `cashBalance`는 그대로인지, 조정치 0이면 기존과 완전히 동일한 결과인지 회귀), 통합(튜토리얼 완료
  보상을 받고 그 돈으로 실제 종목을 매수해 평가차익을 낸 시나리오 — plan.md 4-3의 수치 예시와 동일한
  `totalValue`가 나오는지, 이것이 이 spec 전체에서 가장 회귀에 취약한 계산이므로 반드시 통합 테스트로
  고정). (spec SANDBOX-EXCL-007, plan 4-5번)

- [ ] **`sandbox_cash_adjustment` 컬럼 + 통합 백필 마이그레이션** — 착수 시 `origin/dev` 최신 `V{N}`
  재확인 후 `V{N}__add_sandbox_cash_adjustment_and_backfill.sql`(plan.md 잠정 번호 `V34`)에서 (1)
  `accounts.sandbox_cash_adjustment BIGINT NOT NULL DEFAULT 0` 컬럼 추가, (2) 샌드박스 종목 매매의
  현금 순변동 합계 + `practice_completions` 행 수 × 5,000,000(tutorial_key→market 매핑 포함)으로
  `sandbox_cash_adjustment`를 재계산(전체 덮어쓰기, `+=` 아님), (3) 실제 종목 매도 합계로
  `realized_pnl`을 재계산(전체 덮어쓰기)한다(plan.md 5번 SQL 그대로). 테스트: Testcontainers로
  마이그레이션 적용 전 상태를 흉내낸 데이터(샌드박스 매매 이력·보상 지급 이력이 있는 계좌, 오염되지
  않은 계좌 포함)를 준비한 별도 마이그레이션 테스트에서, 이 마이그레이션 적용 후
  `sandbox_cash_adjustment`·`realized_pnl` 둘 다 정확히 재계산되고 오염되지 않은 계좌는 값이 변하지
  않는지 확인. 두 번 적용해도(재실행 시뮬레이션) 같은 결과인지(멱등성) 확인. (spec SANDBOX-EXCL-005·
  008, plan 5번)

- [ ] **통합 테스트 + 문서 동기화 + `./gradlew build`** — Testcontainers 통합 테스트로 실제 종목 +
  샌드박스 종목을 함께 보유·매도한 계좌 하나가 `GET /api/holdings`·`GET /api/journal`·
  `GET /api/rankings`·`GET /api/rankings/me` 네 응답 모두에서 샌드박스 항목·대상자 판정이 빠지고
  실제 항목만 남는지 확인. 튜토리얼 완료 보상 + 실거래 평가차익 혼합 시나리오(위 작업에서 이미
  작성했다면 여기서는 전체 빌드 컨텍스트에서 통과만 재확인). `026`/`031`의 기존 튜토리얼 통합
  테스트(이슈 #313·#339)가 회귀 없이 통과하는지 확인. 컨트롤러 응답 스키마는 바뀌지 않으므로
  `docs/api-routes.md`·`docs/api-contracts.md` 갱신은 대상 아님(엔드포인트·필드 변경 없음, 내부 필터·
  계산 방식만 변경) — 착수 시 실제로 필드 변경이 없었는지 재확인만 하고 필요 시에만 갱신한다.
  `docs/prd.md` §3 구현 현황은 PORT-001/JOUR-006/RANK-001·002가 이미 "완료"로 기재돼 있고 이 spec은
  그 기능 제공 범위를 넓히는 것이 아니라 정확도를 고치는 버그 수정이므로 갱신 대상이 아니다
  (CLAUDE.md 규칙 10 "갱신 비대상" 참고) — 착수 시 이 판단이 여전히 맞는지만 재확인한다. 전체 재실행
  `./gradlew build` `BUILD SUCCESSFUL` 확인, 실패 시 수정 후 재실행. (spec SANDBOX-EXCL-009, 완료
  조건)
