# Run Log: 002-auth-account

## Issue #53

### Task 1: state 서명·검증 계약과 오류 코드·환경변수

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 23:30 | implementer | `.\gradlew.bat compileJava compileTestJava --no-daemon --max-workers=1` — `BUILD SUCCESSFUL`, `.\gradlew.bat spotlessApply`, `.\gradlew.bat test --tests "*OAuthStateGeneratorTest" --tests "*OAuthAuthorizationServiceTest" --tests "*OAuthAuthorizationControllerTest"` — `BUILD SUCCESSFUL` | issue-53-plan.md D1(HMAC-SHA-256 서명 state)·D2(서명 실패 403), conventions.md 시크릿·네이밍 규칙, ADR-0002 |

### Task 2: `reauth_tokens` 스키마와 `AuthService.reauthenticate` 발급 트랜잭션

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 00:05 | implementer | `.\gradlew.bat compileJava compileTestJava --no-daemon --max-workers=1`; `.\gradlew.bat spotlessApply spotbugsMain`; `.\gradlew.bat test --tests "*AuthServiceTest" --tests "*OAuthAuthServiceTest"` — 모두 `BUILD SUCCESSFUL` | issue-53-plan.md D4(조회+발급만 하는 별도 트랜잭션)·D5(`reauth_tokens` 스키마·엔티티 패턴), ADR-0004(신규 V5 마이그레이션), conventions.md 엔티티·DTO 규칙 |

### Task 3: callback purpose 분기와 reauth 응답 계약

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 00:20 | implementer | `.\gradlew.bat test --tests "*OAuthLoginIntegrationTest" --tests "*FakeOAuthFlowIntegrationTest" --tests "*FakeOAuthCodeConsumptionIntegrationTest"` — 17건 전부 실패 후 원인 수정하고 재실행해 `BUILD SUCCESSFUL`; `.\gradlew.bat build --no-daemon --max-workers=1` — 503 tests `BUILD SUCCESSFUL` | issue-53-plan.md D3(분기 순서)·D8(`ReauthTokenResponse` 계약), ADR-0003 Testcontainers 통합 테스트, CLAUDE.md 규칙 4·7·9 |

### Task 4: `purpose=reauth` authorize 엔드포인트와 Security 인증 분기

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 00:45 | implementer | `jar tf spring-security-web-7.1.0.jar`로 매처 클래스 확인; `.\gradlew.bat test --tests "*OAuthAuthorizationServiceTest" --tests "*OAuthAuthorizationControllerTest" --tests "*SecurityConfigTest"`; `.\gradlew.bat test --tests "*FakeOAuthFlowIntegrationTest"`; `.\gradlew.bat spotlessApply spotbugsMain` — 모두 `BUILD SUCCESSFUL` | issue-53-plan.md D6(조건부 공개 매처)·D7(같은 클래스에 authorizeReauth 추가), conventions.md API·DTO 규칙, CLAUDE.md 규칙 7 |

### Task 5: 전체 회귀·문서 동기화

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 01:10 | implementer | `.\gradlew.bat build --no-daemon --max-workers=1` — `BUILD SUCCESSFUL`, 519 tests / 실패 0 / 스킵 0 (HEAD `c7543d6`, 워킹트리 clean) | issue-53-plan.md 완료 체크리스트, CLAUDE.md 규칙 4·7, ADR-0003 |
| 09:40 | reviewer(리뷰) | `git diff origin/dev...HEAD`(커밋 81b7cd6·17e294c·c08fcd1·c7543d6·ffaaba3 전체) | issue-53-plan.md D1~D8, conventions.md, ADR-0002·0003·0004, docs/api-routes.md, docs/agent-mistakes.md |

### 실제 네이버 OAuth 재인증 스모크

| 시각 | 실행 | 결과 |
|---|---|---|
| 01:09 | `oauth-real,local` 프로필로 `bootRun` 기동 후 실제 네이버 계정으로 `purpose=login` 로그인 → 발급된 accessToken으로 `purpose=reauth` authorize(브라우저 콘솔 fetch, Bearer 헤더) → 같은 네이버 계정으로 callback 완료 | `reauthToken` 200 정상 발급(`expiresInSeconds: 300`). `reauth_tokens` 1행(`user_id=1`, 해시만 저장, `expires_at`=`created_at`+5분, `consumed_at` NULL), `users`/`social_accounts`/`accounts` 행 수(1/1/2) 재인증 전후 불변을 MySQL로 직접 확인 |

서명 포맷 적용으로 약 110자까지 늘어난 state가 네이버 실제 서버를 문제없이 왕복함을 확인해 issue-53-plan.md "미확정" 5번 항목을 해소했다. 카카오는 이번엔 실제 스모크를 수행하지 않았다(NOT RUN). Client ID/Secret·accessToken 원문은 로그·문서에 기록하지 않았다.

## Issue #10

### PR #49 차단 리뷰 대응 및 최종 검증

- reviewer가 지적한 raw state cookie 재전송 문제는 서버 state 저장을 추가하는 제안 1 대신 제안 2로 대응한다. Issue #9의 authorize가 Redis·DB 변경 없이 브라우저 state 쿠키만 사용하는 계약을 유지한다.
- 보장 범위를 “정상 브라우저 callback 응답에서 state 쿠키 만료”로 좁힌다. 서버는 state를 저장하지 않으므로 raw cookie 재전송 자체를 차단한다고 주장하지 않으며, 실제 공급자의 authorization code 단일 사용이 재전송을 거부한다.
- Fake는 authorize마다 state에 결합된 고유 code를 발급하고 KAKAO/NAVER 전용 callback bean이 Fake 전용 thread-safe store에서 `(provider, code, state)`를 원자적으로 한 번만 소비한다. 특수 fixture code는 기존 오류 테스트용으로 유지한다.
- 검증 완료: 잘못된 provider는 grant 소비 없이 400으로 거부되고, 원 provider는 이후 한 번 성공하며 재사용은 400이다. generated code 순차 재사용·동시성 단일 성공, authorize URI별 고유 code, 첫 전체 callback 200 후 동일 `(provider, code, state)`와 raw cookie 재전송 400, RefreshToken·User·SocialAccount·Account 불변, 기존 state 누락·불일치 400 회귀가 대상 테스트 묶음에서 `PASS`했다.
- Issue #9 plan과 ADR은 수정하지 않는다. Issue #10의 Fake Provider·자동 회귀 후속 변경과 `docs/api-routes.md` 계약 정정으로 한정한다.
- `.\gradlew.bat spotlessApply`와 관련 대상 테스트 묶음이 `PASS`했고, provider 결합 후 메인 최종 `.\gradlew.bat build --no-daemon --max-workers=1`은 3분 23초에 `BUILD SUCCESSFUL`이었다.

