# Email Signup API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 유효한 가입 토큰을 원자적으로 소비해 BCrypt 회원과 시장별 계좌 2개를 만들고 실제 Access/Refresh JWT 쌍을 반환하는 `POST /api/auth/signup`을 완성한다.

**Architecture:** `AuthController → AuthService → auth/account service·repository` 흐름을 유지한다. `AuthService`가 가입 트랜잭션을 소유하고, `EmailVerificationRepository`의 조건부 UPDATE로 토큰 재사용을 막으며, `AccountService`와 `JwtTokenProvider`를 조합한다. Refresh Token은 원문 대신 SHA-256 해시만 MySQL에 저장한다.

**Tech Stack:** Java 17, Spring Boot 4.1, Spring Data JPA, Bean Validation, Spring Security Crypto, JJWT 0.13.0, MySQL 8.4 Testcontainers, JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

## Global Constraints

- 작업 범위는 GitHub Issue #4와 `docs/specs/002-auth-account/issue-4-design.md`로 제한한다.
- 새 Java 소스 첫 줄에는 파일 역할을 설명하는 한국어 한 줄 주석을 둔다.
- DTO는 record로 작성하고 요청 문자열에는 최대 길이를 명시한다.
- 비밀번호는 BCrypt, 가입 토큰과 Refresh Token은 SHA-256 해시만 저장한다.
- `JWT_SECRET`과 `EMAIL_VERIFICATION_SECRET`은 서로 다른 환경변수이며 코드·YAML에 기본값을 두지 않는다.
- Access Token은 1시간, Refresh Token은 14일이다.
- 회원, 계좌 2개, 가입 토큰 소비, Refresh Token 해시 저장은 하나의 트랜잭션이다.
- 로그인, Bearer 필터, 보호 경로, Refresh 회전, 로그아웃, 내 정보 조회는 구현하지 않는다.
- 병합된 `V2__create_auth_account_tables.sql`은 수정하지 않는다.
- controller를 추가하면 `docs/api-routes.md`를 같은 작업에서 동기화한다.
- 검증은 Windows wrapper `.\gradlew.bat`로 실행한다.

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/auth/controller/AuthController.java`: 회원가입 HTTP 계약과 201 응답.
- `src/main/java/com/finplay/api/auth/service/AuthService.java`: 가입 트랜잭션과 전체 유스케이스 조정.
- `src/main/java/com/finplay/api/auth/config/AuthCryptoConfig.java`: BCrypt `PasswordEncoder` 빈.
- `src/main/java/com/finplay/api/auth/token/JwtTokenProvider.java`: Access/Refresh JWT 발급.
- `src/main/java/com/finplay/api/auth/token/IssuedTokenPair.java`: 발급 토큰과 만료정보 내부 전달.
- `src/main/java/com/finplay/api/auth/domain/RefreshToken.java`: Refresh Token 해시 영속 엔티티.
- `src/main/java/com/finplay/api/auth/repository/RefreshTokenRepository.java`: Refresh Token 저장.
- `src/main/java/com/finplay/api/auth/dto/request/SignupRequest.java`: 회원가입 요청 검증.
- `src/main/java/com/finplay/api/auth/dto/response/TokenResponse.java`: 토큰 쌍과 만료 초 응답.
- `src/main/java/com/finplay/api/account/domain/Account.java`: 시장별 계좌 엔티티.
- `src/main/java/com/finplay/api/account/domain/Market.java`: `STOCK`, `CRYPTO` 시장 enum.
- `src/main/java/com/finplay/api/account/repository/AccountRepository.java`: 계좌 저장·조회.
- `src/main/java/com/finplay/api/account/service/AccountService.java`: 회원별 계좌 2개 생성.

### Production files to modify

- `build.gradle`: JJWT 0.13.0과 Spring Security Crypto 의존성, 테스트용 `JWT_SECRET`.
- `src/main/java/com/finplay/api/auth/repository/UserRepository.java`: 이메일·닉네임 중복 조회와 통합 테스트용 이메일 조회.
- `src/main/java/com/finplay/api/auth/repository/EmailVerificationRepository.java`: 토큰 해시 조회와 조건부 소비 UPDATE.

### Test files to create

- `src/test/java/com/finplay/api/auth/token/JwtTokenProviderTest.java`
- `src/test/java/com/finplay/api/account/service/AccountServiceTest.java`
- `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java`
- `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java`
- `src/test/java/com/finplay/api/auth/repository/SignupPersistenceRepositoryTest.java`
- `src/test/java/com/finplay/api/auth/service/SignupIntegrationTest.java`

### Documentation files to modify

- `docs/api-routes.md`
- `docs/specs/002-auth-account/tasks.md`
- `docs/specs/002-auth-account/run-log.md`

---

### Task 1: JWT 발급과 Refresh Token 영속 기반

**Files:**
- Create: `src/main/java/com/finplay/api/auth/token/IssuedTokenPair.java`
- Create: `src/main/java/com/finplay/api/auth/token/JwtTokenProvider.java`
- Create: `src/main/java/com/finplay/api/auth/domain/RefreshToken.java`
- Create: `src/main/java/com/finplay/api/auth/repository/RefreshTokenRepository.java`
- Create: `src/test/java/com/finplay/api/auth/token/JwtTokenProviderTest.java`
- Modify: `build.gradle`

**Interfaces:**
- Produces: `IssuedTokenPair JwtTokenProvider.issue(Long userId, String role)`
- Produces: `RefreshToken.create(User user, String tokenHash, LocalDateTime expiresAt, LocalDateTime now)`
- Produces: `RefreshTokenRepository extends JpaRepository<RefreshToken, Long>`

- [ ] **Step 1: JWT 발급 실패 테스트를 작성한다**

`JwtTokenProviderTest`는 고정 `Clock`과 32바이트 이상의 테스트 시크릿으로 provider를 직접 생성한다. `issue(7L, "USER")` 결과의 두 토큰을 JJWT parser로 검증해 다음 값을 단언한다.

```java
assertThat(accessClaims.getSubject()).isEqualTo("7");
assertThat(accessClaims.get("role", String.class)).isEqualTo("USER");
assertThat(accessClaims.get("tokenType", String.class)).isEqualTo("ACCESS");
assertThat(refreshClaims.get("tokenType", String.class)).isEqualTo("REFRESH");
assertThat(tokens.accessTokenExpiresInSeconds()).isEqualTo(3600L);
assertThat(tokens.refreshTokenExpiresInSeconds()).isEqualTo(1_209_600L);
assertThat(tokens.refreshTokenExpiresAt())
    .isEqualTo(LocalDateTime.ofInstant(FIXED_INSTANT.plusSeconds(1_209_600), ZoneOffset.UTC));
