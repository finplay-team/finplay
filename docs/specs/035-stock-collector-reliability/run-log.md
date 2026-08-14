# Run Log: 035-stock-collector-reliability

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 14:11 | implementer | `./gradlew compileJava compileTestJava test --tests "*KisHistoricalCandleCollectorTest" --tests "*InstrumentRepositoryTest"` | tasks.md 항목 1, spec.md COLLECT-STAB-002·004 |
| 15:20 | implementer | `./gradlew compileJava` | tasks.md 항목 2, plan.md §락 설계 세부, ADR-0014, feedback.service.RedisLock/CryptoWatchLock 선례 |
| 16:05 | implementer | `./gradlew compileJava` | tasks.md 항목 3, spec.md COLLECT-STAB-003, plan.md §재시도 스케줄 근거·§파일별 변경 지점, docs/agent-mistakes.md(zone 명시 함정) |

## 모니터링 (사람용 요약)
- 14:11 — 샌드박스 종목 제외 조회 메서드 추가, 수집 결과 로그 남김, 관련 테스트 24건 통과.
- 15:20 — MarketStockProperties/Config·StockCollectionLock 신설, collect()에 tryLock/finally-unlock 배선. compileJava 통과, 기존 KisHistoricalCandleCollectorTest·MarketDataPipelineIntegrationTest는 생성자 시그니처 변경으로 컴파일 깨짐(tester 조치 대상).
- 16:05 — MarketStockProperties에 retry-cron(기본 `0 15,30,45 8-10 * * MON-FRI`) 추가, retryPendingInstruments()가 collect()를 그대로 위임 호출하도록 신설(zone="Asia/Seoul" 명시). collect() 내부 락을 그대로 타므로 별도 락 우회 경로 없음. compileJava 통과.
