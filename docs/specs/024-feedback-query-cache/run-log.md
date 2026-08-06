# Run Log: 024-feedback-query-cache

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 01:05 | implementer | `.\gradlew.bat compileJava compileTestJava spotlessApply` | ADR-0015 §4, plan.md 구성요소 설계(`RedisLock`), ADR-0014 |
| 01:23 | implementer | `.\gradlew.bat compileJava` + `test --tests CryptoWatchLockIntegrationTest`(컨텍스트 기동 확인) | ADR-0015 §2·§3·§5·§6·§7, plan.md 프로퍼티·키 표 |
| 01:47 | implementer | `.\gradlew.bat compileJava` + `test --tests InstrumentNewsQueryServiceTest --tests MarketBriefingServiceTest`(51건 통과) | ADR-0015 §1, plan.md "변경: 조회 2곳", §C-4 판정 순서 |
| 02:05 | implementer | `.\gradlew.bat compileJava compileTestJava` + 영향 단위 테스트 재실행 | ADR-0015 §3, tasks.md 항목 5 |
| 02:20 | tester | 항목 6 — 만료 쏠림 방어 대조(mock `RedisLock` 대조군 vs 스프링 빈 방어군) 통합 테스트 작성·실행 | tasks.md 항목 6, ADR-0015 §4, #244 `CryptoWatchLockConcurrencyIntegrationTest` 대조 구조 |
| 02:30 | tester | 항목 7 — 범위 전환·코인 주기·Redis 장애·원장 불변 경계 통합 테스트 + `.\gradlew.bat build` 통과(SHA `d490c2e`) | tasks.md 항목 7, ADR-0003, PRD C-005 |
| 02:40 | reviewer(리뷰) | `git diff dev...HEAD` (34파일) | ADR-0015, spec.md, docs/conventions.md, ADR-0002·0003·0004, ADR-0014, CLAUDE.md 규칙 7·10 |
| 03:15 | implementer | 리뷰 권장 4건 반영 후 `.\gradlew.bat compileJava` + `test --tests "…feedback.store.*" --tests CryptoWatchLock*` | PR 리뷰 [권장 1·2·3·4], ADR-0015 §2·§6 |
| 03:55 | implementer | 조회 트랜잭션 경계 분리 후 `.\gradlew.bat build` 통과 | PR #257 남은 위험 1, `PostSellFeedbackService`/`PostSellFeedbackReader` 선례, ADR-0002 |
| 04:40 | reviewer(리뷰) | `git diff dev...HEAD` (42파일, 마지막 커밋 `8fbd3b7` 중점) | ADR-0015, spec.md(024), spec 012 §C-4·§C-6, docs/conventions.md, ADR-0002·0003·0004, CLAUDE.md 규칙 7·10 |
| 05:05 | implementer | 2회차 리뷰 중 2건 반영 후 `.\gradlew.bat compileJava` | PR 리뷰 2회차 [참고 — TTL≤0 WARN]·[권장 — spec 012 §C-6], ADR-0015 §2 |
| 05:40 | reviewer(리뷰) | `git diff dev...HEAD` (43파일, 테스트 방어력·단순성 2관점 한정) | spec.md(024), ADR-0015, docs/conventions.md, CLAUDE.md 전역규칙 2·3, ADR-0014, docs/agent-mistakes.md |
| 05:50 | implementer | 3회차 리뷰 코드 7건 반영 후 `.\gradlew.bat compileJava` | PR 리뷰 3회차, ADR-0015 §1·§2·§6, docs/agent-mistakes.md 2026-08-04 |