```

- [ ] **Step 2: 테스트가 컴파일 또는 실행 실패하는지 확인한다**

Run:

```powershell
.\gradlew.bat test --tests "*JwtTokenProviderTest"
```

Expected: `JwtTokenProvider`와 JJWT 의존성이 없어 FAIL.

- [ ] **Step 3: 의존성과 토큰 발급 구현을 추가한다**

`build.gradle`:

```groovy
implementation 'org.springframework.security:spring-security-crypto'
implementation 'io.jsonwebtoken:jjwt-api:0.13.0'
runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.13.0'
runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.13.0'
```

test task 환경변수:

```groovy
environment 'JWT_SECRET', 'test-jwt-secret-that-is-at-least-32-bytes'
```

`IssuedTokenPair`:

```java
public record IssuedTokenPair(
    String accessToken,
    String refreshToken,
    LocalDateTime refreshTokenExpiresAt,
    long accessTokenExpiresInSeconds,
    long refreshTokenExpiresInSeconds) {
}
```

`JwtTokenProvider`는 `jwt.secret`, `jwt.access-token-expiration-ms`, `jwt.refresh-token-expiration-ms`, `Clock`을 생성자로 받고 다음 계약으로 발급한다.

```java
public IssuedTokenPair issue(Long userId, String role)
```

두 JWT 모두 `subject=userId`, `role`, `tokenType`, `issuedAt`, `expiration`을 가지며 HMAC 키로 서명한다. 만료 초는 millisecond 설정을 `Duration.ofMillis(...).toSeconds()`로 변환한다.

- [ ] **Step 4: Refresh Token 엔티티와 repository를 추가한다**

`RefreshToken.create`는 다음 값을 설정한다.

```java
this.user = user;
this.tokenHash = tokenHash;
this.expiresAt = expiresAt;
this.createdAt = now;
this.revokedAt = null;
```

JPA 매핑은 기존 `refresh_tokens`의 `user_id`, `token_hash`, `expires_at`, `revoked_at`, `created_at`과 정확히 일치시킨다.

- [ ] **Step 5: 대상 테스트를 통과시킨다**

Run:

```powershell
.\gradlew.bat test --tests "*JwtTokenProviderTest"
```

Expected: PASS.

- [ ] **Step 6: Task 1을 커밋한다**

```powershell
git add build.gradle src/main/java/com/finplay/api/auth/token src/main/java/com/finplay/api/auth/domain/RefreshToken.java src/main/java/com/finplay/api/auth/repository/RefreshTokenRepository.java src/test/java/com/finplay/api/auth/token/JwtTokenProviderTest.java
git commit -m "feat: 회원가입 JWT 발급 기반 추가"
```

---

### Task 2: 시장별 계좌 생성

**Files:**
- Create: `src/main/java/com/finplay/api/account/domain/Market.java`
- Create: `src/main/java/com/finplay/api/account/domain/Account.java`
- Create: `src/main/java/com/finplay/api/account/repository/AccountRepository.java`
- Create: `src/main/java/com/finplay/api/account/service/AccountService.java`
- Create: `src/test/java/com/finplay/api/account/service/AccountServiceTest.java`

**Interfaces:**
- Consumes: `User`
- Produces: `void AccountService.createAccountsFor(User user)`
- Produces: `List<Account> AccountRepository.findAllByUserId(Long userId)`

- [ ] **Step 1: 계좌 생성 실패 테스트를 작성한다**

Mockito로 `AccountRepository`를 주입한 `AccountServiceTest`에서 `createAccountsFor(user)` 호출 후 `saveAll` 인자를 캡처한다.

```java
assertThat(accounts)
    .extracting(Account::getMarket)
    .containsExactlyInAnyOrder(Market.STOCK, Market.CRYPTO);
