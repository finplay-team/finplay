# Plan: 튜토리얼 손절·익절 프리셋 — CRYPTO 자동 예약(OCO) 연동

## 관련 문서

- Spec: `./spec.md` (2026-08-18 개정판)
- 병행 spec/plan: `../041-tutorial-market-scenario` — 대본과 구간 종류 규칙. 이 plan의 프리셋 수치는
  041 plan §프리셋 도달 조건 검증의 도달 부등식으로 정당화된다.
- Delta 정본: `../039-tutorial-flow-redesign`, `../021-general-risk-management-oco`,
  `../019-exit-price-policy`, `../026-market-order-practice-tutorial`
- 관련 ADR: ADR-0002(레이어드), ADR-0003(테스트 전략), ADR-0004(Flyway),
  ADR-0012(favorite·legacy intention 인메모리 경계), ADR-0021(배포·파괴적 변경 2단계)

**새 ADR은 필요 없다.** 프리셋 선택값을 attempt 행에 두는 것은 039가 attempt 상태를 DB에 둔 것과 같은
근거이며 ADR-0012가 인메모리로 정한 대상(favorite, legacy intention)을 건드리지 않는다.

## 도메인 경계

- `education.marketpractice`가 프리셋 선택·검증·snapshot 계산을 소유하고, 매수 체결 트랜잭션에서 `order`의
  OCO 생성 서비스를 **호출**한다.
- `order`가 OCO의 예약·체결·취소 규칙을 계속 소유한다. 이 plan은 그 규칙을 바꾸지 않고 **호출부와 귀속
  컬럼만 더한다.**
- `education`은 `order`의 repository를 직접 주입하지 않는다(039가 세운 경계 유지). tick 정산이 exit plan을
  훑는 것도 `order` 안에서 일어난다.

## 프리셋 수치

| 식별자 | 표시 이름 | 손절률 `s` | 익절률 `t` |
|---|---|---:|---:|
| `CAUTIOUS` | 조심스럽게 | 2% | 3% |
| `BALANCED` | 보통 | 3% | 5% |
| `RELAXED` | 느긋하게 | 5% | 8% |

**`BALANCED`가 기본값이며 현행 -3%·+5%와 정확히 같다**(EXITPRESET-002). 아무 조작도 하지 않은 사용자의
결과가 이 기능 도입 전과 동일해야 한다는 요구가 이 값을 고정한다.

**나머지 두 값의 근거는 041 대본(강화안)과의 도달 조건이다.** 041 plan §프리셋 도달 조건 검증이 세
프리셋 전부에 대해 부등식을 확인했다. 요약하면 이렇다.

- `t`의 하한은 **1막에서 익절이 터지면 안 된다**가 정한다(터지면 2막 손절 학습을 통째로 못 한다).
  1막 고점이 배율 1.018이고 진입가가 최저 0.998이므로 `0.998 × (1+t) > 1.018`, 즉 `t > 2.0%`다.
  `CAUTIOUS`의 3%가 이 하한 바로 위다.
- `t`의 상한은 **3막에서 익절이 터져야 한다**가 정한다. 3막 고점 1.010, 재진입가 최대 0.872이므로
  `t < 15.8%`로 여유가 크다. 실질 상한은 그쪽이 아니라 **익절 후 추가 상승이 과도하지 않아야 한다**
  (SCENARIO-006)가 정하며, `RELAXED`의 8%에서 7.7%p다.
- `s`의 상한은 **4막에서 손절이 터져야 한다**가 정한다. 4막 저점 0.790, 재진입가 최소 0.868이므로
  `0.868 × (1−s) > 0.790`, 즉 `s < 9.0%`다. `RELAXED`의 5%가 이 안이다.
- **`s`의 세 값을 1%p 간격으로 벌린 이유는 2막 루머 분기다**(041 SCENARIO-006a). 루머 저점 0.975가
  `CAUTIOUS` 손절선 구간 `[0.97804, 0.98196]`과 `BALANCED` 손절선 구간 `[0.96806, 0.97194]` 사이에
  들어가야 하고, 세 구간이 서로 겹치지 않아야 한다. **간격을 좁히면 이 분기가 진입가 편차에 먹혀
  무너진다** — 프리셋을 고른 의미가 결과로 드러나는 유일한 자리이므로 이 간격은 임의 조정 대상이 아니다.

즉 **세 값은 임의로 고른 것이 아니라 대본이 허용하는 구간의 양 끝과 가운데이며, 간격은 루머 분기가
정한다.** 대본을 손보면 이 구간이 움직이므로, 041 plan의 부등식 테스트가 두 문서를 함께 잠근다.

계산은 `ReferencePriceCalculator.calculateFromPercent(entryPrice, stopLossRate, takeProfitRate)`를 쓴다.
`019` 공식대로 이미 구현돼 있고(중간 계산 `MathContext.DECIMAL128`, 최종 가격만 scale 8 `HALF_UP`),
`s=3`·`t=5`를 넣으면 현행 `×0.97`·`×1.05`와 정확히 같은 값이 나온다. **이 메서드는 지금까지 production
에서 호출되지 않았고 이 기능이 처음 실사용한다.**

