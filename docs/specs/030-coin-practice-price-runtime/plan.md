# Plan: 코인 튜토리얼 가상 가격 실행 환경

## 관련 문서

- Spec: `./spec.md`
- 상속: `../020-coin-practice-tutorial`, `../026-market-order-practice-tutorial`, `../015-limit-order`
- 관련 ADR: ADR-0002, ADR-0003, ADR-0004, ADR-0012

새 ADR은 필요 없다. ADR-0012는 즐겨찾기·사전 의도의 유실 가능한 인메모리 선택만 소유하며, 이 기능의 가격 cursor와 주문 귀속은 재기동 정합성이 수용 기준이므로 ADR-0004에 따라 DB로 영속화한다.

## 도메인·패키지 경계

- 가격 세션 엔티티·Repository·결정적 생성기·API와 next-tick orchestration은 `education` 도메인 하위(예: `com.finplay.api.education.priceruntime`)가 소유한다. `030`의 구현을 `order`나 `market` 하위에 두지 않는다.
- `education`은 `order`의 공개 service를 통해 주문 생성·세션별 잠금·체결·취소를 요청하며 `OrderRepository`·`AccountRepository`·`HoldingRepository`를 직접 주입하지 않는다. 필요한 session-scoped 유스케이스는 `order` service가 제공한다.
- `order`는 nullable scalar `practicePriceSessionId`와 기존 주문·예약·체결 규칙만 소유한다. `practice_price_sessions` 엔티티·Repository·`education` service에 의존하지 않으며, FK도 JPA 연관관계가 아니라 scalar 필드로 매핑한다.
- `market`의 `PriceStore`·`CryptoPriceUpdatedEvent`는 기존 실제 시세 경계로 유지한다. 교육 가격 생성·이벤트는 `education` 내부에서 끝나며 `market` 저장소를 호출하지 않는다.

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | `/api/education/practice/price-sessions` | `PracticePriceSessionCreateRequest` | 201 `PracticePriceSessionResponse` | 코인 가격 세션 생성 |
| GET | `/api/education/practice/price-sessions/{sessionId}` | path | 200 `PracticePriceSessionResponse` | 본인 세션 현재 상태 조회 |
| POST | `/api/education/practice/price-sessions/{sessionId}/ticks` | `PracticePriceTickAdvanceRequest` | 200 `PracticePriceSessionResponse` | 기대 tick으로 한 칸 진행 |
| POST | `/api/education/practice/limit-orders` | `PracticeLimitOrderCreateRequest` | 201 기존 `OrderResponse` | 세션 귀속 코인 지정가 BUY 생성(side는 서버 고정) |

모두 Access Bearer 인증이 필요하다. 생성 요청은 `{"instrumentId":1}`, tick 요청은 `{"expectedTick":1}`, 주문 요청은 `{"practicePriceSessionId":1,"instrumentId":1,"quantity":0.01,"limitPrice":9500}`다. 교육 주문의 side는 입력받지 않고 서버가 `BUY`로 고정한다. 응답 세션 필드는 `sessionId`, `instrumentId`, `status(ACTIVE|COMPLETED)`, `generatorVersion`, `startPrice`, `currentTick`, `currentPrice`, `tickSeconds(3)`, `totalTicks(100)`, `createdAt`, `completedAt(nullable)`다. seed는 서버 내부 재현 정보이며 API에 노출하지 않는다.

## 입력·오류 계약

| 조건 | HTTP / 코드 |
|---|---|
| 필수값 누락, ID·expectedTick 0 이하, 수량·가격 정밀도 위반 | 400 `VALIDATION_ERROR` |
| 세션·종목 없음 또는 타인 세션(존재 은닉) | 404 `NOT_FOUND` |
| 주식·거래 불가 종목 | 409 `INSTRUMENT_NOT_TRADABLE` |
| 동일 사용자·종목 ACTIVE 세션 존재 | 409 `PRACTICE_PRICE_SESSION_ALREADY_ACTIVE` |
| COMPLETED 세션 진행·주문 생성 | 409 `PRACTICE_PRICE_SESSION_CLOSED` |
| `expectedTick != currentTick+1` 또는 99 초과 | 409 `PRACTICE_PRICE_TICK_CONFLICT` |
| 주문의 사용자·종목이 세션과 불일치 | 409 `PRACTICE_PRICE_SESSION_MISMATCH` |
| 같은 세션에 PENDING 교육 주문이 이미 존재 | 409 `PRACTICE_LIMIT_ORDER_ALREADY_PENDING` |
| 현금 부족 등 기존 지정가 주문 규칙 위반 | 기존 order 오류 계약 재사용 |