assertThat(accounts)
    .allSatisfy(account -> {
        assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
        assertThat(account.getSeedMoney()).isEqualTo(10_000_000L);
        assertThat(account.getRealizedPnl()).isZero();
    });
```

- [ ] **Step 2: 테스트 실패를 확인한다**

Run:

```powershell
.\gradlew.bat test --tests "*AccountServiceTest"
```

Expected: account 타입이 없어 FAIL.

- [ ] **Step 3: 계좌 모델과 서비스를 구현한다**

`Market`:

```java
public enum Market {
    STOCK,
    CRYPTO
}
```

`Account.create(User user, Market market, LocalDateTime now)`는 cash balance와 seed money를 `10_000_000L`, realized PnL을 `0L`로 설정한다. `AccountService`는 주입받은 `Clock`으로 같은 `now`를 만들고 두 엔티티를 한 번의 `saveAll`로 저장한다.

- [ ] **Step 4: 계좌 테스트를 통과시킨다**

Run:

```powershell
.\gradlew.bat test --tests "*AccountServiceTest"
```

Expected: PASS.

- [ ] **Step 5: Task 2를 커밋한다**

```powershell
git add src/main/java/com/finplay/api/account src/test/java/com/finplay/api/account
git commit -m "feat: 회원가입 시장별 계좌 생성 추가"
```

---

### Task 3: 가입 토큰 원자 소비와 AuthService

**Files:**
- Create: `src/main/java/com/finplay/api/auth/service/AuthService.java`
- Create: `src/main/java/com/finplay/api/auth/config/AuthCryptoConfig.java`
- Create: `src/main/java/com/finplay/api/auth/dto/response/TokenResponse.java`
- Create: `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java`
- Create: `src/test/java/com/finplay/api/auth/repository/SignupPersistenceRepositoryTest.java`
- Modify: `src/main/java/com/finplay/api/auth/repository/UserRepository.java`
- Modify: `src/main/java/com/finplay/api/auth/repository/EmailVerificationRepository.java`

**Interfaces:**
- Consumes: `AccountService.createAccountsFor(User)`
- Consumes: `JwtTokenProvider.issue(Long, String)`
- Produces: `TokenResponse AuthService.signup(String email, String nickname, String password, String signupVerificationToken)`
- Produces: `int EmailVerificationRepository.consumeValidToken(String tokenHash, LocalDateTime now)`

- [ ] **Step 1: repository 소비 쿼리 실패 테스트를 작성한다**

`SignupPersistenceRepositoryTest`는 Testcontainers MySQL에서 확인 완료된 인증 행을 저장한 후 조건부 UPDATE를 두 번 실행한다.

```java
assertThat(repository.consumeValidToken(tokenHash, now)).isEqualTo(1);
assertThat(repository.consumeValidToken(tokenHash, now.plusSeconds(1))).isZero();
```

만료된 토큰, 미확인 토큰도 영향 행이 0인지 검증한다. `RefreshTokenRepository`에는 해시 문자열을 저장하고 원문 검색 결과가 없는지 확인한다.

- [ ] **Step 2: AuthService 실패 테스트를 작성한다**

다음 테스트를 각각 독립적으로 작성한다.

```java
signupRejectsDuplicateEmail();
signupRejectsDuplicateNickname();
signupRejectsUnknownToken();
signupRejectsExpiredOrConsumedToken();
signupRejectsTokenEmailMismatch();
signupRejectsWhenConditionalConsumeLosesRace();
signupCreatesUserAccountsAndHashedRefreshToken();
signupMapsConcurrentUserUniqueViolationToDuplicateResource();
```

정상 테스트는 `PasswordEncoder.matches(raw, capturedUser.getPasswordHash())`, `accountService.createAccountsFor(savedUser)`, `refreshTokenRepository.save(...)`를 검증한다. 저장된 hash는 원문과 다르고 테스트가 계산한 SHA-256 hex와 같아야 한다.

- [ ] **Step 3: 실패를 확인한다**

Run:

```powershell
.\gradlew.bat test --tests "*SignupPersistenceRepositoryTest" --tests "*AuthServiceTest"
```

Expected: 신규 repository 메서드와 AuthService가 없어 FAIL.

- [ ] **Step 4: repository 계약을 구현한다**

`UserRepository`:

```java
boolean existsByNickname(String nickname);
Optional<User> findByEmail(String email);
```

`EmailVerificationRepository`:

```java
Optional<EmailVerification> findByTokenHash(String tokenHash);

