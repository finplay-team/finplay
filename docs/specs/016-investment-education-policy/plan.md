# Plan: 3단계 투자 실습 튜토리얼

## 관련 문서
- Spec: `./spec.md`
- PRD: `../../prd.md` — C-001, C-003, C-004, 2차·3차 MVP 범위
- 관련 ADR: ADR-0002, ADR-0003, ADR-0004
- 기존 시장가 주문: `POST /api/orders`
- Spec 번호: 011은 order-ledger가 점유하고 013은 원격 `feat/143-candle-interval`에서 사용하며 014 ranking·015 limit가 예약되어 있어 016을 사용한다.

## 착수 제한
- 현재 #158 범위는 상세 API 계약·구현 순서 확정뿐이다. production, Controller, migration, `docs/api-routes.md`, `docs/api-contracts.md`를 변경하지 않는다.
- 아래 API는 후속 구현 이슈의 계약이며 구현 전에는 실제 사용 가능하다고 문서화하지 않는다.

## 도메인 경계
- `favorite`: 사용자별 관심 종목 등록·목록·해제.
- `education`: 사용자·튜토리얼 공통 progress, 3단계 의도·관찰·복기 기록과 실제 도메인 증거를 읽어 계산한 진행 상태. education application orchestration이 favorite·intention을 검증하고 검증 snapshot을 명시적 order application port에 전달한다.
- `order`: 기존 시장가 즉시 체결과 tutorial-only OCO exit plan의 예약·트리거·취소. order application port는 전달받은 snapshot과 order 소유의 trade·holding만 검증하며 education service나 repository를 호출하지 않는다.
- 호출 방향은 `education application → order application port` 한 방향만 허용한다. order에서 education으로의 역호출과 양방향 service 참조를 금지해 순환 의존을 막는다.
- 도메인 간 검증은 service를 통해 수행하고 다른 도메인의 repository를 직접 주입하지 않는다.

## API 설계
| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | `/api/favorites` | `FavoriteCreateRequest` | `FavoriteResponse` | 본인 즐겨찾기 등록 |
| GET | `/api/favorites` | 없음 | `FavoriteListResponse` | 본인 목록 순수 조회. 확인 시각 등 write 없음 |
| DELETE | `/api/favorites/{instrumentId}` | 없음 | 없음 | 본인 즐겨찾기 해제 |
| GET | `/api/education/practice` | 없음 | `InvestmentPracticeResponse` | 실제 증거 기반 3단계 상태·잠금·증거 조회 |
| POST | `/api/education/practice/intentions` | `PracticeIntentionCreateRequest` | `PracticeIntentionResponse` | 매수 전 손절·익절·수량 기록 |
| POST | `/api/orders` | 기존 `OrderCreateRequest` | 기존 주문·체결 응답 | `MARKET` 매수 즉시 체결. 예약 아님 |
| POST | `/api/exit-plans` | `ExitPlanCreateRequest` | `ExitPlanResponse` | 한 보유 수량에 손절·익절 OCO 생성 |
| GET | `/api/exit-plans?status=` | 선택 `status` | `ExitPlanListResponse` | 본인 예약 목록 순수 조회. 생략 시 `PENDING`, 확인 시각 등 write 없음 |
| DELETE | `/api/exit-plans/{exitPlanId}` | 없음 | 없음 | PENDING OCO 전체 취소와 예약 1회 반환 |
| POST | `/api/education/practice/observations` | `PracticeObservationCreateRequest` | `PracticeObservationResponse` | 서버 현재가로 라인 접근 관찰 기록 |
| POST | `/api/education/practice/reflections` | `PracticeReflectionCreateRequest` | `PracticeReflectionResponse` | 정답 없는 3단계 자유 복기 저장 |

모든 10개 신규 API는 `Authorization: Bearer <access-token>`을 요구한다. 인증 누락·실패는 공통 401이다. path/body의 id로 직접 조회·조작하는 favorite 또는 exit plan이 타인 소유이면 존재를 숨겨 해당 리소스의 404로 응답한다. 단, OCO 생성이 내부에서 검증하는 favorite → intention → trade → holding chain의 존재·소유자·종목·수량 불일치는 409 `PRACTICE_EVIDENCE_MISSING`이고, intention 생성의 favorite 부재·종목 불일치는 409 `PRACTICE_STEP_LOCKED`다. JSON 요청은 `Content-Type: application/json`이다. `POST /api/exit-plans`만 `Idempotency-Key` UUID header가 필수이고, 나머지 POST는 멱등하지 않으며 각각 중복 규칙으로 보호한다. DELETE는 성공 시 body 없는 204다. 어느 API에서든 예상하지 못한 내부 오류는 PRD 공통 계약의 500 `INTERNAL_ERROR` body로 응답한다.

### 확정 HTTP·JSON 계약

