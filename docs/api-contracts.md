# API 계약 상세

엔드포인트별 요청·응답·오류 계약이다. 전체 라우트를 한눈에 보는 지도는 `docs/api-routes.md`에 있다.

**controller를 추가/변경하면 `docs/api-routes.md`의 라우트 목록과 이 문서의 해당 절을 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

블랙박스 QA는 구현 코드(`src/main`)를 읽지 않고 이 문서와 spec만을 계약 근거로 사용한다 (`docs/context-router.md`).

순서는 도메인 기준이다 — auth → market → community → order.

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

### 주식 1분봉 조회 (캔들)

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/instruments/{instrumentId}/candles | Access Bearer 필수 | `interval`(필수, 1차는 `1m`만), `from`·`to`(선택, ISO-8601 `LocalDateTime`, 예: `2026-07-27T09:00:00`) | 200 `[{"sourceTime":"2026-07-22T09:00:00","open":71000,"high":71500,"low":70900,"close":71200,"volume":12345}, ...]` (`CandleResponse[]`, 시각 오름차순). 아직 공개된 분봉이 없거나 재생세션이 준비되지 않은 경우에도 예외 없이 200 `[]` | `interval`이 `1m`이 아니면 400 `VALIDATION_ERROR`. `from > to`면 400 `VALIDATION_ERROR`. 코인 `instrumentId`면 400 `VALIDATION_ERROR`("주식 종목만 캔들 조회를 지원합니다"). 존재하지 않는 `instrumentId`는 404 `NOT_FOUND`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 003 MKT-002, Issue #17 |

