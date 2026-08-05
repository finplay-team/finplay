# Spec: 3단계 투자 실습 튜토리얼

> 상태: spec·계획 API 계약 확정, candidate 1~4는 구현됐고 #193에서 즐겨찾기·사전 의도 저장을 인메모리로 전환·튜토리얼 합성 시세를 추가 완료, 나머지 production 구현 미착수
>
> 이 문서의 OCO·관찰 규칙은 주식 체결 재생 세션 기준으로 확정돼 있다. **코인 시장 실습의 정본은 `docs/specs/020-coin-practice-tutorial`이다** — 튜토리얼 key 분리, GTC 수명, 세션 없는 잠금 순서, 소수 수량 비교는 그 문서가 소유한다.

## 개요
1차 고도화(PRD 2차 MVP)의 교육 범위는 사용자가 실제 FinPlay 도메인 API를 사용해 계획·매수·예약·관찰·복기를 경험하는 3단계 실습이다. 서버는 실제 즐겨찾기, 체결, OCO exit plan과 관찰·복기 기록을 연결해 진행 상태를 판정하며 클라이언트의 완료 주장만으로 단계를 완료하지 않는다.

기존 8개 투자 지식 과정(투자와 위험, 주식과 코인의 차이, 주문과 체결, 시장가와 지정가, 평가손익과 실현손익, 수수료와 수익률, 분산투자, 투자 계획과 복기)과 객관식 문항·과정별 배지·`INVESTMENT_BEGINNER`·RAG 교육 코치는 2차 고도화(PRD 3차 MVP)로 이동한다. #158은 이 spec 확정만 수행해 production 코드, Controller, 실제 라우트 문서를 변경하지 않았다. 현재 candidate 1 즐겨찾기 등록 API는 #163에서 구현됐다.

## 사용자 시나리오
- 로그인 사용자는 종목을 즐겨찾기에 등록하고 본인 목록에서 확인해 1단계를 완료한다.
- 로그인 사용자는 손절·익절 라인과 수량을 먼저 기록하고, 같은 종목을 기존 시장가 주문 API로 매수한 뒤 한 번의 OCO exit plan을 예약하고 목록에서 확인해 2단계를 완료한다.
- 로그인 사용자는 서버 가격 관찰 조건 하나를 충족한 뒤 정답 없는 복기 질문에 답해 3단계를 완료한다. 가격이 계획선에 가까워지지 않아도 일정 시간에 걸친 반복 관찰로 완료할 수 있다.

## 요구사항
- [ ] EDU-PRACTICE-001: 튜토리얼은 아래 3단계만 순서대로 제공한다.
- [ ] EDU-PRACTICE-002: 1단계는 즐겨찾기 등록 성공과 인증 사용자 본인 목록 포함을 서버가 모두 확인해야 완료한다.
- [ ] EDU-PRACTICE-003: 2단계는 손절·익절·수량 사전 기록, 이후 시장가 매수 체결, 동일 보유분 OCO exit plan 생성, 예약 목록 포함을 서버가 모두 확인해야 완료한다.
- [ ] EDU-PRACTICE-004: 기존 `POST /api/orders`의 시장가 주문은 예약 주문이 아니라 요청 시 즉시 체결되는 진입 주문이다.
- [ ] EDU-PRACTICE-005: OCO exit plan은 PRICE 또는 PERCENT intention에서 확정한 하나의 보유 수량에 `currentPrice >= takeProfitPrice` 익절과 `currentPrice <= stopLossPrice` 손절 조건을 함께 묶는다.
- [ ] EDU-PRACTICE-006: OCO 한쪽이 체결되거나 사용자가 plan을 취소하면 다른 조건도 원자적으로 취소되고 예약 수량은 정확히 한 번만 체결 또는 반환된다.
- [ ] EDU-PRACTICE-007: 3단계 복기 질문에는 정답·오답과 보상 판정이 없으며 자유 응답 저장 성공만 서버가 확인한다.
- [ ] EDU-PRACTICE-008: 클라이언트가 임의로 단계를 완료시키는 API나 `completed=true` 입력을 제공하지 않는다.
- [ ] EDU-PRACTICE-009: 1차 고도화 실습에는 배지·포인트·가상 시드머니 등 보상과 LLM을 사용하지 않는다.
- [ ] EDU-PRACTICE-010: OCO 생성은 서버 유효 현재가를 baseline으로 저장하며 유효 시세가 없으면 plan·예약 흔적 없이 거부한다.
- [ ] EDU-PRACTICE-011: 전체 완료는 복기 저장 트랜잭션에서 불변 기록으로 확정하고 이후 favorite 삭제나 plan 종결로 회귀하지 않는다.
- [ ] EDU-PRACTICE-012: 클라이언트 관찰 POST는 본인 `PENDING` exit plan에만 허용하고 terminal plan의 final observation은 서버 종결 트랜잭션만 생성한다.
- [ ] EDU-PRACTICE-013: favorite부터 OCO까지 같은 사용자·종목 chain과 intention·buyTrade·exitPlan의 수량 snapshot equality를 서버가 검증하고 중간 evidence 누락·불일치를 거부한다.