@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("""
    update EmailVerification verification
       set verification.consumedAt = :now
     where verification.tokenHash = :tokenHash
       and verification.verifiedAt is not null
       and verification.consumedAt is null
       and verification.tokenExpiresAt > :now
    """)
int consumeValidToken(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);
```

- [ ] **Step 5: BCrypt 설정과 응답 DTO를 구현한다**

`AuthCryptoConfig`:

```java
@Bean
PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

`TokenResponse`:

```java
public record TokenResponse(
    String accessToken,
    String refreshToken,
    long accessTokenExpiresInSeconds,
    long refreshTokenExpiresInSeconds) {

    public static TokenResponse from(IssuedTokenPair tokens) {
        return new TokenResponse(
            tokens.accessToken(),
            tokens.refreshToken(),
            tokens.accessTokenExpiresInSeconds(),
            tokens.refreshTokenExpiresInSeconds());
    }
}
```

- [ ] **Step 6: AuthService를 구현한다**

`signup`은 `@Transactional`이며 다음 순서를 그대로 유지한다.

```java
checkDuplicate(email, nickname);
String tokenHash = sha256(signupVerificationToken);
EmailVerification verification = findAndValidateVerification(tokenHash, email, now);
int consumed = emailVerificationRepository.consumeValidToken(tokenHash, now);
if (consumed != 1) {
    throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
}
User user = saveUser(email, passwordEncoder.encode(password), nickname, now);
accountService.createAccountsFor(user);
IssuedTokenPair tokens = jwtTokenProvider.issue(user.getId(), user.getRole());
refreshTokenRepository.save(RefreshToken.create(
    user, sha256(tokens.refreshToken()), tokens.refreshTokenExpiresAt(), now));
return TokenResponse.from(tokens);
```

