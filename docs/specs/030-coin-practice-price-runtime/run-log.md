# Run Log: 030-coin-practice-price-runtime

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| - | implementer | `education.priceruntime` 패키지에 엔티티·Repository·v1 생성기·서비스·컨트롤러·V29 migration 작성 | plan.md 도메인·패키지 경계, 데이터 모델, 생성기·가격 anchor 절, Issue #318 |
| - | implementer | `.\gradlew.bat compileJava`, `spotlessApply` | conventions.md 포맷 규칙 |

## 모니터링 (사람용 요약)
- Issue #318 범위(세션 생성·조회 API, v1 생성기, migration V29)만 구현. next-tick·교육 지정가는 후속 이슈.
- compileJava 통과. Testcontainers 검증은 tester 담당.