| API | 입력 | 성공 | 정렬·페이지·멱등 | 도메인 오류 |
|---|---|---|---|---|
| `POST /api/favorites` | body `{"instrumentId":1}` | 201 `FavoriteResponse` | 같은 사용자·종목 재등록은 기존 행을 반환하지 않고 409 | 404 `NOT_FOUND`(종목), 409 `INSTRUMENT_NOT_TRADABLE`, `DUPLICATE_RESOURCE` |
| `GET /api/favorites` | 추가 입력 없음 | 200 `{"content":[FavoriteResponse...]}` | `createdAt DESC, favoriteId DESC`, pagination 없음, write 없음 | 인증 공통 오류만 |
| `DELETE /api/favorites/{instrumentId}` | 양의 `Long` path | 204 | 반복 삭제는 404 | 404 `FAVORITE_NOT_FOUND` |
| `GET /api/education/practice` | 추가 입력 없음 | 200 `InvestmentPracticeResponse` | 단일 튜토리얼 조회, pagination·write 없음 | 인증 공통 오류만 |
| `POST /api/education/practice/intentions` | body `instrumentId`, `quantity`, `stopLoss`, `takeProfit` | 201 `PracticeIntentionResponse` | 유효 요청마다 새 intention; 완료 뒤 409 | 404 `NOT_FOUND`는 요청 `instrumentId` 종목 자체가 없을 때만 사용; favorite 부재·타인 favorite·종목 불일치는 409 `PRACTICE_STEP_LOCKED`; 완료는 409 `PRACTICE_ALREADY_COMPLETED` |
| `POST /api/exit-plans` | UUID `Idempotency-Key`; body `intentionId`, `buyTradeId`, `instrumentId`, `quantity`, `stopLoss`, `takeProfit` | 최초 201, 기존 plan 수렴은 200 `ExitPlanResponse` | 아래 fingerprint 수렴 규칙 적용 | 요청 `instrumentId` 종목 자체의 미존재만 404 `NOT_FOUND`; 내부 favorite·intention·trade·holding의 부재·타인 소유·종목·수량 chain 불일치는 409 `PRACTICE_EVIDENCE_MISSING`; 그 밖에 409 `EXIT_PLAN_INVALID_PRICE_RANGE`, `EXIT_PLAN_SESSION_CLOSED`, `PRICE_UNAVAILABLE`, `INSUFFICIENT_QTY`, `IDEMPOTENCY_CONFLICT` |
| `GET /api/exit-plans?status=` | 선택 enum query `status`; 생략 시 `PENDING`, 명시할 때도 이 버전은 `PENDING`만 허용 | 200 `{"content":[ExitPlanResponse...]}` | `reservedAt DESC, exitPlanId DESC`, pagination 없음, write 없음 | `PENDING` 외 값은 400 `VALIDATION_ERROR` |
| `DELETE /api/exit-plans/{exitPlanId}` | 양의 `Long` path | 204 | PENDING만 취소; 반복·경합 패자는 409 | 404 `EXIT_PLAN_NOT_FOUND`, 409 `EXIT_PLAN_NOT_PENDING` |
| `POST /api/education/practice/observations` | body `{"exitPlanId":1}` | 201 `PracticeObservationResponse` | 호출마다 서버 관찰 1행; PENDING만 허용 | 404 `EXIT_PLAN_NOT_FOUND`, 409 `EXIT_PLAN_NOT_PENDING`, `PRICE_UNAVAILABLE` |
| `POST /api/education/practice/reflections` | body `exitPlanId`, `answer` | 최초 201 `PracticeReflectionResponse` | 사용자·튜토리얼 최초 완료만 저장; 모든 재시도 409·무저장 | 직접 지정한 plan이 없거나 타인 소유면 404 `EXIT_PLAN_NOT_FOUND`; 본인 plan의 연결 evidence 누락·불일치는 409 `PRACTICE_EVIDENCE_MISSING`; 완료는 409 `PRACTICE_ALREADY_COMPLETED` |

OCO 요청 fingerprint는 UTF-8 canonical JSON의 SHA-256이다. key 순서는 `intentionId`, `buyTradeId`, `instrumentId`, `quantity`, `stopLoss`, `takeProfit`으로 고정하고 불필요한 whitespace를 넣지 않는다. 세 `BigDecimal`은 양수 검증 후 `stripTrailingZeros().toPlainString()` 결과를 JSON number token으로 사용해 `1.0`과 `1.00`을 같게 만든다. key·intention 조회 알고리즘은 다음으로 고정한다.

`Idempotency-Key` header는 `UUID.fromString`으로 parse한다. 성공하면 입력의 대소문자·표현을 그대로 쓰지 않고 `UUID.toString()`의 36자 lowercase canonical 문자열로 normalize해 lookup/store한다. Java UUID parsing에 성공하는 비정규 표현도 이 canonical 값으로 수렴하고 parsing 실패만 400 `VALIDATION_ERROR`다. fingerprint에는 idempotency key를 포함하지 않는다.

1. `(userId,idempotencyKey)`는 `exit_plan_idempotency_keys` mapping을 통해 plan을 조회하고, `(userId,intentionId)`는 `exit_plans` 정본을 조회한다.
2. 둘 다 없으면 plan·두 condition·예약과 요청 key mapping을 한 트랜잭션에서 생성한다.
3. key hit만 있으면 mapping의 plan/request hash가 요청과 같을 때 200, 다르면 409 `IDEMPOTENCY_CONFLICT`다.
4. intention hit만 있으면 plan의 request hash가 같을 때 새 key mapping을 같은 트랜잭션에 저장한 뒤 200, 다르면 409 `IDEMPOTENCY_CONFLICT`다.
5. 둘 다 같은 plan이면 plan·mapping의 hash가 요청과 모두 같을 때 200, 하나라도 다르면 409 `IDEMPOTENCY_CONFLICT`다.
6. 둘 다 존재하지만 `planId`가 다르면 fingerprint와 무관하게 409 `IDEMPOTENCY_CONFLICT`다.
7. key mapping의 `(user_id,idempotency_key)` 또는 plan의 `(user_id,intention_id)` unique 경합 뒤에는 아래 transaction retry 경계를 적용한 뒤 key mapping→plan과 intention→plan을 모두 재조회해 1~6으로 결정한다. 따라서 관측한 모든 key가 영속화되고 이후 다른 request나 intention에서 재사용되면 alias hit로 409가 된다.

후보 7의 한 `REQUIRED` transactional attempt에서 plan unique 또는 mapping unique 위반이 발생하면 그 attempt는 plan·condition·reservation·mapping을 전부 rollback하고 즉시 끝낸다. rollback-only/실패 transaction 안에서 조회나 저장을 계속하지 않는다. retry coordinator 자체는 비트랜잭션이며 `@Transactional`을 붙이지 않는다. creation·reconciliation·final-read는 각각 별도 Spring proxy bean 메서드 또는 `TransactionTemplate`로 새 `REQUIRED` transaction을 열어야 하며 같은 bean의 self-invocation으로 transaction 경계를 만들지 않는다. coordinator가 예외를 잡고 unique winner commit이 보이는 새 transaction에서 key mapping→plan과 intention→plan을 모두 재조회해 1~6을 재적용한다. 동일 intention/hash loser의 새 key는 이 새 transaction에서 mapping을 저장하고 200, 다른 plan/hash는 409다. alias 저장이 다시 mapping unique 경합하면 그 transaction도 전부 rollback하고 세 번째 새 read-only transaction에서 winner mapping과 plan을 재조회해 200 또는 409를 결정한다. creation attempt, reconciliation attempt, final read의 최대 3개 transaction으로 제한한다. final read에도 winner 정본이 보이지 않는 DB invariant 위반은 위 공통 500 `INTERNAL_ERROR`를 의도적으로 발생시키는 후보 7의 구체 사례다. 실패 attempt의 부분 저장은 항상 0이다.

숫자 path/body의 누락·null·0 이하, enum 불일치, answer 길이 위반, 잘못된 UUID header는 400 `VALIDATION_ERROR`다. 공통 오류 body는 항상 `{"error":{"code":"...","message":"...","requestId":"..."}}`이며 DTO는 Java `record`, 시각은 `LocalDateTime` ISO-8601 문자열, 가격·수량은 `BigDecimal` JSON number를 사용한다.

