# Plan: 3단계 투자 실습 튜토리얼

## 관련 문서
- Spec: `./spec.md`
- PRD: `../../prd.md` — C-001, C-003, C-004, 2차·3차 MVP 범위
- 관련 ADR: ADR-0002, ADR-0003, ADR-0004
- 기존 시장가 주문: `POST /api/orders`
- Spec 번호: 011은 order-ledger가 점유하고 013은 원격 `feat/143-candle-interval`에서 사용하며 014 ranking·015 limit가 예약되어 있어 016을 사용한다.

## 착수 제한
- 현재 #152/PR #153 범위는 spec·PRD 확정뿐이다. production, Controller, migration, `docs/api-routes.md`, `docs/api-contracts.md`를 변경하지 않는다.
- 아래 API는 후속 구현 이슈의 계약이며 구현 전에는 실제 사용 가능하다고 문서화하지 않는다.

## 도메인 경계
- `favorite`: 사용자별 관심 종목 등록·목록·해제.
- `education`: 사용자·튜토리얼 공통 progress, 3단계 의도·관찰·복기 기록과 실제 도메인 증거를 읽어 계산한 진행 상태. favorite·intention chain 검증 service 계약을 제공한다.
- `order`: 기존 시장가 즉시 체결과 tutorial-only OCO exit plan의 예약·트리거·취소. `order`가 education repository에 직접 의존하지 않고 education service의 소유권·instrument·quantity·시각 검증 계약을 호출하는 orchestration boundary를 둔다.
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
| GET | `/api/exit-plans?status=PENDING` | 없음 | `ExitPlanListResponse` | 본인 예약 목록 순수 조회. 확인 시각 등 write 없음 |
| DELETE | `/api/exit-plans/{exitPlanId}` | 없음 | 없음 | PENDING OCO 전체 취소와 예약 1회 반환 |
| POST | `/api/education/practice/observations` | `PracticeObservationCreateRequest` | `PracticeObservationResponse` | 서버 현재가로 라인 접근 관찰 기록 |
| POST | `/api/education/practice/reflections` | `PracticeReflectionCreateRequest` | `PracticeReflectionResponse` | 정답 없는 3단계 자유 복기 저장 |

## 입력 명세
| 요청 | 필드 | 검증 |
|---|---|---|
| Favorite | `instrumentId` | 필수, 거래 가능 종목, 본인 중복 등록 금지 |
| Intention | `instrumentId`, `quantity`, `stopLoss`, `takeProfit` | 필수·양수, 현재 존재하는 step 1 본인 favorite와 같은 instrument. 없거나 불일치하면 `PRACTICE_STEP_LOCKED` |
| Market order | 기존 `market`, `instrumentId`, `side=BUY`, `orderType=MARKET`, `quantity` | 기존 주문 계약 사용, 즉시 `FILLED` |
| Exit plan | `Idempotency-Key` header, 필수 `intentionId`, `buyTradeId`, `instrumentId`, `quantity`, `stopLoss`, `takeProfit` | tutorial-only, owner·instrument chain 동일, `intention.quantity == buyTrade.quantity == exitPlan.quantity`, holding은 owner·instrument와 `availableQuantity >= exitPlan.quantity`, 의도보다 체결이 나중, 가격 범위 유효, 의도당 plan 한 건 |
| Observation | `exitPlanId` | 본인 `PENDING` plan만 허용. 현재가·관찰유형·시각은 요청에서 받지 않고 서버가 결정 |
| Reflection | `exitPlanId`, `answer` | A·B·C 관찰 증거 중 하나 이후, terminal plan도 허용, 공백 제외 1~2000자 |

