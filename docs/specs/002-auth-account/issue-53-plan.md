# Issue #53 OAuth 민감 작업 재인증 토큰 구현 계획

> **For agentic workers:** 각 작업은 실패 테스트를 먼저 작성한다. Fake 자동 검증과 실제 공급자 스모크를 서로 대체하거나 합쳐 보고하지 않는다.

**Goal:** `GET /api/auth/oauth/{provider}/authorize`에 `purpose=login|reauth`를 추가해 목적을 state에 위·변조 불가능하게 묶고, `GET /api/auth/oauth/{provider}/callback`이 state에서 purpose를 복원해 기존 로그인(#10)과 신규 OAuth 재인증을 분기하도록 만든다. 재인증 성공 시 5분 유효·일회용 `reauthToken`을 발급하되 회원·소셜계정·계좌·시드머니는 절대 생성·변경하지 않는다.

**관련 정본:** GitHub Issue #53, PRD `AUTH-003`·`AUTH-005` (`docs/prd.md` 231-244행, 268-281행, 511-512행), `spec.md`, `plan.md`, Issue #9/#10 계획, ADR-0002, ADR-0004, `docs/conventions.md`

**선행:** Issue #8·#9·#10이 `origin/dev`에 있다 — `GET /api/auth/oauth/{provider}/authorize`(302), `GET /api/auth/oauth/{provider}/callback`(로그인), `OAuthStateGenerator`(순수 랜덤 state), `OAuthStateCookieFactory`(10분 보안 쿠키), `AuthService.oauthLogin`, `GET /api/auth/me`.

**Architecture:** 기존 `OAuthAuthorizationController → OAuthAuthorizationService`와 `OAuthCallbackController → OAuthCallbackService → AuthService`를 그대로 두고, state 생성·검증 계층(`OAuthStateGenerator`)에 purpose(`LOGIN`/`REAUTH`)와 (reauth인 경우) userId를 HMAC-SHA-256으로 서명해 묶는 기능을 추가한다. `OAuthCallbackService`는 검증된 claims의 purpose로 기존 `AuthService.oauthLogin`(#10, 완전 유지)과 신규 `AuthService.reauthenticate`(별도 `@Transactional`, 읽기+`reauth_tokens` 쓰기만) 사이를 분기한다. login 경로의 302 계약과 신규가입/재로그인 트랜잭션은 한 글자도 바꾸지 않는다.

**Tech Stack:** Java 17, Spring Boot 4.1, Spring Security(커스텀 `RequestMatcher`), Spring Data JPA, MySQL 8.4 Testcontainers, JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

---

## 요구사항 ID와 수용 기준

### PRD `AUTH-003` (재인증 부분)

- 내 정보 수정용 OAuth 재인증은 이미 연결된 동일 `provider + providerUserId`만 허용하고 5분 유효·일회용 `reauthToken`을 발급한다.
- 재인증에서는 회원·소셜계정·계좌·시드머니를 생성하거나 변경하지 않는다.
- 다른 제공자 계정으로 인증하면 403 `REAUTHENTICATION_FAILED`로 거부한다.
- 자동 테스트는 Fake OAuth 제공자를 사용한다.

### PRD `AUTH-005` (재인증 소비 측 — 이번 이슈 범위 밖이지만 계약 정합성 확인용)

- OAuth 전용 회원의 닉네임·이메일 변경은 AUTH-003에서 발급한 5분 유효·일회용 `reauthToken`을 요구한다 — 이번 이슈는 발급까지만 구현하고 소비(닉네임/이메일 변경 API)는 구현하지 않는다.
- 재인증 토큰 원문은 저장하지 않고 해시만 저장하며 성공 시 한 번만 소비한다 — 소비 로직 자체는 후속 이슈지만 저장 스키마(해시·만료·소비 여부)는 이번 이슈에서 확정한다.

### Issue #53 수용 시나리오

1. Given 로그인한 사용자가 이미 `KAKAO` 소셜 계정을 연결한 상태
2. When `GET /api/auth/oauth/kakao/authorize?purpose=reauth`를 `Authorization: Bearer` 헤더와 함께 호출
3. Then 200과 `{ "authorizationUri": "..." }`을 받고, state 쿠키는 purpose와 사용자 ID를 서명해 묶은 값으로 발급된다.
4. And 브라우저가 그 URL로 이동해 같은 카카오 계정으로 인가하면 `GET /api/auth/oauth/kakao/callback`이 state에서 purpose=REAUTH와 userId를 복원하고, 콜백에서 받은 `provider+providerUserId`가 그 회원에게 연결된 `SocialAccount`와 일치하는지 확인한 뒤 5분 유효·일회용 `reauthToken`을 200으로 반환한다.
5. And 다른 카카오 계정으로 인가하거나, 그 회원에게 연결되지 않은 provider로 인가하거나, state의 purpose·userId가 위·변조되면 403 `REAUTHENTICATION_FAILED`이며 User·SocialAccount·Account·시드머니는 변하지 않는다.
6. And `purpose`를 생략하거나 `purpose=login`으로 호출하는 기존 흐름은 인증 없이 302 리다이렉트되고, callback은 기존 `AuthService.oauthLogin` 경로로 그대로 처리된다 (Issue #9/#10 회귀 없음).
7. And `purpose=reauth` 요청을 `Authorization` 헤더 없이 호출하면 401 `UNAUTHORIZED`다.

---

## 범위와 제외

### 포함

- `GET /api/auth/oauth/{provider}/authorize`에 `purpose` 쿼리 파라미터 처리 — 생략/`login`은 기존 302 계약 그대로, `reauth`는 인증 필요 + 200 JSON 계약.
- `OAuthStateGenerator`를 HMAC-SHA-256 서명 state(purpose + userId + nonce)로 확장.
- `SecurityConfig`에서 `purpose=reauth` authorize 요청만 인증을 요구하는 커스텀 `RequestMatcher` 분기.
- `GET /api/auth/oauth/{provider}/callback`의 purpose 분기 — login은 기존 `AuthService.oauthLogin` 완전 재사용, reauth는 신규 `AuthService.reauthenticate`.
- `reauth_tokens` Flyway 마이그레이션, `ReauthToken` 엔티티, `ReauthTokenRepository`, 원문 미저장(SHA-256 해시만) 발급 트랜잭션.
- `REAUTHENTICATION_FAILED`(403) `ErrorCode` 추가.
- `OAUTH_STATE_SECRET` 신규 환경변수와 `.env.example`·`application.yml`·`build.gradle` test 환경 반영.
- `docs/api-routes.md` 동기화.

### 제외

- `reauthToken` 소비(닉네임·이메일 변경 API) — 발급까지만 구현한다 (Issue 지시 #8).
- 명시적 소셜 계정 연결 기능, 새 provider 추가.
- 카카오·네이버 실제 콘솔에서 새로 길어진 state 파라미터 길이 제한 재검증 — 아래 "미확정" 참고.
- 관리자·1차 이후 고도화.

---

## 설계 결정

### D1. state는 purpose·userId·nonce를 HMAC-SHA-256으로 서명해 하나의 opaque 문자열로 만든다

- 신규 enum `OAuthPurpose { LOGIN, REAUTH }` (`OAuthProviderName`과 같은 패턴의 `from(String)` 정적 메서드 포함, authorize 컨트롤러가 쿼리 `purpose` 문자열을 여기로 해석).
- 신규 `OAuthStateClaims(OAuthPurpose purpose, Long userId)` record — `userId`는 `LOGIN`이면 `null`.
- `OAuthStateGenerator`를 다음과 같이 확장한다(대체가 아니라 기존 클래스 확장 — 호출부가 하나뿐이라 새 클래스를 만들 이유가 없다):
  ```java
  public String generate(OAuthPurpose purpose, Long userId) { ... }   // 기존 generate() 제거하고 이 시그니처로 교체
  public OAuthStateClaims verify(String state) { ... }
  ```
  - `generate`: 32바이트 `SecureRandom` nonce를 base64url(padding 없음)로 인코딩하고, `purpose.name() + "." + (userId==null?"":userId) + "." + nonce` 형태의 원문 payload를 만든 뒤 그 바이트를 base64url로 인코딩해 `payloadPart`를 만든다. `payloadPart` 문자열 바이트에 HMAC-SHA-256 서명을 계산해 base64url로 인코딩한 `signaturePart`를 만들고 `payloadPart + "." + signaturePart`를 반환한다. `payloadPart`·`signaturePart` 모두 `.`을 포함하지 않는 base64url 문자셋이므로 `state.split("\\.")`로 안전하게 분리할 수 있다.
  - `verify`: `.` 기준으로 `payloadPart`/`signaturePart`를 분리하고(정확히 2개가 아니면 실패), `payloadPart`로 서명을 재계산해 `MessageDigest.isEqual`로 상수 시간 비교한다. 실패하거나 `payloadPart` 디코딩·3필드 파싱(purpose enum 미해당 포함)이 실패하면 **모두** `BusinessException(ErrorCode.REAUTHENTICATION_FAILED)`를 던진다(아래 D2 참고). 성공하면 `OAuthStateClaims`를 반환한다.
  - 만료 시각(`exp`)은 payload에 넣지 않는다 — 기존 `OAuthStateCookieFactory`의 10분 `Max-Age`가 이미 state의 유효 기간을 제한하므로 중복 방어를 추가하지 않는다(단순성 우선).
  - 서명 시크릿은 **신규 환경변수 `OAUTH_STATE_SECRET`**을 쓴다. `JWT_SECRET`을 재사용하지 않는다 — 이 저장소는 `EMAIL_VERIFICATION_SECRET`을 `JWT_SECRET`과 반드시 분리하는 기존 컨벤션(conventions.md, plan.md)을 이미 따르고 있고, OAuth state 서명 키가 유출되면 임의 `userId`로 reauth 위조가 가능해지므로 JWT 서명 키와 결합 위험을 만들지 않는다. `application.yml`에 `oauth.state-secret: ${OAUTH_STATE_SECRET}` 추가, `.env.example`에 이름만 추가, `build.gradle`의 `tasks.withType(Test).configureEach` 블록에 `environment 'OAUTH_STATE_SECRET', 'test-oauth-state-secret-that-is-at-least-32-bytes'`를 기존 `JWT_SECRET`·`EMAIL_VERIFICATION_SECRET`과 같은 자리에 추가한다.
- 기존 `OAuthAuthorizationService.authorize(String rawProvider)`는 시그니처와 동작(state 생성 → 쿠키 → 302)을 그대로 두되 내부에서 `stateGenerator.generate(OAuthPurpose.LOGIN, null)`을 호출하도록만 바꾼다. `OAuthAuthorizationController.authorize(...)`는 **한 줄도 바꾸지 않는다** (Issue #9/#10 302 계약 회귀 금지, 지시 #1).
- Kakao/Naver 실제 Provider와 Fake authorize/callback/grant store는 state를 항상 불투명한 문자열로만 다뤄 그대로 통과시키므로(패스스루), 이번 변경으로 `KakaoOAuthAuthorizationProvider`·`NaverOAuthAuthorizationProvider`·`KakaoOAuthCallbackProvider`·`NaverOAuthCallbackProvider`·`FakeOAuthAuthorizationProvider`·`FakeOAuthCallbackProvider`·`FakeOAuthGrantStore`는 **수정하지 않는다**.

### D2. state 서명 실패는 모두 403 `REAUTHENTICATION_FAILED`로 통일한다 (400과 구분)

- 기존 Issue #10 계약(쿼리 `state`와 쿠키 `oauth_state`의 누락·불일치 → 400 `VALIDATION_ERROR`)은 그대로 유지한다. 이 검사는 여전히 외부 호출 이전, HMAC 검증 이전에 먼저 실행한다.
- 그 검사를 통과한 뒤 `OAuthStateGenerator.verify()`가 서명 위조·형식 오류로 실패하면 403 `REAUTHENTICATION_FAILED`다. 이슈 지시 #7이 "state 위·변조"를 다른 재인증 실패 사유(다른 계정·미연결 provider·재사용 토큰)와 같은 403 그룹으로 명시했기 때문이다.
- 이 규칙은 `purpose=LOGIN`으로 위장한 위조 state에도 동일하게 적용한다 — 서명이 깨졌다는 사실만으로는 원래 의도한 purpose를 신뢰할 수 없으므로 구분하지 않는다(아래 "미확정" 참고: PRD가 이 세부 경우를 명시하지 않아 확장 해석했다).
- `error` 쿼리(사용자 인가 취소)와 authorization code 만료/재사용은 purpose와 무관하게 기존과 동일하게 400 `OAUTH_AUTHORIZATION_FAILED`다 — 순서상 state 검증 다음, 코드 교환 이전에 그대로 둔다.

### D3. `OAuthCallbackService`의 purpose 분기

```
1. provider 해석 (기존, 400 VALIDATION_ERROR)
2. validateState(queryState, cookieState) — 기존 상수 시간 일치 검사, 그대로 유지
3. OAuthStateClaims claims = stateGenerator.verify(queryState) — 신규, 실패 시 403 REAUTHENTICATION_FAILED
4. authorizationError 검사 — 기존, 400 OAUTH_AUTHORIZATION_FAILED
5. code 공백 검사 — 기존, 400 VALIDATION_ERROR
6. callbackProvider.fetchUser(...) — 기존, purpose 무관 동일 호출
7. validateOAuthUser(oauthUser) — 기존 그대로 재사용 (email 필수 검증 포함, 아래 "미확정" 참고)
8. claims.purpose()로 분기:
   - LOGIN → authService.oauthLogin(provider, oauthUser)  // #10과 완전히 동일, 코드 변경 없음
   - REAUTH → authService.reauthenticate(claims.userId(), provider, oauthUser)  // 신규
```

- 반환 타입은 두 분기가 다른 DTO(`TokenResponse` vs `ReauthTokenResponse`)를 반환하므로 `OAuthCallbackService.callback(...)`과 `OAuthCallbackController.callback(...)`의 선언 타입을 `Object`로 바꾼다. purpose는 provider가 그대로 되돌려주는 `code`/`state`/`error`에는 포함되지 않고 오직 state 내부에 서명되어 있으므로, 컨트롤러 라우팅(`params=`)으로 응답 타입을 분리할 수 없다 — 새 sealed 인터페이스나 마커를 만드는 대신 `Object` 반환이 가장 단순하다(Jackson은 선언 타입이 아니라 실제 런타임 타입으로 직렬화한다).
- state 쿠키 만료(`stateCookieFactory.expire(...)`) 로직은 purpose와 무관하게 기존과 동일하게 응답에 포함한다.

### D4. `AuthService.reauthenticate` — 별도 트랜잭션, 회원·계좌 불변

```java
@Transactional
public ReauthTokenResponse reauthenticate(Long userId, OAuthProviderName provider, OAuthUserDto oauthUser) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.REAUTHENTICATION_FAILED));
    SocialAccount socialAccount = socialAccountRepository
        .findByProviderAndProviderUserId(provider, oauthUser.providerUserId())
        .orElseThrow(() -> new BusinessException(ErrorCode.REAUTHENTICATION_FAILED));
    if (!socialAccount.getUser().getId().equals(userId)) {
        throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
    }
    LocalDateTime now = LocalDateTime.now(clock);
    String rawToken = reauthTokenGenerator.generate();
    reauthTokenRepository.save(ReauthToken.create(user, sha256(rawToken), now.plusMinutes(5), now));
    return new ReauthTokenResponse(rawToken, Duration.ofMinutes(5).toSeconds());
}
```

- 기존 `sha256(String)` private 헬퍼(이미 refresh token·signup token 해시에 사용 중)를 그대로 재사용한다.
- `userId`로 회원을 찾지 못하는 경우(서명된 state가 가리키는 회원이 그 사이 삭제된 극단적 경쟁 상태)도 403 `REAUTHENTICATION_FAILED`로 통일한다 — 이슈 본문·PRD에 이 경우가 명시돼 있지 않아 "다른 계정/미연결 provider"와 같은 그룹으로 취급하기로 결정했다(아래 "미확정" 참고).
- 이 메서드는 `userRepository`·`socialAccountRepository`를 **조회만** 하고 `accountService`·`accountService.createAccountsFor`·`userRepository.save`·`socialAccountRepository.save`·`refreshTokenRepository`를 절대 호출하지 않는다 — 지시 #6의 핵심 제약이며 MySQL 통합 테스트로 행 수·값 불변을 직접 검증한다.
- `AuthService` 생성자에 `ReauthTokenRepository reauthTokenRepository`와 `ReauthTokenGenerator reauthTokenGenerator` 의존성을 추가한다 (명시적 생성자이므로 기존 `AuthServiceTest`의 모든 `new AuthService(...)` 호출부를 갱신해야 한다 — Task 2에서 함께 처리).

### D5. `reauth_tokens` 스키마 (ADR-0004 — 새 Flyway 마이그레이션)

`V5__create_reauth_tokens_table.sql`, 기존 V1~V4는 수정하지 않는다.

```sql
CREATE TABLE reauth_tokens (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    user_id     BIGINT       NOT NULL,
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  DATETIME(6)  NOT NULL,
    consumed_at DATETIME(6)  NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_reauth_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_reauth_tokens_user FOREIGN KEY (user_id) REFERENCES users (id)
);
```

- 컬럼명은 `email_verifications`의 `consumed_at`(널=미소비) 패턴을 그대로 따른다 — `refresh_tokens`의 `revoked_at`(폐기)과 의미가 달라서(일회 소비 vs 강제 폐기) 이름을 구분한다.
- `ReauthToken` 엔티티는 `RefreshToken`과 같은 Lombok·정적 팩토리 패턴(`@Getter` + `@NoArgsConstructor(PROTECTED)`, `create(...)` 정적 팩토리, setter 없음)을 따른다.
- `ReauthTokenRepository`는 이번 이슈에서 `save()`(상속) 외 커스텀 쿼리 메서드를 추가하지 않는다 — `findByTokenHash`/소비용 조건부 UPDATE는 소비 API를 구현하는 후속 이슈의 몫이다(지시 #8, 범위 제외).
- `ReauthTokenGenerator`는 `OAuthStateGenerator`의 nonce 생성 패턴과 동일하게 32바이트 `SecureRandom` → base64url(패딩 없음)로 원문 토큰을 만든다. `com.finplay.api.auth.oauth` 패키지에 둔다(재인증은 OAuth 전용 기능이므로 `OAuthStateGenerator`·`OAuthNicknameGenerator`와 같은 자리).

### D6. `purpose=reauth` authorize의 Security 분기 — 필터 단에서 쿼리 파라미터로 판별

- `SecurityConfig.PUBLIC_GET_PATHS`에서 `/api/auth/oauth/*/authorize`를 제거한다.
- 커스텀 `RequestMatcher`를 추가해 "GET이고 경로가 `/api/auth/oauth/*/authorize`이고 `purpose` 파라미터가 없거나 공백이거나 대소문자 무관 `login`"인 요청만 공개로 허용한다.
  ```java
  private static final RequestMatcher OAUTH_LOGIN_AUTHORIZE_MATCHER = new AndRequestMatcher(
      new AntPathRequestMatcher("/api/auth/oauth/*/authorize", HttpMethod.GET.name()),
      request -> isLoginPurpose(request.getParameter("purpose")));
  ```
  `isLoginPurpose`는 `value == null || value.isBlank() || "login".equalsIgnoreCase(value)`.
- `requestMatchers(OAUTH_LOGIN_AUTHORIZE_MATCHER).permitAll()`을 `PUBLIC_GET_PATHS`용 규칙과 별도 줄로 추가한다. `purpose=reauth`(또는 그 외 어떤 값)인 요청은 이 매처에 걸리지 않고 `anyRequest().authenticated()`로 떨어져 401을 요구한다 — "모르는 purpose는 기본적으로 인증을 요구한다"는 안전한 기본값이다.
- callback 경로(`/api/auth/oauth/*/callback`)는 두 purpose 모두 공개로 유지한다 — 변경 없음. 브라우저가 OAuth 공급자에서 돌아올 때는 `Authorization` 헤더를 보낼 수 없으므로 신원 확인은 서명된 state의 userId로만 한다.

### D7. authorize 컨트롤러 — 같은 클래스에 메서드 추가, 기존 메서드는 그대로

- `OAuthAuthorizationController.authorize(String provider)`(기존, 302)는 **수정하지 않는다**(D1 참고).
- 신규 메서드를 같은 클래스에 params로 구분해 추가한다:
  ```java
  @GetMapping(value = "/{provider}/authorize", params = "purpose=reauth")
  public ResponseEntity<OAuthReauthorizeResponse> authorizeReauth(
      @PathVariable String provider,
      @AuthenticationPrincipal AuthenticatedUser principal) {
      OAuthAuthorizationResult result = authorizationService.authorizeForReauth(provider, principal.userId());
      ResponseCookie stateCookie = stateCookieFactory.create(result.provider(), result.state());
      return ResponseEntity.ok()
          .header(HttpHeaders.SET_COOKIE, stateCookie.toString())
          .body(new OAuthReauthorizeResponse(result.authorizationUri().toString()));
  }
  ```
  Spring MVC는 `params="purpose=reauth"`가 더 구체적이므로 `purpose=reauth`일 때 이 메서드로, 그 외(생략·`login`·기타 값)에는 기존 `authorize(...)`로 라우팅한다. `purpose=reauth`가 아닌데 인증되지 않은 요청은 D6의 Security 매처가 이미 permitAll로 통과시켰으므로 컨트롤러는 그 값이 blank/login이라고 가정할 수 있지만, 방어적으로 기존 `authorize(...)` 안에서 `purpose`가 blank·`login`이 아니면 400 `VALIDATION_ERROR`를 던지도록 파라미터를 추가한다(심층방어 — Issue #10 D1과 같은 철학).
- `OAuthAuthorizationService`에 `authorizeForReauth(String rawProvider, Long userId)`를 추가한다. provider 해석과 authorization provider 선택 로직은 기존 `authorize(...)`와 동일하게 재사용하되(중복 코드는 private 메서드로 추출), `stateGenerator.generate(OAuthPurpose.REAUTH, userId)`를 호출하는 점만 다르다.
- `OAuthReauthorizeResponse(String authorizationUri)` — dto/response, 단순 값 래핑이라 정적 팩토리 없이 생성자 직접 사용.

### D8. `ReauthTokenResponse` 계약

```java
public record ReauthTokenResponse(String reauthToken, long expiresInSeconds) {}
```

- `reauthToken`은 원문을 딱 한 번만 응답 본문에 노출한다. DB에는 SHA-256 해시만 저장한다(D5).
- `expiresInSeconds`는 항상 300(5분)이다 — `TokenResponse`처럼 여러 발급 정책을 가질 이유가 없어 상수로 둔다.

---

## HTTP 계약

| 구분 | Method / Path | 인증 | 입력 | 성공 응답 | 오류 응답 |
|---|---|---|---|---|---|
| authorize(login) | GET `/api/auth/oauth/{provider}/authorize` (purpose 생략 또는 `login`) | 공개 (변경 없음) | 경로 `provider` | 302 리다이렉트 + `oauth_state` 쿠키 (변경 없음) | 미지원 provider 400 `VALIDATION_ERROR` (변경 없음) |
| authorize(reauth) | GET `/api/auth/oauth/{provider}/authorize?purpose=reauth` | `Authorization: Bearer` 필수 | 경로 `provider` | 200 `{"authorizationUri":"https://..."}` + `oauth_state` 쿠키(purpose+userId 서명) | 인증 없음 401 `UNAUTHORIZED`; 미지원 provider 400 `VALIDATION_ERROR` |
| callback(공통) | GET `/api/auth/oauth/{provider}/callback` | 공개 (변경 없음) | query `code`/`state`(취소 시 `error`), cookie `oauth_state` | 분기 참고 | state 누락·불일치 400 `VALIDATION_ERROR`; state 서명 위·변조 403 `REAUTHENTICATION_FAILED`; 인가 취소·코드 만료/재사용 400 `OAUTH_AUTHORIZATION_FAILED`; 이메일 없음 400 `OAUTH_EMAIL_REQUIRED`; 공급자 장애 502 `OAUTH_PROVIDER_ERROR` |
| callback(login 분기) | 〃 | 〃 | 〃 | 200 `TokenResponse` (accessToken/refreshToken/두 만료초) — #10과 완전히 동일 | 신규 조합 이메일 충돌 409 `ACCOUNT_LINK_REQUIRED` (변경 없음) |
| callback(reauth 분기) | 〃 | 〃 | 〃 | 200 `{"reauthToken":"<원문, 1회>","expiresInSeconds":300}` | 다른 provider+providerUserId, 미연결 provider, (극단) userId 회원 없음 → 모두 403 `REAUTHENTICATION_FAILED` |

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/auth/oauth/OAuthPurpose.java`
- `src/main/java/com/finplay/api/auth/oauth/OAuthStateClaims.java`
- `src/main/java/com/finplay/api/auth/oauth/ReauthTokenGenerator.java`
- `src/main/java/com/finplay/api/auth/domain/ReauthToken.java`
- `src/main/java/com/finplay/api/auth/repository/ReauthTokenRepository.java`
- `src/main/java/com/finplay/api/auth/dto/response/OAuthReauthorizeResponse.java`
- `src/main/java/com/finplay/api/auth/dto/response/ReauthTokenResponse.java`
- `src/main/resources/db/migration/V5__create_reauth_tokens_table.sql`

### Production files to modify

- `src/main/java/com/finplay/api/auth/oauth/OAuthStateGenerator.java` — `generate(purpose, userId)`/`verify(state)`로 확장
- `src/main/java/com/finplay/api/auth/service/OAuthAuthorizationService.java` — 기존 `authorize` 내부 `generate(LOGIN,null)` 호출, `authorizeForReauth` 추가
- `src/main/java/com/finplay/api/auth/controller/OAuthAuthorizationController.java` — `authorizeReauth` 메서드 추가, 기존 `authorize`에 purpose 방어 검증 추가
- `src/main/java/com/finplay/api/auth/service/OAuthCallbackService.java` — `verify` 호출과 purpose 분기, 반환 타입 `Object`
- `src/main/java/com/finplay/api/auth/controller/OAuthCallbackController.java` — 반환 타입 `ResponseEntity<Object>`
- `src/main/java/com/finplay/api/auth/service/AuthService.java` — 생성자에 `ReauthTokenRepository`·`ReauthTokenGenerator` 추가, `reauthenticate` 메서드 추가
- `src/main/java/com/finplay/api/auth/config/SecurityConfig.java` — `purpose=reauth` 인증 분기용 커스텀 `RequestMatcher`
- `src/main/java/com/finplay/api/common/ErrorCode.java` — `REAUTHENTICATION_FAILED(403)` 추가
- `src/main/resources/application.yml` — `oauth.state-secret: ${OAUTH_STATE_SECRET}`
- `.env.example` — `OAUTH_STATE_SECRET` 이름만 추가
- `build.gradle` — `tasks.withType(Test).configureEach`에 `OAUTH_STATE_SECRET` 테스트용 더미 값 추가

### Test files

- `OAuthStateGeneratorTest` — 기존 `generate()` 호출부 전부 갱신 + 서명 왕복·위조 거부·형식 오류 거부 신규 케이스
- `OAuthAuthorizationServiceTest` — 기존 케이스 시그니처 갱신 + `authorizeForReauth` 신규 케이스
- `OAuthAuthorizationController` 관련 `@WebMvcTest`/Security 통합 — purpose 없음/`login` 302 회귀, `reauth` 미인증 401, `reauth` 인증 200, `reauth` 아닌 이상값 400
- `OAuthCallbackServiceTest` — 기존 login 케이스 회귀 + reauth 성공/다른 계정/미연결 provider/서명 위조 케이스
- `AuthServiceTest` — 생성자 변경 반영 + `reauthenticate` 단위 케이스(Mockito, DB 미호출 검증)
- `ReauthTokenRepositoryTest` (`@DataJpaTest`) — 저장, `token_hash` UNIQUE, FK
- OAuth 재인증 MySQL 통합 테스트(`XxxIntegrationTest`) — Fake OAuth로 authorize(reauth)→callback(reauth) 전체 흐름, 성공 전후 User/SocialAccount/Account/시드머니 불변, `reauth_tokens` 1행만 추가
- 기존 Issue #9/#10 회귀 스위트 — 컴파일 브레이크(시그니처 변경) 수정 후 전부 재통과 확인

### Documentation files to modify after implementation

- `docs/api-routes.md`
- `docs/specs/002-auth-account/tasks.md`
- `docs/specs/002-auth-account/run-log.md`
- PR 본문의 검증 표

### 수정하지 않을 파일

- `src/main/resources/db/migration/V1__init.sql` ~ `V4__create_post_comments_table.sql`
- `KakaoOAuthAuthorizationProvider`, `NaverOAuthAuthorizationProvider`, `KakaoOAuthCallbackProvider`, `NaverOAuthCallbackProvider`
- `FakeOAuthAuthorizationProvider`, `FakeOAuthCallbackProvider`, `FakeOAuthGrantStore`
- `OAuthProviderName`, `OAuthAuthorizationResult`, `OAuthAuthorizationProvider`, `OAuthCallbackProvider`, `OAuthUserDto`
- `TokenResponse`, `RefreshToken`, `RefreshTokenRepository`
- `AuthService.oauthLogin`, `AuthService.signup`, `AuthService.login`의 기존 로직

---

## Task 1: state 서명·검증 계약과 오류 코드·환경변수

- [ ] 실패 테스트로 `OAuthPurpose`, `OAuthStateClaims`, `OAuthStateGenerator.generate(purpose, userId)`/`verify(state)` 계약을 고정한다: LOGIN/REAUTH 왕복, userId null 처리, 위조 서명·잘못된 형식·모르는 purpose 문자열이 모두 `REAUTHENTICATION_FAILED`(403)로 거부됨을 확인한다.
- [ ] `ErrorCode.REAUTHENTICATION_FAILED(HttpStatus.FORBIDDEN, ...)`를 추가한다.
- [ ] `OAUTH_STATE_SECRET`을 `application.yml`·`.env.example`·`build.gradle` test 환경에 추가한다.
- [ ] 기존 `OAuthAuthorizationService.authorize(rawProvider)` 내부에서 `stateGenerator.generate(OAuthPurpose.LOGIN, null)`을 호출하도록 최소 수정하고, `OAuthAuthorizationController.authorize(...)`는 변경 없이 기존 302 회귀 테스트가 그대로 통과하는지 확인한다.
- [ ] 컴파일이 깨지는 기존 `OAuthStateGeneratorTest` 등 `generate()` 호출부를 새 시그니처로 갱신한다.

## Task 2: `reauth_tokens` 스키마와 `AuthService.reauthenticate` 발급 트랜잭션

- [ ] `V5__create_reauth_tokens_table.sql`, `ReauthToken` 엔티티, `ReauthTokenRepository`, `ReauthTokenGenerator`를 실패 테스트로 먼저 고정한다.
- [ ] `AuthService` 생성자에 `ReauthTokenRepository`·`ReauthTokenGenerator`를 추가하고 기존 `AuthServiceTest`의 모든 생성 호출부를 갱신한다.
- [ ] `AuthService.reauthenticate(userId, provider, oauthUser)`를 구현한다: 동일 회원+동일 provider/providerUserId 성공, 다른 회원 거부, 미연결 provider 거부, 존재하지 않는 userId 거부 — 전부 403 `REAUTHENTICATION_FAILED`. `userRepository.save`·`socialAccountRepository.save`·`accountService` 어느 것도 호출하지 않음을 Mockito `verifyNoInteractions`류로 확인한다.
- [ ] `@DataJpaTest`로 `reauth_tokens` 저장과 `token_hash` UNIQUE·`user_id` FK를 검증한다.

## Task 3: callback purpose 분기와 reauth 응답 계약

- [ ] `OAuthCallbackService`에 `stateGenerator.verify(...)` 호출과 purpose 분기를 추가한다: LOGIN은 기존 `authService.oauthLogin(...)` 그대로, REAUTH는 `authService.reauthenticate(...)`로 위임한다.
- [ ] `OAuthCallbackController`·`OAuthCallbackService`의 반환 타입을 `Object`로 바꾸고 `ReauthTokenResponse`를 추가한다.
- [ ] Fake OAuth로 reauth 성공(200 `reauthToken`)·다른 계정 거부·미연결 provider 거부·state 위·변조 거부(모두 403)를 `@WebMvcTest`/서비스 단위 테스트로 검증한다.
- [ ] 기존 Issue #9/#10 login 회귀 스위트(성공/이메일 없음/계정 연결 필요/state 오류/공급자 오류)가 그대로 통과하는지 확인한다.
- [ ] MySQL 통합 테스트로 reauth 성공 전후 User/SocialAccount/Account 행·값과 시드머니가 불변임을, `reauth_tokens`에 해시 1행만 추가됨을 검증한다.

## Task 4: `purpose=reauth` authorize 엔드포인트와 Security 인증 분기

- [ ] `OAuthAuthorizationService.authorizeForReauth(rawProvider, userId)`를 실패 테스트로 먼저 고정한 뒤 구현한다(provider 해석 로직은 기존 `authorize`와 공유).
- [ ] `OAuthAuthorizationController`에 `@GetMapping(params="purpose=reauth")` 메서드를 추가하고, 기존 `authorize(...)`에는 purpose가 blank/`login`이 아니면 400 `VALIDATION_ERROR`를 던지는 방어 검증을 추가한다.
- [ ] `SecurityConfig`에서 `/api/auth/oauth/*/authorize`를 `PUBLIC_GET_PATHS`에서 빼고, purpose가 없거나 `login`인 GET만 허용하는 커스텀 `RequestMatcher`(`AndRequestMatcher` + `AntPathRequestMatcher` + 람다)를 추가한다.
- [ ] Security 통합 테스트로 다음을 확인한다: purpose 없음/`login` + 미인증 → 302(회귀 유지); `purpose=reauth` + 미인증 → 401; `purpose=reauth` + 인증 → 200 `authorizationUri`; `purpose=` 그 외 값 + 인증 → 400.

## Task 5: 전체 회귀·문서 동기화

- [ ] 대상 단위·슬라이스·통합 테스트 전체와 기존 Issue #9/#10 회귀 스위트, Spotless, `.\gradlew.bat build --no-daemon --max-workers=1`을 실행한다.
- [ ] Fake OAuth로 authorize(reauth)→callback(reauth) 전체 흐름과 authorize(login)→callback(login) 전체 흐름을 자동 통합 테스트로 함께 유지한다.
- [ ] `docs/api-routes.md`의 authorize/callback 표를 purpose 분기·`REAUTHENTICATION_FAILED`·인증 규칙 표(조건부 공개 경로) 기준으로 갱신한다.
- [ ] `docs/specs/002-auth-account/tasks.md`에 Issue #53 작업 항목 절을 추가하고 진행 상태를 반영한다.

---

## 완료 체크리스트

- [ ] `purpose` 생략/`login`의 authorize 302 계약과 login callback 전체 흐름이 Issue #9/#10과 동일하게 회귀 없이 통과한다.
- [ ] `purpose=reauth` authorize가 미인증 401, 인증 200(`authorizationUri`)을 반환하고 state가 purpose+userId를 서명해 담는다.
- [ ] reauth callback이 동일 계정 성공(5분 유효·일회용 `reauthToken`), 다른 계정/미연결 provider/state 위·변조 거부(모두 403 `REAUTHENTICATION_FAILED`)를 자동 테스트로 통과한다.
- [ ] reauth 성공·실패 전후 User·SocialAccount·Account·시드머니가 MySQL 통합 테스트로 불변임이 확인된다.
- [ ] `reauth_tokens`가 원문을 저장하지 않고 해시·만료시각·소비 여부만 저장한다(소비 로직 자체는 범위 밖).
- [ ] `docs/api-routes.md`가 실제 Controller 매핑과 일치한다.
- [ ] `./gradlew build`(Spotless·SpotBugs·JaCoCo 포함)가 통과한다.

---

## 미확정·PRD 불일치 (임의로 정하고 보고하는 항목)

이슈 본문·PRD가 세부를 명시하지 않아 이번 계획에서 아래처럼 결정했다. 팀 리뷰에서 다르게 정하면 이 문서를 갱신한다.

1. **state 서명 실패의 오류 코드**: 이슈 지시 #7은 "state 위·변조"를 다른 재인증 실패 사유와 같은 403 `REAUTHENTICATION_FAILED` 그룹으로 명시했지만, 이는 원래 재인증 시나리오를 전제로 한 문구다. 이번 계획은 `purpose=LOGIN`으로 위장한 위조 state까지 포함해 서명 검증 실패를 전부 403으로 통일했다 — PRD에 이 확장 해석이 명시돼 있지는 않다.
2. **userId 회원이 존재하지 않는 극단 케이스**: `reauthenticate`에서 state가 가리키는 `userId`로 회원을 찾지 못하면 403 `REAUTHENTICATION_FAILED`로 처리하기로 했다. 이슈 본문은 "다른 계정/미연결 provider/state 위·변조/만료·재사용 토큰" 4가지만 나열했고 이 경우는 명시하지 않았다.
3. **`OAUTH_STATE_SECRET` 신규 환경변수**: 이슈는 "신규 환경변수 vs 기존 JWT 시크릿 재사용"을 결정하라고만 했다. 기존 `EMAIL_VERIFICATION_SECRET`/`JWT_SECRET` 분리 컨벤션을 근거로 신규 환경변수를 선택했다 — PRD에 이 변수명이 명시돼 있지는 않다.
4. **재인증 경로의 `OAUTH_EMAIL_REQUIRED` 적용 여부**: PRD `AUTH-003`이 이메일 필수 규칙을 login/reauth로 구분하지 않으므로 `validateOAuthUser`(email 필수)를 두 경로에 동일 적용하기로 했다. 재인증은 이메일이 굳이 필요하지 않을 수 있다는 반론이 가능하지만, 이번 계획은 코드 재사용과 PRD 문구를 우선했다.
5. **카카오·네이버 실제 콘솔의 state 길이 제한 (해소됨)**: 서명 포맷 적용 시 state 문자열이 기존 43자에서 약 110자 내외로 늘어난다. `oauth-real` 프로필 + 실제 네이버 계정으로 `purpose=reauth` authorize→callback 전체 흐름을 수동 스모크했고, 늘어난 state가 네이버 실제 서버를 왕복하는 데 문제없음을 확인했다(`reauthToken` 정상 발급, DB에 해시만 저장, `users`/`social_accounts`/`accounts` 행 수 불변). 카카오는 아직 별도 실제 스모크를 수행하지 않았다.
