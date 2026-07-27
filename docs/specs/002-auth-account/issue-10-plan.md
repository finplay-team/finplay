# Issue #10 카카오·네이버 OAuth 콜백 API 구현 계획

> **For agentic workers:** 각 작업은 실패 테스트를 먼저 작성하고, Fake 자동 검증과 실제 공급자 스모크 결과를 서로 대체하거나 합쳐 보고하지 않는다.

**Goal:** `GET /api/auth/oauth/{provider}/callback`에서 state를 검증한 뒤 인가 코드를 교환하고 사용자 정보를 조회한다. 기존 소셜 회원은 기존 회원으로 로그인시키고, 신규 소셜 회원은 `social_accounts`와 STOCK·CRYPTO 계좌를 원자적으로 생성한 뒤 FinPlay Access·Refresh JWT를 발급한다.

**관련 정본:** GitHub Issue #10, PRD `AUTH-003`·`ACCT-001`·`C-005`, `spec.md`, `plan.md`, Issue #9 계획, ADR-0002, `docs/conventions.md`

**선행:** Issue #9의 `GET /api/auth/oauth/{provider}/authorize`, `OAuthProviderName`, 10분 `oauth_state` 쿠키, Fake/실제 OAuth 프로필 분기가 `origin/dev`에 존재한다.

**Architecture:** `OAuthCallbackController → OAuthCallbackService → OAuthCallbackProvider`로 state 검증·인가 코드 교환·사용자 정보 조회를 조정한다. 외부 HTTP 호출이 끝난 뒤 기존 `AuthService`의 별도 `@Transactional` OAuth 로그인 메서드가 `UserRepository`·새 `SocialAccountRepository`·`AccountService`·Refresh Token 저장을 조합한다. 외부 네트워크 대기 중에는 DB 트랜잭션을 열지 않는다.

**Tech Stack:** Java 17, Spring Boot 4.1, Spring Security, Spring Data JPA, `RestClient`, JJWT, MySQL 8.4 Testcontainers, JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

---

## 요구사항 ID와 수용 기준

### PRD `AUTH-003`

- `KAKAO`, `NAVER`를 공통 OAuth 어댑터 계약으로 처리한다.
- 회원 식별자는 이메일이 아니라 `provider + providerUserId`다.
- 같은 `provider + providerUserId`는 한 회원에게만 연결된다.
- 최초 로그인은 회원·`social_accounts`·두 계좌를 같은 트랜잭션에서 생성하고 FinPlay JWT를 발급한다.
- OAuth 회원은 FinPlay 이메일 인증을 거치지 않는다.
- 공급자가 이메일을 제공하지 않으면 400 `OAUTH_EMAIL_REQUIRED`다.
- 같은 이메일의 기존 회원을 자동 연결하지 않고 409 `ACCOUNT_LINK_REQUIRED`로 거부한다.
- 자동 테스트는 Fake OAuth Provider를 사용하고 실제 공급자 검증과 구분한다.

### PRD `ACCT-001`

- 신규 OAuth 회원에게 STOCK·CRYPTO 계좌를 정확히 하나씩 생성한다.
- 각 계좌의 `seed_money`와 최초 `cash_balance`는 10,000,000원이다.
- `UNIQUE(user_id, market)`를 유지한다.

### PRD `C-005`

- Fake·Mock·Testcontainers 성공을 실제 카카오·네이버 OAuth 성공으로 표현하지 않는다.
- 실행한 자동 검증, 공급자별 실제 스모크, 미실행 사유와 남은 위험을 분리한다.

### Issue #10 수용 시나리오

