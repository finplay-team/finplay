# Spec: 완료 튜토리얼 attempt 재시작 허용 — 보상은 최초 완료 1회만

> 이슈: #402
>
> 이 문서는 `039-tutorial-flow-redesign`의 **TUTORIAL-FLOW-005를 대체(supersede)한다.**
>
> TUTORIAL-FLOW-005(원문): "완료 attempt는 진입 또는 재시작 요청으로 초기화하거나 새 실행 세대를 만들지
> 않는다. 두 요청 모두 기존 완료 evidence를 사용하는 `REPLAY` 모드를 반환하고 completion·reward·ledger를
> 그대로 유지한다."
>
> 이 spec은 위 문장 중 **"재시작 요청"에 대한 부분만** 뒤집는다 — 완료 attempt도 명시적 `POST
> .../restart` 요청으로 새 실행 세대를 시작할 수 있다. **"진입 요청"(`PUT .../attempts/{market}`)에 대한
> 부분은 그대로 유지한다** — 단순 진입·새로고침은 여전히 attempt를 초기화하지 않고 기존 완료 evidence를
> `REPLAY`로 반환한다. `completion·reward·ledger를 그대로 유지한다`는 문장도 **최초 완료 evidence**에
> 대해서는 그대로 유지되며(아래 비즈니스 규칙), 이 spec은 그 evidence 위에 "몇 번째 완료인지" 판정을
> 추가할 뿐 최초 완료 기록을 바꾸지 않는다.
>
> 함께 참고: `026-market-order-practice-tutorial`의 MKT-PRACTICE-009("완료 판정은 ... `practice_progresses`·
> `practice_completions`의 불변 완료 원칙을 그대로 상속한다")와 ADR-0012는 **대체하지 않는다.** 이 두 문서가
> 말하는 "불변 완료"는 최초 완료 evidence(`practice_progresses`, `practice_completions`,
> `practice_market_reflections`)에 대한 것이며, 이 spec은 그 evidence 테이블에 새 행을 추가하거나 기존 행을
> 수정하지 않는다. attempt별 "현재 실행이 완료됐는가"는 `practice_attempts.status`/`completed_at`으로만
> 표현하고, 이 값은 재시작마다 재설정된다 — 이 구분이 이 spec의 핵심이다.
>
> **재시작 시 튜토리얼 계좌 리셋은 `047-tutorial-sandbox-cash-isolation`이 정본이다.** 이 spec은 재시작
> 절차(정리 순서·잠금·완료 보상 1회 캡)의 정본으로 계속 남아 있고, `047`은 그 절차(`PracticeRunRestartOrderService.cleanupCurrentRun`)
> 안에 "그 시장의 튜토리얼 전용 계좌를 1000만원으로 초기화한다"는 단계 하나만 추가한다 — 이 spec이 정의한
> 재시작 가능 여부·정리 대상·완료 보상 지급 규칙 자체는 바뀌지 않는다.

## 개요

현재는 시장(`STOCK`/`CRYPTO`)별 튜토리얼을 한 번 완료하면 그 attempt는 영구히 읽기 전용 `REPLAY`로
고정되어 다시 진행할 수 없다. 이 기능은 완료된 attempt도 재시작해 새 실행 세대(run)로 다시 진행할 수
있게 한다. 단, 완료 보상(500만원, 이슈 #343)은 실제 금전적 가치가 있는 자원이므로 사용자·market
조합에서 **최초로 완료했을 때 정확히 한 번만** 지급하고, 이후 몇 번을 재시작·재완료해도 다시 지급하지
않는다. 이 기능 배포 이전에 이미 완료한 사용자도 재시작 시 보상을 다시 받지 않아야 한다.

## 사용자 시나리오

- 이미 STOCK 또는 CRYPTO 튜토리얼을 완료한 사용자는 진입 화면에서 재시작을 눌러 종목 선택부터 다시
  진행할 수 있다.
- 재시작한 사용자는 새 실행 세대에서 매수·자동 위험 스냅샷·차트·매도·복기·완료를 기존 미완료 attempt와
  동일하게 처음부터 다시 경험한다.
- 재시작 후 다시 완료해도 계좌에 완료 보상이 추가로 입금되지 않는다.
- 이 기능이 배포되기 전에 이미 완료했던 사용자가 배포 이후 처음 재시작·재완료해도 보상을 받지 않는다 —
  "최초 완료"는 기능 배포 시점이 아니라 실제 최초 완료 시점 기준이다.
- 동시에 여러 재완료 요청이 들어와도(중복 클릭, 재시도) 보상은 사용자·market 조합당 총 1회를 넘지
  않는다.
- 사용자는 완료 후 재시작한 진행 중 attempt로 다시 진입(새로고침 포함)해도 오류 없이 현재 실행 상태를
  이어서 볼 수 있다.

## 요구사항

- [ ] TUTORIAL-RESTART-001: `POST /api/education/practice/attempts/{market}/restart`는 attempt 상태가
  `COMPLETED`여도 무변경 `REPLAY`를 반환하지 않고, 기존 미완료 attempt와 동일한 원자 정리(TUTORIAL-FLOW-003·
  004의 현재 실행 pending 취소·예약 반환·순체결수량 보상매도)를 수행한 뒤 실행 세대를 증가시키고
  `SELECTING_INSTRUMENT`로 되돌린다.
- [ ] TUTORIAL-RESTART-002: `PUT /api/education/practice/attempts/{market}`(진입/ensure)는 이번 spec으로
  동작이 바뀌지 않는다 — completion evidence가 있고 attempt가 현재 `COMPLETED`면 여전히 attempt를
  초기화하지 않고 `REPLAY`를 반환한다(TUTORIAL-FLOW-002 유지, "진입은 재시작이 아니다").
- [ ] TUTORIAL-RESTART-003: 같은 진입 조회는 completion evidence가 있어도 attempt가 현재 `COMPLETED`가
  아니면(재시작 후 진행 중) 오류를 던지지 않고 attempt의 현재 상태(`SELECTING_INSTRUMENT`/`IN_PROGRESS`/
  `EXPIRED`)를 그대로 반환한다. (현재 구현은 이 조합을 데이터 정합성 오류로 오판해 `PRACTICE_EVIDENCE_MISSING`을
  던진다 — 이 재판정 자체가 이 기능의 필수 수정 대상이다.)
- [ ] TUTORIAL-RESTART-004: 완료 보상(계좌 현금 500만원 + 샌드박스 조정 반영)은 사용자·market(tutorial key)
  조합에서 **최초 완료 1회만** 지급한다. 최초 완료 여부는 기존 `practice_completions`
  (`UNIQUE(user_id, tutorial_key)`) 행의 존재 여부로 판정하며, 이 판정에 별도 백필이나 새 컬럼을 필요로
  하지 않는다 — 이 기능 배포 이전에 이미 완료한 사용자도 그 행이 이미 만들어져 있으므로 정확히
  "이미 최초 완료함"으로 판정된다.
- [ ] TUTORIAL-RESTART-005: 재완료(사용자·market 조합에서 두 번째 이후 완료)는 `practice_completions`·
  `practice_market_reflections`·`practice_progresses`에 새 행을 추가하거나 기존 행을 수정하지 않는다.
  오직 해당 `practice_attempts` 행의 `status`를 `COMPLETED`로, `completed_at`을 이번 완료 시각으로
  갱신할 뿐이다.
- [ ] TUTORIAL-RESTART-006: 최초 완료 여부 판정과 보상 지급 여부 결정은 하나의 DB 트랜잭션에서
  `practice_progresses` 행을 `FOR UPDATE`로 잠근 뒤 수행한다(기존 복기 완료 트랜잭션이 이미 이 잠금을
  건다). 동시에 들어온 재완료 요청들은 이 잠금으로 직렬화되어 보상이 두 번 지급되지 않는다.
- [ ] TUTORIAL-RESTART-007: 재완료 요청에서 사용자가 입력한 복기 답변(`answer`)은 evidence 검증(매도·
  관찰 evidence, 5분 기한)은 최초 완료와 동일하게 통과해야 하지만, 텍스트 자체는 영속하지 않는다 — 최초
  완료의 답변만 `practice_market_reflections`에 영구 보존된다.

## 비즈니스 규칙

- 완료 evidence 3종(`practice_progresses`, `practice_completions`, `practice_market_reflections`)은 최초
  완료 시점 값에서 절대 변경하지 않는다(insert도, update도 하지 않는다) — 이는 026/031/ADR-0012의 불변
  완료 원칙을 그대로 유지하는 것이며, 이 spec은 그 evidence를 "재완료 여부 판정 기준"으로 읽기만 한다.
- attempt별 "현재 실행 완료" 여부는 `practice_attempts.status`/`completed_at`만으로 표현한다. 이 값은
  재시작마다 초기화되고 재완료마다 다시 채워질 수 있는, evidence 3종과는 별개의 가변 상태다.
- 재시작 정리(잠금 순서 `attempt → 현재 실행 pending order(id ASC) → account → holding`, 트랜잭션 원자성,
  순체결수량만 보상매도)는 039의 기존 규칙을 그대로 따르며 `COMPLETED` 상태에서 시작한 재시작에도 동일하게
  적용한다.
- **(경계 조건, 이슈 #433)** 아래 "범위 제외"의 legacy(실제 종목) 완료자는 재시작 경로 **밖에 있지 않다** —
  `PUT .../attempts/{market}`(진입)이 legacy completion을 보고 실제 종목을 심은 `COMPLETED/REPLAY` attempt를
  만들어 주기 때문에(039 TUTORIAL-FLOW-002), 그 사용자가 TUTORIAL-RESTART-001의 재시작을 누르면 실제 종목을
  가진 attempt가 정리 경로로 들어온다. 이 경우 재시작은 **종목을 정리 대상으로 넘기지 않는다** — 실제 종목의
  holding·주문·체결은 이 기능이 건드리는 대상이 아니며, 현재 run에 귀속된 주문이 0건일 때만 실행 세대를
  증가시키고 `SELECTING_INSTRUMENT`로 되돌린다. 귀속 주문이 남아 있으면 종전대로 409
  `PRACTICE_EVIDENCE_MISSING`으로 막는다(정리해야 할 원장을 조용히 건너뛰지 않는다). 재시작 이후의 종목
  선택은 샌드박스 샘플만 허용하므로 사용자는 정상 흐름으로 복귀한다.
- 보상 지급 여부는 "이번 완료 트랜잭션 시작 시점에 `practice_completions` 행이 이미 존재했는가"로 결정한다.
  존재하지 않았다면(최초) 트랜잭션 안에서 행을 생성하고 보상을 지급한다. 존재했다면(재완료) 행을 만들지
  않고 보상도 지급하지 않는다.
- 5분 매도 기한, 샘플 종목 전용 evidence 판정 등 026/031의 기존 완료 전제조건은 최초 완료·재완료 모두
  동일하게 적용한다 — 재완료라고 evidence 검증을 생략하지 않는다.
- 이 기능은 attempt 기반(샌드박스 종목) 완료 경로에만 적용한다. 실제 종목 chain 기반의 legacy 진행
  경로(`PracticeHoldingReflectionService.createReflection` 상단, attempt가 없는 경우)는 대상이 아니며
  기존처럼 완료 후 재진행을 지원하지 않는다.

## 범위 제외

- 실제 종목 chain 기반(non-attempt) 진행·완료 경로의 재시작·재완료 지원 — 다만 그 경로로 완료한 사용자도
  진입이 만들어 주는 replay attempt를 통해 재시작 요청은 할 수 있다. 그때의 동작은 위 "비즈니스 규칙"의
  경계 조건(이슈 #433)을 따른다.
- 재완료 시 사용자가 입력한 복기 답변의 이력 저장, 완료 이력 목록 조회 API
- 보상 금액 변경, 최초 완료 이외의 추가 보상·이벤트 지급 정책
- 프론트엔드 구현 — `finplay-frontend`의 `AttemptTutorialFlow.tsx` replay 게이팅 변경은 별도 레포의 별도
  작업이며 이 spec은 백엔드 계약만 정의한다. (영향 참고: 완료 상태에서도 재시작 버튼이 보이도록 게이팅
  조건이 바뀌어야 한다.)
- `docs/specs/016-investment-education-policy`가 정의한 즐겨찾기·의도 재시작 시 완료 전 evidence 소실
  규칙 변경

## 완료 조건

- [ ] 완료(`COMPLETED`) attempt에서 `POST .../restart`가 정리 후 실행 세대를 증가시키고
  `SELECTING_INSTRUMENT`로 되돌리는 것이 자동 테스트로 확인된다.
- [ ] 재시작 후 매수·위험 스냅샷·차트·매도·복기·완료 전체 흐름이 미완료 attempt와 동일하게 동작한다.
- [ ] 재완료 시 계좌 현금·샌드박스 조정액이 변하지 않고(보상 미지급), `practice_completions`·
  `practice_market_reflections`·`practice_progresses` 행 수가 늘지 않는 것이 확인된다.
- [ ] 이 기능 배포 이전에 이미 완료한 사용자를 재현한 데이터(사전에 존재하는 `practice_completions` 행)로
  재시작·재완료해도 보상이 지급되지 않는 것이 Testcontainers로 확인된다.
- [ ] 동시 재완료 요청 경합에서도 총 보상 지급 횟수가 사용자·market 조합당 1회(이미 지급됐다면 0회)를
  넘지 않는 것이 동시성 테스트로 확인된다.
- [ ] 완료 후 재시작해 진행 중인 attempt에 대해 `PUT .../attempts/{market}`(진입 재조회)가 오류 없이
  현재 실행 상태를 반환하는 것이 확인된다.
- [ ] `docs/specs/039-tutorial-flow-redesign/spec.md`의 TUTORIAL-FLOW-005 항목에 이 spec으로 대체됐다는
  표기가 추가된다.