수량은 실제 order schema와 같은 `DECIMAL(30,8)`로 정수부 최대 22자리·소수부 최대 8자리이며 request에 `@Positive @Digits(integer=22, fraction=8)`를 적용한다. `quantity`, holding 예약·가용 수량과 모든 quantity 응답에 동일 정밀도를 사용한다. 가격은 `DECIMAL(18,8)`로 정수부 최대 10자리·소수부 최대 8자리이며 request의 `stopLoss`, `takeProfit`에 `@Positive @Digits(integer=10, fraction=8)`를 적용한다. `entryPrice`, `baselinePrice`, `currentPrice`, condition trigger price와 모든 가격 응답·저장 컬럼에도 동일 정밀도를 사용한다. 초과 scale/precision은 반올림하지 않고 400 `VALIDATION_ERROR`다. 모든 request/path의 `instrumentId`, `intentionId`, `buyTradeId`, `exitPlanId`는 positive `Long`이다.

### DTO 필드와 nullable

- `FavoriteCreateRequest(Long instrumentId)`: non-null·양수.
- `FavoriteResponse(Long favoriteId, Long instrumentId, String market, String symbol, String name, LocalDateTime createdAt)`: 모두 non-null, `market` 허용값은 `STOCK|CRYPTO`.
- `FavoriteListResponse(List<FavoriteResponse> content)`: non-null, 없으면 빈 배열.
- `PracticeIntentionCreateRequest(Long instrumentId, BigDecimal quantity, BigDecimal stopLoss, BigDecimal takeProfit)`: 모두 non-null·양수; id는 positive `Long`, quantity는 `@Digits(integer=22, fraction=8)`, 두 가격은 `@Digits(integer=10, fraction=8)`.
- `PracticeIntentionResponse(Long intentionId, Long instrumentId, BigDecimal quantity, BigDecimal stopLoss, BigDecimal takeProfit, LocalDateTime createdAt)`: 모두 non-null; quantity는 `(30,8)`, 가격은 `(18,8)` 범위.
- `ExitPlanCreateRequest(Long intentionId, Long buyTradeId, Long instrumentId, BigDecimal quantity, BigDecimal stopLoss, BigDecimal takeProfit)`: 모두 non-null·양수; ids는 positive `Long`, quantity는 `@Digits(integer=22, fraction=8)`, 두 가격은 `@Digits(integer=10, fraction=8)`.
- `ExitPlanResponse(Long exitPlanId, Long intentionId, Long buyTradeId, Long replaySessionId, Long instrumentId, BigDecimal quantity, BigDecimal entryPrice, BigDecimal stopLoss, BigDecimal takeProfit, BigDecimal baselinePrice, LocalDateTime baselineObservedAt, String status, LocalDateTime reservedAt, LocalDateTime closedAt, Long triggeredOrderId)`: `status` 허용값은 `PENDING|FILLED_TAKE_PROFIT|FILLED_STOP_LOSS|CANCELLED|CANCELLED_EXPIRED`; `replaySessionId`는 코인만 null, `closedAt`·`triggeredOrderId`는 PENDING에서 null이며 취소·만료의 `triggeredOrderId`도 null; 나머지는 non-null.
- `ExitPlanListResponse(List<ExitPlanResponse> content)`: non-null, 없으면 빈 배열.
- `PracticeObservationCreateRequest(Long exitPlanId)`: non-null·양수. 클라이언트 가격·유형·시각 필드는 없다.
- `PracticeObservationResponse(Long observationId, Long exitPlanId, BigDecimal currentPrice, LocalDateTime observedAt, Boolean closerToBoundary, String closerBoundary, String evidenceType)`: 앞의 다섯 필드는 non-null이고 `currentPrice`는 `(18,8)` 범위다. `closerBoundary` 허용값은 `STOP_LOSS|TAKE_PROFIT`이며 가까워진 경계가 없으면 null, `evidenceType` 허용값은 `CLOSER_TO_BOUNDARY|TIMED_REPETITION`이며 A·B가 아직 충족되지 않으면 null. 서버 내부 관찰의 `FINAL_EVENT`는 이 POST 응답 값이 아니다.
- `PracticeReflectionCreateRequest(Long exitPlanId, String answer)`: id는 non-null·양수이고 answer는 `@NotBlank @Size(max=2000)`이다. whitespace-only는 거부하고 raw Java `String.length()`가 2000 이하여야 하며 trim·공백 제거 없이 원문을 저장한다.
- `PracticeReflectionResponse(Long reflectionId, Long exitPlanId, String prompt, String answer, LocalDateTime createdAt)`: 모두 non-null.
- `InvestmentPracticeResponse(String tutorialKey, String status, Integer currentStep, List<PracticeStepResponse> steps, LocalDateTime completedAt)`: `tutorialKey=INVESTMENT_PRACTICE_V1`, `status` 허용값은 `NOT_STARTED|IN_PROGRESS|COMPLETED`; `currentStep`은 완료 시 null, 그 외 1~3; `completedAt`은 완료 전 null. `steps`는 항상 1,2,3 순서의 세 항목이다.
- `PracticeStepResponse(Integer step, String status, Boolean locked, PracticeEvidenceResponse evidence)`: 모두 non-null. `status`는 `NOT_STARTED|IN_PROGRESS|COMPLETED`; 잠긴 단계의 evidence도 null 대신 모든 nullable 필드가 null인 객체다.
- `PracticeEvidenceResponse(Long favoriteId, LocalDateTime favoriteCreatedAt, Long intentionId, LocalDateTime intentionCreatedAt, Long buyTradeId, LocalDateTime buyTradeExecutedAt, Long exitPlanId, LocalDateTime exitPlanReservedAt, Long observationId, LocalDateTime observationObservedAt, String evidenceType, Long reflectionId, LocalDateTime reflectionCreatedAt)`: 각 리소스 id와 대응 시각은 함께 null 또는 함께 non-null이다. observation의 id·시각·`evidenceType`은 삼쌍으로 null/non-null이며 type 허용값은 `CLOSER_TO_BOUNDARY|TIMED_REPETITION|FINAL_EVENT`다. 선택된 chain에서 복수 관찰 증거가 충족되면 `observedAt ASC, observationId ASC`의 최초 qualifying observation을 선택한다. 1단계는 favorite 쌍만, 2단계는 favorite·intention·buyTrade·exitPlan 쌍, 3단계 reflection 전에는 앞 단계와 qualifying observation 삼쌍만, reflection 저장 뒤에는 reflection 쌍까지 채운다. 아직 확보되지 않은 이후 리소스는 null이고 단일 합성 `evidencedAt`은 두지 않는다.

### Candidate 12 진행 상태 정본

`GET /api/education/practice`는 아래 표대로 계산한다. `unlocked`는 `locked=false`를 뜻한다. locked 단계의 `PracticeEvidenceResponse`는 객체 자체는 non-null이고 모든 필드가 null이다. unlocked 단계는 현재까지 실제로 확인된 리소스만 위 DTO 채움 규칙대로 반환한다.

