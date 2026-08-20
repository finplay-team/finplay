# Plan: 시장가/지정가 매매 기반 3단계 투자 실습 완결 경로 (OCO 없이)

## 관련 문서

- Spec: `./spec.md`
- PRD: `../../prd.md` — C-001, C-003, C-004, §2 "2차 MVP — 남은 범위와 계약 정의", §3 구현 현황
- 상속 정본(변경하지 않음): `ai/specs/016-investment-education-policy`(1단계·의도 API·공통 판정 원칙), `ai/specs/019-exit-price-policy`(PRICE/PERCENT 계산), `ai/specs/020-coin-practice-tutorial`(tutorial_key market 분기, scale 무관 수량 비교)
- 참고(수정하지 않음): `ai/specs/015-limit-order`(코인 지정가), `ai/specs/021-general-risk-management-oco`
- 관련 ADR: ADR-0002(레이어드), ADR-0003(테스트 전략), ADR-0004(migration 정책), ADR-0012(즐겨찾기·의도 인메모리)
- 기존 시장가/지정가 매수: `POST /api/orders`, `POST /api/orders/limit`

## 도메인 경계

- `favorite`, `education`(의도·합성 시세) — `016`과 동일, 변경 없음.
- 이 spec이 추가하는 도메인 로직(evidence 판정·관찰·복기)은 `education` 도메인 하위에 둔다(예: `com.finplay.api.education.marketpractice` 가칭). `016`의 OCO 전용 코드(아직 없음)와 패키지를 분리해 3차 MVP에서 OCO를 구현할 때 이 경로의 코드를 건드리지 않게 한다.
- 매수 체결 조회는 기존 `order`/`portfolio` 도메인의 조회 전용 조합만 사용한다(직접 repository 주입 금지, `education` service가 `order`/`portfolio`의 service 조회 메서드만 호출).
- OCO 관련 엔티티(`exit_plans` 등)는 이 spec에서 만들지 않는다.

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | `/api/education/practice` | 없음 | `InvestmentPracticeResponse` | 이 경로 기준 3단계 상태·잠금·evidence 조회 (최초 구현) |
| POST | `/api/education/practice/holding-observations` | `PracticeHoldingObservationCreateRequest` | `PracticeHoldingObservationResponse` | 서버 현재가로 참조선 접근 관찰 기록 |
| POST | `/api/education/practice/holding-reflections` | `PracticeHoldingReflectionCreateRequest` | `PracticeHoldingReflectionResponse` | A·B 관찰 이후 자유 복기 저장, 완료 확정 |

변경 없이 재사용(이 PR이 건드리지 않음): `POST /api/favorites`, `GET /api/favorites`, `DELETE /api/favorites/{instrumentId}`, `POST /api/education/practice/intentions`, `POST /api/orders`, `POST /api/orders/limit`, `GET /api/education/practice/synthetic-prices/{instrumentId}`.

모든 3개 API는 `Authorization: Bearer <access-token>`을 요구한다. JSON 요청은 `Content-Type: application/json`. 멱등키(`Idempotency-Key`)는 요구하지 않는다 — 두 POST 모두 호출마다 새 행을 추가하는 자연스러운 append이며 `016`의 관찰·복기도 같은 이유로 멱등키가 없었다. 예상하지 못한 내부 오류는 공통 500 `INTERNAL_ERROR`.

### 확정 HTTP·JSON 계약

| API | 입력 | 성공 | 도메인 오류 |
|---|---|---|---|
| `GET /api/education/practice?market=` | `market` 필수(`STOCK|CRYPTO`) | 200 `InvestmentPracticeResponse` | 400 `VALIDATION_ERROR`; 인증 공통 오류 |
| `POST /api/education/practice/holding-observations` | body `{"holdingId":1}` | 201 `PracticeHoldingObservationResponse` | 404 `NOT_FOUND`(holding 자체가 없거나 타인 소유 — 존재 은닉); 409 `PRACTICE_EVIDENCE_MISSING`(chain 재해석 실패: intention 유실·불일치), `PRICE_UNAVAILABLE` |
| `POST /api/education/practice/holding-reflections` | body `{"holdingId":1,"answer":"..."}` | 최초 201 `PracticeHoldingReflectionResponse` | 404 `NOT_FOUND`(holding); 409 `PRACTICE_EVIDENCE_MISSING`(A·B 미충족 또는 chain 불일치), `PRACTICE_ALREADY_COMPLETED` |

