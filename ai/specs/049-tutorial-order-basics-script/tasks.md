# Tasks: 튜토리얼 2단계 — 주문 방법 학습 전용 대본과 단계 순서 강제

각 항목 = 커밋 1개. 순서대로 진행한다 — 1·2번이 없으면 3번 이후가 설 자리가 없다.

> **빌드·테스트는 구현 세션이 실행한다.** 이 문서를 쓴 planner 세션은 `./gradlew`를 돌리지 않았다
> (메인 세션과 워크스페이스를 공유해 gradle 데몬이 겹치면 양쪽이 깨진다). 아래 "검증"은 전부
> **구현 세션이 실제로 실행해 확인할 것**이다. 완료 선언 전 `./gradlew build`는 CLAUDE.md 규칙 4대로.

---

## 1. 대본 기준가를 파일 필드로 옮기고 2단계 대본을 등록한다 ✅ (이슈 #507)

- `TutorialScenarioScript`에 `BigDecimal basePrice` 추가, 로더 검증에 `basePrice > 0` 추가.
- `TutorialScenarioScriptId` 열거형 신설(`CRYPTO_ORDER_BASICS_V1`·`CRYPTO_STORY_V1`), 로더를
  `Map<TutorialScenarioScriptId, TutorialScenarioScript>`로 바꾸고 `script(scriptId)`·
  `firstScriptId(market)` 노출. `hasScript(market)`는 의미를 유지한 채 구현만 바꾼다.
- `TutorialPriceGenerator`의 버전 2 경로(`canonicalPrice(input, script, cursor)`)와
  **`generateHistory`**가 `script.basePrice()`를 쓰도록 오버로드. 버전 1은 기존 상수 그대로.
- `src/main/resources/tutorial/scenario-crypto-v1.json`에 `"basePrice": 10000.00000000` 한 줄 추가
  (**id·배율·사건은 한 글자도 건드리지 않는다**).
- `ai/specs/049-.../scenario-crypto-orderbasics-v1.json`을
  `src/main/resources/tutorial/scenario-crypto-orderbasics-v1.json`으로 옮긴다.

**검증**
- `@SpringBootTest` 컨텍스트가 뜬다 = 두 대본이 기동 검증을 통과한다. **대본 정합성은 이 지점에서만
  잡히므로 `@DataJpaTest`로 대신하지 않는다.**
- `basePrice`를 지운 사본·배율 개수를 틀린 사본으로 `TutorialScenarioScriptLoader.load`를 직접 불러
  기동이 실패하는지(기존 로더 테스트와 같은 방식).
- **회귀 고정**: 041 대본 실행의 canonical 가격과 29개 과거 봉이 이 커밋 전후로 동일한지 값으로 비교.
  이 테스트가 없으면 `generateHistory` 갈래가 조용히 틀려도 드러나지 않는다.
- 2단계 대본의 극값이 정확히 `88000.00000000`·`112000.00000000`이고 각각 8회 닿는지.

---

## 2. attempt에 대본 식별자를 영속한다 (V53) ✅ (이슈 #507)

- `V53__add_scenario_script_id_to_practice_attempts.sql` — `scenario_script_id VARCHAR(32) NULL`.
  추가만 하는 nullable 컬럼이라 파괴적 변경이 아니다(ADR-0021 §결정 7의 2단계 배포 대상 아님).
  **머지 직전에 `origin/dev`의 최고 마이그레이션 번호를 다시 확인한다** — 병렬 브랜치가 V53을 선점하면
  CI가 막는다(`ai/agent-mistakes.md`의 동형 사례).
- `PracticeAttempt`에 매핑 + **`NULL` 해석 규칙을 파생 접근자 한 곳에** 둔다
  (버전 2 + `NULL` → `CRYPTO_STORY_V1`, plan §2).
- `selectInstrument`가 `firstScriptId(market)`를 박고, **`clearScenarioProgress()`가 이 컬럼도 지운다.**
- `PracticeAttemptCanonicalPriceService.script(attempt)`를 `loader.script(attempt.scenarioScriptId())`로.

**검증**
- `@DataJpaTest`로 컬럼 왕복 저장·조회, `restart` 후 `NULL`이 되는지.
- **하위 호환 단위 테스트**: 버전 2 + `scenario_script_id = NULL` + `scenario_stage_id = "ACT2_RUMOR"`인
  attempt가 500이 아니라 041 대본 가격을 받는지. (049 배포 순간 진행 중이던 사용자의 상태다.)