완료 전 단수 evidence 선택은 다음 우선순위로 고정한다. ① qualifying observation이 있는 유효 favorite → intention → buyTrade → exitPlan chain이 하나 이상이면 그 집합에서 `exitPlan.reservedAt ASC, exitPlan.id ASC` 첫 chain, ② 없으면 전체 유효 chain에서 같은 정렬의 첫 chain, ③ 유효 chain이 없으면 현재 favorite 중 `favorite.createdAt ASC, favorite.id ASC` 첫 행을 선택한다. chain을 선택하면 step 1~3은 반드시 그 chain의 favorite·intention·trade·plan만 사용하고 observation도 그 plan 안에서 고른다. immutable completion은 저장된 completion → reflection → plan 관계를 사용하므로 이 선택 규칙을 다시 적용하지 않는다.

| 정본 evidence | overall `status` | `currentStep` | step 1 | step 2 | step 3 | `completedAt` |
|---|---|---:|---|---|---|---|
| progress와 evidence 모두 없음 | `NOT_STARTED` | 1 | `NOT_STARTED`, unlocked, 빈 evidence | `NOT_STARTED`, locked, 빈 evidence | `NOT_STARTED`, locked, 빈 evidence | null |
| favorite만 존재 | `IN_PROGRESS` | 2 | `COMPLETED`, unlocked, favorite 쌍 | `IN_PROGRESS`, unlocked, favorite 쌍만 | `NOT_STARTED`, locked, 빈 evidence | null |
| 유효 favorite → intention → buyTrade → exitPlan chain 존재 | `IN_PROGRESS` | 3 | `COMPLETED`, unlocked, favorite 쌍 | `COMPLETED`, unlocked, chain 쌍 | `IN_PROGRESS`, unlocked, chain 쌍 | null |
| qualifying observation 존재, reflection 전 | `IN_PROGRESS` | 3 | `COMPLETED`, unlocked, favorite 쌍 | `COMPLETED`, unlocked, chain 쌍 | `IN_PROGRESS`, unlocked, chain 쌍+observation 삼쌍 | null |
| immutable completion 존재 | `COMPLETED` | null | `COMPLETED`, unlocked, completion에서 추적 가능한 evidence | `COMPLETED`, unlocked, completion에서 추적 가능한 evidence | `COMPLETED`, unlocked, reflection과 qualifying observation evidence | non-null completion 시각 |

완료 전 evidence가 삭제·종결되어 규칙을 더는 만족하지 않으면 earliest missing step을 `currentStep`과 unlocked `IN_PROGRESS` 단계로 삼고 이후 단계는 `NOT_STARTED`·locked로 돌린다. 이때 progress 행이 있거나 일부 evidence가 하나라도 있으면 overall은 `IN_PROGRESS`이고, progress와 evidence가 모두 없을 때만 `NOT_STARTED`다. completion 행이 있으면 현재 evidence 회귀를 무시하고 마지막 행처럼 영구 완료를 반환한다. 완료 뒤 삭제된 리소스의 DTO 필드는 null일 수 있지만 step status와 overall status는 회귀하지 않으며, 남아 있는 id·시각 및 observation 삼쌍에는 동일 nullable 규칙을 적용한다. 1단계는 favorite 존재만, 2단계는 그 favorite에 연결된 유효 intention → buyTrade → exitPlan chain으로 판정하며 GET 호출 이력은 사용하지 않는다.

## 입력 명세
| 요청 | 필드 | 검증 |
|---|---|---|
| Favorite | `instrumentId` | 필수, 거래 가능 종목, 본인 중복 등록 금지 |
| Intention | `instrumentId`, `quantity`, `stopLoss`, `takeProfit` | 필수·양수, 현재 존재하는 step 1 본인 favorite와 같은 instrument. 없거나 불일치하면 `PRACTICE_STEP_LOCKED` |
| Market order | 기존 `market`, `instrumentId`, `side=BUY`, `orderType=MARKET`, `quantity` | 기존 주문 계약 사용, 즉시 `FILLED` |
| Exit plan | `Idempotency-Key` header, 필수 `intentionId`, `buyTradeId`, `instrumentId`, `quantity`, `stopLoss`, `takeProfit` | tutorial-only, owner·instrument chain 동일, `intention.quantity == buyTrade.quantity == exitPlan.quantity`, holding은 owner·instrument와 `availableQuantity >= exitPlan.quantity`, 의도보다 체결이 나중, 가격 범위 유효, 의도당 plan 한 건 |
| Observation | `exitPlanId` | 본인 `PENDING` plan만 허용. 현재가·관찰유형·시각은 요청에서 받지 않고 서버가 결정 |
| Reflection | `exitPlanId`, `answer` | A·B·C 관찰 증거 중 하나 이후, terminal plan도 허용. answer는 `@NotBlank @Size(max=2000)`, whitespace-only 거부, raw 길이 최대 2000, 원문 저장 |

## 핵심 응답 계약
- `InvestmentPracticeResponse`: `status`, `currentStep`, 단계별 `status`, `locked`, `evidence`를 반환한다. 증거에는 실제 본인 favorite·의도·체결·OCO·관찰·복기 리소스 key와 생성·체결 시각만 포함하고 GET 호출 확인 시각이나 타 사용자 정보는 포함하지 않는다.
- `ExitPlanResponse`: `exitPlanId`, `intentionId`, `buyTradeId`, `replaySessionId`(주식만), `instrumentId`, `quantity`, `entryPrice`, `stopLoss`, `takeProfit`, `baselinePrice`, `baselineObservedAt`, `status`, `reservedAt`, `closedAt`, `triggeredOrderId`.
- `PracticeObservationResponse`: `observationId`, `exitPlanId`, 서버 `currentPrice`, `observedAt`, `closerToBoundary`, `closerBoundary`(`STOP_LOSS` 또는 `TAKE_PROFIT`, 해당 없으면 null), `evidenceType`(`CLOSER_TO_BOUNDARY`, `TIMED_REPETITION`, 아직 미충족이면 null). `FINAL_EVENT`는 클라이언트 POST 응답으로 생성되지 않는다.
- `PracticeReflectionResponse`: `reflectionId`, `exitPlanId`, 고정 `prompt`, `answer`, `createdAt`. 정답·점수·보상 필드는 없다.