## 데이터 모델

### 추가형 변경

| 테이블 | 컬럼 | 타입 | 의미 |
|---|---|---|---|
| `practice_attempts` | `exit_preset` | `VARCHAR(20) NULL` | 현재 실행 세대의 선택값. null이면 미선택(= `BALANCED`) |
| `practice_risk_snapshots` | `exit_preset` | `VARCHAR(20) NULL` | 확정 시점에 적용된 프리셋. 기존 행은 null(= 기본으로 해석) |
| `practice_risk_snapshots` | `entry_sequence` | `INT NOT NULL DEFAULT 1` | 그 실행 세대의 몇 번째 진입인지 |
| `exit_plans` | `practice_attempt_id` | `BIGINT NULL` | 튜토리얼 귀속 |
| `exit_plans` | `practice_attempt_run_number` | `BIGINT NULL` | 튜토리얼 귀속 |

`exit_plans`의 두 컬럼은 `orders`가 이미 가진 것과 **같은 모양·같은 CHECK**(둘 다 null이거나 둘 다
non-null, run > 0)로 만든다. 039가 주문 귀속에 쓴 패턴을 그대로 따르는 것이고, tick 정산 대상 선별
(EXITPRESET-014)과 재시작 정리(EXITPRESET-015)가 이 컬럼으로 이뤄진다.

**`ExitPlanEducationalOriginDto`를 재사용하지 않는다.** 그 record는 `intentionId`를 필수로 요구하는데
039가 사전 의도를 없앴으므로 튜토리얼 attempt 경로에는 줄 값이 없다. 귀속은 위 두 컬럼이 맡고,
`educationalOrigin`은 계속 null(일반 경로 형태)로 둔다.

