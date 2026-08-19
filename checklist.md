# 하네스 셋팅 체크리스트

## 완료
- [x] Spring Boot 4.1 프로젝트 생성 (Gradle Kotlin DSL, Java 21, MySQL)
- [x] Testcontainers MySQL 8.4 고정
- [x] ADR 3건 (기록 규칙 / 아키텍처 / 테스트 전략)
- [x] specs 구조 + 템플릿
- [x] conventions.md (코드 + 팀)
- [x] CLAUDE.md (AI 규칙)
- [x] 서브에이전트 (code-reviewer, test-runner) + settings.json
- [x] PR/이슈 템플릿, CI 워크플로우

- [x] `./gradlew build` 통과 확인 (Testcontainers MySQL 포함, 영문 경로 이전 후)
- [x] git init + 초기 커밋

## 2차 보강 완료 (2026-07-22)
- [x] docs/api-routes.md + springdoc(Swagger UI) 라이브 문서
- [x] Flyway (spring-boot-starter-flyway + V1) + ddl-auto validate — ADR-0004
- [x] compose.yaml (MySQL 8.4, 포트 13307) + spring-boot-docker-compose 자동 기동
- [x] 프로필 분리 (application.yml / application-local.yml)
- [x] Spotless(palantirJavaFormat) + JaCoCo
- [x] README (영문 경로 경고 포함)
- [x] 에이전트 플릿: implementer / test-writer / code-reviewer(보강) / qa-verifier / doc-syncer / test-runner
- [x] 스킬: /feature (개발 루프), /review-pr (PR 리뷰 오케스트레이션) — ADR-0005
- [x] CI 보강 (spotlessCheck, JaCoCo 업로드) + PR 제목 검사 + CODEOWNERS + dependabot
- [x] bootRun 검증 (Swagger 200, Flyway V1 적용, compose 자동 기동)

## 이전 하네스 선별 이식 완료 (2026-07-23)
- [x] Context Router (docs/context-router.md) — 작업 유형별 읽을 문서 지정
- [x] 실수 로그 (docs/agent-mistakes.md) — 실제 사례 2건 시드
- [x] 영향도 기반 CI — docs-only PR은 Gradle 스킵 (paths-filter)
- [x] SharedTestcontainers — MySQL 컨테이너 static 싱글턴화
- [x] packet 계약 — /feature, /review-pr 최소 전달 원칙
- [x] 제외 확정: evidence 6종, harness_gate.py, 역할 8종, Level 0~7, pre-commit 훅

## PRD 반입 + 스택 확정 완료 (2026-07-23)
- [x] PRD 반입 (`docs/prd.md` — Notion 원본의 레포 정본화, FinPlay·`/api`·Java 17 반영)
- [x] 프로젝트명 FinPlay 변경 (패키지 `com.finplay.api`, ADR-0006) + Java 17 전환
- [x] 에러 응답 포맷 PRD안 확정 + URL 버저닝 미사용 (conventions.md)
- [x] CI 하네스 로드맵 문서화 (`docs/harness-roadmap.md` — 튜터 제안: 이슈 트리거 + 지표)
- [x] implementer 읽기 목록에 agent-mistakes.md 추가, settings.json에 gh 권한 추가
- [x] `./gradlew build` 통과 재확인 (Java 17 + 리네임 후)

## Codex 자동 리뷰 + 병렬 에이전트 (2026-07-23)
- [x] ~~Codex PR 1차 자동 리뷰 워크플로우~~ → **당일 철회** (리뷰 3중화 부담, ADR-0007 폐기)
- [x] 병렬 에이전트 가이드 (`docs/parallel-agents.md` — Agent View는 내장이라 미구현, 사용법만 기록)
- [x] 에이전트 팀 실험 기능 활성화 (settings.json env — 새 세션부터 적용)
- [x] 정적 분석 게이트 — SpotBugs + JaCoCo 라인 커버리지 도입 (build에 포함, 튜터 피드백 "화이트박스 검증"). 도입 당시 60%, 이후 40%로 조정 — 아래 참조

## 1차 MVP spec 작성 (2026-07-23)
- [x] PRD §8 태스크 9개 → spec 폴더 9개 생성 (001-foundation ~ 009-integration)
- [x] 001~003은 풀세트(spec+plan+tasks), 004~009는 spec.md만 (plan·tasks는 착수 직전 작성)
- [x] 튜터 계획서 양식 반영 — spec 템플릿에 "비즈니스 규칙", plan 템플릿에 "입력 명세" 섹션 추가

## 서브에이전트 4개 체제 전환 (2026-07-23, 튜터 피드백)
- [x] planner 신설 (계획 모드: spec/plan/tasks 작성 + 동기화 모드: api-routes.md — doc-syncer 흡수)
- [x] tester 병합 (test-writer + test-runner — 작성·실행·실패 분석 한 투입)
- [x] reviewer 병합 (code-reviewer + qa-verifier — 리뷰/QA 모드 분리, 블랙박스 독립성 유지)
- [x] /feature·/review-pr 스킬, CLAUDE.md 에이전트 나열 갱신
- [x] ADR-0008 작성 + ADR-0005 상태 줄 부분 대체 표기
- [x] 검증 경량화 — 항목별 리뷰 → 마무리 1회, QA는 /review-pr에서만, 커버리지 게이트 60% → 40%
- [x] 전체 검토 + github-workflow-agents 선별 이식 (이슈/PR 템플릿 보강, 작은/큰 루프, 되돌리기 금지) + 낡은 참조 2건 수정

## MVP 범위·시세 정책 문서 확정 (2026-07-24)
- [x] 이메일 인증을 가입 선행 단계로 편입 (PRD AUTH-004, 002 spec/plan/tasks. 인증 전 users·accounts 미생성)
- [x] OAuth 이메일 자동 계정 연결 제거 (ACCOUNT_LINK_REQUIRED 거부. 명시적 연결 기능은 1차 제외)
- [x] 배포·CI를 1차 MVP 범위로 편입 + `010-deployment` spec 신설 (최소 CI → 수동 배포 → 스모크)
- [x] 차수 용어 대응표 (팀 회의 MVP/1차 고도화/2차 고도화 ↔ PRD 1차/2차/3차 MVP)
- [x] 주식 시세 공급자 2종 분리 (`StockPriceProvider` — KRX 재생 공개 기본 / KIS 실시간 개발자 본인 전용, 승인 없는 공개 KIS는 fail-fast)
- [x] /feature·/review-pr 전체 build 중복 제거 (SHA 재사용·CI 결과 판독·문서 전용 PR 생략)

