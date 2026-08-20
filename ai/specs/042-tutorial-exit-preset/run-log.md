# Run Log: 042-tutorial-exit-preset

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 2026-08-19 19:45 | 메인 세션(직접 구현) | `./gradlew compileJava`, `./gradlew test --tests "*ExitPreset*" --tests "*ReferencePriceCalculator*" --tests "*PracticeAttempt*" --tests "*ExitPlan*"` | 042 tasks 1·2번, 041 tasks §교차 순서 4번 |
| 2026-08-19 19:53 | reviewer(리뷰) | `git diff origin/dev...HEAD` | 042 spec·plan·tasks, 041 plan §프리셋 도달 조건 검증, conventions.md, ADR-0002·0003·0004·0021 |
| 2026-08-19 20:10 | reviewer ×3(리뷰) | `git diff origin/dev...HEAD` — 반영 재검증 / 결함 사냥 / 다음 세션 관점 | 042·041 spec·plan·tasks, conventions.md, ADR-0002·0003·0004·0021 |
| 2026-08-19 20:40 | 메인 세션 | `./gradlew build` | PR 전 전체 검증 (git-conventions §머지 조건) |

## 모니터링 (사람용 요약)
- 19:45 — 042 1·2번 구현(이슈 #470). 프리셋 상수·계산 / V51 마이그레이션·엔티티 두 커밋.
- 19:53 — 리뷰: 차단 0건, 권장 3건, 참고 7건. 머지 가능.
- 20:05 — 권장 3건 + 손댈 수 있는 참고 3건 반영(왕복 테스트 `@EnumSource`, FK 자동 인덱스 부재 단언, 체결가 scale 8 선정규화, 현행 상수 직접 참조, 테스트 이름 정정, 탐색 루프 범위 방어).
- 20:10 — 2차 리뷰 3종: 차단 0건. V51 주석의 "컬럼을 읽지도 쓰지도 않는다"와 orders 인덱스 근거가 틀린 것을 잡아 실측으로 정정했고, 042 5번이 밟을 정규화 비대칭 함정을 tasks 5번·javadoc에 박았다.
- 20:15 — 문서와 다르게 간 판단(부등식 판정 위치, CHECK·인덱스 추가, 표시 이름 미구현)은 `tasks.md` 각 항목의 인용구와 V51 주석에 적었다 — 이 파일은 한 줄 요약만 둔다(`specs/README.md`).
- 20:40 — `./gradlew build` **BUILD SUCCESSFUL** (4,609건, 실패·오류 0건). 검증 SHA `73bdc9d9`. PR #471.

## 042 3~7번 판정 기록 (이슈 #477, 2026-08-20)

### 순보유수량 판정을 실행 세대 범위로 바로잡았다 — 가장 중요한 정정

plan §자동 예약 생성은 "직전 순보유수량 = 현재 순보유수량 − 이번 체결 수량"이라고만 적었고, 구현 초안은
그 "현재 순보유수량"을 `holdings` 행의 수량으로 읽었다. **틀렸다.** 그 값은 실행 세대를 넘어 누적되므로,
재시작이 청산하지 못한 이전 보유(attempt 도입 전에 만들어진 holding 등)가 남아 있으면 **새 실행의 첫
매수인데도 "이미 들고 있다"로 판정돼 위험 snapshot이 아예 만들어지지 않는다.**
`PracticeAttemptRestartRecompletionIntegrationTest`가 재현했다 — 단위 테스트는 전부 초록이었다.

`TradeService.netFilledQuantity(attemptId, runNumber)`(스칼라 집계 한 줄)로 바꿨고, **042의 프리셋 잠금·
진입당 1회 가드와 041의 대기 구간 탈출 판정이 이 한 메서드를 공유한다** — plan이 "구현에서 한 메서드로
모은다"고 요구한 자리다. 041이 만든 `HoldingService.findNetQuantity`는 이 변경이 만든 고아라 함께 지웠다.

### 순환 참조 — 매도 전 예약 취소를 포트에 두면 안 된다

"튜토리얼 매도 접수 전 PENDING 예약 취소"를 `PracticeOrderAttributionPort`에 두었더니
`PracticeOrderSettlementService` → `LimitOrderFillService` → 포트 → 정산 서비스로 순환 참조가 되어
**Spring 컨텍스트가 아예 뜨지 않았다.** 호출부(`OrderExecutionService`)가 `lockForOrder`로 이미 받은
귀속 정보(attemptId·runNumber)를 그대로 쓰도록 바꿔 포트를 넓히지 않았다.

### 귀속 컬럼의 애플리케이션 레벨 검증 — 두기로 했다

plan이 "5번에서 판정하라"고 남긴 항목이다. `ExitPlan.createPractice`가 두 귀속 값(attempt id·양의 실행
세대 번호)을 검증한다 — `Order.createForPracticeAttempt`의 `validatePracticeAttemptAttribution`과 대칭이다.
두지 않으면 이 불변식을 지키는 것이 DB CHECK 하나뿐이고, 위반이 트랜잭션 커밋 시점에야 드러난다.

### 진입별 대조 배열은 041 6번으로 넘겼다

같은 배열에 041이 `unrealizedPnlIfHeld`·`priceAfterSell`을 얹도록 되어 있어 042가 혼자 모양을 정하면
두 번 고쳐야 한다. **새 이슈를 만들지 않았다** — 041 tasks 6번이 이미 그 배열을 명시하고 있어, 같은 일을
두 문서에 적으면 한쪽만 고쳐도 초록이 남는다(042 1번의 도달 부등식에서 겪은 것과 같은 문제다).

넘긴 대가: 그때까지 재진입한 사용자의 완료 화면은 첫 매도만 가리킨다. **금액은 맞다** — 틀리는 것은
"무슨 일이 있었는가"이며 2막 손절 → 3막 익절이 손절 하나로 보인다.

### 응답 계약에서 정한 것

- **`exitPresetLocked`에는 기본값을 두지 않았다.** 잠금 여부는 순보유수량을 조회해야 알 수 있고, 잘못
  false로 내리면 클라이언트가 바꿀 수 없는 컨트롤을 열어 준다. 튜토리얼 계좌 잔고 3필드가 "이 호출부는
  모른다"로 0을 내리는 것과 성격이 다르다 — 컴파일러가 호출 지점 8곳에서 판단을 강제하게 했다.
- **`selectedExitPreset`은 미선택이어도 `null`이 아니라 `BALANCED`다**(EXITPRESET-002). 클라이언트가 null
  분기를 갖지 않고, 화면에 보이는 값과 실제로 적용될 값이 같아야 한다.
- **프리셋 표시 이름은 여전히 서버가 주지 않는다.** 042 1번이 남긴 "3번에서 정한다"에 대한 답이며,
  `availableExitPresets`가 식별자·비율만 내려보내고 문구는 클라이언트가 갖는다.

## 사전 리뷰가 잡은 차단 둘 (이슈 #477, 2026-08-20)

관점을 나눈 리뷰어 둘(예약 원장·잠금 순서 / 회귀·계약)이 **독립적으로 같은 차단**을 냈다. 둘 다 통합
테스트 부재를 원인으로 지목했고, 실제로 단위 테스트는 전부 초록이었다.

### 차단 1 — OCO 체결이 만드는 매도 주문에 attempt 귀속이 없었다

`ExitPlanFillService.executeMarketSell`이 `Order.create(...)`로 주문을 만들어
`practice_attempt_id`가 null이었다. 042의 판정이 **전부** `orders.practice_attempt_id`로 실행 세대를
좁히므로, 자동 청산 매도가 원장에서 통째로 빠진다. 결과는 연쇄적이다.

- 순보유수량이 매수분 그대로 남아 **프리셋이 영구 잠긴다**(EXITPRESET-003 불성립)
- `heldBeforeThisFill > 0`이라 **재매수에 새 기준선도 새 예약도 생기지 않는다**(EXITPRESET-017 불성립)
- `firstSellTrade == null`이라 **`sellCause`가 `STOP_LOSS`가 되는 경로가 없다**(EXITPRESET-008 사문화)
- 재시작이 순체결수량과 holding 잔량 불일치로 **영구히 409** — 손절당한 사용자에게 탈출 경로가 없다
- 041의 대기 구간 판정도 "계속 보유 중"으로 굳어 재진입 대기 구간이 사라진다

**이 결함은 이 PR이 만들었다.** 순보유수량을 holding 행에서 실행 세대 체결 원장으로 옮기면서, 귀속 없는
매도가 존재한다는 사실과 부딪혔다. holding 기준이었다면 드러나지 않았을 것이다 — 대신 그쪽에는 이전
실행의 잔여 보유를 세는 결함이 있었다(그것도 통합 테스트가 잡았다). **두 판정 모두 구멍이 있었고, 올바른
해법은 자동 청산 매도에 귀속을 붙이는 것이다** — 그러면 043의 attempt 주문 목록에도 정상적으로 나타난다.

### 차단 2 — 튜토리얼 지정가 매도가 예약 수량에 막혔다

EXITPRESET-016의 "매도 접수 전 예약 취소"를 시장가 경로에만 넣었다. 043이 튜토리얼 지정가 예약 카드를
계약으로 갖고 있어 그 경로가 실제로 쓰이고, 전량 예약 상태에서 `availableQuantity`가 0이라 409가 난다.
`LimitOrderCreationService.createSellOrder`에도 같은 취소를 넣었다.

### 함께 반영한 것

- **튜토리얼 자동 예약이 일반 OCO 화면에 새어 나갔다.** 042 전에는 `ExitPlanService.create`가 샌드박스를
  막아 튜토리얼 `exit_plans` 행 자체가 없었는데 이 PR이 처음 만든다. `GET /api/exit-plans`에서 제외하고
  `DELETE`도 거부한다 — 차단을 엔진이 아니라 호출부에 두는 것은 047·021 RISK-OCO-014와 같은 이유다.
- 회귀 방어로 `PracticeExitPresetOcoIntegrationTest`를 넣었다. 매수 → tick 손절 체결 → 원장 3종 확인 →
  프리셋 재선택 → 재매수(새 진입) → 재시작까지 한 번에 돌고, **전량 예약 상태의 수동 매도**도 함께 본다.

## PR #487 리뷰 반영 (차단 1건 / 권장 3건)

### 차단 3 — 튜토리얼 자동 예약이 실시간 시세 피드로 체결됐다

리뷰 QA가 블랙박스로 3회 재현했다. CRYPTO 샌드박스 종목을 매수하면 **tick을 한 번도 부르지 않았는데
2~3초 뒤 예약이 `FILLED_TAKE_PROFIT`으로 체결**되고, 체결가가 `entryPrice`와 무관한 값이었다.

원인은 `ExitPlanRepository.findPendingExitPlansToFill`이 **종목 단위로만** 후보를 고른 것이다. 경로를
추적하면 이렇다.

1. `BithumbFeedSimulator`(`@Profile("!prod")`)가 3초마다
   `findByMarketAndTradableTrueOrderByIdAsc(CRYPTO)`로 코인 종목을 훑는데, `SANDBOX_COIN_1`도
   `market=CRYPTO`·`tradable=true`라 **함께 잡힌다**.
2. 그 종목에 10만~1000만원 범위의 합성 틱이 들어가 `PriceStore.saveTick` → `CryptoPriceUpdatedEvent` 발행.
3. `ExitPlanTriggerListener`가 그 심볼의 instrument를 찾아 `findPendingExitPlansToFill`을 부르고,
   튜토리얼 예약(익절선 1만원대)이 후보로 걸려 즉시 체결된다.

**같은 위험을 지정가 주문은 이미 막고 있었다.** `findPendingLimitOrdersToFill`에는
`practicePriceSessionId is null and practiceAttemptId is null`이 있고 주석이 "030 역방향 오염 차단 — 실제
빗썸 시세 tick이 교육 주문을 체결하지 않는다"라고 적혀 있다. **이 PR이 OCO 예약이라는 새 트리거 대상을
만들면서 같은 방어를 복제하지 않은 것**이 결함의 실체다.

`findPendingExitPlansToFill`에 `p.practiceAttemptId is null`을 더했다. 종목(`isTutorialSample`)이 아니라
**귀속**으로 거르는 이유는 지정가 쪽 선례와 같고, 불변식 자체가 "교육 예약은 전용 경로로만 체결된다"이기
때문이다. 튜토리얼 예약은 `PracticeOrderSettlementService.settleCurrentRun`이 attempt·실행 세대로 좁힌
`findPendingPracticeRunExitPlanIds`로 따로 읽으므로 **canonical 경로는 영향을 받지 않는다**(이 쿼리의
production 호출부는 `ExitPlanTriggerListener` 하나뿐임을 확인했다).

**prod에서는 증상이 달랐을 것이다.** 시뮬레이터가 꺼지고 실제 빗썸 피드는 `SANDBOX_COIN_1` 심볼을
보내지 않으므로 오체결은 나지 않는다. 다만 그것은 "실제 피드에 그 심볼이 없다"는 **우연**에 기댄 것이라
불변식이라 부를 수 없고, 데모·로컬은 팀이 실제로 시연하는 환경이다. 그래서 프로필과 무관하게 막는다.

`ExitPlanRepositoryTest`에 회귀 2건을 넣었다 — 귀속된 예약이 손절·익절 양방향 가격에서 모두 제외되는 것,
같은 종목에 일반 예약이 섞여 있으면 **일반 예약만** 후보가 되는 것.

### 함께 메운 커버리지 공백

리뷰가 지적한 대로 `ExitPlanService.list()`의 튜토리얼 제외 필터와 `cancel()`의
`EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED` 거부에 테스트가 없었다(동작 자체는 QA에서 정상 확인). 두
동작 모두 042 5번이 요구하는 안전장치라 `ExitPlanServiceTest`에 단위 테스트 5건을 넣었다 — 필터 2건,
취소 거부·미존재·정상 위임 3건.

### 권장 3건

1. 042 tasks 8번을 완료로 표시하고, `PracticeExitPresetOcoIntegrationTest`의 재예약 단언에 **가격**을
   더했다. 기존에는 예약 개수(`hasSize(1)`)만 봐서 프리셋이 BALANCED로 굳어 있어도 통과했다.
2. `ai/prd.md` EXITPRESET 행 근거에 `PR #487`을 더했다.
3. 실재하지 않는 메서드명 `Order.createPracticeFilled` → `Order.createForPracticeAttempt`로 정정
   (`ExitPlan.java` 주석, 042 tasks.md, 042 run-log.md 3곳).

### 남긴 것 — 시뮬레이터의 샌드박스 종목 오염

`BithumbFeedSimulator`가 샌드박스 종목에도 합성 틱을 넣어 `price:crypto:SANDBOX_COIN_1` 키를 계속
갱신하는 것 자체는 그대로 뒀다. 이 PR의 차단은 소비 측에서 닫혔고, 다른 소비자는 이미 각자 막고 있다 —
`PriceQueryService`는 샘플 종목을 `TutorialSampleInstrumentPriceService`로 우회하고(031 SANDBOX-003),
`CryptoPriceMoveWatcher`는 `getRealInstrumentEntities`로 제외한다(이슈 #406). 생산 측을 고치는 것은 이
PR의 범위 밖이라 별도 이슈로 남긴다.
