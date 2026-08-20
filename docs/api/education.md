# API 계약 — education

"016 투자 실습"·"026 시장가/지정가 매매 기반 투자 실습" 절의 API 계약 상세다. 전체 라우트를 한눈에 보는 지도는 `ai/api-routes.md`에 있다.

**controller를 추가/변경하면 `ai/api-routes.md`의 라우트 목록과 이 문서를 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

블랙박스 QA는 구현 코드(`src/main`)를 읽지 않고 이 문서와 spec만을 계약 근거로 사용한다 (`ai/context-router.md`).

일반 리스크관리 OCO(`intentionId` 생략, production)의 생성·취소·목록 조회는 이 문서가 아니라 `docs/api/order.md`의 "021 일반 리스크관리 OCO" 절이 정본이다 — 아래 016 절은 `intentionId`를 지정하는 **교육 경로**(아직 미구현) 계약이다.

---

## 016 투자 실습 (candidate 1~4·합성 시세 제공, OCO 계열 계획)

`ai/specs/016-investment-education-policy`의 계약은 제공 5건과 계획 6건, 총 11건이다. **`POST`·`DELETE /api/exit-plans`는 Issue #348로 production에 추가됐지만 이 절이 아니라 `docs/api/order.md`의 "021 일반 리스크관리 OCO" 절이 정본이다** — 이번에 구현된 것은 `intentionId`를 생략하는 **일반 경로**뿐이고, 이 절이 다루는 `intentionId` 지정 **교육 경로**는 여전히 컨트롤러가 이를 거부한다(400 `VALIDATION_ERROR`). 아래 "OCO exit plan 생성 (계획)"·"OCO 예약 취소" 두 절의 본문은 교육 경로 계약이므로 QA 실행 근거로 쓰지 않는다.

**제공 중(블랙박스 QA 실행 가능)** — 5건: candidate 1 `POST /api/favorites`, candidate 2 `GET /api/favorites`, candidate 3 `DELETE /api/favorites/{instrumentId}`, candidate 4 `POST /api/education/practice/intentions`, 튜토리얼 합성 시세 `GET /api/education/practice/synthetic-prices/{instrumentId}`. 아래 각 절에 "(계획)" 표시가 없는 것이 이에 해당한다.

**계획 상태(QA 실행 근거로 쓰지 않는다)** — 6건: `POST /api/education/practice/oco/intentions`(candidate 4 확장), `GET /api/education/practice/oco`(candidate 12), `GET /api/exit-plans?status=`(candidate 8), 교육 경로(`intentionId` 지정) `POST`·`DELETE /api/exit-plans`(7·9, 라우트 자체는 존재하나 이 경로는 항상 거부됨), `POST /api/education/practice/observations`(13), `POST /api/education/practice/reflections`(14). 전부 3차 MVP OCO 경로이며 절 제목에 "(계획)"으로 표시한다. `EXIT_PLAN_ALREADY_EXISTS`·`EXIT_PLAN_INVALID_PRICE_RANGE`·`EXIT_PLAN_NOT_FOUND`·`EXIT_PLAN_NOT_PENDING`은 Issue #348로 이미 `ErrorCode` enum에 있다(일반 경로 구현으로 추가됨). 이 절이 참조하는 나머지 `EXIT_PLAN_SESSION_CLOSED` 등 교육 경로 전용 오류 코드는 아직 없다.

