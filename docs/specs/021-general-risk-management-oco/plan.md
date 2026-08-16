# Plan: 일반 리스크관리 OCO (intentionId 없는 손절·익절 예약)

## 관련 문서

- Spec: `./spec.md`
- 교육 전용 chain·엔진 초안: `../016-investment-education-policy/spec.md`, `../016-investment-education-policy/plan.md` (이 문서가 엔진 소유권을 이어받는다)
- PRICE/PERCENT 입력 정책: `../019-exit-price-policy/spec.md`, `../019-exit-price-policy/plan.md` (변경 없이 재사용)
- 코인 튜토리얼 delta: `../020-coin-practice-tutorial/spec.md`, `../020-coin-practice-tutorial/plan.md` (엔진 설명은 이 문서로 일반화, `020`은 튜토리얼 delta만 계속 소유)
- 공통 예약 원장 실제 구현: `../015-limit-order/plan.md` — `Holding.reservedQuantity`, `getAvailableQuantity()`, `reserveQuantity()`, `releaseReservedQuantity()`가 이미 production에 존재한다(PR #215/#220). 이 spec은 새 원장을 만들지 않고 그대로 재사용한다.
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(도메인 경계, cross-domain repository 직접 주입 금지), [ADR-0003](../../adr/0003-testing-strategy.md), [ADR-0004](../../adr/0004-flyway-migrations.md), [ADR-0012](../../adr/0012-tutorial-state-in-memory.md)(교육 경로에서만 적용, 일반 경로는 해당 없음 — holding은 항상 DB 행)
- PRD: `../../prd.md` §2(2차 MVP 3단계 실습 절, "일반 리스크 관리 OCO는 3차 MVP 후보로 분리"), §4(EDU-PRACTICE 관련 서술), §3 구현 현황 — 이 spec 확정 후 동기화 필요(`tasks.md` 후속 확인 항목 참고)

## 착수 제한

이 문서는 계약·스키마·잠금 설계만 확정한다. production, Controller, migration, `docs/api-routes.md`, `docs/api-contracts.md`를 변경하지 않는다. 아래 계약은 후속 구현 이슈의 계약이며 구현 전에는 실제 사용 가능하다고 문서화하지 않는다.

## 라우트 재사용 결정

`016`이 이미 계획한 `POST /api/exit-plans`·`GET /api/exit-plans?status=`·`DELETE /api/exit-plans/{exitPlanId}`를 그대로 재사용하고 새 라우트를 만들지 않는다.

**근거**: PRD가 이미 "튜토리얼 OCO는 일반 기능의 특수 사례"로 통합하기로 했다(2026-08-06 브레인스토밍). 별도 라우트(`/api/exit-plans` vs `/api/risk-plans` 등)를 만들면 클라이언트가 의미상 같은 자원(손절·익절 예약)을 두 API로 나눠 다뤄야 하고, 서버도 사실상 같은 엔진을 감싸는 두 Controller를 유지해야 한다. `intentionId`를 선택 필드로 두는 것만으로 한 라우트가 두 경로를 표현할 수 있으므로 라우트를 늘리지 않는다.

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | `/api/exit-plans` | `ExitPlanCreateRequest`(아래 두 경로 통합) | `ExitPlanResponse` | `intentionId` 유무로 일반·교육 경로 분기 |
| GET | `/api/exit-plans?status=` | 선택 `status`(생략 시 `PENDING`) | `ExitPlanListResponse` | 본인 예약 목록, 경로 무관 |
| DELETE | `/api/exit-plans/{exitPlanId}` | 없음 | 없음 | 본인 `PENDING` OCO 취소, 경로 무관 |

세 API는 `Authorization: Bearer <access-token>`을 요구한다. 나머지 공통 계약(JSON, 오류 body, 404 존재 은닉)은 `016` plan의 확정 규칙을 그대로 따른다.

### `ExitPlanCreateRequest` 필드와 경로별 필수·금지

| 필드 | 교육 경로 (`intentionId` 지정) | 일반 경로 (`intentionId` 생략) |
|---|---|---|
| `intentionId` | 필수, positive `Long` | 없음(필드 자체를 생략) |
| `buyTradeId` | 필수 | **금지** — 포함되면 400 `VALIDATION_ERROR` |
| `instrumentId` | 필수 | **금지** — holding이 종목을 특정하므로 포함되면 400 |
| `holdingId` | **금지** — 교육 경로는 여전히 favorite→intention→buyTrade→holding chain에서 holding을 유도한다 | 필수, positive `Long`, 본인 소유 |
| `quantity` | 필수, `DECIMAL(30,8)` | 필수, `DECIMAL(30,8)` |
| `exitPriceType` | **없음** — intention 정본 사용(`016`·`019` 그대로) | **필수** — `PRICE` 또는 `PERCENT` (생략 호환 없음, 일반 경로는 사전 의도 단계가 없어 매번 명시해야 한다) |
| `stopLoss`/`takeProfit` | **없음** — intention에서 읽음 | `exitPriceType=PRICE`일 때 필수, 그 외 금지 |
| `stopLossRate`/`takeProfitRate` | **없음** | `exitPriceType=PERCENT`일 때 필수, 그 외 금지 |

교육 경로 필드에 일반 경로 필드가 섞이거나(예: `intentionId`와 `holdingId`를 함께 보냄) 그 반대인 경우 400 `VALIDATION_ERROR`다. 가격·rate 정밀도는 `019`와 동일(`@Digits(integer=18-10, fraction=8)`류, 상세는 `019` plan 참고).

## 일반 경로 검증 순서 (`POST /api/exit-plans`, `intentionId` 생략)

1. `holdingId`로 holding을 조회한다. 없으면 404 `NOT_FOUND`, 본인 소유가 아니면 존재를 숨겨 같은 404다.
2. `holding.instrument.market != CRYPTO`면 400 `VALIDATION_ERROR`("코인 종목만 일반 리스크관리 OCO를 지원합니다.") — 기존 `015`가 지정가 생성에서 쓴 시장 제한 패턴과 동일하다.
3. `holding`을 잠근다(`SELECT ... FOR UPDATE`, 세션 없음).
4. 그 holding에 이미 `PENDING` exit plan이 있는지 조회한다. 있으면(이 요청이 그 plan의 멱등 재현이 아닌 한) 409 `EXIT_PLAN_ALREADY_EXISTS`로 plan·condition·예약 흔적 없이 거부한다.
5. `holding.getAvailableQuantity() < request.quantity`면 409 `INSUFFICIENT_QTY`.
6. `entryPrice = holding.getAveragePrice()`(생성 시점 snapshot)를 `019`의 `ExitPricePolicy`에 전달해 `stopLossPrice`·`takeProfitPrice`를 확정한다. PRICE는 그대로 snapshot, PERCENT는 `019` 계산식을 그대로 사용한다.
7. 계산 결과가 `0 < stopLossPrice < entryPrice < takeProfitPrice` 또는 `DECIMAL(18,8)` 상한을 벗어나면 409 `EXIT_PLAN_INVALID_PRICE_RANGE`.
8. 서버 유효 현재가를 조회해 `baselinePrice`·`baselineObservedAt`으로 저장한다. 시세가 없으면 409 `PRICE_UNAVAILABLE`.
9. `holding.reserveQuantity(quantity)`, `exit_plans` 1행과 `exit_plan_conditions` 2행을 같은 트랜잭션에 저장한다. `intention_id`·`buy_trade_id`·`intention_instance_key`는 모두 null이다.

## 교육 경로 (`intentionId` 지정) — 변경 없음

`016`·`019`가 확정한 favorite→intention→buyTrade→holding chain 검증, `intention_instance_key` key-first 멱등 coordinator, `progress → favorite 락 → intention 락 → holding → plan` 잠금 순서를 그대로 사용한다. 이 문서는 이 경로의 알고리즘을 다시 쓰지 않는다 — 유일한 변경은 **4단계(holding당 PENDING 1건 검증)를 이 경로에도 동일하게 적용하는 것**뿐이다(아래 "holding당 PENDING 1건" 절).

## holding당 PENDING 1건 불변식 (경로 공통)

- 엔진이 하나이므로 불변식도 하나다: **holding 하나에 동시에 존재하는 `PENDING` exit plan은 0건 또는 1건이다.** 경로(`intentionId` 유무)와 무관하게 적용한다.
- **DB unique 제약으로 강제하지 않는다.** 같은 holding이 시간에 따라 여러 번(체결·취소로 종결된 뒤 다시) `PENDING` plan을 가질 수 있어 "모든 행 중 1건"이 아니라 "현재 `PENDING` 상태 중 1건"이 조건이기 때문이다. MySQL 8.4의 부분(partial) unique index 부재로 이 조건을 선언적 제약으로 표현하려면 생성 컬럼·트리거 같은 우회가 필요한데, 이는 `015` plan이 이미 밝힌 관례("CHECK 제약 쓰지 않음, 앱 계층에서 불변식 검증")와도 맞지 않는다.
- 대신 holding 행의 비관적 락 아래에서 `exit_plans`를 `WHERE holding_id = ? AND status = 'PENDING'`으로 조회해 검증한다. holding을 이미 lock 순서상 잠그므로 추가 락이 필요 없다.
- 새 오류 코드 `EXIT_PLAN_ALREADY_EXISTS`(409)를 추가한다. 기존 `DUPLICATE_RESOURCE`를 재사용하지 않는 이유: `DUPLICATE_RESOURCE`는 "같은 리소스를 다시 만들려 함"을 뜻하는 기존 관례(즐겨찾기 재등록 등)로 쓰이는데, 이 경우는 "다른 리소스(새 OCO)를 만들려 했으나 기존 활성 리소스가 자리를 막음"이라 의미가 다르다.
- 멱등 재시도(`Idempotency-Key` 재현)는 이 검증보다 먼저 처리된다(아래 "멱등성" 절) — 즉 4단계는 "key miss일 때만" 도달하므로 원래 성공한 자기 자신의 재시도가 이 오류로 막히지 않는다.

## 멱등성

두 경로 모두 `Idempotency-Key` UUID header가 필수이고 `exit_plan_idempotency_keys`(공통 테이블, `016` 설계 그대로) 매핑을 먼저 조회하는 key-first 판정을 쓴다는 점은 같다. 재해석 복잡도는 경로에 따라 다르다.

- **교육 경로**: `016`·`019`가 확정한 5단계 key-first 알고리즘과 실패 시 최대 3-transaction coordinator(생성 attempt → reconciliation → final read)를 그대로 쓴다. 이 복잡도는 ADR-0012의 in-memory intention이 서버 재시작으로 유실되거나 숫자 `intentionId`가 재사용될 수 있다는 사실에서만 발생한다.
- **일반 경로**: `holdingId`는 항상 영속 DB 행이라 재시작 유실·ID 재사용 문제가 없다. 따라서 이 경로는 코드베이스에 이미 있는 단순 패턴(`OrderService.createOrder`, `LimitOrderService` — 선제 조회 → 실행 → `DataIntegrityViolationException` 캐치 → 재조회 폴백, 단일 attempt)만으로 충분하다. 별도 coordinator를 만들지 않는다.
  1. `(userId, idempotencyKey)` 매핑을 조회한다. 있고 request hash가 같으면 200으로 과거 plan을 재현한다. hash가 다르면 409 `IDEMPOTENCY_CONFLICT`.
  2. 매핑이 없으면 위 "일반 경로 검증 순서" 1~9단계를 한 트랜잭션에서 시도한다.
  3. `exit_plan_idempotency_keys.(user_id, idempotency_key)` unique 위반이 발생하면(동시 재시도) 트랜잭션을 롤백하고 1번부터 한 번만 재조회한다.
- fingerprint canonical JSON key 순서: **교육 경로** `intentionId, buyTradeId, instrumentId, quantity`(`016`·`019` 그대로, 가격 필드는 intention snapshot에서 오므로 포함하지 않음). **일반 경로** `holdingId, quantity, exitPriceType` + (`PRICE`면 `stopLoss, takeProfit`, `PERCENT`면 `stopLossRate, takeProfitRate`). 두 경로 모두 `quantity`는 `stripTrailingZeros().toPlainString()`으로 정규화한다(소수 수량 표기 차이가 다른 요청으로 오인되지 않게 — `020`의 scale 무관 비교 원칙을 fingerprint에도 적용).

## 트리거·취소·잠금 순서 (경로 공통)

| 유스케이스 | 잠금 순서 | 근거 |
|---|---|---|
| 생성(일반) | `holding → plan` | 세션 없음(코인 전용) |
| 생성(교육) | `favorite 락(in-memory) → intention 락(in-memory) → holding → plan` | `016` 변경 없음 |
| 가격 트리거 | `holding → plan`[^1] | `016`·`020`이 코인 경로용으로 확정한 순서, 경로 무관하게 동일 |
| 사용자 취소 | `holding → plan` | 동일 |

[^1]: 실제 구현(`ExitPlanFillService`)은 PR #371 리뷰 반영으로 `account → holding → plan`을 쓴다 — 체결 시 계좌 cash를 직접 mutate하므로 account 락이 먼저 필요했고, 기존 시장가·지정가 매도(`OrderExecutionService`)의 잠금 관례와 통일하기 위함이다. 이 표의 값은 `016`·`020`이 최초 확정한 설계 순서를 기록한 것이라 원문은 유지하고 각주로만 보강한다.

- 트리거 판정은 가격 공급자(코인은 빗썸 실시간)가 거래 가능으로 인정한 유효 갱신 이벤트에서만 수행한다. 장애 중에는 `PENDING`을 유지한다.
- 중복·역순 가격 이벤트는 plan 잠금에서 최초 커밋한 이벤트만 승자가 되고 후속 이벤트는 terminal plan을 보고 no-op으로 skip한다(`016` 규칙 그대로).
- 취소와 트리거가 경합하면 먼저 커밋한 전이만 성공한다. 승자는 예약을 정확히 한 번 소비 또는 반환하고 패자는 409 `EXIT_PLAN_NOT_PENDING`이다.
- 종결 상태는 `FILLED_TAKE_PROFIT`, `FILLED_STOP_LOSS`, `CANCELLED` 세 가지뿐이다. **이 spec은 코인만 다루므로 `CANCELLED_EXPIRED`·세션 만료 scan을 정의하지 않는다** — 이 상태·경로는 `016`이 주식 3차 MVP 완성 시점에 소유한다.
- 기존 시장가·지정가 SELL은 `Holding.getAvailableQuantity()`만 검증한다(변경 없음, `015`가 이미 구현).

## 데이터 모델

`016`이 초안으로 그렸던 `exit_plans` 스키마를 이 spec이 최종 확정한다(`016`은 아직 production이 없으므로 스키마 충돌이 아니라 최초 확정이다).

### `exit_plans`

| 컬럼 | 타입 | nullable | 설명 |
|---|---|---|---|
| `id` | `BIGINT` PK | N | |
| `user_id` | `BIGINT` FK | N | |
| `holding_id` | `BIGINT` FK → `holdings.id` | **N** | 두 경로 모두 항상 실제 holding에 결합된다(교육 경로도 chain에서 holding을 유도해 저장) |
| `intention_id` | `BIGINT` | Y | 응답·evidence용 숫자 snapshot. 일반 경로는 항상 null. FK·unique 아님(`016` 그대로) |
| `intention_instance_key` | `CHAR(36)` ascii | Y | 교육 경로만 사용하는 내부 UUID identity. 일반 경로는 항상 null |
| `buy_trade_id` | `BIGINT` FK | Y | 교육 경로만. 일반 경로는 null |
| `instrument_id` | `BIGINT` FK | N | 두 경로 모두 저장(응답·조회 편의) |
| `quantity` | `DECIMAL(30,8)` | N | |
| `entry_price` | `DECIMAL(18,8)` | N | 교육: `buyTrade.entryPrice`. 일반: 생성 시점 `holding.averagePrice` snapshot |
| `exit_price_type` | `VARCHAR(10)` | N | `PRICE`\|`PERCENT` |
| `stop_loss_rate` | `DECIMAL(7,4)` | Y | PERCENT만 |
| `take_profit_rate` | `DECIMAL(8,4)` | Y | PERCENT만 |
| `stop_loss_price` | `DECIMAL(18,8)` | N | 확정 실행 가격선 |
| `take_profit_price` | `DECIMAL(18,8)` | N | 확정 실행 가격선 |
| `baseline_price` | `DECIMAL(18,8)` | N | |
| `baseline_observed_at` | `DATETIME(6)` | N | |
| `status` | `VARCHAR(20)` | N | `PENDING`\|`FILLED_TAKE_PROFIT`\|`FILLED_STOP_LOSS`\|`CANCELLED` (이 spec 범위에는 `CANCELLED_EXPIRED` 없음) |
| `reserved_at` | `DATETIME(6)` | N | |
| `closed_at` | `DATETIME(6)` | Y | |
| `triggered_order_id` | `BIGINT` FK | Y | |
| `replay_session_id` | `BIGINT` | Y | 이 spec 범위에서는 코인만 다루므로 항상 null. 주식 확장 시 `016`이 정의한 FK를 재사용 |
| `request_hash` | `CHAR(64)` | N | 멱등 fingerprint SHA-256 |

- `UNIQUE(user_id, intention_instance_key)` — MySQL은 다중 NULL을 서로 다른 값으로 취급하므로 일반 경로 행(항상 null)끼리는 이 제약과 무관하다. 교육 경로 identity 보장은 `016` 그대로다.
- holding당 `PENDING` 1건 제약은 위에서 설명한 대로 DB 제약이 아니라 앱 계층 검증이다 — 이 테이블에는 `(holding_id, status)` 부분 unique 인덱스를 두지 않는다.
- 새 migration은 이 candidate가 착수될 때 `exit_plans`, `exit_plan_conditions`, `exit_plan_idempotency_keys`를 한 번에 만든다(`016`의 candidate 7·후보 migration 소유권을 그대로 이 spec이 흡수). 기존 migration 수정은 하지 않는다(ADR-0004).

### `exit_plan_conditions`, `exit_plan_idempotency_keys`

`016` plan이 확정한 물리 설계를 그대로 사용한다(변경 없음) — 조건 테이블은 plan·`STOP_LOSS`/`TAKE_PROFIT`·`trigger_price DECIMAL(18,8)`·상태, key 테이블은 `(user_id, idempotency_key)` unique·`request_hash`·`exit_plan_id` FK.

## 응답 계약

`ExitPlanResponse`에 `holdingId`(항상 non-null)를 추가하고 `intentionId`·`buyTradeId`는 일반 경로에서 null이 되도록 nullable로 바꾼다. 그 외 필드(`exitPriceType`, 원본 rate, 확정 가격선, `baselinePrice`, `status`, `reservedAt`, `closedAt`, `triggeredOrderId`, `replaySessionId`)는 `016`·`019`가 정의한 그대로다. `GET /api/exit-plans`도 같은 응답 항목을 재사용한다.

## 오류 코드

| HTTP | 코드 | 사용처 | 신규 여부 |
|---|---|---|---|
| 404 | `NOT_FOUND` | 존재하지 않거나 타인 소유 `holdingId` | 기존 |
| 400 | `VALIDATION_ERROR` | 시장 범위 위반(주식 holding), 경로별 필드 조합 위반, 정밀도 위반 | 기존 |
| 409 | `EXIT_PLAN_ALREADY_EXISTS` | holding에 이미 `PENDING` plan 존재 | **신규** |
| 409 | `INSUFFICIENT_QTY` | `availableQuantity` 미달 | 기존 |
| 409 | `EXIT_PLAN_INVALID_PRICE_RANGE` | 가격선 범위·정밀도 위반 | 기존(`019`) |
| 409 | `PRICE_UNAVAILABLE` | 생성 시 유효 현재가 없음 | 기존 |
| 409 | `IDEMPOTENCY_CONFLICT` | 같은 key, 다른 request hash | 기존 |
| 404 | `EXIT_PLAN_NOT_FOUND` | 취소 대상 plan 없음/타인 소유 | 기존(`016`) |
| 409 | `EXIT_PLAN_NOT_PENDING` | terminal plan 취소·경합 패자 | 기존(`016`) |
| 409 | `PRACTICE_STEP_LOCKED`, `PRACTICE_EVIDENCE_MISSING`, `PRACTICE_ALREADY_COMPLETED` | 교육 경로(`intentionId` 지정)에서만 발생 | 기존(`016`), 일반 경로는 절대 발생하지 않음 |

## 테스트 계획

- 단위: 일반 경로 필드 조합 검증(홀딩당 필드 필수·금지), PERCENT 기준가로 `holding.averagePrice` 사용, 계산·범위 검증(`019` 재사용), fingerprint 정규화.
- 슬라이스(`@DataJpaTest`): `exit_plans.holding_id` FK·nullable 조합, `intention_instance_key` 다중 null 허용, `(user_id, idempotency_key)` unique.
- 슬라이스(`@WebMvcTest`): `intentionId` 유무에 따른 400/201 분기, 시장 제한 400, 소유권 404.
- 통합(Testcontainers):
  - 일반 경로 전체 흐름 — holding 보유 → OCO 생성(PRICE·PERCENT 각각) → 목록 → 취소, 예약 반환 검증.
  - 같은 holding에 두 번째 `PENDING` 생성 시도가 409 `EXIT_PLAN_ALREADY_EXISTS`로 무흔적 거부됨(첫 plan이 아직 `PENDING`일 때만).
  - 첫 plan이 체결·취소로 종결된 뒤 같은 holding에 새 `PENDING` plan 생성 성공(회귀 방지 — "1건 제약"이 "다시는 불가"로 오독되지 않는지 확인).
  - 교육 경로(`intentionId` 지정) 기존 흐름이 이 문서의 4단계(holding당 1건 검증) 추가에도 그대로 성공.
  - 주식 holding으로 일반 경로 생성 시도가 400으로 거부되고 흔적을 남기지 않음.
  - 취소 ↔ 트리거 경합에서 정확히 한 번 규칙(`015`의 동시성 테스트 패턴 재사용).
  - 일반 경로 멱등 재시도 — 같은 key 재요청이 재조회 폴백으로 200을 반환하고 두 번째 plan을 만들지 않음.

## 확정 경계와 잔여 위험

- 공통 예약 원장(`Holding.reservedQuantity` 등)의 물리 모델은 `015`가 이미 구현했다. 이 spec은 그 위에 새 소비자를 추가할 뿐 원장을 재설계하지 않는다.
- holding당 PENDING 1건 검증을 앱 계층에 두기로 한 결정은 동시 생성 요청 두 건이 정확히 같은 timing에 holding 락을 순차 획득한다는 가정에 의존한다 — holding을 잠근 뒤에만 조회하므로 TOCTOU는 없다.
- 주식 확장 시 `replay_session_id`·`CANCELLED_EXPIRED`·세션 잠금을 이 테이블에 다시 활성화하는 것은 `016`의 3차 MVP 완성 이슈가 맡는다. 이 spec은 그 컬럼을 nullable로 남겨 두어 스키마 재설계 없이 확장할 수 있게만 해 둔다.