동시 create의 unique 충돌도 `PRACTICE_PRICE_SESSION_ALREADY_ACTIVE`로 매핑한다. `Idempotency-Key`는 도입하지 않는다. next-tick의 optimistic precondition인 `expectedTick`과 세션 비관 잠금이 재시도 안전성을 제공한다.

## 데이터 모델

- `practice_price_sessions`: `id BIGINT PK`, `user_id BIGINT FK NOT NULL`, `instrument_id BIGINT FK NOT NULL`, `status VARCHAR(16) NOT NULL`, `seed BIGINT NOT NULL`, `generator_version SMALLINT NOT NULL`, `start_price DECIMAL(18,8) NOT NULL`, `current_tick SMALLINT NOT NULL`, `current_price DECIMAL(18,8) NOT NULL`, `created_at DATETIME(6) NOT NULL`, `completed_at DATETIME(6) NULL`.
- ACTIVE 하나 제약은 MySQL generated column `active_slot = CASE WHEN status='ACTIVE' THEN 1 ELSE NULL END`과 `UNIQUE(user_id,instrument_id,active_slot)`로 DB에서도 보장한다. CHECK로 status, tick `0..99`, 양수 가격을 검증한다.
- `orders.practice_price_session_id BIGINT NULL FK`: null은 기존 실제 가격 주문, non-null은 교육 세션 주문이다. 기존 행은 null이며 백필하지 않는다. `market=CRYPTO`, `type=LIMIT`, `side=BUY` 제약은 서비스가 검증하고 통합 테스트로 보강한다.
- 새 migration 번호는 구현 착수 직전 `origin/dev` 최신 번호를 재확인하여 추가하며 머지된 migration은 수정하지 않는다.

## 생성기·가격 anchor

- 생성 트랜잭션은 종목을 검증한 뒤 기존 실제 `PriceQueryService`의 유효 가격을 한 번 읽는다. `PRICE_UNAVAILABLE`이면 실패시키지 않고 정확히 `10000.00000000`을 anchor로 쓴다.
- seed는 서버 CSPRNG로 생성한다. `generatorVersion=1`은 기존 표시 API 구현을 호출하지 않는 별도 순수 생성기다. 각 tick `n(1..99)`은 `seed`의 signed 8-byte big-endian 표현과 `n`의 signed 4-byte big-endian 표현을 이어 SHA-256으로 digest하고, 앞 8바이트를 unsigned 64-bit 정수로 해석한 뒤 `units = (value mod 20001) - 10000`으로 계산한다. 변동률은 `units / 1,000,000`(정확히 -1%..+1%)이며 새 가격은 `previousPrice * (1 + rate)`를 scale 8 `HALF_UP`으로 반올림한다. 결과가 `startPrice * 0.5`의 scale 8 `HALF_UP` 값보다 작으면 그 하한으로 clamp한다. 이 byte encoding·digest·modulo·반올림·하한은 v1 계약이며 변경하지 않는다.
- DB에는 현재 위치만 저장하고 전체 100개 배열은 저장하지 않는다. 재기동 시 version·seed·startPrice로 필요한 tick까지 재생성하며 저장된 `currentPrice`와 일치하지 않으면 내부 오류로 중단한다.

## 트랜잭션·잠금·이벤트

