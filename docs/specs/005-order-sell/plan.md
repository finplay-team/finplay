# Plan: 시장가 매도 (FIFO lot 소비 · 실현손익)

## 관련 문서

- Spec: `./spec.md`
- 근거: GitHub 이슈 #41
- 선행 spec: `docs/specs/011-order-ledger-schema/`(엔티티 5종·마이그레이션 V10, dev에 merge됨), `docs/specs/004-order-buy/`(`OrderService`·`OrderController`·`PortfolioBuyService`·`Holding`·`HoldingLot` 구현 완료, PR #88)
- 관련 ADR: [ADR-0002](../../adr/0002-architecture.md)(도메인 간 참조는 service 레이어만 — `OrderService`는 portfolio 도메인 repository를 직접 주입하지 않는다), [ADR-0004](../../adr/0004-flyway-migrations.md)(이번 spec은 스키마 변경 없음 — `trade_allocations`는 V10에서 이미 생성됨, 새 마이그레이션 금지)
- PRD 근거: §4 ORD-001~006(매도 관점), §5 API 계약·공통 오류표, §6 데이터 모델(011에서 이미 반영), §7 트랜잭션 경계("시장가 매도: 주문+체결+FIFO 배분+holding+현금증가+실현손익 원자 처리")

## 미확정·PRD 불일치 (구현 착수 전 사람 확인 권장)

- **코인 최소주문금액(5,000원) 검증을 매도에도 적용**: PRD ORD-003은 "코인 주문금액은 최소 5,000원이다"를 매수·매도 구분 없이 나열하고("매수는 현금...", "매도는 보유수량..."처럼 별도 side를 명시한 다른 두 불릿과 달리 이 불릿엔 side qualifier가 없음), 이슈 #41 본문도 이 규칙을 명시적으로 언급하지 않는다. `spec.md`의 매도 ORD-003 항목도 "시장별 수량 형식 규칙은 매수와 동일"이라고만 적어 최소금액까지 포함하는지 문면상 불명확하다. 이 plan은 **매수와 동일하게 매도에도 최소주문금액 검증을 적용**한다(코인 소액 매도로 인한 dust 방지 목적상 대칭 적용이 합리적이라 판단). 사람이 보기에 매도에는 최소금액 제한이 없어야 한다면 이 검증만 제거하면 된다(아래 설계 노트 1의 공용 메서드 재사용 여부만 바꾸면 됨).
- **`Holding.averagePrice`는 매도 시 갱신하지 않는다**: PRD·이슈 어디에도 매도 시 평균단가 재계산 규칙이 없다(포트폴리오 조회 API는 006 범위). 일반적인 증권사 UX와 동일하게 "평균매입단가는 매수 시에만 갱신되고 매도로는 변하지 않는다"는 통상 규칙을 채택한다 — `Holding.applySell`은 `quantity`만 차감하고 `averagePrice`는 그대로 둔다. FIFO 원가(`unitCost`)와 별개로 관리되는 값이라는 점을 006 구현자가 인지해야 한다.
- **배분 수수료·원가 안분의 "원 단위 잔여 처리" 규칙**: 이슈 본문이 규칙 존재를 요구할 뿐 계산식을 주지 않아 아래 설계 노트 3에서 이 plan이 직접 확정한다(핵심 결정이므로 특히 리뷰 요망).

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | /api/orders | `OrderCreateRequest`(`side=SELL`) + Header `Idempotency-Key` | `OrderResponse`(201, `realizedPnl` 필드 추가) | 시장가 매도 즉시 전량 체결 + FIFO 배분 + 실현손익. 새 엔드포인트 아님 — 004가 만든 동일 `POST /api/orders`에 SELL 분기 추가. `side != BUY`면 400으로 거부하던 임시 분기(`OrderService.createBuyOrder` 61~63행)를 제거한다 |

- 요청 예시: `{"market":"STOCK","instrumentId":1,"side":"SELL","orderType":"MARKET","quantity":"10"}` — 요청 DTO·헤더 계약은 004와 완전히 동일(`OrderCreateRequest`·`Idempotency-Key` 필수 검증만, 재현 방지는 #22).
- 응답은 주문+체결 결과에 `realizedPnl`을 추가한다(BUY 응답은 계속 `null`).

## 입력 명세

004의 입력 명세(시장·종목ID·주문유형·수량·헤더 검증)를 그대로 재사용한다. 매도 전용으로 달라지는 부분만 표기한다.

| 필드 | 필수 | 검증 |
|---|---|---|
| `side` | 필수 | `SELL`이면 이 spec의 매도 분기로 진입(기존 400 거부 제거). `market`·`instrumentId`·`orderType`·`quantity` 형식 검증은 004와 100% 동일한 메서드를 재사용(신규 코드 없음) |
| `quantity` | 필수 | 형식 검증(정수/8자리) 동일. 추가로 **보유수량 ≤ 0 또는 요청수량 > 보유수량**이면 409 `INSUFFICIENT_QTY`(신규) |

## 설계 노트 (구현자가 임의로 해석하지 않도록 확정)

### 1. `OrderService` 재구성 — 공개 메서드 통합

- `createBuyOrder(...)`를 `createOrder(Long userId, String idempotencyKey, OrderCreateRequest request)`로 이름을 바꾸고, 메서드 시작부에서 `request.side()`로 분기한다. `OrderController.createOrder(...)`가 호출하는 메서드명도 `orderService.createOrder(...)`로 바꾼다.
- 두 side가 공유하는 준비 단계를 private 메서드로 뽑는다(중복 제거, C-002 최소 구현 위반 아님 — 이미 존재하는 로직의 재배치일 뿐 새 추상화 계층 추가가 아님):
  - `orderType == "MARKET"` 검증(공유, 최상단에서 side 무관하게 먼저 수행 — 매도도 지정가 요청 시 동일하게 422).
  - `Instrument` 조회(404) + 시장 일치 검증(400) — 공유.
  - `validateQuantityFormat(market, quantity)` — 공유, 코드 변경 없음.
  - 계좌 조회(`accountService.getAccountFor`) — 공유.
  - 코인 최소주문금액 검증 — 공유 메서드로 추출(현재 BUY 흐름에 inline돼 있는 `rawAmount` 비교를 `validateMinOrderAmount(Market market, BigDecimal rawAmount, Instrument instrument)`로 뽑아 양쪽에서 호출). 미확정 섹션 참조.
  - `priceQueryService.assertOrderable(instrument)` → `priceQueryService.getPrice(instrument)` → `amount`/`fee` 계산(FLOOR 규칙, 수수료율 상수 `STOCK_FEE_RATE`/`CRYPTO_FEE_RATE` 재사용 — ORD-004가 매수·매도 동일 수수료율이라고 명시) — 공유.
- side별로 갈라지는 지점: **가격/최소금액 검증 이후, Order 영속 이전**. BUY는 기존 현금 검증(`INSUFFICIENT_CASH`) 후 `Order.create`→`Trade.of`(realizedPnl=null)→`account.deductCash`→`PortfolioBuyService.applyBuyTrade`. SELL은 아래 순서.

### 2. SELL 분기 순서 (정확히 이 순서로 구현)

가격을 조회하기 *전에* 보유수량 검증부터 하면 불필요한 시세 조회를 피할 수 있어(BUY의 현금검증은 가격에 의존하지만 SELL의 수량검증은 가격과 무관), 계좌 조회 직후로 당긴다:

```
1. orderType == "MARKET" 검증 (공유, 최상단)
2. instrument 조회(404) + market 일치 검증(400) (공유)
3. validateQuantityFormat(market, quantity) (공유)
4. account = accountService.getAccountFor(userId, accountMarket) (공유)
5. holding = portfolioSellService.getHoldingOrThrow(account, instrument, quantity)
   → holding 없음 또는 holding.quantity < quantity 이면 BusinessException(INSUFFICIENT_QTY)
   → 이 시점까지 Order/Trade 어떤 것도 저장하지 않았으므로 "실패 시 무흔적" 자동 충족
6. priceQueryService.assertOrderable(instrument) → getPrice(instrument)
7. rawAmount = price * quantity; validateMinOrderAmount(market, rawAmount, instrument) (미확정 섹션 참조)
8. amount = floor(rawAmount); fee = floor(amount * feeRate) (BUY와 동일 반올림 규칙, RoundingMode.FLOOR)
9. user = userQueryService.getUser(userId); now = LocalDateTime.now(clock); requestHash 계산(BUY와 동일, side="SELL"이 자동 포함됨)
10. order = Order.create(user, account, instrument, SELL, MARKET, quantity, idempotencyKey, requestHash, now); orderRepository.save(order)
11. trade = Trade.of(order, account, instrument, SELL, price, quantity, amount, fee, /*realizedPnl=*/ null, now, now); tradeRepository.save(trade)
    // realizedPnl은 lot 배분이 끝난 뒤에만 계산 가능하므로 최초 저장 시 null. Trade는 setter가 없으므로
    // 아래 12단계 이후 신규 메서드 Trade.fillRealizedPnl(long)로 채운다("fill" 컨벤션 — conventions.md Entity 규칙).
12. allocation = portfolioSellService.applySellTrade(holding, trade, quantity, now)
    // FIFO lot 소비 + trade_allocations 저장 + holding.applySell (아래 설계 노트 3·4)
    // allocation = { totalAllocatedCost: long, totalAllocatedBuyFee: long }
13. realizedPnl = (amount - fee) - (allocation.totalAllocatedCost + allocation.totalAllocatedBuyFee)
14. trade.fillRealizedPnl(realizedPnl)
15. account.addCash(amount - fee); account.addRealizedPnl(realizedPnl)
16. return OrderResponse.of(order, trade)
```

전체가 하나의 `@Transactional`(기존 `createOrder`에 이미 걸려 있음, 새로 추가하지 않음) 안에서 실행되어 PRD §7 "주문+체결+FIFO 배분+holding+현금증가+실현손익 원자 처리"를 만족한다.

### 3. FIFO lot 소비 + 배분 수수료·원가 "원 단위 잔여 처리" 규칙 (핵심 — 이 순서·공식 그대로 구현)

`PortfolioSellService.applySellTrade(Holding holding, Trade sellTrade, BigDecimal sellQuantity, LocalDateTime now)`:

```
lots = holdingLotRepository
    .findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(holding.getId(), ZERO)
    // 실행시각(executedAt) 오름차순, 동시각이면 id 오름차순 — ORD-005 FIFO 순서 그대로

remainingToAllocate = sellQuantity
totalAllocatedCost = 0L
totalAllocatedBuyFee = 0L

for lot in lots:
    if remainingToAllocate <= 0: break

    allocatedQuantity = min(remainingToAllocate, lot.remainingQuantity)
    isLastAllocationForLot = (allocatedQuantity == lot.remainingQuantity)  // 이 배분으로 lot이 정확히 소진되는지

    if isLastAllocationForLot:
        // 이 lot에 대해 지금까지(다른 매도 건 포함) 배분된 누적값을 조회해, "진짜 원가/수수료 총액 - 누적 배분값"을
        // 이번 배분에 그대로 넘긴다. 원 단위 내림을 여러 번 반복하며 생기는 잔여(누적 낙전)가
        // lot이 완전히 소진되는 마지막 배분 한 번에 정확히 흡수되어, lot 단위로는 항상
        // buyTrade.amount == sum(allocatedCost), lot.buyFee == sum(allocatedBuyFee)가 성립한다.
        previousCost = tradeAllocationRepository.sumAllocatedCostByHoldingLotId(lot.getId())
        previousBuyFee = tradeAllocationRepository.sumAllocatedBuyFeeByHoldingLotId(lot.getId())
        allocatedCost = lot.buyTrade.amount - previousCost
        allocatedBuyFee = lot.buyFee - previousBuyFee
    else:
        // 부분 소비 — 수량 비례로 원 단위 내림(FLOOR). double 사용 금지, BigDecimal.divide(scale=0, FLOOR) 한 번으로 계산.
        allocatedCost = lot.unitCost.multiply(allocatedQuantity)
            .setScale(0, RoundingMode.FLOOR).longValueExact()
        allocatedBuyFee = BigDecimal.valueOf(lot.buyFee).multiply(allocatedQuantity)
            .divide(lot.originalQuantity, 0, RoundingMode.FLOOR).longValueExact()

    lot.consume(allocatedQuantity)   // remainingQuantity -= allocatedQuantity (신규 엔티티 메서드)
    holdingLotRepository.save(lot)

    tradeAllocationRepository.save(
        TradeAllocation.create(sellTrade, lot, allocatedQuantity, allocatedCost, allocatedBuyFee, now))

    totalAllocatedCost += allocatedCost
    totalAllocatedBuyFee += allocatedBuyFee
    remainingToAllocate -= allocatedQuantity

if remainingToAllocate > 0:
    // holding.quantity와 lot 잔여수량 합계가 어긋난 정합성 오류 — 정상 흐름에서 도달 불가(사전에 5단계에서
    // holding.quantity로 검증 완료). 사용자 입력 오류가 아니므로 BusinessException이 아니라 방어적 IllegalStateException.
    throw new IllegalStateException("보유 lot 잔여수량 합계가 holding 보유수량과 일치하지 않습니다.")

holding.applySell(sellQuantity, now)   // quantity -= sellQuantity, 0이면 isActive=false (설계 노트 미확정 섹션 참조)
holdingRepository.save(holding)

return new SellAllocationDto(totalAllocatedCost, totalAllocatedBuyFee)
```

- **왜 마지막 배분에서만 조회하는가**: 같은 lot이 여러 번(서로 다른 매도 트랜잭션)에 걸쳐 부분 소비될 수 있다. 매번 비례식으로 내림하면 낙전(반올림 손실)이 누적돼 `sum(allocatedCost) < buyTrade.amount`로 어긋날 수 있다. lot이 완전히 소진되는 마지막 배분에서만 "총액 - 누적 배분액"을 계산해 정확히 맞추면, 부분 소비가 몇 번 걸치든 lot 단위 합계가 항상 원 단위로 정확히 일치한다(spec.md 비즈니스 규칙 "lot의 잔여수량은 배분 합계와 항상 일치" — 이 규칙을 금액에도 그대로 적용한 것). 하나의 매도가 여러 lot을 소비해도 각 lot은 이 규칙을 독립적으로 만족한다.
- **매도금액·매도수수료는 배분하지 않는다**: `trades.fee`·`trades.amount`는 매도 체결 1건당 이미 확정된 단일 값이라 lot별로 쪼갤 필요가 없다(스키마에도 `trade_allocations`에 매도 관련 금액 컬럼이 없다 — `allocated_cost`·`allocated_buy_fee`만 존재). 실현손익도 매도 1건 단위로 한 번만 계산해 `trades.realized_pnl`에 저장한다(배분 행 단위 실현손익 컬럼 없음).

### 4. 실현손익 계산 공식 (고정 — 이 순서 그대로)

```
realizedPnl = (매도금액 - 매도수수료) - (배분된 매수원가 합 + 배분된 매수수수료 합)
            = (sellTrade.amount - sellTrade.fee) - (totalAllocatedCost + totalAllocatedBuyFee)
```

- 계산은 `long`(원 단위) 뺄셈만 사용한다. 음수(손실)도 그대로 저장한다(`accounts.realized_pnl`·`trades.realized_pnl` 모두 부호 있는 정수).
- `Trade.realizedPnl`은 생성 시 `null`로 두고(BUY와 동일한 팩토리 `Trade.of` 재사용), FIFO 배분이 끝난 뒤 신규 메서드 `Trade.fillRealizedPnl(long)`로 채운다. 이미 값이 있는데 다시 호출하면 방어적으로 `IllegalStateException`(엔티티 불변 원장 원칙 보호 — spec.md "체결과 lot 배분 기록은 수정·삭제하지 않는다"와 모순되지 않도록, 이 메서드는 "생성 직후 1회 채움"만 허용하고 이후 재호출은 막는다).

### 5. 신규 엔티티 상태 변경 메서드 (setter 금지 컨벤션 준수)

| 엔티티 | 메서드 | 동작 |
|---|---|---|
| `Trade`(order.domain) | `fillRealizedPnl(long realizedPnl)` | `this.realizedPnl != null`이면 `IllegalStateException`, 아니면 대입 |
| `Account`(account.domain) | `addCash(long amount)` | `cashBalance += amount` |
| `Account`(account.domain) | `addRealizedPnl(long amount)` | `realizedPnl += amount`(음수 허용) |
| `Holding`(portfolio.domain) | `applySell(BigDecimal quantity, LocalDateTime now)` | `newQuantity = this.quantity - quantity`; `newQuantity < 0`이면 `IllegalStateException`(방어적, 정상 흐름 도달 불가); `this.quantity = newQuantity`; `newQuantity == 0`이면 `isActive = false`; `averagePrice`는 변경하지 않음(미확정 섹션 참조); `updatedAt = now` |
| `HoldingLot`(portfolio.domain) | `consume(BigDecimal quantity)` | `newRemaining = remainingQuantity - quantity`; `< 0`이면 `IllegalStateException`(방어적); `remainingQuantity = newRemaining` |

### 6. 신규 Repository 메서드

- `HoldingLotRepository`: `List<HoldingLot> findByHoldingIdAndRemainingQuantityGreaterThanOrderByExecutedAtAscIdAsc(Long holdingId, BigDecimal remainingQuantity)` — 호출 시 두 번째 인자로 `BigDecimal.ZERO` 전달.
- `TradeAllocationRepository`: 아래 두 개를 `@Query` JPQL로 추가(파생 쿼리로 불가능한 SUM 집계).
  ```java
  @Query("select coalesce(sum(a.allocatedCost), 0) from TradeAllocation a where a.holdingLot.id = :holdingLotId")
  long sumAllocatedCostByHoldingLotId(@Param("holdingLotId") Long holdingLotId);

  @Query("select coalesce(sum(a.allocatedBuyFee), 0) from TradeAllocation a where a.holdingLot.id = :holdingLotId")
  long sumAllocatedBuyFeeByHoldingLotId(@Param("holdingLotId") Long holdingLotId);
  ```
- `HoldingRepository`: 신규 메서드 없음(`findByAccountIdAndInstrumentId`을 004에서 이미 추가함, 그대로 재사용).

### 7. `PortfolioSellService` (portfolio.service, 신규 — `PortfolioBuyService`와 별도 클래스)

- BUY와 동일하게 매도 전용 단일 책임 서비스를 새로 만든다(하나의 서비스가 매수·매도를 모두 처리하지 않는다 — conventions.md 금지 패턴 "하나의 service가 여러 책임을 모두 처리" 방지 차원에서 액션 단위로 분리한 004의 선례를 따른다).
- 공개 메서드 2개:
  - `Holding getHoldingOrThrow(Account account, Instrument instrument, BigDecimal requiredQuantity)` — `holdingRepository.findByAccountIdAndInstrumentId(...)`가 없거나 `holding.quantity < requiredQuantity`이면 `BusinessException(ErrorCode.INSUFFICIENT_QTY)`. (기존 `AccountService.getAccountFor`가 이미 "조회 후 없으면 BusinessException"을 서비스가 던지는 선례이므로 동일 패턴.)
  - `SellAllocationDto applySellTrade(Holding holding, Trade sellTrade, BigDecimal sellQuantity, LocalDateTime now)` — 위 설계 노트 3 전체.
- `@Transactional` 애노테이션은 걸지 않는다(트랜잭션 경계는 `OrderService`가 소유 — 004의 `PortfolioBuyService`와 동일 원칙, 전파는 `REQUIRED` 기본값).
- `SellAllocationDto(long totalAllocatedCost, long totalAllocatedBuyFee)` — record, `portfolio.service` 패키지에 둔다(`PriceQuoteDto`가 `market.service`에 있는 기존 선례와 동일하게 서비스 패키지에 바로 배치, 별도 `dto` 하위 패키지 만들지 않음 — 이 DTO는 controller와 통신하지 않는 서비스 간 내부 전달용이라 `~Dto` 접미사만 적용).

### 8. `OrderResponse` 확장

- `realizedPnl` 필드(`Long`, nullable — BUY는 `null`) 추가. `OrderResponse.of(Order, Trade)`가 `trade.getRealizedPnl()`을 그대로 전달(이미 `Trade`에 필드가 있으므로 신규 계산 없음).

## 패키지 구성

| 구성 요소 | 위치 | 역할 |
|---|---|---|
| `OrderService.createOrder`(기존 `createBuyOrder` 이름 변경 + 리팩터링) | `order.service` | 공유 검증 추출 + side 분기(BUY 기존 로직 유지, SELL 신규) |
| `OrderController`(기존 파일 수정 — 호출 메서드명만 변경) | `order.controller` | `orderService.createBuyOrder(...)` → `orderService.createOrder(...)` |
| `OrderResponse`(기존 파일 수정) | `order.dto.response` | `realizedPnl` 필드 추가 |
| `Trade.fillRealizedPnl`(기존 파일 수정) | `order.domain` | 설계 노트 5 |
| `Account.addCash`/`addRealizedPnl`(기존 파일 수정) | `account.domain` | 설계 노트 5 |
| `Holding.applySell`(기존 파일 수정) | `portfolio.domain` | 설계 노트 5 |
| `HoldingLot.consume`(기존 파일 수정) | `portfolio.domain` | 설계 노트 5 |
| `HoldingLotRepository`(기존 파일 수정) | `portfolio.repository` | 설계 노트 6 |
| `TradeAllocationRepository`(기존 파일 수정) | `portfolio.repository` | 설계 노트 6 |
| `PortfolioSellService`(신규) | `portfolio.service` | 설계 노트 7 |
| `SellAllocationDto`(신규) | `portfolio.service` | 설계 노트 7 |

`OrderService`가 `PortfolioSellService`(portfolio 도메인 서비스)만 통해 `Holding`/`HoldingLot`/`TradeAllocation`을 다루고 해당 도메인 repository를 직접 주입하지 않는 것은 004가 세운 선례(`PortfolioBuyService`) 그대로다(ADR-0002 준수).

## 데이터 모델

스키마 변경 없음 — `docs/specs/011-order-ledger-schema/`의 V10 마이그레이션·엔티티 5종(`Order`·`Trade`·`Holding`·`HoldingLot`·`TradeAllocation`)을 그대로 사용한다. 이번 spec은 엔티티에 상태 변경 메서드만 추가하고, Repository에 조회 메서드만 추가한다(위 설계 노트 5·6). 새 컬럼·새 테이블 없음(ADR-0004).

## 테스트 계획

- 단위(Mockito, `OrderServiceTest` 확장): 004가 이미 작성한 BUY 테스트는 유지하되 mock 호출부만 `createOrder(...)`로 갱신. SELL 케이스 추가:
  - 다중 매수 후 부분 매도 시 FIFO 순서(먼저 산 lot부터)로 `holdingLotRepository`/`tradeAllocationRepository` 저장 호출 검증.
  - 하나의 매도가 여러 lot을 소비하는 케이스 — 배분 수량 합 = 매도수량, 각 lot `remainingQuantity` 갱신 검증.
  - 실현손익 계산식 검증(설계 노트 4의 공식대로 `trade.fillRealizedPnl` 인자 검증).
  - 수수료·원가 안분 "원 단위 잔여 처리" 검증 — 나눠떨어지지 않는 수량(예: 수수료 100원을 3개 lot에 비례 배분 시 마지막 lot이 남은 원 단위를 흡수)을 경계값으로 명시적 테스트.
  - 보유수량 초과 매도 → 409 `INSUFFICIENT_QTY`, `orderRepository`/`tradeRepository`/`holdingLotRepository`/`tradeAllocationRepository` 저장 계열 mock **미호출** 검증(spec.md "흔적 없음").
  - 전량 매도 시 `holding.applySell` 이후 `isActive=false`(mock 검증 또는 실제 `Holding` 인스턴스 상태 검증).
- 단위(신규 `PortfolioSellServiceTest`): FIFO 단일 lot 차감, 다중 lot 소비, 정확히 소진, 일부 남김 각각 검증(spec.md 완료조건과 1:1 대응). `getHoldingOrThrow` 없음/부족 시 예외.
- 단위(엔티티): `HoldingLot.consume` 경계(정확히 소진 시 0, 초과 소비 시 예외), `Holding.applySell`(전량 매도 시 `isActive=false`, 부분 매도 시 유지, `averagePrice` 불변), `Trade.fillRealizedPnl`(정상 1회, 재호출 시 예외), `Account.addCash`/`addRealizedPnl`(양수·음수).
- 슬라이스 `@WebMvcTest`(`OrderControllerTest` 확장): SELL 요청도 201과 `realizedPnl` 필드까지 `jsonPath` 검증(서비스는 mock).
- 통합(Testcontainers, 신규 `OrderSellIntegrationTest`, 핵심 시나리오 — spec.md 완료조건 그대로):
  - 다중 매수 후 부분 매도 시 FIFO 순서로 lot이 차감되고 배분이 저장되는지.
  - 하나의 매도가 여러 lot을 소비하는 케이스의 배분·실현손익 값 검증.
  - 실현손익 공식(설계 노트 4) 검증.
  - 보유수량 초과 매도 409 + 주문·체결·holding·lot·배분·계좌 테이블 무흔적 검증.
  - 전량 매도 시 holding 잔량 0(`isActive=false`) 처리 검증.
  - `./gradlew build` 전체 통과 확인(스팟버그스·JaCoCo 포함).

## 문서 갱신

`OrderController`의 `POST /api/orders` 계약이 SELL을 포함하도록 바뀌므로(응답에 `realizedPnl` 추가, `side=SELL`이 더 이상 400 오류 케이스가 아니라 정상 처리 + 새 오류 케이스 409 `INSUFFICIENT_QTY` 추가) `docs/api-routes.md`·`docs/api-contracts.md`를 이번 tasks 마지막 항목에서 같은 커밋으로 갱신한다(동기화 모드, planner 재투입 또는 `/feature` 마무리 단계). 특히 `api-contracts.md` 222행의 오류 목록에서 "`side=SELL`은 400 `VALIDATION_ERROR`" 문구를 제거하고 409 `INSUFFICIENT_QTY`를 추가해야 한다.
