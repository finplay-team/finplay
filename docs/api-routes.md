# API 라우트 지도

전체 엔드포인트를 한눈에 보는 레지스트리. **controller를 추가/변경하면 같은 커밋에서 이 문서를 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

상세 명세(요청/응답 스키마)는 앱 실행 후 Swagger UI에서 확인한다: `http://localhost:8080/swagger-ui.html`

## 라우트 목록

| Method | URL | 도메인 | 요약 | Spec |
|---|---|---|---|---|
| POST | /api/auth/email-verifications | auth | 인증번호 발송 (202, 본문 없음). 발송 제한·중복 이메일 검사 | 002 AUTH-004 |
| POST | /api/auth/login | auth | 이메일·비밀번호 검증 후 Access·Refresh 토큰 발급 | 002 AUTH-002 |
| GET | /api/auth/oauth/{provider}/authorize | auth | 카카오·네이버 OAuth 인가 시작 (302, state 보안 쿠키) | 002 AUTH-003 |

## 시스템 엔드포인트

| Method | URL | 요약 |
|---|---|---|
| GET | /actuator/health | 헬스체크 |
| GET | /swagger-ui.html | API 문서 (springdoc) |
| GET | /v3/api-docs | OpenAPI JSON |

## 이메일 인증번호 확인

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/auth/email-verifications/confirm | `{"email":"user@finplay.com","code":"123456"}` | 200 `{"signupVerificationToken":"<원문>","expiresInSeconds":1800}` | 400 `VALIDATION_ERROR` 또는 `EMAIL_VERIFICATION_FAILED`, 429 `TOO_MANY_REQUESTS` 공통 오류 형식 | 002 AUTH-004 |

## 이메일 회원가입

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/auth/signup | `{"email":"user@finplay.com","nickname":"finplayer","password":"password123","termsAgreed":true,"signupVerificationToken":"<가입 인증 토큰 원문>"}` | 201 `{"accessToken":"<JWT>","refreshToken":"<JWT>","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}` | 400 `VALIDATION_ERROR`, 409 `DUPLICATE_RESOURCE` 또는 `EMAIL_VERIFICATION_REQUIRED` 공통 오류 형식 | 002 Issue #4 |

## 로그인

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/auth/login | `{"email":"user@finplay.com","password":"password123"}` | 200 `{"accessToken":"<JWT>","refreshToken":"<JWT>","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}` | 400 `VALIDATION_ERROR`, 401 `UNAUTHORIZED` 공통 오류 형식 | 002 AUTH-002 |

회원 없음·소셜 전용 가입자·비밀번호 불일치는 원인과 무관하게 동일한 401 `UNAUTHORIZED`로 응답한다 (계정 열거 방지).
