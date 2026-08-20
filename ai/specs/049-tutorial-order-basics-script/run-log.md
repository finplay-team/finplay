# Run Log: 049-tutorial-order-basics-script

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 21:20 | implementer | `.\gradlew.bat spotlessApply compileJava compileTestJava` | tasks.md 1번, plan.md §1(로더 구조·basePrice·generateHistory 분기), ADR-0002 |
| 21:42 | implementer | `.\gradlew.bat spotlessApply compileJava compileTestJava spotbugsMain` | tasks.md 2번, plan.md §2(NULL 해석 규칙·값이 정해지는 자리), ADR-0004, ADR-0021 §결정 7 |
| 21:51 | implementer | `.\gradlew.bat test --tests PracticeScenarioFullJourneyIntegrationTest` | 2026-08-20 사용자 결정(진입 대본 041 고정), tasks.md 2번·5번 |
| 22:10 | implementer | `.\gradlew.bat spotlessApply compileJava compileTestJava spotbugsMain` | tasks.md 2-A번, spec.md §비즈니스 규칙 "2단계 대본과 자동 예약" |
| 22:57 | implementer | `.\gradlew.bat spotlessApply compileJava compileTestJava` | reviewer 권장 2건(가시성 축소·시장 조건) + 낡은 주석 2건 |
| 23:15 | implementer | `.\gradlew.bat spotlessApply compileJava compileTestJava` | tasks.md 3번, plan.md §5(안내 범위 일반식), ADR-0002 |

## 모니터링 (사람용 요약)
- 21:20 — `basePrice`를 대본 파일 필드로 옮기고 `TutorialScenarioScriptId`로 대본 2개를 등록, 041 대본의 canonical 가격 120분·과거 29봉×18조합을 커밋 전후 값으로 대조해 전부 동일함을 확인, 컴파일 통과.
- 21:42 — V53로 `scenario_script_id`를 추가하고 NULL 해석을 `PracticeAttempt.scenarioScriptId()` 한 곳에 두었다, 대본 식별자 열거형은 엔티티 매핑 때문에 `market.domain`으로 옮겼다(도메인→service 역방향 회피), 컴파일 통과.
- 21:51 — 진입 대본을 `CRYPTO_STORY_V1` 고정으로 되돌렸다(전환 엔드포인트 전에 진입을 바꾸면 041 이야기가 도달 불가), 041 여정 통합 테스트 2건 초록.
- 22:10 — 2단계 대본 실행에서 `createAutomaticExitPlan` 호출만 건너뛰고 위험 기준선·예약 엔진 규칙은 그대로 두었다(판정은 `attempt.scenarioScriptId()` 하나), 컴파일 통과.
- 22:57 — 대본 없는 `generateHistory`를 package-private으로 좁히고 진입 대본 고정에 `market == CRYPTO` 조건을 더해 STOCK 대본 도입 시의 500을 막았다, 컴파일 통과.
- 23:15 — `market.service`에 안내 범위 순수 함수(단위 1,000 미고정), `PracticeTutorialChartResponse`에 `priceGuideRange` 추가, 판정은 `script.events().isEmpty()` 하나. 2단계 대본 값(90000~110000)을 별도 계산으로 검증, 컴파일 통과.
