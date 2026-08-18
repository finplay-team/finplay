# Run Log: 047-tutorial-sandbox-cash-isolation

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 18:46 | implementer | `./gradlew test --tests TutorialAccountRepositoryTest` | plan.md 데이터 모델·마이그레이션 설계, ADR-0004 |
| 19:20 | implementer | `./gradlew compileJava` | plan.md TutorialAccountService API 표, 설계 판단 — 계좌 생성 시점 |

## 모니터링 (사람용 요약)
- 18:46 — TutorialAccount 엔티티·Repository·V46 마이그레이션·TUTORIAL_INSUFFICIENT_CASH 추가, DataJpaTest 6건 통과.
- 19:20 — TutorialAccountService(get-or-create·reset) 추가, ensureAttempt에 연결. compileJava 통과, 기존 PracticeAttemptServiceTest는 생성자 인자 불일치로 compileTestJava 깨짐(tester 몫).