**Flyway 번호는 미리 못박지 않는다.** 041·042를 합쳐 마이그레이션이 넷(`SNAP-1`, `SNAP-2`, 041의 진행
컬럼, 위 추가형 변경)이고 머지 순서는 `../041-tutorial-market-scenario/tasks.md` §교차 순서가 정한다.
각 PR 머지 직전에 `git ls-tree origin/dev -- src/main/resources/db/migration`으로 확인해 번호를 부여한다
(이슈 #394 — CI의 번호 역전 검사가 base 이동을 놓칠 수 있으므로 머지 버튼 직전에 본다).

### 파괴적 변경 하나 — `UNIQUE(attempt_id, run_number)` 교체

**여기가 이 plan에서 가장 조심해야 할 부분이다.**

`practice_risk_snapshots`는 `UNIQUE(attempt_id, run_number)`를 갖는다. 한 실행 세대에 snapshot이 정확히
하나라는 뜻이고, 039가 "실행당 최초 BUY 한 번"을 전제로 만든 제약이다. **041의 재진입 흐름은 한 실행
세대에 매수가 두 번 생기므로 이 제약에 걸린다**(EXITPRESET-017).

제약을 `UNIQUE(attempt_id, run_number, entry_sequence)`로 바꿔야 하는데, **기존 제약 삭제는 파괴적
변경이라 ADR-0021 §결정 7에 따라 한 배포에 담을 수 없다.** 자동 배포의 롤백은 앱만 되돌리고 스키마는
되돌리지 않기 때문이다.

**2단계로 나눈다.**

- **1차 배포 (`SNAP-1`)** — `entry_sequence` 컬럼을 `NOT NULL DEFAULT 1`로 추가하고, 새 UNIQUE
  `uk_practice_risk_snapshots_attempt_run_seq(attempt_id, run_number, entry_sequence)`를 **추가만** 한다.
  기존 UNIQUE는 그대로 둔다. 이 시점의 앱은 여전히 실행당 snapshot 하나만 만들므로 두 제약이 공존해도
  충돌하지 않는다. 롤백해도 구버전 앱이 새 컬럼(기본값 1)과 새 UNIQUE를 모르는 채 정상 동작한다.
- **2차 배포 (`SNAP-2`)** — 기존 `uk_practice_risk_snapshots_attempt_run`을 삭제한다. 이 배포부터 재진입
  snapshot 생성이 열린다.

**두 마이그레이션은 전체 작업의 맨 앞에 둔다**(`tasks.md` §스키마 선행 작업). 어느 코드도 이 컬럼·제약에
의존하지 않으므로 언제 나가도 안전하고, 미리 끝내 두면 "041의 재진입 이동이 2차 배포보다 먼저 나가면
재진입 매수가 깨진다"는 순서 의존 자체가 사라진다. 2차 배포를 뒤로 미루면 그 위험을 사람이 계속 챙겨야
한다 — **위험을 관리하는 대신 없애는 쪽을 택한다.**

두 배포 사이의 창에서도 중복 snapshot은 생길 수 없다. 그 시점의 코드는 항상 `entry_sequence = 1`을 쓰고,
새 UNIQUE가 기존 UNIQUE와 동일한 보호를 하기 때문이다.

### 제약 교체만으로는 부족하다 — 단건 조회를 함께 고쳐야 한다

`PracticeRiskSnapshotRepository.findByAttemptIdAndRunNumber(Long, long)`가 **`Optional`을 반환**하고
호출 지점이 6곳이다(`InvestmentPracticeQueryService` 2곳, `PracticeAttemptEvidenceService`,
`PracticeAttemptOrderAttributionService`, `PracticeAttemptRestartService`, `PracticeAttemptService`).

**`SNAP-2`로 DB 제약만 풀고 이 쿼리를 그대로 두면, 재진입 매수 직후 진행 조회·복기·재시작·완료가 전부
`IncorrectResultSizeDataAccessException`으로 죽는다.** 초판은 `SNAP-1`·`SNAP-2`를 "코드 변경 없음"이라
적었는데 사실이 아니다.

- 쿼리를 **두 개로 나눈다** — `findTopBy...OrderByEntrySequenceDesc`(최신 진입)와
  `findBy...AndEntrySequence(1)`(첫 진입).
- **6개 호출 지점마다 "현재 진입"인지 "그 run의 첫 진입"인지 판정해야 한다.** 특히
  `PracticeAttemptEvidenceService`가 반환하는 snapshot은 관찰 필터(`observedAt >= snapshot.createdAt`)와
  evidence A 기준선의 정본이므로, 어느 것을 쓸지 정하지 않으면 evidence 판정이 미정의가 된다.
  **판정을 미루지 않고 여기서 정한다.**

  | 호출 지점 | 무엇을 써야 하는가 | 이유 |
  |---|---|---|
  | `PracticeAttemptEvidenceService` (관찰 필터 기준선) | **첫 진입** | 최신을 쓰면 재매수 순간 이전 관찰이 필터에서 사라져 3단계가 미완료로 되돌아간다. 같은 유형이 이슈 #420으로 프로덕션에서 재현된 적 있다 |
  | `PracticeAttemptEvidenceService` (매수 evidence 검증·화면 표시) | **최신 진입** | "지금 진입의 체결이 내 것인가"를 보는 자리다. 이 서비스가 돌려주는 snapshot 하나가 하류에서 두 용도로 쓰이므로 `ResolvedPracticeAttemptEvidenceDto`가 `riskSnapshot`(최신)과 `observationBaseline`(첫 진입)을 **둘 다** 들고 다닌다 |
  | `PracticeAttemptOrderAttributionService` (다음 snapshot 생성) | 개수만 필요 | `entry_sequence` 산출용 |
  | `PracticeAttemptRestartService` (응답 조립) | **최신 진입** | `toResponse()`에서 `PracticeAttemptResponse.from(attempt, snapshot)`에 넘길 단건이다. 초판은 이 자리를 "정리 → 전체"로 적었으나 **정리는 `PracticeRunRestartOrderService.cleanupCurrentRun`이 맡고 이 서비스에는 벌크 조회 지점이 없다**(PR #456에서 코드와 대조해 정정) |
  | `InvestmentPracticeQueryService` ×2, `PracticeAttemptService` (기준선 표시) | **최신 진입** | 화면의 "지금 내 기준선" |

  > 위 표는 **PR #456이 실제 구현으로 확정한 결과**다. 초판의 "정리 → 전체" 행과 "7개 호출 지점" 표기는
  > 코드와 맞지 않아 정정했다(실제 호출 지점은 6곳이고, `PracticeAttemptEvidenceService`가 그중 하나를
  > 두 용도로 쓴다).

  **evidence는 실행 세대 단위 개념이고 snapshot은 진입 단위 개념이다.** 둘을 같은 객체로 다루던 것이
  재진입 도입으로 처음 드러났다(041 SCENARIO-019a).
- 이 작업은 `SNAP-2` **배포 전에** 끝나야 한다. 순서가 뒤집히면 재진입한 사용자가 500을 본다.

> 대안으로 "재진입을 새 실행 세대(run+1)로 취급"을 검토했다. 스키마를 안 건드려도 되지만, 재시작이 아닌
> 재매수가 run을 올리면 `039`의 실행 세대 의미(재시작 단위)가 무너지고 주문 귀속·재시작 정리·완료 판정이
> 전부 흔들린다. **채택하지 않는다.** 파괴적 변경 2단계가 더 싸다.

## 자동 예약 생성

### 어디서

`PracticeAttemptOrderAttributionService.createFirstBuyRiskSnapshot(order, trade, createdAt)`이다. 이미
최초 BUY 체결 트랜잭션 안에서 attempt를 잠그고 snapshot을 만드는 자리라, EXITPRESET-012가 요구하는
"snapshot과 예약이 같은 트랜잭션"이 자연히 성립한다.

메서드 이름이 `First`인데 재진입에서도 불려야 하므로 `createRiskSnapshotOnBuyFill`로 바꾸고,
`entry_sequence`를 그 실행 세대의 기존 snapshot 수 + 1로 채운다.

```
onBuyFill(order, trade):
  1. attempt 잠금 (기존)
  2. 이번 체결 직전 순보유수량이 0이 아니었으면 → 아무것도 하지 않고 반환   # EXITPRESET-020
  3. preset = attempt.exitPreset ?? BALANCED
  4. lines = ReferencePriceCalculator.calculateFromPercent(trade.price, preset.s, preset.t)
  5. snapshot 저장 (entry_sequence = 기존 수 + 1, exit_preset = preset)
  6. market == CRYPTO 이면:
         holding 조회·잠금
         baseline = 041의 대본 canonical price          # 사인파 항시 시세를 쓰지 않는다
         ExitPlanCreationService.create(practice(user, holding, trade.quantity, lines, hash, attempt, run, baseline))
  # market == STOCK 이면 5번까지만 (EXITPRESET-018)
```

**"직전 순보유수량"의 산출을 한 곳에서 정의한다.** `OrderExecutionService`·`LimitOrderFillService` 모두
`applyBuyTrade`를 **먼저** 호출한 뒤 snapshot 생성을 부르므로, 가드가 도는 시점에 holding에는 이번 체결이
이미 반영돼 있다. 따라서 "직전"은 `현재 순보유수량 − 이번 체결 수량`으로 역산한다. 042의 프리셋 잠금
조건(§프리셋 잠금 조건)과 041의 대기 탈출 판정도 같은 산출식을 공유해야 하며, 구현에서 한 메서드로 모은다.

**2단계 가드가 이 plan에서 가장 중요한 한 줄이다.** 초판에는 이 가드가 없어 보유 중 추가 매수가
(1) `validateNoPendingPlan` 409로 매수를 통째로 실패시키고, (2) 새 snapshot으로 기준선을 갱신해 039의
고정 규칙을 깨고, (3) 평단 이동으로 `041` SCENARIO-006a의 루머 분기를 무너뜨렸다. "직전 순보유수량이
0이었는가"는 체결 트랜잭션 안에서 판정 가능하다.

`ExitPlanCreateCommandDto`에 팩토리 `practice(...)`를 더한다. **엔진의 변경 범위를 정직하게 적는다** —
초판은 "엔진은 바꾸지 않는다"고 했으나 사실이 아니다. 귀속 컬럼을 채우려면 record 컴포넌트,
`ExitPlan` 생성 팩토리, `ExitPlanCreationService.newExitPlan`이 함께 바뀌어야 하고, baseline 주입을 위해
8단계도 바뀐다.

**바뀌지 않는 것**은 검증 순서와 예약 원장 취급이다 — holding 잠금, `availableQuantity` 검증,
`validateNoPendingPlan`, `holding.reserveQuantity()` 호출은 그대로다.

**엔진을 직접 부르는 이유가 하나 더 생겼다(2026-08-19).** `047` TUTORIAL-CASH-ISOL-010(이슈 #461,
PR #463)이 **호출부인 `ExitPlanService.create`에 샌드박스 종목 차단을 넣었다** —
`validateNotTutorialSample`이 대상 holding의 종목이 `isTutorialSample()`이면 409
`EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED`로 거부한다(`021` RISK-OCO-014가 정본).

- 그 차단은 **공용 엔진(`ExitPlanCreationService`)에는 없다.** 배치 이유는 `021` RISK-OCO-014가 명시한다
  — "경로 공용 엔진(`ExitPlanCreationService`)에 두지 않는 이유는 향후 교육 경로(`intentionId` 지정,
  `016` EDU-PRACTICE-005·006)가 재접합될 때 그 경로 자신이 이 차단에 막히지 않아야 하기 때문이다"
  (`021/spec.md` §비즈니스 규칙, 같은 취지가 `021/plan.md` §일반 경로 검증 순서 2단계에도 있다).
  즉 이 자리는 교육 경로를 위해 의도적으로 비워 둔 것이다.
- **그리고 042는 그 차단이 막으려던 문제를 애초에 만들지 않는다.** 047이 든 근거는 "일반 경로 OCO가
  체결되면 `Order.create(...)`로 진행돼 attempt 귀속이 없어 재시작·진행 판정이 복구 불가능하게 깨진다"인데,
  042의 예약은 `practice_attempt_id`·`practice_attempt_run_number`를 갖고 체결도 tick 정산 경로를 탄다.
  귀속이 있으므로 그 파손이 일어나지 않는다.

**이 근거를 문서에 남기는 이유는 방어다.** 지금은 차단이 호출부에만 있지만, 나중에 누군가 "엔진에서
막는 게 맞지 않나" 하고 옮기면 042가 통째로 깨진다. 그때 이 문단이 왜 옮기면 안 되는지의 근거가 된다.

**baseline은 반드시 주입해야 한다.** 현재 엔진 8단계가 `priceQueryService.getPrice(instrumentId)`로
baseline을 확정하는데, `PriceQueryService`는 튜토리얼 샘플이면 `TutorialSampleInstrumentPriceService`
(주기 180초·진폭 ±3%의 **벽시계 사인파**)로 분기한다. 대본과 아무 관계 없는 값이 `baseline_price`·
`baseline_observed_at`에 영속되고, `039` TUTORIAL-FLOW-011("화면 현재가·체결 판정·tick 정산이 같은 값")과
어긋난다. 매수가 실패하지는 않지만(`validateRange`가 baseline을 검증하지 않는다) 거짓 데이터가 남는다.

`requestHash`는 `attemptId:runNumber:entrySequence`의 SHA-256으로 만든다(컬럼이 `CHAR(64)`다).
**이것은 멱등키가 아니다** — `exit_plans.request_hash`에 UNIQUE 제약이 없고 엔진도 이 값을 읽지 않는다
(멱등성은 별도 테이블 `exit_plan_idempotency_keys`가 `ExitPlanIdempotentCreationService`를 통할 때만
동작하는데, 튜토리얼 경로는 그 서비스를 거치지 않는다). 실제 중복 방어는 아래 두 가지다.

- 엔진 4단계의 `validateNoPendingPlan`(holding당 PENDING 1건) — **초판이 "엔진은 안 바꾼다"고 적으면서
  이 검증만 언급에서 빠뜨렸다.** 보유 중 추가 매수가 예약을 또 만들려 하면 이 검증이 409를 던져 매수
  트랜잭션 전체가 롤백된다. EXITPRESET-020의 "진입당 1회" 가드가 그 상황을 애초에 만들지 않는다.
- `entry_sequence` 산출이 attempt를 잠근 트랜잭션 안에서 이뤄지므로 동시 요청이 직렬화된다.

`request_hash`는 감사용 snapshot으로만 남긴다.

### `exitPriceType`

`exit_plans.exit_price_type`은 `PRICE`/`PERCENT`를 구분하고 `stop_loss_rate`·`take_profit_rate`가 nullable
이다. 튜토리얼은 **`PERCENT`로 저장한다** — 프리셋이 비율이고, 비율을 남겨 두면 완료 화면에서 "내가 고른
기준"을 plan만 읽어도 복원할 수 있다. 가격 두 개는 019 공식으로 계산해 함께 넣는다.

## tick 정산 (EXITPRESET-014)

현재 `PracticeOrderSettlementService.settleCurrentRun(attemptId, runNumber, pricedAt)`은 이렇다.

```java
for (Long orderId : orderRepository.findPendingPracticeRunOrderIds(attemptId, runNumber)) {
    limitOrderFillService.fillIfPending(orderId, pricedAt);
}
```

`exit_plans`를 보지 않으므로 **이 상태로 예약을 걸면 대본이 급락해도 손절이 발동하지 않는다.**

`ExitPlanFillService.fillIfPending(Long exitPlanId, BigDecimal currentPrice)`가 이미 있으므로 진입점을
잇는 문제다. 같은 모양으로 한 루프를 더한다.

```java
for (Long exitPlanId : exitPlanRepository.findPendingPracticeRunExitPlanIds(attemptId, runNumber)) {
    exitPlanFillService.fillIfPending(exitPlanId, canonicalPrice);
}
```

새 repository 메서드 `findPendingPracticeRunExitPlanIds(attemptId, runNumber)`를 더한다. 기존
`findPendingExitPlansToFill(instrumentId, ...)`은 종목 단위라 다른 실행 세대·다른 사용자를 함께 잡으므로
쓰지 않는다.

**시그니처 차이에 주의한다.** `limitOrderFillService.fillIfPending`은 시각(`pricedAt`)을 받고
`exitPlanFillService.fillIfPending`은 가격(`currentPrice`)을 받는다. 041의 tick이 이미 진행 후 canonical
price를 손에 들고 있으므로 그 값을 그대로 넘긴다 — **차트와 체결이 같은 값을 쓴다**는 039
TUTORIAL-FLOW-011이 여기서 지켜진다.

**정산은 tick 종점 가격 하나가 아니라 건너뛴 가상 분마다 호출한다**(`041` SCENARIO-013). 041의 tick
알고리즘이 이미 분 단위로 순회하므로, 이 루프는 그 순회 안에서 분마다 돈다. tick 종점 가격만 쓰면
30초 간격에서 `−3%` 손절이 `−10%` 넘는 가격에 체결되고 루머 분기가 무작위가 된다.

**중복 tick 방어**는 `fillIfPending`의 이름 그대로 PENDING일 때만 체결하는 성질에 기댄다. 같은 가상 분에
tick이 두 번 와도 첫 번째에서 terminal이 된 plan은 두 번째에 잡히지 않는다. 지정가 주문과 같은 패턴이다.

**순서는 지정가 → OCO다.** 튜토리얼 흐름에서 둘이 동시에 걸리는 경우는 없지만(교육 지정가는 `030`의
세션 경로이고 attempt 경로와 겹치지 않는다), 순서를 고정해 두어야 나중에 겹칠 때 결과가 결정적이다.

## 수동 매도와 예약의 공존 (EXITPRESET-016)

예약이 `holding.reserveQuantity()`로 수량을 잡으므로, 전량 예약된 상태에서 사용자가 시장가 매도를 넣으면
`availableQuantity`가 0이라 거부된다. 이것을 막아야 한다.

**튜토리얼 샘플 종목의 매도 주문은 접수 전에 현재 실행 세대의 PENDING 예약을 먼저 취소한다.** 같은
트랜잭션 안에서 처리하므로 매도가 실패하면 취소도 함께 롤백돼 예약이 사라진 채 남는 상태가 없다.

```
sellOrder(attempt 귀속 매도):
  1. attempt 잠금
  2. 현재 run의 PENDING exit plan 전부 취소 (ExitPlanCancelService, 예약 수량 반환)
  3. 기존 매도 체결 진행
```

**부분 예약 반환은 하지 않는다.** 튜토리얼은 전량 매수 → 전량 매도 흐름이고, 부분 매도를 지원하면 남은
수량에 대한 예약을 다시 만들어야 해서 상태가 급격히 복잡해진다. 전부 취소하고 매도한다.

**부수 효과 하나를 기록한다.** 사용자가 부분 매도를 하면 남은 보유분에는 예약이 없는 상태가 된다. 화면에
"예약이 해제되었습니다"가 보여야 하며, 다시 걸어 주지 않는다 — 자동 재예약은 사용자가 고른 기준선을
새 체결가 없이 되살리는 것이라 EXITPRESET-004의 "체결가 기준" 규칙과 어긋난다.

## 재시작 정리 (EXITPRESET-015)

`PracticeRunRestartOrderService`가 현재 pending 주문만 정리한다. 여기에 exit plan 취소를 더한다.

```
restart:
  1. attempt 잠금 (기존)
  2. 현재 run의 PENDING exit plan 취소 → 예약 수량 반환   # 신규
  3. 현재 run의 PENDING 주문 취소 → 예약 현금·수량 반환   # 기존
  4. 순체결수량 보상 매도                                  # 기존
  5. run += 1, exit_preset = null, 선택·clock 초기화
```

**2번이 3번보다 먼저다.** 예약 수량이 남아 있으면 4번의 보상 매도가 `availableQuantity` 부족으로 실패한다.

`exit_preset`을 null로 되돌리는 것이 EXITPRESET-009(재시작하면 기본값으로 초기화)다.

## API 계약

### 신규

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| PUT | `/api/education/practice/attempts/{market}/exit-preset` | `{ "preset": "CAUTIOUS" }` | 200 `PracticeAttemptResponse` | 현재 실행 세대의 프리셋 선택. 순보유수량이 0인 동안에만(매수 전·재진입 대기 중) |

`PUT`인 이유는 자연 멱등이기 때문이다 — 같은 값을 몇 번 보내도 결과가 같고, 체결 전이면 몇 번이든 바꿀 수
있다(EXITPRESET-003). `Idempotency-Key`는 요구하지 않는다(015 LMT-005의 `PATCH`와 같은 판단).

### 프리셋 잠금 조건

**잠금 기준은 "최초 BUY 체결 여부"가 아니라 "현재 보유 중인가"다**(EXITPRESET-003, 2026-08-18 결정).

```
프리셋 변경 허용 = (현재 실행 세대의 순보유수량 == 0)
```

- 매수 전 → 허용
- 보유 중 → 거부 (`PRACTICE_STEP_LOCKED`)
- 손절·익절·수동 매도로 포지션 정리 후 재진입 대기 → **다시 허용**
- 완료 후 → 거부 (`PRACTICE_ALREADY_COMPLETED`)

**이 조건을 "시나리오가 멈춰 있으면 기준을 고칠 수 있다"로 설명하면 안 된다.** 041이 진행 조건을 구간
종류로 바꾸면서 그 등식이 깨졌다 — 4막을 관전 중인 미보유 사용자는 대본이 흐르는 중인데도 프리셋을 바꿀
수 있다. 화면 문구는 **"들고 있지 않을 때만 기준을 바꿀 수 있다"**로 적어야 한다.

교육적 근거는 두 방향이 한 조건으로 갈린다는 데 있다. 손절을 겪은 사용자가 다음 진입의 기준을 다시 정하는
것은 이 기능이 훈련시키려는 판단 그 자체이고, 손실 중에 손절선을 내리는 사후 합리화는 039가 막으려 한
것이다. 보유 중 잠금이 후자만 정확히 막는다.

**이미 확정된 snapshot은 어떤 경우에도 변경하지 않는다.** 프리셋을 바꿔도 과거 진입의 snapshot과 그
snapshot으로 만들어진 예약(이미 체결·취소됨)은 그대로 남는다. 다음 진입에만 적용된다.

**부수 효과 하나.** 재진입 대기 중 프리셋 변경은 예약을 만들지 않는다 — 그 시점에 보유가 없어 기준
가격(체결가)이 없기 때문이다(EXITPRESET-004의 "체결가 기준" 규칙). 화면에는 비율만 표시되고 가격선은
재매수 체결 후에 생긴다. 최초 매수 전과 완전히 같은 상태이므로 클라이언트가 두 경우를 다르게 다룰 필요가
없다.

### 기존 응답 확장

`PracticeAttemptResponse.riskSnapshot`에 추가:

| 필드 | 타입 | 설명 |
|---|---|---|
| `exitPreset` | `String` | 확정된 프리셋 식별자. 기존 데이터는 `BALANCED`로 채워 내려보낸다 |
| `stopLossRate` | `BigDecimal` | 손절률 |
| `takeProfitRate` | `BigDecimal` | 익절률 |
| `entrySequence` | `int` | 그 실행 세대의 몇 번째 진입인지 |

`PracticeAttemptResponse` 최상위에 추가:

| 필드 | 타입 | 설명 |
|---|---|---|
| `selectedExitPreset` | `String` | 체결 전 선택값. 미선택이면 `BALANCED` |
| `exitPresetLocked` | `boolean` | 현재 보유 중이면 true. 재진입 대기 중에는 false로 돌아온다 |
| `availableExitPresets` | `List<ExitPresetResponse>` | 식별자·손절률·익절률. 클라이언트가 금액을 계산할 재료 |

**금액 병기(EXITPRESET-006)는 클라이언트가 계산한다.** 서버가 계산하려면 "고른 수량"을 미리 받아야 하고,
그러면 수량 입력 → 서버 왕복 → 프리셋 표시라는 단계가 하나 늘어난다. 초보자를 멈추게 하지 않는 것이 이
기능의 전제(대안 2의 근거)이므로 왕복을 만들지 않는다. 서버는 비율과 기준 가격을 다 주므로 계산에 필요한
값이 부족하지 않다.

`PracticeEvidenceResponse.tradeResult`에 추가:

| 필드 | 타입 | 설명 |
|---|---|---|
| `sellCause` | `String` | `STOP_LOSS`\|`TAKE_PROFIT`\|`MANUAL`. 매도 전이면 null |

**이것이 EXITPRESET-008에서 유일하게 없던 값이다.** 나머지(`sellPrice`·`realizedPnl`·`returnRate`·
`sellVerdict`)는 이슈 #421로 이미 있다. `sellVerdict`만으로는 구분할 수 없다 — 자동 예약 체결이면
`sellVerdict`가 정의상 항상 경계값이 되기 때문이다.

`sellCause`는 매도 주문의 `exit_plans.triggered_order_id` 역참조로 판정한다. 그 주문을 가리키는 plan이
있고 상태가 `FILLED_STOP_LOSS`면 `STOP_LOSS`, `FILLED_TAKE_PROFIT`면 `TAKE_PROFIT`, 없으면 `MANUAL`이다.

### 오류 계약

새 코드를 만들지 않는다.

| 조건 | HTTP / 코드 |
|---|---|
| `preset`이 정의된 집합 밖·누락 | 400 `VALIDATION_ERROR` |
| 보유 중 프리셋 변경 | 409 `PRACTICE_STEP_LOCKED` |
| 완료 attempt에서 프리셋 변경 | 409 `PRACTICE_ALREADY_COMPLETED` |
| 종목 선택 전 프리셋 변경 | 409 `PRACTICE_STEP_LOCKED` |

## 테스트 전략 (ADR-0003)

| 대상 | 방식 |
|---|---|
| 프리셋 → 가격 계산 | 단위 테스트. `BALANCED`가 현행 `×0.97`·`×1.05`와 **정확히 같은 값**임을 포함 |
| 프리셋 수치 ↔ 041 대본 도달 조건 | 단위 테스트. 041 plan의 도달 부등식을 대본 파일과 프리셋 상수로 판정 |
| 선택 잠금(체결 후 변경 거부) | `@WebMvcTest` + 서비스 단위 |
| 매수 체결 시 snapshot·예약 동시 생성 | 통합 테스트. 트랜잭션 실패 시 **둘 다 남지 않음**을 함께 |
| tick의 OCO 정산 | 통합 테스트. 손절 체결 + 익절 자동 취소, 같은 가상 분 중복 tick |
| 수동 매도와 예약 공존 | 통합 테스트. 전량 예약 상태에서 시장가 매도가 정상 체결 |
| 재시작의 예약 정리 | 통합 테스트. 취소 순서(예약 → 주문 → 보상매도)와 예약 수량 정확히 1회 반환 |
| 재진입 재예약 | Testcontainers 통합. **`SNAP-2` 배포 이후에만 통과한다** |
| snapshot 단건 조회 7곳 | 슬라이스 + 통합. run 안에 snapshot이 2건일 때 조회·복기·재시작·완료가 모두 정상 동작 |
| 보유 중 추가 매수 | 통합. 체결은 되고 snapshot·예약은 새로 생기지 않으며 기준선이 그대로임 |
| `sellCause` 판정 | `@WebMvcTest` + 통합 |
| 기존 데이터 호환 | `exit_preset`이 null인 snapshot이 `BALANCED`로 해석됨 |
| 일반 경로 OCO 회귀 | 기존 테스트 전부 |

**mock만으로 끝내지 않는다.** 특히 예약 수량은 `holdings.reserved_quantity` 원장이 얽히므로 통합
테스트가 정본이다.

## 작업 순서 (tasks.md 초안)

1. 프리셋 enum·상수 + `ReferencePriceCalculator` 연결 → `BALANCED` 동치 테스트
2. 추가형 마이그레이션(컬럼 5개 + 새 UNIQUE 추가) + 엔티티
3. `PUT .../exit-preset` 엔드포인트 + 선택 잠금 + 진행 조회 응답 확장
4. 매수 체결 시 snapshot에 프리셋 반영 (예약 생성 없이 여기까지) → 회귀 확인
5. CRYPTO 자동 예약 생성 (`ExitPlanCreateCommandDto.practice`, 귀속 컬럼)
6. tick의 OCO 정산 + 재시작 예약 정리 + 수동 매도 공존
7. `sellCause` + 완료 응답 대조 + `ai/api-routes.md`·`api-contracts.md` 갱신
8. 재진입 재예약 통합 테스트 (`SNAP-2` 배포 이후에만 통과)

**4번과 5번을 나눈 이유**는 프리셋 반영과 자동 예약이 서로 다른 위험을 갖기 때문이다. 4번까지는
"기존 동작 + 값이 달라짐"이라 회귀만 보면 되고, 5번부터 예약 원장이 얽힌다. 한 커밋에 담으면 문제가
생겼을 때 어느 쪽인지 가르는 데 시간이 든다.

**041과의 순서는 `../041-tutorial-market-scenario/tasks.md` §교차 순서가 정본이다.** 요지는 둘이다 —
스키마 선행 작업(`SNAP-1`·`SNAP-1b`·`SNAP-2`)을 맨 앞으로 빼고, 자동 예약과 tick 정산은 041이 tick 코드를 자리잡게
한 뒤에 얹는다.

## 열린 질문 — 이 plan에서 정하지 않은 것

- ~~재매수 시 프리셋 재선택 허용 여부~~ → **2026-08-18 결정: 허용한다.** 잠금 조건이 "최초 BUY 체결
  이후"에서 **"현재 보유 중"**으로 바뀌었다(EXITPRESET-003 개정). 구현 영향은 아래 §프리셋 잠금 조건에
  적었다.
- **`026`의 "참조선은 자동 청산을 유발하지 않는다" 문장 개정 필요 여부.** 이 plan의 판단은 "그 문장의
  취지가 '서버가 사용자 몰래 팔지 않는다'이므로 사용자가 명시적으로 고른 기준의 실행은 위배가 아니다"
  이지만, 문서 개정 없이 두면 다음 사람이 모순으로 읽는다. 팀 판단이 필요하다.
- **금액 병기 문구의 형식.** 클라이언트 계산으로 정했으나 문구 자체는 프론트 소유다.
- **이슈 #401 정리.** #444와 중복이며 #445로 해결됐다. 닫아야 한다.

## 잔여 위험 (plan 단계에서 추가로 확인된 것)

- ~~2단계 배포 사이의 창~~ → **해소됨.** 두 마이그레이션을 전체 작업의 맨 앞으로 옮겨(`tasks.md`
  §스키마 선행 작업) 재진입 코드가 나가기 한참 전에 끝나도록 했다. 남는 것은 "두 PR을 연달아 배포해야
  한다"는 것뿐이고, 그 사이에 다른 작업이 끼어도 깨지지 않는다.
- **매도 전 예약 취소가 만드는 짧은 무방비 구간.** 같은 트랜잭션이라 외부에서 관측되지 않지만, 트랜잭션이
  길어지면 holding 잠금 경합이 늘어난다. 튜토리얼은 사용자당 하나의 attempt라 경합 자체가 드물다.
- **`PERCENT` 저장이 019의 미사용 분기를 처음 켠다.** `calculateFromPercent`가 production에서 한 번도
  불린 적이 없으므로, 단위 테스트는 있어도 실제 데이터 경로에서 처음 도는 코드다. `BALANCED` 동치
  테스트(작업 1번)를 통과 조건으로 두는 이유다.
- **CAUTIOUS를 고른 사용자의 익절 후 추가 상승 13.0%p.** 041 plan의 잔여 위험과 같은 항목이다.
  "조심스럽게 골랐더니 덜 벌었다"가 트레이드오프로 읽히지 않고 손해로 읽히면, 프리셋 선택 자체가
  부정적으로 학습된다. 강화안을 택하며 커진 값이라 위험도 함께 커졌고, **4막의 −21.8%가 상쇄한다는 전제**
  위에 서 있다. 완화는 화면 문구이며 서버가 할 수 있는 것은 사실 제공까지다.
- **CAUTIOUS가 2막 루머에서 먼저 털린다는 사실 자체의 양면성.** 이 분기가 이 기능의 교육적 핵심이지만
  (041 SCENARIO-006a), 사용자가 "조심스럽게를 고르면 손해"로 일반화할 위험도 같이 생긴다. 실제 시장에서
  좁은 손절선은 자주 털리는 대신 크게 잃지 않는 것이고, 그 대가 관계가 화면에 함께 보여야 한다 —
  루머에서 나간 사용자는 확정 다이빙(−12%)을 피했다는 사실이 완료 화면의 사후 대조(041 SCENARIO-021)에
  반드시 나타나야 한다. **없으면 이 분기는 그냥 "좁게 잡으면 손해"만 가르친다.**
