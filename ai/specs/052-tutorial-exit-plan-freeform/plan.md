# Plan: 튜토리얼 손절·익절 자유 입력과 양쪽 체험 보장

## 관련 문서

- Spec: `./spec.md`
- 앞선 spec: `ai/specs/042-tutorial-exit-preset/`(이 문서가 개정하는 delta 원본),
  `ai/specs/041-tutorial-market-scenario/`(대본·진입별 대조·반사실),
  `ai/specs/049-tutorial-order-basics-script/`(2단계 대본·단계 게이트),
  `ai/specs/039-tutorial-flow-redesign/`(attempt·실행 세대·기준선 고정),
  `ai/specs/019-exit-price-policy/`(퍼센트 표기·계산·반올림 공식),
  `ai/specs/021-general-risk-management-oco/`(OCO 엔진 구조)
- 관련 ADR: ADR-0002(레이어드 구조), ADR-0003(테스트 전략), ADR-0004(Flyway),
  ADR-0021 §결정 7(파괴적 변경 2단계 배포), ADR-0012(무엇이 인메모리이고 무엇이 DB인지)
- 입력 초안: `finplay-frontend/docs/exit-plan-redesign-proposal-2026-08-21.md`(이 spec이 대체하는 정본)

**이 계획은 1차(제안 A)와 2차(제안 B·C)를 나눠 적는다.** 1차 계약은 이미 확정돼 구현 중이므로 아래 §1차는
그 계약의 기록이고, §2차는 아직 착수하지 않은 설계다.

---

## 1차 — API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| PUT | /api/education/practice/attempts/{market}/exit-rates | `{"stopLossRate":3.0,"takeProfitRate":5.0}` | 200 `PracticeAttemptResponse` | 현재 실행 세대의 손절률·익절률을 확정. 보유 중이면 거부. 자연 멱등이라 `Idempotency-Key` 불필요 |
| PUT | /api/education/practice/attempts/{market}/exit-preset | 기존 그대로 | 기존 그대로 | **이번 배포에서 제거하지 않는다**(EXITFREE-012). 프론트 전환 후 별도 배포로 삭제 |
| GET | /api/education/practice?market= | 변경 없음 | 200 + 필드 추가 | `exitStopLossRate`·`exitTakeProfitRate`(항상 non-null)와 `exitRateBounds` 추가. 기존 프리셋 필드는 유지 |

### 오류 응답

| 상황 | 코드 |
|---|---|
| 범위 밖·소수 둘째 자리·필드 누락·미지원 market | 400 `VALIDATION_ERROR` |
| 대본을 쓰는 CRYPTO 실행에서 앞 단계(시장가·지정가 왕복) 미완료 | 409 `PRACTICE_STAGE_LOCKED` (049 게이트 승계, **보유 중 판정보다 앞선다**) |
| attempt 없음 · 보유 중 변경 시도 | 409 `PRACTICE_STEP_LOCKED` |
| 완료된 attempt | 409 `PRACTICE_ALREADY_COMPLETED` |
| 계산된 기준선이 `0 < stop < entry < take`를 깨거나 `DECIMAL(18,8)` 초과 (진입 체결 시점) | 409 `EXIT_PLAN_INVALID_PRICE_RANGE` |

**새 오류 코드를 만들지 않는다.** 위 집합은 전부 `PUT .../exit-preset`이 이미 쓰는 것이며, 두 API가 같은
잠금·같은 판정을 쓰므로 오류도 같아야 한다.

## 1차 — 입력 명세

| 필드 | 필수 | 검증 |
|---|---|---|
| `stopLossRate` | 필수 | `BigDecimal`, `2.0 ≤ r ≤ 5.0`(양 끝 포함), scale ≤ 1, **양수로 받는다**(서버가 손실 방향으로 해석). 위반 400 `VALIDATION_ERROR` |
| `takeProfitRate` | 필수 | `BigDecimal`, `3.0 ≤ r ≤ 8.0`(양 끝 포함), scale ≤ 1. 위반 400 `VALIDATION_ERROR` |
| `market`(path) | 필수 | `STOCK`\|`CRYPTO`. 그 외 400 |

- 두 값은 **한 요청에 함께** 온다. 하나만 보내면 400이다 — OCO 예약 하나가 두 조건을 함께 갖는 구조라
  (021) 반쪽만 확정된 상태가 성립하지 않는다.
- 소수 자릿수는 **`@Digits(integer = 1, fraction = 1)` 성격의 형식 검증**으로 거부한다. 반올림해서 받아주지
  않는다 — 화면에 입력한 값과 실제 적용될 값이 달라지면 이 기능이 가르치려는 "내가 정한 기준"이 무너진다.
