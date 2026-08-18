# Spec: 일반 리스크관리 OCO (intentionId 없는 손절·익절 예약)

> 상태: 문서 설계 확정, production 구현은 3차 MVP 착수분(2026-08-06 재확정)
>
> **2차 MVP는 이 OCO 없이 `docs/specs/026-market-order-practice-tutorial`(2026-08-10 신설)로 튜토리얼을 완결한다.** 이 spec은 3차 MVP 착수 전까지 설계 문서로만 남아 있으며, `026`은 이 엔진을 기다리지 않고 시장가/지정가 매매 결과만으로 완결하도록 별도 설계됐다 — 서로 대체 관계가 아니라 같은 문제(3단계 실습 완결)의 서로 다른 시점 해법이다.
>
> `docs/prd.md`는 손절·익절 OCO를 "튜토리얼 전용"과 "일반 리스크관리 OCO"(3차 MVP 후보)로 나눠 두었다(§2). 2026-08-05에는 코인(GTC) 튜토리얼 경로를 2차 MVP 활성 트랙으로 우선 구현하기로 했었으나, 2026-08-06 결정으로 그 우선 구현을 철회하고 튜토리얼 OCO·일반 리스크관리 OCO를 함께 3차 MVP에서 착수하기로 확정했다. 이 spec은 그 3차 착수 시점에 두 트랙을 처음부터 하나의 엔진으로 설계해 두는 문서다 — 교육 튜토리얼에 종속되지 않는 일반 기능으로 처음 설계한다. `POST /api/exit-plans` 등 관련 production 코드는 아직 전혀 없다 — 이 문서는 리팩터링이 아니라 최초 설계다.
>
> **OCO 체결의 현금 처리(샌드박스 격리)는 `047-tutorial-sandbox-cash-isolation`이 정본이다.** 이 spec은
> 애초에 튜토리얼 현금 격리를 고려한 적이 없었다 — `047`은 이 spec을 대체하는 것이 아니라, 이 spec이
> 비워 둔 자리(대상 holding이 샌드박스 종목일 때 OCO 체결이 실제 계좌 대신 튜토리얼 전용 계좌를 증감시키는
> 규칙)를 채운다. `ExitPlanFillService`의 체결 로직 자체(트리거 판정, 잠금 순서, 정확히 한 번 규칙)는 이
> spec이 계속 정본이며 `047`은 그 안의 현금 대상만 바꾼다.

## 개요

`docs/specs/016-investment-education-policy`는 OCO exit plan(손절·익절을 한 쌍으로 묶어 한쪽 도달 시 반대쪽을 자동 취소하는 예약)을 3단계 투자 실습 튜토리얼에만 쓰도록 설계했다 — 생성에 `intentionId`가 필수이고 favorite→intention→buyTrade→holding→exitPlan chain 검증이 붙는다. 이 spec은 같은 OCO 생성·트리거·취소 엔진을 튜토리얼에 종속되지 않는 **일반 기능**으로 확장한다.

일반 사용자는 지금 보유 중인 holding에 사전 의도 기록 없이 바로 OCO를 걸 수 있다. `intentionId`는 이 API의 **선택 파라미터**가 된다.

- `intentionId`를 생략하면 순수 일반 리스크관리 OCO다 — holding 소유권·가용 수량만 검증한다.
- `intentionId`를 지정하면 016이 정의한 교육 전용 chain 검증(favorite→intention→buyTrade→holding 존재·소유자·종목·수량 snapshot equality, `PRACTICE_STEP_LOCKED`/`PRACTICE_EVIDENCE_MISSING`)이 그대로 추가 적용된다.

즉 튜토리얼 OCO는 이 일반 기능의 특수 사례로 통합된다. **OCO 생성·트리거·취소 엔진 자체는 하나만 존재하며 두 경로가 공유한다.**

## 문서 소유권 재배치