## 3단계 정의와 완료 증거

### 1단계 — 종목을 관심 대상으로 정하기
- 사용자는 선행 즐겨찾기 API로 거래 가능한 종목 하나를 등록한다.
- 등록 후 본인 즐겨찾기 목록에서 같은 `instrumentId`를 확인한다.
- 서버 완료 증거는 같은 사용자의 즐겨찾기 행 존재다. `GET /api/favorites` 호출 여부나 별도 확인 시각은 증거로 저장하지 않는다. 목록 응답에 같은 `instrumentId`가 보이는지는 API 테스트와 UX 수용 기준으로 검증한다.

### 2단계 — 먼저 계획하고 시장가 진입과 OCO 청산을 예약하기
- 사용자는 매수 전에 `instrumentId`, `quantity`와 PRICE 방식의 `stopLoss`·`takeProfit` 또는 PERCENT 방식의 `stopLossRate`·`takeProfitRate`를 실습 의도로 기록한다. 기존 타입 생략+가격 요청은 PRICE로 호환하며 상세 tagged union은 `docs/specs/019-exit-price-policy`를 따른다. **PERCENT는 019 구현 전까지 production에 없으며 현재는 PRICE(또는 타입 생략) 요청만 실제로 받을 수 있다.**
- intention의 `instrumentId`는 현재 존재하는 step 1 본인 favorite의 `instrumentId`와 같아야 한다. favorite가 없거나 다른 종목이면 409 `PRACTICE_STEP_LOCKED`로 intention 생성을 거부한다.
- 최초 intention 생성 트랜잭션은 `(user_id, tutorial_key)`별 `practice_progresses` 행을 상태를 덮어쓰지 않는 MySQL 원자 upsert로 확보한 뒤 재조회·잠그고, 대상 favorite의 사용자 단위 in-memory 락을 이어서 획득해 intention 저장까지 유지한다. upsert는 unique 예외를 발생시키지 않고 이미 완료된 progress도 변경하지 않는다. favorite DELETE도 같은 in-memory 락을 사용해 직렬화하며, 삭제 선행은 409 `PRACTICE_STEP_LOCKED`·intention 무저장, intention 선행은 201 뒤 DELETE 204다. 동시 intention 요청도 progress 한 행으로 수렴한다.
- 이후 기존 `POST /api/orders`에 `orderType="MARKET"`, `side="BUY"`로 같은 종목·수량을 주문한다. 이 시장가 주문은 기존 계약대로 즉시 `FILLED` 체결되며 예약 상태가 아니다.
- 서버는 실제 매수 `tradeId`와 체결가 `entryPrice`를 기준으로 PRICE 값을 복사하거나 PERCENT 값을 계산해 `0 < stopLossPrice < entryPrice < takeProfitPrice`을 검증한다.
- 사용자는 같은 `instrumentId`의 holding에서 intention·매수 체결과 정확히 같은 `quantity` snapshot으로 OCO exit plan 하나를 생성한다. 손절과 익절을 별도 매도 예약 두 건으로 만들 수 없다.
- OCO 생성 시 step 1 favorite가 아직 존재해야 하며 favorite → intention → `buyTradeId` → holding → exit plan의 사용자와 `instrumentId`가 모두 같아야 한다. exact quantity equality는 `intention.quantity == buyTrade.quantity == exitPlan.quantity`에만 적용한다.
- holding은 생성 시 owner·instrument 일치와 `availableQuantity >= exitPlan.quantity`만 검증한다. 기존 또는 추가 매수로 `holding.totalQuantity`가 snapshot quantity와 달라도 정상이다. favorite 삭제 또는 chain 누락·불일치는 409 `PRACTICE_EVIDENCE_MISSING`이며 plan·예약을 남기지 않는다.
- 하나의 사전 의도에는 OCO exit plan을 한 건만 연결한다. 같은 의도·동일 fingerprint의 새 멱등키 요청은 기존 plan과 새 key mapping으로 200 수렴하고 추가 수량을 예약하지 않는다. 관측한 모든 key는 영속화되어 다른 요청·intention 재사용을 409로 막는다.
- 서버 완료 증거는 사전 의도 기록 시각이 매수 체결보다 앞서고, 같은 값이 실제 체결·보유·OCO plan에 연결된 사실이다. `GET /api/exit-plans` 호출 여부는 완료 증거가 아니며 plan이 본인 목록 응답에 보이는지는 API 테스트와 UX 수용 기준으로 검증한다.

