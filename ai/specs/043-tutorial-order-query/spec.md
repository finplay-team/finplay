# Spec: 튜토리얼 attempt 전용 주문 조회

## 개요

이슈 #435 — 코인 튜토리얼(`039-tutorial-flow-redesign`)에서 지정가 주문을 걸면 예약 카드가 잠깐 떴다가
사라지고 "확인하지 못했습니다"로 바뀐다. 새로고침해도 복원되지 않는다. 원인은 `OrderRepositoryImpl`의
`GET /api/orders`·`GET /api/orders/pending` 두 조회가 샌드박스 종목(`instrument.tutorialSample=true`)
주문을 항상 제외하기 때문이다(`033-exclude-tutorial-sandbox-data`의 SANDBOX-EXCL 원칙, "실거래 화면에
샌드박스 데이터가 섞이면 안 된다"). 그런데 튜토리얼 화면이 자신의 주문을 읽을 경로가 이 두 엔드포인트뿐이라,
샘플 종목 지정가 주문이 조회에서 통째로 빠져 튜토리얼이 진행 불가 상태가 된다.

이 spec은 교육 도메인에 **튜토리얼 attempt 전용 조회 엔드포인트**를 신설해 이 gap을 없앤다.
`GET /api/orders`·`GET /api/orders/pending`의 샌드박스 제외 필터는 033을 되돌리는 것이므로 건드리지
않는다 — 새 엔드포인트는 그 두 조회를 대체하지 않고, 튜토리얼 화면 전용의 별도 읽기 경로를 하나 추가할
뿐이다.

## 사용자 시나리오

- 튜토리얼 진행 중인 사용자는 자신이 이번 실행(attempt·run)에서 낸 주문을, 시장가·지정가/매수·매도를
  가리지 않고, 상태(`PENDING`·`FILLED`·`CANCELLED`)와 함께 조회할 수 있다.
- 사용자가 지정가 매수를 걸고 새로고침해도, 그 주문이 아직 `PENDING`이면 이 조회에서 그대로 보인다 —
  카드가 사라지거나 "확인하지 못했습니다"로 바뀌지 않는다.
- 사용자가 재시작(`POST .../attempts/{market}/restart`)해 새 실행 세대로 넘어가면, 지난 실행 세대의
  주문은 이 조회에 더 이상 나타나지 않는다 — 항상 **현재** attempt·run의 주문만 보인다.
- 사용자가 아직 이 시장의 튜토리얼에 진입한 적이 없으면(attempt 없음), 이 조회는 오류 없이 빈 목록을
  반환한다.

## 요구사항

- [ ] TUTORIAL-ORDER-001: `GET /api/education/practice/attempts/{market}/orders`는 인증 사용자 본인의
  `market`(`STOCK`|`CRYPTO`) 튜토리얼 attempt를 조회해, 그 attempt의 **현재 실행 세대(run)**에 귀속된
  주문(`order.practiceAttemptId = attempt.id AND order.practiceAttemptRunNumber = attempt.runNumber`)만
  반환한다. attempt가 없으면 빈 목록을 반환한다(오류 아님).
- [ ] TUTORIAL-ORDER-002: 응답은 상태와 무관하게 현재 run의 모든 주문을 포함한다 — `PENDING`(아직 대기
  중)·`FILLED`(체결됨)·`CANCELLED`(취소됨) 전부. 프론트가 상태를 추측하지 않도록 각 주문의 실제
  `status`를 그대로 노출한다.
- [ ] TUTORIAL-ORDER-003: 이전 실행 세대(재시작 이전 run)의 주문, 이 attempt에 귀속되지 않은 일반 주문
  (`practiceAttemptId=null`), 다른 사용자·다른 시장의 주문은 결과에 포함하지 않는다.
- [ ] TUTORIAL-ORDER-004: `GET /api/orders`·`GET /api/orders/pending`의 샌드박스 종목 제외 필터
  (`033-exclude-tutorial-sandbox-data` SANDBOX-EXCL 원칙)는 이 spec으로 변경하지 않는다. 이 엔드포인트는
  그 두 조회의 필터를 우회하거나 대체하지 않고, 완전히 별도인 읽기 경로다.

## 비즈니스 규칙

- "현재 attempt·run"의 정의는 `039-tutorial-flow-redesign`이 이미 확정한 것을 그대로 따른다 —
  `practice_attempts` 행(사용자·시장 조합에 정확히 하나)의 `runNumber`가 정본이며, 이 spec은 그 값을
  새로 정의하거나 바꾸지 않는다.
- 이 조회는 순수 읽기다 — 어떤 주문 상태도 변경하지 않고, attempt를 잠그지 않는다(쓰기 경로인 재시작·
  주문 생성·tick 체결과 자원을 다투지 않는다).
- attempt가 `COMPLETED`(`REPLAY`) 상태여도 이 조회는 막지 않는다 — 완료된 실행의 주문 내역도 그대로
  조회 가능하다(다른 attempt 쓰기 API처럼 `PRACTICE_ALREADY_COMPLETED`를 던지지 않는다). 완료 후에도
  "이번 실행에서 무슨 주문을 냈는지"는 유효한 조회 대상이다.

## 범위 제외

- 프론트엔드가 이 엔드포인트를 실제로 사용하도록 화면을 바꾸는 작업 — 별도 레포(`finplay-frontend`)의
  별도 작업이다.
- `GET /api/orders`·`GET /api/orders/pending`의 샌드박스 필터 자체를 수정·완화하는 것 — SANDBOX-EXCL
  원칙(033)은 유지한다.
- 페이지네이션 — 한 attempt·run에 귀속되는 주문 수는 튜토리얼 한 회차 매매 범위로 자연히 작게 bound
  되므로(수십 건 이내), 커서 페이지네이션을 추가하지 않는다.
- `POST /api/education/practice/limit-orders`(030 코인 가상 가격 세션 지정가)가 만드는
  `practicePriceSessionId` 귀속 주문 — 이 주문은 `practiceAttemptId`가 null이라 이 조회 대상이 아니다.
  030 세션 주문의 조회 경로(`GET /api/education/practice/price-sessions/{sessionId}`)는 이미 존재하며
  이 spec은 그 경로를 바꾸지 않는다.

## 완료 조건

- [ ] 지정가 매수를 건 뒤 이 엔드포인트로 조회하면 `PENDING` 상태의 그 주문이 보인다(자동 테스트로 확인).
- [ ] 체결·취소된 뒤에는 같은 주문이 각각 `FILLED`·`CANCELLED` 상태로 계속 보인다(자동 테스트로 확인).
- [ ] 재시작 후에는 이전 run의 주문이 이 조회에서 빠지고, 새 run의 주문만 보인다(Testcontainers로 확인).
- [ ] attempt가 없는 사용자·시장 조합은 오류 없이 빈 목록을 반환한다(자동 테스트로 확인).
- [ ] `GET /api/orders`·`GET /api/orders/pending`의 기존 샌드박스 제외 동작에 회귀가 없다(기존 테스트
  유지·통과).
