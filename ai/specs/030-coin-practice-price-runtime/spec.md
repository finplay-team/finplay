# Spec: 코인 튜토리얼 가상 가격 실행 환경

> **후속 delta:** `039-tutorial-flow-redesign`은 샘플 종목 attempt에서 차트·체결·관찰·재시작 보상 매도가
> 하나의 canonical tutorial price를 사용하도록 확장한다. 이 문서의 일반 가격 세션 격리·pending 주문
> 취소/예약 반환 원칙은 그대로 상속한다.

> 상태: 2026-08-10 정책·계약 확정. production 구현은 후속 이슈로 분리한다.

## 개요

2차 MVP 코인 튜토리얼이 실제 빗썸 시세와 분리된 사용자·종목별 가상 가격으로 지정가 매수와 holding 관찰을 끝까지 수행하게 한다. 서버는 가격 세션의 생성 정보와 진행 위치를 DB에 저장하여 재기동 후에도 같은 시계열을 재현하고, 튜토리얼 주문만 전용 이벤트로 체결한다.

기존 `GET /api/education/practice/synthetic-prices/{instrumentId}`는 표시 전용 무상태 API로 호환 유지한다. 이 API의 배열은 체결·관찰·진행 evidence에 쓰지 않는다.

## 사용자 시나리오

- 로그인 사용자는 코인 종목의 ACTIVE 가상 가격 세션을 만들고 현재 가격과 진행 위치를 조회할 수 있다.
- 사용자는 세션 가격을 기준으로 교육 전용 지정가 BUY 주문을 만든 뒤, 3초마다 명시적으로 다음 tick을 요청할 수 있다.
- 지정가가 충족되면 같은 사용자·세션에 속한 튜토리얼 주문만 체결되고, 그 trade로 만든 holding의 관찰은 같은 세션 현재 가격을 사용한다.
- 마지막 tick까지 미체결인 튜토리얼 주문은 자동 취소되고 예약 현금은 정확히 한 번 반환된다.

## 요구사항

- [ ] COIN-PRICE-RUNTIME-001: 서버는 사용자·종목별 ACTIVE 세션을 최대 1개 허용하고 소유권을 검증한다.
- [ ] COIN-PRICE-RUNTIME-002: 세션은 서버 생성 `seed`, `generatorVersion`, `startPrice`, `currentTick`, `currentPrice`를 영속화하고 재기동 후 동일 가격열을 재현한다.
- [ ] COIN-PRICE-RUNTIME-003: 시작가는 생성 시점의 실제 유효 현재가를 한 번 anchor로 사용하고, 없으면 `10000`을 사용한다. 생성 이후 실제 가격원은 세션에 영향을 주지 않는다.
- [ ] COIN-PRICE-RUNTIME-004: 클라이언트는 `expectedTick`을 포함한 next-tick API를 명시적으로 호출하며 서버는 중복·누락·역순 진행을 거부한다.
- [ ] COIN-PRICE-RUNTIME-005: 가격열은 3초 간격 의미의 100개 tick(`0..99`)이며 tick 99 도달 시 세션은 `COMPLETED`가 된다.
- [ ] COIN-PRICE-RUNTIME-006: education 전용 지정가 BUY API는 ACTIVE 세션의 현재 가격을 검증하고 기존 주문·현금 예약·체결 원장을 재사용하되 주문에 `practicePriceSessionId`를 기록한다. side는 입력받지 않고 서버가 BUY로 고정하며, 세션별 PENDING 교육 주문은 최대 1건이다.
- [ ] COIN-PRICE-RUNTIME-007: next-tick은 세션 전용 가격 이벤트를 발행하고 같은 사용자·종목·세션의 PENDING 튜토리얼 주문만 체결한다. 일반 주문, 다른 사용자 주문, 다른 세션 주문은 건드리지 않는다.
- [ ] COIN-PRICE-RUNTIME-008: 마지막 tick 처리 트랜잭션은 남은 PENDING 튜토리얼 주문을 취소하고 예약 현금을 정확히 한 번 반환한 뒤 세션을 완료한다.
- [ ] COIN-PRICE-RUNTIME-009: holding 관찰은 요청 `holdingId`에서 buy trade와 order를 거슬러 세션을 서버가 찾고 ACTIVE 또는 COMPLETED 세션의 `currentPrice`를 사용한다. 클라이언트가 관찰용 세션 ID를 선택하지 않는다.
- [ ] COIN-PRICE-RUNTIME-010: 세션 없는 기존 시장가·실제 지정가로 생성된 holding은 기존 `PriceQueryService` 가격 경로를 그대로 사용한다.
- [ ] COIN-PRICE-RUNTIME-011: 튜토리얼 가격은 `PriceStore`에 기록하지 않고 `CryptoPriceUpdatedEvent`로 발행하지 않는다.
- [ ] COIN-PRICE-RUNTIME-012: 기존 무상태 합성 시세 API는 표시 전용으로 유지하며 새 세션과 seed·cursor·가격을 공유하지 않는다.

