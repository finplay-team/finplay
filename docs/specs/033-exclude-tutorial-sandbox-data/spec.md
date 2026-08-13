# Spec: 튜토리얼 샌드박스 매매·완료 보상의 포트폴리오·투자일기·랭킹 제외

> 배경: `031-tutorial-sandbox-instruments`가 실제 계좌·주문 API를 그대로 쓰는 튜토리얼 전용 샌드박스
> 종목(`instruments.is_tutorial_sample=true`)을 도입하면서, 그 매매·보상이 실거래 화면에 그대로 섞여
> 나오는 부작용이 드러났다(이슈 #366). 이 spec은 그 누출을 막는다. `031`의 샌드박스 종목·항시 시세·
> 매도 단계·5분 제한 자체는 변경하지 않는다.

## 개요

`031`은 샌드박스 종목의 매수·매도를 기존 `POST /api/orders`로 그대로 체결시킨다. 그 결과 샌드박스
거래도 실제 종목 거래와 완전히 같은 모양으로 `holdings`·`trades`·`accounts.realized_pnl`에 쌓인다.
이 spec은 (1) 포트폴리오 보유 목록, (2) 투자일기 목록, (3) 랭킹 대상자 선정·`realized_pnl` 누적 세
지점에서 샌드박스 기원 데이터를 걸러내고, (4) 이미 오염된 `accounts.realized_pnl`을 바로잡는다.

## 사용자 시나리오

- 사용자가 튜토리얼에서 샌드박스 종목을 매수해 보유 중이어도, `GET /api/holdings`(포트폴리오 보유
  목록)에는 그 보유가 나타나지 않는다 — 실제 보유 종목만 보인다.
- 사용자가 샌드박스 종목을 매수·매도해 투자일기를 남겨도, `GET /api/journal`(투자일기 목록)에는 그
  회고가 나타나지 않는다.
- 샌드박스 종목만 매도해본 사용자는 `GET /api/rankings`·`GET /api/rankings/me`의 랭킹 대상자가 되지
  않는다 — 실제 종목을 한 번도 매도한 적이 없다면 매도 이력 없는 사용자와 완전히 동일하게 취급된다
  (대상자 목록·"매도 이력 있음" status 판정 둘 다).
- 샌드박스 종목 매도로 발생한 손익은 그 사용자의 `accounts.realized_pnl`(랭킹 score)에 반영되지 않는다
  — 매도 체결(`trades.realized_pnl`) 자체의 값은 그대로 남지만 계좌 집계에는 더해지지 않는다.
- 사용자가 튜토리얼 완료 보상(500만원)을 받거나 샌드박스 종목을 매매해도, 포트폴리오 화면의
  평가자산(`totalValue`)·수익률(`returnRate`)은 그 영향을 전혀 받지 않는다 — 실제 주문 가능
  현금(`cashBalance`)은 실제 돈이므로 그대로 늘어나 있지만, 평가자산·수익률은 마치 그 보상·매매가
  없었던 것처럼 보인다.
- 사용자가 튜토리얼 완료 보상으로 받은 현금으로 **실제** 종목을 사고 그 투자가 수익을 내면, 그 수익은
  정상적으로 평가자산·수익률에 반영된다 — 보상금 자체(원금)만 계속 제외되고, 그 돈으로 실제로 낸
  투자 성과는 제외되지 않는다.
- 이 spec 적용 이전에 샌드박스 매도·튜토리얼 보상으로 이미 오염된 계좌(`realized_pnl`, 평가자산·
  수익률 계산의 기초값)는, 이 spec을 배포하는 시점에 과거 이력으로부터 다시 계산된 값으로 일괄
  정정된다.

## 요구사항

- [x] SANDBOX-EXCL-001: `GET /api/holdings`(및 그 평가손익 계산)는 `instrument.isTutorialSample=true`인
  holding을 결과에서 제외한다. 튜토리얼 자체가 내부적으로 자기 holding을 조회하는 경로(2·3단계 chain
  해석, holding 상세 조회)는 이 제외 대상이 아니다 — 그 경로는 애초에 샌드박스 holding을 찾아야 동작
  하므로 그대로 둔다.
- [x] SANDBOX-EXCL-002: 투자일기 목록 조회(`GET /api/journal`)는 대상 체결의 종목이
  `isTutorialSample=true`인 매수·매도 회고를 결과에서 제외한다. 투자일기 상세 조회·작성·수정(체결 ID를
  직접 지정하는 경로)은 이번 범위에서 바꾸지 않는다(아래 "범위 제외" 참고).
- [x] SANDBOX-EXCL-003: 랭킹 대상자 판정(`TradeRepository.findDistinctAccountIdsBySideAndMarket` 및
  이를 쓰는 `RankingRebuildService.rebuild`)과 랭킹 status 판정(`TradeRepository.
  existsByAccountIdAndSide`/`existsBySideAndAccountMarket`, `TradeService.hasSellHistory`/
  `hasAnySellHistory`를 거쳐 `RankingService`가 쓰는 두 메서드)은 모두
  `isTutorialSample=true`인 종목에 대한 매도 체결을 판정 대상에서 제외한다. 실제 종목을 한 번도
  매도하지 않고 샌드박스 종목만 매도한 계좌는 랭킹 대상자 목록·"매도 이력 있음" status 둘 다에서
  매도 이력이 아예 없는 계좌와 동일하게 취급된다.
- [x] SANDBOX-EXCL-004: 매도 체결 시 실현손익을 `Account.realizedPnl`에 반영하는 두 지점(시장가 매도
  `OrderExecutionService.createSellOrder`, 지정가·시장가 공통 `PortfolioSellService.
  finalizeSellRealizedPnl`) 모두, 매도 종목이 `isTutorialSample=true`이면 `account.addRealizedPnl(...)`
  호출을 건너뛴다. `trade.fillRealizedPnl(...)`(체결 자체의 실현손익 기록)은 종목 종류와 무관하게 항상
  수행한다 — 개별 체결 원장 값은 감사·튜토리얼 evidence 판정에 계속 쓰이기 때문이다.
- [x] SANDBOX-EXCL-005: 이 spec을 배포하는 시점에, 이미 존재하는 모든 계좌의 `realized_pnl`을 "실제
  종목(`isTutorialSample=false`) 매도 체결의 `trades.realized_pnl` 합계"로 재계산해 덮어쓴다. 재계산
  결과가 기존 값과 같은 계좌(샌드박스 매도 이력이 없는 계좌)는 값이 변하지 않는다.
- [x] SANDBOX-EXCL-006: 계좌에 `sandboxCashAdjustment`(누적값)를 신설한다. 다음 현금 변동이 발생할
  때마다 `cashBalance`에 적용하는 것과 정확히 같은 부호·같은 금액을 이 값에도 함께 누적한다 —
  (a) 샌드박스 종목 시장가·지정가 매수 체결(현금 차감분의 음수), (b) 샌드박스 종목 시장가·지정가
  매도 체결(현금 입금분), (c) 튜토리얼 완료 보상 지급(+5,000,000). `cashBalance` 자체의 계산·용도
  (주문 가능 현금 검증 등)는 전혀 바뀌지 않는다.
- [x] SANDBOX-EXCL-007: 계좌 요약(`AccountSummaryResponse`)과 포트폴리오 합산 요약
  (`PortfolioSummaryResponse`)의 `totalValue`(평가자산)는 "현금 + 실제 보유종목 평가금액 -
  `sandboxCashAdjustment`"로 계산한다(실제 보유종목만 남는 것은 SANDBOX-EXCL-001의 결과를 그대로
  재사용). `returnRate`(수익률)는 이 `totalValue`를 기준으로 기존과 동일한 공식(시드머니 대비 증감률)
  으로 계산한다. `cashBalance` 필드 자체의 값은 이 계산과 무관하게 그대로 노출한다(실제 주문 가능
  금액이어야 하므로).