### Task 4 production 점검 기록

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 22:49 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` — `BUILD SUCCESSFUL` | issue-10-plan.md Task 4 Fake authorize→callback→소셜 로그인 default profile 빈 연결 정적 점검 |
| 23:05 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1`; `.\gradlew.bat spotbugsMain --no-daemon --max-workers=1` — 모두 `BUILD SUCCESSFUL` | 전체 build 차단 `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` 수정 재검증 |
| 23:18 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1`; `.\gradlew.bat spotbugsMain --no-daemon --max-workers=1` — 모두 `BUILD SUCCESSFUL` | 리뷰 차단: 실제 OAuth connect 5초/read 10초 timeout과 트랜잭션 전 DTO 검증 |

- 22:49 — default profile Fake 전체 흐름의 production 누락이 없음을 확인해 src는 변경하지 않고, 실제 오류 계약을 `docs/api-routes.md`에 보완했다.
- 최초 전체 build는 테스트·JaCoCo 통과 후 Naver token nullable 응답의 분리 검증을 SpotBugs가 추적하지 못해 실패했다.
- nullable 검증과 access token 반환을 같은 흐름으로 합쳐 경고를 해소했다. `org.jetbrains.annotations.Nullable` 보조 누락 메시지는 남지만 SpotBugs 경고·게이트는 통과했다.
- 두 번째 전체 build는 테스트·JaCoCo·SpotBugs 통과 후 수정 파일의 줄바꿈 포맷을 `spotlessJavaCheck`가 차단했다. `spotlessApply` 후 재실행해 전체 `BUILD SUCCESSFUL`을 확인했다.
- 메인 자동 회귀 — `FakeOAuthFlowIntegrationTest`를 실제 `mysql:8.4`로 재실행해 KAKAO/NAVER Fake 신규·기존·오류·DB 흐름의 `BUILD SUCCESSFUL`을 확인했다. 실제 공급자 검증은 아니다.
- 리뷰 수정 — 실제 Kakao/Naver RestClient에 유한 timeout을 강제하고, providerUserId·email 오류를 `AuthService` 트랜잭션 진입 전에 각각 502·400으로 차단했다.

### Task 3 구현 기록

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 22:34 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` — `BUILD SUCCESSFUL` | issue-10-plan.md Task 3 기존/신규 소셜 로그인 원자 트랜잭션, ADR-0002 |

- 22:34 — SocialAccount 매핑, 안전한 OAuth nickname, 기존/신규 소셜 로그인과 User·SocialAccount·계좌·Refresh Token 원자 저장을 구현하고 컴파일을 통과했다.
- 메인 재검증 — Task 3 단위·`@DataJpaTest`·`@SpringBootTest` 6개 클래스를 `mysql:8.4` Testcontainers와 함께 실행해 `BUILD SUCCESSFUL`(1분 35초)을 확인했다.
- 검증 수준 — 신규/기존 소셜 로그인, SocialAccount 유일성, 계좌 2개와 초기 잔액, Refresh Token 해시/JWT, SocialAccount·Account·Refresh 저장 실패의 전체 롤백을 실제 MySQL에서 검증했다. 실제 외부 OAuth 호출은 아니다.

### Task 2 구현 기록

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 22:19 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` — `BUILD SUCCESSFUL` | issue-10-plan.md Task 2, 카카오·네이버 공식 OAuth REST 문서(2026-07-26 확인) |

- 22:19 — Fake·카카오·네이버 callback Provider와 인가/공급자 오류 정규화, 실제 프로필 설정 fail-fast를 추가하고 컴파일을 통과했다.
- 메인 재검증 — Fake/Kakao/Naver callback, profile, callback error, 기존 authorize, 공통 오류의 8개 대상 테스트를 한 Gradle 실행으로 검증해 `BUILD SUCCESSFUL`을 확인했다. 단위·Mock HTTP·WebMvc 수준이며 실제 OAuth가 아니다.
- 비밀값 검사 — `oauth.txt`의 실제 값과 Git 추적 파일의 리터럴을 비교해 일치 0건을 확인했다. 값 자체는 출력·기록하지 않았다.

### Task 1 구현 기록

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 22:07 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` — `BUILD SUCCESSFUL` | issue-10-plan.md Task 1 state 선검증·쿠키 만료·공개 callback 경계, ADR-0002 |

- 22:07 — callback state 상수 시간 검증, 성공·실패 만료 쿠키 선등록, 공개 GET Controller/Security 및 API 문서를 추가하고 컴파일을 통과했다.
- 메인 재검증 — `.\gradlew.bat test --tests "com.finplay.api.auth.service.OAuthCallbackServiceTest" --tests "com.finplay.api.auth.oauth.OAuthStateCookieFactoryTest" --tests "com.finplay.api.auth.controller.OAuthCallbackControllerTest" --tests "com.finplay.api.auth.config.SecurityConfigTest" --no-daemon --max-workers=1` — `BUILD SUCCESSFUL` (단위·WebMvc 슬라이스, 실제 OAuth·DB 아님).
- 포맷 — `.\gradlew.bat spotlessApply --no-daemon --max-workers=1` — `BUILD SUCCESSFUL`.

### 계획 승인

- 2026-07-26 — 신규 OAuth nickname 생성·충돌 정책과 `OAUTH_AUTHORIZATION_FAILED`(400)·`OAUTH_PROVIDER_ERROR`(502) 오류 분류를 사용자 승인으로 확정했다. 시크릿·토큰·authorization code 값은 기록하지 않았다.

### 검증 기록 계약 (implementer·reviewer가 실행 결과로 갱신)

| 구분 | 공급자/명령 | 결과 | 검증 수준·사유 |
|---|---|---|---|
| 자동 회귀 | Fake OAuth 대상 테스트 | PASS | `(provider, code, state)` 결합, 잘못된 provider 거부·grant 비소비, 원 provider 1회 성공·재사용/동시성 400, 전체 flow 재전송 DB 불변 포함. 실제 OAuth 아님 |
| 전체 게이트 | `.\gradlew.bat build --no-daemon --max-workers=1` | PASS | provider 결합 후 최종 production/test 상태에서 전체 tests·JaCoCo·SpotBugs·Spotless `BUILD SUCCESSFUL`(3분 23초). 이후 검증 기록 문서만 변경 |
| 실제 OAuth | KAKAO | PASS | 사용자 조작 authorize→로그인·이메일 동의→callback→코드 교환→사용자 정보→신규/기존→JWT 및 DB 검증 완료. 기존 회원 응답 렌더링 제한은 아래 기록 |
| 실제 OAuth | NAVER | PASS | 사용자 조작 authorize→로그인/동의→callback→코드 교환→사용자 정보→신규/기존→JWT 및 DB 검증 완료. 기존 회원 두 번째 응답 렌더링 제한은 아래 기록 |
| PR #49 차단 후속 | Fake code 단일 사용·재전송/동시성·DB 불변 대상 테스트와 전체 build | PASS | Spotless·관련 대상 테스트 묶음·전체 build 통과. 실제 KAKAO/NAVER 스모크를 이번 검증에서 재실행한 결과가 아님 |

