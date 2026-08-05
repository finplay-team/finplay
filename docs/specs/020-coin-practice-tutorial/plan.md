# Plan: 코인 투자 실습 튜토리얼 정책

> 이 문서는 계약·설계만 확정한다. production 코드는 `016` candidate 6~7과 이 spec의 tasks가 착수 시점에 소유한다.

## 설계 원칙

- `016`을 상속하고 코인 차이만 덮어쓴다. 공통 규칙(서버 evidence 판정, 클라이언트 완료 주장 불허, 보상·LLM 미사용, 무흔적 거부)을 이 문서에 복제하지 않는다 — 정본은 `016`이고 여기서는 delta만 확정한다.
- 주식 경로 계약은 건드리지 않는다. 코인 분기를 추가하는 변경만 허용하고 기존 주식 검증을 제거·완화하지 않는다.
- market 분기는 서비스 계층에서 종목의 `market`으로 해석한다. 클라이언트가 market이나 tutorial key를 입력하는 필드를 만들지 않는다.

## tutorial key 해석

- 현재: `PracticeIntentionService.TUTORIAL_KEY = "INVESTMENT_PRACTICE_V1"` 단일 상수. intention 생성이 이 key로 `practice_progresses` 행을 upsert·잠근다.
- 변경: key를 상수 하나에서 market → key 매핑으로 바꾼다.

| 종목 `market` | tutorial key |
|---|---|
| `STOCK` | `INVESTMENT_PRACTICE_V1` |
| `CRYPTO` | `COIN_PRACTICE_V1` |

- 해석 시점은 intention 생성 트랜잭션에서 `instrumentService`로 종목을 확인한 직후다. 잠금 순서(`progress`(DB) → `favorite`(in-memory))는 바뀌지 않는다.
- 스키마 변경 없음. `practice_progresses.tutorial_key`는 `VARCHAR(50)`이고 unique 제약이 `(user_id, tutorial_key)`이므로 새 key 값이 추가 행으로 들어간다. **migration 파일을 만들지 않는다** (ADR-0004 — 변경할 스키마가 없다).
- 기존 행 처리: 백필·재분류하지 않는다. 완료 기록의 회귀 금지 규칙 때문에 이미 `COMPLETED`인 행을 다른 key로 옮기는 것은 허용하지 않는다.
- `practice_completions`도 같은 key 규칙을 사용한다. reflection의 `(user_id, exit_plan_id)` 유일 제약은 key와 무관하게 유지된다.

## API 계약 delta

라우트는 신설하지 않는다. `016`이 계획한 실습 API의 동작 delta만 확정한다.

| 엔드포인트 | 코인 delta |
|---|---|
| `POST /api/education/practice/intentions` | 대상 종목이 `CRYPTO`면 `COIN_PRACTICE_V1` progress를 확보·잠근다. 코인 favorite 부재·불일치는 409 `PRACTICE_STEP_LOCKED`. 요청 필드 변경 없음 |
| `GET /api/education/practice` | 조회 대상 튜토리얼별로 응답한다. `tutorialKey`는 `INVESTMENT_PRACTICE_V1` 또는 `COIN_PRACTICE_V1`이고 evidence는 같은 key chain에서만 구성한다 |
| `POST /api/exit-plans` | 코인은 세션 검증·15:30 조건을 수행하지 않고 `replaySessionId`를 null로 저장한다. `holding → plan` 잠금. `EXIT_PLAN_SESSION_CLOSED`를 반환하지 않는다 |
| `GET /api/exit-plans` | 코인 항목의 `replaySessionId`는 null. 생성 시각과 예약 수량을 노출해 사용자가 미종결 plan을 판단·취소할 수 있게 한다 |
| `DELETE /api/exit-plans/{id}` | 코인 취소는 세션 잠금 없이 `holding → plan` 순서. 예약 수량을 정확히 한 번 반환 |
| `POST /api/education/practice/observations` | 코인 `PENDING` plan만 허용. terminal은 409 `EXIT_PLAN_NOT_PENDING`. 코인은 `CANCELLED_EXPIRED` 상태가 존재하지 않는다 |
| `POST /api/education/practice/reflections` | `COIN_PRACTICE_V1` progress를 `FOR UPDATE`로 먼저 잠그고 코인 chain evidence를 재검증한다 |

- `GET /api/education/practice`의 튜토리얼 선택 방식(경로 분리 vs query 파라미터)은 그 엔드포인트를 실제로 구현하는 이슈가 소유한다. 이 spec은 **응답이 하나의 key에만 대응하고 두 튜토리얼 evidence를 섞지 않는다**는 제약만 확정한다.
- 응답 DTO의 nullable 규칙은 `016` plan을 따른다. 코인에서 `replaySessionId`가 항상 null인 것은 기존 "코인만 null" 규칙과 동일하며 새 규칙이 아니다.

## 트랜잭션과 잠금

| 경로 | 주식 | 코인 |
|---|---|---|
| intention 생성 | progress(DB) → favorite(in-memory) | 동일 |
| OCO 생성 | replay session → holding → plan | holding → plan |
| 가격 트리거 | replay session → holding → plan | holding → plan |
| 사용자 취소 | replay session → holding → plan | holding → plan |
| 세션 만료 | replay session → holding → plan | **경로 없음** |
| 복기 저장 | progress → favorite → intention → plan | 동일 (key만 다름) |