1. Given Issue #9 authorize 응답의 `oauth_state` 쿠키와 동일한 query `state`, 유효한 `code`가 있는 상태
2. When `GET /api/auth/oauth/kakao/callback` 또는 `/naver/callback`
3. Then state를 상수 시간 비교하고 state 쿠키를 즉시 만료시킨 뒤, 해당 공급자에서 코드를 Access Token으로 교환하고 사용자 ID·이메일을 조회한다.
4. And `provider + providerUserId`가 이미 있으면 새 회원·소셜 계정·계좌를 만들지 않고 그 회원의 FinPlay JWT를 발급한다.
5. And 조합이 없고 이메일 충돌도 없으면 회원·소셜 계정·STOCK/CRYPTO 계좌를 한 트랜잭션에서 만들고 FinPlay JWT를 발급한다.
6. And 이메일이 없으면 400 `OAUTH_EMAIL_REQUIRED`, 같은 이메일 회원이 있으면 아무 행도 만들지 않고 409 `ACCOUNT_LINK_REQUIRED`다.
7. And 신규 가입 중 소셜 계정·계좌·Refresh Token 저장이 실패하면 회원을 포함한 DB 변경 전체가 롤백된다.
8. And state 누락·쿠키 누락·불일치는 외부 공급자를 호출하지 않고 400 `VALIDATION_ERROR`로 거부한다.
9. And 성공·실패 여부와 무관하게 callback 응답은 Issue #9와 같은 Path의 `oauth_state` 만료 쿠키(`Max-Age=0`)를 포함한다.
10. And 사용자가 인가를 취소하거나 authorization code가 만료·재사용되면 400 `OAUTH_AUTHORIZATION_FAILED` 공통 오류로 응답한다.
11. And 공급자 장애·timeout·malformed response는 502 `OAUTH_PROVIDER_ERROR` 공통 오류로 응답한다.

---

## 범위와 제외

### 포함

- `GET /api/auth/oauth/{provider}/callback`
- `KAKAO`·`NAVER`의 인가 코드 교환과 사용자 정보 조회
- local·test의 `FakeOAuthCallbackProvider`와 Fake 기반 자동 회귀 테스트
- query `state`와 `oauth_state` 쿠키의 UTF-8 바이트 `MessageDigest.isEqual` 비교
- state 쿠키의 1회 소비와 성공·실패 응답에서의 즉시 만료
- 기존 소셜 회원 로그인
- 신규 회원·`social_accounts`·계좌 2개·Refresh Token 해시의 원자 저장
- `OAUTH_EMAIL_REQUIRED`·`ACCOUNT_LINK_REQUIRED`·`VALIDATION_ERROR`·`OAUTH_AUTHORIZATION_FAILED`·`OAUTH_PROVIDER_ERROR` 공통 오류
- callback 공개 경로 Security 설정과 `docs/api-routes.md` 동기화
- 카카오·네이버 각각의 실제 브라우저 스모크와 DB 검증

### 제외

- 이메일 일치만으로 기존 회원에 소셜 계정을 연결하는 기능
- 한 회원에 다른 공급자를 명시적으로 추가 연결하는 기능
- 카카오·네이버 Access/Refresh Token 저장·재발급·로그아웃·연결 해제
- 프론트엔드 성공 페이지나 토큰 전달 방식 변경
- OAuth 공급자 추가, 범용 OAuth 프레임워크·Facade·Manager
- 기존 V2 Flyway 마이그레이션 수정 또는 스키마 변경
- 투자일기·AI·랭킹·알림·지정가·Kafka·별도 동시성 제어

---

## 설계 결정

### D1. state를 외부 호출보다 먼저 검증하고 쿠키는 항상 소비한다

- Controller는 `code`, query `state`, `oauth_state` 쿠키를 받고 callback 서비스를 호출한다.
- `OAuthCallbackService`는 provider를 해석한 뒤 query/cookie state의 존재와 일치를 검증한다. 비교는 두 문자열의 UTF-8 바이트에 `MessageDigest.isEqual`을 사용한다.
- 실패 시 400 `VALIDATION_ERROR`이며 `OAuthCallbackProvider`와 DB 서비스는 호출하지 않는다.
- `OAuthStateCookieFactory`에 같은 이름·Path·보안 속성과 `Max-Age=0`을 쓰는 만료 쿠키 생성을 추가한다.
- Controller는 서비스 호출 전에 만료 `Set-Cookie` 헤더를 응답에 등록해 이후 state 검증, 외부 공급자, DB 단계에서 예외가 나도 쿠키가 남지 않게 한다. 테스트는 성공·모든 오류 응답의 헤더를 검증한다.
- query의 `code`, `state`, 쿠키 원문은 로그·예외 메시지·requestId 부가정보에 남기지 않는다.

### D2. 인가와 callback 어댑터 책임을 분리한다

Issue #9의 `OAuthAuthorizationProvider`는 인가 URI 생성만 담당하므로 callback 외부 통신은 별도 계약으로 둔다.

```java
boolean supports(OAuthProviderName provider);
OAuthUserDto fetchUser(String authorizationCode, String state);
```

