# Issue #5 로그인 및 JWT 발급 API 구현 계획

> **For agentic workers:** 각 Task는 실패 테스트를 먼저 작성한 뒤 구현한다. Step은 체크박스(`- [ ]`)로 추적한다.

**Goal:** `POST /api/auth/login`이 이메일·비밀번호를 검증해 Access/Refresh 토큰 쌍을 반환하고, Spring Security 기반 Bearer 인증이 보호 경로를 지키며 401·403을 공통 오류 포맷으로 응답하도록 구현한다.

**관련 정본:** GitHub Issue #5, PRD `AUTH-002`·§5 공통 오류표, `spec.md`, ADR-0002·0003·0004, `docs/conventions.md`

**Architecture:** `AuthController → AuthService → auth repository` 흐름을 유지한다. Controller는 HTTP 계약만, `AuthService`가 자격증명 검증과 토큰 발급 트랜잭션을 소유하고, Bearer 인증은 Spring Security 필터체인에서 `JwtTokenProvider`가 파싱한 결과로 `SecurityContext`를 채우는 방식으로 분리한다.

**Tech Stack:** Java 17, Spring Boot 4.1, Spring Security(신규), Spring Data JPA, Bean Validation, JJWT 0.13.0, MySQL 8.4 Testcontainers, JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

---

## 범위와 제외

### 포함

- `POST /api/auth/login` — 이메일·비밀번호 검증 후 Access/Refresh 토큰 쌍 반환.
- Refresh Token은 원문 대신 SHA-256 해시만 `refresh_tokens`에 저장한다.
- `JwtTokenProvider`의 검증 확장 — 서명 검증, 만료 판정, 변조 탐지, `tokenType` 판별.
- Spring Security 기반 Bearer 인증 — `SecurityFilterChain`, 커스텀 `JwtAuthenticationFilter`, 공개 경로 화이트리스트.
- 401 `UNAUTHORIZED` / 403 `FORBIDDEN`을 001 공통 `ErrorResponse` 포맷으로 응답하는 `AuthenticationEntryPoint`·`AccessDeniedHandler`.
- Spring Security 도입으로 영향받는 기존 `@WebMvcTest` 슬라이스의 정리.

### 제외