## 모니터링 (사람용 요약)
- 01:05 — 항목 1: `RedisLock` 추출, `CryptoWatchLock`이 위임하도록 전환. 컴파일·기존 단위 테스트 통과(#244 테스트 무수정).
- 01:23 — 항목 2: `FeedbackQueryCacheProperties`·`Config`·`FeedbackQueryCache` 신설, yml 블록 추가. `MarketSessionTimes` 두 상수를 public으로 넓힘.
- 01:47 — 항목 3·4: 조회 2곳 배선(요약 텍스트 2종 + 브리핑 텍스트 2종 + 주식 브리핑 items). Redis 불건전 시 락·대기를 건너뛰도록 `read`가 미스와 장애를 구분.
- 02:05 — 항목 5: 코인 갱신 2곳이 저장 성공 뒤에만 해당 캐시 키를 무효화. 주식 생성 경로는 무변경.
- 02:20 — 항목 6: 락만 무력화한 대조군(캐시는 켠 채)에서 원본 N회, 스프링 빈 방어군에서 1회를 같은 클래스에서 대조. 결정론적 보조 단정 2건 포함.
- 02:30 — 항목 7: 범위 전환(15:29/15:31)·코인 TTL 상한·Redis 장애 200·원장 불변 확인. 전체 `build` 통과(SHA `d490c2e`).
- 02:40 — 리뷰 판정: 차단 0건 / 권장 5건 / 참고 4건. 캐시 값의 시각 비의존성과 §C-4 판정 순서 불변은 확인됨(적중=READY, 미적중=로더 반드시 실행). 권장은 락 획득 후 캐시 재확인 누락, 읽기 트랜잭션 안 대기 슬립, 주식 브리핑 items 무효화 근거 공백, 코인 배치 분(05) 리터럴 이중화, CryptoWatchLock의 `new RedisLock`.
- 03:15 — 리뷰 권장 4건 반영: 락 획득 직후 double-check, items TTL을 다음 수집(`collect-cron`)으로, 코인 배치 분 드리프트 테스트 신설, `CryptoWatchLock`이 `RedisLock`을 주입받도록 환원. 트랜잭션 안 대기 1건은 PR "남은 위험"으로 남긴다.
- 03:55 — 조회 2곳을 비트랜잭션 오케스트레이터 + `InstrumentNewsQueryReader`·`MarketBriefingReader`(각 메서드가 자기 읽기 트랜잭션)로 분리. 캐시 대기가 더 이상 JDBC 커넥션을 쥐지 않는다. 전체 `build` 통과.
- 04:40 — 리뷰 판정(2회차): 차단 0건 / 권장 3건 / 참고 4건 — 머지 가능. 트랜잭션 경계 분리는 옳다(로더 안 조회만 트랜잭션, 대기는 밖, 쓰기 경로 경계 무변경). §C-4 판정 순서는 `SummaryTextLookupDto`로도 그대로 유지되고(적중=READY, 미적중이면 로더가 반드시 실행돼 4·5번을 가른다), §C-6의 "생성·조회가 같은 구간 질의를 공유"도 Reader 한 벌로 유지된다. 권장은 엔티티 누출 테스트가 lazy 연관을 만지는 경로(브리핑 items·프롬프트 조립)를 비껴간 것, `context-notes.md` "남긴 것"이 마지막 커밋과 모순되는 것, spec 012 §C-6 코드 요소 목록 미갱신.
- 05:05 — TTL≤0 로그를 "로더가 도는 사이 경계 통과"(DEBUG)와 "진입 시점부터 과거"(WARN)로 분리 — 코인 매시 경계 잡음을 없애면서 계산 버그 신호는 남긴다. spec 012 §C-6에 `RedisLock`·`FeedbackQueryCache`(`store/`)·Reader 2종·`SummaryTextLookupDto`를 근거(#245 / PR #257)와 함께 추가.
- 05:40 — 리뷰 판정(3회차, 테스트 방어력·단순성 한정): 차단 0건 / 권장 5건 / 참고 4건 — 머지 가능. 뮤테이션이 살아남는 자리 2곳(`RedisLock`의 `REDIS_FAILURE`↔`NOT_HELD`, `FeedbackQueryCache`의 `expiryWasAheadAtEntry` DEBUG/WARN 분기), 추출로 무효화된 단정 2개(`CryptoWatchLockTest`의 "Redis 장애" 오분류 단정 — 그 WARN이 이제 `RedisLock` 로거로 나간다), 남은 캐시 키 결합 1건(`CryptoFeedbackBatchIntegrationTest`가 전역 `crypto-briefing-text`를 지우지 않는다), 주석 오류 2건.
- 05:50 — 3회차 리뷰 반영: `spring.data.redis.timeout/connect-timeout: 2s` 명시(Lettuce 기본 60초 노출 차단, 값 근거는 소비자별 오탐 대가 — PriceStore가 하한을 정한다), 대기 루프가 락 키 소멸 시 즉시 이탈(음성 결과에서 wait-millis 낭비 제거, `RedisLock.isHeld` 신설), 캐시 키에 값 형식 버전 `v1` 추가, TTL≤0 로그를 단일 DEBUG로 되돌림(진입 시점 플래그로는 정상 요청을 오분류한다), ADR-0015 §1 표에 정정 문단, plan.md 키·TTL·킬 스위치 롤링 창 반영, 주석 2곳 범위 정정.