- `OAuthUserDto`는 service 간 내부 전달 record이며 `providerUserId`, `email`만 포함한다.
- `KakaoOAuthCallbackProvider`, `NaverOAuthCallbackProvider`: `prod | oauth-real`
- `FakeOAuthCallbackProvider`: `!prod & !oauth-real`, KAKAO·NAVER 모두 지원
- 외부 Provider Access Token은 메서드 지역 값으로만 사용하고 DB·로그·응답에 저장하지 않는다.
- HTTP 응답 DTO는 공급자 JSON 구조를 그대로 모델링하되 controller 응답 DTO로 재사용하지 않는다.

### D3. 공급자별 실제 HTTP 계약

| 공급자 | 코드 교환 | 사용자 정보 | 식별·이메일 |
|---|---|---|---|
| 카카오 | `POST https://kauth.kakao.com/oauth/token`, form-urlencoded `grant_type=authorization_code`, client ID, redirect URI, code, Client Secret | `GET https://kapi.kakao.com/v2/user/me`, `Authorization: Bearer <Provider Access Token>` | `id`, `kakao_account.email` |
| 네이버 | `POST https://nid.naver.com/oauth2.0/token`, `grant_type=authorization_code`, client ID, Client Secret, code, state | `GET https://openapi.naver.com/v1/nid/me`, `Authorization: Bearer <Provider Access Token>` | `response.id`, `response.email` |

- URI·form 파라미터는 Spring URI/form 빌더로 인코딩하고 문자열 이어붙이기를 하지 않는다.
- 공급자 오류 본문 전체, 인가 코드, Provider Access Token을 예외나 로그에 포함하지 않는다.
- callback query의 사용자 취소 응답과 공급자가 만료·재사용 authorization code를 거부한 응답은 400 `OAUTH_AUTHORIZATION_FAILED`로 정규화한다.
- 공급자 5xx·연결 실패·timeout 또는 Access Token·사용자 ID 등 필수 필드가 없는 malformed response는 502 `OAUTH_PROVIDER_ERROR`로 정규화한다.
- 실제 API 계약은 구현 시 카카오·네이버 공식 문서와 다시 대조한다.

### D4. 실제 OAuth 설정은 환경변수 전용이다

| 환경변수 | 용도 |
|---|---|
| `KAKAO_CLIENT_ID` | 카카오 REST API Client ID |
| `KAKAO_CLIENT_SECRET` | 카카오 코드 교환 Client Secret |
| `KAKAO_REDIRECT_URI` | 카카오 개발자 콘솔과 정확히 같은 callback URL |
| `NAVER_CLIENT_ID` | 네이버 Client ID |
| `NAVER_CLIENT_SECRET` | 네이버 Client Secret |
| `NAVER_REDIRECT_URI` | 네이버 개발자 콘솔과 정확히 같은 callback URL |
| `OAUTH_STATE_COOKIE_SECURE` | `oauth-real`에서는 반드시 `true` |

- 실제 값은 셸·CI/배포 시크릿 또는 gitignore된 `.env`로만 주입한다.
- Client ID/Secret, Provider Access Token, authorization code를 코드·설정 기본값·문서·테스트 fixture·run-log·PR·스크린샷에 기록하지 않는다.
- `.env.example`에는 변수 이름과 설명만 유지한다.
- 환경변수, 개발자 콘솔 Callback URL, 이메일 제공 동의 상태가 확인되지 않으면 값을 추측하거나 설정 완료로 간주하지 않는다.

### D5. 외부 호출과 DB 트랜잭션을 분리한다

1. `OAuthCallbackService`가 state를 검증한다.
2. 활성 `OAuthCallbackProvider`가 코드 교환과 사용자 정보를 완료한다.
3. 이메일이 없으면 DB 진입 전에 400 `OAUTH_EMAIL_REQUIRED`다.
4. 기존 `AuthService.oauthLogin(provider, OAuthUserDto)`를 Spring 프록시를 거쳐 호출한다.
5. `AuthService`의 단일 `@Transactional` 경계에서 다음을 수행한다.
   - `social_accounts(provider, provider_user_id)` 조회
   - 기존 조합이면 연결된 기존 User로 FinPlay JWT·Refresh Token 발급
   - 신규 조합이면 같은 이메일 User 존재 여부 확인 후 409 `ACCOUNT_LINK_REQUIRED`
   - User, SocialAccount, STOCK·CRYPTO Account, Refresh Token 해시 저장