`UserRepository.saveAndFlush`의 `DataIntegrityViolationException`만 잡아 `BusinessException(DUPLICATE_RESOURCE)`으로 변환한다. 이 예외를 다시 던져 가입 트랜잭션 전체를 롤백한다.

`findAndValidateVerification`은 다음 조건을 모두 검사하고 하나라도 실패하면 `EMAIL_VERIFICATION_REQUIRED`를 던진다.

```java
if (verification.getVerifiedAt() == null
    || verification.getConsumedAt() != null
    || verification.getTokenExpiresAt() == null
    || !verification.getTokenExpiresAt().isAfter(now)
    || !verification.getEmail().equals(email)) {
    throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
}
```

가입 토큰과 Refresh Token 해시는 별도 범용 helper를 만들지 않고 `AuthService`의 private 메서드로 계산한다.

```java
private String sha256(String value) {
    try {
        byte[] digest = MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
        throw new IllegalStateException("토큰 SHA-256 계산에 실패했습니다.", ex);
    }
}
```

- [ ] **Step 7: 대상 테스트를 통과시킨다**

Run:

```powershell
.\gradlew.bat test --tests "*SignupPersistenceRepositoryTest" --tests "*AuthServiceTest"
```

Expected: PASS.

- [ ] **Step 8: Task 3을 커밋한다**

```powershell
git add src/main/java/com/finplay/api/auth src/test/java/com/finplay/api/auth/service/AuthServiceTest.java src/test/java/com/finplay/api/auth/repository/SignupPersistenceRepositoryTest.java
git commit -m "feat: 가입 토큰 원자 소비와 회원 생성 추가"
```

---

### Task 4: 회원가입 Controller와 요청 계약

**Files:**
- Create: `src/main/java/com/finplay/api/auth/controller/AuthController.java`
- Create: `src/main/java/com/finplay/api/auth/dto/request/SignupRequest.java`
- Create: `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java`

**Interfaces:**
- Consumes: `AuthService.signup(String, String, String, String)`
- Produces: `POST /api/auth/signup`

- [ ] **Step 1: WebMvc 실패 테스트를 작성한다**

정상 요청은 실제 `TokenResponse`를 stubbing하고 다음을 검증한다.

```java
mockMvc.perform(post("/api/auth/signup")
    .contentType(MediaType.APPLICATION_JSON)
    .content("""
        {
          "email":"user@finplay.com",
          "nickname":"finplayer",
          "password":"password123",
          "termsAgreed":true,
          "signupVerificationToken":"signup-token"
        }
        """))
    .andExpect(status().isCreated())
    .andExpect(jsonPath("$.accessToken").value("access-token"))
    .andExpect(jsonPath("$.refreshToken").value("refresh-token"))
    .andExpect(jsonPath("$.accessTokenExpiresInSeconds").value(3600))
    .andExpect(jsonPath("$.refreshTokenExpiresInSeconds").value(1209600));
```

