# Run Log: 036-remove-crypto-stale-status

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `./gradlew compileJava` | plan.md "코드 변경 설계", spec.md PRICE-NOSTALE-001·002 |
| - | implementer | `./gradlew compileTestJava` | spec.md PRICE-NOSTALE-002·003, plan.md "테스트 계획" |

## 모니터링 (사람용 요약)
- 항목 1 완료 — `PriceStatus` 2값 원복, `PriceQueryService` stale 분기 제거, `PriceQueryServiceTest` STALE 단정 케이스 5건 AVAILABLE로 재작성. compileJava 통과, compileTestJava는 항목 2 대상 7개 파일에서 예상대로 실패.
- 항목 2 완료 — 하위 소비자 7개 테스트의 `PriceStatus.STALE` 참조를 전부 "관측 시각이 오래돼도 AVAILABLE" 회귀로 재작성(HoldingValuationServiceTest 포함, "-" 깜빡임 해소 확인). `MarketStatusEventTest`는 STALE을 참조하지 않았으나 오해 소지 있는 예시값·주석만 정리. `CryptoCandleAndPriceIndependenceTest`는 무변경. compileTestJava 통과.