## 비즈니스 규칙

- 종목은 존재하고 `market=CRYPTO`, `tradable=true`여야 한다. 주식은 3차 MVP다.
- ACTIVE 세션은 `(user_id, instrument_id)`별 하나다. 완료 세션은 이 제한에 포함하지 않으며 새 세션을 만들 수 있다.
- 생성 직후 `currentTick=0`, `currentPrice=startPrice`다. next 요청의 `expectedTick`은 반드시 `currentTick + 1`이어야 하고 허용 범위는 `1..99`다.
- 생성기는 `generatorVersion`별 순수 결정 함수다. 같은 version·seed·startPrice·tick은 항상 같은 scale 8 가격을 반환한다. v1의 digest·변동률·반올림·하한 계산은 `plan.md`에 고정하며, 기존 version의 알고리즘은 변경하지 않고 새 version을 추가한다.
- 교육 지정가 주문은 BUY만 허용한다. 주문 가격·수량 검증, 계좌 예약, trade·holding 생성은 기존 코인 지정가 규칙을 상속한다.
- 같은 세션에 PENDING 교육 주문이 이미 있으면 새 주문은 409 `PRACTICE_LIMIT_ORDER_ALREADY_PENDING`으로 거부한다. 기존 주문이 체결된 뒤에는 ACTIVE 세션에서 새 주문 1건을 만들 수 있다.
- 세션 종료 후 next-tick 및 신규 튜토리얼 주문은 거부한다. COMPLETED 세션의 마지막 가격은 기존 세션 holding 관찰에는 계속 사용할 수 있다.
- 세션 가격 이벤트는 영속 세션 ID를 격리 키로 사용한다. 사용자 ID만 또는 종목 ID만으로 대상 주문을 고르지 않는다.

## 범위 제외

- production Java 코드와 Flyway migration 구현
- 기존 `/api/orders/limit`와 실제 코인 가격 이벤트 동작 변경
- 주식 튜토리얼 가상 시세(3차 MVP)
- OCO 생성·취소·트리거, 자동 scheduler 재생
- SELL 튜토리얼 지정가, 부분 체결, 프론트엔드 UI
- 기존 표시 전용 합성 시세 API 폐기 또는 응답 변경

## 완료 조건

- [ ] 생성·조회·next-tick·교육 지정가 API의 요청·응답·오류가 확정된다.
- [ ] 세션 스키마, 주문 귀속 FK, 상태 전이와 100틱 종료 규칙이 확정된다.
- [ ] 잠금 순서와 마지막 tick의 주문 취소·예약 반환 트랜잭션이 확정된다.
- [ ] 실제 가격원·일반 주문·사용자·세션 간 격리 테스트가 정의된다.
- [ ] holding 관찰의 세션 역추적과 기존 가격 경로 fallback이 정의된다.
- [ ] 후속 구현 순서가 세션 가격원 → 지정가 연결 → 관찰 연결 → #313 전체 흐름 검증으로 분리되고, #313의 재개 조건과 테스트 소유 범위가 확정된다.
