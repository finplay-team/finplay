# Plan: 인증과 계좌

## 관련 문서
- Spec: `./spec.md`
- 관련 ADR: ADR-0002 (도메인 패키지), ADR-0003 (테스트 전략), ADR-0004 (Flyway)
- PRD: §5 인증 API, §6 데이터 모델, §7 트랜잭션 경계
- 선행: `001-foundation` (공통 오류 체계·Redis)

## API 설계

Base URL `/api`. 인증 표시가 없는 것은 공개 엔드포인트.

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| POST | /api/auth/email-verifications | `EmailVerificationRequest` (email) | 202 (본문 없음) | 인증번호 발송. 발송 제한 적용 |
| POST | /api/auth/email-verifications/confirm | `EmailVerificationConfirmRequest` (email, code) | `SignupTokenResponse` | 인증번호 확인 + 가입 토큰 발급 |
| POST | /api/auth/signup | `SignupRequest` (email, nickname, password, termsAgreed, signupVerificationToken) | 201 `TokenResponse` | 토큰 소비 + 가입 + 계좌 2개 생성 + 토큰 발급 |
| POST | /api/auth/login | `LoginRequest` (email, password) | `TokenResponse` | 로그인 |
| POST | /api/auth/refresh | `RefreshRequest` (refreshToken) | `TokenResponse` | 재발급 + 이전 토큰 폐기(회전) |
| POST | /api/auth/logout | `RefreshRequest` | 204 | Refresh Token 폐기. 인증 필요 |
| GET | /api/auth/me | - | `MemberResponse` (id, email, nickname) | 내 정보. 인증 필요 |
| GET | /api/auth/oauth/{provider}/authorize | - | 302 리다이렉트 | provider = kakao·naver |
| GET | /api/auth/oauth/{provider}/callback | code 쿼리 | `TokenResponse` | 신규 가입 후 토큰 발급. 이메일 미제공 400 `OAUTH_EMAIL_REQUIRED`, 같은 이메일의 기존 회원 존재 409 `ACCOUNT_LINK_REQUIRED` |

- `TokenResponse` = accessToken, refreshToken, 만료 정보.
- `SignupTokenResponse` = signupVerificationToken, expiresInSeconds(1800).
- 오류는 001의 공통 포맷. 중복은 `DUPLICATE_RESOURCE`(409), 인증 실패는 `UNAUTHORIZED`(401).

## 입력 명세

### EmailVerificationRequest
| 필드 | 필수 | 검증 |
|---|---|---|
| email | 필수 | 이메일 형식. 위반 시 400 VALIDATION_ERROR. 이미 가입된 이메일이면 409 DUPLICATE_RESOURCE. 60초 이내 재요청·시간 5회·일 10회 초과 시 429 TOO_MANY_REQUESTS |

### EmailVerificationConfirmRequest
| 필드 | 필수 | 검증 |
|---|---|---|
| email | 필수 | 이메일 형식 |
| code | 필수 | 숫자 6자리. 불일치·만료·미발급 시 400 EMAIL_VERIFICATION_FAILED. 시도 5회 초과 시 해당 코드 무효화 후 429 TOO_MANY_REQUESTS |

### SignupRequest
| 필드 | 필수 | 검증 |
|---|---|---|
| email | 필수 | 이메일 형식. 중복 시 409 DUPLICATE_RESOURCE |
| nickname | 필수 | 공백 불가. 중복 시 409 DUPLICATE_RESOURCE |
| password | 필수 | 8자 이상. 위반 시 400 VALIDATION_ERROR |
| termsAgreed | 필수 | true만 허용. false·누락 시 400 VALIDATION_ERROR |
| signupVerificationToken | 필수 | 미존재·만료·사용됨·토큰 이메일과 불일치 시 409 EMAIL_VERIFICATION_REQUIRED |

### LoginRequest
| 필드 | 필수 | 검증 |
|---|---|---|
| email | 필수 | 이메일 형식 |
| password | 필수 | 불일치 시 401 UNAUTHORIZED (계정 존재 여부 노출 금지) |

### RefreshRequest
| 필드 | 필수 | 검증 |
|---|---|---|
| refreshToken | 필수 | 만료·폐기·미존재 시 401 UNAUTHORIZED |

## 구성 요소 설계

### auth 패키지 (`com.finplay.api.auth`)