## 상태와 오류
- Exit plan: `PENDING`, `FILLED_TAKE_PROFIT`, `FILLED_STOP_LOSS`, `CANCELLED`, `CANCELLED_EXPIRED`.
- 내부 조건 상태: 대기 중 두 조건, 종결 시 체결 조건 `TRIGGERED`, 반대쪽 `CANCELLED_BY_OCO`; 사용자 취소 시 둘 다 `CANCELLED`, 주식 세션 만료 시 둘 다 `CANCELLED_EXPIRED`. plan에는 `TRIGGERED` 중간상태를 두지 않는다.
- 주요 오류: `FAVORITE_NOT_FOUND` 404, `EXIT_PLAN_NOT_FOUND` 404, `PRACTICE_STEP_LOCKED` 409(intention 전 favorite 없음·종목 불일치), `PRACTICE_EVIDENCE_MISSING` 409(OCO·reflection의 owner·instrument chain, intention·trade·plan quantity snapshot, 저장된 holdingId 또는 A·B·C 누락·불일치), `PRACTICE_ALREADY_COMPLETED` 409, `EXIT_PLAN_INVALID_PRICE_RANGE` 409, `EXIT_PLAN_SESSION_CLOSED` 409, `EXIT_PLAN_NOT_PENDING` 409. 기존 `DUPLICATE_RESOURCE`·`INSUFFICIENT_QTY`·`PRICE_UNAVAILABLE`·`IDEMPOTENCY_CONFLICT`는 재사용한다.

## 데이터 모델
- `favorites`: 새 Flyway migration으로 `id BIGINT NOT NULL AUTO_INCREMENT`, `user_id BIGINT NOT NULL`, `instrument_id BIGINT NOT NULL`, `created_at DATETIME(6) NOT NULL`, PK `id`, `UNIQUE(user_id, instrument_id)`, 목록용 `INDEX(user_id, created_at, id)`, FK `user_id → users(id)`, `instrument_id → instruments(id)`를 만든다. 기존 migration처럼 `ON DELETE` 절을 쓰지 않아 MySQL `RESTRICT/NO ACTION`으로 회원·종목 삭제를 차단한다. V2의 `users.id BIGINT`, V7의 `instruments.id BIGINT`와 타입을 일치시키며 머지된 migration은 수정하지 않는다.
- `practice_intentions`: 사용자, 종목, `quantity DECIMAL(30,8)`, `stop_loss DECIMAL(18,8)`, `take_profit DECIMAL(18,8)`, 생성시각. 이후 매수·OCO와 연결.
- `practice_progresses`: 사용자, 고정 `tutorial_key`, `IN_PROGRESS`·`COMPLETED`, 최초 시작·완료시각, `UNIQUE(user_id, tutorial_key)`. 최초 intention 생성에서 atomic insert-or-existing으로 한 행을 확보한다.
- `trades.stock_replay_session_id`: nullable FK. 주식 fill은 당시 current replay session id를 저장하고 코인 fill은 null을 유지한다. 별도 migration·order fill 변경 이슈가 소유한다.
- `exit_plans`: 사용자, 사전 의도, 위 여섯 요청 필드의 SHA-256 `request_hash CHAR(64)`, 매수 체결, 주식 `replay_session_id`(코인은 null), holding, 종목, 예약수량, 진입가, 손절·익절, `baseline_price`, `baseline_observed_at`, 상태, 생성·종결시각, 트리거 매도 주문. `UNIQUE(user_id, intention_id)`이며 idempotency key 자체는 중복 저장하지 않는다.
- `exit_plan_idempotency_keys`: 후보 7 migration이 `id BIGINT NOT NULL AUTO_INCREMENT`, `user_id BIGINT NOT NULL`, `idempotency_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL`, `request_hash CHAR(64) NOT NULL`, `exit_plan_id BIGINT NOT NULL`, `created_at DATETIME(6) NOT NULL`, PK `id`, `UNIQUE(user_id, idempotency_key)`, FK `user_id → users(id)`, `exit_plan_id → exit_plans(id)`로 만든다. key는 항상 `UUID.toString()` lowercase canonical 값이라 DB 기본 collation의 대소문자 동등성에 의존하지 않는다. original key와 이후 동일 요청으로 관측된 새 key를 모두 저장한다. 기존 migration의 FK 정책처럼 `ON DELETE`를 쓰지 않아 `RESTRICT/NO ACTION`이며 plan이나 user 삭제로 key 기억이 유실되지 않게 한다.
- `exit_plan_conditions`: plan, `STOP_LOSS`·`TAKE_PROFIT`, `trigger_price DECIMAL(18,8)`, 상태. plan의 quantity는 `DECIMAL(30,8)`, entry/baseline/stop/take price는 `DECIMAL(18,8)`이며 수량 예약은 condition이 아니라 plan 한 건에만 둔다.
- `practice_observations`: 사용자, plan, 서버 `current_price DECIMAL(18,8)`, 가까워진 경계, `CLOSER_TO_BOUNDARY`·`TIMED_REPETITION`·`FINAL_EVENT` 증거 유형, 관찰시각. 모든 가격은 서버 값이다. 클라이언트 API는 PENDING plan의 앞 두 유형만 만들고 `FINAL_EVENT`는 서버 체결·만료 트랜잭션만 만든다.
- `practice_reflections`: 사용자, plan, 고정 prompt version, 원문 자유 답변 `answer VARCHAR(2000) NOT NULL`, 생성시각, `UNIQUE(user_id, exit_plan_id)`. 애플리케이션의 raw `String.length()` 최대 2000 검증과 같은 문자 수 경계를 사용하며 trim하지 않는다.
- `practice_completions`: 사용자, 고정 `tutorial_key`, 최초 완료시각, 완료 reflection, `UNIQUE(user_id, tutorial_key)`. progress의 완료 전이와 함께 생성되며 삭제·상태 회귀하지 않는 완료 정본이다.
- 공통 holding 예약 원장: OCO와 일반 지정가 SELL 예약을 합산해 `available_quantity = total_quantity - reserved_quantity`를 제공한다. 시장가 SELL도 같은 값을 검증한다.
- 물리 스키마는 ADR-0004에 따라 새 migration으로 추가하며 기존 migration을 수정하지 않는다.

