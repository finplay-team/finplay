# Spec: 코인 지정가 매매 — 생성·체결·취소·미체결 목록 조회 (LMT-001~004)

## 배경

- 이슈: #210 `[1차 고도화][매매] 코인 지정가 매매 구현`(LMT-001~002), #218 `[1차 고도화][매매] 지정가 주문 취소`(LMT-003), #224(시장가 매수 경로에 계좌·holdings 비관적 락 보강), #235 `[1차 고도화][매매] 코인 지정가 미체결 주문 목록 조회 구현`(LMT-004), #239 `[1차 고도화][매매] 코인 지정가 주문 수정 API 구현`(LMT-005)
- 선행: #138(완료, PR #209 — `docs/prd.md`에 지정가·알림 정책 반입), #210(완료, PR #215 — LMT-001~002), #218(완료, PR #220 — LMT-003), #224(완료 — 시장가 매수 경로 락 보강), #235(완료, PR #237 — LMT-004), #238(완료, 머지됨 — `docs/prd.md`에 LMT-005 정책 확정)
- PRD 근거: `docs/prd.md` 591~649행 (§4 "지정가 주문·체결" 중 LMT-001~005 + "계좌·보유 조회 계약 영향(Decision Gate)"), §3 구현 현황 213행
- 이 spec 폴더는 PRD 631행이 예약해둔 자리다. LMT-001(생성)·LMT-002(체결 트리거)는 PR #215로, LMT-003(취소)은 PR #220으로, LMT-004(미체결 목록 조회)는 PR #237로 완료됐다. 이슈 #224 — 시장가 매수 경로(계좌·holdings)에 비관적 락을 보강하는 작업도 완료됐다(아래 "알려진 한계" 절 참고). **이번 절은 이슈 #239 — 지정가 주문 수정(LMT-005)을 이 spec 폴더에 이어 붙인다.** 정책은 PR #238로 `docs/prd.md`에 이미 확정돼 있고(코인 전용, `PATCH /api/orders/{orderId}`, 부분 갱신 허용, 원자성, 이력 미보관), 이 절에서 다시 확정하는 것은 "수정 이력 미보관" 트레이드오프 하나뿐이다(아래 "확정된 설계 결정" 12번).
- 대상은 **코인(CRYPTO) 전용**이다. 주식 지정가는 PRD 593행의 사유(재생 데이터 기반이라 "이 가격 도달 시" 조건이 성립하기 어려움)로 이번 범위에서 제외한다.

## 목적

`POST /api/orders`(시장가 전용)와 별개로 `POST /api/orders/limit`을 신설해 코인 매수·매도 주문에 목표가를 지정할 수 있게 하고, 빗썸 웹소켓 시세 갱신 시 상시(이벤트 드리븐)로 체결 조건을 평가해 조건 충족 시 목표가로 고정 체결한다.

## 범위

**포함**

