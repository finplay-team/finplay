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
| 23:40 | implementer | `.\gradlew.bat spotlessApply compileJava compileTestJava` 후 `.\gradlew.bat test --tests FinPlayApiApplicationTests` | tasks.md 4번, plan.md §4(순환 참조 회피 — 포트 인자만 증가) |
| 00:05 | implementer | `.\gradlew.bat spotlessApply compileJava compileTestJava` | tasks.md 5번, plan.md §3(전환 절차·잠금 순서), CLAUDE.md 규칙 7 |
| 00:30 | implementer | `.\gradlew.bat spotlessApply compileJava compileTestJava` (compileJava 통과, compileTestJava는 기존 테스트 시그니처 불일치로 실패) | tasks.md 5-A번, plan.md §3-A(진입별 대본 식별자·V55·NULL 해석 위치), CLAUDE.md 규칙 7·8 |
| 01:10 | implementer | `.\gradlew.bat spotlessApply compileJava compileTestJava`(통과), `.\gradlew.bat test --tests PracticeOrderBasicsFullJourneyIntegrationTest`(환경 원인 Testcontainers 연결 끊김으로 미검증, 재시도 1회 후 tester에 위임) | tasks.md 6번, spec.md ORDERBASICS-012~014, CLAUDE.md 규칙 7·10 |

## 모니터링 (사람용 요약)
- 21:20 — `basePrice`를 대본 파일 필드로 옮기고 `TutorialScenarioScriptId`로 대본 2개를 등록, 041 대본의 canonical 가격 120분·과거 29봉×18조합을 커밋 전후 값으로 대조해 전부 동일함을 확인, 컴파일 통과.
- 21:42 — V53로 `scenario_script_id`를 추가하고 NULL 해석을 `PracticeAttempt.scenarioScriptId()` 한 곳에 두었다, 대본 식별자 열거형은 엔티티 매핑 때문에 `market.domain`으로 옮겼다(도메인→service 역방향 회피), 컴파일 통과.
- 21:51 — 진입 대본을 `CRYPTO_STORY_V1` 고정으로 되돌렸다(전환 엔드포인트 전에 진입을 바꾸면 041 이야기가 도달 불가), 041 여정 통합 테스트 2건 초록.
- 22:10 — 2단계 대본 실행에서 `createAutomaticExitPlan` 호출만 건너뛰고 위험 기준선·예약 엔진 규칙은 그대로 두었다(판정은 `attempt.scenarioScriptId()` 하나), 컴파일 통과.
- 22:57 — 대본 없는 `generateHistory`를 package-private으로 좁히고 진입 대본 고정에 `market == CRYPTO` 조건을 더해 STOCK 대본 도입 시의 500을 막았다, 컴파일 통과.
- 23:15 — `market.service`에 안내 범위 순수 함수(단위 1,000 미고정), `PracticeTutorialChartResponse`에 `priceGuideRange` 추가, 판정은 `script.events().isEmpty()` 하나. 2단계 대본 값(90000~110000)을 별도 계산으로 검증, 컴파일 통과.
- 23:40 — `PRACTICE_STAGE_LOCKED` 추가, `lockForOrder`에 `OrderType` 인자만 늘리고(포트 메서드 신설 없음) 게이트는 education 구현체 안에서 `PracticeStageProgressCalculationService`를 불러 끝냈다, `selectExitPreset`에도 보유 중 잠금보다 앞서 같은 게이트를 넣었다. `FinPlayApiApplicationTests`로 컨텍스트가 실제로 뜨는 것까지 확인(순환 참조 없음), 컴파일 통과.
- 00:05 — `POST .../advance-script` 신설(거부 5가지 → 예약·지정가 정리 → `scenario_script_id` 교체 + 커서 전체 초기화, run·계좌·`exitPreset` 무변경). 정리는 `PracticeRunRestartOrderService.cleanupCurrentRun`을 재사용하지 않고 `PracticeOrderSettlementService`에 개별 지정가 취소 메서드를 새로 뽑아 썼다(홀딩 0 보장 전제라 계좌 리셋·보상매도 로직이 불필요). `PracticeAttemptService.scenarioScriptIdFor`를 `firstScriptId(market)`로 되돌려 진입을 2단계로 열었다. `ai/api-routes.md`·`docs/api/education.md`에 advance-script 계약 추가, 컴파일 통과.
- 00:30 — V55로 `practice_risk_snapshots.scenario_script_id`를 추가(로컬·origin/dev 둘 다 최고 V54라 산술대로 V55 확정). `PracticeRiskSnapshot`은 원본 컬럼·평범한 getter만 추가(NULL 해석 없음). `createRiskSnapshotOnBuyFill`이 이미 로드된 `attempt.scenarioScriptId()`를 스냅샷 생성 인자로 그대로 넘겨 추가 조회 없음. `PracticeEntryResponse`에 `scenarioScriptId` 필드 추가, `PracticeEntryComparisonService.toEntry`가 `exitPreset`과 같은 자리·같은 패턴으로 NULL을 해석(`!usesScenarioScript()`→null, 스냅샷 NULL→`CRYPTO_STORY_V1`, 그 외 스냅샷 값). `ai/api-routes.md`·`docs/api/education.md`의 `entries[]` 계약에 필드 반영. `compileJava` 통과, `compileTestJava`는 생성자 시그니처가 바뀐 기존 테스트 3개 파일이 실패 — tester 몫으로 남김.
- 01:10 — tasks.md 6번(마지막 항목). 통합 테스트 `PracticeOrderBasicsFullJourneyIntegrationTest` 1개로 시장가 왕복→범위 밖(130,000원) 지정가 매도 접수→여러 tick 미체결 유지→취소→범위 안(108,000원) 재접수→다음 바퀴 내 체결을 검증(ORDERBASICS-012~014). 종목 선택만으로 `firstScriptId(CRYPTO)`가 2단계 대본을 열어 주므로 별도 reflection 없이 서비스 경로로 픽스처를 만들었다. `ai/api-routes.md`의 chart·tick 행에 `priceGuideRange` 명시 추가. `docs/api/education.md` — `scenarioStage` 허용값에 `ORDER_BASICS` 추가, chart·tick 응답에 `priceGuideRange` 계약 반영, "판정만 하고 강제하지 않는다" 문장을 뒤집어 409 `PRACTICE_STAGE_LOCKED` 게이트 실제 동작을 반영(exit-preset·`education/practice/limit-orders` 오류표에도 코드 추가). `ai/prd.md` §3 — `TUTORIAL-STAGE-001`을 완료로 갱신하고 049 전체 요약 행(ORDERBASICS-001~023, PR #TBD)을 신설. `compileJava`·`compileTestJava` 통과, 새 통합 테스트 실행은 Testcontainers MySQL 연결 끊김(환경 리소스 부족, 재시도 1회도 데몬 중단)으로 이 세션에서 확인하지 못해 tester에 위임.