### 3단계 — 가격 움직임을 견디며 복기하기
- OCO 생성 트랜잭션은 서버 유효 현재가를 `baselinePrice`, 조회 시각을 `baselineObservedAt`으로 저장한다. 클라이언트 가격은 받지 않는다.
- 복기를 열어 주는 관찰 증거는 다음 A·B·C 중 하나다.
  - A: plan 생성 후 서버 유효 현재가가 `baselinePrice`보다 손절선 또는 익절선까지의 절대 거리에서 가까워진 관찰 1회.
  - B: plan 생성 후 서버 유효 현재가 관찰 3회가 존재하고 첫 관찰과 마지막 관찰이 최소 2분 범위에 있음. 가격이 어느 경계에도 가까워질 필요는 없다.
  - C: 익절·손절 체결 또는 주식 세션 만료 트랜잭션이 서버의 해당 이벤트 가격·시각을 final observation으로 자동 기록함. 주식 만료에 유효 종료 가격이 없으면 마지막 유효 서버 가격을 사용하며, 그것도 없을 수 없는 이유는 plan 생성 baseline이 필수이기 때문이다.
- 고정 질문은 `지금 팔고 싶나요? 그렇다면 왜 그런가요? 계획한 손절·익절 라인과 비교해 적어보세요.`다.
- 답은 `@NotBlank @Size(max=2000)`에 해당하는 자유 텍스트다. whitespace-only는 거부하고 raw Java `String.length()`가 2000 이하여야 하며 trim·공백 제거 없이 원문을 저장한다. 정답, 점수, 배지, 금전성 보상과 LLM 피드백은 없다.
- A·B·C 중 하나와 그 이후 저장된 복기 답변이 3단계 완료 증거다. plan이 체결·만료된 뒤에도 복기를 저장할 수 있다. 사용자 취소는 자동 final observation을 만들지 않지만 취소 전에 A 또는 B를 충족했다면 복기를 허용한다.
- `POST /api/education/practice/observations`는 인증 사용자 본인의 `PENDING` plan만 받는다. `CANCELLED`, `FILLED_TAKE_PROFIT`, `FILLED_STOP_LOSS`, `CANCELLED_EXPIRED`에는 409 `EXIT_PLAN_NOT_PENDING`이며 클라이언트가 A·B 관찰을 추가할 수 없다.
- terminal plan의 `FINAL_EVENT` observation은 익절·손절 체결 또는 주식 만료 서버 트랜잭션만 생성한다. 클라이언트 요청으로 `FINAL_EVENT` 유형이나 가격·시각을 지정할 수 없다.

