# API 계약 — auth

auth 도메인의 API 계약 상세다. 전체 라우트를 한눈에 보는 지도는 `ai/api-routes.md`에 있다.

**controller를 추가/변경하면 `ai/api-routes.md`의 라우트 목록과 이 문서를 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

블랙박스 QA는 구현 코드(`src/main`)를 읽지 않고 이 문서와 spec만을 계약 근거로 사용한다 (`ai/context-router.md`).

---

### 이메일 인증번호 발송

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/auth/email-verifications | `{"email":"user@finplay.com"}` | 202 (본문 없음) | 400 `VALIDATION_ERROR`, 409 `DUPLICATE_RESOURCE`, 429 `TOO_MANY_REQUESTS` 공통 오류 형식 | 002 AUTH-004 |

### 이메일 인증번호 확인

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/auth/email-verifications/confirm | `{"email":"user@finplay.com","code":"123456"}` | 200 `{"signupVerificationToken":"<원문>","expiresInSeconds":1800}` | 400 `VALIDATION_ERROR` 또는 `EMAIL_VERIFICATION_FAILED`, 429 `TOO_MANY_REQUESTS` 공통 오류 형식 | 002 AUTH-004 |

### 이메일 회원가입

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/auth/signup | `{"email":"user@finplay.com","nickname":"finplayer","password":"password123","termsAgreed":true,"signupVerificationToken":"<가입 인증 토큰 원문>"}` | 201 `{"accessToken":"<JWT>","refreshToken":"<JWT>","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}` | 400 `VALIDATION_ERROR`, 409 `DUPLICATE_RESOURCE` 또는 `EMAIL_VERIFICATION_REQUIRED` 공통 오류 형식 | 002 Issue #4 |

### 로그인

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/auth/login | `{"email":"user@finplay.com","password":"password123"}` | 200 `{"accessToken":"<JWT>","refreshToken":"<JWT>","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}` | 400 `VALIDATION_ERROR`, 401 `UNAUTHORIZED` 공통 오류 형식 | 002 AUTH-002 |

회원 없음·소셜 전용 가입자·비밀번호 불일치는 원인과 무관하게 동일한 401 `UNAUTHORIZED`로 응답한다 (계정 열거 방지).

### Refresh Token 재발급

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/auth/refresh | `{"refreshToken":"<Refresh JWT 원문>"}` | 200 `{"accessToken":"<JWT>","refreshToken":"<JWT>","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}` | 누락·빈 값·4,096자 초과는 400 `VALIDATION_ERROR`. 1~4,096자의 비어 있지 않은 변조·만료·폐기·미존재·재사용 토큰은 401 `UNAUTHORIZED` 공통 오류 형식 | 002 AUTH-002, Issue #6 |

재발급에 성공하면 제출한 이전 Refresh Token은 즉시 폐기되고 새 Refresh Token의 SHA-256 해시만 저장된다. 같은 이전 토큰의 재사용과 동시 회전 요청 중 경합에서 진 요청은 401 `UNAUTHORIZED`다.

### 로그아웃

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/auth/logout | Access Bearer 필수 | `{"refreshToken":"<Refresh JWT 원문>"}` (`RefreshRequest`) | 204 (본문 없음) | 누락·빈 값·4,096자 초과는 400 `VALIDATION_ERROR`. Access 인증 실패 또는 변조·만료·폐기·미존재 Refresh Token은 401 `UNAUTHORIZED`. 다른 사용자의 활성 Refresh Token은 403 `FORBIDDEN` 공통 오류 형식 | 002 AUTH-002, Issue #7 |

로그아웃은 제출한 본인의 활성 Refresh Token 한 건만 폐기한다. 폐기된 토큰으로 이후 `/api/auth/refresh`를 호출하면 401 `UNAUTHORIZED`이며, 다른 사용자의 활성 Refresh Token을 제출하면 403 `FORBIDDEN`이고 해당 토큰은 폐기되지 않는다.