숫자 필드 누락·null·0 이하, `answer` 길이 위반은 400 `VALIDATION_ERROR`. 공통 오류 body는 `{"error":{"code":"...","message":"...","requestId":"..."}}`. DTO는 Java `record`, 시각은 `LocalDateTime` ISO-8601, 가격은 `BigDecimal`.

`holdingId`가 존재하지 않거나 타인 소유이면 리소스 존재를 숨겨 404 `NOT_FOUND`로 응답한다(다른 도메인 GET의 소유권 은닉 관례 재사용). holding은 본인 소유가 확인된 뒤 내부적으로 해당 instrument·사용자의 favorite→intention→buyTrade chain을 재해석하며, 그 chain 자체가 없거나 evidence 규칙을 만족하지 못하면 409 `PRACTICE_EVIDENCE_MISSING`이다(존재 자체는 확인됐으므로 404가 아니라 409).

### DTO 필드와 nullable

- `PracticeHoldingObservationCreateRequest(Long holdingId)`: non-null·양수.
- `PracticeHoldingObservationResponse(Long observationId, Long holdingId, BigDecimal currentPrice, LocalDateTime observedAt, Boolean closerToBoundary, String closerBoundary, String evidenceType)`: 앞 다섯은 non-null. `closerBoundary` 허용값 `STOP_LOSS|TAKE_PROFIT`(해당 없으면 null), `evidenceType` 허용값 `CLOSER_TO_BOUNDARY|TIMED_REPETITION`(A·B 모두 미충족이면 null). `FINAL_EVENT`는 이 경로에 없다(evidence C 부재).
- `PracticeHoldingReflectionCreateRequest(Long holdingId, String answer)`: `holdingId` non-null·양수, `answer`는 `@NotBlank @Size(max=2000)`, whitespace-only 거부, raw 길이 최대 2000, 원문 저장.
- `PracticeHoldingReflectionResponse(Long reflectionId, Long holdingId, String prompt, String answer, LocalDateTime createdAt)`: 모두 non-null. 정답·점수·보상 필드 없음.
- `InvestmentPracticeResponse(String tutorialKey, String status, Integer currentStep, List<PracticeStepResponse> steps, LocalDateTime completedAt)`: `016`과 동일 구조. 필수 `market`이 `STOCK`이면 `INVESTMENT_PRACTICE_V1`, `CRYPTO`이면 `COIN_PRACTICE_V1`이다. 한 응답은 한 key만 나타내며 OCO 전용 key와 완료 상태를 공유하지 않는다.
- `PracticeStepResponse(Integer step, String status, Boolean locked, PracticeEvidenceResponse evidence)`: `016`과 동일.
- `PracticeEvidenceResponse(Long favoriteId, LocalDateTime favoriteCreatedAt, Long intentionId, LocalDateTime intentionCreatedAt, Long buyTradeId, LocalDateTime buyTradeExecutedAt, Long holdingId, BigDecimal referenceStopLossPrice, BigDecimal referenceTakeProfitPrice, Long observationId, LocalDateTime observationObservedAt, String evidenceType, Long reflectionId, LocalDateTime reflectionCreatedAt)`: `016`의 `exitPlanId`·`exitPlanReservedAt`을 `holdingId`(대응 시각 없음 — holding 생성시각을 별도로 노출하지 않는다, buyTradeExecutedAt이 그 역할을 한다)와 계산된 참조 가격선 둘로 대체했다. `referenceStopLossPrice`·`referenceTakeProfitPrice`는 buyTrade가 확정된 뒤에만 non-null이고, intention이 인메모리에서 유실되면(재시작) 다시 계산할 수 없어 null로 되돌아갈 수 있다(완료 후에는 회귀하지 않음 — 아래 "재시작 유실과 완료 불변" 참고). 각 id·시각 쌍의 null 규칙은 `016`과 동일(favorite/intention/buyTrade는 함께 null 또는 함께 non-null).

## 데이터 모델

> 이 spec은 예약 원장(exit_plans류)을 만들지 않는다. 신규 테이블은 관찰·복기 두 개뿐이다.

