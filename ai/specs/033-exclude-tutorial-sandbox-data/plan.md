# Plan: 튜토리얼 샌드박스 매매·완료 보상의 포트폴리오·투자일기·랭킹 제외

## 관련 문서

- Spec: `./spec.md`
- 배경(변경하지 않음): `ai/specs/031-tutorial-sandbox-instruments`(샌드박스 종목·항시 시세·매도 단계·
  5분 제한의 정본, `instruments.is_tutorial_sample` 컬럼 출처), `ai/specs/026-market-order-practice-tutorial`
  (2·3단계 chain 해석, 이슈 #343 튜토리얼 완료 보상 500만원)
- 관련 ADR: ADR-0002(레이어드 경계), ADR-0003(테스트 전략), ADR-0004(Flyway migration 전용, 머지된
  파일 수정 금지 + ADR-0021 §결정7의 무중단 배포 제약)
- 영향받는 기존 spec(계약을 바꾸지 않고 내부 필터만 추가): `006-portfolio-query`(`GET /api/holdings`),
  `007-journal`(`GET /api/journal`), `014-ranking`(`GET /api/rankings`, `GET /api/rankings/me`)

**새 ADR은 만들지 않는다.** 이 spec이 건드리는 것은 (1) 기존 조회 쿼리에 조건절을 하나 추가하는 것,
(2) 기존 서비스 메서드 내부에 조건 분기를 추가하는 것, (3) 값을 바로잡는 1회성 데이터 마이그레이션이다.
셋 다 ADR-0002가 이미 규정한 레이어드 경계 안에서 끝나고, API 계약(요청·응답 필드)도 바꾸지 않는다.
`031`의 plan.md가 같은 논리로 "새 ADR 불필요"를 판단한 선례와 같은 근거다. 이슈 #343(보상)을 다루는
ADR도 존재하지 않는다(`ai/adr/` 확인, 026/031 plan.md에만 설계가 남아 있다) — 이 spec이 그와 충돌할
결정을 내리지 않으므로 새로 만들 필요도 없다.

## 도메인 경계

- 포트폴리오 필터(SANDBOX-EXCL-001)는 `portfolio` 도메인이 소유한다(`HoldingRepository` 쿼리 수정,
  기존 계층 그대로).
- 투자일기 필터(SANDBOX-EXCL-002)는 `journal` 도메인이 소유한다(`BuyTradeJournalRepositoryImpl`/
  `SellTradeJournalRepositoryImpl`의 QueryDSL 조건 수정).
- 랭킹 대상자·status 필터(SANDBOX-EXCL-003)는 `order` 도메인이 소유한 `TradeRepository`에 조건을
  추가한다 — `ranking` 도메인은 이미 `TradeService.getSoldAccountIds`/`hasSellHistory`/
  `hasAnySellHistory`를 경유해서만 접근하므로(ADR-0002, 기존 `RankingRebuildService` 헤더 주석) 이
  spec으로 도메인 경계가 바뀌지 않는다.
- 현금·평가자산 보정(SANDBOX-EXCL-006·007·008)은 `account` 도메인이 `sandboxCashAdjustment` 컬럼·
  누적 메서드를 소유한다(`cashBalance`·`realizedPnl`과 같은 위치, `Account` 엔티티). 이 값을 실제로
  누적시키는 호출은 `order`(`OrderExecutionService`, `LimitOrderFillService`)·`portfolio`
  (`PortfolioSellService`)·`education.marketpractice`(`PracticeHoldingReflectionService`) 네 곳에
  흩어져 있는데, 이는 이미 그 네 곳이 `account.addCash`/`deductCash`/`confirmReservedCash`를 호출하는
  기존 코드 구조를 그대로 따르는 것이다 — 새로운 도메인 간 호출을 추가하지 않고, 기존에 이미 계좌를
  참조하던 지점에 메서드 호출 하나씩만 더한다.
- 쓰기 시점 필터(SANDBOX-EXCL-004)는 `order`(`OrderExecutionService`)와 `portfolio`
  (`PortfolioSellService`) 두 곳에 각각 있다 — 015-limit-order가 지정가 매도를
  `PortfolioSellService.finalizeSellRealizedPnl`로, 시장가 매도를 `OrderExecutionService.createSellOrder`
  인라인 계산으로 이미 분리해뒀으므로 이 spec도 그 경계를 그대로 따른다(하나로 합치는 리팩터링은
  이 spec의 범위가 아니다).
- 백필(SANDBOX-EXCL-005·008)은 Flyway SQL 마이그레이션 하나로 끝낸다 — 별도 배치 서비스·스케줄러·
  1회성 실행 플래그를 두지 않는다(아래 "5. 백필 설계" 참고).

## 1. 포트폴리오 필터 — `HoldingRepository.findAllByAccountIdAndIsActiveTrue`

**결정: JPQL `WHERE` 절에 `AND h.instrument.isTutorialSample = false`를 추가한다.**

```java
@Query("SELECT h FROM Holding h JOIN FETCH h.instrument WHERE h.account.id = :accountId AND h.isActive = true "
	+ "AND h.instrument.isTutorialSample = false ORDER BY h.instrument.symbol ASC")
List<Holding> findAllByAccountIdAndIsActiveTrue(@Param("accountId") Long accountId);
```

- 이 메서드의 유일한 호출자는 `HoldingService.getHoldings`(포트폴리오 API)와
  `HoldingValuationService.evaluateHoldings`(그 안에서 호출되는 평가손익 계산) — 이 메서드 하나만 고치면
  두 소비자 모두에 자동으로 적용된다(호출 경로 확인 완료, `Grep` 결과 두 파일뿐).
  `HoldingService.findHoldingId`/`findHoldingForOwner`는 이 메서드를 쓰지 않고 각각
  `findByAccountIdAndInstrumentId`/`findById`를 직접 쓰므로 영향받지 않는다(SANDBOX-EXCL-001 후단이
  요구하는 "튜토리얼 내부 조회는 그대로" 조건이 코드 구조상 이미 성립).
- 메서드 이름(`findAllByAccountIdAndIsActiveTrue`)은 그대로 둔다 — 파생 쿼리 이름이 아니라 `@Query`
  수동 지정이므로 이름과 실제 조건이 완전히 일치할 필요는 없다(기존 코드도 `ORDER BY` 절을 이름에
  반영하지 않는 관례, PR #97 리뷰 권장사항 1과 동일 선례).

## 2. 투자일기 필터 — `BuyTradeJournalRepositoryImpl`/`SellTradeJournalRepositoryImpl`

**결정: `findByAccountIdWithCursor`의 `BooleanBuilder` 초기 조건에 `journal.buyTrade.instrument.
isTutorialSample.eq(false)`(매도는 `sellTrade.instrument...`)를 추가한다.**

```java
BooleanBuilder condition = new BooleanBuilder(
	journal.buyTrade.account.id.eq(accountId)
		.and(journal.buyTrade.instrument.isTutorialSample.eq(false)));
```

- QueryDSL Q타입(`QBuyTradeJournal`/`QSellTradeJournal`)은 `Trade`·`Instrument` 엔티티 필드를 그대로
  따라가므로 `Instrument.isTutorialSample` 필드(031에서 이미 추가됨, boolean getter
  `isTutorialSample()`)가 Q타입에도 이미 생성돼 있다 — 어노테이션 프로세서 재실행만 필요하고 엔티티
  변경은 없다.
- 이 메서드의 유일한 호출자는 `JournalService.getMyJournalEntries`(목록 조회) — 상세 조회·작성·수정은
  `findByBuyTradeId`/`findBySellTradeId`/`existsBy...`(단순 파생 쿼리, `tradeService.getOwnedTrade`로
  이미 소유권만 검증)를 쓰므로 이 변경의 영향을 받지 않는다(spec "범위 제외"와 일치).

## 3. 랭킹 필터 — 대상자 판정과 쓰기 시점 반영

### 3-1. 대상자 판정 (`TradeRepository.findDistinctAccountIdsBySideAndMarket`)

**결정: JPQL에 `AND t.instrument.isTutorialSample = false`를 추가한다.**

```java
@Query("SELECT DISTINCT t.account.id FROM Trade t WHERE t.side = :side AND t.account.market = :market "
	+ "AND t.instrument.isTutorialSample = false")
List<Long> findDistinctAccountIdsBySideAndMarket(@Param("side") OrderSide side, @Param("market") Market market);
```

- 이 메서드는 `RankingRebuildService.rebuild`(재구성 배치)에서만 쓰인다 — `TradeService.
  getSoldAccountIds`가 유일한 경유지(코드 확인 완료).

**확정(오케스트레이터가 사용자와 확인 완료): `existsByAccountIdAndSide`/`existsBySideAndAccountMarket`도
같은 조건으로 함께 고친다.** 이 두 메서드는 각각 `TradeService.hasSellHistory`(RANK-002 "내 랭킹"
status 판정)·`hasAnySellHistory`(RANK-001 목록 status 판정)를 거쳐 `RankingService`가 쓴다(PRD
RANK-001·RANK-002). 대상자 판정만 고치고 이 둘을 남겨두면, `rebuild`가 만든 ZSET에는 그 계좌가 없는데
`existsBy...`는 "있음"을 반환해 `status`·순위 조회 결과가 서로 모순되는 상태가 생긴다(예: 랭킹 목록엔
없는데 "내 랭킹" 조회는 대상자처럼 응답). 같은 판별 기준(`isTutorialSample`)을 세 지점에 동일하게
적용해 이 불일치를 없앤다.

```java
// TradeRepository — 기존 두 메서드는 그대로 두지 않고 조건을 추가해 교체한다(호출자가 TradeService
// 하나뿐이라 시그니처를 바꿔도 컴파일 타임에 안전하게 전파된다 — 파생 쿼리 이름만 바뀐다).
boolean existsByAccountIdAndSideAndInstrument_IsTutorialSampleFalse(Long accountId, OrderSide side);
boolean existsBySideAndAccountMarketAndInstrument_IsTutorialSampleFalse(OrderSide side, Market market);
```

```java
// TradeService — 호출부(RankingService)는 이 두 메서드의 시그니처를 이미 그대로 쓰고 있으므로 내부
// 구현만 바뀐다. RankingService쪽 변경은 없다.
public boolean hasSellHistory(Long accountId) {
	return tradeRepository.existsByAccountIdAndSideAndInstrument_IsTutorialSampleFalse(accountId, OrderSide.SELL);
}

public boolean hasAnySellHistory(Market market) {
	return tradeRepository.existsBySideAndAccountMarketAndInstrument_IsTutorialSampleFalse(OrderSide.SELL, market);
}
```

  기존 `existsByAccountIdAndSide`/`existsBySideAndAccountMarket` 메서드 자체는 `TradeRepository`에서
  제거한다 — 다른 호출자가 없으므로(코드 확인 완료, `TradeService`가 유일한 호출자) 죽은 메서드로
  남겨두지 않는다. 이름이 길어지는 트레이드오프는 `@Query` 대신 파생 쿼리를 유지해 QueryDSL 없이도
  조건을 표현할 수 있다는 이점과 맞바꾼다 — 기존 두 메서드도 파생 쿼리였다.

### 3-2. 쓰기 시점 — `Account.realizedPnl` 반영 건너뛰기

**결정: 두 매도 체결 경로 모두에서 `instrument.isTutorialSample()`이면 `account.addRealizedPnl(...)`을
호출하지 않는다. `trade.fillRealizedPnl(...)`은 항상 호출한다.**

- `PortfolioSellService.finalizeSellRealizedPnl`(지정가 매도 체결 공통 경로):

```java
public long finalizeSellRealizedPnl(
	Account account, Trade sellTrade, long amount, long fee, SellAllocationDto allocation) {
	long realizedPnl = (amount - fee) - (allocation.totalAllocatedCost() + allocation.totalAllocatedBuyFee());
	sellTrade.fillRealizedPnl(realizedPnl);
	account.addCash(amount - fee);
	if (!sellTrade.getInstrument().isTutorialSample()) {
		account.addRealizedPnl(realizedPnl);
	}
	return realizedPnl;
}
```

- `OrderExecutionService.createSellOrder`(시장가 매도 인라인 경로, 152~160행):

```java
trade.fillRealizedPnl(realizedPnl);
account.addCash(pricing.amount() - pricing.fee());
if (!instrument.isTutorialSample()) {
	account.addRealizedPnl(realizedPnl);
}
eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()));
```

  `RealizedPnlUpdatedEvent`는 조건 없이 그대로 발행한다 — 리스너가 어차피 DB에서 최신
  `account.realizedPnl`을 다시 읽어(코드 주석 "동시성 경합 Decision Gate") 랭킹 ZSET에 반영하므로,
  샌드박스 매도라 값이 바뀌지 않았어도 이벤트 발행 자체는 멱등하고 해롭지 않다. 조건부로 이벤트
  발행까지 건너뛰면 "이 계좌가 방금 실제 매도도 같이 했는지"를 추가로 알아야 하는 불필요한 복잡도가
  생긴다.
- `account.addCash(...)`는 두 경로 모두 종목 종류와 무관하게 항상 호출한다 — 샌드박스 매도도 사용자의
  실제 `cashBalance`를 움직이는 거래이고(spec "범위 제외"에서 확정), 현금 반영을 건너뛰면 holding
  수량은 줄었는데 현금이 늘지 않는 원장 불일치가 생긴다.
- `Trade.realizedPnl`은 종목 종류와 무관하게 항상 채워진다 — 이 값이 SANDBOX-EXCL-005 백필의
  재계산 소스이자, `031`의 튜토리얼 evidence 판정·`PostSellFeedbackService` 등 기존 소비자의 계약이기
  때문이다(변경하면 그 소비자들이 깨진다).

## 4. 현금·평가자산·수익률 배제 — `sandboxCashAdjustment` (사용자 확정, 2026-08-13)

> **결정 변경 이력**: 최초 안(이 문서 작성 시점 1차)은 `cashBalance`/`totalValue`/`returnRate`의 소급
> 정정을 "현금은 기원을 분리할 원장이 없다"는 이유로 범위 제외했었다. 오케스트레이터가 사용자에게
> 확인한 결과 **"튜토리얼 데이터는 절대 순위나 포트폴리오에 올라가면 안 된다"는 요구가 최대 배제를
> 의미**함이 명확해져, 이 절을 전면 재설계했다. 아래는 그 확정 설계다.

### 4-1. 왜 순수 "읽기 시점 필터"가 안 되는가

`realized_pnl`(랭킹 score)은 `trades.realized_pnl`이라는 체결 단위 불변 원장이 이미 있어서 "실제 종목
매도만 골라 합산"이라는 조건부 집계로 정확히 재현할 수 있다. 반면 `cashBalance`는 매수 차감·매도
입금·튜토리얼 보상이 전부 같은 스칼라 컬럼(`accounts.cash_balance`) 하나에 누적되고, 그 값 자체는
"어느 금액이 어느 사건에서 왔는지"를 담고 있지 않다. 조회 시점에 조건을 걸어 걸러낼 대상(행)이 없다
— 걸러낼 "행"이 아니라 걸러낼 "금액"이기 때문이다. 그래서 매수·매도·보상이 실제로 `cashBalance`를
바꾸는 매 순간, 그 변동분이 샌드박스 기원인지 아닌지를 **그 순간에** 별도로 같이 기록해 둬야 사후에
분리할 수 있다.

### 4-2. 설계: 누적 오프셋 컬럼 + 표시 시점 차감

**결정: `accounts`에 `sandbox_cash_adjustment BIGINT NOT NULL DEFAULT 0` 컬럼을 추가한다. `cashBalance`를
변경하는 모든 지점에서, 그 변경이 샌드박스 기원이면 **정확히 같은 부호·같은 금액**을 이 컬럼에도 함께
누적한다. `cashBalance` 자체는 절대 건드리지 않는다(주문 가능 잔고는 항상 정확해야 하므로). 평가자산
(`totalValue`)을 계산할 때만 `totalValue - sandboxCashAdjustment`로 표시용 값을 만든다.**

```java
// Account.java — cashBalance·realizedPnl과 나란히 둔다
@Column(name = "sandbox_cash_adjustment", nullable = false)
private long sandboxCashAdjustment;

public void addSandboxCashAdjustment(long amount) {
	this.sandboxCashAdjustment += amount;
}
```

`Account` 생성자(`private Account(...)`)에도 `this.sandboxCashAdjustment = 0L;`을 추가한다(다른 필드와
동일한 관례).

### 4-3. 왜 이 값을 빼면 정확히 "샌드박스가 없었다면"이 재현되는가 (증명)

`sandboxCashAdjustment`를 "`cashBalance`에 지금까지 반영된 금액 중 샌드박스 기원 누적 순액"으로
정의하면, 임의 시점에 다음이 항상 성립한다:

```
cashBalance = cashBalance_실거래만있었다면 + sandboxCashAdjustment
⇒ cashBalance - sandboxCashAdjustment = cashBalance_실거래만있었다면
```

holdingsValue는 이미 SANDBOX-EXCL-001(포트폴리오 필터)로 실제 종목 보유만 남기므로 샌드박스 보유의
평가금액은 애초에 0으로 취급된다. 따라서:

```
totalValue = cashBalance + holdingsValue(실제 종목만)
totalValue - sandboxCashAdjustment = cashBalance_실거래만있었다면 + holdingsValue(실제 종목만)
                                    = "샌드박스가 전혀 없었을 때의 평가자산"
```

**보상금을 실제 투자에 쓴 경우도 자동으로 올바르게 작동한다** — 예시로 검증한다. 시드머니 1000만원,
튜토리얼 보상 500만원 수령 후(`sandboxCashAdjustment = +500만`, `cashBalance = 1500만`), 그 500만원
전액으로 실제 종목을 사서(`cashBalance -= 500만` → `1000만`, 이 매수는 실제 종목이라
`sandboxCashAdjustment` 변화 없음, 그대로 `+500만`) 나중에 그 종목이 600만원으로 평가되면
(`holdingsValue = 600만`): `totalValue = 1000만 + 600만 = 1600만`,
`displayTotalValue = 1600만 - 500만 = 1100만`. 시드머니 1000만원 대비 **+100만원**만 수익률에
반영된다 — 정확히 그 실거래가 낸 진짜 수익(600만 평가 - 500만 매수원가 = 100만)이다. 보상 원금
500만원은 계속 제외된 채로 남고, 그 돈으로 낸 실제 성과만 반영된다는 spec의 사용자 시나리오 요구와
수식으로 일치한다. 이 값이 정확히 "투자 원금 기준선 조정"과 동치인 이유는, `totalValue`에서 빼는 것과
분모(시드머니)에 더하는 것이 대수적으로 같은 효과를 내되(`(totalValue - adj - seed) / seed` vs
`(totalValue - seed - adj) / (seed + adj)`), 전자가 분모를 시드머니로 고정해 두 시장(STOCK/CRYPTO)
합산·`PortfolioService`의 기존 `returnRate` 계산 구조를 전혀 바꾸지 않고 그대로 재사용할 수 있어 더
단순하기 때문이다(아래 4-5 참고). 두 방식 모두 "보상 원금 제외, 실거래 성과 반영"이라는 목표는
동일하게 달성한다.

### 4-4. 누적 대상 — `cashBalance`를 바꾸는 5개 지점 전부

`cashBalance`(또는 `reservedCash` 확정을 통해 `cashBalance`)를 실제로 바꾸는 지점은 저장소 전체에
정확히 5곳이다(코드 확인 완료, `deductCash`/`addCash`/`confirmReservedCash` 호출부 전수 조사).
`reserveCash`/`releaseReservedCash`(지정가 생성·취소·정정)는 예약만 걸고 풀 뿐 `cashBalance` 자체를
바꾸지 않으므로 대상이 아니다.

| # | 위치 | 기존 호출 | 종목/맥락 | 추가할 호출 |
|---|---|---|---|---|
| 1 | `OrderExecutionService.createBuyOrder` | `account.deductCash(cashRequired)` | 시장가 매수 | `if (instrument.isTutorialSample()) account.addSandboxCashAdjustment(-cashRequired);` |
| 2 | `OrderExecutionService.createSellOrder` | `account.addCash(pricing.amount() - pricing.fee())` | 시장가 매도 | `if (instrument.isTutorialSample()) account.addSandboxCashAdjustment(pricing.amount() - pricing.fee());` |
| 3 | `PortfolioSellService.finalizeSellRealizedPnl` | `account.addCash(amount - fee)` | 지정가 매도 체결(`LimitOrderFillService.fillSell`이 호출) | `if (sellTrade.getInstrument().isTutorialSample()) account.addSandboxCashAdjustment(amount - fee);` |
| 4 | `LimitOrderFillService.fillBuy` | `account.confirmReservedCash(amount + fee)` | 지정가 매수 체결(`030`의 코인 지정가 세션 포함) | `if (instrument.isTutorialSample()) account.addSandboxCashAdjustment(-(amount + fee));` |
| 5 | `PracticeHoldingReflectionService.payTutorialCompletionReward` | `account.addCash(TUTORIAL_COMPLETION_REWARD_AMOUNT)` | 튜토리얼 완료 보상(이 메서드는 항상 샌드박스 기원 — 종목 조건 불필요) | `account.addSandboxCashAdjustment(TUTORIAL_COMPLETION_REWARD_AMOUNT);`(조건 없이 항상) |

- #1·#2는 SANDBOX-EXCL-004(랭킹 실현손익 조건부 반영)와 같은 메서드 안에 있으므로 같은 커밋에서
  `instrument.isTutorialSample()` 분기를 한 번만 계산해 두 용도(실현손익 skip, 현금 조정 누적)에
  같이 쓴다 — 조건 중복 계산 없음.
  - `#1`은 매수라 실현손익 반영 자체가 없으므로(매수는 `addRealizedPnl`을 호출하지 않는다) 이 표에서만
    등장하고 SANDBOX-EXCL-004 표에는 없다.
- #4(`LimitOrderFillService.fillBuy`)는 SANDBOX-EXCL-004의 범위 밖이다(그건 매도 실현손익 반영 지점만
  다룬다) — 이 spec에서 `LimitOrderFillService`를 건드리는 유일한 이유가 이 현금 조정이다.
- 표의 순서(#1~#5)는 구현 커밋 순서를 강제하지 않는다 — `tasks.md`에서 하나의 작업으로 묶는다.

### 4-5. 읽기 시점 반영 — `AccountService.getAccountSummary`, `PortfolioService.getPortfolioSummary`

**결정: `AccountService.getAccountSummary`의 `totalValue` 계산에만 `- account.getSandboxCashAdjustment()`를
추가한다. `PortfolioService.getPortfolioSummary`는 코드 변경이 필요 없다.**

```java
// AccountService.getAccountSummary — 변경 후
long cashBalance = account.getCashBalance();                       // 그대로, 조정 없음
long totalValue = cashBalance + holdingsValue - account.getSandboxCashAdjustment();
long realizedPnl = account.getRealizedPnl();                       // 이미 SANDBOX-EXCL-004로 조정됨
long seedMoney = account.getSeedMoney();                           // 그대로
BigDecimal returnRate = seedMoney == 0
	? BigDecimal.ZERO
	: BigDecimal.valueOf(totalValue - seedMoney)
		.divide(BigDecimal.valueOf(seedMoney), RETURN_RATE_SCALE, RoundingMode.HALF_UP);
```

- `holdingsValue`·`unrealizedPnl`은 이미 `evaluateActiveHoldingsForAccount` → `HoldingRepository.
  findAllByAccountIdAndIsActiveTrue`를 거치므로(코드 확인 완료, 1번 항목에서 고치는 바로 그 메서드)
  SANDBOX-EXCL-001 필터가 적용된 이후에는 자동으로 실제 종목만 반영한다 — 이 메서드에서 별도로
  손댈 곳이 없다.
- `PortfolioService.getPortfolioSummary`는 `stockSummary.totalValue()`·`cryptoSummary.totalValue()`를
  그대로 더하고, `returnRate`도 `(totalValue - seedMoneyTotal) / seedMoneyTotal`로 계산한다(기존 코드
  그대로). 그 입력값(`totalValue`)이 이미 조정된 값이므로 이 메서드는 코드를 바꾸지 않아도 자동으로
  올바른 결과를 낸다 — 합산·비율 계산 자체는 선형이라 "각 계좌를 먼저 조정한 뒤 더하기"와 "더한 뒤
  조정하기"가 같은 결과를 낸다(단, 조정을 대상 계좌 각각에 적용했기 때문에 실제로는 전자만 일어난다).
- `AccountSummaryResponse.cashBalance`(응답 필드)는 `account.getCashBalance()`를 그대로 노출한다 —
  변경 없음. `AccountSummaryResponse.totalValue`·`returnRate`(응답 필드)는 이름·타입·위치가 전혀
  바뀌지 않고 계산 방식만 바뀐다 — API 계약(필드 자체) 변경이 아니다.

## 5. 백필 설계 — `realized_pnl`·`sandbox_cash_adjustment` 통합 재계산

**결정: Flyway 버전 마이그레이션 하나(`V{N}__add_sandbox_cash_adjustment_and_backfill.sql`)에서
(1) `sandbox_cash_adjustment` 컬럼을 추가하고, (2) 전체 계좌의 `sandbox_cash_adjustment`를 과거
샌드박스 매매·보상 이력으로부터 재계산하고, (3) 전체 계좌의 `realized_pnl`을 실제 종목 매도 합계로
재계산한다. 별도 Java 배치 서비스·1회성 실행 플래그·관리자 API를 만들지 않는다.**

```sql
ALTER TABLE accounts ADD COLUMN sandbox_cash_adjustment BIGINT NOT NULL DEFAULT 0;

-- (2) sandbox_cash_adjustment 재계산: 샌드박스 종목 매매의 현금 순변동 + 이미 지급된 튜토리얼 완료
-- 보상. 튜토리얼 완료 보상은 practice_completions(user_id, tutorial_key UNIQUE)에 정확히 1행당 1회
-- 지급됐으므로(PracticeHoldingReflectionService.createReflection이 매 완료마다 정확히 1번만 호출),
-- COUNT(*) * 5,000,000으로 정확히 역산할 수 있다. tutorial_key → market 매핑은
-- PracticeIntentionService.TUTORIAL_KEY="INVESTMENT_PRACTICE_V1"(STOCK),
-- COIN_TUTORIAL_KEY="COIN_PRACTICE_V1"(CRYPTO) 그대로다.
UPDATE accounts a
SET a.sandbox_cash_adjustment =
	COALESCE((
		SELECT SUM(CASE WHEN t.side = 'SELL' THEN t.amount - t.fee ELSE -(t.amount + t.fee) END)
		FROM trades t
		JOIN instruments i ON t.instrument_id = i.id
		WHERE t.account_id = a.id
		  AND i.is_tutorial_sample = TRUE
	), 0)
	+ (
		SELECT COUNT(*) * 5000000
		FROM practice_completions pc
		WHERE pc.user_id = a.user_id
		  AND ((pc.tutorial_key = 'INVESTMENT_PRACTICE_V1' AND a.market = 'STOCK')
		    OR (pc.tutorial_key = 'COIN_PRACTICE_V1' AND a.market = 'CRYPTO'))
	);

-- (3) realized_pnl 재계산: 이미 확정한 대로 실제 종목 매도 합계만 남긴다(SANDBOX-EXCL-005).
UPDATE accounts a
SET a.realized_pnl = COALESCE((
	SELECT SUM(t.realized_pnl)
	FROM trades t
	JOIN instruments i ON t.instrument_id = i.id
	WHERE t.account_id = a.id
	  AND t.side = 'SELL'
	  AND i.is_tutorial_sample = FALSE
), 0);
```

### 왜 이 방식인가 (검토한 대안과 근거)

- **왜 하나의 마이그레이션 파일인가**: `031`의 `V32`가 이미 "컬럼 추가 + 데이터 반영"을 한 파일에서
  했던 선례(시드 데이터 삽입)와 같은 패턴이다. 컬럼 추가(신규 컬럼, `DEFAULT 0`)는 파괴적 변경이
  아니므로(ADR-0021 §결정7의 "컬럼·테이블 삭제, 이름 변경, 타입 축소, NOT NULL 승격"에 해당하지 않음)
  2단계 배포로 나눌 필요가 없다 — `V34`(SANDBOX-EXCL-005 재계산 절 참고)와 합쳐 하나로 처리한다.
- **왜 SQL 마이그레이션인가(대안: 기동 시 1회 실행 Java 컴포넌트, 관리자 트리거 API)**: SQL 한 문장
  으로 표현 가능한 순수 집계·갱신을 굳이 Java 서비스·스케줄러로 옮기면 기동 시점 부하·재시도 로직·
  "이미 실행됐는지" 판별 상태까지 새로 설계해야 하는데, Flyway의 `flyway_schema_history`가 이미 그
  "1회만 실행됨"을 보장한다.
- **왜 `sandbox_cash_adjustment` 재계산도 "전체 덮어쓰기"(`=`)이지 "누적"(`+=`)이 아닌가**: 신규
  컬럼의 초기값은 어차피 0이라 이번 마이그레이션 한정으로는 결과가 같지만, `realized_pnl` 재계산과
  같은 패턴(전체 덮어쓰기)으로 통일해 멱등성을 코드 형태로도 명확히 한다 — 나중에 이 마이그레이션을
  참고해 비슷한 재계산을 또 만들 사람이 `+=`를 따라 쓰다가 재실행 시 값이 두 배가 되는 실수를
  막는다(`ai/agent-mistakes.md`에 기록할 만한 유형의 실수를 사전에 차단).
- **`practice_completions` COUNT 방식의 정확성 전제**: 보상 지급 로직(`PracticeHoldingReflectionService.
  createReflection`)은 `practiceCompletionRepository.save(...)` 직후 정확히 한 번
  `payTutorialCompletionReward(...)`를 호출하고, `practice_completions`에는
  `UNIQUE(user_id, tutorial_key)` 제약이 있다(마이그레이션 파일 확인 완료) — 즉 이 테이블의 행 수가
  곧 지급 횟수와 정확히 일치한다. 보상 금액이 나중에 바뀌면(현재 500만원 고정) 이 백필 SQL의 상수도
  같이 업데이트해야 하지만, 그 경우도 새 마이그레이션을 추가하는 것이지 이 마이그레이션을 수정하는
  것은 아니다(ADR-0004).
- **동시성**: 마이그레이션은 애플리케이션 기동 전(Flyway가 `ddl-auto=validate`보다 먼저 실행되는
  Spring Boot 표준 흐름) 단발성으로 실행되므로 실행 중 다른 트랜잭션의 동시 매도와 경합하지 않는다.
- **ADR-0021 §결정7(무중단 배포) 롤백 시나리오**: 구버전 앱(이 spec 이전 코드)은 여전히 모든 매도에
  `addRealizedPnl`을 호출하고 `sandboxCashAdjustment`라는 컬럼 자체를 모른다(엔티티에 필드가 없으므로
  단순히 갱신하지 않고 남겨둔다) — 컬럼이 이미 존재하는 채로 구버전 앱이 도는 것은 안전하다(모르는
  컬럼을 무시할 뿐 `validate` 검증도 "엔티티에 없는데 스키마에 있는 컬럼"은 실패시키지 않는다,
  하이버네이트 validate는 "엔티티에 있는데 스키마에 없는 컬럼"만 잡는다). 롤백 중 남은 위험(샌드박스
  매도·보상이 다시 옛 방식으로 반영됨)은 이 spec 배포 전과 같은 상태로 돌아가는 것뿐이라 새로운
  위험이 아니다.
- **마이그레이션 번호**: 착수 시 `origin/dev` 최신 `V{N}`을 재확인한다 — 이 문서 작성 시점 최신은
  `V33__rename_tutorial_sample_instruments.sql`이므로 잠정 번호는 `V34`(`ai/specs/README.md` 번호
  규칙과 같은 종류의 선점 위험, 병렬 브랜치 확인 필요).

## 6. 알려진 한계

- `sandboxCashAdjustment` 백필은 "샌드박스 매매의 현금 순변동 + 지급된 보상"만 역산한다. 이미 계정이
  삭제됐거나 계정과 무관하게 발생한 이례적 데이터(예: 수동 DB 조작으로 생긴 값)는 대상이 아니다 —
  이 저장소에 그런 경로가 존재하지 않음을 코드로 확인했으므로 실질적 한계는 아니다.
  `PracticeHoldingReflectionService`가 보상 지급의 유일한 코드 경로임을 전제로 한다(코드 확인 완료).
- 보상 금액(500만원)이 향후 시장·튜토리얼 종류별로 달라지면(현재는 고정 상수) 이 spec의 백필 SQL은
  그 시점 기준 상수만 반영한 스냅샷이 된다 — 값이 바뀌기 전 지급 이력에는 옛 금액을, 바뀐 후에는
  새 금액을 적용해야 하는데, 현재 `practice_completions`는 지급 시점 금액을 별도로 기록하지 않는다.
  현재 요구사항(고정 500만원)에서는 문제가 없으므로 이번 spec에서 별도 컬럼을 추가하지 않는다 — 금액이
  가변화되면 그때 `practice_completions`에 지급액 컬럼을 추가하는 별도 spec이 필요하다.

## 데이터 모델 요약

- 신규 컬럼 1개: `accounts.sandbox_cash_adjustment BIGINT NOT NULL DEFAULT 0`(`Account` 엔티티에
  `sandboxCashAdjustment` 필드·`addSandboxCashAdjustment(long)` 메서드 추가).
- 신규 테이블 없음. `instruments.is_tutorial_sample`(`031`, 기존 컬럼)·`practice_completions`(`026`,
  기존 테이블)만 참조한다.
- 신규 migration 1개: `V{N}__add_sandbox_cash_adjustment_and_backfill.sql`(컬럼 추가 1개 + DML 2건).

## 입력 명세

| 대상 | 필드 | 검증 |
|---|---|---|
| `GET /api/holdings` | 없음(기존과 동일) | 요청·응답 스키마 변경 없음. 반환되는 행 집합만 좁아진다 |
| `GET /api/journal` | 없음(기존과 동일) | 요청·응답 스키마 변경 없음. 반환되는 행 집합만 좁아진다 |
| `GET /api/rankings`, `GET /api/rankings/me` | 없음(기존과 동일) | 요청·응답 스키마 변경 없음. 대상자·
  status 판정 집합만 좁아진다 |
| `GET /api/holdings/summary`·`GET /api/portfolio`(계좌 요약·포트폴리오 합산, 정확한 경로는 착수 시
  `AccountController`/`PortfolioController` 재확인) | 없음(기존과 동일) | 요청·응답 스키마 변경 없음.
  `totalValue`·`returnRate`의 계산 방식만 바뀐다. `cashBalance`는 값·계산 모두 변경 없음 |
| `POST /api/orders`(BUY·SELL) | 없음(기존과 동일) | 요청·응답 스키마 변경 없음. `account.realizedPnl`·
  `account.sandboxCashAdjustment` 반영 여부만 종목 종류에 따라 달라진다(`trade.realizedPnl`·
  `account.cashBalance`는 항상 기존과 동일하게 채워짐) |

## 테스트 계획

- 단위: `PortfolioSellService.finalizeSellRealizedPnl`(샌드박스/실제 종목 각각 `addRealizedPnl`·
  `addSandboxCashAdjustment` 호출 여부, `trade.fillRealizedPnl`·`account.addCash`는 항상 호출),
  `OrderExecutionService.createBuyOrder`/`createSellOrder`의 같은 분기(기존 시장가 매수·매도 단위
  테스트에 케이스 추가), `LimitOrderFillService.fillBuy`(샌드박스 지정가 매수 체결 시
  `addSandboxCashAdjustment` 호출), `AccountService.getAccountSummary`(샌드박스 조정치가 있을 때
  `totalValue`·`returnRate`가 조정되고 `cashBalance`는 그대로인지, 조정치가 0이면 기존과 동일한
  결과인지 회귀).
- 슬라이스(`@DataJpaTest`): `HoldingRepository.findAllByAccountIdAndIsActiveTrue`가 샌드박스 holding을
  제외하는지(실제+샌드박스 혼재 시딩), `BuyTradeJournalRepositoryImpl`/`SellTradeJournalRepositoryImpl`
  의 `findByAccountIdWithCursor`가 샌드박스 종목 체결의 회고를 제외하는지, `TradeRepository.
  findDistinctAccountIdsBySideAndMarket`이 샌드박스 종목만 매도한 계좌를 제외하는지, 새
  `existsByAccountIdAndSideAndInstrument_IsTutorialSampleFalse`/
  `existsBySideAndAccountMarketAndInstrument_IsTutorialSampleFalse`.
- 슬라이스(`@WebMvcTest`): `GET /api/holdings`·`GET /api/journal`·`GET /api/rankings`·`GET /api/rankings/me`
  응답에 샌드박스 기원 항목이 없음(기존 컨트롤러 테스트에 케이스 추가, 새 엔드포인트 아님). 계좌 요약·
  포트폴리오 응답의 `totalValue`·`returnRate`가 샌드박스 활동이 있는 계좌에서 조정된 값을 반환하고
  `cashBalance`는 원값 그대로인지. `HoldingService.findHoldingId`/`findHoldingForOwner`가 샌드박스
  holding을 여전히 찾음(회귀, `031`/`026` 튜토리얼 흐름이 깨지지 않는지 확인).
- 통합: (1) 실제 종목 + 샌드박스 종목을 함께 보유·매도한 계좌 하나를 시딩해, 포트폴리오·투자일기·랭킹
  네 조회(`GET /api/holdings`·`GET /api/journal`·`GET /api/rankings`·`GET /api/rankings/me`) 모두에서
  샌드박스 항목·대상자 판정이 빠지고 실제 항목만 남는지 확인. (2) 튜토리얼 완료 보상을 받고 그 돈으로
  실제 종목을 매수해 평가차익을 낸 계좌 하나를 시딩해, `totalValue`가 "보상 원금은 제외하고 실거래
  평가차익만 반영"함을 4-3의 수치 예시와 동일한 값으로 확인하는 시나리오(이 spec의 핵심 요구사항이자
  가장 회귀에 취약한 계산이므로 반드시 통합 테스트로 고정한다). 백필 마이그레이션은 별도로 —
  마이그레이션 적용 전 상태를 흉내낸 데이터(샌드박스 매매·보상 이력이 있지만 `sandbox_cash_adjustment`
  컬럼이 없던 시절의 데이터)를 만든 뒤 `flyway migrate`(테스트 컨텍스트 기동)가 정확한 값으로 채우고
  `realized_pnl`도 정확한 값으로 되돌리는지 확인.
- 회귀: `026`/`031`의 기존 튜토리얼 통합 테스트(이슈 #313·#339)가 이 변경 이후에도 그대로 통과
  (holding 조회·chain 해석 경로는 건드리지 않았으므로), `006`/`007`/`014`의 실제 종목 전용 기존 테스트
  전부 통과. 샌드박스 활동이 전혀 없는 기존 계좌의 `totalValue`·`returnRate`가 이 spec 이전과 완전히
  동일한 값을 반환하는지(`sandboxCashAdjustment = 0`이면 수식이 기존과 동일하므로 자연히 성립하지만
  명시적 회귀 테스트로 고정).