이메일 누락·형식 오류·256자 초과, 닉네임 누락·51자, 비밀번호 누락·7자·101자, 약관 누락·false, 가입 토큰 누락을 각각 400으로 검증한다. 서비스의 `DUPLICATE_RESOURCE`와 `EMAIL_VERIFICATION_REQUIRED`는 각각 409 공통 포맷으로 검증한다.

- [ ] **Step 2: WebMvc 테스트 실패를 확인한다**

Run:

```powershell
.\gradlew.bat test --tests "*AuthControllerTest"
```

Expected: controller와 request DTO가 없어 FAIL.

- [ ] **Step 3: SignupRequest를 구현한다**

```java
public record SignupRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @NotBlank @Size(max = 50) String nickname,
    @NotBlank @Size(min = 8, max = 100) String password,
    @NotNull @AssertTrue Boolean termsAgreed,
    @NotBlank @Size(max = 255) String signupVerificationToken) {
}
```

각 검증 애노테이션에는 conventions의 한국어 마침표 메시지를 지정한다.

- [ ] **Step 4: AuthController를 구현한다**

```java
@PostMapping("/signup")
public ResponseEntity<TokenResponse> signup(@Valid @RequestBody SignupRequest request) {
    TokenResponse response = authService.signup(
        request.email(),
        request.nickname(),
        request.password(),
        request.signupVerificationToken());
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
}
```

- [ ] **Step 5: WebMvc 테스트를 통과시킨다**

Run:

```powershell
.\gradlew.bat test --tests "*AuthControllerTest"
```

Expected: PASS.

- [ ] **Step 6: Task 4를 커밋한다**

```powershell
git add src/main/java/com/finplay/api/auth/controller/AuthController.java src/main/java/com/finplay/api/auth/dto src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java
git commit -m "feat: 이메일 회원가입 API 추가"
```

---

### Task 5: 핵심 가입 통합 시나리오

**Files:**
- Create: `src/test/java/com/finplay/api/auth/service/SignupIntegrationTest.java`

**Interfaces:**
- Consumes: 인증번호 발송·확인 API의 기존 service, `AuthService.signup`, 실제 MySQL repository.
- Verifies: 회원·계좌·가입 토큰·Refresh Token이 같은 트랜잭션 경계를 공유한다.

- [ ] **Step 1: Testcontainers 통합 테스트를 작성한다**

`@SpringBootTest`와 `@Import(TestcontainersConfiguration.class)`를 사용한다. `FakeEmailSender`에서 코드를 얻어 기존 확인 service로 가입 토큰을 발급한 뒤 signup을 호출한다.

검증 시나리오:

```java
signupCreatesOneUserAndTwoSeededAccounts();
reusingSignupTokenReturnsEmailVerificationRequired();
duplicateNicknameFailureLeavesTokenReusable();
refreshTokenIsPersistedOnlyAsSha256Hash();
```

정상 가입 후:

```java
assertThat(userRepository.findById(userId)).isPresent();
assertThat(accountRepository.findAllByUserId(userId))
    .hasSize(2)
    .allSatisfy(account -> {
        assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
        assertThat(account.getSeedMoney()).isEqualTo(10_000_000L);
    });
assertThat(refreshTokenRepository.findAll())
    .extracting(RefreshToken::getTokenHash)
    .contains(sha256(response.refreshToken()))
    .doesNotContain(response.refreshToken());
```

- [ ] **Step 2: 통합 테스트를 실행한다**

Run:

```powershell
.\gradlew.bat test --tests "*SignupIntegrationTest"
```

