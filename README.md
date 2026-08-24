# finplay-api

FinPlay 백엔드 API 서버. FinPlay는 코인과 주식을 가상 자산으로 매매해보는 **교육형 모의투자 플랫폼**이다 — 실제 돈을 넣기 전에 매매 흐름을 경험하고, 결과를 기록·복기하고, 다른 사용자와 성과를 비교한다. 중심 시장은 코인으로 빗썸 실시간 시세를 그대로 따라가며(24시간 거래·지정가·자동 청산), 주식은 실제 과거 거래일 데이터를 모든 사용자에게 같은 순서로 재생한다. 가입 시 코인·주식 계좌가 각 1,000만원 가상 현금과 함께 생성된다.

프론트엔드는 별도 레포 `FinPlay`(Vite + React + TypeScript)다.

> ⚠️ **반드시 영문 경로에 클론하세요.** 한글이 포함된 경로에서는 Gradle 테스트 워커가 클래스패스를 읽지 못해 테스트가 전부 `ClassNotFoundException`으로 실패합니다 (컴파일은 되어서 더 헷갈림).

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| 언어·프레임워크 | Java 17, Spring Boot 4.1 (Gradle, Groovy DSL) |
| 데이터 | MySQL 8.4, Spring Data JPA + QueryDSL, Flyway, Redis 7.4 (랭킹 ZSET·분산 락) |
| 인증 | Spring Security + JJWT — stateless JWT Bearer (Access/Refresh 회전), 카카오·네이버 OAuth |
| AI | Spring AI 2.0 (OpenAI) — 뉴스 요약·시장 브리핑·매도 피드백 서술 생성 |
| API 문서 | springdoc-openapi (Swagger UI) |
| 테스트 | JUnit 5, Mockito, Testcontainers (MySQL 8.4) |
| 품질 게이트 | Spotless (NAVER 자바 스타일), SpotBugs, JaCoCo 라인 커버리지 40% |
| 외부 연동 | 한국투자증권 KIS Open API(주식 시세), 빗썸 WebSocket(코인 시세), 네이버 뉴스 검색, OpenDART 공시, Resend(이메일), AWS S3(커뮤니티 이미지) |

외부 API 키가 없어도 기동·빌드는 성공한다 — 비-prod 프로필에서는 Fake 구현(이메일·뉴스·공시·코인 시세)이 대신 뜨고, 실데이터는 `crypto-real`·`news-real` 프로필로 켠다.

## 실행

요구사항: JDK 17, Docker Desktop.

```bash
./gradlew bootRun    # compose.yaml의 MySQL 8.4(:13307)·Redis 7.4(:16379)를 자동으로 띄우고 연결함
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- 헬스체크: http://localhost:8080/actuator/health

환경변수가 필요한 기능(OAuth 로그인, 실시세, AI 등)은 `.env`를 셸에 주입한 뒤 실행한다. 필요한 변수 목록은 `.env.example`이 정본이고, 실제 값은 gitignore된 `.env`에만 둔다.

```bash
set -a; . ./.env; set +a
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

## 테스트

```bash
./gradlew build           # 전체 (Testcontainers 통합 테스트 포함, Docker 필요)
./gradlew test            # 테스트만
./gradlew spotlessApply   # 커밋 전 코드 포맷 (필수)
```

테스트 전략은 3단계 피라미드다 (ADR-0003). 서비스 로직은 JUnit5+Mockito 단위 테스트, Repository 쿼리는 `@DataJpaTest`, API 계약은 `@WebMvcTest`, 핵심 시나리오는 `@SpringBootTest`+Testcontainers 통합 테스트로 검증한다. H2는 쓰지 않고 슬라이스·통합 모두 Testcontainers MySQL(`mysql:8.4` 고정)을 쓴다. Docker가 없으면 `--tests` 필터로 단위 테스트만 선별 실행할 수 있다.

## 아키텍처

레이어드 아키텍처(`controller → service → repository`)에 도메인 기준 패키지 구조를 쓴다 (ADR-0002, ADR-0029). 도메인 간 참조는 service 레이어로만 하고, 전역 공통(예외 처리·공통 응답·설정·분산 락)은 `com.finplay.api.global`에 둔다.

```
com.finplay.api
├── domain.<도메인>          # 도메인별 controller / service / repository / entity / dto
└── global                  # config · exception · filter · lock
```

| 도메인 | 역할 |
|---|---|
| auth | 이메일 회원가입·로그인·JWT 재발급, 카카오·네이버 OAuth, 비밀번호 재설정 |
| account | 가입 시 STOCK·CRYPTO 초기 계좌 생성, 계좌 요약, 튜토리얼 계좌 |
| market | 종목·캔들(1m/1d/1w/1M) 조회, KIS 시세 수집, 주식 SSE 스트림, 재생 세션 |
| order | 시장가 체결, 코인 지정가(에스크로), OCO 손절·익절 예약, Idempotency-Key 멱등성 |
| portfolio | 보유 종목, FIFO lot 기반 원가·평가금액·미실현손익 계산 |
| journal | 매수·매도 체결에 대한 투자일기·회고 작성과 통합 조회 |
| feedback | AI 피드백 — 뉴스·공시 수집과 요약, 개장 전 브리핑, 변동 원인 카드, 매도 직후 피드백 |
| education | 시장별 3단계 투자 실습·튜토리얼 (시나리오 재생, 관찰·복기, 합성 시세) |
| ranking | 시장별 실현손익 랭킹 (Redis ZSET, 원장 기반 재구성) |
| community | 게시글·댓글·좋아요, 이미지 첨부 (로컬/S3 저장 추상화) |
| watchlist / favorite | 관심목록·종목 즐겨찾기 (favorite는 튜토리얼 전용, 인메모리) |