- [x] SANDBOX-EXCL-008: 이 spec을 배포하는 시점에, 이미 존재하는 모든 계좌의 `sandboxCashAdjustment`를
  과거 이력(샌드박스 종목 매수·매도 체결 원장 + 이미 지급된 튜토리얼 완료 보상 이력)으로부터 다시
  계산해 채운다. 샌드박스 활동이 전혀 없었던 계좌는 0으로 남는다.
- [x] SANDBOX-EXCL-009: 이 spec은 `031`의 샌드박스 종목 정의·항시 시세·매도 단계·5분 제한, `026`의 2·3
  단계 chain 해석, `030`의 코인 가상 가격 세션을 변경하지 않는다. 실제 종목의 포트폴리오·투자일기·
  랭킹 동작도 이 spec 이전과 동일해야 한다.

## 비즈니스 규칙

- "샌드박스 기원"의 판정 기준은 항상 매매 대상 종목의 `instruments.is_tutorial_sample` 플래그다. 별도
  플래그나 명명 규칙을 추가하지 않는다(`031`이 이미 확립한 유일한 판별 근거를 그대로 쓴다). 튜토리얼
  완료 보상은 종목이 아니라 지급 행위 자체가 항상 샌드박스 기원이다(그 행위를 호출하는 코드 경로가
  `PracticeHoldingReflectionService` 하나뿐이므로 별도 판별이 필요 없다).
- `trades.realized_pnl`(체결 단위 값)과 `accounts.realized_pnl`(계좌 집계 값)은 이 spec 이후 서로 다른
  의미를 가진다 — 전자는 모든 매도 체결에 대해 항상 채워지는 원장 값이고, 후자는 "실제 종목 매도만
  합산한 랭킹 score"다. 이 구분은 새 컬럼을 만들지 않고 "집계 시점의 조건부 반영"으로만 구현한다.
