# Spec: 튜토리얼 흐름 재설계 — 영속 attempt·자동 위험 스냅샷·단일 라이브 차트

> 이슈: #378
>
> 이 문서는 `026-market-order-practice-tutorial`과 `031-tutorial-sandbox-instruments`의 샘플 종목 완료
> 경로를 부분 대체하는 delta다. `030-coin-practice-price-runtime`의 코인 지정가 세션과
> `033-exclude-tutorial-sandbox-data`의 샌드박스 데이터 격리 원칙은 유지한다. 기존 불변 체결 원장,
> `practice_completions`, 시장별 최초 완료 보상은 수정·삭제하지 않는다.

## 개요

현재 튜토리얼은 인메모리 favorite·사전 의도, 여러 가격 표현, 5분 chain 판정에 의존해 새로고침과 재시작의
의미가 불명확하다. 이 기능은 사용자·시장별 하나의 영속 attempt를 정본으로 두고, 미완료 attempt를 언제든
종목 선택부터 안전하게 다시 시작할 수 있게 한다. 사용자 입력 사전 의도는 화면에서 제거하고 실제 매수
체결가에 서버가 생성한 교육용 손절 -3%·익절 +5% 스냅샷을 영속한다.

주식·코인 모두 같은 흐름을 사용한다. 차트는 29개 완결 일봉과 가상 12:00부터 진행 중인 오늘 일봉 하나만
보여 주며, 실제 3초를 가상 1분으로 계산한다. attempt에 저장한 시간·seed·생성기 버전으로 다시 계산하므로
새로고침과 서버 재기동 뒤에도 같은 시각의 같은 가격을 재현한다. 차트 GET은 어떤 체결·상태 변경도 만들지
않는 순수 조회이고, 클라이언트가 3초마다 호출하는 명시적 tick POST만 canonical 가격 정산과 live update를
실행한다.

## 사용자 시나리오

- 로그인 사용자는 STOCK 또는 CRYPTO 튜토리얼에 들어가 시장의 거래 가능한 샘플 종목을 선택하고 매수부터
  매도·복기까지 진행할 수 있다.
- 사용자는 미완료·만료 attempt에서 다시 시작을 누르면 pending 주문과 이번 실행분 보유를 정리한 뒤 종목
  선택 화면으로 돌아간다.
- 사용자는 손절·익절 값을 직접 입력하지 않아도, 체결가 기준 -3%·+5% 교육 기준선을 보고 관찰·복기한다.
- 사용자는 29개 과거 일봉과 현재 가상 일봉을 한 차트에서 보고, 새로고침 후에도 같은 흐름을 이어 간다.
- 이미 시장별 튜토리얼을 완료한 사용자는 완료·보상·원장을 초기화하지 않고 읽기 전용 replay로 다시 본다.

## 요구사항

- [ ] TUTORIAL-FLOW-001: 사용자·시장(`STOCK|CRYPTO`) 조합마다 영속 `practice_attempt`는 정확히 하나다.
  동일 행의 실행 세대가 재시작 때 증가하며, 현재 실행에 속하는 주문·체결·스냅샷은 attempt ID와 실행
  세대로 격리된다.
- [ ] TUTORIAL-FLOW-002: 진입 요청은 attempt가 없을 때만 종목 미선택 상태로 생성하고, 기존 미완료
  attempt가 있으면 현재 run을 변경 없이 반환한다. 재시작은 별도 명시 요청으로만 실행하며 정리를 원자
  완료한 뒤 실행 세대를 증가시키고 종목 미선택 상태로 되돌린다. 따라서 page reload는 재시작이 아니다.
- [ ] TUTORIAL-FLOW-003: 재시작은 현재 실행 세대에 귀속된 pending 주문만 취소하고 현금·수량 예약을 정확히
  한 번 반환한다. 다른 실행 세대, 일반 주문, 다른 사용자·시장의 주문은 변경하지 않는다.
