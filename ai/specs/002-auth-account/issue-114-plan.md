# Issue #114 현재 비밀번호 확인 기반 비밀번호 변경 API 구현 계획

> **For agentic workers:** 각 Task는 실패 테스트를 먼저 작성한 뒤 구현한다. Step은 체크박스(`- [ ]`)로 추적한다. "현재 비밀번호 불일치(403)", "새 비밀번호 정책 위반(400)", "새 비밀번호가 현재와 동일(400)", "OAuth 전용 회원(400)"은 각각 별도 테스트로 고정하며 서로 대체하거나 합쳐 보고하지 않는다.

**Goal:** 인증 사용자가 `PATCH /api/auth/me/password`로 현재 비밀번호를 재인증한 뒤 자신의 비밀번호를 바꾼다. 재인증 검증 → `passwordHash` 교체 → 회원의 기존 Refresh Token 전체 폐기 → 요청한 기기용 새 토큰 쌍 발급이 하나의 트랜잭션으로 처리되고, 실패하면 아무 것도 바뀌지 않는다. 성공 응답은 200 `TokenResponse`이며 **다른 기기만 로그아웃**된다.

**관련 정본:** GitHub Issue #114, PRD `AUTH-005`(`ai/prd.md` 267-288행 — 비밀번호 변경 항목은 아직 없으며 이번 이슈에서 추가한다, "미확정" 1번), `ai/specs/002-auth-account/spec.md`, `ai/specs/002-auth-account/plan.md`, Issue #54 계획(`issue-54-plan.md`, 재인증 판별·`/api/auth/me/*` 구조), Issue #56 계획(`issue-56-plan.md`, Refresh Token 전체 폐기), ADR-0002, ADR-0003, ADR-0004, `docs/conventions.md`

**선행:** Issue #4·#5·#54·#56이 `dev`에 있다. 실제 코드베이스 확인 결과 이번 이슈가 필요로 하는 부품이 모두 존재한다.

- `Sha256BcryptPasswordEncoder`(`encode`·`matches`, `{sha256-bcrypt}` 접두사) — Issue #4.
- `AuthService.verifyCurrentPassword(user, currentPassword)` private 헬퍼 — 비어 있으면 400 `VALIDATION_ERROR`, 불일치면 403 `REAUTHENTICATION_FAILED`. Issue #54에서 이미 도입돼 `changeNickname`이 쓰고 있다.
- `AuthService.issueTokenPair(user, now)` private 헬퍼 — `jwtTokenProvider.issue` 후 `RefreshToken` 행을 저장하고 `TokenResponse`를 반환한다.
- `RefreshTokenRepository.revokeAllActiveByUserId(userId, now)` 벌크 `@Modifying` UPDATE — Issue #56에서 추가됨, `revokedAt IS NULL`인 모든 행을 소프트 폐기한다.
- `SocialAccountRepository.findByUserId(userId)` — `getMe`·`changeNickname`이 가입 방식 판별에 쓰는 조회.
- `User`에는 `changeNickname`·`changeEmail`만 있고 `changePassword`가 없다 — 이번 이슈에서 추가한다.

---

## Architecture

- 기존 `AuthController → AuthService → UserRepository`/`SocialAccountRepository`/`RefreshTokenRepository` 흐름만 확장한다. 새 컨트롤러·새 서비스·새 Security 컴포넌트·새 Flyway 마이그레이션·새 `ErrorCode`를 만들지 않는다.
- `AuthController`에 같은 클래스 안에서 `PATCH /me/password` 매핑을 추가한다 — `/me/nickname`과 나란히 둔다. 별도 `PasswordController`를 만들지 않는다(`EmailChangeController`는 발송·확인 2개 엔드포인트와 별도 도메인 서비스를 가져 분리했지만, 비밀번호 변경은 엔드포인트 1개이고 `AuthService`가 직접 처리한다).
- `AuthService`에 `changePassword(userId, currentPassword, newPassword)`를 신설한다. 이 메서드가 트랜잭션 경계를 갖고 (1) 회원 조회, (2) `social_accounts`로 가입 방식 판별, (3) OAuth 전용 회원 거부, (4) 현재 비밀번호 대조, (5) 새 비밀번호 동일 여부 검사, (6) `User.changePassword`로 해시 교체, (7) `refreshTokenRepository.revokeAllActiveByUserId`, (8) `issueTokenPair` 순으로 직접 수행한다.
- `User`에 `changePassword(newPasswordHash, now)` 인스턴스 메서드를 추가한다 — 기존 `changeNickname`·`changeEmail`과 동일한 setter 금지·의도 노출 패턴이며 `passwordHash`와 `updatedAt`만 갱신한다.
- `AuthService`의 기존 의존성(`userRepository`, `socialAccountRepository`, `refreshTokenRepository`, `passwordEncoder`, `jwtTokenProvider`, `clock`)만 사용한다 — 생성자·필드를 바꾸지 않는다.
- `SecurityConfig`는 수정하지 않는다 — `PATCH /api/auth/me/password`는 공개 목록에 없어 기본 `anyRequest().authenticated()`로 보호된다.

## Tech Stack

Java 17, Spring Boot 4.1, Spring Security(`@AuthenticationPrincipal`), Spring Data JPA, MySQL 8.4(Testcontainers), JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

---

## 요구사항 ID와 수용 기준

### PRD `AUTH-005` (비밀번호 변경 부분 — 이번 이슈에서 신설)

PRD `AUTH-005`는 현재 닉네임·이메일 변경만 규정하고 비밀번호 변경 항목이 없다. 이번 이슈가 아래 항목을 PRD에 추가하고 함께 구현한다(Task 4, "미확정" 1번).

- 이메일 회원의 비밀번호 변경은 현재 비밀번호 확인을 요구한다.
- 새 비밀번호는 가입과 같은 정책(8자 이상 100자 이하)을 적용하며, 현재 비밀번호와 같으면 거부한다.
- OAuth 전용 회원은 비밀번호를 변경할 수 없다 — 비밀번호 최초 설정은 1차 범위가 아니다.
- 비밀번호 변경이 완료되면 기존 Refresh Token을 모두 폐기하고 요청한 기기에만 새 토큰 쌍을 발급한다. 다른 기기는 로그아웃되며 요청한 기기는 재로그인 없이 이어서 사용한다. 기존 Access Token은 짧은 만료시간까지 유효할 수 있다.
- 비밀번호 변경 전후로 이메일·닉네임·계좌·시드머니·잔액·주문·체결은 그대로 유지한다.
- 비밀번호 원문은 저장하지 않고 `Sha256BcryptPasswordEncoder`로 해시해 저장하며, 어떤 응답에도 노출하지 않는다.

