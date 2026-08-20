# Plan: 튜토리얼 2단계 — 주문 방법 학습 전용 대본과 단계 순서 강제

## 관련 문서

- Spec: `./spec.md`
- 첨부 대본 초안: `./scenario-crypto-orderbasics-v1.json` (아직 `src/main/resources`가 **아니다** —
  로더가 시장 하나당 대본 하나만 들 수 있어 등록 자체가 설계 변경이다, 아래 §1 참고)
- 상위 delta: `../041-tutorial-market-scenario`(대본 구조·커서·사건 공개), `../042-tutorial-exit-preset`
  (프리셋·자동 예약·도달 부등식), `../040-tutorial-restart-after-completion`(재시작 계약),
  `../047-tutorial-sandbox-cash-isolation`(튜토리얼 계좌)
- 선행 이슈: #479(왜 분리하는가), #503(단계 진행 판정 — **이미 구현됨**, 이번엔 소비만 한다)
- 계약 정본: `docs/api/education.md` §"실습 진행 조회", `ai/api-routes.md`
- 관련 ADR: ADR-0002(레이어드·도메인 경계), ADR-0003(테스트 전략), ADR-0004(Flyway),
  ADR-0021 §결정 7(파괴적 변경 2단계 배포)

**새 ADR은 필요 없다.** 대본은 041이 이미 "코드와 함께 배포되는 클래스패스 리소스"로 결정했고, 이번
변경은 그 리소스를 하나에서 둘로 늘릴 뿐이다. 저장 위치·생성기 버전 체계·커서 영속 방식을 바꾸지 않는다.

## 도메인 경계 (ADR-0002)

| 소유 | 무엇 |
|---|---|
| `market` | 대본 파일 적재·기동 검증, 대본 식별자 열거형, "(대본, 커서) → 가격" 순수 변환, 안내 범위 계산 |
| `education.marketpractice` | attempt가 어느 대본을 쓰는지 영속·해석, 커서 진행, 2→3단계 전환, 단계 순서 판정·거부 |
| `order` | 바뀌지 않는다. 체결·예약 규칙 그대로. 포트 시그니처 한 개만 인자가 는다(§4) |

---

## 1. 로더가 시장 하나당 대본 하나만 들 수 있다

### 현재

```java
private static final Map<Market, String> SCRIPT_RESOURCE_PATHS = Map.of(Market.CRYPTO,
    "/tutorial/scenario-crypto-v1.json");
```

`hasScript(Market)`·`script(Market)`가 이 키를 그대로 노출하고, `PracticeAttemptCanonicalPriceService.script(attempt)`가
`loader.script(attempt.getMarket())`로 부른다. CRYPTO 대본이 둘이 되면 이 키가 성립하지 않는다.

### 결정 — 대본 식별자 열거형을 도입한다

`market.service`에 `TutorialScenarioScriptId`를 새로 만든다.

| 식별자 | 파일 | 시장 | 용도 |
|---|---|---|---|
| `CRYPTO_ORDER_BASICS_V1` | `/tutorial/scenario-crypto-orderbasics-v1.json` | CRYPTO | 2단계(이번에 추가) |
| `CRYPTO_STORY_V1` | `/tutorial/scenario-crypto-v1.json` | CRYPTO | 3단계(기존, 파일 무변경) |

- 로더는 `Map<TutorialScenarioScriptId, TutorialScenarioScript>`를 들고 **둘 다** 기동 시 검증한다.
- `script(TutorialScenarioScriptId)`가 새 진입점이다.
- `hasScript(Market)`는 **그대로 남긴다.** `PracticeAttemptService.generatorVersionFor(market)`가 이것으로
  생성기 버전 2를 정하는데, 그 판정("이 시장에 대본이 있는가")의 의미는 달라지지 않는다. 구현은
  "그 시장의 대본이 하나라도 있는가"로 바뀐다.
- **시장별 시작 대본**을 `firstScriptId(Market)`로 노출한다 — CRYPTO는 `CRYPTO_ORDER_BASICS_V1`이다.
  종목 선택 시점에 attempt에 박힌다(§2).

### 구간 id를 건드리지 않는 것이 이 설계의 제약이다

