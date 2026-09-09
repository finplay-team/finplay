# CLAUDE.md — finplay-api

FinPlay 백엔드 API 서버. Spring Boot 4.1 / Java 17 / Gradle (`build.gradle`, Groovy DSL) / MySQL.
프론트엔드: `FinPlay` (Vite + React + TypeScript, 별도 레포).

## 명령어

```bash
./gradlew build          # 전체 빌드 + 테스트 + 포맷검사 + 커버리지 (Docker 필요)
./gradlew test           # 테스트만
./gradlew bootRun        # 로컬 실행 (compose.yaml의 MySQL 자동 기동, 포트 13307)
./gradlew compileJava    # 컴파일만 빠르게 확인
./gradlew spotlessApply  # 코드 포맷 (커밋 전 필수)
```

## AI가 반드시 지켜야 하는 규칙

1. **코드 작성 전 `ai/context-router.md`에서 해당 작업 유형의 문서만 읽는다.** docs/·ai/ 전체 순회 금지. spec이 없는 기능 요청을 받으면 spec부터 작성 제안한다.
2. **ADR 위반 금지.** 기존 ADR과 어긋나는 구현이 필요하면 구현하지 말고 새 ADR 초안을 제안한다. ADR은 수정하지 않고 새 번호로 대체(superseded)한다.
3. **테스트 전략 준수.** `ai/adr/0003-testing-strategy.md` 기준. 서비스 로직은 단위 테스트, Repository 쿼리는 `@DataJpaTest`, API 계약은 `@WebMvcTest`, 핵심 시나리오는 Testcontainers 통합 테스트. mock만으로 검증을 끝내지 않는다.
4. **완료 선언 전 검증을 돌린다.** 원칙은 `./gradlew build`이고 실패하면 고치고 재실행한다. **단, 이 머신에서는 전체 `build`를 습관적으로 돌리지 않는다** — Bash 도구는 Testcontainers가 Docker 소켓을 못 찾아 전량 실패하고 PowerShell 도구는 전체 스위트에서 메모리 부족으로 죽는다(재현 3회). 대신 `compileJava compileTestJava spotlessJavaCheck spotbugsMain spotbugsTest`(트리 전체를 보는 태스크라 게이트 3개 중 둘을 그대로 재현한다) + 변경에 영향받는 테스트를 `--tests` 필터로 돌리고, 전체 `build`는 머신이 한가할 때만 시도한다. **대신하지 못하는 것은 커버리지 40% 하나이며 PR의 CI가 본다.** 절차와 근거는 `ai/agent-mistakes.md` §공유 작업 폴더 규칙 5.
5. **컨벤션은 3개 문서로 나뉘어 있다.** 코드는 `docs/conventions/code.md`(레이어 구조, 네이밍, API 응답 포맷, 예외 처리, 주석 작성 원칙, 리뷰 체크 질문), 브랜치·커밋·PR은 `docs/conventions/git.md`, 이슈·리뷰 운영은 `docs/conventions/team.md`를 따른다.
6. **Java 소스에는 주석을 작성·유지하지 않는다.** `//`, 블록, Javadoc 모두 예외 없이 대상이다. 정본은 `docs/conventions/code.md`의 "주석 작성 원칙".
7. **controller를 추가/변경하면 `ai/api-routes.md`(라우트 목록)와 `docs/api/`의 해당 도메인 파일(계약 상세)을 같은 커밋에서 함께 갱신한다.**
8. **스키마 변경은 Flyway 마이그레이션으로만.** 엔티티 변경 시 `db/migration/V{N}__*.sql` 동반 필수, 머지된 마이그레이션 수정 금지 (ADR-0004). **파괴적 변경(컬럼·테이블 삭제, 이름 변경, 타입 축소, NOT NULL 승격)은 한 배포에 담지 않고 두 배포로 나눈다** — 자동 배포의 롤백은 앱만 되돌리고 스키마는 되돌리지 않기 때문이다 (ADR-0021 §결정 7).
9. **구현 시작 전 `ai/agent-mistakes.md`를 읽는다.** 하네스/빌드 관련 실수를 재현·확인하면 같은 파일에 기록한다 (재현된 실수만, 추측 금지).
10. **요구사항 ID의 구현 상태를 바꾸면 `ai/prd.md` §3 "구현 현황" 행을 같은 커밋에서 갱신한다.** 규칙 7이 controller ↔ API 문서를 묶는 것과 같은 취지로, 이 표가 "무엇이 실제로 동작하는가"의 정본이기 때문이다 (PR #204에서 신설).
    - **갱신 대상**: 요구사항 ID(`RANK-002`, `JOUR-005` 등)를 미착수 → 완료로 바꾸는 PR, 새 엔드포인트를 제공하는 PR, 기능을 일부만 구현해 "일부 완료"의 내용이 달라지는 PR. 근거 칸에는 **그 PR 번호를 적는다**(기존 행들이 이미 그 형태다).
    - **갱신 비대상**: 리팩터링, 테스트 추가, 버그 수정, 문서 수정, 마이그레이션 번호 조정처럼 **제공하는 기능이 그대로인 변경.** 모든 PR에 이 부담을 지우면 규칙이 무시된다.
    - 표에 없는 새 기능을 만들면 행을 새로 추가한다. 판정(완료·일부 완료·미착수)과 **근거를 함께** 적는다 — 근거 없는 판정은 다음 사람이 검증할 수 없다.

## 에이전트 워크플로우 (ADR-0005)

- **기능 개발**: `/feature ai/specs/NNN-이름` — (spec 없으면 planner) → 항목별 implementer → tester 루프 → 마무리에 빌드 + reviewer 리뷰 1회. QA는 /feature에서 하지 않는다.
- **PR 리뷰**: `/review-pr <번호>` — reviewer(리뷰/QA 모드) + tester(빌드 검증) 병렬 투입 후 `gh pr review`로 게시. QA는 여기서 1회 수행.
- 메인 세션은 오케스트레이터 역할이 기본이다. 위 스킬이 적용 가능한 작업이면 직접 구현하지 말고 스킬 경로를 따른다.
- **경량 경로**: spec 단위 기능만 /feature를 탄다. 파일 1~2개 규모의 버그픽스·설정·문서 작업은 메인 세션이 직접 구현한다 (`./gradlew build` 통과 의무는 동일).
- 서브에이전트 4개 (ADR-0008): planner(계획·문서 동기화) / implementer(구현) / tester(테스트 작성·실행) / reviewer(리뷰·블랙박스 QA, 모드 분리) — 정의는 `.claude/agents/`. 팀장은 메인 세션(오케스트레이터)이다.
- 병렬 작업(독립 spec 동시 진행, 에이전트 팀)은 `ai/parallel-agents.md`를 따른다. /feature 루프 내부는 순차 유지.
- 서브에이전트 세션 생명주기(implementer/tester 재사용·동일 세션 재개, reviewer 신규, 전환 조건)는 ADR-0010을 따른다. 실행 방법은 `/feature` 스킬에 있다.

### 범용 에이전트 스킬 사용 제한

- 기능 개발과 PR 리뷰는 프로젝트 전용 `/feature`, `/review-pr` 워크플로를 우선한다. 동일한 계획·구현·테스트·리뷰·완료 절차를 제공하는 범용 워크플로를 중복 실행하지 않는다.
- `brainstorming`은 기존 PRD·spec·ADR만으로 결정할 수 없는 요구사항, 범위 또는 설계 선택이 있을 때만 사용한다. spec이 없어도 요구사항이 명확하면 기존 planner가 계획한다.
- `systematic-debugging`은 실패 원인이 불명확하거나 반복되거나 빌드·환경·트랜잭션이 예상과 다르게 동작할 때만 사용한다. 원인이 명확한 실패는 기존 implementer ↔ tester 루프로 처리한다.
- 범용 스킬을 예외적으로 사용해도 현재 `/feature` 또는 `/review-pr` 안에서 필요한 분석만 수행하며 별도 plan, ledger, task brief, reviewer 체인을 만들지 않는다.

## 아키텍처

- 레이어드: `controller → service → repository`, 도메인별 패키지 (`com.finplay.api.domain.<도메인>`)
- 전역 공통(예외 처리, 공통 응답, 설정 등 특정 도메인에 속하지 않는 것)은 `com.finplay.api.global`에 둔다. 도메인 패키지에 전역 코드를 섞지 않는다.
- 상세: `ai/adr/0002-architecture.md`, `ai/adr/0029-domain-global-package-split.md`

## 문서 지도

`docs/`는 사람이 열어볼 제품 문서, `ai/`는 AI 개발 워크플로 산출물이다. `docs/prd.md`(사람용)와 `ai/prd.md`(AI용)는 이름은 같지만 다른 문서다 — spec 근거·구현 현황 갱신(규칙 10)은 항상 `ai/prd.md`를 본다.

| 문서 | 용도 |
|---|---|
| `ai/context-router.md` | 작업 유형별 읽을 문서 지정 (여기부터 시작) |
| `docs/prd.md` | 사람용 제품 요구사항 문서 |
| `ai/prd.md` | AI용 요구사항 정본 — 요구사항 ID·수용 기준·차수·§3 "구현 현황" (모든 spec의 상위 문서, 규칙 10 갱신 대상) |
| `ai/agent-mistakes.md` | 재현·확인된 AI 실수 로그 |
| `ai/adr/` | 아키텍처 결정 기록 (왜) |
| `ai/specs/` | 기능 명세 spec → plan → tasks (무엇을) |
| `docs/conventions/code.md` | 코드 컨벤션 + 리뷰 체크 질문 |
| `docs/conventions/git.md` | 브랜치 네이밍·커밋 메시지·PR 제목·PR 본문 템플릿·머지 조건 |
| `docs/conventions/team.md` | 이슈→브랜치→PR 흐름, 이슈 분할 기준, 리뷰 지적 처리 |
| `ai/api-routes.md` | API 엔드포인트 지도 (controller와 항상 동기화, AI 라우팅용) |
| `docs/api/` | 도메인별 요청·응답·오류 계약 (블랙박스 QA 근거) |
| `docs/erd.md` | JPA 엔티티·DB 테이블 구조 지도 |
| `context-notes.md` | 세션 간 인수인계용 결정 기록 |