- [ ] TUTORIAL-FLOW-004: 재시작은 현재 실행 세대의 샘플 종목 FILLED BUY 합계에서 FILLED SELL 합계를 뺀
  양수 순체결수량만 서버 보상 매도한다. 가격은 그 attempt의 재시작 시점 canonical tutorial price이며,
  기존 불변 주문·체결 원장을 삭제·수정하지 않고 새 FILLED SELL 원장을 남긴다. 순수량이 0이면 매도하지 않는다.
- [ ] TUTORIAL-FLOW-005 (`ai/specs/040-tutorial-restart-after-completion`으로 부분 대체됨, Issue #402 —
  "재시작 요청" 부분만 뒤집혔다: 완료 attempt도 `POST .../restart`로 실제 재시작한다. "진입 요청"에 대한
  아래 문장은 그대로 유효하다): 완료 attempt는 진입 또는 재시작 요청으로 초기화하거나 새 실행 세대를 만들지
  않는다. 두 요청 모두 기존 완료 evidence를 사용하는 `REPLAY` 모드를 반환하고 completion·reward·ledger를
  그대로 유지한다.
- [ ] TUTORIAL-FLOW-006: 종목 선택은 해당 시장의 `tradable=true` 튜토리얼 샘플 종목만 허용한다. 선택 시
  실행 anchor 시각, tutorial date, seed, generator version을 영속하고 가상 시각을 12:00부터 시작한다.
- [ ] TUTORIAL-FLOW-007: 샘플 종목 주문은 서버가 사용자·시장·선택 종목으로 현재 실행 세대를 찾아 귀속한다.
  attempt 밖 일반 주문이나 과거 실행 주문을 현재 실행 evidence로 인정하지 않는다.
- [ ] TUTORIAL-FLOW-008: 사용자에게 사전 의도 입력 폼을 노출하거나 완료 전제로 요구하지 않는다. 현재 실행의
  최초 FILLED BUY 때 서버가 entry price를 snapshot하고 `stopLossPrice=entryPrice×0.97`,
  `takeProfitPrice=entryPrice×1.05`를 scale 8 `HALF_UP`으로 계산해 영속한다. snapshot은 이후 가격·재시작
  전까지 변경하지 않는다.
- [ ] TUTORIAL-FLOW-009: 순수 조회 `GET /api/education/practice/attempts/{market}/chart`의 응답은 시간순
  30개 일봉이다. 앞 29개는 seed·generator version으로 생성한 완결 과거 일봉이고 마지막 하나는 선택
  시점의 가상 12:00에서 시작한 현재 tutorial-day 일봉이다. GET은 주문 체결·attempt 상태·예약·원장을
  변경하거나 settlement를 암묵 실행하지 않는다.
- [ ] TUTORIAL-FLOW-010: 가상 clock은 `floor((요청시각-anchorAt)/3초)`분이며, 미래 분을 미리 노출하지
  않는다. 현재 일봉의 open은 첫 가상 분 가격, high/low는 공개된 분 가격의 최대/최소, close는 마지막 공개
  분 가격이다. 동일 attempt·실행 세대·가상 분은 reload·재기동·다중 인스턴스에서도 같은 OHLC와 canonical
  price를 반환한다. 클라이언트는 `POST /api/education/practice/attempts/{market}/tick`을 3초마다 호출하고,
  이 명시적 action만 해당 요청 시점 가상 분의 canonical price로 pending 교육 주문 정산과 live update를
  실행한다. 같은 가상 분의 중복 tick은 이미 정산된 결과를 중복 체결하지 않는다.
- [ ] TUTORIAL-FLOW-011: 화면의 현재가, 샘플 시장가·교육 지정가 체결 판정, tick 정산, 관찰, 재시작 보상
  매도는 모두 같은 가상 분의 canonical tutorial price를 사용한다. 차트와 실제 튜토리얼 체결 가격이
  갈라지지 않으며 GET chart가 빠르게 반복돼도 체결 부수효과는 생기지 않는다.
- [ ] TUTORIAL-FLOW-012: STOCK·CRYPTO의 상태 전이, 재시작, 위험 스냅샷, 차트 계약은 동일하다. 완료는 기존
  시장별 tutorial key와 불변 completion을 재사용하며 최초 완료 보상은 시장별 정확히 한 번만 지급한다.