이 spec이 아래 항목의 정본이 되며, `016`의 "OCO exit plan 비즈니스 규칙" 절 중 엔진 공통 부분을 대체한다. `016`을 폐기하지 않고 소유권만 나눈다.

| 항목 | 정본 문서 |
|---|---|
| `exit_plans`·`exit_plan_conditions`·`exit_plan_idempotency_keys` 물리 스키마 | **이 spec (021)** |
| OCO 생성·트리거·취소 엔진(잠금 순서, 예약 원장 연동, 정확히 한 번 규칙, 코인 GTC 수명) | **이 spec (021)** |
| 일반 경로 입력 검증(holding 소유권·가용 수량, 시장 범위) | **이 spec (021)** |
| PRICE/PERCENT 입력 정책과 계산 규칙 | `019` (변경 없음, 이 spec이 재사용) |
| 교육 전용 chain 검증(favorite→intention→buyTrade→holding→exitPlan, 수량 snapshot equality, `PRACTICE_STEP_LOCKED`/`PRACTICE_EVIDENCE_MISSING`) | `016` (변경 없음, `intentionId` 지정 시에만 적용) |
| 튜토리얼 진행 판정(3단계 상태 계산, 관찰 A·B·C, 복기, `practice_progresses`/`practice_completions`) | `016`·`020` (변경 없음) |
| 코인 튜토리얼 delta(튜토리얼 key market 분기, 코인 evidence 정의) | `020` (변경 없음). `020`이 설명하던 OCO 엔진 메커니즘(holding→plan 잠금, GTC, scale 무관 수량 비교)은 이 spec이 일반화해 흡수했다 — `020`은 계속 소유하되 이후 문서 갱신에서 엔진 세부 설명을 이 spec 참조로 축약할 수 있다(이 spec 자체는 `020`의 파일을 수정하지 않는다).
| 주식 세션 귀속·15:30 자동 만료(`EXIT_PLAN_SESSION_CLOSED`, `CANCELLED_EXPIRED`) | `016` (변경 없음, 3차 MVP 완성분 — 이 spec은 코인만 다룬다)

## 사용자 시나리오

- 로그인 사용자는 보유 중인 코인 종목의 holding에서 수량 일부 또는 전부를 골라, 절대 가격 또는 실제 평균매수가 대비 퍼센트로 손절·익절 기준을 지정해 OCO를 즉시 예약한다. 사전에 의도를 따로 기록하지 않는다.
- 같은 holding에 이미 `PENDING` OCO가 있으면 새 OCO 생성은 거부된다 — 취소하거나 체결을 기다려야 새로 걸 수 있다.
- 사용자는 본인 OCO 목록에서 `PENDING` 예약을 확인하고 취소할 수 있다.
- 익절가 또는 손절가에 도달하면 서버가 트리거 시점 현재가로 시장가 청산하고 반대 조건을 자동 취소한다.
- 3단계 투자 실습 사용자는 `intentionId`를 지정해 같은 API를 호출하며, 이 경우 016이 정의한 튜토리얼 chain 검증이 추가로 적용된다(동작 변경 없음).

## 요구사항

