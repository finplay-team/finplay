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