### 내 정보 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/auth/me | Access Bearer 필수 | 본문·경로 변수·쿼리 없음 | 200 `{"id":42,"email":"user@finplay.com","nickname":"finplayer","signupMethod":"EMAIL"}` | Access 인증 실패(헤더 없음·만료·변조·Refresh Token 제출)는 401 `UNAUTHORIZED` 공통 오류 형식 | 002 AUTH-005, Issue #8 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자로만 결정한다. 경로·쿼리에 대상 식별자가 없어 다른 회원 정보는 조회할 수 없다.

`signupMethod`는 `EMAIL`·`KAKAO`·`NAVER` 중 하나이며 별도 컬럼이 아니라 해당 회원의 `social_accounts` 행 존재 여부와 `provider`로 조회 시점에 판별한다(행이 없으면 `EMAIL`). 응답 필드는 위 4개로 고정이며 `passwordHash`·`role`·`status`·OAuth `providerUserId` 등은 어떤 이름으로도 포함하지 않는다.

유효한 Access Token의 주체가 DB에 없는 경우도 401 `UNAUTHORIZED`로 응답한다 (계정 존재 여부 미노출).

### 닉네임 변경

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| PATCH | /api/auth/me/nickname | Access Bearer 필수 | 이메일 회원 `{"nickname":"새닉네임","currentPassword":"현재비밀번호"}`, OAuth 전용 회원 `{"nickname":"새닉네임","reauthToken":"<원문 토큰>"}` (`nickname`만 필수, 최대 50자. `currentPassword` 최대 100자, `reauthToken` 최대 255자) | 200 `{"id":42,"email":"user@finplay.com","nickname":"새닉네임","signupMethod":"EMAIL"}` (`MemberResponse`) | `nickname` 누락·공백·50자 초과, `currentPassword`·`reauthToken` 최대 길이 초과, 가입 방식에 필요한 재인증 필드 누락은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 비밀번호 불일치, `reauthToken` 만료·이미 소비·타인 소유는 403 `REAUTHENTICATION_FAILED`. 다른 회원이 쓰는 닉네임은 409 `DUPLICATE_RESOURCE` 공통 오류 형식 | 002 AUTH-005, Issue #54 |

대상 회원은 요청에서 받지 않고 Access Token의 인증 사용자로만 결정한다. `currentPassword`와 `reauthToken` 중 무엇이 필수인지는 클라이언트가 채운 필드가 아니라 서버가 조회한 실제 가입 방식(`social_accounts` 행 존재 여부)으로 판별한다. 성공 응답은 `/api/auth/me`와 같은 4개 필드로 고정이며 `currentPassword`·`reauthToken`·`passwordHash`는 어떤 이름으로도 포함하지 않는다.

### 비밀번호 변경

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| PATCH | /api/auth/me/password | Access Bearer 필수 | `{"currentPassword":"현재비밀번호","newPassword":"새비밀번호"}` (`PasswordChangeRequest`, 둘 다 필수. `currentPassword` 최대 100자, `newPassword`는 가입과 같은 8자 이상 100자 이하) | 200 `{"accessToken":"...","refreshToken":"...","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}` (`TokenResponse`) | 필드 누락·공백, `newPassword` 8자 미만·100자 초과, OAuth 전용 회원(`"OAuth 전용 회원은 비밀번호를 변경할 수 없습니다."`), 새 비밀번호가 현재와 동일(`"새 비밀번호는 현재 비밀번호와 달라야 합니다."`)은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 현재 비밀번호 불일치는 403 `REAUTHENTICATION_FAILED` 공통 오류 형식 | 002 AUTH-005, Issue #114 |

대상 회원은 요청에서 받지 않고 Access Token의 인증 사용자로만 결정한다. OAuth 전용 회원 여부는 클라이언트 필드가 아니라 서버가 조회한 `social_accounts` 행 존재 여부로 판별하며, 이 검사가 비밀번호 대조보다 먼저다(바꿀 비밀번호가 없는 계정이므로 재인증 실패 403이 아니라 계정 유형 불일치 400이다).