- 코인은 세션 잠금이 빠지므로 잠금 계층이 하나 줄어든다. 순서를 뒤집지 않고 앞 단계만 생략하므로 주식 경로와 데드락 순서 충돌이 생기지 않는다.
- 코인 plan의 종결 전이는 `FILLED_TAKE_PROFIT`, `FILLED_STOP_LOSS`, `CANCELLED` 3개다. 상태 머신에 만료 전이를 추가하지 않는다.
- 예약 수량 정합성(`holding.reservedQuantity`)은 생성·트리거·취소 각각 한 DB 트랜잭션에서 유지한다. 공통 예약 원장의 물리 모델은 `016` candidate 6이 소유한다.

## 수량 비교 구현 방침

- 3자 equality(`intention` = `buyTrade` = `exitPlan`)는 `BigDecimal.compareTo(...) == 0`으로 판정한다. `equals`·`Objects.equals`는 사용하지 않는다.
- 멱등 fingerprint는 수량을 비교 전에 정규화한다(예: `stripTrailingZeros()` 후 공통 표현). 같은 수량의 다른 표기가 `IDEMPOTENCY_CONFLICT`를 만들지 않는 것이 요구사항이다.
- 요청 단계 정밀도 검증은 기존 `@Digits(integer = 22, fraction = 8)`을 유지한다. 주식 정수 제약을 intention에 새로 추가하는 것은 이 spec 범위가 아니다(기존 동작 유지).
- 이 규칙은 순수 정책이라 단위 테스트로 검증한다 — DB 왕복이 필요하지 않다.

## 테스트 계획 (ADR-0003)

- 단위: market → tutorial key 매핑, scale 무관 수량 equality(`0.1` vs `0.10000000`), fingerprint 정규화, 코인 상태 머신에 만료 전이 부재.
- 슬라이스(`@DataJpaTest`): 같은 사용자에 두 tutorial key 행이 공존하고 각 unique 제약이 독립적으로 동작함.
- 슬라이스(`@WebMvcTest`): 코인 intention 생성의 409 `PRACTICE_STEP_LOCKED`, terminal plan 관찰의 409 `EXIT_PLAN_NOT_PENDING`, 수량 정밀도 400.
- 통합(Testcontainers):
  - 코인 chain 전체 흐름(favorite → intention → 시장가 매수 → OCO → 관찰 → 복기) 완료와 `COIN_PRACTICE_V1` completion 1행 생성.
  - 주식 실습 완료 사용자가 코인 실습을 독립적으로 진행·완료하고 두 progress·completion이 서로 회귀하지 않음.
  - 코인 plan 생성 시각 무제약(주식이 거부되는 시각에도 코인은 생성 성공) 회귀.
  - 코인 plan에 자동 만료가 없음(만료 scan 실행 후에도 `PENDING` 유지, 예약 수량 불변).
  - 소수 수량 chain(예: `0.005`)에서 표기 차이가 있어도 OCO 생성이 성공.
  - 취소 ↔ 트리거 경합에서 예약 소비·반환이 정확히 한 번.
- 코인 시세는 실제 빗썸 연결 대신 테스트 가격 공급자로 주입한다. 실 거래소 호출을 테스트에 넣지 않는다.

## 의존성과 착수 순서

1. 이 spec 문서 확정 (이 이슈, #222 — production 변경 없음)
2. `016` candidate 6 공통 예약 원장 — 코인·주식 공통 선행
3. `016` candidate 7 OCO 생성 — 이 spec의 코인 분기를 함께 반영
4. tutorial key market 분기 — intention 생성 경로 변경 (2·3과 독립적으로 착수 가능)
5. 관찰·복기·진행 조회 — 코인 key와 A·B·C 코인 규칙 반영

- 4는 `016` candidate 6~7을 기다리지 않아도 된다. 다만 4를 먼저 병합하면 그 시점부터 코인 종목 intention이 새 key 행을 만들기 때문에, 진행 조회 API가 구현되기 전까지는 사용자에게 노출되는 변화가 없다는 점을 확인해야 한다.
- `019`의 PRICE·PERCENT 계약과 충돌하지 않는다 — 이 spec은 가격선 계산을 다시 정의하지 않고 `019`를 그대로 인용한다.

## 트레이드오프 기록

- **GTC 수명 상한 미도입**: 미종결 코인 plan이 예약 수량을 무기한 점유한다. 대안(고정 TTL, N일 후 자동 취소)을 검토했으나 24시간 시장에 정당한 기준 시각이 없고 사용자가 만들지 않은 종결이 예약 원장·복기 evidence를 사용자 의도와 어긋나게 만든다. 상한 도입은 되돌리기 비용이 비대칭인 완화 방향이라 지금 넣지 않는다. 재평가 조건: 실습이 아닌 일반 OCO(3차 MVP 후보)가 도입되어 계정당 plan 수가 늘어날 때.
- **tutorial key 분리 vs 단일 key 유지**: 단일 key를 유지하면 코인으로 완료한 사용자가 주식 실습을 다시 할 수 없고, 세션 기반 규칙이 적용되지 않는 코인 chain이 주식 실습 완료로 기록된다. 분리는 진행 상태 행이 사용자당 최대 2개로 늘어나는 비용만 발생하고 migration이 필요하지 않아 분리를 택했다.
- **기존 행 비백필**: 코인 종목으로 진행한 기존 `INVESTMENT_PRACTICE_V1` 행이 주식 실습 진행으로 남는다. 소급 재분류가 더 정확하지만 완료 기록 회귀 금지 규칙과 충돌하고, 2차 MVP 진행 중 실사용 데이터가 적어 이득이 비용보다 작다.