## 즐겨찾기·사전 의도 상태 정책(Issue #193)
- 1단계 즐겨찾기와 2단계 사전 의도(`practice_intentions`)는 서버 인스턴스의 메모리에만 보관한다(ADR-0012). API 경로·요청/응답 형식·오류 코드는 바뀌지 않는다.
- 서버 재시작 또는 배포 시 진행 중이던 즐겨찾기·사전 의도가 사라질 수 있다. 사용자는 즐겨찾기 재등록부터 다시 시작해야 하며, 이는 사용자와 합의된 감수 사항이다.
- `practice_progresses`(완료 여부만 담는 진행 상태)와 `practice_completions`(불변 완료 기록)는 계속 DB에 남는다. 즐겨찾기·의도가 재시작으로 사라져도 이미 완료된 튜토리얼의 `COMPLETED` 상태는 회귀하지 않는다 — 다만 완료 전에 재시작이 일어나면 사용자는 evidence가 사라진 단계부터 다시 진행해야 한다(기존 "완료 전 evidence 삭제 시 재진행" 규칙과 동일하게 취급한다).
- 다중 인스턴스 배포에서는 인스턴스별로 메모리 상태가 분리된다. 이 spec은 단일 인스턴스 배포를 전제로 하며, sticky session 없는 다중 인스턴스 환경에서의 동작은 범위 밖이다(범위 제외 참고).

## 튜토리얼 전용 합성 시세(Issue #193)
- 사용자는 2단계에서 사전 의도를 기록하거나 매수 전후로, 화면에서 참고용으로 움직이는 차트를 본다. 이 차트는 실제 시세(KIS 재생·빗썸)와 무관한 서버 생성 랜덤워크이며 실제 주문 체결가·평가손익 계산에는 쓰이지 않는다.
- 사용자는 종목별로 합성 시세 시계열 조회를 요청하면, 3초 간격 틱으로 5분 분량(약 100틱)의 가격 배열을 즉시 받는다. 같은 요청을 다시 보내면 새로 생성된 다른 시계열을 받을 수 있다 — 서버는 특정 시드나 이전 응답과의 연속성을 보장하지 않는다.
- 이 합성 시세는 튜토리얼 진행 판정(evidence)에 사용하지 않는다. 3단계 관찰(A·B·C)과 OCO 트리거는 여전히 실제 시장 가격 공급자만 사용한다.

## 선행 즐겨찾기 API 계약
- `POST /api/favorites`: `{"instrumentId": 1}`을 받아 거래 가능한 종목을 본인 즐겨찾기에 등록하고 201을 반환한다. `(userId, instrumentId)`는 유일하며 재등록은 409 `DUPLICATE_RESOURCE`다.
- `GET /api/favorites`: 본인 즐겨찾기를 등록 최신순으로 반환한다. 각 항목은 `instrumentId`, `market`, `symbol`, `name`, `createdAt`을 포함한다.
- `DELETE /api/favorites/{instrumentId}`: 본인 즐겨찾기를 해제하고 204를 반환한다. 없는 종목은 404 `FAVORITE_NOT_FOUND`, 타인 행은 노출하지 않는다.
- 즐겨찾기 등록·목록·해제는 주문·계좌·보유 상태를 변경하지 않는다.