- 실제 카카오·네이버는 각각 `PASS`여야 Issue #10 PR 완료 조건을 충족한다. 미검증 공급자는 실제 연동 완료로 주장하지 않는다.
- 환경변수·개발자 콘솔 Callback URL·이메일 동의가 부족하면 추측하지 않고 공급자별 `NOT RUN` 사유를 기록한다.
- 브라우저 로그인·동의 단계는 사용자 조작 완료를 기다린다.
- 자동 회귀와 실제 공급자 스모크는 서로 대체하지 않고 run-log와 PR에 별도 기록한다.
- Client ID/Secret, Provider Access Token, authorization code는 명령·결과·로그·PR에 기록하지 않는다.

### oauth-real 런타임·KAKAO/NAVER 실제 스모크 기록

- 실제 `oauth-real` 기동에서 `RestClient.Builder` main 자동설정 누락을 발견했다. `spring-boot-restclient` 추가, `OAuthRealContextIntegrationTest`, timeout counterfactual 테스트로 보완한 뒤 실제 jar 기동과 authorize 302를 확인했다.
- NAVER 신규 로그인은 사용자 브라우저 조작으로 로그인·동의를 완료했고 callback의 코드 교환·사용자 정보 조회 후 FinPlay JWT 응답 필드와 만료 3,600초·1,209,600초를 확인했다.
- 신규 DB는 `users=1`, `social_accounts(provider=NAVER)=1`, `accounts=2`였다. STOCK·CRYPTO 모두 `cash_balance=10,000,000`, `seed_money=10,000,000`이고 `refresh_tokens=1`이었다.
- 같은 NAVER 계정의 두 번째 authorize/callback은 기존 회원으로 처리돼 users·social_accounts·accounts 수가 변하지 않았고 `refresh_tokens=2`가 됐다.
- NAVER 두 번째 response 렌더링은 Chrome client의 `ERR_BLOCKED_BY_CLIENT`로 차단됐다. 다만 서버 트랜잭션의 `issueTokenPair` 경로에서 새 Refresh Token 행이 커밋돼 기존 회원 JWT pair 발급 경로가 실행된 사실을 확인했다. 브라우저에서 두 번째 JWT 응답 본문을 직접 확인했다는 의미는 아니다.
- KAKAO 신규 로그인은 사용자 브라우저 조작으로 로그인·이메일 동의를 완료했고 callback의 코드 교환·사용자 정보 조회 후 FinPlay JWT 응답 필드와 만료 3,600초·1,209,600초를 확인했다.
- KAKAO 신규 DB는 `users=1`, `social_accounts(provider=KAKAO)=1`, `accounts=2`였다. STOCK·CRYPTO 모두 `cash_balance=10,000,000`, `seed_money=10,000,000`이고 `refresh_tokens=1`이었다.
- 같은 KAKAO 계정의 기존 회원 재로그인 요청 2회는 서버에서 완료돼 users·social_accounts·accounts 수가 변하지 않았고 `refresh_tokens=3`이 됐다.
- KAKAO 기존 회원 response 렌더링은 Chrome 브라우저 제어 계층의 `ERR_BLOCKED_BY_CLIENT`로 차단됐다. `AuthService.issueTokenPair`가 JWT pair를 생성한 뒤 Refresh Token 행을 저장하고 실제 행이 3개까지 커밋됐으므로 두 요청 모두 기존 회원 JWT 발급 경로가 실행된 사실을 확인했다. 브라우저에서 두 응답 본문을 직접 확인했다는 의미는 아니다.
- KAKAO 실제 스모크 중 애플리케이션 로그의 `ERROR`, `ClientAbort`, `Broken pipe`, `Connection reset`은 모두 0건이었다.
- 두 공급자 모두 신규 회원의 JWT 응답 본문을 직접 확인했다. 두 공급자 모두 기존 회원 재로그인의 응답 본문은 Chrome에서 직접 확인하지 못했고, 서버 트랜잭션과 DB Refresh Token 커밋으로 발급 경로 실행을 확인했다.
- 실제 스모크 과정과 이 기록에는 Client ID/Secret, Provider Access Token, authorization code 원문을 남기지 않았다.

### PR 검증·리뷰 기록

- 자동 회귀와 실제 KAKAO·NAVER 스모크를 각각 별도 증빙으로 기록했다. PR #49 차단 리뷰 후속 자동 회귀와 전체 build도 `PASS`했으며, 실제 공급자 스모크는 이번 후속 검증에서 재실행하지 않았다.
- 런타임 점검에서 발견한 `RestClient.Builder` 자동설정 누락은 의존성과 실제 프로필 컨텍스트·timeout 반증 테스트로 보완했고, 검증 실행 HEAD 전체 build와 실제 jar authorize 302로 재검증했다.

## Issue #7

### 최종 검증

| 구분 | 실행 명령·근거 | 결과 | 검증 수준 |
|---|---|---|---|
| 서비스 구현 컴파일 | `.\gradlew.bat compileJava --no-daemon --max-workers=1`, 커밋 `d33103f` | `BUILD SUCCESSFUL` (30초) | `AuthService.logout`의 Refresh JWT·해시·소유권 검증과 조건부 폐기 컴파일 |
| Controller 구현 컴파일 | `.\gradlew.bat compileJava --no-daemon --max-workers=1`, 커밋 `e16f9d2` | `BUILD SUCCESSFUL` (18초) | 보호된 `POST /api/auth/logout`와 204 빈 응답 컴파일 |
| 서비스 대상 테스트 | `.\gradlew.bat test --tests "*AuthServiceTest" --no-daemon --max-workers=1` | 27/27 통과 | JWT·DB 사용자 불일치 401, Access·DB 소유권 불일치 403, 조건부 폐기 검증 |
| WebMvc·Security 대상 테스트 | `.\gradlew.bat test --tests "*AuthControllerTest" --tests "*SecurityConfigTest" --no-daemon --max-workers=1` | `AuthControllerTest` 42/42, `SecurityConfigTest` 21/21 통과 | 204·요청 검증·공통 오류와 logout 보호 경로 검증 |
| logout 통합 테스트 | `.\gradlew.bat test --tests "*LogoutIntegrationTest" --no-daemon --max-workers=1` | MySQL 8.4 통합 3/3 통과 | 폐기 후 refresh 401, 타인 토큰 403·상태 보존, 선택 토큰만 폐기 검증 |
| 최종 전체 빌드 | `.\gradlew.bat build --no-daemon --max-workers=1`, HEAD `c6c04f41c33843e98d12c8eb999d7e1ecbc7c56f` | `BUILD SUCCESSFUL` (2분 3초), 13 tasks(9 executed, 4 up-to-date) | 전체 tests·JaCoCo·SpotBugs·Spotless 게이트 통과 |
| 리뷰 | reviewer가 Issue #7 변경 검토 | 코드·테스트 차단 0건, 문서 동기화 1건 | production·테스트 정적 diff와 API 문서 일치 여부 검토 |

