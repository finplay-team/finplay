# Spec: 배포와 CI (최소 CI · 수동 배포 · 배포 후 스모크)

> PRD 근거: §3 1차 MVP, §8 태스크 10. 선행: 001-foundation.
> 착수는 009-integration보다 앞선다 — 1주차에 첫 배포를 시작한다.
> plan.md·tasks.md는 착수 직전 작성한다.

## 개요

MVP 기능을 실제 환경에 올려 팀과 시연자가 확인할 수 있게 한다. 배포 전에 최소 CI로 전체 build·테스트를 통과시키고, 배포는 사람이 수동으로 수행하며, 배포 후 담당자가 스모크 스크립트로 기본 동작을 확인한다. 자동 배포는 이번 범위가 아니다.

CI 워크플로우는 2026-07-24 튜터 피드백("CI는 배포 단계에")으로 제거된 상태다. 이 태스크가 그 재도입 시점이다 (`docs/harness-roadmap.md`).

## 사용자 시나리오

- 개발자는 PR을 올리면 CI에서 전체 build·테스트 결과를 확인한다.
- 배포 담당자는 dev 병합 후 수동으로 배포하고 `scripts/smoke.ps1`로 정상 여부를 확인한다.
- 팀원은 배포된 주소로 접속해 기능을 확인한다.

## 요구사항

