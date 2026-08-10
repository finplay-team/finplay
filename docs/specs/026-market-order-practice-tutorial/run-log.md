# Run Log: 026-market-order-practice-tutorial

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `ls src/main/resources/db/migration \| sort -V` (V27 확정) | plan.md "신규 migration 번호는 착수 시점에 재확인", ADR-0004 |
| - | implementer | `.\gradlew.bat compileJava` | plan.md "2단계 chain 해석", ADR-0002(도메인 서비스 경유) |

## 모니터링 (사람용 요약)
- V27 migration + practice_market_observations/reflections 엔티티·repository + MarketPracticeChainResolutionService(2단계 chain 해석) 추가, compileJava 통과.