## OCO exit plan 비즈니스 규칙
- 2차 MVP의 OCO는 이 튜토리얼에서만 사용한다. `intentionId` 없는 일반 OCO 생성은 지원하지 않으며 일반 리스크 관리 OCO는 3차 MVP 후보 범위다.
- 생성 입력은 positive `Long`의 `intentionId`, `buyTradeId`, `instrumentId`, `DECIMAL(30,8)` 범위의 양수 `quantity`이며 `Idempotency-Key` header가 필수다. 가격·rate는 다시 받지 않고 사용자 단위 in-process 락 안에서 조회한 intention에서 읽는다.
- `buyTradeId`는 인증 사용자 본인의 `FILLED` 시장가 매수 체결이어야 하며 `instrumentId`와 실제 holding이 일치해야 한다.
- `exitPlan.quantity`는 intention·buyTrade quantity와 정확히 같고, 생성 시 holding의 `availableQuantity` 이하여야 한다. plan 하나가 그 수량을 한 번만 예약한다.
- PRICE는 intention의 가격에 대해, PERCENT는 실제 진입 체결가로 계산한 scale 8 가격에 대해 `0 < stopLossPrice < entryPrice < takeProfitPrice`여야 한다. 계산·반올림과 입력 조합은 `docs/specs/019-exit-price-policy`를 따르며 범위가 깨지면 거부한다.
- 생성 트랜잭션은 서버의 거래 가능한 유효 현재가를 조회해 `baselinePrice`, `baselineObservedAt`으로 plan에 저장한다. 유효 시세가 없으면 409 `PRICE_UNAVAILABLE`로 거부하고 plan·condition·수량 예약을 하나도 남기지 않는다.
- `trades.stock_replay_session_id`를 nullable FK로 추가한다. 주식 체결은 체결 당시 현재 replay session id를 반드시 저장하고 코인 체결은 null이다.
- 주식은 `buyTradeId.stockReplaySessionId`와 현재 `OPEN` replay session이 같고 15:30 전일 때만 plan을 생성한다. plan에 같은 `replaySessionId`를 저장한다. 이 FK migration과 주식 fill 기록 변경은 OCO보다 먼저 배포한다. 코인은 session 연결 없이 GTC다.
- 익절 조건은 `currentPrice >= takeProfitPrice`, 손절 조건은 `currentPrice <= stopLossPrice`다. 체결가격은 트리거 시점의 공통 현재가를 사용하는 시장가 청산으로 처리하며 특정 체결가격을 보장하지 않는다.
- 두 조건은 `PENDING` plan 하나에 속한다. 한쪽 조건을 각각 별도 수량 예약으로 계산하지 않는다.
- 가격 갱신 시 replay session(주식만) → holding → plan 순서로 비관 잠금한다. 충족된 한 조건으로 전량 시장가 매도 체결을 생성하고 plan을 `FILLED_TAKE_PROFIT` 또는 `FILLED_STOP_LOSS`로 종결한다. 반대 조건은 같은 트랜잭션에서 `CANCELLED_BY_OCO`가 된다.
- `stopLossPrice < takeProfitPrice`이고 단일 가격을 평가하므로 한 이벤트가 두 조건을 동시에 만족할 수 없다. 중복 또는 역순으로 도착한 가격 이벤트가 경합하면 plan 잠금에서 최초 커밋한 이벤트만 종결 승자가 되고 후속 이벤트는 terminal plan을 보고 아무 작업 없이 skip한다.
- 기존 시장가 SELL과 일반 지정가 SELL도 `totalQuantity`가 아니라 공통 예약 원장에서 계산한 `availableQuantity = totalQuantity - reservedQuantity`를 검증한다. OCO 예약분을 포함한 SELL은 `INSUFFICIENT_QTY`로 거부해 이중 매도를 막는다.
- plan 취소와 가격 트리거가 경합하면 같은 plan 잠금에서 먼저 커밋한 상태 전이만 성공한다. 취소 승자는 예약 수량을 한 번 반환하고, 체결 승자는 예약을 매도에 한 번 소비한다. 패자는 최신 종결 상태를 확인하고 409 `EXIT_PLAN_NOT_PENDING`을 반환한다.
- plan 생성·트리거·취소는 `(holding, reservedQuantity)` 정합성을 한 DB 트랜잭션에서 유지한다. 부분 체결, 개별 조건 수정, 조건 한쪽만 취소는 범위 밖이다.
- `exit_plans`의 `(user_id, intention_instance_key)`와 별도 `exit_plan_idempotency_keys`의 `(user_id, idempotency_key)`는 유일하다. `intention_instance_key`는 ADR-0012의 재시작별 숫자 ID 재사용과 영속 plan 충돌을 막는 내부 UUID이며 응답용 `intentionId`와 다르다. key는 `UUID.fromString` 성공 후 `UUID.toString()` 36자 lowercase canonical 값으로 normalize해 저장하며 fingerprint에는 포함하지 않는다. idempotency key mapping을 현재 intention보다 먼저 조회하는 key-first 판정을 쓴다: key hit이고 request hash가 같으면 재시작으로 intention이 유실됐거나 숫자 ID가 다른 instance에 재사용돼도 instance를 비교하지 않고 과거 plan을 그대로 200으로 재현하며, key hit인데 hash가 다르면 즉시 409 `IDEMPOTENCY_CONFLICT`다. key miss일 때만 현재 intention을 instance key로 해석해 instance plan을 조회·생성한다. 최초 plan과 original key mapping, 동일 intention instance·fingerprint의 새 key mapping은 각각 후보 7의 한 transactional attempt에서 원자 저장한다. unique loser attempt는 부분 저장 없이 전체 rollback하고, 바깥 coordinator가 winner commit 뒤 새 transaction에서 key mapping을 재조회해 위 key-first 규칙으로 200/409를 수렴한다. 실패 transaction은 재사용하지 않는다.
- 트리거 판정은 시장별 가격 공급자가 거래 가능한 값으로 인정한 유효 가격 갱신 이벤트에서만 수행한다. 유효 이벤트가 없거나 가격 공급자가 장애인 동안 plan은 별도 `TRIGGERED` 중간상태 없이 `PENDING`을 유지한다.
- 주식 plan은 해당 replay session의 마지막 유효 가격 이벤트 처리 후 15:30 세션 종료에 아직 `PENDING`이면 `CANCELLED_EXPIRED`로 자동 종결하고 두 조건을 취소하며 예약 수량을 한 번 반환한다. 코인 plan은 자동 만료 없는 GTC다.
- 주식 생성·가격 트리거·사용자 취소·세션 만료는 replay session → holding → plan 순서로 잠근다. 생성 시 plan은 아직 없으므로 session과 holding을 잠근 뒤 생성한다. 15:30 만료와 생성이 직렬화되어 만료가 먼저면 생성은 `EXIT_PLAN_SESSION_CLOSED`로 거부되고, 생성이 먼저면 해당 session 만료 scan 대상이 된다. 코인은 holding → plan 순서다.

