# Issue #9 OAuth 인가 요청 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:test-driven-development`로 각 작업의 실패 테스트를 먼저 작성한다.

**Goal:** `GET /api/auth/oauth/{provider}/authorize`가 KAKAO·NAVER 인가 URL로 302 응답하고, 암호학적 난수 `state`를 10분 유효 보안 쿠키로 전달하도록 구현한다.

**관련 정본:** GitHub Issue #9, PRD `AUTH-003`, `spec.md`, ADR-0002·0003, `docs/conventions.md`

## 범위와 제외

### 포함

- `{provider}`는 대소문자와 무관하게 `KAKAO`, `NAVER`만 허용한다.
- 지원 provider는 공급자별 인가 URL을 `Location`에 담아 HTTP 302로 응답한다.
- `state`는 `SecureRandom`의 32바이트 난수를 Base64 URL-safe/no-padding 문자열로 생성한다.
- authorize 응답은 동일한 state를 `oauth_state` 쿠키에 저장한다. 쿠키 속성은 `HttpOnly`, `SameSite=Lax`, `Path=/api/auth/oauth/{provider}/callback`, `Max-Age=600`이다.
- prod·`oauth-real`에서는 쿠키의 `Secure=true`를 보장한다. local·test는 HTTP 테스트를 위해 프로퍼티로 `false`를 명시할 수 있으며, 프로퍼티가 누락되면 fail-safe로 `true`를 사용한다.
- Issue #10 callback은 query state와 쿠키 state를 UTF-8 byte 배열로 변환해 `MessageDigest.isEqual`로 비교하고, 성공·실패와 무관하게 같은 Path의 `Max-Age=0` 쿠키를 응답하여 즉시 소비한다는 계약을 따른다.
- local·test는 실제 키 없이 `FakeOAuthAuthorizationProvider`, prod 및 `oauth-real` 프로필은 카카오·네이버 실제 구현을 사용한다.

### 제외

