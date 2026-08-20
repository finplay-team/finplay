# Plan: 주식 분봉 수집 배치 안정성 보강

## 관련 문서

- Spec: `./spec.md`
- 원 계약: `docs/specs/003-market-data/spec.md` §MKT-005 데이터 수집과 보관
- ADR-0014 (`docs/adr/0014-crypto-watch-lock.md`) — 다중 인스턴스 중복 방지에 Redis 분산 락을 쓴 선례. `RedisLock`
  (획득·해제 메커니즘)이 ADR-0015 §4에서 `com.finplay.api.feedback.service`로 이미 추출·재사용 가능한 상태다.
- ADR-0015 (`docs/adr/0015-feedback-query-cache.md` §4) — `RedisLock` 추출 근거. "재사용이 실제로 필요해지면 그때
  추출한다"(ADR-0014 §후속)의 두 번째 소비자가 §245(조회 캐시)였고, 이 spec이 **세 번째이자 첫 도메인 간
  (market → feedback) 소비자**다.
- ADR-0020 (`docs/adr/0020-managed-service-deployment.md`) — 배포 아키텍처(EC2 + RDS·ElastiCache + 블루-그린).
- ADR-0021 (`docs/adr/0021-continuous-deployment.md`) — 배포 실행 방식(GitHub Actions, SSM, 블루-그린 전환).
- `docs/specs/033-exclude-tutorial-sandbox-data/spec.md` — 같은 성격(샌드박스 데이터의 실거래 인프라 누출)의 선례.
  033은 조회 경로(포트폴리오·투자일기·랭킹)에서 걸러냈고, 이 spec은 **수집 배치의 조회 대상**에서 걸러낸다는
  점이 다르다.
- ADR-0004 (`docs/adr/0004-flyway-migrations.md`) — 이 spec은 스키마 변경이 없어 §결정 7(파괴적 변경 2배포 분리)
  적용 대상이 아니다.

## 배경 조사

### ① 다중 인스턴스 중복 실행 — 배포 환경이 실제로 그런 구조인지 (실측 대신 문서 근거로 확인 완료)

이 세션은 EC2에 접근할 수 없어 "지금 이 순간 blue·green이 몇 개 떠 있는지"를 직접 확인하지 못했다. 대신
ADR-0020·ADR-0021 본문에서 **설계 자체가 이미 다중 컨테이너 상시 실행을 전제한다**는 근거를 찾았고, 이것으로
설계 결정을 내리기에 충분하다고 판단했다(추가 실측은 배포 후 검증 항목으로 남긴다).

- ADR-0021 §맥락: 블루-그린 다섯 단계 중 5번째가 "이전 색을 **내리지 않고** 남긴다 (롤백 경로)"다.
- ADR-0021 §결정 6: "이전 색은 내리지 않고 남긴다 (**다음 배포까지** 롤백 경로)". "다음 배포까지"는 두 배포
  사이의 전체 기간 동안 이전 색 컨테이너가 계속 살아 있다는 뜻이다 — 전환 직후 잠깐만 겹치는 것이 아니다.
- ADR-0021 §9(이 ADR이 결정하지 않는 것): "이 파이프라인은 EC2 **한 대** 안의 두 색을 전제한다" — 즉 "상시 2
  EC2 인스턴스"가 아니라 **"EC2 한 대 안에서 두 색(blue·green) 컨테이너가 상시 함께 뜬다"**가 정확한 구조다.
  이슈 #370이 "블루-그린 상시 2스택"이라고 표현한 것은 이 의미로 맞다(EC2 대수가 아니라 컨테이너 수 기준).
- `@Scheduled` cron은 컨테이너(JVM) 단위로 독립 발동하고 ALB 트래픽 라우팅과 무관하다 — 트래픽을 받지 않는
  구버전 색도 자기 cron은 그대로 돈다. 두 컨테이너 모두 같은 RDS(`finplay-db`)에 연결되므로(ADR-0020 §1) 같은
  `stock_candles`·`market_data_imports`에 동시에 쓴다.

**결론**: 두 번째 배포 이후로는 상시로 두 개의 수집 배치 스케줄러가 살아있다는 것이 이 저장소의 **의도된
설계**다(ADR-0021이 되돌리려는 문제가 아니다 — 되돌리면 롤백 경로가 사라진다). 따라서 "근본 원인 제거"(인스턴스를
하나로 줄이기)는 선택지가 아니다 — ADR-0021 §결정 6을 정면으로 거스르므로 새 ADR 없이는 할 수 없고, 이 spec은
그 방향을 택하지 않는다. 아래 §설계 옵션에서 락 방식만 비교한다.

