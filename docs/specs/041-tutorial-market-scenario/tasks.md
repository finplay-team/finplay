# Tasks: 튜토리얼 사건-가격 시나리오 — 저작 대본·포지션 앵커 시계

> 이 spec은 `../042-tutorial-exit-preset`과 같은 코드를 건드린다. **아래 §교차 순서를 먼저 읽는다.**
> 042의 스키마 선행 작업(SNAP-1·SNAP-2)이 끝나기 전에 4번 항목을 배포하면 재진입 매수가 UNIQUE 위반으로
> 실패한다.

## 교차 순서 (041 ↔ 042)

두 spec을 합친 배포 순서다. 각 줄이 하나의 PR이며, 같은 줄 안에서만 순서를 바꿀 수 있다.

| # | 소속 | 내용 | 왜 이 자리인가 |
|---|---|---|---|
| 1 | 042 | `SNAP-1` — `entry_sequence` 컬럼 + 새 UNIQUE **추가만** | 코드 변경 없음. 아무도 의존하지 않아 언제 나가도 안전 |
| 2 | 042 | `SNAP-2` — 기존 `UNIQUE(attempt_id, run_number)` **삭제** | 새 UNIQUE가 같은 보호를 하므로 무해. **재진입 코드보다 먼저 끝내 두는 것이 핵심** |
| 3 | 041 | 1·2·3번 (대본·생성기 V2·attempt 컬럼) | 042의 프리셋 검증 테스트가 대본 파일을 읽는다 |
| 4 | 042 | 1·2번 (프리셋 상수·엔티티) | 3번의 대본이 있어야 도달 부등식 테스트가 돈다 |
| 5 | 041 | 4·5번 (진행 계산·tick 통합) | tick 코드를 먼저 자리잡게 한다 |
| 6 | 042 | 3~7번 (선택 API·자동 예약·tick OCO 정산) | 5번이 만든 tick 위에 얹는다 |
| 7 | 041 | 6·7번 (사건 노출·통합 시나리오·문서) | 마지막에 전체를 관통해 확인한다 |

**1·2번을 맨 앞으로 뺀 것이 plan에서 바뀐 점이다.** plan은 042의 마지막 작업으로 뒀는데, 그러면
"041의 재진입이 042의 2차 배포보다 먼저 나가면 깨진다"는 순서 의존을 사람이 계속 챙겨야 한다. 두 마이그
레이션은 어떤 코드도 의존하지 않으므로 맨 앞에서 미리 끝내면 그 위험 자체가 없어진다.

