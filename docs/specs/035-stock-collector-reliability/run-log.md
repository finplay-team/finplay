# Run Log: 035-stock-collector-reliability

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 14:11 | implementer | `./gradlew compileJava compileTestJava test --tests "*KisHistoricalCandleCollectorTest" --tests "*InstrumentRepositoryTest"` | tasks.md 항목 1, spec.md COLLECT-STAB-002·004 |
| 15:20 | implementer | `./gradlew compileJava` | tasks.md 항목 2, plan.md §락 설계 세부, ADR-0014, feedback.service.RedisLock/CryptoWatchLock 선례 |

## 모니터링 (사람용 요약)
- 14:11 — 샌드박스 종목 제외 조회 메서드 추가, 수집 결과 로그 남김, 관련 테스트 24건 통과.
- 15:20 — MarketStockProperties/Config·StockCollectionLock 신설, collect()에 tryLock/finally-unlock 배선. compileJava 통과, 기존 KisHistoricalCandleCollectorTest·MarketDataPipelineIntegrationTest는 생성자 시그니처 변경으로 컴파일 깨짐(tester 조치 대상).
