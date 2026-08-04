# Context Router — 작업 유형별 읽을 문서

AI 에이전트는 **docs/ 전체를 순회하지 않는다.** 작업 유형에 맞는 행의 문서만 읽는다. (컨텍스트 낭비 방지 — 이전 프로젝트 하네스에서 이식한 규칙)

| 작업 유형 | 반드시 읽을 문서 |
|---|---|
| spec 작성 / 차수 범위 판단 | `docs/prd.md` (요구사항 ID·수용 기준·제외 범위 + §3 구현 현황) + `docs/specs/README.md` |
| 기능 구현 | 해당 `docs/specs/NNN-*/` (spec, plan, tasks) + `docs/conventions.md` + `docs/adr/0002-architecture.md` |
| LLM·AI 기능 구현 | 위 목록 + `docs/adr/0011-llm-provider-integration.md` (프로바이더 추상화, 실패 시 템플릿 폴백, Fake 테스트 방침) |
| 엔티티/스키마 변경 | 위 + `docs/adr/0004-flyway-migrations.md` |
| 테스트 작성 | `docs/adr/0003-testing-strategy.md` |
| 코드 리뷰 | `docs/conventions.md`(리뷰 체크 질문 포함) + `docs/adr/0002-architecture.md` + `docs/adr/0003-testing-strategy.md` + `docs/adr/0004-flyway-migrations.md` + `docs/api-routes.md` + `docs/api-contracts.md` |
| 블랙박스 QA | 해당 spec의 `spec.md` + `docs/api-contracts.md` — **구현 코드(src/main) 금지** |
| API 문서 갱신 | `docs/api-routes.md`(라우트 목록) + `docs/api-contracts.md`(계약 상세) — 둘을 같은 커밋에서 갱신 |
| 브랜치 생성 / 커밋 / PR 작성 | `docs/git-conventions.md` |
| 이슈 분할 / 리뷰 지적 처리 | `docs/team-conventions.md` |
| 하네스/문서 수정 | `AGENTS.md` + `CLAUDE.md` + 이 파일 + `docs/adr/0005-local-agent-orchestration.md` + `docs/adr/0008-four-agent-roster.md` + `docs/adr/0009-codex-local-orchestration.md` + `docs/adr/0010-agent-session-lifecycle.md` |
| 배포 / CI 구성 / 스모크 | `docs/specs/010-deployment/spec.md` + `docs/conventions.md`(시크릿 절) |
| 배포 스택 실행 (수동 배포 절차) | `deploy/README.md` (+ `compose.deploy.yaml`·`deploy/nginx.conf`·`Dockerfile`) |
| 하네스 CI 전환 (미착수) | `docs/harness-roadmap.md` + `docs/adr/0005-local-agent-orchestration.md` |
| 병렬 작업 / 팀 구성 | `docs/parallel-agents.md` |
| 과거 실수 확인 | `docs/agent-mistakes.md` (구현 시작 전 1회) |

## 규칙

- 여기 없는 문서(architecture 상세, 과거 spec 등)는 필요해진 시점에 grep으로 찾아 해당 부분만 읽는다.
- 새 정본 문서를 추가하면 이 표에도 행을 추가한다. 같은 규칙을 두 문서에 복제하지 않는다 — 정본 하나, 나머지는 링크.