외부 HTTP 호출은 DB 트랜잭션 밖에 있어 공급자 지연 동안 DB 연결·락을 점유하지 않는다.

### D6. 기존 소셜 로그인과 신규 가입의 영속성

- `SocialAccount` 엔티티와 `SocialAccountRepository`를 기존 `social_accounts` 테이블에 매핑한다.
- 신규 저장은 기존 V2의 `UNIQUE(provider, provider_user_id)`를 실제 MySQL `@DataJpaTest`로 검증한다.
- 기존 소셜 조합에서는 공급자 이메일이 바뀌어도 식별자는 조합을 정본으로 삼고 기존 User를 사용한다.
- 신규 조합인데 `users.email`이 이미 존재하면 해당 회원이 비밀번호 회원인지 다른 소셜 회원인지와 무관하게 자동 연결하지 않고 409로 거부한다.
- 신규 회원 nickname은 `finplay-` 뒤에 `SecureRandom` 6바이트를 소문자 hex 12자리로 인코딩해 만든다. 전체 형식은 `^finplay-[0-9a-f]{12}$`다.
- nickname에는 이메일과 providerUserId의 전체 또는 일부를 넣지 않는다.
- 생성할 때마다 `UserRepository.existsByNickname`으로 충돌을 확인하고 최대 5회까지 새 난수를 생성한다. 5회 모두 충돌하면 후보 값이나 회원 식별정보를 노출하지 않는 기존 공통 500 내부 오류로 중단하며 User·SocialAccount·Account·Refresh Token을 저장하지 않는다.
- 회원·소셜 계정·계좌·Refresh Token 중 하나라도 실패하면 같은 트랜잭션 전체를 롤백한다.

### D7. HTTP 계약

| 항목 | 내용 |
|---|---|
| Method / Path | `GET /api/auth/oauth/{provider}/callback` |
| 인증 | 공개 경로. Issue #9의 state 쿠키 검증 필수 |
| 입력 | 성공 시 query `code`, 취소 시 query `error`; 공통 query `state`, cookie `oauth_state` |
| 성공 | 200 `TokenResponse` |
| 미지원 provider, state 누락·불일치 | 400 `VALIDATION_ERROR` |
| 사용자 인가 취소, 만료·재사용 authorization code | 400 `OAUTH_AUTHORIZATION_FAILED` |
| 공급자 이메일 없음 | 400 `OAUTH_EMAIL_REQUIRED` |
| 신규 조합의 이메일 충돌 | 409 `ACCOUNT_LINK_REQUIRED` |
| 공급자 장애·timeout·malformed response | 502 `OAUTH_PROVIDER_ERROR` |
| 공통 | 성공·실패 모두 state 만료 `Set-Cookie` |

