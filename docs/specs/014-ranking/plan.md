# Plan: 전체 랭킹 조회 (RANK-001)

## 관련 문서

- Spec: `./spec.md`
- PRD: `docs/prd.md` RANK-001(2026-08-03 확정, 이슈 #139)
- 이슈: #187(`gh issue view 187`), 선행 이슈 #139(완료, 정책 확정)
- 관련 ADR
  - **ADR-0002 (레이어드 아키텍처)** — `controller → service → repository/store`. 도메인 간 참조는 service를 통해서만 한다. 이 기능은 `ranking` 도메인이 `account` 도메인의 이벤트 타입 하나만 참조하고, 그 외에는 자체 `AccountRepository` 조회로 데이터를 가져온다(다른 도메인의 service를 주입하지 않음).
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
- **리스너 메서드 본문 전체를 try/catch로 감싼다 — `RankingStore.addScoreWithRetry` 내부의 try/catch만으로는 부족하다.** Spring 트랜잭션 매니저는 `AFTER_COMMIT` 동기화 콜백에서 던진 예외를 삼키지 않고 `commit()` 호출부까지, 결국 `@Transactional` 프록시를 거쳐 원래 호출 스레드(매도 요청을 처리 중인 HTTP 스레드)까지 전파시킨다(DB 롤백은 안 되지만 응답은 500이 나갈 수 있음). `RankingStore.addScoreWithRetry`는 Redis 호출만 감싸므로, 그 앞단인 `RankingService.refreshScore`의 `accountRepository.findById(accountId)`가 던지는 예외(예: DB 커넥션 풀 고갈로 인한 `SQLTransientConnectionException`, 순간적 DB 장애로 인한 `DataAccessResourceFailureException` — 둘 다 재현 전례 있음, `docs/agent-mistakes.md` 2026-07-30)는 그 try/catch로 못 막는다. 그래서 리스너 메서드 최상위에서 한 번 더 감싸 **어떤 예외가 나든** 로그만 남기고 절대 밖으로 전파하지 않게 한다(완료 조건 "매도 체결 자체는 정상 성공" 보장의 실질적 구현 지점 — `RankingStore`의 내부 try/catch가 아니라 이 리스너 레벨의 try/catch다).

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
    private final AccountRepository accountRepository; // refreshScore에서 사용 (RankingService로 옮길 수도 있음 — 아래 설계는 RankingService가 조회하고 RankingStore는 순수 Redis 연산만 담당하는 쪽으로 확정)

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

- `RankingStore`는 DB를 몰라야 하는가? 재검토 결과 **`RankingStore`에서 `AccountRepository`를 직접 쓰지 않는다** — 위 코드 스케치의 주석대로, 최종 설계는 `RankingService.refreshScore(accountId)`가 `AccountRepository.findById`로 `Account`를 조회하고, 그 결과(`market`, `realizedPnl`)만 `RankingStore.addScoreWithRetry(market, accountId, realizedPnl)`에 넘긴다. `RankingStore`는 순수 Redis 연산 컴포넌트로 유지한다(컨벤션의 "인프라 연동 위치" 표 — Redis는 전용 component, MySQL/JPA는 repository, 이 둘을 한 클래스에 섞지 않는다).
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
    private final AccountRepository accountRepository;

    @Transactional
    public void refreshScore(Long accountId) {
        accountRepository.findById(accountId).ifPresentOrElse(
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
        List<Account> accounts = accountRepository.findAllByIdInFetchUser(
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

1. `RankingStore.topN(market, limit)`으로 최대 `limit`개 (accountId, score) window를 score desc로 가져온다.
2. window 안의 **고유 score 값마다 1회만** `RankingStore.countStrictlyGreater(market, score)`를 호출해 해시맵에 캐시한다(같은 score를 가진 동점자가 여러 명이어도 Redis 호출은 1번).
3. `rank(score) = countStrictlyGreater(score) + 1`.
4. window를 `(score desc, userId asc)`로 재정렬해 최종 노출 순서를 만든다.
5. 각 항목을 `RankingListItemResponse(rank, nickname, realizedPnl)`로 매핑.

이 흐름을 `RankingService`의 `private` 메서드로 분리해 두면(예: `calculateRanks(List<RankingEntryDto>, Market)`), RANK-002가 "내 계좌의 accountId 하나에 대해 score만 알면 같은 공식으로 순위를 계산"하는 데 그대로 재사용할 수 있다 — RANK-002는 이 spec의 범위가 아니므로 지금은 재사용 가능한 형태로만 분리하고 실제 호출부는 만들지 않는다.

### 7) 랭킹 대상 제외가 "쓰기 시점"에 자연스럽게 해결됨

매도 이력이 한 번도 없는 계좌는 `RealizedPnlUpdatedEvent`가 발행된 적이 없으므로 `ranking:{market}` ZSET에 **member 자체가 존재하지 않는다.** 별도의 "매도 이력 있음" 플래그·조회를 추가하지 않고도 완료 조건("매도 이력 없는 회원 제외")이 쓰기 경로 설계로 자동 충족된다 — 이 점을 구현 시 주석으로 남긴다.

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

com.finplay.api.order.service.OrderExecutionService (기존 파일 — 수정)
└── createSellOrder()에서 account.addRealizedPnl(...) 직후 eventPublisher.publishEvent(new RealizedPnlUpdatedEvent(account.getId()))
```

## 테스트 계획

- **단위 (`RankingStoreTest`, Redis mock)**: `addScoreWithRetry`가 `StringRedisTemplate`(mock)에서 예외를 던지도록 stub했을 때 3회 재시도 후 예외를 삼키고 반환하는지(호출 스레드로 예외가 전파되지 않음), 성공 시 1회만 ZADD 호출하는지.
- **단위 (`RankingServiceTest`, `RankingStore`/`AccountRepository` mock)**: `refreshScore`가 존재하지 않는 accountId에 대해 예외 없이 로그만 남기는지, `getRankings`가 limit 클램핑(0·음수→10, 51 이상→50, null→10)을 올바르게 적용하는지, 동점 그룹이 있을 때 `countStrictlyGreater` 캐시로 동일 score에 대해 Redis 호출이 1회만 발생하는지, 공동 순위 계산이 "1,1,3" 패턴을 만드는지(단위 테스트에서 mock 반환값으로 검증), 동점자 내부 정렬이 userId 오름차순인지.
- **단위 (`RankingEventListenerTest`, `RankingService` mock, 신규)**: `rankingService.refreshScore(...)`가 `DataAccessException`(DB 재조회 실패 시뮬레이션 — `RankingStore` 내부가 아니라 그 앞단인 `AccountRepository.findById` 실패를 재현)을 던지도록 stub했을 때, `onRealizedPnlUpdated` 호출이 예외를 밖으로 전파하지 않고 정상 반환하는지 확인한다. 이 테스트가 "리스너 레벨 try/catch"의 존재 근거를 검증하는 유일한 테스트이므로 빠지면 안 된다.
- **슬라이스 (`@WebMvcTest RankingControllerTest`)**: `market` 누락·미지원 리터럴 400, `limit` 0·음수·51 이상도 200(클램핑되어 서비스 호출, 400 아님— `GET /api/trades`와의 차이를 명시적으로 검증), 정상 요청 200과 `rank`/`nickname`/`realizedPnl`/`market` 필드 계약, 인증 없이 요청 시 401.
- **슬라이스 (`@DataJpaTest`, `AccountRepositoryTest` 기존 파일 갱신)**: `findAllByIdInFetchUser`가 요청한 id 목록의 `Account`+`User`를 N+1 없이 정확히 반환하는지.
- **통합 (`RankingIntegrationTest` 신규, Testcontainers MySQL+Redis — `TestcontainersConfiguration`의 기존 정적 싱글턴 재사용, `@ServiceConnection` 사용 금지)**:
  - 매도 체결 API 호출 → 커밋 → (동기 리스너이므로 응답 반환 시점에 이미 반영됨을 전제로) `GET /api/rankings` 조회 시 해당 계좌가 올바른 score로 나타나는지.
  - 같은 계좌에 대해 `RankingEventListener.onRealizedPnlUpdated`를 실제 DB 커밋 순서와 다른 순서로 두 번 직접 호출(이벤트 순서 역전 시뮬레이션)했을 때, 최종 ZSET score가 DB의 최신 `realized_pnl`과 일치하는지.
  - 매도 요청이 검증 실패로 커밋되지 않는 경우(예: 보유수량 부족) `ranking:{market}` ZSET에 해당 계좌가 추가되지 않는지(커밋 전 반영 없음 확인).
  - Redis 컨테이너를 일시 정지시키거나 `RankingStore`를 실패하도록 구성한 상태에서 매도 체결 API를 호출해도 주문·체결·계좌 갱신이 정상 200으로 성공하는지(랭킹 갱신 실패가 체결에 영향 없음).
  - 매도 이력이 없는 계좌는 `GET /api/rankings` 목록·순위 산정에 나타나지 않는지, 동점자 공동 순위(공동 1위 2명 다음 3위)가 실제 Redis 데이터로 검증되는지.
