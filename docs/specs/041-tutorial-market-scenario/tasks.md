# Tasks: 튜토리얼 사건-가격 시나리오 — 저작 대본·구간 종류가 정하는 시계

> 이 spec은 `../042-tutorial-exit-preset`과 같은 코드를 건드린다. **아래 §교차 순서를 먼저 읽는다.**
> 042의 스키마 선행 작업(SNAP-1·SNAP-1b·SNAP-2)이 끝나기 전에 4번 항목을 배포하면 재진입 매수가
> UNIQUE 위반으로 실패한다.

## 교차 순서 (041 ↔ 042)

두 spec을 합친 배포 순서다. 각 줄이 하나의 PR이며, 같은 줄 안에서만 순서를 바꿀 수 있다.

| # | 소속 | 내용 | 왜 이 자리인가 |
|---|---|---|---|
| 1 | 042 | `SNAP-1` — `entry_sequence` 컬럼 + 새 UNIQUE **추가만** | 코드 변경 없음. 아무도 의존하지 않아 언제 나가도 안전 |
| 1b | 042 | `SNAP-1b` — snapshot 단건 조회 6곳을 "첫 진입/최신 진입"으로 분리 | **제약만 풀고 쿼리를 두면 재진입 직후 500이 나고, 기준선을 잘못 고르면 관찰 evidence가 사라진다** |
| 2 | 042 | `SNAP-2` — 기존 `UNIQUE(attempt_id, run_number)` **삭제** | 새 UNIQUE가 같은 보호를 하므로 무해. **재진입 코드보다 먼저 끝내 두는 것이 핵심** |
| 3 | 041 | 1·2·3번 (대본·생성기 V2·attempt 컬럼) | 042의 프리셋 검증 테스트가 대본 파일을 읽는다 |
| 4 | 042 | 1·2번 (프리셋 상수·엔티티) | 3번의 대본이 있어야 도달 부등식 테스트가 돈다 |
| 5 | 041 | 4·5번 (진행 계산·tick 통합) | tick 코드를 먼저 자리잡게 한다 |
| 6 | 042 | 3~7번 (선택 API·자동 예약·tick OCO 정산) | 5번이 만든 tick 위에 얹는다 |
| 7 | 041 | 6·7번 (사건 노출·통합 시나리오·문서) | 마지막에 전체를 관통해 확인한다 |
| 8 | 042 | 8번 (재진입 재예약 통합 테스트) | `SNAP-2`(2번)와 042 5~7번이 모두 나간 뒤에야 통과한다 |

**1~2번을 맨 앞으로 뺀 것이 plan에서 바뀐 점이다.** plan은 042의 마지막 작업으로 뒀는데, 그러면
"041의 재진입이 042의 2차 배포보다 먼저 나가면 깨진다"는 순서 의존을 사람이 계속 챙겨야 한다. 두 마이그
레이션은 어떤 코드도 의존하지 않으므로 맨 앞에서 미리 끝내면 그 위험 자체가 없어진다.

**Flyway 번호는 이 표의 순서대로 부여한다.** 두 spec을 합쳐 마이그레이션이 넷(1·2번, 3번의 attempt 진행
컬럼, 042의 프리셋 컬럼)이라 각 문서가 번호를 미리 못박으면 순서가 조금만 바뀌어도 어긋난다. 실제 번호는
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
  **테스트**: V2 golden vector 신규 + **V1 golden vector 무변경 통과**(SCENARIO-023).

- [ ] **3. attempt 진행 컬럼** — 마이그레이션(`scenario_stage_id`, `scenario_stage_elapsed_seconds`,
  `scenario_candle_open/high/low` 5개 nullable 추가) + `PracticeAttempt` 엔티티 필드.
  **`select()`와 `restart()` 양쪽의 초기화 대상에 새 컬럼을 추가한다** — `restart()`가 빠뜨리면 재시작한
  사용자가 이전 실행의 대본 위치를 물려받는다. **`047`이 같은 재시작 흐름에 튜토리얼 계좌 리셋을
  추가해 뒀으니(TUTORIAL-CASH-ISOL-006) 충돌을 예상하고 rebase한다.**

- [ ] **4. 진행 계산 서비스** — plan §상태 전이표 전 항목. 특히 **대기 구간에서 매수 시 다음 진행 구간
  0분으로 점프**(초판이 빠뜨린 전이), 대기 구간의 벽시계 진행·되감기, 진행 구간은 미보유여도 진행,
  **매도해도 위치를 옮기지 않음**(SCENARIO-010 — 순간이동 제거), `MAX_TICK_GAP = 30초` clamp,
  대기 탈출 시 체결 시각 기준으로 delta 절단, 초 단위 누적, 봉 3값 갱신,
  **건너뛴 가상 분마다 순차 정산 + 분마다 `holding` 재조회**(SCENARIO-013).
  **테스트**: 시각 주입 단위 테스트 — 전이표 3행, 2초 간격 tick에서도 드리프트 없음,
  tick 간격을 가상 10분으로 벌려도 루머 손절이 재현됨, 소비하지 않은 초가 차감되지 않음.

