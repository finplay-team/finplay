# Run Log: 033-exclude-tutorial-sandbox-data

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `HoldingRepository`/`BuyTradeJournalRepositoryImpl`/`SellTradeJournalRepositoryImpl` 조건절 수정 후 `.\gradlew.bat compileJava compileTestJava` | plan.md 1·2번, spec SANDBOX-EXCL-001·002 |
| - | implementer | `TradeRepository.findDistinctAccountIdsBySideAndMarket`에 `t.instrument.tutorialSample = false` 추가(Instrument 실제 필드명 확인, getter `isTutorialSample`과 다름), `existsByAccountIdAndSide`/`existsBySideAndAccountMarket`을 `...Instrument_TutorialSampleFalse` 파생 쿼리로 교체, `TradeService.hasSellHistory`/`hasAnySellHistory` 내부 구현 변경 후 `.\gradlew.bat compileJava compileTestJava` | plan.md 3-1번, spec SANDBOX-EXCL-003 |
| - | implementer | `PortfolioSellService.finalizeSellRealizedPnl`·`OrderExecutionService.createSellOrder`에 `instrument.isTutorialSample()` 분기로 `account.addRealizedPnl` skip 추가, 기존 단위 테스트 클래스에 케이스 추가 후 `.\gradlew.bat compileJava compileTestJava` | plan.md 3-2번, spec SANDBOX-EXCL-004 |
| - | implementer | `Account`에 `sandboxCashAdjustment`(long)·`addSandboxCashAdjustment(long)` 추가, plan.md 4-4번 표의 5개 지점(`OrderExecutionService.createBuyOrder`/`createSellOrder`, `PortfolioSellService.finalizeSellRealizedPnl`, `LimitOrderFillService.fillBuy`, `PracticeHoldingReflectionService.payTutorialCompletionReward`)에 누적 호출 추가, 각 클래스 기존 단위 테스트에 케이스 추가 후 `.\gradlew.bat compileJava compileTestJava` | plan.md 4-2·4-4번, spec SANDBOX-EXCL-006 |

## 모니터링 (사람용 요약)
- 포트폴리오·투자일기 조회 필터(tasks.md 항목 1) 구현, `@DataJpaTest`에 샌드박스 제외·`findHoldingId`/`findHoldingForOwner` 회귀 테스트 추가, 컴파일 통과.
- 랭킹 대상자·status 판정 필터(tasks.md 항목 2) 구현, 파생 쿼리명은 `Instrument_IsTutorialSampleFalse`가 아니라 `Instrument_TutorialSampleFalse`(엔티티 필드명이 `tutorialSample`)로 확정, 기존·신규 `@DataJpaTest` 모두 컴파일 통과.
- 매도 체결 쓰기 시점 필터(tasks.md 항목 3) 구현, 시장가·지정가 매도 두 경로 모두 샌드박스 종목이면 `account.addRealizedPnl` skip, `trade.realizedPnl`·`account.cashBalance`·이벤트 발행은 항상 수행. 기존 단위 테스트 클래스에 케이스 추가, 컴파일 통과.
- `sandboxCashAdjustment` 엔티티·5개 지점 누적(tasks.md 항목 4) 구현, `cashBalance`/`reservedCash` 계산·검증 로직은 손대지 않았고 병렬 누적 컬럼만 추가. 5곳 각각 샌드박스/실제 종목 케이스 단위 테스트 추가(보상 지급은 무조건 호출 검증), 컴파일 통과.
