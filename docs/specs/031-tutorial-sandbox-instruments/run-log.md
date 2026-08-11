# Run Log: 031-tutorial-sandbox-instruments

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `.\gradlew.bat compileJava compileTestJava` | plan.md 1번 "샘플 종목 데이터 모델", ADR-0004(migration 정책) |
| - | implementer | `.\gradlew.bat compileJava compileTestJava` | plan.md "2. 항시 시세·장시간 우회"(PR #340 정정본), ClockConfig 기존 Clock 빈 |
| - | implementer | `.\gradlew.bat compileJava compileTestJava` | plan.md "3. 매도 단계 API 설계"(매도 chain 조회), 026 `findEarliestFilledBuyTradeMatching`과의 대칭 원칙 |
| - | implementer | `.\gradlew.bat compileJava compileTestJava` | plan.md "3. 매도 단계 API 설계" GET 4단계 응답(PR #340 정정본), plan.md "4. 5분 타이머" |

## 모니터링 (사람용 요약)
- V32 마이그레이션(`is_tutorial_sample` 컬럼 + 샘플 종목 6행) 추가, `Instrument`·`InstrumentResponse`에 필드 반영, `InstrumentService.getTradableInstrumentEntity`에 샘플 종목 커뮤니티 태그 제외 조건 추가. 컴파일 통과.
- `TutorialSampleInstrumentPriceService` 신설 + `PriceQueryService` 3개 메서드(getPriceQuote/getOrderExecutionPrice/getPriceQuotes) 샘플 종목 분기 추가, compileJava/compileTestJava 통과(기존 테스트 생성자 시그니처만 조정).
- `TradeService.findEarliestFilledSellTradeAfter` 추가(기존 derived 쿼리 재사용, SELL로 side만 다르게), `MarketPracticeChainResolutionService.resolveForFavorite`가 buyTrade 이후 첫 SELL을 채워 `ResolvedPracticeChainDto`에 `sellTradeId`·`sellTradeExecutedAt` 2필드 추가. 기존 DTO 생성 호출부(테스트 4개 파일) 시그니처만 `null, null` 보정. 컴파일 통과.
- `ResolvedPracticeChainDto`에 `instrumentIsTutorialSample` 추가, `InvestmentPracticeQueryService`가 샘플 종목 chain에서만 `steps` 4개(신규 4단계 매도·복기, `EXPIRED` 상태 포함)로 확장하고 `PracticeEvidenceResponse`에 `sellTradeId`·`sellTradeExecutedAt`·`saleDeadlineAt` 3필드 추가(실제 종목 chain은 3단계 그대로, 신규 필드는 항상 null). 5분 만료 판정(`!isAfter` 경계 포함)을 GET 조회에만 반영, `holding-reflections` 전제조건 변경은 다음 항목. 기존 테스트 5개 파일 생성자 시그니처만 보정, 컴파일 통과. `docs/api-routes.md`·`docs/api-contracts.md` 동기화, prd.md §3은 spec 미완결(후속 항목 남음)이라 갱신 대상 아님.
