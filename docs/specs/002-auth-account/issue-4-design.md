# Issue #4 설계: 이메일 회원가입 API

> 대상 이슈: GitHub #4 `[MVP][인증] 이메일 회원가입 API 구현`
>
> API: `POST /api/auth/signup`
>
> 선행 구현: Issue #3 이메일 인증번호 확인과 30분 일회용 가입 토큰 발급

## 목표와 범위

유효한 가입 토큰을 제시한 방문자를 회원으로 등록하고, STOCK·CRYPTO 계좌를 각각 10,000,000원으로 생성한다. 가입 토큰 소비, 회원 저장, 계좌 2개 생성, Refresh Token 해시 저장은 하나의 트랜잭션으로 처리한다. 성공 응답은 실제 Access/Refresh JWT 쌍을 반환한다.

이번 이슈는 회원가입에서 사용할 토큰 발급 기반까지 완성한다. 후속 이슈와의 경계를 지키기 위해 로그인 API, Bearer 인증 필터와 보호 경로 설정, Refresh Token 회전, 로그아웃, 내 정보 조회는 포함하지 않는다.

## API 계약

요청:

```json
{
  "email": "user@finplay.com",
  "nickname": "finplayer",
  "password": "password123",
  "termsAgreed": true,
  "signupVerificationToken": "<가입 토큰 원문>"
}
```

- `email`: 필수, 이메일 형식, 최대 255자
- `nickname`: 필수, 공백 문자열 불가, 최대 50자
- `password`: 필수, 8자 이상, 최대 100자
- `termsAgreed`: 필수이며 `true`만 허용
- `signupVerificationToken`: 필수, 공백 문자열 불가

성공은 HTTP 201과 다음 응답을 반환한다.

```json
{
  "accessToken": "<JWT>",
  "refreshToken": "<JWT>",
  "accessTokenExpiresInSeconds": 3600,
  "refreshTokenExpiresInSeconds": 1209600
}
```

- Access Token 유효시간은 기존 설정대로 1시간, Refresh Token은 14일이다.
- 이메일 또는 닉네임 중복은 409 `DUPLICATE_RESOURCE`다.
- 가입 토큰이 없거나, 해시와 일치하는 행이 없거나, 미확인·만료·소비 상태이거나, 요청 이메일과 다르면 409 `EMAIL_VERIFICATION_REQUIRED`다.
- DTO 검증 실패와 `termsAgreed=false`는 400 `VALIDATION_ERROR`다.

## 구성 요소

### auth

- `AuthController`: 요청 검증, `AuthService.signup` 호출, 201 응답만 담당한다.
- `AuthService`: 중복 검사, BCrypt 해시, 가입 토큰 검증·소비, 회원 생성, 계좌 생성, JWT 발급, Refresh Token 해시 저장을 하나의 트랜잭션으로 조정한다.
- `JwtTokenProvider`: 사용자 ID와 역할을 기반으로 Access/Refresh JWT를 발급한다. Access와 Refresh는 token type claim으로 구분한다.
- `RefreshToken`: Refresh Token 원문의 SHA-256 해시, 사용자, 만료 시각, 생성 시각을 저장한다. 원문은 응답 이후 저장하지 않는다.
- `EmailVerificationRepository`: 토큰 SHA-256 해시로 인증 행을 조회하고, `consumed_at IS NULL`, `token_expires_at > now` 조건을 포함한 조건부 UPDATE로 소비한다.

### account

- `Account`: 시장, 현금잔고, 시드머니, 실현손익, 생성·수정 시각을 표현한다.
- `AccountRepository`: 계좌 영속성만 담당한다.
- `AccountService.createAccountsFor`: STOCK과 CRYPTO 계좌를 각각 10,000,000원으로 생성한다.
- `AuthService`는 account repository를 직접 사용하지 않고 `AccountService`를 호출한다.

기존 V2 마이그레이션에 필요한 테이블과 제약이 이미 병합돼 있으므로 수정하거나 새 마이그레이션을 추가하지 않는다.

## 처리 순서와 원자성

1. 요청 DTO 검증을 통과한다.
2. 이메일과 닉네임 중복을 검사한다.
3. 가입 토큰 원문을 SHA-256으로 해시하고 대응하는 이메일 인증 행을 찾는다.
4. 인증 성공 여부, 토큰 만료·소비 여부, 요청 이메일 일치를 검사한다.
5. 비밀번호를 BCrypt로 해시한다.
6. 조건부 UPDATE로 가입 토큰을 소비한다. 영향 행이 1이 아니면 409로 중단한다.
7. 회원을 저장하고 account service로 계좌 2개를 생성한다.
8. Access/Refresh JWT를 발급하고 Refresh Token 해시를 저장한다.
9. 트랜잭션 커밋 후 토큰 쌍을 201로 반환한다.

