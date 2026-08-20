# Issue #56 이메일 변경 확인 및 적용 API 구현 계획

> **For agentic workers:** 각 작업은 실패 테스트를 먼저 작성한다. 5회 시도 초과·만료·재사용·타인 요청·동시 중복 이메일 경합은 각각 별도 테스트로 고정하며, 서로 대체하거나 합쳐 보고하지 않는다.

**Goal:** `POST /api/auth/email-changes/confirm`을 신설해, Issue #55에서 발송한 새 이메일 인증번호를 확인하고 성공 시에만 `users.email`을 원자적으로 변경한다. 인증번호 소비·이메일 변경·기존 Refresh Token 전체 폐기가 부분 성공 없이 한 트랜잭션 단위로 처리되어야 하며, 변경 완료 후에는 재로그인이 필요하다.

**관련 정본:** GitHub Issue #56, PRD `AUTH-005`(`ai/prd.md` 57-65행), `ai/specs/002-auth-account/spec.md`, `ai/specs/002-auth-account/plan.md`, Issue #55 계획(`issue-55-plan.md`, 인증번호 발송·스키마), Issue #54 계획(`issue-54-plan.md`, 재인증 판별·닉네임 변경 트랜잭션 선례), ADR-0002, ADR-0003, ADR-0004, `docs/conventions.md`

**선행:** Issue #8·#53·#54·#55가 `dev`에 있다. 실제 코드베이스 확인 결과 #55는 계획대로 구현되어 있다 — `EmailChangeVerification`(`user_id`·`new_email`·`code_hash`·`attempt_count`·`expires_at`·`last_sent_at`·`consumed_at`), `EmailChangeVerificationRepository`(`countByUserIdAndCreatedAtAfter`, `findByUserIdAndNewEmailAndConsumedAtIsNullAndExpiresAtAfter`), `EmailChangeService.requestEmailChange`(재인증 판별·중복 409·발송 제한 429·재발송 무효화), `EmailChangeController`(`POST /api/auth/email-changes`, 202), `ReauthTokenRepository.consumeIfValidForUser(tokenHash, userId, now)`(원자적 1회 소비, `@Param` 부착 확인됨)가 모두 존재한다. 다만 `EmailChangeVerification`에는 `attempt_count`를 증가시키는 메서드와 소비(`consumedAt` 설정) 메서드가 아직 없다(#55 D2에서 "사용처 없음"으로 의도적으로 생략) — 이번 이슈에서 추가해야 한다.

---

## Architecture

- 확인 로직은 `AuthService`에 `confirmEmailChange(userId, newEmail, code)`를 신설하는 방식으로 둔다(사용자 확정, 미확정 8번). `EmailVerificationService`가 발송+확인을 모두 갖는 선례보다, `AuthService`가 항상 최종 `User` 상태 변경을 담당해온 기존 불변 규칙(`changeNickname` 등)이 이번 케이스(직접 `users.email` 변경 + `RefreshToken` 전체 폐기)에 더 강하게 적용된다 — `EmailVerificationService.confirmVerificationCode`는 확인 성공 시 `User`를 건드리지 않고 토큰만 발급하지만, 이번 확인은 성공 시 바로 `User`·`RefreshToken`을 직접 변경하므로 성격이 다르다.
- `EmailChangeService`는 인증번호 검증·소비 전용 역할로 좁힌다 — `validateAndConsumeCode(userId, newEmail, code)`를 추가하고 D1의 검증 순서(조회 → 소비 상태 → 만료 → 시도 횟수 초과 → 코드 일치, `incrementAttemptCount`/`consume` 호출 포함)를 그대로 옮긴다. 발송 로직(`requestEmailChange`)은 수정하지 않는다. `RefreshTokenRepository`는 이 서비스에 주입하지 않는다.
- `AuthService`가 `EmailChangeService`를 새 의존성으로 주입받는다(반대 방향 아님) — `EmailChangeService`는 `AuthService`를 알지 못한다.
- `AuthService.confirmEmailChange`가 트랜잭션 경계를 갖고(D2), `emailChangeService.validateAndConsumeCode` 호출 → `user.changeEmail(newEmail, now)` → `userRepository.saveAndFlush(user)` → `refreshTokenRepository.revokeAllActiveByUserId(userId, now)` 순서로 직접 수행한다. `RefreshTokenRepository`·`SocialAccountRepository`는 이미 `AuthService`의 기존 의존성이라 새로 주입할 필요가 없다.
- `User`에 `changeEmail(newEmail, now)` 인스턴스 메서드를 추가한다 — 기존 `changeNickname`과 동일한 setter 금지·의도 노출 패턴.
- `EmailChangeVerification`에 `incrementAttemptCount()`·`consume(now)`를 추가한다 — 기존 `EmailVerification`의 동명 메서드와 동일한 역할.
- `EmailChangeVerificationRepository`에 확인 대상 최신 행을 조회하는 `findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(userId, newEmail)`를 추가한다 — `EmailVerificationRepository.findFirstByEmailOrderByCreatedAtDesc`와 동일 패턴을 (userId, newEmail) 쌍으로 좁힌 버전.
- `RefreshTokenRepository`에 회원 전체 활성 토큰을 한 번에 폐기하는 `revokeAllActiveByUserId(userId, now)` 벌크 `@Modifying` 쿼리를 추가한다 — 기존 `revokeIfActiveAndNotExpired(id, now)`(단건 id 대상)와 달리 `userId` 전체를 대상으로 한다.
- 신규 예외 `EmailChangeConflictException`(`com.finplay.api.auth.exception` 패키지, 신설)을 추가한다(D2) — 이메일 유니크 제약 경합 전용, `BusinessException`을 상속하고 `ErrorCode.DUPLICATE_RESOURCE`로 생성한다. 실제 코드베이스 확인 결과 `BusinessException`은 `com.finplay.api.common`에 단일 클래스로만 존재하고 서브타입 선례가 전혀 없다 — 이번이 첫 서브타입 추가이자 첫 `auth.exception` 패키지 신설이다. 도메인별 패키지 구조(ADR-0002)를 따라 인증/계정 도메인 예외이므로 `auth` 패키지 아래에 둔다.
- `SecurityConfig`는 수정하지 않는다 — `/api/auth/email-changes/confirm`은 `/api/auth/email-changes`와 마찬가지로 공개 목록에 없어 기본 `anyRequest().authenticated()`로 보호된다.