### 구현 근거

- `d33103f` — `AuthService.logout`에 Refresh Token 검증·소유권 판정·조건부 폐기를 추가했다.
- `e16f9d2` — 인증이 필요한 `POST /api/auth/logout`와 204 빈 응답을 추가했다.

### 검증 범위와 남은 위험

- 통합 테스트는 MySQL 8.4 Testcontainers를 사용했으며 운영 DB 검증 결과가 아니다.
- 실제 외부 API, 운영 환경 연동, 실행 서버 대상 블랙박스 API QA는 실행하지 않았다.
- reviewer의 문서 동기화 1건은 코드·테스트 차단과 구분되는 문서 범위 지적이다.

## Issue #6

### 최종 검증

증분 비교 기준은 로컬 `dev`가 아니라 `origin/dev`의 `4c399a6`이며, 최종 구현 HEAD는 `e42f0be`다.

| 구분 | 실행 명령·근거 | 결과 | 검증 수준 |
|---|---|---|---|
| JWT 대상 테스트 | `.\gradlew.bat test --tests "*JwtTokenProviderTest" --no-daemon --max-workers=1` | 25/25 통과 | Access·Refresh JWT 서명·만료·변조·토큰 타입·subject 파싱 단위 검증 |
| Repository 대상 테스트 | `.\gradlew.bat test --tests "*RefreshTokenRepositoryTest" --no-daemon --max-workers=1` | 4/4 통과 | MySQL 8.4 Testcontainers 기반 활성 토큰 조건부 폐기 쿼리 검증 |
| 서비스·통합·롤백 대상 테스트 | `.\gradlew.bat test --tests "*AuthServiceTest" --tests "*RefreshTokenIntegrationTest" --tests "*RefreshTokenRollbackIntegrationTest" --no-daemon --max-workers=1` | `AuthServiceTest` 20/20, MySQL 통합 2/2, MySQL 롤백 1/1 통과 | 해시 조회, 단일 회전, 이전 토큰 재사용 401, 동시 요청 단일 성공, 발급 실패 시 폐기 롤백 |
| Controller 대상 테스트 | `.\gradlew.bat test --tests "*AuthControllerTest" --no-daemon --max-workers=1` | 33/33 통과 | 요청 누락·빈 값·4,096자 초과 400, 범위 내 무효 토큰 401, 200 응답과 공개 POST 경로 MVC 검증 |
| 최종 전체 빌드 | `.\gradlew.bat build --no-daemon --max-workers=1`, HEAD `e42f0be` | `BUILD SUCCESSFUL` (2분 17초) | 전체 tests·JaCoCo·SpotBugs·Spotless 게이트 통과 |
| 최초 리뷰 | reviewer가 `origin/dev` `4c399a6` 대비 HEAD `e42f0be` 검토 | production 차단 0건, 문서 동기화 차단 1건 | Controller·서비스·Repository·트랜잭션·동시성 구현 검토. 문서 차단은 후속 동기화에서 반영 |
| 최종 재리뷰 | reviewer가 `origin/dev` `4c399a6` 대비 HEAD `c050aff` 전체 diff 재검토 | 차단 0건, 권장 0건, 참고 2건. 이전 문서 차단 해소 확인 | 문서 동기화 포함 최종 정적 diff 검토. 이 재리뷰에서는 Gradle·블랙박스 QA를 다시 실행하지 않음 |

### 구현 근거

- `6abfe0d` — Refresh JWT 검증 추가.
- `9e2d538` — 활성·미만료 Refresh Token의 조건부 원자 폐기 쿼리 추가.
- `3349c40` — 폐기와 새 토큰 발급·해시 저장의 단일 트랜잭션, 재사용·동시 요청·롤백 검증 추가.
- `e42f0be` — `POST /api/auth/refresh`, 최대 4,096자 Bean Validation, 공개 POST 경로 추가.

### 검증 범위와 남은 위험

- Repository·통합·롤백 테스트는 MySQL 8.4 Testcontainers를 사용했으며 운영 DB 검증 결과가 아니다.
- 운영 환경, 외부 API 연동, 실행 서버 대상 블랙박스 API QA는 실행하지 않았다.
- logout은 Issue #7, `GET /api/auth/me`는 Issue #8의 잔여 범위다.
- 최종 재리뷰의 참고 2건은 차단·권장 사항이 아니며, 재리뷰 시 Gradle·QA는 재실행하지 않았다.

## Issue #5

### 최종 검증

| 구분 | 실행 명령·근거 | 결과 | 검증 수준 |
|---|---|---|---|
| 최초 전체 빌드 | `.\gradlew.bat build --no-daemon --max-workers=1`, HEAD `61cd74f` | `BUILD SUCCESSFUL` (1분 40초) | Issue #5 당시 전체 Gradle 게이트 통과 |
| 직전 전체 테스트 | 전체 테스트 실행 | 201개 중 기존 test helper 관련 1건 실패. 원인을 수정했으며 이 실행 자체는 통과로 간주하지 않음 | 실패 이력을 보존한 회귀 테스트 실행 |
| JWT 대상 테스트 | `.\gradlew.bat test --tests "*JwtTokenProviderTest" --no-daemon --max-workers=1` | 16/16 통과 | 발급별 `jti`, 만료·변조·토큰 타입 검증 |
| 로그인 통합 테스트 | `.\gradlew.bat test --tests "*LoginIntegrationTest" --no-daemon --max-workers=1` | 5/5 통과 | MySQL Testcontainers 기반 로그인·Refresh Token 해시 저장 통합 검증 |
| 최종 전체 빌드 | `.\gradlew.bat build --no-daemon --max-workers=1`, HEAD `7915077` | `BUILD SUCCESSFUL` (16초), 13 tasks. Spotless 실행, test·coverage·SpotBugs는 up-to-date | 최종 소스 상태의 전체 Gradle 게이트 |
| 최종 리뷰 | reviewer 최종 검토 | 차단 0건 | Issue #5 범위 코드·계약 검토 |

