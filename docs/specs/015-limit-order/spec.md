# Spec: 코인 지정가 매매 — 생성·체결·취소 (LMT-001~003)

## 배경

- 이슈: #210 `[1차 고도화][매매] 코인 지정가 매매 구현`(LMT-001~002), #218 `[1차 고도화][매매] 지정가 주문 취소`(LMT-003)
- 선행: #138(완료, PR #209 — `docs/prd.md`에 지정가·알림 정책 반입), #210(완료, PR #215 — LMT-001~002)
- PRD 근거: `docs/prd.md` 591~615행 (§4 "지정가 주문·체결" 중 LMT-001~003), §3 구현 현황 211행
- 이 spec 폴더는 PRD 631행이 예약해둔 자리다. LMT-001(생성)·LMT-002(체결 트리거)는 PR #215로 완료됐다. **이번 절은 이슈 #218 — LMT-003(지정가 주문 취소, `DELETE /api/orders/{orderId}`)을 같은 폴더에 이어 붙인다.** 미체결 목록조회(LMT-004)는 여전히 후속 이슈다.
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

**제외 (이슈 원문 그대로)**

- 주식 지정가 — 재생 데이터 기반이라 "이 가격 도달 시" 조건 자체가 성립하기 어려워 추후 별도로 다룬다
- 미체결 지정가 목록조회(LMT-004, `GET /api/orders/pending`) — 후속 이슈
- 주식 지정가 취소, 시장가 주문 취소 — 이번 취소 기능은 코인 지정가(`orderType=LIMIT`) 전용이다(시장가는 생성 즉시 `FILLED`라 애초에 `PENDING` 상태를 갖지 않으므로 취소 대상이 될 수 없다 — 별도 분기 없이 상태 검증만으로 자연히 배제된다)
- 지정가 체결 알림(NOTI-001~005) — LMT-002 체결 트리거가 선행되어야 하므로 별도 이슈
- `AccountSummaryResponse`·`HoldingListItemResponse`에 예약분(availableCash/availableQuantity) 노출 — 계약 변경은 Decision Gate, 착수 시 필요성이 확인되면 별도 이슈
- 부분체결·슬리피지 허용 — 전량 목표가 체결만 다룬다
- 외부 메시지 큐(Redis/Kafka) 도입 — 인메모리 `ApplicationEvent`로 처리하고 리스너 경계만 열어둔다

## 알려진 한계 (이번 PR에서 완전히 해소되지 않는 경합)