## 트랜잭션과 경합
- OCO 생성 orchestration: education application이 필수 `intentionId`의 본인 favorite → intention chain을 검증해 owner·instrument, intention quantity, 라인·시각을 담은 검증 snapshot을 명시적 order application port에 전달한다. order port 구현은 education을 호출하지 않고 buyTrade·holding을 검증한다. exact equality는 snapshot의 intention quantity·buyTrade·exitPlan quantity에만 적용하고 holding은 owner·instrument와 `availableQuantity >= exitPlan.quantity`만 검증한다. 깨지면 `PRACTICE_EVIDENCE_MISSING`으로 전체 롤백한다.
- OCO 생성 orchestration 전체가 하나의 최상위 DB 트랜잭션 경계다. education application은 snapshot 검증에 필요한 favorite·intention 행을 잠근 채 order application port를 호출하고, port 구현은 새 트랜잭션을 분리하지 않고 같은 트랜잭션에 참여해 기존 순서대로 replay session(주식) → holding을 잠근 뒤 plan·condition 저장과 수량 예약까지 수행한다. 따라서 전체 잠금 순서는 `favorite → intention → replay session(주식) → holding → plan(신규)`이며, 검증 뒤 evidence가 삭제·변경되는 TOCTOU를 막고 어느 단계든 실패하면 education 검증부터 order 예약·저장까지 전부 롤백한다.
- OCO 생성 트랜잭션: 주식은 현재 OPEN replay session → holding 순서로 잠그고 `buyTrade.stockReplaySessionId` 일치와 15:30 전을 재검증한다. 코인은 holding만 잠근다. 서버 유효 현재가를 baseline으로 얻은 뒤 예약 가능 수량 검증, plan·두 condition 생성, holding 수량 예약을 한 트랜잭션으로 처리한다. 시세 없음은 409 `PRICE_UNAVAILABLE`로 전체 롤백한다.
- intention 생성: `(user_id, tutorial_key)` progress는 native `INSERT ... ON DUPLICATE KEY UPDATE id = id`처럼 기존 상태·시각을 바꾸지 않고 unique 예외도 내지 않는 MySQL 원자 upsert로 확보한다. 같은 트랜잭션에서 해당 progress를 `SELECT ... FOR UPDATE`로 재조회·잠근 뒤 대상 `(user_id,instrument_id)` favorite 행을 비관 잠금한다. 잠긴 favorite의 존재·종목을 검증하고 intention 저장까지 유지한다. 완료 progress는 upsert로 덮어쓰지 않고 잠금 재조회 뒤 409 `PRACTICE_ALREADY_COMPLETED`를 반환한다. favorite 없음·불일치는 `PRACTICE_STEP_LOCKED`이며 intention을 남기지 않는다. 동시 최초 요청은 예외나 rollback-only 전환 없이 progress 한 행에서 직렬화된다. 후보 3 DELETE도 같은 favorite 행을 잠근 뒤 삭제하므로 삭제 선행이면 intention은 409·무저장, intention 선행이면 201 커밋 뒤 DELETE가 204로 직렬화된다.
- 가격 트리거: 주식 replay session → holding → plan, 코인 holding → plan 순서로 잠그고 `PENDING` 한 건만 승자로 전이한다. 중복·역순 이벤트는 최초 커밋만 처리하고 terminal plan 후속 이벤트는 no-op/skip한다. 매도 체결·예약 소비·반대 condition 취소·final observation을 한 트랜잭션으로 처리한다.
- 트리거 평가 입력: 공통 가격 공급자가 거래 가능하다고 판정한 유효 가격 갱신 이벤트만 사용한다. 유효 이벤트 부재·가격 장애 중에는 아무 상태 전이 없이 `PENDING`을 유지한다.
- 사용자 취소: 주식 replay session → holding → plan, 코인 holding → plan 순서로 잠근다. `PENDING → CANCELLED`와 예약 반환이 함께 커밋되고 재요청·경합 패자는 409다.
- 주식 세션 만료: replay session → holding → plan 순서로 잠근다. 마지막 유효 가격 이벤트 처리 뒤 15:30에 남은 `PENDING` plan을 `CANCELLED_EXPIRED`로 바꾸고 두 condition 만료, 예약 수량 1회 반환, 마지막 유효 가격 final observation 저장을 한 트랜잭션으로 처리한다. 생성과 직렬화되어 만료 선행 시 생성 거부, 생성 선행 시 scan 포함이다. 코인은 이 경로가 없는 GTC다.
- 기존 시장가 SELL과 일반 지정가 SELL: holding 잠금에서 공통 예약 원장의 `availableQuantity`만 검증한다. 이 변경은 기존 `POST /api/orders` SELL service·contract·Controller 테스트와 실제 API 문서, 일반 지정가 SELL 구현을 함께 동기화하는 후속 이슈다.
- 클라이언트 관찰: 본인 plan을 잠그고 `PENDING`을 재검증한 뒤 서버 유효 현재가로 A·B 관찰만 저장한다. terminal 상태는 409이며 `FINAL_EVENT`는 이 경로에서 만들지 않는다.
- 복기 완료: `practice_progresses → favorite → practice_intention → exit plan` 순서로 비관 잠금한다. progress가 `COMPLETED`면 409다. 아니면 현재 favorite 존재·owner·instrument를 잠근 상태로 intention → trade → 저장된 holdingId → OCO의 owner·instrument, original intention·buyTrade·exitPlan quantity snapshot과 A·B·C 중 하나를 검증하고 reflection/completion/progress 완료 커밋까지 잠금을 유지한다. 현재 holding quantity는 terminal 체결·추가 매수로 달라질 수 있어 재검증하지 않는다. 누락·불일치는 `PRACTICE_EVIDENCE_MISSING`으로 전체 롤백한다. 후보 3 DELETE도 같은 favorite를 잠그므로 삭제 선행은 reflection 409와 reflection·completion·progress 완료 무저장, reflection 선행은 201 완료 커밋 뒤 DELETE 204이며 immutable completion은 삭제 후에도 유지된다.
- 잠금 순서에는 cycle이 없다. intention은 `progress → favorite`, reflection은 `progress → favorite → intention → plan`, OCO 생성은 `favorite → intention → replay session(주식) → holding → plan`, favorite DELETE는 `favorite`만 잠근다. 두 개 이상 공통 행을 잠그는 경로가 favorite/intention/plan을 역순으로 취득하지 않으며 reflection은 replay session·holding을 잠그지 않는다.
- 튜토리얼 판정: 완료 전에는 실제 evidence 현재 존재로 상태를 계산해 삭제·취소 시 재진행이 필요할 수 있다. completion 행 생성 뒤 overall은 불변 `COMPLETED`다. 교육 service는 존재·소유권·시각 순서·필드 일치를 재검증하며 클라이언트 완료 flag는 받지 않는다. favorite·OCO GET 호출 여부는 판정 입력이 아니다.
- 조회 API: favorite·OCO 목록과 practice 진행 GET은 DB write를 하지 않는다. 목록에 실제 리소스가 포함되는지는 응답 필드 API 테스트로 검증한다.