**OAuth 전용 회원 거부가 여기서는 400이고 비밀번호 재설정 발송에서는 409 `SOCIAL_ACCOUNT_ONLY`인 이유** — 이 엔드포인트는 Access Token으로 이미 신원이 확정된 본인이 자기 계정에 맞지 않는 요청을 보낸 경우라 잘못된 요청(400)이다. 반면 재설정 발송은 비인증 공개 경로여서 요청 자체는 정상이고 서버가 조회한 계정의 상태 때문에 처리할 수 없는 경우라 충돌(409)이다. 프런트는 두 계약을 따로 다뤄야 한다.

성공 시 부수 효과 — 그 회원의 활성 Refresh Token이 모두 폐기되고 응답에 담긴 새 Refresh Token 1개만 유효하다. **다른 기기만 로그아웃**되며 요청한 기기는 재로그인 없이 이어서 사용한다(이메일 변경이 재로그인을 요구하는 것과 의도적으로 다르다). 이미 발급된 Access Token은 블랙리스트되지 않아 남은 만료 시간까지 유효할 수 있다. 실패하면 `password_hash`와 Refresh Token이 모두 변경 전 상태로 롤백된다. 성공·실패와 무관하게 이메일·닉네임·계좌·시드머니는 이 엔드포인트가 건드리지 않는다. 응답에는 `currentPassword`·`newPassword`·`passwordHash`가 어떤 이름으로도 포함되지 않는다.

### 비밀번호 재설정 인증번호 발송

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/auth/password-resets | **불필요 (공개 경로)** | `{"email":"member@finplay.com"}` (`PasswordResetRequest`, `email` 필수·이메일 형식·최대 255자) | 202 (본문 없음) | `email` 누락·공백·형식 위반·255자 초과 400 `VALIDATION_ERROR`; 60초 재발송·같은 이메일 1시간 5회·하루 10회 초과 429 `TOO_MANY_REQUESTS`; 가입되지 않은 이메일 404 `NOT_FOUND`(메시지 `"가입되지 않은 이메일입니다."`); 비밀번호가 없는 소셜 로그인 전용 회원 409 `SOCIAL_ACCOUNT_ONLY` 공통 오류 형식 | 002 AUTH-006, Issue #115 |

인증 헤더 없이 호출해도 401이 아니다 — `SecurityConfig.PUBLIC_POST_PATHS`의 공개 경로이며, Authorization 헤더를 보내도 대상 회원은 헤더가 아니라 요청 본문의 `email`로만 결정된다.

판정 순서는 400(Bean Validation) → 429 → 404 → 409다. 발송 제한을 존재 확인보다 먼저 보는 이유는, 존재 확인을 먼저 하면 제한을 초과한 요청자도 계정 상태를 계속 알아낼 수 있기 때문이다. 이 순서는 `POST /api/auth/email-verifications`(중복 확인 → 발송 제한)와 의도적으로 반대다.

응답이 가입 여부(404)와 소셜 전용 계정 여부(409)를 구분해 드러내는 것은 감수한 설계다 — `POST /api/auth/email-verifications`가 이미 가입된 이메일을 409로 알려주고 있고, 같은 주소의 반복 조회는 아래 발송 제한이 막는다. 발송 제한은 **이메일 주소 단위**이며 **404·409로 거부된 요청도 집계에 포함**되므로, 존재하지 않는 주소를 반복 조회해도 하루 10회에서 429가 된다. 429가 된 요청 자체는 집계 행을 남기지 않는다(남기면 제한이 영구 차단이 된다).

