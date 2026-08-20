# Run Log: 041-tutorial-market-scenario

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 16:25 | reviewer(리뷰) | `git diff origin/dev...HEAD` (041 1~3번) | conventions.md, ADR-0002·0003·0004·0021, 041 spec·plan·tasks |
| 22:10 | 메인 세션 | `./gradlew test --tests "*Practice*" --tests "*Tutorial*" --tests "*Scenario*"` (041 4~5번) | 경량 경로 — 전체 빌드는 PR 직전 1회 |
| 22:34 | reviewer(리뷰) | `git diff origin/dev...HEAD` (041 4~5번, 브랜치 `feat/472-tutorial-progress-tick`) | conventions.md, ADR-0002·0003·0004, CLAUDE.md 3·6·7·10, agent-mistakes.md, 041 tasks·plan, `scenario-crypto-v1.json` |
| 22:34 | reviewer(리뷰, 순회 알고리즘 관점) | `git diff origin/dev...HEAD` (041 4~5번) | 041 plan §상태 전이표·§tick 알고리즘·§데이터 모델 |
| 22:34 | reviewer(리뷰, 회귀·통합 관점) | `git diff origin/dev...HEAD` (041 4~5번) | 가격 경로 전수 grep, ADR-0004·0021 §결정 7, api-contracts 대조 |
| 23:20 | reviewer(리뷰, 2차) | `git diff origin/dev...HEAD` + `git show d64b7ca6` | 1차 반영 재확인, 042 6번이 얹힐 자리, 테스트가 잡는 것 |

## 모니터링 (사람용 요약)
- 23:20 — 2차 리뷰(1차 반영 재확인): **차단 0건**, 권장 2건(문서 정합성), 참고 4건. 폴백 정산이 진입 있는 tick에서 이중으로 돌지 않음, `exitIdleLoop` 두 호출점의 규칙 일치, `step` 음수 방어의 커서 정리, 버전 판정 호출부 전수, 로더 새 규칙과 현행 대본, 문서 4종의 코드 일치를 각각 반례 시도로 확인했다.
- 22:34 — 041 4~5번 1차 리뷰 3건(관점 분리: 순회 알고리즘 / 회귀·통합 / 컨벤션·테스트). **셋이 각각 같은 차단 하나를 찾았다** — 진행 계산이 가상 분 진입 때만 정산해 대본 종료 후 PENDING 지정가가 영구 미체결. 추가 차단 1건은 문서(`api-contracts.md`가 generator version 2를 시장 구분 없이 서술). 순회 관점 리뷰어는 무한 루프·소비 초·`pricedAt` 단조성·봉 불변식을 반례 구성으로 검증해 전부 확인함으로 판정했다.
- 22:34 — 041 4~5번 리뷰(컨벤션·테스트 관점): 차단 1건(진행 계산이 가상 분 진입 때만 정산해 FINISHED 이후 PENDING 지정가가 영구 미체결), 권장 5건, 참고 8건. 레이어·V52·문서 동기화·Jackson 3은 문제 없음. 통합 테스트의 `9941.58`은 대본 배율로 검산해 4번째 분에서만 최초 충족(종점은 9750)임을 확인했다.
- 16:25 — 041 1~3번 리뷰: 차단 1건(대본 로더가 Jackson 2 `ObjectMapper`를 주입받아 Boot 4.1 컨텍스트에 후보 빈이 없음), 권장 2건. 대본 값·문안·도달 부등식·V50 마이그레이션은 문서와 일치.

## 041 4~5번 판정 기록 (이슈 #472, 2026-08-19)

plan이 "구현 착수 시 판정하고 run-log에 남긴다"고 지시한 두 항목이다.

### `progress_updated_at` — 새 컬럼 `scenario_progress_updated_at`(V52)