- `POST /api/orders/limit` — 코인 매수·매도 지정가 주문 생성, `PENDING` 상태로 대기 (LMT-001)
- 매수는 현금(수량 × 지정가 + 예상 수수료), 매도는 수량을 예약(에스크로)하는 원장 도입
- 빗썸 웹소켓 가격 갱신을 트리거로 하는 상시 체결 처리 (LMT-002)
- 동시 체결 경합 제어를 위한 비관적 락(`SELECT ... FOR UPDATE`) 도입 — 이 기능은 주문 원장 전체에 처음 도입되는 비관적 락이다(아래 "알려진 한계·확정된 설계 결정" 참고)
- 기존 시장가 매도 체결(`OrderExecutionService`)의 락 순서를 이번에 정한 전역 순서(account가 holding보다 항상 먼저)에 맞추는 조정
- `DELETE /api/orders/{orderId}` — 본인 소유 `PENDING` 지정가 주문을 취소하고 예약 현금(매수)·예약 수량(매도)을 반환 (LMT-003, 이슈 #218)
- `GET /api/orders/pending?market=&cursor=&limit=` — 본인 소유의 `PENDING` 상태 지정가 주문만 최신순 커서 페이지네이션으로 조회 (LMT-004, 이슈 #235)
- `AccountSummaryResponse`에 `reservedCash`, `HoldingListItemResponse`에 `reservedQuantity` 필드를 추가해 예약분을 노출 — "계좌·보유 조회 계약 영향(Decision Gate)" 해소 (이슈 #235)
- `PATCH /api/orders/{orderId}` — 본인 소유 `PENDING` 코인 지정가 주문의 `limitPrice`·`quantity`를 부분 갱신, 예약 재계산을 원자적으로 처리 (LMT-005, 이슈 #239)

**제외 (이슈 원문 그대로)**

- 주식 지정가 — 재생 데이터 기반이라 "이 가격 도달 시" 조건 자체가 성립하기 어려워 추후 별도로 다룬다
- 주식 지정가 취소·수정, 시장가 주문 취소·수정 — 취소(LMT-003)·수정(LMT-005) 모두 코인 지정가(`orderType=LIMIT`) 전용이다(시장가는 생성 즉시 `FILLED`라 애초에 `PENDING` 상태를 갖지 않으므로 취소·수정 대상이 될 수 없다 — 별도 분기 없이 상태 검증만으로 자연히 배제된다)
- ~~주문 수정(가격·수량 변경) — 취소 후 재생성으로 대체한다. 필요성이 확정되면 별도 이슈로 다룬다(이슈 #235 원문)~~ → **완료(이 spec, LMT-005, 이슈 #239)**. PR #238이 필요성을 확정해 `docs/prd.md`에 정책을 반입했고, 아래 "LMT-005 지정가 주문 수정" 절에서 요구사항을 확정한다.
- 지정가 체결 알림(NOTI-001~005) — LMT-002 체결 트리거가 선행되어야 하므로 별도 이슈
- 부분체결·슬리피지 허용 — 전량 목표가 체결만 다룬다
- 외부 메시지 큐(Redis/Kafka) 도입 — 인메모리 `ApplicationEvent`로 처리하고 리스너 경계만 열어둔다
- 수정 이력 저장·조회 — 아래 "확정된 설계 결정" 12번에서 재확정(미보관 유지)

## 알려진 한계 (해소됨)

이 절이 기록해두었던 두 gap은 모두 이슈 #224로 닫혔다(아래 "시장가 매수 경로 락 보강 (이슈 #224)" 절 참고).

- ~~시장가 매수(BUY) 경로에는 계좌 비관적 락이 없다~~ → 해소됨. `OrderExecutionService.createBuyOrder`가 기존 SELL 경로가 쓰던 `getAccountForUpdateFor`(=`accountService.getAccountForUpdate`)를 재사용해 계좌를 먼저 잠근다.
- ~~BUY 체결 경로는 holdings row를 잠그지 않는다~~ → 해소됨. `PortfolioBuyService.applyBuyTrade`(시장가·지정가 매수 체결 공통 호출부)가 `HoldingRepository.findByAccountIdAndInstrumentIdForUpdate`(SELL 경로가 이미 쓰던 락 쿼리)를 재사용해 holdings row를 잠근 뒤 갱신한다.

## 요구사항

### LMT-001 지정가 주문 생성 (코인 전용)

- `POST /api/orders/limit`로 매수·매도 주문에 목표가(지정가)를 지정해 생성한다.
- `POST /api/orders`(시장가 전용)의 `orderType="LIMIT"` 422 `UNSUPPORTED_ORDER_TYPE` 거부는 그대로 유지한다 — 두 경로가 영구히 공존한다.
- 즉시 체결되지 않고 `PENDING` 상태로 대기한다(유효기간 없음, GTC).
- 매수는 `수량 × 지정가 + 예상 수수료`만큼 현금을, 매도는 수량을 예약(에스크로)한다.
- 코인 지정가 SELL은 holding 예약 원장을 사용해 `availableQuantity = totalQuantity - reservedQuantity`를 검증·차감하고, **기존 시장가 SELL도 이 원장을 반영해 예약된 수량을 중복 매도할 수 없다.**
- 예약 가능한 현금·수량이 부족하면 거부한다(오류 원칙은 시장가 ORD-003의 현금·수량 부족과 동일하게 재사용: 409 `INSUFFICIENT_CASH`/`INSUFFICIENT_QTY`).
- 매수 지정가가 현재가 이상이거나 매도 지정가가 현재가 이하로 즉시 체결 조건을 충족하는 주문도 생성 시점에 거부하지 않는다 — 다음 가격 갱신 트리거에서 그대로 체결된다.

### LMT-002 지정가 체결 트리거 (코인 전용)

- 체결은 배치가 아니라 상시 처리(이벤트 드리븐)다. 트리거는 빗썸 웹소켓 수신으로 내부 가격 모델(`PriceStore`)이 갱신되는 시점이다.
- 체결 판정: 매수 `현재가 ≤ 지정가`, 매도 `현재가 ≥ 지정가`.
- 목표가 도달 시 체결가는 목표가로 고정한다(슬리피지 없음).
- 체결 시 예약을 실제 현금·보유수량 이동으로 확정하고 `status`를 `FILLED`로 변경한다.
- 동시 체결 경합(같은 주문에 가격 갱신 이벤트가 겹쳐 도착)은 비관적 락으로 제어해 중복 체결을 막는다.
- 잠금 순서는 `order → account → holding`으로 고정한다(holding 행이 없으면 `order → account`). **order 락을 가장 먼저 잡는다** — 이는 "같은 주문에 대한 중복 이벤트(그리고 후속 이슈의 취소 요청)"만 배제하는 목적이라 그 주문 자신의 row 외에는 아무와도 경쟁하지 않는다. 여러 트랜잭션이 실제로 경합하는 자원은 **account·holding**이며, 이 둘의 순서는 어떤 흐름에서도 항상 account가 먼저다(2026-08-05 확정, plan.md "잠금 순서 요약" 참고 — 아래 "확정된 설계 결정" 7번).
- 큐는 별도 메시지 큐 없이 인메모리 Spring `ApplicationEvent`로 처리하며, `AFTER_COMMIT`이 아닌 일반 리스너가 즉시 받는다(가격 수신이 DB 트랜잭션이 아니므로 커밋 대기 대상이 없음).

### LMT-003 지정가 주문 취소 (코인 전용)

- `DELETE /api/orders/{orderId}`로 본인 소유의 `PENDING` 지정가 주문을 취소한다.
- 성공 시 204, 본문 없음(기존 `DELETE /api/community/posts/{postId}`·`DELETE /api/community/comments/{commentId}` 컨벤션과 동일, 2026-08-05 사용자 확인).
- 취소 시 매수는 예약 현금(`reservedCash`)을, 매도는 예약 수량(`holding.reservedQuantity`)을 반환한다 — 실제 `cashBalance`·`quantity`는 건드리지 않는다(애초에 체결되지 않았으므로).
- 검증 순서는 **존재(404) → 소유(403) → 상태(409)**로 고정한다(기존 investment journal 엔드포인트 패턴 재사용, `docs/api-contracts.md` 439행 "검증 순서는 존재(404) → 소유(403) → ... → 중복(409)로 고정" 참고. `DELETE /api/exit-plans/{exitPlanId}`의 404 통일 방식(`docs/api-contracts.md` 569행)은 아직 미구현 candidate 9라 따르지 않는다 — 실제 구현된 코드 컨벤션을 우선한다).
  - 대상 `orderId`가 없으면 404 `NOT_FOUND`(기존 order 도메인이 이미 쓰는 공용 코드 재사용 — LMT-001 plan.md의 instrumentId·계좌 404와 동일 원칙).
  - 존재하지만 본인 소유가 아니면(요청자 ≠ 주문의 `user`) 403 `FORBIDDEN`(공용 코드 재사용, journal 403과 동일 원칙 — 소유하지 않은 주문의 상태·시장가/지정가 여부를 오류 코드로 흘리지 않는다).
  - 본인 소유이지만 `status`가 `PENDING`이 아니면 상태별로 나눠 거부한다(2026-08-05 사용자 확인 — 신규 코드 2개): 이미 `FILLED`면 409 `ORDER_ALREADY_FILLED`, 이미 `CANCELLED`면 409 `ORDER_ALREADY_CANCELLED`. 시장가 주문(`orderType=MARKET`)은 생성 즉시 `FILLED`이므로 취소 시도 시 `ORDER_ALREADY_FILLED`로 자연히 걸러진다(별도 `orderType` 검증 불필요).
- 취소 판정·잠금 순서는 LMT-002 체결과 동일한 `order → account → holding`을 그대로 재사용한다(SELL만 holding까지, BUY는 order → account) — "확정된 설계 결정" 7번에서 이미 이 순서를 LMT-003이 따르도록 확정해뒀다(재논의하지 않는다).
- 체결 트리거(LMT-002)와 취소 요청이 동시에 도착해도 안전하다: 두 흐름 모두 order를 가장 먼저 잠그므로, 먼저 락을 획득한 쪽이 끝까지 처리되고 나중 쪽은 락 대기 후 `status != PENDING`을 보고 자기 작업을 거부/no-op한다(체결이 먼저면 취소는 409 `ORDER_ALREADY_FILLED`, 취소가 먼저면 체결 리스너는 `fillIfPending` 내부에서 `status != PENDING`으로 조용히 반환).

### 시장가 매수 경로 락 보강 (이슈 #224)

- 배경: PR #215(LMT-001~002)가 order→account→holding 비관적 락을 처음 도입하면서 기존 **시장가 매도** 경로도 account를 먼저 잠그도록 조정했지만, 시장가 **매수** 경로는 그때 범위 밖이라 손대지 않았다(위 "알려진 한계" 참고, 이제 해소됨). LMT 번호 체계(코인 지정가 전용)와 달리 이 항목은 시장가·지정가 매수 체결 공통 경로를 다루므로 별도 LMT 번호를 붙이지 않는다.
- `OrderExecutionService.createBuyOrder`(시장가 매수, HTTP 스레드)가 계좌를 비관적 락으로 잠근 뒤 현금을 확인·차감한다 — 기존 매도 경로가 이미 따르는 "account가 holding보다 항상 먼저" 전역 순서와 동일하게 맞춘다.
- `PortfolioBuyService.applyBuyTrade`(시장가·지정가 매수 체결 공통 — `OrderExecutionService.createBuyOrder`와 `LimitOrderFillService.fillBuy` 양쪽에서 호출)가 holdings row를 비관적 락으로 잠근 뒤 수량·평균단가를 갱신한다. 이 메서드 한 곳만 고치면 두 호출부가 동시에 보호된다.
- 신규 종목 첫 매수(holding row가 아직 없는 경우)의 동시 생성 경합은 holdings 락이 아니라 **account 락만으로 방지한다**("확정된 설계 결정" 10번, 재논의하지 않는다) — account 락을 먼저 잡으면 같은 계좌를 건드리는 모든 매수 경로가 자동으로 직렬화되므로 `holdings.uk_holdings_account_instrument` 유니크 제약 위반에 대한 방어적 catch·재조회 로직은 추가하지 않는다.

**시장가 매수 경로 락 보강 완료 조건 (이슈 #224)**

- [x] `OrderExecutionService.createBuyOrder`가 계좌를 비관적 락(`getAccountForUpdateFor`/`accountService.getAccountForUpdate` 재사용)으로 잠근 뒤 현금을 확인·차감한다.
- [x] `PortfolioBuyService.applyBuyTrade`가 holdings row를 비관적 락(`HoldingRepository.findByAccountIdAndInstrumentIdForUpdate` 재사용)으로 잠근 뒤 수량·평균단가를 갱신한다 — 시장가 매수 체결과 지정가 매수 체결(`LimitOrderFillService.fillBuy`) 양쪽 호출부 모두 이 변경 하나로 보호된다.
- [x] 지정가 매수 체결(가격 피드 스레드)·시장가 매수(HTTP 스레드)가 동시에 같은 계좌·holding을 대상으로 실행돼도 lost update 없이 안전하게 직렬화됨을 통합 테스트로 증명한다(경합 재현 테스트 포함, 시나리오 13·14).
- [x] 전역 잠금 순서(account가 holding보다 항상 먼저)가 시장가 매수 경로에도 일관되게 적용되어, 기존 매도·지정가 체결 경로와 반대 순서로 잠그는 ABBA 데드락 회귀가 없다(시나리오 15).
- [x] 신규 종목 첫 매수 동시 생성 경합이 방어적 catch·재조회 로직 없이 account 락만으로 방지됨을 재확인한다("확정된 설계 결정" 10번, 기존 LMT-002 BUY 체결 전제와 동일).
- [x] `./gradlew build` 통과.

**추가로 발견·수정한 결함(계획 범위 밖, 시나리오 13 테스트 작성 중 재현)**: 계좌 락만으로는 "매수 합산 소비액이 잔액을 초과할 수 없다"는 불변식이 지켜지지 않았다 — `OrderExecutionService.createBuyOrder`의 현금 부족 검증이 `cashBalance`만 보고 `reservedCash`(지정가 매수 예약분)를 무시했기 때문이다(지정가 생성 경로는 이미 `getAvailableCash()` 기준). 검증식을 `getAvailableCash()` 기준으로 통일해 락과 검증 기준을 일치시켰다.

### LMT-004 미체결 주문 목록 조회 (코인 전용, 이슈 #235)

- `GET /api/orders/pending?market=&cursor=&limit=`로 본인 소유의 `PENDING` 상태 지정가 주문만 조회한다.
- `market`은 `PORT-002`(거래내역 조회) 규칙과 동일하게 필수 파라미터다. `market`으로 조회한 계좌가 존재하지 않거나 타인 소유면 조회할 수 없다.
- 다른 사용자의 미체결 주문은 조회할 수 없다 — 본인 계좌에 속한 주문만 대상이다.
- 커서 기반 페이지네이션을 사용하며 최신순으로 정렬한다.
- 이 목록은 취소(LMT-003)·체결(LMT-002)로 상태가 바뀐 주문은 더 이상 포함하지 않는다 — 조회 시점의 `status`를 그대로 반영하는 실시간 목록이다(별도 스냅샷·캐시 없음).

### LMT-005 지정가 주문 수정 (코인 전용, 이슈 #239)

- `PATCH /api/orders/{orderId}`로 본인 소유의 `PENDING` 코인 지정가 주문의 `limitPrice`·`quantity`를 변경한다. 둘 다 보낼 수도, 한쪽만 보내는 부분 갱신도 허용한다(둘 다 생략하면 400 `VALIDATION_ERROR`).
- 같은 `orderId`의 `orders` 행을 갱신하며 새 주문을 만들지 않는다. `requestedAt`도 유지되어 `GET /api/orders/pending`(LMT-004)에서 주문의 정렬 위치가 바뀌지 않는다.
- **원자성**: 예약 재계산이 실패하면(현금·수량 부족) 원주문·계좌·보유를 변경 전 상태 그대로 유지한다 — 취소 후 재생성 두 번 호출로 흉내 낼 때 취소만 성공하고 재생성이 실패해 주문이 사라지는 문제를 막는 것이 이 요구사항의 존재 이유다. 한 트랜잭션(하나의 `@Transactional` 메서드) 안에서 해제→재예약을 수행하고, 재예약이 실패해 예외가 전파되면 트랜잭션이 롤백되어 이미 수행한 해제도 함께 취소된다(엔티티 변경은 커밋 시점에만 flush되므로 별도의 수동 복구 로직이 필요 없다).
- **예약 재계산**: 매수는 변경 전 예약(`수량 × 지정가 + 예상 수수료`)을 해제하고 변경 후 값으로 다시 예약한다. 매도는 변경 전 예약 수량을 해제하고 변경 후 수량으로 다시 예약한다. 예약 가능한 현금·수량이 부족하면 LMT-001 생성과 동일한 오류(409 `INSUFFICIENT_CASH`·`INSUFFICIENT_QTY`)로 거부한다 — 신규 오류 코드는 추가하지 않는다.
- **검증 순서는 LMT-003(취소)과 동일하게 존재(404 `NOT_FOUND`) → 소유(403 `FORBIDDEN`) → 상태(409, 이미 `FILLED`면 `ORDER_ALREADY_FILLED`, 이미 `CANCELLED`면 `ORDER_ALREADY_CANCELLED`)로 고정한다.** 요청 본문 자체가 비어 있는지(양쪽 다 미지정)는 리소스 상태와 무관한 요청 형식 검증이라 위 세 단계보다 먼저 400으로 거부한다(LMT-001 생성 시 `market`·`quantity`·`limitPrice` 형식 검증이 계좌·종목 조회보다 먼저 실행되는 것과 같은 순서 원칙).
- **잠금 순서**는 LMT-002·LMT-003과 동일한 `order → account → (SELL만) holding`을 그대로 재사용한다(재논의하지 않는다) — 대상 주문을 가장 먼저 잠그므로 수정-대-체결, 수정-대-취소 동시 도착 경합도 같은 방식으로 직렬화된다.
- **`Idempotency-Key` 헤더는 요구하지 않는다** — 변경 후 값을 절대값으로 지정하는 요청이라 같은 요청을 두 번 보내도 결과가 같다(자연 멱등, `DELETE /api/orders/{orderId}`(LMT-003)와 동일한 이유).
- 수정 결과가 즉시 체결 조건을 충족하는 값이어도 거부하지 않는다 — LMT-001 생성과 동일하게 다음 가격 갱신 트리거(LMT-002)에서 그대로 체결된다.
- 시장가 주문(`orderType=MARKET`)과 타인 주문은 상태·소유 검증만으로 자연히 배제된다(별도 분기를 추가하지 않는다 — LMT-003 취소와 동일 구조).
- 주식 지정가 수정은 범위 밖이다(주식 지정가 자체가 아직 범위 밖).

### 계좌·보유 조회 계약 영향 해소 (Decision Gate, PRD 634행, 이슈 #235)

- LMT-001~003이 배포된 시점부터 `cashBalance`(ACCT-002)·보유수량(PORT-001) 응답과 실제 "주문 가능" 값이 갈라지는 문제가 존재해왔다(지정가 주문이 현금·수량을 예약하기 때문). 이 이슈에서 예약분을 응답에 노출해 해소한다.
- `AccountSummaryResponse`에 `reservedCash` 필드를 추가해 해당 계좌의 예약 현금(지정가 매수로 묶인 금액)을 노출한다.
- `HoldingListItemResponse`에 `reservedQuantity` 필드를 추가해 해당 보유 종목의 예약 수량(지정가 매도로 묶인 수량)을 노출한다.
- 두 필드 모두 이미 원장에 존재하는 값(`accounts.reserved_cash`, `holdings.reserved_quantity`, V22)을 그대로 노출하는 것이며, 새로운 계산·집계를 도입하지 않는다.
- 기존 `cashBalance`·`quantity`·`totalValue`·`holdingsValue` 등 다른 필드의 계산식은 이 변경으로 바뀌지 않는다 — 예약분은 원장 값과 나란히 추가로만 노출된다("주문 가능 금액"·"주문 가능 수량" 같은 파생값 필드는 추가하지 않는다. 클라이언트가 필요하면 두 필드로 직접 계산한다).

## 시나리오

1. **매수 지정가 생성 → 대기 → 체결**: 사용자가 BTC를 지정가로 매수 주문한다. 현금이 예약되고 `PENDING`으로 응답받는다. 이후 빗썸 시세가 지정가 이하로 내려오면, 다음 가격 갱신 이벤트에서 예약된 현금이 실제 차감되고 보유수량이 생기며 `status`가 `FILLED`로 바뀐다.
2. **매도 지정가 생성 → 대기 → 체결**: 사용자가 보유 중인 ETH를 지정가로 매도 주문한다. 수량이 예약되고 `PENDING`으로 응답받는다. 시세가 지정가 이상으로 오르면 다음 갱신 이벤트에서 예약이 실제 매도로 확정되고 실현손익이 반영된다.
3. **예약 현금 부족**: 매수 지정가 주문 시 `수량 × 지정가 + 예상 수수료`가 계좌의 `availableCash`(=cashBalance-reservedCash)를 초과하면 409 `INSUFFICIENT_CASH`로 거부하고 아무것도 예약되지 않는다.
4. **예약 수량 부족(이중예약 방지)**: 이미 다른 지정가 주문으로 수량 일부가 예약된 보유종목에 대해, 남은 `availableQuantity`를 초과하는 매도 지정가를 내면 409 `INSUFFICIENT_QTY`로 거부한다. 이는 기존 시장가 매도에도 동일하게 적용된다(같은 종목을 지정가로 예약해둔 상태에서 시장가로 초과 매도할 수 없다).
5. **즉시체결 조건이어도 생성 거부하지 않음**: 매수 지정가가 현재가보다 높아도(또는 매도 지정가가 현재가보다 낮아도) 생성 자체는 성공하며 `PENDING`으로 응답한다.
6. **동시 체결 경합**: 같은 주문에 대해 가격 갱신 이벤트가 짧은 시간에 두 번 도착해도(중복·재시도) 체결은 정확히 1회만 일어나고 두 번째 이벤트는 이미 `FILLED`가 된 주문을 보고 아무 것도 하지 않는다.
7. **기존 시장가 매도와의 락 순서 회귀 방지**: 지정가 체결(order→account→holding으로 잠금)과 기존 시장가 매도 체결이 서로 다른 주문을 동시에 처리해도, 시장가 매도가 이번에 조정된 순서(account 먼저)로 잠그므로 실제 경합 자원(account·holding)에 대해 ABBA 데드락이 발생하지 않는다.
8. **매수 지정가 취소**: 사용자가 아직 `PENDING`인 BTC 매수 지정가 주문을 취소한다. 204로 응답받고, 계좌의 `reservedCash`가 해당 주문이 예약했던 만큼 정확히 줄어들며 `cashBalance`는 변하지 않는다. 주문 `status`는 `CANCELLED`가 된다.
9. **매도 지정가 취소**: 사용자가 아직 `PENDING`인 ETH 매도 지정가 주문을 취소한다. 204로 응답받고, holding의 `reservedQuantity`가 해당 주문이 예약했던 만큼 정확히 줄어들며 `quantity`(총 보유수량)는 변하지 않는다.
10. **타인 소유 주문 취소 거부**: 다른 사용자의 지정가 주문 `orderId`로 취소를 요청하면 403 `FORBIDDEN`으로 거부되고 아무 예약도 반환되지 않는다.
11. **이미 처리된 주문 재취소 거부**: 이미 `FILLED`된 주문을 다시 취소 요청하면 409 `ORDER_ALREADY_FILLED`로, 이미 `CANCELLED`된 주문을 다시 취소 요청하면 409 `ORDER_ALREADY_CANCELLED`로 각각 거부되고 예약이 이중으로 반환되지 않는다.
12. **취소-대-체결 동시 경합**: 같은 `PENDING` 주문에 취소 요청과 가격 갱신에 의한 체결 트리거가 거의 동시에 도착해도, 정확히 한쪽만 성공한다 — 체결이 이기면 주문은 `FILLED`로 확정되고 취소 요청은 409로 거부되며, 취소가 이기면 주문은 `CANCELLED`로 확정되고 체결 트리거는 조용히 아무 것도 하지 않는다. 어느 경우에도 예약이 이중으로 반환되거나 이중으로 소비되지 않는다.
13. **시장가 매수 대 지정가 매수 생성 — 계좌 경합**: 같은 계좌에 시장가 매수(현금 즉시 차감)와 지정가 매수 생성(현금 예약)이 거의 동시에 도착한다. account 비관적 락으로 두 요청이 직렬화되어, 뒤에 처리되는 쪽은 항상 앞선 요청이 반영된 최신 `cashBalance`/`reservedCash`를 보고 `availableCash`를 검증한다 — 두 요청을 합친 소비액이 원래 잔액을 초과하는데도 둘 다 통과하는 일이 없다.
14. **시장가 매수 대 지정가 매수 체결 — holdings 경합(lost update 방지)**: 이미 보유 중인 종목에 대해 시장가 매수(HTTP 스레드)와 지정가 매수 체결(가격 피드 스레드)이 거의 동시에 같은 holding row를 갱신한다. holdings 비관적 락으로 두 갱신이 직렬화되어, 최종 `quantity`·평균단가에 두 매수 수량이 모두 반영된다(하나가 유실되지 않는다).
15. **ABBA 데드락 회귀 방지**: 조정된 시장가 매수(account→holding)가 기존 시장가 매도·지정가 체결(이미 account→holding 순서로 잠그는 흐름)과 서로 다른 주문으로 같은 계좌·holding을 동시에 대상으로 실행돼도, 반대 순서로 잠그는 흐름이 없어 데드락이 발생하지 않는다.
16. **미체결 목록 조회 — 기본 흐름**: 사용자가 코인 지정가 매수·매도 주문을 여러 건 낸 뒤 `GET /api/orders/pending?market=CRYPTO`를 호출하면, 아직 체결·취소되지 않은 `PENDING` 주문만 최신 요청순으로 응답받는다.
17. **미체결 목록 조회 — 상태 변화 반영**: 미체결 목록에 있던 주문이 체결(LMT-002)되거나 취소(LMT-003)되면, 이후 같은 목록을 다시 조회했을 때 더 이상 나타나지 않는다.
18. **미체결 목록 조회 — 페이지네이션**: 미체결 주문이 `limit`보다 많으면 `nextCursor`로 다음 페이지를 이어받아 누락·중복 없이 전체를 순회할 수 있다.
19. **미체결 목록 조회 — 타인 계좌 차단**: 다른 사용자의 미체결 주문은 본인 목록 조회에 섞이지 않는다. `market`으로 조회한 계좌가 존재하지 않으면 목록을 조회할 수 없다.
20. **계좌 요약·보유 목록에서 예약분 확인**: 코인 지정가 매수 주문 생성 직후 `GET /api/accounts/summary?market=CRYPTO`를 조회하면 `reservedCash`가 해당 주문이 예약한 금액만큼 증가해 있다. 코인 지정가 매도 주문 생성 직후 `GET /api/holdings?market=CRYPTO`를 조회하면 해당 종목의 `reservedQuantity`가 예약한 수량만큼 증가해 있다. 두 값 모두 주문이 체결되거나 취소되면 원래 값(0 또는 그만큼 감소한 값)으로 돌아온다.
21. **매수 지정가 수정 — 지정가만 변경**: `PENDING`인 BTC 매수 지정가 주문의 `limitPrice`만 올려 `PATCH`하면, 계좌의 `reservedCash`가 변경 전 예약을 해제하고 변경 후 값으로 재예약한 만큼 정확히 갱신되며, `orderId`·`requestedAt`은 그대로다. 응답의 `status`는 여전히 `PENDING`이다.
22. **매도 지정가 수정 — 수량만 변경**: `PENDING`인 ETH 매도 지정가 주문의 `quantity`만 줄여 `PATCH`하면, holding의 `reservedQuantity`가 변경 전 예약분을 해제하고 변경 후 수량으로 재예약한 만큼 정확히 갱신되며 `quantity`(총 보유수량)는 불변이다.
23. **원자성 — 예약 부족으로 수정 거부**: 매수 지정가를 예약 가능 현금을 초과하도록 올려 `PATCH`하면 409 `INSUFFICIENT_CASH`로 거부되고, 주문의 `limitPrice`·`quantity`, 계좌의 `reservedCash`·`cashBalance`가 모두 요청 이전 값 그대로 DB에 남는다(취소 후 재생성 방식이었다면 사라졌을 주문이 원자적 처리로 그대로 유지됨을 확인하는 것이 이 시나리오의 핵심). 매도 지정가를 예약 가능 수량을 초과하도록 늘리는 경우도 409 `INSUFFICIENT_QTY`로 동일하게 원자적으로 거부된다.
24. **수정-대-체결/수정-대-취소 동시 경합**: 같은 `PENDING` 주문에 수정 요청과 가격 갱신에 의한 체결 트리거가 거의 동시에 도착하면 정확히 한쪽만 성공한다 — 체결이 이기면 주문은 `FILLED`로 확정되고 수정 요청은 409(`ORDER_ALREADY_FILLED`)로 거부되며, 수정이 이기면 변경된 값으로 확정된 뒤 체결 트리거가 그 값을 기준으로 평가한다. 같은 패턴으로 수정 요청과 취소 요청이 동시에 도착해도 한쪽만 성공하고 예약이 이중으로 반환·소비되지 않는다.
25. **타인 소유·이미 처리된 주문 수정 거부**: 다른 사용자의 지정가 주문을 수정하려 하면 403 `FORBIDDEN`으로, 이미 `FILLED`된 주문은 409 `ORDER_ALREADY_FILLED`로, 이미 `CANCELLED`된 주문은 409 `ORDER_ALREADY_CANCELLED`로 각각 거부되고 아무 예약도 변경되지 않는다.

## 비즈니스 규칙

- 예약 계산식: 매수 예약현금 = `FLOOR(수량 × 지정가)` + `FLOOR(FLOOR(수량 × 지정가) × 0.05%)` (기존 코인 수수료율 재사용, ORD-004와 동일한 내림 규칙). 매도는 수량 그대로 예약(수수료는 체결 시점에만 발생).
- 체결가는 항상 지정가로 고정되므로, 생성 시 예약한 금액·수수료와 체결 시 실제 확정되는 금액·수수료가 항상 정확히 일치한다. 별도 정산(차액 환급)은 필요 없다.
- GTC: 코인은 24시간 시장이라 만료 없이 유지된다. 취소는 `DELETE /api/orders/{orderId}`(LMT-003)로만 가능하다.
- 수량·최소주문금액 검증은 기존 ORD-003 규칙(코인 소수점 8자리 이하, 최소 5,000원)을 `수량 × 지정가` 기준으로 그대로 재사용한다.
- 시장(`market`)은 `CRYPTO`만 허용한다. `STOCK`으로 요청하면 400 `VALIDATION_ERROR`로 거부한다.
- 취소 시 반환하는 예약액·예약수량은 생성 시 예약한 값과 항상 정확히 일치한다(부분 취소 없음 — 전량 취소만 지원, 부분체결이 없는 것과 대칭). 반환 후 `reservedCash`·`reservedQuantity`가 음수가 되면 원장 불변식 위반이므로 엔티티 메서드가 방어적으로 `IllegalStateException`을 던진다(정상 흐름에서는 발생하지 않는다 — 서비스 계층이 상태 검증을 먼저 통과시키기 때문).
- 수정(LMT-005)의 예약 재계산도 생성·체결·취소와 완전히 동일한 공식(`FLOOR(수량 × 지정가)` + `FLOOR(FLOOR(수량 × 지정가) × 0.05%)`)을 쓴다 — 변경 전 예약분을 해제할 때는 변경 전 값으로, 재예약할 때는 변경 후 값으로 각각 이 공식을 적용한다. 이 계산은 이제 생성·취소·체결·수정 네 곳이 동일하게 쓰므로 plan.md에서 공용 유틸리티로 추출한다(아래 "확정된 설계 결정" 12번).

## 완료 조건

- [x] `POST /api/orders/limit`이 매수·매도 지정가 주문을 `PENDING`으로 생성하고, 매수는 현금(수량 × 지정가 + 예상 수수료)을, 매도는 수량을 예약한다.
- [x] 예약 가능한 현금·수량이 부족하면 거부한다(기존 시장가 ORD-003 오류 원칙 재사용).
- [x] 매수 지정가가 현재가 이상이거나 매도 지정가가 현재가 이하로 즉시 체결 조건을 충족해도 생성 시점에 거부하지 않는다.
- [x] `POST /api/orders`(시장가 전용)의 `orderType="LIMIT"` 422 `UNSUPPORTED_ORDER_TYPE` 거부가 그대로 유지된다(회귀 없음).
- [x] holding 예약 원장(`availableQuantity = totalQuantity - reservedQuantity`)이 도입되고, 기존 시장가 SELL(`OrderExecutionService`)도 이 원장을 반영해 예약된 수량을 중복 매도할 수 없다.
- [x] 빗썸 웹소켓 가격 갱신 시 `PENDING` 지정가 주문의 체결 조건이 평가되고, 충족 시 목표가로 고정 체결되어 예약이 실제 현금·보유수량 이동으로 확정되며 `status`가 `FILLED`로 바뀐다.
- [x] 동시 체결 경합(가격 갱신 이벤트 중복 도착)이 `order → account → holding` 잠금 순서의 비관적 락으로 제어되어 예약 이중 반환이나 중복 체결이 재현되지 않는다(신규 종목 첫 매수는 `order → account`).
- [x] 기존 시장가 매도 체결 경로가 account를 먼저 잠그도록 조정되어, 실제로 경합하는 account·holding 두 자원에 대해 지정가 체결과 반대 순서로 락을 시도하지 않는다(ABBA 데드락 회귀 테스트 포함).
- [x] `docs/api-routes.md`·`docs/api-contracts.md`에 신규 엔드포인트(`POST /api/orders/limit`)가 반영된다.
- [x] `docs/prd.md` §3 구현 현황의 "지정가 주문·상시 체결(LMT-001~004)" 행을 이 PR 번호를 근거로 "일부 완료"(LMT-001~002)로 갱신한다 — 취소(LMT-003)·목록조회(LMT-004)는 이번 PR 범위가 아니라고 명시한다.
- [x] `./gradlew build` 통과.

### LMT-003 완료 조건 (이슈 #218)

- [x] `DELETE /api/orders/{orderId}`가 본인 소유의 `PENDING` 지정가 주문을 취소하고 `status`를 `CANCELLED`로 변경하며 204(본문 없음)를 응답한다.
- [x] 취소 시 매수는 예약 현금을(`reservedCash` 감소, `cashBalance` 불변), 매도는 예약 수량을(`reservedQuantity` 감소, `quantity` 불변) 정확히 반환한다.
- [x] 이미 `FILLED`인 주문의 취소 요청은 409 `ORDER_ALREADY_FILLED`로, 이미 `CANCELLED`인 주문의 취소 요청은 409 `ORDER_ALREADY_CANCELLED`로 각각 거부되고 예약이 이중 반환되지 않는다.
- [x] 본인 소유가 아닌 주문의 취소 요청은 403 `FORBIDDEN`으로 거부된다. 검증 순서는 존재(404 `NOT_FOUND`) → 소유(403) → 상태(409)로 고정된다.
- [x] 체결 트리거(LMT-002)와 취소 요청이 동시에 도착해도 `order → account → holding` 잠금 순서로 예약 이중 반환이나 "체결 후 취소" 같은 경합 없이 안전하게 처리된다(경합 재현 테스트 포함 — 시나리오 12).
- [x] `docs/api-routes.md`·`docs/api-contracts.md`에 신규 엔드포인트(`DELETE /api/orders/{orderId}`)가 반영된다.
- [x] `docs/prd.md` §3 구현 현황의 "지정가 주문·상시 체결(LMT-001~004)" 행을 이 PR 번호를 근거로 "일부 완료"(LMT-001~003)로 갱신한다 — 목록조회(LMT-004)는 이번 PR 범위가 아니라고 명시한다. (근거: PR #220)
- [x] `./gradlew build` 통과. `ErrorCodeTest`의 stale 카운트(26→27)·매핑 누락(`ORDER_NOT_PENDING`)을 오케스트레이터가 직접 수정 후 전체 `./gradlew build` 재실행으로 확인.

### LMT-004 완료 조건 (이슈 #235)

- [x] `GET /api/orders/pending?market=&cursor=&limit=`가 본인 소유의 `PENDING` 상태 지정가 주문만 최신순 커서 페이지네이션으로 반환한다.
- [x] `market`이 누락되거나 `STOCK`\|`CRYPTO`가 아니면 400으로 거부한다(`PORT-002`와 동일 규칙).
- [x] 커서가 손상되었으면(형식 불일치) 400으로 거부한다. `limit`이 1~100 범위를 벗어나면 400으로 거부한다.
- [x] 다른 사용자의 미체결 주문은 응답에 나타나지 않는다. `market`으로 조회한 계좌가 존재하지 않으면 조회할 수 없다.
- [x] 체결(LMT-002)·취소(LMT-003)로 상태가 바뀐 주문은 이후 조회에서 제외된다.
- [x] `AccountSummaryResponse`에 `reservedCash` 필드가 추가되고, `Account.reservedCash` 원장 값을 그대로 노출한다(계산식 변경 없음).
- [x] `HoldingListItemResponse`에 `reservedQuantity` 필드가 추가되고, `Holding.reservedQuantity` 원장 값을 그대로 노출한다(계산식 변경 없음).
- [x] `docs/api-routes.md`·`docs/api-contracts.md`에 신규 엔드포인트(`GET /api/orders/pending`)와 `AccountSummaryResponse`·`HoldingListItemResponse`의 필드 추가가 같은 커밋에서 반영된다.
- [x] `docs/prd.md` §3 구현 현황의 "지정가 주문·상시 체결(LMT-001~004)" 행을 이 PR 번호를 근거로 "완료"로 갱신하고, "계좌·보유 조회 계약 영향(Decision Gate)"이 해소됐음을 반영한다(근거: PR #237).
- [x] `./gradlew build` 통과.

### LMT-005 완료 조건 (이슈 #239)

- [x] `PATCH /api/orders/{orderId}`가 본인 소유의 `PENDING` 코인 지정가 주문의 `limitPrice`·`quantity`를 변경한다. 둘 다 보내는 경우와 한쪽만 보내는 부분 갱신이 모두 동작하고, 둘 다 생략하면 400 `VALIDATION_ERROR`로 거부한다.
- [x] 같은 `orderId`의 `orders` 행을 갱신하며 새 주문을 만들지 않는다. `requestedAt`도 유지되어 `GET /api/orders/pending`(LMT-004)에서 정렬 위치가 바뀌지 않는다.
- [x] 매수는 변경 전 예약(`수량 × 지정가 + 예상 수수료`)을 해제하고 변경 후 값으로 재예약한다. 매도는 변경 전 예약 수량을 해제하고 변경 후 수량으로 재예약한다.
- [x] **예약 가능한 현금·수량이 부족하면 409 `INSUFFICIENT_CASH`·`INSUFFICIENT_QTY`로 거부하고 주문·계좌·보유가 변경 전 상태 그대로 DB에 남는다** — 한 트랜잭션에서 처리되어 부분 반영이 남지 않는다는 것을 통합 테스트로 확인한다(서비스 예외만 확인하는 얕은 검증으로 끝내지 않고, 거부 이후 실제 DB 값을 재조회해 대조한다).
- [x] 검증 순서가 존재(404 `NOT_FOUND`) → 소유(403 `FORBIDDEN`) → 상태(409)로 고정된다. 이미 체결된 주문은 `ORDER_ALREADY_FILLED`, 이미 취소된 주문은 `ORDER_ALREADY_CANCELLED`로 거부한다(LMT-003과 동일한 코드 재사용, 신규 오류 코드 없음).
- [x] `Idempotency-Key` 헤더를 요구하지 않는다 — 같은 요청을 두 번 보내도 결과가 같다.
- [x] 잠금 순서 `order → account → (SELL만) holding`을 지킨다. 수정-대-체결, 수정-대-취소 동시 도착 경합에서 예약 이중 반환·이중 소비가 발생하지 않음을 `LimitOrderConcurrencyIntegrationTest`에 실제 멀티스레드 시나리오로 추가해 확인한다.
- [x] 수정 결과가 즉시 체결 조건을 충족하는 값이어도 생성(LMT-001)과 동일하게 거부하지 않는다.
- [x] 시장가 주문(`orderType=MARKET`)과 타인 주문은 상태·소유 검증만으로 자연히 배제된다(별도 분기 없음).
- [x] `docs/api-routes.md`·`docs/api-contracts.md`에 `PATCH /api/orders/{orderId}`를 반영한다.
- [x] `docs/prd.md` §3 "지정가 주문·상시 체결" 행을 이 PR 번호를 근거로 갱신한다 — LMT-005 완료로 판정을 갱신한다.
- [x] `./gradlew build` 통과.

## 확정된 설계 결정 (2026-08-05, 사용자 확인)

이후 구현·리뷰에서 다시 논의하지 않는다. 근거가 필요하면 이 절을 인용한다.

1. **예약 현금 스키마**: `accounts.reserved_cash` 컬럼 추가(기본값 0). `cashBalance`는 원장 값 그대로 두고, `availableCash = cashBalance - reservedCash`로 계산한다. `holdings.reservedQuantity`와 대칭 구조.
2. **시장가 매수 경로 락 범위**: 최초 PR(#215, LMT-001~002)에서는 추가하지 않고 매도 경로만 조정했다. **이 결정은 이슈 #224로 대체됐다** — 아래 10번 결정에 따라 시장가 매수 경로에도 계좌·holdings 락을 보강한다(위 "알려진 한계" 절이 "해소됨"으로 갱신된 이유).
3. **가격 갱신 이벤트**: `PriceStore.saveTick`이 틱을 최신값으로 채택했을 때만(MKT-003 과거 틱 무시 로직 통과 시) `ApplicationEvent`를 publish한다. 리스너는 신규 `InstrumentRepository.findByMarketAndSymbol(CRYPTO, symbol)` 조회로 `instrumentId`를 알아낸다.
4. **예상 수수료**: 기존 `CRYPTO_FEE_RATE`(0.05%)와 동일한 내림(FLOOR) 계산을 `수량 × 지정가`에 적용한다. 체결가가 항상 지정가로 고정되므로 예약 시점과 체결 시점의 수수료가 정확히 일치해 별도 정산이 필요 없다.
5. **동시 체결 처리 단위**: 한 가격 이벤트에서 조건을 충족하는 `PENDING` 주문이 여러 건이면 주문별로 독립된 트랜잭션에서 `requestedAt` 오름차순(동시각은 `id` 오름차순)으로 순차 처리한다.
6. **Idempotency-Key**: `POST /api/orders/limit`도 `Idempotency-Key` 헤더를 필수로 받고, 기존 `POST /api/orders`의 재조회·409 `IDEMPOTENCY_CONFLICT` 패턴을 그대로 재사용한다.
7. **체결 잠금 순서 정정(2026-08-05, 항목4 구현·검증 중 확인)**: 이슈 원문·본 문서 초안은 "`account → holding → order`"로 적었으나, 실제로는 `order → account → holding`(holding 없으면 `order → account`)으로 구현·검증되었고 이 문서도 이에 맞춰 정정한다. order 락은 같은 주문에 대한 중복 이벤트(및 후속 LMT-003 취소 요청)만 배제하는 용도라 그 주문 자신의 row 외에는 경쟁하지 않으므로 가장 먼저 잡아도 안전하다 — 오히려 이미 `FILLED`인 주문에 대해 account·holding 락을 불필요하게 잡는 낭비를 막는다. 실제로 여러 트랜잭션이 경합하는 자원은 account·holding뿐이며 이 순서(account 먼저)는 모든 흐름에서 불변이므로 ABBA 위험은 없다(tester가 코드 대조로 확인). LMT-003(취소)도 대상 주문을 먼저 잠그는 동일 순서를 따르면 취소-대-체결 경합도 같은 방식으로 안전하게 직렬화된다.
8. **LMT-003 취소 정책(2026-08-05, 사용자 확인, 이슈 #218)**: (a) 성공 응답은 204·본문 없음(`DELETE /api/community/posts/{postId}`·`DELETE /api/community/comments/{commentId}` 컨벤션 재사용). (b) 타인 소유 주문 취소 시도는 403 `FORBIDDEN`. (c) 검증 순서는 존재(404) → 소유(403) → 상태(409)로 고정, journal 엔드포인트 패턴(`docs/api-contracts.md` 439행)을 재사용하고 아직 미구현인 `DELETE /api/exit-plans/{exitPlanId}`(016 candidate 9, 404 통일 방식)는 따르지 않는다 — 실제 구현된 코드 컨벤션을 우선한다. (d) 404·403은 기존 `ErrorCode.NOT_FOUND`·`ErrorCode.FORBIDDEN`을 재사용하고(주문 도메인 전용 접두 코드를 신설하지 않는다 — LMT-001/002 plan.md가 이미 "404 NOT_FOUND"로 order 도메인 404를 통일해뒀다), 이미 `FILLED`/`CANCELLED`인 주문에 대한 409 코드는 아래 9번 결정으로 대체됐다. (e) 잠금 순서는 LMT-002와 동일하게 `order → account → holding`(SELL만 holding)을 그대로 재사용한다(위 7번 결정을 재적용, 재논의하지 않는다).
9. **LMT-003 상태(409) 오류 코드 분리(2026-08-05, 사용자 확인, 이슈 #218 후속)**: 위 8번(d)가 도입한 단일 `ORDER_NOT_PENDING`을 폐기하고 `ORDER_ALREADY_FILLED`(이미 `FILLED`)·`ORDER_ALREADY_CANCELLED`(이미 `CANCELLED`)로 분리한다 — 클라이언트가 두 사유를 구분해 다른 안내를 보여줄 수 있어야 한다는 사용자 판단. `DELETE /api/orders/{orderId}` 응답 계약·`LimitOrderCancelService`의 상태 분기·관련 테스트(`LimitOrderCancelServiceTest`·`OrderControllerTest`·`LimitOrderConcurrencyIntegrationTest`·`ErrorCodeTest`)·`docs/api-contracts.md`를 모두 이 분리에 맞춰 갱신했다.
10. **신규 종목 첫 매수 동시 생성 경합 방지 정책(2026-08-05, 사용자 확인, 이슈 #224)**: "계좌 락만으로 충분" 방식을 채택한다. account 락을 먼저 잡으면 같은 계좌를 건드리는 모든 매수 경로(시장가·지정가 체결)가 자동으로 직렬화되므로, `holdings.uk_holdings_account_instrument`(마이그레이션 `V10__create_order_ledger_tables.sql` 53행) 유니크 제약 위반에 대한 방어적 catch·재조회 로직은 추가하지 않는다. 기존 LMT-002 BUY 체결 경로가 이미 이 전제(신규 종목 첫 매수는 order→account만 잠그고 holding 단계는 건너뜀, 위 7번 결정)로 설계돼 있어 이번 작업도 동일 전제를 따른다. 이 원칙은 코드베이스가 일어날 수 없는 시나리오에 방어 코드를 넣지 않는다는 관례(`docs/conventions.md`)와도 일치한다.
11. **LMT-004 응답 필드명 확정(2026-08-06, 이슈 #235 본문 확정)**: `AccountSummaryResponse.reservedCash`(long)·`HoldingListItemResponse.reservedQuantity`(BigDecimal)로 확정한다. "주문 가능 금액"·"주문 가능 수량" 같은 파생값 필드(`availableCash`/`availableQuantity`)는 추가하지 않는다 — 클라이언트가 이미 노출된 `cashBalance - reservedCash`, `quantity - reservedQuantity`로 직접 계산할 수 있고, 이는 엔티티의 `Account.getAvailableCash()`/`Holding.getAvailableQuantity()`와 동일한 계산식이라 서버가 같은 값을 필드로 중복 노출할 이유가 없다.
12. **LMT-005 수정 이력 미보관 재확정(2026-08-06, 이슈 #239 요구에 따른 spec 단계 재검토)**: PRD LMT-005 절은 마이그레이션을 피하려고 미보관으로 정했지만, 이슈 #239가 "학습용 시뮬레이터에서 복기 가치가 있으면 이 시점에 컬럼·테이블 추가를 결정하라"고 명시적으로 요구해 다시 판단했다. 결론은 **미보관 유지**다(PRD 결론과 동일하되 근거는 이번에 새로 확인한 것). 근거:
    - 이 코드베이스에는 도메인 엔티티 변경 이력(audit trail)을 저장하는 기존 패턴이 전혀 없다(계좌 설정·워치리스트 등 다른 가변 상태도 마찬가지로 미보관). 지정가 수정 이력만 예외적으로 저장하면 "이 도메인만 왜 이력을 남기는가"라는 일관성 질문이 남고, 새 패턴(신규 테이블 또는 JSON 감사 컬럼)을 이 기능 하나만을 위해 도입하는 비용이 크다.
    - `PENDING` 상태에서의 수정은 아직 자본이 묶이기만 했을 뿐 확정된 경제적 행위가 아니다 — "얼마에 최종 체결됐는지"(학습에 가장 중요한 신호)는 이미 `Trade`·`Order`(FILLED 상태)에 정확히 남는다. "체결 전에 지정가를 몇 번 바꿨는지"는 부가적인 행동 패턴 정보이지, 이 시뮬레이터의 핵심 학습 지표(실현손익·수익률)와 직결되지 않는다.
    - 비용 대비 편익이 비대칭이다: 이력을 남기려면 신규 Flyway 마이그레이션(ADR-0004, 머지된 마이그레이션은 수정 불가하므로 새 번호 필수) + 신규 테이블/컬럼 + 원자성 트랜잭션 내부에 추가 INSERT 한 단계가 더 필요해, 이 기능의 핵심 요구사항인 "원자성"의 실패 표면을 넓힌다. 반면 이력 조회를 요구하는 사용자 시나리오나 화면은 현재 PRD·spec 어디에도 없다(수요가 확인되지 않은 상태에서 선제 투자).
    - 되돌리기 비대칭성도 고려했다: 지금 미보관으로 시작해도 나중에 수요가 확인되면 신규 테이블 추가로 언제든 붙일 수 있다(과거 미보관 기간의 이력만 소급 불가, 신규 데이터부터는 즉시 확보 가능). 반대로 지금 이력 테이블을 만들었다가 안 쓰이면 죽은 스키마가 남는다 — 후자가 더 되돌리기 어렵다.
    - 결론: 신규 컬럼·마이그레이션·이력 테이블을 추가하지 않는다. `orders` 행은 수정 시 덮어쓰기(overwrite)되며 변경 전 `limitPrice`·`quantity`는 남기지 않는다.

## 부수 효과(요구사항은 아니지만 확인 필요)

- 기존 `GET /api/orders?market=`(PORT-003)은 `Order` 엔티티를 그대로 투영하는 일반 목록이라, 지정가 `PENDING` 주문도 자동으로 이 목록에 섞여 나온다(코드 변경 없이 발생하는 자연스러운 결과 — `OrderListItemResponse`는 `Trade`를 참조하지 않는다). `GET /api/orders/pending`(LMT-004)은 이와 별개로 `PENDING`만 필터링하는 전용 엔드포인트다.

## 후속 이슈

- NOTI-001~005 지정가 체결 알림 — 이 이슈의 체결 트리거(LMT-002)를 소비한다. LMT-003 취소가 확정된 주문은 체결되지 않으므로 알림 대상이 아니다.
- ~~시장가 매수 경로 락 추가(계좌 락 + holdings 락)~~ — 이슈 #224로 닫혔다(위 "시장가 매수 경로 락 보강 (이슈 #224)" 절 참고).
- ~~미체결 지정가 목록 조회, 계좌·보유 조회 계약 영향(Decision Gate)~~ — 이슈 #235로 닫혔다(위 "LMT-004 미체결 주문 목록 조회"·"계좌·보유 조회 계약 영향 해소" 절 참고).
- ~~주문 수정(가격·수량 변경)~~ — 이슈 #239(LMT-005)로 닫혔다(위 "LMT-005 지정가 주문 수정" 절 참고).
