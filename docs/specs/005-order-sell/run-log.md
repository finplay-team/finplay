# Run Log: 005-order-sell

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 16:47 | implementer | `./gradlew test --tests AccountTest,TradeTest,HoldingTest,HoldingLotTest,HoldingLotRepositoryTest,TradeAllocationRepositoryTest` | plan.md 설계 노트 5·6, docs/conventions.md Entity 규칙 |
| 17:20 | implementer | `./gradlew test --tests com.finplay.api.portfolio.service.PortfolioSellServiceTest` | plan.md 설계 노트 3·7 (FIFO 배분·원 단위 잔여 처리 의사코드 그대로 구현) |

## 모니터링 (사람용 요약)
- 16:47 — 항목 1(엔티티·Repository 확장 지점) 구현·단위 테스트 18건 통과, compileJava 통과.
- 17:20 — 항목 2(`PortfolioSellService` FIFO 배분·실현손익 원가 계산) 구현, `PortfolioSellServiceTest` 7건 통과, compileJava 통과.
