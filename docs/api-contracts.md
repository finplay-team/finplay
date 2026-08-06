# API 계약 상세

엔드포인트별 요청·응답·오류 계약이다. 전체 라우트를 한눈에 보는 지도는 `docs/api-routes.md`에 있다.

**controller를 추가/변경하면 `docs/api-routes.md`의 라우트 목록과 이 문서의 해당 절을 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

블랙박스 QA는 구현 코드(`src/main`)를 읽지 않고 이 문서와 spec만을 계약 근거로 사용한다 (`docs/context-router.md`).

순서는 도메인 기준이다 — auth → market → community → order → account → portfolio → journal.

---

## auth

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

> **상세 계약 미작성** — Issue #56으로 구현·머지됐으나 이 문서에 계약 절이 아직 없다. 라우트 목록에는 등재되어 있다 (`POST /api/auth/email-changes/confirm` — 새 이메일 인증번호 확인 후 `users.email` 원자적 변경, 성공 시 기존 Refresh Token 전체 폐기). 계약 상세는 `docs/specs/002-auth-account/spec.md` AUTH-005와 구현 PR #75를 근거로 후속 보완이 필요하다.

### OAuth 재인증 authorize

| Method | URL | 인증 | 입력 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/auth/oauth/{provider}/authorize?purpose=reauth | `Authorization: Bearer` 필수 | 경로 `provider`, query `purpose=reauth` | 200 `{"authorizationUri":"https://..."}` 및 purpose+userId를 서명해 담은 `oauth_state` 쿠키 | 401 `UNAUTHORIZED`(토큰 없음·만료·변조), 400 `VALIDATION_ERROR`(미지원 provider) 공통 오류 형식 | 002 AUTH-003, Issue #53 |