### Issue #114 수용 시나리오

1. Given 이메일 회원이 올바른 현재 비밀번호를 아는 상태
2. When 현재 비밀번호와 정책에 맞는 새 비밀번호로 `PATCH /api/auth/me/password` 호출
3. Then 200과 새 토큰 쌍을 받고, 이후 새 비밀번호로 로그인되며 기존 비밀번호 로그인은 401이다.
4. And 잘못된 현재 비밀번호는 403 `REAUTHENTICATION_FAILED`이며 `password_hash`가 바뀌지 않는다.
5. And 정책 위반 새 비밀번호(8자 미만·100자 초과·공백)는 400 `VALIDATION_ERROR`이며 아무 것도 바뀌지 않는다.
6. And 새 비밀번호가 현재 비밀번호와 같으면 400 `VALIDATION_ERROR`이며 아무 것도 바뀌지 않는다.
7. And 변경 성공 후 다른 기기가 갖고 있던 기존 Refresh Token으로 `/api/auth/refresh`를 호출하면 401이다.
8. And 변경 성공 응답으로 받은 새 Refresh Token으로는 `/api/auth/refresh`가 정상 동작한다(요청한 기기는 로그아웃되지 않는다).
9. And OAuth 전용 회원의 요청은 400 `VALIDATION_ERROR`("OAuth 전용 회원은 비밀번호를 변경할 수 없습니다.")로 거부되고 `password_hash`·Refresh Token·`social_accounts`가 모두 그대로다.
10. And 성공·실패와 무관하게 이메일·닉네임·계좌 2개·시드머니·잔액은 그대로 유지된다.
11. And 어떤 응답에도 `passwordHash`·`currentPassword`·`newPassword`가 노출되지 않는다.

---

## 범위와 제외

### 포함

- `PATCH /api/auth/me/password` — Access Bearer 필수(기존 `SecurityConfig` 기본 보호 재사용, 화이트리스트 추가 없음).
- `PasswordChangeRequest(currentPassword, newPassword)` 요청 DTO — 가입과 동일한 비밀번호 정책 검증.
- `User.changePassword(newPasswordHash, now)` 추가.
- `AuthService.changePassword(userId, currentPassword, newPassword)` — 재인증·정책 검사·해시 교체·Refresh Token 전체 폐기·새 토큰 쌍 발급을 한 트랜잭션으로 처리.
- 서비스 단위 테스트, Controller `@WebMvcTest`, 실제 MySQL 통합 테스트.
- `ai/prd.md` AUTH-005 비밀번호 변경 항목 추가, `ai/api-routes.md`·`docs/api-contracts.md`·`tasks.md` 동기화.

### 제외

- 비밀번호 분실 시 재설정 — 발송 Issue #115, 확인·적용 Issue #116.
- OAuth 전용 회원의 비밀번호 최초 설정(sentinel `{oauth-only}`을 실제 비밀번호로 교체하는 흐름).
- Access Token 즉시 블랙리스트 — 변경 직전 발급된 Access Token은 남은 짧은 만료 시간까지 유효할 수 있다. PRD가 이미 명시한 기존 한계를 그대로 유지한다.
- 비밀번호 변경 알림 메일.
- 비밀번호 재사용 이력 검사·만료 주기·복잡도(대소문자·숫자·특수문자 조합) 정책 — 가입 정책(8~100자)을 넘어서는 규칙은 추가하지 않는다.
- 새 Flyway 마이그레이션 — `users.password_hash` 컬럼은 V1/V2에 이미 있고 스키마를 바꾸지 않는다(ADR-0004).
- 새 `ErrorCode` — 기존 코드만 재사용한다(D7).
- `RefreshTokenRepository`·`ReauthTokenRepository`·`EmailChangeService` 수정 — 이번 이슈는 기존 메서드를 호출만 한다.

---

## 설계 결정

### D1. 요청 DTO는 `currentPassword`·`newPassword` 둘 다 필수이며 새 비밀번호에만 가입과 같은 길이 정책을 건다

```java
public record PasswordChangeRequest(
    @NotBlank(message = "현재 비밀번호는 필수입니다.")
    @Size(max = 100, message = "현재 비밀번호는 최대 100자까지 입력할 수 있습니다.")
    String currentPassword,
    @NotBlank(message = "새 비밀번호는 필수입니다.")
    @Size(min = 8, max = 100, message = "비밀번호는 8자 이상 100자 이하로 입력해야 합니다.")
    String newPassword) {
}
```

- `newPassword`의 `@Size(min = 8, max = 100)`과 메시지는 `SignupRequest.password`와 글자 그대로 같게 맞춘다 — 이슈가 "가입과 같은 정책(8~100자)"을 요구하므로 두 곳의 규칙이 갈라지지 않게 한다.
- `currentPassword`에는 `min`을 걸지 않는다. 최소 길이를 걸면 짧은 문자열을 보냈을 때 비밀번호 대조 이전에 400이 나 "정책상 존재할 수 없는 비밀번호"라는 정보를 응답 코드로 흘린다. 값이 틀린 것은 400이 아니라 403이어야 하므로(D3) 형식 검증은 `@NotBlank` + 최대 길이까지만 한다.
- `NicknameUpdateRequest`와 달리 두 필드 모두 `@NotBlank`다 — 이 엔드포인트는 가입 방식에 따라 필요한 필드가 갈리지 않고 항상 현재 비밀번호 한 가지만 재인증 수단으로 쓰기 때문이다. `reauthToken`은 받지 않는다(D2).

### D2. OAuth 전용 회원은 400 `VALIDATION_ERROR`로 거부한다 — 재인증 실패(403)가 아니라 계정 유형 불일치다 (팀 확정)

```java
SignupMethod signupMethod = socialAccountRepository.findByUserId(userId)
    .map(socialAccount -> SignupMethod.fromProvider(socialAccount.getProvider()))
    .orElse(SignupMethod.EMAIL);
if (signupMethod != SignupMethod.EMAIL) {
    throw new BusinessException(
        ErrorCode.VALIDATION_ERROR, "OAuth 전용 회원은 비밀번호를 변경할 수 없습니다.");
}
```