## 핵심 응답 계약
- `InvestmentPracticeResponse`: `status`, `currentStep`, 단계별 `status`, `locked`, `evidence`를 반환한다. 증거에는 실제 본인 favorite·의도·체결·OCO·관찰·복기 리소스 key와 생성·체결 시각만 포함하고 GET 호출 확인 시각이나 타 사용자 정보는 포함하지 않는다.
- `ExitPlanResponse`: `exitPlanId`, `buyTradeId`, `replaySessionId`(주식만), `instrumentId`, `quantity`, `entryPrice`, `stopLoss`, `takeProfit`, `baselinePrice`, `baselineObservedAt`, `status`, `reservedAt`, `closedAt`, `triggeredOrderId`.
- `PracticeObservationResponse`: `observationId`, `exitPlanId`, 서버 `currentPrice`, `observedAt`, `closerToBoundary`, `closerBoundary`(`STOP_LOSS` 또는 `TAKE_PROFIT`, 해당 없으면 null), `evidenceType`(`CLOSER_TO_BOUNDARY`, `TIMED_REPETITION`, 아직 미충족이면 null). `FINAL_EVENT`는 클라이언트 POST 응답으로 생성되지 않는다.
- `PracticeReflectionResponse`: `reflectionId`, `exitPlanId`, 고정 `prompt`, `answer`, `createdAt`. 정답·점수·보상 필드는 없다.

## 상태와 오류
- Exit plan: `PENDING`, `FILLED_TAKE_PROFIT`, `FILLED_STOP_LOSS`, `CANCELLED`, `CANCELLED_EXPIRED`.
- 내부 조건 상태: 대기 중 두 조건, 종결 시 체결 조건 `TRIGGERED`, 반대쪽 `CANCELLED_BY_OCO`; 사용자 취소 시 둘 다 `CANCELLED`, 주식 세션 만료 시 둘 다 `CANCELLED_EXPIRED`. plan에는 `TRIGGERED` 중간상태를 두지 않는다.
- 주요 오류: `FAVORITE_NOT_FOUND` 404, `EXIT_PLAN_NOT_FOUND` 404, `PRACTICE_STEP_LOCKED` 409(intention 전 favorite 없음·종목 불일치), `PRACTICE_EVIDENCE_MISSING` 409(OCO·reflection의 owner·instrument chain, intention·trade·plan quantity snapshot, 저장된 holdingId 또는 A·B·C 누락·불일치), `PRACTICE_ALREADY_COMPLETED` 409, `EXIT_PLAN_INVALID_PRICE_RANGE` 409, `EXIT_PLAN_SESSION_CLOSED` 409, `EXIT_PLAN_NOT_PENDING` 409, 기존 `INSUFFICIENT_QTY`·`PRICE_UNAVAILABLE` 재사용.

## 데이터 모델
- `favorites`: `user_id`, `instrument_id`, `created_at`, `UNIQUE(user_id, instrument_id)`.
- `practice_intentions`: 사용자, 종목, 수량, 손절·익절, 생성시각. 이후 매수·OCO와 연결.
- `practice_progresses`: 사용자, 고정 `tutorial_key`, `IN_PROGRESS`·`COMPLETED`, 최초 시작·완료시각, `UNIQUE(user_id, tutorial_key)`. 최초 intention 생성에서 atomic insert-or-existing으로 한 행을 확보한다.
- `trades.stock_replay_session_id`: nullable FK. 주식 fill은 당시 current replay session id를 저장하고 코인 fill은 null을 유지한다. 별도 migration·order fill 변경 이슈가 소유한다.
- `exit_plans`: 사용자, 사전 의도, 멱등키, 매수 체결, 주식 `replay_session_id`(코인은 null), holding, 종목, 예약수량, 진입가, 손절·익절, `baseline_price`, `baseline_observed_at`, 상태, 생성·종결시각, 트리거 매도 주문. `(user_id, intention_id)`와 `(user_id, idempotency_key)`는 유일하다.
- `exit_plan_conditions`: plan, `STOP_LOSS`·`TAKE_PROFIT`, trigger price, 상태. 수량 예약은 condition이 아니라 plan 한 건에만 둔다.
- `practice_observations`: 사용자, plan, 서버 현재가, 가까워진 경계, `CLOSER_TO_BOUNDARY`·`TIMED_REPETITION`·`FINAL_EVENT` 증거 유형, 관찰시각. 모든 가격은 서버 값이다. 클라이언트 API는 PENDING plan의 앞 두 유형만 만들고 `FINAL_EVENT`는 서버 체결·만료 트랜잭션만 만든다.
- `practice_reflections`: 사용자, plan, 고정 prompt version, 자유 답변, 생성시각, `UNIQUE(user_id, exit_plan_id)`.
- `practice_completions`: 사용자, 고정 `tutorial_key`, 최초 완료시각, 완료 reflection, `UNIQUE(user_id, tutorial_key)`. progress의 완료 전이와 함께 생성되며 삭제·상태 회귀하지 않는 완료 정본이다.
- 공통 holding 예약 원장: OCO와 일반 지정가 SELL 예약을 합산해 `available_quantity = total_quantity - reserved_quantity`를 제공한다. 시장가 SELL도 같은 값을 검증한다.
- 물리 스키마는 ADR-0004에 따라 새 migration으로 추가하며 기존 migration을 수정하지 않는다.