- 범위 4값은 서버 상수 한 곳(`ExitRateBounds` 성격의 정의)에서 나오고, 검증·응답(`exitRateBounds`)이 **같은
  상수를 읽는다.** 두 곳에 적으면 화면이 허용하는 값이 400으로 거부되는 상태가 조용히 생긴다.

## 1차 — 응답 계약 (프론트가 그대로 붙일 수 있게)

`GET /api/education/practice`의 진행 조회 응답에 다음이 더해진다.

```jsonc
{
  "exitStopLossRate": 3.0,      // 항상 non-null. 미설정이면 기본값 3.0
  "exitTakeProfitRate": 5.0,    // 항상 non-null. 미설정이면 기본값 5.0
  "exitRateBounds": {           // 항상 non-null. 클라이언트는 범위를 하드코딩하지 않는다
    "stopLossMin": 2.0, "stopLossMax": 5.0,
    "takeProfitMin": 3.0, "takeProfitMax": 8.0
  }
}
```

- **비율은 퍼센트 수다**(3%는 `3.0`). 042의 `availableExitPresets`·`riskSnapshot`의 rate 표기와 같다.
- **손절도 양수다.** 화면이 `-`를 붙여 그리는 것은 표현이며 계약이 아니다.
- 기존 `selectedExitPreset`·`exitPresetLocked`·`availableExitPresets`는 **그대로 남는다**(EXITFREE-012).
  프론트는 전환 기간 동안 새 필드만 읽으면 되고, 옛 필드를 읽어도 깨지지 않는다.
- `entries[]`의 각 항목에 **그 진입에 적용된 비율**이 더해진다(EXITFREE-009). 기존 `exitPreset` 필드는 남되,
  자유 비율로 연 진입에서는 프리셋으로 환원되지 않으므로 화면은 **비율 쪽을 정본으로 읽는다.**
- 잠금 판정(`exitPresetLocked`)은 자유 비율에도 그대로 적용된다 — 판정 근거가 "지금 보유 중인가" 하나라
  입력 방식과 무관하다.
- `tutorialStageProgress.exitPresetSelected`의 **필드명은 유지하고 의미만 넓힌다** — "프리셋을 골랐거나
  자유 비율을 정했다". 개명은 프리셋 제거 배포에서 함께 한다(spec §열린 질문 5).

## 1차 — 데이터 모델

**Flyway 마이그레이션이 필요하다.** 다음 빈 번호는 `origin/dev` 기준 **V56**이다(V55가 마지막).
**착수 시점에 `git ls-tree -r origin/dev --name-only -- src/main/resources/db/migration`으로 다시 확인한다** —
병렬 브랜치가 선점하는 종류의 충돌이 이 저장소에서 실제로 반복됐다(ADR-0004, ADR-0027).

```sql
-- 튜토리얼 손절·익절 자유 입력 비율을 실행 세대와 진입에 영속한다 (052 EXITFREE-001·009).
ALTER TABLE practice_attempts
    ADD COLUMN exit_stop_loss_rate   DECIMAL(4,1) NULL AFTER exit_preset,
    ADD COLUMN exit_take_profit_rate DECIMAL(4,1) NULL AFTER exit_stop_loss_rate;

ALTER TABLE practice_risk_snapshots
    ADD COLUMN exit_stop_loss_rate   DECIMAL(4,1) NULL AFTER exit_preset,
    ADD COLUMN exit_take_profit_rate DECIMAL(4,1) NULL AFTER exit_stop_loss_rate;
```

- **전부 추가형·nullable이라 ADR-0021 §결정 7의 2단계 배포 대상이 아니다.** 롤백해도 구버전 앱은 이 컬럼을
  언급하지 않는다.
- **CHECK는 "둘 다 NULL이거나 둘 다 양수"까지만 건다.** 범위(2~5 · 3~8)는 스키마에 잠그지 않는다 —
  042의 `exit_preset` CHECK와 다른 판단이며 이유는 **범위가 041 대본과 함께 조정될 값**이기 때문이다.
  대본 튜닝마다 마이그레이션을 강요하면 범위가 코드에서 조용히 갈라진다. 값 집합이 고정된 열거형과 성질이 다르다.
- **백필하지 않는다.** 기존 행은 두 컬럼이 NULL이고, 애플리케이션이 **NULL → 그 행의 `exit_preset` → 그것도
  NULL이면 기본값(3/5)** 순으로 해석한다. 042가 `exit_preset` NULL을 `BALANCED`로 해석한 것과 같은 방식이며,
  백필은 "그때 실제로 고른 값"을 사후에 지어내는 것이 된다.