- **판별은 클라이언트 필드가 아니라 DB의 `social_accounts`로 한다.** Issue #54 D1과 같은 근거다 — 요청 본문의 필드 조합으로 분기하면 필드를 조작해 비밀번호 검증 경로 자체를 우회하려는 시도를 서버가 신뢰하게 된다. `AuthService.getMe`·`changeNickname`이 이미 쓰는 `socialAccountRepository.findByUserId` 조회를 그대로 재사용한다.
- **왜 403 `REAUTHENTICATION_FAILED`가 아니라 400 `VALIDATION_ERROR`인가 (팀 확정 결정 2).** OAuth 전용 회원의 `password_hash`는 sentinel `{oauth-only}`이라 `Sha256BcryptPasswordEncoder.matches`가 항상 `false`를 반환한다 — 아무 것도 하지 않고 두면 403 `REAUTHENTICATION_FAILED`가 나간다. 그러나 이것은 "맞는 비밀번호를 넣었는데 틀렸다고 판정된" 상황이 아니라 **애초에 이 회원에게는 바꿀 비밀번호가 존재하지 않는** 상황이다. 403은 "재인증을 시도했으나 증명에 실패했다"는 뜻이고(Issue #53·#54가 그 의미로 고정), OAuth 전용 회원에게 그 코드를 주면 클라이언트는 "비밀번호를 다시 입력하면 성공할 수 있다"고 오해해 무한히 재시도하게 된다. 계정 유형이 이 요청과 맞지 않는다는 사실은 요청 자체가 성립하지 않는다는 뜻이므로 400 `VALIDATION_ERROR`가 맞다.
- 커스텀 메시지 "OAuth 전용 회원은 비밀번호를 변경할 수 없습니다."를 `BusinessException(ErrorCode, String)` 생성자로 담는다 — Issue #54가 "이메일 회원은 현재 비밀번호가 필요합니다."를 같은 방식으로 담은 선례를 따른다.
- **계정 열거 우려는 없다.** 이 응답을 받으려면 이미 그 회원 본인의 유효한 Access Bearer가 있어야 하므로, 공격자가 남의 가입 방식을 알아내는 데 쓸 수 없다. 자신의 가입 방식은 `GET /api/auth/me`의 `signupMethod`로 이미 알 수 있다.
- **이 검사를 비밀번호 대조보다 먼저 한다**(D3). sentinel 값에 대해 BCrypt 대조를 돌릴 이유가 없고, 순서를 뒤집으면 OAuth 전용 회원이 항상 403을 먼저 받아 이 결정이 무의미해진다.

### D3. 검증 순서 — 회원 조회 → OAuth 전용 거부(400) → 현재 비밀번호 대조(403) → 새 비밀번호 동일 거부(400)

```java
// AuthService.java
@Transactional
public TokenResponse changePassword(Long userId, String currentPassword, String newPassword) {
    LocalDateTime now = LocalDateTime.now(clock);
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

    SignupMethod signupMethod = socialAccountRepository.findByUserId(userId)
        .map(socialAccount -> SignupMethod.fromProvider(socialAccount.getProvider()))
        .orElse(SignupMethod.EMAIL);
    if (signupMethod != SignupMethod.EMAIL) {
        throw new BusinessException(
            ErrorCode.VALIDATION_ERROR, "OAuth 전용 회원은 비밀번호를 변경할 수 없습니다.");
    }

    verifyCurrentPassword(user, currentPassword);

    if (currentPassword.equals(newPassword)) {
        throw new BusinessException(
            ErrorCode.VALIDATION_ERROR, "새 비밀번호는 현재 비밀번호와 달라야 합니다.");
    }

    user.changePassword(passwordEncoder.encode(newPassword), now);
    userRepository.saveAndFlush(user);

    // 폐기가 먼저다 — 새 토큰을 먼저 저장하면 벌크 UPDATE가 그 행까지 폐기한다 (D5).
    refreshTokenRepository.revokeAllActiveByUserId(userId, now);
    return issueTokenPair(user, now);
}
```

- **OAuth 전용 거부를 가장 먼저 하는 이유는 D2 마지막 항목과 같다.** 비밀번호 대조를 먼저 하면 이 회원은 언제나 403을 받는다.
- **현재 비밀번호 대조는 기존 `verifyCurrentPassword(user, currentPassword)` private 헬퍼를 그대로 재사용한다.** Issue #54가 이미 "빈 값이면 400 `VALIDATION_ERROR`, 불일치면 403 `REAUTHENTICATION_FAILED`"로 만들어 뒀고 `changeNickname`이 쓰고 있다. 이번이 두 번째 호출부이므로 별도 클래스로 추출하지 않는다(`docs/conventions.md`의 "세 번째 중복이 보이고 책임이 명확할 때만 공통화"). `PasswordChangeRequest`의 `@NotBlank` 덕분에 헬퍼의 빈 값 분기는 실제로는 도달하지 않지만, 서비스가 컨트롤러 검증에 의존하지 않도록 헬퍼를 그대로 통과시킨다.
- **새 비밀번호 동일 검사는 대조 성공 이후에 한다.** 순서를 뒤집으면 현재 비밀번호를 모르는 공격자도 "내가 보낸 두 값이 같다"는 400을 받을 수 있어 의미 없는 분기가 노출된다. 대조가 끝난 뒤라면 `currentPassword`가 저장된 해시와 일치함이 이미 확인됐으므로, 단순 문자열 비교 `currentPassword.equals(newPassword)`가 `passwordEncoder.matches(newPassword, user.getPasswordHash())`와 동치이면서 BCrypt 연산을 한 번 아낀다.
- **`passwordHash`가 `null`인 회원:** `users.password_hash`는 nullable이고 `login()`도 `null` 방어를 갖고 있다. OAuth 전용 회원은 sentinel 문자열을 갖고 `social_accounts` 검사에서 먼저 걸리므로 여기 도달하지 않지만, 만에 하나 `null`이면 `Sha256BcryptPasswordEncoder.matches(raw, null)`이 `false`를 반환해 403이 된다 — 별도 분기를 추가하지 않는다(불가능한 시나리오를 위한 방어 코드를 만들지 않는다).
- 반환 타입이 `MemberResponse`가 아니라 `TokenResponse`인 이유는 D5·D6에 있다.

### D4. `User.changePassword(newPasswordHash, now)` — setter 대신 의도가 드러나는 인스턴스 메서드

```java
public void changePassword(String newPasswordHash, LocalDateTime now) {
    this.passwordHash = newPasswordHash;
    this.updatedAt = now;
}
```

- `docs/conventions.md`의 Entity 규칙과 기존 `changeNickname`·`changeEmail`을 그대로 따른다. `passwordHash`와 `updatedAt`만 바꾸고 `email`·`nickname`·`role`·`status`·`createdAt`은 건드리지 않는다.
- **파라미터는 원문이 아니라 이미 인코딩된 해시다.** 엔티티가 `PasswordEncoder`를 알면 도메인이 인프라에 의존하게 된다 — 인코딩은 서비스가 하고 엔티티는 결과만 받는다. 파라미터 이름을 `newPasswordHash`로 두어 호출부에서 원문을 넘기는 실수를 막는다.
- Issue #116(비밀번호 재설정 적용)이 이 메서드를 그대로 재사용한다 — 이슈 본문이 "여기서 만들고 #116이 재사용"이라고 명시했으므로 재설정 흐름에서도 쓸 수 있게 이름·시그니처에 "변경 사유"를 넣지 않는다.

### D5. 성공 시 기존 Refresh Token 전체 폐기 후 새 토큰 쌍을 발급한다 — 다른 기기만 로그아웃 (팀 확정)

- **팀 확정 결정 1.** 변경 성공 시 `refreshTokenRepository.revokeAllActiveByUserId(userId, now)`로 그 회원의 활성 Refresh Token을 전부 폐기한 다음, `issueTokenPair(user, now)`로 요청한 기기용 새 토큰 쌍을 즉시 발급해 200 `TokenResponse`로 반환한다. 결과적으로 **다른 기기만 로그아웃**되고 요청한 기기는 재로그인 없이 이어서 사용한다.
- **Issue #56(이메일 변경)과 다른 이유.** #56은 PRD AUTH-005가 "이메일 변경이 완료되면 기존 Refresh Token을 모두 폐기하고 **재로그인을 요구한다**"고 못박아 새 토큰을 발급하지 않았다. 비밀번호 변경에는 그런 PRD 문장이 애초에 없고(항목 자체가 없다, "미확정" 1번), 두 기능은 사용자가 처한 상황이 다르다.
  - 이메일 변경은 로그인 식별자 자체가 바뀌므로 새 식별자로 다시 로그인해 보는 것이 자연스러운 확인 절차다.
  - 비밀번호 변경은 "비밀번호가 유출된 것 같다"는 상황에서 쓰는 즉시 대응 수단이다. 이때 요청자 본인까지 로그아웃시키면, 방금 보안 조치를 한 사용자가 바로 로그인 화면으로 튕겨 나가 조치가 실제로 반영됐는지 확인하지 못한다. 반대로 세션을 유지해 주면 "내 기기는 그대로, 침입자 기기는 끊김"이라는 의도가 그대로 달성된다.
  - 폐기 범위는 두 기능이 동일하다(회원의 활성 Refresh Token 전부). 달라지는 것은 그 직후 요청자에게 새 토큰을 주느냐뿐이다.
- **호출 순서가 중요하다 — 폐기가 반드시 먼저다.** `RefreshToken`은 `GenerationType.IDENTITY`라 `refreshTokenRepository.save(...)` 시점에 INSERT가 즉시 실행된다. 새 토큰을 먼저 발급하면 뒤이은 `revokeAllActiveByUserId`의 `WHERE user.id = :userId AND revokedAt IS NULL` 조건에 방금 만든 행까지 걸려 요청자도 함께 로그아웃된다. 이 순서 의존성은 코드 주석과 통합 테스트(Task 3)로 함께 고정한다.
- `revokeAllActiveByUserId`는 물리 삭제가 아니라 `revokedAt`을 채우는 소프트 폐기다(#56 D4). 이번 이슈는 이 메서드를 호출만 하고 수정하지 않는다.
- 폐기와 발급이 같은 트랜잭션 안에 있으므로(D6), 발급 중 실패하면 폐기도 함께 롤백되어 "모든 기기에서 로그아웃됐는데 새 토큰은 못 받은" 중간 상태가 생기지 않는다.

### D6. 트랜잭션 경계 — 단순 `@Transactional`, 실패 시 전부 롤백

- `changePassword` 전체가 평범한 `@Transactional`이다. `noRollbackFor`·`rollbackFor`를 쓰지 않는다 — #56은 인증번호 `attempt_count` 증가분을 실패 시에도 커밋해야 해서 depth 매칭이 필요했지만, 이번 이슈에는 **실패해도 남겨야 하는 부수 효과가 하나도 없다.** 시도 횟수 카운터·잠금·감사 로그를 도입하지 않으므로(범위 제외) 어떤 실패든 전부 롤백이 정답이다.
- 따라서 어느 단계에서 예외가 나든 `password_hash`·`revoked_at`·새 `refresh_tokens` 행이 모두 원래대로 돌아간다(수용 시나리오 4·5·6·9).
- `accountService`·`emailChangeService`·`reauthTokenRepository`·`socialAccountRepository.save`는 이 메서드에서 호출하지 않는다 — 계좌·시드머니·소셜 연결은 그대로 유지되어야 한다. 통합 테스트로 값 불변을 직접 검증한다(Task 3).
- `userRepository.saveAndFlush(user)`를 쓴다. `password_hash`에는 UNIQUE 제약이 없어 `DataIntegrityViolationException`을 잡을 이유가 없으므로 `try/catch`는 두지 않는다 — `changeNickname`·`confirmEmailChange`가 유니크 경합 때문에 감싼 것과 달리 이번에는 경합 대상이 없다. 다만 `flush` 시점을 앞당겨 뒤따르는 벌크 UPDATE와 순서가 뒤엉키지 않게 한다.

### D7. 새 `ErrorCode`를 추가하지 않는다 — 기존 코드만 재사용한다

| 상황 | 코드 | 근거 |
|---|---|---|
| Access Bearer 없음·만료·변조·Refresh Token을 Bearer로 제출 | 401 `UNAUTHORIZED` | 기존 Security 필터 계약, 변경 없음 |
| 유효 Access Token의 주체가 DB에 없음(극단적 경쟁 상태) | 401 `UNAUTHORIZED` | `getMe`·`changeNickname`과 동일한 방어적 처리 |
| `currentPassword`·`newPassword` 누락·공백, `newPassword` 8자 미만·100자 초과 | 400 `VALIDATION_ERROR` | Bean Validation, 가입과 동일한 정책 |
| OAuth 전용 회원의 요청 | 400 `VALIDATION_ERROR` + "OAuth 전용 회원은 비밀번호를 변경할 수 없습니다." | D2, 팀 확정 |
| 새 비밀번호가 현재 비밀번호와 동일 | 400 `VALIDATION_ERROR` + "새 비밀번호는 현재 비밀번호와 달라야 합니다." | D3 — 값 자체는 정책을 만족하지만 요청이 성립하지 않는다 |
| 현재 비밀번호 불일치 | 403 `REAUTHENTICATION_FAILED` | Issue #54·#55와 동일 — 이슈 본문이 "403으로 통일"을 명시 |

- 409는 이 엔드포인트에 존재하지 않는다 — 유니크 제약이 걸린 값을 바꾸지 않기 때문이다.
- 429도 없다 — 시도 횟수 제한은 범위 밖이다("미확정" 4번).

### D8. 성공 응답은 200 `TokenResponse`다 — `MemberResponse`가 아니다

```
200 {"accessToken":"...","refreshToken":"...",
     "accessTokenExpiresInSeconds":1800,"refreshTokenExpiresInSeconds":1209600}
```

- `/me/nickname`(#54)과 `/email-changes/confirm`(#56)은 변경된 회원 정보를 확인시키려고 `MemberResponse`를 반환했다. 비밀번호는 `MemberResponse`에 담기지 않는 값이라(담아서도 안 된다) 같은 DTO를 반환해도 클라이언트가 얻는 정보가 없다.
- 반대로 이번 응답에서 클라이언트가 반드시 받아야 하는 것은 **새 토큰 쌍**이다(D5). 기존 Refresh Token이 방금 전부 폐기됐으므로, 새 토큰을 주지 않으면 요청자도 다음 갱신에서 로그아웃된다.
- 따라서 `TokenResponse`(로그인·재발급과 동일 스키마)를 그대로 재사용한다 — 프론트엔드가 이미 아는 토큰 저장 로직을 재사용할 수 있다. 새 응답 DTO를 만들지 않는다.
- `TokenResponse`에는 비밀번호 관련 필드가 구조적으로 없으므로 수용 시나리오 11(민감 필드 미노출)은 자동으로 만족된다.
- `ResponseEntity.ok(...)`로 200을 반환한다(`docs/conventions.md`의 "수정 200" 규칙).

---

## HTTP 계약

| 구분 | Method / Path | 인증 | 입력 | 성공 응답 | 오류 응답 |
|---|---|---|---|---|---|
| 비밀번호 변경 | PATCH `/api/auth/me/password` | `Authorization: Bearer <유효한 Access JWT>` 필수 | `{"currentPassword":"현재비밀번호","newPassword":"새비밀번호"}` (`PasswordChangeRequest`) | 200 `TokenResponse`(`accessToken`, `refreshToken`, `accessTokenExpiresInSeconds`, `refreshTokenExpiresInSeconds`) | 필드 누락·공백, `newPassword` 8자 미만·100자 초과, OAuth 전용 회원, 새 비밀번호가 현재와 동일 → 400 `VALIDATION_ERROR`; Bearer 없음·만료·변조·Refresh Bearer → 401 `UNAUTHORIZED`; 현재 비밀번호 불일치 → 403 `REAUTHENTICATION_FAILED` (공통 오류 형식) |

- 성공 시 부수 효과 — 그 회원의 모든 활성 Refresh Token이 폐기되고 응답의 새 Refresh Token 1개만 유효하다. 다른 기기는 다음 `/api/auth/refresh` 호출에서 401이 된다.
- 기존 Access Token은 블랙리스트되지 않아 남은 만료 시간까지 유효할 수 있다(범위 제외, PRD 기존 한계).

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/auth/dto/request/PasswordChangeRequest.java` — D1.

### Production files to modify

- `src/main/java/com/finplay/api/auth/domain/User.java` — `changePassword(newPasswordHash, now)` 추가 (D4).
- `src/main/java/com/finplay/api/auth/service/AuthService.java` — `changePassword(userId, currentPassword, newPassword)` 추가 (D3·D5·D6). 기존 생성자·필드는 변경하지 않는다(필요한 의존성이 모두 이미 주입돼 있음).
- `src/main/java/com/finplay/api/auth/controller/AuthController.java` — `@PatchMapping("/me/password")` 추가, 200 `TokenResponse` 반환 (D8).

### Test files to modify

- `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java` — `changePassword` 단위 테스트(Mockito) 추가.
- `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java` — `PATCH /me/password` WebMvc 슬라이스 테스트 추가.

### Test files to create

- `src/test/java/com/finplay/api/auth/service/PasswordChangeIntegrationTest.java` — 실제 MySQL(Testcontainers)로 변경 후 신규 비밀번호 로그인 성공·기존 비밀번호 401, 다른 기기 Refresh Token 폐기·응답 토큰 유효, 실패 시 무변경 롤백, 계좌·잔액 불변 검증(Task 3).

### Documentation files to modify after implementation

- `ai/prd.md` — AUTH-005에 비밀번호 변경 항목 추가 ("요구사항 ID와 수용 기준" 절의 6개 문장).
- `ai/api-routes.md` — 라우트 목록과 Security 보호 목록에 `PATCH /api/auth/me/password` 추가.
- `docs/api-contracts.md` — 인증 도메인 절에 요청·응답·오류 계약 추가.
- `ai/specs/002-auth-account/tasks.md` — Issue #114 작업 항목 절(이 계획과 함께 이미 추가됨) 체크 갱신.
- `ai/specs/002-auth-account/run-log.md` — implementer·reviewer가 자동 기록.

### 수정하지 않을 파일

- `src/main/resources/db/migration/*` — 신규 마이그레이션 없음. `users.password_hash`는 기존 컬럼이다(ADR-0004).
- `src/main/java/com/finplay/api/auth/repository/RefreshTokenRepository.java` — `revokeAllActiveByUserId`(#56)를 호출만 한다.
- `src/main/java/com/finplay/api/common/ErrorCode.java` — 새 코드 추가 없음(D7).
- `src/main/java/com/finplay/api/auth/config/SecurityConfig.java` — 새 경로가 공개 목록에 없어 이미 `anyRequest().authenticated()`로 보호된다. 확인만 하고 수정하지 않는다.
- `src/main/java/com/finplay/api/auth/crypto/Sha256BcryptPasswordEncoder.java` — 그대로 재사용.
- `src/main/java/com/finplay/api/auth/service/EmailChangeService.java`, `EmailVerificationService.java`, `OAuthCallbackService.java` — 이번 이슈와 무관.

---

## Task 1: `User.changePassword`와 `AuthService.changePassword` 서비스 로직

**Files**

- Modify: `src/main/java/com/finplay/api/auth/domain/User.java`
- Modify: `src/main/java/com/finplay/api/auth/service/AuthService.java`
- Modify: `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java`

**Interfaces**

- Produces: `void User.changePassword(String newPasswordHash, LocalDateTime now)`
- Produces: `TokenResponse AuthService.changePassword(Long userId, String currentPassword, String newPassword)`

- [ ] **Step 1: 서비스 실패 테스트를 먼저 작성한다(Mockito)**
  - `changePasswordSucceedsAndReissuesTokenPairForEmailUser` — 비밀번호 일치 시 `passwordEncoder.encode(newPassword)` 결과로 `user.getPasswordHash()`가 바뀌고 `userRepository.saveAndFlush`가 호출되며 `TokenResponse`를 반환한다.
  - `changePasswordRevokesAllRefreshTokensBeforeIssuingNewPair` — `InOrder`로 `refreshTokenRepository.revokeAllActiveByUserId` → `refreshTokenRepository.save`(새 토큰 저장) 순서를 고정한다(D5의 순서 의존성).
  - `changePasswordFailsWithReauthenticationFailedWhenCurrentPasswordMismatches` — `passwordEncoder.matches`가 `false`, `saveAndFlush`·`revokeAllActiveByUserId`·`jwtTokenProvider.issue` 미호출.
  - `changePasswordFailsWithValidationErrorWhenNewPasswordEqualsCurrentPassword` — 400, 저장·폐기·발급 미호출.
  - `changePasswordFailsWithValidationErrorForOAuthOnlyUser` — `socialAccountRepository.findByUserId`가 `SocialAccount`를 반환하면 400과 "OAuth 전용 회원은 비밀번호를 변경할 수 없습니다." 메시지, `passwordEncoder.matches` 자체가 호출되지 않음(D2·D3의 순서).
  - `changePasswordFailsWithUnauthorizedWhenUserNotFound`
- [ ] **Step 2: 대상 테스트 실패를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*AuthServiceTest" --no-daemon --max-workers=1
  ```

  Expected: 메서드 부재로 컴파일 실패 또는 신규 케이스 FAIL.

- [ ] **Step 3: 최소 구현을 추가한다**
  - `User.changePassword`는 D4 그대로.
  - `AuthService.changePassword`는 D3의 구현 그대로 — 기존 `verifyCurrentPassword`·`issueTokenPair` private 헬퍼를 재사용하고 새 의존성을 주입하지 않는다. 폐기 → 발급 순서에 D5를 근거로 한 한 줄 주석을 남긴다.
- [ ] **Step 4: 같은 대상 테스트를 다시 실행해 PASS를 확인한다**
- [ ] **Step 5: 논리 커밋한다**

  ```powershell
  git add src/main/java/com/finplay/api/auth/domain/User.java src/main/java/com/finplay/api/auth/service/AuthService.java src/test/java/com/finplay/api/auth/service/AuthServiceTest.java
  git commit -m "feat: 현재 비밀번호 확인 기반 비밀번호 변경 서비스 추가"
  ```

## Task 2: 보호된 `PATCH /api/auth/me/password` HTTP 계약

**Files**

- Create: `src/main/java/com/finplay/api/auth/dto/request/PasswordChangeRequest.java`
- Modify: `src/main/java/com/finplay/api/auth/controller/AuthController.java`
- Modify: `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java`

**Interfaces**

- Consumes: `PasswordChangeRequest`, Access Bearer의 `AuthenticatedUser`
- Produces: 200 `TokenResponse`

- [ ] **Step 1: WebMvc 실패 테스트를 먼저 작성한다**
  - `updatePasswordReturnsOkWithNewTokenPair` — `authService.changePassword(...)`가 반환한 `TokenResponse`를 200으로 그대로 반환, `jsonPath`로 4개 필드 확인.
  - `updatePasswordRejectsBlankCurrentPasswordWithoutCallingService` — 400, 서비스 미호출.
  - `updatePasswordRejectsShortNewPasswordWithoutCallingService` — 7자 새 비밀번호 400, 서비스 미호출.
  - `updatePasswordRejectsTooLongNewPasswordWithoutCallingService` — 101자 400, 서비스 미호출.
  - `updatePasswordMapsServiceValidationErrorToBadRequest` — 서비스가 `BusinessException(VALIDATION_ERROR, "OAuth 전용 회원은 비밀번호를 변경할 수 없습니다.")`를 던지면 400과 그 메시지.
  - `updatePasswordMapsServiceReauthenticationFailedToForbidden` — 403.
  - `updatePasswordRejectsMissingAccessTokenWithoutCallingService` — 401, 서비스 미호출.
  - `updatePasswordRejectsRefreshBearerWithoutCallingService` — 401(기존 `logout`·`me` 테스트의 `jwtTokenProvider.parseAccessToken(...).thenReturn(Optional.empty())` 패턴 재사용).
  - 응답 JSON에 `currentPassword`·`newPassword`·`passwordHash` 키가 없음을 확인.
- [ ] **Step 2: 대상 테스트 실패를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*AuthControllerTest" --no-daemon --max-workers=1
  ```

  Expected: `PATCH /me/password` 매핑 부재로 404 또는 신규 검증 FAIL.

- [ ] **Step 3: DTO와 Controller 매핑만 구현한다**
  - `PasswordChangeRequest`는 D1 그대로 — `newPassword`의 `@Size` 메시지를 `SignupRequest.password`와 동일하게 맞춘다.
  - `@PatchMapping("/me/password")`에 `@AuthenticationPrincipal AuthenticatedUser principal`, `@Valid @RequestBody PasswordChangeRequest request`를 받아 `authService.changePassword(principal.userId(), request.currentPassword(), request.newPassword())` 호출 후 `ResponseEntity.ok(...)`.
  - `SecurityConfig`는 수정하지 않는다(확인만).
- [ ] **Step 4: 같은 대상 테스트를 다시 실행해 PASS를 확인한다**
- [ ] **Step 5: 논리 커밋한다**

  ```powershell
  git add src/main/java/com/finplay/api/auth/dto/request/PasswordChangeRequest.java src/main/java/com/finplay/api/auth/controller/AuthController.java src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java
  git commit -m "feat: 비밀번호 변경 API 추가"
  ```

## Task 3: 실제 MySQL 통합 검증 — 세션 처리와 무변경 롤백

**Files**

- Create: `src/test/java/com/finplay/api/auth/service/PasswordChangeIntegrationTest.java`

기존 `LoginIntegrationTest`(이메일 가입→로그인), `NicknameChangeIntegrationTest`(계좌 스냅샷 비교), `FakeOAuthFlowIntegrationTest`(Fake OAuth 신규 가입) 준비 흐름을 재사용한다.

- [ ] **Step 1: 실제 MySQL 통합 테스트를 작성한다**
  - `changePasswordAllowsLoginWithNewPasswordAndRejectsOldPassword` — 가입 → 로그인 → 비밀번호 변경 → 새 비밀번호 로그인 성공, 기존 비밀번호 로그인은 401 `UNAUTHORIZED`.
  - `changePasswordRevokesOtherDeviceRefreshTokensButKeepsIssuedOne` — 같은 회원으로 두 번 로그인해 기기 A·B의 Refresh Token을 만든 뒤 A에서 비밀번호를 변경한다. 응답으로 받은 새 Refresh Token으로는 `/api/auth/refresh`가 성공하고, 기기 B의 기존 Refresh Token과 변경 전 A의 Refresh Token은 401이다(수용 시나리오 7·8, D5).
  - `changePasswordRejectsWrongCurrentPasswordWithoutAnyChange` — 403이며 재조회한 `password_hash`가 변경 전과 동일하고, 기존 Refresh Token들이 여전히 유효하다.
  - `changePasswordRejectsSameNewPasswordWithoutAnyChange` — 400이며 `password_hash`·`updated_at`·Refresh Token 모두 불변.
  - `changePasswordRejectsOAuthOnlyUserWithoutAnyChange` — Fake OAuth 신규 가입 회원으로 호출하면 400과 확정 메시지, `password_hash`(sentinel)·Refresh Token·`social_accounts` 행이 모두 불변.
  - `changePasswordKeepsEmailNicknameAndAccountsUnchanged` — 변경 전 두 계좌(STOCK·CRYPTO)의 `cashBalance`·`seedMoney`·`realizedPnl`과 `users.email`·`users.nickname`을 스냅샷으로 저장한 뒤, 성공 변경 후에도 스냅샷과 동일함을 확인한다.
  - 주문·체결(Order/Execution) 불변 검증은 해당 도메인이 아직 코드베이스에 없어 제외한다("미확정" 5번, Issue #54와 동일한 범위 축소).
- [ ] **Step 2: 대상 테스트를 실행해 PASS를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*PasswordChangeIntegrationTest" --no-daemon --max-workers=1
  ```

- [ ] **Step 3: 논리 커밋한다**

  ```powershell
  git add src/test/java/com/finplay/api/auth/service/PasswordChangeIntegrationTest.java
  git commit -m "test: 비밀번호 변경 세션 처리와 무변경 롤백 통합 검증"
  ```

## Task 4: PRD·API 문서 동기화와 전체 회귀

**Files**

- Modify: `ai/prd.md`
- Modify: `ai/api-routes.md`
- Modify: `docs/api-contracts.md`
- Modify: `ai/specs/002-auth-account/tasks.md`
- Modify during feature workflow: `ai/specs/002-auth-account/run-log.md`

- [ ] **Step 1: PRD AUTH-005에 비밀번호 변경 항목을 추가한다**
  - 이 계획의 "PRD `AUTH-005` (비밀번호 변경 부분 — 이번 이슈에서 신설)" 6개 문장을 AUTH-005 요구사항 목록에 넣는다.
  - 특히 "기존 Refresh Token을 모두 폐기하고 요청한 기기에만 새 토큰 쌍을 발급한다"가 바로 위 이메일 변경 항목의 "재로그인을 요구한다"와 다르다는 점이 문서상 분명히 드러나게 쓴다(D5).
- [ ] **Step 2: 실제 Controller 매핑으로 API 문서를 동기화한다**
  - `ai/api-routes.md` 라우트 표에 `PATCH /api/auth/me/password` 행과 Security 보호 목록 행을 추가한다.
  - `docs/api-contracts.md` 인증 도메인 절에 요청 본문, 200 `TokenResponse`, 400/401/403 오류 계약과 "다른 기기만 로그아웃" 부수 효과를 기록한다.
- [ ] **Step 3: tasks 상태를 갱신한다** — `ai/specs/002-auth-account/tasks.md`의 Issue #114 절을 완료 표시한다.
- [ ] **Step 4: Spotless와 대상 테스트를 실행한다**

  ```powershell
  .\gradlew.bat spotlessApply
  .\gradlew.bat test --tests "*AuthServiceTest" --tests "*AuthControllerTest" --tests "*PasswordChangeIntegrationTest" --tests "*LoginIntegrationTest" --tests "*NicknameChangeIntegrationTest" --no-daemon --max-workers=1
  ```

- [ ] **Step 5: 전체 게이트를 실행한다**

  ```powershell
  .\gradlew.bat build --no-daemon --max-workers=1
  ```

- [ ] **Step 6: 현재 diff와 라우트 일치를 확인한다**

  ```powershell
  git diff --check
  git diff -- src/main/java/com/finplay/api/auth/controller/AuthController.java ai/api-routes.md docs/api-contracts.md
  git status --short
  ```

- [ ] **Step 7: 문서 변경을 논리 커밋한다**

  ```powershell
  git add ai/prd.md ai/api-routes.md docs/api-contracts.md ai/specs/002-auth-account/tasks.md ai/specs/002-auth-account/run-log.md
  git commit -m "docs: 비밀번호 변경 API 요구사항과 계약 동기화"
  ```

---

## 완료 체크리스트

- [ ] `PATCH /api/auth/me/password`는 유효한 Access Bearer를 요구한다.
- [ ] 올바른 현재 비밀번호로 변경하면 새 비밀번호로 로그인되고 기존 비밀번호 로그인은 401이다.
- [ ] 잘못된 현재 비밀번호는 403 `REAUTHENTICATION_FAILED`이며 `password_hash`가 바뀌지 않는다.
- [ ] 정책 위반 새 비밀번호(8자 미만·100자 초과·공백)와 현재 비밀번호와 동일한 새 비밀번호는 400 `VALIDATION_ERROR`이며 아무 것도 바뀌지 않는다.
- [ ] OAuth 전용 회원의 요청은 400 `VALIDATION_ERROR`("OAuth 전용 회원은 비밀번호를 변경할 수 없습니다.")로 거부되고 `password_hash`·Refresh Token·`social_accounts`가 불변이다.
- [ ] 변경 성공 후 다른 기기의 기존 Refresh Token은 401이고, 응답으로 받은 새 Refresh Token은 정상 동작한다.
- [ ] 폐기 → 발급 순서가 단위 테스트(`InOrder`)와 통합 테스트 양쪽으로 고정돼 있다.
- [ ] 어떤 응답에도 `passwordHash`·`currentPassword`·`newPassword`가 어떤 이름으로도 없다.
- [ ] 변경 전후로 이메일·닉네임·계좌 2개(STOCK·CRYPTO)·시드머니·잔액이 실제 MySQL 통합 테스트로 불변임이 확인된다.
- [ ] 새 Flyway 마이그레이션·새 `ErrorCode`를 추가하지 않았다(D7).
- [ ] `ai/prd.md` AUTH-005에 비밀번호 변경 항목이 추가됐고, `ai/api-routes.md`·`docs/api-contracts.md`가 실제 Controller 매핑과 일치한다.
- [ ] `.\gradlew.bat build --no-daemon --max-workers=1`(Spotless·SpotBugs·JaCoCo 포함)이 통과한다.

---

## 미확정·PRD 불일치 (임의로 정하지 않고 보고하는 항목)

1. **PRD `AUTH-005`에 비밀번호 변경 항목이 아예 없다 (PRD 공백, 가장 중요).** `ai/prd.md` 267-288행의 AUTH-005는 닉네임·이메일 변경만 규정하고 비밀번호 변경을 한 줄도 언급하지 않는다. 이슈 #114가 "이 이슈에서 PRD 항목을 추가하고 함께 구현한다"고 명시해 이번 계획은 위 "요구사항 ID와 수용 기준" 절의 6개 문장을 PRD에 추가하는 것으로 잡았다(Task 4 Step 1). 다만 `ai/prd.md` 머리말은 **제품 범위의 정본이 Notion "10 X TEN"이며 저장소 PRD는 그 스냅샷**이라고 못박고 있다 — 따라서 이 항목 추가는 Notion에도 반영되어야 하며, Notion에 비밀번호 변경이 1차 MVP로 확정돼 있는지 팀 확인이 필요하다. 저장소 문서만 먼저 고치면 두 정본이 갈라진다.
2. **팀 확정 1 — 성공 시 세션 처리가 PRD의 이메일 변경 문장과 다르다.** PRD AUTH-005는 이메일 변경에 대해 "기존 Refresh Token을 모두 폐기하고 **재로그인을 요구한다**"고 적었고 Issue #56이 그대로 구현했다. 비밀번호 변경은 팀 결정에 따라 전부 폐기 후 요청 기기용 새 토큰 쌍을 즉시 발급해 **다른 기기만 로그아웃**시킨다(D5). 두 기능의 동작이 의도적으로 다르다는 점을 PRD 문장에도 명시해야 하며(Task 4 Step 1), 그렇지 않으면 이후 읽는 사람이 #56 문장을 비밀번호 변경에도 적용된다고 오해한다. 근거는 D5에 기록했다.
3. **팀 확정 2 — OAuth 전용 회원 거부 코드.** 400 `VALIDATION_ERROR` + "OAuth 전용 회원은 비밀번호를 변경할 수 없습니다."로 확정했다(D2). 재인증 실패(403)가 아니라 계정 유형 불일치라는 근거를 D2에 기록했다. 이슈 본문은 "명시적으로 거부할지, 비밀번호 최초 설정을 별도 이슈로 뺄지 정한다"고만 적었고 PRD에는 관련 문장이 없다. OAuth 전용 회원의 비밀번호 최초 설정은 이번 범위에서 제외했으며, 필요해지면 별도 이슈로 만든다.
4. **비밀번호 변경 시도 횟수 제한·계정 잠금·감사 로그를 두지 않는다.** 이슈·PRD 모두 요구하지 않아 이번 범위에서 제외했다(그래서 D6이 단순 `@Transactional`로 충분하다). 현재 비밀번호를 모르는 상태에서 403을 반복해서 받아 볼 수 있으므로, 무차별 대입 방어가 필요하다고 판단되면 별도 이슈로 다뤄야 한다 — 다만 이 엔드포인트는 이미 유효한 Access Bearer를 요구하므로 로그인 엔드포인트보다 노출 면적이 작다.
5. **주문·체결(Order/Execution) 불변 검증 범위 축소.** 이슈의 수용 기준은 "주문·체결은 그대로 유지"를 요구하지만, 현재 코드베이스에 주문·체결 도메인 엔티티·리포지터리·픽스처가 없다(계좌 도메인만 존재). Issue #54·#55와 동일하게 계좌·시드머니·잔액까지만 통합 테스트로 검증하고, 주문·체결 불변 검증은 해당 도메인 구현 이후로 미룬다. 존재하지 않는 도메인에 대한 테스트를 추측으로 작성하지 않는다.
6. **새 비밀번호가 현재와 같을 때의 오류 코드.** 이슈는 "현재 비밀번호와 동일하면 거부한다"고만 적고 코드를 지정하지 않았다. 값 자체는 8~100자 정책을 만족하지만 요청이 성립하지 않는 경우이므로 400 `VALIDATION_ERROR` + 커스텀 메시지로 정했다(D3·D7). 409 `DUPLICATE_RESOURCE`를 쓰는 대안도 있었지만 이 코드는 "다른 회원이 이미 쓰는 값"이라는 뜻으로 고정돼 있어 기각했다.
7. **응답 DTO 선택.** 200 `TokenResponse`(로그인·재발급과 동일 스키마)로 정했다(D8). 이슈 본문이 "200 `TokenResponse`"를 지시했고 팀 확정 1과도 일관되지만, PRD에는 이 엔드포인트의 성공 응답 스키마가 명시돼 있지 않다. `/me/nickname`·`/email-changes/confirm`이 `MemberResponse`를 반환하는 것과 응답 형태가 달라지는 점은 의도된 차이다.
8. **`currentPassword`에 최소 길이 검증을 걸지 않는다.** 8자 미만 현재 비밀번호를 보내면 400이 아니라 403이 된다(D1). 이슈·PRD가 이 세부를 지정하지 않았고, 재인증 값의 형식으로 정책 정보를 흘리지 않는 편을 택했다.