### ④ 배치 실행 시각 9시간 불일치 — 조사 미완료 (정직하게 보고)

**이 세션에 SSH·AWS 자격 증명이 없어 원인을 규명하지 못했다.** 로컬 코드 리딩만으로 확인 가능한 범위는 다음과
같이 확인했고, 그 이상은 실제 배포 환경 접근이 필요하다.

**로컬 코드에서 확인한 것 (원인이 "아닌" 것)**:
- `ClockConfig`가 `Clock.system(ZoneId.of("Asia/Seoul"))`을 반환하고, `KisHistoricalCandleCollector.collect()`·
  `StockReplaySessionScheduler.resolveTodaySession()` 둘 다 `@Scheduled(cron = "...", zone = "Asia/Seoul")`로
  `zone`을 명시한다. 둘 다 최초 구현 커밋(`43eac525`, 이슈 자체가 확인)부터 있었다.
- 이 저장소의 알려진 함정(`CryptoFeedbackBatchService`의 주석, `docs/agent-mistakes.md` 계열) — "`zone`을
  빠뜨리면 배포 JVM 기본(UTC)으로 9시간 밀린다" — 은 **여기 해당하지 않는다**. Spring의 `@Scheduled` cron
  트리거는 `zone` 속성이 있으면 JVM 기본 타임존과 무관하게 그 zone으로 다음 실행 시각을 계산한다. `zone`이
  이미 명시돼 있으므로 이 알려진 함정과 같은 메커니즘으로는 설명되지 않는다(이슈 본문도 같은 결론).
- `collected_at`·`resolved_at` 컬럼은 `DATETIME(6)`이라(V9·V11 마이그레이션) MySQL이 조회 시 타임존 변환을
  하지 않는다 — 저장된 값 자체가 17:10이라는 뜻이고, 클라이언트 조회 도구의 타임존 해석 문제가 아니다.

**확인하지 못한 것 — 실제 배포 환경 접근이 필요하다**:
1. **배포된 이미지의 실제 커밋 SHA.** ADR-0021 §결정 4는 이미지 태그가 커밋 SHA라고 정했다 — 지금 라이브인
   색의 ECR 이미지 태그를 EC2(또는 ECR 콘솔)에서 확인해, 그 커밋에 실제로 `zone = "Asia/Seoul"`이 들어있는지
   `git show <SHA>:src/main/java/com/finplay/api/market/service/KisHistoricalCandleCollector.java`로 대조해야
   한다. 로컬 `git log`가 "최초 커밋부터 있었다"고 말해도, **배포된 이미지가 그 커밋을 실제로 담고 있는지는
   별개 사실**이다.
2. **EC2 컨테이너의 실제 환경변수.** `TZ`가 설정돼 있는지, `docker exec <container> env | grep TZ`(또는 SSM
   Send Command로 동일 명령)로 확인해야 한다. `compose.bluegreen.yaml`에 `TZ` 선언이 있는지도 함께 봐야
   한다 — 파일 자체는 이 세션에서 읽을 수 있었지만(레포에 존재), "실제로 그 compose 정의로 뜬 컨테이너가
   맞는지"는 배포 시점의 것과 지금 저장소의 것이 같다는 보장이 없다(수동 배포 이력이 있었다면 더욱 그렇다).
3. **실제 실행 시각의 컨테이너 로그.** `docker logs`(또는 CloudWatch가 연결돼 있다면 그쪽)에서 08:10과 17:10
   양쪽 시각 부근에 실제로 무엇이 찍혔는지 확인해야 한다 — cron이 08:10(컨테이너 자체 시간 기준)에 정상
   발동했는데 그 로그의 타임스탬프 표시만 9시간 밀려 보이는 것(로깅 프레임워크의 타임존)인지, 아니면 발동
   자체가 17:10에 일어난 것인지는 로그 없이는 구분할 수 없다.
4. **지금 몇 개의 색이 실제로 떠 있는지.** `docker ps`(EC2 SSH 또는 SSM)로 blue·green이 정말 둘 다 살아있는지
   직접 확인 — §① 결론이 문서 근거로는 타당하지만, 실제로 배포가 아직 1회뿐이었다면(초기 배포 후 재배포가
   없었다면) 지금은 컨테이너가 하나뿐일 수 있다.