`updated_at` 재사용은 택하지 않았다. 그 컬럼은 재시작·완료·프리셋 선택(042 3번)·완료 replay 조정처럼
**대본 진행과 무관한 경로가 전부 갱신**하므로, 그런 요청 한 번이 사용자가 실제로 기다린 시간을 0으로
만들고 대본이 그만큼 뒤로 밀린다. 041 3번 세션도 별도 컬럼을 권했다(context-notes.md). 추가형 nullable
이라 ADR-0021 §결정 7의 2단계 배포 대상이 아니며, 대본 위치 다섯 컬럼과 함께 종목 선택·재시작에서
지워진다. 머지 직전 `git ls-tree origin/dev -- src/main/resources/db/migration`로 최고 번호가 V51임을
확인해 V52를 부여했다.

### `order` 인터페이스 — `canonicalPrice` 커서화 (오버로드 아님)

`LimitOrderFillService`에 가격을 받는 오버로드를 더하는 대안은 **문제를 옮기기만 한다.** 체인이
`fillIfPending(orderId, pricedAt)` → `lockForFill(attribution, pricedAt)` →
`canonicalPrice(attempt, pricedAt)`라서, 오버로드를 더해도 `lockForFill`이 여전히 시각에서 가격을
파생한다. 그 경로까지 함께 고쳐야 하고, 그러면 `order`의 공개 계약만 넓어지고 남는 이득이 없다.
커서화는 `PracticeAttemptCanonicalPriceService.canonicalPrice(attempt, observedAt)`의 시그니처를
유지한 채 내부 파생만 바꾸므로 `order`의 공개 계약이 그대로다 — 버전 2에서 `observedAt`이 무시될 뿐이다.

**대가는 순회 도중의 중간 커서가 보인다는 것**이고, tick이 attempt를 비관 잠금하고 있어 같은 attempt에
대한 다른 요청이 직렬화되므로 받아들였다. 오히려 이 성질이 SCENARIO-013을 가능하게 한다 — 커서를 밀고
정산을 부르면 체결이 그 분의 가격을 그대로 본다.

### plan과 다르게 구현한 것

- **`GENERATOR_VERSION`을 시장 조건부로 올렸다.** plan·tasks는 "2로 올린다"라고만 적었는데, 저작된
  대본이 `scenario-crypto-v1.json` 하나뿐이라 STOCK까지 2를 주면 모든 가격 조회가
  `IllegalArgumentException("대본이 저작되지 않은 시장입니다: STOCK")`으로 터진다 —
  `PracticeAttemptCompletionFlowIntegrationTest`의 STOCK 파라미터에서 실제로 재현했다.
  `TutorialScenarioScriptLoader.hasScript(market)`로 판정하므로 STOCK 대본(SCENARIO-024)이 들어오면
  자동으로 따라간다.
- **완료 replay attempt는 버전 1로 남겼다.** `initializeCompletedReplay`는 legacy completion만 있는
  사용자에게 만들어 주는 읽기 전용 실행이라 커서가 없고 tick도 돌지 않는다. 버전 2를 주면 대본 첫 구간
  0분에 고정된 평평한 차트가 되므로 기존 재현을 유지했다.
- **`virtualDateTime`은 두 버전 모두 `anchorAt` 기준 벽시계 파생을 유지했다.** plan은 이 값의 버전 2
  정의를 정하지 않았다. 커서에서 파생하면 대기 루프에서 시계가 되감기고, 별도 누적 컬럼을 두는 것은
  이 항목의 범위를 넘는다. 결과적으로 clamp된 만큼 표시 시계가 커서보다 앞설 수 있으며
  `docs/api-contracts.md`에 적었다. 사건의 `revealedAtVirtualMinute`을 내리는 6번이 이 값을 다시 볼 수
  있다.
- **버전 2 차트의 과거 29봉은 버전 1과 같은 seed 생성을 쓴다.** plan §대본 설계가 "과거 29개 완결
  일봉은 대본 대상이 아니다"라고 적은 것을 그대로 따랐고, `TutorialPriceGenerator.generateHistory`를
  두 버전이 공유하도록 뽑아 V1 golden vector가 무변경으로 통과한다.

### 042 6번에 남긴 자리