로더 주석이 못박은 대로 **구간 id는 사실상 스키마**다(진행 중 attempt가 `practice_attempts.scenario_stage_id`에
그 리터럴을 들고 있다). 그래서 041 파일의 id 여덟(`IDLE_ENTRY`·`ACT1_RISE`·`ACT2_RUMOR`·`ACT2_FAKEOUT`·
`ACT2_CONFIRM`·`IDLE_REENTRY`·`ACT3_REBOUND`·`ACT4_CRASH`)을 그대로 두고, 새 대본은 어느 것과도 겹치지 않는
`ORDER_BASICS` 하나만 쓴다. `scenario_stage_id`는 `VARCHAR(32)`이고 `ORDER_BASICS`는 12자다.

> 구간 id 중복 검사는 **대본 파일 안**에서만 돈다(`validate`의 `HashSet<String> stageIds`). 두 대본 사이의
> 충돌은 로더가 잡아 주지 않으므로, id 유일성은 attempt가 정확히 하나의 대본만 가리킨다는 §2의 계약에
> 의존한다. 그럼에도 **겹치지 않는 이름을 고르는 것이 안전판**이다 — 두 대본에 같은 id가 있으면 대본
> 식별자가 잘못된 attempt가 500이 아니라 **엉뚱한 가격**을 조용히 받는다.

### `basePrice`를 대본 파일 필드로 옮긴다 (ORDERBASICS-003)

- `TutorialScenarioScript`에 `BigDecimal basePrice`를 더한다. 로더 검증에 `basePrice != null &&
  basePrice.signum() > 0`을 추가한다.
- `TutorialPriceGenerator`의 `STOCK_BASE_PRICE`·`CRYPTO_BASE_PRICE` 상수는 **생성기 버전 1 전용으로
  남긴다.** 버전 2 경로(`canonicalPrice(input, script, cursor)`)는 `script.basePrice()`를 쓴다.
- **041 파일에 `"basePrice": 10000.00000000` 한 줄을 같은 커밋에서 더한다.** 값이 현행 상수와 같으므로
  진행 중인 실행의 가격이 한 자리도 달라지지 않는다.

⚠️ **`generateHistory`도 같이 갈라야 한다.** 과거 29개 완결 일봉은 지금 `basePrice(market)`(CRYPTO 10,000)로
만들어진다. 그대로 두면 2단계 대본에서 **과거 봉은 9,000~11,000원인데 진행 중 봉만 88,000~112,000원**인
차트가 나온다 — 화면이 무너진다. 버전 2 경로의 `generateHistory`가 `script.basePrice()`를 받도록 오버로드를
더한다. 041 대본의 `basePrice`가 상수와 같은 값이라 **기존 실행의 29개 봉은 비트 단위로 동일**하다.

### 이 대본이 기동 검증을 통과하는 근거 (코드를 읽고 확인, 실행하지 않음)

`TutorialScenarioScriptLoader.validate`를 첨부 대본으로 한 줄씩 따라간 결과다.

| 검증 | 결과 | 근거 |
|---|---|---|
| `version == VERSION_2` | 통과 | 파일 `"version": 2` |
| `market == 파일 위치의 시장` | 통과 | `"market": "CRYPTO"`, CRYPTO로 등록 |
| `stages` 비어 있지 않음 | 통과 | 1개 |
| 구간 id 비어 있지 않음·중복 없음 | 통과 | `ORDER_BASICS` 하나 |
| `kind != null` | 통과 | `PROGRESS` |
| `minutes > 0` | 통과 | 160 |
| `ratios.size() == minutes` | 통과 | 160 == 160 |
| 모든 배율 > 0 | 통과 | 최솟값 `0.880000` |
| `LOOP`면 첫 배율 == 끝 배율 | **적용 안 됨** | `kind != LOOP`라 조건이 단락된다 |
| 마지막 구간이 `PROGRESS` | 통과 | 유일한 구간이 `PROGRESS` |
| 구간 경계에서 배율 연속 | **적용 안 됨** | 반복문이 `index < stages.size() - 1`이라 구간 1개면 0회 |
| 사건 검증 5종 | **적용 안 됨** | `events`가 빈 배열이라 반복문이 0회 |
| (신규) `basePrice > 0` | 통과 | `100000.00000000` |

**실제 기동으로 확인하지 않았다.** 구현 세션이 `./gradlew test`로 확인할 몫이다(tasks 1번).

---

## 2. attempt가 지금 어느 대본을 쓰는지 어떻게 아는가

