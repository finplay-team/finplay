# Run Log: 003-market-data

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `./gradlew compileJava` | plan.md 구성요소 설계(market 패키지), spec.md MKT-001, ADR-0002/0004, conventions.md |

## 모니터링 (사람용 요약)
- instruments 마이그레이션(V7, 시드 16+12) + Instrument 엔티티·Repository·Service·Controller(GET /api/instruments?market=) 구현, 컴파일 통과. 단건 조회(#15)는 범위 밖으로 제외.