- 공통 잠금 순서는 `practice_price_session → 모든 대상 order(id ASC) → account → holding`이다. 교육 주문 생성은 아직 order 행이 없으므로 `session → account → order insert`를 따른다. tick은 해당 session의 PENDING 주문 전체(현재 가격에 미도달한 주문과 tick 99 취소 후보 포함)를 ID 오름차순 단일 `FOR UPDATE` 조회로 먼저 모두 잠근 뒤에만 체결·취소 과정에서 account와 holding을 잠근다. 기존 `LimitOrderFillService`는 이미 잠근 order를 재조회하되 새 order 락을 추가로 획득하지 않아야 한다. 일부 order 처리 후 다음 order를 잠그는 순차 방식은 일반 단건 취소와 `order ↔ account` 교착을 만들 수 있으므로 금지한다.
- next-tick 서비스는 세션을 `SELECT ... FOR UPDATE`로 잠그고 owner·ACTIVE·expectedTick을 검증한 뒤 새 가격/cursor를 저장한다. 같은 트랜잭션에서 sessionId가 포함된 education 전용 이벤트를 발행하고, 일반 동기 `@EventListener`는 발행 호출이 반환되기 전에 해당 sessionId의 PENDING 주문만 잠가 기존 체결 서비스를 호출한다. 리스너는 예외를 삼키지 않아 tick 트랜잭션 전체를 롤백시킨다.
- `@TransactionalEventListener(BEFORE_COMMIT)`은 사용하지 않는다. 이 단계는 서비스 본문이 끝난 뒤 실행되어 tick 99 잔여 주문 취소보다 체결이 늦어질 수 있기 때문이다. 발행 즉시 동기 처리로 tick 가격 저장 → 체결 → 마지막 취소 → 세션 완료를 하나의 원자 단위로 만들며, 일반 `CryptoPriceUpdatedEvent` listener와 타입을 공유하지 않는다.
- tick 99에서는 먼저 해당 가격으로 체결 조건을 판정한다. 이후 남은 PENDING 세션 주문을 order ID 오름차순으로 잠가 취소·예약 현금 반환하고 세션을 `COMPLETED`로 전이한다. 체결된 주문은 취소하지 않으며, rollback이면 tick·체결·취소·반환이 모두 롤백된다.
- 주문 생성은 세션 잠금을 먼저 잡고 ACTIVE/owner/instrument와 PENDING 주문 부재를 검증한 뒤 기존 계좌 예약 서비스를 호출한다. 세션 잠금이 PENDING 1건 상한의 동시 생성도 직렬화한다. next와 주문 생성 경합 역시 세션 잠금에서 직렬화되어 종료 뒤 PENDING 주문이 생기지 않는다.

## holding 관찰 연결

`holdingId → MarketPracticeChainResolutionService가 선택한 buyTrade → order.practicePriceSessionId` 순서로 서버가 역추적한다. sessionId가 있으면 owner·instrument 일치를 다시 검증하고 해당 세션 `currentPrice`를 관찰 가격으로 쓴다. sessionId가 null이면 기존 `PriceQueryService.getPrice`를 사용한다. COMPLETED 세션도 마지막 가격 조회는 허용하므로 마지막 tick 체결 holding을 복기할 수 있다. 클라이언트 입력으로 sessionId를 받지 않는다.

## 테스트 계획

- 단위: version 1 결정성·±1%·scale·100틱, fallback anchor, expectedTick·상태 전이, 세션별 주문 필터, 관찰 가격원 분기.
- JPA: ACTIVE unique, 세션 비관 잠금, sessionId별 PENDING 조회, nullable 주문 FK와 기존 행 호환.
- MVC: 4개 API 인증·validation·소유권 은닉·오류 매핑과 JSON 필드.
- 통합: 실제 가격 anchor와 fallback 각각 생성 후 재기동 context에서 동일 tick 재현; tick이 같은 세션 주문만 체결하고 일반/타 사용자/타 세션 주문은 불변; 세션당 PENDING 1건 상한의 순차·동시 생성; tick 99 체결 우선 후 미체결 취소·예약 정확히 한 번 반환; next-vs-next, next-vs-order 생성, tick-vs-일반 단건 취소 경합에서 교착·부분 커밋 없음.
- 회귀: 기존 `/synthetic-prices` 응답과 `/api/orders/limit`, `PriceStore`, `CryptoPriceUpdatedEvent`, 세션 없는 holding 관찰 동작 불변.

## 후속 이슈 순서

1. 세션 엔티티·migration·결정적 생성기와 생성/조회/next API.
2. 교육 지정가 API·주문 session FK·전용 이벤트 체결·종료 취소.
3. holding 관찰의 trade/order 세션 역추적 가격원 연결.
4. 앞의 production 구현 3개가 모두 dev에 병합된 뒤 #313을 재개한다. 030 구현 이슈는 세션·주문·관찰 구성요소별 계약·격리·경합 테스트를 소유하고, #313은 즐겨찾기 → 사전 의도 → 교육 지정가 → tick 체결 → holding 관찰 → 복기·완료를 실제 API로 연결한 전체 흐름과 서버 재기동 후 이어하기만 소유한다. `026/tasks.md`의 미완료 전체 흐름 항목은 #313에서 닫으며 030 구현 이슈에 중복 작성하지 않는다.

각 production 이슈는 자신이 구현한 controller의 요청·응답·오류를 이 문서와 `docs/api-contracts.md`의 030 계획 표에 대조한다. 구현과 계약이 다르면 코드를 임의로 맞추지 않고 먼저 spec 변경을 확정한다. 일치하면 같은 커밋에서 `docs/api-routes.md`의 해당 행을 계획 표에서 실제 라우트 표로 옮기고 `docs/api-contracts.md`의 해당 계약에서 “계획” 상태를 제거한다. 계획 4개가 한 번에 모두 구현된 것처럼 일괄 이동하지 않는다.