- `POST /api/auth/refresh` (Issue #6), `POST /api/auth/logout` (Issue #7), `GET /api/auth/me` (Issue #8) — **이 세 API는 만들지 않는다.**
- Refresh Token 회전·폐기 로직과 재사용 거부(폐기는 Issue #6·#7).
- OAuth callback(Issue #10), 투자일기·AI·랭킹·알림·지정가·Kafka·동시성 제어.
- 실제 보호 API 컨트롤러 — 이번 이슈에는 인증이 필요한 업무 API가 없다. 인증 계약은 테스트 전용 컨트롤러로 검증한다(Task 3).

---

## 설계 결정

### D1. Bearer 인증 기반은 Spring Security로 구현한다 (확정)

`build.gradle`에 `spring-boot-starter-security`를 추가하고 `SecurityFilterChain` + 커스텀 `JwtAuthenticationFilter`로 구성한다. 순수 서블릿 필터 방식은 채택하지 않는다.

- **근거** — 인증 실패(401)와 인가 실패(403)의 분기, 익명/인증 주체 구분, `SecurityContext` 기반 사용자 주입은 Spring Security의 `ExceptionTranslationFilter`·`AuthorizationFilter`가 이미 제공한다. 직접 필터로 구현하면 같은 분기를 손으로 재현해야 하고, 소유권 검증(PRD `COM-003` 403)이 붙는 시점에 다시 갈아엎게 된다.
- **기존 `spring-security-crypto`와의 관계** — `spring-boot-starter-security`가 crypto를 전이 의존으로 포함한다. 다만 `Sha256BcryptPasswordEncoder`·`AuthCryptoConfig`가 이 라이브러리를 직접 사용하므로 명시적 의존 선언은 그대로 유지한다(직접 쓰는 의존은 명시한다). 버전은 Spring Boot BOM이 관리하므로 충돌하지 않는다.
- **기본 자동설정 무력화** — starter를 추가하는 순간 `SecurityAutoConfiguration`이 "모든 경로 인증 + HTTP Basic + 폼 로그인 + 자동 생성 비밀번호"를 켠다. `SecurityFilterChain` 빈을 직접 정의해 이를 대체한다. 정의하지 않으면 기존 공개 API가 전부 401이 된다.
- **향후 소유권 검증 확장** — `SecurityContext`의 principal에 `userId`를 담아두면 Issue #8 이후 `@AuthenticationPrincipal`로 인증 사용자를 주입받고, 게시물·일기 소유권 위반을 403 `FORBIDDEN`으로 내려보내는 경로가 이미 열려 있다.

### D2. 필터 순서 — `RequestIdFilter`가 Security 체인보다 먼저 실행되어야 한다

`RequestIdFilter`는 `@Component`로만 등록되어 있어 order가 `LOWEST_PRECEDENCE`다. Spring Security의 `springSecurityFilterChain`은 `SecurityProperties.DEFAULT_FILTER_ORDER`(-100)로 등록되므로 **Security가 먼저 실행된다.** 이 상태로 두면 401 응답을 만드는 `AuthenticationEntryPoint` 시점에 MDC의 `requestId`가 비어 있고, 응답의 `X-Request-Id` 헤더도 없다 — 공통 오류 포맷 계약이 깨진다.

따라서 `RequestIdFilter`에 `@Order(SecurityProperties.DEFAULT_FILTER_ORDER - 1)`를 부여한다. 이 한 줄이 이번 이슈에서 001 공통 오류 인프라를 건드리는 유일한 변경이며, 회귀 여부는 401 응답의 `requestId`·헤더 단언으로 고정한다.

### D3. 401·403 본문은 핸들러가 직접 직렬화한다

필터 체인에서 발생한 인증·인가 실패는 `@RestControllerAdvice`(`GlobalExceptionHandler`)에 도달하지 않는다. 따라서 `AuthenticationEntryPoint`·`AccessDeniedHandler`가 `ErrorResponse.of(ErrorCode.UNAUTHORIZED, ...)` / `ErrorCode.FORBIDDEN`을 만들어 직접 쓴다. 직렬화는 Spring Boot 4가 제공하는 `tools.jackson.databind.ObjectMapper` 빈을 생성자 주입해 사용한다(프로젝트가 이미 Jackson 3 API를 쓴다). 응답 코드·메시지·`requestId` 구성은 `GlobalExceptionHandler.build`와 동일한 값이어야 한다.

### D4. 잘못된 토큰은 필터에서 던지지 않고 인증을 비운 채 통과시킨다

`JwtAuthenticationFilter`는 `Authorization: Bearer <token>`이 있을 때만 파싱을 시도하고, 파싱 실패(만료·변조·`tokenType != ACCESS`·subject 파싱 불가)면 `SecurityContext`를 채우지 않고 체인을 그대로 진행한다. 보호 경로라면 `AuthorizationFilter`가 거부하고 `ExceptionTranslationFilter`가 익명 주체를 판별해 `AuthenticationEntryPoint`(401)로 보낸다. 공개 경로라면 토큰이 잘못되어도 정상 처리된다 — 이것이 의도한 동작이다.

이 설계 덕분에 `JwtTokenProvider`는 예외 대신 `Optional`을 반환하면 되고, 필터에 인증 실패 응답 조립 책임이 새지 않는다.

### D5. Refresh Token 토큰으로는 보호 API에 접근할 수 없다

`tokenType` 클레임이 `ACCESS`가 아니면 파싱을 실패로 처리한다. Refresh Token은 Issue #6의 재발급 엔드포인트에서만 쓰인다.

### D6. signup의 토큰 발급·해시 저장 로직은 `AuthService` private 메서드로 추출해 재사용한다

`AuthService.signup`의 마지막 3줄(`jwtTokenProvider.issue` → `sha256(refreshToken)` → `refreshTokenRepository.save` → `TokenResponse.from`)은 login과 완전히 동일하다. 새 컴포넌트나 공통 클래스를 만들지 않고 **같은 클래스의 private 메서드 `issueTokenPair(User user, LocalDateTime now)`로 추출**해 두 경로가 공유한다.

- 근거 — conventions의 "공통화는 세 번째 중복이 보이고 책임이 명확할 때만 검토"에 따라 새 클래스(`TokenIssuer` 같은 이름)는 만들지 않는다. 동시에 Refresh Token 해시 저장은 절대 빠지면 안 되는 보안 규칙이라 두 경로에 복붙으로 두지 않는다. 같은 클래스 안의 private 추출이 두 요구를 모두 만족한다.
- `sha256` private 메서드도 그대로 재사용한다.

### D7. 로그인 실패는 원인과 무관하게 동일한 401이다

다음 세 경우를 모두 `BusinessException(ErrorCode.UNAUTHORIZED)`로 처리한다 — 응답 코드·메시지·상태가 완전히 같아야 한다.

1. 해당 이메일의 회원이 없다.
2. 회원은 있으나 `passwordHash`가 `null`이다(소셜 전용 가입자).
3. 비밀번호가 일치하지 않는다.

타이밍 차이를 이용한 계정 열거 방어(존재하지 않는 이메일에도 더미 해시를 검증해 응답 시간을 맞추는 기법)는 PRD·spec에 요구가 없으므로 이번 범위에서 구현하지 않는다.

### D8. 성공 응답은 200이며 기존 `TokenResponse`를 재사용한다

conventions의 "조회·수정 200, 생성 201" 기준에서 로그인은 리소스 생성이 아니다(회원가입만 201). 응답 본문은 signup과 동일한 필드 집합이므로 `TokenResponse`를 그대로 쓴다.

### D9. 로그인 시 기존 Refresh Token을 폐기하지 않는다

PRD `AUTH-002`는 "로그아웃 또는 재발급 시 이전 Refresh Token을 폐기한다"까지만 규정한다. 로그인마다 기존 토큰을 지우면 다중 기기 로그인이 끊기는데, 그런 요구는 PRD에 없다. 따라서 로그인은 `refresh_tokens`에 행을 **추가**만 한다. 폐기 규칙은 Issue #6·#7에서 구현한다.

### D10. 스키마 변경 없음 — 마이그레이션 불필요

- `refresh_tokens`(`user_id`, `token_hash`, `expires_at`, `revoked_at`, `created_at`)는 `V2__create_auth_account_tables.sql`에 이미 존재하고 `idx_refresh_tokens_token_hash` 인덱스도 있다.
- `users.password_hash`도 V2에 존재하며 로그인은 이 컬럼을 읽기만 한다.
- 로그인·Bearer 인증은 새 테이블·컬럼·인덱스를 요구하지 않는다. 따라서 **`V3__*.sql`을 만들지 않는다.** 병합된 V2는 수정하지 않는다(ADR-0004).

### D11. 파일 배치

| 파일 | 위치 근거 |
|---|---|
| `SecurityConfig`, `RestAuthenticationEntryPoint`, `RestAccessDeniedHandler` | `auth/config/` — 인증 도메인의 설정이다. `common`은 "전역 예외·오류 응답·공통 설정"만 두는 곳이고(conventions), 기존 `AuthCryptoConfig`가 이미 `auth/config`에 있다. |
| `JwtAuthenticationFilter`, `AuthenticatedUser` | `auth/token/` — 토큰 해석 책임이므로 `JwtTokenProvider`와 같은 패키지에 둔다. |
| `LoginRequest` | `auth/dto/request/` — 기존 `SignupRequest`와 동일 규칙. |

---

## HTTP 계약

### 로그인

| 항목 | 내용 |
|---|---|
| Method / Path | `POST /api/auth/login` |
| 요청 | `{"email":"user@finplay.com","password":"password123"}` |
| 성공 | 200 `{"accessToken":"<JWT>","refreshToken":"<JWT>","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}` |
| 입력 오류 | 400 `VALIDATION_ERROR` (공통 포맷) |
| 자격증명 오류 | 401 `UNAUTHORIZED` (공통 포맷, 원인 불문 동일) |

`LoginRequest` 필드 명세.

| 필드 | 필수 | 검증 | 비고 |
|---|---|---|---|
| `email` | 필수 | `@NotBlank`, `@Email`, `@Size(max = 255)` | 메시지는 한국어 + 마침표 (conventions) |
| `password` | 필수 | `@NotBlank`, `@Size(max = 100)` | **`min = 8`을 붙이지 않는다** — 로그인은 비밀번호 정책 검사가 아니라 자격증명 대조다. 최소 길이를 걸면 짧은 입력만 400, 나머지는 401로 갈려 응답이 저장된 자격증명에 대한 힌트가 된다. 최대 길이는 과대 입력 차단용으로 유지한다. |

### 인증 규칙

| 구분 | 경로 |
|---|---|
| 공개 | `POST /api/auth/signup`, `POST /api/auth/login`, `POST /api/auth/email-verifications`, `POST /api/auth/email-verifications/confirm`, `GET /api/auth/oauth/*/authorize` |
| 공개(시스템) | `GET /actuator/health`, `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**` |
| 보호 | 위 목록을 제외한 전부 (`anyRequest().authenticated()`) — `/actuator/health` 외 actuator 엔드포인트 포함 |

- 화이트리스트는 **현재 존재하는 컨트롤러 경로만** 등록한다. `POST /api/auth/refresh`(#6), OAuth callback(#10)은 해당 이슈에서 자기 경로를 추가한다.
- 보호 경로 접근 시 토큰 없음·만료·변조·`REFRESH` 타입은 모두 401 `UNAUTHORIZED`.
- 인증은 되었으나 권한이 없는 경우는 403 `FORBIDDEN`(핸들러만 준비하고, 실제 권한 규칙은 후속 이슈).

### `SecurityFilterChain` 구성

```
csrf().disable()
sessionManagement(STATELESS)
formLogin().disable(), httpBasic().disable()
authorizeHttpRequests(공개 경로 permitAll → anyRequest().authenticated())
addFilterBefore(JwtAuthenticationFilter, UsernamePasswordAuthenticationFilter)
exceptionHandling(authenticationEntryPoint, accessDeniedHandler)
```

---

## 보호 API 인증 검증 방법 (보호 컨트롤러가 없는 상태에서)

이번 이슈에는 인증이 필요한 업무 API가 없다. 따라서 `GlobalExceptionHandlerTest`가 쓰는 **테스트 전용 컨트롤러 패턴**을 그대로 따른다.

```java
@WebMvcTest(controllers = SecurityConfigTest.ProtectedTestController.class)
@Import({SecurityConfigTest.ProtectedTestController.class, SecurityConfig.class, JwtAuthenticationFilter.class})
```

- 테스트 전용 컨트롤러는 `SecurityConfigTest`의 static inner class로 두고 `/test/protected`를 노출한다. **`src/main`에 테스트용 컨트롤러를 만들지 않는다.**
- `/test/protected`는 화이트리스트에 없으므로 `anyRequest().authenticated()`에 걸린다 — 프로덕션 화이트리스트를 테스트 편의로 넓히지 않는다.
- 토큰 생성은 실제 `JwtTokenProvider`(고정 `Clock` + 테스트 시크릿)로 만들고 `@TestConfiguration`으로 제공한다. 만료 토큰은 `Clock`을 앞당겨, 변조 토큰은 서명 부분 문자열을 바꿔 만든다.

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/auth/token/AuthenticatedUser.java` — 인증 주체(userId, role) record.
- `src/main/java/com/finplay/api/auth/token/JwtAuthenticationFilter.java` — Bearer 헤더 파싱 후 `SecurityContext` 설정.
- `src/main/java/com/finplay/api/auth/config/SecurityConfig.java` — `SecurityFilterChain` 구성.
- `src/main/java/com/finplay/api/auth/config/RestAuthenticationEntryPoint.java` — 401 공통 포맷 응답.
- `src/main/java/com/finplay/api/auth/config/RestAccessDeniedHandler.java` — 403 공통 포맷 응답.
- `src/main/java/com/finplay/api/auth/dto/request/LoginRequest.java` — 로그인 요청 검증.

### Production files to modify

- `build.gradle` — `spring-boot-starter-security` 추가.
- `src/main/java/com/finplay/api/auth/token/JwtTokenProvider.java` — `parseAccessToken` 추가.
- `src/main/java/com/finplay/api/auth/service/AuthService.java` — `login` 추가, 토큰 발급 private 추출.
- `src/main/java/com/finplay/api/auth/controller/AuthController.java` — `POST /login` 추가.
- `src/main/java/com/finplay/api/common/RequestIdFilter.java` — `@Order` 부여 (D2).

### Test files to create

- `src/test/java/com/finplay/api/auth/config/SecurityConfigTest.java`
- `src/test/java/com/finplay/api/auth/service/LoginIntegrationTest.java`

### Test files to modify

- `src/test/java/com/finplay/api/auth/token/JwtTokenProviderTest.java`
- `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java`
- `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java`
- `src/test/java/com/finplay/api/auth/controller/EmailVerificationControllerTest.java`
- `src/test/java/com/finplay/api/auth/controller/OAuthAuthorizationControllerTest.java`
- `src/test/java/com/finplay/api/common/GlobalExceptionHandlerTest.java`

### Documentation files to modify

- `docs/api-routes.md`
- `docs/specs/002-auth-account/tasks.md`
- `docs/specs/002-auth-account/run-log.md`

---

## Task 1: JWT 검증 확장

**Files**
- Create: `src/main/java/com/finplay/api/auth/token/AuthenticatedUser.java`
- Modify: `src/main/java/com/finplay/api/auth/token/JwtTokenProvider.java`
- Modify: `src/test/java/com/finplay/api/auth/token/JwtTokenProviderTest.java`

**Interfaces**
- Produces: `Optional<AuthenticatedUser> JwtTokenProvider.parseAccessToken(String token)`
- Produces: `record AuthenticatedUser(Long userId, String role)`

- [x] **Step 1: 실패 테스트를 작성한다**

`JwtTokenProviderTest`에 다음 케이스를 추가한다. 기존 고정 `Clock`·테스트 시크릿 설정을 그대로 쓴다.

```java
parseAccessTokenReturnsUserIdAndRoleForValidAccessToken();
parseAccessTokenReturnsEmptyForRefreshToken();
parseAccessTokenReturnsEmptyForExpiredToken();      // 만료 시각 이후로 Clock을 옮긴 provider로 파싱
parseAccessTokenReturnsEmptyForTamperedSignature(); // 서명 세그먼트 1글자 변경
parseAccessTokenReturnsEmptyForOtherSecret();       // 다른 시크릿으로 서명한 토큰
parseAccessTokenReturnsEmptyForMalformedToken();    // "not-a-jwt", 빈 문자열
```

만료 검증은 반드시 주입된 `Clock`을 기준으로 한다 — JJWT 파서에 `.clock(() -> Date.from(clock.instant()))`를 지정해 시스템 시계에 의존하지 않게 한다. 이걸 놓치면 만료 테스트가 실제 시간에 좌우된다.

- [x] **Step 2: 실패를 확인한다**

```powershell
.\gradlew.bat test --tests "*JwtTokenProviderTest"
```

Expected: `parseAccessToken`이 없어 컴파일 FAIL.

- [x] **Step 3: 구현한다**

`JwtTokenProvider.parseAccessToken`은 서명 검증 → 만료 검증 → `tokenType == "ACCESS"` 확인 → `subject`를 `Long`으로 변환 순서로 처리하고, 어느 단계에서든 `JwtException`·`IllegalArgumentException`·`NumberFormatException`이 나면 `Optional.empty()`를 반환한다. 예외를 밖으로 던지지 않는다(D4). 토큰 원문은 로그에 남기지 않는다.

- [x] **Step 4: 통과시킨다**

```powershell
.\gradlew.bat test --tests "*JwtTokenProviderTest"
```

Expected: PASS.

- [x] **Step 5: 커밋한다**

```powershell
git add src/main/java/com/finplay/api/auth/token src/test/java/com/finplay/api/auth/token
git commit -m "feat: Access Token 검증과 인증 주체 파싱 추가"
```

---

## Task 2: Spring Security 도입과 공통 401·403 응답

**Files**
- Modify: `build.gradle`
- Create: `src/main/java/com/finplay/api/auth/token/JwtAuthenticationFilter.java`
- Create: `src/main/java/com/finplay/api/auth/config/SecurityConfig.java`
- Create: `src/main/java/com/finplay/api/auth/config/RestAuthenticationEntryPoint.java`
- Create: `src/main/java/com/finplay/api/auth/config/RestAccessDeniedHandler.java`
- Modify: `src/main/java/com/finplay/api/common/RequestIdFilter.java`
- Modify: 기존 `@WebMvcTest` 4종 (`AuthControllerTest`, `EmailVerificationControllerTest`, `OAuthAuthorizationControllerTest`, `GlobalExceptionHandlerTest`)

**Interfaces**
- Consumes: `JwtTokenProvider.parseAccessToken`, `ErrorResponse`, `ErrorCode.UNAUTHORIZED`·`FORBIDDEN`
- Produces: `SecurityFilterChain securityFilterChain(HttpSecurity http)`

> 이 Task는 의존성 추가로 기존 슬라이스 테스트가 깨지므로, 프로덕션 구성과 기존 테스트 정리를 **한 커밋**으로 묶는다. 중간 커밋에서 빌드가 깨지지 않게 하기 위한 의도적 묶음이다.

- [x] **Step 1: 의존성을 추가하고 깨지는 범위를 먼저 확인한다**

```groovy
implementation 'org.springframework.boot:spring-boot-starter-security'
```

```powershell
.\gradlew.bat test
```

Expected: `SecurityFilterChain` 빈이 없어 기본 자동설정이 모든 요청을 막고, 기존 컨트롤러 슬라이스가 401로 FAIL. **실제 실패 목록을 기록한다** — 아래 Step 5의 조정 대상은 추측이 아니라 이 출력으로 확정한다.

- [x] **Step 2: 필터체인 계약 테스트를 작성한다**

`SecurityConfigTest`에 다음을 추가한다(테스트 전용 컨트롤러 패턴은 위 "보호 API 인증 검증 방법" 참조).

```java
rejectsProtectedPathWithoutTokenAsUnauthorized();     // 401, code=UNAUTHORIZED, requestId·X-Request-Id 존재
rejectsProtectedPathWithExpiredTokenAsUnauthorized();
rejectsProtectedPathWithTamperedTokenAsUnauthorized();
rejectsProtectedPathWithRefreshTokenAsUnauthorized();
allowsProtectedPathWithValidAccessToken();            // 200, principal userId 확인
allowsPublicAuthPathsWithoutToken();                  // /api/auth/login, /api/auth/signup 등
```

401 응답은 반드시 `$.error.code`, `$.error.message`, `$.error.requestId`를 모두 단언하고, `X-Request-Id` 헤더 값과 본문 `requestId`가 같은지도 단언한다(D2 회귀 방지).

- [x] **Step 3: 실패를 확인한다**

```powershell
.\gradlew.bat test --tests "*SecurityConfigTest"
```

Expected: `SecurityConfig`가 없어 FAIL.

- [x] **Step 4: 프로덕션 구성을 구현한다**

- `JwtAuthenticationFilter`는 `OncePerRequestFilter`를 확장하고, `Authorization` 헤더가 `Bearer `로 시작할 때만 `parseAccessToken`을 호출한다. 성공 시 `UsernamePasswordAuthenticationToken(authenticatedUser, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)))`을 `SecurityContextHolder`에 넣는다. 실패 시 아무것도 하지 않고 체인을 이어간다.
- `SecurityConfig`는 위 "`SecurityFilterChain` 구성"과 "인증 규칙"의 화이트리스트를 그대로 구현한다. 화이트리스트 경로는 `private static final String[] PUBLIC_PATHS`처럼 상수로 뺀다(conventions — 매직 문자열 금지).
- `RestAuthenticationEntryPoint`·`RestAccessDeniedHandler`는 상태코드·`Content-Type: application/json`·UTF-8을 설정하고 `ErrorResponse.of(errorCode, errorCode.getDefaultMessage(), MDC.get(RequestIdFilter.REQUEST_ID_MDC_KEY))`를 직렬화한다. 원인 예외 메시지를 본문에 노출하지 않는다.
- `RequestIdFilter`에 `@Order(SecurityProperties.DEFAULT_FILTER_ORDER - 1)`를 붙인다. Boot 4의 `SecurityProperties` import 경로는 컴파일로 확인한다.

- [x] **Step 5: 기존 슬라이스 테스트를 정리한다**

| 테스트 | 조치 | 근거 |
|---|---|---|
| `AuthControllerTest`, `EmailVerificationControllerTest`, `OAuthAuthorizationControllerTest` | `@Import(SecurityConfig.class)` + 필터가 요구하는 `JwtTokenProvider`를 `@MockitoBean`으로 제공 | 대상 경로가 공개 화이트리스트에 있으므로 실제 체인을 태워 화이트리스트 계약까지 함께 검증한다 |
| `GlobalExceptionHandlerTest` | `@WebMvcTest(..., excludeAutoConfiguration = {SecurityAutoConfiguration.class, SecurityFilterAutoConfiguration.class})` | `/test/**`는 프로덕션 화이트리스트에 없고 넣어서도 안 된다. `addFilters = false`는 `RequestIdFilter`까지 꺼버려 기존 requestId 단언이 깨지므로 쓰지 않는다 |

`/test/**`를 `SecurityConfig`의 공개 경로에 추가하는 해법은 **금지한다** — 테스트 편의로 운영 인증 경계를 넓히는 변경이다.

- [x] **Step 6: 통과시킨다**

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat test
```

Expected: `SecurityConfigTest` 포함 전체 PASS.

- [x] **Step 7: 커밋한다**

```powershell
git add build.gradle src/main/java/com/finplay/api/auth/config src/main/java/com/finplay/api/auth/token/JwtAuthenticationFilter.java src/main/java/com/finplay/api/common/RequestIdFilter.java src/test/java/com/finplay/api/auth src/test/java/com/finplay/api/common/GlobalExceptionHandlerTest.java
git commit -m "feat: Spring Security 기반 Bearer 인증과 401/403 공통 응답 추가"
```

---

## Task 3: 보호 경로 인증 계약 확장 검증

**Files**
- Modify: `src/test/java/com/finplay/api/auth/config/SecurityConfigTest.java`

**Interfaces**
- Verifies: 화이트리스트·보호 경로 경계와 인증 주체 주입.

> Task 2에서 골격을 만들었으므로 여기서는 **경계 케이스만** 채운다. 테스트 전용 변경이라 별도 커밋으로 둔다.

- [x] **Step 1: 경계 케이스를 추가한다**

```java
rejectsBearerHeaderWithoutTokenValue();          // "Bearer", "Bearer " → 401
rejectsNonBearerAuthorizationScheme();           // "Basic ..." → 401
ignoresInvalidTokenOnPublicPath();               // 잘못된 토큰이어도 공개 경로는 정상 처리
exposesUserIdAndRoleAsAuthenticationPrincipal(); // principal이 AuthenticatedUser이고 authority가 ROLE_USER
rejectsNonHealthActuatorEndpointWithoutToken();  // /actuator/health만 공개임을 고정
```

- [x] **Step 2: 실행한다**

```powershell
.\gradlew.bat test --tests "*SecurityConfigTest"
```

Expected: PASS. 실패하면 프로덕션 결함인지 테스트 기대가 계획과 다른지 구분해 보고한다.

- [x] **Step 3: 커밋한다**

```powershell
git add src/test/java/com/finplay/api/auth/config/SecurityConfigTest.java
git commit -m "test: Bearer 인증 경계 케이스 검증 추가"
```

---

## Task 4: 로그인 서비스

**Files**
- Modify: `src/main/java/com/finplay/api/auth/service/AuthService.java`
- Modify: `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java`

**Interfaces**
- Consumes: `UserRepository.findByEmail`, `PasswordEncoder.matches`, `JwtTokenProvider.issue`, `RefreshTokenRepository.save`
- Produces: `TokenResponse AuthService.login(String email, String password)`

- [x] **Step 1: 실패 테스트를 작성한다**

`AuthServiceTest`에 추가한다.

```java
loginReturnsTokenPairAndPersistsHashedRefreshToken();
loginFailsWithUnauthorizedWhenEmailNotFound();
loginFailsWithUnauthorizedWhenPasswordDoesNotMatch();
loginFailsWithUnauthorizedWhenUserHasNoPasswordHash();   // 소셜 전용 회원
loginDoesNotRevokeExistingRefreshTokens();               // 기존 토큰 삭제·폐기 호출이 없음 (D9)
```

정상 케이스는 저장된 `RefreshToken.getTokenHash()`가 응답 `refreshToken` 원문과 다르고 테스트가 계산한 SHA-256 hex와 같은지 단언한다. 실패 3종은 모두 `ErrorCode.UNAUTHORIZED`인지 단언한다(D7).

- [x] **Step 2: 실패를 확인한다**

```powershell
.\gradlew.bat test --tests "*AuthServiceTest"
```

Expected: `login`이 없어 FAIL.

- [x] **Step 3: 구현한다**

```java
@Transactional
public TokenResponse login(String email, String password) {
    LocalDateTime now = LocalDateTime.now(clock);
    User user = userRepository.findByEmail(email)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    if (user.getPasswordHash() == null
        || !passwordEncoder.matches(password, user.getPasswordHash())) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
    return issueTokenPair(user, now);
}
```

`issueTokenPair(User user, LocalDateTime now)`는 기존 `signup` 말미의 발급·해시 저장 블록을 그대로 옮긴 private 메서드이며, `signup`도 이 메서드를 호출하도록 바꾼다(D6). 이 리팩터링으로 signup 동작이 바뀌면 안 되므로 **기존 signup 테스트가 수정 없이 통과**해야 한다.

- [x] **Step 4: 통과시킨다**

```powershell
.\gradlew.bat test --tests "*AuthServiceTest" --tests "*SignupIntegrationTest"
```

Expected: PASS (signup 회귀 없음 포함).

- [x] **Step 5: 커밋한다**

```powershell
git add src/main/java/com/finplay/api/auth/service/AuthService.java src/test/java/com/finplay/api/auth/service/AuthServiceTest.java
git commit -m "feat: 이메일 로그인 자격증명 검증과 토큰 발급 추가"
```

---

## Task 5: 로그인 API 계약

**Files**
- Create: `src/main/java/com/finplay/api/auth/dto/request/LoginRequest.java`
- Modify: `src/main/java/com/finplay/api/auth/controller/AuthController.java`
- Modify: `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java`

**Interfaces**
- Consumes: `AuthService.login(String, String)`
- Produces: `POST /api/auth/login`

- [x] **Step 1: WebMvc 실패 테스트를 작성한다**

```java
loginReturnsOkWithTokenPair();                  // 200 + 4개 필드 jsonPath 값 검증
loginReturnsBadRequestForInvalidRequest();      // 이메일 누락·형식 오류·256자, 비밀번호 누락·101자
loginReturnsUnauthorizedForInvalidCredentials(); // 서비스의 UNAUTHORIZED → 401 공통 포맷
```

성공 응답은 `mock(TokenResponse.class)`가 아니라 실제 값이 든 `TokenResponse`를 stubbing한다(conventions). 400 케이스에서는 `verifyNoInteractions(authService)`로 서비스가 호출되지 않음을 확인한다.

- [x] **Step 2: 실패를 확인한다**

```powershell
.\gradlew.bat test --tests "*AuthControllerTest"
```

Expected: 매핑과 DTO가 없어 FAIL.

- [x] **Step 3: 구현한다**

`LoginRequest`는 위 "로그인" 표의 검증을 record 컴포넌트에 직접 붙이고 메시지는 한국어 + 마침표로 쓴다. `AuthController`에 `@PostMapping("/login")`을 추가하고 `ResponseEntity.ok(...)`로 200을 반환한다.

- [x] **Step 4: 통과시킨다**

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat test --tests "*AuthControllerTest"
```

Expected: PASS.

- [x] **Step 5: 커밋한다**

```powershell
git add src/main/java/com/finplay/api/auth/controller/AuthController.java src/main/java/com/finplay/api/auth/dto/request/LoginRequest.java src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java
git commit -m "feat: 로그인 API 추가"
```

---

## Task 6: 로그인 통합 검증

**Files**
- Create: `src/test/java/com/finplay/api/auth/service/LoginIntegrationTest.java`

**Interfaces**
- Consumes: 실제 MySQL repository, `AuthService.signup`·`login`, 실제 `JwtTokenProvider`.
- Verifies: 가입한 회원이 실제 DB 자격증명으로 로그인하고 Refresh Token이 해시로만 남는다.

- [x] **Step 1: Testcontainers 통합 테스트를 작성한다**

`@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`를 쓰고, 기존 `SignupIntegrationTest`의 인증번호→가입 준비 흐름(`FakeEmailSender`)을 재사용한다.

```java
signupThenLoginReturnsTokenPairForSameCredentials();
loginPersistsAdditionalRefreshTokenRowAsSha256Hash();  // 행 2개(가입 1 + 로그인 1), 원문 미저장
loginFailsWithUnauthorizedForWrongPassword();
issuedAccessTokenIsAcceptedByJwtTokenProvider();       // parseAccessToken이 가입 회원 id를 돌려준다
```

- [x] **Step 2: 실행한다**

```powershell
.\gradlew.bat test --tests "*LoginIntegrationTest"
```

Expected: Docker와 MySQL 8.4가 있으면 PASS. Docker 불가면 실패 로그를 보존하고 단위·슬라이스 통과와 구분해 보고한다(추정으로 통과 처리 금지).

- [x] **Step 3: 커밋한다**

```powershell
git add src/test/java/com/finplay/api/auth/service/LoginIntegrationTest.java
git commit -m "test: 로그인 통합 검증 추가"
```

---

## Task 7: 문서 동기화와 전체 게이트

**Files**
- Modify: `docs/api-routes.md`, `docs/specs/002-auth-account/tasks.md`, `docs/specs/002-auth-account/run-log.md`

- [x] **Step 1: 라우트 문서를 갱신한다**

라우트 목록에 추가한다.

```markdown
| POST | /api/auth/login | auth | 이메일·비밀번호 검증 후 Access·Refresh 토큰 발급 | 002 AUTH-002 |
```

"로그인" 상세 표(요청·200 응답·400/401)와 "인증 규칙" 절(공개 경로 목록, 보호 경로 기본값, 401·403 공통 포맷)을 추가한다. 후속 이슈가 자기 경로를 화이트리스트에 추가한다는 점도 한 줄 적는다.

- [x] **Step 2: 전체 게이트를 실행한다**

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat build
```

Expected: compile, test, Spotless, SpotBugs, JaCoCo 40% 게이트 모두 PASS. 실패하면 고치고 재실행한다.

- [x] **Step 3: tasks와 run log를 실제 결과로 갱신한다**

`tasks.md`의 "JWT·Security" 항목은 `GET /api/auth/me`(Issue #8)를 포함하므로 **완료로 체크하지 않는다.** 대신 로그인과 Bearer 인증이 이번 이슈에서 끝났고 `/api/auth/me`가 남아 있다는 사실을 항목 옆에 한 줄로 남긴다.

`run-log.md`의 Issue #5 구역에 실행 시각, 명령, 통과 테스트 수, Docker/Testcontainers 여부를 기록한다.

- [x] **Step 4: 최종 diff를 확인한다**

```powershell
git status --short --branch
git diff dev...HEAD --check
git diff --stat dev...HEAD
```

Expected: 범위 밖 파일 변경이 없다. `V3__*.sql`이 생성되지 않았는지도 함께 확인한다(D10).

- [x] **Step 5: 문서 커밋을 만든다**

```powershell
git add docs/agent-mistakes.md docs/api-routes.md docs/specs/002-auth-account/issue-5-plan.md docs/specs/002-auth-account/tasks.md docs/specs/002-auth-account/run-log.md
git commit -m "docs: 이슈 5 구현 결과 동기화"
```

---

## 완료 조건

- [x] 정상 로그인이 Access/Refresh 토큰 쌍을 200으로 반환한다.
- [x] 잘못된 이메일·비밀번호·소셜 전용 회원이 **동일한** 401 `UNAUTHORIZED` 공통 포맷으로 응답된다.
- [x] Method/Path가 `POST /api/auth/login` 계약과 일치한다.
- [x] 만료·변조·`REFRESH` 타입 토큰으로 보호 경로에 접근하면 401 공통 포맷이며 `requestId`와 `X-Request-Id`가 채워져 있다.
- [x] 공개 경로가 토큰 없이 동작하고 기존 signup·인증번호·OAuth authorize 테스트가 회귀 없이 통과한다.
- [x] Refresh Token 원문이 DB에 존재하지 않는다(해시만 저장).
- [x] `.\gradlew.bat build`가 통과한다.