- `practice_market_observations`: `id BIGINT PK AUTO_INCREMENT`, `user_id BIGINT NOT NULL`, `holding_id BIGINT NOT NULL`, `instrument_id BIGINT NOT NULL`(조회 편의를 위한 비정규화, holding에서 유도 가능하지만 인덱스·evidence 조회를 단순화), `current_price DECIMAL(18,8) NOT NULL`, `closer_to_boundary BOOLEAN NULL`, `closer_boundary VARCHAR(16) NULL`(`STOP_LOSS|TAKE_PROFIT`), `evidence_type VARCHAR(20) NULL`(`CLOSER_TO_BOUNDARY|TIMED_REPETITION`), `observed_at DATETIME(6) NOT NULL`, FK `user_id → users(id)`, FK `holding_id → holdings(id)`, index `(user_id, holding_id, observed_at)`. `ON DELETE`는 지정하지 않는다(기존 관례, holding·user 삭제 없음이 전제).
- `practice_market_reflections`: `id BIGINT PK AUTO_INCREMENT`, `user_id BIGINT NOT NULL`, `holding_id BIGINT NOT NULL`, `tutorial_key VARCHAR(50) NOT NULL`, `prompt_version SMALLINT NOT NULL DEFAULT 1`, `answer VARCHAR(2000) NOT NULL`, `created_at DATETIME(6) NOT NULL`, FK `user_id → users(id)`, FK `holding_id → holdings(id)`, `UNIQUE(user_id, tutorial_key)`(완료는 튜토리얼당 1회이므로 exit_plan 단위가 아니라 이 unique로 충분하다 — `016`의 `UNIQUE(user_id, exit_plan_id)`와 다른 점).
- 재사용(스키마 변경 없음): `favorites`(인메모리, `016`/ADR-0012), `practice_intentions`(인메모리), `practice_progresses`, `practice_completions`(둘 다 이미 `tutorial_key VARCHAR(50)`, `UNIQUE(user_id, tutorial_key)` — `020`이 이미 market 분기를 확인해뒀다), `holdings`, `trades`, `orders`.
- **신규 migration 번호는 착수 시점에 `origin/dev`의 최신 `V{N}`을 다시 확인해 정한다.** 이 문서 작성 시점(2026-08-10) origin/dev 최신은 `V26__create_community_post_images.sql`이므로 잠정 `V27__create_practice_market_observations_and_reflections.sql`을 쓰되, 착수 전 재확인이 필수다(다른 동시 작업이 먼저 V27을 점유할 수 있음).

## Evidence 계산 규칙

### 2단계 chain 해석

1. 대상 사용자의 완료되지 않은(또는 완료된) 튜토리얼 key(`INVESTMENT_PRACTICE_V1`/`COIN_PRACTICE_V1`)별로 독립 계산한다.
2. 그 key에 속하는 market의 본인 favorite 각각에 대해, 같은 `instrumentId`의 살아있는 intention(인메모리) 중 `createdAt`이 가장 이른 것을 본다.
3. 그 intention의 `instrumentId`·수량과 정규화 비교(`BigDecimal.compareTo`, `020`의 scale 무관 규칙)로 일치하고 `executedAt > intention.createdAt`인 본인 `FILLED` BUY `trade`를 `executedAt ASC, tradeId ASC`로 하나 선택한다.
4. 그 trade의 `instrumentId`로 본인 holding(owner·instrument 일치)이 존재하면 chain이 완성된다.
5. 여러 favorite/intention이 후보가 되면 `016`과 동일한 우선순위(qualifying observation 있는 chain 우선, 그 안에서 buyTrade.executedAt ASC, 없으면 전체 유효 chain 중 같은 정렬, 그것도 없으면 favorite.createdAt ASC)로 하나를 선택한다.

### 3단계 참조 가격선 계산 (매 요청마다 재계산, snapshot 저장 안 함)

```
if intention.exitPriceType == PRICE:
    referenceStopLossPrice = intention.stopLoss
    referenceTakeProfitPrice = intention.takeProfit
else: # PERCENT
    entryPrice = buyTrade.entryPrice
    referenceStopLossPrice = entryPrice × (1 - stopLossRate/100)   # 019 공식, scale 8 HALF_UP
    referenceTakeProfitPrice = entryPrice × (1 + takeProfitRate/100)
```

- 계산에 필요한 intention이 이미 유실됐으면(서버 재시작) 참조 가격선을 계산할 수 없다 — 이때 관찰 POST는 409 `PRACTICE_EVIDENCE_MISSING`이고 GET evidence는 해당 필드가 null로 보인다(완료 전이라면). 완료 후에는 이 계산이 필요 없다(완료 판정에 재검증하지 않음, 아래 "재시작 유실과 완료 불변" 참고).

