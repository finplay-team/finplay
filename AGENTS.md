# AGENTS.md — finplay-api

FinPlay 백엔드 API 서버. Spring Boot 4.1 / Java 17 / Gradle(`build.gradle`, Groovy DSL) / MySQL.
프론트엔드 `FinPlay`은 별도 저장소다.

## 시작과 문서 라우팅

- 작업 전 `git status --short --branch`로 브랜치와 기존 변경을 확인한다.
- `docs/context-router.md`에서 작업 유형에 해당하는 문서만 읽는다. `docs/` 전체 순회는 금지한다.
- 기능 요청에 spec이 없으면 구현하지 말고 spec 작성부터 제안한다.
- 구현 시작 전 `docs/agent-mistakes.md`를 읽고, 재현·확인된 하네스/빌드 실수는 같은 파일에 기록한다.
- 기존 ADR과 충돌하는 구현은 중단하고 새 ADR 초안을 제안한다. 기존 ADR은 수정하지 않는다.

## 구현 규칙

- 한 번에 하나의 Issue 또는 명시된 작업 범위만 처리한다.
- 기존 사용자 변경과 범위 밖 파일을 수정·삭제·되돌리지 않는다.
- 아키텍처와 컨벤션은 `docs/adr/0002-architecture.md`, `docs/conventions.md`를 따른다.
- 테스트 수준은 `docs/adr/0003-testing-strategy.md`를 따른다. Mock 성공을 실제 DB·외부 API 검증으로 표현하지 않는다.
- 엔티티/스키마 변경은 `docs/adr/0004-flyway-migrations.md`에 따라 새 Flyway 마이그레이션을 추가한다. 머지된 마이그레이션은 수정하지 않는다.
- 새 Java 소스 파일 첫 줄에는 파일 역할을 설명하는 한 줄 한국어 주석을 둔다.
- controller를 추가·변경하면 `docs/api-routes.md`를 같은 작업에서 동기화한다.

## 명령과 완료 기준

- Windows: `.\gradlew.bat`; POSIX: `./gradlew`.
- 빠른 컴파일: `gradlew compileJava`
- 대상 테스트: `gradlew test --tests "<패턴>"`
- 포맷: `gradlew spotlessApply`
- 전체 게이트: `gradlew build`
- 완료를 주장하기 전에 현재 작업에서 검증을 새로 실행하고 명령과 결과를 보고한다.
- 실행하지 못한 검증과 남은 위험은 통과한 검증과 구분한다.

## Codex 에이전트 워크플로

- spec 단위 기능 개발은 `feature` 스킬을 사용한다.
- `feature` 스킬을 선택한 작업에서는 해당 스킬을 유일한 오케스트레이션 하네스로 사용한다. 범용 brainstorming, writing-plans, executing-plans, subagent-driven-development, task별 code-review 하네스를 중첩하거나 `.superpowers/sdd` 산출물을 만들지 않는다.
- 범용 스킬의 기법이 필요해도 `feature`의 planner → implementer → tester → build → reviewer → 문서 동기화 단계 안에서만 적용하며, 별도 세션·ledger·review package를 추가하지 않는다.
- PR 검토·게시 요청은 `review-pr` 스킬을 사용한다.
- 파일 1~2개 규모의 버그 수정·설정·문서 작업은 메인 에이전트가 직접 처리할 수 있다.
- 역할은 `.codex/agents/`의 planner, implementer, tester, reviewer를 사용한다.
- production 코드 작성자는 작업 항목마다 implementer 한 명으로 제한한다.
- planner·tester·reviewer는 역할 파일에 허용된 범위만 수정한다.
- 서브에이전트 완료 보고만 신뢰하지 않는다. 메인 에이전트가 diff와 검증 결과를 다시 확인한다.
- 독립 spec 병렬 처리와 파일 소유권은 `docs/parallel-agents.md`를 따른다.
- 모델·승인 정책·인증은 저장소에 고정하지 않는다. 각자 `~/.codex/config.toml`에서 모델과 승인 모드를 설정한 뒤 실행한다 (ADR-0009).
- 서브에이전트 세션 생명주기(implementer/tester 재사용·재개, reviewer 신규, 전환 조건)는 ADR-0010을 따른다. 실행 방법은 `feature` 스킬에 있다.

## 브랜치와 리뷰

- 기능 브랜치는 `dev`에서 만들고 PR 대상도 `dev`로 한다.
- `main`은 배포·시연용이며 직접 푸시하지 않는다.
- 리뷰 지적 중 현재 범위의 수정은 같은 브랜치에서 처리하고, 범위 밖 문제는 후속 Issue 후보로 분리한다.
- 자동 승인·자동 머지는 하지 않는다.
