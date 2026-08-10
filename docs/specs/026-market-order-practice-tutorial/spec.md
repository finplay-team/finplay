# Spec: 시장가/지정가 매매 기반 3단계 투자 실습 완결 경로 (OCO 없이)

> 상태: 2026-08-10 신규 작성 → **일부 구현 완료**. MKT-PRACTICE-001~007·009~012는 production에 반영됐고(PR #295·#298·#302·#304, `V27`·`V28`), **MKT-PRACTICE-008(`GET /api/education/practice` 진행 조회)만 미착수**(이슈 #305)다. 아래 "범위 제외"의 "순수 설계" 서술은 최초 작성 시점 기준이며 더 이상 유효하지 않다.
>
> **이 spec이 2차 MVP의 유일한 실제 튜토리얼 완료 경로다.** 사용자는 이미 API 호출만으로 3단계를 완료할 수 있다(즐겨찾기 → 사전 의도 → 매수 → 관찰 → 복기). OCO 기반 경로(`016`·`019`·`020`·`021`)는 3차 MVP로 이연됐다.
>
> **번호 확정 경위**: 작업 지시 시점에는 024를 쓰기로 했으나, 착수 전 `git ls-tree origin/dev docs/specs/`로 재확인한 결과 origin/dev에 이미 `024-feedback-query-cache`·`025-review-gate-auto-fix`가 존재해 026으로 올렸다. `022-community-enhancement`/`027-crypto-tick-candle-cache`, `023-watchlist` 모두 origin/dev에 이미 존재한다(로컬 미커밋 추측은 틀렸다 — 실제로는 이미 머지돼 있었다).

## 개요

3단계 투자 실습 튜토리얼(`docs/specs/016-investment-education-policy`)은 2·3단계를 OCO exit plan(사전 의도 → 시장가 매수 → 손절·익절 예약)으로 설계했다. OCO는 2026-08-06 결정으로 3차 MVP(2차 고도화)로 이동했다(`docs/prd.md` §2·§3·§4, PR #249). `016`·`019`·`020`·`021`은 OCO 기반 버전의 정본으로 계속 유효하며 이 spec은 그 문서들의 계약을 바꾸지 않는다.

이 spec은 **OCO 없이, 지금 이미 production에 있는 거래 기능(시장가 매수 `POST /api/orders`, 코인 지정가 매수 `POST /api/orders/limit`)만으로 2·3단계를 완결시키는 대안 경로**를 확정한다. 1단계(즐겨찾기)와 사전 의도 기록 API(`POST /api/education/practice/intentions`, PR #176)는 변경 없이 그대로 재사용한다. 이 경로는 2차 MVP에서 실제로 동작하는 유일한 3단계 실습 완료 경로다 — OCO 기반 버전이 아직 없는 지금은 "튜토리얼의 대안"이 아니라 "튜토리얼의 유일한 실제 완료 방법"이다.

## 사용자 시나리오

- 로그인 사용자는 종목을 즐겨찾기에 등록해 1단계를 완료한다(변경 없음, `016` 그대로).
- 로그인 사용자는 손절·익절과 수량을 먼저 의도로 기록하고(`016`의 `POST /api/education/practice/intentions`, 변경 없음), 같은 종목·같은 수량을 실제 매수 방법(시장가 또는 코인 지정가)으로 매수해 그 매수와 연결된 보유(holding)를 만들어 2단계를 완료한다. **OCO 예약은 만들지 않는다.**
- 로그인 사용자는 자신이 기록한 손절·익절 참조선과 실제 매수 체결가를 기준으로 서버가 판정하는 가격 관찰 조건(A 또는 B) 하나를 충족한 뒤, 그 보유(holding)를 가리키는 자유 복기를 저장해 3단계를 완료한다. **가격이 자동으로 청산되는 일은 없다** — 사용자는 언제든 스스로 매도할 수 있고, 매도 여부와 무관하게 evidence 조건이 채워지면 복기를 저장할 수 있다.

## 요구사항

- [ ] MKT-PRACTICE-001: 이 경로는 `016`이 정의한 1단계(즐겨찾기)·사전 의도 기록 API를 변경 없이 재사용한다.
- [ ] MKT-PRACTICE-002: 2단계 완료 증거는 OCO exit plan이 아니라 intention과 연결된 실제 `FILLED` 매수 체결(시장가 또는 코인 지정가) 및 그 매수로 만들어진 holding의 존재다.
- [ ] MKT-PRACTICE-003: 2단계 evidence chain은 `favorite → intention → buyTrade → holding` 순서로 구성하며, `intention.quantity == buyTrade.quantity`(수량 정규화 비교, `020`의 scale 무관 규칙 재사용)와 owner·instrument 일치를 검증한다. holding은 owner·instrument 일치만 검증하고 현재 수량이 intention 수량과 달라도(추가 매수·일부 매도) 무방하다.
- [ ] MKT-PRACTICE-004: 3단계 evidence A는 intention에 저장된 손절·익절 기준(`019`의 PRICE/PERCENT 정책으로 계산한 절대 가격선)과 매수 체결가(`buyTrade.entryPrice`)를 기준선(baseline)으로 사용해, 서버 유효 현재가가 그 기준선보다 경계에 가까워졌는지 판정한다. OCO exit plan을 생성하지 않는다.
- [ ] MKT-PRACTICE-005: 3단계 evidence B(최소 2분 범위의 관찰 3회, 경계 접근 불필요)는 `016`의 정의를 그대로 재사용한다.
- [ ] MKT-PRACTICE-006: 3단계 evidence C(익절·손절 자동 체결)는 이 경로에 존재하지 않는다. A 또는 B만으로 3단계를 완료할 수 있어야 한다.
- [ ] MKT-PRACTICE-007: 가격 관찰·복기 저장 API는 `exitPlanId`가 아니라 `holdingId`를 요청 식별자로 받는다.
- [ ] MKT-PRACTICE-008: `GET /api/education/practice`는 이 경로에서 처음으로 실제 production에 구현되며, `016`의 `InvestmentPracticeResponse`/`PracticeStepResponse`/`PracticeEvidenceResponse` DTO 설계를 evidence 필드만 이 경로에 맞게 조정해 재사용한다.
- [ ] MKT-PRACTICE-009: 완료 판정은 클라이언트 완료 주장을 받지 않고 서버가 실제 도메인 증거를 연결해 계산하며, `practice_progresses`·`practice_completions`의 불변 완료 원칙(`016`)을 그대로 상속한다.
- [ ] MKT-PRACTICE-010: `tutorial_key`는 기존 `INVESTMENT_PRACTICE_V1`(주식)·`COIN_PRACTICE_V1`(코인)을 그대로 재사용한다. 이 경로가 지금 그 key들의 유일한 실제 완료 정본이다.
- [ ] MKT-PRACTICE-011: 주식·코인 시장 모두 지원한다. 코인은 시장가·지정가 매수 둘 다 2단계 증거로 인정하고, 주식은 시장가 매수만 인정한다(주식 지정가는 `015`에서 코인 전용으로 확정돼 존재하지 않는다 — 별도 분기 코드 없이 자연히 배제된다).
- [ ] MKT-PRACTICE-012: 이 경로에는 배지·포인트·보상·LLM을 사용하지 않는다(`016` C-004 상속).

## 2단계 완료 증거 — 설계 결정과 근거

`016`의 2단계는 `intention.quantity == buyTrade.quantity == exitPlan.quantity`라는 3자 exact equality를 요구했다. exitPlan이 예약한 수량이 정확히 하나의 chain에 귀속되어야 했기 때문이다. **이 경로에는 그 제약의 근거(예약 원장)가 없다.**

결정: 3자 equality를 2자(`intention.quantity == buyTrade.quantity`)로 완화하고, holding 쪽은 "owner·instrument 일치 + 존재"만 확인한다(`016`이 이미 holding에 대해서는 총량 일치를 요구하지 않았던 것과 동일선상). 근거:

- OCO가 없으므로 "이 수량만큼 예약됐다"는 사실 자체가 없다. exitPlan.quantity와의 3자 비교는 애초에 대응 개념이 없다.
- `intention.quantity == buyTrade.quantity` 2자 비교는 유지한다 — 이것마저 없으면 "계획한 수량으로 실제 매수했다"는 사용자 시나리오("먼저 계획하고 실제로 지킨다")가 느슨해져, 임의의 소액 매수로 의도와 무관하게 2단계를 우회할 수 있다. 이 비교는 여전히 서버가 검증 가능하고 사용자에게 자연스러운 기준이다.
- holding 존재만으로 완화하는 대안(수량 비교를 전혀 하지 않음)도 검토했으나, intention과 무관한 기존 보유만으로 2단계가 완료되는 것을 막기 위해 채택하지 않는다 — 최소한 "이 intention 이후 이 수량만큼 실제로 산 적이 있다"는 사실은 유지한다.

## 3단계 evidence — 기준선(baseline) 설계

`016`의 OCO는 exit plan **생성 시점**의 서버 유효 현재가를 `baselinePrice`로 저장했다. 이 경로에는 "plan 생성"이라는 별도 이벤트가 없으므로 새 기준점을 정의해야 한다.

결정: **매수 체결(`buyTrade`) 자체를 기준점으로 쓴다** — `baselinePrice = buyTrade.entryPrice`, `baselineObservedAt = buyTrade.executedAt`. 근거:

- `buyTrade`는 이미 불변 원장으로 영속돼 있다. 별도 snapshot 테이블이나 "plan 생성" 트랜잭션을 새로 만들지 않아도 된다 — 서버가 이미 가진 사실만 사용한다.
- 매수 시점이야말로 사용자가 "이 가격에 들어갔다"고 인지하는 자연스러운 기준점이며, OCO의 "예약을 건 시점"과 같은 역할을 한다.
- 손절·익절 절대 가격선은 intention의 `exitPriceType`이 `PRICE`면 그 값을 그대로, `PERCENT`면 `buyTrade.entryPrice`를 기준으로 `019`의 반올림 규칙(scale 8, `RoundingMode.HALF_UP`)으로 계산한다. **이 계산은 매번 조회 시점에 살아있는 intention과 buyTrade에서 다시 계산한다 — OCO의 `exit_plans` 같은 snapshot 테이블에 결과를 저장하지 않는다.** intention이 인메모리(ADR-0012)라 서버 재시작으로 사라지면 이 계산 자체가 불가능해지고, 이는 `016`이 이미 감수한 "재시작 시 미완료 evidence 유실" 리스크와 동일한 성격이다(아래 "범위 제외·잔여 위험" 참고).

## 관찰·복기 API 대상 식별자 — 새 계약

`016`은 `POST /api/education/practice/observations`·`reflections`가 `exitPlanId`를 요청 필드로 받도록 설계했다. exit plan이 없는 이 경로는 그 계약을 그대로 가져올 수 없다.

결정: 새 엔드포인트 `POST /api/education/practice/holding-observations`·`POST /api/education/practice/holding-reflections`를 만들고 요청 필드로 `holdingId`를 받는다. **URL을 `016`의 `/observations`·`/reflections`와 다르게 한다** — `016`의 그 URL은 아직 미구현이지만 3차 MVP에서 OCO 기반으로 구현될 예정인 정본 계약이다. 같은 URL을 이 경로가 먼저 다른 요청 필드(`holdingId`)로 점유하면 3차 MVP 구현 시점에 URL 재사용 여부를 다시 결정해야 하는 불필요한 충돌이 생긴다. 새 URL을 쓰면 두 경로가 완전히 독립적으로 공존할 수 있다(3차 MVP에서 OCO 버전이 실제로 만들어져도 이 경로의 엔드포인트를 건드릴 필요가 없다).

`holdingId`만으로 서버가 대상 chain(favorite → intention → buyTrade → holding)을 내부적으로 재해석한다 — 클라이언트가 intentionId나 buyTradeId를 직접 지정하지 않는다(소유권 우회 방지, `016`과 같은 원칙).

## 3단계 정의와 완료 증거

### 1단계 — 종목을 관심 대상으로 정하기

`016`과 완전히 동일하다. 변경 없음.

### 2단계 — 먼저 계획하고 실제로 매수하기

- 사용자는 매수 전에 `instrumentId`, `quantity`와 PRICE 또는 PERCENT 방식의 손절·익절 기준을 `POST /api/education/practice/intentions`로 기록한다(`016`·`019` 그대로, 변경 없음).
- intention의 `instrumentId`는 1단계 favorite의 `instrumentId`와 같아야 한다(변경 없음, 409 `PRACTICE_STEP_LOCKED`).
- 이후 실제 매수를 실행한다 — 주식은 `POST /api/orders`(`orderType=MARKET`, `side=BUY`)만, 코인은 그 API 또는 `POST /api/orders/limit`(`side=BUY`)로 매수해 실제 `FILLED` 체결을 만든다. 어느 쪽이든 기존 계약 그대로 즉시(시장가) 또는 트리거 시(지정가) 체결되며 이 spec은 그 계약을 바꾸지 않는다.
- 서버는 `intention.createdAt`보다 나중에 체결된, 같은 사용자·같은 `instrumentId`·같은 수량(정규화 비교)의 `FILLED` BUY `trade`를 이 intention의 매수로 인정한다. 후보가 여럿이면 `executedAt ASC, tradeId ASC`로 가장 이른 것을 선택한다.
- 매수 이후 그 종목의 holding이 owner·instrument 일치로 존재해야 한다(현재 수량은 검증하지 않음).
- 서버 완료 증거는 intention 기록 시각이 매수 체결보다 앞서고, 같은 수량 값이 실제 체결과 holding에 연결된 사실이다. favorite·intention·buyTrade·holding 중 하나라도 없거나 owner·instrument·수량이 불일치하면 409 `PRACTICE_EVIDENCE_MISSING`이다.

### 3단계 — 가격 움직임을 견디며 복기하기

- 관찰을 여는 증거는 A 또는 B다(C는 없음, 위 요구사항 MKT-PRACTICE-006).
  - A: `POST /api/education/practice/holding-observations` 호출 시점의 서버 유효 현재가가, `buyTrade.entryPrice` 기준 손절선/익절선까지의 절대 거리보다 가까워진 관찰 1회.
  - B: 관찰 3회가 존재하고 첫 관찰과 마지막 관찰이 최소 2분 범위에 있음(가격이 경계에 가까워질 필요 없음, `016`과 동일).
- 고정 질문·답변 제약(`@NotBlank @Size(max=2000)`, whitespace-only 거부, 원문 저장, 정답·점수·보상·LLM 없음)은 `016`과 동일하다.
- 복기는 매도 여부와 무관하게 저장할 수 있다 — OCO의 "체결·만료 뒤에도 복기 가능"과 같은 취지이나, 이 경로에는 자동 종결이 없으므로 holding이 여전히 존재하기만 하면(수량 0이어도 owner·instrument 기록으로 조회 가능) 언제든 복기할 수 있다.
- `POST /api/education/practice/holding-reflections`는 A 또는 B 관찰이 이미 존재해야 하며, 없으면 409 `PRACTICE_EVIDENCE_MISSING`이다.

## 진행 조회

- `GET /api/education/practice`는 실제 favorite·intention·buyTrade·holding·관찰·복기 리소스를 조회해 단계별 상태·잠금·evidence를 계산한다(`016`의 계약을 이 경로에 맞게 최초로 구현). 조회는 어떤 것도 쓰지 않는다.
- 응답 DTO 이름(`InvestmentPracticeResponse`/`PracticeStepResponse`/`PracticeEvidenceResponse`)은 `016`과 동일하게 유지하되, evidence 필드에서 `exitPlanId`·`replaySessionId`·`baselinePrice`(plan snapshot) 대신 `holdingId`, 계산된 참조 손절가·익절가(`referenceStopLossPrice`·`referenceTakeProfitPrice`)를 담는다. 상세는 `plan.md`.
- 완료 전 여러 유효 chain이 있으면 `016`과 같은 우선순위 규칙(qualifying observation 있는 chain 우선, 그 안에서 가장 이른 buyTrade)을 적용한다.

## 완료 판정과 tutorial_key

- `tutorial_key`는 대상 종목의 `market`으로 결정한다 — `STOCK`이면 `INVESTMENT_PRACTICE_V1`, `CRYPTO`이면 `COIN_PRACTICE_V1`(`020`의 규칙 그대로 재사용). 새 key를 만들지 않는다.
- 근거: 이 경로는 지금 이 두 key의 유일한 실제 완료 방법이다. 사용자에게는 "같은 튜토리얼을 완료했다"는 사실이 중요하고, 완료 방법(OCO냐 시장가·지정가 매매냐)이 다르다고 별개의 튜토리얼로 보이게 하는 것은 어색하다. 새 key(`INVESTMENT_PRACTICE_MARKET_V1` 등)를 만들면 3차 MVP에서 OCO 버전이 완성됐을 때 "같은 실습을 두 번 완료해야 하는가"라는 혼란이 생긴다.
- **미확정 잔여 위험 — 지금 결정하지 않음**: 3차 MVP에서 OCO 기반 버전이 실제로 production에 들어가면, 같은 `tutorial_key`를 두 경로가 계속 공유해도 되는지(즉 "OCO로 완료"와 "시장가·지정가 매매로 완료" 중 하나만 만족하면 전체 완료로 치는지), 아니면 그 시점에 key를 분리해야 하는지는 이 spec이 결정하지 않는다. `docs/prd.md` §2·§3에 근거를 남기고 3차 MVP 착수 spec(`016`의 후속)에서 재판단해야 한다.

## 비즈니스 규칙

- 이 경로는 holding 수량 예약을 도입하지 않는다 — 실제 매수·매도는 기존 계약(시장가·지정가) 그대로 동작하고 이 튜토리얼은 그 결과만 읽는다.
- 참조 손절가·익절가는 OCO의 트리거 가격처럼 자동 청산을 유발하지 않는다 — 순수하게 evidence 판정용 참조선이다.
- 관찰은 PENDING/terminal 같은 상태 구분이 없다 — holding이 존재하는 한 언제든 호출 가능하며 매도로 수량이 0이 되어도 계속 호출 가능하다(OCO의 "terminal plan은 거부" 규칙이 대응 개념 없이 사라짐).
- 완료(`practice_completions`) 이후에는 favorite 삭제, holding 매도로 수량 0 등 evidence 변화가 있어도 완료 상태가 회귀하지 않는다(`016` 원칙 상속).

## 범위 제외

- OCO exit plan 생성·수정·트리거·만료 — `016`·`019`·`020`·`021`이 정본으로 유지하며 이 spec은 만들거나 바꾸지 않는다.
- 이 spec이 만드는 신규 엔드포인트가 `016`의 `/observations`·`/reflections`·`/exit-plans` 계열 URL을 점유하거나 대체하는 일 — URL을 명시적으로 분리했다(위 "관찰·복기 API 대상 식별자" 절).
- 8개 투자 지식 과정, 배지, RAG 코치, 보상(PRD 3차 MVP, C-004).
- ~~production 코드, Controller, DTO, entity, migration 구현 — 이 spec은 순수 설계다.~~ **(2026-08-10 해소)** 최초 작성 시점의 범위 제외였으나 이후 같은 spec 범위로 구현됐다 — 상태 헤더 참고.
- 3차 MVP에서 OCO 버전이 들어올 때 `tutorial_key` 공유 여부의 최종 결정 — 위 "완료 판정과 tutorial_key" 절의 잔여 위험으로 남긴다.

## 완료 조건

- [ ] 1단계는 `016`과 완전히 동일하게 재사용되고 어떤 계약도 바뀌지 않는다.
- [ ] 2단계 완료 증거가 `favorite → intention → buyTrade → holding` chain과 2자 수량 비교(`intention.quantity == buyTrade.quantity`)로 확정되고, OCO exitPlan quantity 비교가 대응 개념 없이 제거된 근거가 문서화된다.
- [ ] 3단계 evidence A·B가 `buyTrade.entryPrice`/`executedAt`을 기준선으로 사용하도록 확정되고, evidence C가 이 경로에 존재하지 않음이 명시된다.
- [ ] 관찰·복기 API가 `holdingId` 요청 필드의 새 URL(`/holding-observations`, `/holding-reflections`)로 확정되고 `016`의 `/observations`·`/reflections`와 충돌하지 않는다.
- [ ] `GET /api/education/practice`가 이 경로 기준으로 최초 구현 대상이 되고 DTO 설계가 `016`에서 최대한 재사용된다.
- [ ] `tutorial_key` 재사용 결정과 3차 MVP 재판단 필요성이 문서에 남는다.
- [ ] 새 오류 코드를 추가하지 않고 기존 코드(`PRACTICE_STEP_LOCKED`, `PRACTICE_EVIDENCE_MISSING`, `PRACTICE_ALREADY_COMPLETED`, `PRICE_UNAVAILABLE`, `VALIDATION_ERROR`)만으로 표현됨이 확인된다.