### 수정 근거

- `4d8716f` — JWT 발급별 고유 `jti`를 추가해 토큰 차단 문제를 수정했다.
- `7915077` — JWT 서명 변조 테스트를 안정화했으며 최종 검증 HEAD다.

### 검증 범위와 남은 위험

- `LoginIntegrationTest`는 MySQL Testcontainers를 사용했으며 운영 DB를 검증한 결과가 아니다.
- 403 `RestAccessDeniedHandler`의 전용 계약 테스트는 없다. 현재 reviewer 비차단 권장 사항으로 남긴다.
- Refresh Token 회전·폐기·재사용 거부, logout, `GET /api/auth/me`는 Issue #5 범위 밖이며 아직 검증하지 않았다.

## Issue #4

### 최종 검증

| 구분 | 실행 명령 | 결과 | 검증 수준 |
|---|---|---|---|
| 대상 테스트 | `.\gradlew.bat test --tests "*JwtTokenProviderTest" --tests "*AccountServiceTest" --tests "*AuthServiceTest" --tests "*AuthControllerTest" --tests "*SignupPersistenceRepositoryTest" --tests "*SignupIntegrationTest"` | `BUILD SUCCESSFUL` (68.4초), XML 6 suites·38 tests·failures 0·errors 0·skipped 0 | JWT·계좌·가입 서비스 단위 테스트, MVC 슬라이스, MySQL 8.4·Redis Testcontainers 기반 영속성·통합 테스트 |
| 전체 빌드 1차 | `.\gradlew.bat build` | 실패: SpotBugs `CT_CONSTRUCTOR_THROW` 1건 | `JwtTokenProvider`를 `final`로 수정한 커밋 `0c134b8` 후 `spotbugsMain` warnings 0 확인 |
| 전체 빌드 2차 | `.\gradlew.bat build` | 실패: mixed EOL로 `spotlessJavaCheck` 실패 | `.\gradlew.bat spotlessApply`로 줄바꿈 정규화 |
| 최종 전체 빌드 | `.\gradlew.bat build --rerun-tasks` | `BUILD SUCCESSFUL` (1분 38초), XML 20 suites·98 tests·failures 0·errors 0·skipped 0, HEAD `4f5ac48` | Spotless·SpotBugs·JaCoCo 통과. SHA-256 사전 해시+BCrypt 장문·다바이트와 실제 MySQL 소비 후 롤백·동일 토큰 재시도·2-thread 경합 검증 포함 |

### 검증 범위와 남은 위험

- 대상 테스트는 MySQL 8.4와 Redis Testcontainers를 사용했으며 운영 DB나 운영 Redis를 검증한 결과가 아니다.
- 실제 Resend 메일 발송, 외부 API, 운영 환경 연동은 실행하지 않았다.
- SpotBugs 분석에는 비차단 `org.jetbrains.annotations.Nullable` missing auxiliary class 메시지가 남아 있다.

## Issue #3 Task 3

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 20:35 | main | `.\gradlew.bat build` | Issue #3 전체 컴파일·테스트·Spotless·SpotBugs·JaCoCo 게이트, SHA `bdb888b` |

## Issue #3 Task 2

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 20:26 | implementer | `.\gradlew.bat compileJava` | Task 2 HTTP 계약, Bean Validation, ADR-0002 controller-service 경계, API 경로 문서 동기화 |