### 판정에서 파생하지 않는다 — 컬럼을 둔다

`tutorialStageProgress`에서 파생하면(예: "지정가 왕복을 마쳤으면 3단계 대본") 사용자가 지정가 매도를
체결하는 **그 순간** 대본이 바뀐다. 그런데 그 순간 `practice_attempts.scenario_stage_id`에는
`ORDER_BASICS`가 살아 있고, 새 대본에는 그 구간이 없다 →
`TutorialScenarioScript.stage()`가 `IllegalArgumentException("대본에 없는 구간입니다")`를 던져
**조회·tick·주문이 모두 500**이 된다. 로더 주석이 경고한 바로 그 상태이고 회복 수단은 재시작뿐이다.

따라서 **대본 식별자는 커서만큼 내구적이어야 한다** — 커서 다섯 컬럼과 같은 행에 둔다.

### 마이그레이션 (ADR-0004)

`V53__add_scenario_script_id_to_practice_attempts.sql` — **현재 최고 번호가 V52이므로 V53이다.**

```sql
ALTER TABLE practice_attempts
  ADD COLUMN scenario_script_id VARCHAR(32) NULL COMMENT '이 실행이 쓰는 대본 식별자. NULL은 대본 미사용 또는 049 이전 실행';
```

- **추가만 하는 nullable 컬럼이라 파괴적 변경이 아니다**(ADR-0021 §결정 7의 2단계 배포 대상이 아니다).
  컬럼 삭제·이름 변경·NOT NULL 승격을 하지 않는다.
- 백필하지 않는다. **`NULL`의 해석 규칙을 코드에 둔다.**

### `NULL` 해석 규칙 (하위 호환)

| attempt 상태 | `scenario_script_id` | 해석 |
|---|---|---|
| 생성기 버전 1 (STOCK, legacy) | `NULL` | 대본을 쓰지 않는다. 지금과 동일 |
| 생성기 버전 2 + `NULL` (049 배포 전에 시작한 CRYPTO 실행) | `NULL` | **`CRYPTO_STORY_V1`으로 읽는다** — 그 사용자의 살아 있는 커서가 041 대본의 구간 id이므로, 그렇게 읽어야 500에 갇히지 않는다 |
| 생성기 버전 2 + 값 있음 | 그 값 | 그 대본 |

이 규칙은 `PracticeAttempt.scenarioScriptId()`(엔티티의 파생 접근자)에 한 곳만 둔다. 조회부가 각자
`null` 처리를 하면 한 곳을 놓쳤을 때 그 경로만 다른 대본을 본다.

### 값이 정해지는 자리

- `selectInstrument` — 생성기 버전 2면 `loader.firstScriptId(market)`(= `CRYPTO_ORDER_BASICS_V1`)를 박는다.
- `restart` — 커서 다섯 컬럼과 함께 `NULL`로 지운다. 다음 종목 선택이 다시 첫 대본을 박는다.
  ⚠️ **`clearScenarioProgress()`에 이 컬럼을 반드시 함께 넣는다** — 빠뜨리면 3단계에서 재시작한 사용자가
  2단계를 건너뛰고 041 대본으로 다시 시작한다.
- `advanceToStoryScript`(§3) — `CRYPTO_STORY_V1`로 바꾸고 커서를 비운다.

### 읽는 자리 (한 곳)

`PracticeAttemptCanonicalPriceService.script(attempt)`가 `loader.script(attempt.getMarket())` →
`loader.script(attempt.scenarioScriptId())`로 바뀐다. **가격·커서·사건 공개·복기 대조가 전부 이 메서드를
통과하므로 다른 호출부는 손대지 않는다.**

---

## 3. 2단계 → 3단계 전환

### 재시작을 재사용하지 않는다

`PracticeAttemptRestartService.restart`는 (a) `runNumber+1`, (b) 튜토리얼 계좌 현금·실현손익 리셋
(047 TUTORIAL-CASH-ISOL-006), (c) `exitPreset = null`, (d) 현재 run의 PENDING 지정가·예약 정리,
(e) 종목 미선택 상태로 되돌림을 한다.

