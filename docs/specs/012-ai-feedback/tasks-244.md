# Tasks: 012 AI 피드백 — 이슈 #244 (코인 변동 감시 다중 인스턴스 중복 방지)

> **이 문서는 이슈 #244의 범위만 담는다.** `./tasks.md`는 이슈 #225(코인 실시간 변동 감시 구현) 전용이고, 이 이슈는 `plan.md` §진행 상태 8개 분할 표 **밖의 별도 이슈**다(선례 #198 — LLM 호출량 계측). 값·규칙은 `./spec.md` §확정값과 `docs/adr/0014-crypto-watch-redis-lock.md`가 정본이다. 여기에 값을 다시 적지 않는다.
>
> **작성 규칙(`docs/specs/README.md`)** — 항목 하나 = implementer 1회 투입 = 커밋 1개. `run-log.md`에 시각·에이전트·실행 명령·근거를 1줄씩 남긴다(장문 금지). 테스트 레벨은 ADR-0003을 따른다.

## 배경 — 판정 완료, 지금 재현되는 버그 아님

- 단일 인스턴스에서는 Spring `@Scheduled` cron이 자기 자신과 절대 안 겹친다(이슈 #244 1단계, 소스·실측 확인 완료). **다중 인스턴스 전환 시점에만 문제가 된다.**
- 코인 카드는 `window_start`가 `NULL`이라 `UNIQUE(instrument_id, origin_trade_date, event_type, window_start)`가 안 걸린다 — `PriceMoveEventRepositoryTest.cryptoCardsAreNotDeduplicatedByTheUniqueConstraintBecauseWindowStartIsNull`로 이미 실측·커밋돼 있다.
- 방어 방식은 ADR-0014(승인됨)로 확정됐다 — `CryptoPriceMoveWatcher.watchOne()`이 z-score 게이트 통과 직후(쿨다운 확인 전)부터 카드 저장까지 종목 단위 Redis 락을 건다. 획득 실패 시 대기 없이 그 틱을 건너뛴다.

## 이 이슈 전체에 걸리는 제약

- **새 라이브러리를 추가하지 않는다.** Redisson 등은 쓰지 않는다 — 기존 `StringRedisTemplate`(`spring-boot-starter-data-redis`, 이미 의존성에 있음)로 직접 만든다(ADR-0014).
- **`CryptoWatchLock`은 이 이슈의 유일한 소비자다.** 범용 `DistributedLockService`로 미리 일반화하지 않는다(YAGNI, ADR-0014 §결정) — 재사용이 필요해지면(예: #245) 그때 추출한다.
- **`CryptoPriceMoveWatcher.watchOne(Instrument instrument, LocalDateTime now)`은 이미 있다**(`src/main/java/com/finplay/api/feedback/service/CryptoPriceMoveWatcher.java`). 새로 만들지 않고 그 메서드 안에 락 획득/해제를 끼워 넣는다. 순서는 이미 의사코드 그대로 구현돼 있다 — `score < detectionProperties.zScoreK()` 게이트(142번째 줄 근방) 통과 직후, `isWithinCooldown` 호출 전에 락을 건다.
- **새 마이그레이션이 필요 없다.** 이 이슈는 Redis에만 쓰고 DB 스키마를 건드리지 않는다.
- **새 `@Scheduled`를 추가하지 않는다.** 따라서 `spring.task.scheduling.pool.size`도 이 이슈에서는 바꾸지 않는다 — #225와 다른 점이다.
- **원장에 쓰지 않는다.** 이 이슈가 건드리는 것은 Redis 락 키와 `feedback.crypto.watch-lock-ttl-seconds` 설정뿐이고, `price_move_events`·`price_move_event_sources`에 대한 쓰기 경로 자체는 바뀌지 않는다(호출 여부만 락이 게이트한다).

## 작업 항목

- [x] **1. `FeedbackCryptoProperties`에 `watchLockTtlSeconds` 추가 + `application.yml` 갱신**

  §C-7의 `watch-lock-ttl-seconds`(기본 45)를 `FeedbackCryptoProperties`(`src/main/java/com/finplay/api/feedback/config/FeedbackCryptoProperties.java`)에 필드로 더한다 — 기존 6개 필드와 같은 형태(`@DefaultValue`)로, compact constructor 검증(`< 1`)도 나머지 넷과 같이 추가한다.

  **처음 계획에서 바뀐 점**: 착수 시점에는 "0 이하여도 '락을 못 건다'는 명확한 실패 모드"라고 보아 검증을 넣지 않기로 했으나, 실제로는 `Duration.ofSeconds`가 Redis 오류를 유발하고 `CryptoWatchLock.tryLock`의 `catch(RuntimeException)`이 이를 삼켜 항상 `Optional.empty()`를 반환한다 — 예외도 로그(WARN)도 없이 **모든 코인 카드가 영구 0건**이 되는, 나머지 넷과 같은 유형이라 방침을 뒤집었다. TTL 기본값도 처음의 30에서 조정됐다 — 락 안에서 도는 LLM 호출(`feedback.llm.timeout-seconds`, 20초)이 최악 시간이라 30과의 여유가 약 1.5배뿐이었기 때문이다.
  - `application.yml`의 `feedback.crypto` 블록(198번째 줄 근방, `match-before-minutes` 다음)에 `watch-lock-ttl-seconds: 45`를 추가하고 한 줄 주석으로 ADR-0014를 인용한다.
  - `application-crypto-real.yml`은 **대상이 아니다** — 이 이슈는 `@Scheduled`를 추가하지 않으므로 그 파일의 풀 계산 주석과 무관하다.
  - 기존 `FeedbackCryptoProperties`류 드리프트 테스트(yml ↔ `@DefaultValue` 일치)가 있다면 이 필드도 덮도록 갱신한다.
  - 검증 — 단위(새 필드 바인딩, yml·`@DefaultValue` 드리프트 테스트).

- [x] **2. `CryptoWatchLock` 컴포넌트 신설 — Redis `SET NX PX` 획득 + Lua check-then-delete 해제**

  신설 위치 `com.finplay.api.feedback.service.CryptoWatchLock`(ADR-0014 §결정). `PriceStore`(`market/store`)의 `StringRedisTemplate` 직접 사용 패턴을 참고하되, 이 컴포넌트는 `feedback` 소유이므로 자체 `StringRedisTemplate`을 주입받는다 — `market.store.PriceStore`를 거치지 않는다(코인 시세 데이터가 아니라 감시 로직의 락이므로 §C-6 "market은 서비스를 경유한다" 규칙의 대상이 아니다).
  - 키: `feedback:crypto-watch:lock:{instrumentId}`.
  - 획득 메서드(예: `tryLock(Long instrumentId)` → 이번 시도의 토큰 문자열을 담은 `Optional<String>` 또는 락 핸들 반환): `redisTemplate.opsForValue().setIfAbsent(key, token, Duration.ofSeconds(ttlSeconds))` 한 번의 원자적 명령. `token`은 호출마다 `UUID.randomUUID().toString()`으로 새로 만든다.
  - 해제 메서드(예: `unlock(Long instrumentId, String token)`): `DefaultRedisScript<Long>`으로 "GET이 token과 같으면 DEL, 아니면 아무것도 안 함"을 원자적으로 실행한다(check-then-delete를 두 명령으로 나누면 그 사이 TTL 만료 후 다른 인스턴스가 잡은 락을 지울 위험이 있다 — ADR-0014 §결정). 이 저장소에 Lua 스크립트 실행 선례가 없으므로 `RedisScript`/`DefaultRedisScript` 사용법은 Spring Data Redis 공식 API를 그대로 따른다.
  - TTL은 `feedback.crypto.watch-lock-ttl-seconds`(1번 항목)를 주입받는다.
  - Redis 자체가 예외를 던지면(장애) 획득 실패로 처리한다 — 호출부(`CryptoPriceMoveWatcher`)가 "얻지 못함"과 동일하게 취급하도록, 이 컴포넌트가 예외를 삼키고 빈 결과를 반환할지 예외를 던져 호출부가 잡을지는 구현자가 정하되 **어느 쪽이든 그 종목의 이번 틱은 건너뛰고 배치 전체는 죽지 않아야 한다**(§실패 처리).
  - 검증 — 단위(획득 성공/실패, 토큰 불일치 시 미해제, TTL 경과 후 자동 해제) + `@SpringBootTest`(실제 Redis에 락이 걸리고 풀리는지, 다른 토큰으로 해제 시도 시 안 풀리는지).

- [x] **3. `CryptoPriceMoveWatcher.watchOne()`에 락 통합**

  z-score 게이트(`score < detectionProperties.zScoreK()`) 통과 직후, `isWithinCooldown` 호출 전에 `CryptoWatchLock.tryLock(instrument.getId())`을 호출한다. 실패하면 그 종목의 이번 틱을 종료(`return false`)한다 — 로그 레벨은 §실패 처리의 다른 "정상 종료" 케이스들과 같은 `DEBUG`가 적절하다(오류가 아니다).
  - 락 범위는 쿨다운 확인 → 일일 상한 확인 → 근거 매칭 → LLM 호출 → 저장(`priceMoveCardWriter.persist`)까지 전부를 감싼다(ADR-0014). `try { ... } finally { cryptoWatchLock.unlock(...) }`로 어떤 경로로 빠져나가도(정상 종료·예외) 해제되도록 한다.
  - `watch()`의 종목별 `try/catch(RuntimeException)`(기존 코드, 99번째 줄 근방)는 그대로 둔다 — 락 해제가 `finally`에 있으면 이 바깥 catch와 상호작용해도 락이 새게 두지 않는다.
  - 검증 — 단위(락 획득 실패 시 근거 매칭·LLM 호출·저장이 전부 스킵됨을 mock으로 확인) + Testcontainers/`@SpringBootTest` 동시성 통합 테스트: 같은 종목·같은 시각 조건으로 `watchOne`을 두 스레드(또는 두 `CryptoPriceMoveWatcher` 인스턴스)에서 동시에 실행해 **카드가 1건만 저장되고 `NarrativeService` 호출도 1회만 일어나는지**(mock 호출 횟수) 확인한다.

- [x] **4. 재현 테스트 — 방어 비활성/우회 시 중복 재현, 활성 시 1건만 남음**

  spec §완료 조건 "동시성 방어" 절의 두 조건을 통합 테스트로 고정한다.
  - **비활성/우회 재현**: `CryptoWatchLock`을 항상 락 획득에 성공하는 것처럼 두거나(예: 테스트 전용으로 락 단계를 건너뛴 옛 경로 재현) 락 자체를 주입하지 않은 상태를 만들어, 동일 조건의 동시 실행이 카드 중복(그리고 `NarrativeService` 중복 호출)을 실제로 만든다는 것을 먼저 보인다 — 이 테스트가 "왜 락이 필요한가"를 증명한다.
  - **활성 시 1건만 남음**: 3번 항목의 통합 테스트와 같은 시나리오를 락이 켜진 상태로 실행해 1건만 저장되는 것을 대조군으로 확인한다.
  - 두 테스트를 같은 클래스에 나란히 두어(예: `CryptoWatchLockConcurrencyTest`) 대조가 한 파일에서 보이게 한다.
  - 검증 — Testcontainers(MySQL) + 실제 Redis(compose의 Redis 컨테이너) 통합. 고정 `Clock`으로 `now`를 통제해 z-score 게이트를 항상 통과하는 픽스처를 만든다.

- [x] **5. 문서 동기화 검증 — spec.md·ADR-0014 정합 확인**

  spec.md §C-9·§탐지 알고리즘(코인)·§C-7·§실패 처리·§완료 조건은 이미 planner가 이 이슈 착수 전에 갱신해 뒀다(ADR-0014 인용, `watch-lock-ttl-seconds` 설정값, Redis 장애 처리, "동시성 방어" 완료 조건 2건). 이 항목은 **구현 결과가 그 문서와 실제로 일치하는지 최종 확인**만 한다 — 클래스명·메서드명·설정 키가 spec·ADR 원문과 정확히 같은지, §완료 조건의 두 조건이 4번 항목의 테스트로 실제로 커버되는지 대조한다.
  - `docs/api-routes.md`·`docs/api-contracts.md`는 **대상이 아니다** — 이 이슈는 새 컨트롤러·새 엔드포인트를 만들지 않는다.
  - `docs/prd.md` §3은 **대상이 아니다** — FEED-005(코인 실시간 경로)는 이미 완료로 기록돼 있고, 이 이슈는 그 기능의 신뢰성을 다중 인스턴스 환경에 대해 보강하는 것이라 "제공하는 기능"이 늘지 않는다(CLAUDE.md 규칙 10 갱신 비대상 — 리팩터링·강화성 개선 유형).
  - 검증 — 없음(문서 대조뿐, 코드 검증 아님).

## 완료 조건 소유

`spec.md` §완료 조건의 "동시성 방어 (통합, 이슈 #244)" 절 2건이 이 이슈의 전부다. `plan.md` §완료 조건 배정의 "총 91건" 표에는 들어가지 않는다 — 그 표는 8개 분할(#147~#225) 전용이고 이 이슈는 그 밖의 별도 이슈다(선례 #198).

| # | 완료 조건 (`spec.md` §완료 조건 "동시성 방어") | 항목 |
|---|---|---|
| 1 | 다중 인스턴스 동시 실행 시 Redis 락으로 카드 중복·LLM 중복 호출을 방지한다 | **2**(락 구현) + **3**(통합) |
| 2 | 방어 비활성/우회 시 중복이 재현되고, 활성 시 1건만 남는 것을 테스트로 고정한다 | **4** |

## 이 이슈에서 하지 않는 것

- **원인 판정 재조사** — 이미 끝났다(이슈 #244 1단계, 이 문서 §배경).
- **범용 분산 락 서비스로 일반화** — ADR-0014가 명시적으로 기각했다(YAGNI). `CryptoWatchLock`은 이 이슈의 유일한 소비자로 남는다.
- **Redis 자체의 다중화(센티널/클러스터) 대응** — ADR-0014 §결과의 "받아들이는 대가"로 남겨 뒀다. 이 저장소는 Redis도 단일 인스턴스다.
- **`market.crypto.*`(가격 스냅샷) 변경** — 이 이슈는 `feedback.crypto.*`와 `CryptoPriceMoveWatcher`만 건드린다. `CryptoPriceSnapshotService`·`PriceStore`의 스냅샷 기록·조회 경로는 무관하다.
- **`feedback.crypto.watch-lock-ttl-seconds`의 실측 조정** — ADR-0014 §후속에 남겨 뒀다. 다중 인스턴스 배포 후 실제 처리 시간을 재고 조정한다.