## 테스트 계획
- 단위: 3단계 증거 판정, baseline, PENDING 전용 A·B 관찰, 서버 전용 C, 불변 완료, 시장가 즉시 체결과 OCO 예약 구분, 가격 범위, 자유 복기 무판정.
- chain: favorite와 intention 종목 불일치, favorite 삭제 후 intention·OCO, owner·instrument 불일치, intention·trade·plan snapshot quantity 불일치를 각각 정의된 409와 무흔적으로 검증한다. holding total quantity가 snapshot과 다르지만 available capacity는 충분한 생성 성공, terminal 후 holding quantity 0인 복기 성공도 검증한다.
- 슬라이스: 즐겨찾기 3 API, 실습 조회·의도·관찰·복기, OCO 생성·목록·취소의 인증·소유권·검증·응답과 GET 무쓰기.
- 통합: 실제 favorite 등록/목록 → 의도 → 기존 시장가 FILLED → baseline 포함 OCO 생성/목록 → A·B·C별 복기 전체 흐름, terminal 관찰 POST 409, 서버 `FINAL_EVENT`, 완료 후 evidence 삭제·종결에도 완료 불변을 검증한다.
- 복기 경합: 같은 사용자의 같은 plan뿐 아니라 서로 다른 eligible intention·plan 동시 요청도 공통 progress 잠금에서 직렬화되어 한 요청만 reflection·completion 각 1행, progress 완료와 201을 만들고 다른 요청은 409이며 답변 원문이 추가 저장되지 않음을 DB로 검증한다.
- 경합: 중복·역순 가격 이벤트, 트리거 대 취소·주식 세션 만료·생성, OCO 예약분 포함 시장가·지정가 SELL에서 매도 1회 또는 반환 1회를 DB로 검증한다.
- evidence 경합: latch/barrier로 favorite 잠금 선점 순서를 고정해 삭제와 OCO 생성의 직렬화를 검증한다. 삭제 선행은 생성 409와 exit plan·condition·holding 예약 무저장을, 생성 선행은 생성 201 커밋 뒤 삭제 204와 생성된 plan·예약 유지를 검증한다.
- 만료: 주식은 마지막 유효 가격 처리 후 15:30 자동 `CANCELLED_EXPIRED`·예약 반환, 코인은 GTC, 가격 장애 중 `PENDING` 유지를 검증한다.

## 후속 구현 이슈 후보
각 후보는 API 하나 또는 원자적 트랜잭션 경계 하나만 소유한다. 번호는 권장 착수 순서일 뿐이며 실제 착수 가능 여부는 각 항목에 적은 선행 후보 DAG로 판단한다. 이슈는 미리 일괄 생성하지 않고 선행 gate를 만족해 실제 착수할 후보 하나만 생성한다. Controller를 실제 변경한 후보만 같은 커밋에서 `docs/api-routes.md`와 `docs/api-contracts.md`를 동기화한다.

실제 topological 착수 순서는 `1 → (2·3·4 병렬 가능)`, `5·6 독립`, `4+5+6 → 7`, `7 → (8·9·13 병렬 가능)`, `2+4+7+8 → 12`, `7+13 → 10`, `7+10 → 11`, `3+10+11+12+13 → 14`, `6 → 15`다. 한 건씩 이슈를 생성할 때도 이 topo와 gate를 사용한다.

1. **즐겨찾기 등록** — `POST /api/favorites`, favorites migration·유일 제약·등록 API. 선행 없음.
2. **즐겨찾기 목록** — `GET /api/favorites`, 순수 목록과 실제 리소스 포함 응답. 후보 1 선행.
3. **즐겨찾기 해제** — `DELETE /api/favorites/{instrumentId}`. 후보 1 선행.
4. **실습 의도 기록** — `POST /api/education/practice/intentions`, progress atomic insert-or-existing와 사전 계획. 후보 1 선행.
5. **주식 체결 세션 FK** — nullable `trades.stock_replay_session_id` migration과 주식 fill current session 기록·코인 null 유지. 기존 replay session·order fill 선행.
6. **공통 매도 예약 원장** — reservation ledger migration과 기존 `POST /api/orders` MARKET SELL의 `availableQuantity` service·contract·Controller 테스트·API 문서 동기화. 기존 holding·order 선행.
7. **튜토리얼 OCO 생성** — `POST /api/exit-plans`, education → order port 단방향 orchestration, OPEN session·baseline·1회 예약. 후보 4·5·6 선행.
8. **OCO 예약 목록** — `GET /api/exit-plans?status=`, 생략 시 `PENDING`인 순수 목록과 실제 plan 포함 응답. 후보 7 선행.
9. **OCO 사용자 취소** — `DELETE /api/exit-plans/{exitPlanId}`, 두 조건 종결·예약 1회 반환. 후보 7 선행.
10. **OCO 가격 트리거** — 유효 가격 이벤트, 시장가 매도·반대 조건 취소·final observation 원자 트랜잭션. 후보 7·13 선행.
11. **주식 OCO 세션 만료** — 15:30 자동 만료·두 조건 취소·예약 1회 반환·final observation. 후보 7·10 선행.
12. **실습 진행 조회** — `GET /api/education/practice`, 실제 증거·불변 완료 기반 순수 조회. 후보 2·4·7·8 선행.
13. **가격 관찰 기록** — `POST /api/education/practice/observations`, PENDING 전용 서버 현재가 A·B 관찰. 후보 7 선행.
14. **복기와 불변 완료** — `POST /api/education/practice/reflections`, progress → favorite → intention → plan 잠금, A·B·C 검증과 동시·재요청 무저장 409. favorite DELETE 경합 검증을 위해 후보 3과 후보 10·11·12·13 선행.
15. **일반 LIMIT SELL 예약 연동** — 일반 지정가 SELL의 공통 원장·`availableQuantity` 트랜잭션. 후보 6 선행이며 공통 ledger 배포 전 LIMIT SELL 활성화 금지.

### Migration·트랜잭션 소유권

