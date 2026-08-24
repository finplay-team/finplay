# ADR-0028: holdings 신규 생성 INSERT 데드락을 격리수준 조정과 재시도로 완화한다

- 상태: 구현됨
- 날짜: 2026-08-21
- 관계: `ai/specs/015-limit-order/plan.md`의 "Decision Gate 설계 결정 10번"(신규 종목 첫 매수 동시 생성
  경합은 account 락만으로 방지, 방어적 유니크 제약 catch 없음)을 대체(superseded)하지 않는다. 그 결정은
  "같은 계좌가 같은 종목을 동시에 두 번 처음 사려는 경우"에 대해서는 여전히 맞다 — 이 ADR은 그 결정이
  검토하지 않은 다른 축("서로 다른 계좌들이 각자 다른 종목을 동시에 처음 사는 경우")을 보강한다.
  [이슈 #513](https://github.com/finplay-team/finplay-backend/issues/513)에서 재현·원인·대안을 먼저
  정리했고, 이 ADR은 그중 채택한 방향을 결정 기록으로 남긴다.

## 맥락

`PortfolioBuyService.applyBuyTrade`는 신규 종목 첫 매수 시 `holdings` row가 없으면 새로 INSERT한다.
`holdings`는 `uk_holdings_account_instrument` 유니크 제약(account_id, instrument_id)을 갖고 있고, 이
앱은 격리수준을 별도로 낮추지 않아 MySQL 기본값인 REPEATABLE READ를 그대로 쓴다. 이 조합에서는 서로
다른 계좌·종목이라도 유니크 인덱스에 여러 트랜잭션이 동시에 INSERT를 시도하면 실제 값이 겹치지 않아도
InnoDB 갭 락 경합으로 데드락이 날 수 있다는 게 MySQL 공식 문서가 명시한 동작이다.

`LimitOrderFillAccountLockContentionIntegrationTest`로 재현한 결과, 서로 다른 계좌 8개가 각자 다른
종목을 동시에 처음 매수하면 40건의 체결 시도 중 32건(80%)이 이 데드락으로 실패했다(상세 수치는
`docs/loadtest/holdings-insert-deadlock-result.md`). 시장가 매수(`OrderExecutionService.execute`)는
이 경로에 재시도가 없어 요청이 그대로 실패하고, 지정가 체결(`LimitOrderFillService.fillIfPending`/
`fillBatch`)은 실패한 주문이 PENDING으로 남아 다음 가격 틱에 재시도되는 방식으로 우회된다(ADR-0024
§결정 2의 재시도 철학과 동일).

## 결정

**이 데드락이 발생하는 트랜잭션의 격리수준을 READ COMMITTED로 좁혀 적용하고, 재시도 수단이 없는
시장가 매수 경로에만 데드락 발생 시 1회 재시도를 추가한다.**

### 1. 격리수준 — READ COMMITTED를 해당 트랜잭션에만 좁혀 적용

**후보**: (a) 전역 설정(`spring.jpa.properties.hibernate.connection.isolation` 등)으로 애플리케이션
전체를 READ COMMITTED로 낮춘다, (b) 데드락이 발생하는 트랜잭션 경계에만 `@Transactional(isolation =
Isolation.READ_COMMITTED)`로 좁혀 적용한다.

**(b)를 채택한다.** `OrderExecutionService.execute`(시장가 매수·매도 공통 진입점)와
`LimitOrderFillService.fillIfPending`/`fillBatch`(지정가 체결 진입점) 세 곳에만 적용한다. 전역으로
낮추면(a) 이 문제와 무관한 다른 트랜잭션(랭킹 집계, 피드백 배치 등)의 격리 수준까지 의도치 않게
바뀌어 영향 범위를 가늠하기 어렵다. 이 저장소의 핵심 원장 경로가 정확성을 지키는 방식은 애초에
REPEATABLE READ의 스냅숏 격리가 아니라 명시적 비관적 락(`SELECT ... FOR UPDATE`)이므로(account·holding
모두 이 방식), READ COMMITTED로 낮춰도 그 정확성 보장에는 영향이 없다 — 그래서 좁혀 적용해도 잃는 게
없다.

`OrderExecutionService.execute`는 매수·매도 분기가 하나의 `@Transactional` 메서드 안에 있어 매도
경로에도 함께 적용되지만, 매도는 애초에 신규 holdings row를 만들지 않아 이 데드락과 무관하다 — 함께
적용해도 위험이 없고, 매수·매도로 트랜잭션 경계를 분리하는 편이 오히려 더 큰 변경이라 그렇게 하지
않는다.

**정정(PR #514 리뷰 차단사항, 이 ADR의 최초 구현이 놓쳤던 경로).** `LimitOrderFillService`의 세 메서드에
`isolation = READ_COMMITTED`를 선언해도, 이미 열려 있는 트랜잭션에 합류(`Propagation.REQUIRED`)할 때는
Spring이 그 선언을 조용히 무시한다(`AbstractPlatformTransactionManager.validateExistingTransaction`
기본값이 `false`라 트랜잭션 속성 충돌을 검증하지 않는다). 튜토리얼(코인 모의투자) 지정가 체결이
정확히 이 경로다 — `PracticeOrderSettlementService.settleOnTick`/`settleCurrentRun`이 이미 열린
트랜잭션 안에서 `fillIfPending`을 호출하므로, 격리수준은 실제로 그 트랜잭션을 여는 지점에 명시해야
적용된다. 추적한 결과 진짜 트랜잭션 시작점은 둘이다.

- `PracticePriceTickService.advanceTick` — `PracticeTickFillListener.onTickAdvanced`(트랜잭션 없는
  일반 `@EventListener`)가 이 트랜잭션 안에서 동기 호출되므로, `advanceTick` 자신이 진짜 시작점이다.
- `PracticeAttemptChartService.tick` — 컨트롤러(`PracticeAttemptChartController`, 트랜잭션 없음)가
  직접 호출한다. `PracticeScenarioProgressService.advance`는 이 트랜잭션에 합류할 뿐이라(자신의
  `@Transactional`은 항상 no-op) 별도로 손대지 않았다.

두 메서드에 `isolation = READ_COMMITTED`를 추가해 닫았다. 다만 이 방식의 구조적 한계는 남는다 — **앞으로
`fillIfPending`을 또 다른(격리수준 미지정) 트랜잭션 안에서 호출하는 코드가 추가되면 컴파일러·테스트
없이 조용히 같은 구멍이 다시 생긴다.** 이걸 근본적으로 막으려면 대안 (a)(holdings INSERT를 원자적 upsert
SQL로 바꿔 어느 트랜잭션에서 호출돼도 안전하게 만드는 것)가 필요하지만, 지금은 발견된 구멍을 닫는 데
집중하고 이 한계를 알려진 채무로 남긴다(§후속).

### 2. 재시도 — 시장가 매수 경로에만 1회 추가

**후보**: (a) 재시도를 추가하지 않고 격리수준 조정만으로 끝낸다, (b) 데드락이 발생하는 모든 경로(시장가·
지정가)에 재시도를 추가한다, (c) 재시도 수단이 이미 없는 시장가 경로에만 추가한다.

**(c)를 채택한다.** 지정가 체결은 실패해도 주문이 PENDING으로 남아 다음 가격 틱이 다시 시도한다는
재시도 수단이 이미 ADR-0024·0025로 확립돼 있다 — 여기 재시도를 또 추가하면 "필요 이상의 상태를 만들지
않는다"는 이 저장소 관례(ADR-0024 §결정 2)와 어긋난다. 반면 시장가 매수는 실패하면 그 요청이 그대로
끝난다 — 사용자에게 재시도 수단이 전혀 없다. `OrderService.createOrder`가 멱등키 유니크 제약 위반을
캐치해 재조회하는 기존 패턴이 이미 있으므로, 같은 위치에 `CannotAcquireLockException`(Spring이
`MySQLTransactionRollbackException`을 변환한 타입, `TransientDataAccessException` 계열이라 재시도
안전성이 프레임워크 차원에서도 분류돼 있다)을 캐치해 `orderExecutionService.execute(...)`를 1회만 다시
호출한다. 데드락은 트랜잭션 전체가 롤백되므로(부분 커밋 없음) 같은 인자로 재시도해도 이중 체결·이중
차감 위험이 없다.

재시도는 1회로 제한한다. 격리수준 조정만으로 데드락 빈도가 크게 줄 것으로 보고, 무한 재시도 루프로
근본 원인을 가리는 대신 재시도가 또 실패하면 예외를 그대로 전파해 원인을 숨기지 않는다. 별도
라이브러리(`spring-retry` 등)는 도입하지 않는다 — 이 저장소에 이미 없고, 재시도 지점이 1곳뿐이라
`OrderService.createOrder`의 기존 try/catch 패턴을 그대로 확장하는 것으로 충분하다.

## 결과

**얻는 것**

- 서로 다른 계좌·종목의 동시 첫 매수가 데드락 없이(또는 최소 재시도로) 정상 체결된다.
- 정확성 보장(명시적 비관적 락)에 의존하지 않는 격리수준만 낮춰서, 이 변경이 다른 트랜잭션 정합성에
  영향을 주지 않는다.
- 시장가 매수의 요청 실패율이 낮아진다 — 지정가는 기존 재시도 철학을 그대로 재사용해 새 상태를
  만들지 않는다.

**받아들이는 대가**

- READ COMMITTED 아래에서는 같은 트랜잭션 안에서 반복 조회한 값이 그 사이 다른 트랜잭션의 커밋을
  반영해 달라질 수 있다(non-repeatable read). 이 세 트랜잭션은 계좌·보유 값을 명시적 락으로 한 번
  읽어 그 안에서만 쓰므로 실질적 위험은 낮다고 판단했지만, 새로 이 트랜잭션에 반복 조회 로직을
  추가할 때는 이 전제가 깨지지 않는지 확인해야 한다.
- 재시도가 실패의 첫 신호를 한 번 가린다 — 데드락이 실제로 잦아지면(운영 관찰 필요) 재시도만으로는
  부족하고 §후속에서 다룰 더 근본적인 대안(upsert SQL 등)을 재검토해야 한다.

## 대안과 기각 사유

**(a) `INSERT ... ON DUPLICATE KEY UPDATE` 같은 원자적 upsert SQL로 전환**

경쟁 자체를 없애는 가장 근본적인 해법이지만, JPA 엔티티 기반(`HoldingRepository.save`)으로 짜인 지금
구조에서 이 부분만 raw SQL/`JdbcTemplate`으로 빼야 해서 구현·리뷰 범위가 이 이슈의 심각도에 비해 크다.
격리수준 조정으로 데드락 빈도가 충분히 줄지 않는 것이 운영에서 확인되면 재검토한다.

**(b) `holdings` 생성 전용 애플리케이션 레벨 락(예: 종목 단위 `synchronized`·분산 락)**

이 데드락은 논리적으로 같은 행을 두고 경쟁하는 게 아니라(계좌·종목이 전부 다름) InnoDB 인덱스 구조
자체의 갭 락 때문이라, 계좌나 종목 단위로 애플리케이션 락을 걸어도 막을 수 없다 — 락의 키를 무엇으로
잡아도 서로 다른 계좌·종목 간 경쟁을 좁히지 못한다. 기각.

**(c) 아무것도 하지 않는다**

지정가는 다음 틱 재시도로 이미 우회되지만, 시장가는 그대로 사용자 요청 실패로 남는다. 이슈 #513의
재현 조건(서로 다른 계좌들의 동시 첫 매수, 예: 인기 신규 코인 상장 직후)이 이 저장소가 실제로 겪을 수
있는 시나리오와 일치해 방치하지 않기로 했다.

## 후속

- READ COMMITTED 적용 후 데드락 재현 빈도를 같은 조건(`LimitOrderFillAccountLockContentionIntegrationTest`
  baseline 시나리오)으로 재측정해 `docs/loadtest/holdings-insert-deadlock-result.md`에 추가한다.
- 운영 배포 후에도 데드락 로그(`CannotAcquireLockException`)가 무시할 수 없는 빈도로 남으면 대안 (a)
  upsert 전환을 재검토한다.
- 이슈 #501(지정가 체결 청크 내 벌크 락 최적화)처럼 이 경로의 락 자체를 줄이거나 없애는 변경을 검토할
  때는, 지금 계좌 락이 이 데드락을 부수적으로 막아주는 효과가 있었다는 점(`docs/loadtest/holdings-
  insert-deadlock-result.md` "해석" 참고)을 함께 고려해야 한다.
- **(이슈 #491, PR #521) 이 ADR의 두 수단이 적용된 지점이 하나씩 늘었다.** §결정 1의 `READ_COMMITTED`는
  네 번째로 `PracticeAttemptService.ensureAttempt`에, §결정 2의 1회 재시도는
  `PracticeAttemptDeadlockRetryService`가 감싸는 **사용자 요청 세 개**(진입·재시작·tick)에 적용됐다.
  세 경로를 고른 기준은 §결정 2와 같다 — 실패하면 사용자에게 재시도 수단이 전혀 없는 경로인가.
  종목 선택·프리셋 선택은 같은 버튼을 다시 누르면 되므로 제외했다. 위 §결정 절의 "세 곳에만"·"시장가 매수 경로에만"은
  **작성 시점의 목록이지 상한이 아니다** — 같은 판정 기준(정확성을 명시적 비관 락이 담당하는 트랜잭션인가 /
  사용자에게 다른 재시도 수단이 없는 경로인가)을 만족하면 늘어난다. 다만 #491의 교착은 **형태가 다르다** —
  이 ADR이 다룬 것은 갭 락 경합이지만 #491은 유니크 인덱스 레코드의 S→X 승격이라(`SHOW ENGINE INNODB
  STATUS`가 `locks rec but not gap`으로 확인) 격리수준 조정만으로는 고쳐지지 않았고, 근본 수정은 잠금 조회를
  INSERT보다 앞에 두는 호출 순서 변경이었다. `READ_COMMITTED`는 그 순서 변경이 새로 여는 갭 락 경로를 막는
  짝으로 따라붙은 것이다. **"교착이면 READ COMMITTED"가 아니라 교착의 형태를 먼저 확인해야 한다.**
- **(PR #514 리뷰로 발견) `fillIfPending`을 이미 열린 트랜잭션 안에서 호출하는 새 호출부가 추가될 때마다
  같은 방식으로 재검토가 필요하다.** `LimitOrderFillAccountLockContentionIntegrationTest.
  innerReadCommittedDeclarationIsIgnoredWhenJoiningAnAlreadyOpenDefaultIsolationTransaction`이 이
  Spring 전파 규칙 자체(외부 트랜잭션이 격리수준을 결정하고, 안쪽 선언은 무시된다)를 결정론적으로
  고정해뒀지만, 이건 "이 규칙이 여전히 유효하다"만 검증할 뿐 "새 호출부가 이 규칙을 어겼는지"는 잡아주지
  않는다 — 이 한계가 실제로 반복되면(호출부가 새로 생길 때마다 놓치는 사고가 재발하면) 대안 (a) upsert
  전환으로 근본적으로 없애는 것을 재검토한다.