> `GET /api/education/practice/oco`는 3차 MVP OCO 전용 진행조회다. 2차 holding 기반 `GET /api/education/practice`와 URL·완료 key를 공유하지 않는다(2026-08-10 확정, 이슈 #308).
>
> 3단계 실습을 **지금 QA한다면 이 절이 아니라 아래 `026` 절**을 근거로 삼는다 — 2차 MVP에서 실제로 완료 가능한 경로는 그쪽이다. 일반 리스크관리 OCO 생성·취소(`intentionId` 생략)를 QA한다면 `docs/api/order.md`의 "021 일반 리스크관리 OCO" 절을 근거로 삼는다.

각 후속 구현이 병합될 때 해당 계약을 실제 상태로 전환하고 `ai/api-routes.md`의 계획 행도 실제 라우트 목록으로 옮긴다. 모든 경로는 Access Bearer 인증과 공통 오류 body를 사용하며 JSON POST는 `Content-Type: application/json`이다.

수량은 양수 `DECIMAL(30,8)` 범위(정수부 최대 22자리·소수부 최대 8자리), 가격은 양수 `DECIMAL(18,8)` 범위(정수부 최대 10자리·소수부 최대 8자리)다. 초과 precision/scale은 반올림하지 않고 400 `VALIDATION_ERROR`로 거부한다. 모든 id는 양의 `Long`이다.

**즐겨찾기·사전 의도의 저장 방식 (정본: `ai/adr/0012-tutorial-state-in-memory.md`)** — 이 절 전체에 적용되므로 각 엔드포인트에서 반복하지 않는다. `#193`부터 `favorites`(V14)·`practice_intentions`(V16) 테이블은 DROP됐고 서버 힙 메모리(인스턴스 단위 `ConcurrentHashMap`)에 저장한다. 클라이언트가 관측하는 결과는 셋이다 — ① **서버 재시작·재배포 시 등록된 즐겨찾기와 사전 의도가 모두 사라진다**(재등록 필요, 삭제 요청은 404가 된다), ② `favoriteId`·`intentionId`는 프로세스 기동마다 1부터 재채번되므로 서로 다른 시점의 같은 id가 다른 리소스일 수 있다, ③ 다중 인스턴스에서 sticky session이 없으면 인스턴스마다 다른 상태가 보인다. `practice_progresses`·`practice_completions`(완료 판정)는 DB에 남아 이 유실의 영향을 받지 않는다.

### 즐겨찾기 등록

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/favorites | `{"instrumentId":1}` (`FavoriteCreateRequest`) | 201 `{"favoriteId":1,"instrumentId":1,"market":"STOCK","symbol":"005930","name":"삼성전자","createdAt":"2026-08-03T10:00:00"}` (`FavoriteResponse`) | 400 `VALIDATION_ERROR`; 404 `NOT_FOUND`(종목); 409 `INSTRUMENT_NOT_TRADABLE`, `DUPLICATE_RESOURCE` | 016 candidate 1 |

같은 사용자의 `(userId, instrumentId)`는 유일하다. 중복 등록은 기존 값을 반환하지 않는다. 저장 방식과 재시작 유실은 이 절 도입부 참고.

### 즐겨찾기 목록 조회

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/favorites | 추가 입력 없음 | 200 `{"content":[FavoriteResponse...]}`; 없으면 빈 배열 | 인증 공통 오류 | 016 candidate 2 |

`createdAt DESC, favoriteId DESC` 순이며 페이지네이션과 write가 없다.

### 즐겨찾기 해제

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| DELETE | /api/favorites/{instrumentId} | 양의 `instrumentId` path | 204, 본문 없음 | 400 `VALIDATION_ERROR`; 404 `FAVORITE_NOT_FOUND` | 016 candidate 3 |

타인 소유 행은 존재를 숨겨 404로 처리하며 반복 삭제도 404다. 재시작 후에는 삭제 대상 자체가 사라져 있어 항상 404다(도입부 ① 참고).

### 투자 실습 진행 조회 (계획 — 3차 MVP OCO 버전)

> 이 계약은 3차 MVP OCO 전용 정본이다. `exitPlanId`·`FINAL_EVENT` evidence와 OCO 전용 완료 key를 사용하며 아래 `026`의 holding 기반 계약과 독립적으로 공존한다.

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/education/practice/oco?market= | `market` 필수(`STOCK|CRYPTO`) | 200 `InvestmentPracticeResponse` | 400 `VALIDATION_ERROR`; 인증 공통 오류 | 016 candidate 12 (3차 MVP) |

응답은 `market=STOCK`이면 `tutorialKey="INVESTMENT_OCO_PRACTICE_V1"`, `market=CRYPTO`이면 `tutorialKey="COIN_OCO_PRACTICE_V1"`이며, `status`(`NOT_STARTED|IN_PROGRESS|COMPLETED`), `currentStep`(진행 중 1~3, 완료 시 null), 1~3 순서의 `steps`, `completedAt`(완료 전 null)을 포함한다. 각 step은 `step`, `status`, `locked`, non-null `evidence`를 가진다. evidence는 favorite·intention·buyTrade·exitPlan·observation·reflection 각각의 id와 시각을 쌍으로 노출하며 아직 없는 값은 null이다. observation은 `evidenceType`(`CLOSER_TO_BOUNDARY|TIMED_REPETITION|FINAL_EVENT`)까지 삼쌍으로 null/non-null이다. 완료 전에는 qualifying observation이 있는 유효 chain을 우선해 `exitPlan.reservedAt ASC, exitPlan.id ASC` 첫 chain을 선택하고, 없으면 전체 유효 chain에서 같은 정렬의 첫 chain을 선택한다. 유효 chain도 없으면 `favorite.createdAt ASC, favorite.id ASC` 첫 favorite를 사용한다. 단계별 evidence와 observation은 선택한 한 chain 안에서만 구성한다. 조회는 write하지 않고, 최초 완료 기록 이후에는 evidence 삭제·종결에도 `COMPLETED`가 회귀하지 않는다.

### 투자 의도 기록

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/education/practice/intentions | Access Bearer 필수. `{"instrumentId":1,"quantity":10,"stopLoss":65000,"takeProfit":75000}` (`PracticeIntentionCreateRequest`). 네 필드 모두 필수·양수이며 `quantity`는 정수부 22자리/소수부 8자리 이하, `stopLoss`·`takeProfit`은 각각 정수부 10자리/소수부 8자리 이하 | 201 `{"intentionId":1,"instrumentId":1,"quantity":10,"stopLoss":65000,"takeProfit":75000,"createdAt":"2026-08-03T10:01:00"}` (`PracticeIntentionResponse`) | 400 `VALIDATION_ERROR`; Access 인증 실패는 401 `UNAUTHORIZED`; 404 `NOT_FOUND`(종목); 409 `PRACTICE_STEP_LOCKED`, `PRACTICE_ALREADY_COMPLETED` 공통 오류 형식 | 016 candidate 4, Issue #175 |
| POST | /api/education/practice/oco/intentions | **계획 — QA 실행 근거 아님.** 요청·응답 DTO와 검증은 위 API와 동일 | 201 `PracticeIntentionResponse`. 종목 market에 따라 OCO 전용 progress를 생성·잠그고 intention에 같은 key를 내부 귀속 | 위 API와 같은 오류 형식. 완료 판정은 OCO 전용 key 기준 | 016 candidate 4 확장, Issue #308 |

현재 존재하는 본인 favorite와 같은 종목만 허용한다. 서비스는 `(user_id, tutorial_key)` 유일 제약의 `practice_progresses`를 atomic insert-if-absent 한 뒤 진행 행과 favorite를 잠가 검증한다.

**`tutorial_key`는 대상 종목의 `market`으로 서버가 결정한다** — `STOCK`이면 `INVESTMENT_PRACTICE_V1`, `CRYPTO`이면 `COIN_PRACTICE_V1`(이슈 #226 구현 완료, 규칙 정본은 `ai/specs/020-coin-practice-tutorial`). **클라이언트는 key를 입력하지 않는다.** 주식과 코인은 완전히 독립된 튜토리얼이라 한쪽 완료가 다른 쪽에 영향을 주지 않으며, 코인 종목으로 의도를 기록하려면 1단계 favorite도 같은 코인 종목이어야 한다(아니면 409 `PRACTICE_STEP_LOCKED`). 완료 상태면 저장 없이 409 `PRACTICE_ALREADY_COMPLETED`, favorite가 없으면 저장 없이 409 `PRACTICE_STEP_LOCKED`다. 유효 요청마다 사용자별 리스트에 새 레코드를 추가하며 **중복 intention을 금지하는 유일 제약은 없다**(저장 방식·유실은 도입부 참고). 이 API는 의도만 기록하며 실제 매수 체결은 `POST /api/orders`(시장가) 또는 `POST /api/orders/limit`(코인 지정가)의 별도 요청이다.

OCO 전용 경로는 `STOCK`이면 `INVESTMENT_OCO_PRACTICE_V1`, `CRYPTO`이면 `COIN_OCO_PRACTICE_V1`을 사용한다. 두 경로의 intention은 같은 인메모리 ID 공간에 저장하되 내부 tutorial key로 구분하며 상호 대체할 수 없다. OCO 생성은 OCO 전용 intention만 인정하고, OCO 복기는 exit plan 시장의 OCO progress를 잠가 같은 key의 completion을 생성한다. 따라서 holding 기반 실습을 이미 완료한 사용자도 OCO 전용 의도를 새로 기록해 독립적으로 시작할 수 있다.

**후속 확장 계획(#199, 아직 미구현):** 기존 타입 생략+`stopLoss`·`takeProfit` 요청은 PRICE로 호환하면서 `exitPriceType=PRICE|PERCENT`를 추가한다. PERCENT는 퍼센트 단위(백분율 값, `5`=5%)의 `stopLossRate`·`takeProfitRate`만 받고 실제 시장가 BUY `entryPrice`를 기준으로 OCO 생성 시 scale 8 절대 가격선을 계산한다. intention은 ADR-0012대로 인메모리를 유지하고 내부 UUID instance key로 영속 exit plan과 숫자 ID 재사용을 구분한다. tagged union, rate 범위·반올림·저장 정책은 `ai/specs/019-exit-price-policy`가 정본이며, 구현 전까지 위 현재 요청·응답만 실제 호출 가능하다.

### 튜토리얼 합성 시세 조회

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/education/practice/synthetic-prices/{instrumentId} | Access Bearer 필수. 양의 `Long` path `instrumentId` | 200 `{"title":"삼성전자","tickSeconds":3,"prices":[69000,69200,...]}` (`SyntheticPriceSeriesResponse`, `prices` 100개) | 400 `VALIDATION_ERROR`(양수 아님); Access 인증 실패는 401 `UNAUTHORIZED`; 404 `NOT_FOUND`(종목 없음) | 016 candidate 4 후속(#193 tasks 항목4) |

`InstrumentService.getInstrumentEntity`로 종목 존재만 확인하고 `instrument.isTradable()`은 검증하지 않는다(비거래 종목도 순수 참고용 차트로 허용). `title`은 `Instrument.name`(예: `"삼성전자"`)이고 `tickSeconds`는 항상 3, `prices`는 100개(5분/3초, 시작가 포함) `BigDecimal` 정수 배열이다. 시작가는 `PriceQueryService.getPriceQuote`로 조회한 실제 현재가를 사용하고, 가격이 없으면(PRICE_UNAVAILABLE 등) 고정 fallback 상수(10,000)를 시작가로 쓴다. 각 틱은 이전 값 대비 -1%~+1% 균등분포로 변동하며 시작가의 50% 미만으로는 떨어지지 않게 clamp한다. 요청마다 새로 계산하며 어떤 저장소에도 남기지 않고, 비즈니스 락은 없다(`InstrumentService`/`PriceQueryService` 조회를 위한 읽기 전용 트랜잭션만 사용).

이 API는 030 도입 뒤에도 표시 전용 호환 API다. 아래 영속 세션과 seed·cursor·가격을 공유하지 않으며 주문 체결·holding 관찰 evidence에 사용하지 않는다.

### 030 코인 가상 가격 세션 생성·조회·tick 진행

| Method | URL | 요청 | 성공 응답 | 오류 응답 |
|---|---|---|---|---|
| POST | `/api/education/practice/price-sessions` | Access Bearer 필수. `{"instrumentId":1}`(`PracticePriceSessionCreateRequest`, 양의 `Long`) | 201 `PracticePriceSessionResponse` | 400 `VALIDATION_ERROR`(누락·0 이하); 401 `UNAUTHORIZED`; 404 `NOT_FOUND`(종목 없음); 409 `INSTRUMENT_NOT_TRADABLE`(주식이거나 `tradable=false`), `PRACTICE_PRICE_SESSION_ALREADY_ACTIVE`(동일 사용자·종목 ACTIVE 중복, 동시 생성의 unique 위반도 동일 매핑) |
| GET | `/api/education/practice/price-sessions/{sessionId}` | Access Bearer 필수. 양의 `Long` path `sessionId` | 200 `PracticePriceSessionResponse` | 400 `VALIDATION_ERROR`(0 이하); 401 `UNAUTHORIZED`; 404 `NOT_FOUND`(없음·타인 소유, 존재 은닉) |
| POST | `/api/education/practice/price-sessions/{sessionId}/ticks` | Access Bearer 필수. 양의 `Long` path `sessionId`. `{"expectedTick":1}`(`PracticePriceTickAdvanceRequest`, 양의 `Integer`) | 200 `PracticePriceSessionResponse` | 400 `VALIDATION_ERROR`(누락·0 이하); 401 `UNAUTHORIZED`; 404 `NOT_FOUND`(없음·타인 소유, 존재 은닉); 409 `PRACTICE_PRICE_SESSION_CLOSED`(이미 COMPLETED), `PRACTICE_PRICE_TICK_CONFLICT`(`expectedTick != currentTick+1`) |
| POST | `/api/education/practice/limit-orders` | Access Bearer 필수. `{"practicePriceSessionId":1,"instrumentId":1,"quantity":0.01,"limitPrice":9500}`(`PracticeLimitOrderCreateRequest`) | 201 `LimitOrderResponse`(side는 서버가 `BUY`로 고정, PENDING 상태) | 400 `VALIDATION_ERROR`(누락·수량·가격 형식); 401 `UNAUTHORIZED`; 404 `NOT_FOUND`(세션·종목 없음 또는 타인 세션, 존재 은닉); 409 `INSTRUMENT_NOT_TRADABLE`, `PRACTICE_PRICE_SESSION_CLOSED`(COMPLETED 세션), `PRACTICE_PRICE_SESSION_MISMATCH`(요청 종목이 세션과 불일치), `PRACTICE_LIMIT_ORDER_ALREADY_PENDING`(세션에 PENDING 교육 주문 이미 존재), 현금 부족은 샌드박스 종목이면 `TUTORIAL_INSUFFICIENT_CASH`, 실제 종목(030 코인 연습 세션이 BTC·ETH 등을 다루는 경우)이면 `INSUFFICIENT_CASH`(047 이후, `PracticeLimitOrderCreationService.createSessionBuyOrder`가 `instrument.isTutorialSample()`로 분기) 등 기존 지정가 주문 오류 |

`PracticePriceSessionResponse`는 `sessionId`, `instrumentId`, `status`(`ACTIVE`\|`COMPLETED`), `generatorVersion`, `startPrice`, `currentTick`, `currentPrice`, `tickSeconds=3`, `totalTicks=100`, `createdAt`, nullable `completedAt`을 반환한다. `seed`는 서버 내부 재현 정보이며 응답에 노출하지 않는다. 생성은 `InstrumentService.getInstrumentEntity`로 종목 존재를 확인한 뒤 `market=CRYPTO`·`tradable=true`를 서비스가 검증하고, `PriceQueryService.getPriceQuote`로 조회한 실제 유효 현재가를 anchor로 쓰며 미가용 시 정확히 `10000.00000000`을 쓴다. seed는 서버 CSPRNG(`SecureRandom`)로 생성하고 생성 직후 `currentTick=0`, `currentPrice=startPrice`, `status=ACTIVE`다. 조회 시 seed·startPrice로 저장된 tick까지 v1 생성기를 재실행해 저장된 `currentPrice`와 일치하는지 검증하고, 불일치하면 클라이언트 오류가 아니라 500 `INTERNAL_ERROR`로 중단한다(재기동 재현성 방어).

가격 세션은 DB 영속이며 `(user_id, instrument_id)`별 ACTIVE 세션은 MySQL generated column(`active_slot`)과 unique 제약으로 최대 1개만 허용한다(완료 세션은 이 상한에 포함하지 않는다). 상세 스키마·생성기 byte encoding·잠금·재기동 계약은 `ai/specs/030-coin-practice-price-runtime/plan.md`가 정본이다.

next-tick은 세션을 owner 스코프로 `SELECT ... FOR UPDATE`(`findByIdAndUserIdForUpdate`) 잠근 뒤 상태·`expectedTick`을 검증하고, v1 생성기로 다음 가격을 계산해 `PracticePriceSession.advance()`로 `currentTick`·`currentPrice`를 갱신한다. `expectedTick`은 정확히 `currentTick+1`만 받으며(1..99 범위를 벗어나는 요청은 항상 이 비교에서 걸린다), 같은 트랜잭션 안에서 세션 전용 `PracticePriceTickAdvancedEvent`를 발행해 `PracticeTickFillListener`(일반 동기 `@EventListener`, 예외를 삼키지 않는다)가 같은 세션의 PENDING 교육 지정가 주문만 체결 판정한다. tick 99 도달 시에는 체결 판정을 먼저 마친 뒤 남은 PENDING 주문을 취소·예약 현금 반환하고, 그다음 세션을 `COMPLETED`로 전이해 `completedAt`을 채운다(`PracticePriceSession.complete()`). 튜토리얼 이벤트는 `PriceStore`·`CryptoPriceUpdatedEvent`를 사용하지 않고 같은 sessionId의 주문만 처리하며, 일반 지정가 체결 경로(`findPendingLimitOrdersToFill`)는 `practicePriceSessionId is null` 조건으로 교육 주문을 제외한다.

교육 지정가 생성(`POST /api/education/practice/limit-orders`)은 세션을 owner 스코프로 먼저 잠근 뒤(존재하지 않거나 타인 소유는 404 `NOT_FOUND`) `status=ACTIVE`(아니면 409 `PRACTICE_PRICE_SESSION_CLOSED`)·요청 `instrumentId`가 세션의 종목과 일치(아니면 409 `PRACTICE_PRICE_SESSION_MISMATCH`)를 검증하고, order 도메인의 `PracticeLimitOrderCreationService`에 위임한다. 이 서비스가 종목 `market=CRYPTO`·`tradable=true`(아니면 409 `INSTRUMENT_NOT_TRADABLE`), 수량·지정가 형식과 최소주문금액(기존 015 지정가 규칙 재사용), 세션당 PENDING 교육 주문 1건 상한(초과 시 409 `PRACTICE_LIMIT_ORDER_ALREADY_PENDING`)을 검증한 뒤 계좌 현금을 예약하고(부족 시 기존 409 `INSUFFICIENT_CASH`) `Order`를 `practicePriceSessionId`와 함께 PENDING으로 저장한다. side는 요청에서 받지 않고 서버가 항상 `BUY`로 고정한다. `Idempotency-Key` 헤더는 요구하지 않으며 서버가 `practice:{sessionId}:{UUID}` 형태로 멱등키를 합성한다(재요청 재현이 아니라 세션 비관 잠금 + PENDING 1건 상한이 중복 생성을 막는다). 응답은 기존 지정가 생성과 동일한 `LimitOrderResponse`다(생성 시점은 항상 PENDING이라 체결 정보를 포함하는 `OrderResponse`를 쓰지 않는다).

holding 관찰은 buyTrade→order에서 sessionId를 서버가 역추적한다(이슈 #321, 3안 — priceruntime의 `PracticePriceObservationService` 파사드가 세션 가격원을 소유). sessionId가 있으면 ACTIVE/COMPLETED 세션의 마지막 현재가, null이면 기존 실제 가격원(`PriceQueryService`)을 사용한다. sessionId가 있는데 세션 owner·instrument가 chain과 불일치하면(데이터 이상 상태) 실제 가격으로 조용히 fallback하지 않고 409 `PRACTICE_EVIDENCE_MISSING`으로 거부한다 — `holding-observations` API의 기존 오류 집합을 늘리지 않는다. 세션 가격 경로에서는 `PRICE_UNAVAILABLE`이 발생하지 않는다(세션 `currentPrice`는 항상 non-null). 상세 잠금 순서·트랜잭션 계약은 `ai/specs/030-coin-practice-price-runtime/plan.md`가 정본이다.

### OCO exit plan 생성 — 교육 경로, `intentionId` 지정 (계획)

> **라우트는 이미 존재한다(`POST /api/exit-plans`, Issue #348).** 아래 표는 `intentionId`를 지정하는 **교육 경로** 계약이며 아직 구현되지 않았다 — 현재 컨트롤러는 `intentionId`가 오면 무조건 400 `VALIDATION_ERROR`로 거부한다. `intentionId`를 생략하는 **일반 경로**는 이미 production이며 `docs/api/order.md`의 "021 일반 리스크관리 OCO" 절이 정본이다.

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/exit-plans | 필수 `Idempotency-Key: <UUID>`; body `{"intentionId":1,"buyTradeId":10,"instrumentId":1,"quantity":10}` (`ExitPlanCreateRequest`). 가격·rate는 잠근 intention 정본 사용 | 최초 201, 기존 plan 수렴 200 `ExitPlanResponse` | 400 `VALIDATION_ERROR`; 404 `NOT_FOUND`(요청 종목); 409 `PRACTICE_EVIDENCE_MISSING`, `EXIT_PLAN_INVALID_PRICE_RANGE`, `EXIT_PLAN_SESSION_CLOSED`, `PRICE_UNAVAILABLE`, `INSUFFICIENT_QTY`, `IDEMPOTENCY_CONFLICT` | 016 candidate 7, 019 |

`ExitPlanResponse`는 기존 식별자·수량·entry/baseline/status/시각과 `exitPriceType`, PERCENT에서만 non-null인 `stopLossRate`·`takeProfitRate`, 항상 non-null인 `stopLossPrice`·`takeProfitPrice`를 반환한다. `replaySessionId`는 코인만 null이며 PENDING이면 `closedAt`·`triggeredOrderId`가 null이다. 상태는 `PENDING|FILLED_TAKE_PROFIT|FILLED_STOP_LOSS|CANCELLED|CANCELLED_EXPIRED`다.

본인 favorite → 현재 process intention → FILLED 시장가 BUY trade → holding의 종목·소유권을 검증하고 `intention.quantity == buyTrade.quantity == request.quantity`, `availableQuantity >= quantity`, `0 < stopLossPrice < entryPrice < takeProfitPrice`를 요구한다. PRICE는 intention 가격을 복사하고 PERCENT는 실제 `entryPrice`로 계산하며 클라이언트가 OCO 생성 시 값을 덮어쓰지 못한다. 계산 가격이 `DECIMAL(18,8)`을 초과하거나 범위가 깨지면 409 `EXIT_PLAN_INVALID_PRICE_RANGE`로 plan·예약 없이 거부한다. 서버 유효 현재가를 baseline으로 저장하고 수량은 한 번만 예약한다. idempotency key와 내부 intention instance UUID는 lowercase canonical 문자열로 저장한다. key hit는 현재 intention보다 먼저 영속 mapping을 조회해 같은 hash면 재시작 후에도 과거 plan을 200으로 재현한다. key miss에서만 현재 intention instance를 해석하며 같은 instance·같은 fingerprint의 새 key는 기존 plan으로 200 수렴하고 충돌은 409다.

### OCO 예약 목록 조회 (production, 경로 공통)

> Issue #348 후속(021 "목록·응답 계약 전환")으로 실제 production이 됐다 — `docs/api/order.md`의 "021 일반 리스크관리 OCO" 절이 정본이다. `status` 허용 값은 016이 계획했던 `PENDING` 전용에서 `ExitPlanStatus` 전체로 넓어졌다(경로 무관, 일반·교육 plan을 구분하지 않는다).

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/exit-plans?status= | 선택 query `status`(`PENDING`\|`FILLED_TAKE_PROFIT`\|`FILLED_STOP_LOSS`\|`CANCELLED`); 생략 시 `PENDING` | 200 `ExitPlanListResponse`(`{"content":[ExitPlanResponse...]}`); 없으면 빈 배열 | 400 `VALIDATION_ERROR`(허용 값 밖) | 021, 016 candidate 8, Issue #348 |

`id DESC` 순이며 페이지네이션이 없다. 본인 소유 예약만 조회한다(타인 예약은 목록에 나타나지 않는다 — 존재 은닉이 아니라 필터). 응답은 생성·취소와 같은 `ExitPlanResponse`를 재사용한다.

### OCO 예약 취소 (production, 경로 공통)

> Issue #348로 실제 production이 됐다 — `docs/api/order.md`의 "021 일반 리스크관리 OCO" 절이 정본이다. 이 표는 016이 원래 세운 계약이며 실제 구현과 일치한다(경로 무관, 일반·교육 plan을 구분하지 않는다).

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| DELETE | /api/exit-plans/{exitPlanId} | 양의 `exitPlanId` path | 204, 본문 없음 | 400 `VALIDATION_ERROR`; 404 `EXIT_PLAN_NOT_FOUND`; 409 `EXIT_PLAN_NOT_PENDING` | 021 일반 리스크관리 OCO, 016 candidate 9, Issue #348 |

본인 PENDING plan만 취소한다. 두 조건을 함께 종결하고 예약 수량을 정확히 한 번 반환한다. 타인 plan은 404, 반복 요청과 가격 트리거·만료 경합 패자는 409다.

### 가격 관찰 기록 (계획)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/education/practice/observations | `{"exitPlanId":1}` (`PracticeObservationCreateRequest`) | 201 `{"observationId":1,"exitPlanId":1,"currentPrice":69000,"observedAt":"2026-08-03T10:05:00","closerToBoundary":true,"closerBoundary":"STOP_LOSS","evidenceType":"CLOSER_TO_BOUNDARY"}` (`PracticeObservationResponse`) | 400 `VALIDATION_ERROR`; 404 `EXIT_PLAN_NOT_FOUND`; 409 `EXIT_PLAN_NOT_PENDING`, `PRICE_UNAVAILABLE` | 016 candidate 13 |

본인 PENDING plan만 허용하며 가격·시각·유형은 클라이언트가 보내지 않는다. `closerBoundary`는 `STOP_LOSS|TAKE_PROFIT` 또는 null, `evidenceType`은 `CLOSER_TO_BOUNDARY|TIMED_REPETITION` 또는 아직 미충족이면 null이다. terminal `FINAL_EVENT`는 서버 종결 트랜잭션만 만든다.

### 투자 실습 복기 저장 (계획)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/education/practice/reflections | `{"exitPlanId":1,"answer":"계획한 손절선에 가까워져 팔고 싶었지만 미리 정한 기준을 확인했다."}` (`PracticeReflectionCreateRequest`) | 최초 201 `{"reflectionId":1,"exitPlanId":1,"prompt":"지금 팔고 싶나요? 그렇다면 왜 그런가요? 계획한 손절·익절 라인과 비교해 적어보세요.","answer":"...","createdAt":"2026-08-03T10:10:00"}` (`PracticeReflectionResponse`) | 400 `VALIDATION_ERROR`; 404 `EXIT_PLAN_NOT_FOUND`; 409 `PRACTICE_EVIDENCE_MISSING`, `PRACTICE_ALREADY_COMPLETED` | 016 candidate 14 |

> **이 절의 프롬프트 문구를 holding 기준 경로에 그대로 쓰지 않는다.** 위 문구는 사용자가 exit plan으로 손절·익절을 **직접 계획하는** 016 OCO 경로(미구현)의 것이다. `POST /api/education/practice/holding-reflections`는 039가 사전 의도 입력을 없애고 서버가 체결가 기준 -3%/+5%를 자동 고정하는 흐름이라 문구가 갈라졌다(이슈 #422). 각 경로의 문구는 해당 절이 정본이다.

`answer`는 whitespace-only가 아닌 raw Java 문자열 길이 1~2000이며 trim 없이 `VARCHAR(2000) NOT NULL`에 원문을 저장한다. A(경계에 가까워짐), B(2분 이상 범위의 서버 관찰 3회), C(체결·주식 만료 final event) 중 하나와 전체 owner·instrument·수량 evidence를 재검증한다. 정답·점수·보상은 없다. 사용자·튜토리얼 최초 요청만 reflection·completion을 원자 저장하고 이후 요청은 답변을 추가 저장하지 않은 채 409다.

### 투자 실습 공통 인증·오류 및 DTO 규칙

- 인증 실패는 401 `UNAUTHORIZED`; 예상하지 못한 실패는 500 `INTERNAL_ERROR` 공통 body다.
- path/body로 직접 지정한 favorite·exit plan이 타인 소유이면 리소스별 404로 존재를 숨긴다. OCO·복기 내부 evidence chain 불일치는 409 `PRACTICE_EVIDENCE_MISSING`이다.
- 목록 `content`와 진행 조회 `steps`는 항상 non-null이다. 모든 응답 시각은 ISO-8601 `LocalDateTime` 형식이다.
- `POST /api/exit-plans`만 멱등 API다. 나머지 POST는 중복 규칙으로 보호하며 DELETE 성공 응답에는 body가 없다.

| DTO | 필드 순서와 타입 | nullable 규칙 |
|---|---|---|
| `FavoriteCreateRequest` | `Long instrumentId` | non-null |
| `FavoriteResponse` | `Long favoriteId`, `Long instrumentId`, `String market`, `String symbol`, `String name`, `LocalDateTime createdAt` | 모두 non-null; market은 `STOCK|CRYPTO` |
| `FavoriteListResponse` | `List<FavoriteResponse> content` | non-null, 빈 배열 허용 |
| `PracticeIntentionCreateRequest` | `Long instrumentId`, `BigDecimal quantity`, `BigDecimal stopLoss`, `BigDecimal takeProfit` | 모두 non-null |
| `PracticeIntentionResponse` | `Long intentionId`, `Long instrumentId`, `BigDecimal quantity`, `BigDecimal stopLoss`, `BigDecimal takeProfit`, `LocalDateTime createdAt` | 모두 non-null |
| `ExitPlanCreateRequest` | `Long intentionId`, `Long buyTradeId`, `Long instrumentId`, `Long holdingId`, `BigDecimal quantity`, `ExitPriceType exitPriceType`, `BigDecimal stopLoss`, `BigDecimal takeProfit`, `BigDecimal stopLossRate`, `BigDecimal takeProfitRate` | **일반 경로(production, 021)**: `holdingId`·`quantity`·`exitPriceType` non-null 필수, `intentionId`·`buyTradeId`·`instrumentId`는 포함하면 400. **교육 경로(계획, 016)**: `intentionId`·`buyTradeId`·`instrumentId`·`quantity` non-null 필수, `holdingId`·`exitPriceType`·가격·rate 필드는 없음(현재 컨트롤러가 `intentionId` non-null이면 무조건 400으로 거부) |
| `ExitPlanResponse` | `Long id`, `Long holdingId`, `Long intentionId`, `Long buyTradeId`, `Long instrumentId`, `BigDecimal quantity`, `BigDecimal entryPrice`, `ExitPriceType exitPriceType`, `BigDecimal stopLossRate`, `BigDecimal takeProfitRate`, `BigDecimal stopLossPrice`, `BigDecimal takeProfitPrice`, `BigDecimal baselinePrice`, `LocalDateTime baselineObservedAt`, `ExitPlanStatus status`, `LocalDateTime reservedAt`, `LocalDateTime closedAt`, `Long triggeredOrderId`, `Long replaySessionId` | `holdingId`·`instrumentId`·`quantity`·`entryPrice`·`exitPriceType`·확정 가격 둘·`baselinePrice`·`baselineObservedAt`·`status`·`reservedAt`은 항상 non-null. `intentionId`·`buyTradeId`는 일반 경로(021)에서 항상 null, 교육 경로(계획, 016)에서 non-null. rate 둘은 PERCENT만 non-null. `closedAt`·`triggeredOrderId`는 PENDING이면 null. `replaySessionId`는 코인이면 항상 null(021 범위) |
| `ExitPlanListResponse` | `List<ExitPlanResponse> content` | non-null, 빈 배열 허용 |
| `PracticeObservationCreateRequest` | `Long exitPlanId` | non-null |
| `PracticeObservationResponse` | `Long observationId`, `Long exitPlanId`, `BigDecimal currentPrice`, `LocalDateTime observedAt`, `Boolean closerToBoundary`, `String closerBoundary`, `String evidenceType` | 앞의 다섯 필드는 non-null; 뒤의 두 필드는 조건 미충족 시 null |
| `PracticeReflectionCreateRequest` | `Long exitPlanId`, `String answer` | 모두 non-null; answer는 blank 불가, raw 길이 최대 2000 |
| `PracticeReflectionResponse` | `Long reflectionId`, `Long exitPlanId`, `String prompt`, `String answer`, `LocalDateTime createdAt` | 모두 non-null |
| `InvestmentPracticeResponse` | `String tutorialKey`, `String status`, `Integer currentStep`, `List<PracticeStepResponse> steps`, `LocalDateTime completedAt` | `currentStep`·`completedAt`은 완료 여부에 따라 null; 나머지는 non-null |
| `PracticeStepResponse` | `Integer step`, `String status`, `Boolean locked`, `PracticeEvidenceResponse evidence` | 모두 non-null; locked 단계도 빈 evidence 객체 반환 |
| `PracticeEvidenceResponse` | `Long favoriteId`, `LocalDateTime favoriteCreatedAt`, `Long intentionId`, `LocalDateTime intentionCreatedAt`, `Long buyTradeId`, `LocalDateTime buyTradeExecutedAt`, `Long exitPlanId`, `LocalDateTime exitPlanReservedAt`, `Long observationId`, `LocalDateTime observationObservedAt`, `String evidenceType`, `Long reflectionId`, `LocalDateTime reflectionCreatedAt` | 리소스 id·시각은 쌍으로 null/non-null; observation은 id·시각·type이 함께 null/non-null |

진행 상태의 모든 조합, OCO fingerprint canonical JSON과 동시성·잠금 정본은 `ai/specs/016-investment-education-policy/plan.md`를 따른다.

## 026 시장가/지정가 매매 기반 투자 실습 (OCO 없이, holding-observations)

`016`의 OCO exit plan 없이 지금 production에 있는 시장가·코인 지정가 매수만으로 2·3단계를 완결하는 경로다(`ai/specs/026-market-order-practice-tutorial`). `holdingId`를 요청 식별자로 받으며 `016`의 `/observations`·`exitPlanId` 계약과 URL·필드가 다르고 서로 공존한다(spec.md "관찰·복기 API 대상 식별자" 절).

> **2차 MVP에서 3단계 실습을 실제로 완료할 수 있는 유일한 경로이며, 블랙박스 QA는 이 절을 근거로 삼는다.** `016` 절의 OCO 계약(`/exit-plans`, `/observations`, `/reflections`)은 3차 MVP 설계이고 controller가 없다.
>
> **`GET /api/education/practice?market=STOCK|CRYPTO`가 이 holding 기반 경로의 정본이다**(2026-08-10 확정, 이슈 #308, PR #307 구현). `market`은 필수이며 응답 하나는 선택한 시장의 `INVESTMENT_PRACTICE_V1|COIN_PRACTICE_V1` 한 key만 나타낸다. evidence 필드는 `holdingId`와 계산된 참조 손절·익절가를 담고 `evidenceType`은 `CLOSER_TO_BOUNDARY|TIMED_REPETITION`만 허용한다. 3차 OCO는 별도 `GET /api/education/practice/oco?market=`와 별도 완료 key를 사용한다.

**시장 적용 범위**: 주식·코인 모두 지원한다. 2단계 매수 증거는 주문 유형을 구분하지 않으므로(`TradeService.findEarliestFilledBuyTradeMatching`이 `side=BUY`와 수량 일치만 검사) 코인은 `POST /api/orders`(시장가)와 `POST /api/orders/limit`(지정가) 체결 둘 다 인정되고, 주식은 지정가 API 자체가 없어 시장가만 자연히 해당한다.

### 실습 3단계 가격 관찰 기록 (holding 기준)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/education/practice/holding-observations | Access Bearer 필수. `{"holdingId":1}` (`PracticeHoldingObservationCreateRequest`). `holdingId` 필수·양수 | 201 `{"observationId":1,"holdingId":1,"currentPrice":69000,"observedAt":"2026-08-10T10:05:00","closerToBoundary":true,"closerBoundary":"STOP_LOSS","evidenceType":"CLOSER_TO_BOUNDARY"}` (`PracticeHoldingObservationResponse`). 영속 attempt가 있는 샘플 종목은 현재 run의 risk snapshot BUY·holding만 인정하고 snapshot 이후 관찰만 현재 evidence로 누적하며 canonical 가격을 사용한다. favorite·intention은 요구하지 않는다. attempt가 없는 기존 샘플과 실제 종목은 기존 026 chain 계약을 유지한다 | 400 `VALIDATION_ERROR`(`holdingId` 누락·0 이하); Access 인증 실패는 401 `UNAUTHORIZED`; 404 `NOT_FOUND`(holding 없음 또는 타인 소유, 존재 비노출); 409 `PRACTICE_EVIDENCE_MISSING`(현재 attempt/run snapshot·holding 불일치 또는 legacy chain 불일치), `PRACTICE_ALREADY_COMPLETED`, `PRICE_UNAVAILABLE`(세션 없는 holding의 서버 유효 현재가가 아예 없음 — 코인은 연결 끊김이거나 그 심볼의 시세를 한 번도 받은 적이 없는 경우만 해당하며, 연결 유지 + 수신 이력이 있으면 관측 시각이 얼마나 오래됐어도 더 이상 여기 해당하지 않는다(036, 032·이슈 #355는 대체됨). 세션 가격 경로에서는 발생하지 않음) | 026 MKT-PRACTICE-004·005·007, 030 COIN-PRICE-RUNTIME-009·010, 032, 036, Issue #300·#321·#355; 039 TUTORIAL-FLOW-007·008·011, Issue #378 |

처리 순서는 다음과 같다(plan.md "확정 HTTP·JSON 계약" 절). ① `holdingId`로 본인 소유 holding을 조회한다(`HoldingService.findHoldingForOwner`) — 없거나 타인 소유면 404. ② holding의 종목 market으로 `tutorialKey`(`STOCK`→`INVESTMENT_PRACTICE_V1`, `CRYPTO`→`COIN_PRACTICE_V1`)를 정하고 `MarketPracticeChainResolutionService.resolveForInstrument`로 favorite→intention→buyTrade→holding chain을 재해석한다 — chain이 없거나 해석된 chain의 `holdingId`가 요청 holding과 다르면 409 `PRACTICE_EVIDENCE_MISSING`. ③ `ReferencePriceCalculator`로 참조 손절·익절 가격선을 매 요청 재계산한다 — intention이 유실돼 계산할 수 없으면 같은 409 `PRACTICE_EVIDENCE_MISSING`. ④ 관찰 가격을 구한다(이슈 #321) — `education.priceruntime.PracticePriceObservationService.findObservationPrice`가 `chain.buyTradeId()`로 `TradeService.findPracticePriceSessionId`(order의 공개 조회)를 거쳐 세션 귀속을 역추적한다. 세션이 있으면 owner·instrument 일치를 재검증한 뒤(불일치 시 409 `PRACTICE_EVIDENCE_MISSING`) ACTIVE/COMPLETED 세션의 `currentPrice`를 쓰고, 세션이 없으면 기존 `PriceQueryService.getPrice`로 fallback한다(표시 경로라 코인은 연결 유지 + 수신 이력이 있으면 관측 시각이 얼마나 오래됐어도 마지막 실제 가격을 그대로 관찰 근거로 쓴다 — 036. 가격이 아예 없으면(연결 끊김·미수신) 자체적으로 409 `PRICE_UNAVAILABLE`). ⑤ 같은 holding의 기존 관찰 목록을 조회해 `EvidenceJudgmentService`로 evidence A(경계 접근)·B(2분 이상 3회 시간 분산)를 판정한다. ⑥ `practice_market_observations` 1행을 append-only insert한다(비관적 락 없음, 동시 여러 건이 여러 행으로 쌓여도 무해).

`closerBoundary`는 `STOP_LOSS|TAKE_PROFIT` 또는 A 미충족 시 null, `evidenceType`은 `CLOSER_TO_BOUNDARY|TIMED_REPETITION` 또는 A·B 모두 미충족 시 null이다(`FINAL_EVENT`는 이 경로에 없음, evidence C 부재). `Idempotency-Key`를 요구하지 않는다 — 호출마다 새 행을 추가하는 append이며 `016`의 관찰 API도 같은 이유로 멱등키가 없다.

| DTO | 필드 순서와 타입 | nullable 규칙 |
|---|---|---|
| `PracticeHoldingObservationCreateRequest` | `Long holdingId` | non-null, 양수 |
| `PracticeHoldingObservationResponse` | `Long observationId`, `Long holdingId`, `BigDecimal currentPrice`, `LocalDateTime observedAt`, `Boolean closerToBoundary`, `String closerBoundary`, `String evidenceType` | 앞의 다섯 필드는 non-null; `closerBoundary`·`evidenceType`은 조건 미충족 시 null |

### 실습 3단계 자유 복기 저장 및 완료 확정 (holding 기준)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/education/practice/holding-reflections | Access Bearer 필수. `{"holdingId":1,"answer":"..."}` (`PracticeHoldingReflectionCreateRequest`). `holdingId` 필수·양수, `answer`는 `@NotBlank @Size(max=2000)`(whitespace-only 거부, 원문 저장) | 최초·재완료 모두 201 `PracticeHoldingReflectionResponse`. 영속 attempt가 있는 샘플 종목은 attempt를 먼저 잠그고 현재 run snapshot 이후 qualifying 관찰과 같은 run의 5분 내 SELL을 검증한다(재완료도 evidence 검증은 동일하게 통과해야 한다). legacy intention이 만들던 progress 행은 없으면 원자 생성한다. `practice_completions` 행이 이번 트랜잭션 시작 시점에 이미 있으면(재시작 후 재완료) `practice_market_reflections`·`practice_completions`·`practice_progresses`에는 쓰지 않고 `attempt.status=COMPLETED`만 갱신하며 보상을 지급하지 않는다(`reflectionId=null`, `rewardGranted=false`, `answer`는 검증에만 쓰고 영속하지 않음). 없었으면(최초 완료) 기존대로 reflection·completion·progress 완료·attempt `COMPLETED`·시장 계좌 500만원 보상을 한 트랜잭션에서 확정한다(`rewardGranted=true`). attempt가 없는 기존 샘플과 실제 종목은 기존 progress/026 chain 계약을 유지하며 재완료 분기가 없다(`rewardGranted`는 최초 완료 시 `true`) | 400 `VALIDATION_ERROR`; 401 `UNAUTHORIZED`; 404 `NOT_FOUND`; 409 `PRACTICE_EVIDENCE_MISSING`(현재 run snapshot·holding·관찰·SELL 부족 또는 legacy chain 불일치), `PRACTICE_ALREADY_COMPLETED`(attempt가 재시작 없이 이미 `COMPLETED`인 상태로 같은 완료를 중복 제출하는 경우만 — 재시작 후 재완료는 더 이상 이 오류를 던지지 않는다), `PRACTICE_SANDBOX_TIME_EXPIRED`(현재 run SELL 없음 상태로 5분 초과 또는 늦은 SELL) — **(041 SCENARIO-014, Issue #472) generator version 2 attempt(= 대본이 저작된 CRYPTO 실행)에서는 이 코드가 도달 불가다.** 시간 제한이 폐지돼 매도 시각을 검사하지 않으며, 매도 체결이 없는 경우는 시간과 무관하게 `PRACTICE_EVIDENCE_MISSING`이다. **STOCK 튜토리얼은 대본이 없어 여전히 버전 1이라 5분 마감이 그대로 적용된다** — legacy chain(attempt 없는 샘플·실제 종목)도 마찬가지다 | 026 MKT-PRACTICE-004·005·007·009, Issue #303; 031 SANDBOX-006·007·008, Issue #339; 039 TUTORIAL-FLOW-005·007·008·012, Issue #378; 040 TUTORIAL-RESTART-004·005·006·007, Issue #402 |

처리 순서는 다음과 같다(지시사항 및 plan.md "트랜잭션과 경합" 절). ① `holdingId`로 본인 소유 holding을 조회한다(`HoldingService.findHoldingForOwner`) — 없거나 타인 소유면 404. ② holding의 종목 market으로 `tutorialKey`를 정하고 `PracticeProgressRepository.findByUserIdAndTutorialKeyForUpdate`로 `practice_progresses` 행을 잠근다 — 사전 의도 기록을 거치지 않아 행 자체가 없으면 409 `PRACTICE_EVIDENCE_MISSING`. ③ progress가 이미 `COMPLETED`면 409 `PRACTICE_ALREADY_COMPLETED`(영속 attempt가 있는 샘플 종목 경로는 이 progress 체크 대신 attempt 상태로 판정한다 — 아래 참고). ④ `MarketPracticeChainResolutionService.resolveForInstrument`로 chain을 재해석한다 — 실패하거나 해석된 chain의 `holdingId`가 요청 holding과 다르면 409 `PRACTICE_EVIDENCE_MISSING`. ⑤ 같은 holding의 기존 `practice_market_observations` 중 `evidenceType`이 non-null인 행이 하나도 없으면 같은 409. ⑥ `practice_market_reflections` 1행 + `practice_completions` 1행을 저장하고 progress를 `COMPLETED`로 전이한다 — 전부 같은 트랜잭션. `Idempotency-Key`를 요구하지 않는다(`016`의 복기 API와 같은 이유).

**영속 attempt 경로(040, Issue #402)**: holding 종목이 샘플이고 attempt가 있으면 위 progress 기반 순서 대신 `PracticeHoldingReflectionService.createAttemptReflection`을 탄다. attempt가 이미 `COMPLETED`면 즉시 409 `PRACTICE_ALREADY_COMPLETED`(재시작 없이 같은 완료를 다시 제출). 아니면 evidence를 검증한 뒤 `practice_progresses`를 `FOR UPDATE`로 잠그고 그 시점에 `practice_completions` 행이 있는지로 최초/재완료를 가른다 — 있으면 재완료(위 표의 쓰기 생략·`reflectionId=null`·`rewardGranted=false`), 없으면 최초 완료(기존 저장·보상 흐름, `rewardGranted=true`). 이 판정은 `practice_progresses` 비관적 락으로 직렬화되어(TUTORIAL-RESTART-006) 동시 재완료 요청도 보상을 두 번 지급하지 않는다.

`(user_id, tutorial_key)` unique(`practice_market_reflections`, `practice_completions` 모두)가 최종 경합 방어선이며, `practice_progresses` 비관적 락으로 동시 복기 요청을 직렬화한다.

응답 `prompt`는 요청과 무관하게 항상 같은 고정 문구다(`PracticeHoldingReflectionResponse.PROMPT`). 현재 문구는 `방금 판 이유가 무엇인가요? 화면에 표시된 손절선·익절선과 비교해서, 지금 돌아보면 그 판단이 어땠는지 한 줄로 적어 보세요.`이며, 이 단계가 전량 매도 이후에 열리고(031 SANDBOX-006) 손절·익절선을 사용자가 아니라 서버가 자동 고정한다는(039 TUTORIAL-FLOW-008) 전제를 반영한다(Issue #422). 앞의 `016` 계획 절(`POST /api/education/practice/reflections`)의 OCO 복기 문구는 사용자가 exit plan을 직접 계획하는 별도 경로이므로 이 문구와 다르다.

| DTO | 필드 순서와 타입 | nullable 규칙 |
|---|---|---|
| `PracticeHoldingReflectionCreateRequest` | `Long holdingId`, `String answer` | `holdingId` non-null·양수; `answer`는 non-blank·2000자 이하 |
| `PracticeHoldingReflectionResponse` | `Long reflectionId`, `Long holdingId`, `String prompt`, `String answer`, `LocalDateTime createdAt`, `boolean rewardGranted` | `holdingId`·`prompt`·`answer`·`createdAt`·`rewardGranted`는 non-null; `reflectionId`는 최초 완료만 non-null이고 재완료(040)는 `null`(새 reflection 행을 만들지 않으므로) |

### 036 튜토리얼 attempt 진입·종목 선택·자동 위험 스냅샷

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| PUT | `/api/education/practice/attempts/{market}` | Access Bearer 필수. path `market=STOCK|CRYPTO`, body 없음 | 200 `PracticeAttemptResponse`. 먼저 선택 대기 행을 insert-if-absent한 뒤 `attempt → 기존 progress` 순서로 잠그고 completion을 읽는다. completion이 없으면 기존 attempt를 무변경 반환한다. legacy completion이 있고 이 호출이 빈 SELECTING 행을 새로 삽입했다면 reflection holding 종목(샘플·실제 모두 허용), `completedAt` 기반 anchor/date와 completion 식별자 기반 결정적 seed/version을 가진 `COMPLETED/REPLAY`로 같은 run 1에서 전환하며 주문·체결 원장을 쓰지 않는다. attempt가 이미 `COMPLETED`면 무변경이다. **(040, Issue #402) completion이 있고 attempt가 현재 `COMPLETED`가 아닌 조합(재시작 후 진행 중인 attempt로 재진입)은 더 이상 오류로 거부하지 않고 attempt의 현재 상태(`SELECTING_INSTRUMENT`/`IN_PROGRESS`/`EXPIRED`)를 그대로 반환한다** — completion evidence는 "최초 완료했었다"는 기록일 뿐 현재 실행 상태를 덮어쓰지 않는다. lazy replay의 `riskSnapshot=null`이다 | 400 `VALIDATION_ERROR`; 401 `UNAUTHORIZED`; legacy completion 종목 시장 불일치는 409 `PRACTICE_EVIDENCE_MISSING` | 039 TUTORIAL-FLOW-001·002·005, Issue #378; 040 TUTORIAL-RESTART-002·003, Issue #402; 047 TUTORIAL-CASH-ISOL-011, Issue #450 |
| POST | `/api/education/practice/attempts/{market}/restart` | Access Bearer 필수. path `market=STOCK|CRYPTO`, body 없음 | 200 `PracticeAttemptResponse`. attempt를 먼저 잠그고 현재 run 주문을 ID 오름차순으로 잠근 뒤 pending 예약을 한 번만 반환한다. FILLED BUY-SELL 순수량이 양수면 현재 canonical tutorial price로 보상 `FILLED SELL` 원장을 남기고, 정리가 모두 성공한 뒤 `runNumber+1`, `status=SELECTING_INSTRUMENT`와 선택·clock 필드 null을 반환한다. **(040, Issue #402) `COMPLETED` attempt도 더 이상 무변경 `REPLAY`를 반환하지 않고 위와 동일한 정리·재시작을 실제로 수행한다** — 재시작 자체는 보상을 지급하거나 회수하지 않으며, `practice_completions`(최초 완료 evidence)는 이 요청으로 삭제·수정되지 않는다. **(이슈 #433) 선택 종목이 샌드박스 샘플이 아닌 legacy 완료 replay attempt**(V32 샌드박스 종목 도입 이전에 실제 종목으로 완료해 `PUT .../attempts/{market}` 진입이 실제 종목을 심어 준 `COMPLETED` attempt)**는 원장 정리 없이 run만 증가시킨다** — 종목을 정리 대상으로 넘기지 않으므로 실제 종목의 holding·주문·체결은 조회도 변경도 하지 않고, 현재 run에 귀속된 주문이 0건일 때만 `runNumber+1`·`SELECTING_INSTRUMENT` 전이를 수행한다. 다음 종목 선택은 `PUT .../attempts/{market}/instrument`가 샌드박스 샘플만 허용하므로 정상 흐름으로 복귀한다 | 400 `VALIDATION_ERROR`(미지원 market); 401 `UNAUTHORIZED`; 409 `PRACTICE_EVIDENCE_MISSING`(attempt 없음, 원장 귀속 또는 순수량과 가용 샘플 holding 불일치, legacy 완료 replay attempt에 귀속 주문이 남아 있음, 정리 대상 종목이 요청 시장의 샌드박스 샘플이 아님; 전체 rollback), `PRICE_UNAVAILABLE`(보상 가격 없음; 전체 rollback) | 039 TUTORIAL-FLOW-003·004, Issue #378; 040 TUTORIAL-RESTART-001, Issue #402; Issue #433; 047 TUTORIAL-CASH-ISOL-011, Issue #450 |
| GET | `/api/education/practice/attempts/{market}/chart` | Access Bearer 필수. path `market=STOCK|CRYPTO`, body 없음 | 200 `PracticeTutorialChartResponse`: `attemptId`, `runNumber`, `instrumentId`, `virtualDateTime`, `secondsPerVirtualMinute=3`, 시간순 30개 `candles`. ACTIVE는 조회 시각을 사용하고 `COMPLETED/REPLAY`는 `completedAt`을 관측 시각으로 고정해 이후 clock 진행에도 chart·virtualDateTime이 변하지 않는다. 주문·attempt·예약을 변경하지 않는 순수 조회다. **(041 5번, Issue #472) 생성기 버전 2 attempt는 마지막 봉의 종가가 저장된 대본 커서의 가격이고 시·고·저는 attempt에 누적된 값이다** — 이 조회는 커서를 전진시키지 않으므로 `POST .../tick` 없이 새로고침만 반복하면 같은 값이 계속 나온다. 과거 29개 완결 일봉은 대본 대상이 아니라 버전 1과 같은 seed 생성이다. `virtualDateTime`은 두 버전 모두 `anchorAt` 기준 벽시계 파생이라 대본 진행이 clamp된 만큼 커서보다 앞설 수 있다. **(041 6번, Issue #488) 응답에 `scenarioStage`·`scenarioProgressing`·`causeStatus`·`revealedEvents` 4필드가 더해졌다** — `scenarioStage`는 `IDLE_ENTRY`\|`ACT1`\|`ACT2`\|`IDLE_REENTRY`\|`ACT3`\|`ACT4`\|`FINISHED`로 **대본 내부 구간이 아니라 act 단위**이며 `FINISHED`가 대본 종료 알림이다(별도 완료 필드를 두지 않는다). `scenarioProgressing`은 현재 구간이 진행 구간인지이고 **보유 여부와 무관하다**(미보유로 4막을 관전 중이어도 `true`). `causeStatus`는 `REVEALED`\|`NONE_KNOWN` **둘뿐이며 미공개 사건이 있는 구간도 `NONE_KNOWN`이라 두 경우를 구분할 수 없다**(SCENARIO-015·016). 판정은 막이 아니라 **대본 구간** 단위라 2막-b 속임수 반등은 앞 구간 루머가 공개된 뒤에도 `NONE_KNOWN`이다. `revealedEvents`는 `{stage, headline}`의 배열로 **공개 시점이 지난 사건만** 담고 공개 순서(오래된 것 → 최근)다 — **시각을 담지 않는다**(사건 공개는 대본 커서가, `virtualDateTime`은 벽시계가 정해 두 시계가 어긋나므로 화면은 순서만 보고 "방금"·"조금 전"으로 그린다). 대본을 쓰지 않는 attempt(생성기 버전 1·완료 replay)는 앞 3필드가 `null`이고 `revealedEvents`가 빈 배열이다 | 400 `VALIDATION_ERROR`; 401 `UNAUTHORIZED`; 409 `PRACTICE_STEP_LOCKED`(attempt 없음 또는 종목 미선택) | 039 TUTORIAL-FLOW-005·009·010·011·012, Issue #378; 041 SCENARIO-002·015·016·022, Issue #472·#488 |
| POST | `/api/education/practice/attempts/{market}/tick` | Access Bearer 필수. path `market=STOCK|CRYPTO`, body 없음 | 200 `PracticeTutorialChartResponse`(GET chart와 동일). attempt를 비관 잠근 뒤 같은 run의 PENDING 지정가 주문을 ID 순서로 조회하고 현재 canonical close로 조건 판정·체결하며, 응답 차트와 체결가는 같은 서버 관측 시각을 쓴다. 조건 미충족 주문은 PENDING과 예약을 유지한다. **(041 5번, Issue #472) 생성기 버전 2 attempt는 이 호출이 대본 커서를 전진시키는 유일한 지점이다** — 직전 tick 이후 경과를 최대 30초로 clamp해 소비하고, 그 사이 지나간 **가상 분마다 순차로** 지정가 조건을 판정한다(tick 종점 가격 하나로 판정하지 않는다). 대기 구간에서 보유가 생기면 시간을 소비하지 않고 다음 진행 구간 0분으로 이동하며 남은 경과를 매수 체결 시각 기준으로 자른다. 매도는 커서를 옮기지 않는다. 마지막 구간 끝에서는 진행을 멈추고 마지막 가격을 유지하며 오류를 던지지 않는다 — **다만 정산은 계속한다**(새 가상 분에 진입하지 않은 tick도 현재 커서 가격으로 지정가를 1회 판정하므로, 대본이 끝난 뒤 접수한 지정가도 조건을 만족하면 체결된다). **(041 6번, Issue #488) 응답 4필드는 GET chart와 같다** — 커서를 민 뒤의 값이라 tick 응답이 사건 공개의 실시간 창구다 | 400 `VALIDATION_ERROR`(미지원 market); 401 `UNAUTHORIZED`; 409 `PRACTICE_STEP_LOCKED`(attempt 없음·종목 미선택·현재 run 불일치), `PRACTICE_ALREADY_COMPLETED`(완료 replay 쓰기 차단) | 039 TUTORIAL-FLOW-010·011·012, Issue #378; 041 SCENARIO-008·010·013·014·015·016, Issue #472·#488 |
| PUT | `/api/education/practice/attempts/{market}/exit-preset` | Access Bearer 필수. path `market=STOCK|CRYPTO`, `{"preset":"CAUTIOUS"}`(`PracticeAttemptExitPresetUpdateRequest`, `@NotNull ExitPreset`) | 200 `PracticeAttemptResponse`. attempt를 비관 잠근 뒤 현재 실행 세대의 손절·익절 프리셋을 확정한다. **잠금 기준은 "최초 매수 여부"가 아니라 "지금 보유 중인가"다**(042 EXITPRESET-003) — 매수 전과 손절·익절·수동 매도로 포지션을 정리한 뒤(재진입 대기)에는 몇 번이든 바꿀 수 있고, 보유 중에만 막힌다. **이미 확정된 snapshot과 그 snapshot으로 만들어진 예약은 바뀌지 않으며 다음 진입에만 적용된다.** `PUT`인 이유는 자연 멱등이기 때문이며 `Idempotency-Key`를 요구하지 않는다. **(이슈 #502)** 응답의 `tutorialCashBalance`·`tutorialAvailableCash`·`tutorialRealizedPnl`은 같은 트랜잭션에서 읽은 실제 값이다 | 400 `VALIDATION_ERROR`(정의 밖 값·누락·미지원 market); 401 `UNAUTHORIZED`; 409 `PRACTICE_STEP_LOCKED`(attempt 없음·보유 중), `PRACTICE_ALREADY_COMPLETED` | 042 EXITPRESET-003, Issue #477; Issue #502 |
| PUT | `/api/education/practice/attempts/{market}/instrument` | Access Bearer 필수. `{"instrumentId":1}`(`PracticeAttemptInstrumentUpdateRequest`, 양의 `Long`) | 200 `PracticeAttemptResponse`. 최초 선택은 `status=IN_PROGRESS`와 anchor·tutorial date를 저장하고, 같은 종목 재요청은 무변경 응답. **(이슈 #502)** 두 경우 모두 응답의 `tutorialCashBalance`·`tutorialAvailableCash`·`tutorialRealizedPnl`은 같은 트랜잭션에서 읽은 실제 값이다 — 예전에는 항상 `0`이라 화면이 종목 선택 직후 "보유 현금 0원"을 그렸다 | 400 `VALIDATION_ERROR`(누락·0 이하·미지원 market); 401 `UNAUTHORIZED`; 404 `NOT_FOUND`(종목 없음); 409 `INSTRUMENT_NOT_TRADABLE`(다른 시장·실제 종목·`tradable=false`), `PRACTICE_STEP_LOCKED`(attempt 없음·다른 종목 재선택·선택 가능 상태 아님), `PRACTICE_ALREADY_COMPLETED` | 039 TUTORIAL-FLOW-006, Issue #378; Issue #502 |
| GET | `/api/education/practice/attempts/{market}/orders` | Access Bearer 필수. path `market=STOCK|CRYPTO`, body 없음 | 200 `List<OrderListItemResponse>`(주문 도메인의 기존 목록 항목 DTO 재사용, 커서 래퍼 없이 배열 그대로). 인증 사용자 본인의 `market` attempt를 잠금 없이 조회해 현재 run(`practiceAttemptId`·`practiceAttemptRunNumber` 일치)의 주문을 상태(`PENDING`·`FILLED`·`CANCELLED`) 무관 `id` 오름차순으로 반환한다. 이 목록의 모든 행은 `practiceAttemptId`·`practiceAttemptRunNumber`가 non-null이다. attempt가 없으면(이 시장 튜토리얼 미진입) 오류 없이 `[]`를 반환한다. `COMPLETED` attempt도 차단하지 않는다. `GET /api/orders`·`GET /api/orders/pending`의 샌드박스 종목 제외 필터(033 SANDBOX-EXCL)를 우회하는 별도 읽기 경로이며 그 두 엔드포인트의 동작은 바꾸지 않는다 | 400 `VALIDATION_ERROR`(미지원 market); 401 `UNAUTHORIZED` | 043, Issue #435 |

`PracticeAttemptResponse` 필드 순서는 `attemptId`, `market`, `runNumber`, `mode`, `status`, nullable `instrumentId`, nullable `anchorAt`, nullable `tutorialDate`, nullable `riskSnapshot`, nullable `completedAt`, `tutorialCashBalance`, `tutorialAvailableCash`, `tutorialRealizedPnl`, `selectedExitPreset`, `exitPresetLocked`, `availableExitPresets`이다. **(042 EXITPRESET-001·002·003, Issue #477)** `selectedExitPreset`은 현재 실행 세대의 선택값이며 **미선택이면 `null`이 아니라 `"BALANCED"`(기본 프리셋)를 내려보낸다** — 클라이언트가 null 분기를 갖지 않고, 화면에 보이는 값과 실제로 적용될 값이 같아야 하기 때문이다. `exitPresetLocked`는 **현재 보유 중이면 `true`**이고 재진입 대기 중에는 `false`로 돌아온다(완료 attempt는 `mode=REPLAY`로 이미 구분된다). `availableExitPresets`는 `preset`·`stopLossRate`·`takeProfitRate` 세 필드를 가진 고정 3개 배열이며 **비율은 퍼센트 수다**(3%는 `3`). **금액 병기는 클라이언트가 계산한다** — 서버가 계산하려면 "고른 수량"을 먼저 받아야 해서 왕복이 하나 늘고, 초보자를 멈추게 하지 않는 것이 이 기능의 전제다(EXITPRESET-006). 표시 이름(조심스럽게·보통·느긋하게)은 서버가 주지 않는다. 쓰기 가능한 미완료 실행은 `mode=ACTIVE`, 완료 실행은 읽기 전용 `mode=REPLAY`이며 현재 attempt API가 반환하는 상태는 `SELECTING_INSTRUMENT|IN_PROGRESS|COMPLETED`다. 도메인 열거형의 `EXPIRED`는 향후 영속 전이를 위한 예약 값이며 현재 만료는 진행 조회의 4단계 상태로만 계산한다. `riskSnapshot`은 `entryPrice`, `stopLossPrice`, `takeProfitPrice`, `buyTradeId`, `createdAt`과 **(042, Issue #477)** `exitPreset`·`stopLossRate`·`takeProfitRate`·`entrySequence`를 반환하고 seed·generator version은 노출하지 않는다. 비율은 퍼센트 수다(3%는 `3`). `entrySequence`는 그 실행 세대의 몇 번째 진입인지이며 손절 후 재매수하면 2가 된다. **기능 도입 전에 만들어진 스냅샷은 `exit_preset`이 `NULL`인데 기본 프리셋(`BALANCED`)으로 해석해 내려보낸다** — 화면이 "기준을 모르는 진입"을 그리지 않게 하기 위해서다. **(047, TUTORIAL-CASH-ISOL-011)** `tutorialCashBalance`·`tutorialAvailableCash`(`=tutorialCashBalance-예약현금`)·`tutorialRealizedPnl`은 그 시장의 `TutorialAccount`(사용자·시장별 1000만원 초기 현금의 샌드박스 전용 계좌, 실제 `Account`와 별개) 현재 상태를 반환한다. **(이슈 #502)** `PracticeAttemptResponse`를 반환하는 **쓰기 경로 네 곳은 모두 실제 값을 채운다** — `PUT /api/education/practice/attempts/{market}`(진입)·`POST .../restart`(재시작)는 그 트랜잭션에서 이미 조회·리셋한 계좌를, `PUT .../instrument`(종목 선택)·`PUT .../exit-preset`(프리셋 선택)은 attempt를 잠근 같은 트랜잭션에서 계좌를 한 번 더 읽어 싣는다. **세 필드가 `0`으로 내려오는 곳은 `GET /api/education/practice`의 `attempt` 필드 하나뿐이다** — tick과 함께 폴링되는 경로라 호출마다 계좌를 다시 읽지 않는다. 계좌 자체는 진입 시점에 get-or-create되어 항상 존재하므로 그 `0`은 "잔고가 0"이 아니라 "이 응답은 계좌를 조회하지 않았다"는 뜻이며, 잔액이 필요한 화면은 위 네 응답이나 계좌 API를 쓴다.

동시 진입은 MySQL `INSERT IGNORE`와 `UNIQUE(user_id, market)`로 하나의 attempt만 만들며 reload는 run을 증가시키거나 선택을 지우지 않는다. 종목 선택은 attempt 행을 비관 잠근 뒤 요청 시장과 일치하는 `tradable=true`, `is_tutorial_sample=true` 종목만 허용하고 선택 시각을 `anchorAt`, 그 날짜를 `tutorialDate`, 서버 생성 seed와 generator version을 같은 행에 저장한다. **(041 5번, Issue #472) 새로 시작하는 실행의 generator version은 대본이 저작된 시장에서만 2(저작 대본)다 — 현재 CRYPTO 하나뿐이며 STOCK은 대본이 저작되는 SCENARIO-024 전까지 계속 1이다.** 배포 시점에 이미 진행 중이던 버전 1 실행은 재시작을 강제하지 않고 그대로 버전 1로 재현하며, legacy completion만 있는 사용자에게 만들어지는 읽기 전용 replay attempt도 버전 1이다.

재시작 잠금 순서는 `attempt → 현재 run order(id ASC) → account → holding`이다. 조회 범위는 같은 attempt ID와 현재 run으로 제한되어 다른 사용자·시장·과거 run·일반 주문을 취소하거나 순수량에 합산하지 않는다. 순수량과 예약 반환 후 가용 샘플 holding 수량이 정확히 같지 않으면 보상 매도나 run 증가 없이 트랜잭션 전체를 롤백한다. 과거 주문·체결·위험 스냅샷은 감사 원장으로 삭제하지 않는다.

정리 대상 종목은 샌드박스 샘플만 허용한다(`is_tutorial_sample=true` + 요청 시장 일치) — 이 검증이 "재시작 정리가 실제 포트폴리오를 건드리지 못하게 막는" 방어선이다. 그래서 실제 종목을 들고 있는 legacy 완료 replay attempt는 종목을 넘기지 않고 "종목 미선택 재시작"과 같은 분기를 탄다(이슈 #433). 이 분기도 현재 run에 귀속된 주문이 하나라도 있으면 409 `PRACTICE_EVIDENCE_MISSING`으로 막으므로, 정리해야 할 원장이 있는데 조용히 건너뛰는 경우는 없다.

차트의 공개 가상 분은 `max(0, floor((조회시각-anchorAt)/3초))`이며 clock이 anchor 이전으로 돌아가도 분 0으로 고정한다. generator 입력은 영속된 `generatorVersion`, `priceSeed`, `instrumentId`, `runNumber`, `tutorialDate`뿐이라 같은 실행 세대·가상 분은 reload·서버 재기동·다중 인스턴스에서 같은 29+1 OHLC를 재현한다. 현재 일봉은 분 0 가격을 open으로, 공개된 분 가격의 최대·최소를 high·low로, 마지막 공개 분을 close로 쓴다. 샘플 시장가 체결, 일반·교육 지정가 조건 판정과 체결가, holding 관찰, 재시작 보상 SELL은 모두 같은 attempt canonical close를 사용한다. 실제 종목은 기존 `PriceQueryService` 가격 경로를 유지한다.

영속 attempt가 있고 선택된 샘플 종목의 신규 `POST /api/orders` 시장가 주문, `POST /api/orders/limit` 일반 지정가 BUY·SELL, `POST /api/education/practice/limit-orders` 교육 지정가 BUY는 클라이언트 입력이 아니라 서버가 잠근 현재 attempt ID/run number에 귀속한다. attempt가 없으면 기존 샘플 주문처럼 귀속 없이 처리하고, attempt가 존재하지만 종목·상태가 현재 실행과 다르면 409 `PRACTICE_STEP_LOCKED`이며 일반 종목 주문도 귀속 컬럼이 계속 null이다. 현재 실행의 최초 FILLED BUY는 체결 트랜잭션 안에서 `entryPrice`를 scale 8 `HALF_UP`으로 고정하고 `stopLossPrice=entryPrice×0.97`, `takeProfitPrice=entryPrice×1.05`를 같은 방식으로 계산해 `practice_risk_snapshots`에 한 번만 저장한다. 이후 BUY는 기존 스냅샷을 변경하지 않는다. legacy `POST /api/education/practice/intentions`는 유지되지만 이 attempt·주문 귀속·스냅샷 경로의 전제조건이 아니다.

### 실습 진행 조회 (holding 기준)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/education/practice?market= | Access Bearer 필수. `market`(`STOCK`\|`CRYPTO`) 필수 | 200 `InvestmentPracticeResponse` | 400 `VALIDATION_ERROR`(`market` 누락·미지원 값); Access 인증 실패는 401 `UNAUTHORIZED` | 026 MKT-PRACTICE-008, Issue #305; 031 SANDBOX-005·007, Issue #339; 039 TUTORIAL-FLOW-013, Issue #421; 041 SCENARIO-019b·020·021·021a, Issue #488; Issue #503 |

조회는 `market`으로 holding 기반 `tutorialKey`(`STOCK`→`INVESTMENT_PRACTICE_V1`, `CRYPTO`→`COIN_PRACTICE_V1`)를 정하고 쓰기 없이 상태를 계산한다. 영속 attempt가 있으면 현재 attempt ID/run의 risk snapshot과 귀속 주문 원장을 정본으로 쓰며 favorite·intention을 조회하지 않고 legacy completion으로 fallback하지 않는다. attempt가 없는 기존 샘플·실제 종목 사용자만 기존 026 chain으로 fallback한다. OCO 전용 key와 evidence는 조회하거나 합치지 않는다.

샘플 attempt 응답은 4단계다. 1단계는 종목 선택, 2단계는 현재 run 최초 BUY/risk snapshot, 3단계는 snapshot 이후 qualifying 관찰, 4단계는 관찰 이후 같은 run의 5분 내 SELL과 reflection이다. 재시작 전 snapshot·BUY·SELL·관찰은 현재 run evidence에 포함하지 않는다. SELL 뒤 새 관찰도 그대로 저장되고 현재 run evidence로 누적된다(026 spec.md "매도 여부와 무관하게" 원칙을 031이 상속, 이슈 #420). 완료 reflection은 attempt를 먼저 잠근 뒤 completion·legacy progress 호환 행·attempt `COMPLETED`·시장별 500만원 보상을 원자 확정한다.

**(041 6번, Issue #488) attempt 경로 응답에 최상위 3필드(`entries`·`priceAfterSell`·`revealedEvents`)가 더해졌다.** 아래 셋째 항목은 그 중 `entries[]`의 필드 하나를 따로 설명한 것이다.

- `entries` — **진입별 대조 배열**이며 진입 순번 오름차순이다. 각 항목은 `entrySequence`·`exitPreset`·`buyOrderType`·`buyAt`·`buyPrice`·`buyQuantity`·`stopLossPrice`·`takeProfitPrice`·`sellPrice`·`sellQuantity`·`sellAt`·`sellCause`·`realizedPnl`·`unrealizedPnlIfHeld`다. **`realizedPnl`과 `unrealizedPnlIfHeld`는 `sellQuantity` 기준**이라 부분 매도한 진입에서는 `buyQuantity`와 다르다 — 두 금액을 전체 수량의 것으로 읽으면 안 된다. **왜 필요한가** — 042가 재진입을 열면서 한 실행 세대에 매도가 둘 이상 생겼는데 `tradeResult`의 매도 시각·`sellCause`는 **첫 매도** 기준이라, 2막 손절 → 3막 익절한 사용자의 완료 화면에 손절 하나만 뜬다(금액은 맞고 이야기가 틀린다). 이 배열이 그 결함을 닫는다. 진입 안에서는 실행 전체와 같은 규칙(첫 매도)을 진입 범위로 좁혀 쓰고, `sellPrice`는 수량 가중평균·`realizedPnl`은 합이라 부분 매도도 금액이 전부 반영된다. **대본 여부와 무관하게 채운다** — 재진입은 시장을 가리지 않는다. 매수 전이거나 attempt가 없는 legacy 경로는 빈 배열이다. **(이슈 #503)** `buyOrderType`은 그 진입을 **연** 매수의 주문 유형(`MARKET`\|`LIMIT`)이며 진입 경계인 위험 snapshot의 매수 체결이 가리키는 주문에서 읽는다 — **매도의 유형이 아니다**(시장가로 산 포지션을 지정가로 팔거나 예약이 청산할 수 있다). **`buyQuantity`·`buyPrice`와 기준이 다르다** — 그 둘은 진입 구간의 매수를 전부 합산한 값이라, 보유 중 추가 매수(새 진입을 만들지 않고 같은 진입에 합쳐진다)를 다른 유형으로 하면 `buyOrderType`은 첫 매수의 유형만 가리킨다. "이 수량을 어떤 방식으로 샀는가"로 읽으면 안 된다.
- `priceAfterSell` — "그때 팔지 않았다면"의 **기준 가격**이며 `unrealizedPnlIfHeld`가 이 가격으로 계산된다. **진행 중에는 현재 대본가**, **완료 응답에서는 대본의 마지막 진행 구간 끝 가격**이다. 완료 값이 진행과 무관한 이유는 손절 뒤 재매수하지 않고 나간 사용자도 같은 대조를 얻어야 하기 때문이고(SCENARIO-021), 진행 중에 종점 가격을 쓰지 않는 이유는 그것이 이야기의 결말을 미리 알려주기 때문이다. 대본을 쓰지 않는 실행은 `null`이며 그때 `unrealizedPnlIfHeld`도 `null`이다.
- `unrealizedPnlIfHeld` — `(FLOOR(priceAfterSell × 팔린 수량) − FLOOR(그 금액 × 시장 매도수수료율)) − (배분 매수원가 + 배분 매수수수료)`, 원 단위(2단 `FLOOR`). 식·라운딩이 `OrderExecutionService.priceOrder`와 같다. **매도 수수료를 빼는 것이 핵심**이다 — 비교 대상인 `realizedPnl`이 매수·매도 수수료가 모두 반영된 원장 값이라, 빼지 않으면 가상 보유 쪽이 항상 조금 유리해 보인다(SCENARIO-021a). 서버가 계산해 내려보내며 클라이언트는 그리기만 한다.
- `revealedEvents` — 그 실행에서 **공개된** 사건만 공개 순서로 담고 시각을 담지 않는다. 완료 시점에도 미공개 사건은 노출하지 않는다(SCENARIO-020). 형식·이유는 `GET .../chart` 소절과 같다. 대본을 쓰지 않는 실행은 빈 배열이다.

**(이슈 #503) 최상위 `tutorialStageProgress`가 더해졌다.** 튜토리얼 5단계(종목 고르기 → 시장가 매수·매도 → 지정가 매수·매도 → 손절·익절 프리셋 → 되돌아보기) 중 **주문 방법·프리셋 단계를 그 실행에서 실제로 마쳤는지**를 서버가 판정한다. 근거가 셋이 같지 않다 — 왕복 두 개는 체결 원장에서, 프리셋은 그 실행의 선택값에서 나온다. 화면이 스스로 세면 새로고침에 날아가지만 원장은 남는다. **판정 범위는 현재 attempt의 현재 실행 세대**이며 재시작하면 셋 다 `false`로 돌아간다. **`null`이 되지 않는다** — attempt가 없는 경로(legacy chain, 즐겨찾기만, 미착수)와 종목 미선택도 세 값 모두 `false`인 객체로 나간다.

| 필드 | 판정 |
|---|---|
| `marketBuySellCompleted` | 이 실행에 `MARKET` 매수 체결이 있고, **사용자가 낸** `MARKET` 매도 체결도 있다 |
| `limitBuySellCompleted` | 이 실행에 `LIMIT` 매수 체결과 `LIMIT` 매도 체결이 둘 다 있다. **STOCK 실행에서는 영원히 `false`다** — 지정가 주문 경로가 코인 전용이라(`LimitOrderCreationService`가 `market != CRYPTO`를 400으로 거부, `POST .../limit-orders`도 코인만) 주식 튜토리얼에는 이 단계를 통과할 수단이 없다. 프론트가 5단계 진행바를 시장 구분 없이 쓰면 주식에서 막힌다 |
| `exitPresetSelected` | 이 실행에서 프리셋을 **직접 골랐는가**(`PUT .../exit-preset`) |

**손절·익절 예약이 발동시킨 매도는 왕복 판정에서 뺀다.** 그 매도도 원장에는 `MARKET` 주문으로 남지만(`ExitPlanFillService`) 사용자가 낸 주문이 아니라, 세면 프리셋에 청산당하기만 한 사용자가 이 단계를 통과한 것으로 표시된다. 제외 근거는 `exit_plans.triggered_order_id`다. 제외는 **두 주문 유형 모두에 적용된다** — 현재 예약 청산은 `MARKET`으로만 만들어지지만, 나중에 지정가 청산이 생겨도 계약이 그대로 성립하도록 유형을 가리지 않는다. 재시작 보상 매도도 `MARKET`이지만 **직전 실행 세대에 귀속**되므로 애초에 판정 범위 밖이다. **부분 매도도 왕복으로 센다** — 산 수량을 다 팔지 않아도 "사고팔아 봤다"는 성립한다.

**`exitPresetSelected`는 "고른 프리셋으로 진입까지 했는가"가 아니다.** 그렇게 판정하면 두 방향으로 틀리기 때문이다. (1) 고르지 않은 사용자의 진입에도 snapshot에는 기본 프리셋 `BALANCED`가 박히므로(042 EXITPRESET-002), 앞 단계를 기본값으로 마친 사용자가 4단계에서 **세 보기 중 "보통"을 고르는 순간** 재진입 없이 통과한다. (2) 이미 통과한 사용자가 다음 진입을 준비하며 프리셋을 바꾸면(042 EXITPRESET-003이 허용하는 정상 조작) 그 프리셋의 진입이 아직 없어 **통과가 취소되고 화면이 이미 연 단계를 되잠근다.** "골랐는가"만 보면 둘 다 사라지고, 재시작이 `attempt.exit_preset`을 지우므로 실행 안에서 단조롭게 증가한다.

**판정만 하고 강제하지 않는다.** 잘못된 순서의 주문을 409로 거부하는 게이트는 이 계약에 없다 — 사용자가 API를 직접 불러 순서를 건너뛸 수 있으며, 이는 보안이 아니라 학습 순서다.

1. `(userId, tutorialKey)` 완료 행이 있고 attempt가 없거나 attempt가 `COMPLETED`면 `COMPLETED`, `currentStep=null`이며 저장된 `completedAt`을 반환한다. 완료 evidence 일부가 인메모리 재시작으로 유실돼도 완료 상태는 회귀하지 않는다. 완료 행이 있어도 attempt가 040 재시작으로 다시 진행 중이면(`SELECTING_INSTRUMENT`\|`IN_PROGRESS`) 최상위 `status`·`currentStep`·`steps`·evidence는 **그 실행 기준**(`IN_PROGRESS` 또는 4단계 만료 시 `EXPIRED`)이며, `completedAt`·`rewardAmount`는 최초 완료 값을 그대로 유지한다 — `practice_completions` 행은 남아 보상 재지급을 막는다(이슈 #426, 040 비즈니스 규칙).
2. 완료 전 유효 chain이 있으면 1·2단계는 `COMPLETED`, 3단계는 `IN_PROGRESS`다. 같은 holding의 qualifying observation이 있으면 observation evidence도 채운다.
3. 유효 chain은 없고 해당 시장 favorite만 있으면 1단계 `COMPLETED`, 2단계 `IN_PROGRESS`, 3단계 `NOT_STARTED`다.
4. favorite도 없으면 전체 `NOT_STARTED`, `currentStep=1`이다.

`locked`는 직전 단계가 완료되지 않았으면 `true`다. favorite·intention·buyTrade와 observation의 식별자·시각은 각각 한 쌍으로 null/non-null이며 observation은 `evidenceType`까지 함께 null/non-null이다. `holdingId`는 chain이 있을 때 채우고 참조 손절·익절가는 완료 전 계산 가능한 경우에만 채운다.

**`steps` 배열 길이(031, Issue #339, `ai/specs/031-tutorial-sandbox-instruments`)**: 위 1~4번에서 해석·완료된 chain의 종목이 샘플 종목(`is_tutorial_sample=true`)이면 `steps`가 3개가 아니라 **4개**다(실제 종목 chain은 위 계약을 그대로 유지, 항상 3개). 4번째 "매도·복기" 단계는 샘플 종목 chain에만 존재한다.

- 4단계 evidence는 (a) `buyTrade.executedAt` 기준 5분 이내 `FILLED` `SELL` 체결(`POST /api/orders` 재사용, 신규 매도 엔드포인트 없음), (b) 자유 복기 저장(`holding-reflections`, 031에서 샘플 종목 chain에 전제조건 추가) 둘 다 필요하다.
- 4단계 `status`는 (a)·(b) 모두 있으면 `COMPLETED`, (a)만 있으면 `IN_PROGRESS`(복기 대기), 매도가 아직 없고 5분 이내면 신규 상태값 **`AWAITING_SALE`**(`locked=false` — 이 API의 다른 `NOT_STARTED`는 전부 `locked=true`와 짝을 이루므로, 지금 매도해야 하는 이 단계에 `NOT_STARTED`를 재사용하면 프론트엔드가 기존 관례대로 CTA를 숨길 위험이 있어 별도 값을 쓴다), 매도 없이 5분 초과 또는 매도 체결 자체가 `buyTrade.executedAt+5분`을 초과했으면(늦은 매도) 신규 상태값 **`EXPIRED`**다(`status` 필드는 여전히 `String`이라 스키마 변경 없음).
- 만료 판정은 매 요청 시점에 재계산하며 진행 조회의 4단계 `status=EXPIRED`로 명시한다. `PracticeAttemptResponse.status`나 attempt 행은 GET에서 변경하지 않는다. 이미 `practice_completions` 행과 `attempt.status=COMPLETED`가 있으면 만료 여부를 다시 검사하지 않는다 — 완료 replay는 원장 변화와 무관하게 회귀하지 않는다.
- **(041 SCENARIO-014, Issue #472) generator version 2 attempt(= 대본이 저작된 CRYPTO 실행)에서는 `evidence.saleDeadlineAt`이 `null`이고 4단계 `status` 문자열 `"EXPIRED"`가 도달 불가다. STOCK 튜토리얼은 버전 1이라 마감 값과 `EXPIRED` 판정이 그대로 나온다 — 프론트는 시장이 아니라 `saleDeadlineAt`의 null 여부로 분기한다.** 시간 제한이 폐지돼 마감이 존재하지 않으므로 매수 후 아무리 오래 지나도 `AWAITING_SALE`(매도 전) 또는 `IN_PROGRESS`/`COMPLETED`(매도 후)만 나온다. 최상위 `status`도 같은 이유로 `"EXPIRED"`가 되지 않는다. **프론트가 분기하는 것은 열거형이 아니라 이 문자열이므로, `saleDeadlineAt=null`을 "마감 없음"으로 다루고 남은 시간 표시를 숨긴다.** 버전 1 attempt와 legacy chain(attempt 없는 경로)은 기존 마감 값과 `EXPIRED` 판정을 그대로 유지한다.
- 실제 종목 chain의 evidence(1~3단계)에서는 아래 3개 신규 필드가 항상 `null`이다. 샘플 종목 chain도 1~3단계 evidence는 이 3개 필드가 `null`이며, **4단계 evidence에서만** 채워진다.
- **이번 실행 매매 결과 `tradeResult`(신규, 이슈 #421, 039 TUTORIAL-FLOW-013)**: 영속 attempt 경로의 evidence는 `tradeResult` 객체를 함께 반환한다(legacy 026 chain fallback과 빈 evidence에서는 객체 자체가 `null` — 세 수량 필드가 legacy에서 null인 것과 같은 기준이다). `buyPrice`는 현재 run FILLED BUY의 **수량 가중평균 체결가**(scale 8 `HALF_UP`)라 매수가 1건이면 `attempt.riskSnapshot.entryPrice`와 정확히 같고, `sellPrice`는 현재 run FILLED SELL의 같은 방식 가중평균이다. 부분 매도·복수 체결이 가능한 구조이므로 두 값 모두 평균 단가이며 특정 체결 1건의 가격이 아니다.
  - **(042 EXITPRESET-008, Issue #477) `sellCause`** — `STOP_LOSS`\|`TAKE_PROFIT`\|`MANUAL`, 매도 전이면 `null`. 자동 예약(OCO)이 팔았는지 사용자가 직접 팔았는지를 구분한다. **`sellVerdict`로는 알 수 없다** — 자동 예약 체결이면 verdict가 정의상 항상 경계값(`ABOVE_TAKE_PROFIT`·`BELOW_STOP_LOSS`)이 되기 때문이다. 판정 근거는 `exit_plans.triggered_order_id` 역참조 하나뿐이라, 예약이 가리키지 않는 매도는 전부 `MANUAL`이다 — **예약 자체가 없는 STOCK 튜토리얼과 기능 도입 전 실행도 여기 들어간다.** ⚠️ **이 값은 그 실행의 첫 매도 기준이다.** 같은 문단의 `sellPrice`·`realizedPnl`이 실행 전체 집계인 것과 다르므로, 재진입해서 두 번 판 실행(2막 손절 → 3막 익절)에서는 금액은 두 매도의 합인데 원인은 `STOP_LOSS` 하나로 나온다. 진입별 배열이 이 간극을 닫는다(041 SCENARIO-019b, 041 tasks 6번).
  - **수수료 기준을 반드시 이렇게 읽는다.** `realizedPnl`은 현재 run 매도 체결의 `trades.realized_pnl` 합이고 그 원장 값의 정의는 `(매도금액 − 매도수수료) − (배분 매수원가 + 배분 매수수수료)`다(`PortfolioSellService.finalizeSellRealizedPnl`, ORD-005) — **매수·매도 수수료(주식 0.015%, 코인 0.05%)가 모두 차감된 순손익**이다. 반면 `buyPrice`·`sellPrice`는 체결 **단가**라 수수료를 포함하지 않는다. 따라서 `(sellPrice − buyPrice) × 수량`은 `realizedPnl`과 수수료만큼 다르며, **클라이언트가 단가로 손익을 다시 계산하면 서버 원장과 어긋난다** — 금액은 항상 `realizedPnl`을 그대로 쓴다.
  - `returnRate`는 `realizedPnl ÷ (배분 매수원가 + 배분 매수수수료)`, scale 4 `HALF_UP`이다 — **매도 직후 피드백(`GET /api/ai/post-sell-feedback/{tradeId}`)의 `returnRate`와 같은 식·같은 정밀도**라 한 제품 안에서 같은 이름의 수익률이 두 정의를 갖지 않는다. 분모를 구할 수 없으면 0이 아니라 `null`이다("본전"과 "계산 불가"를 구분한다).
  - `sellVerdict`는 매도 체결가가 자동 위험 스냅샷 기준선 대비 어디였는지를 **서버가** 판정한 값이다. `sellPrice ≥ takeProfitPrice`면 `ABOVE_TAKE_PROFIT`, `sellPrice ≤ stopLossPrice`면 `BELOW_STOP_LOSS`, 그 사이면 `BETWEEN_LINES`이며 **양 끝을 포함**한다(기준선에서 정확히 팔린 경우도 판정이 나온다). 손절 -3%·익절 +5%는 서버가 만든 교육용 규칙(TUTORIAL-FLOW-008)이라 경계 규칙을 클라이언트에 복제하지 않는다 — 관찰 evidence(`evidenceType`)를 서버가 판정하는 것과 같은 이유다.
  - 매도 전(`AWAITING_SALE`)에는 `sellPrice`·`realizedPnl`·`returnRate`·`sellVerdict`가 전부 `null`이고 `buyPrice`만 채워진다. 매수 전(1·2단계 진행 중)에는 evidence 자체가 빈 객체라 `tradeResult`도 `null`이다. 완료 후 재조회(`REPLAY`)는 완료 시점과 같은 값을 그대로 반환한다.
- **시장별 최초 완료 보상(신규, 이슈 #343)**: 3단계(실제 종목) 또는 4단계(샘플 종목) 튜토리얼을 시장별로 최초 완료하면 `PracticeHoldingReflectionService.createReflection`이 완료 저장과 같은 트랜잭션에서 해당 시장 계좌(`cash_balance`)에 500만원을 지급한다. `practice_completions`의 `UNIQUE(user_id, tutorial_key)` 불변(026 완료 불변 원칙)에 결합되어 정확히 1회만 지급되며, 주식·코인은 독립이라 각각 최초 완료 시 각각 지급된다(합산 최대 1,000만원). 별도 지급 이력 테이블은 두지 않는다.

| DTO | 필드 순서와 타입 | nullable 규칙 |
|---|---|---|
| `InvestmentPracticeResponse` | `String tutorialKey`, `String status`, `Integer currentStep`, `List<PracticeStepResponse> steps`, `LocalDateTime completedAt`, `Long rewardAmount`, nullable `PracticeAttemptResponse attempt` | attempt 흐름이면 `attempt`가 현재 ID/run/mode/status/instrument/anchor/date/risk snapshot을 반환하고 legacy fallback이면 null. `currentStep`은 완료 시 null, `rewardAmount`는 `practice_completions` 행이 있으면 `5000000`이고 040 재시작으로 다시 진행 중이어도 유지된다(이슈 #426) |
| `PracticeStepResponse` | `Integer step`, `String status`, `Boolean locked`, `PracticeEvidenceResponse evidence` | 모두 non-null; locked 단계도 빈 evidence 객체 반환. `status`는 샘플 종목 chain의 4단계에서만 `AWAITING_SALE`·`EXPIRED`일 수 있다(둘 다 `locked=false`) |
| `PracticeEvidenceResponse` | 기존 favorite/intention/BUY/holding/risk/observation/reflection/SELL/deadline 필드 + nullable `BigDecimal buyQuantity`, `BigDecimal sellQuantity`, `BigDecimal remainingQuantity`, nullable `PracticeTradeResultResponse tradeResult` | attempt 흐름의 BUY 이후에는 `favoriteId|favoriteCreatedAt|intentionId|intentionCreatedAt=null`, 자동 snapshot risk line과 current-run `buyQuantity=FILLED BUY 합`, `sellQuantity=FILLED SELL 합`, `remainingQuantity=max(BUY 합-SELL 합,0)`을 반환한다. 완료 `REPLAY`도 BUY/SELL ID·시각, 세 수량, risk line, `tradeResult`를 유지한다. legacy fallback의 신규 수량과 `tradeResult`는 null |
| `PracticeTradeResultResponse` | `BigDecimal buyPrice`, `BigDecimal sellPrice`, `Long realizedPnl`, `BigDecimal returnRate`, `String sellVerdict` | 매도 전에는 `buyPrice`만 non-null이고 나머지 넷은 null. `sellVerdict`는 `ABOVE_TAKE_PROFIT|BELOW_STOP_LOSS|BETWEEN_LINES` |