## 튜토리얼 판정 규칙
- 진행 상태는 `NOT_STARTED`, `IN_PROGRESS`, `COMPLETED`이며 세 단계는 순차 잠금한다.
- `GET /api/education/practice`가 실제 즐겨찾기·의도·체결·OCO·관찰·복기 리소스를 서버에서 조회해 단계별 `evidence`와 상태를 계산한다. 모든 GET은 쓰기를 하지 않는다.
- 완료 전 복수 chain은 qualifying observation이 있는 유효 chain을 우선하고 그 안에서 `exitPlan.reservedAt ASC, exitPlan.id ASC` 첫 chain을 선택한다. 그런 chain이 없으면 전체 유효 chain에서 같은 정렬의 첫 chain, 유효 chain 자체가 없으면 `favorite.createdAt ASC, favorite.id ASC` 첫 favorite를 선택한다. 단계별 evidence는 선택한 한 chain 안에서만 구성한다.
- 1단계 완료는 본인 favorite 존재만으로 계산한다. 2단계 완료는 그 favorite의 사용자·종목에 연결되고 시각·수량 규칙을 만족하는 intention → buyTrade → exitPlan chain 존재로 계산한다. favorite·OCO 목록 API 호출 여부 자체는 어느 단계의 완료 증거도 아니다.
- 전체 완료 전 단계 상태는 현재 실제 evidence 존재로 계산한다. favorite 삭제나 완료 전 OCO 취소로 evidence가 사라지면 해당 단계를 다시 진행해야 할 수 있다.
- 복기 저장 트랜잭션은 1·2단계의 현재 evidence와 A·B·C 관찰 증거 중 하나를 다시 검증하고 사용자별 불변 practice completion 기록을 최초 한 번 생성한다. 저장된 `holdingId`의 owner·instrument와 original intention·buyTrade·exitPlan quantity snapshot chain은 검증하지만 현재 holding quantity는 재검증하지 않는다. terminal 체결 뒤 0이거나 추가 매수로 달라져도 정상이다. 완료 기록이 생기면 overall 상태는 영구 `COMPLETED`이며 이후 favorite 삭제, plan 체결·취소·만료에도 회귀하지 않는다. GET은 이 기록을 조회할 뿐 쓰지 않는다.
- 복기 저장은 사용자·튜토리얼 공통 `practice_progresses` 행을 가장 먼저 `FOR UPDATE`로 잠그고, 이어서 현재 favorite → 선택한 `practice_intention` → exit plan을 잠근 뒤 evidence를 검증한다. `progress → favorite → intention → exit plan` 잠금을 reflection·completion 저장과 progress 완료 커밋까지 유지한다. favorite DELETE도 같은 favorite 잠금을 사용하므로 삭제 선행은 409 `PRACTICE_EVIDENCE_MISSING`과 reflection·completion·progress 완료 무저장, 복기 선행은 201 완료 커밋 뒤 DELETE 204이며 immutable completion은 이후 삭제에도 유지된다.
- progress가 이미 `COMPLETED`면 즉시 409 `PRACTICE_ALREADY_COMPLETED`를 반환한다. `IN_PROGRESS`인 최초 요청만 reflection 1행, completion 1행 저장과 progress의 `COMPLETED` 전이를 같은 트랜잭션에서 수행하고 201을 반환한다. 서로 다른 intention·plan의 동시 요청도 공통 progress에서 직렬화되며, 대기 후 완료를 본 요청은 새 답변을 저장하지 않는다.
- progress와 completion의 `(user_id, tutorial_key)` 유일 제약, reflection의 `(user_id, exit_plan_id)` 유일 제약을 최종 중복 방어선으로 둔다.
- 실습 전용 쓰기 API는 의도·가격 관찰·복기만 받는다. 단계 완료 boolean, tradeId 조작에 의한 소유권 우회, 클라이언트 제공 현재가는 받지 않는다.
- `PRACTICE_STEP_LOCKED`는 intention 생성 시 step 1 favorite가 없거나 선택 종목이 favorite와 다를 때 사용한다. `PRACTICE_EVIDENCE_MISSING`는 OCO 생성 또는 복기 저장 시 favorite → intention → trade → holding → OCO의 owner·instrument, intention·trade·plan quantity snapshot, 저장된 holdingId 또는 A·B·C 관찰 증거가 누락·불일치할 때 사용한다.