- [ ] TUTORIAL-FLOW-013 (이슈 #421 추가): 진행 조회의 attempt evidence는 이번 실행의 매매 결과를 함께
  반환한다. 매수 뒤에는 수량 가중평균 매수 체결가를, 매도 뒤에는 수량 가중평균 매도 체결가·실현손익
  금액·수익률·매도가의 기준선 대비 위치 판정을 채운다. 매도 전에는 매수 체결가를 제외한 네 값이 모두
  `null`이다. 실현손익과 수익률은 매수·매도 수수료가 모두 반영된 원장 값(`trades.realized_pnl`) 기준이고
  체결가는 수수료를 포함하지 않는 단가이며, 계약이 이 구분을 명시해 클라이언트가 단가로 손익을 다시
  계산하지 않게 한다. 기준선 대비 위치는 서버가 판정한다 — 손절 -3%·익절 +5%는 서버가 만든 교육용
  규칙이므로 경계 포함 규칙이 클라이언트에 복제되면 안 된다. 스키마 변경 없이 기존 원장으로 계산한다.

## 비즈니스 규칙

- attempt 잠금이 현재 실행 정리·종목 선택·주문 귀속·최초 위험 snapshot 생성의 직렬화 기준이다.
- 실행 세대는 증가만 한다. 재시작 전 실행의 원장과 risk snapshot은 감사 근거로 남되 현재 진행 판정에서는
  제외한다.
- 재시작 정리 순서는 `attempt → 현재 실행 pending order(id ASC) → account → holding`이다. 취소·예약 반환·
  필요 시 보상 SELL·attempt 초기화·실행 세대 증가는 하나의 DB 트랜잭션이다.
- 보상 SELL 수량은 이 실행의 순체결수량과 현재 샘플 holding 가용수량 중 작은 값이 아니라 정확히
  순체결수량이어야 한다. 불일치는 데이터 정합성 오류로 전체 재시작을 롤백하며 다른 보유를 대신 팔지 않는다.
- 기존 5분 제한과 샘플 종목의 매도·복기 완료 조건은 유지하되, 만료된 미완료 attempt도 같은 재시작 계약을
  사용한다. 완료된 attempt는 시간 경과로 회귀하지 않는다.
- 기존 `POST /api/education/practice/intentions`는 이번 작업에서 삭제하지 않는다. 새 attempt 흐름과
  프론트엔드는 이를 호출하지 않으며, 후속 제거는 별도 호환성 결정으로 다룬다.

## 범위 제외

- 실제 종목 매매, 실제 주식·코인 시세, OCO 경로 변경
- 기존 주문·체결 원장, completion, reflection, reward 기록 삭제·수정
- 완료 사용자의 보상 재지급 또는 쓰기 가능한 두 번째 attempt 생성
- 샘플 종목 이외 주문의 자동 취소·보상 매도
- 외부 실시간 데이터로 과거 29개 일봉을 수집하거나 원본 데이터 다운로드를 제공하는 기능
- 기존 사전 의도 API의 물리 삭제와 ADR-0012의 favorite 저장 정책 변경
- 복수 차트, 분봉/주봉/월봉 전환, SSE·WebSocket push; 클라이언트는 GET을 재조회한다.

## 완료 조건

- [ ] 두 시장에서 최초 시작·종목 선택·매수·자동 위험 snapshot·차트·매도·복기·완료가 동작한다.
- [ ] 미완료 재시작이 현재 실행 pending 예약과 순체결수량만 원자 정리하고 새 실행 세대로 돌아간다.
- [ ] 완료 재진입이 `REPLAY`이며 completion·reward·ledger가 변하지 않는다.
- [ ] 차트 29+1 구성, 순수 GET, 명시적 3초 tick 정산, 3초=1분 clock, reload·재기동 결정성이 자동
  테스트로 확인된다.
- [ ] 다른 사용자·시장·실행 세대·일반 주문 격리와 재시작 경합이 MySQL Testcontainers로 확인된다.
- [ ] 백엔드 실제 mapping과 API 문서, 프론트 companion이 같은 계약을 사용한다.
