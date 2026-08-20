# Run Log: 005-order-sell

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 16:47 | implementer | `./gradlew test --tests AccountTest,TradeTest,HoldingTest,HoldingLotTest,HoldingLotRepositoryTest,TradeAllocationRepositoryTest` | plan.md 설계 노트 5·6, docs/conventions.md Entity 규칙 |
| 17:20 | implementer | `./gradlew test --tests com.finplay.api.portfolio.service.PortfolioSellServiceTest` | plan.md 설계 노트 3·7 (FIFO 배분·원 단위 잔여 처리 의사코드 그대로 구현) |
| 17:55 | implementer | `./gradlew compileJava compileTestJava test spotlessApply` | plan.md 설계 노트 1·2 (createBuyOrder→createOrder side 분기, SELL 16단계 순서 그대로) |
| 18:10 | implementer | `./gradlew compileJava` | plan.md 설계 노트 8 (`OrderResponse.realizedPnl` 추가, `trade.getRealizedPnl()` 그대로 전달) |
| 18:40 | implementer | `./gradlew build --no-daemon --max-workers=1` | plan.md 테스트 계획 통합 테스트 시나리오 5종, `OrderBuyIntegrationTest`와 동일한 Testcontainers·MutableClock 패턴 재사용 |

## 모니터링 (사람용 요약)
- 16:47 — 항목 1(엔티티·Repository 확장 지점) 구현·단위 테스트 18건 통과, compileJava 통과.
- 17:20 — 항목 2(`PortfolioSellService` FIFO 배분·실현손익 원가 계산) 구현, `PortfolioSellServiceTest` 7건 통과, compileJava 통과.
- 17:55 — 항목 3(`OrderService` SELL 분기 통합) 구현, `createBuyOrder`→`createOrder` 이름 변경 + 공유 검증 추출 + SELL 분기(설계 노트 2 순서) 추가. 컨트롤러·기존 통합테스트 호출부는 컴파일 유지 목적의 최소 이름 변경만 반영. `OrderServiceTest`에 SELL 케이스(실현손익 계산·다중 lot 합산·원단위 경계값·409 무흔적·전량매도 비활성화) 추가, 전체 테스트·spotlessCheck 통과.
- 18:10 — 항목 4(`OrderController`+`OrderResponse` realizedPnl 노출) 구현. `OrderController`는 이미 `orderService.createOrder(...)` 호출 중(항목 3에서 완료)이고 side=SELL을 거부하는 controller/DTO 레벨 검증 없음(확인 완료). `OrderResponse`에 `Long realizedPnl` 필드 추가, `of()`가 `trade.getRealizedPnl()` 그대로 전달. `OrderControllerTest`에 SELL 201 + `realizedPnl` jsonPath 검증 테스트 추가, 기존 BUY 테스트는 `realizedPnl` null 검증 추가. compileJava 통과. `ai/api-routes.md`·`docs/api-contracts.md` 갱신은 tasks.md 항목 6(문서 갱신, planner 담당) 범위로 남겨둠.
- 18:40 — 항목 5(`OrderSellIntegrationTest` 통합 테스트) 구현. spec.md 완료조건 5종(FIFO 부분소진·단일lot only, 다중lot 소비+실현손익 공식, 보유수량 초과 409+6개 테이블 무흔적, 전량매도 holding 비활성화) 각각 1개 테스트로 매핑, 4건 전부 통과. `OrderBuyIntegrationTest`와 동일한 Testcontainers+MutableClock+분봉 픽스처 패턴 재사용(신규 픽스처 코드 없음). `./gradlew build --no-daemon --max-workers=1`(JAVA_HOME=corretto-17) 전체 통과 — SpotBugs·Spotless·JaCoCo 커버리지 게이트 포함, 기존 테스트 깨짐 없음.

## 리뷰 로그
| 시각 | 에이전트 | 점검 범위 | 점검한 문서 |
|---|---|---|---|
| 리뷰 | reviewer(리뷰 모드) | `git diff origin/dev...HEAD`(HEAD 5d478d9) 전체, FIFO 배분·원단위잔여·실현손익 공식·거래경계·ADR-0002 중점 확인 | docs/conventions.md, adr/0002·0003·0004, ai/specs/005-order-sell/spec.md·plan.md |

- 판정: 차단 0건 / 권장 0건 / 참고 1건 — plan.md 의사코드·실현손익 공식·트랜잭션 경계·ADR-0002 모두 구현과 일치, 머지 가능.