## 범위 제외
- 인메모리 즐겨찾기·사전 의도 상태의 다중 인스턴스 동기화(sticky session, 분산 캐시, 세션 클러스터링). 단일 인스턴스 배포를 전제로 한다.
- 튜토리얼 전용 합성 시세의 재현성·시드 고정, 다른 사용자와의 시계열 공유, 실제 시세 데이터 연동.
- 8개 투자 지식 과정(투자와 위험, 주식과 코인의 차이, 주문과 체결, 시장가와 지정가, 평가손익과 실현손익, 수수료와 수익률, 분산투자, 투자 계획과 복기), 객관식 정답 판정, 과정별 배지, `INVESTMENT_BEGINNER`, RAG 교육 코치 — PRD 3차 MVP.
- LLM 설명, AI 피드백, 투자 추천, 가격 예측, 예상 수익 생성.
- 별도 익절 지정가와 손절 주문 두 건에 같은 수량을 각각 예약하는 모델.
- 실제 증권사 주문, 부분 체결, 슬리피지 보장, OCO 조건 수정·한쪽 취소.
- intention 없는 일반 리스크 관리 OCO — PRD 3차 MVP 후보.
- 현재 이슈에서의 production·Controller·DB migration 및 계획 API를 실제 제공 라우트로 표시하는 작업.

