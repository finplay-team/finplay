# Run Log: 032-price-quote-stale-split

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 항목1 | implementer | `.\gradlew.bat compileJava` | plan.md 코드 변경 설계(`PriceStatus`·`PriceQueryService`), ADR-0002 레이어 규칙 |
| 항목1 | implementer | `.\gradlew.bat test --tests "*PriceQueryServiceTest*" --tests "*CryptoCandleAndPriceIndependenceTest*"` | plan.md "테스트 계획" (a)~(f), tasks.md 항목1 제약(CryptoCandleAndPriceIndependenceTest 단정 불변) |
| 항목2 | implementer | `.\gradlew.bat compileJava compileTestJava` | plan.md `getCryptoDisplayPriceQuotes(List)` 설계, tasks.md 항목2("연결상태는 루프 밖에서 1회 조회") |
| 항목2 | implementer | `.\gradlew.bat test --tests "*PriceQueryServiceTest*" --tests "*CryptoCandleAndPriceIndependenceTest*"` | tasks.md 항목2, 공통 제약(CryptoCandleAndPriceIndependenceTest 단정 불변) |
| 항목3 | implementer | `.\gradlew.bat compileJava` 후 `.\gradlew.bat test --tests "*HoldingValuationServiceTest*"` | plan.md `HoldingValuationService` 절, spec.md PRICE-STALE-005(엄격 유지 확정) |
| 항목4 | implementer | `.\gradlew.bat test --tests "*CryptoPriceStreamServiceTest*" --tests "*SyntheticPriceServiceTest*" --tests "*PracticePriceSessionServiceTest*"` | spec.md PRICE-STALE-004·005, plan.md "테스트 계획"(코드 변경 없음, 기존 분기 고정) |

## 모니터링 (사람용 요약)
- 항목1 — `PriceStatus.STALE` 추가, `getCryptoExecutionPriceQuote`/`getCryptoDisplayPriceQuote` 분리 완료. 단건 테스트 29건 통과, CryptoCandleAndPriceIndependenceTest 2건 회귀 없음(스텁 보강 불필요).
- 항목2 — 배치 `getCryptoPriceQuotes`를 `getCryptoDisplayPriceQuotes`로 대체(연결상태 1회 조회 + 심볼별 `getLatestPrice`+`isStale`). `PriceQueryServiceTest` 32건 통과, CryptoCandleAndPriceIndependenceTest 2건 회귀 없음.
- 항목3 — `buildValuation` 조건을 `!= AVAILABLE`로 고정, STALE 입력 테스트 1건 추가. `HoldingValuationServiceTest` 전체 통과, api-contracts.md `## portfolio`·`## account`는 변경 없음(확인만).
- 항목4 — 프로덕션 코드 무변경, 3개 테스트 파일에 STALE 케이스만 추가(코드 확인 결과 spec.md 가정과 일치). BUILD SUCCESSFUL.
