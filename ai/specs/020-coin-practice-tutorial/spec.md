# Spec: 코인 투자 실습 튜토리얼 정책

> 상태: 문서 설계 확정. OCO 계열 production은 미착수이나, **튜토리얼 key market 분기(`COIN_PRACTICE_V1`)는 이미 구현됨**(이슈 #226) — `tasks.md` 참고.
>
> **이 spec은 OCO(예약형 손절·익절) 기반 코인 실습만 다룬다.** 차수는 **3차 MVP(2차 고도화)**다(2026-08-06 재확정 — 결정 경위 전문은 `ai/prd.md` §3이 정본). 설계(튜토리얼 key 분리, GTC 수명, 세션 없는 잠금 순서, 소수 수량 비교)는 그대로 유효하며 3차 착수 시점에 그 설계를 따른다.
>
> **2차 MVP에서 코인 실습을 실제로 완결할 수 있는 경로는 `ai/specs/026-market-order-practice-tutorial`이다** (2026-08-10 신설, OCO 없이 시장가·코인 지정가 매수로 완결). `026`은 이 문서의 튜토리얼 key 분리(`COIN_PRACTICE_V1`)와 scale 무관 수량 비교 규칙을 그대로 재사용한다 — 그 key 분기는 이미 production에 있다(이슈 #226, `PracticeIntentionService`). 진행조회는 필수 `market=STOCK|CRYPTO`로 한 시장만 선택하고, 3차 OCO는 별도 URL·완료 key를 사용한다(2026-08-10, 이슈 #308).
>
> **2차 코인 holding 경로의 가격원은 `ai/specs/030-coin-practice-price-runtime`이 부분 대체한다.** 아래 COIN-PRACTICE-008·009의 빗썸 가격/합성 시세 evidence 비사용은 3차 OCO와 세션 없는 기존 주문에 계속 유효하다. 교육 가격 세션으로 만든 지정가 trade·holding만 030의 영속 세션 가격으로 체결·관찰한다. 기존 표시 전용 `/synthetic-prices`는 여전히 evidence가 아니다.

## 개요

3단계 투자 실습 튜토리얼(`ai/specs/016-investment-education-policy`)은 계약이 주식 기준으로 확정돼 있다. OCO exit plan의 생성·트리거·만료가 주식 체결 재생 세션(`trades.stock_replay_session_id`, 15:30 종료)에 묶여 있고, 3단계 관찰 증거 C도 "익절·손절 체결 또는 **주식 세션 만료**"로 정의된다. 코인은 그 규칙 안에서 "session 연결 없이 GTC", "자동 만료 없음", "holding → plan 잠금"이라는 예외 문구로만 등장한다.

이 spec은 코인 시장 실습을 독립적으로 완결되는 튜토리얼로 확정한다. 주식 규칙을 수정하지 않고 코인 경로만 정의하며, 세션 경계가 없는 24시간 시장에서 튜토리얼 identity·GTC plan 수명·관찰 증거·소수 수량 비교를 확정한다. 016의 공통 규칙(클라이언트 완료 주장 불허, 서버 evidence 연결, 보상·LLM 미사용)은 그대로 상속한다.

production 구현은 이 이슈 범위가 아니다 — `019`(#199)와 같은 문서 설계 전용 spec이며, 착수 시점은 위 차수 서술대로 3차 MVP다(2026-08-06 재확정 — 2차 MVP 우선 production 결정은 철회됨).

## 사용자 시나리오

- 로그인 사용자는 코인 종목을 즐겨찾기에 등록해 코인 실습 1단계를 완료한다.
- 로그인 사용자는 손절·익절과 수량을 먼저 기록하고, 같은 코인을 시장가로 매수한 뒤 OCO exit plan 하나를 예약해 2단계를 완료한다. 코인 시장은 개장·마감이 없어 시각과 무관하게 예약할 수 있다.
- 로그인 사용자는 가격 관찰 조건 하나를 충족한 뒤 자유 복기를 저장해 3단계를 완료한다. 코인 plan은 자동 만료되지 않으므로 체결되지 않아도 반복 관찰로 복기를 열 수 있다.
- 로그인 사용자는 주식 실습을 이미 완료했더라도 코인 실습을 처음부터 별도로 진행할 수 있다.

## 요구사항

- [ ] COIN-PRACTICE-001: 코인 실습은 `tutorialKey=COIN_PRACTICE_V1`로 주식 실습(`INVESTMENT_PRACTICE_V1`)과 분리된 독립 튜토리얼이며 진행·완료가 서로 간섭하지 않는다.
- [ ] COIN-PRACTICE-002: 코인 실습 chain의 모든 리소스(favorite·intention·buyTrade·holding·exitPlan)는 `market=CRYPTO` 종목 하나로 일치해야 한다.
- [ ] COIN-PRACTICE-003: 코인 OCO는 체결 재생 세션에 귀속되지 않고 `trades.stock_replay_session_id`·`exitPlan.replaySessionId`는 null이며 세션 기반 오류·상태를 사용하지 않는다.
- [ ] COIN-PRACTICE-004: 코인 OCO는 자동 만료 없는 GTC이며 종결 경로는 익절 체결·손절 체결·사용자 취소 세 가지뿐이다.
- [ ] COIN-PRACTICE-005: 코인 경로의 잠금 순서는 세션 없이 `holding → plan`이고 복기 저장은 `progress → favorite → intention → plan`이다.
- [ ] COIN-PRACTICE-006: 코인 관찰 증거는 A·B를 그대로 사용하고 C는 익절·손절 체결 트랜잭션에서만 생성된다.
- [ ] COIN-PRACTICE-007: intention·buyTrade·exitPlan의 수량 snapshot equality는 scale 무관 수치 비교로 판정한다.
- [ ] COIN-PRACTICE-008: 코인 유효 가격은 빗썸 실시간 공급자가 거래 가능으로 인정한 갱신 이벤트이며 공급자 장애 중 plan은 중간 상태 없이 `PENDING`을 유지한다.
- [ ] COIN-PRACTICE-009: 튜토리얼 전용 합성 시세는 코인에서도 진행 판정 evidence로 사용하지 않는다.
- [ ] COIN-PRACTICE-010: 손절·익절 입력 방식과 가격선 확정은 `019`의 PRICE·PERCENT 정책을 그대로 사용한다.
- [ ] COIN-PRACTICE-011: 코인 실습에도 단계 완료 boolean·클라이언트 제공 현재가·보상·LLM 판정이 없다.

## 튜토리얼 identity

- 코인 실습의 `tutorial_key`는 `COIN_PRACTICE_V1`이다. `practice_progresses`·`practice_completions`의 `UNIQUE(user_id, tutorial_key)`가 key별로 한 행을 보장하므로 한 사용자가 주식·코인 실습을 각각 한 번 완료할 수 있다.
- `practice_progresses.tutorial_key`는 이미 `VARCHAR(50)`이고 unique 제약도 key를 포함하므로 **스키마 변경·migration이 필요하지 않다**. 현재 하드코딩된 단일 상수(`PracticeIntentionService.TUTORIAL_KEY`)를 종목 market으로 해석하는 규칙으로 바꾸는 것이 구현 범위다.
- key는 intention 생성 시 대상 종목의 `market`으로 결정한다. `STOCK`이면 `INVESTMENT_PRACTICE_V1`, `CRYPTO`이면 `COIN_PRACTICE_V1`이다. 클라이언트는 key를 입력하지 않는다.
- **기존 행은 재해석·백필하지 않는다.** 현재 구현은 market과 무관하게 `INVESTMENT_PRACTICE_V1` 행을 만들기 때문에, 코인 종목으로 진행한 기존 진행 상태가 그 key에 남아 있을 수 있다. 그 행은 주식 실습 진행으로 그대로 두고 코인 실습은 새 key에서 새로 시작한다 — 완료 기록은 회귀 금지 대상이므로 소급 이동하지 않는다.
- holding 기반 `GET /api/education/practice?market=STOCK|CRYPTO`와 OCO 기반 `GET /api/education/practice/oco?market=STOCK|CRYPTO`는 모두 `market`을 필수로 받아 한 시장의 튜토리얼만 응답한다. holding 기반 key는 `INVESTMENT_PRACTICE_V1|COIN_PRACTICE_V1`, OCO 기반 key는 `INVESTMENT_OCO_PRACTICE_V1|COIN_OCO_PRACTICE_V1`이며 서로 완료를 공유하지 않는다.

## 3단계 정의와 완료 증거 (코인)

### 1단계 — 코인 종목을 관심 대상으로 정하기

- 사용자는 `market=CRYPTO`인 거래 가능 종목 하나를 즐겨찾기에 등록한다.
- 서버 완료 증거는 같은 사용자의 코인 즐겨찾기 행 존재다. `GET /api/favorites` 호출 여부는 증거가 아니다(016과 동일).
- 주식 종목 즐겨찾기는 코인 실습 1단계 증거가 아니다. 코인 실습 진행 중 코인 favorite가 없으면 2단계 intention 생성은 409 `PRACTICE_STEP_LOCKED`다.

### 2단계 — 먼저 계획하고 시장가 진입과 OCO 청산을 예약하기

- 016의 순서(사전 의도 기록 → 시장가 매수 체결 → 동일 수량 OCO exit plan 생성)와 chain 검증을 그대로 따른다.
- **세션 조건이 없다.** 코인은 `buyTrade.stockReplaySessionId`가 null이므로 세션 일치 검증을 수행하지 않고, 15:30 이전 조건도 적용하지 않는다. `EXIT_PLAN_SESSION_CLOSED`는 코인 경로에서 발생하지 않는다.
- 생성 트랜잭션은 세션을 잠그지 않고 `holding → plan` 순서로 잠근다.
- 서버 유효 현재가를 `baselinePrice`·`baselineObservedAt`으로 저장한다. 유효 시세가 없으면 409 `PRICE_UNAVAILABLE`로 plan·condition·예약 흔적 없이 거부한다(016과 동일).
- 수량은 소수를 허용한다. `intention.quantity == buyTrade.quantity == exitPlan.quantity`는 아래 수량 비교 규칙을 따르고, holding은 `availableQuantity >= exitPlan.quantity`만 검증한다.
- 하나의 사전 의도에는 OCO plan 한 건만 연결하며 멱등 key 규칙과 `019`의 key-first 판정을 그대로 사용한다.

### 3단계 — 가격 움직임을 견디며 복기하기

- 관찰 증거는 다음 중 하나다.
  - A: plan 생성 후 서버 유효 현재가가 `baselinePrice`보다 손절선 또는 익절선까지의 절대 거리에서 가까워진 관찰 1회.
  - B: plan 생성 후 서버 유효 현재가 관찰 3회가 존재하고 첫 관찰과 마지막 관찰이 최소 2분 범위에 있음. 가격이 어느 경계에도 가까워질 필요는 없다.
  - C: 익절 또는 손절 체결 트랜잭션이 그 이벤트의 가격·시각을 `FINAL_EVENT` final observation으로 자동 기록함.
- **C의 세션 만료 경로는 코인에 존재하지 않는다.** 코인 plan은 자동 만료되지 않으므로 체결 없이 C가 생성되는 일이 없다. 체결되지 않는 코인 plan에서 복기를 여는 경로는 A와 B이며, 24시간 시장이라 B의 2분 관찰 창은 시각 제약 없이 충족할 수 있다.
- 사용자 취소는 final observation을 만들지 않는다. 취소 전에 A 또는 B를 충족했다면 취소 후에도 복기를 저장할 수 있다(016과 동일).
- `POST /api/education/practice/observations`는 본인 `PENDING` plan만 받는다. `CANCELLED`, `FILLED_TAKE_PROFIT`, `FILLED_STOP_LOSS`에는 409 `EXIT_PLAN_NOT_PENDING`이다. 코인 plan은 `CANCELLED_EXPIRED`가 될 수 없다.
- 고정 질문·답변 제약(`@NotBlank @Size(max=2000)`, 원문 저장, 정답·점수·보상·LLM 없음)은 016과 동일하다.
- 복기 저장 트랜잭션은 `COIN_PRACTICE_V1` progress를 먼저 `FOR UPDATE`로 잠그고 현재 evidence를 재검증한 뒤 불변 completion을 최초 한 번 생성한다. 이미 `COMPLETED`면 409 `PRACTICE_ALREADY_COMPLETED`다.

## GTC plan 수명 정책

- 코인 plan의 종결 상태는 `FILLED_TAKE_PROFIT`, `FILLED_STOP_LOSS`, `CANCELLED` 세 가지다. 자동 만료 상태(`CANCELLED_EXPIRED`)와 만료 scan은 코인에 없다.
- **수명 상한을 두지 않는다.** 근거는 둘이다. ① 24시간 시장에는 세션 경계가 없어 만료 시각을 정당화하는 자연스러운 기준이 없다. ② 임의 TTL은 사용자가 만들지 않은 종결을 발생시켜 예약 원장과 복기 evidence가 사용자 의도와 어긋나게 되며, "잠금 없음 → 추가"는 확장이지만 "자동 만료 있음 → 제거"는 이미 대응한 오류·상태 경로를 걷어내야 하는 완화라 되돌리기 비용이 비대칭이다.
- 감수 사항: 미종결 코인 plan은 예약 수량을 무기한 잡는다. 그 수량은 `availableQuantity` 계산에서 계속 차감되므로 일반 시장가·지정가 SELL이 `INSUFFICIENT_QTY`로 거부될 수 있다. 사용자가 직접 취소하는 것이 유일한 해제 경로이며, 목록 응답이 생성 시각과 예약 수량을 노출해 이를 판단할 수 있게 한다.
- 취소와 가격 트리거가 경합하면 같은 plan 잠금에서 먼저 커밋한 전이만 성공한다. 취소 승자는 예약을 한 번 반환하고 체결 승자는 예약을 매도에 한 번 소비하며 패자는 409 `EXIT_PLAN_NOT_PENDING`이다(016과 동일).

## 가격 이벤트와 트리거 (코인)

- 유효 가격 이벤트는 빗썸 실시간 시세 공급자가 거래 가능한 값으로 인정한 갱신이다. 트리거 판정은 그 이벤트에서만 수행한다.
- 공급자 장애·연결 끊김 동안 plan은 별도 `TRIGGERED` 중간 상태 없이 `PENDING`을 유지한다. 장애 자체는 만료·취소 사유가 아니다.
- 익절은 `currentPrice >= takeProfitPrice`, 손절은 `currentPrice <= stopLossPrice`이며 트리거 시점 현재가를 사용하는 시장가 청산이다. 특정 체결가를 보장하지 않는다.
- 가격 갱신은 `holding → plan` 순서로 비관 잠금하고, 충족된 한 조건으로 전량 시장가 매도를 만들며 반대 조건을 같은 트랜잭션에서 `CANCELLED_BY_OCO`로 만든다.
- 중복·역순 이벤트는 plan 잠금에서 최초 커밋한 이벤트만 승자가 되고 후속 이벤트는 terminal plan을 보고 no-op으로 skip한다.
- 튜토리얼 전용 합성 시세(`GET /api/education/practice/synthetic-prices/{id}`)는 참고용 차트이며 baseline·관찰·트리거 어디에도 사용하지 않는다.

## 수량 비교 규칙

- 코인 수량은 소수점 8자리까지 허용된다(`DECIMAL(30,8)`, 요청 검증은 정수부 22자리·소수부 8자리). 주식과 달리 정수 제약이 없다.
- snapshot equality는 **scale 무관 수치 비교**로 판정한다. `BigDecimal.compareTo(other) == 0`을 사용하며 `equals`를 사용하지 않는다 — `0.1`과 `0.10000000`은 같은 수량이고, scale이 다르다는 이유로 409 `PRACTICE_EVIDENCE_MISSING`이 발생하면 안 된다.
- 같은 규칙을 `intention.quantity`·`buyTrade.quantity`·`exitPlan.quantity` 3자 비교와 멱등 fingerprint의 수량 정규화에 함께 적용한다. fingerprint는 비교 전에 수량을 공통 표현으로 정규화해 같은 수량의 다른 표기가 `IDEMPOTENCY_CONFLICT`를 만들지 않게 한다.
- holding capacity 검증(`availableQuantity >= exitPlan.quantity`)은 equality가 아니라 부등호 비교이므로 scale 영향이 없다.
- 요청 수량이 소수부 8자리를 초과하거나 정수부 범위를 넘으면 기존대로 400 `VALIDATION_ERROR`이며 반올림하지 않는다.

## 주식 실습과의 차이 요약

| 항목 | 주식 (`016`) | 코인 (이 spec) |
|---|---|---|
| tutorial key | `INVESTMENT_PRACTICE_V1` | `COIN_PRACTICE_V1` |
| 체결 재생 세션 | `trades.stock_replay_session_id` 필수, plan에 session 귀속 | null, 세션 개념 없음 |
| plan 생성 시각 제약 | `OPEN` session 일치 + 15:30 이전 | 없음 (24시간) |
| 자동 만료 | 15:30에 `CANCELLED_EXPIRED` + 예약 반환 | 없음 (GTC) |
| 잠금 순서 | session → holding → plan | holding → plan |
| 관찰 증거 C | 체결 또는 세션 만료 | 체결만 |
| 세션 오류 코드 | `EXIT_PLAN_SESSION_CLOSED` 사용 | 사용하지 않음 |
| 수량 | 정수 | 소수 8자리, scale 무관 비교 |
| 가격 공급자 | 주식 체결 재생 | 빗썸 실시간 |

## 오류 계약

| 상태 | 코드 | 코인 경로에서의 발생 조건 |
|---|---|---|
| 409 | `PRACTICE_STEP_LOCKED` | intention 생성 시 코인 favorite가 없거나 선택 종목이 favorite와 다름 |
| 409 | `PRACTICE_EVIDENCE_MISSING` | OCO 생성·복기 저장 시 chain owner·instrument·market 불일치, 수량 snapshot 불일치, 관찰 증거 누락 |
| 409 | `PRACTICE_ALREADY_COMPLETED` | `COIN_PRACTICE_V1` progress가 이미 `COMPLETED` |
| 409 | `EXIT_PLAN_NOT_PENDING` | terminal plan에 관찰 추가·취소·재트리거 시도 |
| 409 | `PRICE_UNAVAILABLE` | plan 생성 시 유효 빗썸 시세 없음 (무흔적 거부) |
| 409 | `IDEMPOTENCY_CONFLICT` | 같은 key에 다른 request hash |
| 400 | `VALIDATION_ERROR` | 수량·가격 정밀도 위반, PRICE/PERCENT 필드 조합 위반 |
| — | `EXIT_PLAN_SESSION_CLOSED` | **코인에서 발생하지 않는다** |

## 범위 제외

- production 코드, Controller, DTO, entity, DB migration 구현
- OCO 생성·목록·취소·가격 트리거 production 구현 (`016` candidate 6~7 범위)
- `016`의 주식 실습 계약 변경 — 이 spec은 코인 경로만 확정한다
- 기존 `INVESTMENT_PRACTICE_V1` 행의 market별 재분류·백필
- 코인 plan 수명 상한·자동 만료·알림 도입
- 8개 투자 지식 과정, 객관식 판정, 배지, `INVESTMENT_BEGINNER`, RAG 교육 코치 (PRD 3차 MVP)
- LLM 설명, AI 피드백, 투자 추천, 가격 예측
- 실제 거래소 주문, 부분 체결, 슬리피지 보장
- 프론트엔드 화면 구현

## 완료 조건

- [ ] 코인 실습의 tutorial key와 주식 실습과의 진행·완료 분리가 확정되고 기존 행 비백필 규칙이 명시된다.
- [ ] 1~3단계가 코인 기준으로 재정의되고 세션 귀속·15:30 조건이 코인 경로에서 제거됨이 명시된다.
- [ ] GTC 수명 정책(종결 경로 3개, 상한 미도입 근거, 예약 무기한 점유 감수 사항)이 확정된다.
- [ ] 코인 잠금 순서와 생성·트리거·취소 경합의 정확히 한 번 규칙이 확정된다.
- [ ] 관찰 A·B·C가 코인 기준으로 확정되고 `FINAL_EVENT`가 체결 트랜잭션에서만 생성됨이 명시된다.
- [ ] 소수 수량 snapshot equality가 scale 무관 비교로 확정되고 fingerprint 정규화까지 포함된다.
- [ ] 빗썸 유효 가격 이벤트·장애 중 `PENDING` 유지·합성 시세 evidence 비사용이 확정된다.
- [ ] 코인에서 발생하지 않는 세션 기반 코드·상태가 오류 계약에서 구분된다.
- [ ] `016`·`019`·PRD와 충돌하지 않으며 필요한 최소 문서만 동기화된다.