- [ ] RISK-OCO-001: OCO 생성은 `intentionId`를 선택 파라미터로 받는다. 생략하면 일반 경로, 지정하면 교육 경로다.
- [ ] RISK-OCO-002: 일반 경로는 `holdingId`(본인 소유)와 `quantity`, PRICE 또는 PERCENT 입력을 필수로 받는다. `buyTradeId`·`instrumentId`는 일반 경로에 존재하지 않는다.
- [ ] RISK-OCO-003: 교육 경로(`intentionId` 지정)는 016이 정의한 `intentionId`·`buyTradeId`·`instrumentId`·`quantity` 요청과 chain 검증을 그대로 유지한다.
- [ ] RISK-OCO-004: 한 holding에 동시에 존재할 수 있는 `PENDING` OCO plan은 경로와 무관하게 최대 1건이다. 이미 `PENDING` plan이 있는 holding에 새 생성을 시도하면 흔적 없이 409로 거부한다.
- [ ] RISK-OCO-005: 부분 수량 예약을 허용한다 — 생성 시 `holding.availableQuantity >= plan.quantity`만 검증하고 holding 총수량과의 일치를 요구하지 않는다.
- [ ] RISK-OCO-006: 이 spec은 코인 시장만 다룬다. 주식 holding으로 일반 OCO를 생성하려는 요청은 400으로 거부한다. 주식 지원은 이 spec 범위 밖의 후속 확장이다.
- [ ] RISK-OCO-007: 일반 경로의 PERCENT 기준가(`entryPrice` 대응)는 생성 시점 holding의 평균매수가(`averagePrice`) snapshot이다.
- [ ] RISK-OCO-008: 손절·익절 입력 방식과 가격선 확정 계산은 `019`의 PRICE/PERCENT 정책을 그대로 사용한다(엔진 재발명 없음).
- [ ] RISK-OCO-009: 생성·트리거·취소는 세션 개념 없이 `holding → plan` 순서로 잠그고, 중복·역순 가격 이벤트에서 최초 커밋만 승자가 되며 예약은 정확히 한 번 소비 또는 반환된다(016·020이 코인 경로용으로 확정한 규칙 그대로).
- [ ] RISK-OCO-010: 이 spec의 OCO는 항상 GTC다 — 코인만 다루므로 자동 만료 상태·세션 만료 경로가 없다.
- [ ] RISK-OCO-011: 일반 경로에는 튜토리얼 진행 판정(단계 완료, 관찰 A·B·C, 복기)이 전혀 개입하지 않는다. `intentionId` 지정 시에만 016의 진행 판정 대상이 된다.
- [ ] RISK-OCO-012: 클라이언트가 단계 완료·현재가·보상을 직접 지정하는 입력은 어느 경로에도 없다. LLM·투자 추천을 사용하지 않는다(PRD C-004 상속).
- [ ] RISK-OCO-013: `Idempotency-Key` header는 두 경로 모두 필수이며, 재시도는 최초 결과를 그대로 재현한다.

## 비즈니스 규칙

- **holding당 PENDING 1건 불변식은 경로와 무관한 엔진 규칙이다.** 교육 경로에서 여러 intention으로 같은 holding에 여러 `PENDING` plan을 만들 수 있었던 016의 암묵적 여지를 이 spec이 명시적으로 좁힌다 — 016은 production이 없어 동작 회귀가 아니다. 엔진을 하나로 유지하려면 불변식도 하나여야 한다.
- 이 불변식은 "그 holding에 지금 걸린 `PENDING` plan이 없어야 한다"는 뜻이며, "그 holding에 다시는 OCO를 걸 수 없다"는 뜻이 아니다 — 기존 plan이 체결·취소로 종결되면 같은 holding에 새 `PENDING` plan을 다시 만들 수 있다.
- 일반 경로의 `holdingId`는 요청자 본인 계좌의 holding이어야 한다. 타인 holding은 존재를 숨겨 404 `NOT_FOUND`로 응답한다.
- 일반 경로는 `instrumentId`를 별도로 받지 않는다 — holding이 이미 종목을 특정하므로 중복 입력을 만들지 않는다.
- PRICE/PERCENT 조합·필수·금지 필드는 `019`의 intention 입력 규칙과 동일한 형태를 일반 경로 요청에 직접 적용한다. 다만 일반 경로는 사전 의도 기록 단계가 없으므로 `exitPriceType`이 항상 필수다(교육 경로처럼 생략 시 PRICE로 호환하는 구형 요청은 없다).
- 계산된 `stopLossPrice`·`takeProfitPrice`는 `0 < stopLossPrice < entryPrice < takeProfitPrice`를 만족해야 하며, `entryPrice`는 교육 경로는 `buyTrade.entryPrice`(변경 없음), 일반 경로는 holding의 생성 시점 `averagePrice` snapshot이다.
- 생성 트랜잭션은 서버 유효 현재가를 baseline으로 저장한다. 유효 시세가 없으면 두 경로 모두 409 `PRICE_UNAVAILABLE`로 plan·condition·예약 흔적 없이 거부한다.
- 가격 트리거는 익절 `currentPrice >= takeProfitPrice`, 손절 `currentPrice <= stopLossPrice`이며 트리거 시점 현재가로 시장가 청산한다. 두 경로가 완전히 같은 트리거·체결·반대 조건 취소 로직을 공유한다.
- 취소·체결·만료 경합에서 예약 수량은 항상 정확히 한 번 소비되거나 반환된다. 기존 시장가·지정가 SELL은 공통 예약 원장(`Holding.getAvailableQuantity()`, `reserveQuantity()`, `releaseReservedQuantity()` — `docs/specs/015-limit-order`가 이미 구현)에서 `availableQuantity`만 검증한다. 이 원장은 새로 만들지 않고 그대로 재사용한다.
- 일반 경로 생성·취소는 `Idempotency-Key` 재시도에서 최초 결과를 그대로 재현한다. 일반 경로는 holding이 영속 DB 행이라 ADR-0012의 재시작 인스턴스 재사용 문제가 없으므로, 교육 경로처럼 복잡한 key-first 재해석 coordinator가 필요하지 않다(`plan.md` 참고).