**Flyway 번호는 이 표의 순서대로 부여한다.** 두 spec을 합쳐 마이그레이션이 넷(1·2번, 3번의 attempt 진행
컬럼, 4번의 프리셋 컬럼)이라 각 문서가 번호를 미리 못박으면 순서가 조금만 바뀌어도 어긋난다. 실제 번호는
각 PR 머지 직전에 `git ls-tree origin/dev -- src/main/resources/db/migration`으로 확인해 정한다
(이슈 #394).

## 작업 항목

- [ ] **1. 대본 파일과 로더** — `scenario-crypto-v1.json` 저작(8개 stage / 120개 배율 / 사건 5개),
  파서, 기동 시점 정합성 검증(배열 길이 = `minutes`, 사건 `stageId` 실재, `impactStart + impactMinutes`가
  구간 내, LOOP 구간의 첫·끝 배율 일치). 잘못된 대본이면 기동을 실패시킨다.
  **테스트**: 대본을 읽어 판정하는 단위 테스트 — plan §프리셋 도달 조건의 부등식 전부, 특히 루머 저점
  0.975가 `CAUTIOUS`·`BALANCED` 손절선 구간 사이에 들어가고 세 구간이 겹치지 않음. 무귀속 분 > 귀속 분.

- [ ] **2. 생성기 V2** — 대본 위치(`stageId`, `stageMinute`) → 가격 변환을 별도 클래스로 만들고
  `TutorialPriceGenerator` 진입점에서 `generatorVersion`으로 분기. `validate`가 1·2를 모두 허용.
  **테스트**: V2 golden vector 신규 + **V1 golden vector 무변경 통과**(SCENARIO-021).

- [ ] **3. attempt 진행 컬럼** — 마이그레이션(`scenario_stage_id`, `scenario_stage_minute`,
  `holding_elapsed_seconds`, `progress_updated_at` 4개 nullable 추가) + `PracticeAttempt` 엔티티 필드.
  종목 선택 시 `generatorVersion = 2`, `scenarioStageId = IDLE_ENTRY`로 시작하도록 초기화 경로 수정.

- [ ] **4. 진행 계산 서비스** — 보유 여부 판정, `MAX_TICK_GAP = 30초` clamp, 구간 전이, LOOP 되감기,
  **act가 `ACT2`일 때만 `IDLE_REENTRY` 0분으로 이동**. 5분 만료 판정을 `holding_elapsed_seconds >= 300`
  으로 전환하되 **V1 attempt는 기존 `buyTrade.executedAt + 5분`을 유지**.
  **테스트**: 시각 주입 단위 테스트 — 미보유 시 미진행, clamp 동작, 루프 되감기, `ACT2` 외 구간에서는
  재진입 이동 안 함, V1/V2 만료 분기.

- [ ] **5. tick 통합** — `POST .../tick`에서 `진행 → 정산 → 재진입 이동` 순서로 실행. **정산 뒤에 이동**
  해야 손절 체결이 반영된다. `GET .../chart`는 진행을 갱신하지 않는 순수 조회를 유지한다.
  **테스트**: `@DataJpaTest`·통합 — 순서 역전 시 손절 사용자가 2막에 갇히는 회귀를 잡는 케이스 포함,
  대기 구간에서 tick을 반복해도 진행하지 않음, 대기가 길어도 만료되지 않음.

- [ ] **6. 사건 노출** — `PracticeTutorialChartResponse`에 `scenarioStage`(act 단위)·`scenarioProgressing`·
  `causeStatus`·`revealedEvents` 추가, `GET /api/education/practice`에 `revealedEvents`·`priceAfterSell`
  추가. `causeStatus`는 `REVEALED`·`NONE_KNOWN` 둘뿐이며 **미공개 사건은 `NONE_KNOWN`과 구분 불가능해야
  한다**(SCENARIO-013·014).
  **테스트**: `@WebMvcTest` — 공개 시점 이전 응답에 문안·시각·개수·자리표시자 어떤 형태로도 없음.

- [ ] **7. 통합 시나리오와 문서** — Testcontainers 통합 테스트로 **0막 대기 → 매수 → 1막 → 2막 손절 →
  재진입 대기 → 재매수 → 3막 익절 → 4막 관전 → 복기 → 완료** 완주. `CAUTIOUS`가 루머에서, `BALANCED`가
  확정에서 손절되는 분기도 함께 확인. `docs/api-routes.md`·`docs/api-contracts.md` 갱신,
  `docs/prd.md` §3에 `SCENARIO-001~022` 행 추가(PR 번호와 함께).

## 사람이 직접 확인할 것

자동 테스트로 대신할 수 없는 항목이다. 7번 이후 배포본에서 수행한다.

- [ ] 한 실행 안에서 (a) 호재가 열렸는데 더 오르지 않는 장면, (b) 급락 후 되돌림, (c) 원인 없는 변동
  셋을 눈으로 확인한다. **(c)는 2막-b 속임수 반등이 대표 지점이다.**
- [ ] 매수하지 않고 오래 머문 뒤 매수해, 이야기를 처음부터 볼 수 있음을 확인한다.
- [ ] 종목 선택 화면부터 완료 화면까지 훑어, 대본과 무관한 고정 호재 문구가 남아 있지 않고 가상 종목·
  가상 사건 표시가 모든 화면에 있음을 확인한다.

## 선행 확인 (구현 착수 전)

- [ ] 프론트 레포에서 `INSTRUMENT_SCENARIOS` 상수의 실제 내용과 노출 위치 확인 (SCENARIO-017의 대상 확정)
- [ ] 사건 노출 창구를 tick 응답으로 확정할지 별도 조회를 둘지 프론트와 합의 (plan "확인 필요" 2번)