`TokenResponse`는 기존 로그인·가입 응답과 같은 accessToken, refreshToken, 두 만료 초 필드를 재사용한다. callback은 `SecurityConfig` 공개 GET 경로에 추가한다.

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/auth/domain/SocialAccount.java`
- `src/main/java/com/finplay/api/auth/repository/SocialAccountRepository.java`
- `src/main/java/com/finplay/api/auth/oauth/OAuthCallbackProvider.java`
- `src/main/java/com/finplay/api/auth/oauth/OAuthUserDto.java`
- `src/main/java/com/finplay/api/auth/oauth/KakaoOAuthCallbackProvider.java`
- `src/main/java/com/finplay/api/auth/oauth/NaverOAuthCallbackProvider.java`
- `src/main/java/com/finplay/api/auth/oauth/FakeOAuthCallbackProvider.java`
- `src/main/java/com/finplay/api/auth/service/OAuthCallbackService.java`
- `src/main/java/com/finplay/api/auth/controller/OAuthCallbackController.java`

### Production files to modify

- `src/main/java/com/finplay/api/auth/oauth/OAuthStateCookieFactory.java`
- `src/main/java/com/finplay/api/auth/service/AuthService.java`
- `src/main/java/com/finplay/api/auth/config/SecurityConfig.java`
- `src/main/java/com/finplay/api/common/ErrorCode.java` — 승인된 OAuth 오류 코드 2개 추가
- `src/main/resources/application.yml`
- `.env.example` — 필요한 변수 이름·설명만 확인하며 실제 값은 금지

### Test files

- Provider 단위/HTTP 계약 테스트와 profile 빈 선택 테스트
- `OAuthStateCookieFactoryTest`, callback service·controller·Security 테스트
- `SocialAccountRepositoryTest`
- `AuthServiceTest`
- Fake OAuth callback MySQL 통합 테스트
- 신규 가입 중 실패 롤백 MySQL 통합 테스트

### Documentation files to modify after implementation

- `docs/api-routes.md`
- `docs/specs/002-auth-account/tasks.md`
- `docs/specs/002-auth-account/run-log.md`
- PR 본문의 Issue #10 검증 표

### 수정하지 않을 파일

- `src/main/resources/db/migration/V2__create_auth_account_tables.sql`
- 새 Flyway 마이그레이션
- Issue #9 authorize 계약의 URL·state 생성 규칙

---

## Task 1: callback state·쿠키·HTTP 경계

- [x] 실패 테스트에서 KAKAO/NAVER provider 해석, query/cookie state의 상수 시간 일치, 누락·불일치 400, 외부 호출 없음, 모든 응답의 state 쿠키 만료를 고정한다.
- [x] `OAuthStateCookieFactory` 만료 기능, `OAuthCallbackService`의 state 선검증, `OAuthCallbackController`의 200 `TokenResponse` 계약을 최소 구현한다.
- [x] callback을 공개 GET 경로로 추가하고 미인증 접근은 callback 로직까지 도달하지만 다른 보호 경로는 그대로 401인지 Security 회귀 테스트로 확인한다.
- [x] 인가 코드·state·쿠키 원문이 오류 메시지와 테스트 출력에 노출되지 않는지 확인한다.

## Task 2: Fake·카카오·네이버 callback Provider

- [x] `OAuthCallbackProvider`와 `OAuthUserDto` 계약을 실패 테스트로 고정한다.
- [x] Fake Provider가 외부 통신 없이 KAKAO/NAVER의 결정적 사용자 ID·이메일을 반환하도록 구현한다.
- [x] 카카오·네이버 코드 교환과 사용자 정보 조회를 `RestClient` 기반으로 구현하고 Mock HTTP 서버에서 method, URL, header, form, JSON 매핑을 검증한다.
- [x] 사용자 취소·만료/재사용 code는 400 `OAUTH_AUTHORIZATION_FAILED`, 공급자 장애·timeout·malformed response는 502 `OAUTH_PROVIDER_ERROR`로 정규화하고 민감한 공급자 응답은 노출하지 않는다.
- [x] local·test에서는 Fake만, `prod | oauth-real`에서는 실제 두 Provider만 활성화되고 실제 프로필의 Client ID/Secret/redirect URI 누락·공백은 fail-fast인지 검증한다.
- [x] Provider Access Token과 authorization code를 저장·로그·응답하지 않는다.

## Task 3: 안전한 nickname과 기존/신규 소셜 로그인 트랜잭션

- [x] 신규 OAuth nickname 생성기를 테스트로 먼저 고정한다: `finplay-` + `SecureRandom` 기반 소문자 hex 12자리, 이메일/providerUserId 미포함.
- [x] `existsByNickname` 충돌 시 새 난수를 최대 5회 생성하고, 모두 충돌하면 식별정보를 노출하지 않는 공통 500 내부 오류이며 저장이 전혀 없는지 검증한다.
- [x] `SocialAccount`·Repository를 V2 스키마에 매핑하고 실제 MySQL에서 `(provider, provider_user_id)` 유일성을 검증한다.
- [x] 기존 조합은 기존 User로 JWT만 발급하며 User·SocialAccount·Account를 추가 생성하지 않는다.
- [x] 신규 조합은 이메일 없음 400, 이메일 충돌 409를 저장 전에 거부한다.
- [x] 신규 성공은 User 1·SocialAccount 1·STOCK/CRYPTO Account 2·Refresh Token 해시 1을 원자 저장한다.
- [x] 소셜 계정·계좌·Refresh Token 저장 실패 각각에서 회원을 포함한 전체 롤백을 실제 MySQL 통합 테스트로 검증한다.

## Task 4: Fake 자동 회귀·전체 게이트·문서

- [x] Fake authorize→callback→코드 교환 대체→사용자 정보 대체→신규 가입→JWT 전 흐름을 자동 통합 테스트로 검증한다.
- [x] 같은 Fake `provider + providerUserId` 재호출은 기존 회원 로그인이고 중복 행이 없음을 검증한다.
- [x] 이메일 미제공, 일반 회원 충돌, state 오류, 사용자 취소·만료/재사용 code, 공급자 장애·timeout·malformed response, 신규 가입 롤백을 자동 회귀 테스트로 유지한다.
- [x] Issue #9 authorize/state 테스트와 기존 auth-account 회귀를 포함한 대상 테스트, Spotless, `.\gradlew.bat build --no-daemon --max-workers=1`을 실행한다.
- [x] 실제 Controller 매핑 기준으로 `docs/api-routes.md`를 동기화하고 자동 검증 결과만 run-log·PR의 “자동 테스트” 영역에 기록한다.
- [x] 실제 `oauth-real` 컨텍스트에서 `RestClient.Builder` 자동설정 누락을 재현하고 `spring-boot-restclient`, `OAuthRealContextIntegrationTest`, timeout counterfactual 테스트로 보완한 뒤 실제 jar 기동과 authorize 302를 확인한다.

## Task 5: 공급자별 실제 OAuth 스모크와 PR 완료 게이트

- [x] `oauth-real` 프로필에서 카카오 환경변수·개발자 콘솔 Callback URL·이메일 동의를 사람이 확인한 뒤 브라우저 authorize부터 시작한다.
- [x] 카카오 로그인·이메일 동의를 사용자 조작으로 완료하고, callback→코드 교환→사용자 정보→신규 회원→FinPlay JWT 응답 필드와 Access 3,600초·Refresh 1,209,600초를 검증한다.
- [x] 카카오 신규 회원의 `social_accounts` 1행과 STOCK/CRYPTO 계좌 2행(각 10,000,000원)을 DB에서 확인하고, 같은 카카오 계정의 기존 회원 요청 2회에서 User·SocialAccount·Account 중복이 없고 Refresh Token만 3행까지 증가함을 확인한다. 기존 회원 응답 본문은 Chrome `ERR_BLOCKED_BY_CLIENT`로 직접 확인하지 못했으나 서버의 `issueTokenPair`와 Refresh Token 커밋으로 JWT pair 발급 경로 실행을 확인했다.
- [x] 네이버는 별도의 환경 확인과 사용자 브라우저 조작을 거쳐 신규/기존/JWT/DB 흐름을 독립 검증했다. 두 번째 callback 응답 렌더링은 Chrome client의 `ERR_BLOCKED_BY_CLIENT`로 확인하지 못했으나, 서버 트랜잭션에서 두 번째 Refresh Token 행 커밋을 확인했다.
- [x] 카카오·네이버 결과를 `PASS`·`FAIL`·`NOT RUN` 중 하나와 공급자별 사유로 run-log 및 PR에 분리 기록한다. 한 공급자의 성공으로 다른 공급자까지 실제 연동됐다고 주장하지 않는다.
- [x] 두 공급자 모두 `PASS`이고 자동 회귀·전체 build도 별도로 통과해 Issue #10 PR 완료 조건을 충족한다.

---

## 실제 OAuth 스모크 절차

각 공급자를 독립 실행한다.

1. 필요한 환경변수가 셸/시크릿 저장소에 있고 저장소 파일에 값이 없는지 확인한다.
2. 개발자 콘솔의 Callback URL이 해당 `*_REDIRECT_URI`와 정확히 일치하고 이메일 제공 동의가 활성화됐는지 확인한다.
3. `oauth-real` 프로필로 애플리케이션을 시작하고 공급자 authorize URL을 브라우저에서 연다.
4. 로그인·동의 화면이 나타나면 자동 입력하지 않고 사용자 조작 완료를 기다린다.
5. callback이 state 검증, 코드 교환, 사용자 정보 조회를 거쳐 FinPlay JWT를 반환하는지 확인한다.
6. 신규 회원 DB에서 User 1, 해당 Provider SocialAccount 1, STOCK/CRYPTO Account 2와 초기 금액을 확인한다.
7. 같은 공급자 계정으로 다시 authorize부터 진행해 같은 User의 기존 회원 로그인이며 User/SocialAccount/Account 중복이 없는지 확인한다.
8. authorization code, Provider Access Token, Client ID/Secret은 명령 출력·로그·run-log·PR·스크린샷에서 마스킹하거나 아예 출력하지 않는다.

환경변수·Callback URL·이메일 동의가 부족하면 설정을 추측해 진행하지 않는다. 해당 공급자를 `NOT RUN`으로 기록하고 정확한 부족 항목만 사유로 남긴다. 사용자 브라우저 조작이 필요한 동안에는 실패 처리하지 않고 사용자 완료를 기다린다.

### PR 검증 기록

| 구분 | 공급자/명령 | 결과 | 검증 수준·사유 |
|---|---|---|---|
| 자동 회귀 | Fake OAuth 대상 테스트 | PASS | 단위·Mock HTTP·WebMvc·MySQL 8.4 Testcontainers. 실제 OAuth 아님 |
| 전체 게이트 | `.\gradlew.bat build --no-daemon --max-workers=1` | PASS | 검증 실행 HEAD `03040887451e2d842af7b987561978bf11288cc9`, `BUILD SUCCESSFUL`(13 tasks up-to-date). 이후 검증 기록 문서만 변경 |
| 실제 OAuth | KAKAO | PASS | 신규/기존·JWT·DB 검증 완료. 기존 회원 요청 2회의 응답 본문은 Chrome `ERR_BLOCKED_BY_CLIENT`로 미확인했으며 Refresh Token 행 커밋으로 서버 발급 경로 실행 확인 |
| 실제 OAuth | NAVER | PASS | 신규/기존·JWT·DB 검증 완료. 기존 회원 두 번째 응답 본문은 Chrome `ERR_BLOCKED_BY_CLIENT`로 미확인했으며 새 Refresh Token 행 커밋으로 서버 발급 경로 실행 확인 |

---

## 확정 정책과 남은 외부 준비

1. **OAuth 신규 회원 nickname (승인 완료):** `finplay-` + 암호학적 무작위 소문자 hex 12자리로 생성한다. 이메일/providerUserId를 포함하지 않고 `existsByNickname` 충돌 시 최대 5회 재생성한 뒤 안전한 공통 500 내부 오류로 중단한다.
2. **OAuth 외부 오류 (승인 완료):** 사용자 취소·만료/재사용 authorization code는 400 `OAUTH_AUTHORIZATION_FAILED`, 공급자 장애·timeout·malformed response는 502 `OAUTH_PROVIDER_ERROR`다.
3. **실제 스모크 준비·실행 (완료):** 카카오·네이버 각각 환경변수, 개발자 콘솔 Callback URL, 이메일 동의 상태를 확인한 뒤 사용자 조작으로 실제 스모크를 완료했다. 민감값 원문은 문서·로그·PR에 기록하지 않았다.

---

## 완료 체크리스트

- [x] Fake Provider 기반 신규/기존 OAuth와 오류·롤백 자동 회귀가 통과한다.
- [x] nickname 형식·비식별성·최대 5회 충돌 재시도와 안전한 실패가 통과한다.
- [x] `OAUTH_AUTHORIZATION_FAILED` 400과 `OAUTH_PROVIDER_ERROR` 502 분류 테스트가 통과한다.
- [x] 기존 Issue #9 authorize/state 및 auth-account 전체 회귀가 유지된다.
- [x] 신규 OAuth 회원의 SocialAccount 1·계좌 2·Refresh Token 해시가 회원과 원자 저장된다.
- [x] 실제 카카오 OAuth 전체 흐름과 신규/기존/DB 검증이 `PASS`다. 단, 기존 회원 요청 2회의 HTTP 응답 본문은 Chrome 제한으로 직접 확인하지 못해 서버 트랜잭션·DB 증거로 확인한 범위를 run-log에 별도 기록한다.
- [x] 실제 네이버 OAuth 전체 흐름과 신규/기존/DB 검증이 `PASS`다. 단, 기존 회원 두 번째 HTTP 응답의 브라우저 렌더링 제한은 run-log에 별도 기록한다.
- [x] 자동 테스트와 실제 카카오·네이버 결과가 run-log와 PR에 별도 기록된다.
- [x] 시크릿·Provider Access Token·authorization code가 저장소·로그·검증 기록에 없다.
- [x] `docs/api-routes.md`가 실제 callback Controller와 일치한다.
- [x] 현재 HEAD에서 대상 테스트와 전체 build를 새로 실행해 결과를 기록한다.