- **`exit_preset` 컬럼은 이번에 삭제하지 않는다**(EXITFREE-012).

## 1차 — 구성요소

| 자리 | 하는 일 |
|---|---|
| `PracticeAttemptController` | `PUT .../exit-rates` 추가. 검증은 DTO의 Bean Validation + 서비스 |
| `PracticeAttemptService` | 비율 확정. 잠금 순서·게이트 판정은 `selectExitPreset`과 **같은 경로를 공유**한다 — 두 API가 다른 판정을 하면 화면 잠금과 서버 거부가 갈린다 |
| `PracticeAttempt` | 비율 필드 2개 + "유효 비율" 접근자(자유 비율 → 프리셋 → 기본값 순 해석). **이 해석은 이 한 곳에만 있어야 한다**(spec 잔여 위험 5) |
| `PracticeAttemptOrderAttributionService.createRiskSnapshotOnBuyFill` | 프리셋 대신 **유효 비율**로 기준선·자동 예약 생성. 구조(진입당 1회·같은 트랜잭션·2단계 대본 제외)는 042·049 그대로 |
| `ReferencePriceCalculator` | 프리셋이 아닌 **비율 2개**를 받는 계산 경로. 공식·반올림은 019 그대로 |
| `PracticeStageProgressCalculationService` | `exitPresetSelected` 판정을 "프리셋 또는 자유 비율을 정했는가"로 확장. 게이트도 같은 산출식을 읽으므로 자동으로 따라온다 |
| `PracticeRiskSnapshot` | 적용 비율 2개 영속(`entries[]`가 진입별로 읽는다) |

## 1차 — 대본 검증(EXITFREE-013)

041의 도달 가능성 판정을 **경계 4값**으로 다시 돌린다.

- 2막 저점 ≤ 진입가 × (1 − 5/100)  — 가장 넓은 손절도 닿는다
- 2막 루머 저점 ≤ 진입가 × (1 − 2/100) — 가장 좁은 손절이 루머 단계에서 닿는다(SCENARIO-006a의 연속 경계)
- 3막 고점 ≥ 진입가 × (1 + 8/100) — 가장 넓은 익절도 닿는다
- 3막 고점의 상한 조건(SCENARIO-006) — 가장 좁은 익절(+3%) 기준으로도 "익절 안 했으면 더 벌었다"가 되지 않는다

**대본 파일은 바꾸지 않는다.** 042의 프리셋 3종이 이미 이 범위의 경계·내부 값이므로 041이 통과시킨 조건과
같은 부등식이다. 확인 대상이 이산 3점에서 경계 4점으로 바뀔 뿐이며, 그 사이 값은 단조성으로 따라온다.

---

## 2차 — API 설계 (미착수)

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | /api/education/practice/attempts/{market}/exit-plan | `{"stopLossRate":3.0,"takeProfitRate":5.0}` | 201 `ExitPlanResponse` | 사용자가 매도 화면에서 직접 거는 예약. 현재 진입 전량, **진입당 1회(write-once)** |
| GET | /api/education/practice?market= | — | 200 + `exitExperience` | 겪음 상태와 권유 대상 |
| POST | /api/education/practice/attempts/{market}/tick | — | 200 + `exitPlanFills[]` | 이번 tick에 체결된 예약 |

**`POST /api/exit-plans`의 교육 경로(`intentionId` 지정)를 쓰지 않는다.** 그 계약은 016 시절 설계라
favorite → intention → BUY trade 체인을 전제하는데, 039 이후 튜토리얼은 intention을 요구하지 않는다
(`ai/api-routes.md`가 기록한 대로 현재 그 경로는 **400으로 거부**된다). 튜토리얼 전용 경로를 attempt 아래에
두면 attempt 잠금·실행 세대 귀속·단계 게이트를 기존 경로와 같은 방식으로 처리할 수 있다.
→ **`docs/api/education.md`의 "OCO exit plan 생성 — 교육 경로 (계획)" 절은 2차에서 이 결정으로 정리해야 한다.**

### 2차 응답 계약