## 남은 작업
- [x] GitHub 레포 생성 + 푸시 — `finplay-team/finplay` (2026-07-23. ADR-0006 권장명은 `finplay-api`였으나 팀 결정으로 `finplay`)
- [ ] 로컬 폴더명 `tradeclass-api` → `finplay-api` 변경 (팀원 클론 전이라면 지금이 적기)
- [x] GitHub 브랜치 보호 규칙 설정 — main 직접 푸시 금지 + 승인 1명 (2026-07-23. dev 통합 브랜치 체제)
- [ ] CODEOWNERS에 실제 팀원 핸들 채우기 (4번째 팀원 org 가입 대기)
- [x] /review-pr 실전 검증 — docs-only PR 1건으로 리뷰·빌드·QA 모드 전부 확인 (2026-07-23)
- [x] ~~CI paths-filter 실동작 검증~~ → **CI 워크플로우 제거** (2026-07-24 튜터 피드백 "CI는 배포 단계에" — 재도입 시점은 harness-roadmap)
- [x] `/feature docs/specs/001-foundation` 루프 실측 (PR 전까지, 약 25분 — context-notes 참조. 측정 후 코드 폐기, 실제 구현은 재작업 필요)
- [ ] 전역 예외 핸들러 + 에러 응답 포맷 구현 (`001-foundation` spec 범위. PRD §5 오류표 코드 전부를 ErrorCode enum으로 — 이메일 인증·OAuth 코드 5종 추가됨)
- [x] JaCoCo 커버리지 최소선 추가 (현재 기준 **라인 40%** — 정본은 `docs/conventions.md`. 지표 보고 후 상향)
- [ ] 최소 CI 재도입 + 배포 후 스모크 (`scripts/smoke.ps1`) — **`010-deployment`로 이관** (2026-07-24. 첫 배포가 통합 검증보다 앞서므로 PRD 9가 아니다)
- [ ] Coordinator(사람)·Orchestrator(AI) 역할 구분 문구 — `docs/parallel-agents.md` + `CLAUDE.md`
- [ ] 지표 체계 문서화 (지표 7종 + `mode:solo`/`mode:parallel` 라벨) — `harness-roadmap.md`는 아직 측정 항목 3개
- [ ] SonarCloud 연동 — 보류 (필요해지면 재검토, 현재는 로컬 도구로 충분 판단)

## 시세 소스 KRX → KIS 전환, 이슈 #17 범위 분리 (2026-07-28)
- [x] PRD·spec 003/009/010 문서에서 KRX 관련 기술을 KIS Open API 기준으로 갱신 (`KIS_HISTORICAL`·`KisHistoricalReplayPriceProvider`·`KisHistoricalCandleCollector`)
- [x] 이슈 #17 본문을 KIS Open API 기준으로 수정 (범위: 캔들 조회 API + 과거 데이터 수집 기본 동작만)
- [x] ~~이슈 #82(KIS 실시간 틱 집계)~~, #83(수집 파이프라인 장기운영 방어 로직) 신규 생성 — #17에서 분리. → **#82는 실제로 만들어지지 않았다** (2026-07-31 이슈 #109에서 확인). 그 번호의 실제 이슈는 무관한 「[MVP][포트폴리오] 내 체결 내역 조회 API」이고, KIS 실시간 틱 집계는 `docs/specs/003-market-data/tasks.md`의 후속 항목으로만 남아 있다
- [x] 이미 병합된 이슈 #16 코드(`KrxReplayPriceProvider`·`KRX_REPLAY` enum)의 KIS 네이밍 리네이밍 여부 결정 및 실행 — 이슈 #19에서 완료(`KrxReplayPriceProvider`→`KisHistoricalReplayPriceProvider` 리네이밍, `KRX_REPLAY` enum은 `StockFeedProvider` 삭제로 리네이밍 자체가 불필요해짐)
- [ ] 이슈 #17 실제 구현 (`/feature docs/specs/003-market-data`)