진행 계산은 가상 분마다 `PracticeOrderSettlementService.settleCurrentRun(attemptId, runNumber,
pricedAt)` **하나만** 부른다. 042의 OCO 정산 루프를 그 메서드 안에 얹으면 지정가와 OCO가 같은 가상 분에
같은 순서로 판정된다 — 진행 계산 쪽은 손대지 않아도 된다.

## 2차 리뷰가 남긴 것 (다음 작업으로 넘김)

차단은 없었고, 아래 셋은 **041 6번과 042 6번이 각각 받아야 할 항목**이다.

- **대기 루프 되감기 지점의 체결도 한 tick 밀린다 (041 6번).** 순회 도중 체결 경로는 이번에 통일했지만,
  구간 끝에 닿아 0으로 되감는 `enterMinute`에서 체결되면 그때 `remaining`이 0이라 탈출이 다음 tick으로
  간다. 시간 손실은 없고 LOOP은 첫·끝 배율이 같아 가격도 튀지 않는다. **다만 6번이
  `scenarioProgressing`("대기 중 / 진행 중")을 응답에 싣기 시작하면 이 한 tick이 화면에 보인다** — 그때
  함께 본다.
- **대기 탈출 tick은 같은 `pricedAt`으로 정산을 두 번 부른다 (042 6번).** 대기 구간의 체결 분에서 1회,
  이동 직후 진행 구간 0분에서 1회다. 커서와 가격은 다르고 시각만 같다. 지정가는 `fillIfPending`의 PENDING
  재확인으로 멱등이라 지금은 무해하지만, **OCO 정산을 `settleCurrentRun` 안에 넣으면 같은 가상 시각이 두
  번 판정되고 두 체결의 `executedAt`이 같아진다.** 042가 진입 시퀀스나 체결 순서를 `executedAt`으로
  정렬한다면 tie-break(주문 id)를 함께 정한다.
- **버전 1 attempt 경로의 5분 만료를 통합으로 검증하는 자리가 없어졌다.** `TutorialSandboxPracticeIntegrationTest`의
  만료 시나리오는 attempt를 만들지 않는 legacy chain이라 이번 변경의 영향을 받지 않는 것을 확인했다(회귀
  없음). attempt 경로의 `EXPIRED`는 단위 테스트로만 남는다 — STOCK 대본(SCENARIO-024)이 들어오면 그
  경로 자체가 사라지므로 지금 추가하지 않았다.

## 041 6~7번 판정 기록 (이슈 #488, 2026-08-20)

### 사건에 시각을 붙이지 않는다 — plan과 다르게 간 자리

plan §API 계약 변경은 사건 항목을 `{headline, revealedAtVirtualMinute}`으로 적었다. **구현하지 않았다.**
그 분 값은 원점이 있어야 뜻이 생기는데, 대본 커서(구간·구간 내 분)와 응답의 `virtualDateTime`(`anchorAt`
기준 벽시계 파생)이 서로 다른 시계라 원점을 정하는 순간 두 시계를 맞추는 문제로 되돌아온다. 같은 구간
안의 사건이라면 `현재 분 − 공개 분`이 정확하지만, 손절 후 대기 구간을 거쳐 온 사용자에게는 그 값이 실제
경과와 무관해진다 — **일부에만 정확한 숫자를 내려보내는 것이 아예 안 주는 것보다 나쁘다.**

대신 목록 순서가 공개 순서(대본 구간 순서)이며 마지막 항목이 가장 최근 공개다. 화면은 "방금"·"조금 전"
같은 상대 표현으로 그린다. **spec SCENARIO-020의 "같은 가상 시간축"은 이 결정으로 완화됐다** — 사용자와
합의한 방향이며, 두 시계를 맞추는 작업은 이 spec의 범위 밖이다.

### `causeStatus`는 막이 아니라 대본 구간으로 판정한다

구현 중 발견한 자기모순이다. `scenarioStage`는 act 단위로 노출하는데(2막이 셋으로 쪼개진 것은 대본의
사정) 그 라벨로 원인 상태까지 판정하면, **루머의 원인이 열린 뒤 2막-b 속임수 반등 구간에서도
`REVEALED`가 된다.** plan §사건 배치가 "여기에는 사건을 붙이지 않는다 — 사용자가 가장 속기 쉬운 자리에
설명이 없다는 것 자체가 SCENARIO-005가 가르치려는 내용"이라고 못박은 구간이 통째로 무의미해진다.
`PracticeScenarioNarrativeCalculatorTest`에 회귀를 넣었다.