- `accounts.cash_balance`(실제 주문 가능 현금)와 `accounts.sandbox_cash_adjustment`(표시용 평가자산
  보정치)는 서로 다른 목적을 가진 별개의 값이다 — 전자는 실거래에 그대로 쓰이는 진짜 잔고이고
  절대 조정하지 않는다. 후자는 "지금까지 `cash_balance`에 반영된 금액 중 샌드박스 매매·튜토리얼
  보상에서 온 누적 순액"이며, 평가자산·수익률을 표시할 때만 `cash_balance`에서 빼는 용도로 쓴다.
- `sandboxCashAdjustment`는 "아직 안 쓴 보상금"이 아니라 "지금까지 유입된 샌드박스 기원 현금의 누적
  순액"이다 — 사용자가 보상금으로 실제 종목을 사서 수익을 내면, 그 수익은 정상적으로 평가자산에
  반영되고 보상 원금만 계속 제외된 채로 남는다(위 사용자 시나리오 참고, `plan.md`에서 수식으로 증명).
- 두 백필(SANDBOX-EXCL-005 `realized_pnl`, SANDBOX-EXCL-008 `sandboxCashAdjustment`)은 모두 멱등이다
  — 대상 값을 `+=`가 아니라 과거 이력으로부터 다시 계산한 값으로 통째로 덮어쓰므로, 여러 번
  실행해도(재배포·재시도) 항상 같은 결과를 낸다.

## 범위 제외

- **튜토리얼 완료 보상(500만원) 자체의 축소·제외** — 대상 아님. 보상 지급 로직(`026` 이슈 #343)은
  그대로 유지한다. 이 spec은 그 보상이 평가자산·수익률에 나타나지 않게 만들 뿐, 지급 자체나 지급
  금액은 바꾸지 않는다.
- **투자일기 상세 조회·작성·수정(`JournalService.getBuyJournal`/`getSellJournal`/`createBuyJournal`/
  `createSellJournal`/`updateBuyJournal`/`updateSellJournal`)의 샌드박스 종목 거부** — 대상 아님. 이
  경로들은 체결 ID를 직접 지정해 호출하므로 목록에 섞여 나오는 누출과 성격이 다르고, 이슈 #366도 목록
  조회만 지적한다.
- **holding 상세 조회(`HoldingService.findHoldingForOwner`)·2·3단계 chain 해석용 조회
  (`HoldingService.findHoldingId`)의 샌드박스 종목 거부** — 대상 아님. 이 두 메서드는 `026`/`031`
  튜토리얼 자체가 자신의 샌드박스 holding을 찾기 위해 쓰는 경로이므로 여기서 막으면 튜토리얼이
  깨진다.
- **`031` 커뮤니티 태그 제외 로직 재검토** — 대상 아님. 이미 `031` 구현 시 처리됐다(plan.md "리뷰
  권장사항 반영").
- **다중 인스턴스·동시 백필 재실행 경합 방지 장치 신설** — 대상 아님. 백필은 배포 1회성 마이그레이션
  이고 멱등이므로 별도 락은 두지 않는다.

## 완료 조건

- [x] 포트폴리오 보유 목록(`GET /api/holdings`)이 샌드박스 holding을 제외하고, 튜토리얼 내부 조회
  경로는 영향받지 않음이 테스트로 확인된다.
- [x] 투자일기 목록(`GET /api/journal`)이 샌드박스 종목 체결의 회고를 제외함이 테스트로 확인된다.
- [x] 랭킹 대상자 판정과 랭킹 status 판정(`existsBy...` 계열) 모두 샌드박스 종목만 매도한 계좌를
  매도 이력 없는 계좌와 동일하게 취급함이 테스트로 확인된다.
- [x] 매도 체결 시 샌드박스 종목이면 `account.addRealizedPnl`이 호출되지 않고, `trade.realizedPnl`은
  여전히 채워짐이 테스트로 확인된다(시장가·지정가 경로 둘 다).
- [x] 배포 시점 백필이 기존 오염 계좌의 `realized_pnl`을 실제 종목 매도 합계로 정정하고, 오염되지 않은
  계좌는 값이 변하지 않음이 확인된다.
- [x] 샌드박스 종목 매수·매도·튜토리얼 완료 보상이 각각 `sandboxCashAdjustment`에 `cashBalance`와
  같은 부호·금액으로 누적됨이 테스트로 확인된다(시장가·지정가 매수·매도 경로 전부).
- [x] 계좌 요약·포트폴리오 합산 요약의 `totalValue`·`returnRate`가 `sandboxCashAdjustment`를 제외해
  계산되고, `cashBalance`는 그대로 노출됨이 테스트로 확인된다. 보상금으로 실제 종목을 매수해 수익을
  낸 시나리오에서 그 수익만 `totalValue`에 반영되고 보상 원금은 계속 제외됨이 통합 테스트로 확인된다.
- [x] 배포 시점 백필이 기존 계좌의 `sandboxCashAdjustment`를 과거 샌드박스 매매·보상 이력으로부터
  정확히 재계산하고, 샌드박스 활동이 없는 계좌는 0으로 유지됨이 확인된다.
- [x] 실제 종목만 다루는 기존 포트폴리오·투자일기·랭킹 테스트가 이 변경 이후에도 회귀 없이 통과한다.
