# Plan: OCO 손절·익절 가격·퍼센트 입력 정책

## 관련 문서

- Spec: `./spec.md`
- 투자 실습: `../016-investment-education-policy/spec.md`, `../016-investment-education-policy/plan.md`
- 일반 지정가: `../../prd.md`의 LMT-001~004 (OCO와 별도 기능)
- 관련 ADR: `../../adr/0002-architecture.md`, `../../adr/0003-testing-strategy.md`, `../../adr/0004-flyway-migrations.md`, `../../adr/0012-tutorial-state-in-memory.md`

## API 변경 계획

### `POST /api/education/practice/intentions`

구현 시 요청 record의 단순 Bean Validation만으로 tagged union을 표현하지 않는다. 공통 숫자 형식은 DTO에서 검증하고, 타입별 필수·금지 조합은 education service의 별도 정책 객체가 검증한다. 타입을 생략한 legacy PRICE는 기존과 같은 개별 양수·정밀도만 검증하고, `exitPriceType=PRICE`를 명시한 요청에만 intention 단계의 `stopLoss < takeProfit` 검증을 추가한다.

| 필드 | PRICE | PERCENT | 검증 |
|---|---:|---:|---|
| `instrumentId` | 필수 | 필수 | positive Long |
| `quantity` | 필수 | 필수 | 기존 `DECIMAL(30,8)` 범위 |
| `exitPriceType` | 선택 | 필수 | 생략은 PRICE 호환 모드 |
| `stopLoss` | 필수 | 금지 | 기존 `DECIMAL(18,8)` 양수 |
| `takeProfit` | 필수 | 금지 | 기존 `DECIMAL(18,8)` 양수 |
| `stopLossRate` | 금지 | 필수 | `DECIMAL(7,4)`, 0 초과 100 미만 |
| `takeProfitRate` | 금지 | 필수 | `DECIMAL(8,4)`, 0 초과 1000 이하 |

응답은 기존 6개 필드에 `exitPriceType`, `stopLossRate`, `takeProfitRate`를 추가한다. PRICE의 기존 `stopLoss`·`takeProfit`은 계속 non-null이고 rate는 null이다. PERCENT는 가격 둘이 null이고 rate 둘이 non-null이다.

### `POST /api/exit-plans`

아직 production이 없는 계획 계약을 다음 요청으로 단순화한다.

```json
{
  "intentionId": 1,
  "buyTradeId": 10,
  "instrumentId": 1,
  "quantity": 10
}
```

- 요청에서 `stopLoss`, `takeProfit`, rate 필드를 제거한다.
- education application이 사용자 단위 in-process 락 안에서 조회한 intention의 내부 `intentionInstanceKey`와 입력 snapshot을 order application port에 전달한다.
- order port는 본인 BUY trade의 `entryPrice`와 snapshot으로 실행 가격선을 확정한다.
- PRICE는 intention 가격을 복사하고, PERCENT는 계산 정책으로 가격을 만든다.
- owner·instrument, intention quantity = buyTrade quantity = request quantity 검증은 유지한다.

## 멱등 fingerprint

- 클라이언트 canonical JSON key 순서는 `intentionId`, `buyTradeId`, `instrumentId`, `quantity`다.
- `quantity`는 기존처럼 `stripTrailingZeros().toPlainString()` JSON number token을 사용한다.
- 가격·rate는 클라이언트 OCO 요청에 존재하지 않으므로 request fingerprint에 넣지 않는다.
- 대신 OCO 생성 트랜잭션이 in-process 락 안에서 조회한 불변 intention의 `intentionInstanceKey`, `exitPriceType`과 원본 값, BUY trade id·entry price를 plan snapshot에 저장한다.
- 같은 intention에는 plan 하나만 허용하되, ADR-0012에서 숫자 `intentionId`가 프로세스 재시작 뒤 재사용되므로 `(user_id, intention_instance_key)` unique를 최종 방어선으로 사용한다. 숫자 `intention_id`는 표시·evidence snapshot이며 FK나 unique 대상이 아니다.
- idempotency key mapping은 현재 in-memory intention보다 먼저 조회한다. key hit에서 request hash가 같으면 재시작 후 intention이 유실됐거나 숫자 ID가 새 instance에 재사용돼도 과거 plan을 200으로 재현하고, hash가 다를 때만 409다. key miss에만 현재 intention을 instance key로 해석해 instance plan 조회·생성을 수행한다.

## 계산 정책

- order 도메인에 `ExitPricePolicy` 같은 명시적 역할의 순수 계산기를 둔다. education entity나 request DTO에 의존하지 않고 내부 snapshot DTO를 입력받는다.
- PRICE는 OCO 생성 시 legacy/명시 여부와 무관하게 `stopLoss < entryPrice < takeProfit` 검증 후 scale 8 snapshot을 반환한다.
- PERCENT는 아래 순서로 한 번 계산한다.

```text
normalizedStopRate = stopLossRate.movePointLeft(2)
normalizedTakeRate = takeProfitRate.movePointLeft(2)
stopLossPrice = entryPrice.multiply(ONE.subtract(normalizedStopRate))
    .setScale(8, HALF_UP)
takeProfitPrice = entryPrice.multiply(ONE.add(normalizedTakeRate))
    .setScale(8, HALF_UP)
```