### 공개 판정에는 clamp하지 않은 분을 쓴다

가격용 커서는 마지막 구간을 다 쓰면 마지막 분으로 clamp된다(FINISHED에서 마지막 가격 유지, plan §상태
전이표 4행). 그 값을 공개 판정에 그대로 쓰면 **마지막 구간의 사건이 영영 열리지 않는다.** 두 용도를
분리했다.

### `priceAfterSell`의 기준이 완료 여부로 갈린다

- **진행 중**: 현재 대본가. 종점 가격을 내려보내면 4막 폭락을 2막에서 미리 알려주는 셈이라 사건 노출
  게이트(SCENARIO-015)보다 큰 누설이 된다.
- **완료**: 대본의 마지막 진행 구간 끝 가격. 커서를 쓰면 손절 뒤 재매수하지 않고 나간 사용자의 대조가
  자기 매도가와 거의 같아져 항등식이 된다 — spec이 2026-08-18에 초판을 정정한 바로 그 이유다.

### `PostSellArithmetic` 재사용은 불가능했다

plan §"안 팔았다면" 선은 "공식은 `PostSellArithmetic`을 재사용한다"고 적었지만 그 클래스는 feedback
패키지 전용(package-private)이고 다른 도메인이다. `PracticeTradeResultCalculator`가 이미 같은 이유로
"식·정밀도만 맞춘다"는 선례를 남겼고 그대로 따랐다. **시장별 수수료율 상수가 이 레포에서 다섯 번째
복제가 됐다**(`OrderExecutionService`·`PostSellArithmetic`·`PracticeRunRestartOrderService`·
`LimitOrderFeeCalculator`). 한곳으로 모으는 것은 이 PR의 범위를 넘어 손대지 않고 출처를 주석에 적었다.

### 진입별 배열의 모양

042 tasks 7번이 요구한 것(`entrySequence`·`exitPreset`·매수가·수량·매도가·매도 시각·`sellCause`·
`realizedPnl`)에 041이 요구한 `unrealizedPnlIfHeld`를 얹고, SCENARIO-019b의 "진입마다 **기준선**·매도가·
매도 원인을 나란히"를 위해 `stopLossPrice`·`takeProfitPrice`를 더했다.

- **경계는 위험 snapshot의 매수 체결 id다.** "몇 번째 진입인가"는 education이 소유하는 개념이라
  `TradeService.summarizePracticeRunEntries`는 경계 id만 받아 자기 원장을 나눈다.
- **집계는 `summarizePracticeRun`과 같은 코드다.** 두 벌이면 진입별 합과 실행 전체 합이 조용히 어긋난다.
- **진입 안에서는 첫 매도를 쓴다** — 실행 전체가 첫 매도를 쓰는 규칙을 진입 범위로 좁힌 것이다.
- **대본 여부와 무관하게 채운다.** 042의 재진입은 시장을 가리지 않으므로 버전 1 실행에도 같은 결함이 있다.

### 남긴 것

- **STOCK 대본(SCENARIO-024).** 계약은 시장 중립이라 대본 파일만 추가하면
  `TutorialScenarioScriptLoader.hasScript`가 버전 전환을 자동으로 따라간다.
- **041 tasks §"사람이 직접 확인할 것" 4항목.** 자동 테스트로 대신할 수 없어 배포본에서 눈으로 본다.
- **2차 리뷰가 남긴 "대기 루프 되감기 지점의 체결이 한 tick 밀린다"**(041 4~5번 run-log). 이번에
  `scenarioProgressing`을 응답에 싣기 시작했으므로 화면에 보일 수 있는 자리가 됐다. 통합 완주에서
  재현되지 않았고 LOOP은 첫·끝 배율이 같아 가격도 튀지 않아 이번 범위에서는 손대지 않았다.
