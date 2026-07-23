# CLAUDE.md — finplay-api

FinPlay 백엔드 API 서버. Spring Boot 4.1 / Java 17 / Gradle (Kotlin DSL) / MySQL.
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

1. **코드 작성 전 `docs/context-router.md`에서 해당 작업 유형의 문서만 읽는다.** docs/ 전체 순회 금지. spec이 없는 기능 요청을 받으면 spec부터 작성 제안한다.
2. **ADR 위반 금지.** 기존 ADR과 어긋나는 구현이 필요하면 구현하지 말고 새 ADR 초안을 제안한다. ADR은 수정하지 않고 새 번호로 대체(superseded)한다.
3. **테스트 전략 준수.** `docs/adr/0003-testing-strategy.md` 기준. 서비스 로직은 단위 테스트, Repository 쿼리는 `@DataJpaTest`, API 계약은 `@WebMvcTest`, 핵심 시나리오는 Testcontainers 통합 테스트. mock만으로 검증을 끝내지 않는다.
4. **완료 선언 전 `./gradlew build` 실행.** 실패하면 고치고 재실행한다.
5. **컨벤션은 `docs/conventions.md`를 따른다.** 레이어 구조, 네이밍, API 응답 포맷, 예외 처리 방식 포함.
6. **새 소스 파일 첫 줄에 한 줄 한국어 주석**으로 파일 역할을 적는다 (`// 주문 생성/조회를 담당하는 서비스`).
7. **controller를 추가/변경하면 `docs/api-routes.md`를 같은 커밋에서 갱신한다.**
8. **스키마 변경은 Flyway 마이그레이션으로만.** 엔티티 변경 시 `db/migration/V{N}__*.sql` 동반 필수, 머지된 마이그레이션 수정 금지 (ADR-0004).
9. **구현 시작 전 `docs/agent-mistakes.md`를 읽는다.** 하네스/빌드 관련 실수를 재현·확인하면 같은 파일에 기록한다 (재현된 실수만, 추측 금지).

## 에이전트 워크플로우 (ADR-0005)

- **기능 개발**: `/feature docs/specs/NNN-이름` — (spec 없으면 planner) → 항목별 implementer → tester 루프 → 마무리에 빌드 + reviewer 리뷰 1회. QA는 /feature에서 하지 않는다.
- **PR 리뷰**: `/review-pr <번호>` — reviewer(리뷰/QA 모드) + tester(빌드 검증) 병렬 투입 후 `gh pr review`로 게시. QA는 여기서 1회 수행.
- 메인 세션은 오케스트레이터 역할이 기본이다. 위 스킬이 적용 가능한 작업이면 직접 구현하지 말고 스킬 경로를 따른다.
- **경량 경로**: spec 단위 기능만 /feature를 탄다. 파일 1~2개 규모의 버그픽스·설정·문서 작업은 메인 세션이 직접 구현한다 (`./gradlew build` 통과 의무는 동일).
- 서브에이전트 4개 (ADR-0008): planner(계획·문서 동기화) / implementer(구현) / tester(테스트 작성·실행) / reviewer(리뷰·블랙박스 QA, 모드 분리) — 정의는 `.claude/agents/`. 팀장은 메인 세션(오케스트레이터)이다.
- 병렬 작업(독립 spec 동시 진행, 에이전트 팀)은 `docs/parallel-agents.md`를 따른다. /feature 루프 내부는 순차 유지.

## 아키텍처

- 레이어드: `controller → service → repository`, 도메인별 패키지 (`com.finplay.api.<도메인>`)
- 상세: `docs/adr/0002-architecture.md`

## 문서 지도

| 문서 | 용도 |
|---|---|
| `docs/context-router.md` | 작업 유형별 읽을 문서 지정 (여기부터 시작) |
| `docs/prd.md` | 1차 MVP 제품 요구사항 정본 (모든 spec의 상위 문서) |
| `docs/agent-mistakes.md` | 재현·확인된 AI 실수 로그 |
| `docs/adr/` | 아키텍처 결정 기록 (왜) |
| `docs/specs/` | 기능 명세 spec → plan → tasks (무엇을) |
| `docs/conventions.md` | 코드/팀 컨벤션 |
| `docs/api-routes.md` | API 엔드포인트 지도 (controller와 항상 동기화) |
| `checklist.md` | 현재 진행 중인 작업 체크리스트 |
| `context-notes.md` | 세션 간 인수인계용 결정 기록 |