## 트랜잭션과 경합
- OCO 생성 orchestration: education service가 필수 `intentionId`의 본인 favorite → intention chain을 검증해 owner·instrument, intention quantity snapshot, 라인·시각 계약을 order service에 전달한다. order는 education repository를 직접 조회하지 않는다. order service는 buyTrade·holding을 검증한다. exact equality는 intention·buyTrade·exitPlan quantity에만 적용하고 holding은 owner·instrument와 `availableQuantity >= exitPlan.quantity`만 검증한다. 깨지면 `PRACTICE_EVIDENCE_MISSING`으로 전체 롤백한다.
- OCO 생성 트랜잭션: 주식은 현재 OPEN replay session → holding 순서로 잠그고 `buyTrade.stockReplaySessionId` 일치와 15:30 전을 재검증한다. 코인은 holding만 잠근다. 서버 유효 현재가를 baseline으로 얻은 뒤 예약 가능 수량 검증, plan·두 condition 생성, holding 수량 예약을 한 트랜잭션으로 처리한다. 시세 없음은 409 `PRICE_UNAVAILABLE`로 전체 롤백한다.
- intention 생성: `(user_id, tutorial_key)` progress를 atomic insert-or-existing으로 확보한 뒤 같은 사용자의 favorite 존재와 `instrumentId` 일치를 검증하고 intention을 저장한다. favorite 없음·불일치는 `PRACTICE_STEP_LOCKED`이며 intention을 남기지 않는다. concurrent insert unique 충돌은 기존 progress를 재조회해 `IN_PROGRESS` 단일 행으로 수렴시키며 완료 progress에는 새 intention을 만들지 않고 409를 반환한다.
- 가격 트리거: 주식 replay session → holding → plan, 코인 holding → plan 순서로 잠그고 `PENDING` 한 건만 승자로 전이한다. 중복·역순 이벤트는 최초 커밋만 처리하고 terminal plan 후속 이벤트는 no-op/skip한다. 매도 체결·예약 소비·반대 condition 취소·final observation을 한 트랜잭션으로 처리한다.
- 트리거 평가 입력: 공통 가격 공급자가 거래 가능하다고 판정한 유효 가격 갱신 이벤트만 사용한다. 유효 이벤트 부재·가격 장애 중에는 아무 상태 전이 없이 `PENDING`을 유지한다.
- 사용자 취소: 주식 replay session → holding → plan, 코인 holding → plan 순서로 잠근다. `PENDING → CANCELLED`와 예약 반환이 함께 커밋되고 재요청·경합 패자는 409다.
- 주식 세션 만료: replay session → holding → plan 순서로 잠근다. 마지막 유효 가격 이벤트 처리 뒤 15:30에 남은 `PENDING` plan을 `CANCELLED_EXPIRED`로 바꾸고 두 condition 만료, 예약 수량 1회 반환, 마지막 유효 가격 final observation 저장을 한 트랜잭션으로 처리한다. 생성과 직렬화되어 만료 선행 시 생성 거부, 생성 선행 시 scan 포함이다. 코인은 이 경로가 없는 GTC다.
- 기존 시장가 SELL과 일반 지정가 SELL: holding 잠금에서 공통 예약 원장의 `availableQuantity`만 검증한다. 이 변경은 기존 `POST /api/orders` SELL service·contract·Controller 테스트와 실제 API 문서, 일반 지정가 SELL 구현을 함께 동기화하는 후속 이슈다.
- 클라이언트 관찰: 본인 plan을 잠그고 `PENDING`을 재검증한 뒤 서버 유효 현재가로 A·B 관찰만 저장한다. terminal 상태는 409이며 `FINAL_EVENT`는 이 경로에서 만들지 않는다.
- 복기 완료: `practice_progresses → practice_intention → exit plan` 순서로 비관 잠금한다. progress가 `COMPLETED`면 409다. 아니면 favorite → intention → trade → 저장된 holdingId → OCO의 owner·instrument, original intention·buyTrade·exitPlan quantity snapshot과 A·B·C 중 하나를 검증한다. 현재 holding quantity는 terminal 체결·추가 매수로 달라질 수 있어 재검증하지 않는다. 누락·불일치는 `PRACTICE_EVIDENCE_MISSING`으로 전체 롤백한다. 성공 시 reflection 1행, completion 1행, progress `COMPLETED` 전이를 한 트랜잭션에서 저장해 201을 반환한다.
- 튜토리얼 판정: 완료 전에는 실제 evidence 현재 존재로 상태를 계산해 삭제·취소 시 재진행이 필요할 수 있다. completion 행 생성 뒤 overall은 불변 `COMPLETED`다. 교육 service는 존재·소유권·시각 순서·필드 일치를 재검증하며 클라이언트 완료 flag는 받지 않는다. favorite·OCO GET 호출 여부는 판정 입력이 아니다.
- 조회 API: favorite·OCO 목록과 practice 진행 GET은 DB write를 하지 않는다. 목록에 실제 리소스가 포함되는지는 응답 필드 API 테스트로 검증한다.