## Tech Stack

Java 17, Spring Boot 4.1, Spring Data JPA, MySQL 8.4(Testcontainers), Spring Security(`@AuthenticationPrincipal`), JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

---

## 요구사항 ID와 수용 기준

### PRD `AUTH-005` (확인·실변경·Refresh Token 폐기 부분)

- 이메일 변경은 새 이메일로 보낸 인증번호 확인 성공 시에만 반영한다.
- 이메일 변경이 완료되면 기존 Refresh Token을 모두 폐기하고 재로그인을 요구한다.
- 내 정보 변경 전후 계좌·시드머니·잔액·주문·체결·투자일기는 그대로 유지한다.
- (spec.md 비즈니스 규칙) OAuth 회원이 이메일을 변경해도 `provider + providerUserId` 연결을 유지하고 이후 로그인에서 제공자 이메일로 자동 덮어쓰지 않는다 — 이번 이슈는 `social_accounts`를 전혀 건드리지 않으므로 자연히 만족된다.

### Issue #56 수용 시나리오

1. Given #55에서 발급된 유효한 인증번호(5분 이내, 5회 미만 시도, 미소비)가 있는 상태
2. When 같은 회원이 같은 새 이메일·올바른 인증번호로 `POST /api/auth/email-changes/confirm` 호출
3. Then `users.email`이 새 이메일로 한 번 변경되고, 같은 인증번호로 재호출하면 거부된다.
4. And 인증번호가 없거나(요청 이력 없음)·틀리거나·만료됐거나·재발송으로 무효화된 경우 이메일은 변경되지 않는다.
5. And 5회 초과 시도는 인증번호를 즉시 무효화하고 429로 응답하며 이후 같은 인증번호는 만료 처리와 동일하게 거부된다.
6. And 다른 회원이 인증된 상태로 이 인증번호·새 이메일 조합을 확인하려 해도 이메일은 변경되지 않는다.
7. And 확인 성공 직후 동시에 다른 경로로 같은 새 이메일이 다른 회원에게 먼저 배정된 경합 상황에서는 DB 유니크 제약을 최종 방어선으로 409가 되고, 이 경우 인증번호 소비·Refresh Token 폐기도 함께 되돌아간다(원자성).
8. And 확인 성공 후 변경 전 발급된 Refresh Token으로 `/api/auth/refresh`를 호출하면 401이며 재로그인이 필요하다.
9. And 확인 성공·실패와 무관하게 계좌·잔액·주문·체결·투자일기, `social_accounts`의 `provider`+`providerUserId` 연결은 변하지 않는다.

---

## 범위와 제외

### 포함

- `POST /api/auth/email-changes/confirm` — Bearer 인증 필수, 새 이메일+인증번호 검증, 성공 시 `users.email` 원자적 변경과 회원의 기존 Refresh Token 전체 폐기.
- `EmailChangeVerification.incrementAttemptCount()`/`consume(now)`, `EmailChangeVerificationRepository.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc`, `RefreshTokenRepository.revokeAllActiveByUserId(userId, now)`, `User.changeEmail(newEmail, now)` 추가.
- `ai/api-routes.md` 동기화.

### 제외