## Issue #3 Task 1

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 20:07 | implementer | `.\gradlew.bat compileJava` | plan.md 인증번호 확인·가입 토큰 발급, ADR-0002 service 트랜잭션 경계, conventions DTO·엔티티 상태 전이 규칙 |

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 14:30 | implementer | `.\gradlew.bat compileJava` | plan.md 데이터 모델·구성요소 설계, ADR-0002·ADR-0004 |
| 15:10 | implementer | `.\gradlew.bat compileJava` | plan.md 구성요소 설계(EmailSender 어댑터·프로필 분기), conventions.md 시크릿 규칙 |
| 15:45 | implementer | `.\gradlew.bat compileJava` | spec AUTH-004·plan API설계(발송 제한·HMAC·이전 코드 무효화), conventions dto/레이어 규칙, ClockConfig 주입 |
| 16:10 | implementer | `.\gradlew.bat test --tests "*GlobalExceptionHandlerTest" --tests "*EmailVerificationControllerTest"` | @WebMvcTest 슬라이스 스캔 원리(무지정 시 전체 컨트롤러 스캔), 이슈 #32 기존 테스트 |
| 16:40 | reviewer(리뷰) | `git diff dev...HEAD` | conventions.md(시크릿·레이어·DTO), ADR-0002·0003·0004, spec/plan AUTH-004 |
| 18:50 | implementer | `.\gradlew.bat test --tests "*FinPlayApiApplicationTests" --tests "*EmailVerificationServiceTest" --tests "*EmailVerificationControllerTest"` | 리뷰 차단 반영: conventions.md 시크릿 규칙(기본값 금지·fail-fast), JWT_SECRET 패턴 |
| 19:00 | implementer | `.\gradlew.bat build` | 시크릿 주입 중앙화: build.gradle Test 태스크에 더미 env 일괄 공급, @DynamicPropertySource 중복 제거(fail-fast 유지) |
| 21:13 | implementer | `.\gradlew.bat compileJava` | issue-9-plan.md Task 1 Provider 계약·프로필별 URI, ADR-0002·conventions.md |
| 21:18 | implementer | `.\gradlew.bat compileJava --rerun-tasks` | issue-9-plan.md Task 2 SecureRandom state·10분 보안 쿠키 계약 |
| 21:21 | implementer | `.\gradlew.bat compileJava --rerun-tasks` | issue-9-plan.md Task 3 service·302 Controller·라우트 계약 |
| 21:30 | implementer | `.\gradlew.bat compileJava --rerun-tasks` | 리뷰 차단: 실제 OAuth 프로필 Secure 쿠키 강제·Provider 설정 fail-fast |
| 21:53 | 메인 | `.\gradlew.bat build --no-daemon --max-workers=1` | HEAD `35a9feeb00f2330053de8c88b7667b6d1354a538` 최종 게이트, BUILD SUCCESSFUL |
| 17:11 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` | issue-5-plan.md Task 1(D4 Optional 반환·주입 Clock 만료 판정), conventions.md 파일 주석·record 규칙, ADR-0002 |
| 17:20 | implementer | `.\gradlew.bat test --tests "*AuthControllerTest" --tests "*EmailVerificationControllerTest" --tests "*OAuthAuthorizationControllerTest" --tests "*GlobalExceptionHandlerTest" --no-daemon --max-workers=1` | issue-5-plan.md Task 2(D2 필터 순서·D3 핸들러 직접 직렬화·D4), Boot 4.1 실제 자동설정 경로 확인(`SecurityFilterProperties`·`ServletWebSecurityAutoConfiguration`) |
| 17:30 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` | issue-5-plan.md Task 4(D6 issueTokenPair 추출·D7 동일 401·D9 기존 토큰 유지), conventions.md 레이어·트랜잭션 경계 |
| 17:37 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` | issue-5-plan.md Task 5 HTTP 계약 표(비밀번호 min 금지·D8 200 응답), conventions.md DTO 검증 메시지 규칙, CLAUDE.md 7(api-routes 동기화) |
| 18:01 | implementer | `.\gradlew.bat spotbugsMain --no-daemon --max-workers=1` | SpotBugs EI_EXPOSE_REP2 2건 해소: conventions.md Lombok 표(component는 `@RequiredArgsConstructor`), exclude.xml 첫 줄 규칙(재현된 오탐만 제외) |
| 20:45 | implementer | `.\gradlew.bat test --tests "*AuthServiceTest" --no-daemon --max-workers=1` — 32/32 통과 | issue-8-plan.md Task 1(D1 AuthService 자체 UserRepository·D2 MemberResponse·D3 SocialAccount 조회 판별·D4 SignupMethod 분리·D5 readOnly), conventions.md DTO·레이어 규칙 |
| 20:46 | 메인 | `git cherry-pick`으로 이슈 8 커밋을 `feat/24-community-post-list`(공유 워크트리에서 다른 세션이 사용 중이던 브랜치)에서 `feat/008-auth-me`로 이동 | 오케스트레이터가 사전 확인 없이 implementer를 투입해 브랜치가 바뀐 상태에서 커밋된 것을 발견, 정정 |
| 20:57 | implementer | `.\gradlew.bat test --tests "*AuthControllerTest" --no-daemon --max-workers=1` — 실패 4건 확인 후 48/48 통과 | issue-8-plan.md Task 2(D6 SecurityConfig 미변경·D7 200 MemberResponse), conventions.md 테스트 작성 규칙(jsonPath 값 검증) |
| 21:06 | implementer | `.\gradlew.bat test --tests "*MeIntegrationTest" --no-daemon --max-workers=1` — 5/5 통과, `.\gradlew.bat spotlessApply` | issue-8-plan.md Task 3(실제 MySQL 가입 방식 판별·회원 간 격리·민감 필드 미노출), ADR-0003 Testcontainers 통합 테스트 |
| 22:15 | implementer | `.\gradlew.bat build --no-daemon --max-workers=1` — BUILD SUCCESSFUL (대상 테스트 3종·spotlessApply 선행) | issue-8-plan.md Task 4, CLAUDE.md 규칙 4·7(완료 전 build, controller 변경 시 api-routes.md 동기화) |
| 22:30 | reviewer(리뷰) | `git diff origin/dev...HEAD` (auth/me 관련 프로덕션·테스트·문서 파일), `MemberResponse`·`SocialAccountRepository`·`AuthService`·`AuthController`·마이그레이션(V2) 확인 | issue-8-plan.md D1~D7, conventions.md 레이어·DTO·Lombok·테스트 규칙, ADR-0002, ADR-0003, ADR-0004(마이그레이션 미추가 확인)
| 02:14 | implementer | `.\gradlew.bat test --tests "*ReauthTokenRepositoryTest" --tests "*UserRepositoryTest" --no-daemon --max-workers=1` — 컴파일 실패 확인 후 7/7·5/5 통과, `.\gradlew.bat spotlessApply` | issue-54-plan.md Task 1(D2 원자적 소비 쿼리·D4 본인 제외 중복 확인), ADR-0003 `@DataJpaTest`, ADR-0004(마이그레이션 미추가) |
| 02:59 | implementer | `.\gradlew.bat test --tests "*AuthServiceTest" --no-daemon --max-workers=1` — 컴파일 실패(`cannot find symbol: changeNickname`) 확인 후 47/47 통과, `.\gradlew.bat spotlessApply` | issue-54-plan.md Task 2(D3 setter 대신 `changeNickname`·D5 `MemberResponse` 재사용·D6 단일 트랜잭션·D7 기존 ErrorCode만 사용), conventions.md 엔티티·레이어 규칙, ADR-0002 |
| 03:35 | implementer | `.\gradlew.bat test --tests "*AuthControllerTest" --no-daemon --max-workers=1` — 신규 9건 FAILED 확인 후 59/59 통과, `.\gradlew.bat spotlessApply` | issue-54-plan.md Task 3(D1 요청 DTO 선택 필드·D5 `MemberResponse` 응답·D7 SecurityConfig 미변경), conventions.md DTO·API 규칙, CLAUDE.md 규칙 7(api-routes.md 동기화) |
| 03:25 | implementer | `.\gradlew.bat spotlessApply`(UP-TO-DATE); 대상 테스트 5종 `BUILD SUCCESSFUL`; `.\gradlew.bat build --no-daemon --max-workers=1` — `BUILD SUCCESSFUL`(2분 20초), 65 suites / 564 tests / 실패·오류·스킵 0 (HEAD `fca5233`) | issue-54-plan.md Task 5·완료 체크리스트, CLAUDE.md 규칙 4·7, ADR-0003 |
| 04:20 | implementer | `.\gradlew.bat test --tests "*NicknameChangeIntegrationTest" --no-daemon --max-workers=1` — 4/4 통과, `.\gradlew.bat spotlessApply` | issue-54-plan.md Task 4·미확정 6번(Order/Execution 미존재로 계좌까지만 검증), ADR-0003 Testcontainers 통합 테스트 |

## 모니터링 (사람용 요약)
- 14:30 — V2 마이그레이션(auth 5개 테이블) + User·EmailVerification 엔티티/Repository 추가, 컴파일 통과.
- 15:10 — EmailSender 어댑터(Fake=`!prod`·Resend=`prod` RestClient) 추가, prod yml에 resend/email 설정, 컴파일 통과.
- 15:45 — 인증번호 발송 API(`POST /api/auth/email-verifications`) 추가: Controller·EmailVerificationService(발송 제한 3종·HMAC 저장·이전 코드 무효화)·요청 DTO, api-routes 갱신, 컴파일 통과.
- 16:10 — 전체 스위트에서 깨지던 GlobalExceptionHandlerTest를 `@WebMvcTest(controllers = TestController.class)`로 슬라이스 한정, 두 테스트 클래스 통과 확인.
- 16:40 — 리뷰 완료(발송 API 범위). 차단 1건: EMAIL_VERIFICATION_SECRET 코드 내 dev 기본값(시크릿 컨벤션 위반·prod fail-open). 권장 2·참고 2.
- 18:50 — 차단 수정: EMAIL_VERIFICATION_SECRET `@Value` 기본값 제거(fail-fast), contextLoads 테스트에만 `@DynamicPropertySource`로 더미 시크릿 공급. 3개 테스트 통과.
- 19:00 — @SpringBootTest 확장성 문제 해결: 더미 시크릿을 build.gradle Test 태스크 env로 중앙화하고 `@DynamicPropertySource` 제거, 전체 `build` BUILD SUCCESSFUL.
- 21:13 — OAuth Provider 계약과 카카오·네이버 실제/Fake 프로필 구현, 환경변수 바인딩 추가, 컴파일 통과.
- 21:18 — 32바이트 URL-safe/no-padding OAuth state 생성기와 callback 경로 한정 10분 보안 쿠키 팩토리 추가, 컴파일 통과.
- 21:21 — OAuth 인가 service와 302 Controller 추가, 환경변수 예시·API 라우트 동기화, 컴파일 통과.
- 21:30 — prod·oauth-real에서 state 쿠키 Secure=false 기동 차단, 실제 Provider 필수 설정 공백 검증 추가, 컴파일 통과.
- 21:53 — HEAD `35a9feeb00f2330053de8c88b7667b6d1354a538`에서 직렬 최종 build를 실행해 BUILD SUCCESSFUL 확인.
- 17:11 — Issue #5 Task 1: `AuthenticatedUser` record와 `JwtTokenProvider.parseAccessToken`(주입 Clock 기반 만료 판정, 실패 시 `Optional.empty()`) 추가, 컴파일 통과.
- 17:20 — Issue #5 Task 2: Spring Security 체인·Bearer 필터·401/403 핸들러 추가, `RequestIdFilter`에 `@Order` 부여, 기존 슬라이스 4종 정리. `spring-boot-starter-security-test`가 없으면 `@WebMvcTest`에 Security 체인이 아예 붙지 않는 것을 실측해 test 의존성으로 추가.
- 17:30 — Issue #5 Task 4: `AuthService.login`(원인 불문 동일 401, 기존 Refresh Token 유지) 추가하고 signup 말미 발급 블록을 `issueTokenPair` private 메서드로 추출해 공유, 컴파일 통과.
- 17:37 — Issue #5 Task 5: `LoginRequest`(비밀번호 최소 길이 미적용)와 `POST /api/auth/login`(200) 추가, api-routes.md에 라우트·로그인 상세 표 반영, 컴파일 통과.
- 20:45 — Issue #8 Task 1: `SignupMethod` enum·`MemberResponse` record·`SocialAccountRepository.findByUserId`·`AuthService.getMe`(회원 없으면 401)를 TDD로 추가해 `AuthServiceTest` 32개 통과. 이 구현은 공유 워크트리에서 다른 세션이 쓰던 `feat/24-community-post-list` 위에 실수로 커밋됐다가(해당 브랜치의 별개 머지 문제를 잘못 복원하는 커밋도 함께 생성됨) 오케스트레이터가 발견해 실제 작업 대상인 `feat/008-auth-me`로 `git cherry-pick`했다. `feat/24-community-post-list`는 원래 tip으로 되돌려 원상복구했다.
- 20:57 — Issue #8 Task 2: 보호된 `GET /api/auth/me`를 TDD로 추가해 `AuthControllerTest` 48개 통과. 응답 키 집합을 `jsonPath("$.*", hasSize(4))`로 고정해 민감 필드 유출을 회귀 차단했고, `SecurityConfig`는 화이트리스트 밖 기본 보호만으로 401이 나오는 것을 확인해 수정하지 않았다.
- 21:06 — Issue #8 Task 3: `MeIntegrationTest` 5건을 실제 MySQL에서 통과시켰다. 이메일·카카오·네이버 가입자를 실제로 만들어 `signupMethod`가 저장된 `SocialAccount` 행으로 판별됨을 확인하고, 주입된 `ObjectMapper`로 직렬화해 응답 키 4개 고정과 `passwordHash` 미노출을 회귀 차단했다. 처음에 Jackson 2 `com.fasterxml` 패키지로 `ObjectMapper`를 주입해 `NoSuchBeanDefinitionException`이 났고 이 프로젝트가 쓰는 Jackson 3 `tools.jackson`으로 교체했다.
- 18:01 — 401·403 핸들러 2종의 수기 생성자를 `@RequiredArgsConstructor`로 교체(conventions Lombok 규칙 준수)해 SpotBugs EI_EXPOSE_REP2 2건 해소, `spotbugsMain` Total Warnings 0. exclude.xml·새 의존성은 추가하지 않았다.
- 22:15 — Issue #8 Task 4: `GET /api/auth/me` 실제 계약(응답 4필드·401 공통 포맷·SecurityConfig 미변경)을 api-routes.md에 반영하고 tasks.md의 JWT·Security 항목을 완료 처리했다. plan.md API 표는 313fec8에서 이미 갱신돼 있어 확인만 했고, 전체 `build`를 직렬로 돌려 BUILD SUCCESSFUL을 확인했다.
- 22:30 — 리뷰 판정: 차단 0건. AUTH-005 조회 계약(민감 필드 미노출·가입 방식 판별)과 D1~D7 설계가 구현·테스트에 그대로 반영됨을 확인, 머지 가능.
- 23:30 — Issue #53 Task 1: `OAuthPurpose`·`OAuthStateClaims`와 HMAC-SHA-256 서명/검증(`generate(purpose, userId)`·`verify`, 실패는 전부 403 `REAUTHENTICATION_FAILED`)을 `OAuthStateGenerator`에 추가하고 `OAUTH_STATE_SECRET`을 yml·.env.example·build.gradle test 환경에 배선했다. `OAuthAuthorizationService.authorize`는 `generate(LOGIN, null)` 호출로만 바꿔 302 계약은 그대로 회귀 통과했다.
- 01:10 — Issue #53 Task 5: HEAD `c7543d6`·워킹트리 clean 상태에서 전체 `build`(Spotless·SpotBugs·JaCoCo 40% 포함)를 돌려 519 tests 전부 통과했다. authorize(reauth)→callback(reauth)는 `OAuthReauthCallbackIntegrationTest` 4건, authorize(login)→callback(login)은 `FakeOAuthFlowIntegrationTest` 5건·`OAuthLoginIntegrationTest` 6건으로 같은 빌드 안에서 함께 실행됨을 테스트 결과 XML로 확인했다. api-routes.md는 Task 3·4에서 이미 authorize·callback 양쪽을 갱신해 추가 변경이 필요 없었고, tasks.md의 Issue #53 5번째 항목만 체크했다.
- 00:45 — Issue #53 Task 4: `authorizeForReauth`(provider 해석은 private `createAuthorization`으로 공유), `authorizeReauth` 컨트롤러 메서드(`params="purpose=reauth"`, 200 `OAuthReauthorizeResponse`), 기존 `authorize`의 purpose 방어 검증, `SecurityConfig`의 조건부 공개 매처를 추가했다. **계획서 D6의 `AntPathRequestMatcher`는 Spring Security 7.1에서 제거돼 존재하지 않아** jar 내용을 직접 확인하고 `PathPatternRequestMatcher`로 대체했다(`AndRequestMatcher`는 그대로 사용). 기존 302 계약은 `FakeOAuthFlowIntegrationTest`로 회귀 확인했다.
- 00:20 — Issue #53 Task 3: `OAuthCallbackService`에 `stateGenerator.verify` 호출과 purpose 분기를 넣고 서비스·컨트롤러 반환 타입을 `Object`로 바꿨다. 실제 MySQL 통합 테스트를 처음 돌려서야 Task 1이 심어둔 결함(생성자 2개 `@Component`에 `@Autowired` 누락 → `@SpringBootTest` 전체가 `No default constructor found`)이 드러났고, 이는 컴파일·단위 테스트만으로는 잡히지 않아 `docs/agent-mistakes.md`에 기록했다. `ErrorCodeTest`의 코드 개수 고정(19→20)도 Task 1 여파로 함께 갱신했다.
- 00:05 — Issue #53 Task 2: `V5__create_reauth_tokens_table.sql`·`ReauthToken`·`ReauthTokenRepository`·`ReauthTokenGenerator`·`ReauthTokenResponse`를 추가하고 `AuthService.reauthenticate`(회원·소셜계정 조회 후 해시만 저장, 실패는 전부 403)를 구현했다. `AuthService` 생성자가 2개 늘어 `AuthServiceTest`·`OAuthAuthServiceTest`의 생성 호출부를 갱신했고 기존 케이스는 그대로 통과했다. Docker가 없어 `@DataJpaTest`·Testcontainers 검증은 tester에게 위임한다.
- 09:40 — Issue #53 리뷰 판정: 차단 0건. HMAC 상수 시간 검증, reauthenticate 회원/계좌 불변, purpose 분기 순서, SecurityConfig 안전 기본값, 이전 로그인 계약 무회귀를 D1~D8과 대조해 확인, 머지 가능.
- 02:14 — Issue #54 Task 1: `ReauthTokenRepository.consumeIfValidForUser`(해시·소유자·미소비·미만료를 한 번의 조건부 UPDATE로 확인, `RefreshTokenRepository.revokeIfActiveAndNotExpired` 선례 재사용)와 `UserRepository.existsByNicknameAndIdNot`을 TDD로 추가했다. 벌크 UPDATE는 영속성 컨텍스트를 우회하므로 테스트에서 `EntityManager.clear()` 후 재조회로 `consumedAt` 값을 검증했고, 재사용·만료·타인 소유 케이스가 모두 0을 반환함을 실제 MySQL로 확인했다.
- 02:59 — Issue #54 Task 2: `User.changeNickname`(닉네임+`updatedAt`만 갱신)과 `AuthService.changeNickname`(가입 방식은 DB의 `SocialAccount`로 판별, 이메일은 비밀번호 대조·OAuth는 sha256 해시로 `consumeIfValidForUser` 소비, 사전 중복 확인 + `saveAndFlush`의 `DataIntegrityViolationException` 둘 다로 409 방어)을 TDD로 추가해 `AuthServiceTest` 47/47이 통과했다. 새 의존성·ErrorCode·마이그레이션은 추가하지 않았고, 계획 D5의 비밀번호 대조는 `passwordHash` null 방어 없이 그대로 구현했다(EMAIL 분기는 `SocialAccount`가 없는 회원만 도달하므로 null이 나올 수 없다).
- 04:20 — Issue #54 Task 4: `NicknameChangeIntegrationTest` 4건을 실제 MySQL(Docker 가용)에서 통과시켰다. OAuth 준비는 커밋된 상태를 실제로 재조회해야 해서 `@Transactional` 롤백형 MockMvc 콜백 대신 `authService.oauthLogin` + Issue #53 `reauthenticate` 서비스 경로로 유니크 데이터를 만들었고(고정 `fake-oauth-user`·고정 이메일 충돌 회피), 계좌 불변은 `id·market·cashBalance·seedMoney·realizedPnl·createdAt·updatedAt` 스냅샷 비교로 검증했다. 주문·체결 도메인은 부재해 계획 미확정 6번대로 제외했다.
- 03:25 — Issue #54 Task 5: HEAD `fca5233`(코드 워킹트리 clean) 상태에서 전체 `build`(Spotless·SpotBugs·JaCoCo 40% 포함)를 돌려 564 tests 전부 통과했다. `docs/api-routes.md`는 Task 3에서 이미 라우트·상세 계약·보호 경로를 갱신해 두었고 실제 `AuthController`의 `@PatchMapping("/me/nickname")`과 일치함을 확인해 추가 변경이 없었으며, `tasks.md`의 JWT·Security 항목에 Issue #54 완료를 명시하고 작업 항목 5개 절을 추가했다. 코드 수정은 필요 없었다.
- 03:35 — Issue #54 Task 3: `NicknameUpdateRequest`(nickname만 `@NotBlank`, 재인증 필드 2개는 선택)와 보호된 `PATCH /api/auth/me/nickname`을 TDD로 추가해 `AuthControllerTest` 59/59가 통과했다. 응답 키 집합을 `hasSize(4)`로 고정하고 `currentPassword`·`reauthToken`·`passwordHash` 부재를 명시 검증했다. 인증 없음·Refresh Bearer 케이스는 매핑 추가 전부터 Security 체인이 401로 막아 `SecurityConfig`는 수정하지 않았고, api-routes.md에 라우트·상세 계약·보호 경로를 같은 커밋에서 동기화했다.
