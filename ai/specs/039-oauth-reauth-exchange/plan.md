# Plan: OAuth 재인증 왕복이 실제로 동작하게 만들기

## 관련 문서

- Spec: `./spec.md`
- 관련 ADR: ADR-0002(도메인 패키지·service 경유 원칙), ADR-0003(테스트 전략 — Fake OAuth·Testcontainers),
  ADR-0022(CORS·`allowCredentials=false`·`www.finplay.site`/`finplay.site` same-site 전제 — 이 spec이 고치는
  결함 2건의 근거 문서)
- 선행: `002-auth-account`(AUTH-003·AUTH-005, `reauth_tokens` 테이블·`ReauthTokenGenerator`·
  `AuthService.reauthenticate`/`changeNickname`), LOGIN purpose의 302+교환 코드 패턴(`OAuthLoginExchangeStore`,
  `POST /api/auth/oauth/login-exchange`, PR #385 — 이번 spec은 REAUTH purpose에 같은 패턴을 적용한다)
- 건드리는 기존 파일: `OAuthStateGenerator`, `OAuthStateCookieFactory`(변경 없음, 참고용), `OAuthCallbackController`,
  `OAuthCallbackService`, `OAuthAuthorizationController`(변경 여부는 §설계 판단 3 참고), `application.yml`,
  `.env.example`, `docs/api-routes.md`, `docs/api-contracts.md`

## 설계 판단 (요청받은 트레이드오프 3건)

### 판단 1 — state 만료시각 필드 위치·유효기간

현재 payload는 `purpose.userId.nonce` 3필드다(`OAuthStateGenerator.PAYLOAD_FIELD_COUNT = 3`). **4번째 필드로
`exp`(만료 시각, epoch 초 문자열)를 끝에 추가한다** — 기존 3필드의 의미·순서를 그대로 두고 뒤에 붙이는 것이 diff가
가장 작다. `PAYLOAD_FIELD_COUNT`를 4로 올리고, `verify()`가 서명·purpose·userId 파싱 뒤 `exp`를 파싱해 현재 시각과
비교한다. 만료 시 기존과 동일하게 `REAUTHENTICATION_FAILED`를 던진다(이미 malformed·위조 state가 이 코드로
통일돼 있다 — `OAuthStateGeneratorTest`·`OAuthCallbackServiceTest`의 기존 테스트들이 이 관례를 근거로 삼는다).

**유효기간은 10분, LOGIN·REAUTH 공통 단일 상수로 둔다.** 근거.

- `OAuthStateCookieFactory.MAX_AGE`(10분)가 "카카오·네이버 인가 왕복을 감당해야 하는 시간"으로 이미 검증된 값이다
  — state의 역할이 그 쿠키와 같으므로(둘 다 "이 왕복이 아직 유효한 시도인지" 판정) 같은 값을 재사용하는 것이
  자연스럽다. LOGIN purpose는 지금 쿠키가 이 시간을 감당하고 있어 state에 만료시각이 생겨도 사용자 경험이 바뀌지
  않는다.
- REAUTH 전용으로 더 짧은 값(예: `reauthToken` 자체의 5분)을 쓰는 방안도 검토했으나, `reauthToken`의 5분은
  "재인증을 마친 뒤 그 증명을 얼마나 오래 쓸 수 있는가"이고 state의 만료는 "카카오·네이버 인가 화면에서 얼마나
  오래 머물러도 되는가"로 성격이 다른 값이다. 후자를 전자와 같게 둘 근거가 없고, 값을 두 개로 나누면
  `OAuthStateGenerator.generate()`가 purpose별 TTL을 알아야 해서 책임이 하나 늘어난다. 단일 상수가 더 단순하다.
- `OAuthStateGenerator`는 현재 `Clock`을 갖지 않는다(난수 생성만 한다). 만료 판정을 테스트 가능하게 하려면 `Clock`을
  주입해야 한다 — 프로젝트 전역에 이미 `ClockConfig`가 단일 `Clock` 빈을 제공하므로(`AuthService` 등 다수가 이미
  주입받는 것과 같은 패턴) `@Autowired` 생성자에 `Clock`을 추가한다. **기존 2-인자 package-private 생성자
  (`SecureRandom, String`)는 시스템 클럭을 쓰는 형태로 남겨 기존 테스트 호출부(`OAuthStateGeneratorTest`,
  `OAuthCallbackServiceTest`의 `STATE_GENERATOR`, 그 밖의 통합 테스트가 주입받는 실제 빈)를 그대로 두고, 만료
  경계를 고정 시각으로 확인해야 하는 새 테스트만 `Clock`을 받는 3-인자 생성자를 추가로 쓴다** — 표시 필드 하나
  늘리는 변경치고 기존 호출부 전부를 건드리지 않기 위한 최소 diff 선택이다.

### 판단 2 — REAUTH 쿠키 이중제출 제거의 정확한 위치

`OAuthCallbackService.callback()`의 현재 순서는 `validateState(query, cookie 일치)` → `stateGenerator.verify(query)`
→ `error` 쿼리 검사 → `fetchUser` → purpose 분기다. 쿠키 일치 검사가 서명 검증보다 먼저 실행되므로, purpose를 아직
모르는 시점에 쿠키를 요구하는 구조다. **순서를 다음과 같이 바꾼다.**

1. provider 해석(변경 없음).
2. `queryState`가 없거나 공백이면 즉시 400 `VALIDATION_ERROR`(purpose와 무관하게 지금과 동일 — 서명을 검증할
   대상 자체가 없다).
3. `stateGenerator.verify(queryState)`를 먼저 호출해 `claims`(purpose·userId)를 얻는다. 서명 위조·형식 오류·
   **만료**는 지금처럼 `REAUTHENTICATION_FAILED`.
4. `claims.purpose() == LOGIN`이면 지금과 동일하게 `cookieState`가 없거나 `queryState`와 다르면 400
   `VALIDATION_ERROR`. **`claims.purpose() == REAUTH`면 이 단계를 건너뛴다** — `cookieState` 값은 아예 보지
   않는다.
5. 이후(`error` 쿼리 검사, `fetchUser`, purpose 분기)는 변경 없음.

이 재배치가 기존 테스트에 미치는 영향을 미리 확인했다(`OAuthCallbackServiceTest`).

- `callbackValidatesStateBeforeAuthorizationError`(LOGIN state, cookie 불일치 → 400) — 새 순서에서도
  `verify(ASCII_STATE)`가 먼저 성공하고 purpose=LOGIN이라 쿠키 비교가 여전히 실행돼 같은 결과가 나온다. **변경
  없음.**
- `callbackRejectsUnsignedStateThatMatchesCookie`(서명되지 않은 state, cookie 일치 → REAUTHENTICATION_FAILED) —
  `queryState`가 공백이 아니므로 3번으로 진입해 `verify()`가 그대로 던진다. **변경 없음.**
- `callbackRejectsInvalidInputBeforeExternalOrAuthCalls`(`invalidCallbacks()` 파라미터 목록) — 이 목록에 **LOGIN
  state로 cookie만 빠진 케이스**가 있다면 그대로 400을 유지해야 하고(§요구사항 유지 대상), 이 spec이 추가하는
  변경 자체를 검증하려면 **REAUTH state로 cookie가 빠지거나 다른 케이스가 이제는 400이 아니라 정상 진행(성공 또는
  provider 이후 단계의 다른 오류)** 임을 보이는 케이스가 새로 필요하다 — tasks.md 항목 2에서 이 테스트 파일을
  같이 손본다.
- `OAuthReauthCallbackIntegrationTest`의 네 테스트 모두 지금은 `cookie(new Cookie("oauth_state", state))`를
  항상 같이 보낸다 — 통과에는 영향 없지만, "쿠키가 없어도 성공한다"를 보여 주는 **새 테스트**가 이 spec의
  완료 조건이 요구하는 회귀 방지 증거다(쿠키를 계속 보내는 기존 4개는 그대로 두고 새로 추가).

**`OAuthAuthorizationController.authorizeReauth()`가 응답에 계속 `oauth_state` 쿠키를 실어 보내는지는 바꾸지
않는다** — 쿠키 발급 자체를 없애는 것은 이 spec이 고치려는 결함(콜백이 그 쿠키를 검증 조건으로 요구하는 것)과
다른 범위다. 쿠키가 실제로 브라우저에 안착하는 환경(같은 site로 배포된 경우 등)에서는 여전히 부가적인 신호로
남을 수 있고, 검증하지 않는 필드를 하나 남기는 비용이 컨트롤러·기존 `OAuthAuthorizationControllerTest`를 함께
고치는 비용보다 작다. 다만 컨트롤러·서비스의 **주석**(현재 "재인증 SPA가 이미 열어 둔 팝업이 응답을 직접
읽으므로 기존 200 JSON 계약을 그대로 둔다" — 이 전제 자체가 틀렸다는 것이 이 spec의 출발점이다)은 실제 동작에
맞게 고친다.

### 판단 3 — 교환 코드 저장소를 제네릭화할지, 별도 컴포넌트로 둘지

**별도 컴포넌트 `OAuthReauthExchangeStore`를 새로 만들고 `OAuthLoginExchangeStore`는 손대지 않는다.**
`docs/conventions.md`가 명시한 공통화 기준 — "공통화는 **세 번째 중복**이 보이고 책임이 명확할 때만 검토한다" —
을 그대로 적용한 결과다. 지금 이 변경은 로그인 교환 코드에 이은 **두 번째** 같은 모양의 저장소이며, 세 번째
사례가 아직 없다. 제네릭화(`OAuthExchangeStore<T>`처럼 `Class<T>`를 받아 `TokenResponse`·`ReauthTokenResponse`
둘 다 저장)는 지금 당장은 타입 파라미터·Redis key 네임스페이스 분리 로직만 추가하고 실질적으로 줄어드는 코드가
없다(각각 30줄 안팎의 얇은 컴포넌트). 세 번째 유사 사례가 생기면 그때 두 컴포넌트를 함께 걷어내고 제네릭화를
검토한다.

새 컴포넌트는 `OAuthLoginExchangeStore`와 구조를 그대로 미러링한다.

- Redis key prefix `auth:oauth-reauth-exchange:v1:` (기존 `auth:oauth-login-exchange:v1:`과 네임스페이스만 다름).
- TTL 60초 — 로그인 교환 코드와 같은 값을 쓸 이유가 없으면 다르게 둘 이유도 없다(§spec 비즈니스 규칙).
- `issue(ReauthTokenResponse)` → 코드 문자열, `consume(String code)` → `Optional<ReauthTokenResponse>`
  (`getAndDelete`로 1회용).

## API 설계

Base URL `/api`. 표에 없는 필드·엔드포인트는 이 spec 전후로 동일.

| Method | URL | 요청 | 응답 | 설명 |
|---|---|---|---|---|
| GET | /api/auth/oauth/{provider}/authorize?purpose=reauth | `Authorization: Bearer` 필수 | 200 `{"authorizationUri":"..."}` + `oauth_state` 쿠키(변경 없음) | 변경 없음 — 이 spec은 이 엔드포인트의 동작을 바꾸지 않는다 |
| GET | /api/auth/oauth/{provider}/callback | query `code`,`state`; cookie `oauth_state`(REAUTH는 이제 검증에 쓰이지 않음) | **LOGIN**: 302, `Location: <oauth.login-redirect-uri>?code=<코드>` (변경 없음). **REAUTH**: **302로 변경**, `Location: <oauth.reauth-redirect-uri>?code=<1회용 교환 코드>` (기존 200 `ReauthTokenResponse` JSON 직접 반환을 대체) | REAUTH 분기만 변경 |
| POST | /api/auth/oauth/login-exchange | `LoginExchangeRequest`(code) | `TokenResponse` | 변경 없음 |
| **POST** | **/api/auth/oauth/reauth-exchange** | **`ReauthExchangeRequest`(code)** | **`ReauthTokenResponse`(reauthToken, expiresInSeconds)** | **신규.** 위 REAUTH 리다이렉트의 교환 코드를 실제 `reauthToken`으로 바꾼다. 공개 경로, 1회용·TTL 60초 |

- `ReauthExchangeRequest` — `LoginExchangeRequest`와 같은 형태(`code` 단일 필드)지만 이름은 도메인을 그대로
  드러낸다. 두 요청을 하나의 DTO로 합치지 않는다(§판단 3과 같은 "세 번째 중복 전까지는 분리" 원칙 — DTO는 5줄
  안팎이라 합칠 이득이 더 작다).
- `ReauthTokenResponse`는 기존 타입을 그대로 재사용한다(신규 DTO 아님).

## 입력 명세

### ReauthExchangeRequest

| 필드 | 필수 | 검증 |
|---|---|---|
| code | 필수 | 공백 불가, 최대 100자(`LoginExchangeRequest`와 동일 기준). 형식 오류·만료·이미 소비됨·존재하지 않음은 모두 400 `VALIDATION_ERROR`로 구분하지 않는다(`login-exchange`와 동일 계약 — `docs/api-contracts.md` 기존 서술을 그대로 따른다) |

기존 `GET /api/auth/oauth/{provider}/authorize?purpose=reauth`, `GET /api/auth/oauth/{provider}/callback`의
`code`·`state`·`error` 쿼리, `oauth_state` 쿠키 입력 형식은 변경 없음(REAUTH 분기의 쿠키 필수 여부만 위 §판단 2
대로 바뀐다).

## 구성 요소 설계

### auth.oauth 패키지

- `OAuthStateGenerator` — payload 4필드(`purpose.userId.nonce.exp`)로 확장. `Clock` 의존성 추가(§판단 1).
  `generate(purpose, userId)` 시그니처는 그대로 두고 내부에서 `Clock.instant() + STATE_TTL`을 4번째 필드로 싣는다.
- `OAuthReauthExchangeStore`(신규) — `OAuthLoginExchangeStore` 미러링(§판단 3).
- `OAuthStateCookieFactory` — 변경 없음.

### auth.controller 패키지

- `OAuthCallbackController.callback()` — 현재 `result instanceof TokenResponse tokenResponse` 분기 옆에
  `result instanceof ReauthTokenResponse reauthTokenResponse` 분기를 추가해 같은 모양의 302 리다이렉트를
  만든다(`oauth.reauth-redirect-uri` 사용). 두 분기가 `OAuthCallbackService.callback()`이 반환하는 타입을
  전부 소진하므로, 기존의 `return ResponseEntity.ok(result);` fallback은 이제 도달 불가능한 죽은 코드가
  된다 — **이 변경이 직접 만든 orphan이므로 함께 제거한다**(CLAUDE.md 규칙 3). 반환 타입은 계속
  `ResponseEntity<Object>`로 두거나, 두 케이스가 전부 `ResponseEntity.status(FOUND)...build()`로 수렴하므로
  `ResponseEntity<Void>`로 좁힐 수 있는지는 구현 시점에 판단한다(간단하면 좁히고, 아니면 유지 — 이 spec이
  강제하지 않는다).
  - `POST /api/auth/oauth/reauth-exchange` 핸들러를 `login-exchange` 핸들러 옆에 추가한다.
  - 잘못된 컨트롤러 주석("재인증 SPA가 이미 열어 둔 팝업이 응답을 직접 읽는다")을 실제 동작으로 정정한다.

### auth.service 패키지

- `OAuthCallbackService`
  - `validateState()`를 §판단 2의 순서로 재작성(또는 동등한 새 메서드로 교체).
  - `issueReauthExchangeCode(ReauthTokenResponse)`·`consumeReauthExchangeCode(String)` 추가 —
    `issueLoginExchangeCode`/`consumeLoginExchangeCode`와 같은 얇은 위임.
- `AuthService.reauthenticate()` — 변경 없음(§spec 비즈니스 규칙 2번이 이미 이 메서드에 의존한다는 것을 근거로만
  삼는다).

### 설정

`application.yml`의 `oauth` 블록에 `reauth-redirect-uri: ${OAUTH_REAUTH_REDIRECT_URI}`를 `login-redirect-uri`
옆에 추가한다(**별도 프로퍼티로 둔다** — 재인증 팝업의 콜백 라우트는 로그인 콜백 라우트와 다른 프론트 경로가 될
가능성이 높고, 두 리다이렉트 대상을 하나의 값으로 묶으면 프론트가 팝업용 라우트를 따로 두지 못하게 강제하는
꼴이 된다). `.env.example`에 `OAUTH_REAUTH_REDIRECT_URI=`를 `OAUTH_LOGIN_REDIRECT_URI` 옆에 추가한다. 값이
없어도 `./gradlew build`·로컬 기동이 되는지는 `login-redirect-uri`와 같은 방식(현재 필수 환경변수, 로컬은
`.env`로 채움)을 그대로 따른다 — 새 프로필 분기를 만들지 않는다.

## 데이터 모델

스키마 변경 없음. 상태 저장은 전부 Redis(`OAuthReauthExchangeStore`)와 서명된 state(자체 만료시각 포함) 안에
있고, `reauth_tokens` 테이블 구조·컬럼은 이 spec에서 바뀌지 않는다. Flyway 마이그레이션을 만들지 않는다.

## 테스트 계획

- **단위**
  - `OAuthStateGeneratorTest` — 만료 이전 검증 성공, 만료 시점 이후 `REAUTHENTICATION_FAILED`, 만료 경계(고정
    `Clock`으로 TTL 직전·직후) LOGIN·REAUTH 양쪽. 기존 `verifyRejectsWrongPayloadFieldCount` 등 필드 개수 관련
    테스트는 4필드 기준으로 payload 픽스처를 맞춘다.
  - `OAuthCallbackServiceTest` — REAUTH purpose는 `cookieState`가 `null`이거나 `queryState`와 달라도 성공,
    LOGIN purpose는 지금처럼 쿠키 불일치·누락 시 400(회귀 방지). `issueReauthExchangeCode`/
    `consumeReauthExchangeCode`가 store에 위임하는지. `invalidCallbacks()` 픽스처에 REAUTH 케이스 갱신.
  - `OAuthReauthExchangeStoreTest`(신규, `OAuthLoginExchangeStoreTest` 미러링) — 발급한 코드로 1회 소비 성공,
    두 번째 소비 실패, TTL 경과 후 실패(Redis TTL이므로 Testcontainers Redis 또는 기존 store 테스트와 같은
    방식을 따른다).
- **슬라이스(`@WebMvcTest`)**
  - `OAuthCallbackControllerTest` — REAUTH 성공이 200 JSON이 아니라 302이고 `Location`에 `reauthToken` 원문이
    없는지(현재 `callbackReturnsReauthTokenAndExpiresStateCookie` 테스트를 302 기대로 다시 씀).
    `POST /api/auth/oauth/reauth-exchange` 성공·잘못된 코드 400 계약 테스트(`login-exchange` 테스트 쌍을
    미러링).
- **통합(Testcontainers, ADR-0003)**
  - `OAuthReauthCallbackIntegrationTest` — 기존 4개 시나리오는 유지하되(쿠키를 계속 보내도 여전히 성공해야
    한다), **쿠키 없이도 성공하는 케이스**와 **302 리다이렉트 + `reauth-exchange` 호출까지 이어지는 전체
    왕복**을 추가한다. "다른 계정으로 재인증 시도" 테스트는 그대로 두되 302/신규 엔드포인트 흐름에 맞게 응답
    검증부만 갱신.
  - 신규 또는 기존 `NicknameChangeIntegrationTest` 확장 — Fake OAuth 인가 → 콜백 302 → `reauth-exchange` →
    받은 `reauthToken`으로 `PATCH /api/auth/me/nickname` 성공까지 한 시나리오로 검증(spec 완료 조건 항목).
