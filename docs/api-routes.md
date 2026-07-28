# API 라우트 지도

전체 엔드포인트를 한눈에 보는 레지스트리. **controller를 추가/변경하면 같은 커밋에서 이 문서를 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

상세 명세(요청/응답 스키마)는 앱 실행 후 Swagger UI에서 확인한다: `http://localhost:8080/swagger-ui.html`

## 라우트 목록

| Method | URL | 도메인 | 요약 | Spec |
|---|---|---|---|---|
| POST | /api/auth/email-changes | auth | 인증 사용자의 새 이메일 변경 인증번호 발송 (202, 본문 없음). 재인증 증명·중복 이메일·발송 제한 검사 | 002 AUTH-005, Issue #55 |
| POST | /api/auth/email-changes/confirm | auth | 새 이메일 인증번호 확인 후 users.email 원자적 변경, 성공 시 기존 Refresh Token 전체 폐기 | 002 AUTH-005, Issue #56 |
| POST | /api/auth/email-verifications | auth | 인증번호 발송 (202, 본문 없음). 발송 제한·중복 이메일 검사 | 002 AUTH-004 |
| POST | /api/auth/login | auth | 이메일·비밀번호 검증 후 Access·Refresh 토큰 발급 | 002 AUTH-002 |
| POST | /api/auth/logout | auth | 본인의 Refresh Token을 폐기하고 로그아웃 (204, 본문 없음) | 002 AUTH-002 |
| GET | /api/auth/me | auth | 인증 사용자 본인의 id·email·nickname·가입 방식 조회 | 002 AUTH-005, Issue #8 |
| PATCH | /api/auth/me/nickname | auth | 재인증(현재 비밀번호 또는 reauthToken) 후 본인 닉네임 변경 | 002 AUTH-005, Issue #54 |
| POST | /api/auth/refresh | auth | 유효한 Refresh Token을 회전하고 새 Access·Refresh 토큰 발급 | 002 AUTH-002 |
| GET | /api/auth/oauth/{provider}/authorize | auth | 카카오·네이버 OAuth 인가 시작. `purpose` 생략/`login`은 공개 302, `purpose=reauth`는 인증 필요 200 JSON | 002 AUTH-003, Issue #53 |
| GET | /api/instruments?market= | market | 인증 사용자의 종목 목록 조회 (market 선택: STOCK·CRYPTO, 생략 시 전체) | 003 MKT-001, Issue #14 |
| GET | /api/instruments/{instrumentId} | market | 인증 사용자의 종목 단건 조회 | 003 MKT-001, Issue #15 |
| POST | /api/community/posts | community | 인증 사용자의 텍스트 게시물 작성 | 008 COM-001, Issue #23 |
| GET | /api/community/posts/{postId} | community | 인증 사용자의 커뮤니티 게시물 단건 조회 | 008 COM-001, Issue #25 |
| GET | /api/community/posts?page=&size= | community | 인증 사용자의 게시물 목록을 최신순 페이지네이션으로 조회 | 008 COM-001, Issue #24 |
| PATCH | /api/community/posts/{postId} | community | 본인 소유 커뮤니티 게시물의 제목·본문 수정 | 008 COM-001, Issue #26 |
| DELETE | /api/community/posts/{postId} | community | 본인 소유 커뮤니티 게시물 삭제 (204, 본문 없음, 댓글도 함께 삭제) | 008 COM-001, Issue #27 |
| GET | /api/community/posts/{postId}/comments | community | 인증 사용자가 게시물의 평면 댓글을 오래된 순으로 조회 | 008 COM-002, Issue #29 |
| POST | /api/community/posts/{postId}/comments | community | 인증 사용자의 평면 댓글 작성 | 008 COM-002, Issue #28 |
| DELETE | /api/community/comments/{commentId} | community | 본인 소유 댓글 삭제 (204, 본문 없음) | 008 COM-002, Issue #30 |

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