```jsonc
{
  "exitExperience": {
    "stopLossExperienced": true,     // 이 실행 세대에서 FILLED_STOP_LOSS 예약이 있었는가
    "takeProfitExperienced": false,
    "bothExperienced": false,
    "recommendedNext": "TAKE_PROFIT" // 먼저 겪은 쪽의 반대. 없으면 null(아무것도 안 겪음 / 둘 다 겪음)
  },
  "exitPlanFills": [                 // tick 응답. 이번 tick에 체결된 예약. 보통 0~1건
    { "entrySequence": 1, "cause": "STOP_LOSS", "filledPrice": "9700.00000000",
      "quantity": "1.00000000", "realizedPnl": -3000,
      "stopLossPrice": "9700.00000000", "takeProfitPrice": "10500.00000000" }
  ]
}
```

- `exitExperience`는 **판정만 한다.** 서버는 화면을 잠그지 않고 주문을 거부하지 않는다(확정 결정 1).
- **저장소를 새로 만들지 않는다.** 판정은 `exit_plans`의 `practice_attempt_id` ·
  `practice_attempt_run_number` · `status`(V51의 인덱스 `idx_exit_plans_practice_attempt_run_status`가 그대로
  받는다)에서 파생한다. 재시작은 run이 증가하므로 **초기화 코드 없이** 자동으로 리셋된다.
- 반사실은 기존 `priceAfterSell`·`unrealizedPnlIfHeld`를 그대로 쓴다. **새 계산도 새 필드도 없다**
  (EXITFREE-031). 진행 중 값은 현재 대본가 기준이며 대본 종점을 쓰지 않는다.

## 문서 갱신 (구현 커밋과 같은 커밋 — CLAUDE.md 규칙 7·10)

**지금 고치지 않는다.** 아래는 구현할 때 함께 가야 하는 항목이다.

- `ai/api-routes.md`
  - `PUT /api/education/practice/attempts/{market}/exit-rates` 행 **추가**(근거 `052 EXITFREE-001~004`).
  - `PUT .../exit-preset` 행에 "052로 대체 예정, 이번 배포에서 제거하지 않음" 한 줄 **추가**.
  - `GET /api/education/practice` 행에 새 응답 필드(`exitStopLossRate`·`exitTakeProfitRate`·`exitRateBounds`,
    `entries[]`의 진입별 비율, `exitPresetSelected` 판정 확장) **반영**.
  - (2차) `POST /api/exit-plans` 행의 "교육 경로는 아직 지원하지 않음(400)" 서술을 052의 attempt 전용 경로
    결정으로 **갱신**.
- `docs/api/education.md`
  - `exit-rates` 요청·응답·오류 계약 표 **추가**(오류 집합은 `exit-preset`과 동일함을 명시).
  - `PracticeAttemptResponse`·진행 조회 절에 새 필드 **추가**, 프리셋 필드의 존치 이유(2단계 배포) **명시**.
  - `tutorialStageProgress.exitPresetSelected` 설명을 확장된 판정으로 **갱신**.
  - (2차) "OCO exit plan 생성 — 교육 경로 (계획)" 절을 attempt 전용 경로 결정으로 **정리**.
- `docs/erd.md` — `practice_attempts`·`practice_risk_snapshots`에 컬럼 2개씩 추가 반영.
- `ai/prd.md` §3 "구현 현황" — **갱신 대상이다.** 새 요구사항 ID 집합(`EXITFREE-001~013`)과 새 엔드포인트가
  생기므로 새 행을 추가하고, 기존 `EXITPRESET-001~020` 행에 "EXITPRESET-001은 052가 뒤집었다"를 덧붙인다.
  근거 칸에는 그 PR 번호를 적는다.

## 테스트 계획 (ADR-0003)

- **단위** — 비율 → 기준선 계산(경계값 2.0·5.0·3.0·8.0, 소수 첫째 자리, 반올림), 유효 비율 해석 순서
  (자유 비율 → 프리셋 → 기본값), 단계 판정 확장, `recommendedNext` 산출(2차).
- **슬라이스** — `@WebMvcTest`로 `PUT .../exit-rates`의 400/409 집합. `@DataJpaTest`로 새 컬럼 영속과
  NULL 해석(기존 행이 프리셋으로 해석됨).
- **통합(Testcontainers)** — (1) 프리셋에 없던 조합(손절 2 + 익절 8)으로 매수 → 대본 tick → 그 선에서 자동
  청산, (2) 재진입 대기 중 비율 변경 → 재매수 → `entries[]`가 진입별로 다른 비율, (3) 재시작 후 기본값 복귀와
  PENDING 예약 정리, (4) 보유 중 변경 거부 → 매도 후 허용.
- **대본 판정** — EXITFREE-013의 경계 4값 부등식을 기존 041 대본 검증 테스트에 추가한다.
- **회귀** — 042의 프리셋 경로 테스트가 그대로 통과해야 한다(EXITFREE-012의 병존).