`tutorialStageProgress` 세 값은 **현재 실행 세대**로 판정되므로(#503), (a)가 일어나는 순간 세 값이 전부
`false`가 된다. 즉 **3단계로 넘어갈 자격을 확인한 직후 그 자격을 지우는** 동작이다. 화면은 다시
"시장가부터 해 보세요"를 그리고, ORDERBASICS-015의 게이트가 지정가를 막는다. 쓸 수 없다.

### 같은 run 안에서 대본만 갈아끼운다

새 엔드포인트 `POST /api/education/practice/attempts/{market}/advance-script`.

수행 순서(하나의 트랜잭션, attempt를 먼저 비관 잠금 — 기존 경로와 같은 잠금 순서 `attempt → 튜토리얼 계좌`)

1. attempt 잠금. `COMPLETED`면 409 `PRACTICE_ALREADY_COMPLETED`.
2. 대본을 쓰지 않는 실행이거나 이미 `CRYPTO_STORY_V1`이면 409(§4의 새 오류 코드).
3. `tutorialStageProgress`의 `marketBuySellCompleted && limitBuySellCompleted`가 아니면 409.
4. `tradeService.netFilledQuantity(attemptId, runNumber).signum() > 0`이면 409 — **보유 중 전환 금지**
   (ORDERBASICS-020).
5. 현재 run의 PENDING 지정가 주문과 PENDING 예약을 정리한다. **재시작의 정리 경로
   (`PracticeRunRestartOrderService.cleanupCurrentRun`)와 같은 규칙을 쓰되 run을 올리지 않고 계좌를
   리셋하지 않는다** — 구현 세션이 그 서비스를 재사용할지 정리 부분만 뽑아 쓸지 판단한다.
6. `scenario_script_id = CRYPTO_STORY_V1`, 커서 다섯 컬럼 `NULL`(= 미시작). 다음 tick이 041 대본의
   `IDLE_ENTRY` 0분으로 커서를 세우고 진행 중 봉을 새로 연다.
7. 응답은 기존 `PracticeAttemptResponse`.

### 무엇에 영향이 있는가

| 대상 | 결과 | 왜 |
|---|---|---|
| `runNumber` | 그대로 | 2단계 완료 판정을 보존해야 게이트가 유지된다 |
| 튜토리얼 계좌 | **유지**(ORDERBASICS-019) | 2단계의 손익을 들고 3단계에 들어가는 것이 자연스럽다 |
| `tutorialStageProgress` | 시장가·지정가 `true` 유지 | 판정 범위가 run이고 run이 안 바뀐다 |
| `exitPreset` | 유지(선택했다면) | run 귀속 값이고 run이 안 바뀐다 |
| 완료 보상 | 영향 없음 | `practice_completions`의 `UNIQUE(user_id, tutorial_key)`는 run과 무관 |
| 진행 중 봉 3값 | 새로 열림 | 기준가가 10만 → 1만으로 바뀌므로 이어 붙이면 봉이 깨진다 |
| `entries[]`·`tradeResult` | **두 대본이 섞인다** | run 전체 집계다 — spec §미결 2번 |

---

## 4. 순서 강제 (ORDERBASICS-015)

### 판정 시점 — `lockForOrder` 하나

주문 생성 경로 셋이 **모두** 같은 포트를 통과한다.

| 경로 | 클래스 | 호출 |
|---|---|---|
| 시장가 (`POST /api/orders`) | `OrderExecutionService.execute` | `practiceOrderAttributionPort.lockForOrder(userId, instrument)` |
| 일반 지정가 (`POST /api/orders/limit`) — **튜토리얼 지정가 매수·매도가 여기로 온다** | `LimitOrderCreationService.execute` | 같음 |
| 030 가격 세션 지정가 (`POST /api/education/practice/limit-orders`) | `PracticeLimitOrderCreationService.createSessionBuyOrder` | 같음 |

구현체는 `PracticeAttemptOrderAttributionService.lockForOrder`이고, 첫 줄이
`if (!instrument.isTutorialSample()) return Optional.empty();`다. **실거래는 이 지점을 즉시 빠져나가므로
게이트 비용을 한 푼도 내지 않는다**(ORDERBASICS-016).

**그래서 게이트는 여기 하나에 둔다.** 세 호출부에 각각 검사를 넣으면 넷째 경로가 생길 때 조용히 새고,
`OrderService`·`LimitOrderService` 같은 위층에 두면 030 세션 경로가 빠진다.

### 포트 시그니처 변경

```java
Optional<PracticeOrderAttributionDto> lockForOrder(
    Long userId, Instrument instrument, OrderSide side, OrderType orderType);
```

`OrderSide`·`OrderType`은 `com.finplay.api.order.domain`이고 education 패키지가 **이미 둘 다
쓴다**(`OrderSide`는 `createRiskSnapshotOnBuyFill`, `OrderType`은
`PracticeStageProgressCalculationService`). 새 의존 방향이 생기지 않는다.

`side`는 지금 규칙에 쓰이지 않지만(§규칙 표는 주문 유형만 본다) 함께 넘긴다 — "매수는 되고 매도는 안 되는"
규칙이 필요해질 때 시그니처를 또 바꾸지 않기 위해서다. **쓰지 않는 인자를 넣는 것이 부담이면 구현
세션이 `orderType`만 넘기는 쪽을 골라도 된다.** 이 문서가 못박는 것은 "게이트는 `lockForOrder` 안에
있다" 하나다.

### ⚠️ 순환 참조 (042에서 실제로 겪은 것)

이미 있는 방향은 `PracticeOrderSettlementService`(order) → `LimitOrderFillService`(order) →
`PracticeOrderAttributionPort`(order에 선언, education이 구현) → education이다.

**포트에 새 메서드를 더해 order가 education에게 무언가를 되묻게 만들면 스프링 컨텍스트가 뜨지 않는다.**
이번 설계는 그것을 하지 않는다.

- 포트에 **메서드를 더하지 않는다**(기존 메서드의 인자만 는다).
- 게이트 판정은 **education 쪽 구현체 안에서** 끝난다 —
  `PracticeAttemptOrderAttributionService` → `PracticeStageProgressCalculationService` →
  (`TradeService`·`PracticeExitPlanQueryService`, 둘 다 order). education → order 방향이라 기존과 같다.
- `PracticeStageProgressCalculationService`는 **아무것도 저장하지 않는 조회 서비스**이고
  `PracticeAttemptOrderAttributionService`를 참조하지 않으므로 education 내부에도 고리가 생기지 않는다.

구현 세션은 이 판단을 **컨텍스트가 실제로 뜨는지로** 확인해야 한다(tasks 4번). 단위 테스트는 순환을
잡지 못한다.

### 오류 코드 — 새로 만든다

**결정: `ErrorCode`에 `PRACTICE_STAGE_LOCKED(HttpStatus.CONFLICT, "앞 단계를 먼저 마쳐야 합니다.")`를
더한다.** 기존 `PRACTICE_STEP_LOCKED`를 재사용하지 않는다.

근거는 **클라이언트가 다르게 다뤄야 하기 때문**이다. `PRACTICE_STEP_LOCKED`는 지금 일곱 자리에서
"attempt가 없다 / 종목이 안 맞는다 / run이 안 맞는다 / 완료 상태다 / 보유 중이라 프리셋이 잠겼다"를
전부 나타낸다 — 화면이 할 수 있는 일은 **새로고침·재시작 안내**다. 반면 이번 거부는 **정상 흐름 안의
예상된 안내**이고 화면이 할 일은 "먼저 시장가로 사고팔아 보세요"다. 같은 코드로 내려보내면 프론트는
두 상황을 메시지 문자열로 구분해야 한다.

- HTTP 상태는 둘 다 409다(요구사항의 "409로 막는다"를 만족한다).
- **어느 단계가 막혔는지는 오류 본문에 싣지 않는다**(ORDERBASICS-017). 그 정보는
  `GET /api/education/practice`의 `tutorialStageProgress`에 이미 있고, 두 곳에 두면 갈라진다.
- `ErrorCodeTest`의 상태 코드·메시지 표에 행을 더해야 한다.

### 프리셋 선택 경로

`PracticeAttemptService.selectExitPreset`은 포트를 타지 않으므로 **그 메서드 안에** 같은 판정을 넣는다.
기존 보유 중 잠금(`exitPresetLocked`)보다 **앞에** 둔다 — 두 조건이 동시에 걸릴 때 "앞 단계를 먼저"가
사용자에게 더 정확한 안내다.

### 규칙 표 (spec ORDERBASICS-015의 구현 형태)

```
gate(attempt, orderType):
  if !attempt.usesScenarioScript()            -> 통과 (STOCK·legacy)
  if orderType == MARKET                       -> 통과
  if orderType == LIMIT                        -> progress.marketBuySellCompleted 이어야 함
selectExitPreset(attempt):
  if !attempt.usesScenarioScript()            -> 통과
  else marketBuySellCompleted && limitBuySellCompleted 이어야 함
advanceScript(attempt):
  marketBuySellCompleted && limitBuySellCompleted 이어야 함
```

---

## 5. 대본 가격 안내 범위 (ORDERBASICS-009~011)

### 계산 (일반식 — 이 대본에 맞춘 상수가 아니다)

`market.service`의 순수 함수로 둔다(`TutorialScenarioPriceGenerator`와 같은 자리, 상태 없음).

```
min  = basePrice × min(모든 구간의 모든 배율)
max  = basePrice × max(모든 구간의 모든 배율)
span = max − min
margin = span × 0.05                                   # 극값에서 폭의 5%만큼 안쪽
unit   = 10^floor(log10(span × 0.10))                  # span의 10% 이하인 가장 큰 10의 거듭제곱
low  = ceil((min + margin) / unit) × unit              # 안쪽(위)으로 반올림
high = floor((max − margin) / unit) × unit             # 안쪽(아래)으로 반올림
if low >= high -> 범위 없음(null)                       # 폭이 너무 좁은 대본
```

`unit`을 상수 1,000으로 박지 않는 이유는 STOCK 대본(기준가 50,000)이나 다른 기준가가 들어올 때
1,000원 단위가 의미를 잃기 때문이다. `log10`은 부동소수를 타지만 결과는 **10의 거듭제곱 하나**이므로
`BigDecimal` 정밀도에 영향을 주지 않는다 — 실제 반올림은 `BigDecimal`로 한다.

### 이 대본의 값 (메인 세션이 배열을 생성해 확인)

| 값 | 결과 |
|---|---|
| 최저 배율 | `0.880000` → **88,000.00000000원** (주기의 15분, 전체 8회: 분 15·35·55·75·95·115·135·155) |
| 최고 배율 | `1.120000` → **112,000.00000000원** (주기의 5분, 전체 8회: 분 5·25·45·65·85·105·125·145) |
| span / margin / unit | 24,000 / 1,200 / 1,000 |
| **안내 범위** | **90,000원 ~ 110,000원** |
| 범위 안에 머무는 시간 | 한 주기당 상단 3분(분 4·5·6), 하단 3분(분 14·15·16) |

`TutorialScenarioPriceGenerator.canonicalPrice`가 `basePrice.multiply(ratio).setScale(8, HALF_UP)`이므로
기준가 `100000.00000000`에서 극값은 **정확히** `88000.00000000`·`112000.00000000`이 된다(반올림 오차 없음).

### 어디에 실리는가

`GET /api/education/practice/attempts/{market}/chart`와 `POST .../tick`의
`PracticeTutorialChartResponse`에 필드 하나를 더한다.

```json
"priceGuideRange": { "low": 90000.00000000, "high": 110000.00000000 }
```

- **사건이 하나라도 있는 대본은 `null`이다**(ORDERBASICS-011). 판정식은 `script.events().isEmpty()`.
  041 대본에서 이 값을 내리면 4막 폭락(0.79 → 7,900원)이 `low`로 새어 나가 SCENARIO-015·020을 뚫는다.
- 대본을 쓰지 않는 실행(생성기 버전 1·완료 replay)도 `null`이다 — 기존 네 필드
  (`scenarioStage`·`scenarioProgressing`·`causeStatus`·`revealedEvents`)와 같은 규칙이다.
- `GET /api/education/practice`는 **건드리지 않는다.** 지정가를 거는 화면이 차트 폴링을 이미 하고 있고,
  같은 값을 두 응답에 실으면 갈라진다.

---

## 6. API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | /api/education/practice/attempts/{market}/advance-script | 본문 없음 | `PracticeAttemptResponse` | 2단계 대본을 마친 실행을 같은 run 안에서 3단계 대본으로 전환 |
| GET | /api/education/practice/attempts/{market}/chart | 무변경 | `PracticeTutorialChartResponse` **+ `priceGuideRange`** | 사건 없는 대본에서만 안내 범위를 싣는다 |
| POST | /api/education/practice/attempts/{market}/tick | 무변경 | 같음 | GET chart와 동일 |
| POST | /api/orders · /api/orders/limit · /api/education/practice/limit-orders | 무변경 | 무변경 | **오류만 는다** — 앞 단계 미완료 시 409 `PRACTICE_STAGE_LOCKED` |
| PUT | /api/education/practice/attempts/{market}/exit-preset | 무변경 | 무변경 | 같은 409가 는다 |

## 7. 입력 명세

| 필드 | 필수 | 검증 |
|---|---|---|
| `advance-script`의 `market` (path) | 필수 | `STOCK`\|`CRYPTO`. 그 외 400 `VALIDATION_ERROR` |
| — attempt 존재 | — | 없으면 409 `PRACTICE_STEP_LOCKED`(기존 경로와 같은 처리) |
| — attempt 상태 | — | `COMPLETED`면 409 `PRACTICE_ALREADY_COMPLETED` |
| — 대본 사용 여부 | — | 생성기 버전 1이거나 이미 `CRYPTO_STORY_V1`이면 409 `PRACTICE_STAGE_LOCKED` |
| — 단계 진행 | — | 시장가·지정가 왕복 둘 다 아니면 409 `PRACTICE_STAGE_LOCKED` |
| — 보유 수량 | — | 현재 run 순보유수량 > 0이면 409 `PRACTICE_STAGE_LOCKED` |

**새 요청 DTO는 없다.** 본문 없는 POST이며 대상 대본을 클라이언트가 고르지 않는다 — 고르게 하면
사용자가 2단계를 건너뛰고 3단계로 갈 수 있고, 그것이 이 spec이 막으려는 것이다.

## 8. 데이터 모델

`practice_attempts`에 컬럼 하나(§2). **다른 테이블은 바뀌지 않는다.**

| 컬럼 | 타입 | NULL | 뜻 |
|---|---|---|---|
| `scenario_script_id` | `VARCHAR(32)` | 허용 | 이 실행이 쓰는 대본 식별자. `NULL`은 "대본 미사용" 또는 "049 이전 실행(= 041 대본)" |

- 인덱스를 더하지 않는다 — 이 컬럼으로 조회하지 않는다(attempt는 항상 `(user_id, market)` 또는 `id`로 찾는다).
- CHECK 제약을 걸지 않는다 — 값 집합이 열거형이고 대본이 늘어날 때마다 마이그레이션을 또 쓰게 된다
  (머지된 마이그레이션은 수정 금지, ADR-0004).

## 9. 대본 파일

`./scenario-crypto-orderbasics-v1.json` (spec 첨부물 → 구현 시 `src/main/resources/tutorial/`로 이동)

```json
{ "version": 2, "market": "CRYPTO", "basePrice": 100000.00000000,
  "stages": [ { "id": "ORDER_BASICS", "act": null, "kind": "PROGRESS", "minutes": 160,
                "ratios": [ ... 160개 ... ] } ],
  "events": [] }
```

- 배율은 `1 + 0.12 × sin(2π × 분 / 20)`을 소수점 6자리로 반올림한 **20개를 8번 반복**한다.
- 한 주기(분 0~19):
  `1.000000, 1.037082, 1.070534, 1.097082, 1.114127, 1.120000, 1.114127, 1.097082, 1.070534, 1.037082,
   1.000000, 0.962918, 0.929466, 0.902918, 0.885873, 0.880000, 0.885873, 0.902918, 0.929466, 0.962918`
- 한 바퀴 60초(`SECONDS_PER_VIRTUAL_MINUTE = 3` × 20분), 전체 480초, 왕복 8회.
- **대본이 끝나면(분 159) 커서가 멈추고 가격이 `0.962918` → 96,291.8원에 고정된다.**
  041 plan §상태 전이표 4행(FINISHED)의 동작이며 이 대본에서도 같다.
- `act`가 `null`이므로 `PracticeScenarioNarrativeCalculator.stageLabel`이 구간 id를 그대로 쓴다 →
  `scenarioStage`는 `"ORDER_BASICS"`, 대본 종료 후 `"FINISHED"`다. **041의 막 라벨과 겹치지 않는다.**
  프론트가 새 값 하나를 알아야 한다.
- `scenarioProgressing`은 항상 `true`(구간이 `PROGRESS`, 종료 후 `false`), `causeStatus`는 항상
  `"NONE_KNOWN"`, `revealedEvents`는 항상 `[]`다.

**Jackson은 3이다** — 빈으로 등록된 것은 `tools.jackson.databind.ObjectMapper`뿐이다. 로더가 그것을
주입받고 있으므로 새 파일도 같은 매퍼로 읽힌다. `com.fasterxml.jackson` 계열을 새로 import하지 않는다.

## 10. 테스트 계획 (ADR-0003)

**단위 테스트만으로 끝내지 않는다.** 이 spec 계열(041·042)에서 차단급 결함 다섯이 전부 통합 테스트나
블랙박스 QA에서만 드러났고 그때 단위 테스트는 모두 초록이었다.

- **기동 시점 검증** (`@SpringBootTest` 컨텍스트 로딩) — 대본 정합성은 `@DataJpaTest`가 잡지 못한다.
  로더가 두 대본을 모두 읽고 검증을 통과하는지, 깨뜨린 사본이 기동을 실패시키는지.
- **단위**
  - 안내 범위 계산 — 이 대본에서 90,000~110,000, 041 대본에서 `null`, 폭이 좁은 인공 대본에서 `null`.
  - 순서 강제 규칙표 — 주문 유형 × 진행 상태 조합.
  - 전환 서비스의 거부 조건 5가지.
- **회귀(가장 중요)** — 041 대본으로 진행 중이던 attempt의 **canonical 가격과 29개 과거 봉이 이번
  변경 전후로 동일**함을 `basePrice` 이관 전후 값 비교로 고정한다. 이 테스트가 없으면 §1의
  `generateHistory` 갈래가 조용히 틀려도 아무도 모른다.
- **슬라이스** — `@WebMvcTest`로 `advance-script`의 409 매핑과 `priceGuideRange` 직렬화(`null` 포함),
  `ErrorCodeTest`에 새 코드 행.
- **통합(Testcontainers)** — 두 시나리오.
  1. **미체결 → 취소 → 재접수 → 체결.** 시장가 왕복 → 지정가 열림 → 범위 밖(130,000원) 매도 접수 →
     여러 tick 동안 미체결 확인 → 취소 → 범위 안(108,000원) 재접수 → 다음 바퀴 안에 체결.
  2. **2단계 완주 → 전환 → 3단계 진입.** 시장가·지정가 왕복 → `advance-script` → `runNumber` 불변·
     튜토리얼 계좌 현금 유지·`tutorialStageProgress` 두 값 `true` 유지 → tick → 041 대본 `IDLE_ENTRY`
     가격이 나오는지 → 프리셋 선택이 이제 허용되는지.
- **컨텍스트 기동** — §4의 순환 참조 판단은 `@SpringBootTest`가 실제로 뜨는지로만 확인된다.

## 11. 문서 갱신 (CLAUDE.md 규칙 7·10)

- `ai/api-routes.md` — `advance-script` 행 추가, chart·tick 행에 `priceGuideRange` 명시.
- `docs/api/education.md` — **`scenarioStage` 허용값에 `ORDER_BASICS`를 더한다.** 2단계 대본의 유일한
  구간이 `id: ORDER_BASICS`·`act: null`이고 `PracticeScenarioNarrativeCalculator`가 `act`가 null이면
  구간 id를 그대로 라벨로 내보내므로, 지금 계약이 못박은 7개(`IDLE_ENTRY`\|`ACT1`\|`ACT2`\|
  `IDLE_REENTRY`\|`ACT3`\|`ACT4`\|`FINISHED`) 밖의 값이 나간다. `PracticeTutorialChartResponse`의
  javadoc도 같은 목록을 들고 있어 함께 고친다 — 빠뜨리면 프론트가 모르는 값을 받는다.
- `docs/api/education.md` — 실습 진행 조회·차트 절에 안내 범위와 순서 강제 409를 더한다.
  ⚠️ 지금 그 문서에 **"판정만 하고 강제하지 않는다"**가 적혀 있다(#503). 이번 변경이 그 문장을 뒤집으므로
  **반드시 같은 커밋에서 고친다.**
- `ai/prd.md` §3 — `TUTORIAL-STAGE-001` 행의 "미착수: 게이트 강제"를 갱신하고, 2단계 대본 분리에
  해당하는 행을 새로 더한다(판정·근거 PR 번호 포함).