5. **RDS 서버의 `time_zone` 시스템 변수와 JDBC 접속 문자열의 타임존 파라미터.** `DATETIME` 컬럼은 이론상 서버
   타임존 변환의 영향을 받지 않지만(TIMESTAMP와 달리), 커넥터 설정(`serverTimezone` 등)에 따라 애플리케이션
   레벨에서 변환이 개입할 가능성은 배제하지 못했다 — `DB_URL` 환경변수의 실제 값을 확인해야 한다.

**권고**: 이 5개 확인 항목은 EC2·AWS 콘솔 접근 권한이 있는 사람(또는 그 권한을 가진 별도 세션)이 먼저 확인해야
한다. 확인 결과가 "이 저장소 코드의 버그"로 좁혀지면 이 spec의 후속 태스크로 들어오고, "배포 파이프라인·인프라
설정 문제"(예: `compose.bluegreen.yaml`에 `TZ` 미선언, 또는 아직 배포되지 않은 구버전 이미지가 라이브)로
밝혀지면 **별도 이슈**로 분리한다(오케스트레이터 지시, 이슈 #370과 무관한 배포 인프라 이슈). 이 spec의
tasks.md는 이 조사를 포함하지 않는다 — 원인 없이 코드를 추측으로 고치지 않는다(CLAUDE.md 정신).

### ③ 샌드박스 제외 — 033 선례와의 관계

033은 실거래 조회 API(포트폴리오·투자일기·랭킹) 세 지점에서 `isTutorialSample`을 걸렀다. 이 spec은 네 번째
지점을 추가하는 것이 아니라 **033이 다루지 않은 새로운 층**(수집 배치의 조회 대상)이다 — 033 §범위 제외에도
이 배치는 언급되지 않는다. 판정 기준은 033과 동일하게 `instruments.is_tutorial_sample` 플래그 하나뿐이고, 새
플래그·명명 규칙을 추가하지 않는다(033 §비즈니스 규칙과 같은 원칙).

## 설계 옵션 비교 — COLLECT-STAB-001 (다중 인스턴스 중복 실행 방지)

배포 환경이 상시 2컨테이너 구조라는 것을 §배경 조사 ①에서 확인했으므로, "다중 인스턴스가 실제 원인인지 먼저
확인"은 이미 끝났다 — 남은 것은 락 방식 선택이다.

**(a) Redis 분산 락 — ADR-0014 `CryptoWatchLock`/`RedisLock` 선례를 그대로 재사용 (채택)**

- `RedisLock`(획득: `SET NX PX`, 해제: Lua check-then-delete)이 이미 `com.finplay.api.feedback.service`에
  범용 컴포넌트로 추출돼 있다(ADR-0015 §4). 새 메커니즘을 만들 필요가 없다 — `CryptoWatchLock`과 같은 모양으로
  키 접두사·TTL만 새로 정하는 얇은 컴포넌트(`StockCollectionLock`) 하나만 추가하면 된다.
- Redis는 이미 이 배포의 필수 인프라다(ElastiCache, ADR-0020) — 새 인프라 추가가 없다.
- 락 범위를 KIS 호출 이전(가장 앞)부터 저장 완료까지로 잡을 수 있어, DB 유니크 제약에 부딪히기 **전에** 경합을
  막는다 — 낭비되는 KIS 호출 자체를 없앤다(ADR-0014가 코인 LLM 비용을 막은 것과 같은 이유).
- 대가: `RedisLock`이 처음으로 `feedback` 도메인 밖(=`market` 도메인)에서 재사용된다. `conventions.md`의
  "도메인 간 참조는 service 레이어를 통해서만 한다"는 규칙은 지키지만(`RedisLock`은 repository가 아니라
  service 레이어 컴포넌트), 패키지 이름(`feedback.service.RedisLock`)이 이제 도메인 중립적 인프라를 가리키는
  것과 어긋나 보일 수 있다. 이번 spec에서는 옮기지 않는다 — 옮기려면 새 공용 패키지가 필요한데
  `conventions.md`의 `common`은 "전역 예외·오류 응답·공통 설정만" 두는 곳으로 이미 범위가 못박혀 있어(패키지
  구조 절), 새 패키지 신설은 이 spec의 범위(C-002 최소 구현)를 넘는다. **후속 관찰 사항으로 plan.md에 남기고
  구현하지 않는다** — reviewer가 참고할 수 있게.

**(b) DB 기반 락 (`SELECT ... FOR UPDATE`, 이 저장소의 기존 동시성 제어 방식) — 기각**

이 저장소의 전통적인 방식(`AccountRepository`·`OrderRepository`·`HoldingRepository`)이지만 이 배치에는 두 가지
이유로 맞지 않는다.

1. `KisHistoricalCandleCollector.collect()`는 **의도적으로 트랜잭션을 열지 않는다** — 16종목 순차 KIS HTTP
   호출(최대 10페이지, connect 5s/read 10s 타임아웃) 동안 DB 커넥션을 점유하지 않기 위해서다(PR #94 리뷰
   권장사항 ②, 클래스 주석에 이미 명시). DB 락으로 이 구간 전체를 지키려면 그 설계를 되돌려야 한다.
2. 잠글 대상 행이 애매하다 — `stock_replay_sessions`처럼 거래일당 1행이 미리 있는 테이블이 없고(수집 대상
   거래일 행 자체가 이 배치가 만드는 것), 잠글 행을 만들려면 스키마를 새로 추가해야 한다(이 spec은 스키마
   변경 없음을 목표로 한다).
3. MySQL 세션 어드바이저리 락(`GET_LOCK`)으로 우회할 수도 있지만, 그 락은 **커넥션(세션) 생존 기간에 묶인다** —
   HikariCP가 유휴 커넥션을 재활용·종료하면 그 순간 락이 조용히 풀릴 수 있다. 이 저장소에 이 방식의 선례가
   없고, Redis 락(TTL로 명시적 수명을 가짐)보다 실패 모드가 예측하기 어렵다.

**(c) 다중 인스턴스가 실제 원인인지 먼저 확인 후 근본 원인 제거 — 기각(§배경 조사 ①에서 이미 판단)**

배포 구조가 "상시 2컨테이너"인 것이 ADR-0021의 **의도된 설계**(롤백 경로)이므로, "원인 제거"는 그 설계를
되돌리는 것과 같다. ADR을 어기지 않고는 할 수 없고, 어기려면 새 ADR이 필요한데 이 spec의 문제(배치 중복
실행)는 락 하나로 해결되므로 배포 아키텍처를 바꿀 이유가 없다(CLAUDE.md 규칙 2 — ADR 위반 시 구현하지 않고
새 ADR 제안, 그런데 이번엔 그럴 필요조차 없다).

### 락 설계 세부

- **컴포넌트**: `com.finplay.api.market.service.StockCollectionLock` — `CryptoWatchLock`과 같은 모양(키 조립·
  TTL 결정만 담당, 메커니즘은 `feedback.service.RedisLock`에 위임).
- **락 키**: `market:stock-collect:lock:{tradingDate}` (`tradingDate`는 `yyyy-MM-dd`, `collect()`가 이미
  계산하는 값). 정규 배치와 재시도가 같은 거래일을 다루므로 같은 키로 자연히 서로도 배제한다.
- **락 범위**: `collect()` 진입 직후(대상 종목 조회 전)부터 `persist()`/`recordFailedImport()` 완료까지
  전체를 감싼다. `try { ... } finally { unlock } `으로 정상 종료·예외 종료 모두 해제를 보장하고, TTL은 프로세스
  가 죽는 등 `finally`조차 못 도는 경우의 최후 방어선이다.
- **획득 실패 시**: 조용히 로그(`log.info` 수준 — 정상적인 경합이지 장애가 아니다)만 남기고 `return` — KIS
  호출도, `market_data_imports` 기록도 하지 않는다(spec COLLECT-STAB-001).
- **TTL**: `market.stock.collect-lock-ttl-seconds`(신규, 기본값 **600초(10분)**). 근거 —
  `KisHistoricalCandleClientImpl`의 페이지당 최대 재시도(5회, `backOffAfterRateLimit`가
  `max(requestIntervalMs, 400ms) × (attempt+1)`로 대기, `requestIntervalMs=600`이면 재시도 5회 합계 약 9초)와
  종목당 최대 10페이지, 종목 수 13(샌드박스 3종 제외 후)을 곱한 **이론적 최악치는 수십 분대**이지만, 이는
  "13종목 전부가 동시에 최대 재시도까지 소진"하는 사실상 발생하지 않는 조합이다. 정상 실행은 종목당 수 초
  (2026-07-30 실측 기준 페이지당 600ms 간격에 성공률 약 67%)이므로 13종목 합쳐 수십 초~2~3분대로 추정된다.
  ADR-0014와 같은 방식으로 "실측이 아니라 여유 마진 추정"임을 명시하고, 배포 후 실제 실행 시간을 측정해
  조정한다(§후속). 600초는 정상 실행 시간의 수 배 여유를 두면서도, 프로세스가 죽었을 때 하루 종일 락이
  잠겨 있지 않게 하는 값이다.
- **재사용하지 않는 것**: `CryptoWatchLock`을 직접 재사용하지 않는다 — 키 접두사·TTL이 코인 감시 전용으로
  박혀 있다(`RedisLock` 자체의 재사용 이유와 같음, ADR-0015 §4 주석 참고).

## 파일별 변경 지점

### `KisHistoricalCandleCollector.java`

- `StockCollectionLock`을 생성자 주입으로 추가.
- `collect()`: `tradingDate` 계산 직후 `stockCollectionLock.tryLock(tradingDate)` 시도. 실패하면 로그 후 즉시
  `return`(COLLECT-STAB-001). 성공하면 기존 로직 전체를 `try { ... } finally { stockCollectionLock.unlock(...) }`
  으로 감싼다.
- 종목 조회를 `instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK)`에서
  `instrumentRepository.findByMarketAndTutorialSampleFalseOrderByIdAsc(Market.STOCK)`로 변경
  (COLLECT-STAB-002) — `InstrumentRepository`에 이미 있는 `findByMarketAndTradableTrueOrderByIdAsc`와 같은
  패턴의 파생 쿼리 메서드를 새로 추가한다.
- 새 메서드 추가:
  ```java
  @Scheduled(cron = "${market.stock.retry-cron}", zone = "Asia/Seoul")
  public void retryPendingInstruments() {
      collect();
  }
  ```
  (COLLECT-STAB-003) — `StockReplayImportTriggerService.trigger()`가 이미 `collect()`를 그대로 재호출해
  로컬에서 검증된 패턴이다. `collect()`가 종목 단위 `existsByInstrumentIdAndTradingDate` 스킵을 이미 갖고
  있으므로, 재시도 실행은 **아직 분봉이 없는 종목만** 자연히 다시 시도한다 — 별도의 "실패 종목 목록 조회" 로직을
  새로 만들지 않는다(C-002). 대상 종목이 모두 이미 수집돼 있으면 `collectInstrument`가 전부 스킵되어
  KIS 호출이 0건이지만, `persist()`는 여전히 `market_data_imports`에 `SUCCESS` 행 하나를 추가로 남긴다 —
  하루 최대 재시도 횟수(§재시도 스케줄 참고)만큼만 늘어나는 작은 감사 테이블의 여분 행이라 억제 로직을
  추가하지 않는다(불필요한 복잡도, C-002).
- 클래스 첫 줄 한글 주석과 `collect()` 위 주석을 갱신해 락·재시도·샌드박스 제외를 반영한다.

### `InstrumentRepository.java`

- 추가: `List<Instrument> findByMarketAndTutorialSampleFalseOrderByIdAsc(Market market);`
  (엔티티 필드가 `tutorialSample`이므로 Spring Data 파생 쿼리 프로퍼티명은 `TutorialSample`이다.)

### `KisHistoricalCandleImportWriter.java`

- `persist()`: `status`가 `SUCCESS`가 아니면(`PARTIAL_SUCCESS`·`FAILED`) 저장 직후
  `log.warn("주식 분봉 수집이 {}로 끝났습니다 (tradingDate={}, failureReason={})", status, tradingDate, failureReason)`
  를 남긴다(COLLECT-STAB-004). `FAILED`는 `log.error`로, `PARTIAL_SUCCESS`는 `log.warn`으로 구분한다 —
  전체 실패와 부분 실패의 심각도가 다르다(기존 `collect()`의 전체 예외 처리가 `log.error`를 쓰는 것과 같은
  기준).

### 신규 `com.finplay.api.market.service.StockCollectionLock`

- `CryptoWatchLock`과 같은 구조 — `feedback.service.RedisLock`을 주입받아 키 조립·TTL만 결정한다.
- `tryLock(LocalDate tradingDate)` → `Optional<String>`, `unlock(LocalDate tradingDate, String token)`.
- 키 조립: `"market:stock-collect:lock:" + tradingDate` (`tradingDate.toString()`은 ISO-8601 `yyyy-MM-dd`).

### 신규 `com.finplay.api.market.config.MarketStockProperties` + `MarketStockConfig`

- `MarketCryptoProperties`/`MarketCryptoConfig`와 같은 패턴.
- 필드: `@DefaultValue("600") int collectLockTtlSeconds`, `@DefaultValue("0 15,30,45 8-10 * * MON-FRI")
  String retryCron`.
- `application.yml`에 `market.stock.collect-lock-ttl-seconds: 600`, `market.stock.retry-cron: "0 15,30,45 8-10
  * * MON-FRI"` 미러링(다른 `market.*`/`feedback.*` 설정과 같은 방침 — yml과 `@DefaultValue` 양쪽에 값을
  두고 드리프트 테스트로 대조).
- **재시도 스케줄 근거**: 08:10 정규 배치 이후 08:15부터 10:45까지 15분 간격(08:15·08:30·08:45·09:15·09:30·
  09:45·10:15·10:30·10:45 — `8-10`시 구간의 `15,30,45`분, 정각(`0`분)은 정규 배치와 겹치지 않도록 제외) 총 9회
  재시도한다. 09:00 개장 전에 최소 3회(08:15·08:30·08:45)의 기회가 있고, 개장 이후에도 09:00~10:45 구간
  6회를 더 두어 장중에도 복구 기회를 남긴다. 그 이후는 003의 기존 정책(다음 영업일 정규 배치)에 맡긴다 —
  하루 종일 재시도하지 않는 것은 "언젠가 고쳐질 정상적인 지연"과 "그날 KIS 응답 자체가 계속 없는 상태"를
  구분할 신호가 없는 상태에서 무한정 재시도하지 않기 위해서다(범위 제외 참고).

### `KisProperties.java` + `application-prod.yml`

- `application-prod.yml`에 다음을 추가한다(COLLECT-STAB-005):
  ```yaml
  kis:
    request-interval-ms: 600
  ```
  근거: 이슈 #370이 확인한 대로 `kis.base-url`의 운영 기본값은 로컬과 같은 모의투자 도메인이다(`KisProperties`
  의 "실전투자 도메인 기준" 주석은 실제 배포 구성과 어긋난다) — 로컬에서 이미 검증된 600ms를 그대로 쓴다. 이
  spec으로 다중 인스턴스 중복 호출(①)이 없어지므로 실효 호출 간격이 더 이상 배 이상 무너지지 않고, 600ms +
  기존 EGW00201 전용 재시도(최대 5회, 백오프)가 합쳐지면 정상적인 하루는 대부분 성공할 것으로 기대한다 — 다만
  이것도 추정이며, 배포 후 실제 성공률로 재조정 대상이다(§후속).
