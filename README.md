# finplay-api

FinPlay 백엔드 API 서버. Spring Boot 4.1 / Java 17 / Gradle (`build.gradle`, Groovy DSL) / MySQL.

> ⚠️ **반드시 영문 경로에 클론하세요.** 한글이 포함된 경로에서는 Gradle 테스트 워커가 클래스패스를 읽지 못해 테스트가 전부 `ClassNotFoundException`으로 실패합니다 (컴파일은 되어서 더 헷갈림).

## 실행

요구사항: JDK 17, Docker Desktop.

```bash
./gradlew bootRun    # compose.yaml의 MySQL 8.4를 자동으로 띄우고 연결함
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- 헬스체크: http://localhost:8080/actuator/health

## 테스트

```bash
./gradlew build      # 전체 (Testcontainers 통합 테스트 포함, Docker 필요)
./gradlew spotlessApply   # 커밋 전 코드 포맷
```

## 문서 지도

| 문서 | 내용 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | Codex 프로젝트 규칙 |
| [`CLAUDE.md`](CLAUDE.md) | Claude Code 프로젝트 규칙 |
| [Notion 10 X TEN](https://app.notion.com/p/10-X-TEN-ae2b1fddfba9830abe9c813974422885) | 제품 범위·API 단계·담당자 정본 |
| [`docs/prd.md`](docs/prd.md) | Notion 결정을 옮긴 1차 MVP 구현 스냅샷 (PRD) |
| [`docs/conventions.md`](docs/conventions.md) | 코드 컨벤션 + 리뷰 체크 질문 |
| [`docs/git-conventions.md`](docs/git-conventions.md) | 브랜치 네이밍·커밋 메시지·PR 제목·머지 조건 |
| [`docs/team-conventions.md`](docs/team-conventions.md) | 이슈→브랜치→PR 흐름, 이슈 분할 기준, 리뷰 지적 처리 |
| [`docs/adr/`](docs/adr/) | 아키텍처 결정 기록 |
| [`docs/specs/`](docs/specs/) | 기능 명세 (spec → plan → tasks) |
| [`docs/api-routes.md`](docs/api-routes.md) | API 엔드포인트 지도 (라우트 목록·인증 규칙) |
| [`docs/api-contracts.md`](docs/api-contracts.md) | 엔드포인트별 요청·응답·오류 계약 |

## 팀 규칙 요약

정본은 [`docs/git-conventions.md`](docs/git-conventions.md)(브랜치·커밋·PR)와 [`docs/team-conventions.md`](docs/team-conventions.md)(이슈·리뷰 운영)다. 아래는 요약이다.

- 브랜치: `dev`가 기본(통합) 브랜치. `dev`에서 분기한 `<타입>/<이슈번호>-<영문-요약>`(이슈번호는 0으로 채우지 않는다 — `feat/16-price-query`) → **dev로 PR** → 리뷰 승인 1명 후 **Merge commit**(2026-08-07 정정 — 팀 실제 관행에 맞춤, `docs/git-conventions.md` 참고). PR 전 로컬 `./gradlew build` 통과는 작성자 의무. **`dev` 머지는 곧 배포 실행이다** — CI(`./gradlew build`)가 통과한 코드만 머지되도록 한다 (ADR-0021).
- `main`은 시연·심사 스냅샷 — 직접 푸시 금지(보호 규칙), `dev`에서 PR로만 머지. **배포되는 브랜치는 `dev`다** — `dev` 머지가 곧 배포 실행이며 그 뒤 사람이 개입하는 단계가 없다 (ADR-0021, 구축 전).
- 커밋/PR 제목은 Conventional Commits (`feat:`, `fix:`, ...).
- 시크릿은 어떤 값도 yml·코드에 커밋 금지 — `.env`(gitignore)로 주입, 목록은 `.env.example` 참조.
- 스키마 변경은 Flyway 마이그레이션으로만 (ADR-0004).
- 기능 개발·PR 리뷰는 Claude Code의 `/feature`·`/review-pr` 또는 Codex의 `feature`·`review-pr` 스킬을 사용 (ADR-0005, ADR-0009).