- **시장가 매수(BUY) 경로에는 이번에 계좌 비관적 락을 추가하지 않는다.** 이슈 완료조건은 "기존 시장가 **매도** 체결 경로가 account를 먼저 잠그도록 조정"만 요구하며, 매수 경로는 범위 밖이다. 그 결과 **지정가 매수 생성(현금 예약)과 기존 시장가 매수 체결이 동시에 같은 계좌에서 실행되면, 매수 경로가 잠금 없이 현금을 확인·차감하므로 완전히 직렬화되지 않는다** — 즉 이 PR은 "지정가 대 지정가", "지정가 대 시장가 매도" 경합은 비관적 락으로 막지만 "지정가 대 시장가 매수" 경합은 막지 않는다. 이 gap을 닫는 작업(시장가 매수 경로에 계좌 락 추가)은 별도 후속 이슈로 남긴다.
- **BUY 체결 경로는 holdings row도 잠그지 않는다(PR #215 리뷰 권장사항).** `LimitOrderFillService.fillBuy`가 호출하는 `PortfolioBuyService.applyBuyTrade`는 SELL 경로(`getHoldingForUpdate`)와 달리 락 없는 `HoldingRepository.findByAccountIdAndInstrumentId`를 그대로 쓴다. 이번 PR이 추가한 LMT-002 리스너가 가격 피드 스레드에서 같은 미락 경로로 진입하는 두 번째 지점이 되면서, "시장가 매수(HTTP 스레드) vs 지정가 매수 체결(피드 스레드)"이 동시에 같은 holding을 read-modify-write하면 lost update(수량·평균단가 유실) 가능성이 구조적으로 남는다. 위 계좌 락 gap과 원인이 같으므로 같은 후속 이슈(시장가 매수 경로 락 추가)의 범위에 holdings 락도 포함한다.

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
  - 본인 소유이지만 `status`가 `PENDING`이 아니면(이미 `FILLED` 또는 이미 `CANCELLED`) 409 `ORDER_NOT_PENDING`(신규 코드 — spec.md "완료 조건" 아래 신설). 시장가 주문(`orderType=MARKET`)은 생성 즉시 `FILLED`이므로 이 분기로 자연히 걸러진다(별도 `orderType` 검증 불필요).
- 취소 판정·잠금 순서는 LMT-002 체결과 동일한 `order → account → holding`을 그대로 재사용한다(SELL만 holding까지, BUY는 order → account) — "확정된 설계 결정" 7번에서 이미 이 순서를 LMT-003이 따르도록 확정해뒀다(재논의하지 않는다).
- 체결 트리거(LMT-002)와 취소 요청이 동시에 도착해도 안전하다: 두 흐름 모두 order를 가장 먼저 잠그므로, 먼저 락을 획득한 쪽이 끝까지 처리되고 나중 쪽은 락 대기 후 `status != PENDING`을 보고 자기 작업을 거부/no-op한다(체결이 먼저면 취소는 409 `ORDER_NOT_PENDING`, 취소가 먼저면 체결 리스너는 `fillIfPending` 내부에서 `status != PENDING`으로 조용히 반환).

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
11. **이미 처리된 주문 재취소 거부**: 이미 `FILLED`된 주문이나 이미 `CANCELLED`된 주문을 다시 취소 요청하면 409 `ORDER_NOT_PENDING`으로 거부되고 예약이 이중으로 반환되지 않는다.
12. **취소-대-체결 동시 경합**: 같은 `PENDING` 주문에 취소 요청과 가격 갱신에 의한 체결 트리거가 거의 동시에 도착해도, 정확히 한쪽만 성공한다 — 체결이 이기면 주문은 `FILLED`로 확정되고 취소 요청은 409로 거부되며, 취소가 이기면 주문은 `CANCELLED`로 확정되고 체결 트리거는 조용히 아무 것도 하지 않는다. 어느 경우에도 예약이 이중으로 반환되거나 이중으로 소비되지 않는다.

## 비즈니스 규칙

- 예약 계산식: 매수 예약현금 = `FLOOR(수량 × 지정가)` + `FLOOR(FLOOR(수량 × 지정가) × 0.05%)` (기존 코인 수수료율 재사용, ORD-004와 동일한 내림 규칙). 매도는 수량 그대로 예약(수수료는 체결 시점에만 발생).
- 체결가는 항상 지정가로 고정되므로, 생성 시 예약한 금액·수수료와 체결 시 실제 확정되는 금액·수수료가 항상 정확히 일치한다. 별도 정산(차액 환급)은 필요 없다.
- GTC: 코인은 24시간 시장이라 만료 없이 유지된다. 취소는 `DELETE /api/orders/{orderId}`(LMT-003)로만 가능하다.
- 수량·최소주문금액 검증은 기존 ORD-003 규칙(코인 소수점 8자리 이하, 최소 5,000원)을 `수량 × 지정가` 기준으로 그대로 재사용한다.
- 시장(`market`)은 `CRYPTO`만 허용한다. `STOCK`으로 요청하면 400 `VALIDATION_ERROR`로 거부한다.
- 취소 시 반환하는 예약액·예약수량은 생성 시 예약한 값과 항상 정확히 일치한다(부분 취소 없음 — 전량 취소만 지원, 부분체결이 없는 것과 대칭). 반환 후 `reservedCash`·`reservedQuantity`가 음수가 되면 원장 불변식 위반이므로 엔티티 메서드가 방어적으로 `IllegalStateException`을 던진다(정상 흐름에서는 발생하지 않는다 — 서비스 계층이 상태 검증을 먼저 통과시키기 때문).

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

- [ ] `DELETE /api/orders/{orderId}`가 본인 소유의 `PENDING` 지정가 주문을 취소하고 `status`를 `CANCELLED`로 변경하며 204(본문 없음)를 응답한다.
- [ ] 취소 시 매수는 예약 현금을(`reservedCash` 감소, `cashBalance` 불변), 매도는 예약 수량을(`reservedQuantity` 감소, `quantity` 불변) 정확히 반환한다.
- [ ] 이미 `FILLED`이거나 이미 `CANCELLED`인 주문의 취소 요청은 409 `ORDER_NOT_PENDING`으로 거부되고 예약이 이중 반환되지 않는다.
- [ ] 본인 소유가 아닌 주문의 취소 요청은 403 `FORBIDDEN`으로 거부된다. 검증 순서는 존재(404 `NOT_FOUND`) → 소유(403) → 상태(409)로 고정된다.
- [ ] 체결 트리거(LMT-002)와 취소 요청이 동시에 도착해도 `order → account → holding` 잠금 순서로 예약 이중 반환이나 "체결 후 취소" 같은 경합 없이 안전하게 처리된다(경합 재현 테스트 포함 — 시나리오 12).
- [ ] `docs/api-routes.md`·`docs/api-contracts.md`에 신규 엔드포인트(`DELETE /api/orders/{orderId}`)가 반영된다.
- [ ] `docs/prd.md` §3 구현 현황의 "지정가 주문·상시 체결(LMT-001~004)" 행을 이 PR 번호를 근거로 "일부 완료"(LMT-001~003)로 갱신한다 — 목록조회(LMT-004)는 이번 PR 범위가 아니라고 명시한다.
- [ ] `./gradlew build` 통과.

## 확정된 설계 결정 (2026-08-05, 사용자 확인)

이후 구현·리뷰에서 다시 논의하지 않는다. 근거가 필요하면 이 절을 인용한다.

1. **예약 현금 스키마**: `accounts.reserved_cash` 컬럼 추가(기본값 0). `cashBalance`는 원장 값 그대로 두고, `availableCash = cashBalance - reservedCash`로 계산한다. `holdings.reservedQuantity`와 대칭 구조.
2. **시장가 매수 경로 락 범위**: 이번 PR에서 추가하지 않는다(위 "알려진 한계" 참고). 매도 경로만 조정한다.
3. **가격 갱신 이벤트**: `PriceStore.saveTick`이 틱을 최신값으로 채택했을 때만(MKT-003 과거 틱 무시 로직 통과 시) `ApplicationEvent`를 publish한다. 리스너는 신규 `InstrumentRepository.findByMarketAndSymbol(CRYPTO, symbol)` 조회로 `instrumentId`를 알아낸다.
4. **예상 수수료**: 기존 `CRYPTO_FEE_RATE`(0.05%)와 동일한 내림(FLOOR) 계산을 `수량 × 지정가`에 적용한다. 체결가가 항상 지정가로 고정되므로 예약 시점과 체결 시점의 수수료가 정확히 일치해 별도 정산이 필요 없다.
5. **동시 체결 처리 단위**: 한 가격 이벤트에서 조건을 충족하는 `PENDING` 주문이 여러 건이면 주문별로 독립된 트랜잭션에서 `requestedAt` 오름차순(동시각은 `id` 오름차순)으로 순차 처리한다.
6. **Idempotency-Key**: `POST /api/orders/limit`도 `Idempotency-Key` 헤더를 필수로 받고, 기존 `POST /api/orders`의 재조회·409 `IDEMPOTENCY_CONFLICT` 패턴을 그대로 재사용한다.
7. **체결 잠금 순서 정정(2026-08-05, 항목4 구현·검증 중 확인)**: 이슈 원문·본 문서 초안은 "`account → holding → order`"로 적었으나, 실제로는 `order → account → holding`(holding 없으면 `order → account`)으로 구현·검증되었고 이 문서도 이에 맞춰 정정한다. order 락은 같은 주문에 대한 중복 이벤트(및 후속 LMT-003 취소 요청)만 배제하는 용도라 그 주문 자신의 row 외에는 경쟁하지 않으므로 가장 먼저 잡아도 안전하다 — 오히려 이미 `FILLED`인 주문에 대해 account·holding 락을 불필요하게 잡는 낭비를 막는다. 실제로 여러 트랜잭션이 경합하는 자원은 account·holding뿐이며 이 순서(account 먼저)는 모든 흐름에서 불변이므로 ABBA 위험은 없다(tester가 코드 대조로 확인). LMT-003(취소)도 대상 주문을 먼저 잠그는 동일 순서를 따르면 취소-대-체결 경합도 같은 방식으로 안전하게 직렬화된다.
8. **LMT-003 취소 정책(2026-08-05, 사용자 확인, 이슈 #218)**: (a) 성공 응답은 204·본문 없음(`DELETE /api/community/posts/{postId}`·`DELETE /api/community/comments/{commentId}` 컨벤션 재사용). (b) 타인 소유 주문 취소 시도는 403 `FORBIDDEN`. (c) 검증 순서는 존재(404) → 소유(403) → 상태(409)로 고정, journal 엔드포인트 패턴(`docs/api-contracts.md` 439행)을 재사용하고 아직 미구현인 `DELETE /api/exit-plans/{exitPlanId}`(016 candidate 9, 404 통일 방식)는 따르지 않는다 — 실제 구현된 코드 컨벤션을 우선한다. (d) 404·403은 기존 `ErrorCode.NOT_FOUND`·`ErrorCode.FORBIDDEN`을 재사용하고(주문 도메인 전용 접두 코드를 신설하지 않는다 — LMT-001/002 plan.md가 이미 "404 NOT_FOUND"로 order 도메인 404를 통일해뒀다), 이미 `FILLED`/`CANCELLED`인 주문에 대한 409만 신규 코드 `ORDER_NOT_PENDING`을 추가한다(미구현 candidate `EXIT_PLAN_NOT_PENDING`과 동일한 `_NOT_PENDING` 명명 관례를 재사용). (e) 잠금 순서는 LMT-002와 동일하게 `order → account → holding`(SELL만 holding)을 그대로 재사용한다(위 7번 결정을 재적용, 재논의하지 않는다).

## 부수 효과(요구사항은 아니지만 확인 필요)

- 기존 `GET /api/orders?market=`(PORT-003)은 `Order` 엔티티를 그대로 투영하는 일반 목록이라, 이번에 생성되는 `PENDING` 지정가 주문도 자동으로 이 목록에 섞여 나온다(코드 변경 없이 발생하는 자연스러운 결과 — `OrderListItemResponse`는 `Trade`를 참조하지 않는다). LMT-004(`GET /api/orders/pending`)는 이와 별개로 `PENDING`만 필터링하는 전용 엔드포인트이며 이번 범위가 아니다.

## 후속 이슈

- LMT-004 미체결 지정가 목록 조회(`GET /api/orders/pending`) — 이 spec 폴더에 이어 붙인다.
- NOTI-001~005 지정가 체결 알림 — 이 이슈의 체결 트리거(LMT-002)를 소비한다. LMT-003 취소가 확정된 주문은 체결되지 않으므로 알림 대상이 아니다.
- "알려진 한계"에 기록한 시장가 매수 경로 락 추가(계좌 락 + holdings 락) — LMT-003도 이 gap 위에서 동작한다(취소는 order→account만 잠그므로 시장가 매수 체결과의 미보호 경합은 이 후속 이슈가 해소될 때까지 동일하게 남는다).
