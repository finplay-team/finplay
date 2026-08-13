# Run Log: 033-exclude-tutorial-sandbox-data

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `HoldingRepository`/`BuyTradeJournalRepositoryImpl`/`SellTradeJournalRepositoryImpl` 조건절 수정 후 `.\gradlew.bat compileJava compileTestJava` | plan.md 1·2번, spec SANDBOX-EXCL-001·002 |
| - | implementer | `TradeRepository.findDistinctAccountIdsBySideAndMarket`에 `t.instrument.tutorialSample = false` 추가(Instrument 실제 필드명 확인, getter `isTutorialSample`과 다름), `existsByAccountIdAndSide`/`existsBySideAndAccountMarket`을 `...Instrument_TutorialSampleFalse` 파생 쿼리로 교체, `TradeService.hasSellHistory`/`hasAnySellHistory` 내부 구현 변경 후 `.\gradlew.bat compileJava compileTestJava` | plan.md 3-1번, spec SANDBOX-EXCL-003 |
| - | implementer | `PortfolioSellService.finalizeSellRealizedPnl`·`OrderExecutionService.createSellOrder`에 `instrument.isTutorialSample()` 분기로 `account.addRealizedPnl` skip 추가, 기존 단위 테스트 클래스에 케이스 추가 후 `.\gradlew.bat compileJava compileTestJava` | plan.md 3-2번, spec SANDBOX-EXCL-004 |

## 모니터링 (사람용 요약)
- 포트폴리오·투자일기 조회 필터(tasks.md 항목 1) 구현, `@DataJpaTest`에 샌드박스 제외·`findHoldingId`/`findHoldingForOwner` 회귀 테스트 추가, 컴파일 통과.
- 랭킹 대상자·status 판정 필터(tasks.md 항목 2) 구현, 파생 쿼리명은 `Instrument_IsTutorialSampleFalse`가 아니라 `Instrument_TutorialSampleFalse`(엔티티 필드명이 `tutorialSample`)로 확정, 기존·신규 `@DataJpaTest` 모두 컴파일 통과.
- 매도 체결 쓰기 시점 필터(tasks.md 항목 3) 구현, 시장가·지정가 매도 두 경로 모두 샌드박스 종목이면 `account.addRealizedPnl` skip, `trade.realizedPnl`·`account.cashBalance`·이벤트 발행은 항상 수행. 기존 단위 테스트 클래스에 케이스 추가, 컴파일 통과.
