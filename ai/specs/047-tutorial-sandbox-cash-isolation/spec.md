# Spec: 튜토리얼 전용 계좌 신설을 통한 샌드박스 매매·완료 보상 현금 격리

> 이슈: [#450](https://github.com/finplay-team/finplay-backend/issues/450)
>
> **번호 경위**: 이 spec은 애초에 `045-tutorial-sandbox-cash-isolation`으로 작성 중이었으나, 착수 시점
> `045`가 이미 `045-community-likes-sort`(PR #442, `dev` 머지 완료)로 선점되어 있음을 발견해
> `docs/specs/README.md`의 번호 규칙에 따라 `047`로 옮겼다(`046`도 `046-community-trade-share`로 이미
> 선점됨).
>
> 이 문서는 `033-exclude-tutorial-sandbox-data`의 **SANDBOX-EXCL-006·SANDBOX-EXCL-007을 대체(supersede)한다.**
>
> SANDBOX-EXCL-006(원문): "계좌에 `sandboxCashAdjustment`(누적값)를 신설한다. ... `cashBalance` 자체의 계산·
> 용도(주문 가능 현금 검증 등)는 전혀 바뀌지 않는다."
> SANDBOX-EXCL-007(원문): "... `totalValue`(평가자산)는 '현금 + 실제 보유종목 평가금액 - `sandboxCashAdjustment`'로
> 계산한다. ... `cashBalance` 필드 자체의 값은 이 계산과 무관하게 그대로 노출한다."
>
> 두 항목은 "`cashBalance`는 실제 돈이므로 손대지 않고, 표시(`totalValue`)에서만 보정한다"는 전제를 깔고
> 있었다. 이 spec은 그 전제 자체를 없앤다 — 샌드박스 매매·보상이 애초에 실제 `Account`를 건드리는 통로를
> 없애므로(아래 "설계 판단"), 표시 시점 보정(`totalValue` 공식, `sandboxCashAdjustment` 누적)도 더 이상
> 필요하지 않아 함께 폐지한다. `033`의 나머지 항목(SANDBOX-EXCL-001~005, 008~009 — 포트폴리오·투자일기·
> 랭킹 제외, `realized_pnl` 백필, `trades.realized_pnl` 원장 유지)은 **변경 없이 그대로 유지된다.**
>
> `040-tutorial-restart-after-completion`의 재시작 계약(무제한 재시작, 완료 보상 시장당 1회 캡, 재시작 정리
> 순서·잠금)은 **대체하지 않는다.** 이 spec은 그 재시작 정리 절차(`PracticeRunRestartOrderService.cleanupCurrentRun`)
> 안에 "튜토리얼 계좌를 초기 잔고로 리셋한다"는 단계 하나를 추가할 뿐이다. 040 쪽에도 상호 참조를 남겼다.
>
> `021-general-risk-management-oco`도 **대체하지 않는다.** 021은 애초에 튜토리얼 현금 격리를 고려한 적이
> 없다(뒤집을 기존 문장이 없다) — 이 spec은 021이 비워 둔 자리(OCO 체결의 현금 처리)를 채운다. 021 쪽에도
> 상호 참조를 남겼다.

## 개요

`033`은 샌드박스(튜토리얼 전용) 종목 매매와 완료 보상이 랭킹·포트폴리오·투자일기 화면에 노출되는 것은 막았지만,
`accounts.cash_balance`(실제 주문 가능 현금) 자체는 "실제 돈이므로" 의도적으로 그대로 두었다. 그런데 `031`이
샌드박스 매매를 실제 주문 API(`POST /api/orders`, `POST /api/orders/limit`)로 그대로 체결시키고, `040`이
완료된 튜토리얼도 무제한 재시작을 허용하면서, 이 둘의 결합이 실제 현금을 무제한으로 불릴 수 있는 경로가 됐다
(이슈 #450, 재현 방법은 이슈 본문 참고).

이 spec은 "실제 계좌·주문 API를 그대로 쓴다"(`031`)는 기존 전제를 유지한 채, **샌드박스 매매·완료 보상이
현금에 미치는 영향의 대상 자체를 실제 `Account`에서 완전히 새로운 튜토리얼 전용 계좌로 옮긴다.** 매수·매도·
지정가 예약/체결·재시작 보상매도·OCO 손절익절 체결 등 현금을 움직이는 모든 지점이, 대상 종목이
`instrument.isTutorialSample()=true`이면 실제 `Account`가 아니라 이 새 계좌를 대신 증감시킨다. 완료 보상만
예외로 남아 계속 실제 계좌로 들어간다(아래 "설계 판단" 4).

## 사용자 시나리오

- 사용자가 STOCK 또는 CRYPTO 튜토리얼에 처음 진입해 샌드박스 종목을 매수하면, 그 시장 전용의 튜토리얼 계좌
  (처음 보는 사용자라면 1000만원으로 새로 만들어짐)에서 매수 금액이 차감된다. 실제 `Account.cashBalance`는
  전혀 줄지 않는다.
- 사용자가 샌드박스 종목을 매도해 이익을 내도(가격이 올랐을 때 팔았거나 재시작 보상매도로 정산됐거나, 손절·
  익절 OCO 체결이거나) 튜토리얼 계좌의 현금만 늘고, 실제 `Account.cashBalance`는 늘지 않는다.
- 사용자가 튜토리얼을 완료해 시장당 500만원 보상을 받으면, 그 보상은 지금과 동일하게 실제
  `Account.cashBalance`로 들어간다 — 튜토리얼 계좌는 관여하지 않는다.
- 사용자가 튜토리얼 계좌 잔고보다 큰 금액의 샌드박스 매수를 시도하면, 실제 계좌 잔고와 무관하게(튜토리얼
  계좌 잔고만 보고) 현금 부족으로 거부된다 — "가상이지만 진짜처럼" 예산 제약이 작동한다.
- 사용자가 튜토리얼을 재시작하면(`POST .../attempts/{market}/restart`), 그 시장의 튜토리얼 계좌가 이전
  거래 내역과 무관하게 1000만원으로 다시 초기화된다 — "거래 내역 자체가 리셋된다."
- 사용자가 매수 → 관찰 → 매도(또는 재시작 보상매도) → 재시작을 몇 번을 반복하든, 그 사이 실제 종목을 한 번도
  거래하지 않았다면 실제 `Account.cashBalance`는 튜토리얼을 시작하기 전 값과 정확히 같다.
- 사용자가 실제 종목을 매매하는 화면·API는 이 spec 이전과 완전히 동일하게 동작한다 — 이 격리는 매매 대상이
  `instrument.isTutorialSample()=true`일 때만 적용된다.
- 이 spec 배포 이전에 이미 샌드박스 매매·보상으로 부풀려진 `Account.cashBalance`를 가진 사용자는, 배포
  시점에 그 부풀려진 만큼 정확히 원복된다.

## 요구사항

- [ ] TUTORIAL-CASH-ISOL-001: 사용자·시장(STOCK/CRYPTO) 조합마다 독립적인 튜토리얼 전용 계좌가 존재한다.
  최초 접근(튜토리얼 진입 시점, 아래 "설계 판단" 참고) 시 현금 1000만원·실현손익 0원으로 생성된다. 실제
  `Account`와 달리 보유종목(holding)은 이 계좌가 추적하지 않지만, 현금(`cashBalance`·`reservedCash`)과
  이번 판(현재 run) 실현손익(`realizedPnl`)은 추적한다(아래 "비즈니스 규칙" 참고).
- [ ] TUTORIAL-CASH-ISOL-002: 매매 대상 종목이 `instrument.isTutorialSample()=true`인 매수는 체결 경로와
  무관하게(시장가 `POST /api/orders`, 코인 지정가 `POST /api/orders/limit`의 예약·체결·취소, 교육 지정가
  세션 경로 포함) 실제 `Account.cashBalance`·`Account.reservedCash`를 전혀 변경하지 않는다 — 대신 같은
  사용자·시장의 튜토리얼 계좌에서 동일한 차감·예약·해제·확정이 일어난다.
- [ ] TUTORIAL-CASH-ISOL-003: 매도 대상 종목이 샌드박스면, `PortfolioSellService.finalizeSellRealizedPnl`을
  거치는 모든 매도(시장가·지정가 매도 체결, 재시작 보상매도, 손절·익절 OCO 체결)는 실제
  `Account.cashBalance`를 증가시키지 않는다 — 대신 튜토리얼 계좌의 현금이 늘고, 같은 체결의 실현손익만큼
  튜토리얼 계좌의 `realizedPnl`도 함께 증가한다(매도 체결 시점에 현금·실현손익이 같은 트랜잭션에서 갱신됨,
  아래 "비즈니스 규칙"). `Trade.realizedPnl`(체결 원장 값)은 `033`의 원칙대로 종목 종류와 무관하게 항상
  채우고, 실제 `Account.realizedPnl`(랭킹 집계)은 `033` SANDBOX-EXCL-004대로 계속 제외된다 — 이 spec이
  새로 추적하는 `TutorialAccount.realizedPnl`은 랭킹과 무관한 별개 값이다.
- [ ] TUTORIAL-CASH-ISOL-004: 튜토리얼 완료 보상(500만원, `payTutorialCompletionReward`)은 지금과 동일하게
  실제 `Account.cashBalance`로 들어간다 — 튜토리얼 계좌는 관여하지 않는다. 보상이 시장당 정확히 1회만
  지급된다는 `040`의 캡 로직도 변경하지 않는다.
- [ ] TUTORIAL-CASH-ISOL-005: 샌드박스 종목 매수 시 튜토리얼 계좌의 가용 현금이 부족하면 새 오류 코드
  `TUTORIAL_INSUFFICIENT_CASH`로 거부된다 — 실제 계좌의 `INSUFFICIENT_CASH`와는 별도 코드다(확정, 아래
  "설계 판단 — 오류 코드" 참고). 실제 계좌처럼 "가상 예산 제약"이 실질적으로 작동해야 한다(범위 제외의
  "옵션 2와의 차이" 참고).
- [ ] TUTORIAL-CASH-ISOL-006: `POST /api/education/practice/attempts/{market}/restart`가 호출되면(기존
  현재 실행 정리 절차 안에서), 그 사용자·시장의 튜토리얼 계좌가 이전 잔고·예약·손익과 무관하게 현금
  1000만원·예약 현금 0원·`realizedPnl` 0원으로 초기화된다 — "거래 내역 자체가 리셋된다"는 사용자 시나리오에
  따라 현금 리셋과 손익 리셋이 같은 타이밍(같은 트랜잭션)에 함께 일어난다. 아직 튜토리얼 계좌가 없던
  사용자가 재시작하면 1000만원·`realizedPnl` 0원으로 새로 생성된다.
- [ ] TUTORIAL-CASH-ISOL-007: `Account.sandboxCashAdjustment`는 이 spec 배포 이후 더 이상 쓰기·읽기 대상이
  아니다(`033` SANDBOX-EXCL-006·EXCL-007 대체). 계좌 요약(`AccountSummaryResponse`)·포트폴리오 합산 요약
  (`PortfolioSummaryResponse`)의 `totalValue`는 "현금(`cashBalance`) + 실제 보유종목 평가금액"으로
  계산한다(뺄 대상이 없어졌으므로 `033` 이전 공식으로 되돌아간다). 컬럼 자체의 물리적 제거는 이 spec의
  범위가 아니다(아래 "범위 제외").
- [ ] TUTORIAL-CASH-ISOL-008: 이 spec을 배포하는 시점에, 이미 존재하는 모든 실제 계좌에 대해
  `cash_balance ← cash_balance - sandbox_cash_adjustment`, `sandbox_cash_adjustment ← 0`을 적용한다.
  샌드박스 활동이 없었던 계좌(`sandbox_cash_adjustment = 0`)는 `cash_balance`가 변하지 않는다. 이 갱신은
  멱등이다 — 갱신 직후에는 `sandbox_cash_adjustment`가 항상 0이므로 재실행해도 추가 변화가 없다.
- [ ] TUTORIAL-CASH-ISOL-009: 실제 종목(`isTutorialSample()=false`) 매매·완료 관련 `Account.cashBalance`·
  `Account.reservedCash` 동작은 이 spec으로 전혀 바뀌지 않는다. `033`의 포트폴리오·투자일기·랭킹 제외
  (SANDBOX-EXCL-001~004), `accounts.realized_pnl` 백필(SANDBOX-EXCL-005), `trades.realized_pnl` 원장 유지
  원칙(SANDBOX-EXCL-009 상당)도 이 spec으로 바뀌지 않는다.
- [x] TUTORIAL-CASH-ISOL-010 (결정 완료, 이슈 #461): `intentionId` 없는 일반 리스크관리 OCO(`021`)는 이미
  production에 있었고, 생성 시점에는 대상 holding이 샌드박스 종목인지 판정하는 코드가 없었다(직접 확인
  결과 — `ExitPlanCreationService` 호출부에 `isTutorialSample()` 분기가 존재하지 않았다). 이 spec은 그
  OCO가 **체결될 때**의 현금 처리(`ExitPlanFillService` → `finalizeSellRealizedPnl`, TUTORIAL-CASH-ISOL-003으로
  커버)만 격리했고, "샌드박스 holding에 OCO 생성 자체를 막을지"는 이 spec의 결정 범위 밖으로 남겨 뒀었다.
  이슈 #461에서 추적한 결과, 현금은 격리돼도 그 OCO 체결이 `Order.create(...)`(교육 attempt 귀속 없음)로
  진행돼 재시작·진행 판정(`practiceAttemptId` 기반 순체결수량 계산, `PracticeRunRestartOrderService`)이
  복구 불가능하게 깨진다는 것이 확인됐다 — "현금은 안전하니 막을 필요 없다"는 이 spec의 잠정 판단은 현금
  축에서는 맞지만 진행 판정 축에서는 틀렸다. **1안(생성 자체 차단)으로 결정** — `ExitPlanService`가
  `validateMarketIsCrypto` 옆에서 대상 holding이 샌드박스 종목이면 409 `EXIT_PLAN_TUTORIAL_INSTRUMENT_NOT_ALLOWED`로
  거부한다(`021` spec RISK-OCO-014가 정본). 이미 걸려 있는 PENDING plan이나 이미 전량 체결돼 잠긴 attempt를
  구제하는 것은 이 결정의 범위가 아니다(1안의 알려진 한계 — 앞으로만 막는다).
- [ ] TUTORIAL-CASH-ISOL-011: 튜토리얼 진입·재시작 응답(후보: `PracticeAttemptResponse` — 확정은 구현 PR
  몫, 아래 "설계 판단 — API 노출" 참고)이 그 시장의 튜토리얼 계좌 현재 `cashBalance`·`availableCash`
  (`cashBalance - reservedCash`)·`realizedPnl`을 노출하는 필드를 포함한다. 이 응답들은 이미 같은 트랜잭션
  안에서 튜토리얼 계좌를 get-or-create하므로(TUTORIAL-CASH-ISOL-001, "설계 판단 — 계좌 생성 시점"), 응답
  시점에 항상 계좌가 존재해 "계좌 미생성" 상태를 별도로 표현할 필요가 없다 — 매수 전이라면 초기값
  (1000만원·1000만원·0원)이 그대로 노출된다.

## 설계 판단

### 완전히 분리된 튜토리얼 전용 계좌 엔티티

이 spec을 준비하며 두 가지 방향을 검토했다.

1. **기존 `Account`에 컬럼만 추가** — `tutorialCashBalance`·`tutorialReservedCash` 같은 컬럼을 기존
   `accounts` 테이블에 추가하고, 샌드박스 매매는 그 컬럼만 증감시킨다.
2. **완전히 분리된 튜토리얼 전용 계좌 엔티티/테이블 신설** — 실제 계좌와 물리적으로 분리된 새 엔티티를
   만들고, 사용자·시장 조합마다 하나씩 갖는다.

**2번(분리된 엔티티)을 채택한다.** 근거는 사용자와의 논의에서 나온 다음 판단이다 — "튜토리얼은 보통 초반에
한 번 하고 끝나는 성격이라 계좌 자체를 분리하는 게 맞다." 부연하면:

- 튜토리얼은 온보딩 성격의 일회성(또는 소수 회) 경험이다. 실제 계좌와 생명주기가 다르다 — 실제 계좌는
  회원 탈퇴 전까지 계속 쓰이지만, 튜토리얼 계좌는 재시작마다 통째로 리셋되는 것이 자연스러운 모델이다
  (TUTORIAL-CASH-ISOL-006). 컬럼 방식이면 "이 컬럼만 리셋하고 다른 컬럼(`realizedPnl`, `seedMoney` 등)은
  그대로 둔다"는 부분 리셋을 매번 신경 써서 구현해야 하지만, 분리된 엔티티는 "이 행 자체를 초기값으로
  되돌린다"로 단순하다.
- 컬럼 방식은 `accounts` 테이블에 튜토리얼 전용 의미의 컬럼이 계속 누적된다 — 이미 `sandbox_cash_adjustment`
  (`033`, 이번 spec으로 폐지)가 그 전례였고, 그 컬럼의 목적이 좁아 다른 용도로 재활용할 수 없었던 것과
  같은 문제가 반복될 수 있다. 분리된 엔티티는 튜토리얼 전용 개념이 늘어나도(예: 향후 시장별 통계) 실제
  계좌 스키마를 건드리지 않고 그 엔티티 안에서 확장할 수 있다.
- 분리된 엔티티는 "실제 계좌처럼 동작하되 실제 계좌가 아니다"라는 개념을 코드로도 명확히 드러낸다 —
  `TutorialAccount.deductCash(...)`처럼 호출부에서 "지금 이건 진짜 계좌가 아니다"가 타입 수준에서 드러나,
  실수로 실제 `Account`와 혼동해 호출하는 버그(이번 이슈 #450의 근본 원인과 같은 종류)를 컴파일 타임에
  줄인다. 컬럼 방식은 여전히 같은 `Account` 객체의 필드이므로 "샌드박스면 이 필드, 아니면 저 필드"라는
  분기를 서비스 코드 곳곳에서 사람이 계속 정확히 지켜야 한다.

트레이드오프: 분리된 엔티티는 새 테이블·마이그레이션·repository·service가 필요해 컬럼 추가보다 변경 표면이
크다. 이 spec은 그 비용을 감수한다 — money duplication의 근본 원인이 "튜토리얼 현금과 실제 현금이 같은
필드를 공유한다"는 데 있었으므로, 애초에 필드를 공유하지 않는 설계가 재발 방지에 더 근본적이라고 판단한다.

### 오류 코드

튜토리얼 계좌 현금 부족(TUTORIAL-CASH-ISOL-005)에 기존 `ErrorCode.INSUFFICIENT_CASH`를 재사용할지, 새
코드를 만들지 검토했다. **새 코드 `TUTORIAL_INSUFFICIENT_CASH`를 신설하는 것으로 확정한다.** 근거:

- 이 spec의 핵심 전제가 "튜토리얼 계좌는 실제 계좌와 완전히 다른 자원"이라는 것인데, 오류 코드를 공유하면
  클라이언트·로그를 보는 사람이 "지금 실제 돈이 부족한 건지, 튜토리얼 가상 잔고가 부족한 건지"를 코드만
  보고 구분할 수 없다. 특히 이 spec의 배경이 된 이슈 #450 자체가 "튜토리얼과 실제가 같은 자원을 공유해서
  생긴 혼동"이었으므로, 오류 코드 단계에서부터 명확히 분리하는 편이 같은 종류의 혼동 재발을 줄인다.
- 프론트엔드가 "가상 잔고가 부족합니다. 다시 시작해 보세요" 같은 튜토리얼 전용 안내 문구를 보여주려면
  실제 계좌 부족 안내("입금이 필요합니다" 등)와 다른 코드가 필요하다 — 코드가 같으면 프론트가 메시지
  본문 문자열로 분기해야 하는데, 이는 `conventions.md`가 지향하는 "의도가 드러나는" 설계와 어긋난다.
- 변경 비용은 `common`의 `ErrorCode` enum에 항목 하나를 추가하는 정도로 작다(기존 `INSUFFICIENT_CASH`와
  동일하게 `HttpStatus.CONFLICT`, 메시지는 "튜토리얼 계좌의 현금 잔고가 부족합니다." 형태를 제안 — 확정은
  구현 PR에서 `conventions.md`의 에러 메시지 규칙에 맞춰 확정한다).

### 계좌 생성 시점

튜토리얼 전용 계좌를 최초 샌드박스 매수 시점에 lazy 생성할지, 튜토리얼 진입(`PUT
/api/education/practice/attempts/{market}`) 시점에 미리 생성해 둘지 검토했다. **진입 시점 get-or-create로
확정한다** — 그 진입 처리(`PracticeAttemptService.ensureAttempt`)가 이미 같은 사용자·시장을 대상으로
`PracticeAttempt` 행을 잠그고 get-or-create하는 유일한 직렬화 지점이므로, 같은 트랜잭션·같은 잠금 범위
안에서 튜토리얼 계좌도 함께 get-or-create하면 최초 매수 경로에 별도의 동시성 처리(유니크 제약 위반 후
재조회 등)를 새로 설계하지 않아도 된다.

### API 노출

튜토리얼 계좌 잔고·손익을 프론트엔드가 보여줄 수 있으려면 어떤 응답으로든 노출돼야 한다. **이번 spec의
범위에 포함하기로 확정한다**(TUTORIAL-CASH-ISOL-011). 노출 대상 응답의 정확한 DTO·필드명·실제 컨트롤러
코드 변경은 구현 PR에서 확정하되, 이 spec은 "어떤 값이, 언제 노출돼야 하는가"라는 계약만 정의한다 — 유력한
후보는 튜토리얼 진입·재시작 응답(`PracticeAttemptResponse`)에 `tutorialCashBalance`·`tutorialAvailableCash`·
`tutorialRealizedPnl` 세 필드를 추가하는 것이다. 이 응답은 이미 그 시장의 튜토리얼 계좌를 get-or-create하는
지점이므로(위 "계좌 생성 시점"), 추가 조회 없이 같은 트랜잭션에서 얻은 값을 그대로 실어 보낼 수 있어
변경 표면이 가장 작다. `docs/api-routes.md`·`docs/api-contracts.md` 파일 자체의 갱신은 CLAUDE.md 규칙 7에
따라 실제 컨트롤러 변경이 커밋되는 시점(구현 PR)에 한다(아래 "범위 제외").

## 비즈니스 규칙

- "샌드박스 기원" 판정 기준은 `033`과 동일하게 항상 `instruments.is_tutorial_sample`이다. 완료 보상은
  종목이 아니라 지급 행위 자체가 항상 샌드박스 기원이지만, TUTORIAL-CASH-ISOL-004에 따라 예외적으로 실제
  계좌에 지급된다.
- `Account.cashBalance`·`Account.reservedCash`는 이 spec 이후 샌드박스 매매·체결로 **어떤 경우에도** 변하지
  않는다(완료 보상 예외). 이것이 `033`의 "cashBalance는 실제 돈이므로 손대지 않는다"는 문장과 다른 점이다.
  `033`은 "손대지만 표시에서 뺀다"였고, 이 spec은 "애초에 손대지 않는다"이다.
- 튜토리얼 계좌는 사용자·시장 조합당 정확히 하나다(실제 `Account`와 동일한 유일성 규칙). 시장이 다르면
  (STOCK·CRYPTO) 잔고도 완전히 독립이다 — 한쪽 재시작이 다른 쪽 잔고에 영향을 주지 않는다.
- 튜토리얼 계좌는 holding(보유종목)을 추적하지 않는다 — 그 책임은 여전히 실제 `Account`에 연결된
  `Holding`·`Trade` 원장이 진다(`033`이 이미 그 원장을 랭킹·포트폴리오 표시에서 제외하는 방식으로 처리하고
  있고, 이 spec은 그 부분을 바꾸지 않는다). 즉 `Order`·`Trade`·`Holding`의 계좌 외래키는 이 spec 이후에도
  여전히 실제 `Account`를 가리킨다 — 이 spec이 새로 옮기는 것은 현금 증감 호출의 대상과, 아래에서 다루는
  이번 판 실현손익(`realizedPnl`) 추적뿐이다.
- 튜토리얼 계좌는 실현손익(`realizedPnl`)을 추적한다 — 실제 `Account.realizedPnl`(랭킹 집계용, `033`이
  샌드박스 매도를 계속 제외)과는 별개 값으로, "이번 판(현재 run)에서 샌드박스 매매로 낸 손익 누계"를
  의미한다. 매도 체결(TUTORIAL-CASH-ISOL-003의 모든 경로)마다 그 체결의 실현손익만큼 증가·감소하며,
  재시작(TUTORIAL-CASH-ISOL-006)마다 현금·예약 현금과 같은 타이밍에 0으로 리셋된다 — "거래 내역 자체가
  리셋된다"는 사용자 시나리오가 현금뿐 아니라 손익에도 동일하게 적용되는 것이 자연스럽다고 판단했다.
- 튜토리얼 계좌의 현금 부족 시 매수를 거부하는 검증은 실제 계좌의 `INSUFFICIENT_CASH` 검증과 동등한 강도로
  작동한다(TUTORIAL-CASH-ISOL-005) — "가상이지만 진짜 예산 제약"이라는 목표에 따라, 검증을 생략하거나
  느슨하게 하지 않는다.
- `trades.realized_pnl`(체결 단위 원장 값)과 `accounts.realized_pnl`(실제 계좌 집계·랭킹 score)의 구분,
  `accounts.realized_pnl`이 샌드박스 매도를 집계에서 제외하는 규칙(`033` SANDBOX-EXCL-004)은 이 spec과
  독립이며 변경하지 않는다 — 이 spec은 현금(`cash_balance`/`reserved_cash`)의 대상 계좌만 바꾼다.
- 재시작(`040`)의 정리 순서·잠금·순체결수량 계산·완료 보상 1회 캡은 이 spec으로 바뀌지 않는다. 이 spec은
  그 정리 트랜잭션 안에 "튜토리얼 계좌 리셋"이라는 단계를 하나 추가할 뿐이다(TUTORIAL-CASH-ISOL-006).

## 범위 제외

- **완료 보상(500만원) 자체의 축소·폐지, 시장당 1회 캡 로직 변경** — 대상 아님. `040`이 이미 안전하게
  캡을 걸어 두었고 이 spec은 그 지급 대상(실제 계좌)도 바꾸지 않는다.
- **`041-tutorial-market-scenario`의 가격 생성 로직(사건-가격 시나리오)** — 대상 아님. 이 spec은 가격이
  어떻게 만들어지는지와 무관하게 그 가격으로 이뤄지는 매매의 현금 처리 대상만 다룬다.
- **재시작 횟수 제한·쿨다운** — 이번 spec에서는 채택하지 않는다. 튜토리얼 계좌가 실제 계좌와 완전히
  분리되면 재시작을 얼마나 반복하든 실제 현금 익스플로잇이 성립하지 않으므로, money duplication 문제
  자체는 이 spec 하나로 해소된다고 판단한다. 무제한 재시작·쿨다운 없음이 서버 자원(주문·체결 원장 무한
  증가) 관점에서 남기는 우려는 이 spec의 범위가 아니다.
- **`sandbox_cash_adjustment` 컬럼의 물리적 `DROP`** — 이번 배포는 쓰기·읽기만 중단한다. 파괴적 스키마
  변경 2단계 배포 원칙(CLAUDE.md 규칙 8)에 따라 물리적 제거는 후속 spec/이슈로 분리한다. (신설하는
  튜토리얼 계좌 테이블·컬럼 자체는 파괴적 변경이 아니므로 이 원칙과 무관하다.)
- **샌드박스 holding에 `intentionId` 없는 일반 OCO 생성을 막을지 여부** — 이 spec 작성 시점에는 결정하지
  않고 현금 격리만 다뤘으나, TUTORIAL-CASH-ISOL-010에 기록한 대로 이슈 #461이 후속으로 결정·구현했다
  (`021` spec RISK-OCO-014, `ExitPlanService`의 생성 시점 차단).
- **"옵션 2"(호출 자체를 생략)와의 차이 — 현금 부족 개념의 존속.** 이 spec 준비 초기에는 "샌드박스 매매는
  현금 변경 메서드 호출 자체를 생략한다"는 대안(호출 생략)도 검토됐으나, 사용자와의 논의 끝에 기각됐다 —
  분리된 계좌를 두는 이유 자체가 "진짜 계좌처럼" 예산 제약을 실감나게 두기 위함이므로, 호출을 생략해
  현금 부족 개념을 아예 없애는 대안과는 방향이 다르다(TUTORIAL-CASH-ISOL-005).
- **프론트엔드(finplay-frontend) 구현** — 이 spec은 백엔드 계약(현금·요약 응답 계산, 튜토리얼 계좌 존재
  여부)만 정의한다. 화면 문구·설명 변경은 별도 저장소 작업이다.
- **`docs/api-routes.md`·`docs/api-contracts.md`·`docs/prd.md` §3 갱신** — 이 spec 작성 단계에서는 하지
  않는다. TUTORIAL-CASH-ISOL-011이 "어떤 필드가 응답에 있어야 하는가"라는 계약은 정의하지만, 실제
  컨트롤러·DTO 코드 변경과 그 두 문서의 갱신은 CLAUDE.md 규칙 7·10에 따라 실제 구현 PR이 머지되는
  시점에 그 PR이 함께 한다(`tasks.md`에 체크리스트 항목으로 남긴다).

## 완료 조건

- [ ] STOCK·CRYPTO 각각 독립적인 튜토리얼 계좌가 최초 접근 시 1000만원으로 생성되고, 서로의 잔고에 영향을
  주지 않음이 테스트로 확인된다.
- [ ] 샌드박스 종목 시장가·지정가 매수(생성·체결) 전후로 실제 `Account.cashBalance`·`reservedCash`가 변하지
  않고, 튜토리얼 계좌의 현금만 변함이 테스트로 확인된다.
- [ ] 샌드박스 종목 매도(시장가·지정가 체결, 재시작 보상매도, OCO 손절·익절 체결) 전후로 실제
  `Account.cashBalance`가 변하지 않고, 튜토리얼 계좌의 현금만 늘며, `trades.realized_pnl`은 여전히 채워짐이
  테스트로 확인된다.
- [ ] 튜토리얼 완료 보상 지급 전후로 실제 `Account.cashBalance`가 정확히 500만원만큼 늘고(변경 없음), 튜토리얼
  계좌는 그 지급으로 변하지 않음이 테스트로 확인된다.
- [ ] 튜토리얼 계좌 현금이 부족한 상태에서 샌드박스 매수를 시도하면 실제 계좌 잔고와 무관하게 거부됨이
  테스트로 확인된다.
- [ ] 재시작 시 그 시장의 튜토리얼 계좌가 이전 잔고·예약과 무관하게 현금 1000만원·예약 0원으로 초기화됨이
  테스트로 확인된다.
- [ ] 계좌 요약·포트폴리오 합산 요약의 `totalValue`가 `sandboxCashAdjustment` 없이 "현금 + 실제 보유종목
  평가금액"으로 계산됨이 확인된다.
- [ ] 배포 시점 백필이 기존에 오염된 실제 계좌의 `cash_balance`를 `sandbox_cash_adjustment`만큼 정확히
  원복하고 `sandbox_cash_adjustment`를 0으로 재설정하며, 재실행해도 값이 추가로 변하지 않음(멱등성)이
  확인된다.
- [ ] 매수 → 관찰 → 매도(또는 재시작 보상매도) → 재시작을 여러 차례 반복하는 통합 시나리오에서 실제
  `Account.cashBalance`가 반복 전 구간 내내 최초 값과 정확히 동일하게 유지됨이 Testcontainers로 확인된다
  (이슈 #450 재현 시나리오의 역-검증).
- [ ] 실제 종목만 다루는 기존 주문·계좌·포트폴리오 테스트가 이 변경 이후에도 회귀 없이 통과한다.
- [ ] 샌드박스 종목 매도 체결마다 튜토리얼 계좌의 `realizedPnl`이 그 체결의 실현손익만큼 증감하고, 재시작 시
  현금·예약 현금과 같은 타이밍에 0으로 리셋됨이 테스트로 확인된다.
- [ ] 튜토리얼 계좌 현금 부족 시 새 오류 코드 `TUTORIAL_INSUFFICIENT_CASH`가 반환되고, 실제 계좌 현금 부족(`INSUFFICIENT_CASH`)과
  구별되는 별개 코드임이 테스트로 확인된다.
- [ ] 튜토리얼 진입·재시작 응답(후보: `PracticeAttemptResponse`)이 그 시점 튜토리얼 계좌의 `cashBalance`·
  `availableCash`·`realizedPnl`을 정확히 반영해 노출함이 확인된다(TUTORIAL-CASH-ISOL-011).