| 후보 | 소유 schema/migration | 소유 원자 경계 |
|---|---|---|
| 1 | `favorites` | favorite 등록과 unique 충돌 매핑 |
| 2 | 없음 | read-only 목록 |
| 3 | 없음 | 대상 favorite 비관 잠금 + 본인 favorite 단건 삭제 |
| 4 | `practice_intentions`, `practice_progresses` | progress 무변경 MySQL upsert + 잠금 재조회 + favorite 비관 잠금·검증 + intention 저장 |
| 5 | nullable `trades.stock_replay_session_id` FK | 주식 fill과 current session 기록; 코인 null |
| 6 | 공통 reservation ledger | MARKET SELL available 검증·체결/ledger 정합성 |
| 7 | `exit_plans`, `exit_plan_conditions`, canonical UUID `exit_plan_idempotency_keys`와 intention/key unique | 각 attempt의 plan·key mapping·예약 전체 원자성; 실패 tx 전체 rollback 후 바깥 coordinator의 새 tx reconciliation |
| 8 | 없음 | read-only PENDING 목록 |
| 9 | 없음 | PENDING 취소 + 두 condition 종결 + 예약 1회 반환 |
| 10 | 서버 `FINAL_EVENT`를 저장할 후보 13의 `practice_observations` schema 사용 | 가격 trigger + 시장가 SELL + 예약 소비 + 반대 condition 취소 + `FINAL_EVENT` |
| 11 | 없음 | 주식 expiry + condition 만료 + 예약 반환 + `FINAL_EVENT` |
| 12 | 없음 | read-only evidence/progress 계산 |
| 13 | `practice_observations` | PENDING 재검증 + 서버 가격 A·B 관찰 저장 |
| 14 | `practice_reflections`, `practice_completions`; 후보 4의 progress와 후보 1의 favorite 사용 | progress → favorite → intention → plan 잠금 유지 + evidence 검증 + reflection/completion/progress 완료 |
| 15 | 후보 6 ledger 재사용, 새 교육 schema 없음 | LIMIT SELL 예약·available 검증 |

후보 10이 후보 13 schema에 `FINAL_EVENT`를 저장하므로 후보 10의 실제 선행 후보는 7·13이고, 후보 11의 실제 선행 후보는 7·10이다. 이 DAG가 위 권장 번호보다 우선한다.

### 후보별 테스트 책임

`필수`는 해당 후보 PR의 완료 gate, `해당 없음`은 그 레벨을 다른 후보에 전가한다는 뜻이 아니다. 외부 실행 API QA는 구현 PR의 review 단계에서 수행한다.

| 후보 | 단위 | WebMvc | DataJpa/MySQL | 통합·경합 책임 |
|---|---|---|---|---|
| 1 | 등록·중복·tradable 필수 | 201/400/404/409·auth | DDL, FK, unique, 최신순 index | unique 동시 등록 1행 |
| 2 | 정렬·빈 목록 필수 | 응답 필드·auth 필수 | 정렬 query 필수 | GET 무쓰기 |
| 3 | 소유권 은닉·없음 필수 | 204/404·auth 필수 | owner 조회·비관 잠금 후 delete 필수 | 등록 후 삭제와 후보 4 intention 생성 경합 |
| 4 | step lock·progress 수렴 필수 | 201/404/409·validation 필수 | progress/intention FK·unique, 무변경 upsert와 progress/favorite lock query 필수 | 동시 intention은 unique 예외·rollback-only 없이 progress 1행; 완료 상태 무덮어쓰기; favorite DELETE 선행→409·무저장, intention 선행→201 후 DELETE 204 |
| 5 | session 선택 규칙 필수 | 해당 없음 | FK·주식 non-null/코인 null 필수 | 실제 주식/코인 fill 기록 |
| 6 | available 계산 필수 | 기존 orders SELL 400/409 회귀 필수 | ledger 정합성 필수 | MARKET SELL 대 예약 경합 |
| 7 | chain·가격·fingerprint·UUID canonical·비트랜잭션 coordinator 분기 필수 | 201/200/400/404/409·header 필수 | plan/condition/key mapping FK·ascii_bin alias unique·실패 attempt 부분 저장 0 필수 | 별도 proxy/TransactionTemplate의 새 tx 확인; unique loser rollback 후 alias+200/409; alias 재경합 final read |
| 8 | status 기본값·filter·정렬 필수 | query 생략/PENDING 200, 그 외 400·auth·필드 필수 | PENDING query 필수 | GET 무쓰기 |
| 9 | terminal/소유권 필수 | 204/404/409 필수 | 상태 전이·예약 반환 필수 | 취소 대 trigger/expiry |
| 10 | 경계 판정·중복 이벤트 필수 | 해당 없음 | FINAL_EVENT·매도/예약 소비 필수 | 중복·역순 trigger 최초 승자 |
| 11 | STOCK expiry·CRYPTO GTC 필수 | 해당 없음 | 만료·반환·FINAL_EVENT 필수 | 15:30 trigger/생성 경합 |
| 12 | 단계·nullable evidence 계산 필수 | 200·auth·DTO 필수 | evidence 조회 query 필수 | 완료 전 회귀/완료 후 불변·GET 무쓰기 |
| 13 | A/B·PENDING·서버 가격 필수 | 201/404/409 필수 | observation 저장·시간 범위 필수 | 관찰 대 terminal 경합 |
| 14 | A/B/C·무판정·완료 불변 필수 | 201/404/409·answer 필수 | reflection/completion unique와 favorite lock query 필수 | 전체 흐름·서로 다른 plan 동시 완료; latch/barrier로 DELETE 선행→409·reflection/completion/progress 완료 저장 0, reflection 선행→201 뒤 DELETE 204·완료 불변 |
| 15 | LIMIT available 계산 필수 | 해당 LIMIT Controller 계약 필수 | ledger 예약 정합성 필수 | LIMIT 예약 대 MARKET/OCO 매도 경합 |

10개 신규 API의 계획 계약은 #158에서 전역 API 문서의 별도 계획 절에 등록한다. 각 Controller 이슈에서 실제 매핑을 만든 뒤 해당 행·계약을 실제 제공 상태로 전환한다. 기존 `POST /api/orders` SELL은 OCO 예약분을 지키도록 service·계약·Controller 테스트·API 문서를 함께 바꾸는 명시적 변경 후보며 BUY 계약은 유지한다.

## 배포 의존성
- 후보 5와 후보 6을 후보 7보다 먼저 배포하거나 세 후보를 하나의 atomic release로 배포한다. prerequisite가 운영 DB·애플리케이션에 모두 적용되기 전 `/api/exit-plans`를 활성화하지 않는다.
- 후보 15의 일반 LIMIT SELL도 후보 6의 공통 ledger가 배포된 뒤에만 활성화한다.

## 확정 경계와 잔여 위험
- 공통 reservation ledger의 물리 모델은 후보 6이 소유하며 후보 7·15의 선행 gate다. 이 문서는 `availableQuantity = totalQuantity - reservedQuantity`, 단일 예약·소비·반환 원자성까지만 정본으로 고정하고 별도 구조를 선행 발명하지 않는다.
- 현재 저장소에 이미 있는 `DUPLICATE_RESOURCE`, `INSUFFICIENT_QTY`, `PRICE_UNAVAILABLE`, `IDEMPOTENCY_CONFLICT`는 그대로 재사용한다. 나머지 신규 오류 코드만 각 최초 사용 후보가 `ErrorCode`와 예외 매핑 테스트를 함께 추가한다. 이는 계약 미확정이 아니라 구현 작업이다.
