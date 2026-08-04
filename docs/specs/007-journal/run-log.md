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
| 리뷰(S) | reviewer(리뷰) | `git diff dev...HEAD`, `./gradlew compileJava compileTestJava spotlessCheck -q` | conventions.md, ADR-0002, ADR-0003, ADR-0004, spec.md, plan.md, tasks.md |
| U1 | implementer | `./gradlew compileJava` | plan.md §JOUR-004 §데이터 모델(3단계 DDL, `of`/`updateContent` 설계), tasks.md U1, ADR-0004 |
| U2 | implementer | `./gradlew compileJava` | plan.md §JOUR-004 §구성요소 설계(`updateSellJournal` 1~5단계), tasks.md U2, `createSellJournal` 선례(`tradeService.getOwnedTrade` 재사용, 존재→소유→매도여부 순), ADR-0002 |
| U3 | implementer | `./gradlew compileJava` | plan.md §JOUR-004 §API 설계·§컨트롤러(`PATCH .../sell-journal`, 200, DTO 2개), tasks.md U3, `createSellJournal` 컨트롤러 메서드 선례, CLAUDE.md 규칙 7 |
| 리뷰(U) | reviewer(리뷰) | `git diff dev...feat/190-sell-journal-update`, `./gradlew compileJava compileTestJava spotlessCheck -q` | conventions.md, ADR-0002, ADR-0003, ADR-0004, spec.md JOUR-004, plan.md §JOUR-004, tasks.md §JOUR-004 |
| B1 | implementer | `./gradlew compileJava` | plan.md §JOUR-002 §데이터 모델(3단계 DDL, `of`/`updateContent` 설계), tasks.md B1, V18(`sell_trade_journals`) 선례 그대로 미러링, ADR-0004 |
| B2 | implementer | `./gradlew compileJava`, `./gradlew test --tests "*JournalServiceTest*"` | plan.md §JOUR-002 §구성요소 설계(`updateBuyJournal` 1~5단계), tasks.md B2, `updateSellJournal` 선례(존재→소유→매수여부→회고존재 순, 잠금 판정 단계 없음, `HoldingLotRepository`·`TradeAllocationRepository` 미주입), ADR-0002 |
| B3 | implementer | `./gradlew compileJava`, `./gradlew compileTestJava` | plan.md §JOUR-002 §API 설계·§컨트롤러(`PATCH .../journal`, 200, DTO 2개), tasks.md B3, `updateSellJournal` 컨트롤러 메서드 선례, CLAUDE.md 규칙 7 |
| L1 | implementer | `./gradlew compileJava` | plan.md §JOUR-006 §커서 인코딩 형식·§데이터 접근 설계(코드 스니펫), tasks.md L1, `TradeCursor`·`TradeRepositoryCustom`/`Impl`(`order/`) 선례 그대로 미러링, ADR-0002 |