- 검증 순서는 `CandleQueryService.getCandles`가 `interval` → `from`/`to` → `instrumentId` 존재(404) → 시장 종류(코인이면 400) 순으로 판정한 뒤 `StockPriceProvider.getCandles`에 위임한다(컨트롤러에 try-catch 없음).
- `from`·`to`를 모두 생략하면 재생 중인 거래일 전체(그 중 이미 공개된 분봉)를 반환한다.
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
- **price**: `id: STOCK:005930:202607220901` + `{"market":"STOCK","symbol":"005930","price":71200,"sourceTime":"2026-07-22T09:00:00","emittedAt":"2026-07-25T09:01:00","sourceTradingDate":"2026-07-22","marketStatus":"OPEN"}` — 매분 스케줄에서 해당 종목의 `sourceTime`이 직전 값과 달라진(새로 공개된) 경우에만 전송한다. id는 `STOCK:{symbol}:{sourceTime을 yyyyMMddHHmm으로 포맷}`.
- **status**: `{"market":"STOCK","marketStatus":"CLOSED","emittedAt":"2026-07-25T15:30:00"}` — `symbol`·`status`·`reason`은 시장 전체 상태 변화라 없음(생략). 직전 스케줄 실행의 `marketStatus`와 달라진 경우에만 1회 전송한다(서버 기동 시점 값을 기준선으로 삼아 기동 직후 오탐 전송하지 않는다).
- 장 마감(`marketStatus=CLOSED`) 후에도 마지막으로 공개됐던 종목의 `price`·`sourceTime`은 그대로 유지되고 `status`는 `AVAILABLE`을 유지한다 — snapshot·이미 전송된 price 이벤트 모두 동일하게 마지막 값을 유지하며 별도로 `UNAVAILABLE`로 되돌리지 않는다.
- 재접속하면 새 emitter로 `snapshot` 1건을 다시 받는다. 연결이 끊긴 동안 놓친 이벤트를 서버가 재전송하는 기능은 없다(MVP 제외).
- heartbeat(20초 간격 SSE 주석)·`retry` 힌트·`onCompletion`/`onTimeout`/`onError` 시 emitter 정리는 `SseEmitterRegistry`(이슈 #18)가 공통 처리하며 이 컨트롤러에서 재구현하지 않는다.

---

## community

### 커뮤니티 게시물 작성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/community/posts | Access Bearer 필수 | `{"title":"게시물 제목","content":"게시물 본문"}` (`title` 최대 100자, `content` 최대 5,000자) | 201 `{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00"}` | 제목·본문 누락·빈 값·공백·최대 길이 초과는 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 008 COM-001, Issue #23 |

작성자는 요청에서 받지 않고 Access Token의 인증 사용자로 결정한다.

### 커뮤니티 게시물 단건 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/community/posts/{postId} | Access Bearer 필수 | 경로 변수 `postId` | 200 `{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00"}` | Access 인증 실패는 401 `UNAUTHORIZED`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-001, Issue #25 |

### 커뮤니티 게시물 목록 조회

| Method | URL | 인증 | 쿼리 파라미터 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/community/posts | Access Bearer 필수 | `page`(기본 0, 0 이상), `size`(기본 10, 1~50) | 200 `{"content":[{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00"}],"page":0,"size":10,"totalElements":1,"totalPages":1,"hasNext":false}` | `page`·`size` 범위 밖은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 008 COM-001, Issue #24 |

작성시각(`created_at`) 내림차순으로 정렬하며 동시각 항목은 `id` 내림차순으로 안정 정렬해 페이지 경계 중복·누락을 방지한다. 게시물이 없으면 오류가 아니라 200과 빈 `content`를 반환한다.

### 커뮤니티 게시물 수정

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| PATCH | /api/community/posts/{postId} | Access Bearer 필수 | 경로 변수 `postId`, 본문 `{"title":"게시물 제목","content":"게시물 본문"}` (`title` 최대 100자, `content` 최대 5,000자) | 200 `{"postId":1,"authorNickname":"finplayer","title":"게시물 제목","content":"게시물 본문","createdAt":"2026-07-27T12:00:00","updatedAt":"2026-07-27T12:00:00"}` | 제목·본문 누락·빈 값·공백·최대 길이 초과는 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. 본인 소유가 아닌 게시물은 403 `FORBIDDEN`. 게시물 미존재는 404 `NOT_FOUND` 공통 오류 형식 | 008 COM-001, Issue #26 |

작성자 본인만 수정할 수 있으며 소유자 확인은 요청 본문이 아닌 Access Token의 인증 사용자로 판단한다.

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

### 시장가 매수 주문 생성

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| POST | /api/orders | Access Bearer 필수, Header `Idempotency-Key` 필수(`OrderCreateRequest`와 별도) | `{"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET","quantity":"10"}` (`market`은 `STOCK`\|`CRYPTO` 리터럴만 파싱 성공, `side`는 `BUY`\|`SELL` 리터럴만 파싱 성공, `orderType`은 문자열, `quantity`는 문자열 숫자) | 201 `{"orderId":1,"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET","status":"FILLED","quantity":10,"requestedAt":"2026-07-29T09:00:00","tradeId":1,"price":70000,"amount":700000,"fee":105,"executedAt":"2026-07-29T09:00:00"}` (`OrderResponse`) | `Idempotency-Key` 누락, `market`\|`side` 미지원 리터럴(Jackson 파싱 실패), `side=SELL`, 수량 형식 위반(주식 소수·코인 8자리 초과·0 이하), 코인 최소주문금액(5,000원) 미달, 요청 시장≠종목 시장은 400 `VALIDATION_ERROR`. Access 인증 실패는 401 `UNAUTHORIZED`. `instrumentId` 미존재는 404 `NOT_FOUND`. 주식 장외는 409 `MARKET_CLOSED`, 유효한 최신 가격 없음은 409 `PRICE_UNAVAILABLE`, 현금 부족은 409 `INSUFFICIENT_CASH`. `orderType != "MARKET"`(예: `"LIMIT"`)은 422 `UNSUPPORTED_ORDER_TYPE` 공통 오류 형식 | 004 ORD-001~004, Issue #13 |

계좌·주문자는 요청에서 받지 않고 Access Token의 인증 사용자로 결정한다. 이번 구현은 `Idempotency-Key` 헤더 **존재 검증까지만** 하며(이슈 #13 범위), 동일 키 재요청 시 기존 응답 반환과 다른 본문 409 `IDEMPOTENCY_CONFLICT` 판정은 다루지 않는다(#22에서 구현 예정 — `orders.idempotency_key`/`request_hash`에는 값을 정직하게 저장). 검증·시세·현금 부족 등 모든 실패 경로는 주문·체결·계좌·보유 테이블에 어떤 흔적도 남기지 않는다(하나의 `@Transactional` 롤백).

### 내 주문 목록 조회

| Method | URL | 인증 | 요청 | 성공 응답 | 오류 응답 | Spec |
|---|---|---|---|---|---|---|
| GET | /api/orders | Access Bearer 필수 | 본문·경로 변수·쿼리 없음 | 200 `[{"orderId":1,"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET","status":"FILLED","quantity":10,"requestedAt":"2026-07-29T09:00:00"}, ...]` (`OrderListItemResponse[]`); 주문이 없으면 200 `[]` | Access 인증 실패는 401 `UNAUTHORIZED` 공통 오류 형식 | 006 PORT-003, Issue #21 |

조회 대상은 요청에서 받지 않고 Access Token의 인증 사용자 본인 소유 주문으로만 결정한다. `requestedAt` 내림차순, 동시각은 `id` 내림차순으로 정렬한다. 응답 필드는 `orderId`·`market`·`instrumentId`·`side`·`orderType`·`status`·`quantity`·`requestedAt` 8개로 고정이며, 체결 전용 필드(`tradeId`·`price`·`amount`·`fee`·`executedAt`)는 어떤 이름으로도 포함하지 않는다.