`purpose`를 생략하거나 `login`으로 보내면 기존 302 리다이렉트 계약이 그대로 적용된다(Issue #9). `purpose`가 그 외 값이면 Security 매처가 인증을 요구하고, 인증된 요청이라도 컨트롤러가 400 `VALIDATION_ERROR`로 거부한다. 재인증은 302 대신 200 JSON을 쓴다 — 호출자가 이미 로그인한 SPA이므로 이동 시점을 클라이언트가 정한다.

### OAuth callback

| Method | URL | 입력 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/auth/oauth/{provider}/callback | query `code`, `state`; cookie `oauth_state`. 인가 취소 시 query `error`, `state` | purpose=LOGIN state: 200 `{"accessToken":"<JWT>","refreshToken":"<JWT>","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}`. purpose=REAUTH state: 200 `{"reauthToken":"<원문, 1회>","expiresInSeconds":300}`. 두 경우 모두 정상 브라우저 callback 응답의 `oauth_state` 만료 쿠키 포함 | 400 `VALIDATION_ERROR`, `OAUTH_AUTHORIZATION_FAILED`, `OAUTH_EMAIL_REQUIRED`; 403 `REAUTHENTICATION_FAILED`; 409 `ACCOUNT_LINK_REQUIRED` 또는 동시성 충돌 시 `DUPLICATE_RESOURCE`; 500 `INTERNAL_ERROR`; 502 `OAUTH_PROVIDER_ERROR` 공통 오류 형식 | 002 AUTH-003, Issue #10, Issue #53 |

응답 종류는 요청 파라미터가 아니라 서명된 `state` 안의 purpose로 결정된다. 검증 순서는 provider 해석 → query/cookie state 일치(400 `VALIDATION_ERROR`) → state HMAC 서명 검증(실패 시 403 `REAUTHENTICATION_FAILED`) → 인가 취소·code 검사 → 공급자 사용자 조회 → purpose 분기다. 서명이 깨진 state는 purpose를 신뢰할 수 없으므로 `LOGIN`으로 위장한 경우도 403이다. REAUTH 분기는 회원·소셜계정·계좌·시드머니를 만들거나 바꾸지 않고 `reauth_tokens`에 해시 1행만 추가한다.

callback은 query `state`와 cookie `oauth_state`의 존재·일치를 검증하지만 서버가 발급 state를 Redis·DB·메모리에 저장하지는 않는다. 따라서 정상 브라우저는 callback 응답의 만료 쿠키를 적용하지만 raw cookie 재전송 자체를 서버 state 저장으로 차단한다고 보장하지 않는다. 실제 카카오·네이버는 authorization code의 단일 사용으로 같은 code 재전송을 거부한다. local·test Fake는 authorize마다 state에 결합된 고유 code를 발급하고 KAKAO/NAVER 전용 callback bean이 thread-safe store에서 `(provider, code, state)`를 원자적으로 한 번만 소비해 첫 요청만 허용한다. 잘못된 provider 요청은 grant를 소비하지 않고 400 `OAUTH_AUTHORIZATION_FAILED`로 거부하며, 원 provider의 첫 요청은 성공하고 이후 같은 grant 재사용은 400이다. `no-email`, `existing-email` 등 특수 fixture code는 오류 분기 테스트용으로 유지한다.

PR #49 차단 리뷰 후속 Fake 재사용·동시성·DB 불변 자동 회귀와 전체 build는 `PASS`했다. 실제 KAKAO/NAVER 스모크 `PASS`는 기존 별도 검증 기록이며, 이 후속 자동 검증에서 실제 공급자 스모크를 재실행하지 않았다.

---

## market

### 종목 목록 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/instruments?market= | Access Bearer 필수 | `market`(선택, `STOCK`·`CRYPTO`만 허용, 생략·빈 값 시 전체) | 200 `[{"instrumentId":1,"market":"STOCK","symbol":"005930","name":"삼성전자","tickSize":100,"minOrderAmount":70000,"tradable":true}, ...]` | `market`이 `STOCK`·`CRYPTO` 외 값이면 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 003 MKT-001, Issue #14 |

`market` 생략·빈 값 시 시드된 주식 16종·코인 12종 전체를 `id` 오름차순(주식 먼저, 코인 나중)으로 반환한다.

### 종목 단건 조회

| Method | URL | 인증 | 경로 변수 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/instruments/{instrumentId} | Access Bearer 필수 | `instrumentId` | 200 `{"instrumentId":1,"market":"STOCK","symbol":"005930","name":"삼성전자","tickSize":100,"minOrderAmount":70000,"tradable":true}` (`InstrumentResponse`, 목록 API와 동일 DTO) | 존재하지 않는 `instrumentId`는 404 `NOT_FOUND`. 숫자가 아닌 `instrumentId`는 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 003 MKT-001, Issue #15 |

### 종목 현재가 조회

| Method | URL | 인증 | 경로 변수 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/instruments/{instrumentId}/price | Access Bearer 필수 | `instrumentId` | 200 `{"price":71200,"sourceTime":"2026-07-22T09:00:00","status":"AVAILABLE","sourceTradingDate":"2026-07-22"}` (`PriceResponse`) — 코인은 `sourceTradingDate`가 `null` | 존재하지 않는 `instrumentId`는 404 `NOT_FOUND`. 유효한 최신 가격이 없으면(주식: 재생세션 미준비·미공개 분봉, 코인: stale·연결 끊김) 409 `PRICE_UNAVAILABLE`. 숫자가 아닌 `instrumentId`는 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 003 MKT-002/MKT-003/MKT-004, Issue #16 |

이 API는 회원 소유 리소스가 아니라 공개 종목 정보 조회이므로 별도 소유권 검사는 없다(로그인만 하면 누구나 조회 가능). `PriceQueryService`가 `instrument.getMarket()`에 따라 주식은 주입된 `StockPriceProvider`(현재 구현체가 무엇인지 이 API·서비스는 알지 못한다), 코인은 `PriceStore`(Redis)에 위임하며, 어느 경로든 유효성 판정 실패는 컨트롤러가 아니라 서비스가 `BusinessException`으로 던지고 `GlobalExceptionHandler`가 응답 코드로 변환한다(컨트롤러에 try-catch 없음).

### 캔들 조회 (주식·코인 — 1m·1d·1w·1M)

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/instruments/{instrumentId}/candles | Access Bearer 필수 | `interval`(필수, `1m`\|`1d`\|`1w`\|`1M` 중 하나, **대소문자 구분**), `from`·`to`(선택, ISO-8601 `LocalDateTime`, 예: `2026-07-27T09:00:00`) | 200 `[{"sourceTime":"2026-07-22T09:00:00","open":71000,"high":71500,"low":70900,"close":71200,"volume":12345}, ...]` (`CandleResponse[]`, 시각 오름차순, 모든 `interval`·모든 시장 공통 최대 200개, 초과 시 `to` 기준 최신 200개). 주식은 어떤 `interval`이든 아직 공개된 분봉이 없거나 재생세션이 준비되지 않은 경우 예외 없이 200 `[]` | `interval`이 `1m`·`1d`·`1w`·`1M`이 아니면(대소문자 변형 `1D`·`1W`·`1MO`·`1min` 포함) 400 `VALIDATION_ERROR`. `from > to`면 400 `VALIDATION_ERROR` — **코인은 `interval`과 무관하게 항상 시각까지 포함해 비교**하지만, 주식 `1d`·`1w`·`1M`은 날짜 성분만 비교한다(아래 요약 참조). 그래서 같은 `from`·`to`라도 주식 집계에서는 통과하는 값이 코인에서는 400이 될 수 있다. 존재하지 않는 `instrumentId`는 404 `NOT_FOUND`. 코인에서 빗썸 조회가 실패하면 502 `MARKET_DATA_PROVIDER_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. **오류 계약은 `1m`만 지원하던 때와 동일하며 013에서 바뀌지 않았다** — 4값으로 확장된 것은 `interval` 허용값 자체뿐이다 | 003 MKT-002·MKT-008, 013 MKT-009, Issue #17, Issue #20, Issue #143 |

- **`interval` 4값**: `1m`(1분봉)·`1d`(일봉)·`1w`(주봉)·`1M`(월봉). **대소문자를 구분한다** — `1M`은 월봉, `1m`은 분봉이며 서버는 정규화하지 않는다. `interval` 검증은 `instrumentId` 존재 조회보다 **먼저** 수행한다 — 잘못된 `interval`은 존재하지 않는 종목이어도 400이다.
- **주식과 코인의 출처가 다르다** — 응답 형식은 같지만 주식은 MySQL `stock_candles`의 **과거 거래일 재생** 1분봉(`1d`·`1w`·`1M`은 그 1분봉을 서버가 직접 집계), 코인은 빗썸 공개 캔들 REST의 **지금 이 순간까지의 실시간** 봉(모든 `interval`에서 위임, 서버 집계 없음)이다. 소비자는 `market`에 따라 파싱을 나누지 않지만, **미마감 분봉/미완성 버킷 규칙은 시장별로 다르다**(바로 아래 두 절 참조).
- 검증 순서는 `CandleQueryService.getCandles`가 `interval` → `instrumentId` 존재(404) → 시장 종류 → `from`/`to`(시장·`interval`별 판정 기준 상이 — 코인은 날짜 포함 전체 시각, 주식 `1m`은 시각만, 주식 `1d`·`1w`·`1M`은 날짜만) 순으로 판정한 뒤 주식은 `StockPriceProvider.getCandles`, 코인은 `CryptoCandleProvider.getCandles`에 위임한다(컨트롤러에 try-catch 없음).
- **코인 `instrumentId`는 더 이상 400이 아니다** (Issue #20). Issue #17에서 "주식 종목만 캔들 조회를 지원합니다" 400 `VALIDATION_ERROR`로 거부했던 계약이 제거됐다 — 프론트에서 이 400을 분기 처리하던 코드가 있으면 함께 정리한다.
- `volume`은 코인의 소수 수량(예: `0.26725783`)을 표현하기 위해 `BigDecimal`이다. 주식 값의 표현(`12345`, `1d`·`1w`·`1M` 집계도 정수 합계)은 바뀌지 않는다.
- `from`·`to`를 모두 생략하면 공개 상한까지의 최신 200개를 반환한다 — 주식 `1m`은 재생 중인 거래일 전체(그 중 이미 공개된 분봉), 주식 `1d`·`1w`·`1M`은 공개 상한까지의 최신 200개 버킷, 코인은 진행 중인 분봉/봉을 포함해 최신부터 과거로 200개를 반환한다.
- **주식 집계(`1d`·`1w`·`1M`) 규칙 요약** — 원천은 `stock_candles` 1분봉뿐이고 집계 결과는 저장하지 않는다. 버킷 경계는 `1d`=거래일(`trading_date`) 하나, `1w`=그 거래일이 속한 주(**월요일 시작**, ISO-8601·KST), `1M`=그 거래일이 속한 달력월(**1일 시작**, KST). `sourceTime`은 버킷 시작일 `T00:00:00`(월요일·1일이 실제 거래일이 아니어도 라벨로만 쓴다). OHLCV는 버킷 안 분봉 중 최초 open·최대 high·최소 low·최종 close·합계 volume이다. **공개 상한(reveal bound)**: 오늘 `READY` 재생세션이 없으면 어떤 `interval`이든 200 `[]`(1분봉과 동일 계약), 있으면 그 세션의 재생거래일보다 이전 거래일은 전량 집계, 재생거래일 당일은 1분봉과 같은 공개 컷오프(09:01 이전 0개, 09:01부터 컷오프까지)까지만 집계, 재생거래일 이후 거래일은 방어적으로 제외한다. **미완성 버킷은 응답에 포함된다**(진행 중인 거래일·주·월도 이미 공개된 분봉만으로 계산해 노출, 공개된 분봉이 0개인 버킷만 제외). **거래일이 없는 주·월은 응답에 없다**(빈 봉으로 채우지 않음, 반환된 봉 사이가 달력상 연속임을 보장하지 않음). **`from`·`to` 해석이 `1m`과 다르다** — `1d`·`1w`·`1M`은 `from`·`to`의 **날짜 성분만** 쓰고(시각 성분 무시) 버킷 시작일이 `[from의 날짜, to의 날짜]`(양끝 포함) 안에 있으면 포함한다. `1m`은 기존대로 날짜 성분을 무시하고 시각 성분만 쓴다 — 두 규칙은 의도적으로 다르다.
- **코인 위임(`1d`·`1w`·`1M`) 규칙 요약** — `interval`에 따라 빗썸 엔드포인트가 `/v1/candles/days`·`/v1/candles/weeks`·`/v1/candles/months`(`1m`은 기존 `/v1/candles/minutes/1`)로 분기될 뿐, 저장·캐시(MySQL·Redis 어느 쪽에도)는 하지 않는다는 `1m` 원칙이 그대로 적용된다. 필드 매핑도 동일하다 — `trade_price`→`close`(이름과 달리 현재가가 아니다), `candle_acc_trade_volume`→`volume`(거래대금 `candle_acc_trade_price`와 다르다). **버킷 경계는 빗썸이 준 값을 그대로 쓴다** — 서버는 빗썸 봉을 다시 자르거나 묶지 않고 `candle_date_time_kst`를 `sourceTime`으로 그대로 매핑한다. 일·주·월봉 전용 필드(`prev_closing_price`·`change_price`·`change_rate`·`first_day_of_period` 등)는 무시하고 원본 응답을 그대로 내려주지 않는다.
- **경계 일치 관측(외부 스모크, 2026-08-03 KST, `docs/specs/013-candle-interval/run-log.md` "외부 스모크" 참조)** — 빗썸 공개 캔들 REST를 직접 호출해 확인한 결과, 일봉 `candle_date_time_kst`는 KST 자정·그날 날짜, 주봉은 KST 자정·**월요일**(2026-08-03 실측 기준 검증), 월봉은 KST 자정·**매월 1일**이었다. 즉 **관측 시점 기준으로 주식(우리가 KST 거래일로 자르는 경계)과 코인(빗썸이 준 경계)의 버킷 경계가 사실상 일치**한다(KST 자정·월요일 시작·1일 시작). 이것은 빗썸 쪽 사양을 관측한 결과일 뿐이며, 서버 코드는 이 일치를 전제로 코인 경로를 보정하지 않는다 — 빗썸이 경계를 바꾸면 주식·코인 버킷 경계는 다시 벌어질 수 있다.

#### 코인 캔들 전용 규칙 (MKT-008, 013)

> 아래 규칙은 원래 `1m` 계약이며 `1d`·`1w`·`1M`(013)에도 그대로 적용된다 — 코인은 4개 `interval` 모두 저장·집계 없이 빗썸에 위임하는 같은 구조다. `interval`별 빗썸 엔드포인트 분기·버킷 경계 그대로 통과 규칙은 위 "코인 위임(`1d`·`1w`·`1M`) 규칙 요약"을 참조한다.

- 데이터 출처는 빗썸 공개 캔들 REST API다 — 인증·API Key가 필요 없고, 응답을 MySQL·Redis에 저장하지 않는다.
- **실시간 차트다 — 과거 데이터 재생이 아니다.** 주식의 재생 분봉은 옛 거래일을 오늘 다시 트는 것이지만, 코인 분봉은 지금 이 순간까지의 실제 시장이다(11:43:06에 조회하면 `11:43`·`11:42`·`11:41` 봉이 온다). 현재가 API의 WebSocket 틱과 **같은 지금의 시장을 다른 해상도로 본 것**이다.
- **한 요청의 최대 개수는 200개**(빗썸 API 상한)다. `from`·`to` 범위가 200분을 넘으면 `to` 기준 최신 200개만 반환한다. 200개를 넘는 구간을 이어붙이는 페이징은 1차 범위가 아니다.
- **진행 중인(아직 마감하지 않은) 분봉을 포함한다** — 그게 지금의 실시간 시세다. **주식은 미마감 봉을 제외하는데 코인은 포함하며, 이 차이는 의도된 것이다** (주식만 과거 거래일 재생이기 때문). 같은 요청을 다시 보내면 그 마지막 봉의 고가·저가·종가·거래량이 자란 값으로 온다 — 정상 동작이다.
- **코인은 전용 스트림이 없다.** 분 이하 해상도로 차트를 부드럽게 갱신하려면 프론트가 이 API를 짧은 주기로 다시 호출한다 — 진행 중 분봉이 매 호출마다 최신 값으로 갱신되므로 그것으로 충분하다.
- 코인 응답의 `sourceTradingDate` 개념은 없다 — 과거 데이터 재생이 아니라 실제 현재 시각의 분봉이다.
- **빗썸 조회 실패는 502 `MARKET_DATA_PROVIDER_ERROR`다.** 빈 배열 200으로 성공을 위장하거나 이전 값으로 대체하지 않는다. 주식의 200 `[]`("아직 공개할 분봉이 없다"는 정상 상태)와 성격이 다르다.
- 코인 캔들 조회는 `PriceStore`(Redis)·`PriceQueryService`를 거치지 않는다 — **현재가 경로와 완전히 독립이다.** 빗썸 WebSocket이 끊겨 현재가가 409 `PRICE_UNAVAILABLE`인 상태에서도 캔들 조회는 200일 수 있고, 반대로 캔들이 502인 상태에서도 현재가·주문은 정상이다.
- 차트 분봉과 현재가(최신 틱)는 서로 다른 엔드포인트에서 오므로 값이 밀리초 단위로 정확히 일치하지 않을 수 있다. 서버가 둘을 보정하지 않는다.
- **`from`·`to`의 날짜 성분이 실제로 쓰인다** — 아래 주식 규칙의 "날짜 성분 무시"는 코인에 적용되지 않는다. 주식은 재생 중인 단일 거래일 안에서만 조회되지만, 코인은 실제 달력 시각의 분봉이므로 날짜까지 그대로 빗썸 `to`로 전달한다.
- **`to`는 항상 포함(inclusive)이다** — `to`와 정확히 같은 시각에 시작하는 봉도 응답에 포함된다(`1m`·`1d`·`1w`·`1M` 공통, 이슈 #157). 클라이언트는 경계 보정 없이 "오늘 날짜"·"이번 주 월요일" 등을 그대로 `to`로 보내면 된다.
  - **내부 구현 메모**: 빗썸 공개 캔들 REST의 `to`는 **UTC가 아니라 `candle_date_time_kst`와 그대로 비교되는 KST 값**이며(PR #164 리뷰에서 실제 빗썸 API로 재현 확인 — 문서상 UTC로 오인해 KST→UTC 변환을 하면 9시간 밀린 엉뚱한 구간이 반환된다), 그 정확한 경계 시각을 배제(exclusive)한다(2026-08-03 외부 스모크로 실측 확인, `docs/specs/013-candle-interval/run-log.md` "`to` 파라미터의 경계 포함 여부" 참조 — `to`로 오늘 날짜(자정 KST)를 넘기면 그날의 봉이 빠지는 문제가 실측됐다). `BithumbRestCandleProvider.resolveToParam`은 변환 없이 원본 KST 값에 **1초만 더해** 빗썸에 전달함으로써 이 배제 동작을 흡수한다. 빗썸의 최소 봉 간격(1분)보다 훨씬 작은 보정값이라 다음 봉을 끌어오지 않으며, `interval`별 분기 없이 네 값 모두 동일하게 동작한다(2026-08-03 외부 스모크로 1m·1d·1w·1M 모두 재검증, `run-log.md` 참조).

#### 주식 캔들 전용 규칙 (MKT-002, 013)

> 아래 규칙은 `interval=1m`(원분봉) 전용이며 013에서 값·정렬·미마감 규칙·`from`/`to` 해석·빈 배열 조건 모두 회귀 없이 그대로 유지된다. `1d`·`1w`·`1M`(거래일·주·월 집계)의 버킷 경계·공개 상한·미완성 버킷·`from`/`to` 해석은 위 "주식 집계(`1d`·`1w`·`1M`) 규칙 요약"을 참조한다 — 그 규칙은 여기의 `1m` 공개 컷오프를 재사용하지만 날짜 단위로 묶은 결과라는 점에서 다르다.

- 아직 마감하지 않은 분봉은 어떤 경우에도 응답에서 제외된다(리뷰 확정, PR #87) — 09:00~09:00:59(첫 분봉 구간)에는 그 첫 분봉조차 아직 마감 전이므로 **빈 배열**을 반환한다. 09:01부터는 마감이 완료된 마지막 분봉까지, 15:30 마감 후에는 그날 공개된 분봉 전체가 후보가 된다. 같은 첫 분봉 구간에서도 가격 API(`GET .../price`)는 그 첫 분봉의 시가를 예외적으로 현재가로 노출한다는 점에서 캔들 API와 계약이 다르다 — 혼동하지 않는다.
- 재생세션이 `READY`가 아니거나(`PREPARING`·`FAILED`) 그 서비스 날짜의 세션 자체가 없으면 예외를 던지지 않고 200과 빈 배열을 반환한다 — 가격 API가 이 경우 409 `PRICE_UNAVAILABLE`을 반환하는 것과 다른 계약이다.
- `from`·`to`로 범위를 좁히면 그 범위와 공개 컷오프의 교집합만 반환한다(둘 중 더 이른 시각이 상한이 된다).
- **`from`·`to`의 날짜 성분은 무시되고 시각(시:분:초)만 사용된다**(리뷰 확정, PR #87 QA FAIL 옵션 c) — 결과는 항상 현재 재생 중인 단일 거래일(`source_trading_date`) 범위 안에서만 반환되므로, 요청에 다른 날짜를 넣어도 그 날짜는 조회에 반영되지 않는다. `from > to` 판정도 날짜가 아니라 시각만으로 비교한다 — 예를 들어 `from=2026-07-23T09:00:00`·`to=2026-07-22T09:01:00`처럼 날짜만 보면 역전돼 보여도 시각(09:00 ≤ 09:01)이 유효하면 통과하고, 반대로 `from=2026-07-22T09:01:00`·`to=2026-07-23T09:00:00`처럼 날짜는 정상 순서여도 시각(09:01 > 09:00)이 역전되어 있으면 400 `VALIDATION_ERROR`다.

### 주식 SSE 스트림

| Method | URL | 인증 | 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/stocks/stream | Access Bearer 필수(fetch + `Authorization: Bearer <accessToken>` 헤더, 브라우저 기본 `EventSource` 미사용) | `Content-Type: text/event-stream`. `retry: 3000` 1회 → `snapshot`(주식 16종 전체, id 없음) → 이후 `price`(변경된 종목만, id 있음)·`status`(개장·마감 전환 1회, id 없음) | Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식(응답 본문, 스트림 시작 전) | 003 MKT-002, Issue #19 |

- `StockPriceSseController.stream()`이 `SseEmitterRegistry.register(Market.STOCK)`로 emitter를 얻은 뒤(그 자리에서 `retry: 3000` 1회 전송, 이슈 #18) `StockPriceStreamService.sendSnapshot(emitter)`로 그 emitter에만 snapshot을 전송하고 반환한다 — 이후 push는 `StockPriceStreamService`가 매분(정각, `Asia/Seoul`) 스케줄로 담당한다.
- **snapshot**: `{"market":"STOCK","sourceTradingDate":"2026-07-22","marketStatus":"OPEN","emittedAt":"2026-07-25T09:01:00","prices":[{"symbol":"005930","price":71200,"sourceTime":"2026-07-22T09:00:00","status":"AVAILABLE"}, ...]}` — 주식 16종 전체를 배열 1건에 담는다. 가격이 없는 종목도 배열에서 빠지지 않고 `price`·`sourceTime`은 `null`, `status`는 `UNAVAILABLE`이다. id 없음.
- **price**: `id: STOCK:005930:202607220900` + `{"market":"STOCK","symbol":"005930","price":71200,"sourceTime":"2026-07-22T09:00:00","emittedAt":"2026-07-25T09:01:00","sourceTradingDate":"2026-07-22","marketStatus":"OPEN"}` — 매분 스케줄에서 해당 종목의 `sourceTime`이 직전 값과 달라진(새로 공개된) 경우에만 전송한다. id는 `STOCK:{symbol}:{sourceTime을 yyyyMMddHHmm으로 포맷}`(`sourceTime`이 09:00:00이므로 id는 emittedAt의 분(09:01)이 아니라 0900으로 끝난다).
- **status**: `{"market":"STOCK","marketStatus":"CLOSED","emittedAt":"2026-07-25T15:30:00"}` — `symbol`·`status`·`reason`은 시장 전체 상태 변화라 없음(생략). 직전 스케줄 실행의 `marketStatus`와 달라진 경우에만 1회 전송한다(서버 기동 시점 값을 기준선으로 삼아 기동 직후 오탐 전송하지 않는다).
- 장 마감(`marketStatus=CLOSED`) 후에도 마지막으로 공개됐던 종목의 `price`·`sourceTime`은 그대로 유지되고 `status`는 `AVAILABLE`을 유지한다 — snapshot·이미 전송된 price 이벤트 모두 동일하게 마지막 값을 유지하며 별도로 `UNAVAILABLE`로 되돌리지 않는다.
- 재접속하면 새 emitter로 `snapshot` 1건을 다시 받는다. 연결이 끊긴 동안 놓친 이벤트를 서버가 재전송하는 기능은 없다(MVP 제외).
- heartbeat(20초 간격 SSE 주석)·`retry` 힌트·`onCompletion`/`onTimeout`/`onError` 시 emitter 정리는 `SseEmitterRegistry`(이슈 #18)가 공통 처리하며 이 컨트롤러에서 재구현하지 않는다.

### 로컬 KIS 실수집 트리거 (local 프로필 전용)

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/dev/stock-replay-imports | Access Bearer 필수 | 본문 없음 | 200 `{"serviceDate":"2026-07-30","tradingDate":"2026-07-29","collectedKisCandleCount":5630,"preparationStatus":"READY","failureReason":null,"marketStatus":"OPEN"}` (`StockReplayImportTriggerResponse`) | `local` 프로필이 아니면 404. Access 인증 실패는 401 `UNAUTHORIZED` | 003 MKT-005 (개발 도구) |

**왜 있나.** 실제 KIS 분봉은 평일 08:10 배치가 수집하고 08:40 배치가 재생세션을 확정한다. 그 시각을 기다리지 않고, 또는 앱이 그 시각에 꺼져 있어 건너뛴 날에 즉시 실데이터를 채우기 위한 트리거다. `KisHistoricalCandleCollector.collect()`와 `StockReplaySessionScheduler.resolveTodaySession()`을 순서대로 한 번 실행한다.

**무엇을 하나.** ① 실제 수집을 실행한다(종목·거래일 단위로 이미 분봉이 있으면 건너뛴다 — 멱등). ② 재생세션을 확정한다. ③ 결과(수집 건수·세션 상태·시장상태)를 돌려준다.

- **`collectedKisCandleCount`는 그 거래일에 저장된 KIS 분봉의 총량이다** — 이번 호출로 새로 넣은 건수가 아니다. 수집은 종목·거래일 단위로 멱등 스킵하므로, 이미 채워진 날에 다시 호출하면 아무것도 새로 넣지 않고도 같은 총량(예: 5,630)이 그대로 나온다. **정상이다.**
- **0이면 그날 재생할 원본이 없다.** `preparationStatus`가 `READY`여도 마찬가지다 — 세션은 이전 호출에서 이미 확정돼 있을 수 있고 세션 상태만으로는 분봉 유무를 알 수 없다. 즉 **`READY` + `0`은 정상이 아니라 "세션은 섰는데 재생할 데이터가 없는" 깨진 상태**이며, 이 조합이면 그날 시세·차트·주문이 전부 막힌다. 판정은 세션 상태가 아니라 **이 건수로 한다.**
- 0이 나왔을 때 원인은 서버 로그의 `KIS 과거 분봉 조회가 종목 단위로 실패했습니다` WARN에 붙은 KIS 오류 코드로 판별한다. 실측한 원인 세 가지 — `EGW02004`(앱키 환경과 `kis.base-url` 도메인 불일치), `EGW00133`(토큰 발급 1분당 1회 제한), `EGW00201`(초당 거래건수 초과).
- **트랜잭션을 열지 않는다.** `collect()`가 16종목 순차 HTTP 호출 동안 DB 커넥션을 점유하지 않도록 설계된 것을 깨지 않기 위해, DB 작업만 `StockReplayImportTriggerWriter`의 별도 트랜잭션 메서드로 분리했다.
- 실측 소요는 **약 1분 20초**(16종목, `kis.request-interval-ms=600` + 재시도 포함). 08:10~08:40의 30분 여유 안에 충분히 들어간다.

**KIS 앱키 환경과 도메인은 반드시 짝이 맞아야 한다.** 모의투자 앱키로 실전 도메인을 호출하면 토큰 발급까지는 성공하지만 업무 API가 `EGW02004`로 거부한다 — **토큰 발급 성공을 앱키 종류의 근거로 삼으면 안 된다**(2026-07-30 실측).

| 앱키 종류 | `kis.base-url` |
|---|---|
| 모의투자 | `https://openapivts.koreainvestment.com:29443` |
| 실전투자 | `https://openapi.koreainvestment.com:9443` |


**2026-07-30 실측 결과.** 19:50 KST(장외)에 시드 → `marketStatus: OPEN`, 삼성전자 현재가 `70900.0000`(`sourceTime` `2026-07-29T15:30:00`), 캔들 391건(09:00~15:30), 16종목·6,256건 삽입. 이어서 10주 매수(`amount` 709,000 / `fee` 106) → 4주 매도(`realizedPnl` -84) → `cashBalance` 9,574,452로 정산까지 확인했다. 시드 전에는 현재가 409 `PRICE_UNAVAILABLE`·캔들 `200 []`·주문 409 `MARKET_CLOSED`였다.

---

## community

### 커뮤니티 게시물 작성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/community/posts | Access Bearer 필수 | `{"title":"게시물 제목","content":"게시물 본문","instrumentId":1}` (`title` 최대 100자, `content` 최대 5,000자, `instrumentId`는 선택·nullable) | 201 `{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00","instrumentId":1,"instrumentSymbol":"005930","instrumentName":"삼성전자"}` | 제목·본문 누락·빈 값·공백·최대 길이 초과, **존재하지 않거나 비활성(`tradable=false`)인 `instrumentId` 태그**는 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 008 COM-001, 022 COM-004, Issue #23, Issue #246 |

작성자는 요청에서 받지 않고 Access Token의 인증 사용자로 결정한다. `instrumentId`를 생략하거나 `null`로 보내면 미태그 게시물로 생성된다(하위 호환) — 이때 응답의 `instrumentId`·`instrumentSymbol`·`instrumentName`은 모두 `null`이다. 값을 보내면 `InstrumentService.getTradableInstrumentEntity`로 존재·`tradable` 여부를 검증하며, 실패 사유(미존재·비활성)는 구분하지 않고 동일한 400 `VALIDATION_ERROR`로 응답한다. 게시물당 태그 가능한 종목은 최대 1개다.

### 커뮤니티 게시물 단건 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/community/posts/{postId} | Access Bearer 필수 | 경로 변수 `postId` | 200 `{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00","instrumentId":1,"instrumentSymbol":"005930","instrumentName":"삼성전자"}` (태그 없는 게시물은 세 필드 모두 `null`) | Access 인증 실패는 401 `UNAUTHORIZED`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-001, 022 COM-004, Issue #25, Issue #246 |

### 커뮤니티 게시물 목록 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/community/posts | Access Bearer 필수 | `page`(기본 0, 0 이상), `size`(기본 10, 1~50), `instrumentId`(선택) | 200 `{"content":[{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00","instrumentId":1,"instrumentSymbol":"005930","instrumentName":"삼성전자"}],"page":0,"size":10,"totalElements":1,"totalPages":1,"hasNext":false}` | `page`·`size` 범위 밖은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 008 COM-001, 022 COM-004, Issue #24, Issue #246 |

작성시각(`created_at`) 내림차순으로 정렬하며 동시각 항목은 `id` 내림차순으로 안정 정렬해 페이지 경계 중복·누락을 방지한다. 게시물이 없으면 오류가 아니라 200과 빈 `content`를 반환한다. `instrumentId` 지정 시 그 종목이 태그된 게시물만 반환하며, 존재하지 않는 `instrumentId`를 넘겨도 오류가 아니라 빈 `content`를 반환한다(생성·수정 시의 태그 유효성 검증과 달리 이 필터는 조회 조건일 뿐이다). 태그가 없는 게시물은 `instrumentId`·`instrumentSymbol`·`instrumentName`이 모두 `null`이다. 목록 조회는 QueryDSL `leftJoin().fetchJoin()`으로 `instrument`를 함께 로딩해 N+1을 방지한다.

### 커뮤니티 게시물 수정

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| PATCH | /api/community/posts/{postId} | Access Bearer 필수 | 경로 변수 `postId`, 본문 `{"title":"게시물 제목","content":"게시물 본문","instrumentId":1}` (`title` 최대 100자, `content` 최대 5,000자, `instrumentId`는 선택·nullable) | 200 `{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00","instrumentId":1,"instrumentSymbol":"005930","instrumentName":"삼성전자"}` | 제목·본문 누락·빈 값·공백·최대 길이 초과, **존재하지 않거나 비활성인 `instrumentId` 태그**는 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 본인 소유가 아닌 게시물은 403 `FORBIDDEN`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-001, 022 COM-004, Issue #26, Issue #246 |

작성자 본인만 수정할 수 있으며 소유자 확인은 요청 본문이 아닌 Access Token의 인증 사용자로 판단한다. `title`·`content`와 동일하게 매 요청이 전체를 교체한다(부분 패치 아님) — `instrumentId`를 생략/`null`로 보내면 기존 태그를 해제한다. 태그를 유지하려면 클라이언트가 기존 `instrumentId`를 다시 보내야 한다.

### 커뮤니티 게시물 삭제

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| DELETE | /api/community/posts/{postId} | Access Bearer 필수 | 경로 변수 `postId`, 본문 없음 | 204 (본문 없음) | Access 인증 실패는 401 `UNAUTHORIZED`. 본인 소유가 아닌 게시물은 403 `FORBIDDEN`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-001, Issue #27 |

작성자 본인만 삭제할 수 있으며 소유자 확인은 Access Token의 인증 사용자로 판단한다. 삭제 시 해당 게시물에 달린 댓글을 먼저 모두 삭제한 뒤 게시물을 삭제한다.

### 커뮤니티 게시물 댓글 목록 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/community/posts/{postId}/comments | Access Bearer 필수 | 경로 변수 `postId` | 200 `[{"commentId":1,"authorNickname":"finplayer","content":"댓글 본문","createdAt":"2026-07-27T12:00:00"}]`; 댓글이 없으면 `[]` | Access 인증 실패는 401 `UNAUTHORIZED`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-002, Issue #29 |

대상 게시물의 댓글만 `createdAt` 오름차순으로 반환하며, 생성시각이 같으면 `commentId` 오름차순으로 안정 정렬한다. 작성자는 fetch join으로 함께 조회해 댓글 수에 따른 추가 쿼리를 방지한다.

### 커뮤니티 게시물 댓글 작성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/community/posts/{postId}/comments | Access Bearer 필수 | `{"content":"댓글 본문"}` (`content` 필수, 최대 1,000자) | 201 `{"commentId":1,"authorNickname":"finplayer","content":"댓글 본문","createdAt":"2026-07-27T12:00:00"}` | 본문 누락·공백·1,000자 초과는 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-002, Issue #28 |

작성자는 요청에서 받지 않고 Access Token의 인증 사용자로 결정한다. 댓글은 부모 댓글 없이 게시글 바로 아래에 생성되는 평면 구조다.

### 커뮤니티 댓글 삭제

| Method | URL | 인증 | 응답 | 오류 | Spec |
|---|---|---|---|---|---|
| DELETE | /api/community/comments/{commentId} | Access Bearer 필수 | 204 본문 없음 | 작성자 불일치는 403 `FORBIDDEN`. 댓글 미존재는 404 `NOT_FOUND`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 008 COM-002, Issue #30 |

댓글 삭제는 소유자만 가능하며 `CommentController`(`/api/community/comments`)로 분리되어 있다. Security 공개 화이트리스트에 포함되지 않은 인증 필요 경로다.

---

## order

### 시장가 매수·매도 주문 생성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/orders | Access Bearer 필수, Header `Idempotency-Key` 필수(`OrderCreateRequest`와 별도) | 매수 `{"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET","quantity":"10"}`, 매도 `{"market":"STOCK","instrumentId":1,"side":"SELL","orderType":"MARKET","quantity":"10"}` (`market`은 `STOCK`\|`CRYPTO` 리터럴만 파싱 성공, `side`는 `BUY`\|`SELL` 리터럴만 파싱 성공, `orderType`은 문자열, `quantity`는 문자열 숫자) | BUY 201 `{"orderId":1,"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET","status":"FILLED","quantity":10,"requestedAt":"2026-07-29T09:00:00","tradeId":1,"price":70000,"amount":700000,"fee":105,"realizedPnl":null,"executedAt":"2026-07-29T09:00:00"}`; SELL 201 `{"orderId":2,"market":"STOCK","instrumentId":1,"side":"SELL","orderType":"MARKET","status":"FILLED","quantity":10,"requestedAt":"2026-07-29T09:05:00","tradeId":2,"price":71000,"amount":710000,"fee":106,"realizedPnl":9895,"executedAt":"2026-07-29T09:05:00"}` (둘 다 `OrderResponse`) — BUY는 `realizedPnl`이 항상 `null`, SELL은 부호 있는 정수(손실은 음수). 동일 `Idempotency-Key`+동일 요청 본문으로 재요청하면 새로 체결하지 않고 최초 응답을 그대로 재구성해 동일하게 201로 반환한다 | `Idempotency-Key` 누락, `market`\|`side` 미지원 리터럴(Jackson 파싱 실패), 수량 형식 위반(주식 소수·코인 8자리 초과·0 이하), 코인 최소주문금액(5,000원) 미달(매수·매도 공통 적용), 요청 시장≠종목 시장은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. `instrumentId` 미존재는 404 `NOT_FOUND`. 주식 장외는 409 `MARKET_CLOSED`, 유효한 최신 가격 없음은 409 `PRICE_UNAVAILABLE`, 현금 부족(BUY)은 409 `INSUFFICIENT_CASH`, 보유 없음 또는 요청수량이 예약 가능 수량(`availableQuantity = quantity - reservedQuantity`)을 초과(SELL)하면 409 `INSUFFICIENT_QTY`, 동일 `Idempotency-Key`로 다른 요청 본문을 보내거나(요청 해시 불일치) 동시 요청 경합 시 최초 응답 재구성마저 실패하면 409 `IDEMPOTENCY_CONFLICT`. `orderType != "MARKET"`(예: `"LIMIT"`)은 422 `UNSUPPORTED_ORDER_TYPE` 공통 오류 형식 | 004 ORD-001~004, 005 ORD-001~006, 015 LMT-001, 016 candidate 6, Issue #13, Issue #41, Issue #22 |

계좌·주문자는 요청에서 받지 않고 Access Token의 인증 사용자로 결정한다. `OrderService.createOrder`는 요청 해시를 계산한 뒤 `(userId, idempotencyKey)`로 기존 주문을 먼저 조회한다(애플리케이션 레벨 선제 조회) — 기존 주문이 있고 요청 해시가 같으면 그 주문·체결을 다시 읽어 조립한 `OrderResponse`를 검증·체결 로직을 전혀 타지 않고 그대로 반환하며(재요청도 상태코드 201 포함 최초 응답과 동일), 요청 해시가 다르면 즉시 409 `IDEMPOTENCY_CONFLICT`다. 기존 주문이 없으면 신규 생성 경로(`OrderExecutionService.execute`)로 진행하되, 두 요청이 진짜 동시에 들어와 DB 유니크 제약(`uk_orders_user_idempotency`, `V10` 마이그레이션에 이미 존재)에 걸리는 경합만 예외적으로 저장 직후 재조회를 재시도해 가능하면 최초 응답을 재구성하고, 그래도 찾지 못하면 방어적으로 409 `IDEMPOTENCY_CONFLICT`다. 서로 다른 사용자가 같은 키를 사용해도 조회·제약 모두 `userId`로 스코프가 분리되어 있어 서로 간섭하지 않는다.

SELL은 가격을 조회하기 전에 보유수량부터 검증한다(불필요한 시세 조회 회피 — 보유 없음 또는 요청수량이 예약 가능 수량(`availableQuantity = quantity - reservedQuantity`)을 초과하면 409 `INSUFFICIENT_QTY`, 이 시점까지 어떤 것도 저장하지 않는다). 지정가 매도(LMT-001) 예약으로 걸린 수량은 이 검증에서 매도 가능분으로 잡히지 않는다(`PortfolioSellService.getHoldingForUpdateOrThrow`, 016 candidate 6). 이후 보유 lot을 FIFO(체결시각 오름차순, 동시각은 `id` 오름차순)로 소비해 `trade_allocations`에 배분을 저장하고, `realizedPnl = (매도금액 - 매도수수료) - (배분된 매수원가 합 + 배분된 매수수수료 합)`을 계산해 `trades.realized_pnl`·`accounts.realized_pnl`에 반영한다. `Holding.averagePrice`는 매도로 갱신되지 않으며, 전량 매도로 보유수량이 0이 되면 `holding.isActive`는 `false`가 된다.

검증·시세·현금·보유수량 부족 등 모든 실패 경로는 주문·체결·계좌·보유·lot·배분 테이블에 어떤 흔적도 남기지 않는다(하나의 `@Transactional` 롤백).

### 코인 지정가 매수·매도 주문 생성 (LMT-001)

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/orders/limit | Access Bearer 필수, Header `Idempotency-Key` 필수(`LimitOrderCreateRequest`와 별도) | 매수 `{"market":"CRYPTO","instrumentId":1,"side":"BUY","quantity":"0.1","limitPrice":"70000000"}`, 매도 `{"market":"CRYPTO","instrumentId":1,"side":"SELL","quantity":"0.1","limitPrice":"70000000"}` (`market`은 `CRYPTO`만 허용, `side`는 `BUY`\|`SELL` 리터럴만 파싱 성공, `quantity`·`limitPrice`는 문자열 숫자, 둘 다 필수) | 매수·매도 공통 201 `{"orderId":1,"market":"CRYPTO","instrumentId":1,"side":"BUY","orderType":"LIMIT","status":"PENDING","quantity":0.1,"limitPrice":70000000,"requestedAt":"2026-08-05T09:00:00"}` (`LimitOrderResponse`) — `orderType`은 항상 `"LIMIT"`, `status`는 항상 `"PENDING"`(생성 시점에 체결하지 않으므로 `Trade`가 없다). 즉시체결 조건(매수 지정가≥현재가, 매도 지정가≤현재가)을 충족해도 생성 시점에 거부하지 않는다 — 체결은 `PriceStore` 가격 갱신 트리거(LMT-002)에서만 발생. 동일 `Idempotency-Key`+동일 요청 본문으로 재요청하면 새로 예약하지 않고 최초 응답을 그대로 재구성해 동일하게 201로 반환한다 | `Idempotency-Key` 누락, `market != "CRYPTO"`("코인 종목만 지정가 주문을 지원합니다."), `side` 미지원 리터럴(Jackson 파싱 실패), 수량 형식 위반(코인 8자리 초과·0 이하), 지정가 0 이하, 코인 최소주문금액(5,000원, `수량×지정가` 기준) 미달, 요청 시장≠종목 시장은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. `instrumentId` 미존재는 404 `NOT_FOUND`. 예약 가능 현금(`cashBalance-reservedCash`) 부족(BUY)은 409 `INSUFFICIENT_CASH`, 예약 가능 수량(`quantity-reservedQuantity`) 부족 또는 보유 없음(SELL)은 409 `INSUFFICIENT_QTY`, 동일 `Idempotency-Key`로 다른 요청 본문을 보내거나 동시 요청 경합 시 최초 응답 재구성마저 실패하면 409 `IDEMPOTENCY_CONFLICT` | 015 LMT-001, Issue #210 |

`POST /api/orders`(시장가 전용)의 `orderType="LIMIT"` 422 `UNSUPPORTED_ORDER_TYPE` 거부는 이 엔드포인트 추가와 무관하게 그대로 유지된다 — 두 경로가 영구히 공존한다.

`LimitOrderService.createLimitOrder`는 `POST /api/orders`(`OrderService.createOrder`)와 동일한 멱등성 패턴을 재사용한다 — 요청 해시 계산 → `(userId, idempotencyKey)` 선제 조회(있고 해시 일치 시 `Order`만으로 응답 재구성, 불일치 시 즉시 409) → 신규 생성 경로(`LimitOrderCreationService.execute`) → 유니크 제약(`uk_orders_user_idempotency`) 경합 시 저장 직후 재조회 폴백.

예약(에스크로) 로직은 매수·매도가 다른 자원만 잠근다(plan.md 잠금 순서 표) — **BUY**는 계좌만 잠그고(`AccountService.getAccountForUpdate`) `cashRequired = FLOOR(수량×지정가) + FLOOR(FLOOR(수량×지정가)×0.05%)`를 `availableCash`와 비교해 부족하면 저장 전 거부, 통과하면 `Account.reserveCash`로 예약만 하고(`cashBalance`는 불변) `Order.createLimitPending`을 저장한다. **SELL**은 계좌 락 없이 holding만 잠그고(`PortfolioSellService.getHoldingForUpdateOrThrow`, `availableQuantity` 기준 검증) `Holding.reserveQuantity`로 수량만 예약한다(계좌 현금은 건드리지 않음). 두 경로 모두 실패 시 아무것도 예약·저장하지 않는다(하나의 `@Transactional` 롤백).

### 코인 지정가 주문 취소 (LMT-003)

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| DELETE | /api/orders/{orderId} | Access Bearer 필수, `Idempotency-Key` 헤더 불필요(DELETE는 멱등, 재호출은 상태 검증으로 자연히 409 거부) | 없음(경로 변수 `orderId`만) | 204, 본문 없음(`DELETE /api/community/posts/{postId}`·`DELETE /api/community/comments/{commentId}` 컨벤션 재사용) — 매수 취소는 `accounts.reserved_cash`가 해당 주문이 예약했던 만큼 정확히 감소(`cashBalance` 불변), 매도 취소는 `holdings.reserved_quantity`가 정확히 감소(`quantity` 불변), 주문 `status`는 `CANCELLED`로 변경 | `orderId`에 해당하는 주문 없음은 404 `NOT_FOUND`. 존재하지만 요청자 소유가 아니면 403 `FORBIDDEN`(소유하지 않은 주문의 상태·시장가/지정가 여부를 흘리지 않음). 본인 소유이지만 이미 `FILLED`면 409 `ORDER_ALREADY_FILLED`(신규 코드), 이미 `CANCELLED`면 409 `ORDER_ALREADY_CANCELLED`(신규 코드). 검증 순서는 존재(404)→소유(403)→상태(409) 고정. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 015 LMT-003, Issue #218 |

시장가 주문(`orderType=MARKET`)은 생성 즉시 `FILLED`이므로 이 엔드포인트로 취소를 시도하면 상태 검증(409 `ORDER_ALREADY_FILLED`)에서 자연히 걸러진다 — 별도 `orderType` 분기 없이 상태만으로 시장가 주문을 배제한다. `LimitOrderCancelService.cancelOrder`는 `orderRepository.findByIdForUpdate`로 주문 락+존재 확인을 동시에 수행한 뒤(LMT-002 `fillIfPending`과 같은 지점) 소유·상태를 순서대로 검증하고, `accountService.getAccountByIdForUpdate`로 계좌를 잠근다(잠금 순서는 LMT-002 체결과 동일한 `order → account → (SELL만) holding`). BUY는 `Account.releaseReservedCash`, SELL은 `PortfolioSellService.getHoldingForUpdate` + `Holding.releaseReservedQuantity`로 예약만 되돌리고 실제 `cashBalance`·`quantity`는 건드리지 않는다(애초에 체결되지 않았으므로). 체결 트리거(LMT-002)와 취소가 동시에 도착해도 두 흐름 모두 order를 가장 먼저 잠그므로, 먼저 락을 획득한 쪽이 끝까지 처리되고 나중 쪽은 락 대기 후 `status != PENDING`을 보고 자기 작업을 거부/no-op한다 — 예약이 이중으로 반환되거나 이중으로 소비되지 않는다. 이미 `FILLED`/이미 `CANCELLED`를 하나의 `ORDER_NOT_PENDING`으로 묶지 않고 `ORDER_ALREADY_FILLED`/`ORDER_ALREADY_CANCELLED`로 분리한 이유는 클라이언트가 두 사유를 구분해 다른 안내를 보여줄 수 있어야 한다는 사용자 판단(2026-08-05, spec.md "확정된 설계 결정" 9번)이다.

### 지정가 주문 수정 (LMT-005)

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| PATCH | /api/orders/{orderId} | Access Bearer 필수, `Idempotency-Key` 헤더 불필요(변경 후 값을 절대값으로 지정하는 요청이라 자연 멱등 — `DELETE /api/orders/{orderId}`와 같은 이유) | `LimitOrderUpdateRequest`: `limitPrice`·`quantity` 둘 다 nullable `BigDecimal`, 부분 갱신 허용(예: `{"limitPrice":"75000000"}`만 보내면 `quantity`는 기존값 유지). 둘 다 생략(둘 다 `null`)하면 요청 자체를 거부 | 200 `{"orderId":1,"market":"CRYPTO","instrumentId":1,"side":"BUY","orderType":"LIMIT","status":"PENDING","quantity":0.1,"limitPrice":75000000,"requestedAt":"2026-08-05T09:00:00"}` (`LimitOrderResponse`, `POST /api/orders/limit` 응답과 동일 타입 재사용) — `orderId`·`requestedAt`은 변경 전 값 그대로 유지(새 주문을 만들지 않고 같은 행을 갱신하므로 `GET /api/orders/pending`(LMT-004)의 정렬 위치가 바뀌지 않는다), `status`는 `PENDING` 유지 | `limitPrice`·`quantity` 둘 다 생략("변경할 값이 없습니다."), 합성된 최종 수량이 0 이하이거나 코인 8자리 초과, 합성된 최종 지정가가 0 이하, 합성된 최종값 기준 코인 최소주문금액(5,000원) 미달은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. `orderId`에 해당하는 주문 없음은 404 `NOT_FOUND`. 존재하지만 요청자 소유가 아니면 403 `FORBIDDEN`. 본인 소유이지만 이미 `FILLED`면 409 `ORDER_ALREADY_FILLED`, 이미 `CANCELLED`면 409 `ORDER_ALREADY_CANCELLED`(둘 다 LMT-003과 동일 코드 재사용, 신규 오류 코드 없음). 예약 가능 현금(`cashBalance-reservedCash`, 변경 전 예약 해제 후 기준) 부족(BUY)은 409 `INSUFFICIENT_CASH`, 예약 가능 수량(`quantity-reservedQuantity`, 변경 전 예약 해제 후 기준) 부족(SELL)은 409 `INSUFFICIENT_QTY`(둘 다 LMT-001 생성과 동일 코드 재사용) | 015 LMT-005, Issue #239 |

이 엔드포인트는 신규 오류 코드가 전혀 없다 — `VALIDATION_ERROR`·`NOT_FOUND`·`FORBIDDEN`·`ORDER_ALREADY_FILLED`·`ORDER_ALREADY_CANCELLED`·`INSUFFICIENT_CASH`·`INSUFFICIENT_QTY` 전부 LMT-001·LMT-003이 이미 쓰던 코드를 그대로 재사용한다. 검증 순서·잠금 순서는 LMT-003(취소)과 동일하게 존재(404)→소유(403)→상태(409)→(형식·최소주문금액 재검증)→`order → account → (SELL만) holding` 락 순서로 고정된다(`LimitOrderModifyService.modifyOrder`). 변경 흐름은 "해제 후 재예약"이다 — 매수는 `Account.releaseReservedCash(변경 전 예약)` → 예약 가능 현금 재검증 → `Account.reserveCash(변경 후 예약)`, 매도는 `Holding.releaseReservedQuantity(변경 전 수량)` → 예약 가능 수량 재검증 → `Holding.reserveQuantity(변경 후 수량)` 순으로 처리하며, 재예약 단계에서 거부되면(`INSUFFICIENT_CASH`/`INSUFFICIENT_QTY`) 트랜잭션 전체가 롤백되어 해제도 함께 취소되므로 주문·계좌·보유 모두 변경 전 상태 그대로 남는다(부분 반영 없음). 예약 재계산 비용(수수료 포함 `수량 × 지정가` 총 예약액)은 `LimitOrderFeeCalculator`(LMT-001 생성·LMT-003 취소와 공통)로 계산해 변경 전·후 값을 각각 산출한다. 수정 결과가 즉시 체결 조건(매수 지정가≥현재가, 매도 지정가≤현재가)을 충족해도 LMT-001 생성과 동일하게 거부하지 않는다 — 체결은 다음 가격 갱신 트리거(LMT-002)에서만 발생한다. 체결 트리거(LMT-002)·취소(LMT-003)와 수정이 동시에 도착해도 세 흐름 모두 order를 가장 먼저 잠그므로 먼저 락을 획득한 쪽이 끝까지 처리되고 나중 쪽은 갱신된 `status`를 보고 자기 작업을 거부/no-op한다.

### 내 주문 목록 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/orders | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용), `cursor`(선택, `{requestedAt}_{id}` 형식 문자열, 생략 시 첫 페이지), `limit`(선택, 기본 20, 1~100) | 200 `{"content":[{"orderId":1,"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET","status":"FILLED","quantity":10,"limitPrice":null,"requestedAt":"2026-07-29T09:00:00"}, ...],"nextCursor":"2026-07-29T09:00:00_1","hasNext":true}` (`OrderListResponse`); 주문이 없으면 200 `{"content":[],"nextCursor":null,"hasNext":false}` | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`), `limit`이 1~100 범위 밖(클램핑 없음), `cursor`가 `{ISO_LOCAL_DATE_TIME}_{id}` 형식으로 파싱 실패(구분자 없음·날짜 파싱 실패·id 파싱 실패)는 모두 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 006 PORT-003, 018 PORT-003(1차 고도화), Issue #21, Issue #182, 015 LMT-004(`limitPrice`), Issue #235, PR #237 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유의 해당 시장 계좌 주문으로만 결정한다(`AccountService.getAccountFor`로 소유권+시장 스코프 검증). `requestedAt` 내림차순, 동시각은 `id` 내림차순으로 정렬하며, 커서는 "이전 페이지 마지막 행보다 이 시각 이전이거나(동시각이면 이 id보다 작은)" 조건으로 다음 페이지를 이어받아 페이지 경계에서 중복·누락이 없다. 응답 항목 필드는 `orderId`·`market`·`instrumentId`·`side`·`orderType`·`status`·`quantity`·`limitPrice`·`requestedAt` 9개로 고정이며, 체결 전용 필드(`tradeId`·`price`·`amount`·`fee`·`executedAt`)는 어떤 이름으로도 포함하지 않는다. `limitPrice`는 지정가(`orderType="LIMIT"`) 주문만 값을 가지며, 시장가(`orderType="MARKET"`) 주문은 `Order.limitPrice`가 애초에 저장되지 않으므로 항상 `null`이다(PR #237 리뷰 반영 — 미체결 지정가 목록에서 "얼마에 걸어둔 주문인지" 확인할 수단이 없다는 차단 지적 해소).

### 미체결 주문 목록 조회 (LMT-004)

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/orders/pending | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용), `cursor`(선택, `{requestedAt}_{id}` 형식 문자열, 생략 시 첫 페이지), `limit`(선택, 기본 20, 1~100) | 200 `{"content":[{"orderId":3,"market":"CRYPTO","instrumentId":1,"side":"BUY","orderType":"LIMIT","status":"PENDING","quantity":0.1,"limitPrice":70000000,"requestedAt":"2026-08-06T09:00:00"}, ...],"nextCursor":"2026-08-06T09:00:00_3","hasNext":true}` (`OrderListResponse`, `GET /api/orders`와 동일 타입 재사용); 미체결 주문이 없으면 200 `{"content":[],"nextCursor":null,"hasNext":false}` | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴, `limit`이 1~100 범위 밖(클램핑 없음), `cursor` 파싱 실패는 모두 400 `VALIDATION_ERROR`. `market`으로 조회한 계좌가 존재하지 않으면 404 `NOT_FOUND`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 015 LMT-004, Issue #235, PR #237(`limitPrice`) |