동시에 같은 가입 토큰을 사용하면 조건부 UPDATE에 성공한 요청 하나만 가입된다. 닉네임 중복이나 계좌 저장 실패처럼 소비 이후 예외가 발생하면 전체 트랜잭션이 롤백되므로 토큰도 소비되지 않는다. 남은 유효시간 동안 수정한 입력으로 재시도할 수 있다.

DB UNIQUE 제약은 동시 중복 가입의 최종 방어선이다. 사전 중복 검사와 별개로 `users.email`, `users.nickname`, `accounts(user_id, market)` 제약을 유지한다.

## 보안과 설정

- 비밀번호는 BCrypt 해시만 저장한다.
- 가입 토큰과 Refresh Token은 SHA-256 해시만 저장한다.
- JWT 서명은 `JWT_SECRET`을 사용하며 이메일 인증 HMAC 시크릿과 분리한다.
- 실제 시크릿은 코드·YAML·문서에 기록하지 않는다.
- JWT 만료시간은 `application.yml`의 기존 설정을 사용한다.
- 테스트 태스크에는 테스트 전용 `JWT_SECRET`을 환경변수로 주입하고 production 기본값은 두지 않는다.
- JWT 라이브러리는 계획에 확정된 JJWT를 사용하고, BCrypt에는 Spring Security crypto 모듈을 사용한다. Bearer 필터와 웹 보안 설정은 Issue #5에 남긴다.

## 오류 처리

- controller에는 예외 변환이나 try-catch를 두지 않는다.
- 서비스는 `BusinessException`과 기존 `ErrorCode`를 사용한다.
- Bean Validation 오류는 기존 `GlobalExceptionHandler`의 공통 오류 형식으로 반환한다.
- 저장 시점의 동시 중복으로 UNIQUE 제약이 발생하는 경우도 409 `DUPLICATE_RESOURCE`로 정규화한다.
- JWT 설정 오류나 암호화 초기화 실패는 가입 오류로 숨기지 않고 애플리케이션 구성 실패로 드러낸다.

## 테스트

### 단위

- 정상 가입이 BCrypt 회원, 계좌 2개, JWT 쌍, Refresh Token 해시를 생성한다.
- 이메일·닉네임 중복을 각각 409로 거부한다.
- 미존재·만료·소비 토큰과 이메일 불일치를 409로 거부한다.
- 조건부 소비 실패를 409로 거부한다.
- `JwtTokenProvider`가 Access/Refresh 종류, 사용자 ID, 역할, 만료시간을 올바르게 발급한다.

### 슬라이스

- `@WebMvcTest`: 정상 요청 201과 전체 응답 필드, 각 요청 검증 400, 비즈니스 오류 409를 검증한다.
- `@DataJpaTest`: Refresh Token 해시 저장과 가입 토큰 조건부 소비를 실제 MySQL에서 검증한다.

### 통합

Testcontainers MySQL로 다음 핵심 시나리오를 검증한다.

1. 인증번호 발송·확인 후 회원가입하면 회원 1명과 STOCK·CRYPTO 계좌가 정확히 한 번 생성된다.
2. 두 계좌의 현금잔고와 시드머니가 각각 10,000,000원이다.
3. 가입 토큰 재사용은 409이며 회원·계좌가 추가되지 않는다.
4. 닉네임 중복으로 실패한 가입은 토큰 소비가 롤백되고, 닉네임을 바꾸면 같은 토큰으로 성공한다.
5. Refresh Token 원문은 DB에 없고 SHA-256 해시만 저장된다.

완료 전에 대상 테스트, `spotlessApply`, 전체 `build`를 새로 실행한다. 자동 테스트 결과는 실제 외부 OAuth·메일 발송 검증으로 표현하지 않는다.

## 문서 동기화

- controller 추가와 함께 `docs/api-routes.md`에 회원가입 라우트와 요청·응답·오류 계약을 추가한다.
- `docs/specs/002-auth-account/tasks.md`의 회원가입 작업은 구현·검증 완료 후에만 체크한다.
- 구현 결과와 실제 검증 명령은 기존 `run-log.md`에 Issue #4 구역으로 기록한다.