- [ ] **5. tick 통합·`order` 인터페이스 변경·시간 게이트 제거** — `POST .../tick`이 진행 계산을 호출하고
  `GET .../chart`는 순수 조회를 유지한다. **V2의 `canonicalPrice`가 시각이 아니라 커서를 읽도록 바꾼다**
  (plan §`order` 인터페이스 변경 — 이것 없이는 SCENARIO-013을 구현할 수 없다).
  **`PracticeHoldingReflectionService.verifyAttemptSaleEvidence`의 시간 게이트를 V2에서 제거**하고
  `saleDeadlineAt`을 `null`로 내린다(V1·legacy는 기존 값 유지). `docs/api-contracts.md`를 **같은 커밋에서**
  갱신한다 — 409 계약과 `"EXPIRED"` 응답 문자열의 V2 도달 불가를 함께 적는다.
  **테스트**: 통합 — 대기 구간에서 아무리 오래 있어도, **복기 저장 시점에도** 시간으로 막히지 않음,
  2막 손절 후 그 자리에서 확정 하락을 관전함, 3막 익절 후에도 4막이 재생됨.

- [ ] **6. 사건 노출** — `PracticeTutorialChartResponse`에 `scenarioStage`(act 단위)·`scenarioProgressing`·
  `causeStatus`·`revealedEvents` 추가, `GET /api/education/practice`에 `revealedEvents`·`priceAfterSell`
  (대본 lookahead)와 **진입별 `entries` 배열** 추가 — 각 항목에 `unrealizedPnlIfHeld`("안 팔았다면" 평가손익)를 **서버가 계산해** 담는다. 공식은 `PostSellArithmetic`을 재사용하고 매도 수수료를 뺀 기준으로 맞춘다(SCENARIO-019b·021a). `causeStatus`는 `REVEALED`·`NONE_KNOWN` 둘뿐이며 **미공개 사건은 `NONE_KNOWN`과
  구분 불가능해야 한다**(SCENARIO-015·016). `docs/api-contracts.md`를 **같은 커밋에서** 갱신한다.
  **테스트**: `@WebMvcTest` — 공개 시점 이전 응답에 문안·시각·개수·자리표시자 어떤 형태로도 없음.

- [ ] **7. 통합 시나리오와 문서** — Testcontainers 통합 테스트로 **0막 대기 → 매수 → 1막 →
  2막 손절 → 확정 하락 관전 → 재진입 대기 → 재매수 → 3막 익절 → 4막 관전 → 복기 → 완료** 완주.
  `CAUTIOUS`가 루머에서, `BALANCED`가 확정에서 손절되는 분기와 **재매수 후에도 관찰 evidence가 유지되는
  것**을 함께 확인. `docs/api-routes.md` 최종 확인, `docs/prd.md` §3에 `SCENARIO-001~024` 행 추가 + **SANDBOX 행의 5분 만료
  서술 갱신**(SCENARIO-014가 폐지한다).

> **API 문서는 각 항목이 자기 커밋에서 갱신한다**(CLAUDE.md 규칙 7). 5·6번이 응답 계약을 바꾸므로 각자
> 그 커밋에서 `docs/api-contracts.md`를 갱신하고, 이 항목으로 미루지 않는다.

## 사람이 직접 확인할 것

자동 테스트로 대신할 수 없는 항목이다. 7번 이후 배포본에서 수행한다.

- [ ] 한 실행 안에서 (a) 호재가 열렸는데 더 오르지 않는 장면, (b) 급락 후 되돌림, (c) 원인 없는 변동
  셋을 눈으로 확인한다. **(c)는 2막-b 속임수 반등이 대표 지점이다.**
- [ ] 매수하지 않고 오래 머문 뒤 매수해, 이야기를 처음부터 볼 수 있음을 확인한다.
- [ ] 손절된 뒤 화면을 계속 보며 확정 하락이 이어지는 것을 확인한다. 익절 후 4막 폭락도 마찬가지다.
- [ ] 종목 선택 화면부터 완료 화면까지 훑어, 대본과 무관한 고정 호재 문구가 남아 있지 않고 가상 종목·
  가상 사건 표시가 모든 화면에 있음을 확인한다.

## 선행 확인 (구현 착수 전)

- [ ] 프론트 레포에서 `INSTRUMENT_SCENARIOS` 상수의 실제 내용과 노출 위치 확인 (SCENARIO-019의 대상 확정)
- [ ] 사건 노출 창구를 tick 응답으로 확정할지 별도 조회를 둘지 프론트와 합의 (plan "확인 필요" 2번)