API는 base URL `/api` 아래 83개 엔드포인트가 구현되어 있고(버저닝 없음), 인증·헬스체크·Swagger를 제외한 모든 요청은 `Authorization: Bearer` 토큰을 요구한다. 전체 라우트 지도는 [`ai/api-routes.md`](ai/api-routes.md), 도메인별 요청·응답·오류 계약은 [`docs/api/`](docs/api/)에 있다. DB 스키마 변경은 Flyway 마이그레이션(`db/migration/V{N}__*.sql`)으로만 한다 (ADR-0004).

## 배포

**`dev` 머지가 곧 배포다** (ADR-0021). `dev`에 push되면 GitHub Actions가 이미지를 빌드해 ECR에 올리고(태그=커밋 SHA), SSM으로 EC2의 유휴 색을 기동한 뒤 헬스체크 통과 시 ALB 리스너 가중치를 전환하는 블루-그린 무중단 배포를 수행한다. 머지 이후 사람이 개입하는 단계는 없다. DB·캐시는 RDS·ElastiCache 관리형 서비스를 쓴다.

## 문서 지도

`docs/`는 사람이 보는 제품 문서, `ai/`는 AI 개발 워크플로 산출물이다.

| 문서 | 내용 |
|---|---|
| [`AGENTS.md`](AGENTS.md) | Codex 프로젝트 규칙 |
| [`CLAUDE.md`](CLAUDE.md) | Claude Code 프로젝트 규칙 |
| [Notion 10 X TEN](https://app.notion.com/p/10-X-TEN-ae2b1fddfba9830abe9c813974422885) | 최초 기획 참고용 (더 이상 정본 아님 — 정리 안 됨) |
| [`docs/prd.md`](docs/prd.md) | 사람용 제품 요구사항 문서 |
| [`ai/prd.md`](ai/prd.md) | AI용 요구사항 정본 — 요구사항 ID·수용 기준·구현 현황 |
| [`docs/conventions/code.md`](docs/conventions/code.md) | 코드 컨벤션 + 리뷰 체크 질문 |
| [`docs/conventions/git.md`](docs/conventions/git.md) | 브랜치 네이밍·커밋 메시지·PR 제목·머지 조건 |
| [`docs/conventions/team.md`](docs/conventions/team.md) | 이슈→브랜치→PR 흐름, 이슈 분할 기준, 리뷰 지적 처리 |
| [`ai/adr/`](ai/adr/) | 아키텍처 결정 기록 (0001~0029) |
| [`ai/specs/`](ai/specs/) | 기능 명세 (spec → plan → tasks) |
| [`ai/api-routes.md`](ai/api-routes.md) | API 엔드포인트 지도 (라우트 목록·인증 규칙, AI 라우팅용) |
| [`docs/api/`](docs/api/) | 도메인별 요청·응답·오류 계약 |
| [`docs/erd.md`](docs/erd.md) | JPA 엔티티·DB 테이블 구조 지도 |
| [`docs/loadtest/`](docs/loadtest/) | 부하 테스트·벤치마크 결과 |

## 팀 규칙 요약

정본은 [`docs/conventions/git.md`](docs/conventions/git.md)(브랜치·커밋·PR)와 [`docs/conventions/team.md`](docs/conventions/team.md)(이슈·리뷰 운영)다. 아래는 요약이다.

- 브랜치: `dev`가 기본(통합) 브랜치. `dev`에서 분기한 `<타입>/<이슈번호>-<영문-요약>`(이슈번호는 0으로 채우지 않는다 — `feat/16-price-query`) → **dev로 PR** → 리뷰 승인 1명 후 **Merge commit**. PR 전 로컬 `./gradlew build` 통과는 작성자 의무. **`dev` 머지는 곧 배포 실행이다** — CI(`./gradlew build`)가 통과한 코드만 머지되도록 한다 (ADR-0021).
- `main`은 시연·심사 스냅샷 — 직접 푸시 금지(보호 규칙), `dev`에서 PR로만 머지.
- 커밋/PR 제목은 Conventional Commits (`feat:`, `fix:`, ...). 한국어 50자 이내, 하나의 커밋 = 하나의 논리적 변경.
- 시크릿은 어떤 값도 yml·코드에 커밋 금지 — `.env`(gitignore)로 주입, 목록은 `.env.example` 참조.
- 스키마 변경은 Flyway 마이그레이션으로만 (ADR-0004). 파괴적 변경은 두 배포로 나눈다 (ADR-0021).

## AI 에이전트 워크플로

기능 개발과 PR 리뷰는 로컬 Claude Code 세션이 오케스트레이터가 되어 서브에이전트 4개(planner·implementer·tester·reviewer, 정의는 `.claude/agents/`)에게 위임한다 (ADR-0005, ADR-0008). 진입점은 두 스킬이다.

- `/feature ai/specs/NNN-이름` — 기능 개발 루프. spec 작성 → 항목별 구현 → 테스트 → 빌드 + 리뷰.
- `/review-pr <번호>` — PR 리뷰. 코드 리뷰와 빌드 검증을 병렬 수행 후 `gh pr review`로 게시. 블랙박스 QA는 여기서 1회.

파일 1~2개 규모의 버그픽스·설정·문서 작업은 스킬 없이 메인 세션이 직접 구현한다. AI 에이전트는 [`ai/context-router.md`](ai/context-router.md)에서 작업 유형에 맞는 문서만 읽고 시작한다.
