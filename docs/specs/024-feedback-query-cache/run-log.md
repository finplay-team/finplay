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

## 모니터링 (사람용 요약)
- 01:05 — 항목 1: `RedisLock` 추출, `CryptoWatchLock`이 위임하도록 전환. 컴파일·기존 단위 테스트 통과(#244 테스트 무수정).
- 01:23 — 항목 2: `FeedbackQueryCacheProperties`·`Config`·`FeedbackQueryCache` 신설, yml 블록 추가. `MarketSessionTimes` 두 상수를 public으로 넓힘.
- 01:47 — 항목 3·4: 조회 2곳 배선(요약 텍스트 2종 + 브리핑 텍스트 2종 + 주식 브리핑 items). Redis 불건전 시 락·대기를 건너뛰도록 `read`가 미스와 장애를 구분.
- 02:05 — 항목 5: 코인 갱신 2곳이 저장 성공 뒤에만 해당 캐시 키를 무효화. 주식 생성 경로는 무변경.
- 02:20 — 항목 6: 락만 무력화한 대조군(캐시는 켠 채)에서 원본 N회, 스프링 빈 방어군에서 1회를 같은 클래스에서 대조. 결정론적 보조 단정 2건 포함.
- 02:30 — 항목 7: 범위 전환(15:29/15:31)·코인 TTL 상한·Redis 장애 200·원장 불변 확인. 전체 `build` 통과(SHA `d490c2e`).
- 02:40 — 리뷰 판정: 차단 0건 / 권장 5건 / 참고 4건. 캐시 값의 시각 비의존성과 §C-4 판정 순서 불변은 확인됨(적중=READY, 미적중=로더 반드시 실행). 권장은 락 획득 후 캐시 재확인 누락, 읽기 트랜잭션 안 대기 슬립, 주식 브리핑 items 무효화 근거 공백, 코인 배치 분(05) 리터럴 이중화, CryptoWatchLock의 `new RedisLock`.
- 03:15 — 리뷰 권장 4건 반영: 락 획득 직후 double-check, items TTL을 다음 수집(`collect-cron`)으로, 코인 배치 분 드리프트 테스트 신설, `CryptoWatchLock`이 `RedisLock`을 주입받도록 환원. 트랜잭션 안 대기 1건은 PR "남은 위험"으로 남긴다.
