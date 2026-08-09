# Plan: 전체 랭킹 조회 (RANK-001)

## 관련 문서

- Spec: `./spec.md`
- PRD: `docs/prd.md` RANK-001(2026-08-03 확정, 이슈 #139)
- 이슈: #187(`gh issue view 187`), 선행 이슈 #139(완료, 정책 확정)
- 관련 ADR
  - **ADR-0002 (레이어드 아키텍처)** — `controller → service → repository/store`. 도메인 간 참조는 service를 통해서만 한다. 다른 도메인의 repository를 직접 주입하지 않는다. 이 기능은 `ranking` 도메인이 `account` 도메인의 이벤트 타입 하나를 참조하는 것과 별개로, `RankingService`가 `account` 데이터를 조회할 때는 `AccountRepository`가 아니라 `AccountService`(신규 조회 메서드 `findByIdOrEmpty`/`findAllByIdInFetchUser`)를 주입해 사용한다(리뷰 반영, PR #187).
  - **ADR-0003 (테스트 전략)** — 서비스 로직 단위, `@DataJpaTest`/`@WebMvcTest` 슬라이스, 핵심 시나리오 Testcontainers 통합.
  - **ADR-0004 (Flyway)** — **이번 작업은 스키마 변경이 없다.** `accounts.realized_pnl`을 그대로 읽기만 하며, 신규 컬럼·테이블·마이그레이션이 필요 없다.
- 코드 컨벤션: `docs/conventions.md`(Redis key는 전용 component 한 곳에서만 조립, DTO record 규칙, 레이어 규칙)
- **신규 패턴 명시**: 이 코드베이스에 `@TransactionalEventListener`/`ApplicationEventPublisher` 사용 선례가 **없다**. 이 spec이 최초 도입이다. 참고할 기존 대응표(Trade→Order류)가 없으므로 아래 구성요소 설계는 신규 설계다.
- **조사 확인 사항 (오케스트레이터 제공 정보와 실제 코드 차이)**: 오케스트레이터 지시문은 `docs/context-notes.md` 2026-07-24 기록을 근거로 "Testcontainers Redis가 `GenericContainer`+`@ServiceConnection(name="redis")`로 이미 구성돼 있다"고 안내했으나, **실제 코드(`src/test/java/com/finplay/api/TestcontainersConfiguration.java`)는 이슈 #119로 그 방식을 버리고 `DataRedisConnectionDetails` 빈을 직접 정의하는 방식으로 바뀌어 있다.** `spring-boot-testcontainers` 의존성도 의도적으로 빠져 있어 `@ServiceConnection`을 다시 쓰면 컴파일이 실패한다. 구현 착수 시 `@ServiceConnection`을 시도하지 말고 기존 `TestcontainersConfiguration`의 정적 `REDIS` 싱글턴 + `redisConnectionDetails()` 빈을 그대로 재사용한다(추가 설정 불필요, 이미 범용으로 구성돼 있음).

## Decision Gate 확정 (이번 착수 시점)

| 항목 | 확정 내용 |
|---|---|
| 닉네임 노출 | 전체 노출(마스킹·익명화 없음) |
| 응답 DTO 필드 | `rank`·`nickname`·`realizedPnl`(항목별) + `market`(응답 wrapper 1회) — 근거는 아래 "응답 DTO 필드 확정" |
| 다중 인스턴스 동시성 경합 | after-commit 이벤트는 accountId만 싣고, 처리 시점에 DB에서 `realized_pnl`을 다시 조회해 절댓값 ZADD(최종 수렴). 분산 락 등은 후속 고도화 |
| Redis 갱신 실패 시 | 재시도(backoff) 후 실패하면 로그만 남기고 버림(Outbox 없음). 매도 체결 자체는 영향 없음 |
| 오류 코드 | 기존 `VALIDATION_ERROR` 재사용, 추가 코드 없음 |
| Redis 키·자료구조 | 시장별 ZSET `ranking:{market}`(`ranking:STOCK`/`ranking:CRYPTO`), member=accountId 문자열, score=realizedPnl(double로 저장, 정수 범위 내 손실 없음) |
| 공동 순위 계산 | `ZCOUNT ranking:{market} (score +inf` (스코어보다 엄격히 큰 멤버 수) `+ 1` — 애플리케이션 계층에서 계산. 세부는 아래 "순위 보정 흐름" |
| 동점자 내부 정렬 | userId 오름차순(애플리케이션 계층에서 재정렬 — 근거는 아래) |

### 응답 DTO 필드 확정 (userId 노출 여부 포함 — 내가 결정)

- **포함**: `rank`(int, 1부터 시작, 공동 순위 보정 적용) · `nickname`(String) · `realizedPnl`(long) — 오케스트레이터 지정 최소 필드.
- **market 위치**: 항목(`RankingListItemResponse`)마다 반복하지 않고 wrapper(`RankingListResponse.market`)에 1회만 둔다. 응답 전체가 단일 시장 조회 결과이므로 항목마다 같은 값을 반복하는 것은 불필요한 중복이다(`OrderListResponse`/`TradeListResponse`도 필터 파라미터를 항목마다 반복하지 않는 것과 같은 원칙).
- **userId는 노출하지 않는다.** 근거:
  1. 기존 목록 응답 선례(`CommunityPostResponse.authorNickname`, `PostCommentResponse.authorNickname`)가 **작성자의 내부 PK(`userId`)는 응답에 넣지 않고 닉네임만 노출**하는 패턴을 이미 확립했다 — 랭킹도 동일 원칙을 따른다.
  2. RANK-002(내 랭킹 조회, 후속 이슈)는 인증 컨텍스트(`AuthenticationPrincipal`)로 본인 여부를 판정하므로, RANK-001 목록에 `userId`가 있어야 "내 항목 찾기"가 되는 구조가 아니다 — 클라이언트가 이 목록에서 `userId`로 무언가를 조합해야 하는 후속 유스케이스가 없다.
  3. 닉네임은 전체 노출로 확정했지만, 이는 "표시용 식별자를 감추지 않는다"는 결정이지 "내부 PK까지 노출한다"는 결정은 아니다 — 최소 노출 원칙은 별도로 유지한다.
- `nickname`은 `User.nickname`(`unique=true`)을 그대로 쓴다.

## 신규 이벤트·인프라 설계 (신규 패턴 — 대응표 없음)

### 1) `RealizedPnlUpdatedEvent` (신규, `com.finplay.api.account.event`)

```java
public record RealizedPnlUpdatedEvent(Long accountId) {
}
```

- **손익값을 싣지 않는다** — 동시성 경합 Decision Gate에 따라, 리스너가 이벤트 처리 시점에 DB에서 최신값을 다시 조회한다(이벤트 도착 순서가 뒤바뀌어도 최종 수렴).
- 패키지를 `account` 도메인에 둔 이유: 이 이벤트는 "계좌의 실현손익이 갱신됐다"는 계좌 도메인의 사실을 표현한다. 발행자(`order` 도메인의 `OrderExecutionService`)는 이미 `account` 도메인에 의존하므로 새 의존이 아니다. `ranking` 도메인이 이 이벤트 타입을 구독(import)하는 것은 ADR-0002가 금지하는 "다른 도메인의 repository 직접 주입"이 아니라 이벤트 타입 하나를 참조하는 것뿐이라 결합이 약하다.

### 2) 발행 지점 — `OrderExecutionService.createSellOrder()` (기존 파일, 이번 구현 시 수정)

`account.addRealizedPnl(realizedPnl)` 호출(현재 146행) 바로 다음 줄에 추가:

```java
account.addRealizedPnl(realizedPnl);
eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()));
```

- `OrderExecutionService`에 `ApplicationEventPublisher eventPublisher`를 신규 의존성으로 주입한다(Spring이 기본 제공하는 빈, 별도 설정 불필요).
- `execute()` 메서드는 `@Transactional`이므로, 여기서 `publishEvent`를 호출해도 리스너가 즉시 실행되지 않는다(아래 리스너가 `AFTER_COMMIT`이라 트랜잭션 커밋 이후에만 실행됨) — 이게 "커밋 이후에만 반영" 요구사항을 만족시키는 핵심 메커니즘이다.
- 매수(`createBuyOrder`)는 `realizedPnl`을 갱신하지 않으므로 이벤트를 발행하지 않는다.

### 3) `RankingEventListener` (신규, `com.finplay.api.ranking.listener`)

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class RankingEventListener {

    private final RankingService rankingService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRealizedPnlUpdated(RealizedPnlUpdatedEvent event) {
        try {
            rankingService.refreshScore(event.accountId());
        } catch (Exception e) {
            log.error("랭킹 갱신 처리 중 예외 발생. accountId={}", event.accountId(), e);
        }
    }
}
```

- `phase = AFTER_COMMIT`을 명시한다(기본값이 `AFTER_COMMIT`이지만 의도를 코드로 드러내기 위해 명시 — 리뷰 시 "왜 이 phase인지" 질문이 나오지 않게 한다).
- 이 리스너는 **기본적으로 이벤트를 발행한 스레드에서 동기 실행**된다(`@Async` 미적용) — 별도 스레드풀·`@EnableAsync` 설정을 추가하지 않는다. 재시도 backoff를 포함해도 매도 응답 지연이 수백 ms 이내로 bounded되도록 아래 4)의 백오프 값을 짧게 잡는다. 지연이 실제로 문제가 되면 `@Async` 도입은 후속 고도화(과설계 금지 원칙).
- **리스너 메서드 본문 전체를 try/catch로 감싼다 — `RankingStore.addScoreWithRetry` 내부의 try/catch만으로는 부족하다.** Spring 트랜잭션 매니저는 `AFTER_COMMIT` 동기화 콜백에서 던진 예외를 삼키지 않고 `commit()` 호출부까지, 결국 `@Transactional` 프록시를 거쳐 원래 호출 스레드(매도 요청을 처리 중인 HTTP 스레드)까지 전파시킨다(DB 롤백은 안 되지만 응답은 500이 나갈 수 있음). `RankingStore.addScoreWithRetry`는 Redis 호출만 감싸므로, 그 앞단인 `RankingService.refreshScore`의 `accountService.findByIdOrEmpty(accountId)`가 던지는 예외(예: DB 커넥션 풀 고갈로 인한 `SQLTransientConnectionException`, 순간적 DB 장애로 인한 `DataAccessResourceFailureException` — 둘 다 재현 전례 있음, `docs/agent-mistakes.md` 2026-07-30)는 그 try/catch로 못 막는다. 그래서 리스너 메서드 최상위에서 한 번 더 감싸 **어떤 예외가 나든** 로그만 남기고 절대 밖으로 전파하지 않게 한다(완료 조건 "매도 체결 자체는 정상 성공" 보장의 실질적 구현 지점 — `RankingStore`의 내부 try/catch가 아니라 이 리스너 레벨의 try/catch다).

### 4) `RankingStore` (신규, `com.finplay.api.ranking.store`) — Redis 전용 창구

`PriceStore`와 동일한 역할 원칙(Redis key 문자열은 이 클래스 한 곳에서만 조립)을 따른다.

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class RankingStore {

    private static final String KEY_PREFIX = "ranking:";
    private static final int MAX_ATTEMPTS = 3;
    private static final long[] BACKOFF_MILLIS = {50, 150, 450};

    private final StringRedisTemplate redisTemplate;
    // (초기 스케치에서는 여기서 AccountRepository를 썼으나, 최종 설계는 RankingService가 AccountService를 통해 조회하고
    // RankingStore는 순수 Redis 연산만 담당하는 쪽으로 확정했다 — 아래 두 문단 참고, 리뷰 반영 PR #187)

    public void addScoreWithRetry(Market market, Long accountId, long score) {
        String key = key(market);
        String member = String.valueOf(accountId);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                redisTemplate.opsForZSet().add(key, member, (double) score);
                return;
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("랭킹 ZSET 갱신 실패(재시도 소진). accountId={}, market={}", accountId, market, e);
                    return; // 예외를 던지지 않는다 — 매도 체결에 영향 없어야 함
                }
                sleepBackoff(BACKOFF_MILLIS[attempt - 1]);
            }
        }
    }

    public List<RankingEntryDto> topN(Market market, int limit) {
        Set<ZSetOperations.TypedTuple<String>> window =
            redisTemplate.opsForZSet().reverseRangeWithScores(key(market), 0, limit - 1);
        // TypedTuple → RankingEntryDto(accountId, score) 매핑
    }

    public long countStrictlyGreater(Market market, double score) {
        Long count = redisTemplate.opsForZSet().count(key(market), Range.rightUnbounded(Range.Bound.exclusive(score)));
        return count == null ? 0 : count;
    }

    private String key(Market market) {
        return KEY_PREFIX + market.name();
    }
}
```

- `RankingStore`는 DB를 몰라야 하는가? 재검토 결과 **`RankingStore`에서 `AccountRepository`를 직접 쓰지 않는다** — 위 코드 스케치의 주석대로, 최종 설계는 `RankingService.refreshScore(accountId)`가 `AccountService.findByIdOrEmpty(accountId)`로 `Account`를 조회하고, 그 결과(`market`, `realizedPnl`)만 `RankingStore.addScoreWithRetry(market, accountId, realizedPnl)`에 넘긴다. `RankingStore`는 순수 Redis 연산 컴포넌트로 유지한다(컨벤션의 "인프라 연동 위치" 표 — Redis는 전용 component, MySQL/JPA는 repository, 이 둘을 한 클래스에 섞지 않는다). **또한 `RankingService`는 `AccountRepository`를 직접 주입하지 않고 `AccountService`를 통해 조회한다** — ADR-0002가 금지하는 "다른 도메인 repository 직접 주입"에 해당하기 때문이다(리뷰 반영, PR #187). `AccountService`에는 이를 위해 `findByIdOrEmpty(Long accountId)`와 `findAllByIdInFetchUser(List<Long> accountIds)` 위임 메서드를 추가한다.
- `Range.rightUnbounded(Range.Bound.exclusive(score))`는 Spring Data Redis `ZSetOperations.count`가 받는 `Range<Double>` 표현이다(정확한 API는 구현 시 Spring Data Redis 버전 문서로 재확인 — 여기서는 "score보다 엄격히 큰 멤버 수"라는 의미만 확정).

### 5) `RankingService` (신규, `com.finplay.api.ranking.service`)

```java
@Service
@RequiredArgsConstructor
public class RankingService {

    private static final int DEFAULT_LIMIT = 10;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 50;

    private final RankingStore rankingStore;
    private final AccountService accountService; // AccountRepository 직접 주입 금지(ADR-0002) — AccountService 위임 메서드로 조회

    @Transactional
    public void refreshScore(Long accountId) {
        accountService.findByIdOrEmpty(accountId).ifPresentOrElse(
            account -> rankingStore.addScoreWithRetry(account.getMarket(), accountId, account.getRealizedPnl()),
            () -> log.warn("랭킹 갱신 대상 계좌를 찾을 수 없음. accountId={}", accountId));
    }

    public RankingListResponse getRankings(Market market, Integer limitParam) {
        int limit = clampLimit(limitParam);
        List<RankingEntryDto> window = rankingStore.topN(market, limit);
        if (window.isEmpty()) {
            return RankingListResponse.of(market, List.of());
        }
        // accountId → (userId, nickname) 배치 조회
        List<Account> accounts = accountService.findAllByIdInFetchUser(
            window.stream().map(RankingEntryDto::accountId).toList());
        Map<Long, Account> accountById = accounts.stream()
            .collect(Collectors.toMap(Account::getId, a -> a));

        // 1) score desc, 동점자는 userId asc로 재정렬 (Redis 내부 tie-break는 신뢰하지 않음 — 아래 근거)
        // 2) 동일 score 그룹마다 countStrictlyGreater 호출을 캐시(동일 score 재조회 방지)
        // 3) rank = countStrictlyGreater(score) + 1
        ...
    }

    private int clampLimit(Integer limitParam) {
        if (limitParam == null || limitParam < MIN_LIMIT) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limitParam, MAX_LIMIT);
    }
}
```

- **`limitParam < MIN_LIMIT`(즉 0 이하)일 때 `DEFAULT_LIMIT`(10)으로 클램핑**하고, `MAX_LIMIT`(50) 초과는 50으로 클램핑한다 — PRD·이슈 #187 원문 그대로("1 미만이어도 10으로 클램핑", "50 초과는 50으로 클램핑"). `null`(파라미터 생략)도 기본 10과 동일하게 처리.
- **왜 클램핑이 컨트롤러가 아니라 서비스에 있는가**: `GET /api/trades`류(범위 밖 400 거부)는 컨트롤러의 `validateLimit`이 "요청을 거부하는 검증"이라 controller 책임(요청 검증)에 맞는다. 반면 이번 클램핑은 오류가 아니라 "요청값을 정책값으로 치환하는 비즈니스 규칙"(사용성 우선 정책, PRD 근거)이라 서비스 책임으로 둔다.
- **왜 Redis 자체 tie-break를 신뢰하지 않는가**: Redis ZSET의 동점 멤버 정렬은 멤버 문자열의 **바이트(사전) 순서**다. member가 숫자 문자열(accountId)이라 자릿수가 다르면 사전순과 수치순이 어긋난다(예: `"10"` < `"9"` 사전순). 그래서 `topN`으로 가져온 window를 애플리케이션 계층에서 `(score desc, userId asc)`로 재정렬해야 한다 — 이게 "동점자 내부 정렬은 userId 오름차순" 확정의 실제 구현 근거다.

### 6) 순위 보정 흐름 (공동 순위 계산, RANK-002도 재사용 가능한 형태로 분리)

1. `RankingStore.topN(market, limit)`으로 최대 `limit`개 (accountId, score) window를 score desc로 가져온다. **(PR #196 리뷰 반영으로 변경 — 아래 8-2절 참고: 실제로는 `limit`이 아니라 경계 동점 확인을 위해 `limit + 1`개를 가져온다.)**
2. window 안의 **고유 score 값마다 1회만** `RankingStore.countStrictlyGreater(market, score)`를 호출해 해시맵에 캐시한다(같은 score를 가진 동점자가 여러 명이어도 Redis 호출은 1번).
3. `rank(score) = countStrictlyGreater(score) + 1`.
4. window를 `(score desc, userId asc)`로 재정렬해 최종 노출 순서를 만든다.
5. 각 항목을 `RankingListItemResponse(rank, nickname, realizedPnl)`로 매핑.

이 흐름을 `RankingService`의 `private` 메서드로 분리해 두면(예: `calculateRanks(List<RankingEntryDto>, Market)`), RANK-002가 "내 계좌의 accountId 하나에 대해 score만 알면 같은 공식으로 순위를 계산"하는 데 그대로 재사용할 수 있다 — RANK-002는 이 spec의 범위가 아니므로 지금은 재사용 가능한 형태로만 분리하고 실제 호출부는 만들지 않는다.

### 7) 랭킹 대상 제외가 "쓰기 시점"에 자연스럽게 해결됨

매도 이력이 한 번도 없는 계좌는 `RealizedPnlUpdatedEvent`가 발행된 적이 없으므로 `ranking:{market}` ZSET에 **member 자체가 존재하지 않는다.** 별도의 "매도 이력 있음" 플래그·조회를 추가하지 않고도 완료 조건("매도 이력 없는 회원 제외")이 쓰기 경로 설계로 자동 충족된다 — 이 점을 구현 시 주석으로 남긴다.

### 8) PR #196 리뷰 반영 (구현 이후 설계 변경 — 차단 2건 + 권장 1건)

최초 구현(PR #196) 리뷰에서 지적된 아래 3건을 반영해 위 5)·6) 설계를 다음과 같이 수정했다.

**8-1. Redis window에 DB 계좌가 없는 accountId 필터링 (차단)**

`accountService.findAllByIdInFetchUser(...)`로 배치 조회한 `accountById` 맵에 window의 accountId가 없으면(Redis가 MySQL 트랜잭션 밖의 파생 데이터라 DB 리셋·복원 등으로 언제든 어긋날 수 있음 — 정상 상황으로 취급) `calculateRanks` 진입 시 그 항목을 걸러내고 `log.warn`만 남긴다. 필터링 후 남은 항목이 없으면 예외 없이 빈 `content`를 반환한다. 걸러낸 accountId가 여전히 Redis ZSET 자체에는 남아 있을 수 있어 `countStrictlyGreater`(ZCOUNT)가 그 유령 멤버의 score를 계속 카운트할 수 있다는 미세한 부정확성이 남는데, 이번 수정의 핵심(NPE로 인한 500 방지)만 해결하고 이 부분은 과설계하지 않기로 했다(코드 주석으로 남김).

**8-2. limit 경계 동점자 처리 (차단)**

`RankingStore.topN(market, limit)`만으로는 "어떤 동점자가 window에 들어갈지"가 Redis 멤버 문자열의 사전순으로 결정돼 정책(동점자는 userId 오름차순)과 다르게 잘릴 수 있었다. `RankingService.fetchWindowResolvingBoundaryTies`로 다음과 같이 바꿨다.

1. `RankingStore.topN(market, limit + 1)`로 1개 더 가져온다.
2. `limit`번째(0-indexed `limit-1`)와 `limit+1`번째(0-indexed `limit`) 항목의 score가 다르면 경계에 동점이 없다는 뜻이므로 추가 Redis 호출 없이 그대로 `limit`개로 절단한다(가장 흔한 경우의 성능 최적화).
3. 두 score가 같으면 경계에 동점 그룹이 걸쳐 있다는 뜻이므로, `RankingStore.findAllAtScore(market, score)`(신규 메서드, `ZRANGEBYSCORE key score score`로 정확한 score 구간 조회)로 그 score의 전체 멤버를 가져와 window의 해당 score 항목을 통째로 교체(accountId 기준 중복 제거 병합)한다.
4. 병합된 리스트는 `calculateRanks`에서 `(score desc, userId asc)`로 정렬한 뒤 정확히 `limit`개로 절단한다(`Stream.limit(limit)`).

동점자가 수백 명 규모로 많은 경우도 이 흐름으로 처리된다(3번에서 병합 대상이 커질 뿐, 로직은 동일). 단위 테스트(`RankingServiceTest`)에 `limit=1`·동점 2명 경계 시나리오, 경계에 동점이 없을 때 `findAllAtScore` 미호출(최적화) 검증을 추가했다.

**8-3. `refreshScore` 트랜잭션 전파 (권장이지만 핵심 설계 오류)**

`@TransactionalEventListener(AFTER_COMMIT)` 콜백은 원래 매도 트랜잭션의 EntityManager가 아직 스레드에 바인딩된 시점에 실행된다. `refreshScore`가 `@Transactional`(기본 REQUIRED)이면 그 기존 영속성 컨텍스트에 참여해 1차 캐시의 계좌 객체를 그대로 반환하므로, "이벤트 처리 시점에 DB에서 최신값을 다시 조회한다"(5절·spec.md 동시성 경합 Decision Gate의 전제)가 실제로는 지켜지지 않았다. `@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)`로 바꿔 항상 새 트랜잭션·새 영속성 컨텍스트를 열도록 수정했다.

`RankingIntegrationTest`에 이 문제를 실제로 잡아내는 테스트(`refreshScoreReadsLatestDbValueEvenWhenCallerHasStalePersistenceContext`)를 추가했다 — `TransactionTemplate`으로 바깥 트랜잭션에서 계좌를 먼저 로드해 1차 캐시에 옛 값을 남긴 뒤, 완전히 별도(REQUIRES_NEW)의 트랜잭션에서 DB 값을 갱신·커밋하고, 그 바깥 트랜잭션이 아직 활성인 채로 `refreshScore`를 호출해 AFTER_COMMIT 콜백과 동일한 조건을 재현한다. `REQUIRED`로 되돌려 실행하면 이 테스트가 실패하는 것을 확인해 회귀 테스트로서의 유효성을 검증했다. 기존 `eventOrderReversalStillConvergesToLatestDbRealizedPnl`(트랜잭션 없는 테스트 스레드에서 리스너를 직접 호출)은 "최종 수렴" 자체는 여전히 유효하게 검증하므로 유지하되, 이 전파 버그는 잡아내지 못한다는 한계를 새 테스트로 보완했다.

**8-4. 동점 없는 경계 경로에서 유령 계좌 필터링 후 결과가 limit보다 적어지는 문제 (독립 reviewer 검증 중 발견, 권장)**

8-2의 "경계에 동점이 없으면 그대로 `limit`개로 절단한다"(당시 `window.subList(0, limit)`)가, 8-1의 유령 필터링과 조합되면 문제가 됐다. 예: `limit=2`, `topN(3)` = `[alice(100,정상), 유령(90), carol(80,정상)]`에서 미리 `limit`개로 잘라버리면 `[alice, 유령]`만 남고, 유령을 걸러내면 `alice` 1건만 응답에 남는다 — 원래는 3번째 `carol`이 채워질 수 있었는데 이미 잘려나가 후보에도 못 든다.

**수정**: `fetchWindowResolvingBoundaryTies`의 "동점 없음" 분기가 더는 `limit`개로 미리 자르지 않고 `limit+1`개(fetch한 전체)를 그대로 반환한다. 최종 `limit`개 절단은 이미 `calculateRanks`가 유령 필터링 **이후**에 수행하므로(`.limit(limit)`), 자연스럽게 "+1"로 확보해둔 여유분이 유령 자리를 보충한다. 2개 이상의 유령이 그 "+1" 범위 안에 동시에 있는 경우까지는 완전히 해결하지 못하지만(이미 Redis/DB가 어긋난 비정상 상황에서만 나타나는 조건이라 과설계하지 않기로 함), 리뷰가 지적한 단일 유령 시나리오는 완전히 해결된다. `RankingServiceTest`에 이 정확한 시나리오를 재현하는 회귀 테스트(`getRankingsBackfillsFromSpareEntryWhenBoundaryWindowContainsGhostAccount`)를 추가했다.

**8-5. 유령 계좌가 경계 동점 그룹보다 위쪽 score에 있으면 동점자 선택이 정책(userId 오름차순)을 보장하지 못함 (이슈 #270 QA 재현, 문서화로 갈음)**

`fetchWindowResolvingBoundaryTies`의 동점 감지는 `topN(limit+1)` 원본 window의 `limit-1`·`limit` 인덱스만 비교한다. 유령 계좌가 그 경계 동점 그룹보다 높은 score로 window 앞쪽 슬롯을 차지하고 있으면, `calculateRanks`에서 유령이 걸러진 뒤 실제로는 경계 동점 그룹에서 한 명을 더 채워야 하는 상황인데도, 이 시점의 raw window 비교는 그 사실을 모른 채 `limit-1`·`limit` 위치의 score가 다르면 "동점 없음"으로 판정해 `findAllAtScore`를 호출하지 않는다. 응답 개수는 8-4의 backfill로 맞춰지지만, 그 자리를 채우는 사람은 정책(userId 오름차순)이 아니라 원본 `topN` 조회에서 Redis 멤버 문자열의 사전순으로 우연히 뽑힌 사람이 된다.

Redis·DB가 어긋난 비정상 상황(유령 계좌 존재)에서, 그것도 유령이 경계 동점 그룹보다 정확히 위쪽에 있을 때만 나타나는 이중 조건이라 발생 빈도가 낮다. 고치려면 tie 감지 이전에 DB 존재 여부를 먼저 확인해야 해 `fetchWindowResolvingBoundaryTies`(Redis 전용 윈도우 조회)·`calculateRanks`(그 이후 필터링) 사이의 책임 분리가 깨진다 — 코드로 막을 값어치가 없다고 판단해(리뷰 결정) 이 문단으로 알려진 한계를 문서화하는 것으로 갈음한다. 8-1·8-4와 같은 과설계 회피 기준이다.

## API 설계

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | `/api/rankings?market=&limit=` | `market`(필수), `limit`(선택, 기본 10) | `RankingListResponse` | 시장별 실현손익 상위 랭킹 조회. 인증 필요(다른 GET과 동일하게 `SecurityConfig`의 `anyRequest().authenticated()`로 이미 보호됨 — `PUBLIC_GET_PATHS`에 추가하지 않는다), 다만 응답은 인증 사용자로 스코프되지 않는다(전체 랭킹) |

## 입력 명세

| 필드 | 필수 | 검증 |
|---|---|---|
| `market` | 필수 | `com.finplay.api.account.domain.Market`(`STOCK`\|`CRYPTO`)로 Jackson/Spring 바인딩. 누락·미지원 리터럴은 기존 `TradeController`/`OrderController`와 동일한 전역 처리 경로로 400 `VALIDATION_ERROR`(신규 처리 코드 불필요) |
| `limit` | 선택 | `Integer`(wrapper — 생략 구분을 위해 `int` 미사용, `@RequestParam(required = false) Integer limit`). 서비스 계층에서 클램핑(0 이하·생략→10, 50 초과→50). **컨트롤러에서 400으로 거부하지 않는다** — `GET /api/trades`·`GET /api/orders`와 의도적으로 다른 처리 |

## 데이터 모델

**신규 테이블·컬럼·마이그레이션 없음.** `accounts.realized_pnl`(기존 컬럼)을 그대로 읽는다. Redis `ranking:{market}` ZSET은 파생 데이터이며 스키마 개념이 아니다(Flyway 대상 아님, ADR-0004).

```
ranking:STOCK   (ZSET) member=accountId(문자열), score=accounts.realized_pnl(해당 계좌)
ranking:CRYPTO  (ZSET) member=accountId(문자열), score=accounts.realized_pnl(해당 계좌)
```

## 구성요소 설계 요약 (패키지: `com.finplay.api.ranking`)

```
com.finplay.api.ranking
├── controller/RankingController.java      # GET /api/rankings
├── service/RankingService.java            # limit 클램핑, refreshScore, 공동순위 계산
├── store/RankingStore.java                # Redis ZSET 전용 창구 (key 조립 한 곳)
├── listener/RankingEventListener.java     # @TransactionalEventListener(AFTER_COMMIT)
└── dto/
    ├── RankingEntryDto.java               # service↔store 내부 전달용 (accountId, score) — Dto 접미사, controller 노출 안 함
    └── response/
        ├── RankingListResponse.java       # record(String market, List<RankingListItemResponse> content)
        └── RankingListItemResponse.java   # record(int rank, String nickname, long realizedPnl)

com.finplay.api.account.event
└── RealizedPnlUpdatedEvent.java           # record(Long accountId) — order 도메인이 발행, ranking 도메인이 구독

com.finplay.api.account.repository.AccountRepository (기존 파일 — 메서드 추가)
└── findAllByIdInFetchUser(List<Long> ids)  # JOIN FETCH a.user, 랭킹 목록의 닉네임 배치 조회용 (N+1 방지)

com.finplay.api.account.service.AccountService (기존 파일 — 메서드 추가, 리뷰 반영 PR #187)
├── findByIdOrEmpty(Long accountId)          # RankingService.refreshScore 위임 대상 (AccountRepository 직접 주입 대체)
└── findAllByIdInFetchUser(List<Long> accountIds)  # RankingService.getRankings 위임 대상

com.finplay.api.order.service.OrderExecutionService (기존 파일 — 수정)
└── createSellOrder()에서 account.addRealizedPnl(...) 직후 eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()))
```

## 테스트 계획

- **단위 (`RankingStoreTest`, Redis mock)**: `addScoreWithRetry`가 `StringRedisTemplate`(mock)에서 예외를 던지도록 stub했을 때 3회 재시도 후 예외를 삼키고 반환하는지(호출 스레드로 예외가 전파되지 않음), 성공 시 1회만 ZADD 호출하는지.
- **단위 (`RankingServiceTest`, `RankingStore`/`AccountService` mock)**: `refreshScore`가 존재하지 않는 accountId에 대해 예외 없이 로그만 남기는지, `getRankings`가 limit 클램핑(0·음수→10, 51 이상→50, null→10)을 올바르게 적용하는지, 동점 그룹이 있을 때 `countStrictlyGreater` 캐시로 동일 score에 대해 Redis 호출이 1회만 발생하는지, 공동 순위 계산이 "1,1,3" 패턴을 만드는지(단위 테스트에서 mock 반환값으로 검증), 동점자 내부 정렬이 userId 오름차순인지.
- **단위 (`RankingEventListenerTest`, `RankingService` mock, 신규)**: `rankingService.refreshScore(...)`가 `DataAccessException`(DB 재조회 실패 시뮬레이션 — `RankingStore` 내부가 아니라 그 앞단인 `AccountService.findByIdOrEmpty` 실패를 재현)을 던지도록 stub했을 때, `onRealizedPnlUpdated` 호출이 예외를 밖으로 전파하지 않고 정상 반환하는지 확인한다. 이 테스트가 "리스너 레벨 try/catch"의 존재 근거를 검증하는 유일한 테스트이므로 빠지면 안 된다.
- **슬라이스 (`@WebMvcTest RankingControllerTest`)**: `market` 누락·미지원 리터럴 400, `limit` 0·음수·51 이상도 200(클램핑되어 서비스 호출, 400 아님— `GET /api/trades`와의 차이를 명시적으로 검증), 정상 요청 200과 `rank`/`nickname`/`realizedPnl`/`market` 필드 계약, 인증 없이 요청 시 401.
- **슬라이스 (`@DataJpaTest`, `AccountRepositoryTest` 기존 파일 갱신)**: `findAllByIdInFetchUser`가 요청한 id 목록의 `Account`+`User`를 N+1 없이 정확히 반환하는지.
- **통합 (`RankingIntegrationTest` 신규, Testcontainers MySQL+Redis — `TestcontainersConfiguration`의 기존 정적 싱글턴 재사용, `@ServiceConnection` 사용 금지)**:
  - 매도 체결 API 호출 → 커밋 → (동기 리스너이므로 응답 반환 시점에 이미 반영됨을 전제로) `GET /api/rankings` 조회 시 해당 계좌가 올바른 score로 나타나는지.
  - 같은 계좌에 대해 `RankingEventListener.onRealizedPnlUpdated`를 실제 DB 커밋 순서와 다른 순서로 두 번 직접 호출(이벤트 순서 역전 시뮬레이션)했을 때, 최종 ZSET score가 DB의 최신 `realized_pnl`과 일치하는지.
  - 매도 요청이 검증 실패로 커밋되지 않는 경우(예: 보유수량 부족) `ranking:{market}` ZSET에 해당 계좌가 추가되지 않는지(커밋 전 반영 없음 확인).
  - Redis 컨테이너를 일시 정지시키거나 `RankingStore`를 실패하도록 구성한 상태에서 매도 체결 API를 호출해도 주문·체결·계좌 갱신이 정상 200으로 성공하는지(랭킹 갱신 실패가 체결에 영향 없음).
  - 매도 이력이 없는 계좌는 `GET /api/rankings` 목록·순위 산정에 나타나지 않는지, 동점자 공동 순위(공동 1위 2명 다음 3위)가 실제 Redis 데이터로 검증되는지.

## RANK-002 설계 (`GET /api/rankings/me`, 이슈 #233)

### 관련 문서

- Spec: `./spec.md` "RANK-002 내 랭킹 조회"
- PRD: `docs/prd.md` "RANK-002 내 랭킹 조회"(2026-08-05 확정)
- ADR-0002(레이어드 아키텍처) — 이번에도 `RankingService`가 `AccountRepository`를 직접 주입하지 않고 `AccountService`를 통해 계좌를 조회한다. 최초 설계는 기존 `getAccountFor(userId, market)`를 재사용했으나(신규 repository 메서드 불필요), PR #234 리뷰 권장 반영으로 fetch join 전용 신규 메서드 `getAccountForWithUser(userId, market)`(내부적으로 `AccountRepository.findByUserIdAndMarketFetchUser` 신규 쿼리 사용)로 바꿨다 — 아래 "계좌 하나의 score를 얻는 방법" 다음 절 참고.
- **코드 확인 결과 (구현 착수 전 필수 확인)**: 실제 `RankingStore`/`RankingService`(`src/main/java/com/finplay/api/ranking/`)를 읽어 아래를 확인했다 — plan.md 위쪽 6절의 최초 스케치(`Range.rightUnbounded(...)`)는 실제로는 쓰이지 않았고, `countStrictlyGreater(Market, long score)`(score는 `long`, `count(key, lowerBound, +INF)` 오버로드 사용)로 구현돼 있다(PR #196 리뷰 반영, 위 8절 참고). RANK-002는 이 **실제 시그니처**(`long` 파라미터)를 그대로 재사용한다. 6절이 예고한 "`calculateRanks`를 private으로 분리해 재사용" 방식은 그대로는 재사용하지 않는다 — `calculateRanks`는 "여러 accountId의 window를 한 번에 정렬·매핑"하는 목록 전용 로직이라 단건 조회에 맞지 않는다. 대신 `calculateRanks`가 이미 쓰고 있는 원자 연산 `RankingStore.countStrictlyGreater(market, score)`를 그대로 재사용하고, "이 계좌의 score를 어떻게 얻는가"만 새로 설계한다(아래).

### 계좌 하나의 score를 얻는 방법 — 핵심 결정

RANK-001의 window 조회(`topN`)는 여러 accountId를 한 번에 가져오지만, RANK-002는 **로그인한 사용자 한 명의 계좌 하나**의 score만 있으면 된다. 두 가지 후보를 검토했다:

1. **(채택) Redis ZSET에서 `ZSCORE`로 직접 조회한다.** `RankingStore`에 신규 메서드 `Long score(Market market, Long accountId)`를 추가한다 — `member`가 ZSET에 없으면 `null`을 반환한다(스프링 데이터 레디스 `ZSetOperations.score(key, member)`가 이미 이 시맨틱을 제공). 이 `null` 여부가 곧 "매도 이력 있음/없음" 판정이다(RANK-001이 "ZSET에 member가 없으면 매도 이력 없음"으로 취급하는 것과 완전히 같은 원칙, PRD 2026-08-05 확정 문구가 명시).
2. **(기각) DB `accounts.realized_pnl`을 그대로 읽어 `countStrictlyGreater`에 넘긴다.** 이러면 "매도 이력 있음/없음" 판정을 위해 결국 별도로 ZSET 멤버십을 확인해야 하므로(realized_pnl은 이력이 없어도 항상 0으로 존재해 그 값만으로는 이력 유무를 알 수 없다) Redis 호출이 어차피 필요해진다. 게다가 RANK-001의 `topN` 목록은 ZSET 상태를 기준으로 산정되므로, 순위 계산에 쓰는 score가 DB 값(잠재적으로 after-commit 이벤트 처리 지연으로 ZSET과 순간적으로 어긋날 수 있음, 위 Decision Gate 참고)이면 같은 화면(랭킹)에 대해 RANK-001 목록과 RANK-002 단건 조회가 서로 다른 순위를 보여줄 가능성이 생긴다. **채택안은 이 불일치 가능성을 원천적으로 없앤다** — 순위 계산의 입력값 출처를 항상 ZSET 하나로 통일한다.

이 결정에 따라 **신규 Redis 키·자료구조는 필요 없다** — 기존 `ranking:{market}` ZSET에 대해 조회 커맨드(`ZSCORE`)를 하나 더 쓰는 것뿐이다(오케스트레이터가 제시한 가설을 코드 확인으로 확정함).

```java
// RankingStore (기존 파일 — 메서드 추가)
// 계좌 하나의 score를 조회한다. ZSET에 member가 없으면(매도 이력 없음) null을 반환한다.
public Long score(Market market, Long accountId) {
    Double raw = redisTemplate.opsForZSet().score(key(market), String.valueOf(accountId));
    return raw == null ? null : Math.round(raw); // topN()과 동일하게 정수 score로 취급
}
```

### `RankingService.getMyRanking(Long userId, Market market)` (신규)

```java
// 형제 메서드 getRankings와 동일한 패턴(PR #234 리뷰 권장 반영) — 이 메서드는 트랜잭션으로 감싸지 않는다.
// accountService.getAccountForWithUser가 User를 fetch join으로 미리 로딩해 자신의 트랜잭션 안에서 끝내므로,
// account.getUser() 접근과 Redis 왕복 2회(score, countStrictlyGreater)가 전부 트랜잭션 밖에서 일어나 그동안
// DB 커넥션을 점유하지 않는다. 최초 설계(TradeService.getMyTrades와 동일한 패턴, @Transactional(readOnly=true)로
// 이 메서드 전체를 감싸는 방식)는 Redis 왕복 두 번이 끝날 때까지 DB 커넥션을 쥐고 있는 문제가 있었다.
public MyRankingResponse getMyRanking(Long userId, Market market) {
    Account account = accountService.getAccountForWithUser(userId, market);
    Long score = rankingStore.score(market, account.getId());
    Integer rank = score == null
        ? null
        : (int)(rankingStore.countStrictlyGreater(market, score) + 1); // RANK-001과 동일한 보정 공식 재사용
    long realizedPnl = score == null ? 0L : score; // rank와 같은 출처(ZSET)에서 뽑는다 — PR #234 리뷰 차단 반영
    return new MyRankingResponse(
        market.name(), rank, account.getUser().getNickname(), realizedPnl);
}
```

- **`accountService.getAccountForWithUser`가 `NOT_FOUND`를 던질 수 있는가**: ACCT-001에 따라 회원가입 시 STOCK·CRYPTO 계좌가 함께 생성되므로, 인증된 사용자 + 유효한 `market` 조합에서는 정상적으로 발생하지 않는다(방어적 경로로만 존재, 신규 오류 코드 불필요).
- **`nickname`·`realizedPnl`은 매도 이력과 무관하게 항상 채운다.** `nickname`은 `account`(`getAccountForWithUser`로 항상 조회됨)에서 채운다. **`realizedPnl`은 DB `account.getRealizedPnl()`이 아니라 `rank` 계산에 쓴 것과 같은 `score`(ZSET)에서 채운다** — 최초 설계는 DB 값을 그대로 썼으나, `rank`는 ZSET 기준으로 계산하면서 `realizedPnl`만 DB를 그대로 노출하면 after-commit 반영 지연·Redis 재시도 소진 등으로 두 값이 어긋난 계좌에서 "이 손익, 이 순위"가 응답 안에서 서로 대응하지 않는 차단 사유가 된다(PR #234 리뷰 반영). `score`가 `null`(매도 이력 없음)이면 0을 쓴다 — 이 경우 DB `realized_pnl`도 항상 0이라(이력 없는 계좌는 `refreshScore`가 호출된 적이 없다) 값이 갈리지 않는다. null이 되는 것은 `rank`뿐이다(spec.md "비즈니스 규칙" 참고).
- **다른 사용자 조회 방지**: 파라미터로 accountId·userId를 받지 않고 인증 컨텍스트의 `userId`만 쓰므로, `TradeService.getOwnedTrade`류의 별도 소유권 검증(`FORBIDDEN`)이 구조적으로 불필요하다.

### `MyRankingResponse` (신규, `ranking/dto/response`)

```java
public record MyRankingResponse(String market, Integer rank, String nickname, long realizedPnl) {
}
```

- `rank`는 `Integer`(boxed) — `RankingListItemResponse.rank`(`int`, 항상 값 있음)와 달리 이 응답은 "매도 이력 없음"을 JSON `null`로 표현해야 하므로 boxing이 필수다.
- `market`을 항목에 다시 포함하는 이유: 이 응답은 `RankingListResponse`처럼 여러 항목을 감싸는 wrapper가 아니라 단건이라, market을 wrapper에만 두는 RANK-001 규칙(41행)이 적용되지 않는다 — 단건 응답이므로 필드로 직접 포함하는 것이 자연스럽다.

### `RankingController` (기존 파일 — 엔드포인트 추가)

```java
// AccountController.getAccountSummary와 동일한 인증 패턴: @AuthenticationPrincipal AuthenticatedUser + principal.userId()
@GetMapping("/me")
public ResponseEntity<MyRankingResponse> getMyRanking(
    @AuthenticationPrincipal
    AuthenticatedUser principal,
    @RequestParam
    Market market) {
    return ResponseEntity.ok(rankingService.getMyRanking(principal.userId(), market));
}
```

- `/api/rankings/me`는 기존 `@RequestMapping("/api/rankings")` 아래 하위 경로라 라우팅 충돌이 없다(루트 `GET /api/rankings`와 별개 매핑).
- 인증 보호: 다른 인증 필요 GET과 동일하게 `SecurityConfig`의 `anyRequest().authenticated()`로 이미 보호된다. `PUBLIC_GET_PATHS`에 추가하지 않는다(RANK-001과 동일 원칙, plan.md 245행).

## API 설계 (RANK-002 추가)

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | `/api/rankings/me?market=` | `market`(필수) | `MyRankingResponse` | 인증 사용자 본인의 시장별 실현손익 순위 단건 조회. 인증 필요, 대상은 항상 요청자 본인(파라미터로 다른 사용자 지정 불가) |

## 입력 명세 (RANK-002 추가)

| 필드 | 필수 | 검증 |
|---|---|---|
| `market` | 필수 | RANK-001과 동일한 처리 경로 — `Market`(`STOCK`\|`CRYPTO`) 바인딩, 누락·미지원 리터럴은 400 `VALIDATION_ERROR` |

## 구성요소 설계 요약 추가분 (RANK-002, 기존 `com.finplay.api.ranking` 패키지에 추가)

```
com.finplay.api.ranking
├── controller/RankingController.java      # (기존 파일 수정) GET /api/rankings/me 추가
├── service/RankingService.java            # (기존 파일 수정) getMyRanking(userId, market) 추가
├── store/RankingStore.java                # (기존 파일 수정) score(market, accountId) 추가 — ZSCORE 단건 조회, null=매도 이력 없음
└── dto/response/MyRankingResponse.java    # (신규) record(String market, Integer rank, String nickname, long realizedPnl)

com.finplay.api.account                    # PR #234 리뷰 권장 반영 — 애초 계획엔 없었으나 트랜잭션·Redis 왕복 분리를 위해 추가
├── repository/AccountRepository.java      # (기존 파일 수정) findByUserIdAndMarketFetchUser(userId, market) 추가
└── service/AccountService.java            # (기존 파일 수정) getAccountForWithUser(userId, market) 추가
```

신규 컬럼·마이그레이션 없음(Flyway 대상 아님, ADR-0004). 신규 파일은 `MyRankingResponse` 하나이고, `account` 도메인은 기존 `Account`/`User` 엔티티 변경 없이 조회 메서드만 추가한다(PR #234 리뷰 권장 반영, 아래 "RankingService.getMyRanking" 절 참고).

## 테스트 계획 (RANK-002 추가)

- **단위 (`RankingStoreTest` 확장)**: `score(market, accountId)`가 ZSET에 멤버가 있으면 정수 score를, 없으면 `null`을 반환하는지(Redis mock으로 `ZSetOperations.score` stub).
- **단위 (`RankingServiceTest` 확장, `RankingStore`/`AccountService` mock)**: `getMyRanking`이 (a) 매도 이력 없음(`score`가 null) → `rank`만 null이고 `nickname`·`realizedPnl`(0 포함)은 정상 반환, (b) 매도 이력 있음 → `countStrictlyGreater` 반환값 + 1이 `rank`로 매핑되는지, (c) 동점자가 있는 케이스에서 RANK-001과 동일한 보정 공식이 적용되는지(RANK-001의 경계 동점 처리 자체는 목록 전용이라 재검증하지 않는다 — `countStrictlyGreater` 호출 위임만 검증), (d) DB `realized_pnl`과 ZSET `score`가 어긋나도 응답은 항상 `score` 값을 쓰는지(PR #234 리뷰 차단 회귀 테스트).
- **단위 (`AccountServiceTest`/`AccountRepositoryTest` 확장, PR #234 리뷰 권장 반영)**: `getAccountForWithUser`/`findByUserIdAndMarketFetchUser`가 `findByUserIdAndMarket`과 동일하게 계좌를 찾되 `User`까지 fetch join으로 채워 반환하는지, 미존재 시 각각 `NOT_FOUND`/빈 `Optional`을 반환하는지.
- **슬라이스 (`@WebMvcTest RankingControllerTest` 확장)**: `market` 누락·미지원 리터럴 400, 인증 없이 요청 401, 매도 이력 없는 사용자 200(`rank: null` JSON 필드 계약), 매도 이력 있는 사용자 200(`rank`/`nickname`/`realizedPnl`/`market` 필드 계약).
- **통합 (`RankingIntegrationTest` 확장, PR #234 리뷰 권장 반영)**: 최초 계획은 "RANK-001이 이미 검증한 ZSET 쓰기·이벤트 흐름을 그대로 재사용하므로 신규 통합 테스트 불필요"였다. 하지만 그 근거는 **쓰기 경로**만 커버하고, RANK-002가 RANK-001과 별개로 존재하는 이유인 "상위 `limit`(기본 10) 밖에서도 정확한 보정 순위를 반환한다"는 **읽기 경로**의 핵심 성질은 어떤 테스트로도 검증되지 않고 있었다(`RankingServiceTest`의 단위 테스트는 `countStrictlyGreater`를 stub해 `+1` 산술만 확인할 뿐, 실제 Redis ZSET에서 limit 밖 순위가 맞게 나오는지는 확인하지 못한다). 시나리오 5·6과 픽스처를 재사용해, 기본 limit(10)보다 많은 계좌를 커밋하고 그중 순위가 10위 밖인 계좌로 `GET /api/rankings/me`를 호출해 정확한 순위가 나오는지 확인하는 시나리오 7을 추가한다.

## 랭킹 재구성 설계 (이슈 #279)

### 관련 문서

- Spec: `./spec.md` "랭킹 재구성 절차 (이슈 #279)"
- 이슈: #279(`gh issue view 279`), 선행 이슈 #187(RANK-001)·#233(RANK-002)
- PRD: `docs/prd.md` RANK-001 절("유실 시 MySQL 원장으로 재구성한다 — 세부 구현은 착수 시 확정")·§6 "Redis 키 책임"의 랭킹 항목
- 관련 ADR
  - **ADR-0002 (레이어드 아키텍처)** — `ranking` 도메인은 다른 도메인의 repository를 직접 주입하지 않는다. 매도 이력 조회는 `order` 도메인의 `TradeService`를 경유하고, 계좌 조회는 `account` 도메인의 `AccountService`를 경유한다. RANK-001이 `AccountRepository` 직접 주입을 리뷰에서 지적받아 `AccountService` 경유로 바꾼 것(위 9행)과 같은 기준이다.
  - **ADR-0003 (테스트 전략)** — 단위 / `@DataJpaTest`·`@WebMvcTest` 슬라이스 / Testcontainers 통합. 세부는 아래 "테스트 계획".
  - **ADR-0004 (Flyway)** — **해당 없음. 이번 작업은 스키마 변경이 전혀 없다.** 기존 `trades`(매도 이력)·`accounts`(`realized_pnl`)를 **읽기만** 한다. 신규 테이블·컬럼·인덱스·마이그레이션 파일이 없다. Redis ZSET은 파생 데이터라 Flyway 대상 개념 자체가 아니다(위 "데이터 모델" 절과 동일).
- 코드 컨벤션: `docs/conventions.md`(Redis key는 전용 component 한 곳에서만 조립 — 임시 키도 `RankingStore` 안에서만 조립한다, 레이어 규칙, 새 파일 첫 줄 한국어 주석)
- 선례 코드
  - 기동 훅: `src/main/java/com/finplay/api/market/feed/BithumbFeedLifecycle.java`(`@EventListener(ApplicationReadyEvent.class)`)
  - 크론 배치: `FeedbackBatchService`·`NewsCollectionService`·`CryptoPriceSnapshotService`(`@Scheduled(cron = "${...}", zone = "Asia/Seoul")` — 크론 문자열은 코드 상수가 아니라 `application.yml` 프로퍼티)

### Decision Gate 확정 (사용자 확정, 다시 논의하지 않음)

| 항목 | 확정 내용 |
|---|---|
| 기동 훅 | `@EventListener(ApplicationReadyEvent.class)` — `BithumbFeedLifecycle` 선례 |
| 주기 배치 | `@Scheduled(cron = "${ranking.rebuild.cron}", zone = "Asia/Seoul")`, 값 `0 20 4 * * *`(매일 04:20 KST) |
| 수동 엔드포인트 | 만들지 않는다(관리자 롤 개념 부재 — 범위 확대 회피) |
| 대상 판정 | `trades.side = 'SELL'`인 해당 market 계좌의 `DISTINCT account_id`. score는 `accounts.realized_pnl` |
| 배치 방식 | 항상 전체 재구성. 임시 키 `ranking:{market}:rebuild` 적재 후 `RENAME`으로 원자 교체 |
| 분산 락 | 없음(멱등·단일 인스턴스 전제). **다중 인스턴스 전환 시 재검토** — 아래 별도 절 |
| 상태 필드 | `status`(`READY`\|`REBUILDING`)를 `RankingListResponse`·`MyRankingResponse` 양쪽에 추가 |
| 스키마 변경 | 없음(ADR-0004 해당 없음) |

**크론 시각 선정 근거**: 기존 배치 크론과 겹치지 않는 시간대를 골랐다. 현재 `application.yml`의 크론은 `0 45 8 * * MON-FRI`(피드백 배치), `0 5 * * * *`(매시 05분), `0 32 15 * * MON-FRI`(장 마감 집계), `30 * * * * *`(매 분 30초), `0 5 0 * * *`(매일 00:05), `0 0/30 * * * *`(뉴스 30분 간격), `0 * * * * *`(코인 스냅샷 매 분)이다. 04:20은 이 중 어느 것과도 같은 분에 걸리지 않는다(매시 05분·매 분 계열과도 초·분이 다르다). 새벽 시간대라 조회 트래픽이 가장 적어 `RENAME` 교체 순간의 영향도 최소다. **`zone = "Asia/Seoul"`을 반드시 붙인다** — 배포 JVM 기본 타임존이 UTC라 빠뜨리면 예외도 로그도 없이 KST 13:20에 돈다(`application.yml` 주석이 반복 경고하는 실수).

### 다중 인스턴스 전환 시 재검토 (명시적 기록)

**지금은 분산 락을 두지 않는다.** 재구성은 멱등하고(같은 원장 → 같은 결과, 임시 키 교체라 중간 상태를 남기지 않는다) 현재 단일 인스턴스 전제이기 때문이다. 두 인스턴스가 동시에 재구성해도 마지막 `RENAME`이 이기고 두 결과가 같으므로 데이터가 깨지지 않는다.

**다중 인스턴스로 전환하면 다음 두 가지를 재검토한다.**

1. **임시 키 충돌** — 두 인스턴스가 같은 `ranking:{market}:rebuild` 키에 동시에 `ZADD`하면 서로의 중간 결과가 섞인다. 결과 자체는 같은 원장에서 나오므로 값이 어긋나지는 않지만, 한쪽이 `RENAME`한 뒤 다른 쪽이 남은 `ZADD`를 계속하면 새로 만들어진 임시 키가 다음 재구성까지 남는다. 인스턴스별 임시 키 suffix나 분산 락(`SET NX`)이 필요해진다.
2. **중복 부하** — 인스턴스 수만큼 같은 DB 전량 조회가 동시에 발생한다. 계좌 수가 커지면 새벽 04:20에 불필요한 N배 부하가 된다.

이 재검토 메모는 커밋 `43e9aaa`(Hikari 풀 20 근거에 "다중 인스턴스 전환 시 재검토"를 남긴 선례)와 같은 형식이다 — 지금 과설계하지 않되, 전제가 바뀌는 시점을 문서로 남겨 다음 사람이 놓치지 않게 한다.

### 신규·변경 클래스 목록

```
com.finplay.api.ranking
├── domain/RankingStatus.java                  # (신규) enum { READY, REBUILDING } — 응답 계약 값
├── service/RankingRebuildService.java         # (신규) 재구성 오케스트레이션 + 기동 훅 + @Scheduled
├── service/RankingService.java                # (변경) getRankings·getMyRanking에 status 판정 추가
├── store/RankingStore.java                    # (변경) replaceAll(market, entries) 추가 — 임시 키 적재 + RENAME
└── dto/response/
    ├── RankingListResponse.java               # (변경) status 필드 추가
    └── MyRankingResponse.java                 # (변경) status 필드 추가

com.finplay.api.order
├── repository/TradeRepository.java            # (변경) 매도 이력 조회 3종 추가
└── service/TradeService.java                  # (변경) ranking 도메인이 경유할 위임 메서드 3종 추가

com.finplay.api.account
└── service/AccountService.java                # (변경) getAccountsByIds(accountIds) 추가

src/main/resources/application.yml             # (변경) ranking.rebuild.cron 프로퍼티 추가
```

`RankingController`는 **변경하지 않는다** — 응답 DTO만 바뀌고 매핑·파라미터는 그대로다. 그래서 `docs/api-routes.md`의 Method·URL·요약 행도 그대로이고, 갱신 대상은 `docs/api-contracts.md`뿐이다(CLAUDE.md 규칙 7의 취지는 "controller와 문서를 같은 커밋에서 맞춘다"이므로, 계약이 바뀌는 이번에는 contracts가 그 대상이다).

### 레이어 배치 (ADR-0002)

```
RankingRebuildService (ranking/service)
  ├─→ TradeService.getSoldAccountIds(market)      # order 도메인 — service 경유 (TradeRepository 직접 주입 금지)
  ├─→ AccountService.getAccountsByIds(ids)        # account 도메인 — service 경유
  └─→ RankingStore.replaceAll(market, entries)    # ranking 자체 Redis 창구

RankingService (ranking/service)
  ├─→ RankingStore.topN / score / countStrictlyGreater   # 기존
  ├─→ AccountService.getAccountsWithUser / getAccountForWithUser  # 기존
  └─→ TradeService.hasAnySellHistory / hasSellHistory     # 신규 — status 판정용
```

`ranking` 도메인은 `TradeRepository`·`AccountRepository`를 **직접 주입하지 않는다.** `RankingService`에 `TradeService` 의존이 새로 생기는데, `order` 도메인이 이미 `ranking` 이벤트를 발행하는 방향(`OrderExecutionService` → `RealizedPnlUpdatedEvent`)과 반대 방향이라 순환 참조로 보일 수 있다. 실제로는 **타입 수준 순환이 아니다** — `OrderExecutionService`가 참조하는 것은 `ApplicationEventPublisher`와 `account` 도메인의 이벤트 record뿐이고 `ranking` 패키지의 어떤 빈도 주입하지 않는다. 스프링 빈 그래프상으로도 `RankingService → TradeService`의 단방향이다. 구현 시 이 점을 주석으로 남긴다.

### `TradeRepository` 신규 쿼리 (order 도메인)

```java
// 재구성 대상 조회 — 해당 시장 계좌 중 매도 체결 이력이 있는 계좌 id (RANK 재구성, 이슈 #279)
@Query("SELECT DISTINCT t.account.id FROM Trade t WHERE t.side = :side AND t.account.market = :market")
List<Long> findDistinctAccountIdsBySideAndMarket(@Param("side") OrderSide side, @Param("market") Market market);

// 내 랭킹 status 판정 — 이 계좌에 매도 이력이 있는가 (단건, 인덱스 조회)
boolean existsByAccountIdAndSide(Long accountId, OrderSide side);

// 랭킹 목록 status 판정 — 이 시장에 매도 이력 계좌가 하나라도 있는가
boolean existsBySideAndAccountMarket(OrderSide side, Market market);
```

- 뒤 둘은 Spring Data 파생 쿼리로 충분하다(`account.market` 중첩 프로퍼티 탐색). 첫 번째만 `DISTINCT`가 필요해 `@Query`로 쓴다.
- **`realized_pnl != 0`을 조건으로 쓰지 않는다.** spec.md 비즈니스 규칙 참고 — 매도했지만 손익이 정확히 0인 계좌가 누락되면 재구성 결과가 유실 전과 달라진다.

### `TradeService` 위임 메서드 시그니처 (order 도메인)

```java
// 재구성 대상 계좌 id — ranking 도메인이 TradeRepository를 직접 주입하지 않도록 하는 위임 메서드(ADR-0002)
@Transactional(readOnly = true)
public List<Long> getSoldAccountIds(Market market) {
    return tradeRepository.findDistinctAccountIdsBySideAndMarket(OrderSide.SELL, market);
}

// 이 계좌에 매도 체결 이력이 있는가 — GET /api/rankings/me의 status 판정용
@Transactional(readOnly = true)
public boolean hasSellHistory(Long accountId) {
    return tradeRepository.existsByAccountIdAndSide(accountId, OrderSide.SELL);
}

// 이 시장에 매도 체결 이력이 있는 계좌가 하나라도 있는가 — GET /api/rankings의 status 판정용
@Transactional(readOnly = true)
public boolean hasAnySellHistory(Market market) {
    return tradeRepository.existsBySideAndAccountMarket(OrderSide.SELL, market);
}
```

`OrderSide.SELL` 리터럴이 `ranking` 도메인으로 새지 않고 `order` 도메인 안에 머문다 — 이것도 service 경유의 이득이다.

### `AccountService` 위임 메서드 시그니처 (account 도메인)

```java
// 재구성 시 accountId 목록의 realized_pnl을 배치 조회한다. 랭킹 목록의 getAccountsWithUser와 달리
// User를 fetch join하지 않는다 — 재구성은 닉네임을 쓰지 않고 (id, realizedPnl)만 필요하다.
@Transactional(readOnly = true)
public List<Account> getAccountsByIds(List<Long> accountIds) {
    return accountRepository.findAllById(accountIds);
}
```

`AccountRepository`에는 **신규 메서드가 없다** — `JpaRepository.findAllById`를 그대로 쓴다.

### `RankingStore.replaceAll` — 임시 키 RENAME 절차

```java
// (RankingStore 기존 파일에 추가) 임시 키에 전량 적재한 뒤 RENAME으로 원자 교체한다.
// 재구성 중 조회가 빈 값·부분 값을 보지 않게 하는 것이 목적이다(spec.md 이슈 #279).
// key 문자열 조립은 이 클래스에서만 한다는 원칙(conventions.md)에 따라 임시 키도 여기서 조립한다.
private static final String REBUILD_KEY_SUFFIX = ":rebuild";
private static final int REBUILD_CHUNK_SIZE = 500;

public void replaceAll(Market market, List<RankingEntryDto> entries) { ... }
private String rebuildKey(Market market) { return key(market) + REBUILD_KEY_SUFFIX; }
```

절차는 다음 순서다.

1. `DEL ranking:{market}:rebuild` — 이전 실행이 중간에 죽어 남긴 잔재를 먼저 지운다. 이 단계를 빼면 지난 잔재와 이번 결과가 합쳐진 ZSET이 만들어진다.
2. `entries`를 `REBUILD_CHUNK_SIZE`(500)개씩 나눠 `ZADD ranking:{market}:rebuild`(Spring Data `opsForZSet().add(key, Set<TypedTuple>)` 배치 오버로드)로 적재한다. 청크로 나누는 이유는 계좌 수가 커졌을 때 한 번의 파이프라인 payload와 애플리케이션 힙 점유가 함께 커지는 것을 막기 위해서다. 임시 키에 **누적**하는 방식이라 청크 분할이 결과에 영향을 주지 않는다.
3. `RENAME ranking:{market}:rebuild ranking:{market}` — Redis `RENAME`은 대상 키가 이미 있으면 덮어쓰며 원자적이다. 조회는 교체 전 상태 아니면 교체 후 상태만 본다.

**경계: `entries`가 비었을 때(대상 0건).** 2단계에서 `ZADD`가 한 번도 실행되지 않으면 임시 키가 만들어지지 않고, 존재하지 않는 키에 대한 `RENAME`은 Redis에서 `ERR no such key` 오류다. 그래서 **`entries.isEmpty()`면 `RENAME`을 시도하지 않고 `DEL ranking:{market}`으로 본 키를 삭제하고 끝낸다.** 이것이 의미상으로도 정확하다 — 그 시장에 매도 이력 계좌가 하나도 없으면 랭킹은 비어 있는 것이 맞고, 유실 전 상태와도 일치한다. 빈 상태를 "아무것도 하지 않음"으로 처리하면(본 키를 그대로 두면) 원장에서 사라진 계좌가 ZSET에 영원히 남는다.

**실패 처리.** `replaceAll` 전체를 try/catch로 감싸 어떤 예외도 밖으로 던지지 않고 `log.error`만 남긴다. 실패 시 임시 키 정리(`DEL`)를 한 번 시도하되 그 정리 자체가 실패해도 무시한다(다음 실행의 1단계가 어차피 지운다). 근거: 이 메서드의 호출자는 기동 훅과 스케줄러다 — 여기서 예외가 새면 **기동이 실패**하거나 스케줄러 스레드가 죽는다. `addScoreWithRetry`가 매도 체결을 지키기 위해 예외를 삼키는 것과 같은 방침이며, 재구성은 어차피 다음 기동·다음 배치에 다시 돈다.

**재시도는 하지 않는다.** `addScoreWithRetry`의 backoff 재시도는 "이 한 번을 놓치면 다음 기회가 없다"(이벤트 유실)는 성격 때문이었다. 재구성은 매일 다시 돌고 기동 때마다 다시 도는 멱등 작업이라 즉시 재시도의 값어치가 낮다. 대신 실패를 `log.error`로 명확히 남긴다.

### `RankingRebuildService` (신규, `ranking/service`)

```java
// 매도 이력 원장(trades)과 계좌 실현손익(accounts)에서 랭킹 ZSET을 통째로 재구성하는 서비스 (이슈 #279)
@Service
@RequiredArgsConstructor
@Slf4j
public class RankingRebuildService {

    private final TradeService tradeService;
    private final AccountService accountService;
    private final RankingStore rankingStore;

    // 기동 완료 시점 1회 재구성 — BithumbFeedLifecycle과 동일한 훅.
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildOnStartup() { rebuildAll(); }

    // 매일 04:20(KST) 정기 재구성. 크론은 application.yml의 ranking.rebuild.cron이 정본이다.
    @Scheduled(cron = "${ranking.rebuild.cron}", zone = "Asia/Seoul")
    public void rebuildOnSchedule() { rebuildAll(); }

    // 두 시장을 각각 재구성한다. 한 시장이 실패해도 다른 시장은 계속 시도한다.
    public void rebuildAll() { for (Market market : Market.values()) { rebuild(market); } }

    public void rebuild(Market market) {
        List<Long> accountIds = tradeService.getSoldAccountIds(market);          // 1) 매도 이력 계좌
        List<RankingEntryDto> entries = accountService.getAccountsByIds(accountIds).stream()
            .map(a -> new RankingEntryDto(a.getId(), a.getRealizedPnl()))        // 2) score = realized_pnl
            .toList();
        rankingStore.replaceAll(market, entries);                                // 3) 임시 키 적재 + RENAME
        log.info("랭킹 재구성 완료. market={}, 대상 계좌 수={}", market, entries.size());
    }
}
```

- **`@Scheduled`를 서비스에 직접 붙인다.** 이 저장소의 배치 관례다(`FeedbackBatchService`·`PeerStatsBatchService`·`CryptoPriceSnapshotService` 전부 서비스에 직접 붙어 있다). 기동 훅만 별도 `*Lifecycle` 컴포넌트로 빼는 선례(`BithumbFeedLifecycle`)도 있으나, 트리거 2종이 완전히 같은 일(`rebuildAll()`)을 하므로 한 클래스에 두는 편이 "이 재구성은 언제 도는가"를 한 곳에서 읽게 한다.
- **이 서비스에 `@Transactional`을 붙이지 않는다.** DB 조회는 `TradeService`·`AccountService`가 각자 자기 트랜잭션 안에서 끝내고, 그 사이의 Redis 왕복 동안 DB 커넥션을 쥐지 않는다. `RankingService.getMyRanking`이 같은 이유로 트랜잭션을 걷어낸 것(위 "RANK-002 설계")과 같은 판단이다. 또 `@Scheduled` 메서드에 `@Transactional`을 함께 붙일 때 생기는 프록시·self-invocation 혼선도 피한다.
- **원장을 쓰지 않는다.** 이 흐름의 모든 DB 접근은 `readOnly = true` 조회다. 완료 조건 "재구성 전후로 원장이 변경되지 않는다"가 설계로 보장된다.
- **한 시장의 실패가 다른 시장을 막지 않는다.** `replaceAll`이 예외를 삼키므로 `rebuildAll`의 루프는 자연히 계속 돈다. `getSoldAccountIds`/`getAccountsByIds`가 던지는 예외(DB 장애)까지 막으려면 `rebuild(market)` 호출부를 try/catch로 감싸야 한다 — `rebuildAll`의 루프 안에서 감싼다.

### `application.yml` 프로퍼티

```yaml
# 랭킹 ZSET 재구성 배치(RankingRebuildService, spec 014 이슈 #279). Redis 유실 복구와 정기 교정을 겸한다.
# 기존 크론(08:45·15:32·매시 05분·매 분 계열·00:05·30분 간격)과 겹치지 않는 새벽 시간대로 골랐다.
# 선언부에 zone = "Asia/Seoul"을 반드시 함께 붙인다 — 배포 JVM 기본이 UTC라 빠뜨리면 KST 13:20에 돈다.
ranking:
  rebuild:
    cron: "0 20 4 * * *" # 매일 04:20 KST
```

`feedback`·`market` 블록처럼 `@ConfigurationProperties` record를 함께 두지는 않는다 — 값이 크론 하나뿐이고 `@Scheduled`는 record가 아니라 `Environment`에서 읽으므로 record를 만들면 "두 곳에 같은 값" 관리 부담만 늘어난다(`FeedbackBatchProperties` 주석이 지적하는 바로 그 이중화).

`@EnableScheduling`은 `FinPlayApiApplication`에 **이미 붙어 있다**(SSE heartbeat용) — 추가 설정이 필요 없다.

### 상태(`status`) 판정 — 로직 위치와 규칙

**위치는 `RankingService`다**(controller 아님, store 아님). 판정이 "Redis 상태 + 원장 상태를 함께 보고 비즈니스 의미를 정하는 일"이라 service 책임이고, `RankingStore`는 순수 Redis 연산 컴포넌트로 유지해야 하기 때문이다(RANK-001이 `RankingStore`에서 DB를 걷어낸 것과 같은 기준, 위 141행).

```java
// (신규) com.finplay.api.ranking.domain.RankingStatus
public enum RankingStatus {
    READY,       // ZSET이 원장을 반영하고 있다(빈 랭킹이면 실제로 매도 이력이 없는 것이다)
    REBUILDING   // ZSET이 유실된 상태다 — 재구성 전이며 지금 값은 신뢰할 수 없다
}
```

**`GET /api/rankings`(목록) 판정.**

```java
// getRankings 안: window(topN 결과)가 비었다 == ZSET에 멤버가 하나도 없다.
// topN은 limit+1(최소 2)개를 요청하므로 멤버가 하나라도 있으면 window는 비지 않는다 —
// 별도의 ZCARD 왕복 없이 이 값으로 카디널리티 0을 판정할 수 있다.
if (window.isEmpty()) {
    RankingStatus status = tradeService.hasAnySellHistory(market)
        ? RankingStatus.REBUILDING : RankingStatus.READY;
    return new RankingListResponse(market.name(), status, List.of());
}
// 그 외 경로는 항상 READY
```

- **정상 경로의 추가 비용이 0이다.** DB 조회(`hasAnySellHistory`)는 ZSET이 비었을 때만 일어난다. 랭킹에 사람이 한 명이라도 있으면 이 판정을 위한 왕복이 전혀 없다.
- **유령 필터링으로 `content`가 비는 경우는 `READY`다.** `window`는 비지 않았는데 `calculateRanks`의 DB 부재 필터링(8-1절)으로 결과가 0건이 될 수 있다. 이건 ZSET 유실이 아니라 Redis/DB 불일치라는 다른 상황이고, `REBUILDING`으로 표시하면 상태값의 의미가 흐려진다. 그대로 `READY` + 빈 `content`로 둔다.
- **부분 유실은 감지하지 않는다.** 근거는 spec.md 비즈니스 규칙. 매일 배치가 교정한다.

**`GET /api/rankings/me`(내 랭킹) 판정.**

```java
// getMyRanking 안: score가 null == 이 계좌가 ZSET에 없다.
// 매도 이력이 있는데 ZSET에 없으면 전체 유실이든 부분 유실이든 유실이다.
RankingStatus status = (score == null && tradeService.hasSellHistory(account.getId()))
    ? RankingStatus.REBUILDING : RankingStatus.READY;
```

- **부분 유실까지 잡는다.** 목록과 판정 기준이 다른 의도적 비대칭이며 근거는 spec.md 비즈니스 규칙("확인 비용이 싸고, 틀릴 때 당사자가 100% 잘못된 안내를 받는다").
- DB 조회는 `score == null`일 때만 일어난다(`&&` 단축 평가). 랭킹에 들어 있는 사용자는 추가 왕복이 없다.
- `score != null`이면 항상 `READY`다 — 내 점수가 있는데 다른 사람 점수가 유실됐는지까지는 판정하지 않는다(그 판정은 전체 비교와 같은 비용이다).

### 응답 DTO 변경 (하위 호환)

```java
// (변경) 기존 필드는 그대로, status만 추가한다 — 필드 추가라 기존 클라이언트가 깨지지 않는다.
public record RankingListResponse(String market, RankingStatus status, List<RankingListItemResponse> content) {
    public RankingListResponse { content = List.copyOf(content); }
}

public record MyRankingResponse(String market, RankingStatus status, Integer rank, String nickname, long realizedPnl) {
}
```

- `RankingListItemResponse`(항목)는 **바꾸지 않는다.** `status`는 응답 전체의 성질이지 항목별 성질이 아니다(`market`을 wrapper에만 두는 RANK-001 원칙과 같다).
- `MyRankingResponse`는 단건 응답이라 wrapper 개념이 없으므로 필드로 직접 포함한다(RANK-002가 `market`을 그렇게 둔 것과 같은 이유).
- enum은 Jackson 기본 직렬화로 `"READY"`/`"REBUILDING"` 문자열이 된다 — 별도 `@JsonValue`·컨버터가 필요 없다.

### 문서 동기화 (CLAUDE.md 규칙 7·10)

- **`docs/api-contracts.md`** — 랭킹 절의 `GET /api/rankings`·`GET /api/rankings/me` 응답 예시와 필드 표에 `status`를 추가한다. `rank: null` + `status: READY`(매도 이력 없음)와 `rank: null` + `status: REBUILDING`(집계 준비 중)이 서로 다른 의미라는 점을 명시한다. **응답 DTO가 실제로 바뀌는 커밋과 같은 커밋에서 갱신한다.**
- **`docs/api-routes.md`** — Method·URL·요약이 바뀌지 않으므로 변경 없음이 정상이다. 확인만 하고 불필요하게 손대지 않는다.
- **`docs/prd.md`** — §3 구현 현황 표는 **갱신 대상이 아니다**(RANK-001·RANK-002 행 판정이 "완료"에서 바뀌지 않는다, 근거는 spec.md "요구사항 ID를 새로 부여하지 않는 이유"). 대신 본문 2곳을 갱신한다.
  - RANK-001 절(현재 827행 부근): "재구성 트리거·절차의 세부 구현은 착수 시 확정한다"·"이 경우의 보상·정합성 재확인(재구성 배치 등)은 여전히 Decision Gate다" → 확정 내용(기동 훅 + 매일 04:20 배치, 전체 재구성, 임시 키 RENAME 교체)으로 갱신.
  - §6 "Redis 키 책임"(현재 1048행 부근): "유실 시 MySQL 원장으로 재구성한다" → 재구성 트리거·대상 판정 기준(매도 이력)·교체 방식과 `status` 노출을 덧붙인다.

### 테스트 계획 (ADR-0003)

- **단위 (`RankingStoreTest` 확장, Redis mock)**
  - `replaceAll`이 (1) 임시 키를 먼저 `DEL`하고 (2) `ZADD`로 적재한 뒤 (3) `RENAME`을 호출하는 **순서**를 `InOrder`로 검증한다. 순서가 뒤바뀌면 재구성이 깨지므로 순서 자체가 계약이다.
  - `entries`가 비면 `RENAME`을 호출하지 않고 본 키를 `DEL`하는지(경계).
  - `entries`가 `REBUILD_CHUNK_SIZE`를 넘으면 `ZADD` 배치가 청크 수만큼 호출되고 `RENAME`은 마지막에 1회만 호출되는지.
  - Redis mock이 예외를 던져도 `replaceAll`이 예외를 밖으로 전파하지 않는지(기동 실패 방지의 핵심 — 빠지면 안 된다).
- **단위 (`RankingRebuildServiceTest` 신규, `TradeService`/`AccountService`/`RankingStore` mock)**
  - `rebuild(market)`이 `getSoldAccountIds` → `getAccountsByIds` → `replaceAll` 순으로 위임하고, `replaceAll`에 넘어가는 entries의 score가 `accounts.realized_pnl` 값인지(`ArgumentCaptor`).
  - **매도 이력이 있고 `realized_pnl`이 정확히 0인 계좌가 entries에 포함되는지** — `realized_pnl != 0` 기준을 쓰지 않았음을 고정하는 회귀 테스트. spec.md 비즈니스 규칙의 핵심이라 반드시 넣는다.
  - 대상이 0건이면 빈 리스트로 `replaceAll`이 호출되는지(스킵하지 않는지).
  - `rebuildAll`이 `Market.values()` 전부를 시도하고, 한 시장에서 예외가 나도 나머지 시장을 계속 시도하는지.
  - `rebuildOnStartup`·`rebuildOnSchedule`이 같은 `rebuildAll` 경로를 타는지.
- **단위 (`RankingServiceTest` 확장, `TradeService` mock 추가)**
  - `getRankings`: ZSET 비었음 + 매도 이력 있음 → `REBUILDING`, ZSET 비었음 + 매도 이력 없음 → `READY`, ZSET에 데이터 있음 → `READY`이면서 `hasAnySellHistory`가 **호출되지 않는지**(정상 경로 비용 0 검증).
  - `getMyRanking`: score null + 매도 이력 있음 → `REBUILDING`(+`rank: null`), score null + 매도 이력 없음 → `READY` + `rank: null`, score 있음 → `READY`이면서 `hasSellHistory` 미호출.
- **슬라이스 (`@DataJpaTest`, `TradeRepositoryTest` 확장)**
  - `findDistinctAccountIdsBySideAndMarket`이 매도 이력 계좌만, 요청한 market으로만 한정해, 같은 계좌의 매도가 여러 건이어도 중복 없이 반환하는지. 매수만 있는 계좌가 빠지는지.
  - `existsByAccountIdAndSide`·`existsBySideAndAccountMarket`의 true/false 경계.
- **슬라이스 (`@WebMvcTest RankingControllerTest` 확장)**
  - 두 엔드포인트 응답 JSON에 `status` 필드가 `"READY"`/`"REBUILDING"` 문자열로 나가는지, 기존 필드(`market`·`content`·`rank`·`nickname`·`realizedPnl`)가 그대로 있는지(하위 호환 계약).
  - 유실 상태에서도 **200**인지(오류로 바뀌지 않았다는 계약).
- **통합 (`RankingRebuildIntegrationTest` 신규, Testcontainers MySQL+Redis — 기존 `TestcontainersConfiguration` 재사용, `@ServiceConnection` 사용 금지)**
  - 매도 체결로 랭킹이 만들어진 상태에서 `ranking:{market}`을 직접 `DEL`한 뒤 `rebuildAll()`을 호출하면, `GET /api/rankings`가 **유실 전과 동일한 순위·금액**을 반환하는지(이슈 #279 완료 조건 1·2의 직접 검증).
  - 재구성 후 각 `realizedPnl`이 `accounts.realized_pnl`과 일치하는지.
  - 매도 이력이 있고 `realized_pnl = 0`인 계좌가 재구성 후 랭킹에 **포함**되는지(실데이터 회귀).
  - 매도 이력이 없는 계좌가 재구성 후에도 랭킹에 나타나지 않는지.
  - 대상이 0건인 시장에서 `rebuild(market)`이 예외 없이 끝나고 랭킹이 빈 상태가 되는지(`RENAME` 경계의 실 Redis 검증 — mock으로는 `ERR no such key`가 재현되지 않으므로 이 시나리오는 통합 테스트에서만 의미가 있다).
  - ZSET을 비운 직후(재구성 전) `GET /api/rankings`가 200 + `status: REBUILDING`, `GET /api/rankings/me`가 200 + `rank: null` + `status: REBUILDING`을 반환하는지.
  - 재구성 전후로 `orders`·`trades`·`accounts`·보유·잔액 행이 변하지 않는지(완료 조건 "원장 불변"의 직접 검증 — 재구성 전후 스냅샷 비교).
