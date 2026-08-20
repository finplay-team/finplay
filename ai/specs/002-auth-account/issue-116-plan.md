# Issue #116 비밀번호 재설정 확인 및 적용 API 구현 계획

> **For agentic workers:** 각 작업은 실패 테스트를 먼저 작성한다. 재사용·만료·재발송 무효화·5회 초과·불일치·타인 이메일은 각각 별도 테스트로 고정하며 서로 대체하거나 합쳐 보고하지 않는다. 이 이슈는 **확인과 적용**을 구현한다 — 인증번호 발송(#115)과 로그인 상태의 비밀번호 변경(#114)은 이미 `dev`에 있고 이번 범위에서 수정하지 않는다.

**Goal:** `POST /api/auth/password-resets/confirm`을 비인증 공개 경로로 신설해, #115에서 발송한 인증번호와 새 비밀번호를 **한 요청으로 함께 받아** 즉시 적용한다. 인증번호 1회 소비 · `users.password_hash` 교체 · 해당 회원의 기존 Refresh Token 전체 폐기가 부분 성공 없이 한 트랜잭션으로 처리되어야 한다. 재설정은 비로그인 흐름이므로 **새 토큰 쌍을 발급하지 않으며 항상 전 기기 로그아웃**이다.

**관련 정본:** GitHub Issue #116, PRD `AUTH-006`(`docs/prd.md` 294-318행), `docs/specs/002-auth-account/spec.md`, `docs/specs/002-auth-account/plan.md`, Issue #115 계획(`issue-115-plan.md`, 테이블·발송 규칙), Issue #56 계획(`issue-56-plan.md`, "확인 및 적용" 계열 직전 선례 — 검증 순서·소비·세션 폐기 원자성), Issue #114 계획(`issue-114-plan.md`, `User.changePassword`·세션 폐기 순서), ADR-0002, ADR-0003, ADR-0004, `docs/conventions.md`

**착수 전 확정된 결정:** **단계 구성은 (a)안 — 인증번호와 새 비밀번호를 한 요청으로 받아 즉시 적용한다.** 이슈 본문 "착수 전 결정 필요"는 이것으로 해소됐다. `POST /api/auth/email-changes/confirm`(#56)과 같은 패턴이며, 별도 `passwordResetToken`을 발급하는 2단계 (b)안은 채택하지 않았다 — 엔드포인트가 하나로 끝나고 새 토큰의 저장·소비 구현이 필요 없다는 것이 근거다. 프런트는 인증번호 입력칸과 새 비밀번호 입력칸을 한 화면에 둔다.

**선행 (실제 코드 확인 완료, 모두 `dev`에 있다):**

| 이미 있는 것 | 위치 | 이번 이슈에서 |
|---|---|---|
| `password_reset_verifications`(V12), `PasswordResetVerification`(`create`/`createRejected`/`expire`) | `auth/domain` | `incrementAttemptCount()`·`consume(now)` **추가** |
| `PasswordResetVerificationRepository`(집계·무효화 대상 2개 쿼리) | `auth/repository` | 확인 대상 조회 쿼리 1개 **추가** |
| `PasswordResetService.sendResetCode`(`@Transactional(noRollbackFor = BusinessException.class)`), `hmac`, `MAX` 상수 없음 | `auth/service` | `validateAndConsumeCode` **추가**, 발송 로직 미수정 |
| `PasswordResetController`(`POST /api/auth/password-resets`), `PasswordResetRequest` | `auth/controller`·`dto/request` | `/confirm` 매핑·신규 DTO **추가** |
| `User.changePassword(newPasswordHash, now)`, `User.hasPassword()`, `User.OAUTH_ONLY_PASSWORD_SENTINEL` | `auth/domain/User` | **그대로 재사용**, 수정 없음 |
| `RefreshTokenRepository.revokeAllActiveByUserId(userId, now)` | `auth/repository` | **그대로 재사용**, 수정 없음 |
| `ErrorCode.SOCIAL_ACCOUNT_ONLY`(409), `NOT_FOUND`(404), `EMAIL_VERIFICATION_FAILED`(400), `TOO_MANY_REQUESTS`(429) | `common/ErrorCode` | **신규 코드 추가 없음** |

`PasswordResetVerification`의 `attempt_count`·`consumed_at` 컬럼은 #115 계획 D6이 "#116 범위"로 미뤄둔 것이며 이번에 채운다. **신규 Flyway 마이그레이션은 없다** (ADR-0004 — V12를 수정하지 않는다).

---

## Architecture

- `PasswordResetController → AuthService.confirmPasswordReset → PasswordResetService.validateAndConsumeCode`로 흐른다. 트랜잭션 경계는 `AuthService.confirmPasswordReset`이 갖는다.
- **역할 분담은 #56에서 팀이 확정한 배치를 그대로 따른다.** `PasswordResetService`는 인증번호 검증·소비와 재설정 대상 회원 판별까지, `AuthService`는 `User` 상태 변경(`changePassword`)과 Refresh Token 폐기를 담당한다 — "`AuthService`가 항상 최종 `User` 상태 변경을 담당한다"는 기존 불변 규칙(`changeNickname`·`changeEmail`·`changePassword`)을 유지한다.
- `AuthService`가 `PasswordResetService`를 새 의존성으로 주입받는다(반대 방향 아님). `AuthService`는 `userRepository`·`passwordEncoder`·`refreshTokenRepository`·`clock`을 이미 필드로 갖고 있어 추가 주입은 `PasswordResetService` 하나뿐이다.
- `PasswordResetService`는 `UserRepository`를 이미 주입받고 있어 새 의존성이 없다. `RefreshTokenRepository`·`PasswordEncoder`는 이 서비스에 주입하지 않는다.
- `EmailVerificationService`·`EmailChangeService`·`PasswordResetService.sendResetCode`는 수정하지 않는다.
- `SecurityConfig.PUBLIC_POST_PATHS`에 `/api/auth/password-resets/confirm`을 추가한다 — `/api/auth/password-resets`와 달리 경로가 정확히 일치해야 하므로 별도 항목이 필요하다(`PathPatternRequestMatcher`는 접두 매칭이 아니다).

## Tech Stack

Java 17, Spring Boot 4.1, Spring Data JPA, MySQL 8.4(Testcontainers), Spring Security(공개 경로), BCrypt `PasswordEncoder`, JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

---

## 요구사항 ID와 수용 기준

### PRD `AUTH-006` (확인·교체·폐기 부분, `docs/prd.md` 294-318행)

- 인증번호는 6자리 숫자이며 유효시간 5분, 입력 시도는 최대 5회다. 5회를 초과하면 해당 인증번호를 즉시 무효화하고 429 `TOO_MANY_REQUESTS`로 응답한다. (→ U1 참고: 이슈 본문의 "실패 사유 미구분"과 부분 충돌)
- 재발송하면 이전 인증번호는 즉시 무효화된다 — 유효한 인증번호는 항상 최대 1개다.
- **인증번호 확인에 성공하면 `users.password_hash`를 원자적으로 교체하고 기존 Refresh Token을 모두 폐기해 재로그인을 요구한다.**
- 인증번호 원문은 저장하지 않고 `PASSWORD_RESET_SECRET` 기반 HMAC-SHA-256 해시로만 대조한다.
- (309행) "인증번호 확인, `users.password_hash` 교체, 재설정 완료 시 Refresh Token 폐기는 후속 Issue #116" — 이번 이슈 완료 시 이 문장을 갱신한다(Task 5).

### Issue #116 수용 시나리오

1. Given #115에서 발급된 유효한 인증번호(5분 이내, 5회 미만 시도, 미소비, 재발송으로 무효화되지 않음)가 있는 상태
2. When 같은 이메일·올바른 인증번호·새 비밀번호로 `POST /api/auth/password-resets/confirm` 호출(인증 헤더 없이)
3. Then 비밀번호가 한 번 교체되고, 같은 인증번호로 재호출하면 거부된다.
4. And 교체 후 새 비밀번호로 로그인되고 기존 비밀번호 로그인은 401이다.
5. And 요청 이력 없음 · 잘못된 번호 · 만료 · 재발송으로 무효화된 이전 번호 · 이미 소비된 번호는 모두 비밀번호를 바꾸지 않으며 **실패 사유를 구분하지 않는다**.
6. And 같은 인증번호에 5회 실패하면 그 인증번호는 즉시 무효화되어 이후 정답을 입력해도 쓸 수 없다.
7. And 다른 회원의 이메일·인증번호 조합으로는 어떤 계정의 비밀번호도 바뀌지 않는다.
8. And 재설정 성공 후 기존 Refresh Token으로 `/api/auth/refresh`를 호출하면 401이다(**모든 기기 로그아웃**). 응답에 새 토큰 쌍은 포함되지 않는다.
9. And 성공·실패와 무관하게 이메일 · 닉네임 · OAuth 연결(`social_accounts`) · 계좌 · 시드머니 · 잔액 · 주문 · 체결은 변하지 않는다.
10. And 검증 실패(불일치·5회 초과)로 응답이 실패해도 `attempt_count` 증가는 커밋된다.

---

## 범위와 제외

### 포함

- `POST /api/auth/password-resets/confirm` — 비인증 공개 경로, 이메일 + 인증번호 + 새 비밀번호를 한 요청으로 받아 즉시 적용.
- `PasswordResetVerification.incrementAttemptCount()`·`consume(now)`, `PasswordResetVerificationRepository.findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(email)` 추가.
- `PasswordResetService.validateAndConsumeCode(email, code)` 추가(검증·소비·대상 회원 판별).
- `AuthService.confirmPasswordReset(email, code, newPassword)` 추가(해시 교체 + Refresh Token 전체 폐기, 트랜잭션 경계).
- `PasswordResetConfirmRequest` DTO, `SecurityConfig.PUBLIC_POST_PATHS` 경로 추가.
- PRD `AUTH-006` 구현 단계 문구·엔드포인트 목록 갱신, `docs/api-routes.md`·`docs/api-contracts.md` 동기화.

### 제외

- 인증번호 발송 (#115, 완료).
- 로그인 상태의 비밀번호 변경 (#114, 완료).
- **재설정 직후 자동 로그인** — 새 Access/Refresh Token을 발급하지 않는다(D3).
- Access Token 즉시 블랙리스트 — 다른 기기의 이미 발급된 Access Token은 남은 만료 시간 동안 유효하다는 한계를 `docs/api-contracts.md`에 명시만 한다.
- 재설정 완료 알림 메일.
- 확인 요청 자체에 대한 이메일·IP 단위 발송 제한(발송 단계 제한과 인증번호별 5회 제한으로만 방어 — U4의 한계 참고).
- 새 비밀번호가 기존 비밀번호와 같은지 검사 (D5).
- 신규 Flyway 마이그레이션·신규 `ErrorCode`.

---

## 설계 결정

### D1. 검증 순서 — 인증번호를 먼저 검증하고, 그 뒤에 계정 상태를 본다

```java
// PasswordResetService — @Transactional 선언 없음, 호출자(AuthService)의 트랜잭션에 편입된다.
// 반환값은 재설정 대상 회원이다 — 호출자가 다시 조회하지 않도록 여기서 확정해 넘긴다.
public User validateAndConsumeCode(String email, String code) {
    LocalDateTime now = LocalDateTime.now(clock);

    // (1) 실제로 발송된 행 중 최신 1건. 거부 행(code_hash IS NULL)은 제외한다 — D2.
    PasswordResetVerification verification = passwordResetVerificationRepository
        .findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(email)
        .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED));

    // (2) 이미 소비됐거나, 만료됐거나(자연 만료·재발송 무효화·5회 초과 무효화가 모두 여기로 수렴)
    if (verification.getConsumedAt() != null || !verification.getExpiresAt().isAfter(now)) {
        throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED);
    }
    // (3) 5회 초과 — 증가시키고 즉시 만료시킨 뒤 429 (U1)
    if (verification.getAttemptCount() >= MAX_VERIFICATION_ATTEMPTS) {
        verification.incrementAttemptCount();
        verification.expire(now);
        throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
    }
    // (4) 코드 불일치 — 증가시키고 400
    if (!verification.getCodeHash().equals(hmac(code))) {
        verification.incrementAttemptCount();
        throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED);
    }

    // (5) 여기부터는 인증번호를 맞힌 요청자다 — 이제서야 계정 상태를 드러낸다 (D2).
    User user = userRepository.findByEmail(email)
        .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED));
    if (!user.hasPassword()) {
        throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_ONLY);
    }

    // (6) 모든 판정을 통과한 뒤에만 소비한다.
    verification.consume(now);
    return user;
}
```

- `MAX_VERIFICATION_ATTEMPTS = 5` 상수를 `PasswordResetService`에 추가한다(`EmailVerificationService`·`EmailChangeService`와 같은 값·같은 이름). #115 U5에서 이미 지적한 세 번째 중복이며, 이번에도 공통화하지 않고 별도 리팩터링 이슈로 남긴다.
- (1)~(4)의 판정 순서·오류 코드는 `EmailChangeService.validateAndConsumeCode`(#56 D1)와 동일하다. 코드 재사용은 하지 않고 새 엔티티 타입에 맞춰 다시 작성한다.
- `hmac(code)`는 `PasswordResetService`에 이미 있는 private 메서드를 그대로 쓴다 — 발송과 확인이 같은 `PASSWORD_RESET_SECRET`을 쓴다.
- **소비는 마지막에 한 번만 한다.** (5)에서 던지면 소비 전이므로 인증번호가 낭비되지 않는다.

### D2. 계정 상태 판정을 인증번호 검증 **뒤로** 놓는 이유 (#115 D4·D5와 순서가 반대다)

- `sendResetCode`는 "발송 제한 → 회원 존재 → 비밀번호 보유" 순서다. 이 엔드포인트는 반대로 "인증번호 검증 → 회원 존재 → 비밀번호 보유"다.
- **근거:** 확인 엔드포인트에는 이메일 단위 발송 제한이 없다. 계정 상태 판정을 먼저 하면 이 경로가 **횟수 제한 없는 계정 열거 오라클**이 된다 — 아무 이메일 + 아무 코드로 호출해 404/409/400 응답만 보면 가입 여부와 소셜 전용 여부를 무제한으로 스캔할 수 있고, #115 D2가 "하루 10회 제한이 막는다"를 근거로 감수한 정보 노출이 무력화된다. 인증번호 검증을 먼저 통과시키면 계정 상태를 알 수 있는 요청자는 이미 그 메일함을 통제하는 사람뿐이다.
- 그 결과 **미가입 이메일은 404가 아니라 400 `EMAIL_VERIFICATION_FAILED`다** — "요청 이력 없음"과 같은 응답이며, 실제로도 미가입 이메일에는 발송 행이 없어 (1)에서 이미 걸린다. (5)의 `findByEmail` 실패는 발송 후 이메일이 변경된 극단적 경우만 도달하는 방어 분기다.
- `SOCIAL_ACCOUNT_ONLY`(409)도 같은 이유로 방어 분기다 — 발송 단계가 이미 409로 막으므로 유효한 발송 행이 존재하는 소셜 전용 계정은 정상 흐름에서 생기지 않는다. 이슈 본문의 "`@WebMvcTest`로 200 · 400 · 409 계약" 요구는 이 분기로 충족된다.
- 조회 쿼리에 **`AndCodeHashIsNotNull`이 반드시 들어가야 한다.** `password_reset_verifications`에는 발송하지 않은 거부 행(`code_hash`·`expires_at` NULL, #115 D6)이 섞여 있고, 이 행이 최신일 수 있다(미가입 상태로 요청 → 가입 → 재설정 요청 → 다시 거부되는 조합). 필터를 빠뜨리면 `getExpiresAt()`/`getCodeHash()`에서 `NullPointerException`이 난다.

```java
// PasswordResetVerificationRepository
Optional<PasswordResetVerification> findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(String email);
```

### D3. 트랜잭션 경계와 롤백 정책 — `noRollbackFor`만 쓰고 `rollbackFor` 서브타입은 만들지 않는다

```java
// AuthService.java
// 검증 실패 시 attempt_count 증가는 커밋해야 무차별 대입 방지가 유지된다.
// 소비·해시 교체·세션 폐기는 마지막 throw 지점 이후에만 실행되어 부분 성공이 생길 수 없다.
@Transactional(noRollbackFor = BusinessException.class)
public void confirmPasswordReset(String email, String code, String newPassword) {
    LocalDateTime now = LocalDateTime.now(clock);
    User user = passwordResetService.validateAndConsumeCode(email, code);

    user.changePassword(passwordEncoder.encode(newPassword), now);
    userRepository.saveAndFlush(user);

    // 재설정은 비로그인 흐름이라 발급할 대상 세션이 없다 — 폐기만 하고 새 토큰 쌍은 만들지 않는다.
    refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);
}
```

- `noRollbackFor = BusinessException.class` 한 개만 쓴다. 선례는 `PasswordResetService.sendResetCode`(#115 D5)·`EmailVerificationService.confirmVerificationCode`(#3)·`AuthService.confirmEmailChange`(#56 D2)로 세 곳이며, 이유는 모두 같다 — 실패 응답과 함께 시도/집계 기록이 커밋되어야 한다.
- **#56과 달리 `rollbackFor`용 예외 서브타입(`EmailChangeConflictException` 같은 것)은 필요 없다.** #56은 `users.email` 유니크 제약 경합이라는 커밋 이후 실패 지점이 있었지만, `users.password_hash`에는 유니크 제약이 없어 `DataIntegrityViolationException`이 발생할 경로가 없다. D1이 모든 `BusinessException` 발생 지점을 소비·교체·폐기 **이전**에 몰아둔 덕분에, "일부만 성공하고 예외가 나는" 상태 자체가 만들어지지 않는다.
- `saveAndFlush`는 `revokeAllActiveByUserId`(벌크 UPDATE)보다 먼저 호출한다 — 벌크 연산이 영속성 컨텍스트를 우회하므로 순서를 뒤집으면 flush 순서가 모호해진다. #56·#114와 같은 순서다.
- `changePassword` → `revokeAllActiveByUserId` 사이에 새 토큰 발급이 없으므로, **#114에 있던 "폐기가 먼저다(새 토큰이 폐기 대상에 걸리지 않게)"라는 주의사항은 이번에 해당되지 않는다.**

### D4. 새 토큰을 발급하지 않는다 — #114와의 의도적 차이

| | #114 `PATCH /api/auth/me/password` | #116 `POST /api/auth/password-resets/confirm` |
|---|---|---|
| 인증 | Bearer 필수 | **불필요(공개 경로)** |
| 재인증 수단 | 현재 비밀번호 | 이메일 인증번호 |
| 기존 Refresh Token | 전체 폐기 | 전체 폐기 (동일) |
| 새 토큰 쌍 | **요청 기기용으로 즉시 발급** → 다른 기기만 로그아웃 | **발급하지 않음** → 전 기기 로그아웃 |
| 응답 | 200 `TokenResponse` | 204 (본문 없음) |

- 근거: 재설정은 비로그인 흐름이라 "요청한 기기의 세션"이라는 대상 자체가 없다. 토큰을 발급하면 인증번호를 맞힌 요청자에게 그 자리에서 로그인 세션을 주는 것이라 **자동 로그인**이 되며, 이슈 본문이 이를 명시적으로 제외했다.
- PRD `AUTH-006`도 "기존 Refresh Token을 모두 폐기해 **재로그인을 요구**한다"고 적어 자동 로그인과 모순된다.
- 프런트는 성공 후 로그인 화면으로 보내고 새 비밀번호로 로그인시킨다.

### D5. `PasswordResetConfirmRequest` — 새 비밀번호는 가입과 같은 정책, 기존 비밀번호와의 동일 여부는 검사하지 않는다

```java
public record PasswordResetConfirmRequest(
    @NotBlank(message = "이메일은 필수입니다.")
    @Size(max = 255, message = "이메일은 최대 255자까지 입력할 수 있습니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    String email,
    @NotBlank(message = "인증번호는 필수입니다.")
    @Pattern(regexp = "\\d{6}", message = "인증번호는 6자리 숫자입니다.")
    String code,
    @NotBlank(message = "새 비밀번호는 필수입니다.")
    @Size(min = 8, max = 100, message = "비밀번호는 8자 이상 100자 이하로 입력해야 합니다.")
    String newPassword) {
}
```

- `email`은 `PasswordResetRequest`, `newPassword`는 `SignupRequest`·`PasswordChangeRequest`의 애노테이션·메시지를 그대로 맞춘다(8~100자, 이슈 본문 요구).
- `code`는 `EmailChangeConfirmRequest`와 같은 6자리 숫자 패턴이다.
- **새 비밀번호가 기존 비밀번호와 같은지는 검사하지 않는다.** #114는 "현재 비밀번호를 이미 입력했으니 같은 값이면 무의미한 요청"이라 400으로 막았지만, 재설정은 비밀번호를 잊은 사용자가 대상이라 "같은 값 금지"가 사용자에게 도움이 되지 않고 대조 결과가 응답으로 새어 나갈 여지만 생긴다. PRD·이슈 모두 요구하지 않는다 (U3).

---

## HTTP 계약

| 구분 | Method / Path | 인증 | 입력 | 성공 응답 | 오류 응답 |
|---|---|---|---|---|---|
| 비밀번호 재설정 확인·적용 | POST `/api/auth/password-resets/confirm` | **불필요 (공개 경로)** | `{"email":"member@finplay.com","code":"123456","newPassword":"newSecret123"}` (`PasswordResetConfirmRequest`) | **204 (본문 없음)** | 필드 누락·형식·길이 위반 400 `VALIDATION_ERROR`; 요청 이력 없음·소비됨·만료·재발송 무효화·코드 불일치·미가입 400 `EMAIL_VERIFICATION_FAILED`(사유 미구분); 같은 인증번호 5회 초과 시도 429 `TOO_MANY_REQUESTS`; 비밀번호가 없는 소셜 전용 계정 409 `SOCIAL_ACCOUNT_ONLY`(방어 분기) — 모두 공통 오류 형식 |

- 오류 우선순위는 400(Bean Validation) → 400/429(인증번호 판정) → 400/409(계정 상태)다 (D1).
- 204를 쓰는 이유는 D4다 — 반환할 토큰도 없고, 비인증 요청자에게 회원 프로필(`MemberResponse`)을 내려줄 이유도 없다. `POST /api/auth/logout`이 이미 204를 쓰는 선례가 있다. (U2 — PRD·이슈 미명시)
- **한계(계약 문서에 명시한다):** 다른 기기의 이미 발급된 Access Token은 즉시 무효화되지 않고 남은 만료 시간 동안 유효하다. Refresh Token 폐기로 갱신만 막힌다.

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/auth/dto/request/PasswordResetConfirmRequest.java` (D5)

### Production files to modify

- `src/main/java/com/finplay/api/auth/domain/PasswordResetVerification.java` — `incrementAttemptCount()`·`consume(now)` 추가 (#115 D6이 미뤄둔 부분)
- `src/main/java/com/finplay/api/auth/repository/PasswordResetVerificationRepository.java` — `findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(email)` 추가 (D2)
- `src/main/java/com/finplay/api/auth/service/PasswordResetService.java` — `MAX_VERIFICATION_ATTEMPTS` 상수와 `validateAndConsumeCode(email, code)` 추가 (D1). `sendResetCode`와 기존 private 메서드는 수정하지 않는다.
- `src/main/java/com/finplay/api/auth/service/AuthService.java` — `PasswordResetService` 의존성 추가, `confirmPasswordReset(email, code, newPassword)` 신설 (D3)
- `src/main/java/com/finplay/api/auth/controller/PasswordResetController.java` — `POST /confirm` 매핑 추가, `AuthService` 의존성 주입 (#56에서 `EmailChangeController`가 `AuthService`를 주입받은 것과 같은 형태)
- `src/main/java/com/finplay/api/auth/config/SecurityConfig.java` — `PUBLIC_POST_PATHS`에 `/api/auth/password-resets/confirm` 추가

### 수정하지 않을 파일

- `src/main/resources/db/migration/*` — 신규 마이그레이션 없음. `attempt_count`·`consumed_at`은 V12에 이미 있다 (ADR-0004).
- `src/main/java/com/finplay/api/common/ErrorCode.java` — 기존 코드만 재사용한다.
- `User.java` — `changePassword`·`hasPassword`(#114·#115)를 그대로 쓴다. **새 메서드를 추가하지 않는다.**
- `RefreshTokenRepository.java` — `revokeAllActiveByUserId`(#56)를 그대로 쓴다.
- `EmailVerificationService`, `EmailChangeService`, `PasswordResetService.sendResetCode`, `PasswordResetRequest`
- `.env.example`, `build.gradle`, `deploy/README.md` — `PASSWORD_RESET_SECRET`은 #115에서 이미 배선됐다.

### Test files

- `PasswordResetVerificationRepositoryTest`(`@DataJpaTest`, 기존 파일에 케이스 추가) — `findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc`가 (a) 재발송으로 여러 행이 쌓였을 때 최신 발송 행만 반환하고 (b) **거부 행(`code_hash` NULL)이 더 최신이어도 건너뛰며** (c) 다른 이메일 행을 반환하지 않는지 검증.
- `PasswordResetServiceTest`(Mockito, 기존 파일에 케이스 추가) — `validateAndConsumeCode` 대상: 요청 이력 없음/거부 행만 존재/소비됨/만료/재발송 무효화 400, 5회 초과 429(증가+즉시 만료 확인), 불일치 400(증가 확인, 소비 안 됨), 미가입 400, 소셜 전용 409, 성공 시 `consume` 호출과 반환 `User` 검증. 실패 경로에서 `consume`이 호출되지 않음을 명시적으로 확인한다.
- `AuthServiceTest`(Mockito, 기존 파일에 케이스 추가) — `confirmPasswordReset` 성공 시 `validateAndConsumeCode` → `user.changePassword`(인코딩된 해시) → `saveAndFlush` → `revokeAllActiveByUserId` 순서를 `InOrder`로 고정, **새 토큰 쌍을 발급하지 않음**(`jwtTokenProvider`·`refreshTokenRepository.save` 미호출) 검증, `validateAndConsumeCode`가 예외를 던지면 이후 호출이 전혀 없음을 검증.
- `PasswordResetControllerTest`(`@WebMvcTest`, 기존 파일에 케이스 추가) — 204 성공(본문 없음), `email`·`code`·`newPassword` 각각의 검증 실패 400(7자 비밀번호 포함), 서비스 예외의 400/429/409 매핑, **인증 헤더 없이 호출해도 401이 아님**.
- `PasswordResetConfirmIntegrationTest`(Testcontainers MySQL, Fake `EmailSender`, 신규 클래스 또는 `PasswordResetIntegrationTest`에 케이스 추가) — 발송→확인 전체 흐름, 새 비밀번호 로그인 성공·기존 비밀번호 401, 같은 코드 재사용 실패, 5회 실패 후 정답도 거부, 만료·재발송 무효화 거부, 타인 이메일 격리, 확인 성공 후 기존 Refresh Token으로 `/api/auth/refresh` 401, **실패 시 `attempt_count` 증가 커밋 + `users.password_hash` 불변**, 확인 전후 이메일·닉네임·`social_accounts`·계좌·잔액·주문·체결 불변.

### Documentation files to modify after implementation

- `docs/prd.md` — `AUTH-006` 309행의 구현 단계 문구 갱신, 확인 단계 계약(한 요청으로 인증번호+새 비밀번호, 8~100자, 새 토큰 미발급) 반영, 엔드포인트 목록(550행)에 `/confirm` 추가
- `docs/api-routes.md`·`docs/api-contracts.md` — planner 동기화 모드에서 함께 갱신
- `docs/specs/002-auth-account/tasks.md` — Issue #116 절 체크

---

## Task 1: 엔티티·리포지터리 확장

- [ ] `PasswordResetVerification.incrementAttemptCount()`·`consume(now)`를 추가한다 (`EmailChangeVerification`의 동명 메서드와 동일 시그니처).
- [ ] `PasswordResetVerificationRepository.findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc(email)`를 추가한다 (D2).
- [ ] `@DataJpaTest`로 최신 발송 행 반환, **거부 행(`code_hash` NULL) 건너뜀**, 다른 이메일 미반환을 실패 테스트로 먼저 고정한다.

## Task 2: `PasswordResetService.validateAndConsumeCode` 검증·소비

- [ ] D1의 판정 순서(요청 없음/소비됨/만료 → 5회 초과 429 → 불일치 → 미가입 → 소셜 전용 409 → 소비)를 실패 테스트로 먼저 고정한다.
- [ ] `MAX_VERIFICATION_ATTEMPTS = 5` 상수를 추가하고 기존 `hmac`을 재사용한다. 이 메서드에는 `@Transactional`을 선언하지 않는다 — 호출자 트랜잭션에 편입된다.
- [ ] 실패 경로에서 `consume`이 호출되지 않고, 5회 초과·불일치 경로에서만 `incrementAttemptCount`가 호출됨을 Mockito로 검증한다.
- [ ] `sendResetCode`와 기존 private 메서드를 수정하지 않았음을 확인한다.

## Task 3: `AuthService.confirmPasswordReset`·컨트롤러·공개 경로

- [ ] `AuthService`에 `PasswordResetService` 의존성을 추가하고 `confirmPasswordReset(email, code, newPassword)`를 `@Transactional(noRollbackFor = BusinessException.class)`로 구현한다 (D3) — 검증·소비 → `changePassword(encode(newPassword), now)` → `saveAndFlush` → `revokeAllActiveByUserId` 순서, **새 토큰 발급 없음**.
- [ ] `PasswordResetConfirmRequest`(D5)와 `PasswordResetController.confirmReset`(`POST /api/auth/password-resets/confirm`, 204 본문 없음)를 구현하고 `SecurityConfig.PUBLIC_POST_PATHS`에 경로를 추가한다.
- [ ] `AuthServiceTest`로 호출 순서를 `InOrder`로 고정하고 토큰 미발급을 검증한다.
- [ ] `@WebMvcTest`로 204·400(필드별)·429·409 매핑과 비인증 접근 허용을 검증한다.

## Task 4: MySQL 통합 검증과 전체 회귀

- [ ] Testcontainers MySQL로 발송→확인 성공, 새 비밀번호 로그인 성공·기존 비밀번호 401, 같은 코드 재사용 거부, 5회 실패 후 정답도 거부, 만료·재발송 무효화·타인 이메일 격리를 검증한다.
- [ ] 확인 성공 후 기존 Refresh Token으로 `/api/auth/refresh`가 401임을 검증한다(전 기기 로그아웃).
- [ ] 검증 실패 시 `attempt_count` 증가가 커밋되고 `users.password_hash`는 그대로임을(부분 성공 없음) 실제 커밋 경계로 확인한다.
- [ ] 확인 전후 이메일·닉네임·`social_accounts`·계좌·시드머니·잔액·주문·체결 불변을 검증한다.
- [ ] 대상 테스트 전체와 기존 회귀 스위트, Spotless, `./gradlew build`(SpotBugs·JaCoCo 포함)를 실제 실행하고 결과를 기록한다.

## Task 5: PRD·API 문서 동기화

- [ ] `docs/prd.md` `AUTH-006`의 구현 단계 문구(309행)를 갱신하고, 확인 단계 계약(인증번호+새 비밀번호 동시 수신, 새 비밀번호 8~100자, 새 토큰 미발급·전 기기 로그아웃)을 반영한다. 엔드포인트 목록(550행 부근)에 `POST /api/auth/password-resets/confirm`을 추가한다.
- [ ] U1(5회 초과 응답 코드)의 최종 결론을 PRD 문구와 일치시킨다.
- [ ] `docs/api-routes.md`·`docs/api-contracts.md`를 실제 매핑과 일치하게 갱신한다(Access Token 잔존 한계 명시 포함, planner 동기화 모드).
- [ ] `docs/specs/002-auth-account/tasks.md`의 Issue #116 절을 체크한다.

---

## 완료 체크리스트

- [ ] 유효한 인증번호로 비밀번호가 한 번 교체되고 같은 인증번호 재사용이 거부되는 테스트가 통과한다.
- [ ] 교체 후 새 비밀번호 로그인 성공·기존 비밀번호 401이 실제 MySQL로 검증된다.
- [ ] 요청 이력 없음·불일치·만료·재발송 무효화·소비됨·타인 이메일이 모두 400 `EMAIL_VERIFICATION_FAILED`로 **사유 구분 없이** 거부되고 비밀번호가 바뀌지 않는다.
- [ ] 같은 인증번호 5회 실패가 즉시 무효화로 이어져 이후 정답도 거부됨이 검증된다.
- [ ] 검증 실패 시 `attempt_count` 증가가 커밋되고, 소비·해시 교체·세션 폐기는 하나도 일어나지 않는다(부분 성공 없음).
- [ ] 재설정 성공 후 기존 Refresh Token으로 갱신이 401이며, 응답에 새 토큰 쌍이 없다.
- [ ] OAuth 연결·계좌·시드머니·잔액·주문·체결이 전 시나리오에서 불변임이 확인된다.
- [ ] 인증 헤더 없이 호출해도 401이 아님이 검증된다.
- [ ] 신규 Flyway 마이그레이션·신규 `ErrorCode`가 추가되지 않았다.
- [ ] `docs/prd.md`·`docs/api-routes.md`·`docs/api-contracts.md`가 실제 구현과 일치한다.
- [ ] `./gradlew build`(Spotless·SpotBugs·JaCoCo 포함)가 통과한다.

---

## 미확정·PRD 불일치 (임의로 정하지 않고 보고하는 항목)

**단계 구성 (a)안, 전 기기 로그아웃·새 토큰 미발급, 원자성 요구는 착수 전 확정된 결정이며 여기 목록에 포함하지 않는다.**

**U1. 5회 초과 시도의 응답 코드가 PRD와 이슈 본문 사이에서 어긋난다 (가장 중요).**

- PRD `AUTH-006`(297행)은 **"5회를 초과하면 해당 인증번호를 즉시 무효화하고 429 `TOO_MANY_REQUESTS`로 응답한다"**고 명시한다. #56의 이메일 변경 확인도 같은 상황에서 429다.
- 이슈 #116 본문은 구현 범위에 "만료 · 5회 초과 · … 를 모두 거부하고, **실패 사유는 구분하지 않습니다**"라고 적었고, 테스트 기준의 `@WebMvcTest` 항목은 **"200 · 400 · 409 계약"만 나열해 429가 빠져 있다.**
- 두 문장을 그대로 합치면 "5회 초과도 400으로 뭉갠다"가 되어 PRD와 충돌한다.
- **이 계획은 PRD와 #56 선례를 따라 429로 작성했다**(D1의 (3), HTTP 계약 표). 오케스트레이터가 전달한 "실패 사유를 구분하지 않는다" 목록(요청 없음·소비됨·만료·무효화·불일치)에도 5회 초과가 포함되어 있지 않다는 점을 근거로 삼았다.
- **확정 필요:** 429를 유지하면 이슈 본문의 테스트 기준 문구를 고쳐야 하고, 400으로 통일하면 PRD 297행을 고쳐야 한다. 어느 쪽이든 Task 5에서 문서를 맞춘다.
- 참고로 두 선택의 차이는 크지 않다 — 5회 초과 시 인증번호가 즉시 만료되므로 그 다음 요청부터는 어느 쪽이든 400 `EMAIL_VERIFICATION_FAILED`다. 차이는 "5회를 넘긴 그 한 번의 응답"뿐이다.

**U2. 성공 응답 상태·본문을 204(본문 없음)로 정했다.** PRD·이슈 모두 이 엔드포인트의 성공 응답 스키마를 명시하지 않는다. 대안은 200 + `MemberResponse`(#56 관례)인데, 이번엔 요청자가 비인증 상태라 회원 프로필을 응답으로 내려줄 근거가 없어 `POST /api/auth/logout`의 204 선례를 따랐다. 프런트가 성공 후 표시할 이메일·닉네임이 필요하다면 200 + `MemberResponse`로 바꿔야 한다.

**U3. 새 비밀번호가 기존 비밀번호와 같아도 허용한다 (D5).** #114는 같은 값을 400으로 막지만, 이번에는 검사하지 않기로 했다 — 비밀번호를 잊은 사용자가 대상이고, `passwordEncoder.matches`로 대조하면 "입력한 값이 기존 비밀번호와 같은가"라는 정보가 응답으로 드러난다. PRD·이슈 모두 요구하지 않는다. 팀이 일관성을 우선한다면 400 `VALIDATION_ERROR`로 막는 쪽으로 바꿀 수 있다.

**U4. 확인 엔드포인트 자체에는 요청 횟수 제한이 없다.** 방어는 (a) 발송 단계의 이메일 단위 제한과 (b) 인증번호별 5회 시도 제한 두 가지뿐이다. 활성 인증번호가 없는 이메일에 대한 무한 호출은 항상 400이라 실익이 없지만, 이 경로에는 429가 걸리지 않는다. IP·디바이스 단위 제한은 #115 D4에서 이미 범위 밖으로 정리했고 이번에도 그대로 둔다.

**U5. 확인 시점에 발송 시점의 이메일이 바뀌어 있는 경우의 처리.** `password_reset_verifications`에는 `user_id` FK가 없어(#115 D6·U4) 확인 시점에 이메일로 회원을 다시 조회한다. 발송 후 확인 전에 그 회원이 `POST /api/auth/email-changes/confirm`으로 이메일을 바꿨다면 `findByEmail`이 비어 400이 된다(D1의 (5)). 이 계획은 그것을 정상 동작으로 본다 — 재설정 인증번호는 "그 시점의 그 주소"에 보낸 것이기 때문이다. PRD는 이 교차 시나리오를 언급하지 않는다.

**U6. `MAX_VERIFICATION_ATTEMPTS`·HMAC·인증번호 정책 상수가 네 번째로 복제된다.** `EmailVerificationService`·`EmailChangeService`·`PasswordResetService`(발송)에 이어 확인 로직까지 같은 판정 순서가 반복된다. #115 U5에서 이미 별도 리팩터링 이슈로 제안한 사항이며, 이번 이슈에서도 공통화하지 않는다 — 신규 API 구현과 4중 리팩터링을 같은 커밋에 섞지 않기 위해서다.

**U7. 재설정 완료 알림 메일이 없다.** 이슈가 명시적으로 제외했다. 계정 탈취 시 피해자가 알아챌 수단이 없다는 뜻이며, 필요하면 PRD `AUTH-006`에 항목을 추가하는 별도 이슈가 필요하다.