- `AuthController` / `AuthService` — 가입·로그인·재발급·로그아웃·me.
- `EmailVerificationService` — 발송 제한 판정(60초·시간 5회·일 10회), 인증번호 생성·HMAC 저장, 이전 코드 무효화, 확인·시도 집계, 가입 토큰 발급·소비.
- `EmailSender` 인터페이스 + `ResendEmailSender`(운영, `RestClient`로 Resend HTTP API 호출) + `FakeEmailSender`(로컬·테스트). `OAuthProvider`와 동일한 어댑터 패턴.
- **프로필 분기** — `ResendEmailSender`는 운영 프로필에서만 빈으로 등록하고, 그 외 프로필의 기본 구현은 `FakeEmailSender`다. `RESEND_API_KEY`·`EMAIL_FROM`이 없어도 애플리케이션 기동과 `./gradlew build`가 성공해야 한다 (JWT처럼 무조건 필요한 값으로 바인딩하지 않는다).
- 인증번호는 `EMAIL_VERIFICATION_SECRET` 기반 HMAC-SHA-256, 가입 토큰은 32바이트 난수의 SHA-256 해시로 저장한다. 시크릿은 환경변수로만 주입하며 `JWT_SECRET`과 다른 값을 쓴다.
- `JwtTokenProvider` — 발급·검증. 라이브러리는 jjwt. 시크릿은 환경변수 주입 (conventions 시크릿 규칙).
- Security 설정 — Bearer 인증 필터, 공개 경로 화이트리스트, 401/403을 001 공통 포맷으로 응답.
- 비밀번호 해시는 BCrypt. Refresh Token 저장은 SHA-256 해시 (원문 미저장).
- OAuth 어댑터 — `OAuthProvider` 인터페이스 + `KakaoOAuthProvider` / `NaverOAuthProvider` / `FakeOAuthProvider`(테스트·로컬 프로필). 실키 없으면 Fake 프로필로 실행 가능해야 한다 (PRD §7).

### account 패키지 (`com.finplay.api.account`)

- `Account` 엔티티 + `AccountRepository` + `AccountService.createAccountsFor(user)` — STOCK·CRYPTO 각 1,000만원 생성.
- 가입 트랜잭션은 AuthService가 경계를 갖고 AccountService를 호출한다 (도메인 간 참조는 service 경유 — ADR-0002).

### 트랜잭션 경계 (PRD §7)

- 회원가입 = 가입 토큰 소비 + 회원 + 계좌 2개 원자 처리. 소비는 아래 조건부 UPDATE로 하고 영향 행이 1일 때만 진행한다.
  ```sql
  UPDATE email_verifications SET consumed_at = :now
   WHERE token_hash = :hash AND consumed_at IS NULL AND token_expires_at > :now
  ```
  트랜잭션이 롤백되면 소비도 함께 롤백되어 남은 유효시간 안에 재시도할 수 있다.
- OAuth = 신규 회원 + 계좌 2개 원자 처리. 기존 회원과의 자동 병합 경로는 없다 — 이메일이 겹치면 생성 없이 409로 거부한다.

## 데이터 모델

마이그레이션 `V{next}__create_auth_account_tables.sql`. 금액은 원 단위 BIGINT (PRD C-003).

| 테이블 | 주요 컬럼 | 제약 |
|---|---|---|
| users | id PK, email, password_hash(널 허용 — 소셜 전용), nickname, role, status, created_at, updated_at | UNIQUE(email), UNIQUE(nickname) |
| social_accounts | id PK, user_id FK, provider(KAKAO·NAVER), provider_user_id, created_at | UNIQUE(provider, provider_user_id) |
| refresh_tokens | id PK, user_id FK, token_hash, expires_at, revoked_at(널=유효), created_at | INDEX(token_hash) |
| email_verifications | id PK, email, code_hash, attempt_count(기본 0), expires_at, last_sent_at, verified_at(널=미확인), token_hash(널=미발급), token_expires_at, consumed_at(널=미소비), created_at | UNIQUE(token_hash), INDEX(email, created_at) |
| accounts | id PK, user_id FK, market(STOCK·CRYPTO), cash_balance BIGINT, seed_money BIGINT, realized_pnl BIGINT, created_at, updated_at | UNIQUE(user_id, market) |

발송 1건마다 `email_verifications` 행을 1개 만들고 이전 미확인 행은 만료 처리한다 — 기간별 발송 횟수는 `INDEX(email, created_at)` 범위 집계로 판정한다. 만료 행 정리 배치는 MVP 범위 밖이다 (spec 범위 제외).

## 테스트 계획

- 단위: AuthService (가입 중복 분기, 토큰 회전, OAuth 신규·기존 이메일 거부 판단 — 의존성 mock), JwtTokenProvider (발급·만료·변조), EmailVerificationService (발송 제한 3종, 시도 5회 초과 무효화, 재발송 시 이전 코드 무효화, 토큰 이메일 불일치 거부).
- 슬라이스: `@DataJpaTest` — users·social_accounts·accounts UNIQUE 제약과 `email_verifications` UNIQUE(token_hash)·기간 집계 쿼리 실동작. `@WebMvcTest` — 요청 검증(8자 미만 비밀번호 등 400)·401·429 포맷·응답 직렬화.
- 통합 (Testcontainers): 인증번호 요청→확인→가입→계좌 2개 각 1,000만원 생성(`FakeEmailSender`), 가입 토큰 재사용 거부, 닉네임 중복 실패 후 같은 토큰 재시도 성공, Fake OAuth 신규 가입·기존 이메일 409 거부(회원·계좌가 생기지 않음까지 확인), 로그인→회전→이전 토큰 거부 시나리오.
- 모든 테스트는 `RESEND_API_KEY`·`EMAIL_FROM` 없이 통과해야 한다.