조회 대상은 `GET /api/orders`와 동일하게 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유의 해당 시장 계좌 주문으로만 결정하며, 정렬·커서 규칙(`requestedAt` 내림차순, 동시각은 `id` 내림차순, 이전 페이지 마지막 행 기준 이어받기)과 응답 9개 필드(`limitPrice` 포함)도 완전히 동일하다 — 유일한 차이는 `status = PENDING`인 주문만 필터링한다는 것이다(`OrderRepositoryCustom.findByAccountIdAndStatusWithCursor`, 기존 `findByAccountIdWithCursor`와 병렬 메서드). 이 코드베이스에서 `status = PENDING`은 코인 지정가(`orderType = LIMIT`) 주문만 가질 수 있으므로(시장가는 생성 즉시 `FILLED`) 별도 `orderType` 조건 없이도 결과는 자연히 지정가 주문만 포함하고, 이 목록의 모든 항목은 `limitPrice`가 실제 값을 갖는다(PR #237 리뷰 반영). 목록에 있던 주문이 체결(LMT-002)되거나 취소(LMT-003)되면 이후 조회에서 더 이상 나타나지 않는다 — 별도 스냅샷·캐시 없이 조회 시점의 `status`를 그대로 반영하는 실시간 목록이다. `market=STOCK`으로 요청해도 400으로 거부하지 않는다(`GET /api/orders`와 동일 — 조회 대상 시장을 고르는 필수 파라미터이지 생성 제약이 아니다); 현재 주식 지정가가 없으므로 결과는 자연히 빈 배열이다.

### 내 체결 내역 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/trades | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용), `cursor`(선택, `{executedAt}_{id}` 형식 문자열, 생략 시 첫 페이지), `limit`(선택, 기본 20, 1~100) | 200 `{"content":[{"tradeId":2,"instrumentId":1,"side":"SELL","price":71000,"quantity":5,"amount":355000,"fee":53,"realizedPnl":5000,"executedAt":"2026-07-29T09:05:00"},{"tradeId":1,"instrumentId":1,"side":"BUY","price":70000,"quantity":10,"amount":700000,"fee":105,"realizedPnl":null,"executedAt":"2026-07-29T09:00:00"}],"nextCursor":"2026-07-29T09:00:00_1","hasNext":true}` (`TradeListResponse`); 체결내역이 없으면 200 `{"content":[],"nextCursor":null,"hasNext":false}` | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`), `limit`이 1~100 범위 밖(클램핑 없음), `cursor`가 `{ISO_LOCAL_DATE_TIME}_{id}` 형식으로 파싱 실패(구분자 없음·날짜 파싱 실패·id 파싱 실패)는 모두 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 006 PORT-002, Issue #82 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유의 해당 시장 계좌 체결내역으로만 결정한다(`AccountService.getAccountFor`로 소유권+시장 스코프 검증). `executedAt` 내림차순, 동시각은 `id` 내림차순으로 정렬하며, 커서는 "이전 페이지 마지막 행보다 이 시각 이전이거나(동시각이면 이 id보다 작은)" 조건으로 다음 페이지를 이어받아 페이지 경계에서 중복·누락이 없다. 응답 항목 필드는 `tradeId`·`instrumentId`·`side`·`price`·`quantity`·`amount`·`fee`·`realizedPnl`·`executedAt` 9개로 고정이며, 매수 건은 `realizedPnl`이 항상 `null`이고 매도 건만 FIFO 실현손익 값을 갖는다. `orderId`·`orderType`·`status`·`requestedAt` 등 주문 목록(`GET /api/orders`) 전용 필드는 포함하지 않는다 — 두 API는 "무엇을 요청했는가"와 "실제로 얼마에 체결됐는가"를 분리해서 보여준다.

---

## account

### 시장별 계좌 요약 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/accounts/summary?market= | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용) | 200 `{"cashBalance":9300000,"reservedCash":500000,"holdingsValue":720000,"totalValue":10020000,"realizedPnl":0,"unrealizedPnl":20000,"returnRate":0.0020}` (`AccountSummaryResponse`, 7개 필드 고정) | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`)은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 006 ACCT-002, Issue #81, 015 LMT-004(`reservedCash`), Issue #235 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유의 해당 시장 계좌로만 결정한다(경로·쿼리에 계좌 식별자 없음 — 타인 계좌 조회 자체가 불가능한 구조). 보유 종목이 없어도(신규 가입 직후 등) 예외 없이 200과 0으로 채운 응답을 반환한다(단 `cashBalance`는 초기 시드머니).

응답 7개 필드: `cashBalance`(현금잔고, 계좌 원장 값 그대로) · `reservedCash`(코인 지정가 매수로 예약된 현금, `accounts.reserved_cash` 원장 값 그대로 — 015 LMT-004, 이슈 #235로 추가) · `holdingsValue`(활성 보유의 평가금액 합산 — 아래 시세 무효 처리 참고) · `totalValue`(`cashBalance + holdingsValue`) · `realizedPnl`(계좌 원장 값 그대로, 재계산 없음) · `unrealizedPnl`(활성 보유의 미실현손익 합산 — 아래 시세 무효 처리 참고) · `returnRate`(`(totalValue − seedMoney) ÷ seedMoney`, scale 4 `RoundingMode.HALF_UP`, 비율 값이며 `%` 변환은 응답 책임이 아님). ACCT-003(합산 포트폴리오, Issue #51)은 `totalValue` 등 기존 계산식을 그대로 재사용하며 `reservedCash`는 합산 응답에 포함하지 않는다. `reservedCash`는 원장 값을 그대로 노출할 뿐이며 `totalValue` 등 다른 필드의 계산식을 바꾸지 않는다 — "주문 가능 금액"을 뜻하는 `availableCash` 같은 파생 필드는 추가하지 않는다(클라이언트가 `cashBalance - reservedCash`로 직접 계산할 수 있다, spec.md `docs/specs/015-limit-order/spec.md` "확정된 설계 결정" 11번).

**시세 무효(`PriceStatus.UNAVAILABLE`) 보유 처리 (PR #96 리뷰 반영, 2026-07-30 정책 확정)**: 주식 시세는 재생(replay) 기반이라 장 마감 시간대(평일 09:01 이전·주말·공휴일)엔 전 종목이 동시에 `UNAVAILABLE`이 된다. 이 상태의 보유 종목을 합산에서 완전히 제외(원가까지 제외)하면 실제로는 손실이 없는데도 `holdingsValue=0`·수익률 대폭 마이너스로 보이는 오류가 발생한다(QA 재현: 00:52 KST, 현금+보유 10주 계좌가 수익률 -7%로 응답). 그래서 시세 무효 보유는 다음과 같이 처리한다 — **`evaluationAmount` 대신 `costBasis`(보유수량 × 평균단가, 시세와 무관하게 항상 채워짐)를 `holdingsValue`에 합산하고, `unrealizedPnl` 합계에는 0만 가산한다**(손익을 알 수 없으니 "원금만큼 있다"로 취급, 손익 자체는 표시하지 않음). 시세 유효(`AVAILABLE`) 보유는 기존과 동일하게 `evaluationAmount`·`unrealizedPnl`을 그대로 합산한다. 한 종목의 시세 무효가 전체 계좌 요약 조회를 막지는 않는다(예외 없이 200).

---

## portfolio

### 시장별 보유 종목 목록 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/holdings?market= | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용) | 200 `[{"instrumentId":1,"symbol":"005930","name":"삼성전자","quantity":10,"reservedQuantity":0,"averagePrice":70000,"currentPrice":71000,"evaluationAmount":710000,"unrealizedPnl":10000,"returnRate":0.0143,"priceStatus":"AVAILABLE"},{"instrumentId":2,"symbol":"000660","name":"SK하이닉스","quantity":5,"reservedQuantity":2,"averagePrice":120000,"currentPrice":null,"evaluationAmount":null,"unrealizedPnl":null,"returnRate":null,"priceStatus":"UNAVAILABLE"}]` (`HoldingListItemResponse[]`, 11개 필드 고정); 보유 종목이 없으면 200 `[]` | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`)은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 006 PORT-001, Issue #52, 015 LMT-004(`reservedQuantity`), Issue #235 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유의 해당 시장 계좌가 보유한 **활성**(`isActive=true`) 종목으로만 결정한다(경로·쿼리에 계좌·보유 식별자 없음 — 타인 보유 조회 자체가 불가능한 구조). 전량 매도한 종목은 목록에서 제외되며, 보유 종목이 없으면 예외 없이 200과 빈 배열을 반환한다. `market` 필드는 응답에 포함하지 않는다 — 요청 쿼리로 이미 단일 시장으로 필터링되어 있다.

응답 11개 필드: `instrumentId`·`symbol`·`name`(종목 표시 정보, 화면이 추가 조회를 하지 않도록) · `quantity`·`reservedQuantity`(코인 지정가 매도로 예약된 수량, `holdings.reserved_quantity` 원장 값 그대로 — 015 LMT-004, 이슈 #235로 추가) · `averagePrice`(원장 값, 시세와 무관하게 항상 채워짐) · `currentPrice`·`evaluationAmount`·`unrealizedPnl`·`returnRate`(아래 시세 무효 처리 참고) · `priceStatus`(`"AVAILABLE"`\|`"UNAVAILABLE"`). `reservedQuantity`는 원장 값을 그대로 노출할 뿐이며 `quantity`·평가금액 등 다른 필드의 계산식을 바꾸지 않는다 — "주문 가능 수량"을 뜻하는 `availableQuantity` 같은 파생 필드는 추가하지 않는다(클라이언트가 `quantity - reservedQuantity`로 직접 계산할 수 있다, `docs/specs/015-limit-order/spec.md` "확정된 설계 결정" 11번).

**시세 무효(`PriceStatus.UNAVAILABLE`) 종목 처리 — 계좌 요약(`## account`)과 다른 정책**: 이 API는 종목 단위 목록이므로 `account` 절의 "시세 무효 시 원가로 폴백" 집계 정책을 적용하지 않는다. 대신 `currentPrice`·`evaluationAmount`·`unrealizedPnl`·`returnRate` 4개 필드를 **`null` 그대로 노출**하고 `priceStatus="UNAVAILABLE"`로 구분한다. 이유: 종목 단위에서 원가·0손익으로 채우면 "이 종목은 손익이 정확히 0원"이라는 구체적이고 틀린 사실을 특정 종목에 대해 단정하게 되므로, 집계값 왜곡(계좌 요약)보다 더 나쁜 오정보가 된다. `quantity`·`averagePrice`는 원장 값이라 시세 무효 여부와 무관하게 항상 값이 채워진다. 한 종목의 시세 무효가 전체 목록 조회를 막지는 않는다(예외 없이 200).

정렬 기준: 응답 배열은 종목 심볼(`symbol`) 오름차순으로 고정된다(`HoldingRepository.findAllByAccountIdAndIsActiveTrue`의 `ORDER BY h.instrument.symbol`, PR #97 리뷰 권장사항 1).

### 전체 포트폴리오 합산 요약 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/portfolio | Access Bearer 필수 | 없음(쿼리·본문 모두 없음) | 200 `{"totalValue":20200000,"returnRate":0.0100,"unrealizedPnl":200000,"realizedPnl":50000}` (`PortfolioSummaryResponse`, 4개 필드 고정) | Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식만(`market` 관련 400 케이스 없음 — 이 API에 쿼리 파라미터가 없음) | 006 ACCT-003, Issue #51 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유의 `STOCK`·`CRYPTO` 계좌 전체를 항상 합산한다(`market` 토글 없음 — 시장 하나만 고르는 것이 아니라 두 시장을 합친 뷰이므로 `## account` 절의 `GET /api/accounts/summary?market=`와 달리 쿼리 파라미터가 존재하지 않는다). 한 시장만 보유 종목이 있거나 두 시장 모두 보유 종목이 없어도 예외 없이 200과 0을 포함한 합산 결과를 반환한다.

응답 4개 필드: `totalValue`(두 시장 `totalValue`의 합) · `returnRate`(`(totalValue − seedMoneyTotal) ÷ seedMoneyTotal`, scale 4 `RoundingMode.HALF_UP` — 시장별 수익률을 더하거나 평균 내지 않고 합산된 총평가액·시드머니 합계로 재계산한 값) · `unrealizedPnl`(두 시장 `unrealizedPnl`의 합, `## account`의 시세 무효 폴백 정책이 이미 반영된 값) · `realizedPnl`(두 시장 `realizedPnl`의 합 — 계좌 원장 값 그대로이며 체결 내역(`## order`의 `GET /api/trades`)을 재계산하지 않는다). `cashBalance`·`holdingsValue` 등 `AccountSummaryResponse`의 중간값과 시장별 breakdown은 포함하지 않는다 — 시장별 세부값이 필요하면 `GET /api/accounts/summary?market=`를 시장별로 호출한다.

---

## journal

### 투자일기 목록 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/journal | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용), `cursor`(선택, `{createdAt}_{tradeId}` 형식 문자열, 생략 시 첫 페이지), `limit`(선택, 기본 20, 1~100) | 200 `{"content":[{"journalType":"SELL","buyTradeId":null,"sellTradeId":34,"content":"목표가 도달해서 전량 매도.","createdAt":"2026-08-04T15:20:41","updatedAt":"2026-08-04T15:20:41"},{"journalType":"BUY","buyTradeId":12,"sellTradeId":null,"content":"실적 발표 전 분할 매수.","createdAt":"2026-08-04T10:12:33","updatedAt":"2026-08-05T09:03:12"}],"nextCursor":"2026-08-04T10:12:33_12","hasNext":true}` (`JournalListResponse`); 회고가 없으면 200 `{"content":[],"nextCursor":null,"hasNext":false}` | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`), `limit`이 1~100 범위 밖(클램핑 없음), `cursor`가 `{ISO_LOCAL_DATE_TIME}_{id}` 형식으로 파싱 실패(구분자 없음·날짜 파싱 실패·id 파싱 실패)는 모두 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 요청 시장의 계좌가 없으면 404 `NOT_FOUND` 공통 오류 형식 | 007 JOUR-006, Issue #203 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유의 해당 시장 계좌(`AccountService.getAccountFor`로 소유권+시장 스코프 검증)가 쓴 매수 회고(`buy_trade_journals`)와 매도 회고(`sell_trade_journals`)를 **한 목록에 섞어** 반환한다 — 두 종류를 따로 조회하는 엔드포인트는 없다. 정렬 기준은 **회고를 처음 쓴 시점(`createdAt`) 내림차순**이며 동시각은 체결 ID 내림차순으로 끊는다(`updatedAt` 기준 정렬 아님 — 방금 수정한 오래된 회고가 목록 맨 위로 튀지 않도록). 커서는 "이전 페이지 마지막 행보다 이 시각 이전이거나(동시각이면 이 체결 ID보다 작은)" 조건으로 다음 페이지를 이어받아 페이지 경계에서 중복·누락이 없다.

응답 항목 필드는 `journalType`(`"BUY"`\|`"SELL"`)·`buyTradeId`·`sellTradeId`·`content`·`createdAt`·`updatedAt` 6개로 고정이다. 매수 항목은 `sellTradeId`가, 매도 항목은 `buyTradeId`가 `null`이다. **통합 `journalId`는 노출하지 않는다** — 아래 "투자일기 상세 조회(매수·매도)" 절이 확정한 JOUR-005 식별자 체계(타입별 경로 분리)를 선점하지 않기 위해서다. 종목·가격·수량·실현손익 등 체결 정보는 포함하지 않는다(`GET /api/trades`가 정본). wrapper는 형제 API(`GET /api/trades`·`GET /api/orders`)와 같은 `content`·`nextCursor`·`hasNext` 3필드다.

### 투자일기 상세 조회(매수·매도)

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/journal/buy/{buyTradeId} | Access Bearer 필수 | 경로 변수 `buyTradeId`(숫자), 본문 없음 | 200 `{"journalId":1,"buyTradeId":12,"content":"실적 발표 전 분할 매수. 5% 빠지면 손절 계획.","createdAt":"2026-08-04T10:12:33","updatedAt":"2026-08-05T09:03:12"}` (`BuyJournalDetailResponse`, 5개 필드 고정) | `buyTradeId` 타입 불일치(숫자 파싱 실패)는 400 `VALIDATION_ERROR`. 대상 체결의 `side`가 `BUY`가 아님(매도 체결)도 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 타인 소유 체결은 403 `FORBIDDEN`. `buyTradeId`에 해당하는 체결 없음은 404 `NOT_FOUND`. 체결은 있으나 매수 회고가 아직 없으면 404 `NOT_FOUND` | 007 JOUR-005, Issue #217 |
| GET | /api/journal/sell/{sellTradeId} | Access Bearer 필수 | 경로 변수 `sellTradeId`(숫자), 본문 없음 | 200 `{"journalId":1,"sellTradeId":34,"content":"목표가 도달해서 전량 매도. 다음엔 분할 매도 시도.","createdAt":"2026-08-04T15:20:41","updatedAt":"2026-08-04T15:20:41"}` (`SellJournalDetailResponse`, 5개 필드 고정) | `sellTradeId` 타입 불일치는 400 `VALIDATION_ERROR`. 대상 체결의 `side`가 `SELL`이 아님(매수 체결)도 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 타인 소유 체결은 403 `FORBIDDEN`. `sellTradeId`에 해당하는 체결 없음은 404 `NOT_FOUND`. 체결은 있으나 매도 회고가 아직 없으면 404 `NOT_FOUND` | 007 JOUR-005, Issue #217 |

**경로를 타입별로 분리한다** — 단일 경로 `GET /api/journal/{journalId}`는 만들지 않는다(2026-08-05 이슈 #217 확정, JOUR-005 식별자 체계 Decision Gate 해제). `buy_trade_journals.id`·`sell_trade_journals.id`는 서로 다른 AUTO_INCREMENT 시퀀스라 **같은 값이 두 테이블에 겹칠 수 있고**, 숫자 하나만으로는 어느 테이블인지 정해지지 않는다. 경로 변수는 회고 자체의 PK가 아니라 **그 회고가 달린 체결 ID**(`buyTradeId`/`sellTradeId`)다 — 작성·수정 계약 4개와 목록 항목이 이미 쓰는 식별자를 그대로 재사용한다. `journalId`는 응답 본문에만 남는다.

**검증 순서는 수정 계약(JOUR-002·004)과 같다** — `체결 존재(404) → 소유(403) → 체결 구분(400) → 회고 존재(404)`. 타인 체결이면 그 체결의 매수·매도 속성과 회고 존재 여부를 흘리기 전에 403이 먼저다. 경로와 체결 구분이 어긋나면(매수 경로에 매도 체결 ID, 매도 경로에 매수 체결 ID) **404가 아니라 400**이다 — 리소스가 없는 게 아니라 클라이언트가 경로를 잘못 고른 것으로 취급한다. **이 엔드포인트에 409는 없다**(새 행을 만들지 않는다).

응답은 같은 회고의 수정 응답(`BuyJournalUpdateResponse`·`SellJournalUpdateResponse`)과 필드 구성이 1:1로 같은 5개 고정(`journalId`·`buyTradeId` 또는 `sellTradeId`·`content`·`createdAt`·`updatedAt`)이다. **두 응답의 `journalId`는 서로 다른 테이블의 시퀀스에서 채번되므로 값이 겹칠 수 있다** — 같은 `journalId`가 매수 응답과 매도 응답에 동시에 나타나도 서로 다른 회고를 가리킨다. 종목·가격·수량·실현손익 등 체결 정보는 포함하지 않는다(`GET /api/trades`가 정본). 조회는 읽기 전용이며(`@Transactional(readOnly = true)`), 신규 Flyway 마이그레이션 없이 기존 `findByBuyTradeId`·`findBySellTradeId`를 그대로 재사용한다 — 스키마 변경이 없다.

### 매수 체결 투자일기 작성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/trades/{buyTradeId}/journal | Access Bearer 필수 | 경로 변수 `buyTradeId`(숫자) + 본문 `{"content":"실적 발표 전 분할 매수. 5% 빠지면 손절 계획."}`(`BuyJournalCreateRequest`, `content`는 `@NotBlank` + `@Size(max=5000)`) | 201 `{"journalId":1,"buyTradeId":12,"content":"실적 발표 전 분할 매수. 5% 빠지면 손절 계획.","createdAt":"2026-08-04T10:12:33"}` (`BuyJournalResponse`, 4개 필드 고정) | `content` 누락·공백·5000자 초과, `buyTradeId` 타입 불일치(숫자 파싱 실패), 대상 체결의 `side`가 `BUY`가 아님(매도 체결)은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 타인 소유 체결은 403 `FORBIDDEN`. `buyTradeId`에 해당하는 체결 없음은 404 `NOT_FOUND`. 해당 매수 체결에 투자일기가 이미 존재(선제 조회 또는 유니크 위반)하면 409 `DUPLICATE_RESOURCE` 공통 오류 형식 | 007 JOUR-001, Issue #159 |

작성자는 요청 본문이 아니라 Access Token의 인증 사용자(`AuthenticatedUser#userId`)로 결정한다. 응답 필드는 `journalId`·`buyTradeId`·`content`·`createdAt` 4개로 고정이며, 종목·가격·수량 등 체결 정보는 포함하지 않는다(`GET /api/trades`가 이미 제공한다). 목표가·손절가·예상보유기간 등 구조화 필드는 이번 범위가 아니다. `Location` 헤더는 포함하지 않는다 — 단건 조회는 위 "투자일기 상세 조회(매수·매도)" 절이 담당하며 별도 URL로 가리킨다.

**본문 검증이 경로 검증보다 먼저 일어난다.** `@Valid`는 컨트롤러 메서드 진입 전에 평가되므로, 없는 체결 + 공백 본문 요청은 404가 아니라 **400**이다.

**검증 순서는 `존재(404) → 소유(403) → 매수 여부(400) → 중복(409)`으로 고정한다.** 타인의 매도 체결이면 403이 먼저다 — 소유하지 않은 체결의 속성(매수/매도)을 오류 코드로 흘리지 않기 위해서다.

`content`는 앞뒤 공백을 트림하지 않고 원문 그대로 저장한다(검증만 `@NotBlank`). 대상 매수 체결에 이미 투자일기가 있으면(`buy_trade_journals.buy_trade_id` `UNIQUE`) 애플리케이션 선제 조회로 대부분 409를 반환하고, 동시 요청 경합으로 유니크 제약을 직접 위반해도 같은 409 `DUPLICATE_RESOURCE`로 변환한다.

### 매도 체결 투자일기(매도 회고) 작성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/trades/{sellTradeId}/sell-journal | Access Bearer 필수 | 경로 변수 `sellTradeId`(숫자) + 본문 `{"content":"목표가 도달해서 전량 매도. 다음엔 분할 매도 시도."}`(`SellJournalCreateRequest`, `content`는 `@NotBlank` + `@Size(max=5000)`) | 201 `{"journalId":1,"sellTradeId":34,"content":"목표가 도달해서 전량 매도. 다음엔 분할 매도 시도.","createdAt":"2026-08-04T15:20:41"}` (`SellJournalResponse`, 4개 필드 고정) | `content` 누락·공백·5000자 초과, `sellTradeId` 타입 불일치(숫자 파싱 실패), 대상 체결의 `side`가 `SELL`이 아님(매수 체결)은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 타인 소유 체결은 403 `FORBIDDEN`. `sellTradeId`에 해당하는 체결 없음은 404 `NOT_FOUND`. 해당 매도 체결에 매도 회고가 이미 존재(선제 조회 또는 유니크 위반)하면 409 `DUPLICATE_RESOURCE` 공통 오류 형식 | 007 JOUR-003, Issue #183 |

매수 회고(위 절)와 유스케이스가 대칭이다 — 인증 사용자 결정, 201·`Location` 헤더 미포함, 본문 검증이 경로 검증보다 먼저인 점(없는 체결 + 공백 본문 = 400), 검증 순서(`존재(404) → 소유(403) → 매도 여부(400) → 중복(409)`, 타인의 매수 체결이면 403이 먼저), `content` 트림 없이 원문 저장, 선제 조회 + 유니크 위반(`sell_trade_journals.sell_trade_id` UNIQUE) 변환은 모두 동일하다. **차이는 경로(`/sell-journal`), 대상 체결 구분 검증(`side != SELL`), 응답의 체결 ID 필드명(`sellTradeId`) 세 가지뿐이다.** 매도 회고 응답에는 실현손익·배분된 매수 lot 등 매도 결과 정보를 포함하지 않는다(`GET /api/trades`가 이미 제공한다).

### 매도 체결 투자일기(매도 회고) 수정

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| PATCH | /api/trades/{sellTradeId}/sell-journal | Access Bearer 필수 | 경로 변수 `sellTradeId`(숫자) + 본문 `{"content":"돌아보니 목표가 도달 전에 일부 익절했어야 했다."}`(`SellJournalUpdateRequest`, `content`는 작성과 같은 `@NotBlank` + `@Size(max=5000)`) | 200 `{"journalId":1,"sellTradeId":34,"content":"돌아보니 목표가 도달 전에 일부 익절했어야 했다.","createdAt":"2026-08-04T15:20:41","updatedAt":"2026-08-05T09:03:12"}` (`SellJournalUpdateResponse`, 5개 필드 고정) | `content` 누락·공백·5000자 초과, `sellTradeId` 타입 불일치는 400 `VALIDATION_ERROR`. 대상 체결의 `side`가 `SELL`이 아님(매수 체결)도 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 타인 소유 체결은 403 `FORBIDDEN`. `sellTradeId`에 해당하는 체결 없음은 404 `NOT_FOUND`. 체결은 있으나 매도 회고가 아직 없으면(upsert 아님) 404 `NOT_FOUND` | 007 JOUR-004, Issue #190 |

작성(위 절)과 같은 리소스 경로를 PATCH로 재사용한다. **차이는 상태 200(생성이 아니므로 201 아님), 응답이 `updatedAt`을 더한 5필드(`SellJournalUpdateResponse`, 작성 응답 `SellJournalResponse`는 4필드 그대로 유지), 회고가 아직 없으면 404로 거부(새 회고를 만들지 않음, upsert 아님)라는 점, 검증 순서 마지막 단계가 `중복(409)`에서 `회고 존재(404)`로 바뀌어 **이 엔드포인트에는 409가 없다**는 점이다. 검증 순서는 `체결 존재(404) → 소유(403) → 매도 여부(400) → 회고 존재(404)`로 고정이며, 타인의 매수 체결이면 회고 존재 여부를 확인하기 전에 403이 먼저다. `sellTradeId`·`journalId`·`createdAt`은 요청으로 지정할 수 없고 응답에서도 원본 값 그대로다 — 수정은 본문 교체다. 수정 횟수 제한이나 잠금 조건은 없다(연속 수정 모두 허용, 마지막 본문만 남고 이력은 보관하지 않는다).

### 매수 체결 투자일기 수정

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| PATCH | /api/trades/{buyTradeId}/journal | Access Bearer 필수 | 경로 변수 `buyTradeId`(숫자) + 본문 `{"content":"돌아보니 실적 발표 전 매수 타이밍이 조금 일렀다."}`(`BuyJournalUpdateRequest`, `content`는 작성과 같은 `@NotBlank` + `@Size(max=5000)`) | 200 `{"journalId":1,"buyTradeId":12,"content":"돌아보니 실적 발표 전 매수 타이밍이 조금 일렀다.","createdAt":"2026-08-04T10:12:33","updatedAt":"2026-08-05T09:03:12"}` (`BuyJournalUpdateResponse`, 5개 필드 고정) | `content` 누락·공백·5000자 초과, `buyTradeId` 타입 불일치는 400 `VALIDATION_ERROR`. 대상 체결의 `side`가 `BUY`가 아님(매도 체결)도 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 타인 소유 체결은 403 `FORBIDDEN`. `buyTradeId`에 해당하는 체결 없음은 404 `NOT_FOUND`. 체결은 있으나 투자일기가 아직 없으면(upsert 아님) 404 `NOT_FOUND` | 007 JOUR-002, Issue #197 |

매도 회고 수정(위 절)과 유스케이스가 대칭이다 — 상태 200, 응답이 `updatedAt`을 더한 5필드(`BuyJournalUpdateResponse`, 작성 응답 `BuyJournalResponse`는 4필드 그대로 유지), 일기가 아직 없으면 404로 거부(upsert 아님), 이 엔드포인트에는 409가 없다는 점, `buyTradeId`·`journalId`·`createdAt`은 요청으로 지정할 수 없고 응답에서도 원본 값 그대로라는 점(수정은 본문 교체)이 모두 동일하다. **검증 순서는 `체결 존재(404) → 소유(403) → 매수 여부(400) → 회고 존재(404)`로 고정**이며, 타인의 매도 체결이면 일기 존재 여부를 확인하기 전에 403이 먼저다. **잠금 없음** — 해당 매수 체결의 일부 또는 전부가 이미 매도되어 `trade_allocations`에 배분이 생겼든, 전량 매도됐든 관계없이 항상 수정할 수 있다. 매도 배분·`holding_lots` 여부를 판정에 쓰지 않는다(2026-08-04 이슈 #197 확정 — `005-order-sell` spec.md가 규정했던 "첫 매도 배분 발생 시 잠금"은 이 결정으로 대체됐다). 수정 횟수 제한이나 수정 이력 보관도 없다(연속 수정 모두 허용, 마지막 본문만 남는다).

---

## ranking

### 전체 랭킹 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/rankings | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용), `limit`(선택, 기본 10, 상한 50 — 범위 밖이어도 오류 없이 클램핑) | 200 `{"market":"STOCK","content":[{"rank":1,"nickname":"투자왕","realizedPnl":500000},{"rank":1,"nickname":"차트요정","realizedPnl":500000},{"rank":3,"nickname":"존버맨","realizedPnl":120000}]}` (`RankingListResponse`); 매도 체결 이력이 있는 회원이 한 명도 없으면 200 `{"market":"STOCK","content":[]}` | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`)은 400 `VALIDATION_ERROR`. `limit`이 정수로 파싱 불가능한 값(예: `abc`)이면 값 범위와 무관하게 400 `VALIDATION_ERROR`(클램핑은 파싱된 정수에만 적용). Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 014 RANK-001, Issue #187 |

**`limit`은 이 API에서만 400이 아니라 클램핑된다 — `GET /api/trades`·`GET /api/orders`와 의도적으로 다른 정책이다.** `limit`이 생략되거나 0 이하면 컨트롤러가 거부하지 않고 그대로 `RankingService`로 전달되어 서비스가 10으로 클램핑하고, 51 이상이면 50으로 클램핑한다. `market`만 컨트롤러 검증(누락·미지원 리터럴 400) 대상이다.

**Redis 장애 시**: 쓰기 경로(랭킹 갱신)는 재시도 후 실패를 삼키지만, 조회 경로(`topN`/`countStrictlyGreater`)는 예외를 그대로 던져 500 `INTERNAL_ERROR`가 된다. 별도 장애 응답 정책은 아직 없다(PR #196 리뷰 참고).

조회 대상은 요청에서 받지 않고 `market` 쿼리로 지정한 시장 전체 회원 중 **매도 체결 이력이 한 번도 없는 회원은 제외**한다(실현손익이 정확히 0이어도 매도 이력이 있으면 포함). 정렬은 실현손익 내림차순이며, `nickname`은 마스킹 없이 전체 노출한다. `userId`는 응답에 포함하지 않는다 — 응답은 인증 사용자로 스코프되지 않는 전체 랭킹이다(다른 회원의 항목도 그대로 보인다).

**동점자는 공동 순위를 받고, 다음 순위는 동점자 수만큼 건너뛴다.** 위 예시처럼 공동 1위가 2명이면 다음 회원은 2위가 아니라 3위다(`rank = 해당 score보다 엄격히 큰 회원 수 + 1`). Redis ZSET 기본 순위 커맨드는 동점이어도 멤버 문자열 사전순으로 순차 배정해 이 규칙과 다르게 동작하므로, 애플리케이션 계층(`RankingService`)에서 별도로 보정한다.

응답 2단 구조: wrapper(`RankingListResponse`)에 `market`(요청한 시장, 항목마다 반복하지 않음) · `content`(항목 배열). 항목(`RankingListItemResponse`)은 `rank`·`nickname`·`realizedPnl` 3개 필드로 고정이다.

**score의 정본은 MySQL이다.** 실현손익 값은 매 조회마다 `trades`를 재집계하지 않고, 매도 체결로 `accounts.realized_pnl`이 갱신된 뒤 **커밋 이후(after-commit)**에만 Redis ZSET에 반영된 값을 그대로 읽는다 — 커밋 전 갱신·롤백 시 Redis 오염이 없다. Redis 갱신이 재시도 후에도 실패하면 로그만 남기고 매도 체결 자체(주문·체결·계좌 갱신)에는 영향이 없다.

### 내 랭킹 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/rankings/me | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용) | 200 `{"market":"STOCK","rank":3,"nickname":"존버맨","realizedPnl":120000}` (`MyRankingResponse`); 매도 체결 이력이 없으면 200 `{"market":"STOCK","rank":null,"nickname":"투자왕","realizedPnl":0}`(오류 아님) | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴(예: `FOREX`)은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 014 RANK-002, Issue #233 |

대상은 항상 인증 토큰의 본인이다 — 요청 파라미터로 다른 사용자의 accountId·userId를 지정하는 기능은 없다(구조적으로 타인 조회 불가, 별도 소유권 검증 로직 불필요). 상위 노출 구간(`GET /api/rankings`의 `limit`)에 들지 않아도 본인의 정확한 보정 순위를 반환한다 — 목록 노출 여부와 무관하게 항상 계산된다.

**순위 계산은 RANK-001과 동일한 ZSET 상태·보정 공식(`countStrictlyGreater(score) + 1`)을 재사용한다** — 별도의 새 보정 공식을 만들지 않는다. 매도 이력 유무 판정도 DB `accounts.realized_pnl`이 아니라 항상 Redis ZSET(`RankingStore.score`)을 기준으로 한다 — 두 엔드포인트가 서로 다른 순간의 데이터를 봐서 순위가 불일치하는 상황을 원천적으로 없앤다. **응답의 `realizedPnl`도 이 `score`를 그대로 노출한다** — DB `accounts.realized_pnl`을 별도로 재조회하지 않는다. `rank`와 `realizedPnl`을 서로 다른 저장소에서 읽으면(after-commit 반영 지연·Redis 재시도 소진 등으로 두 값이 순간적으로 어긋날 때) 한 응답 안에서 "이 손익, 이 순위"가 서로 대응하지 않게 되기 때문이다(PR #234 리뷰 반영). 매도 이력이 없어 `score`가 없으면 `realizedPnl`은 0이다(이 경우 DB 값도 항상 0이라 결과가 같다).

**매도 체결 이력이 없는 사용자는 `rank`만 `null`이다.** 전용 상태값·오류 코드를 새로 만들지 않는다 — `nickname`·`realizedPnl`(0 포함)은 매도 이력과 무관하게 항상 정상 값으로 채워진다("랭킹 목록 대상에서 제외됨"(RANK-001)과 "본인 조회 응답에 참고 정보가 없음"은 다른 개념이다). `nickname`은 RANK-001과 동일하게 마스킹 없이 노출한다.

---

## 016 투자 실습 (candidate 1·2·3 제공, 나머지 계획)

`docs/specs/016-investment-education-policy`의 신규 계약 10건이다. candidate 1 `POST /api/favorites`, candidate 2 `GET /api/favorites`, candidate 3 `DELETE /api/favorites/{instrumentId}`는 controller가 구현되어 제공 중이며, 나머지 7건은 아직 계획 상태이므로 블랙박스 QA의 실행 가능 API 근거로 사용하지 않는다. 각 후속 구현이 병합될 때 해당 계약을 실제 상태로 전환하고 `docs/api-routes.md`의 계획 행도 실제 라우트 목록으로 옮긴다. 모든 경로는 Access Bearer 인증과 공통 오류 body를 사용하며 JSON POST는 `Content-Type: application/json`이다.

수량은 양수 `DECIMAL(30,8)` 범위(정수부 최대 22자리·소수부 최대 8자리), 가격은 양수 `DECIMAL(18,8)` 범위(정수부 최대 10자리·소수부 최대 8자리)다. 초과 precision/scale은 반올림하지 않고 400 `VALIDATION_ERROR`로 거부한다. 모든 id는 양의 `Long`이다.

### 즐겨찾기 등록

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/favorites | `{"instrumentId":1}` (`FavoriteCreateRequest`) | 201 `{"favoriteId":1,"instrumentId":1,"market":"STOCK","symbol":"005930","name":"삼성전자","createdAt":"2026-08-03T10:00:00"}` (`FavoriteResponse`) | 400 `VALIDATION_ERROR`; 404 `NOT_FOUND`(종목); 409 `INSTRUMENT_NOT_TRADABLE`, `DUPLICATE_RESOURCE` | 016 candidate 1 |

같은 사용자의 `(userId, instrumentId)`는 유일하다. 중복 등록은 기존 값을 반환하지 않는다. `#193`(ADR-0012)부터 DB가 아닌 서버 힙 메모리(인스턴스 단위 `ConcurrentHashMap`)에 저장하며, `favoriteId`는 프로세스 기동마다 1부터 재채번된다. 서버 재시작 시 등록된 즐겨찾기는 모두 유실된다(재등록 필요).

### 즐겨찾기 목록 조회

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/favorites | 추가 입력 없음 | 200 `{"content":[FavoriteResponse...]}`; 없으면 빈 배열 | 인증 공통 오류 | 016 candidate 2 |

`createdAt DESC, favoriteId DESC` 순이며 페이지네이션과 write가 없다.

### 즐겨찾기 해제

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| DELETE | /api/favorites/{instrumentId} | 양의 `instrumentId` path | 204, 본문 없음 | 400 `VALIDATION_ERROR`; 404 `FAVORITE_NOT_FOUND` | 016 candidate 3 |

타인 소유 행은 존재를 숨겨 404로 처리하며 반복 삭제도 404다. 즐겨찾기가 서버 힙 메모리 저장이므로(위 등록 절 참고) 재시작 후에는 삭제 대상도 사라져 있어 항상 404다.

### 투자 실습 진행 조회 (계획)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/education/practice | 추가 입력 없음 | 200 `InvestmentPracticeResponse` | 인증 공통 오류 | 016 candidate 12 |

응답은 `tutorialKey="INVESTMENT_PRACTICE_V1"`, `status`(`NOT_STARTED|IN_PROGRESS|COMPLETED`), `currentStep`(진행 중 1~3, 완료 시 null), 1~3 순서의 `steps`, `completedAt`(완료 전 null)을 포함한다. 각 step은 `step`, `status`, `locked`, non-null `evidence`를 가진다. evidence는 favorite·intention·buyTrade·exitPlan·observation·reflection 각각의 id와 시각을 쌍으로 노출하며 아직 없는 값은 null이다. observation은 `evidenceType`(`CLOSER_TO_BOUNDARY|TIMED_REPETITION|FINAL_EVENT`)까지 삼쌍으로 null/non-null이다. 완료 전에는 qualifying observation이 있는 유효 chain을 우선해 `exitPlan.reservedAt ASC, exitPlan.id ASC` 첫 chain을 선택하고, 없으면 전체 유효 chain에서 같은 정렬의 첫 chain을 선택한다. 유효 chain도 없으면 `favorite.createdAt ASC, favorite.id ASC` 첫 favorite를 사용한다. 단계별 evidence와 observation은 선택한 한 chain 안에서만 구성한다. 조회는 write하지 않고, 최초 완료 기록 이후에는 evidence 삭제·종결에도 `COMPLETED`가 회귀하지 않는다.

### 투자 의도 기록

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/education/practice/intentions | Access Bearer 필수. `{"instrumentId":1,"quantity":10,"stopLoss":65000,"takeProfit":75000}` (`PracticeIntentionCreateRequest`). 네 필드 모두 필수·양수이며 `quantity`는 정수부 22자리/소수부 8자리 이하, `stopLoss`·`takeProfit`은 각각 정수부 10자리/소수부 8자리 이하 | 201 `{"intentionId":1,"instrumentId":1,"quantity":10,"stopLoss":65000,"takeProfit":75000,"createdAt":"2026-08-03T10:01:00"}` (`PracticeIntentionResponse`) | 400 `VALIDATION_ERROR`; Access 인증 실패는 401 `UNAUTHORIZED`; 404 `NOT_FOUND`(종목); 409 `PRACTICE_STEP_LOCKED`, `PRACTICE_ALREADY_COMPLETED` 공통 오류 형식 | 016 candidate 4, Issue #175 |

현재 존재하는 본인 favorite와 같은 종목만 허용한다. 서비스는 `(user_id, tutorial_key)` 유일 제약의 `practice_progresses`를 atomic insert-if-absent 한 뒤 진행 행과 favorite를 잠가 검증한다. 완료 상태면 저장 없이 409 `PRACTICE_ALREADY_COMPLETED`, favorite가 없으면 저장 없이 409 `PRACTICE_STEP_LOCKED`다. `#193`(ADR-0012)부터 `practice_intentions` 테이블은 DROP되어 있으며, 유효 요청마다 서버 힙 메모리(인스턴스 단위, 사용자별 리스트)에 새 레코드를 추가한다(중복 intention을 금지하는 유일 제약은 없음). 필드는 기존과 동일한 `intentionId`(프로세스 기동마다 1부터 재채번), `instrumentId`, `quantity`, `stopLoss`, `takeProfit`, `createdAt`이며, 서버 재시작 시 모두 유실된다. 이 API는 의도만 기록하며 실제 시장가 매수 체결은 기존 `POST /api/orders`의 별도 요청이다.

**후속 확장 계획(#199, 아직 미구현):** 기존 타입 생략+`stopLoss`·`takeProfit` 요청은 PRICE로 호환하면서 `exitPriceType=PRICE|PERCENT`를 추가한다. PERCENT는 퍼센트 단위(백분율 값, `5`=5%)의 `stopLossRate`·`takeProfitRate`만 받고 실제 시장가 BUY `entryPrice`를 기준으로 OCO 생성 시 scale 8 절대 가격선을 계산한다. intention은 ADR-0012대로 인메모리를 유지하고 내부 UUID instance key로 영속 exit plan과 숫자 ID 재사용을 구분한다. tagged union, rate 범위·반올림·저장 정책은 `docs/specs/019-exit-price-policy`가 정본이며, 구현 전까지 위 현재 요청·응답만 실제 호출 가능하다.

### 튜토리얼 합성 시세 조회

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/education/practice/synthetic-prices/{instrumentId} | Access Bearer 필수. 양의 `Long` path `instrumentId` | 200 `{"title":"삼성전자","tickSeconds":3,"prices":[69000,69200,...]}` (`SyntheticPriceSeriesResponse`, `prices` 100개) | 400 `VALIDATION_ERROR`(양수 아님); Access 인증 실패는 401 `UNAUTHORIZED`; 404 `NOT_FOUND`(종목 없음) | 016 candidate 4 후속(#193 tasks 항목4) |

`InstrumentService.getInstrumentEntity`로 종목 존재만 확인하고 `instrument.isTradable()`은 검증하지 않는다(비거래 종목도 순수 참고용 차트로 허용). `title`은 `Instrument.name`(예: `"삼성전자"`)이고 `tickSeconds`는 항상 3, `prices`는 100개(5분/3초, 시작가 포함) `BigDecimal` 정수 배열이다. 시작가는 `PriceQueryService.getPriceQuote`로 조회한 실제 현재가를 사용하고, 가격이 없으면(PRICE_UNAVAILABLE 등) 고정 fallback 상수(10,000)를 시작가로 쓴다. 각 틱은 이전 값 대비 -1%~+1% 균등분포로 변동하며 시작가의 50% 미만으로는 떨어지지 않게 clamp한다. 요청마다 새로 계산하며 어떤 저장소에도 남기지 않고, 비즈니스 락은 없다(`InstrumentService`/`PriceQueryService` 조회를 위한 읽기 전용 트랜잭션만 사용).

### OCO exit plan 생성 (계획)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/exit-plans | 필수 `Idempotency-Key: <UUID>`; body `{"intentionId":1,"buyTradeId":10,"instrumentId":1,"quantity":10}` (`ExitPlanCreateRequest`). 가격·rate는 잠근 intention 정본 사용 | 최초 201, 기존 plan 수렴 200 `ExitPlanResponse` | 400 `VALIDATION_ERROR`; 404 `NOT_FOUND`(요청 종목); 409 `PRACTICE_EVIDENCE_MISSING`, `EXIT_PLAN_INVALID_PRICE_RANGE`, `EXIT_PLAN_SESSION_CLOSED`, `PRICE_UNAVAILABLE`, `INSUFFICIENT_QTY`, `IDEMPOTENCY_CONFLICT` | 016 candidate 7, 019 |

`ExitPlanResponse`는 기존 식별자·수량·entry/baseline/status/시각과 `exitPriceType`, PERCENT에서만 non-null인 `stopLossRate`·`takeProfitRate`, 항상 non-null인 `stopLossPrice`·`takeProfitPrice`를 반환한다. `replaySessionId`는 코인만 null이며 PENDING이면 `closedAt`·`triggeredOrderId`가 null이다. 상태는 `PENDING|FILLED_TAKE_PROFIT|FILLED_STOP_LOSS|CANCELLED|CANCELLED_EXPIRED`다.

본인 favorite → 현재 process intention → FILLED 시장가 BUY trade → holding의 종목·소유권을 검증하고 `intention.quantity == buyTrade.quantity == request.quantity`, `availableQuantity >= quantity`, `0 < stopLossPrice < entryPrice < takeProfitPrice`를 요구한다. PRICE는 intention 가격을 복사하고 PERCENT는 실제 `entryPrice`로 계산하며 클라이언트가 OCO 생성 시 값을 덮어쓰지 못한다. 계산 가격이 `DECIMAL(18,8)`을 초과하거나 범위가 깨지면 409 `EXIT_PLAN_INVALID_PRICE_RANGE`로 plan·예약 없이 거부한다. 서버 유효 현재가를 baseline으로 저장하고 수량은 한 번만 예약한다. idempotency key와 내부 intention instance UUID는 lowercase canonical 문자열로 저장한다. key hit는 현재 intention보다 먼저 영속 mapping을 조회해 같은 hash면 재시작 후에도 과거 plan을 200으로 재현한다. key miss에서만 현재 intention instance를 해석하며 같은 instance·같은 fingerprint의 새 key는 기존 plan으로 200 수렴하고 충돌은 409다.

### OCO 예약 목록 조회 (계획)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/exit-plans?status= | 선택 query `status`; 생략 시 `PENDING`, 명시할 때도 현재는 `PENDING`만 허용 | 200 `{"content":[ExitPlanResponse...]}`; 없으면 빈 배열 | `PENDING` 외 값은 400 `VALIDATION_ERROR` | 016 candidate 8 |

`reservedAt DESC, exitPlanId DESC` 순이며 페이지네이션과 write가 없다.

### OCO 예약 취소 (계획)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| DELETE | /api/exit-plans/{exitPlanId} | 양의 `exitPlanId` path | 204, 본문 없음 | 400 `VALIDATION_ERROR`; 404 `EXIT_PLAN_NOT_FOUND`; 409 `EXIT_PLAN_NOT_PENDING` | 016 candidate 9 |

본인 PENDING plan만 취소한다. 두 조건을 함께 종결하고 예약 수량을 정확히 한 번 반환한다. 타인 plan은 404, 반복 요청과 가격 트리거·만료 경합 패자는 409다.

### 가격 관찰 기록 (계획)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/education/practice/observations | `{"exitPlanId":1}` (`PracticeObservationCreateRequest`) | 201 `{"observationId":1,"exitPlanId":1,"currentPrice":69000,"observedAt":"2026-08-03T10:05:00","closerToBoundary":true,"closerBoundary":"STOP_LOSS","evidenceType":"CLOSER_TO_BOUNDARY"}` (`PracticeObservationResponse`) | 400 `VALIDATION_ERROR`; 404 `EXIT_PLAN_NOT_FOUND`; 409 `EXIT_PLAN_NOT_PENDING`, `PRICE_UNAVAILABLE` | 016 candidate 13 |

본인 PENDING plan만 허용하며 가격·시각·유형은 클라이언트가 보내지 않는다. `closerBoundary`는 `STOP_LOSS|TAKE_PROFIT` 또는 null, `evidenceType`은 `CLOSER_TO_BOUNDARY|TIMED_REPETITION` 또는 아직 미충족이면 null이다. terminal `FINAL_EVENT`는 서버 종결 트랜잭션만 만든다.

### 투자 실습 복기 저장 (계획)

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/education/practice/reflections | `{"exitPlanId":1,"answer":"계획한 손절선에 가까워져 팔고 싶었지만 미리 정한 기준을 확인했다."}` (`PracticeReflectionCreateRequest`) | 최초 201 `{"reflectionId":1,"exitPlanId":1,"prompt":"지금 팔고 싶나요? 그렇다면 왜 그런가요? 계획한 손절·익절 라인과 비교해 적어보세요.","answer":"...","createdAt":"2026-08-03T10:10:00"}` (`PracticeReflectionResponse`) | 400 `VALIDATION_ERROR`; 404 `EXIT_PLAN_NOT_FOUND`; 409 `PRACTICE_EVIDENCE_MISSING`, `PRACTICE_ALREADY_COMPLETED` | 016 candidate 14 |

`answer`는 whitespace-only가 아닌 raw Java 문자열 길이 1~2000이며 trim 없이 `VARCHAR(2000) NOT NULL`에 원문을 저장한다. A(경계에 가까워짐), B(2분 이상 범위의 서버 관찰 3회), C(체결·주식 만료 final event) 중 하나와 전체 owner·instrument·수량 evidence를 재검증한다. 정답·점수·보상은 없다. 사용자·튜토리얼 최초 요청만 reflection·completion을 원자 저장하고 이후 요청은 답변을 추가 저장하지 않은 채 409다.

### 투자 실습 공통 인증·오류 및 DTO 규칙

- 인증 실패는 401 `UNAUTHORIZED`; 예상하지 못한 실패는 500 `INTERNAL_ERROR` 공통 body다.
- path/body로 직접 지정한 favorite·exit plan이 타인 소유이면 리소스별 404로 존재를 숨긴다. OCO·복기 내부 evidence chain 불일치는 409 `PRACTICE_EVIDENCE_MISSING`이다.
- 목록 `content`와 진행 조회 `steps`는 항상 non-null이다. 모든 응답 시각은 ISO-8601 `LocalDateTime` 형식이다.
- `POST /api/exit-plans`만 멱등 API다. 나머지 POST는 중복 규칙으로 보호하며 DELETE 성공 응답에는 body가 없다.

| DTO | 필드 순서와 타입 | nullable 규칙 |
|---|---|---|
| `FavoriteCreateRequest` | `Long instrumentId` | non-null |
| `FavoriteResponse` | `Long favoriteId`, `Long instrumentId`, `String market`, `String symbol`, `String name`, `LocalDateTime createdAt` | 모두 non-null; market은 `STOCK|CRYPTO` |
| `FavoriteListResponse` | `List<FavoriteResponse> content` | non-null, 빈 배열 허용 |
| `PracticeIntentionCreateRequest` | `Long instrumentId`, `BigDecimal quantity`, `BigDecimal stopLoss`, `BigDecimal takeProfit` | 모두 non-null |
| `PracticeIntentionResponse` | `Long intentionId`, `Long instrumentId`, `BigDecimal quantity`, `BigDecimal stopLoss`, `BigDecimal takeProfit`, `LocalDateTime createdAt` | 모두 non-null |
| `ExitPlanCreateRequest` (계획) | `Long intentionId`, `Long buyTradeId`, `Long instrumentId`, `BigDecimal quantity` | 모두 non-null; 가격·rate 입력 없음 |
| `ExitPlanResponse` (계획) | 기존 식별자·수량·entry/baseline/status/시각 + `String exitPriceType`, `BigDecimal stopLossRate`, `BigDecimal takeProfitRate`, `BigDecimal stopLossPrice`, `BigDecimal takeProfitPrice` | rate 둘은 PERCENT만 non-null; 확정 가격 둘은 항상 non-null; 기존 replay/terminal nullable 규칙 유지 |
| `ExitPlanListResponse` | `List<ExitPlanResponse> content` | non-null, 빈 배열 허용 |
| `PracticeObservationCreateRequest` | `Long exitPlanId` | non-null |
| `PracticeObservationResponse` | `Long observationId`, `Long exitPlanId`, `BigDecimal currentPrice`, `LocalDateTime observedAt`, `Boolean closerToBoundary`, `String closerBoundary`, `String evidenceType` | 앞의 다섯 필드는 non-null; 뒤의 두 필드는 조건 미충족 시 null |
| `PracticeReflectionCreateRequest` | `Long exitPlanId`, `String answer` | 모두 non-null; answer는 blank 불가, raw 길이 최대 2000 |
| `PracticeReflectionResponse` | `Long reflectionId`, `Long exitPlanId`, `String prompt`, `String answer`, `LocalDateTime createdAt` | 모두 non-null |
| `InvestmentPracticeResponse` | `String tutorialKey`, `String status`, `Integer currentStep`, `List<PracticeStepResponse> steps`, `LocalDateTime completedAt` | `currentStep`·`completedAt`은 완료 여부에 따라 null; 나머지는 non-null |
| `PracticeStepResponse` | `Integer step`, `String status`, `Boolean locked`, `PracticeEvidenceResponse evidence` | 모두 non-null; locked 단계도 빈 evidence 객체 반환 |
| `PracticeEvidenceResponse` | `Long favoriteId`, `LocalDateTime favoriteCreatedAt`, `Long intentionId`, `LocalDateTime intentionCreatedAt`, `Long buyTradeId`, `LocalDateTime buyTradeExecutedAt`, `Long exitPlanId`, `LocalDateTime exitPlanReservedAt`, `Long observationId`, `LocalDateTime observationObservedAt`, `String evidenceType`, `Long reflectionId`, `LocalDateTime reflectionCreatedAt` | 리소스 id·시각은 쌍으로 null/non-null; observation은 id·시각·type이 함께 null/non-null |

진행 상태의 모든 조합, OCO fingerprint canonical JSON과 동시성·잠금 정본은 `docs/specs/016-investment-education-policy/plan.md`를 따른다.

## 012 AI 피드백

`docs/specs/012-ai-feedback`의 계약 4건이다(변동 원인 카드·매도 직후 피드백·종목 뉴스 요약·개장 전 브리핑). 네 경로는 URL 접두사(`instruments`·`ai`·`market`)가 다르지만 소유 도메인은 `feedback` 하나다. spec 단위로 묶어 둔다. **네 절 모두 제목에 "(계획)" 표시가 없다 — controller가 전부 있으므로 네 절이 전부 블랙박스 QA 근거다** (매도 직후 피드백이 마지막이며 이슈 #208에서 걷었다). `docs/api-routes.md`도 같은 상태이며 계획 라우트로 남은 2차 경로는 없다. **`plan.md`의 남은 이슈 7·8번은 이 네 절에 필드·분기를 더하고 새 엔드포인트를 만들지 않는다** — 계획 절을 다시 세우지 않는다.

**아래 예시의 `publisher`가 뉴스에서 `hankyung.com`처럼 도메인인 것은 오타가 아니다.** 네이버 뉴스 검색 응답에 언론사 이름 필드가 없어(`title`·`originallink`·`link`·`description`·`pubDate`가 전부) `originallink` 호스트에서 `www.`만 뗀 값을 저장하며, 정본은 spec §C-8이다. 한글 언론사명 매핑은 후속 이슈로 분리했다(`docs/specs/012-ai-feedback/plan.md` §후속으로 낼 이슈). 공시(`type=DISCLOSURE`)의 `publisher`는 `DART` 고정이다.

### 종목 변동 원인 카드 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/instruments/{instrumentId}/price-moves | Access Bearer 필수 | 경로 변수 `instrumentId`만(쿼리·본문 없음) | 200 `{"originTradeDate":"2026-07-29","moves":[{"id":12,"eventType":"INTRADAY","windowStart":"2026-07-29T11:20:00","windowEnd":"2026-07-29T11:25:00","changeRate":-0.0182,"narrative":"11시 20분부터 5분간 1.82% 하락했습니다. 같은 시간대에 생산 차질을 다룬 기사가 있었습니다.","sources":[{"type":"NEWS","title":"...","publisher":"hankyung.com","url":"https://...","publishedAt":"2026-07-29T11:15:00"}]}]}` (`PriceMoveListResponse`); 카드가 없으면 200 `{"originTradeDate":"2026-07-29","moves":[]}` | Access 인증 실패는 401 `UNAUTHORIZED`. `instrumentId` 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 012 FEED-006 |

`originTradeDate`는 주식일 때 현재 재생세션의 원본 거래일이고, 코인은 실시간이므로 항상 `null`이다. **재생세션이 `READY`가 아니면 주식도 `originTradeDate=null`·`moves=[]`이며 200이다** (오류가 아니다). `eventType`은 `INTRADAY`(장중 변동) 또는 `OPENING_GAP`(시가 갭)이다.

**노출 필터**: 주식은 `(서비스 날짜 + revealTime) <= now()`인 카드만 반환한다 — 재생 방식이라 하루치 카드가 08:45 배치에서 이미 전부 생성돼 있으므로, 이 필터가 없으면 오후 사건이 오전에 노출되는 스포일러가 된다. `revealTime`은 응답에 포함하지 않는다(서버 내부 판정값). 코인은 `revealTime`이 `NULL`이고 게이트 없이 최근 24시간 카드를 반환한다.

`revealTime`은 **날짜가 아니라 `TIME`으로 저장**한다 — 같은 원본 거래일이 두 번 재생될 수 있어(수집이 하루 실패하면 스케줄러가 과거로 거슬러 올라간다) 절대 시각으로 두면 두 번째 재생일에 하루치가 09:00에 전부 열린다. **계산식은 spec §노출 판정이 정본이며 `eventType`에 따라 다르다** — 여기 옮겨 적지 않는다. 요지는 둘이다. 근거 기사가 카드보다 늦게 발행될 수 있으므로 그 시각까지 밀고, 전장 기사는 09:00으로 당긴다.

**카드 개수**: 주식은 원본 거래일당 `max-intraday-cards`건 + 시가 갭 1건이다 (§C-7). 시가 갭은 직전 거래일 종가가 `stock_candles`에 있고 갭이 임계치를 넘을 때만 생성되므로 없을 수 있다. 코인은 종목당 `daily-limit`건이다 (§C-7).

**`sources`가 빈 배열인 카드는 존재하지 않는다** — 근거가 없으면 카드 자체를 만들지 않는다(FEED-003). `narrative`는 LLM 생성 문장이거나 후검증에 걸려 대체된 템플릿 문장이며, 어느 쪽이든 항상 채워진다.

**정렬**: `moves`는 `windowStart` 오름차순, 각 카드의 `sources`는 `publishedAt` 내림차순이다. 화면이 카드를 시간축 순서 그대로 놓고 근거는 최신 기사를 위에 놓는 순서이며, 특히 근거는 연결 테이블에 순서 컬럼이 없어 정렬을 고정하지 않으면 실행마다 순서가 달라지므로 계약에 적는다. 같은 `publishedAt`을 가진 근거는 저장 순서로 가른다. **`windowStart`가 같은 카드는 `id` 오름차순으로 가른다** — 첫 분봉이 09:00인 날 시가 갭 카드와 장중 첫 카드의 `windowStart`가 정확히 같아지므로(유니크 키에 `eventType`이 들어가는 이유, §데이터 모델) 2차 키가 없으면 그 둘의 순서가 실행마다 달라진다. `id` 순서는 곧 생성 순서라 개장 전 배치의 단계 순서(시가 갭 → 장중, spec §C-6)를 그대로 따르며, 결과적으로 갭 카드가 먼저 온다. **코인은 `window_start` 컬럼이 항상 `NULL`이라(§C-9) 실제 정렬 기준은 `occurredAt`+`id` 오름차순이다** — 응답의 `windowStart = occurredAt - rollingWindowMinutes`가 `occurredAt`의 단조 변환이므로 결과 순서는 "`windowStart` 오름차순"과 동치다.

`windowStart`·`windowEnd`는 원본 거래일 날짜가 붙은 `LocalDateTime`(`"2026-07-29T11:20:00"`)이다 — **조회한 날짜가 아니라 `originTradeDate`의 날짜**다. 저장은 시각(`TIME`)뿐이고(§C-8) 날짜는 응답 조립에서 붙인다.

### 매도 직후 피드백 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/ai/post-sell/{tradeId} | Access Bearer 필수 | 경로 변수 `tradeId`만(쿼리·본문 없음) | 200 `{"tradeId":2,"instrumentId":1,"symbol":"005930","name":"삼성전자","buyAt":"2026-07-29T09:30:00","sellAt":"2026-07-29T14:40:00","buyPrice":70000,"sellPrice":68500,"quantity":10,"fee":102,"realizedPnl":-15207,"returnRate":-0.0217,"holdingMinutes":310,"sameSessionCompleted":true,"holdHighPrice":70800,"holdHighAt":"2026-07-29T11:05:00","holdLowPrice":68100,"holdLowAt":"2026-07-29T14:20:00","sellVsHighRate":-0.0325,"sellVsLowRate":0.0059,"buyToNewsMinutes":105,"priceMoves":[{"id":12,"windowStart":"2026-07-29T11:20:00","windowEnd":"2026-07-29T11:25:00","changeRate":-0.0182,"minutesAfterBuy":115,"minutesBeforeSell":195,"narrative":"...","sources":[...]}],"postSellFlow":{"status":"READY","closePrice":69200,"closeAt":"2026-07-29T15:29:00","sellToCloseRate":0.0102,"postSellHighPrice":69500,"postSellHighAt":"2026-07-29T15:05:00"},"counterfactuals":{"status":"READY","atClose":{"price":69200,"at":"2026-07-29T15:29:00","returnRate":-0.0117},"atHoldHigh":{"price":70800,"at":"2026-07-29T11:05:00","returnRate":0.0111},"atFirstMoveAfterBuy":{"price":69300,"at":"2026-07-29T11:25:00","returnRate":-0.0103}},"peerComparison":{"status":"READY","priceMoveId":12,"holderCount":47,"soldWithin30MinRate":0.38,"medianMinutesToSell":42,"yourMinutesToSell":195},"narrative":"09시 30분 매수는 이날 하락 구간(11시 20분)보다 1시간 55분 앞섰습니다. 하락 이후에도 3시간 넘게 보유하다 14시 40분에 68,500원에 매도했습니다. 보유 중 최고가는 11시 5분의 70,800원으로 하락이 시작되기 15분 전이었고, 매도가는 그보다 3.25% 낮습니다.","narrativeSource":"LLM","narrativeStatus":"READY"}` (`PostSellFeedbackResponse`) | Access 인증 실패는 401 `UNAUTHORIZED`. `tradeId` 미존재는 404 `NOT_FOUND`. 타인 체결은 403 `FORBIDDEN`. 매수 체결(`side=BUY`)과 **코인 체결(`market=CRYPTO`)**은 400 `VALIDATION_ERROR` 공통 오류 형식 | 012 FEED-007 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유 매도 체결로만 결정한다.

**2차에서는 주식 전용이다 (2026-08-04, 이슈 #136).** 이 응답의 게이트가 전부 "장 마감(15:30) 이후"와 "원본 거래일"에 묶여 있는데 24시간 거래인 코인에는 둘 다 없다. 코인 체결로 호출하면 400 `VALIDATION_ERROR`이며, 빈 값을 채운 200을 돌려주지 않는다. 코인 매도 회고는 3차로 미룬다.

**응답에 `buyAt`·`sellAt`(원본 거래일 기준 체결 시각)이 포함된다.** 화면이 반사실 표의 "실제 (14:40 매도)" 행과 서술의 시각을 그려야 하는데 `holdingMinutes`만으로는 복원할 수 없고, `narrative` 문자열에서 파싱할 수도 없다. **`buyAt`은 배분된 매수 lot 중 가장 이른 체결 시각이다** — 한 매도가 여러 lot에 배분되므로 단일하지 않고, 이 값이 `holdingMinutes`·`buyToNewsMinutes`·`minutesAfterBuy`·반사실의 기준을 전부 결정한다.

이 엔드포인트는 Notion api 명세서 §6의 `GET /ai/post-sell/{id}`(매도 직후 피드백, 계획 대비 실제 2단계)에 대응한다. 기존 명세의 **계획 대조**에 수익률 요약과 뉴스 기반 변동 원인을 합쳐 하나의 응답으로 제공한다. `/api/v1` → `/api` Base URL 규칙만 적용했고 경로 자체는 명세와 같다.

**투자일기에 의존하지 않는다.** Notion 명세 §6은 이 경로에 "계획 대비 실제 대조(2단계)"를 적어 뒀지만, 그 대조에 쓸 목표가·손절가 등 구조화 필드는 아직 없다. `007-journal`(다른 팀원 범위)은 JOUR-001(`POST /api/trades/{buyTradeId}/journal`, 자유 텍스트 `content` 작성)만 구현됐고, 구조화 필드(`plan`·`planOutcome`에 대응하는 목표가·손절가)는 Decision Gate 미해결로 아직 없다. 그 필드가 생기면 **같은 응답에 추가**하면 되고 아래 필드는 그대로 유지되므로 계약이 깨지지 않는다. 투자일기를 쓰지 않고 매수·매도한 건도 정상 200이다.

**위 예시는 실제 계산이 재현되는 값이다.** 수수료율(주식 0.015% / 코인 0.05%, `## order` 절)과 원 미만 내림을 적용하면 — 이 엔드포인트는 주식 전용이므로 0.015%다 — 매수수수료 `FLOOR(700,000×0.00015)=105`, 매수원가 합 700,105, 매도수수료 `FLOOR(685,000×0.00015)=102`, 실현손익 `685,000−102−700,105=−15,207`이다. 반사실 3종도 같은 식으로 시나리오 가격마다 수수료를 다시 계산한 값이다. **테스트 픽스처를 이 예시로 만들어도 된다** — 값이 안 맞으면 그건 예시가 아니라 구현이 틀린 것이다.

**수치는 전부 기존 원장에서 가져온다.** `buyPrice`는 `trade_allocations`의 FIFO 배분 가중평균 매수단가, `sellPrice`·`quantity`·`fee`·`realizedPnl`은 `trades` 행 그대로다. `returnRate = realizedPnl ÷ (배분된 매수원가 합 + 배분된 매수수수료 합)`이며 scale 4 `RoundingMode.HALF_UP`이다. **LLM은 이 수치를 계산하지도 수정하지도 않는다** (C-004).

**수치를 다시 읽어주는 것은 조회이지 피드백이 아니다.** 매수가·매도가·수익률은 사용자가 거래내역에서 이미 보는 값이다. 그래서 서버가 **사용자가 직접 계산하지 않은 관계**를 함께 내려준다 — 전부 시각 비교와 뺄셈이라 AI 판단이 들어가지 않는다.

| 필드 | 의미 |
|---|---|
| `holdHighPrice`·`holdHighAt`·`holdLowPrice`·`holdLowAt` | 보유 구간의 최고가·최저가와 그 시각 |
| `sellVsHighRate`·`sellVsLowRate` | 매도가가 그 극값에서 얼마나 떨어져 있었는지 |
| `buyToNewsMinutes` | 매수 시각과 첫 근거 기사 발행시각의 차(분). **양수면 매수가 기사보다 앞섰다는 뜻**, 음수면 기사가 나온 뒤 매수했다는 뜻. 근거 기사가 없으면 `null` |
| `priceMoves[].minutesAfterBuy`·`minutesBeforeSell` | 그 변동이 매수 몇 분 뒤였고 매도 몇 분 전이었는지 |
| `postSellFlow` | 매도 후 같은 거래일 종가까지의 흐름 (아래 참고) |

**`priceMoves`의 정렬**: `windowStart` 오름차순이고 **같은 `windowStart`는 `id` 오름차순으로 가른다** — 첫 분봉이 09:00인 날 시가 갭 카드와 장중 첫 카드의 `windowStart`가 정확히 같아지므로(위 변동 원인 카드 절과 같은 이유) 2차 키가 없으면 그 둘의 순서가 실행마다 달라진다. 각 카드의 `sources`는 **`publishedAt` 내림차순이고 동률은 연결 행 저장 순서(`price_move_event_sources.id` 오름차순)로 가른다.** **2차 키를 지우는 회귀는 테스트가 반드시 잡지 못한다** — InnoDB가 흔히 PK 순서로 돌려주어 우연히 일치한다. 그래서 보호를 파인더 이름(`...OrderByWindowStartAscIdAsc`)과 이 계약 문장 양쪽에 남긴다.

**`postSellFlow`는 장 마감 이후에만 채워진다.** 14:40에 매도하고 14:41에 조회하면 15:30까지의 가격은 아직 재생되지 않은 미래다. 그걸 보여주면 사용자가 같은 종목을 재매수할 때 답을 아는 상태가 된다. **게이트는 spec §C-5가 정본이다** — 여기 옮겨 적지 않는다. 요지는 기준 날짜가 "오늘"이 아니라 **그 체결의 서비스 날짜**라는 것이다. 오늘로 잡으면 어제 판 체결을 오늘 오전에 열었을 때 `READY`였던 값이 `NOT_YET`으로 되돌아간다. 게이트 전에는 `status="NOT_YET"`이고 가격 필드가 전부 `null`이다. 마감 후 첫 조회에서 `status="READY"`가 된다. **서술 재생성은 여기가 아니라 집단 비교까지 확정된 뒤다**(아래 참고).

**`counterfactuals` — 재생 서비스만 할 수 있는 기능이다.** 같은 수량을 다른 시점에 팔았다면 수익률이 얼마였을지를 세 시나리오로 보여준다(`atClose` 종가까지 보유, `atHoldHigh` 보유 중 최고가, `atFirstMoveAfterBuy` 매수 후 첫 변동 시점). 각 수익률은 **수수료를 다시 계산해** 산출한다 — 매도금액 비례라 가격이 바뀌면 수수료도 바뀐다.

**반사실은 AI 서술에 넣지 않는다.** `"안 팔았다면 -1.17%였습니다"`는 사실이지만 "팔지 말걸"을 암시하고, 문구 제약의 가정법 금지와 부딪힌다. 숫자를 구조화 필드로만 내려보내 화면이 표로 나란히 놓고 해석은 사용자가 한다. 실제 증권사는 오후 가격을 모르므로 이 기능을 그날 안에 제공할 수 없다 — 기존 명세에 `ai/d7`이 있는 이유다.

**`peerComparison` — 모든 회원이 같은 분봉을 보기 때문에 성립한다.** 같은 변동 구간을 겪은 다른 회원들이 어떻게 행동했는지를 익명 집계로 보여준다.

**기준 카드는 보유 구간 안의 첫 변동 카드 하나다** (`atFirstMoveAfterBuy`와 같은 카드). 어느 카드인지 알 수 있도록 응답에 `priceMoveId`를 함께 내려보낸다. **보유 구간에 카드가 0건이면 `status="NO_EVENT"`이고 `priceMoveId`를 포함한 모든 필드가 `null`이다** — 카드는 종목·거래일당 장중 `max-intraday-cards`건이고(spec §C-7) 근거 기사가 없으면 생성되지 않으므로, 카드 0건이 오히려 흔한 경우다. `holderCount`가 **5 미만이면 `status="INSUFFICIENT_SAMPLE"`**이고 모집단 지표 셋(`holderCount`·`soldWithin30MinRate`·`medianMinutesToSell`)이 `null`이 된다(기존 명세의 "유사 사례 5건 이상일 때만 노출" 관행). **`yourMinutesToSell`은 모집단 통계가 아니라 본인 값(`매도시각 − 카드 windowEnd`)이므로 이때도 채워진다.** 회원 ID·닉네임·개별 체결은 어떤 형태로도 포함하지 않는다. 집단 비교는 관측된 사실이므로 AI 서술에 포함해도 된다.

**`counterfactuals`도 같은 게이트를 쓴다** — 아직 재생되지 않은 가격을 쓰므로 미래 정보다. **`peerComparison`은 시각이 아니라 확정 집계 행의 존재로 판정한다**(§C-5). 장 마감 배치가 게이트 시각보다 늦게 돌기 때문에, 시각으로 두면 그 사이 조회가 게이트만 통과하고 값은 비는 상태가 된다. 그 전에는 각각 `status="NOT_YET"`이다.

결과적으로 **매도 직후와 장 마감 후에 보이는 내용이 다르다** — 직후에는 수치·파생 사실·뉴스 카드만, 마감 후에 반사실과 집단 비교가 더해진다. 스포일러 차단의 부수 효과이자 의도된 재방문 유도이므로, 화면은 `NOT_YET`일 때 "장 마감 후 다시 확인" 안내를 노출한다.

**`sameSessionCompleted`가 응답 형태를 가른다.** 매수와 매도가 같은 원본 거래일 안에서 완결됐으면 `true`이고 `holdHighPrice`·`holdHighAt`·`holdLowPrice`·`holdLowAt`·`sellVsHighRate`·`sellVsLowRate`·`buyToNewsMinutes`·`priceMoves`·`postSellFlow`·`counterfactuals`·`peerComparison`이 채워진다. 여러 재생일에 걸친 매매는 `false`이며 이 필드가 전부 `null`(`priceMoves`는 `[]`)이 된다 — 재생일마다 원본 거래일이 달라 분봉이 불연속이라 계산 자체가 성립하지 않는다. **한 매도가 여러 매수 lot에 배분됐고 그 lot들이 서로 다른 원본 거래일에 걸쳐 있어도 `false`다** — 가장 이른 lot 하나만 보고 판정하지 않는다.

**시각이 역전된 조합(`sellAt < buyAt`)도 `sameSessionCompleted=false`다** (2026-08-04 · 2026-08-05 개정, 이슈 #208). 같은 원본 거래일을 여러 서비스 날짜에 재생할 수 있어 **첫 재생일 오후에 매수하고 다음 재생일 오전에 매도하는 조합**이 성립하는데, 그때는 원본 거래일이 같으므로 날짜 대조만으로는 `false`가 되지 않는다. 보유 구간이 빈 구간이라 위 필드가 전부 비므로 **역전 자체를 `false` 조건으로 둔다** — 위 nullable 목록과 규칙은 그대로이고 그 목록에 들어오는 경우가 하나 늘어난 것이다.

**`holdingMinutes`는 그 nullable 목록에 없다 — 역전된 경우에만 `null`이다.** 즉 **역전이면 `sameSessionCompleted=false`이면서 `holdingMinutes`도 `null`이지만, `sameSessionCompleted=false`라고 `holdingMinutes`가 `null`이 되는 것은 아니다** — 원본 거래일이 순방향인 정상 cross-session 매매에서는 그대로 채운다. 음수를 0으로 clamp하거나 서비스 벽시계 경과분으로 대체하지 않는다(같은 응답의 `buyAt`·`sellAt`과 산술이 어긋난다). 규칙은 spec §파생 사실 계산이 정본이다.

**분 단위 값(`holdingMinutes`·`priceMoves[].minutesAfterBuy`·`minutesBeforeSell`·`buyToNewsMinutes`)은 두 끝점을 분으로 내린 뒤 뺀 값이다.** `executed_at`이 `DATETIME(6)`이라 체결 시각에 소수 초가 붙는데 분봉·카드 시각은 정시이므로, 분 축에서 재야 위 예시(`holdingMinutes: 310`, `minutesAfterBuy: 115`, `buyToNewsMinutes: 105`)가 재현된다. **`buyAt`·`sellAt` 자체는 체결 시각이므로 초를 그대로 싣는다.**

**`narrativeStatus`는 항상 `READY`다.** 매도 회고에는 §템플릿 문장이 있어 LLM이 실패하거나 후검증에 걸려도 서버가 수치로 조립한 문장으로 대체하므로, 서술이 비는 경우가 없다. 어느 쪽으로 만들어졌는지는 `narrativeSource`(`LLM`|`TEMPLATE`)로 구분한다. **`UNAVAILABLE`은 이 엔드포인트에 존재하지 않는다** — 템플릿이 없는 뉴스 요약·브리핑에만 있는 상태다.

서술은 최초 조회 시 생성해 `trade_feedbacks`에 저장하고 이후 재사용한다(`UNIQUE(trade_id)`). **예외는 하나다** — `postSellFlow`가 `READY`이고 `peerComparison`이 `NOT_YET`이 아닌 상태(`READY`·`INSUFFICIENT_SAMPLE`·`NO_EVENT` 모두 확정으로 친다)가 된 뒤 **첫 조회에서 재생성을 시도한다.** 매도 후 흐름과 집단 비교가 그때 채워지므로 그 내용을 반영해야 한다. **매도 후 흐름만 보고 재생성하면 안 된다** — 15:30~15:32에 조회한 사용자는 집단 비교가 빠진 문장으로 굳는다. **반사실은 반영하지 않는다** — 구조화 필드로만 나가야 하고, 서술에 넣으면 가정법 금지 후검증에 걸린다. 그 밖에는 매도 체결이 불변 원장이므로 재생성하지 않는다.

**재생성이 성공하면 1회로 끝나지만, 실패하면 다음 조회에서 다시 시도한다** (2026-08-05 정정 — 그전까지 이 소절이 "1회 재생성"으로만 적혀 실제 동작보다 좁았다. 코드는 처음부터 `spec.md` §C-7·FEED-007대로였다). 성공 시 `narrative_finalized`가 `TRUE`가 되어 게이트가 닫힌다. LLM 실패·후검증 위반으로 **템플릿으로 대체되면 실패로 치고 기존 서술을 유지한 채 `narrative_finalized`를 `FALSE`로 남기므로**, 게이트가 열려 있는 다음 조회에서 상한 이내면 또 시도한다 — 템플릿 문장에는 매도 후 흐름·집단 비교가 없어서 그것으로 덮으면 재생성할수록 서술이 빈약해지기 때문이다.

**재시도는 체결 1건당 누적 `max-narrative-retry`회까지이며**(`regeneration_attempts`, 값은 `spec.md` §C-7) **날짜 단위로 리셋하지 않는다** — 실패 시 `generated_at`을 갱신하지 않으므로 날짜 기준이 애초에 성립하지 않는다. 따라서 **LLM 장애가 길어져도 한 체결의 평생 LLM 호출은 최초 생성 1회 + 재생성 상한으로 묶여 있고 조회 수에 비례하지 않는다.** 상한에 닿으면 `narrative_finalized`가 `FALSE`로 남은 채 더 이상 호출하지 않으며, 응답은 그대로 200이고 `narrativeStatus`는 항상 `READY`다(템플릿이 있어 서술이 비지 않는다). 다만 **조회 경로의 LLM 호출량에는 현재 계측이 없다** — `LlmCallStats`는 배치 스코프 전용이며(#198, 의도된 동작) 후속 이슈로 `docs/specs/012-ai-feedback/plan.md` §후속으로 낼 이슈에 적혀 있다.

**문구 제약**: 인과 단정("~때문에"), 투자 권유("매수", "주목"), 가격 예측("오를 것"), 조언·후회 유도("~하세요", "~했으면 좋았을"), **판단·훈수("버티셨네요", "놓치셨", "더 기다렸다면")**를 쓰지 않는다. "하락 이후에도 3시간 보유했습니다"는 사실이고 "3시간이나 버티셨네요"는 판단이다 — 파생 사실이 늘어날수록 이 경계를 넘기 쉬워지므로 가정법 어미(`~다면`)까지 막는다. 서버가 LLM 출력을 후검증해 위반 시 템플릿 문장으로 대체한다 (C-004, FEED-003).

---

### 종목 뉴스 목록·요약 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/instruments/{instrumentId}/news | Access Bearer 필수 | 경로 변수 `instrumentId`만(쿼리·본문 없음) | 200 (주식, 09:00~15:30) `{"originTradeDate":"2026-07-29","summaryScope":"PRE_MARKET","summaryStatus":"READY","summary":"직전 거래일 장 마감 이후 반도체 업황을 다룬 기사가 있었습니다. 같은 구간에 유상증자 관련 공시가 1건 접수됐습니다.","items":[{"type":"NEWS","title":"...","publisher":"hankyung.com","url":"https://...","publishedAt":"2026-07-28T18:40:00"},{"type":"DISCLOSURE","title":"주요사항보고서(유상증자결정)","publisher":"DART","url":"https://dart.fss.or.kr/...","publishedAt":"2026-07-28T00:00:00"}]}` (`InstrumentNewsResponse`); 기사가 없으면 200 `{"originTradeDate":"2026-07-29","summaryScope":"PRE_MARKET","summary":null,"summaryStatus":"EMPTY","items":[]}`; 09:00 이전이면 200 `{"originTradeDate":"2026-07-29","summaryScope":null,"summary":null,"summaryStatus":"NOT_YET","items":[]}` | Access 인증 실패는 401 `UNAUTHORIZED`. `instrumentId` 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 012 FEED-008 |

Notion 1차 고도화 목록의 "뉴스 요약" 항목이다. 수집·저장은 변동 원인 카드(FEED-001)가 이미 하므로 이 엔드포인트는 **조회와 요약만** 추가한다.

**주식은 다른 원본 거래일의 기사를 섞지 않는다.** 재생 중인 거래일과 기사 날짜가 어긋나면 화면의 가격 방향과 기사 내용이 반대가 되므로, 오늘 기사를 섞지 않는다. 범위 하한은 요약과 같다 — spec §C-2가 정본이다. `originTradeDate`는 주식일 때 현재 재생세션의 원본 거래일이고 코인은 `null`이다.

**노출 필터**: 주식은 09:00 이전이거나 재생세션이 `READY`가 아니면 `summaryStatus="NOT_YET"`·`summaryScope=null`·`items=[]`이고(**재생세션 미준비일 때는 `originTradeDate`까지 `null`이다** — 어떤 거래일을 재생 중인지 자체가 확정되지 않은 상태라 날짜를 지어낼 수 없다. 개장 전은 날짜를 채운다. spec §C-4 판정 순서 1·2번), 개장 후에는 `publishedAt`이 현재 재생 시각을 지난 기사만 반환한다(전장 기사는 09:00으로 클램프). **`items`의 범위와 `summaryScope` 대응은 spec §C-2가 정본이다** — 여기 옮겨 적지 않는다. 요지는 "하한은 요약과 같고 상한만 재생 시각까지 넓다"이며, 요약이 목록보다 앞서지만 않으면 된다. 공시는 발행시각이 `00:00:00`뿐이라 날짜 조건으로 따로 판정하며 **원본 거래일 당일 접수분은 `FULL`에서만 나온다**(spec §C-3). **09:00 하한은 개장 전 브리핑과 동일하게 맞춘 것이다** — 두 API가 같은 전장 기사군을 다루므로 이쪽에 하한이 없으면 08:41에 조회해 브리핑이 감추는 기사를 먼저 볼 수 있다 — 변동 원인 카드의 `revealTime` 필터와 같은 목적이다. 15:00 기사를 09:30에 보여주면 앞으로 무슨 일이 일어날지 미리 알려주는 셈이 된다. 코인은 이 필터 없이 최근 24시간 기사를 반환한다.

**주식은 `summary`가 재생 진행에 따라 두 번 바뀐다.** 08:45 배치에서 범위가 다른 요약 두 개를 만들어 두고 조회 시각에 따라 골라 준다.

| 시장 | 조회 시각 | `summaryScope` | 요약 범위 |
|---|---|---|---|
| STOCK | 09:00 이전 | `null` | `summaryStatus="NOT_YET"`, `items=[]` |
| STOCK | 09:00 이상 15:30 미만 | `PRE_MARKET` | §C-2의 `전장` |
| STOCK | 15:30 이상 | `FULL` | §C-2의 `FULL` |
| CRYPTO | 언제나 | `ROLLING_24H` | 최근 24시간 |

15:30 이후 같은 종목을 다시 조회하면 `summaryScope`가 `FULL`로 바뀌고 요약도 하루 전체를 다룬 문장으로 교체된다 — `{"summaryScope":"FULL","summary":"반도체 업황 기사가 오전에 집중됐고, 오후에는 생산 차질을 다룬 보도가 이어졌습니다. …"}`. **이 문장이 09:00에 나가면 안 되는 것이 이 설계의 요지다.**

**코인 경로는 구현돼 있으며 주식과 함께 블랙박스 QA 근거다 (Issue #188).** 코인 종목으로 호출하면 재생세션과 무관하게 `originTradeDate=null`·`summaryScope="ROLLING_24H"`로 내려가고 **`summaryStatus`가 `NOT_YET`이 되지 않는다** — '개장 전'도 재생 거래일도 없어 spec §C-4 판정 순서의 1·2번이 성립하지 않고 3~6번만 쓴다. 저장된 행의 `origin_trade_date`에는 값이 있지만 그것은 유니크 축을 성립시키려고 채운 **배치 실행 날짜**이지 거래일이 아니라 응답에 싣지 않는다(spec §C-9). `items`는 **조회 시각 기준 최근 24시간**의 뉴스뿐이고 노출 게이트가 없다 — 코인은 공시가 없고, 실시간이라 스포일러가 성립하지 않는다. 요약은 **`generated_at` 최신 1행**을 본다("오늘 날짜 행"으로 찾으면 자정 직후와 배치 실패 시각마다 빈다). `items`의 24시간 창은 조회 시각 기준이고 요약은 마지막 배치 기준이라 **최대 65분 어긋나는데 허용된 동작이다** — 맞추려고 조회 시 생성으로 되돌아가지 않는다.

**코인은 범위가 하나뿐이다.** 24시간 거래라 '전장'도 '거래일 경계'도 없어 두 범위로 나눌 수 없다. 주식처럼 개장 전에 하루치를 만들어 둘 시점이 없으므로 **매시 05분 코인 배치가 갱신한다**(`feedback.batch.crypto-cron`). 저장은 새 행이 아니라 **같은 행의 갱신(UPSERT)**이며 종목당 하루 1행을 유지한다.

**조회 시 생성하지 않는다.** GET이 외부 LLM을 호출하고 DB에 쓰면 `docs/conventions.md`의 "GET은 부수효과 없음"을 어기고, 배치 갱신 직후 동시 요청이 전부 LLM을 호출하며, 그 순간의 첫 사용자가 최대 40초(20초 × 재생성 1회)를 기다린다. 배치로 옮기면 이 경로는 순수 조회가 되고 호출량도 사용자 수와 무관하게 고정된다. 코인은 실시간이라 미래를 모르므로 미리 만들어 두어도 스포일러 위험이 없다 — 주식이 사전 배치인 이유가 비용이 아니라 스포일러 차단이었던 것과 대비된다.

**하루 전체를 요약한 문장을 09:00에 주면 `items` 게이트가 무의미해진다.** `"오후에는 생산 차질을 다룬 보도가 이어졌습니다"` 한 문장이 그날 오후를 통째로 알려주기 때문이다. 기사 목록은 하나씩 풀리는데 요약만 전부 알고 있으면 앞뒤가 안 맞는다. 요약도 `items`와 같은 진행 속도를 따르게 맞춘 것이다.

요약은 회원별이 아니다 — `(종목, 원본 거래일, 범위)` 단위로 1건씩 생성해 전 회원이 공유한다.

**`items`의 정렬과 상한.** 정렬은 **발행시각 내림차순이고 동률은 `id` 내림차순**이다. 2차 키를 명시하는 이유는 공시의 발행시각이 전부 `00:00:00`이라 동률이 흔하기 때문이다 — 2차 키를 지우는 회귀는 테스트가 반드시 잡지 못한다(InnoDB가 흔히 PK 순서로 돌려주어 우연히 일치한다). 상한은 `feedback.news.max-items-per-news-list`이며 **요약 프롬프트에 넣는 기사 수 상한(`max-items-per-summary`)과는 다른 값이다** — 전자는 화면에 실리는 목록, 후자는 LLM 입력이다. 상한을 넘으면 **공시를 먼저 채우고 남은 자리를 뉴스 최신순으로 채운다**(spec §뉴스 매칭 범위). 공시가 없으면 잘리는 것이 아니라 구조상 **항상 먼저** 잘리기 때문이며, 그대로 두면 "`D-1` 접수 공시가 `PRE_MARKET` 요약·`items`에 나온다"는 이 절의 규칙과 상한 규칙이 서로 모순된다. **절단과 정렬은 별개다** — 살아남은 항목의 순서는 위 정렬 그대로이고 공시는 목록 아래쪽에 온다.

**`summaryStatus`**는 `READY` · `NOT_YET`(주식, 09:00 이전 또는 재생세션 미준비) · `EMPTY`(기사 없음, **또는 요약 행이 아직 없음** — 배치 미실행·배포 당일) · `UNAVAILABLE`(LLM 호출 실패 **또는 후검증 재생성 1회 후에도 금지 표현이 남음**)이다. **판정 순서는 spec §C-4의 표를 따른다.** 어느 값이든 상태코드는 200이며, **`EMPTY`가 행 없음 때문일 때와 `UNAVAILABLE`일 때는 `items`가 채워진다.**

**저작권**: `items`의 각 항목은 제목·언론사(뉴스는 원문 링크 도메인)·원문 URL·발행시각만 노출하고 본문은 어떤 형태로도 포함하지 않는다. `summary`는 여러 기사를 종합한 서술이며 특정 기사의 문장을 그대로 옮기지 않는다. 공시(`type=DISCLOSURE`)는 OpenDART가 접수일자만 제공하므로 `publishedAt`의 시각 부분이 항상 `00:00:00`이다. **그래서 구간이 아니라 날짜로 판정한다** — `PRE_MARKET`·브리핑에는 **직전 거래일 접수분만** 넣고 09:00부터 노출하며, **원본 거래일 접수분은 `FULL`에서만** 나온다. 구간으로 거르면 간밤 공시가 빠지고 장중 접수 공시가 아침에 들어온다(spec §C-3).

**문구 제약**은 변동 원인 카드와 같다 — 인과 단정·투자 권유·가격 예측을 쓰지 않고 서버가 후검증한다 (C-004, FEED-003).

### 개장 전 브리핑 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/market/briefing?market= | Access Bearer 필수 | `market`(필수, `STOCK`\|`CRYPTO` 리터럴만 허용) | 200 `{"market":"STOCK","originTradeDate":"2026-07-29","status":"READY","summary":"간밤 미국 증시에서 반도체 업종이 강세를 보였다는 보도가 있었습니다. 국내에서는 유상증자 결정 공시가 1건 접수됐습니다. 개장 전까지 확인된 소식은 아래와 같습니다.","items":[{"instrumentId":1,"symbol":"005930","name":"삼성전자","type":"NEWS","title":"...","publisher":"hankyung.com","url":"https://...","publishedAt":"2026-07-28T18:40:00"}]}` (`MarketBriefingResponse`); 09:00 이전이면 200 `{"market":"STOCK","originTradeDate":"2026-07-29","status":"NOT_YET","summary":null,"items":[]}`; 기사가 없거나 재생세션 미준비면 200 `{...,"status":"EMPTY","summary":null,"items":[]}` | `market` 누락 또는 `STOCK`\|`CRYPTO` 외 리터럴은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 012 FEED-009 |

**변동 원인 카드와 역할이 다르다.** 카드는 가격이 움직인 **뒤에** 원인을 설명하므로 매매 판단에 쓸 수 없다. 브리핑은 개장 시점에 그때까지의 정보를 주므로 **뉴스를 보고 매매하는 사용자의 진입점**이다.

**주식은 spec §C-2의 `전장` 구간 기사·공시만 담는다. 장중 기사는 어떤 경우에도 포함하지 않는다.** 재생 방식이라 그날 장중 뉴스를 아침에 노출하면 오후에 무엇이 일어날지 미리 알려주는 셈이 된다. 이 범위는 실제 투자자가 아침에 아는 정보와 동일하므로 교육적으로도 올바르다 — 그 시점에 가진 정보만으로 판단하는 연습이 된다. 장중 기사는 `GET /api/instruments/{id}/news`에서 재생 시각을 따라 하나씩 공개된다.

**`status`** 는 `READY` · `NOT_YET`(주식, 세션은 준비됐고 09:00 이전) · `EMPTY`(기사 없음, 재생세션 미준비, **또는 브리핑 행이 아직 없음**) · `UNAVAILABLE`(LLM 호출 실패 **또는 후검증 재생성 1회 후에도 금지 표현이 남음**)이다. **판정 순서는 spec §C-4의 표를 따른다** — 00:00~08:40처럼 두 조건이 동시에 성립하는 구간이 있다. 어느 값이든 상태코드는 200이며, **`EMPTY`가 행 없음 때문일 때와 `UNAVAILABLE`일 때는 `items`가 채워진다** — 요약이 없어도 기사 목록 자체는 쓸모가 있다.

**`items`의 정렬과 상한.** 정렬은 **발행시각 내림차순이고 동률은 `id` 내림차순**이며, 상한은 `feedback.news.max-items-per-briefing`이다 — **브리핑 프롬프트에 넣는 기사 수 상한(`max-items-per-summary`)과는 다른 값이다.** 상한을 넘으면 **공시를 먼저 채우고 남은 자리를 뉴스 최신순으로 채운다**(spec §뉴스 매칭 범위). **이 규칙이 가장 세게 걸리는 자리가 브리핑이다** — 전 종목 합산 단일 목록이라 종목당 2건만 쌓여도 상한을 넘고, 공시는 발행시각이 `00:00:00`이라 내림차순 목록의 최하위여서 규칙이 없으면 **상시 전멸한다.** 그러면 아래 "`D-1` 접수 공시는 개장 시각에 브리핑·전장 요약·`items` 셋 모두에 나온다"가 구조적으로 깨진다. **절단과 정렬은 별개다** — 살아남은 항목의 순서는 위 정렬 그대로이고 공시는 목록 아래쪽에 온다.

**코인 경로는 구현돼 있으며 주식과 함께 블랙박스 QA 근거다 (Issue #188).** `market=CRYPTO`는 허용 리터럴이라 400이 아니고, 재생세션과 무관하게 `originTradeDate=null`로 내려가며 **`status`가 `NOT_YET`이 되지 않는다** — '개장 전'도 재생 거래일도 없어 spec §C-4 판정 순서의 1·2번이 성립하지 않고 3~6번만 쓴다. `items`는 **조회 시각 기준 최근 24시간**의 코인 뉴스뿐이고 노출 게이트가 없다(코인은 공시가 없다). 브리핑은 **`generated_at` 최신 1행**을 본다 — 자정 직후와 배치 실패 시각마다 비지 않게 하려는 것이며, 저장 행의 `origin_trade_date`는 유니크 축을 성립시키려고 채운 **배치 실행 날짜**라 응답에 싣지 않는다(spec §C-9).

**코인은 '개장 전'이 없다.** 24시간 거래이므로 최근 24시간 기준으로 생성하며 `originTradeDate`는 `null`, `status`는 `NOT_YET`이 되지 않는다. **생성 주체도 다르다** — 주식은 08:45 개장 전 배치이고 코인은 **매시 05분 코인 배치**다(`feedback.batch.crypto-cron`, 종목 뉴스 요약의 코인 경로와 같다). 24시간 거래라 "개장 전에 미리 만들어 둘 시점"이 없기 때문이며, 조회 시 생성하지 않으므로 이 경로도 순수 조회다. 코인은 재생세션 상태와 무관하므로 `READY`가 아니어도 `EMPTY`가 되지 않는다.

**요약은 회원별이 아니다.** `(시장, 원본 거래일)` 단위로 1건만 생성해 전 회원이 공유하며(`UNIQUE(market, origin_trade_date)`), 주식은 08:45 배치에서 변동 원인 카드·뉴스 요약과 함께 만든다. 수집 파이프라인과 시가 갭 구간 데이터를 그대로 재사용하므로 LLM 호출은 하루 1회 추가에 그친다.

`items`는 종목 정보(`instrumentId`·`symbol`·`name`)를 포함해 화면이 추가 조회를 하지 않아도 되게 한다. 기사 본문은 어떤 형태로도 포함하지 않는다. 문구 제약은 변동 원인 카드와 같다 — 인과 단정·투자 권유·가격 예측을 쓰지 않고 서버가 후검증한다 (C-004, FEED-003).

---

## 023 관심목록 (watchlist)

`docs/specs/023-watchlist`의 신규 계약 3건이다. MySQL(`watchlist_items` 테이블)에 영속화하는 실제 서비스 기능이며, `docs/specs/016-investment-education-policy`의 인메모리 튜토리얼 즐겨찾기(`/api/favorites`, ADR-0012)와 완전히 별개다 — 오류 코드도 `WATCHLIST_ITEM_NOT_FOUND`로 분리해 `FAVORITE_NOT_FOUND`와 섞이지 않는다. 모든 경로는 Access Bearer 인증과 공통 오류 body를 사용하며 JSON POST는 `Content-Type: application/json`이다.

### 관심목록 등록

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/watchlist-items | `{"instrumentId":1}` (`WatchlistItemCreateRequest`) | 201 `{"watchlistItemId":1,"instrumentId":1,"market":"STOCK","symbol":"005930","name":"삼성전자","createdAt":"2026-08-06T10:00:00"}` (`WatchlistItemResponse`) | 400 `VALIDATION_ERROR`; 404 `NOT_FOUND`(종목); 409 `DUPLICATE_RESOURCE` | 023 WATCH-001 |

같은 사용자의 `(userId, instrumentId)`는 유일하다(`uk_watchlist_items_user_instrument`). 종목의 `tradable` 여부는 검사하지 않는다 — 존재 여부만 확인한다(즐겨찾기의 `INSTRUMENT_NOT_TRADABLE` 거부와 의도적으로 다름). 동시 등록 경합은 DB unique 제약이 최종 방어선이며 한쪽만 201, 다른 쪽은 409다.

### 관심목록 목록 조회

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/watchlist-items?market= | `market`(선택, `STOCK`\|`CRYPTO` 리터럴만 허용, 생략 시 전체) | 200 `{"content":[WatchlistItemResponse...]}`; 없으면 빈 배열 | `market`이 허용 리터럴 밖이면 400 `VALIDATION_ERROR`; 인증 공통 오류 | 023 WATCH-002 |

`createdAt DESC, watchlistItemId DESC` 순으로 본인이 등록한 항목만 반환한다. MySQL 영속화라 서버 재시작·다중 인스턴스에도 유실되지 않는다(즐겨찾기와 다른 점).

### 관심목록 해제

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| DELETE | /api/watchlist-items/{instrumentId} | 양의 `instrumentId` path | 204, 본문 없음 | 400 `VALIDATION_ERROR`; 404 `WATCHLIST_ITEM_NOT_FOUND` | 023 WATCH-003 |

존재하지 않거나 타인 소유인 항목은 동일하게 404로 처리해 소유 여부를 노출하지 않는다. 동시 삭제 경합은 한쪽만 204, 다른 쪽은 이미 삭제된 행이라 자연스럽게 404가 된다.
