# Run Log: 007-journal

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 항목1 | implementer | `./gradlew compileJava` | plan.md §데이터 모델(V14 DDL), V10 `holding_lots.buy_trade_id` 선례, ADR-0004 |
| 항목2 | implementer | `./gradlew compileJava` | plan.md §`TradeService`에 추가할 조회, ADR-0002(도메인 간 참조는 service 경유), `CommunityPostRepository` `@EntityGraph` 선례 |

## 모니터링 (사람용 요약)
- 항목1 — `V14__create_buy_trade_journals.sql` + `BuyTradeJournal` 엔티티 + `BuyTradeJournalRepository` 추가, compileJava 통과.
- 항목2 — `TradeService.getOwnedTrade` 추가(404/403), `TradeRepository.findById`에 `@EntityGraph(account, account.user)` 오버라이드로 지연 로딩 우회, compileJava 통과.