## 모니터링 (사람용 요약)
- 항목1 — `V14__create_buy_trade_journals.sql` + `BuyTradeJournal` 엔티티 + `BuyTradeJournalRepository` 추가, compileJava 통과. (이후 `dev`에 먼저 병합된 `V14__create_favorites.sql`과 번호가 겹쳐 `V15__create_buy_trade_journals.sql`로 재번호화됨 — plan.md §데이터 모델 참고)
- 항목2 — `TradeService.getOwnedTrade` 추가(404/403), `TradeRepository.findById`에 `@EntityGraph(account, account.user)` 오버라이드로 지연 로딩 우회, compileJava 통과.
- 항목3 — `JournalService.createBuyJournal`(존재→소유→매수여부→중복 순 검증, saveAndFlush 후 DataIntegrityViolationException→409 변환) + `BuyJournalResponse` 추가, compileJava 통과.
- 항목4 — `JournalController`(POST /api/trades/{buyTradeId}/journal) + `BuyJournalCreateRequest` 추가, 기존 `MethodArgumentTypeMismatchException` 핸들러 재사용 확인, 문서 갱신은 항목6에서 처리 예정, compileJava 통과.
- 리뷰 — 차단 1건(docs/api-routes.md·api-contracts.md 미반영, tasks.md 항목6 미완), 코드·테스트 자체는 conventions/ADR 위반 없음.
- S1 — `dev` 최신 번호(V16) 확인 후 `V17__create_sell_trade_journals.sql` + `SellTradeJournal` 엔티티 + `SellTradeJournalRepository`(`existsBySellTradeId`) 추가, `BuyTradeJournal` 선례 그대로 미러링, compileJava 통과.
- S2 — `JournalService.createSellJournal` 추가(`tradeService.getOwnedTrade` 재사용, `side != SELL` 400, 선제 조회+`saveAndFlush` 유니크 위반 409 변환), 서비스 반환 타입 컴파일에 필요해 `SellJournalResponse`(4필드 응답 DTO, `from`)만 함께 추가, `TradeService`·매수 경로는 변경 없음, compileJava 통과.
- S3 — `JournalController`에 `POST /api/trades/{sellTradeId}/sell-journal` 추가(기존 매수 메서드 리팩터링 없음) + `SellJournalCreateRequest`(`@NotBlank`+`@Size(max=5000)`) 추가, `docs/api-routes.md`(journal 행 1개)·`docs/api-contracts.md`(## journal 절, 매도 회고 계약) 같은 커밋 대상으로 갱신, compileJava 통과.
- 리뷰(S) — 차단 0건 / 권장 1건(S4 커밋에 run-log 미기록) / 참고 1건. JOUR-003 코드·테스트·문서·마이그레이션 번호(V17) 모두 conventions/ADR 부합, JOUR-001 기존 코드·테스트 무변경 확인. 머지 가능.
- U1 — `dev` 최신 번호(V17) 확인 후 `V18__add_updated_at_to_sell_trade_journals.sql`(nullable 추가→백필→NOT NULL 3단계) + `SellTradeJournal.updatedAt`·`updateContent(content, updatedAt)`(`of`는 시그니처 유지, 내부에서 `updatedAt`도 `now`로 채움) + `SellTradeJournalRepository.findBySellTradeId` 추가, 테스트는 tester 담당이라 미작성, compileJava 통과.
- U2 — `JournalService.updateSellJournal` 추가(존재→소유→매도여부→회고존재 순 검증, `findBySellTradeId` 빈 값이면 404, upsert 없이 dirty checking으로 `updateContent` 반영) + `SellJournalUpdateResponse`(5필드 응답 DTO, `from`) 추가, 컨트롤러·요청 DTO는 U3 범위라 미착수, compileJava 통과.
- U3 — `JournalController`에 `PATCH /api/trades/{sellTradeId}/sell-journal` 추가(기존 매수·매도 작성 메서드 리팩터링 없음, 200 반환) + `SellJournalUpdateRequest`(`@NotBlank`+`@Size(max=5000)`) 추가, `docs/api-routes.md`(journal 행 1개)·`docs/api-contracts.md`(## journal 절에 수정 소절 추가) 같은 커밋 대상으로 갱신, 테스트는 tester 담당이라 미작성, compileJava 통과.
- 리뷰(U) — 차단 0건. 검증 순서(404→403→400→404) 코드·테스트 일치, JOUR-001·JOUR-003 계약·코드 무변경 확인, V18 마이그레이션은 원장 미접촉·번호 충돌 없음, 컨트롤러에 비즈니스 로직 없음, 새 ErrorCode 미추가, 테스트 전부 의미있는 assertion. 머지 가능.
- B1 — `dev` 최신 번호(V18) 확인 후 `V19__add_updated_at_to_buy_trade_journals.sql`(nullable 추가→백필→NOT NULL 3단계) + `BuyTradeJournal.updatedAt`·`updateContent(content, updatedAt)`(`of`는 시그니처 유지, 내부에서 `updatedAt`도 `now`로 채움) + `BuyTradeJournalRepository.findByBuyTradeId` 추가, 기존 `BuyTradeJournalRepositoryTest`(`of` 시그니처 무변경이라 영향 없음) 확인, 테스트는 tester 담당이라 신규 작성 없음, compileJava 통과.
- PR 리뷰 — `dev`에 `V19__drop_favorites_and_practice_intentions.sql`(#193)이 먼저 병합돼 버전 충돌 발견(reviewer pcb2002 차단 지적). `V20__add_updated_at_to_buy_trade_journals.sql`로 재번호화, `dev` 병합 후 `./gradlew build` 재검증.
- PR 리뷰 후 재확인 — `dev`에 `V20__add_stock_replay_session_to_trades.sql`(#191)까지 먼저 병합돼 방금 붙인 V20과 다시 충돌. `dev` 재병합 후 `V21__add_updated_at_to_buy_trade_journals.sql`로 재번호화, `./gradlew build` 재검증.
- B2 — `JournalService.updateBuyJournal` 추가(존재→소유→매수여부→회고존재 순 검증, 잠금 판정 단계·`HoldingLotRepository`/`TradeAllocationRepository` 미주입, `updateSellJournal`과 단계 구성 동일) + `BuyJournalUpdateResponse`(5필드 응답 DTO, `from`) 추가, `JournalServiceTest`에 대칭 단위 테스트 14건(정상 수정·404×2 구분·403·400·연속 2회 수정) 작성해 28건 전부 통과, 컨트롤러·요청 DTO는 B3 범위라 미착수, compileJava 통과.
- B3 — `JournalController`에 `PATCH /api/trades/{buyTradeId}/journal` 추가(기존 매수·매도 작성·수정 메서드 리팩터링 없음, 200 반환) + `BuyJournalUpdateRequest`(`@NotBlank`+`@Size(max=5000)`) 추가, `docs/api-routes.md`(journal 행 1개)·`docs/api-contracts.md`(## journal 절에 매수 회고 수정 소절 추가, 잠금 없음 명시) 같은 커밋 대상으로 갱신, 테스트는 tester 담당이라 미작성(기존 JournalControllerTest·JournalServiceTest 등 compileTestJava로 무영향 확인), compileJava 통과.
- L1 — `JournalCursor`(`journal.service`, `TradeCursor`와 동형이나 `encode(createdAt, tradeId)`가 값 2개를 직접 받음) + `BuyTradeJournalRepositoryCustom`/`Impl`·`SellTradeJournalRepositoryCustom`/`Impl`(각각 `buyTrade.account.id`/`sellTrade.account.id` 단일 조건으로 market+소유권 결합, `fetchJoin`, `createdAt.desc()`+체결ID `desc()` 정렬) 추가, 기존 두 `~Repository`가 각각 Custom 인터페이스 상속하도록 수정, `QBuyTradeJournal`/`QSellTradeJournal`은 어노테이션 프로세서가 자동 생성(별도 조치 불필요 확인), `JournalService`·컨트롤러·DTO·마이그레이션은 범위 밖이라 미착수, compileJava 통과.