## 범위 제외

- 주식 시장 지원 — holding 시장이 `STOCK`이면 400으로 거부하며 상세 설계(세션 귀속·만료·잠금 확장)는 하지 않는다. 향후 별도 확장이 필요해지면 그때 새 spec 또는 이 spec의 개정으로 다룬다.
- 부분 체결, 슬리피지 보장, 사용자 간 주문 매칭.
- 한 holding에 동시에 여러 `PENDING` OCO를 허용하는 것 — RISK-OCO-004로 명시적으로 금지한다.
- OCO 조건 개별 수정, 한쪽만 취소, 수량 변경.
- 일반 경로의 진행 조회·관찰·복기 API — 이런 API는 애초에 필요하지 않다(튜토리얼 전용, `016`이 계속 소유).
- 8개 투자 지식 과정, 배지, RAG 코치, AI 피드백, 투자 추천, 가격 예측 (PRD 3차 MVP, C-004).
- production Controller·DTO·entity·migration 구현 — 이 spec은 설계 확정만 한다.

## 완료 조건

- [ ] `intentionId` 선택 여부에 따른 일반·교육 두 경로의 요청 필드 필수·금지 조합이 표와 예시로 확정된다.
- [ ] holding당 `PENDING` OCO 최대 1건 불변식이 확정되고, 이미 존재하는 `PENDING`에 대한 새 생성 거부 오류 코드가 확정된다.
- [ ] 일반 경로의 PERCENT 기준가가 holding 평균매수가 snapshot임이 계산 규칙·저장 위치와 함께 확정된다.
- [ ] 두 경로가 완전히 같은 생성·트리거·취소 엔진(잠금 순서, 예약 원장, 정확히 한 번 규칙, GTC)을 공유함이 명시된다.
- [ ] `016`·`019`·`020`과의 문서 소유권 경계가 항목별로 명확히 나뉜다.
- [ ] 주식 범위 제외가 거부 방식(400)과 함께 명시된다.
- [x] `docs/prd.md`와의 차수 불일치(§2·§4의 "3차 MVP 후보" 문구, §3 구현 현황)가 해소된다 — 이 PR(#249)이 §2·§3·§4를 직접 동기화했다(tasks.md "후속 확인 필요" 절 참고). 남은 것은 §4 요구사항 ID 부여 방식뿐이며 3차 착수 이슈로 넘긴다.
