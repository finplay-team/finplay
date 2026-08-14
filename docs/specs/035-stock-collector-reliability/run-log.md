# Run Log: 035-stock-collector-reliability

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 14:11 | implementer | `./gradlew compileJava compileTestJava test --tests "*KisHistoricalCandleCollectorTest" --tests "*InstrumentRepositoryTest"` | tasks.md 항목 1, spec.md COLLECT-STAB-002·004 |

## 모니터링 (사람용 요약)
- 14:11 — 샌드박스 종목 제외 조회 메서드 추가, 수집 결과 로그 남김, 관련 테스트 24건 통과.
