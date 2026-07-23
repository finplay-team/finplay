# finplay-api

FinPlay 백엔드 API 서버. Spring Boot 4.1 / Java 17 / Gradle (Kotlin DSL) / MySQL.

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
| [`CLAUDE.md`](CLAUDE.md) | AI 에이전트 규칙 |
| [`docs/prd.md`](docs/prd.md) | 1차 MVP 제품 요구사항 정본 (PRD) |
| [`docs/conventions.md`](docs/conventions.md) | 코드/팀 컨벤션 (브랜치, 커밋, 리뷰) |
| [`docs/adr/`](docs/adr/) | 아키텍처 결정 기록 |
| [`docs/specs/`](docs/specs/) | 기능 명세 (spec → plan → tasks) |
| [`docs/api-routes.md`](docs/api-routes.md) | API 엔드포인트 지도 |

## 팀 규칙 요약

- 브랜치: `dev`가 기본(통합) 브랜치. `dev`에서 분기한 `feat/이슈번호-요약` → **dev로 PR** → 리뷰 승인 1명 후 Squash merge. PR 전 로컬 `./gradlew build` 통과는 작성자 의무.
- `main`은 배포·시연용 — 직접 푸시 금지(보호 규칙), `dev`에서 PR로만 머지.
- 커밋/PR 제목은 Conventional Commits (`feat:`, `fix:`, ...).
- 시크릿은 어떤 값도 yml·코드에 커밋 금지 — `.env`(gitignore)로 주입, 목록은 `.env.example` 참조.
- 스키마 변경은 Flyway 마이그레이션으로만 (ADR-0004).
- 기능 개발은 `/feature`, PR 리뷰는 `/review-pr` 스킬 사용 (ADR-0005).