## 문서 완료 조건 (#152·#158)
- [x] #158에서 10개 계획 API의 method·URL·header·path/query/body·응답 필드·상태·오류와 DTO nullable 규칙을 구현 가능한 수준으로 확정한다.
- [x] #158에서 `favorites` 물리 스키마를 실제 `users`·`instruments` migration 타입과 삭제 정책에 맞춰 확정한다.
- [x] #158에서 15개 후속 후보의 순서·의존성·테스트·문서 소유권과 착수 시 한 건씩 이슈를 만드는 규칙을 확정한다.
- [x] #158 검증에서 직접 대상 소유권 404와 내부 evidence 409를 분리하고, OCO key·intention fingerprint 수렴, 후보별 migration·트랜잭션·테스트 책임과 단계별 evidence 시각을 확정한다.
- [x] #158 검증에서 1·2단계 증거를 분리하고 candidate 12 진행 상태표와 실제 선행 DAG의 topological 착수 순서를 확정한다.
- [x] #158 closeout에서 모든 OCO 멱등 key mapping 영속화와 key/intention 교차 충돌 알고리즘, intention 생성과 favorite 삭제의 잠금 직렬화를 확정한다.
- [x] #158 최종 리뷰에서 unique loser의 전체 rollback·새 transaction reconciliation 경계와 UUID key canonical 저장 규칙을 확정한다.
- [x] #158 ultimate 리뷰에서 reflection의 progress → favorite → intention → plan 잠금과 비트랜잭션 retry coordinator의 실제 Spring transaction 경계를 확정한다.
- [x] PRD가 2차 MVP의 3단계 실습과 3차 MVP의 지식 교육·배지·RAG 코치를 구분한다.
- [x] 즐겨찾기 등록·목록·해제 선행 계약이 존재한다.
- [x] 시장가 진입이 즉시 체결이고 OCO만 예약이라는 차이가 문서 전체에서 일관된다.
- [x] 서버가 실제 도메인 증거를 연결하고 클라이언트 완료 주장을 받지 않는 정책을 확정한다.
- [x] 3단계 자유 복기에 정답·보상·LLM 판정이 없음을 확정한다.
- [x] #152·#158에는 production 변경이 없다.

## 후속 production 완료 조건
- [ ] OCO가 동일 owner·instrument, intention·trade·plan quantity snapshot equality, holding available capacity와 `0 < stopLossPrice < entryPrice < takeProfitPrice`을 검증하고 한 번만 수량을 예약한다.
- [ ] 생성 baseline과 주식 OPEN replay session 귀속을 검증하고 시세 없음·세션 종료 시 흔적 없이 거부한다.
- [ ] 중복·역순 가격 이벤트, 수동 시장가·지정가 매도, 취소·만료 경합에서 체결 또는 예약 반환이 정확히 한 번만 일어난다.
- [ ] favorite·OCO 목록 GET은 순수 조회이며 실제 리소스가 응답에 포함되는지는 API 테스트로 검증한다.
- [ ] 주식 미체결 OCO는 replay session 15:30에 예약을 한 번 반환하며 만료되고 코인은 GTC를 유지한다.
- [ ] 유효 가격 이벤트가 없으면 OCO는 중간상태 없이 `PENDING`을 유지한다.
- [ ] A·B·C 관찰 대안 중 하나로 복기를 저장할 수 있고 완료 후 실제 evidence 변경에도 overall 완료가 회귀하지 않는다.
- [ ] terminal plan에 클라이언트 관찰을 추가할 수 없고 서버 종결 트랜잭션만 `FINAL_EVENT`를 만든다.
- [ ] 동시·재시도 복기 중 최초 요청만 reflection·completion을 만들고 나머지는 409이며 답변 행이 늘지 않는다.
- [ ] 같은 사용자의 서로 다른 eligible plan에서 동시 복기해도 공통 progress 잠금으로 한 건만 201·저장되고 다른 한 건은 409·무저장이다.
- [ ] intention·OCO는 step 1 favorite와 같은 instrument chain만 허용하고 snapshot quantity equality와 holding capacity를 구분하며 favorite 삭제·불일치 시 정의된 409로 무흔적 거부한다.
- [ ] 주식 trade session FK와 공통 예약 원장·MARKET SELL 변경이 OCO endpoint보다 먼저 또는 같은 atomic release로 배포된다.

## 구현 경계
- 공통 예약 원장의 물리 모델은 `plan.md` 후보 6이 기존 지정가 spec·실제 holding schema와 함께 소유한다. 후보 6 완료 전 OCO와 LIMIT SELL을 활성화하지 않으므로 이 spec에 미확정 계약은 남지 않는다.
