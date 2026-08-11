# Context Router — 작업 유형별 읽을 문서

AI 에이전트는 **docs/ 전체를 순회하지 않는다.** 작업 유형에 맞는 행의 문서만 읽는다. (컨텍스트 낭비 방지 — 이전 프로젝트 하네스에서 이식한 규칙)

| 작업 유형 | 반드시 읽을 문서 |
|---|---|
| spec 작성 / 차수 범위 판단 | `docs/prd.md` (요구사항 ID·수용 기준·제외 범위 + §3 구현 현황) + `docs/specs/README.md` |
| 기능 구현 | 해당 `docs/specs/NNN-*/` (spec, plan, tasks) + `docs/conventions.md` + `docs/adr/0002-architecture.md`. **요구사항 ID의 구현 상태가 바뀌면 `docs/prd.md` §3 "구현 현황" 행도 같은 커밋에서 갱신한다** (CLAUDE.md 규칙 10) — PRD 전체가 아니라 그 절만 읽으면 된다 |
| LLM·AI 기능 구현 | 위 목록 + `docs/adr/0011-llm-provider-integration.md` (프로바이더 추상화, 실패 시 템플릿 폴백, Fake 테스트 방침) |
| 투자 실습 튜토리얼 (education·practice·즐겨찾기·OCO) | **`docs/specs/026-market-order-practice-tutorial`을 먼저 읽는다** — holding 기반 완료 경로·진행 조회 정본. 코인 가상 가격 세션·교육 지정가·관찰 가격원은 **`030-coin-practice-price-runtime`**이 부분 대체 정본이다. 그다음 필요에 따라: `016-investment-education-policy`(3단계 모델·1단계·사전 의도, OCO는 3차), `020-coin-practice-tutorial`(코인 OCO delta·key), `019-exit-price-policy`, `021-general-risk-management-oco`. **+ ADR-0012 필수** — 즐겨찾기·사전 의도만 인메모리이며 030 가격 세션은 DB 영속이다 |
| 엔티티/스키마 변경 | 위 + `docs/adr/0004-flyway-migrations.md` |
| 테스트 작성 | `docs/adr/0003-testing-strategy.md` |
| 코드 리뷰 | `docs/conventions.md`(리뷰 체크 질문 포함) + `docs/adr/0002-architecture.md` + `docs/adr/0003-testing-strategy.md` + `docs/adr/0004-flyway-migrations.md` + `docs/api-routes.md` + `docs/api-contracts.md`. 새 엔드포인트·요구사항 완료가 있으면 `docs/prd.md` §3 갱신 여부도 본다 (CLAUDE.md 규칙 10) |
| 블랙박스 QA | 해당 spec의 `spec.md` + `docs/api-contracts.md` — **구현 코드(src/main) 금지**. 계약 절 제목의 "(계획)" 표시는 controller가 없다는 뜻이니 실행 근거로 쓰지 않는다 |
| API 문서 갱신 | `docs/api-routes.md`(라우트 목록) + `docs/api-contracts.md`(계약 상세) — 둘을 같은 커밋에서 갱신 |
| 브랜치 생성 / 커밋 / PR 작성 | `docs/git-conventions.md` |
| 이슈 분할 / 리뷰 지적 처리 | `docs/team-conventions.md` |
| 하네스/문서 수정 | `AGENTS.md` + `CLAUDE.md` + 이 파일 + `docs/adr/0005-local-agent-orchestration.md` + `docs/adr/0008-four-agent-roster.md` + `docs/adr/0009-codex-local-orchestration.md` + `docs/adr/0010-agent-session-lifecycle.md` |
| 배포 / CI 구성 / 스모크 | `docs/specs/010-deployment/spec.md` + **`docs/adr/0020-managed-service-deployment.md`**(배포 아키텍처 결정 정본 — EC2 + RDS·ElastiCache·S3 + 블루-그린. spec과 판단이 갈리면 ADR이 정본) + `docs/conventions.md`(시크릿 절) |
| 배포 스택 실행 (수동 배포 절차) | `deploy/README.md` (+ `compose.deploy.yaml`·`deploy/nginx.conf`·`Dockerfile`). **DB·캐시는 이 스택 안에 없다** — RDS·ElastiCache이며 근거는 ADR-0020 |
| 하네스 CI 전환 (미착수) | `docs/harness-roadmap.md` + `docs/adr/0005-local-agent-orchestration.md` |
| 병렬 작업 / 팀 구성 | `docs/parallel-agents.md` |
| 과거 실수 확인 | `docs/agent-mistakes.md` (구현 시작 전 1회) |

## 규칙

- 여기 없는 문서(architecture 상세, 과거 spec 등)는 필요해진 시점에 grep으로 찾아 해당 부분만 읽는다.
- 새 정본 문서를 추가하면 이 표에도 행을 추가한다. 같은 규칙을 두 문서에 복제하지 않는다 — 정본 하나, 나머지는 링크.
- **한 카테고리가 spec 여러 개로 갈라지면 그 행에 읽는 순서와 각 문서가 소유한 범위를 함께 적는다.** 019·020·021·026이 이 표에 등재되지 않은 채 늘어나면서 튜토리얼의 정본이 어디인지 아무도 판별할 수 없게 됐던 사례가 있다(이슈 #308). 정본이 다른 문서로 이관되면 **원 문서에도 그 사실을 적는다** — 편도 참조는 옛 문서를 읽는 사람을 그대로 오도한다.