- 인증번호 발송(Issue #55, 이미 완료).
- 기존 이메일 회원과 OAuth 계정 자동 연결·병합.
- Access Token 즉시 블랙리스트(재로그인 요구는 Refresh Token 폐기로만 강제하며, 이미 발급된 Access Token은 남은 만료 시간 동안 유효할 수 있다 — 이슈 본문이 명시적으로 제외).
- 비밀번호 변경·재설정, 관리자·1차·2차 고도화 기능.
- 만료된 `email_change_verifications` 정리 배치(spec.md 범위 제외와 동일하게 유지).

---

## 설계 결정

### D1. 검증 순서 — 조회 → 소비 상태 → 만료 → 시도 횟수 초과 → 코드 일치

```java
EmailChangeVerification verification = emailChangeVerificationRepository
    .findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(userId, newEmail)
    .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED));

if (verification.getConsumedAt() != null || !verification.getExpiresAt().isAfter(now)) {
    throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED);
}
if (verification.getAttemptCount() >= MAX_VERIFICATION_ATTEMPTS) {
    verification.incrementAttemptCount();
    verification.expire(now);
    throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
}
if (!verification.getCodeHash().equals(hmac(code))) {
    verification.incrementAttemptCount();
    throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_FAILED);
}
```

- 이 검증 로직은 `EmailChangeService.validateAndConsumeCode(userId, newEmail, code)`(사용자 확정, 미확정 8번 — 발송 로직만 갖던 `requestEmailChange`와 나란히, 검증·소비 전용 메서드로 좁혀 추가) 안에 그대로 둔다. `EmailChangeService`가 하던 역할 자체(검증 로직 보유)는 바뀌지 않고, 애초 계획에 있던 메서드명 `confirmEmailChange`만 `validateAndConsumeCode`로 바뀐다 — 이메일 변경·Refresh Token 폐기·트랜잭션 경계는 `AuthService.confirmEmailChange`(D2)로 옮긴다.
- `EmailVerificationService.confirmVerificationCode`와 동일한 판정 순서·오류 코드를 그대로 옮긴다(코드 재사용은 하지 않고 새 `EmailChangeVerification` 타입에 맞춰 다시 작성 — #55의 "세 번째 중복이 보일 때만 공통화" 방침을 그대로 따른다).
- 조회는 `(userId, newEmail)`로 좁혀, 인증된 본인의 새 이메일 시도 이력에서만 최신 1건을 찾는다 — 다른 회원의 유효한 인증번호를 대상으로 확인을 시도해도 자동으로 "요청 없음"과 동일한 결과가 된다(D5, 미확정 5번).
- `consumedAt != null` 검사로 같은 인증번호 재사용을 거부한다(수용 시나리오 3).
- `expiresAt`이 지난 경우는 자연 만료와 재발송에 의한 즉시 무효화(`expire(now)`, #55 구현) 모두 같은 조건으로 걸러진다 — 별도 분기가 필요 없다.

### D2. 트랜잭션 경계와 롤백 정책 — 새 예외 서브타입으로 `noRollbackFor` 범위 좁히기 (사용자 확정)

```java
// AuthService.java
@Transactional(noRollbackFor = BusinessException.class, rollbackFor = EmailChangeConflictException.class)
public MemberResponse confirmEmailChange(Long userId, String newEmail, String code) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    LocalDateTime now = LocalDateTime.now(clock);

    // EmailChangeService.validateAndConsumeCode 내부에서 D1의 검증·소비를 수행한다.
    // 일반 BusinessException(불일치·만료·5회초과) 실패 시 attempt_count 증가·즉시 만료가 커밋된다 (noRollbackFor).
    emailChangeService.validateAndConsumeCode(userId, newEmail, code);

    user.changeEmail(newEmail, now);
    try {
        userRepository.saveAndFlush(user);
    } catch (DataIntegrityViolationException ex) {
        throw new EmailChangeConflictException();
    }
    refreshTokenRepository.revokeAllActiveByUserId(userId, now);

    SignupMethod signupMethod = socialAccountRepository.findByUserId(userId)
        .map(socialAccount -> SignupMethod.fromProvider(socialAccount.getProvider()))
        .orElse(SignupMethod.EMAIL);
    return MemberResponse.from(user, signupMethod);
}
```

- `noRollbackFor = BusinessException.class`로 5회 시도 카운트 증가·즉시 만료를 예외 발생과 무관하게 커밋시킨다 — 그렇지 않으면 무차별 대입 방지가 무력화된다.
- 이메일 유니크 제약 위반은 더 이상 일반 `BusinessException`으로 던지지 않고, 신규 서브타입 `EmailChangeConflictException extends BusinessException`(`ErrorCode.DUPLICATE_RESOURCE`로 생성)으로 던진다. Spring `RollbackRuleAttribute`는 예외 클래스와 규칙 클래스 사이 상속 깊이(depth)가 가장 가까운 규칙을 우선 적용한다 — `EmailChangeConflictException`은 `BusinessException`의 서브클래스라 `noRollbackFor = BusinessException.class`에도 매칭되지만, `rollbackFor = EmailChangeConflictException.class`가 depth 0으로 더 가깝게 매칭돼 우선 적용되어 전체 롤백된다. 인증번호 불일치·만료·5회초과가 던지는 일반 `BusinessException`은 depth 1로 `noRollbackFor` 규칙만 매칭되어 커밋된다.
- `DataIntegrityViolationException`을 잡는 지점에서 `throw new EmailChangeConflictException()`으로 변환한다. `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`는 더 이상 호출하지 않는다 — 예외 타입 자체가 롤백 여부를 결정한다.
- 순서상 `refreshTokenRepository.revokeAllActiveByUserId`는 `userRepository.saveAndFlush(user)` 성공 이후에만 호출해, 이메일 변경이 실제로 적용된 경우에만 토큰이 폐기되게 한다.
- `EmailChangeConflictException`은 `com.finplay.api.auth.exception` 패키지에 새로 둔다 — 코드베이스에 `BusinessException` 서브타입 선례가 전혀 없어(기존에는 모두 `new BusinessException(ErrorCode.X)`로 직접 생성) 이번이 첫 서브타입 추가 사례이자 첫 `auth.exception` 패키지 신설이다.
- 이 방식은 미확정 1번의 대안 (b)를 사용자가 채택한 것이다 — 대안 (a)(별도 트랜잭션 2개 분리), 수동 `setRollbackOnly()` 호출 방식은 모두 폐기했다.
- `SignupMethod` 계산은 `AuthService.getMe`/`changeNickname`과 동일하게 `socialAccountRepository.findByUserId`로 조회한다 — `EmailChangeService.requestEmailChange`가 EMAIL·OAuth 회원 모두를 대상으로 하므로(`verifyReauthProof`가 두 경우를 분기), 확인 성공 응답도 실제 가입 방식을 반영해야 하위 OAuth 전용 회원에게 잘못된 `signupMethod`를 반환하지 않는다.

### D3. `attempt_count`/`consumed_at` 갱신 메서드 — `EmailVerification`과 동일하게 추가

```java
public int incrementAttemptCount() {
    return ++this.attemptCount;
}

public void consume(LocalDateTime now) {
    this.consumedAt = now;
}
```

- `EmailChangeVerification`에는 현재 `create`·`expire`만 있다(#55 D2에서 의도적으로 생략). 이번 이슈에서 `EmailVerification`의 `incrementAttemptCount()`와 동일한 시그니처로 추가하고, `EmailVerification`에는 없는 `consume(now)`을 새로 추가한다 — `EmailVerification`은 확인 성공 시 별도 토큰을 발급(`confirm(now, tokenHash, tokenExpiresAt)`)하지만 이번 엔티티는 성공 시 바로 소비 처리만 하면 되므로 시그니처가 다르다(#55 D1·D2에서 이미 확정된 두 엔티티의 목적 차이).

### D4. `RefreshTokenRepository.revokeAllActiveByUserId` — 벌크 폐기

```java
@Modifying
@Query("""
    UPDATE RefreshToken refreshToken
    SET refreshToken.revokedAt = :now
    WHERE refreshToken.user.id = :userId
        AND refreshToken.revokedAt IS NULL
    """)
int revokeAllActiveByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);
```

- 만료 여부(`expiresAt`)는 조건에 넣지 않는다 — 이미 만료된 토큰도 `revokedAt`을 남겨 "이메일 변경 시점에 전체 폐기했다"는 이력을 명확히 한다. `revokeIfActiveAndNotExpired`(단건, 재발급/로그아웃용)와는 목적이 달라 별도 메서드로 추가하고 기존 메서드는 수정하지 않는다.
- 물리 삭제가 아니라 소프트 삭제(`revokedAt` 설정)다 — 기존 로그아웃·재발급과 동일한 폐기 방식.
- 반환값(영향받은 행 수)은 이번 이슈에서 별도로 검증하지 않는다 — 0건(원래 활성 토큰이 없던 회원)도 정상 케이스라 실패로 취급할 이유가 없다.

### D5. `EmailChangeConfirmRequest` — 재인증 증명 재요구 없음

```java
public record EmailChangeConfirmRequest(
    @NotBlank @Email @Size(max = 255) String newEmail,
    @NotBlank @Size(max = 6) @Pattern(regexp = "\\d{6}") String code) {
}
```

- `currentPassword`/`reauthToken`을 다시 받지 않는다 — #55 D3에서 이미 "재인증이 필요한 유일한 시점은 발송 단계이며 확인 단계에서는 다시 요구하지 않는다"고 확정했고, PRD도 확인 단계의 재인증 요구를 언급하지 않는다.
- `EmailVerificationConfirmRequest`(email, code)와 같은 필드 구성이며 검증 애노테이션도 동일하게 맞춘다.

---

## HTTP 계약

| 구분 | Method / Path | 인증 | 입력 | 성공 응답 | 오류 응답 |
|---|---|---|---|---|---|
| 이메일 변경 확인 | POST `/api/auth/email-changes/confirm` | `Authorization: Bearer` 필수 | `{"newEmail":"new@finplay.com","code":"123456"}` (`EmailChangeConfirmRequest`) | 200 `MemberResponse`(`id`, 변경된 `email`, `nickname`, `signupMethod`) | 인증 헤더 없음·만료·변조 401 `UNAUTHORIZED`; `newEmail`·`code` 형식 위반 400 `VALIDATION_ERROR`; 요청 없음·타인 요청·불일치·만료·재발송 무효화·재사용 400 `EMAIL_VERIFICATION_FAILED`; 5회 초과 시도 429 `TOO_MANY_REQUESTS`; 동시 중복 이메일 경합 409 `DUPLICATE_RESOURCE` 공통 오류 형식 |

- 200을 쓰는 이유: 즉시 갱신된 회원 정보를 확인할 수 있는 리소스 상태 변경이며, `PATCH /api/auth/me/nickname`과 동일하게 `MemberResponse`를 그대로 반환하는 편이 프론트엔드가 이미 아는 응답 스키마를 재사용할 수 있어 일관적이다(D-미확정 3 참고 — PRD가 명시하지 않은 선택).
- 새 Access/Refresh Token은 이 응답에 포함하지 않는다 — PRD가 "재로그인을 요구"한다고 명시하므로, 서버가 자동으로 새 토큰을 발급해 재로그인을 대신하지 않는다.

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/auth/dto/request/EmailChangeConfirmRequest.java`
- `src/main/java/com/finplay/api/auth/exception/EmailChangeConflictException.java` — `BusinessException` 상속, `ErrorCode.DUPLICATE_RESOURCE` 전용 (D2, 사용자 확정). 코드베이스 최초의 `BusinessException` 서브타입.

### Production files to modify

- `src/main/java/com/finplay/api/auth/domain/EmailChangeVerification.java` — `incrementAttemptCount()`, `consume(now)` 추가 (D3)
- `src/main/java/com/finplay/api/auth/repository/EmailChangeVerificationRepository.java` — `findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(userId, newEmail)` 추가 (D1)
- `src/main/java/com/finplay/api/auth/repository/RefreshTokenRepository.java` — `revokeAllActiveByUserId(userId, now)` 추가 (D4). 호출 지점은 `EmailChangeService`가 아니라 `AuthService.confirmEmailChange`다(D8, 사용자 확정).
- `src/main/java/com/finplay/api/auth/domain/User.java` — `changeEmail(newEmail, now)` 추가
- `src/main/java/com/finplay/api/auth/service/EmailChangeService.java` — 검증·소비 전용 `validateAndConsumeCode(userId, newEmail, code)` 추가 (D1). `RefreshTokenRepository`는 주입하지 않는다(D8, 사용자 확정으로 계획 변경 — 애초 예정이던 `RefreshTokenRepository` 신규 주입은 취소).
- `src/main/java/com/finplay/api/auth/service/AuthService.java` — `EmailChangeService` 의존성 추가, `confirmEmailChange(userId, newEmail, code)` 신설 (D2·D8, 사용자 확정). 신설 전 실제 파일을 확인한 결과 `RefreshTokenRepository`·`SocialAccountRepository`는 이미 필드로 존재해 추가 주입이 필요 없다.
- `src/main/java/com/finplay/api/auth/controller/EmailChangeController.java` — `POST /api/auth/email-changes/confirm` 추가, `AuthService.confirmEmailChange` 호출(D8) — `AuthService` 의존성을 새로 주입받는다(현재는 `EmailChangeService`만 주입받고 있음, 실제 파일 확인됨).

### 수정하지 않을 파일

- `src/main/resources/db/migration/*` — 이번 이슈는 신규 마이그레이션이 없다. `attempt_count`·`consumed_at` 컬럼은 V6에 이미 존재(#55 D2)하고, `UNIQUE(email)` 제약은 V2에 이미 존재한다.
- `EmailVerificationService`, `EmailVerification`, `EmailVerificationRepository` — 이번 이슈와 무관.
- `SecurityConfig` — 신규 경로가 공개 목록에 없어 기본 보호로 충분.
- `SocialAccount`, `SocialAccountRepository` — OAuth 연결은 변경하지 않는다(단, `AuthService.confirmEmailChange`가 기존 `SocialAccountRepository` 필드로 `signupMethod`만 조회한다).

### Test files

- `EmailChangeVerificationRepositoryTest`(`@DataJpaTest`, 기존 파일에 케이스 추가) — `findFirstByUserIdAndNewEmailOrderByCreatedAtDesc`가 재발송으로 여러 행이 쌓였을 때 최신 행만 반환하는지 검증.
- `RefreshTokenRepositoryTest`(기존 파일에 케이스 추가) — `revokeAllActiveByUserId`가 대상 회원의 활성 토큰만 전부 폐기하고 다른 회원·이미 폐기된 토큰은 건드리지 않음을 검증.
- `EmailChangeServiceTest`(Mockito, 기존 파일에 케이스 추가) — `validateAndConsumeCode(userId, newEmail, code)` 대상으로 요청 없음/타인 요청/코드 불일치/만료/재발송 무효화 400, 5회 초과 429(6번째 시도에서 즉시 만료까지), 재사용(이미 소비) 400, 성공 시 `verification.consume` 호출을 검증한다. `user.changeEmail`·`refreshTokenRepository.revokeAllActiveByUserId` 호출과 409 변환은 이 서비스가 더 이상 수행하지 않으므로(D8, 사용자 확정) 여기서 검증하지 않는다.
- `AuthServiceTest`(Mockito, 기존 파일에 케이스 추가) — `confirmEmailChange` 성공 시 `emailChangeService.validateAndConsumeCode` → `user.changeEmail` → `userRepository.saveAndFlush` → `refreshTokenRepository.revokeAllActiveByUserId` 순서 호출 검증, `emailChangeService.validateAndConsumeCode`가 `BusinessException`을 던지면 이후 호출이 발생하지 않음을 검증, `userRepository.saveAndFlush`가 `DataIntegrityViolationException`을 던지면 `EmailChangeConflictException`(409 `DUPLICATE_RESOURCE`)으로 변환되고 `refreshTokenRepository.revokeAllActiveByUserId`가 호출되지 않음을 검증(D2).
- `EmailChangeControllerTest`(`@WebMvcTest`, 기존 파일에 케이스 추가) — 200 성공 `MemberResponse` 필드 검증, `newEmail`/`code` 검증 실패 400, 인증 없음 401, `authService.confirmEmailChange` 예외의 400/429/409 HTTP 매핑(컨트롤러가 이제 `AuthService`를 호출).
- `EmailChangeConfirmIntegrationTest`(Testcontainers MySQL, `EmailChangeIntegrationTest`에 케이스 추가 또는 신규 클래스) — 발송→확인 전체 흐름 성공 후 `users.email` 변경 확인, 같은 코드 재사용 실패, 5회 실패 후 429와 이후 재사용 불가, 만료·재발송 무효화된 코드 거부, 다른 회원 격리, **동시에 같은 새 이메일을 다른 회원이 먼저 선점하는 경합**에서 409와 함께 인증번호 미소비·Refresh Token 미폐기 롤백 확인, 확인 성공 후 기존 Refresh Token으로 `/api/auth/refresh` 401, 확인 전후 계좌·잔액·주문·체결 불변.

### Documentation files to modify after implementation

- `ai/api-routes.md`
- `ai/specs/002-auth-account/tasks.md`

---

## Task 1: 엔티티·리포지터리 확장

- [x] `EmailChangeVerification.incrementAttemptCount()`·`consume(now)`, `EmailChangeVerificationRepository.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc`를 실패 테스트로 먼저 고정한다.
- [x] `RefreshTokenRepository.revokeAllActiveByUserId(userId, now)`를 `@DataJpaTest`로 검증한다(대상 회원만 폐기, 타인·이미 폐기된 토큰 불변).
- [x] `User.changeEmail(newEmail, now)`를 추가한다(setter 없이 의도 노출 메서드, 기존 `changeNickname`과 동일한 형태).

## Task 2: `EmailChangeService.validateAndConsumeCode` 검증·소비

- [x] D1의 검증 순서(요청 없음/소비됨/만료 → 5회 초과 → 코드 불일치)를 `EmailChangeService.validateAndConsumeCode(userId, newEmail, code)`에 대한 실패 테스트로 먼저 고정한다. 이 메서드 자체는 `@Transactional`을 선언하지 않고, 호출자인 `AuthService.confirmEmailChange`(Task 3)의 트랜잭션 안에서 실행된다.
- [x] 성공 시 `verification.consume(now)` 호출까지만 구현한다 — `user.changeEmail`·`refreshTokenRepository.revokeAllActiveByUserId`·409 변환은 Task 3의 `AuthService`가 수행한다(D8, 사용자 확정).

## Task 3: `AuthService.confirmEmailChange` 트랜잭션·롤백과 `EmailChangeController` 연결

- [x] `EmailChangeConflictException`(`com.finplay.api.auth.exception`, `BusinessException` 상속, `ErrorCode.DUPLICATE_RESOURCE`)을 추가한다(D2).
- [x] `AuthService`에 `EmailChangeService` 의존성을 추가하고, `confirmEmailChange(userId, newEmail, code)`를 `@Transactional(noRollbackFor = BusinessException.class, rollbackFor = EmailChangeConflictException.class)`로 구현한다 — `emailChangeService.validateAndConsumeCode` → `user.changeEmail` → `userRepository.saveAndFlush` → `refreshTokenRepository.revokeAllActiveByUserId` 순서로 직접 수행한다.
- [x] `userRepository.saveAndFlush` 실패(`DataIntegrityViolationException`) 시 `EmailChangeConflictException`으로 변환되어 전체 롤백되고(인증번호 미소비·Refresh Token 미폐기), `validateAndConsumeCode`가 던지는 일반 `BusinessException`은 시도 횟수 증가분이 커밋됨을 Mockito로 검증한다(depth 매칭 동작 확인).
- [x] `EmailChangeConfirmRequest`(`newEmail`·`code` 필수 검증)와 `EmailChangeController.confirmEmailChange`(`POST /api/auth/email-changes/confirm`, `authService.confirmEmailChange` 호출, 200 `MemberResponse`)를 구현한다.
- [x] `@WebMvcTest`로 200 성공 필드 검증, 검증 실패 400, 인증 헤더 없음 401, 서비스 예외의 400/429/409 HTTP 상태 매핑을 검증한다.

## Task 4: 통합 테스트·동시성·전체 회귀·문서 동기화

- [x] Testcontainers MySQL로 발송→확인 성공 전체 흐름, 재사용·5회 초과·만료·재발송 무효화·타인 요청 격리, 확인 성공 후 기존 Refresh Token 401 검증, 확인 전후 계좌·잔액·주문·체결 불변을 검증한다.
- [x] 동시에 같은 새 이메일을 다른 경로로 먼저 선점하는 경합 시나리오를 실제 MySQL로 재현해 409와 인증번호 미소비·Refresh Token 미폐기 롤백을 검증한다.
- [x] 대상 단위·슬라이스·통합 테스트 전체와 기존 회귀 스위트, Spotless, `./gradlew build`를 실행한다. (`BUILD SUCCESSFUL in 3m 10s`, 커밋 `ecdf3aa`)
- [x] `ai/api-routes.md`에 `POST /api/auth/email-changes/confirm` 라우트를 추가하고 `ai/specs/002-auth-account/tasks.md`에 Issue #56 작업 항목 절을 추가한다.

---

## 완료 체크리스트

- [x] 유효한 인증번호로 이메일이 한 번 변경되고 같은 인증번호 재사용은 거부되는 테스트가 통과한다.
- [x] 요청 없음·잘못된 코드·만료·재발송으로 무효화된 코드·다른 회원 요청이 모두 400 `EMAIL_VERIFICATION_FAILED`로 거부되고 `users.email`이 변하지 않는 테스트가 통과한다.
- [x] 5회 초과 시도가 429 `TOO_MANY_REQUESTS`로 응답하고 해당 인증번호가 즉시 무효화되는 테스트가 통과한다.
- [x] 동시 중복 이메일 경합이 409 `DUPLICATE_RESOURCE`로 처리되고, 이 경우 인증번호 소비·Refresh Token 폐기가 함께 롤백됨이 실제 MySQL 통합 테스트로 확인된다.
- [x] 확인 성공 후 기존 Refresh Token으로 `/api/auth/refresh`가 401이 되는 테스트가 통과한다.
- [x] 확인 성공·실패와 무관하게 계좌·잔액·주문·체결·투자일기, `social_accounts`의 `provider`+`providerUserId` 연결이 불변임이 검증된다.
- [x] `ai/api-routes.md`가 실제 Controller 매핑과 일치한다.
- [x] `./gradlew build`(Spotless·SpotBugs·JaCoCo 포함)가 통과한다.

---

## 미확정·PRD 불일치 (임의로 정하고 보고하는 항목)

이슈 본문·PRD가 세부를 명시하지 않아 이번 계획에서 아래처럼 결정했다. 사용자가 구현 전 하나씩 확인해 다르게 정하면 이 문서를 갱신한다.

1. **트랜잭션 롤백 정책 (가장 중요, D2)** — 사용자가 확정함: 대안 (b), 새 예외 서브타입으로 `noRollbackFor` 범위를 좁히는 방식을 채택했다. `EmailChangeConflictException extends BusinessException`(`ErrorCode.DUPLICATE_RESOURCE`로 생성)을 `com.finplay.api.auth.exception` 패키지에 신설하고, `AuthService.confirmEmailChange`를 `@Transactional(noRollbackFor = BusinessException.class, rollbackFor = EmailChangeConflictException.class)`로 선언한다. Spring `RollbackRuleAttribute`는 예외 클래스와 규칙 클래스 사이 상속 깊이(depth)가 가장 가까운 규칙을 우선 적용한다 — `EmailChangeConflictException`은 `BusinessException`의 서브클래스라 `noRollbackFor = BusinessException.class`에도 매칭되지만, `rollbackFor = EmailChangeConflictException.class`가 depth 0으로 더 가깝게 매칭돼 우선 적용되어 전체 롤백된다. 일반 `BusinessException`(인증번호 불일치·만료·5회초과)은 depth 1로 `noRollbackFor` 규칙만 매칭되어 커밋된다. 이메일 유니크 제약 위반을 잡는 지점에서 `throw new EmailChangeConflictException()`으로 변환하며, `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()` 수동 호출 방식은 폐기했다. `EmailChangeConflictException`은 코드베이스 최초의 `BusinessException` 서브타입이다(기존에는 모두 `new BusinessException(ErrorCode.X)`로 직접 생성) — 실제 파일(`BusinessException.java`) 확인 결과 서브타입 선례가 전혀 없었고, 도메인별 패키지 구조(ADR-0002)를 따라 `auth` 도메인 아래 `exception` 패키지를 새로 만들었다. 대안 (a)(별도 트랜잭션 2개 분리)는 폐기했다.
2. **Refresh Token 폐기 방식** — 사용자가 확정함: `RefreshTokenRepository.revokeAllActiveByUserId(userId, now)` 벌크 UPDATE로 `revokedAt IS NULL`인 모든 행을 소프트 삭제한다(만료 여부는 조건에 넣지 않음). 기존 `revokeIfActiveAndNotExpired`(단건, 만료 제외)와 의도적으로 다르게 설계했다 — "전체 폐기"라는 요구를 만료 여부와 무관하게 이력으로 남기기 위함이다. 물리 삭제 여부, 만료된 토큰까지 폐기 표시할지는 PRD·이슈에 명시가 없다.
3. **확인 성공 응답 바디**: 200 `MemberResponse`(변경된 `email` 포함)로 정했다 — `PATCH /api/auth/me/nickname`과 동일한 관례를 재사용했다. 새 Access/Refresh Token은 발급하지 않는다(재로그인 요구와 자동 토큰 재발급이 모순되지 않게). PRD·이슈 모두 이 엔드포인트의 성공 응답 스키마를 명시하지 않는다.
4. **5회 시도 초과 시 처리**: `EmailVerificationService.confirmVerificationCode`와 동일하게 불일치 1~5회는 `attempt_count` 증가 후 400 `EMAIL_VERIFICATION_FAILED`, 6번째 시도(이미 5회 도달)는 `attempt_count` 증가+즉시 만료 후 429 `TOO_MANY_REQUESTS`로 정했다. PRD AUTH-005는 확인 단계의 응답 코드를 명시하지 않아 AUTH-004(가입 인증)의 기존 패턴을 그대로 유추 적용했다.
5. **다른 사용자 요청 격리와 오류 코드**: 인증 사용자의 `userId`+요청 `newEmail`로만 조회해, 타인의 유효한 인증번호·새 이메일 조합을 확인하려 해도 "요청 없음"과 동일하게 400 `EMAIL_VERIFICATION_FAILED`로 응답한다 — 별도 코드로 구분하지 않아 계정 열거를 방지한다(기존 로그인 401 통일 관례와 같은 근거). PRD는 "다른 사용자 요청은 이메일 변경 안 됨"만 요구하고 오류 코드는 지정하지 않는다.
6. **신규 `ErrorCode` 필요 여부**: 없음. `VALIDATION_ERROR`(400)·`EMAIL_VERIFICATION_FAILED`(400)·`UNAUTHORIZED`(401)·`DUPLICATE_RESOURCE`(409)·`TOO_MANY_REQUESTS`(429)를 모두 재사용한다.
7. **확인 단계 재인증 증명 재요구 여부**: 요구하지 않는다 — `EmailChangeConfirmRequest`는 `newEmail`+`code`만 받는다. #55 D3에서 "재인증이 필요한 유일한 시점은 발송 단계"라고 이미 확정했으나, 이슈 #56 본문은 이를 재확인하지 않고 있어 명시적으로 다시 적었다.
8. **`EmailChangeService` vs `AuthService` 배치** — 사용자가 확정함: `AuthService`로 이동한다. `AuthService.confirmEmailChange(userId, newEmail, code)`가 트랜잭션 경계(D2)를 갖고, `EmailChangeService.validateAndConsumeCode(userId, newEmail, code)`(인증번호 검증·소비 전용으로 좁힌 메서드, D1) 호출 → `user.changeEmail(newEmail, now)` → `userRepository.saveAndFlush(user)`(실패 시 `EmailChangeConflictException`) → `refreshTokenRepository.revokeAllActiveByUserId(userId, now)`를 순서대로 직접 수행하고 `MemberResponse`를 반환한다. `AuthService`가 `EmailChangeService`를 의존성으로 주입받는다(반대 방향 아님). 근거: `EmailVerificationService`의 발송+확인 대칭 선례보다, `AuthService`가 항상 최종 `User` 상태 변경을 담당한다는 기존 불변 규칙(`changeNickname` 등)이 이번 케이스(직접 `users.email` 변경 + `RefreshToken` 전체 폐기)에 더 강하게 적용된다 — `EmailVerificationService.confirmVerificationCode`는 확인 성공 시 `User`를 건드리지 않고 토큰만 발급하는 반면, 이번 이슈는 확인 성공 시 바로 `User`·`RefreshToken`을 직접 변경하므로 성격이 다르다.
9. **5회 초과 방어 재확인**: 이미 만료 처리된 행이라도 `attemptCount >= 5` 조건을 별도로 한 번 더 검사한다(방어적 코드) — `expiresAt` 조건만으로도 이미 걸러지므로 엄밀히는 중복 방어일 수 있다. PRD는 "즉시 무효화"만 요구해 이 이중 방어가 꼭 필요한지는 불명확하다.
10. **동시 경합의 예외 매핑**: `userRepository.saveAndFlush(user)` 직후 `DataIntegrityViolationException`을 잡아 409 `DUPLICATE_RESOURCE`로 변환한다 — 기존 `AuthService.saveUser`/`saveOAuthUser`/`changeNickname`과 동일한 패턴을 재사용했다. `UNIQUE(email)` 제약은 V2에 이미 있어 신규 마이그레이션은 없다.
