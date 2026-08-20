# Spec: OCO 손절·익절 가격·퍼센트 입력 정책

> 상태: **부분 구현.** PRICE/PERCENT 계산·반올림 공식(scale 8, `RoundingMode.HALF_UP`)은 `026`의 `ReferencePriceCalculator`로 이미 production에 있다(PR #298). **OCO 트리거·예약 경로는 미착수**(3차 MVP, `021`이 엔진 정본).
>
> 이 문서의 계산 공식은 두 경로가 공유한다 — 3차 MVP의 OCO 트랙(`016`·`020`·`021`)과 2차 MVP의 활성 경로 `026`(참조 가격선 계산). 공식을 고칠 때는 양쪽 영향을 함께 확인한다.
>
> 다만 **PERCENT 입력은 아직 어느 경로에서도 실제로 쓰이지 않는다** — `PracticeIntention`이 PRICE만 저장해(이슈 #243) `ReferencePriceCalculator`의 PERCENT 분기는 현재 호출되지 않는다.

## 개요

투자 실습 사용자는 시장가 매수 전에 손절·익절 기준을 절대 가격 또는 실제 매수 체결가 대비 퍼센트로 기록할 수 있다. 두 입력 방식은 OCO 생성 시 불변 절대 가격선으로 확정되고, 이후 같은 가격 이벤트·예약·시장가 청산 로직을 사용한다.

현재 제공 중인 `POST /api/education/practice/intentions`의 `stopLoss`·`takeProfit` 절대 가격 요청은 호환 유지한다. 아직 production이 없는 OCO 생성 계약은 intention의 기준을 정본으로 사용하도록 정리해 클라이언트가 같은 값을 중복 제출하거나 서로 다른 값으로 바꿀 수 없게 한다.

## 사용자 시나리오

- 사용자는 매수 전에 손절가·익절가를 직접 입력해 기존과 같은 가격 기준 의도를 기록할 수 있다.
- 사용자는 매수 전에 손절률·익절률을 입력하고, 이후 실제 시장가 매수 체결가를 기준으로 계산된 가격선을 확인할 수 있다.
- 어느 방식을 선택해도 OCO는 한 보유 수량을 한 번만 예약하고 한쪽 가격선 도달 시 시장가로 전량 청산한다.

## 요구사항

- [ ] EXIT-PRICE-001: intention 입력 방식은 `PRICE`와 `PERCENT` 두 가지이며 한 요청에서 정확히 하나만 선택한다.
- [ ] EXIT-PRICE-002: 기존 요청처럼 `exitPriceType` 없이 양수 `stopLoss`·`takeProfit`만 보내면 `PRICE`로 처리한다.
- [ ] EXIT-PRICE-003: `PERCENT`는 `stopLossRate`·`takeProfitRate`를 퍼센트 단위(백분율 값)로 받으며 `5`는 5%를 뜻한다.
- [ ] EXIT-PRICE-004: 퍼센트 가격선의 기준은 intention 생성 시 현재가가 아니라 연결된 실제 시장가 BUY의 `entryPrice`다.
- [ ] EXIT-PRICE-005: OCO 생성은 intention 원본 기준과 계산된 `stopLossPrice`·`takeProfitPrice` snapshot을 함께 저장한다.
- [ ] EXIT-PRICE-006: PRICE와 PERCENT 모두 확정된 가격선에 대해 `currentPrice <= stopLossPrice`, `currentPrice >= takeProfitPrice`를 평가한다.
- [ ] EXIT-PRICE-007: 가격선 도달 후 체결은 일반 LIMIT 체결이 아니라 트리거 현재가를 사용하는 기존 시장가 청산이다.
- [ ] EXIT-PRICE-008: OCO 요청은 intention의 가격·퍼센트 값을 다시 받지 않으며 서버가 잠근 intention과 BUY trade에서 가격선을 확정한다.
- [ ] EXIT-PRICE-009: ADR-0012의 인메모리 intention 저장을 유지하면서 기존 PRICE API 요청·응답을 하위 호환한다.
- [ ] EXIT-PRICE-010: 프로세스 재시작으로 `intentionId`가 재사용돼도 영속 exit plan이 다른 intention으로 오인되지 않도록 서버 내부 불변 instance key를 snapshot한다.

## 입력 계약

### PRICE

```json
{
  "instrumentId": 1,
  "quantity": 10,
  "exitPriceType": "PRICE",
  "stopLoss": 65000,
  "takeProfit": 75000
}
```

- `stopLoss`, `takeProfit`: 양수, `DECIMAL(18,8)` 범위.
- `stopLossRate`, `takeProfitRate`: 반드시 누락한다.
- 기존 호환 요청은 `exitPriceType`을 생략할 수 있다. 이 경우 두 가격 필드가 모두 존재해야 하며 서버는 `PRICE`로 정규화한다.
- `exitPriceType=PRICE`를 명시한 새 요청은 intention 생성 시 `stopLoss < takeProfit`을 검증한다. 기존 호환 요청처럼 타입을 생략한 요청은 현재 production과 같은 양수·정밀도 검증만 적용해 동일·역전 가격도 저장을 허용하고, OCO 생성 시 실제 `entryPrice`를 포함한 최종 범위 검증에서 409로 무흔적 거부한다. 이는 기존에 201이던 요청을 깨뜨리지 않기 위한 의도적 호환 경계다.

### PERCENT

```json
{
  "instrumentId": 1,
  "quantity": 10,
  "exitPriceType": "PERCENT",
  "stopLossRate": 5,
  "takeProfitRate": 10
}
```

- `stopLossRate`: `0 < rate < 100`, 소수점 이하 최대 4자리. `5`는 5% 손실선을 뜻한다.
- `takeProfitRate`: `0 < rate <= 1000`, 소수점 이하 최대 4자리. `10`은 10% 이익선을 뜻한다.
- `stopLoss`, `takeProfit`: 반드시 누락한다.
- `exitPriceType` 생략 상태에서 rate 필드를 보내거나, PRICE와 PERCENT 필드를 섞거나, 선택한 방식의 두 필드 중 하나만 보내면 400 `VALIDATION_ERROR`다.

## 계산과 snapshot 규칙

```text
stopLossPrice  = entryPrice × (1 - stopLossRate / 100)
takeProfitPrice = entryPrice × (1 + takeProfitRate / 100)
```

- 모든 계산은 `BigDecimal`로 수행하고 최종 가격만 scale 8, `RoundingMode.HALF_UP`으로 한 번 반올림한다.
- `entryPrice`는 클라이언트 입력이나 intention 생성 시 현재가가 아니라 OCO가 연결한 본인 `FILLED` 시장가 BUY trade의 불변 체결가다.
- 계산 후 `0 < stopLossPrice < entryPrice < takeProfitPrice`를 다시 검증한다. 아주 작은 비율이 반올림되어 entry price와 같아지면 409 `EXIT_PLAN_INVALID_PRICE_RANGE`로 plan·예약 흔적 없이 거부한다.
- 계산된 두 가격이 각각 `DECIMAL(18,8)` precision·scale에 들어가는지도 저장 전에 검증한다. 정수부 10자리 또는 scale 8을 초과하면 DB 오류에 맡기지 않고 같은 409 `EXIT_PLAN_INVALID_PRICE_RANGE`로 plan·예약 흔적 없이 거부한다.
- 계산 가격은 주문 가능한 지정가를 만드는 값이 아니라 OCO 발동 임계값이다. 주식 호가 단위로 보정하지 않고 `DECIMAL(18,8)` snapshot을 그대로 비교한다.
- PRICE 방식도 OCO 생성 시 intention 가격을 `stopLossPrice`·`takeProfitPrice` snapshot으로 복사한다. 이후 intention이나 외부 가격 변화로 snapshot을 다시 계산하지 않는다.

## 응답과 정본

- intention 응답은 `exitPriceType`, PRICE에서만 non-null인 `stopLoss`·`takeProfit`, PERCENT에서만 non-null인 `stopLossRate`·`takeProfitRate`를 반환한다.
- 기존 PRICE 요청은 기존 응답 필드 `stopLoss`·`takeProfit`의 non-null 값을 유지하며 새 필드가 추가될 뿐 기존 필드 의미가 바뀌지 않는다.
- exit plan 응답은 `exitPriceType`, 원본 rate snapshot(PERCENT만), 확정된 `stopLossPrice`·`takeProfitPrice`를 반환한다. 트리거 판정은 항상 뒤의 두 필드만 사용한다.
- 현재 프로세스의 인메모리 `practice_intentions`가 사용자의 매수 전 원본 의도 정본이고, 영속 `exit_plans`가 실제 BUY 체결가에 결합된 실행 가격선 snapshot 정본이다.
- ADR-0012에 따라 intention은 재시작 시 유실되고 숫자 `intentionId`가 재사용될 수 있다. 각 intention에 API로 노출하지 않는 UUID `intentionInstanceKey`를 함께 생성하고 exit plan에 저장해 `(userId, intentionInstanceKey)`로 영속 identity를 구분한다. `intentionId`는 응답·evidence용 snapshot일 뿐 DB FK나 영속 unique identity로 사용하지 않는다.

## 멱등 재현과 intention instance 판정

- idempotency key mapping을 현재 in-memory intention보다 먼저 조회하는 key-first 규칙을 쓴다. key 존재 여부와 request hash 일치 여부만으로 재현·충돌을 가르며, 그 판정에서는 현재 intention instance를 비교하지 않는다. instance 비교는 key miss 분기에서만 수행한다.

| key 상태 | 현재 intention 상태 | instance plan 존재 | 결과 |
|---|---|---|---|
| hit, hash 일치 | 무관(유실·재사용·정상 모두) | 무관 | 200, 과거 plan 그대로 재현(instance 비교 없음) |
| hit, hash 불일치 | 무관 | 무관 | 409 `IDEMPOTENCY_CONFLICT` |
| miss | 현재 instance로 해석 가능 | 같은 instance plan 존재 | 200, 기존 instance plan 반환 + 새 key mapping 저장 |
| miss | 현재 instance로 해석 가능 | 없음 | 201, 신규 plan 생성 + key mapping 저장 |
| miss | 유실(재시작으로 조회 실패) 또는 chain 불일치 | 해당 없음 | 409 `PRACTICE_EVIDENCE_MISSING` |

- **fingerprint에서 가격·rate를 뺀 결과 생기는 안전망 후퇴를 감수한다.** 이전에는 가격이 fingerprint에 있어, 재시작으로 숫자 `intentionId`가 다른 intention(예: 이전 PRICE 65000/75000 → 새 PERCENT 5/10)에 재사용된 상태에서 같은 key와 같은 4필드 body(`intentionId`·`buyTradeId`·`instrumentId`·`quantity`)를 보내면 409 `IDEMPOTENCY_CONFLICT`로 막혔다. 이 정책에서는 key hit + hash 일치 조건만으로 과거 plan을 200 재현하므로 그 안전망이 사라진다. 이는 알고 감수하는 트레이드오프이며, **클라이언트는 서버가 재시작되었을 수 있는 세션에서 이전에 사용한 `Idempotency-Key`를 재사용하지 않아야 한다**(새 OCO 생성 시도마다 새 UUID key를 발급한다).

## 일반 지정가와의 구분

- PRICE 입력의 `stopLoss`·`takeProfit`은 일반 지정가(목표가) 주문의 필드가 아니다. `ai/specs/015-limit-order`가 아직 없어 그 필드명이 확정되지 않았으므로 여기서 `limitPrice`라는 이름을 선점하지 않는다.
- 일반 LIMIT는 단일 BUY/SELL 주문 자체가 지정가(목표가)에 전량 체결된다.
- OCO는 기존 보유분에 두 임계값을 묶고, 한쪽 도달 시 트리거 현재가로 시장가 SELL을 만들며 반대 조건을 취소한다.
- 두 기능은 공통 가격 이벤트와 holding 예약 원장을 사용할 수 있지만 API, 상태, 체결가 정책과 트랜잭션은 분리한다.

## 범위 제외

- production Controller·DTO·entity·migration 구현
- 일반 LIMIT 주문 생성·체결
- 부분 체결, 슬리피지 보장, 사용자 간 주문 매칭
- 퍼센트 기준을 현재가·평균매수가·평가금액 중 사용자가 선택하는 기능
- OCO 생성 후 방식·rate·가격선 수정
- 실제 증권사 주문과 투자 추천

## 완료 조건

- [ ] 구형 PRICE 요청은 현재처럼 각 가격의 양수·정밀도만 검증해 동일·역전 가격도 intention 저장까지 계속 성공하고, OCO 생성에서 최종 범위 오류로 거부된다.
- [ ] PRICE/PERCENT 조합별 필수·금지 필드가 표와 테스트로 확정된다.
- [ ] 같은 intention과 buyTrade에는 항상 같은 scale 8 가격선이 계산된다.
- [ ] 퍼센트 계산의 경계값·반올림과 실제 entry price 기준이 단위 테스트로 검증된다.
- [ ] 계산 결과의 `DECIMAL(18,8)` 상한 초과가 409와 plan·예약 무저장으로 검증된다.
- [ ] OCO 생성 요청이 intention과 다른 가격·rate를 덮어쓸 수 없다.
- [ ] 두 입력 방식이 같은 OCO 트리거·예약·시장가 청산 경로로 수렴한다.
- [ ] 서버 재시작 전후 같은 숫자 intentionId가 재사용돼도 서로 다른 exit plan identity로 구분된다.