### Evidence A (경계 접근)

- `baselineDistance = min(|entryPrice - referenceStopLossPrice|, |referenceTakeProfitPrice - entryPrice|)`
- 관찰 시점 `currentDistance = min(|currentPrice - referenceStopLossPrice|, |referenceTakeProfitPrice - currentPrice|)`
- `currentDistance < baselineDistance`이면 `closerToBoundary=true`, `evidenceType=CLOSER_TO_BOUNDARY`, `closerBoundary`는 더 가까운 쪽(`STOP_LOSS`|`TAKE_PROFIT`)을 그대로 노출한다.
- **동률 tie-break(#297 PR #298 리뷰에서 결정)**: 손절선·익절선까지의 거리가 정확히 같으면 `closerBoundary=STOP_LOSS`를 우선한다. `019`가 강제하는 `stopLossPrice < entryPrice < takeProfitPrice` 불변조건 아래에서는 동률 지점(두 경계의 중간점)의 거리가 항상 `baselineDistance` 이상이 되어 `closerToBoundary=true` 자체가 성립할 수 없으므로, 이 tie-break는 현재 도달 불가능한 분기다. 그럼에도 임의로 방치하지 않고 `STOP_LOSS` 우선으로 명시 확정한다 — 근거: 향후 `019`의 그 불변조건이 깨지는 입력(예: rate 검증 누락, 계산 버그)이 이 서비스에 들어오면 이 분기가 조용히 실제 결과를 바꿀 수 있으므로, 그 시점에 "정의되지 않은 동작"이 아니라 "이미 정한 규칙"이 적용되게 한다.

### Evidence B (시간 분산 관찰)

- 같은 chain(holding)에 대해 저장된 관찰이 3회 이상이고, 그중 `observedAt`이 가장 이른 것과 가장 늦은 것의 차이가 2분 이상이면 그 관찰(가장 늦은 것)의 `evidenceType=TIMED_REPETITION`이다. `016`과 동일 판정.

### Evidence C

- 존재하지 않는다. GET evidence의 `evidenceType`은 `CLOSER_TO_BOUNDARY|TIMED_REPETITION`만 허용값이고 `FINAL_EVENT`는 이 경로에 나타나지 않는다.

### 재시작 유실과 완료 불변

- ADR-0012의 기존 원칙을 그대로 따른다: 완료 전에 intention이 인메모리에서 유실되면 해당 chain의 evidence가 재구성되지 않고, 사용자는 그 단계부터 다시 진행해야 한다(evidence chain 자체가 사라지므로 GET이 이전 단계로 되돌아간 상태를 보여준다).
- 완료(`practice_completions` 행 생성) 이후에는 intention 유실과 무관하게 완료 상태가 유지된다 — 완료 트랜잭션이 그 시점에 필요한 모든 값을 `practice_market_reflections`(및 이미 저장된 `practice_market_observations`)에 영속했기 때문이다. GET의 완료 후 evidence는 이 영속 테이블에서만 조회하며 살아있는 intention을 다시 요구하지 않는다.

## 입력 명세

| 요청 | 필드 | 검증 |
|---|---|---|
| Holding observation | `holdingId` | 필수, 양수, 본인 소유(아니면 404), chain 해석 성공(아니면 409 `PRACTICE_EVIDENCE_MISSING`), 서버 유효 현재가 필요(없으면 409 `PRICE_UNAVAILABLE`) |
| Holding reflection | `holdingId`, `answer` | `holdingId` 위와 동일. `answer`는 `@NotBlank @Size(max=2000)`, whitespace-only 거부, 원문 저장. A 또는 B 관찰 기존 존재 필요(없으면 409 `PRACTICE_EVIDENCE_MISSING`). 이미 완료면 409 `PRACTICE_ALREADY_COMPLETED` |

## 트랜잭션과 경합

- 관찰 저장: holding 소유권만 확인하는 읽기(락 불필요, append-only insert) — 동시 관찰 여러 건이 그냥 여러 행으로 쌓여도 무해하다(OCO의 `PENDING` 단일 상태 잠금과 달리 경쟁 자원이 없음).
- 복기 저장: `practice_progresses(DB) → practice_market_reflections unique(user_id, tutorial_key)` 경합만 방어한다. `016`처럼 favorite in-memory 락까지 잡을 필요는 없다 — 이 경로는 favorite 삭제가 evidence를 무효화하는 시점이 이미 GET 계산(매 요청 재해석)에서 자연히 반영되므로, 복기 저장 시점에만 별도 락으로 TOCTOU를 막을 급한 이유가 약하다(favorite가 지금 삭제되어도 chain 해석이 실패해 그 요청 자체가 409가 될 뿐 이중 완료가 생기지 않는다 — OCO처럼 "수량 예약을 이중으로 반환/소비"할 자원이 없기 때문). `(user_id, tutorial_key)` DB unique가 최종 방어선이다.
  - `practice_progresses`를 `SELECT ... FOR UPDATE`로 잠그고 이미 `COMPLETED`면 409, 아니면 현재 evidence(chain + A/B 중 하나)를 재검증한 뒤 `practice_market_reflections` 1행 + `practice_completions` 1행 저장과 progress `COMPLETED` 전이를 같은 트랜잭션에서 처리한다(`016`과 동일한 동시 요청 직렬화 원칙, `progress` 잠금만 유지).
- GET 조회: 순수 읽기, 어떤 잠금도 쓰지 않는다.

## 테스트 계획

- 단위: 2단계 chain 해석(수량 정규화 비교, 최이른 buyTrade 선택), 참조 가격선 계산(PRICE·PERCENT, 019 반올림), evidence A/B 판정 경계값, evidence C 부재 확인, tutorial_key market 분기.
- 슬라이스: `GET /api/education/practice?market=`의 market 누락·허용값 검증과 시장별 단일 key 응답, `POST .../holding-observations`, `POST .../holding-reflections`의 인증·소유권·검증·응답, holding 타인 소유 404, chain 실패 409.
- 통합(2차 MVP, 이슈 #313): 코인 즐겨찾기 → 의도 → 지정가 매수 FILLED → 관찰 A 또는 B → 복기 → 완료 전체 흐름 1건, 실제 Spring Context 재생성으로 intention 유실 시 완료 전 evidence 회귀·완료 후 DB 완료 불변을 검증한다.
- 통합(3차 MVP로 이관): 주식 시장가 튜토리얼 전체 흐름 검증. 기존 주식 기능 계약은 유지하며 이슈 #313에서 다루지 않는다.
- 경합: 동시 복기 요청이 `practice_progresses` 잠금에서 직렬화되어 한 건만 201·나머지 409, `practice_market_reflections` 행이 정확히 1개인지 DB로 확인.

## 후속 확인 필요 (이 spec이 결정하지 않음)

- `MarketPracticeChainResolutionService.resolve()`는 favorite별 trade·holding 조회에 더해 완성 chain별 qualifying observation 조회를 수행한다. favorite 개수 상한이 없으므로 데이터가 늘면 조회가 선형 증가한다. 후속 성능 작업에서 favorite 상한을 도입하거나 intention·holding·observation을 일괄 조회하도록 배치화하며, 완료 조회의 `Holding → Instrument` lazy 조회도 함께 줄인다(PR #307 리뷰 권장).

- ~~`ai/prd.md` §2·§3 구현 현황 반영 방식 결정~~ — **PR #307에서 해소했다.** holding 기반 완료 경로를 별도 행으로 완료 처리하고 OCO 미착수 행과 분리했다.
- **시장별 최초 완료 보상(이슈 #343)**: `PracticeHoldingReflectionService.createReflection`이 `progress.complete(now)` 직후 같은 트랜잭션에서 `AccountService.getAccountForUpdate` + `Account.addCash(5_000_000L)`으로 완료된 시장 계좌에 500만원을 지급한다. 위 "복기 저장" 절의 `(user_id, tutorial_key)` unique 방어선을 그대로 재사용해 별도 지급 이력 테이블 없이 정확히 1회만 실행된다. `InvestmentPracticeResponse.rewardAmount`가 완료 응답에서만 5,000,000을 노출한다(031의 4단계 완료 경로도 같은 서비스를 거치므로 동일 보상이 적용된다).
- 3차 OCO 경로는 `GET /api/education/practice/oco?market=`와 `INVESTMENT_OCO_PRACTICE_V1|COIN_OCO_PRACTICE_V1`을 사용한다. 이 문서는 해당 production 구현을 다루지 않는다.
- 클라이언트는 주식·코인 진행을 각각 조회해 화면에서 조합한다. 서버가 두 시장을 배열로 합쳐 반환하는 계약은 두지 않는다.
