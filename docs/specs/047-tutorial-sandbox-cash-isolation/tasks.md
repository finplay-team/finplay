# Tasks: 튜토리얼 전용 계좌 신설을 통한 샌드박스 매매·완료 보상 현금 격리

- [x] `TutorialAccount` 엔티티(`cashBalance`·`reservedCash`·`realizedPnl`) + `TutorialAccountRepository`
  (`findByUserIdAndMarketForUpdate` 포함) + `V46__create_tutorial_accounts_and_backfill_cash.sql`(테이블
  신설 + `sandbox_cash_adjustment` 백필, 버전 번호는 착수 시점 `origin/dev` 재확인 후 확정) +
  `common.ErrorCode`에 `TUTORIAL_INSUFFICIENT_CASH` 추가 + `@DataJpaTest`.
- [x] `TutorialAccountService`(get-or-create, reset — 현금·예약 현금·`realizedPnl` 동시 초기화) + 단위
  테스트. `PracticeAttemptService.ensureAttempt`에 get-or-create 연결.
- [ ] 매수 경로 전환 — `OrderExecutionService.createBuyOrder`, `PracticeLimitOrderCreationService.createSessionBuyOrder`,
  `LimitOrderFillService.fillBuy`가 샌드박스 종목일 때 튜토리얼 계좌를 대상으로 하도록 수정(현금 부족 검증을
  `TUTORIAL_INSUFFICIENT_CASH`로 전환, TUTORIAL-CASH-ISOL-002·005) + 단위/슬라이스 테스트.
- [ ] 매도 경로 전환 — `PortfolioSellService.finalizeSellRealizedPnl` 분기 추가(실제 계좌 vs 튜토리얼 계좌,
  튜토리얼 계좌는 현금과 `realizedPnl`을 함께 갱신), `OrderExecutionService.createSellOrder` 인라인 분기
  (TUTORIAL-CASH-ISOL-003) + 단위 테스트. 이 변경만으로 지정가 매도 체결·재시작 보상매도·OCO 체결이 함께
  반영됨을 통합 테스트로 확인.
- [ ] 재시작 리셋 — `PracticeRunRestartOrderService.cleanupCurrentRun`(또는 `PracticeAttemptRestartService.restart`)에
  `TutorialAccountService.resetForUpdate` 호출 추가(TUTORIAL-CASH-ISOL-006, 현금·예약 현금·`realizedPnl`
  동시 리셋) + 통합 테스트.
- [ ] `sandboxCashAdjustment` 폐지 — 5개 호출부의 `addSandboxCashAdjustment(...)` 제거, `AccountService`의
  `totalValue` 공식 원복(TUTORIAL-CASH-ISOL-007) + 계좌/포트폴리오 요약 테스트 갱신.
- [ ] 튜토리얼 잔고 API 노출 — `PracticeAttemptResponse`(또는 확정된 동등 응답)에 `tutorialCashBalance`·
  `tutorialAvailableCash`·`tutorialRealizedPnl` 필드 추가(TUTORIAL-CASH-ISOL-011), 진입·재시작 양쪽 응답에서
  값이 정확히 채워짐을 슬라이스/통합 테스트로 확인 + `docs/api-routes.md`·`docs/api-contracts.md` 갱신
  (CLAUDE.md 규칙 7 — 이 커밋이 실제 컨트롤러·DTO 변경을 담는 커밋이므로 두 문서를 함께 갱신).
- [ ] 통합 테스트(Testcontainers) — 이슈 #450 재현 시나리오 역-검증(매수→관찰→매도→재시작 반복 후 실제
  계좌 잔고 불변), 배포 백필 마이그레이션 멱등성 검증.
- [ ] `docs/prd.md` §3 구현 현황 갱신 — 이 spec 관련 요구사항 ID(TUTORIAL-CASH-ISOL-001~011)의 구현 상태를
  근거 PR 번호와 함께 반영(CLAUDE.md 규칙 10, 구현 PR 몫).