- 종목 선택 직후 attempt가 `CRYPTO_STORY_V1`을 갖는지. **진입 대본은 전환 경로(5번)가 생길 때까지 041 고정이다**
  — dev 머지가 곧 배포인 레포라, 전환 수단 없이 진입만 2단계로 바꾸면 041 이야기가 도달 불가가 된다(2026-08-20 사용자 결정).

---

## 2-A. 2단계 대본 실행에서 자동 OCO 예약을 만들지 않는다 (ORDERBASICS-022) ✅ (이슈 #507)

2번(대본 식별자 영속)이 끝나야 분기 조건이 생긴다. 그 뒤에 한다.

- `PracticeAttemptOrderAttributionService.createRiskSnapshotOnBuyFill`에서 **snapshot은 그대로 만들고**
  `createAutomaticExitPlan` 호출만 건너뛴다. 조건은 그 run의 대본이 2단계용인가다(CRYPTO 조건 옆).
- **기준선까지 빼면 안 된다** — `PracticeAttemptEvidenceService.requireCurrentRun`이
  `PRACTICE_EVIDENCE_MISSING`으로 던져 관찰·복기가 통째로 깨진다.
- `ExitPlan.createPractice`의 귀속 검증과 `findPendingExitPlansToFill`의 `practiceAttemptId is null`
  불변식(PR #487)은 건드리지 않는다.

**검증**

- 통합: 2단계 대본 실행에서 매수 → `exit_plans`가 0건 → tick을 여러 바퀴 돌려도 자동 청산이 없다 →
  직접 시장가 매도 → `marketBuySellCompleted`가 `true`. **tick을 최소 한 바퀴(가상 20분) 이상 돌려라**
  — 기본 프리셋이 살아 있으면 6~9초 만에 발동하므로 짧게 돌리면 회귀를 놓친다.
- 통합: 같은 실행에서 `PracticeRiskSnapshot`은 정상 생성되고 관찰·복기가 끝까지 돈다.
- 3단계 대본 실행에서는 예약이 여전히 생기는지 회귀로 함께 본다.
- 예약 부재가 기존 경로에 무해하다는 것은 spec §비즈니스 규칙에 전수 확인 결과가 있다. **그 조사를
  반복하지 말고** 위 두 통합 테스트로 실제 동작만 고정해라.

## 3. 대본 가격 안내 범위를 차트 응답에 싣는다 ✅

- `market.service`에 순수 계산 함수 추가(plan §5의 일반식). 상수 1,000을 박지 않는다.
- `PracticeTutorialChartResponse`에 `priceGuideRange` 추가. 판정식은 `script.events().isEmpty()` 하나.
- 대본 미사용 실행·완료 replay는 `null`(기존 네 필드와 같은 규칙).

**검증**
- 단위: 2단계 대본 → `90000 ~ 110000`. **041 대본 → `null`**(이 한 줄이 SCENARIO-015·020을 지킨다).
  폭이 좁아 `low >= high`가 되는 인공 대본 → `null`.
- `@WebMvcTest`: `priceGuideRange`가 있을 때/`null`일 때 직렬화.
- 블랙박스로 041 실행의 차트 응답 어디에도 `7900`대 숫자가 없는지 확인.

---

## 4. 단계 순서 강제를 넣는다 (409)

- `ErrorCode`에 `PRACTICE_STAGE_LOCKED(CONFLICT, "앞 단계를 먼저 마쳐야 합니다.")` 추가 +
  `ErrorCodeTest` 표에 행 추가.
- `PracticeOrderAttributionPort.lockForOrder` 시그니처에 주문 유형(필요하면 side도) 추가, 호출부 셋
  (`OrderExecutionService`·`LimitOrderCreationService`·`PracticeLimitOrderCreationService`) 갱신.
- `PracticeAttemptOrderAttributionService.lockForOrder`의 **`isTutorialSample()` 조기 반환 뒤에**
  게이트를 넣는다. 판정은 `PracticeStageProgressCalculationService`(#503) 재사용.
- `PracticeAttemptService.selectExitPreset`에 같은 판정을 **보유 중 잠금보다 앞에** 넣는다.

**검증**
- ⚠️ **`@SpringBootTest` 컨텍스트가 실제로 뜨는지 먼저 확인한다.** 042에서 이 방향으로 실제 순환이
  나 컨텍스트가 통째로 안 떴다. 단위 테스트는 순환을 잡지 못한다.
- 단위: 규칙표 조합(MARKET 항상 통과 / LIMIT은 시장가 왕복 필요 / 프리셋은 둘 다 필요 /
  버전 1·실거래는 통과).
- `@WebMvcTest`: 409 본문의 `error.code`가 `PRACTICE_STAGE_LOCKED`.
- **회귀**: 실거래 지정가·030 가격 세션 경로가 영향받지 않는지(기존 통합 테스트가 그대로 초록).

---

## 5. 2단계 → 3단계 전환 엔드포인트

- `POST /api/education/practice/attempts/{market}/advance-script` + 서비스.
  attempt 잠금 → 거부 조건 5가지 → PENDING 지정가·예약 정리 → `scenario_script_id` 교체 +
  커서 다섯 컬럼 `NULL`. **run·튜토리얼 계좌·`exitPreset`은 건드리지 않는다.**
- 정리 로직은 `PracticeRunRestartOrderService.cleanupCurrentRun`과 같은 규칙을 쓰되 run 증가·계좌
  리셋은 하지 않는다. 재사용할지 뽑아 쓸지는 구현 세션이 판단한다.
- ⚠️ **진입 대본을 2단계로 여는 한 줄을 이 작업이 함께 바꾼다.**
  `PracticeAttemptService.scenarioScriptIdFor`가 2번 작업에서 `CRYPTO_STORY_V1` 고정으로 들어갔다 —
  전환 엔드포인트가 없는 상태에서 진입을 2단계로 바꾸면 041 이야기가 도달 불가가 되기 때문이다.
  이 작업에서 그 한 줄을 `tutorialScenarioScriptLoader.firstScriptId(market)`로 되돌려야 진입이 2단계로 열린다.
  빠뜨리면 049가 끝나도 아무도 2단계 대본을 만나지 못한다.

**검증**
- 단위: 거부 5가지(완료 상태 / 대본 미사용 / 이미 3단계 / 단계 미완료 / 보유 중).
- **통합(Testcontainers)**: 시장가 왕복 → 지정가 왕복 → `advance-script` → `runNumber` 불변 ·
  튜토리얼 계좌 현금 유지 · `tutorialStageProgress` 두 값 `true` 유지 → tick → 041 대본
  `IDLE_ENTRY` 가격 → 프리셋 선택이 이제 허용됨.
- 보유 중 전환 요청이 409로 막히는지 통합에서도 확인.

---

## 5-A. 완료 대조 배열이 진입마다 대본 식별자를 싣는다 (ORDERBASICS-023, 2026-08-21 사용자 결정, 이슈 #512)

5번(전환 엔드포인트, `scenario_script_id` 교체)이 끝나야 같은 run 안에 서로 다른 대본의 진입이 실제로
생긴다. 그 뒤에 한다. plan §3-A가 상세 설계다.

- `V55__add_scenario_script_id_to_practice_risk_snapshots.sql`(**잠정 번호** — `origin/dev`의
  최고 마이그레이션 번호를 머지 직전에 다시 확인한다, tasks 2번과 같은 경고). `practice_risk_snapshots`에
  `scenario_script_id VARCHAR(32) NULL` 추가. 추가만 하는 nullable 컬럼이라 파괴적 변경이 아니다.
- `PracticeRiskSnapshot`에 원본 컬럼 매핑만 추가한다(평범한 getter). **NULL 해석 로직은 엔티티에 두지
  않는다** — 판정에 필요한 `attempt.usesScenarioScript()`를 얻으려면 `attempt`를 지연 로딩해야 하는데,
  호출자(`PracticeEntryComparisonService`)가 이미 `attempt`를 인자로 갖고 있다.
- `PracticeAttemptOrderAttributionService.createRiskSnapshotOnBuyFill`의 `PracticeRiskSnapshot.create(...)`
  호출에 `attempt.scenarioScriptId()`를 인자로 더한다. 그 시점에 `attempt`가 이미 `findByIdForUpdate`로
  완전히 로드돼 있어 추가 조회가 없다.
- `PracticeEntryResponse`에 `String scenarioScriptId` 필드를 추가한다.
- `PracticeEntryComparisonService.toEntry`에 해석을 둔다(`exitPreset`과 같은 자리, 같은 패턴) —
  `!attempt.usesScenarioScript()`면 `null`, 스냅샷 값이 `NULL`이면 `CRYPTO_STORY_V1`(049 이전 진입),
  그 외는 스냅샷 값 그대로.
- `ai/api-routes.md`·`docs/api/education.md`의 완료 대조 배열(`entries[]`) 계약에 `scenarioScriptId`
  필드를 더한다(CLAUDE.md 규칙 7, 같은 커밋).

**검증**
- `@DataJpaTest`: 새 컬럼 왕복 저장·조회.
- 단위(`PracticeEntryComparisonService`): 2단계 진입 스냅샷 → `CRYPTO_ORDER_BASICS_V1`, 3단계 진입 →
  `CRYPTO_STORY_V1`, 컬럼이 `NULL`인 049 이전 스냅샷(대본 사용 attempt) → `CRYPTO_STORY_V1`로 해석,
  대본을 쓰지 않는 실행(생성기 버전 1)의 진입 → `null`.
- **통합**: 시장가 왕복(2단계 진입 1개, `scenario_script_id = CRYPTO_ORDER_BASICS_V1`) →
  `advance-script` → 3단계에서 매수(진입 2개, `scenario_script_id = CRYPTO_STORY_V1`) → `entries[]`
  두 건이 각각 다른 `scenarioScriptId`를 싣는지 확인.

---

## 6. 미체결 → 취소 → 재접수 → 체결 통합 시나리오 + 문서 갱신

- **통합 테스트 1개**로 spec의 핵심 장면을 끝까지 돌린다(ORDERBASICS-012·013·014).
  시장가 왕복 → 범위 밖(130,000원) 지정가 매도 접수 성공 → 여러 tick 미체결 유지 → 취소 성공 →
  범위 안(108,000원) 재접수 → 다음 바퀴 안에 체결.
- `ai/api-routes.md` — `advance-script` 행 추가, chart·tick 행에 `priceGuideRange` 명시.
- `docs/api/education.md` — **`scenarioStage` 허용값에 `ORDER_BASICS`를 더한다.** 2단계 대본의 유일한
  구간이 `id: ORDER_BASICS`·`act: null`이고 `PracticeScenarioNarrativeCalculator`가 `act`가 null이면
  구간 id를 그대로 라벨로 내보내므로, 지금 계약이 못박은 7개(`IDLE_ENTRY`\|`ACT1`\|`ACT2`\|
  `IDLE_REENTRY`\|`ACT3`\|`ACT4`\|`FINISHED`) 밖의 값이 나간다. `PracticeTutorialChartResponse`의
  javadoc도 같은 목록을 들고 있어 함께 고친다 — 빠뜨리면 프론트가 모르는 값을 받는다.
- `docs/api/education.md` — 안내 범위·409 게이트 반영. ⚠️ **지금 그 문서에 "판정만 하고 강제하지
  않는다"가 적혀 있다(#503). 이번 변경이 그 문장을 뒤집으므로 반드시 같은 커밋에서 고친다.**
- `ai/prd.md` §3 — `TUTORIAL-STAGE-001`의 "미착수: 게이트 강제"를 갱신하고, 2단계 대본 분리 행을
  새로 더한다. **근거 칸에는 그 PR 번호를 적는다**(CLAUDE.md 규칙 10).

**검증**
- 통합 테스트 초록. `./gradlew build` 통과.
- 문서 표의 Method/URL이 실제 `@PostMapping` 값과 일치하는지 대조.

---

## 미결 — 구현 전에 사용자에게 확인할 것

- [ ] **대본 종료(실제 8분) 후 미체결 주문의 회복 수단.** 지금 설계는 "재시작뿐"이다. spec §잔여 위험 1번.
- [x] ~~**완료 화면이 두 대본을 섞는 문제.** `entries[]`·`tradeResult`가 run 전체 집계라 2단계 진입
      (10만원대)과 3단계 진입(1만원대)이 한 배열에 들어간다. spec §미결 2번.~~ —
      **결정됨(2026-08-21, 사용자).** `entries[]`는 5-A로 해결(진입마다 `scenarioScriptId`를 싣는다).
      `tradeResult`(실행 세대 요약, 진입별이 아님)는 이번 결정의 대상이 아니다 — 계속 run 전체 합이다.
- [ ] **화면 게이팅(2단계는 시장가·지정가만, 3단계는 손절·익절만 활성) — 프론트 합의 필요.**
      서버는 판정(#503)과 거부(4번 항목)까지만 제공한다.
- [ ] **`advance-script`를 누가 언제 호출하는가**(사용자 버튼 vs 조건 충족 시 자동) — 프론트 합의 필요.
- [ ] **`scenarioStage`에 `"ORDER_BASICS"`라는 새 값이 생긴다** — 프론트가 알아야 한다.