- callback Controller, 인가 코드의 토큰 교환, 사용자 프로필 조회
- callback의 state 비교·쿠키 만료 구현
- 회원·소셜 계정·계좌 저장, 기존 이메일 충돌 처리, FinPlay JWT 발급
- Client Secret 사용과 실제 카카오·네이버 외부 연동 스모크 테스트(모두 Issue #10)

## 계약과 구성

### HTTP 계약

| 입력 | 결과 |
|---|---|
| `GET /api/auth/oauth/kakao/authorize` | 카카오 인가 URL을 `Location`에 담은 302 |
| `GET /api/auth/oauth/naver/authorize` | 네이버 인가 URL을 `Location`에 담은 302 |
| 미지원 provider | 400 `VALIDATION_ERROR`, 기존 공통 오류 본문 |

- Controller는 raw path variable을 service에 전달하고, service 결과의 URI와 state로 302 `Location` 및 보안 쿠키를 만든다.
- service가 provider 해석, state 생성, 활성 provider 선택과 URI 생성을 조합하고 `OAuthAuthorizationResult(URI authorizationUri, String state)`를 반환한다.
- `OAuthAuthorizationProvider` 계약:

```java
boolean supports(OAuthProviderName provider);
URI createAuthorizationUri(OAuthProviderName provider, String state);
```

- authorize 요청은 DB·Redis 등 서버 자원 상태를 변경하지 않는다. 302 응답에 브라우저 보안 쿠키만 설정하므로 callback CSRF 검증에 필요한 상태를 클라이언트 경계에 한정한다.

### 실제 provider 인가 URL

- 카카오: `https://kauth.kakao.com/oauth/authorize`
  - `response_type=code`, `client_id`, `redirect_uri`, `state`, `scope=account_email`
- 네이버: `https://nid.naver.com/oauth2.0/authorize`
  - `response_type=code`, `client_id`, `redirect_uri`, `state`
  - 네이버 공식 명세상 `scope`는 전송하지 않는다.
- URI는 `UriComponentsBuilder`로 구성·인코딩하며 문자열 이어붙이기를 하지 않는다.
- Fake 구현은 provider별 callback URI에 `code=fake-code`와 같은 state를 넣은 URI를 반환한다. callback을 따라가거나 처리하는 기능은 이 Issue에 포함하지 않는다.

### 빈과 환경변수

- `KakaoOAuthAuthorizationProvider`, `NaverOAuthAuthorizationProvider`: `prod | oauth-real`
- `FakeOAuthAuthorizationProvider`: `!prod & !oauth-real`, KAKAO·NAVER 모두 지원
- 환경변수 계약은 `.env.example`에 이름만 기록한다.

| 환경변수 | Issue #9 사용 |
|---|---|
| `KAKAO_CLIENT_ID` | 카카오 `client_id` |
| `KAKAO_REDIRECT_URI` | 카카오 `redirect_uri` |
| `NAVER_CLIENT_ID` | 네이버 `client_id` |
| `NAVER_REDIRECT_URI` | 네이버 `redirect_uri` |
| `OAUTH_STATE_COOKIE_SECURE` | 선택값. 기본 `true`; local·test HTTP 환경에서만 `false` |
| `KAKAO_CLIENT_SECRET`, `NAVER_CLIENT_SECRET` | Issue #10 전용, 이번 구현에서 바인딩·사용하지 않음 |

실제 값과 `C:\Users\user\Desktop\oauth.txt` 내용은 코드·설정·문서·테스트 fixture에 기록하지 않는다.

`oauth.state-cookie-secure`는 `${OAUTH_STATE_COOKIE_SECURE:true}`로 바인딩해 누락 시 항상 `Secure=true`가 되게 한다. local·test에서 `false`를 사용하는 테스트는 프로퍼티를 명시하여 운영 기본값을 약화하지 않는다.

## TDD 작업 항목

### 1. Provider 계약과 프로필별 URI 생성

- [x] `OAuthProviderName`, `OAuthAuthorizationProvider`, 카카오·네이버·Fake 구현 테스트를 작성한다.
- [x] 카카오는 host/path 및 `response_type`, `client_id`, `redirect_uri`, `state`, `scope=account_email`을 검증한다.
- [x] 네이버는 host/path 및 필수 네 파라미터를 검증하고 `scope`가 없음을 검증한다.
- [x] local·test에서 키 없이 Fake 한 개만 활성화되고, `oauth-real`에서 실제 두 구현만 활성화되는지 `ApplicationContextRunner`로 검증한다.
- [x] `.\gradlew.bat test --tests "*OAuthAuthorizationProviderTest" --tests "*OAuthProviderProfileTest" --rerun-tasks`를 통과시킨다.

### 2. State 생성과 10분 보안 쿠키

- [x] 고정/주입 가능한 난수로 길이·URL-safe 형식·연속 호출의 상이성을 검증하는 state 생성기 단위 테스트를 작성한다.
- [x] auth 전용 `OAuthStateCookieFactory` 단위 테스트에서 전달한 state가 쿠키 값과 같고 `HttpOnly`, `SameSite=Lax`, provider별 callback Path, `Max-Age=600`인지 검증한다.
- [x] factory 단위 테스트에서 기본 프로퍼티와 prod·`oauth-real`은 `Secure=true`, 명시적으로 `false`를 준 local·test만 Secure 속성을 생략하는지 검증한다.
- [x] `.\gradlew.bat test --tests "*OAuthStateGeneratorTest" --tests "*OAuthStateCookieFactoryTest" --rerun-tasks`를 통과시킨다.

### 3. Service·Controller·라우트 문서

- [ ] service 단위 테스트에서 KAKAO·NAVER 선택, 생성한 state가 포함된 URI와 결과 반환, 미지원 provider의 `VALIDATION_ERROR`를 먼저 검증한다.
- [ ] `@WebMvcTest`에서 provider별 302 `Location`과 `Set-Cookie`의 최종 결합 계약, 미지원 provider의 400 공통 오류 필드(`code`, `message`, `requestId`)를 먼저 검증한다.
- [ ] Controller/service 최소 구현과 동시에 `.env.example`, `docs/api-routes.md`를 갱신한다.
- [ ] `.\gradlew.bat spotlessApply`, 대상 테스트, `.\gradlew.bat build` 순서로 검증한다. Fake/자동 테스트 통과와 실제 OAuth 외부 스모크 미실행을 구분해 보고한다.