## 코인 차트 빗썸 연동, MKT-008 신설 (2026-07-30)
- [x] PRD에 MKT-008(코인 차트 1분봉) 신설 + 공통 오류표에 502 `MARKET_DATA_PROVIDER_ERROR` 추가 + Redis 키 책임에 "캔들 캐시 키 없음" 명시 + §10 레이트리밋 Decision Gate 추가
- [x] spec 003 갱신 — MKT-008 요구사항·비즈니스 규칙·범위 제외 정정("코인 캔들 영구 저장 제외" → "우리 저장소 보관 제외, 빗썸 조회 중계")·완료 조건
- [x] plan 003 갱신 — 코인 캔들 설계 절(외부 엔드포인트·필드 매핑·정렬 반전·진행 중 봉 제외·from/to→to+count 변환·502 처리·캐시 없음), 구성 요소 4종 추가, 테스트 계획
- [x] tasks 003에 코인 차트 작업 항목 추가 (이슈 #20)
- [x] api-routes.md·api-contracts.md 캔들 절을 주식·코인 공통으로 갱신
- [x] 이슈 #20 본문에 코인 차트(MKT-008) 범위 추가 — 기존 `/api/cryptos/stream` SSE 범위는 그대로 유지
- [ ] 이슈 #20 실제 구현 (`CryptoCandleProvider`·`BithumbRestCandleProvider`·`CandleResponse.volume` BigDecimal 확대·`MARKET_DATA_PROVIDER_ERROR` 추가)
- [ ] 프론트에서 코인 캔들 400 거부를 분기 처리하던 코드가 있으면 정리 (별도 레포 `FinPlay`)
- [ ] 실제 빗썸 캔들 REST 외부 스모크 (12종 전체 200 응답·필드명 일치 확인)

## 이슈 #20 SSE 축 제거 (2026-07-30, 같은 날 추가 정정)
- [x] 이슈 #20 본문 재작성 — SSE 절 삭제, 코인 실시간 1분봉 차트 단일 범위로 확정
- [x] prd.md §5 API 목록에서 `GET /api/cryptos/stream` 제거 (`/stocks/stream`만 유지)
- [x] spec.md 개요·시나리오에서 `/cryptos/stream` 서술 제거 — "코인은 전용 스트림 없음, 캔들 API 재조회로 충당" 명시
- [x] plan.md API 표·이슈 분할·SSE 계약·구성요소 표·흐름도에서 `CryptoPriceSseController`·`/cryptos/stream` 관련 서술 정리 (`/stocks/stream`(#19)만 SSE 대상, MKT-003/004 기반 주문 체결 경로는 유지)
- [x] tasks.md에서 미구현 코인 SSE 태스크 항목 삭제
- [x] api-contracts.md 캔들 절의 "SSE 틱으로 갱신" 문구를 "짧은 주기 재조회"로 정정
- [ ] 이슈 #20 실제 구현 착수 (차트만)

## KIS 분봉 수집 속도 제한 대응 (2026-07-30, 이슈 #105)
- [x] `EGW00201`(초당 거래건수 초과)만 골라 최대 5회 백오프 재시도 — 다른 오류는 즉시 던진다
- [x] `kis.request-interval-ms` 설정 추가 (기본값 0으로 기존 동작 보존, `application-local.yml`에서 600)
- [x] 단위 테스트 — 속도 제한은 재시도해 성공, 도메인 불일치(`EGW02004`)는 재시도하지 않음
- [x] `POST /api/dev/stock-replay-imports` (`@Profile("local")`) — 08:10·08:40 배치 즉시 실행
- [x] `LocalForcedOpenStockPriceProvider` (`@Primary @Profile("local")`) — `force-market-open=true`일 때만 READY 세션 기준 OPEN 강제, 기본값 false
- [x] `docs/api-routes.md`·`docs/api-contracts.md` 동시 갱신
- [x] `./gradlew build` 통과
- [x] 실측 검증 — 수집 성공률 30%(16종 중 1종) → 100%(5,630건), 소요 1분 20초

## 코인 SSE 스테일 참조·이슈번호 오참조 정정 (2026-07-31, 이슈 #109)
- [x] `docs/prd.md` MKT-008 — 분 이하 해상도 실시간 갱신을 "SSE 틱" → "캔들 API 짧은 주기 재조회"로 정정
- [x] `docs/specs/003-market-data/spec.md` MKT-008 — 같은 문장 정정
- [x] 존재하지 않는 "이슈 #82"(KIS 실시간 틱 집계) 참조 53곳을 서술("KIS 실시간 후속")로 교체 — prd.md 13·spec.md 20·plan.md 15·tasks.md 4·checklist.md 1
- [x] `tasks.md` 후속 이슈 절 머리에 "대응 GitHub 이슈 없음" 주석 추가 — 다음 사람이 번호를 다시 지어내지 않도록
- [x] GitHub 이슈 #98(코인 시세 SSE 스트림 API)을 `not planned`로 재분류 + 스코프 아웃 사유 코멘트
- [x] 로컬 시드 엔드포인트 이름 확인 — 백엔드 문서는 이미 `POST /api/dev/stock-replay-imports`로 정확해 정정 대상이 없다. `stock-replay-seeds` 오기는 프론트 레포(`FinPlay`) `checklist.md` 몫
- [x] 고친 문서의 참조 대상 실재 확인 — plan.md 절 제목 4종, 이슈 #19·#83, 엔드포인트 경로, `/api/cryptos/stream` 잔재 없음
- [x] `./gradlew build` 미실행 — Java 코드 무변경. `docs/specs/010-deployment/spec.md:23` "문서만 바뀐 PR은 Gradle 단계를 건너뛴다" 방침
- [x] PR #112 리뷰 반영 — `checklist.md:90`을 원문 취소선 + 정정 덧붙이기로 변경(레포 기존 관행 `~~항목~~ → 철회`와 통일), `tasks.md` 후속 이슈 절에 "이슈 생성 시 서술 일괄 치환" 안내와 대상 검색 grep 추가

## 이슈 #119 빌드 1회차 실패 근본 수정 + 빌드 시간 단축 (2026-08-03)
- [x] 고아 Testcontainers 컨테이너 2개(6시간 방치) 제거 — 관측 오독 방지 (2026-08-02 세션이 남긴 것)
- [x] `TestcontainersConfiguration` — 컨테이너 `@Bean`(`@ServiceConnection`)을 `JdbcConnectionDetails`·`DataRedisConnectionDetails` 빈으로 교체. Spring이 컨테이너를 stop할 수단을 없앤다
- [x] Boot 4.1 인터페이스 이름 확인 — Redis 쪽은 `RedisConnectionDetails`가 아니라 `DataRedisConnectionDetails`(`org.springframework.boot.data.redis.autoconfigure`). jar를 `javap`로 확인
- [x] 교체 전후 동등성 확인 — `@ServiceConnection`이 만들던 값과 대조(바이트코드). JDBC는 `getUsername`·`getPassword`·`getJdbcUrl`, Redis는 원본이 `Standalone.of(getHost(), getMappedPort(6379))`, 이 구현은 `getFirstMappedPort()`로 **호출 API가 다르고 결과값만 같다**(노출 포트가 6379 하나뿐이라 동일 값)
- [x] 드러난 후속 문제 해결 — 컨테이너가 살아남자 `Too many connections`가 6/6 결정론적 재현. 실측 `max_connections=151`, 실제 접속 최대 153. 컨테이너에 `--max-connections=1000` 적용
- [x] 컨테이너 교체 유무 직접 관측 — 0.8초 간격 프로브로 회차마다 등장 컨테이너 2개(MySQL 1 + Redis 1)뿐, 교체 0건
- [x] 전체 빌드 **16회 연속 1회차 통과** (기준: 수정 전 18회 중 11회 실패). 155개 클래스 1329건, 실패·에러·스킵 0
- [x] 빌드 시간 측정 — `--profile` 결과 `:test`가 197.2초 중 194.2초(**98.5%**). 나머지 태스크 전부 합쳐 3초라 설정 캐시·검사도구 최적화는 무의미
- [x] 실험 A(컨텍스트 캐시 `maxSize` 32 → 200) — **효과 없어 되돌림.** 앱 기동 40회 불변. 컨텍스트 40개는 축출 대상이 아니라 실제로 서로 다른 설정
- [x] 실험 B(MySQL 데이터 디렉터리 tmpfs) — 채택. ~~컨테이너 기동 20.6→11.2초, 앱 기동 합계 109.1→89.6초, 빌드 중앙값 **3.30→2.80분(−15%)**, 편차 3.1~3.9 → 2.8 고정~~ → **정정**(PR #133 리뷰 차단 지적). 위 수치는 부하가 다른 두 배치를 비교한 것이라 무효다. 같은 시간대에 tmpfs on/off를 번갈아 3쌍 측정한 결과 **확실한 것은 컨테이너 기동 약 23초 → 약 11초(빌드당 약 12초 단축)뿐**이고, 앱 기동 합계와 전체 빌드 시간에는 일관된 차이가 없다(2번째 쌍은 tmpfs 쪽이 오히려 느렸다). 대가는 램 212MB
- [x] tmpfs 적용 코드로 재검증 (PR #133 본문에 회차 기록)
- [x] PR #133 리뷰 반영 — 차단(철회 수치가 인수인계 문서에 확정 사실로 남음) 정정, 후속 이슈 #134 생성, `spring-boot-testcontainers` 의존성 제거, Redis 동등성 서술 정정
- [ ] **하지 않음(의도)** — `@MockitoBean` 17곳 통합으로 컨텍스트 40개 줄이기. 테스트가 무엇을 검증하는지를 바꾸는 변경이라 **이슈 #134**로 분리
- [ ] **미검증** — PR #127 10초 타임아웃의 병렬 부하 오탐 여부. 동시 빌드 금지 방침이라 병렬 부하 조건을 만들지 않았다 (단독 실행 중 12.7분 걸린 회차도 통과했다는 관찰만 있음)

## 이슈 #134 테스트 Spring 컨텍스트 축소 + 커넥션 보유량 제거 (2026-08-07)
- [x] 측정 방법 고정 — `-PmeasureContexts=true`로 `org.springframework.test.context.cache`를 DEBUG로 켜고 캐시 통계의 `missCount`(= 컨텍스트 적재 횟수)를 읽는다. 커넥션은 컨테이너에 3초 간격 `SHOW GLOBAL STATUS`. **전후 비교는 반드시 같은 스위치로** (측정 스위치가 로그를 10MB로 불려 빌드 시간을 왜곡하므로 PR #133의 3.5분과는 비교 불가)
- [x] 착수 전 실측 — 컨텍스트 **82개**(이슈 본문의 40은 PR #133 시점 값), DataSource 보유 컨텍스트 50, 앱 기동 81회 266.2초, **MySQL 최대 동시 접속 276**
- [x] 정적 분석이 예측한 키 82개와 실측 `missCount` 82가 일치 — **축출로 인한 재적재는 0건이고 82는 전부 서로 다른 설정**이다. PR #133의 "`maxSize` 상향 무효" 결론이 지금도 유효
- [x] 원인 분류 — `@SpringBootTest` 44갈래 중 비기준 43갈래를 만든 요소: **`@Import`한 중첩 `@TestConfiguration` 31** > `@AutoConfigureMockMvc` 11 > `@MockitoBean` 7 > `@MockitoSpyBean` 6 > `@ActiveProfiles` 4 = `@TestPropertySource` 4. **이슈가 지목한 `@MockitoBean`(38곳)은 주범이 아니다**
- [x] 31갈래의 정체 — `FixedClockTestConfig`/`MutableClockTestConfig`라는 이름만 다른 중첩 클래스 27곳. 내용은 `@Primary Clock` 빈 하나이고 `MutableClock` 구현이 22개 파일에 복제돼 있었다. 11곳은 기준 시각까지 `2026-07-29 10:00`으로 동일
- [x] 공용 `TestClock` + `TestClockConfig`(`com.finplay.api.common`)로 27곳 통합 → 컨텍스트 **82 → 64**, 앱 기동 **81 → 63회 / 266.2 → 186.1초**, Hikari 풀 **50 → 32**
- [x] 통합이 검증을 줄이지 않았음을 **일부러 망가뜨려 확인** — (1) `OrderBuyIntegrationTest`의 `clock.set(BASE_NOW)`를 빼면 5건 중 3건 red(클래스별 시각 세팅이 실제 방어선), (2) 프로덕션 `StockReplayService`의 `now(clock)`을 실시각으로 바꾸면 `JournalListIntegrationTest` 13건 중 7건 + `OrderBuy` 3건 red(시각 고정이 여전히 무언가를 지킨다). 둘 다 원복 확인
- [x] `minimum-idle=0` + `idle-timeout=10초`를 테스트에만 적용 → **최대 동시 접속 276 → 16**(`Max_used_connections` 고수위 값이라 순간 스파이크 포함). `maximum-pool-size`는 기본 10 유지라 동시성 테스트는 영향 없음 — 커넥션 고갈을 직접 재는 `FeedbackQueryCacheConnectionHoldingIntegrationTest`도 통과
- [x] `--max-connections=1000` 제거 (PR #133이 남긴 부채 종료). 기본 한도 151 대비 여유 9배
- [x] `docs/agent-mistakes.md` 기록 — 기계 변환의 "수동 보완 남았는가" 판정을 OR로 짜서 1건이 빠져나갔고, 그 여파가 **무관한 리포지토리 카운트 테스트 5건**으로 번졌다
- [ ] **하지 않음(의도)** — `@WebMvcTest` 31갈래 통합. 각자 다른 컨트롤러를 겨냥한 슬라이스라 갈리는 게 정상이고 DataSource가 없어 커넥션과 무관하다. 합치려면 모든 컨트롤러를 한 컨텍스트에 올려야 해 격리가 줄어든다
- [ ] **미검증** — 여러 워크트리가 동시에 빌드를 돌릴 때의 접속 피크. 16은 단독 실행 1회 값이다

## 2차 AI 피드백 설계 확정 (2026-08-02, spec 012)

### 문서 (이번 세션 완료)
- [x] `docs/specs/012-ai-feedback/spec.md` 작성 — Part A(변동 원인) + Part B(매도 회고), FEED-001~007
- [x] `docs/prd.md` C-004 개정 — "외부 시장 정보 미사용" → "시간적 동시 발생 서술에만 사용, 인과 단정 금지"
- [x] `docs/prd.md` 2차 MVP 범위에 spec 012 링크, 뉴스 출처·저작권 게이트를 3차 → 2차로 이동
- [x] `docs/api-routes.md` "2차 계획 라우트" 절 신설 — 구현된 라우트 표와 분리(controller 미존재)
- [x] ~~`docs/api-contracts.md` `## feedback (2차 계획)` 절 — 두 엔드포인트 계약 초안~~ → 이후 절 제목이 `## 012 AI 피드백 (2차 계획 — 아직 구현하지 않음)`으로 바뀌고 엔드포인트도 4개가 됐다 (2차 리뷰 [참고] 반영)
- [x] `context-notes.md` 결정 기록

### 기존 Notion 명세 반영 (2026-08-02 2차 수정)
- [x] Notion `api 명세서` §6에 AI 복기 엔드포인트 6개가 이미 `명세 작성 현황=완료`로 등록돼 있음을 확인 — 담당자는 전부 비어 있다
- [x] 매도 회고 경로를 신규 `/api/trades/{id}/feedback` → 기존 명세의 **`/api/ai/post-sell/{tradeId}`**로 정렬
- [x] ~~계획 대조(`planOutcome` 5값) 요구사항 추가 — 서버 판정, `STOP_LOSS_AVOIDED` 최우선~~ → **철회했다.** 투자일기 비의존 결정으로 계획 대조가 최종 범위에서 빠졌고, `planOutcome`은 현재 어떤 spec에도 요구사항으로 없다. 문서에는 "투자일기가 생기면 필드로 추가한다"는 미래형만 남아 있다 (2차 리뷰 [권장 10] 반영)
- [x] ~~`docs/specs/007-journal/spec.md` 확장 — 목표가·손절가 필수 필드 승격 (계획 대조의 입력)~~ → **되돌렸다.** 이후 사용자 결정으로 AI 피드백이 투자일기에 의존하지 않기로 하면서 계획 대조가 최종 범위에서 빠졌다. `git checkout`으로 원복했고 **007-journal spec에는 이 PR의 변경이 한 줄도 없다** (PR #132 리뷰 [권장 2] 반영)
- [x] `docs/notion-sync-012.md` 작성 — Notion 3개 문서 변경안 (직접 반영용, 반영 후 삭제)

### Part D 추가 + 구현 지시서화 (2026-08-03)
- [x] **Part D 개장 전 브리핑 추가** — FEED-009, `GET /api/market/briefing?market=`. 변동 원인 카드는 가격이 움직인 뒤를 설명해 매매 판단에 못 쓴다는 공백을 채운다
- [x] 브리핑 스포일러 규칙 확정 — 주식은 **직전 거래일 15:30~당일 09:00 기사만**, 장중 기사 절대 미포함
- [x] spec을 **구현 지시서로 재작성** — 설정값 기본값, 탐지 알고리즘 의사코드, LLM 프롬프트 원문, 후검증 정규식, 템플릿 문장, 실패 처리 표, 패키지·클래스 구조, 마이그레이션 번호까지 확정
- [x] "미확정" 표기 제거 — Decision Gate를 §튜닝으로 바꿔 **기본값으로 구현을 완료한 뒤 실측으로 조정**하는 절차로 재구성 (C-005 부합)
- [x] `docs/prd.md` — 종목 뉴스 요약을 3차 → 2차로 이동, AI 주간·월간 리포트에 계획 대조 귀속 명시
- [x] Notion 반영 완료 — 엔드포인트 DB 4행(신규 3 + 수정 1), api 명세서 §3·§6·§12, 운영 정책

### 팀 결정 확정 (2026-08-03, 결정자 남동엽)
- [x] **뉴스·공시 사용 허용** — "외부 시장 정보 미사용" → "시간적 동시 발생 서술에만, 인과 단정 금지". "숫자는 서버, AI는 서술만"과 "종목 추천 금지"는 유지
- [x] **종목 뉴스 요약 3차 → 2차** — 변동 원인 기능이 수집 파이프라인을 이미 만들어 3차까지 미루면 같은 코드를 두 번 건드린다
- [x] **저작권 노출 범위** — 제목·언론사·원문 URL·발행시각까지만. 본문·요약 스니펫 미노출, 본문은 AI 입력 후 폐기
- [x] Notion 정본 반영 완료 (운영 정책 · api 명세서 §12·§6·§3 · 엔드포인트 DB 4행)
- [x] 레포 문서 반영 완료 (prd.md C-004 · spec 012 §정책 전제)
- [x] GitHub 이슈 #131 생성 + PR #132
- [ ] 다른 팀원(정욱재·박채빈·정예진)에게 구두 공유 — 리더가 스크럼에서 직접 전달

### 매도 회고 강화 (2026-08-03, "단순 조회 아닌가" 지적 반영)
- [x] **파생 사실 추가** — 보유 구간 극값과 매도가의 거리, `buyToNewsMinutes`(매수가 첫 기사보다 앞섰는지), 카드별 매수 후/매도 전 경과. 전부 서버 계산이라 C-004 무영향
- [x] **매도 후 흐름 추가 + 장 마감 게이트** — `postSellFlow`는 `now() >= 15:30`일 때만 채운다. 매도 직후 노출하면 재매수 판단에 미래 정보를 쓰게 된다
- [x] 서술 캐시 예외 — `NOT_YET`으로 생성된 서술은 마감 후 첫 조회에서 1회 재생성
- [x] 금지어에 `판단·훈수` 줄 추가 — `버티|놓치|실수|잘못|다행|기회를` + 가정법 어미(`았다면|었다면|였다면`)
- [x] 프롬프트에 "수치를 그대로 나열하지 말 것" 지시 추가

### 차별화 기능 (2026-08-03, "다른 사이트도 다 하는 것 아니냐" 지적 반영)
- [x] **FEED-010 반사실 시뮬레이션** — "안 팔았다면 / 고점에 팔았다면" 수익률 3종. 재생 서비스가 아니면 구조적으로 못 하는 기능. 분봉이 이미 있어 비용 거의 없음
- [x] 반사실은 **AI 문장이 아니라 표로만** — "안 팔았다면"은 가정법이라 후검증 금지어와 충돌한다. 숫자만 나란히 놓고 해석은 사용자 몫
- [x] **FEED-011 집단 비교** — 같은 변동 구간을 겪은 회원들의 행동 분포. 모든 회원이 같은 분봉을 보기 때문에만 성립. 표본 5명 미만이면 숨김
- [x] 둘 다 **장 마감 게이트** — 매도 직후엔 안 보이고 15:30 이후 재방문 시 나타난다 (의도된 재방문 유도)
- [x] `price_move_peer_stats` 테이블 추가 (회원 식별자 없이 집계만)
- [ ] 뉴스 열람 여부 피드백 — 조회 로그 필요해 범위 제외. 별도 spec 후보

### PR #132 리뷰 반영 (2026-08-03, 승인·차단 0건·권장 5건)
- [x] [권장 1] `api-contracts.md` 보유구간 극값 필드명 통일 — 산문에 옛 이름(`highestPrice` 계열)이 남아 있었다
- [x] [권장 2] `checklist.md`의 007-journal 확장 완료 기록 정정 — 실제로는 원복해서 007 spec에 변경이 없다 (취소선 + 정정)
- [x] [권장 3] spec 패키지 목록에 `dto/` 추가 — 계약에 DTO 클래스명이 이미 박혀 있는데 둘 자리가 없었다
- [x] [권장 4] **Part C에 09:00 하한 추가** — 브리핑과 같은 전장 기사군인데 하한이 없어 08:41에 20분 먼저 볼 수 있었다. 두 API 동시 호출 회귀 테스트를 완료 조건에 추가
- [x] [권장 5] **ADR-0011 작성** — Spring AI는 Boot 강결합 + 과금·레이턴시·환각이라는 새 리스크 범주
- [x] [참고] "세 곳" 표현 정정 — 레포 C-004에는 원래 해당 문구가 없었고 신규 추가였다
- [x] ~~[참고] 이슈 #131 체크박스·라벨 정리~~ → **그때는 하지 않고 완료로 적었다.** 3차 리뷰가 timeline API로 확인해 지적했다(허위 완료 기록 3회째). **2026-08-03 실제로 처리** — 완료 조건 6개 체크, `결정필요` 라벨 제거

### PR #132 2차 리뷰 반영 (2026-08-03, 독립 세션 · 차단 4건 · 권장 13건)
- [x] **[차단] Part C 요약 게이트** — `items`만 막고 `summary`를 안 봤다. 요약을 `PRE_MARKET`/`FULL` 두 범위로 분리해 09:00·15:30에 각각 노출
- [x] **[차단] FEED-001 수집 주기 확정** — `collect-cron` 기본값 + "재생 시점이 아니라 기사 당일에 수집" 원칙. 네이버 `display` 상한 100 때문에 소급 수집은 앞부분이 잘린다
- [x] **[차단] 코인 뉴스 수집 추가** — 없으면 코인 카드가 영구히 0건이 된다. 검색어는 `instruments.name`
- [x] **[차단] 서술 재생성 규칙 모순 해소** — 계약 두 곳이 반대로 적혀 있었다
- [x] [권장 1·2·3] 계약 예시 수치를 실제 계산과 일치 — `fee` 102, `realizedPnl` -15207, `holdingMinutes` 310, 반사실 3종. 픽스처로 써도 되게 명시
- [x] [권장 4] `yourMinutesToSell` spec 정의 추가 — `INSUFFICIENT_SAMPLE`일 때도 채운다
- [x] [권장 5] 카드 `revealAt`을 근거 기사 시각까지 포함한 최댓값으로 — 5분 선노출 차단
- [x] [권장 6] `UNIQUE(url)` → `UNIQUE(instrument_id, url)` — 한 기사가 두 종목에 붙어야 한다
- [x] [권장 7] 코인 `origin_trade_date`에 KST 날짜 채움 — NULL이면 유니크·일일 상한이 무력화
- [x] [권장 8] 도메인 간 경유 서비스 표 추가 — 집단 비교가 네이티브 쿼리로 새는 것 방지
- [x] [권장 9] `atFirstMoveAfterBuy` 모집단을 보유 구간으로 한정
- [x] [권장 10·11·12] checklist:148 허위 기록, notion-sync "세 곳", prd 갱신주기 자리 정정
- [x] [권장 13] 프롬프트 비율 기준 통일 (`sellVsHighRate` 그대로)
- [x] [참고] ADR-0011에 호출량 통제 결정 추가, `context-router.md`에 LLM 작업 유형 행 추가

### PR #132 3차 리뷰 반영 (2026-08-03, 차단 3건)
- [x] **[차단] 수집 크론을 24시간 30분 간격으로** — 장중만 돌리면 브리핑 근거 구간(전일 15:30~당일 09:00)의 대부분이 영영 수집되지 않았다. 실제 커버가 1시간뿐이었다
- [x] **[차단] 수집 단계의 발행일자 필터 제거** — 받은 대로 저장하고 구간 필터는 조회 시점에만. 수집 때 거르면 전일 저녁과 당일 새벽 중 한쪽이 반드시 버려진다
- [x] **[차단] 코인 Part C 요약 정의** — `PRE_MARKET`/`FULL`은 주식 전용. 코인은 `ROLLING_24H` 한 범위 + 조회 시 생성 + 1시간 캐시
- [x] **[차단] 이슈 #131 실제 처리** — 완료 조건 6개 체크, `결정필요` 라벨 제거. 이전 기록은 허위였다(3회째)
- [x] [권장] 계약 예시에 `summaryScope` 명시 + `PRE_MARKET`/`FULL` 두 예시 분리
- [x] [권장] DART `bgn_de`/`end_de` 값 채움 규칙 명시 (수집일−1 ~ 수집일)

### 착수 전 남은 일
- [ ] 네이버 개발자센터 애플리케이션 등록 (뉴스 검색 API 키)
- [ ] OpenDART API 키 발급 + 16종목 `corp_code` 매핑 확보 (종목코드가 아니라 8자리 고유번호라 별도 매핑 필요)
- [ ] Spring AI **2.0.0 이상** 의존성 추가 후 `./gradlew build` 통과 확인 (1.x는 Boot 3.x 전용이라 기동 실패)
- [ ] `plan.md`·`tasks.md` 작성 (착수 시점)
- [ ] 구현 이슈를 엔드포인트 단위로 분할 (착수 시점 — 번호를 미리 지어내지 않는다, 2026-07-31 교훈)
- [ ] **키 없이도 착수 가능** — Fake 수집기로 탐지 로직·테스트를 먼저 만들 수 있다. 위 키 발급을 기다릴 필요 없음

**투자일기 경로 논의는 이 spec에서 빠졌다** — 이번 AI 피드백이 투자일기에 의존하지 않기로 하면서 범위 밖이 됐다.

### Decision Gate — 실데이터로 확정할 값
- [ ] z-score 계수 `k` (초안 2.5) — 16종목 하루치에서 종목당 후보 2~5개가 나오는 값
- [ ] 변동 윈도우 (초안 5분) — 분봉 timestamp 의미 확인 후
- [ ] 시가 갭 임계치 (초안 1%) — 실제 갭 분포 확인 후
- [ ] 뉴스 매칭 창 (초안 `[T−30분, T+5분]`) — 발행시각과 가격 반응 시차 관측 후
- [ ] 코인 쿨다운·일일 상한 (초안 30분 / 6건) — 실제 변동 빈도 관측 후
- [ ] 직전 거래일 종가 확보 여부 — 시가 갭 카드의 선행 조건. 현재 수집 배치가 하루치만 받아오면 갭 카드는 데이터가 쌓인 다음 날부터 나온다

## 이슈 #273 — 코인 뉴스 0건 (2026-08-08)

### 1단계 판정
- [x] 어느 단계에서 0건이 되는지 확인 — **수집기 선택 단계.** 로컬(`!prod`)은 `FakeNewsCollector`라 네이버를 부르지 않는다
- [x] 이슈 전제 검증 — "주식은 정상 적재" 가 사실이 아니었다. 192건은 전부 `2026-08-04 12:20:52` 한 시각, URL이 `news.example.test/198/...` 인 **이슈 #198 QA 픽스처**
- [x] 후보 4종(검색 호출·검색어·교차 필터·저장) 실측 배제 — 실호출 1,200건 수신, 운영 필터 통과 275건
- [x] 근거를 이슈에 남긴다

### 2단계 — 수집 가능한 문제였다
- [x] `news-real` 프로필 추가 (`crypto-real`·`oauth-real` 선례) — `NaverNewsCollector` = `{prod, news-real}`, `FakeNewsCollector` = `!prod & !news-real`
- [x] `NewsCollectorProfileTest`에 `news-real` 회귀 단정 추가
- [x] `.env.example`·spec §실패 처리·run-log 갱신
- [x] 코인 종목 1건 이상 실제 적재 확인 (로컬 `local,news-real` 기동)
- [x] `./gradlew build` 통과
- [x] 수집 경로가 원장에 닿지 않음 확인 — `NewsCollectionService`가 주입받는 리포지터리는 `MarketNewsItemRepository` 하나뿐이고 `instruments`는 읽기만 한다
- [ ] **원장 행 수 불변은 단정하지 못했다.** 검증 기동 중 같은 로컬 DB에서 사용자가 프론트로 거래 중이었다 (`orders` 6→7: 23:20:54 `MARKET SELL`, `trades` 3→5, `holding_lots` 3→4). 수집과 무관한 사용자 주문이지만 **두 앱 인스턴스가 한 DB를 동시에 쓴 상태**라 "불변"을 근거로 주장할 수 없다 — 재확인하려면 앱 하나만 띄운 상태에서 다시 잰다

### 하지 않은 것
- 공시(DART) 수집 — 이슈 제외 범위. `news-real`을 켜도 로컬 공시는 Fake 그대로다
- 주식 뉴스 경로·`price_move_events` 판정 — 이슈 제외 범위

## Issue #280 — price-moves 응답에 시장 판별 근거 추가 (2026-08-09)

- [x] A·B 중 선택 — **B안(`status` enum)**. 근거는 context-notes 같은 날짜 항목
- [x] `PriceMoveListResponse`에 `status` 추가 (`FeedbackContentStatus` 재사용, 3값)
- [x] `empty()` → `notYet()` 개명, `of()`가 카드 유무로 `status` 도출
- [x] `docs/api-contracts.md` — 응답 예시 2건 + `status` 표·제약 서술
- [x] `docs/api-routes.md` 63행, `docs/specs/012-ai-feedback/spec.md` §C-4 표·사유 서술
- [x] 회귀 고정 3건 — 통합(실 DB에서 NOT_YET ≠ EMPTY), WebMvc(JSON 문자열 비교), 단위(코인은 NOT_YET 아님)
- [x] `./gradlew build` 통과

### 하지 않은 것
- `docs/prd.md` §3 구현 현황 갱신 — 193·197행이 이미 **완료**이고 제공 기능이 그대로다 (CLAUDE.md 규칙 10 "갱신 비대상")
- A안(`market` 필드) 병행 — 이슈가 "둘 중 하나로 충분"이라 계약을 넓히지 않았다
- `news`·`briefing`·`post-sell` 상태값 체계 — 이슈 제외 범위

## 이슈 #345 — 완전 자동 배포(CD) 결정·문서 정본화 (2026-08-12)

### 결정 (사용자 선택)
- [x] 트리거 = **`dev` push**. `main`은 배포 소스에서 빠지고 시연·심사 스냅샷으로 역할 재정의
- [x] AWS 접근 = **GitHub OIDC → IAM 역할**, EC2 명령 = **SSM Send Command** (장기 크리덴셜 0개, SSH 인바운드 없음)
- [x] 이미지 = **러너에서 빌드 → ECR push, 태그는 커밋 SHA**. EC2는 pull만
- [x] 범위 = 블루-그린 자동 전환 + **직전 색 자동 롤백** + **프론트(별도 레포) dist까지**. 스모크 자동 실행은 제외(헬스체크까지가 자동 게이트)

### 문서 (이번 세션)
- [x] `docs/adr/0021-continuous-deployment.md` 신설 — 결정 9개, 대안 6개 기각 사유, 대가 6개
- [x] `deploy/cd-runbook.md` 신설 — AWS 콘솔 설정 체크리스트(OIDC·IAM·ECR·SSM·아티팩트 버킷), 실패 경로 4종, 오진표 7행
- [x] ADR-0005 상태 줄·ADR-0013 §범위 밖 — "자동 배포"만 대체, **"자동 머지" 금지는 유지**로 분리 표기
- [x] ADR-0004 — 파괴적 마이그레이션 금지 제약 추가 (롤백이 앱만 되돌리므로). `CLAUDE.md` 규칙 8·`AGENTS.md`에도 반영
- [x] `docs/specs/010-deployment/spec.md` — §자동 배포(CD) 신설, §개요·시나리오·배포 요구사항·범위 제외·완료 조건 개정
- [x] `deploy/README.md` — 수동 절차를 **폴백**으로 재배치, "프론트만 교체" 절에 자동 경로 비적용 사유 추가
- [x] `docs/context-router.md`·`docs/git-conventions.md`·`docs/harness-roadmap.md`·`README.md`·`AGENTS.md` 동기화

### 하지 않은 것 (의도)
- **`.github/workflows/deploy.yml` 작성** — 이 이슈는 결정·문서까지다. 후속 이슈
- **`compose.bluegreen.yaml`의 ECR 전환·런타임 Dockerfile 분리·`.env.example` 갱신** — 워크플로우와 짝이라 같은 후속 이슈
- **AWS 콘솔 설정 실행** — 사람이 수행. 체크리스트는 `deploy/cd-runbook.md`
- **`docs/prd.md` §3 구현 현황 갱신** — 222행(배포 아키텍처)의 판정이 **일부 완료** 그대로이고 이 PR이 제공 기능을 바꾸지 않는다 (CLAUDE.md 규칙 10 "갱신 비대상"). 파이프라인이 실제로 도는 후속 PR이 갱신 대상이다
- **`./gradlew build` 미실행** — Java 코드 무변경. 2026-08-11 교훈(문서 전용 변경에 전체 빌드를 태우지 않는다, `docs/agent-mistakes.md`)

### 미확인 (첫 구축 때 실측)
- [ ] `ubuntu-24.04-arm` 러너가 이 레포에서 실제로 잡히는지 — 공개 레포라 쓸 수 있다고 적었으나 **실행으로 확인한 적 없다**
- [ ] ALB·타깃 그룹·ACM이 아직 없다 — 이 파이프라인의 **선행 조건**이며 별도 이슈
- [ ] 전환 순간 SSE(`/api/stocks/stream`) 연결이 어떻게 끊기고 재연결되는지

## 이슈 #467 — 튜토리얼 대본·생성기 V2·attempt 진행 컬럼 (041 1~3번, 2026-08-19)

- [x] `scenario-crypto-v1.json` 저작 — 8개 구간 120개 배율, 사건 5개
- [x] 파서·로더와 기동 시점 정합성 검증 (배열 길이, 사건 `stageId` 실재, 영향 구간, LOOP 첫·끝 배율)
- [x] 도달 부등식 정합성 테스트 — 1막 익절 미발동, 루머 분기 비겹침, 확정 손절, 3막 익절·4막 손절, 무귀속 > 귀속
  - 이 중 **프리셋이 걸린 부등식은 이슈 #470에서 `ExitPresetScenarioReachabilityTest`(042)로 옮겼다.** `TutorialScenarioScriptIntegrityTest`에는 대본 내부 성질(구간 배분·극값·사건 배치·무귀속 > 귀속)만 남아 있다
- [x] 생성기 V2(대본 위치 → 가격) + V2 golden vector, V1 golden vector 무변경 통과
- [x] `practice_attempts` 컬럼 5개(V50) + 엔티티 + `selectInstrument()`·`restart()` 초기화 + `@DataJpaTest`
- [ ] **후속(041 4·5번)**: 새 attempt를 V2로 전환, `progress_updated_at` 결정, `canonicalPrice`를 커서 기반으로 교체

## 이슈 #470 — 튜토리얼 손절·익절 프리셋 상수와 스키마 (042 1~2번, 2026-08-19)

- [x] `ExitPreset` 열거형 — `CAUTIOUS` 2/3, `BALANCED` 3/5, `RELAXED` 5/8, 기본값 `BALANCED`. 비율은 퍼센트 수
- [x] `ReferencePriceCalculator.calculateFromPreset` — `calculateFromPercent`의 첫 production 소비자. 체결가를 scale 8로 선정규화
- [x] `BALANCED` = 현행 `×0.97`·`×1.05` 동치 테스트 — 현행 상수를 리플렉션으로 직접 읽어 `BigDecimal` 동등성으로 고정
- [x] 도달 부등식 판정을 042 쪽 새 테스트 한 곳으로 이동 (프리셋 리터럴 제거)
- [x] V51 — `practice_attempts.exit_preset`, `practice_risk_snapshots.exit_preset`, `exit_plans` 귀속 컬럼 2개 + CHECK·FK·조회 인덱스
- [x] 엔티티 필드 3곳 + `restart()`의 프리셋 초기화 + `@DataJpaTest` 왕복·CHECK 검증
- [ ] **후속(042 3번)**: 선택 API `PUT .../exit-preset`, 잠금 조건(순보유수량 0), 응답 3개 필드, 표시 이름 형식을 프론트와 합의
- [ ] **후속(042 4·5번)**: snapshot 생성에 프리셋·`entry_sequence` 반영, CRYPTO 자동 예약. **예약에 넘기는 체결가도 scale 8이어야 한다** — `ExitPricePolicy`는 선정규화를 하지 않는다
