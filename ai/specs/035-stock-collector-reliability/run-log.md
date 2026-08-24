# Run Log: 035-stock-collector-reliability

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 14:11 | implementer | `./gradlew compileJava compileTestJava test --tests "*KisHistoricalCandleCollectorTest" --tests "*InstrumentRepositoryTest"` | tasks.md 항목 1, spec.md COLLECT-STAB-002·004 |
| 15:20 | implementer | `./gradlew compileJava` | tasks.md 항목 2, plan.md §락 설계 세부, ADR-0014, feedback.service.RedisLock/CryptoWatchLock 선례 |
| 16:05 | implementer | `./gradlew compileJava` | tasks.md 항목 3, spec.md COLLECT-STAB-003, plan.md §재시도 스케줄 근거·§파일별 변경 지점, ai/agent-mistakes.md(zone 명시 함정) |
| 16:30 | implementer | `./gradlew compileJava` | tasks.md 항목 4, spec.md COLLECT-STAB-005, plan.md §`KisProperties.java` + `application-prod.yml`, application-local.yml 기존 600ms 근거 주석 |
| 17:40 | reviewer | `git diff dev...HEAD` 전체 정독(코드·테스트·yml) + `gh issue view 370/373` 대조 | docs/conventions.md, ai/adr/0002-architecture.md, ai/adr/0003-testing-strategy.md, ai/adr/0004-flyway-migrations.md, spec.md, plan.md, RedisLock/CryptoWatchLock 선례 |

## 모니터링 (사람용 요약)
- 14:11 — 샌드박스 종목 제외 조회 메서드 추가, 수집 결과 로그 남김, 관련 테스트 24건 통과.
- 15:20 — MarketStockProperties/Config·StockCollectionLock 신설, collect()에 tryLock/finally-unlock 배선. compileJava 통과, 기존 KisHistoricalCandleCollectorTest·MarketDataPipelineIntegrationTest는 생성자 시그니처 변경으로 컴파일 깨짐(tester 조치 대상).
- 16:05 — MarketStockProperties에 retry-cron(기본 `0 15,30,45 8-10 * * MON-FRI`) 추가, retryPendingInstruments()가 collect()를 그대로 위임 호출하도록 신설(zone="Asia/Seoul" 명시). collect() 내부 락을 그대로 타므로 별도 락 우회 경로 없음. compileJava 통과.
- 16:30 — application-prod.yml에 kis.request-interval-ms: 600 추가(로컬과 동일 근거 주석), KisProperties.java 주석의 "실전투자 도메인 기준" 근거를 현재 배포 구성(운영도 모의투자 도메인, 양쪽 프로필이 600 명시)에 맞게 정정. 설정·주석 변경만이라 별도 테스트 없음. compileJava 통과.
- 17:40 — reviewer 판정: 차단 0건 / 권장 2건 / 참고 2건. 락·재시도 위임·로그·트랜잭션 경계·prd.md 동기화 모두 컨벤션 준수 확인. 신규 재시도 크론(08:15~10:45 9회)이 다른 배치들과 달리 테스트에서 비활성화되지 않아 CI 오염 위험 9배 확대, 마지막 커밋의 [재시도 무대상] 단정 완화가 완료조건 문구보다 좁게 검증되는 점을 권장으로 남김.
