# Run Log: 007-journal

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 항목1 | implementer | `./gradlew compileJava` | plan.md §데이터 모델(V14 DDL), V10 `holding_lots.buy_trade_id` 선례, ADR-0004 |
| 항목2 | implementer | `./gradlew compileJava` | plan.md §`TradeService`에 추가할 조회, ADR-0002(도메인 간 참조는 service 경유), `CommunityPostRepository` `@EntityGraph` 선례 |
| 항목3 | implementer | `./gradlew compileJava` | plan.md §`JournalService`(1~5단계), `CommunityPostService`/`OrderService`의 `Clock`·`DataIntegrityViolationException` 변환 선례 |
| 항목4 | implementer | `./gradlew compileJava` | plan.md §구성요소 설계·§입력 명세·§오류 매핑, `TradeController`의 `AuthenticatedUser` 선례, `GlobalExceptionHandler`(MethodArgumentTypeMismatchException 기존 처리 확인) |
| 리뷰 | reviewer(리뷰) | `git diff dev...HEAD` | conventions.md, ADR-0002, ADR-0003, ADR-0004, spec.md, plan.md |
| S1 | implementer | `./gradlew compileJava` | plan.md §JOUR-003 §데이터 모델(V17 DDL), `buy_trade_journals`(V15)·`BuyTradeJournalRepository` 선례, ADR-0004 |
| S2 | implementer | `./gradlew compileJava` | plan.md §JOUR-003 §구성요소 설계(`createSellJournal` 1~5단계), `createBuyJournal` 선례(존재→소유→매도여부→중복 순, saveAndFlush 후 DataIntegrityViolationException→409 변환), ADR-0002 |
| S3 | implementer | `./gradlew compileJava` | plan.md §JOUR-003 §구성요소 설계·§API 설계·§오류 매핑, `JournalController.createBuyJournal` 선례, CLAUDE.md 규칙 7 |

## 모니터링 (사람용 요약)
- 항목1 — `V14__create_buy_trade_journals.sql` + `BuyTradeJournal` 엔티티 + `BuyTradeJournalRepository` 추가, compileJava 통과. (이후 `dev`에 먼저 병합된 `V14__create_favorites.sql`과 번호가 겹쳐 `V15__create_buy_trade_journals.sql`로 재번호화됨 — plan.md §데이터 모델 참고)
- 항목2 — `TradeService.getOwnedTrade` 추가(404/403), `TradeRepository.findById`에 `@EntityGraph(account, account.user)` 오버라이드로 지연 로딩 우회, compileJava 통과.
- 항목3 — `JournalService.createBuyJournal`(존재→소유→매수여부→중복 순 검증, saveAndFlush 후 DataIntegrityViolationException→409 변환) + `BuyJournalResponse` 추가, compileJava 통과.
- 항목4 — `JournalController`(POST /api/trades/{buyTradeId}/journal) + `BuyJournalCreateRequest` 추가, 기존 `MethodArgumentTypeMismatchException` 핸들러 재사용 확인, 문서 갱신은 항목6에서 처리 예정, compileJava 통과.
- 리뷰 — 차단 1건(docs/api-routes.md·api-contracts.md 미반영, tasks.md 항목6 미완), 코드·테스트 자체는 conventions/ADR 위반 없음.
- S1 — `dev` 최신 번호(V16) 확인 후 `V17__create_sell_trade_journals.sql` + `SellTradeJournal` 엔티티 + `SellTradeJournalRepository`(`existsBySellTradeId`) 추가, `BuyTradeJournal` 선례 그대로 미러링, compileJava 통과.
- S2 — `JournalService.createSellJournal` 추가(`tradeService.getOwnedTrade` 재사용, `side != SELL` 400, 선제 조회+`saveAndFlush` 유니크 위반 409 변환), 서비스 반환 타입 컴파일에 필요해 `SellJournalResponse`(4필드 응답 DTO, `from`)만 함께 추가, `TradeService`·매수 경로는 변경 없음, compileJava 통과.
- S3 — `JournalController`에 `POST /api/trades/{sellTradeId}/sell-journal` 추가(기존 매수 메서드 리팩터링 없음) + `SellJournalCreateRequest`(`@NotBlank`+`@Size(max=5000)`) 추가, `docs/api-routes.md`(journal 행 1개)·`docs/api-contracts.md`(## journal 절, 매도 회고 계약) 같은 커밋 대상으로 갱신, compileJava 통과.