- `KisProperties.java`의 `requestIntervalMs` 주석 중 "실전투자 도메인 기준"이라는 근거 문장을 정정한다 —
  기본값 0 자체는 유지하되(로컬·운영 모두 명시적으로 값을 주므로 기본값이 실제로 쓰이는 경로가 없다), 주석이
  현재 배포 구성(운영도 모의투자 도메인)과 어긋나는 근거를 대고 있었다는 점만 고친다.

### `StockReplaySessionScheduler.java`

- **변경 없음.** §범위 제외에서 판단한 대로 `UNIQUE(service_date)` 제약이 이미 안전망이고, 이 메서드는 외부
  API를 호출하지 않아(순수 DB 조회·상태 전이) 중복 실행의 대가가 낮다. 재시도(`retryPendingInstruments`)가
  `stock_candles`에 새 분봉을 추가해도 이 스케줄러를 다시 부를 필요가 없다 — `resolveTodaySession`이 고른
  `source_trading_date`는 한 번 `READY`로 확정되면 그날 안에 바뀌지 않고(003 spec 비즈니스 규칙, "당일 장중에는
  이 선택을 바꾸지 않는다"), 재시도가 채워 넣는 분봉은 **같은** `tradingDate`의 `stock_candles`에 그대로
  삽입되므로 가격 조회(`StockReplayService`)가 요청 시점에 직접 조회해 즉시 반영된다(캐시 레이어 없음, 코드
  확인 완료). 즉 재시도와 세션 확정 배치는 "같은 거래일 데이터가 늦게 채워져도 조회 경로가 그때그때 다시
  읽으므로" 별도 상호작용 코드 없이 이미 호환된다.

## 데이터 모델

변경 없음 — 스키마 마이그레이션 없음. 새 설정 키(`market.stock.*`)만 추가한다.

## 테스트 계획 (ADR-0003 기준)

- **단위**: `KisHistoricalCandleCollectorTest`(기존 파일 확장) — 락을 얻지 못하면 `kisHistoricalCandleClient`가
  전혀 호출되지 않고 `importWriter`도 호출되지 않음을 mock으로 검증. 샌드박스 종목 제외 후 남은 종목만
  `collectInstrument`가 호출됨을 검증(리포지토리 mock이 이미 필터된 목록을 반환하도록 스텁 — 리포지토리 쿼리
  자체는 `@DataJpaTest`가 검증). `retryPendingInstruments()`가 `collect()`를 위임 호출하는 것만 검증(로직
  중복 없음을 보증).
- **슬라이스**: `InstrumentRepositoryTest`(있다면 확장, 없으면 신규 `@DataJpaTest`) —
  `findByMarketAndTutorialSampleFalseOrderByIdAsc`가 실제 DB에서 샌드박스 종목을 제외하고 반환함을 검증
  (033의 `is_tutorial_sample` 시드 데이터 재사용).
- **통합 (핵심 시나리오, Testcontainers)**: 신규
  `StockCollectionLockConcurrencyIntegrationTest`(`CryptoWatchLockConcurrencyIntegrationTest`와 같은 구조) —
  1. **[방어 켠 상태]** 실제 Redis 락으로 두 스레드가 동시에 `collect()`를 호출해도 `market_data_imports`에
     그 거래일 행이 정확히 1건만 남고, `stock_candles` 유니크 제약 위반 예외가 발생하지 않음을 검증.
  2. **[재현]** 락을 우회하는 mock(`CryptoWatchLockConcurrencyIntegrationTest`의
     `alwaysSucceedingLockWithFreshTokens` 패턴)으로 같은 조건을 재현해, 락이 없으면 실제로 유니크 제약 위반
     예외 또는 두 번째 실행의 `FAILED` 이력이 발생함을 대조 확인(회귀 방지 근거를 남긴다).
  3. **[재시도 멱등성]** 정규 배치로 일부 종목만 저장된 상태에서 `retryPendingInstruments()`를 호출하면 이미
     저장된 종목의 KIS 클라이언트 호출이 발생하지 않고, 누락 종목만 호출됨을 검증(KIS 클라이언트는
     `@MockitoBean`으로 대체).
  4. **[재시도 무대상]** 모든 대상 종목이 이미 수집된 상태에서 `retryPendingInstruments()`를 호출하면 KIS
     클라이언트가 전혀 호출되지 않음을 검증(호출 0건은 mock `verify(never())`로 확인, `market_data_imports`
     추가 행 여부는 이 spec에서 억제하지 않기로 했으므로 단정하지 않는다).
- **회귀**: 기존 `KisHistoricalCandleCollectorTest`·`StockReplaySessionSchedulerTest`가 락·필터 도입 후에도
  통과함을 확인.

## 후속

- `market.stock.collect-lock-ttl-seconds`(600) 기본값은 실측이 아니라 여유 마진 추정이다 — 배포 후 실제 수집
  소요 시간을 측정해 조정한다(ADR-0014의 같은 후속 패턴).
- `kis.request-interval-ms`(600, 운영) 값도 배포 후 실제 EGW00201 발생률을 관찰해 조정 대상이다.
- `feedback.service.RedisLock`이 `market` 도메인의 첫 소비자가 된 것은 §설계 옵션에서 남긴 관찰이다 — 이후
  세 번째 도메인이 필요해지면 `RedisLock`을 도메인 중립적인 새 패키지로 옮기는 것을 검토한다(지금은 새
  패키지를 신설할 근거가 부족해 옮기지 않는다).
- §배경 조사 ④(9시간 불일치)의 5개 미확인 항목은 EC2 접근 권한이 있는 세션이 먼저 확인해야 한다.
