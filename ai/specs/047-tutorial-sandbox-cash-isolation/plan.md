# Plan: 튜토리얼 전용 계좌 신설을 통한 샌드박스 매매·완료 보상 현금 격리

## 관련 문서

- Spec: `./spec.md`
- 관련 ADR: ADR-0002(레이어드 아키텍처, 도메인 패키지), ADR-0004(Flyway 마이그레이션), ADR-0012(튜토리얼
  즐겨찾기·의도는 인메모리 — **이 spec의 `TutorialAccount`는 이 ADR과 무관하게 DB 영속이다**, 아래 "왜
  인메모리가 아닌가" 참고)
- 상호 참조: `021-general-risk-management-oco`(OCO 체결 현금 처리는 이 spec이 정본), `040-tutorial-restart-after-completion`
  (재시작 절차 정본은 040, 이 spec은 그 절차에 계좌 리셋 단계만 추가)

## 왜 인메모리가 아닌가 (ADR-0012와의 관계)

ADR-0012는 즐겨찾기·사전 의도(`practice_intentions`)를 재시작 시 유실돼도 되는 임시 상태로 보고
인메모리로 뒀다. `TutorialAccount`는 다른 성격이다 — 현금 잔고는 "지금 얼마 남았는가"가 매 요청마다
정확해야 하는 값이고, `SELECT ... FOR UPDATE` 행 잠금으로 동시 매수·매도 경합을 직렬화해야 하는 대상이다
(기존 `Account`와 동일한 요구). 인메모리 `ConcurrentHashMap` + `ReentrantLock` 조합도 이론적으로는
가능하지만, 이미 `Account`가 이 패턴(엔티티 + `FOR UPDATE`)으로 안정적으로 동작하고 있고 서비스 로직
(잠금 순서 `account → holding`, 예약/확정/해제)을 그대로 재사용할 수 있어 새 동시성 모델을 설계하는 비용이
없다. 따라서 `TutorialAccount`는 DB 영속 엔티티로 만든다.

## 데이터 모델

### 신규 엔티티 — `TutorialAccount`

패키지 위치: `com.finplay.api.account.domain`(기존 `Account`와 같은 도메인 — 계좌 개념의 변형이지 별도
도메인이 아니다). **엔티티·테이블 이름은 확정됐다(`TutorialAccount`/`tutorial_accounts`).**

```java
@Entity
@Table(name = "tutorial_accounts",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "market"}))
public class TutorialAccount {
    private static final long INITIAL_CASH = 10_000_000L;

    private Long id;
    private User user;           // @ManyToOne, user_id FK
    private Market market;       // STOCK | CRYPTO
    private long cashBalance;    // 초기값 10_000_000
    private long reservedCash;   // 초기값 0
    private long realizedPnl;    // 초기값 0 — 이번 판(현재 run)의 샌드박스 매매 손익 누계
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Account와 동일한 메서드 집합 — 호출부 코드 패턴을 그대로 재사용하기 위함
    public void deductCash(long amount) { ... }         // 초과 차감 시 IllegalStateException
    public void addCash(long amount) { ... }
    public long getAvailableCash() { return cashBalance - reservedCash; }
    public void reserveCash(long amount) { ... }         // 초과 예약 시 IllegalStateException
    public void confirmReservedCash(long amount) { ... }
    public void releaseReservedCash(long amount) { ... }
    public void addRealizedPnl(long amount) { this.realizedPnl += amount; }

    // 재시작 훅(TUTORIAL-CASH-ISOL-006) — 현금·예약 현금·손익을 같은 타이밍에 초기값으로 되돌린다.
    public void reset(LocalDateTime now) {
        this.cashBalance = INITIAL_CASH;
        this.reservedCash = 0L;
        this.realizedPnl = 0L;
        this.updatedAt = now;
    }
}
```

`Account`와 필드 이름·검증 로직(초과 차감·초과 예약 시 `IllegalStateException`)을 동일하게 맞춘다 — 호출부
(`OrderExecutionService` 등)가 `Account`든 `TutorialAccount`든 같은 모양의 메서드를 호출하게 해서 분기
코드를 최소화한다. `seedMoney`·`sandboxCashAdjustment`는 두지 않는다 — 실제 `Account`처럼 항상 같은
초기값(1000만원)에서 시작하므로 `seedMoney`를 별도로 저장할 필요가 없고(리셋마다 `INITIAL_CASH` 상수를
그대로 재사용), `sandboxCashAdjustment`는 표시 보정용으로 실제 `Account`에만 존재했던 개념이라 애초에
대응 개념이 없다. `realizedPnl`은 둔다(TUTORIAL-CASH-ISOL-001·003 — 사용자 확정 사항).

`Holding`·`Trade`·`Order`의 계좌 외래키는 변경하지 않는다 — 계속 실제 `Account.id`를 가리킨다. 이 spec은
현금·손익 증감 호출의 **대상 객체**만 `TutorialAccount`로 바꾼다. `TutorialAccount.realizedPnl`은 실제
`Account.realizedPnl`(랭킹 집계, `033`이 샌드박스 매도를 계속 제외)과 완전히 독립된 값이며 랭킹·포트폴리오
어디에도 집계되지 않는다 — 오직 TUTORIAL-CASH-ISOL-011의 진입/재시작 응답에만 노출된다.

### 마이그레이션

`src/main/resources/db/migration/V46__create_tutorial_accounts_and_backfill_cash.sql`(다음 빈 버전 —
착수 시점에 `origin/dev`·열려 있는 PR을 다시 확인해 충돌 여부를 재검증한다, `docs/specs/README.md` 번호
규칙과 동일한 원칙을 마이그레이션 버전에도 적용). 두 부분으로 구성한다.

1. `CREATE TABLE tutorial_accounts` — 위 엔티티 컬럼(`realizedPnl` 포함) 그대로, `UNIQUE KEY (user_id, market)`,
   `user_id`에 `accounts.user_id`와 동일한 FK.
2. 백필: `UPDATE accounts SET cash_balance = cash_balance - sandbox_cash_adjustment, sandbox_cash_adjustment = 0 WHERE sandbox_cash_adjustment <> 0;`
   — TUTORIAL-CASH-ISOL-008. `sandbox_cash_adjustment = 0`인 행은 `WHERE` 절에서 자연히 제외되어 멱등이다
   (재실행해도 대상 행이 없다).

`sandbox_cash_adjustment` 컬럼 자체는 이 마이그레이션에서 DROP하지 않는다(spec "범위 제외").

## TutorialAccountService — API

`com.finplay.api.account.service.TutorialAccountService` 신설. `AccountService`와 동일한 패턴.

| 메서드 | 용도 |
|---|---|
| `TutorialAccount getOrCreateForUpdate(Long userId, Market market, LocalDateTime now)` | `SELECT ... FOR UPDATE`로 조회, 없으면 1000만원·`realizedPnl` 0원으로 생성 후 즉시 재조회(락 유지) |
| `void resetForUpdate(Long userId, Market market, LocalDateTime now)` | 재시작 훅 — 존재하면 `reset()`(현금·예약 현금·`realizedPnl`을 같은 타이밍에 초기화), 없으면 생성(TUTORIAL-CASH-ISOL-006) |

**생성 시점**: 최초 샌드박스 매수 시도 시 lazy 생성이 아니라, 튜토리얼 진입(`PUT
/api/education/practice/attempts/{market}`, `PracticeAttemptService.ensureAttempt`)에서 `PracticeAttempt`
행을 `findByUserIdAndMarketForUpdate` + 없으면 생성하는 것과 같은 트랜잭션·같은 사용자·시장 잠금 안에서
`TutorialAccount`도 함께 get-or-create한다. 이렇게 하면 최초 매수 경로에 별도 동시성 처리(유니크 제약
위반 재시도 등)를 추가하지 않아도 된다 — 진입 시점에 이미 `PracticeAttempt`가 그 사용자·시장에 대해
직렬화된 유일한 생성 지점이기 때문이다(확정, spec "설계 판단 — 계좌 생성 시점").

## 호출부 변경 지점 (이슈 #450이 확인한 6곳 + `finalizeSellRealizedPnl` 시그니처)

| # | 파일 | 현재 | 변경 |
|---|---|---|---|
| 1 | `OrderExecutionService.createBuyOrder` | `account.deductCash(cashRequired)` 무조건 | `instrument.isTutorialSample()`이면 `tutorialAccountService.getOrCreateForUpdate(userId, market, now).deductCash(cashRequired)`로 대체. 현금 부족 검증도 이 튜토리얼 계좌 기준으로 전환하고, 이때 던지는 예외는 `ErrorCode.TUTORIAL_INSUFFICIENT_CASH`로 분기(TUTORIAL-CASH-ISOL-005) |
| 1 | `OrderExecutionService.createSellOrder` | `account.addCash(...)` 무조건 | 샌드박스면 튜토리얼 계좌 `addCash`+`addRealizedPnl`로 대체. 랭킹 집계(`account.addRealizedPnl`)는 그대로 실제 `Account`에 남는다(033 유지, 이 spec은 현금·튜토리얼 손익만 옮긴다) |
| 2 | `PortfolioSellService.finalizeSellRealizedPnl` | `account.addCash(amount - fee)` 무조건 | 인자로 받은 `Account`는 그대로 `Trade`·실현손익 랭킹 집계용으로 남기고, `sellTrade.getInstrument().isTutorialSample()`이면 `account.getUser().getId()`·`account.getMarket()`으로 튜토리얼 계좌를 조회·잠가 `addCash(amount - fee)` + `addRealizedPnl(realizedPnl)`. 호출부(시장가·지정가 매도 체결, 재시작 보상매도, OCO 체결) 4곳은 변경 없음 — 이 메서드 하나만 고치면 전부 반영된다 |
| 3 | `PracticeLimitOrderCreationService.createSessionBuyOrder` | `account.reserveCash(cashRequired)`(CRYPTO 고정, `isTutorialSample` 체크 없음), 현금 부족 시 `INSUFFICIENT_CASH` | 이 엔드포인트는 항상 튜토리얼 전용이므로 무조건 튜토리얼 계좌로 리다이렉트(실제 `Account` 조회 자체를 생략 가능 — 다만 `Order`·`Trade`의 FK는 실제 `Account`가 필요하므로 조회는 유지하고 현금 메서드 호출만 튜토리얼 계좌로 옮긴다). 현금 부족 오류도 `TUTORIAL_INSUFFICIENT_CASH`로 전환 |
| 4 | `LimitOrderFillService.fillBuy`/`fillSell` | `releaseReservedCash`+`deductCash` 또는 `confirmReservedCash`; 매도는 `finalizeSellRealizedPnl` 경유 | `fillBuy`에서 `instrument.isTutorialSample()`이면 세 메서드 모두 튜토리얼 계좌 대상으로 전환. `fillSell`은 2번 변경으로 자동 커버 |
| 5 | `PracticeRunRestartOrderService.cancelPendingOrders` | `account.releaseReservedCash(...)`(이 서비스는 `validateInstrument`가 `isTutorialSample()`을 이미 강제하므로 도달하는 모든 호출이 튜토리얼 대상) | 무조건 튜토리얼 계좌로 전환 |
| 5 | `PracticeRunRestartOrderService.createCompensatingSell` | `finalizeSellRealizedPnl(account, ...)` | 2번 변경으로 자동 커버 |
| 5 | `PracticeAttemptRestartService.restart` 또는 `PracticeRunRestartOrderService.cleanupCurrentRun` | (신규) | `cleanupCurrentRun` 트랜잭션 끝에 `tutorialAccountService.resetForUpdate(userId, market, restartedAt)` 호출 추가(TUTORIAL-CASH-ISOL-006, 현금·예약 현금·`realizedPnl` 동시 리셋). `cleanupCurrentRun`이 이미 같은 트랜잭션·같은 사용자·시장으로 계좌를 다루고 있으므로 위치가 자연스럽다 |
| 6 | `ExitPlanFillService.executeMarketSell` | `finalizeSellRealizedPnl(account, ...)`, `isTutorialSample` 체크 전무 | 2번 변경으로 자동 커버 — 이 파일 자체는 수정 불필요 |

`PracticeHoldingReflectionService.payTutorialCompletionReward`는 **변경하지 않는다**(TUTORIAL-CASH-ISOL-004,
실제 `account.addCash(TUTORIAL_COMPLETION_REWARD_AMOUNT)` 그대로, 튜토리얼 계좌는 관여하지 않음).

## 요약 응답 — `totalValue` 공식 원복

`AccountService`(계좌 요약)의 `totalValue = cashBalance + holdingsValue - account.getSandboxCashAdjustment()`를
`totalValue = cashBalance + holdingsValue`로 되돌린다(TUTORIAL-CASH-ISOL-007). `PortfolioService`는 이
값을 그대로 합산하므로 별도 변경이 필요 없다. `Account.sandboxCashAdjustment` 필드·`addSandboxCashAdjustment()`
메서드는 코드에서 호출 지점을 전부 제거하되(위 표의 5개 호출부 중 이미 `if (instrument.isTutorialSample())
account.addSandboxCashAdjustment(...)` 형태였던 곳들), 엔티티 필드·컬럼 자체는 남긴다(범위 제외).

## API 설계 — 이번 spec에서 컨트롤러를 바꾸는지 여부

이 spec은 **기존 엔드포인트의 URL·요청 계약을 바꾸지 않는다.** 매수·매도·지정가·재시작 API는 이미 있는
그대로 호출된다 — 내부적으로 어느 계좌를 건드리는지만 바뀐다.

**튜토리얼 계좌 잔고·손익 노출은 이번 spec 범위에 포함된다(TUTORIAL-CASH-ISOL-011, 확정).** 후보 응답은
`PracticeAttemptResponse`(진입·재시작 응답)이며, 다음 세 필드를 추가하는 것을 이 plan의 기준안으로 삼는다.

| 필드 | 타입 | 값 |
|---|---|---|
| `tutorialCashBalance` | `long` | `TutorialAccount.cashBalance` |
| `tutorialAvailableCash` | `long` | `TutorialAccount.cashBalance - TutorialAccount.reservedCash` |
| `tutorialRealizedPnl` | `long` | `TutorialAccount.realizedPnl` |

`PracticeAttemptService.ensureAttempt`·`PracticeAttemptRestartService.restart`가 이미 같은 트랜잭션에서
`TutorialAccount`를 get-or-create/reset하므로(위 "TutorialAccountService — API"), 추가 조회 없이 그 결과를
`PracticeAttemptResponse.from(...)`에 그대로 실어 보낼 수 있어 변경 표면이 가장 작다 — 새 엔드포인트를 만들
필요가 없다. **다만 실제 컨트롤러·DTO 코드 작성과 `docs/api-routes.md`·`docs/api-contracts.md` 갱신은 이
spec 문서 작업의 범위가 아니다** — CLAUDE.md 규칙 7에 따라 구현 PR이 커밋 하나로 코드·두 문서를 함께
갱신한다. 이 plan은 "어떤 필드가, 어느 값으로 채워져야 하는가"라는 계약만 확정해 둔다.

## 오류 코드

TUTORIAL-CASH-ISOL-005(튜토리얼 계좌 현금 부족)는 **새 오류 코드 `TUTORIAL_INSUFFICIENT_CASH`를 신설하는
것으로 확정한다**(spec "설계 판단 — 오류 코드" 참고, 기존 `INSUFFICIENT_CASH` 재사용안은 기각). 구현은
`common`의 `ErrorCode` enum(`src/main/java/com/finplay/api/common/ErrorCode.java`)에 기존 `INSUFFICIENT_CASH`
바로 아래 한 줄을 추가하는 정도다 — 이 파일은 구현 PR이 수정한다(이 문서는 계획만 남긴다).

```java
INSUFFICIENT_CASH(HttpStatus.CONFLICT, "현금 잔고가 부족합니다."),
TUTORIAL_INSUFFICIENT_CASH(HttpStatus.CONFLICT, "튜토리얼 계좌의 현금 잔고가 부족합니다."),
```

`HttpStatus.CONFLICT`로 기존 `INSUFFICIENT_CASH`와 동일한 상태 코드를 쓴다 — "잔고 대비 요청이 유효하지
않다"는 성격이 같고, 다른 상태 코드를 쓸 근거가 없다. 이 코드를 던지는 지점은 위 "호출부 변경 지점" 표의
1번(`OrderExecutionService.createBuyOrder`)·3번(`PracticeLimitOrderCreationService.createSessionBuyOrder`)이다
— 둘 다 매수 생성 시점에 현금 가용성을 검증하는 지점이며, `LimitOrderFillService`(체결)는 생성 시점에 이미
예약이 확정된 금액을 체결하므로 이 시점에 새로 현금 부족이 발생하지 않는다(기존 `Account` 체결 경로와
동일한 전제).

## 테스트 계획

- 단위: `TutorialAccount` 엔티티(초과 차감/예약 시 예외, `reset()`이 `cashBalance`·`reservedCash`·
  `realizedPnl` 셋을 모두 초기화하는지), `TutorialAccountService`(get-or-create, reset),
  `PortfolioSellService.finalizeSellRealizedPnl`의 분기(샌드박스/실제 종목별로 어느 계좌의 현금·손익이
  증가하는지).
- 슬라이스: `TutorialAccountRepository`(`@DataJpaTest` — `findByUserIdAndMarketForUpdate`, unique 제약).
- 통합(Testcontainers): 아래 시나리오를 spec 완료 조건과 1:1로 매핑.
  - 샌드박스 시장가 매수·매도 전후 실제 `Account.cashBalance` 불변 + 튜토리얼 계좌 현금·`realizedPnl` 증감.
  - 코인 지정가 매수 예약→체결, 지정가 매도 체결 동일 검증.
  - 재시작 보상매도·OCO 손절익절 체결 동일 검증(`ExitPlanFillService` 경로 포함).
  - 완료 보상 지급 시 실제 계좌만 증가, 튜토리얼 계좌(현금·`realizedPnl` 모두) 불변.
  - 재시작 시 튜토리얼 계좌 리셋 — 잔고 소진·손익 발생 후 재시작 → 현금 1000만원·`realizedPnl` 0원 동시 복귀.
  - 튜토리얼 계좌 현금 부족 시 `TUTORIAL_INSUFFICIENT_CASH`가 반환되고 실제 `INSUFFICIENT_CASH`와 코드가
    다름을 확인.
  - 진입/재시작 응답의 `tutorialCashBalance`·`tutorialAvailableCash`·`tutorialRealizedPnl`이 그 시점
    `TutorialAccount` 상태와 일치함을 확인.
  - 배포 백필 마이그레이션 적용 전후 `cash_balance`·`sandbox_cash_adjustment` 값(Flyway 마이그레이션
    테스트 또는 `@DataJpaTest`로 직접 SQL 실행 검증).
  - 이슈 #450 재현 시나리오의 역-검증(매수→관찰→매도→재시작 반복 후 실제 계좌 잔고 불변).
