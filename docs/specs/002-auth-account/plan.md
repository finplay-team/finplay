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
| POST | /api/auth/signup | `SignupRequest` (email, nickname, password, termsAgreed) | `TokenResponse` | 가입 + 계좌 2개 생성 + 토큰 발급 |
| POST | /api/auth/login | `LoginRequest` (email, password) | `TokenResponse` | 로그인 |
| POST | /api/auth/refresh | `RefreshRequest` (refreshToken) | `TokenResponse` | 재발급 + 이전 토큰 폐기(회전) |
| POST | /api/auth/logout | `RefreshRequest` | 204 | Refresh Token 폐기. 인증 필요 |
| GET | /api/auth/me | - | `MemberResponse` (id, email, nickname) | 내 정보. 인증 필요 |
| GET | /api/auth/oauth/{provider}/authorize | - | 302 리다이렉트 | provider = kakao·naver |
| GET | /api/auth/oauth/{provider}/callback | code 쿼리 | `TokenResponse` | 가입 또는 병합 후 토큰 발급 |

- `TokenResponse` = accessToken, refreshToken, 만료 정보.
- 오류는 001의 공통 포맷. 중복은 `DUPLICATE_RESOURCE`(409), 인증 실패는 `UNAUTHORIZED`(401).

## 입력 명세

### SignupRequest
| 필드 | 필수 | 검증 |
|---|---|---|
| email | 필수 | 이메일 형식. 중복 시 409 DUPLICATE_RESOURCE |
| nickname | 필수 | 공백 불가. 중복 시 409 DUPLICATE_RESOURCE |
| password | 필수 | 8자 이상. 위반 시 400 VALIDATION_ERROR |
| termsAgreed | 필수 | true만 허용. false·누락 시 400 VALIDATION_ERROR |

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
- `JwtTokenProvider` — 발급·검증. 라이브러리는 jjwt. 시크릿은 환경변수 주입 (conventions 시크릿 규칙).
- Security 설정 — Bearer 인증 필터, 공개 경로 화이트리스트, 401/403을 001 공통 포맷으로 응답.
- 비밀번호 해시는 BCrypt. Refresh Token 저장은 SHA-256 해시 (원문 미저장).
- OAuth 어댑터 — `OAuthProvider` 인터페이스 + `KakaoOAuthProvider` / `NaverOAuthProvider` / `FakeOAuthProvider`(테스트·로컬 프로필). 실키 없으면 Fake 프로필로 실행 가능해야 한다 (PRD §7).

### account 패키지 (`com.finplay.api.account`)

- `Account` 엔티티 + `AccountRepository` + `AccountService.createAccountsFor(user)` — STOCK·CRYPTO 각 1,000만원 생성.
- 가입 트랜잭션은 AuthService가 경계를 갖고 AccountService를 호출한다 (도메인 간 참조는 service 경유 — ADR-0002).

### 트랜잭션 경계 (PRD §7)

- 회원가입 = 회원 + 계좌 2개 원자 처리.
- OAuth = 소셜계정 연결 또는 신규 회원 + 계좌 2개 원자 처리.

## 데이터 모델

마이그레이션 `V{next}__create_auth_account_tables.sql`. 금액은 원 단위 BIGINT (PRD C-003).

| 테이블 | 주요 컬럼 | 제약 |
|---|---|---|
| users | id PK, email, password_hash(널 허용 — 소셜 전용), nickname, role, status, created_at, updated_at | UNIQUE(email), UNIQUE(nickname) |
| social_accounts | id PK, user_id FK, provider(KAKAO·NAVER), provider_user_id, created_at | UNIQUE(provider, provider_user_id) |
| refresh_tokens | id PK, user_id FK, token_hash, expires_at, revoked_at(널=유효), created_at | INDEX(token_hash) |
| accounts | id PK, user_id FK, market(STOCK·CRYPTO), cash_balance BIGINT, seed_money BIGINT, realized_pnl BIGINT, created_at, updated_at | UNIQUE(user_id, market) |

## 테스트 계획

- 단위: AuthService (가입 중복 분기, 토큰 회전, OAuth 병합 판단 — 의존성 mock), JwtTokenProvider (발급·만료·변조).
- 슬라이스: `@DataJpaTest` — users·social_accounts·accounts UNIQUE 제약 실동작. `@WebMvcTest` — 요청 검증(8자 미만 비밀번호 등 400)·401 포맷·응답 직렬화.
- 통합 (Testcontainers): 가입→계좌 2개 각 1,000만원 생성, Fake OAuth 신규 가입·기존 이메일 병합, 로그인→회전→이전 토큰 거부 시나리오.
