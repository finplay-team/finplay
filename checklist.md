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

## 2차 AI 피드백 설계 확정 (2026-08-02, spec 012)

### 문서 (이번 세션 완료)
- [x] `docs/specs/012-ai-feedback/spec.md` 작성 — Part A(변동 원인) + Part B(매도 회고), FEED-001~007
- [x] `docs/prd.md` C-004 개정 — "외부 시장 정보 미사용" → "시간적 동시 발생 서술에만 사용, 인과 단정 금지"
- [x] `docs/prd.md` 2차 MVP 범위에 spec 012 링크, 뉴스 출처·저작권 게이트를 3차 → 2차로 이동
- [x] `docs/api-routes.md` "2차 계획 라우트" 절 신설 — 구현된 라우트 표와 분리(controller 미존재)
- [x] `docs/api-contracts.md` `## feedback (2차 계획)` 절 — 두 엔드포인트 계약 초안
- [x] `context-notes.md` 결정 기록

### 기존 Notion 명세 반영 (2026-08-02 2차 수정)
- [x] Notion `api 명세서` §6에 AI 복기 엔드포인트 6개가 이미 `명세 작성 현황=완료`로 등록돼 있음을 확인 — 담당자는 전부 비어 있다
- [x] 매도 회고 경로를 신규 `/api/trades/{id}/feedback` → 기존 명세의 **`/api/ai/post-sell/{tradeId}`**로 정렬
- [x] 계획 대조(`planOutcome` 5값) 요구사항 추가 — 서버 판정, `STOP_LOSS_AVOIDED` 최우선
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
- [x] [참고] 이슈 #131 체크박스·라벨 정리 — 문서 주장과 이슈 상태가 어긋나 리뷰어가 검증할 수 없었다

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
