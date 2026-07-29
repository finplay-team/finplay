# Run Log: 005-order-sell

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 16:47 | implementer | `./gradlew test --tests AccountTest,TradeTest,HoldingTest,HoldingLotTest,HoldingLotRepositoryTest,TradeAllocationRepositoryTest` | plan.md 설계 노트 5·6, docs/conventions.md Entity 규칙 |
| 17:20 | implementer | `./gradlew test --tests com.finplay.api.portfolio.service.PortfolioSellServiceTest` | plan.md 설계 노트 3·7 (FIFO 배분·원 단위 잔여 처리 의사코드 그대로 구현) |
| 17:55 | implementer | `./gradlew compileJava compileTestJava test spotlessApply` | plan.md 설계 노트 1·2 (createBuyOrder→createOrder side 분기, SELL 16단계 순서 그대로) |

## 모니터링 (사람용 요약)
- 16:47 — 항목 1(엔티티·Repository 확장 지점) 구현·단위 테스트 18건 통과, compileJava 통과.
- 17:20 — 항목 2(`PortfolioSellService` FIFO 배분·실현손익 원가 계산) 구현, `PortfolioSellServiceTest` 7건 통과, compileJava 통과.
- 17:55 — 항목 3(`OrderService` SELL 분기 통합) 구현, `createBuyOrder`→`createOrder` 이름 변경 + 공유 검증 추출 + SELL 분기(설계 노트 2 순서) 추가. 컨트롤러·기존 통합테스트 호출부는 컴파일 유지 목적의 최소 이름 변경만 반영. `OrderServiceTest`에 SELL 케이스(실현손익 계산·다중 lot 합산·원단위 경계값·409 무흔적·전량매도 비활성화) 추가, 전체 테스트·spotlessCheck 통과.