- 계산 결과 범위가 깨지면 기존 409 `EXIT_PLAN_INVALID_PRICE_RANGE`를 사용한다.
- 계산 또는 PRICE 복사 결과가 `DECIMAL(18,8)`의 정수부 10자리·scale 8을 넘는 경우도 저장 전에 같은 409로 거부한다. DB DataIntegrityViolation을 정상 검증 경로로 사용하지 않는다.
- 입력 union 자체가 잘못되면 intention API에서 400 `VALIDATION_ERROR`다.
- `double`·`float`, 중간 단계 반올림, 주식 호가 단위 반올림은 사용하지 않는다.

## 데이터 모델 변경 계획

### `practice_intentions` 인메모리 record

- ADR-0012와 V19에 따라 DB 테이블을 다시 만들지 않고 Flyway migration도 추가하지 않는다.
- 기존 record에 API 비노출 `UUID intentionInstanceKey`, `ExitPriceType exitPriceType`, nullable `stopLoss`, `takeProfit`, `stopLossRate`, `takeProfitRate`를 추가한다.
- repository 저장 시 instance key와 숫자 `intentionId`를 각각 새로 생성한다. 타입 생략 legacy 요청도 저장 전에 `exitPriceType=PRICE`로 정규화한다.
- 정적 팩토리와 service 정책이 PRICE 가격 둘 non-null·rate 둘 null, PERCENT는 반대인 불변식을 강제한다. 이 구조는 `@DataJpaTest`나 DB CHECK 대상이 아니라 순수 단위·동시성 테스트 대상이다.

### `exit_plans`

- candidate 7 최초 migration에서 표시 snapshot `intention_id`, 내부 `intention_instance_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin`, `exit_price_type`, 원본 nullable rate 둘, non-null `stop_loss_price`, `take_profit_price`, `entry_price`를 저장한다.
- `UNIQUE(user_id, intention_instance_key)`를 두고 `intention_id`에는 FK·unique를 두지 않는다. UUID는 lowercase canonical 문자열로 저장한다.
- PRICE 원본은 intention에서 추적 가능하므로 별도 중복 가격 컬럼 없이 실행 snapshot 두 필드를 정본으로 사용한다.
- PERCENT 원본 rate는 실행 결과 설명과 감사 가능성을 위해 plan에도 snapshot한다.

## 트랜잭션과 의존 방향

- 기존 016·ADR-0012 계획처럼 education application이 사용자 단위 favorite/intention in-process 락에서 검증 snapshot을 명시적 order application port로 전달한다.
- order port는 education repository를 호출하지 않으며 같은 최상위 트랜잭션에 참여한다.
- 주식은 replay session → holding, 코인은 holding을 잠근 뒤 BUY trade·capacity와 가격선을 검증하고 plan·conditions·예약을 저장한다.
- 가격 계산 실패를 포함한 어느 실패도 plan·condition·holding 예약을 남기지 않는다.
- 트리거는 plan의 `stopLossPrice`·`takeProfitPrice`만 읽으므로 입력 방식별 분기를 다시 수행하지 않는다.

## 문서 전환 규칙

- 이 문서 PR은 production과 실제 intention API 계약을 바꾸지 않는다. 전역 API 문서에는 “후속 변경 계획”으로만 기록한다.
- intention 확장 Controller 이슈에서 `docs/api-routes.md`와 `docs/api-contracts.md`를 실제 계약으로 전환한다.
- candidate 7 OCO Controller 이슈는 변경된 4필드 요청과 확정 가격선 응답을 actual로 전환한다.

## 테스트 계획

- 단위: tagged union 전 조합, 구형 PRICE의 동일·역전 가격 저장 호환과 명시 PRICE 선행 순서 검증, rate 경계, 계산·scale·HALF_UP, 반올림 후 가격 범위 및 `DECIMAL(18,8)` 초과 실패.
- 인메모리 저장소: 모드별 record 불변식, UUID instance key 유일성, 타입 생략 PRICE 정규화, 사용자별 조회와 동시 저장을 순수 테스트한다.
- 슬라이스: exit plan의 instance key unique, 가격·rate nullable 조합, 정밀도와 JPA 매핑을 MySQL `@DataJpaTest`로 검증한다.
- WebMvc: 구형 PRICE 201, 명시 PRICE 201, PERCENT 201, 혼합·누락·범위 오류 400, 모드별 nullable 응답.
- 통합: intention → 시장가 BUY → OCO 생성에서 PRICE 복사/PERCENT 계산, client 가격 덮어쓰기 불가, legacy 역전 가격과 계산 precision 초과의 409·예약 무저장, 재시작 뒤 key hit 과거 응답 200 재현, key miss에서 같은 숫자 intentionId·다른 instance key의 독립 plan identity.
- 회귀: 기존 PRICE intention, OCO owner·instrument·quantity chain, 멱등 수렴, 시장가 BUY/SELL 계약.