## 테스트 계획
- 단위: 3단계 증거 판정, baseline, PENDING 전용 A·B 관찰, 서버 전용 C, 불변 완료, 시장가 즉시 체결과 OCO 예약 구분, 가격 범위, 자유 복기 무판정.
- chain: favorite와 intention 종목 불일치, favorite 삭제 후 intention·OCO, owner·instrument 불일치, intention·trade·plan snapshot quantity 불일치를 각각 정의된 409와 무흔적으로 검증한다. holding total quantity가 snapshot과 다르지만 available capacity는 충분한 생성 성공, terminal 후 holding quantity 0인 복기 성공도 검증한다.
- 슬라이스: 즐겨찾기 3 API, 실습 조회·의도·관찰·복기, OCO 생성·목록·취소의 인증·소유권·검증·응답과 GET 무쓰기.
- 통합: 실제 favorite 등록/목록 → 의도 → 기존 시장가 FILLED → baseline 포함 OCO 생성/목록 → A·B·C별 복기 전체 흐름, terminal 관찰 POST 409, 서버 `FINAL_EVENT`, 완료 후 evidence 삭제·종결에도 완료 불변을 검증한다.
- 복기 경합: 같은 사용자의 같은 plan뿐 아니라 서로 다른 eligible intention·plan 동시 요청도 공통 progress 잠금에서 직렬화되어 한 요청만 reflection·completion 각 1행, progress 완료와 201을 만들고 다른 요청은 409이며 답변 원문이 추가 저장되지 않음을 DB로 검증한다.
- 경합: 중복·역순 가격 이벤트, 트리거 대 취소·주식 세션 만료·생성, OCO 예약분 포함 시장가·지정가 SELL에서 매도 1회 또는 반환 1회를 DB로 검증한다.
- 만료: 주식은 마지막 유효 가격 처리 후 15:30 자동 `CANCELLED_EXPIRED`·예약 반환, 코인은 GTC, 가격 장애 중 `PENDING` 유지를 검증한다.