### CI
- [x] PR 대상 브랜치가 `dev`·`main`일 때 `./gradlew build`(Testcontainers 포함)를 실행한다 (`.github/workflows/ci.yml`, 이슈 #250).
- [x] 문서만 바뀐 PR은 Gradle 단계를 건너뛴다 (`dorny/paths-filter`, 이슈 #250).
- [x] CI 결과는 `gh pr checks`로 확인 가능하며, `/review-pr`의 tester는 build를 재실행하지 않고 이 결과를 판독한다 — `.claude/skills/review-pr/SKILL.md`·`.agents/skills/review-pr/SKILL.md`에 "CI가 있으면 `gh pr checks`로 판독, 없으면 재실행"이 이미 조건부로 작성돼 있었다 (010-deployment 이후 활성화 조건). 이슈 #250으로 CI가 생겨 그 조건이 지금부터 충족된다 — 추가 수정 불필요.
- [x] 시크릿은 워크플로우 파일에 적지 않고 GitHub Secret으로 주입한다 — `./gradlew build`에 필요한 더미 시크릿은 이미 `build.gradle`의 `Test` 태스크가 직접 주입해 워크플로우·GitHub Secret 어느 쪽에도 실제 시크릿이 필요 없다.

### 배포
- [ ] 운영 프로필(`prod`)로 애플리케이션이 기동된다.
- [ ] 시크릿은 전부 환경변수 또는 AWS Secret으로 주입한다 (`conventions.md` 시크릿 규칙).
- [ ] 배포는 사람이 수행한다 — 자동 배포·자동 머지는 하지 않는다 (ADR-0005).

### 동일 오리진 서빙 (이슈 #108 — A안)

같은 EC2에서 nginx가 프론트 정적 파일과 `/api` 프록시를 함께 서빙한다. 프론트와 API가 같은 오리진이므로 백엔드에 CORS 설정을 추가하지 않는다. 결정 근거는 이슈 #108에 있다.

- [x] 앱 이미지(`Dockerfile`)와 배포용 스택(`compose.deploy.yaml`: nginx·app·mysql·redis)을 작성한다.
- [x] nginx가 프론트 빌드 산출물(`npm run build` → `dist/`)을 정적 서빙하고 SPA 폴백(`try_files ... /index.html`)을 적용한다.
- [x] `/api`를 앱 컨테이너로 프록시하며 SSE가 흐르도록 `proxy_buffering off`·`proxy_cache off`·`proxy_read_timeout 3600s`를 설정한다.
- [x] 스모크가 호출하는 `/actuator`·`/v3/api-docs`도 프록시한다 — 프록시하지 않으면 SPA 폴백이 `index.html`을 200으로 돌려줘 스모크가 거짓 통과한다.
- [x] 앱 컨테이너는 호스트 포트를 열지 않고 nginx만 외부에 노출한다.

**배포 시점 체크 (EC2 준비 후 수행 — 설정 파일 작성 단계에서는 검증 불가)**

- [ ] `docker compose -f compose.deploy.yaml up -d --build`로 4개 컨테이너가 모두 기동한다.
- [ ] 브라우저에서 배포 주소에 접속해 프론트가 뜨고, `/api` 호출이 CORS 오류 없이 성공한다.
- [ ] 브라우저 개발자도구 Network에서 `/api/stocks/stream`이 실제로 **매분 push**되는 것을 확인한다 — nginx 버퍼링이 살아 있으면 여기서 드러난다.
- [ ] `/trade` 등 하위 경로에서 새로고침해도 404가 아니라 앱 화면이 뜬다 (SPA 폴백).
- [ ] HTTPS를 붙이기 전이라면 카카오·네이버 OAuth 로그인이 동작하지 않는 것을 전제로 시연 범위를 잡는다 — `prod` 프로필은 `OAUTH_STATE_COOKIE_SECURE=false`를 거부하고(`OAuthStateCookieFactory` fail-fast, 2026-07-31 실측), 브라우저는 `http://`에서 `Secure` 쿠키를 저장하지 않는다. 이슈 #108이 근거로 든 "HTTP 데모 시 Secure를 내린다"는 현재 코드에서 불가능하다.

### 주식 시세 공급자 설정 (PRD C-007·MKT-007)
- [ ] 공개 배포 환경은 `SERVICE_EXPOSURE=PUBLIC` + `STOCK_FEED_PROVIDER=KIS_HISTORICAL`로 기동한다 — **이것이 현재 공개 배포의 기본값이다.**
- [ ] `KIS_PUBLIC_DISPLAY_APPROVED`는 기본 `false`로 둔다.
- [ ] 공개 환경에서 `STOCK_FEED_PROVIDER=KIS_REALTIME`을 승인 없이 설정하면 애플리케이션이 시작 단계에서 실패하는 것을 배포 전 1회 확인한다 (fail-fast).
- [ ] KIS 실시간 키가 배포 환경에 없어도 `KIS_HISTORICAL` 기동이 성공하는 것을 확인한다.
- [ ] 개인 개발·본인 전용 검증 환경은 `SERVICE_EXPOSURE=PRIVATE` + `STOCK_FEED_PROVIDER=KIS_REALTIME`으로 별도 운영한다 — 공개 배포와 설정을 공유하지 않는다. 이 환경은 **개발자 본인만 접근하는 로컬 또는 접근 통제 환경**이며, 공개 URL이나 여러 사용자가 접근하는 서버로 운영하지 않는다.
- [ ] **팀원·튜터·심사위원 대상 시연은 공개 배포와 동일하게 `KIS_HISTORICAL`로 수행한다** — 실시간 표출 서면 답변 전까지 제3자가 보는 화면에 KIS 실시간 시세를 띄우지 않는다.

### 시크릿 취급
- [ ] `KIS_APP_KEY`·`KIS_APP_SECRET`·`KIS_ACCOUNT_NO`·`KIS_ACCOUNT_PRODUCT_CODE`의 실제 값은 **서버 환경변수 또는 AWS Secret에만** 저장한다.
- [ ] 이 값들을 브라우저(프론트 번들·네트워크 응답), 코드, 문서, GitHub 어디에도 넣지 않는다.
- [ ] KIS 인증은 서버에서만 수행한다 — 클라이언트가 KIS API를 직접 호출하지 않는다.

### 이메일 발송 도메인
- [ ] 팀이 AWS Route 53에서 관리하는 도메인의 **전용 서브도메인**(예: `mail.<팀도메인>`)을 Resend 발신 도메인으로 인증한다.
- [ ] SPF·DKIM 레코드를 Route 53에 등록하고 Resend에서 인증 완료를 확인한다.
- [ ] 실제 도메인 값은 문서·코드에 적지 않고 `EMAIL_FROM` 환경변수로 주입한다.
- [ ] 운영 프로필에서만 `ResendEmailSender`를 활성화한다 — 로컬·테스트는 `FakeEmailSender`를 쓰며 `RESEND_API_KEY`·`EMAIL_FROM` 없이도 기동과 `./gradlew build`가 성공한다.

### 배포 후 스모크 (`scripts/smoke.ps1`)
- [ ] `SMOKE_BASE_URL`·`SMOKE_EMAIL`·`SMOKE_PASSWORD`를 환경변수로 받는다 — 스크립트에 값을 넣지 않는다.
- [ ] 단계별로 실패하면 원인을 출력하고 `exit 1`로 종료한다.
- [ ] 검증 범위는 배포된 기능에 맞춰 단계적으로 확장한다.

| 시점 | 검증 |
|---|---|
| 첫 배포 (001 기준) | `/actuator/health` 200·`UP`, `/v3/api-docs` 200 |
| 002 배포 후 | + 스모크 계정 로그인, `GET /api/auth/me` 200 |
| 003 배포 후 | + `GET /api/instruments` 200 |

## 비즈니스 규칙

- **스모크는 읽기 전용만 수행한다.** 주문·게시물·투자일기를 생성하지 않는다 — 체결은 불변 원장이라 되돌릴 수 없다 (PRD C-003).
- 스모크 계정은 배포 후 **1회 수동 생성**한다. 스크립트가 계정을 만들지 않는다 (이메일 인증이 선행되어야 하므로 자동 생성이 불가능하다).
- 스모크 계정 비밀정보는 로컬 실행 시 `.env`, CI 실행 시 GitHub Secret으로 주입한다.
- Mock·Fake 통과를 실제 외부 연동 성공으로 표현하지 않는다 (PRD C-005).
- 공개 배포의 주식 시세 공급자 기본값은 `KIS_HISTORICAL`이다 — 과거 데이터는 공공데이터로 확인되어 서면 답변을 기다리지 않고 진행한다. 공개 환경을 `KIS_REALTIME`으로 전환하는 것은 **한국투자증권의 서면 허용 또는 계약 완료가 확인된 뒤에만** 판단한다 — `KIS_PUBLIC_DISPLAY_APPROVED`를 `true`로 바꾸는 것만으로 허가가 생기지 않으며, 정본은 서면 답변이다 (PRD C-007). 현재 이 답변은 받은 적이 없다.
- 실시간 표출 답변이 늦거나 허용되지 않아도 **`KIS_HISTORICAL` 방식으로 MVP 배포를 진행한다.** 배포 일정을 이 답변에 묶지 않는다.
- 현재 확인된 것은 과거 데이터의 공공데이터 지위와 개발자 본인의 KIS Open API 사용 가능 여부다. **팀원·튜터·심사위원도 제3자이므로 실시간 시연 화면은 `KIS_HISTORICAL`이다.** 서면 답변에서 제한 시연 또는 공개 표출이 허용되면 그때 Decision Gate를 해제한다.

## 범위 제외

- 자동 배포·배포 파이프라인 (수동 배포로 시작).
- 무중단 배포·롤백 자동화·오토스케일링.
- 실제 이메일 수신, 실제 카카오·네이버 OAuth, 실제 KIS WebSocket 연결 확인 — 자동 스모크와 분리해 주요 배포 시 **수동 외부 스모크**로 처리한다 (PRD §9).
- 공개 환경의 KIS 실시간 전환 실행 (한국투자 서면 답변 Decision Gate — 이번 범위는 기본값을 `KIS_HISTORICAL`로 고정하고 잘못된 조합을 fail-fast로 막는 것까지다).
- 부하테스트·모니터링 대시보드 (2차 MVP).
- GitHub Issue 트리거 CI 하네스 (`docs/harness-roadmap.md` — 별도 ADR 후 착수).

## 완료 조건

- [ ] PR에서 CI가 `./gradlew build`를 실행하고 결과가 PR에 표시된다.
- [ ] 문서만 바뀐 PR에서 Gradle 단계가 실행되지 않는다.
- [ ] 운영 환경에 배포된 앱의 `/actuator/health`가 `UP`을 반환한다.
- [ ] 공개 배포 환경이 `SERVICE_EXPOSURE=PUBLIC`·`STOCK_FEED_PROVIDER=KIS_HISTORICAL`·`KIS_PUBLIC_DISPLAY_APPROVED=false`로 기동됨을 확인한다.
- [ ] 승인 없는 공개 KIS 조합에서 기동이 실패하는 것을 배포 전 1회 확인한다.
- [ ] Resend 발신 서브도메인 인증이 완료되고 실제 인증 메일 수신이 1회 확인된다 (수동 외부 스모크).
- [ ] `scripts/smoke.ps1`이 성공 시 `exit 0`, 실패 시 `exit 1`로 동작한다.
- [ ] 배포 주소에서 프론트와 `/api`가 같은 오리진으로 동작하고, `/api/stocks/stream`이 브라우저에서 실시간으로 흐른다 (이슈 #108).
- [ ] 실행한 검증과 미실행 외부 검증을 구분해 보고한다.