인증번호는 6자리 숫자, 유효시간 5분이다. 원문은 DB에도 로그에도 남지 않고 전용 시크릿(`PASSWORD_RESET_SECRET`) 기반 HMAC-SHA-256 해시만 저장된다. 재발송에 성공하면 같은 이메일의 이전 인증번호가 즉시 만료되어 유효한 인증번호는 항상 최대 1개다. 메일 발송이 실패하면 저장과 이전 코드 무효화가 함께 롤백된다.

이 엔드포인트는 **발송까지만** 한다 — `users`·`accounts`·`refresh_tokens`를 만들거나 바꾸지 않는다. 인증번호 확인과 실제 비밀번호 교체는 아래 `POST /api/auth/password-resets/confirm`(Issue #116)이다. 요구사항 정본은 PRD `AUTH-006`이다.

### 비밀번호 재설정 확인 및 적용

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/auth/password-resets/confirm | **불필요 (공개 경로)** | `{"email":"member@finplay.com","code":"123456","newPassword":"newSecret123"}` (`PasswordResetConfirmRequest`, 셋 다 필수. `email` 이메일 형식·최대 255자, `code` 숫자 6자리, `newPassword`는 가입과 같은 8자 이상 100자 이하) | **204 (본문 없음)** | 필드 누락·형식·길이 위반 400 `VALIDATION_ERROR`; 요청 이력 없음·이미 소비된 인증번호·만료·재발송으로 무효화된 이전 인증번호·코드 불일치·미가입 이메일 400 `EMAIL_VERIFICATION_FAILED`(**사유 미구분**); 같은 인증번호 5회 초과 시도 429 `TOO_MANY_REQUESTS`; 비밀번호가 없는 소셜 로그인 전용 회원 409 `SOCIAL_ACCOUNT_ONLY` 공통 오류 형식 | 002 AUTH-006, Issue #116 |

인증 헤더 없이 호출해도 401이 아니다 — `SecurityConfig.PUBLIC_POST_PATHS`의 공개 경로다. 인증번호와 새 비밀번호를 **한 요청으로 함께 받아 즉시 적용**하며 중간 단계 토큰(`passwordResetToken` 같은 것)은 발급하지 않는다. 프런트는 인증번호 입력칸과 새 비밀번호 입력칸을 한 화면에 둔다.

판정 순서는 400(Bean Validation) → 400/429(인증번호 판정) → 400/409(계정 상태)다. **계정 상태를 인증번호 검증 뒤에 보는 것이 발송 엔드포인트와 반대인 이유** — 확인 경로에는 이메일 단위 발송 제한이 없어, 계정 상태를 먼저 판정하면 아무 이메일·아무 코드로 호출해 응답 코드만 보고 가입 여부와 소셜 전용 여부를 무제한으로 스캔할 수 있다. 그래서 **미가입 이메일은 404가 아니라 400**이며(발송의 404와 의도적으로 다르다), 409 `SOCIAL_ACCOUNT_ONLY`도 발송 단계가 이미 막는 방어 분기다.

인증번호는 같은 인증번호당 최대 5회까지 시도할 수 있다. 5회를 초과하면 그 인증번호는 즉시 무효화되어 이후 정답을 입력해도 쓸 수 없다. 검증에 실패해도 시도 횟수 증가는 커밋되며(무차별 대입 방지), 그 외에는 아무것도 바뀌지 않는다. 인증번호 소비·`users.password_hash` 교체·Refresh Token 폐기는 모든 판정을 통과한 뒤에만 실행되어 **부분 성공이 생기지 않는다**.

성공 시 부수 효과 — 그 회원의 활성 Refresh Token이 모두 폐기되고 **새 토큰 쌍은 발급되지 않는다**. 즉 **항상 전 기기 로그아웃**이며(요청 기기 세션을 유지하는 `PATCH /api/auth/me/password`와 의도적으로 다르다), 사용자는 새 비밀번호로 다시 로그인해야 한다. 같은 인증번호로 재호출하면 400이다. 성공·실패와 무관하게 이메일·닉네임·OAuth 연결(`social_accounts`)·계좌·시드머니·잔액·주문·체결은 이 엔드포인트가 건드리지 않는다. 응답과 오류 본문에는 `code`·`newPassword`·`passwordHash`가 어떤 이름으로도 포함되지 않는다.

**한계** — 다른 기기에 이미 발급된 Access Token은 즉시 무효화되지 않고 남은 만료 시간 동안 유효하다. Refresh Token 폐기로 갱신만 막힌다.

### 새 이메일 변경 인증번호 발송

| Method | URL | 인증 | 입력 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/auth/email-changes | Access Bearer 필수 | `{"newEmail":"new@finplay.com","currentPassword":"...","reauthToken":"..."}` (`EmailChangeRequest`, 회원 유형에 따라 `currentPassword`/`reauthToken` 중 하나만 사용) | 202 (본문 없음) | 인증 헤더 없음·만료·변조 401 `UNAUTHORIZED`; `newEmail` 형식·길이 위반 400 `VALIDATION_ERROR`; 잘못된 현재 비밀번호·만료/재사용/타인 소유 `reauthToken` 403 `REAUTHENTICATION_FAILED`; 다른 회원이 사용 중인 새 이메일 409 `DUPLICATE_RESOURCE`; 60초 재발송·1시간 5회·하루 10회 초과 429 `TOO_MANY_REQUESTS` 공통 오류 형식 | 002 AUTH-005, Issue #55 |

EMAIL 회원은 현재 비밀번호, OAuth 전용 회원은 Issue #53에서 발급된 5분 유효·일회용 `reauthToken`으로 재인증하며, 재인증 실패 사유는 구분하지 않고 403 `REAUTHENTICATION_FAILED`로 통일한다. `reauthToken`은 이 발송 단계에서 소비된다. 확인 전까지 `users.email`은 변경되지 않는다.

### 이메일 변경 확인 및 적용

> **상세 계약 미작성** — Issue #56으로 구현·머지됐으나 이 문서에 계약 절이 아직 없다. 라우트 목록에는 등재되어 있다 (`POST /api/auth/email-changes/confirm` — 새 이메일 인증번호 확인 후 `users.email` 원자적 변경, 성공 시 기존 Refresh Token 전체 폐기). 계약 상세는 `ai/specs/002-auth-account/spec.md` AUTH-005와 구현 PR #75를 근거로 후속 보완이 필요하다.

### OAuth 재인증 authorize

| Method | URL | 인증 | 입력 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/auth/oauth/{provider}/authorize?purpose=reauth | `Authorization: Bearer` 필수 | 경로 `provider`, query `purpose=reauth` | 200 `{"authorizationUri":"https://..."}` 및 purpose+userId를 서명해 담은 `oauth_state` 쿠키 | 401 `UNAUTHORIZED`(토큰 없음·만료·변조), 400 `VALIDATION_ERROR`(미지원 provider) 공통 오류 형식 | 002 AUTH-003, Issue #53 |

`purpose`를 생략하거나 `login`으로 보내면 기존 302 리다이렉트 계약이 그대로 적용된다(Issue #9). `purpose`가 그 외 값이면 Security 매처가 인증을 요구하고, 인증된 요청이라도 컨트롤러가 400 `VALIDATION_ERROR`로 거부한다. 재인증은 302 대신 200 JSON을 쓴다 — 호출자가 이미 로그인한 SPA이므로 이동 시점을 클라이언트가 정한다.

### OAuth callback

| Method | URL | 입력 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/auth/oauth/{provider}/callback | query `code`, `state`; cookie `oauth_state`(LOGIN purpose만 검증에 쓰인다 — REAUTH는 없거나 `state`와 달라도 통과한다, 039 OAUTH-REAUTH-002). 인가 취소 시 query `error`, `state` | purpose=LOGIN state: 302, `Location: <oauth.login-redirect-uri>?code=<1회용 교환 코드>`. purpose=REAUTH state: 302, `Location: <oauth.reauth-redirect-uri>?code=<1회용 교환 코드>`(039 OAUTH-REAUTH-003, 기존 200 `reauthToken` JSON 직접 반환을 대체) — 두 purpose 모두 본문·토큰 원문 없음. 두 경우 모두 정상 브라우저 callback 응답의 `oauth_state` 만료 쿠키 포함 | 400 `VALIDATION_ERROR`, `OAUTH_AUTHORIZATION_FAILED`, `OAUTH_EMAIL_REQUIRED`; 403 `REAUTHENTICATION_FAILED`(서명·purpose·필드개수 위조뿐 아니라 발급 후 10분 지난 state도 포함, 039 OAUTH-REAUTH-001); 409 `ACCOUNT_LINK_REQUIRED` 또는 동시성 충돌 시 `DUPLICATE_RESOURCE`; 500 `INTERNAL_ERROR`; 502 `OAUTH_PROVIDER_ERROR` 공통 오류 형식 | 002 AUTH-003, Issue #10, Issue #53, 039 OAUTH-REAUTH-001/002/003 |
| POST | /api/auth/oauth/login-exchange | body `{"code":"<위 LOGIN 리다이렉트의 code>"}` | 200 `{"accessToken":"<JWT>","refreshToken":"<JWT>","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}` | 400 `VALIDATION_ERROR` — `code` 필드 누락·공백은 `"교환 코드는 필수입니다."`, 형식 오류·만료·이미 소비됨·존재하지 않음은 `"요청 값이 올바르지 않습니다."`로 메시지만 다르고 HTTP 상태·오류 코드는 동일하다(만료·재사용·형식오류를 서로 구분해 알려주지 않는다) 공통 오류 형식 | 002 AUTH-003, Issue #10 |
| POST | /api/auth/oauth/reauth-exchange | body `{"code":"<위 REAUTH 리다이렉트의 code>"}` | 200 `{"reauthToken":"<원문, 1회>","expiresInSeconds":300}` | 400 `VALIDATION_ERROR` — 계약은 `login-exchange`와 동일하다: `code` 필드 누락·공백은 `"교환 코드는 필수입니다."`, 형식 오류·만료·이미 소비됨·존재하지 않음은 `"요청 값이 올바르지 않습니다."`로 메시지만 다르고 HTTP 상태·오류 코드는 동일하다 공통 오류 형식 | 039 OAUTH-REAUTH-004 |

응답 종류는 요청 파라미터가 아니라 서명된 `state` 안의 purpose로 결정된다. 검증 순서는 provider 해석 → query `state` 존재 확인(비어 있으면 400 `VALIDATION_ERROR`) → state HMAC 서명·purpose·필드개수·자체 만료시각(발급 후 10분) 검증(실패 시 403 `REAUTHENTICATION_FAILED`, 039 OAUTH-REAUTH-001) → **LOGIN purpose에서만** query `state`와 cookie `oauth_state`의 일치 검사(불일치·누락 시 400 `VALIDATION_ERROR`; REAUTH purpose는 이 단계를 건너뛴다, 039 OAUTH-REAUTH-002) → 인가 취소·code 검사 → 공급자 사용자 조회 → purpose 분기다. 서명이 깨진 state는 purpose를 신뢰할 수 없으므로 `LOGIN`으로 위장한 경우도 403이다. REAUTH의 CSRF·재생 방어는 쿠키 이중제출이 아니라 state 자체 만료시각과, state에 박힌 userId와 정확히 같은 provider+providerUserId 소셜 계정으로만 재인증을 통과시키는 `AuthService.reauthenticate()` 검증에 의존한다(039). REAUTH 분기는 회원·소셜계정·계좌·시드머니를 만들거나 바꾸지 않고 `reauth_tokens`에 해시 1행만 추가한다.

**LOGIN·REAUTH 성공이 모두 302인 이유.** 카카오·네이버 콘솔의 redirect_uri가 이 API 주소를 직접 가리켜, 사용자가 소셜 로그인을 마치면 브라우저가 프론트를 거치지 않고 이 컨트롤러로 완전히 이동한다. LOGIN은 브라우저 최상위 이동이라 이 콜백이 곧 오프너다. REAUTH는 재인증 팝업이 여기로 이동하는데, 그 순간 팝업은 오프너(재인증을 시작한 프론트 탭)와 다른 오리진이 되어 동일 출처 정책(SOP) 때문에 오프너가 이 응답 본문을 직접 읽을 수 없다 — "재인증 SPA가 이미 열어 둔 팝업이 응답을 직접 읽는다"던 이전 서술은 이 전제 자체가 틀렸다는 것이 드러나 폐기됐다(039). 그래서 두 purpose 모두 토큰을 200 JSON 본문으로 주지 않는다 — LOGIN은 "브라우저 화면에 API 응답이 그대로 뜬다", REAUTH는 "SOP 때문에 오프너가 아예 읽을 수 없다"는 서로 다른 이유로 같은 302+교환 코드 해법에 도달한다. 발급한 토큰·`reauthToken`은 각각 Redis에 TTL 60초로 잠시 보관하고(LOGIN: `OAuthLoginExchangeStore`, REAUTH: `OAuthReauthExchangeStore`), 그 값을 가리키는 1회용 교환 코드만 프론트 주소 쿼리에 실어 리다이렉트한다 — 원문은 URL 어디에도 노출되지 않아 서버 접근 로그·Referer 헤더·브라우저 히스토리에 남지 않는다. 프론트는 그 코드로 각각 `POST /api/auth/oauth/login-exchange` 또는 `POST /api/auth/oauth/reauth-exchange`를 호출해 실제 값을 받는다.

callback은 query `state`의 존재와 서명·만료를 항상 검증하고, LOGIN purpose에 한해 cookie `oauth_state`의 존재·일치도 검증하지만, 서버가 발급 state를 Redis·DB·메모리에 저장하지는 않는다. 따라서 정상 브라우저는 callback 응답의 만료 쿠키를 적용하지만 raw cookie 재전송 자체를 서버 state 저장으로 차단한다고 보장하지 않는다. 실제 카카오·네이버는 authorization code의 단일 사용으로 같은 code 재전송을 거부한다. local·test Fake는 authorize마다 state에 결합된 고유 code를 발급하고 KAKAO/NAVER 전용 callback bean이 thread-safe store에서 `(provider, code, state)`를 원자적으로 한 번만 소비해 첫 요청만 허용한다. 잘못된 provider 요청은 grant를 소비하지 않고 400 `OAUTH_AUTHORIZATION_FAILED`로 거부하며, 원 provider의 첫 요청은 성공하고 이후 같은 grant 재사용은 400이다. `no-email`, `existing-email` 등 특수 fixture code는 오류 분기 테스트용으로 유지한다.

PR #49 차단 리뷰 후속 Fake 재사용·동시성·DB 불변 자동 회귀와 전체 build는 `PASS`했다. 실제 KAKAO/NAVER 스모크 `PASS`는 기존 별도 검증 기록이며, 이 후속 자동 검증에서 실제 공급자 스모크를 재실행하지 않았다.

**후속 미해결 범위.** 이 변경은 LOGIN·REAUTH 성공 경로만 고친다(039). callback이 오류(400·403·409 등)로 끝나는 경우는 여전히 API 주소에 JSON 오류 본문이 그대로 뜬다 — 프론트가 이 상태를 안내 문구로 바꿔 보여줄 방법이 없다(039 OAUTH-REAUTH-005, 범위 제외로 확정). 프론트 쪽 실제 처리(`OAuthCallback.tsx`에서 `code` 쿼리를 읽어 `login-exchange`/`reauth-exchange` 호출, 재인증 팝업의 `postMessage` 배선)는 별도 레포 작업이다.