## OAuth 재인증 authorize

| Method | URL | 인증 | 입력 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/auth/oauth/{provider}/authorize?purpose=reauth | `Authorization: Bearer` 필수 | 경로 `provider`, query `purpose=reauth` | 200 `{"authorizationUri":"https://..."}` 및 purpose+userId를 서명해 담은 `oauth_state` 쿠키 | 401 `UNAUTHORIZED`(토큰 없음·만료·변조), 400 `VALIDATION_ERROR`(미지원 provider) 공통 오류 형식 | 002 AUTH-003, Issue #53 |

`purpose`를 생략하거나 `login`으로 보내면 기존 302 리다이렉트 계약이 그대로 적용된다(Issue #9). `purpose`가 그 외 값이면 Security 매처가 인증을 요구하고, 인증된 요청이라도 컨트롤러가 400 `VALIDATION_ERROR`로 거부한다. 재인증은 302 대신 200 JSON을 쓴다 — 호출자가 이미 로그인한 SPA이므로 이동 시점을 클라이언트가 정한다.

## OAuth callback

| Method | URL | 입력 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| GET | /api/auth/oauth/{provider}/callback | query `code`, `state`; cookie `oauth_state`. 인가 취소 시 query `error`, `state` | purpose=LOGIN state: 200 `{"accessToken":"<JWT>","refreshToken":"<JWT>","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}`. purpose=REAUTH state: 200 `{"reauthToken":"<원문, 1회>","expiresInSeconds":300}`. 두 경우 모두 정상 브라우저 callback 응답의 `oauth_state` 만료 쿠키 포함 | 400 `VALIDATION_ERROR`, `OAUTH_AUTHORIZATION_FAILED`, `OAUTH_EMAIL_REQUIRED`; 403 `REAUTHENTICATION_FAILED`; 409 `ACCOUNT_LINK_REQUIRED` 또는 동시성 충돌 시 `DUPLICATE_RESOURCE`; 500 `INTERNAL_ERROR`; 502 `OAUTH_PROVIDER_ERROR` 공통 오류 형식 | 002 AUTH-003, Issue #10, Issue #53 |

응답 종류는 요청 파라미터가 아니라 서명된 `state` 안의 purpose로 결정된다. 검증 순서는 provider 해석 → query/cookie state 일치(400 `VALIDATION_ERROR`) → state HMAC 서명 검증(실패 시 403 `REAUTHENTICATION_FAILED`) → 인가 취소·code 검사 → 공급자 사용자 조회 → purpose 분기다. 서명이 깨진 state는 purpose를 신뢰할 수 없으므로 `LOGIN`으로 위장한 경우도 403이다. REAUTH 분기는 회원·소셜계정·계좌·시드머니를 만들거나 바꾸지 않고 `reauth_tokens`에 해시 1행만 추가한다.

callback은 query `state`와 cookie `oauth_state`의 존재·일치를 검증하지만 서버가 발급 state를 Redis·DB·메모리에 저장하지는 않는다. 따라서 정상 브라우저는 callback 응답의 만료 쿠키를 적용하지만 raw cookie 재전송 자체를 서버 state 저장으로 차단한다고 보장하지 않는다. 실제 카카오·네이버는 authorization code의 단일 사용으로 같은 code 재전송을 거부한다. local·test Fake는 authorize마다 state에 결합된 고유 code를 발급하고 KAKAO/NAVER 전용 callback bean이 thread-safe store에서 `(provider, code, state)`를 원자적으로 한 번만 소비해 첫 요청만 허용한다. 잘못된 provider 요청은 grant를 소비하지 않고 400 `OAUTH_AUTHORIZATION_FAILED`로 거부하며, 원 provider의 첫 요청은 성공하고 이후 같은 grant 재사용은 400이다. `no-email`, `existing-email` 등 특수 fixture code는 오류 분기 테스트용으로 유지한다.

PR #49 차단 리뷰 후속 Fake 재사용·동시성·DB 불변 자동 회귀와 전체 build는 `PASS`했다. 실제 KAKAO/NAVER 스모크 `PASS`는 기존 별도 검증 기록이며, 이 후속 자동 검증에서 실제 공급자 스모크를 재실행하지 않았다.

## 이메일 회원가입

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/auth/signup | `{"email":"user@finplay.com","nickname":"finplayer","password":"password123","termsAgreed":true,"signupVerificationToken":"<가입 인증 토큰 원문>"}` | 201 `{"accessToken":"<JWT>","refreshToken":"<JWT>","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}` | 400 `VALIDATION_ERROR`, 409 `DUPLICATE_RESOURCE` 또는 `EMAIL_VERIFICATION_REQUIRED` 공통 오류 형식 | 002 Issue #4 |

## 로그인

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/auth/login | `{"email":"user@finplay.com","password":"password123"}` | 200 `{"accessToken":"<JWT>","refreshToken":"<JWT>","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}` | 400 `VALIDATION_ERROR`, 401 `UNAUTHORIZED` 공통 오류 형식 | 002 AUTH-002 |

회원 없음·소셜 전용 가입자·비밀번호 불일치는 원인과 무관하게 동일한 401 `UNAUTHORIZED`로 응답한다 (계정 열거 방지).

## Refresh Token 재발급

| Method | URL | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|
| POST | /api/auth/refresh | `{"refreshToken":"<Refresh JWT 원문>"}` | 200 `{"accessToken":"<JWT>","refreshToken":"<JWT>","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}` | 누락·빈 값·4,096자 초과는 400 `VALIDATION_ERROR`. 1~4,096자의 비어 있지 않은 변조·만료·폐기·미존재·재사용 토큰은 401 `UNAUTHORIZED` 공통 오류 형식 | 002 AUTH-002, Issue #6 |

재발급에 성공하면 제출한 이전 Refresh Token은 즉시 폐기되고 새 Refresh Token의 SHA-256 해시만 저장된다. 같은 이전 토큰의 재사용과 동시 회전 요청 중 경합에서 진 요청은 401 `UNAUTHORIZED`다.

## 로그아웃

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/auth/logout | Access Bearer 필수 | `{"refreshToken":"<Refresh JWT 원문>"}` (`RefreshRequest`) | 204 (본문 없음) | 누락·빈 값·4,096자 초과는 400 `VALIDATION_ERROR`. Access 인증 실패 또는 변조·만료·폐기·미존재 Refresh Token은 401 `UNAUTHORIZED`. 다른 사용자의 활성 Refresh Token은 403 `FORBIDDEN` 공통 오류 형식 | 002 AUTH-002, Issue #7 |

로그아웃은 제출한 본인의 활성 Refresh Token 한 건만 폐기한다. 폐기된 토큰으로 이후 `/api/auth/refresh`를 호출하면 401 `UNAUTHORIZED`이며, 다른 사용자의 활성 Refresh Token을 제출하면 403 `FORBIDDEN`이고 해당 토큰은 폐기되지 않는다.

## 내 정보 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/auth/me | Access Bearer 필수 | 본문·경로 변수·쿼리 없음 | 200 `{"id":42,"email":"user@finplay.com","nickname":"finplayer","signupMethod":"EMAIL"}` | Access 인증 실패(헤더 없음·만료·변조·Refresh Token 제출)는 401 `UNAUTHORIZED` 공통 오류 형식 | 002 AUTH-005, Issue #8 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자로만 결정한다. 경로·쿼리에 대상 식별자가 없어 다른 회원 정보는 조회할 수 없다.

`signupMethod`는 `EMAIL`·`KAKAO`·`NAVER` 중 하나이며 별도 컬럼이 아니라 해당 회원의 `social_accounts` 행 존재 여부와 `provider`로 조회 시점에 판별한다(행이 없으면 `EMAIL`). 응답 필드는 위 4개로 고정이며 `passwordHash`·`role`·`status`·OAuth `providerUserId` 등은 어떤 이름으로도 포함하지 않는다.

유효한 Access Token의 주체가 DB에 없는 경우도 401 `UNAUTHORIZED`로 응답한다 (계정 존재 여부 미노출).

## 닉네임 변경

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| PATCH | /api/auth/me/nickname | Access Bearer 필수 | 이메일 회원 `{"nickname":"새닉네임","currentPassword":"현재비밀번호"}`, OAuth 전용 회원 `{"nickname":"새닉네임","reauthToken":"<원문 토큰>"}` (`nickname`만 필수, 최대 50자. `currentPassword` 최대 100자, `reauthToken` 최대 255자) | 200 `{"id":42,"email":"user@finplay.com","nickname":"새닉네임","signupMethod":"EMAIL"}` (`MemberResponse`) | `nickname` 누락·공백·50자 초과, `currentPassword`·`reauthToken` 최대 길이 초과, 가입 방식에 필요한 재인증 필드 누락은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 비밀번호 불일치, `reauthToken` 만료·이미 소비·타인 소유는 403 `REAUTHENTICATION_FAILED`. 다른 회원이 쓰는 닉네임은 409 `DUPLICATE_RESOURCE` 공통 오류 형식 | 002 AUTH-005, Issue #54 |

대상 회원은 요청에서 받지 않고 Access Token의 인증 사용자로만 결정한다. `currentPassword`와 `reauthToken` 중 무엇이 필수인지는 클라이언트가 채운 필드가 아니라 서버가 조회한 실제 가입 방식(`social_accounts` 행 존재 여부)으로 판별한다. 성공 응답은 `/api/auth/me`와 같은 4개 필드로 고정이며 `currentPassword`·`reauthToken`·`passwordHash`는 어떤 이름으로도 포함하지 않는다.

## 새 이메일 변경 인증번호 발송

| Method | URL | 인증 | 입력 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/auth/email-changes | Access Bearer 필수 | `{"newEmail":"new@finplay.com","currentPassword":"...","reauthToken":"..."}` (`EmailChangeRequest`, 회원 유형에 따라 `currentPassword`/`reauthToken` 중 하나만 사용) | 202 (본문 없음) | 인증 헤더 없음·만료·변조 401 `UNAUTHORIZED`; `newEmail` 형식·길이 위반 400 `VALIDATION_ERROR`; 잘못된 현재 비밀번호·만료/재사용/타인 소유 `reauthToken` 403 `REAUTHENTICATION_FAILED`; 다른 회원이 사용 중인 새 이메일 409 `DUPLICATE_RESOURCE`; 60초 재발송·1시간 5회·하루 10회 초과 429 `TOO_MANY_REQUESTS` 공통 오류 형식 | 002 AUTH-005, Issue #55 |

EMAIL 회원은 현재 비밀번호, OAuth 전용 회원은 Issue #53에서 발급된 5분 유효·일회용 `reauthToken`으로 재인증하며, 재인증 실패 사유는 구분하지 않고 403 `REAUTHENTICATION_FAILED`로 통일한다. `reauthToken`은 이 발송 단계에서 소비된다. 확인 전까지 `users.email`은 변경되지 않으며, 인증번호 확인·실제 이메일 변경·Refresh Token 폐기는 후속 이슈다.

## 커뮤니티 게시물 작성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/community/posts | Access Bearer 필수 | `{"title":"게시물 제목","content":"게시물 본문"}` (`title` 최대 100자, `content` 최대 5,000자) | 201 `{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00"}` | 제목·본문 누락·빈 값·공백·최대 길이 초과는 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 008 COM-001, Issue #23 |

작성자는 요청에서 받지 않고 Access Token의 인증 사용자로 결정한다.

## 커뮤니티 게시물 단건 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/community/posts/{postId} | Access Bearer 필수 | 경로 변수 `postId` | 200 `{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00"}` | Access 인증 실패는 401 `UNAUTHORIZED`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-001, Issue #25 |

## 커뮤니티 게시물 목록 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/community/posts | Access Bearer 필수 | `page`(기본 0, 0 이상), `size`(기본 10, 1~50) | 200 `{"content":[{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00"}],"page":0,"size":10,"totalElements":1,"totalPages":1,"hasNext":false}` | `page`·`size` 범위 밖은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 008 COM-001, Issue #24 |

작성시각(`created_at`) 내림차순으로 정렬하며 동시각 항목은 `id` 내림차순으로 안정 정렬해 페이지 경계 중복·누락을 방지한다. 게시물이 없으면 오류가 아니라 200과 빈 `content`를 반환한다.

## 커뮤니티 게시물 수정

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| PATCH | /api/community/posts/{postId} | Access Bearer 필수 | 경로 변수 `postId`, 본문 `{"title":"게시물 제목","content":"게시물 본문"}` (`title` 최대 100자, `content` 최대 5,000자) | 200 `{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00"}` | 제목·본문 누락·빈 값·공백·최대 길이 초과는 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 본인 소유가 아닌 게시물은 403 `FORBIDDEN`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-001, Issue #26 |

작성자 본인만 수정할 수 있으며 소유자 확인은 요청 본문이 아닌 Access Token의 인증 사용자로 판단한다.

## 커뮤니티 게시물 삭제

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| DELETE | /api/community/posts/{postId} | Access Bearer 필수 | 경로 변수 `postId`, 본문 없음 | 204 (본문 없음) | Access 인증 실패는 401 `UNAUTHORIZED`. 본인 소유가 아닌 게시물은 403 `FORBIDDEN`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-001, Issue #27 |

작성자 본인만 삭제할 수 있으며 소유자 확인은 Access Token의 인증 사용자로 판단한다. 삭제 시 해당 게시물에 달린 댓글을 먼저 모두 삭제한 뒤 게시물을 삭제한다.

## 커뮤니티 게시물 댓글 목록 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/community/posts/{postId}/comments | Access Bearer 필수 | 경로 변수 `postId` | 200 `[{"commentId":1,"authorNickname":"finplayer","content":"댓글 본문","createdAt":"2026-07-27T12:00:00"}]`; 댓글이 없으면 `[]` | Access 인증 실패는 401 `UNAUTHORIZED`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-002, Issue #29 |

대상 게시물의 댓글만 `createdAt` 오름차순으로 반환하며, 생성시각이 같으면 `commentId` 오름차순으로 안정 정렬한다. 작성자는 fetch join으로 함께 조회해 댓글 수에 따른 추가 쿼리를 방지한다.

## 커뮤니티 게시물 댓글 작성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/community/posts/{postId}/comments | Access Bearer 필수 | `{"content":"댓글 본문"}` (`content` 필수, 최대 1,000자) | 201 `{"commentId":1,"authorNickname":"finplayer","content":"댓글 본문","createdAt":"2026-07-27T12:00:00"}` | 본문 누락·공백·1,000자 초과는 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-002, Issue #28 |

작성자는 요청에서 받지 않고 Access Token의 인증 사용자로 결정한다. 댓글은 부모 댓글 없이 게시글 바로 아래에 생성되는 평면 구조다.

| Method | URL | 인증 | 응답 | 오류 | Spec |
|---|---|---|---|---|---|
| DELETE | /api/community/comments/{commentId} | Access Bearer 필수 | 204 본문 없음 | 작성자 불일치는 403 `FORBIDDEN`. 댓글 미존재는 404 `NOT_FOUND`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 008 COM-002, Issue #30 |

댓글 삭제는 소유자만 가능하며 `CommentController`(`/api/community/comments`)로 분리되어 있다. Security 공개 화이트리스트에 포함되지 않은 인증 필요 경로다.

## 종목 목록 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/instruments?market= | Access Bearer 필수 | `market`(선택, `STOCK`·`CRYPTO`만 허용, 생략·빈 값 시 전체) | 200 `[{"instrumentId":1,"market":"STOCK","symbol":"005930","name":"삼성전자","tickSize":100,"minOrderAmount":70000,"tradable":true}, ...]` | `market`이 `STOCK`·`CRYPTO` 외 값이면 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 003 MKT-001, Issue #14 |

`market` 생략·빈 값 시 시드된 주식 16종·코인 12종 전체를 `id` 오름차순(주식 먼저, 코인 나중)으로 반환한다.

## 종목 단건 조회

| Method | URL | 인증 | 경로 변수 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/instruments/{instrumentId} | Access Bearer 필수 | `instrumentId` | 200 `{"instrumentId":1,"market":"STOCK","symbol":"005930","name":"삼성전자","tickSize":100,"minOrderAmount":70000,"tradable":true}` (`InstrumentResponse`, 목록 API와 동일 DTO) | 존재하지 않는 `instrumentId`는 404 `NOT_FOUND`. 숫자가 아닌 `instrumentId`는 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 003 MKT-001, Issue #15 |

## 인증 규칙

Spring Security는 세션을 만들지 않는 Bearer 인증을 사용한다. 현재 `SecurityConfig`의 공개 경로는 다음과 같고, 여기에 없는 모든 요청은 기본적으로 인증이 필요하다.

| 구분 | Method | 경로 |
|---|---|---|
| 공개 | POST | `/api/auth/signup` |
| 공개 | POST | `/api/auth/login` |
| 공개 | POST | `/api/auth/refresh` |
| 공개 | POST | `/api/auth/email-verifications` |
| 공개 | POST | `/api/auth/email-verifications/confirm` |
| 조건부 공개 | GET | `/api/auth/oauth/*/authorize` — `purpose`가 없거나 공백이거나 대소문자 무관 `login`일 때만 공개. 그 밖의 값(`reauth` 포함)은 `anyRequest().authenticated()`로 떨어져 인증 필요 |
| 공개 | GET | `/api/auth/oauth/*/callback` |
| 공개 | GET | `/actuator/health` |
| 공개 | GET | `/swagger-ui.html`, `/swagger-ui/**` |
| 공개 | GET | `/v3/api-docs/**` |
| 보호 | POST | `/api/auth/logout` |
| 보호 | GET | `/api/auth/me` |
| 보호 | PATCH | `/api/auth/me/nickname` |
| 보호 | POST | `/api/auth/email-changes` |
| 보호 | POST | `/api/auth/email-changes/confirm` |
| 보호 | 모든 Method | 위 공개 목록을 제외한 모든 경로 (`anyRequest().authenticated()`) |

- 보호 경로는 `Authorization: Bearer <accessToken>`을 요구한다. 헤더가 없거나 Access Token이 만료·변조됐거나 Refresh Token이면 401 `UNAUTHORIZED`다.
- 인증된 사용자가 권한 없는 리소스에 접근하면 403 `FORBIDDEN`이다.
- 401·403은 모두 `{"error":{"code":"<UNAUTHORIZED|FORBIDDEN>","message":"<기본 메시지>","requestId":"<요청 ID>"}}` 공통 형식이며 `X-Request-Id` 응답 헤더를 포함한다.
- `/api/auth/me`는 `SecurityConfig` 공개 목록에 추가하지 않고 기본 보호(`anyRequest().authenticated()`)만으로 인증을 요구한다.
- authorize의 조건부 공개는 `AndRequestMatcher`(경로+메서드 매처 + `purpose` 파라미터 람다)로 구현한다. 모르는 `purpose` 값은 공개 대상이 아니므로 기본적으로 인증을 요구한다(안전한 기본값). Spring Security 7에는 `AntPathRequestMatcher`가 없어 `PathPatternRequestMatcher`를 쓴다.