Expected: Docker와 MySQL 8.4가 사용 가능하면 PASS. Docker가 불가능하면 실패 로그를 보존하고 단위·슬라이스 통과와 구분 보고한다.

- [ ] **Step 3: 실패가 production 결함이면 기존 production 작성자에게 반환한다**

마지막 실패 로그, 실패 테스트명, 예상값과 실제값만 전달한다. 테스트 기대가 spec과 다르면 tester가 테스트를 수정하고, production 결함이면 implementer가 수정한 뒤 같은 통합 테스트를 다시 실행한다.

- [ ] **Step 4: Task 5를 커밋한다**

```powershell
git add src/test/java/com/finplay/api/auth/service/SignupIntegrationTest.java
git commit -m "test: 회원가입 원자성 통합 검증 추가"
```

---

### Task 6: 문서 동기화와 전체 게이트

**Files:**
- Modify: `docs/api-routes.md`
- Modify: `docs/specs/002-auth-account/tasks.md`
- Modify: `docs/specs/002-auth-account/run-log.md`

**Interfaces:**
- Consumes: 최종 API 계약과 실제 검증 결과.
- Produces: reviewer가 비교할 최신 route map, task 상태, 실행 근거.

- [ ] **Step 1: API route 문서를 갱신한다**

`docs/api-routes.md`에 다음 계약을 추가한다.

```markdown
| POST | /api/auth/signup | auth | 가입 토큰 소비·회원과 시장별 계좌 생성·JWT 발급 | 002 AUTH-001·ACCT-001 |
```

상세 표에는 요청 필드, 201 `TokenResponse`, 400 `VALIDATION_ERROR`, 409 `DUPLICATE_RESOURCE`·`EMAIL_VERIFICATION_REQUIRED`를 기록한다.

- [ ] **Step 2: 포맷을 적용하고 대상 테스트를 한 번에 재실행한다**

Run:

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat test --tests "*JwtTokenProviderTest" --tests "*AccountServiceTest" --tests "*AuthServiceTest" --tests "*AuthControllerTest" --tests "*SignupPersistenceRepositoryTest" --tests "*SignupIntegrationTest"
```

Expected: 모두 PASS.

- [ ] **Step 3: tasks와 run log를 실제 결과로 갱신한다**

대상 테스트가 모두 통과한 경우에만 다음 항목을 `[x]`로 바꾼다.

```markdown
- [x] 회원가입 API — 가입 토큰 원자 소비·토큰 이메일 일치 검증·중복 409·계좌 2개 원자 생성 (+ 단위·@WebMvcTest)
```

`run-log.md`의 Issue #4 구역에는 실행 시각, 명령, 통과 테스트 수, Docker/Testcontainers 여부를 기록한다.

- [ ] **Step 4: 전체 빌드를 실행한다**

Run:

```powershell
.\gradlew.bat build
```

Expected: compile, test, Spotless, SpotBugs, JaCoCo 40% 게이트 모두 PASS.

- [ ] **Step 5: 최종 diff와 HEAD를 확인한다**

Run:

```powershell
git status --short --branch
git diff dev...HEAD --check
git diff --stat dev...HEAD
git rev-parse HEAD
```

Expected: 범위 밖 파일 변경이 없고 diff check가 PASS.

- [ ] **Step 6: 문서 커밋을 만든다**

```powershell
git add docs/api-routes.md docs/specs/002-auth-account/tasks.md docs/specs/002-auth-account/run-log.md
git commit -m "docs: 이슈 4 구현 결과 동기화"
```

- [ ] **Step 7: reviewer 차단 지적 후 게이트를 재실행한다**

reviewer의 차단 지적은 해당 production implementer가 수정한다. 수정 후 대상 테스트와 `.\gradlew.bat build`를 다시 실행하고 새 HEAD SHA를 run log에 추가한다. 권장·참고 지적은 잔여 위험 또는 후속 Issue 후보로 기록한다.
