# AGENTS.md — finplay-api

FinPlay 백엔드 API 서버. Spring Boot 4.1 / Java 17 / Gradle(`build.gradle`, Groovy DSL) / MySQL.
프론트엔드 `FinPlay`은 별도 저장소다.

## 시작과 문서 라우팅

- 작업 전 `git status --short --branch`로 브랜치와 기존 변경을 확인한다.
- `ai/context-router.md`에서 작업 유형에 해당하는 문서만 읽는다. `docs/`·`ai/` 전체 순회는 금지한다.
- 기능 요청에 spec이 없으면 구현하지 말고 spec 작성부터 제안한다.
- 구현 시작 전 `ai/agent-mistakes.md`를 읽고, 재현·확인된 하네스/빌드 실수는 같은 파일에 기록한다.
- 기존 ADR과 충돌하는 구현은 중단하고 새 ADR 초안을 제안한다. 기존 ADR은 수정하지 않는다.

## 구현 규칙

- 한 번에 하나의 Issue 또는 명시된 작업 범위만 처리한다.
- 기존 사용자 변경과 범위 밖 파일을 수정·삭제·되돌리지 않는다.
- 아키텍처와 코드 컨벤션은 `ai/adr/0002-architecture.md`, `docs/conventions/code.md`를 따른다. 브랜치·커밋·PR 형식은 `docs/conventions/git.md`, 이슈·리뷰 운영은 `docs/conventions/team.md`를 따른다.
- 테스트 수준은 `ai/adr/0003-testing-strategy.md`를 따른다. Mock 성공을 실제 DB·외부 API 검증으로 표현하지 않는다.
- 엔티티/스키마 변경은 `ai/adr/0004-flyway-migrations.md`에 따라 새 Flyway 마이그레이션을 추가한다. 머지된 마이그레이션은 수정하지 않는다. **파괴적 변경(컬럼·테이블 삭제, 이름 변경, 타입 축소, NOT NULL 승격)은 한 배포에 담지 않고 두 배포로 나눈다** — 자동 배포의 롤백은 앱만 되돌리고 스키마는 되돌리지 않는다 (ADR-0021 §결정 7).
- 새 Java 소스 파일은 클래스명만으로 책임이 드러나면 첫 줄 역할 주석을 강제하지 않는다. 이름만으로 파악하기 어려운 경우에만 한 줄 한국어 주석으로 보충한다. 주석 작성 전반의 기준(유지·축약·삭제 대상)은 `docs/conventions/code.md`의 "주석 작성 원칙"이 정본이다.
- controller를 추가·변경하면 `ai/api-routes.md`(라우트 목록)와 `docs/api/`의 해당 도메인 파일(계약 상세)을 같은 작업에서 함께 동기화한다.
- 요구사항 ID의 구현 상태를 바꾸면(미착수 → 완료, 새 엔드포인트 제공 등) `ai/prd.md` §3 "구현 현황" 행도 같은 작업에서 갱신하고 근거 칸에 PR 번호를 적는다. 기능 제공 범위가 그대로인 리팩터링·테스트·문서 변경은 대상이 아니다.

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
- PR 검토·게시 요청은 `review-pr` 스킬을 사용한다.
- 파일 1~2개 규모의 버그 수정·설정·문서 작업은 메인 에이전트가 직접 처리할 수 있다.
- 역할은 `.codex/agents/`의 planner, implementer, tester, reviewer를 사용한다.
- production 코드 작성자는 작업 항목마다 implementer 한 명으로 제한한다.
- planner·tester·reviewer는 역할 파일에 허용된 범위만 수정한다.
- 서브에이전트 완료 보고만 신뢰하지 않는다. 메인 에이전트가 diff와 검증 결과를 다시 확인한다.
- 독립 spec 병렬 처리와 파일 소유권은 `ai/parallel-agents.md`를 따른다.
- 모델·승인 정책·인증은 저장소에 고정하지 않는다. 각자 `~/.codex/config.toml`에서 모델과 승인 모드를 설정한 뒤 실행한다 (ADR-0009).
- 서브에이전트 세션 생명주기(implementer/tester 재사용·재개, reviewer 신규, 전환 조건)는 ADR-0010을 따른다. 실행 방법은 `feature` 스킬에 있다.

## 범용 에이전트 스킬 사용 제한

- 기능 개발과 PR 리뷰는 프로젝트 전용 `feature`, `review-pr` 스킬을 정본으로 사용한다.
- 프로젝트 전용 스킬이 적용되는 작업에서는 동일한 계획·구현·테스트·리뷰·완료 절차를 제공하는 범용 워크플로 스킬을 중복 사용하지 않는다.
- `brainstorming`은 기존 PRD·spec·ADR만으로 결정할 수 없는 API 계약, 스키마·트랜잭션 경계, 요구사항 충돌, 범위 또는 설계 선택이 있을 때만 사용한다.
- spec 파일이 없더라도 요구사항이 명확하면 `brainstorming` 없이 프로젝트 planner의 계획 모드를 사용한다.
- `systematic-debugging`은 실패 원인이 불명확하거나 같은 실패가 반복되거나 빌드·환경·트랜잭션이 예상과 다르게 동작할 때만 사용한다.
- 원인이 명확한 테스트 실패와 production 버그는 기존 implementer ↔ tester 수정 루프로 처리한다.
- 범용 스킬을 예외적으로 사용해도 현재 프로젝트 워크플로 안에서 필요한 분석만 수행하며 별도 plan, ledger, task brief, reviewer 체인을 만들지 않는다.
- 그 외 범용 계획·TDD·오케스트레이션·리뷰·완료 스킬은 프로젝트 `feature` 또는 `review-pr`과 중복 호출하지 않는다.

## 브랜치와 리뷰

상세 규칙은 `docs/conventions/git.md`에 있다. 요약은 다음과 같다.

- 기능 브랜치는 `dev`에서 만들고 PR 대상도 `dev`로 한다.
- 브랜치명은 `<타입>/<이슈번호>-<영문-요약>`이며 **이슈번호는 0으로 채우지 않는다** (`feat/16-price-query`). 한국어·개인 이름·날짜를 쓰지 않는다.
- PR 제목은 `<타입>: <한국어 요약> (#이슈번호)`이고 본문에 `Closes #N`과 빌드 검증 SHA를 넣는다.
- `main`은 시연·심사 스냅샷이며 직접 푸시하지 않는다. **배포되는 것은 `dev`다** — `dev` 머지가 곧 배포 실행이다 (ADR-0021).
- 리뷰 지적 중 현재 범위의 수정은 같은 브랜치에서 처리하고, 범위 밖 문제는 후속 Issue 후보로 분리한다.
- 자동 승인·자동 머지는 하지 않는다. **배포만 자동이다** — 머지 버튼은 사람이 누르고, 파이프라인은 그 이후에만 움직인다.