## 후속 구현 이슈 후보
각 후보는 API 하나 또는 원자적 트랜잭션 경계 하나만 소유한다. 서로 다른 후보를 한 이슈로 합치지 않는다.

1. `POST /api/favorites` 등록 API와 유일 제약.
2. `GET /api/favorites` 순수 목록 API와 실제 리소스 포함 응답 검증.
3. `DELETE /api/favorites/{instrumentId}` 해제 API.
4. `POST /api/education/practice/intentions` 공통 progress atomic insert-or-existing과 사전 계획 기록 API.
5. `POST /api/exit-plans` tutorial-only orchestration, OPEN replay session·baseline 시세 검증과 보유수량 1회 예약 트랜잭션. 후보 10·15 완료 전 endpoint 활성화 금지.
6. `GET /api/exit-plans?status=PENDING` 순수 예약 목록 API와 실제 plan 포함 응답 검증.
7. `DELETE /api/exit-plans/{exitPlanId}` 취소·반대 조건 종결·예약 1회 반환 트랜잭션.
8. 유효 가격 이벤트 OCO 트리거·시장가 매도·반대 조건 취소·final observation 트랜잭션.
9. 주식 replay session 15:30 OCO 자동 만료·두 조건 취소·예약 1회 반환 트랜잭션.
10. 공통 reservation ledger migration과 기존 `POST /api/orders` 시장가 SELL의 `availableQuantity` service·contract·Controller 테스트·API 문서 동기화.
11. 일반 지정가 SELL의 공통 예약 원장·`availableQuantity` 검증 트랜잭션. 후보 10의 공통 ledger 배포 전 LIMIT SELL 활성화 금지.
12. `GET /api/education/practice` 실제 증거·불변 완료 기반 순수 진행 조회 API.
13. `POST /api/education/practice/observations` PENDING plan 전용 서버 현재가 A·B 관찰 API.
14. `POST /api/education/practice/reflections` progress → intention → plan 잠금, A·B·C 검증, 최초 복기·불변 완료 저장과 서로 다른 plan 동시·재요청 409 트랜잭션.
15. nullable `trades.stock_replay_session_id` FK migration과 주식 fill current session 기록·코인 null 유지 변경.

각 Controller 이슈에서 실제 매핑을 만든 뒤에만 API 문서를 동기화한다. 기존 `POST /api/orders` SELL은 OCO 예약분을 지키도록 service·계약·Controller 테스트·API 문서를 함께 바꾸는 명시적 변경 후보며 BUY 계약은 유지한다.

## 배포 의존성
- 후보 10은 공통 reservation ledger migration과 기존 MARKET SELL `availableQuantity` service·contract·Controller 테스트·API 문서 변경을 함께 소유한다.
- 후보 10과 후보 15를 후보 5보다 먼저 배포하거나 세 후보를 하나의 atomic release로 배포한다. prerequisite가 운영 DB·애플리케이션에 모두 적용되기 전 `/api/exit-plans`를 활성화하지 않는다.
- 후보 11의 일반 LIMIT SELL도 후보 10의 공통 ledger가 배포된 뒤에만 활성화한다.

## 미확정 구현 사항
- 기존 지정가 주문 예약과 공유할 holding 예약 필드·상태 enum의 물리 설계는 migration 작성 전에 확정한다.
