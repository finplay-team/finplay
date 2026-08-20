# Issue #54 재인증 기반 닉네임 수정 API 구현 계획

> **For agentic workers:** 각 Task는 실패 테스트를 먼저 작성한 뒤 구현한다. Step은 체크박스(`- [ ]`)로 추적한다.

**Goal:** 인증 사용자가 `PATCH /api/auth/me/nickname`으로 본인 닉네임을 변경한다. 이메일 회원은 현재 비밀번호로, OAuth 전용 회원은 Issue #53이 발급한 5분 유효·일회용 `reauthToken`으로 재인증을 증명해야 하며, 검증과 닉네임 변경을 하나의 트랜잭션으로 처리한다. 실패 시 아무 것도 바뀌지 않고, 성공해도 이메일·계좌·시드머니·잔액·주문·체결·투자일기는 그대로 유지된다.

**관련 정본:** GitHub Issue #54, PRD `AUTH-005`(`docs/prd.md` 268-281행), `spec.md`, `plan.md`, Issue #8·#53 계획, ADR-0002, ADR-0003, ADR-0004, `docs/conventions.md`

**선행:** Issue #8(`GET /api/auth/me`, `MemberResponse`, `SignupMethod`, `SocialAccountRepository.findByUserId`)과 Issue #53(`reauth_tokens` 스키마, `ReauthToken` 엔티티, `ReauthTokenRepository`, `AuthService.reauthenticate`로 5분 유효·일회용 `reauthToken` 발급)이 `origin/dev`에 병합돼 있다. 이번 이슈는 그 토큰을 처음으로 **소비**하는 사례다.

**Architecture:** 기존 `AuthController → AuthService → UserRepository`/`SocialAccountRepository`/`ReauthTokenRepository` 흐름만 확장한다. `AuthController`에 같은 클래스 안에서 `PATCH /me/nickname` 매핑을 추가하고, `AuthService`에 새 트랜잭션 메서드 `changeNickname`을 추가한다. 이 메서드는 (1) 회원 조회, (2) `SocialAccountRepository.findByUserId`로 가입 방식 판별(Issue #8 `getMe`와 동일 패턴), (3) 가입 방식에 따라 비밀번호 대조 또는 `ReauthTokenRepository`의 신규 원자적 소비 쿼리 호출, (4) 중복 없는 닉네임으로 `User.changeNickname` 호출 순으로 진행한다. 새 컨트롤러·Security 컴포넌트·Flyway 마이그레이션은 만들지 않는다.

**Tech Stack:** Java 17, Spring Boot 4.1, Spring Security, Spring Data JPA, MySQL 8.4 Testcontainers, JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

---

## 요구사항 ID와 수용 기준

### PRD `AUTH-005` (닉네임 변경 부분)

- 실명 `name` 필드는 추가하지 않으며 화면의 이름은 `nickname`을 사용한다.
- 이메일 회원의 닉네임 변경은 현재 비밀번호 확인을 요구한다.
- OAuth 전용 회원의 닉네임 변경은 AUTH-003(Issue #53)에서 발급한 5분 유효·일회용 `reauthToken`을 요구한다.
- 닉네임 변경은 중복되지 않는 새 `nickname`과 재인증 증명을 받아 즉시 반영한다. 이메일·계좌·잔액·주문·체결·투자일기는 변경하지 않는다.
- 이미 다른 회원이 사용하는 닉네임은 409 `DUPLICATE_RESOURCE`로 거부한다.
- 재인증 토큰 원문은 저장하지 않고 해시만 저장하며, 성공 시 한 번만 소비한다(스키마는 Issue #53에서 이미 확정, 이번 이슈는 소비 로직만 추가).

### Issue #54 수용 시나리오

1. Given 이메일 회원이 올바른 현재 비밀번호를 아는 상태
2. When 새 닉네임과 현재 비밀번호로 `PATCH /api/auth/me/nickname` 호출
3. Then 200과 갱신된 회원 정보를 받고, 닉네임만 바뀌며 이메일·계좌·시드머니·잔액은 그대로다.
4. Given OAuth 전용 회원이 Issue #53으로 발급받은 유효한 `reauthToken`을 가진 상태
5. When 새 닉네임과 그 `reauthToken`으로 `PATCH /api/auth/me/nickname` 호출
6. Then 200과 갱신된 회원 정보를 받고, 그 `reauthToken`은 즉시 소비되어 재사용이 거부된다.
7. And 이메일 회원이 잘못된 비밀번호를 제출하거나, OAuth 회원이 만료·이미 소비·다른 회원 소유의 `reauthToken`을 제출하면 정보 변경 없이 403 `REAUTHENTICATION_FAILED`다.
8. And 이미 다른 회원이 쓰는 닉네임으로 변경을 시도하면 409 `DUPLICATE_RESOURCE`이며 정보는 변경되지 않는다.
9. And Access Bearer가 없거나 만료·변조됐거나 Refresh Token을 Bearer로 제출하면 401 `UNAUTHORIZED`이며 서비스는 호출되지 않는다.
10. And 어떤 응답에도 `passwordHash`·재인증 토큰·OAuth 제공자 식별자는 노출되지 않는다.

---

## 범위와 제외

### 포함

- `PATCH /api/auth/me/nickname` — Access Bearer 필수(기존 `SecurityConfig` 기본 보호 재사용, 화이트리스트 추가 없음).
- `NicknameUpdateRequest(nickname, currentPassword, reauthToken)` 요청 DTO — `currentPassword`·`reauthToken`은 둘 다 Bean Validation 상 선택적, 실제 필수 여부는 서비스가 회원의 실제 가입 방식으로 판별(D1).
- `AuthService.changeNickname` — 재인증 증명 검증(비밀번호 대조 또는 `reauthToken` 원자적 소비) + 닉네임 변경을 한 트랜잭션으로 처리.
- `ReauthTokenRepository`에 해시·소유자·유효성을 한 번에 확인하는 원자적 소비 커스텀 쿼리 메서드 추가(D2) — 이후 이슈(#55 등)의 재인증 소비 로직도 재사용 가능하도록 일반적인 이름으로 설계.
- `User`에 `changeNickname(nickname, now)` 메서드 추가(setter 금지 컨벤션).
- `UserRepository`에 본인 제외 중복 확인 쿼리 메서드 추가.
- 성공 응답은 `MemberResponse`(Issue #8과 동일 DTO) 재사용.
- 서비스 단위 테스트, Repository `@DataJpaTest`, Controller `@WebMvcTest`/Security 슬라이스, 실제 MySQL 통합 테스트(이메일/OAuth 각각 성공·실패, 계좌 2개·잔액 불변).
- 구현 뒤 `docs/api-routes.md`·`tasks.md` 동기화.

### 제외

- 실명 `name` 필드 추가.
- 이메일 변경, 비밀번호 변경·재설정(AUTH-005의 다른 항목, 별도 후속 이슈).
- 프로필 이미지, 회원 탈퇴.
- 관리자, 1차 이후 고도화 기능.
- 새 Flyway 마이그레이션 — `reauth_tokens` 스키마(해시·만료·`consumed_at`)는 Issue #53에서 이미 확정돼 있고 이번 이슈는 기존 컬럼을 소비 조건으로 쓸 뿐 스키마를 바꾸지 않는다(ADR-0004, D6 참고).
- `reauthToken` 발급 로직 변경 — Issue #53의 `AuthService.reauthenticate`, `OAuthCallbackService`, authorize/callback 계약은 손대지 않는다.

---

## 설계 결정

### D1. 요청 DTO는 `currentPassword`·`reauthToken`을 모두 선택 필드로 두고, 서비스가 회원의 실제 가입 방식으로 필수 여부를 판별한다

```java
public record NicknameUpdateRequest(
    @NotBlank(message = "닉네임은 필수입니다.")
    @Size(max = 50, message = "닉네임은 최대 50자까지 입력할 수 있습니다.")
    String nickname,
    @Size(max = 100, message = "현재 비밀번호는 최대 100자까지 입력할 수 있습니다.")
    String currentPassword,
    @Size(max = 255, message = "재인증 토큰은 최대 255자까지 입력할 수 있습니다.")
    String reauthToken) {
}
```

- Bean Validation은 "이메일 회원이면 A 필수, OAuth 회원이면 B 필수" 같은 조건부 규칙을 표현할 수 없다(레코드 컴포넌트 단위 애노테이션은 다른 필드 값을 참조하지 못한다). 그래서 두 필드 모두 `@NotBlank`를 걸지 않고 서비스 레이어에서 필수 여부를 판별한다.
- **판별 기준은 클라이언트가 어떤 필드를 채웠는지가 아니라, 서버가 DB에서 조회한 회원의 실제 가입 방식이다.** Issue #8의 `getMe`가 이미 쓰는 `socialAccountRepository.findByUserId(userId)` 조회를 그대로 재사용한다(있으면 그 `provider`로 `SignupMethod`, 없으면 `EMAIL`). 클라이언트가 보낸 필드 존재 여부로만 분기하면, 이메일 회원이 실수로(혹은 의도적으로) `currentPassword`를 비우고 아무 `reauthToken` 문자열만 채워 비밀번호 검증 자체를 우회하려 시도할 수 있다 — 서버가 실제 가입 방식을 신뢰 기준으로 삼아야 이 우회가 불가능하다.
- 판별된 가입 방식에 필요한 필드가 비어 있으면 400 `VALIDATION_ERROR`(구조적으로 요청이 불완전 — "이메일 회원은 현재 비밀번호가 필요합니다." 같은 커스텀 메시지를 `BusinessException(ErrorCode, String)` 생성자로 담는다). 필드는 채워졌지만 값이 틀리면(비밀번호 불일치, 토큰 만료·소비·타인 소유) 403 `REAUTHENTICATION_FAILED`다 — "요청이 애초에 불완전함"과 "증명을 시도했지만 실패함"을 구분한다(아래 "미확정" 참고, PRD·이슈 본문이 이 세부 구분을 명시하지 않음).
- 가입 방식에 필요하지 않은 다른 필드(예: 이메일 회원이 `reauthToken`도 같이 보낸 경우)는 무시한다 — 검증하거나 거부하지 않는다. 단순성 우선.

### D2. `ReauthTokenRepository`에 해시·소유자·유효성을 한 번에 확인하는 원자적 소비 쿼리 메서드를 추가한다 (조회 후 저장 방식은 쓰지 않는다)

```java
public interface ReauthTokenRepository extends JpaRepository<ReauthToken, Long> {

    @Modifying
    @Query("""
        UPDATE ReauthToken reauthToken
        SET reauthToken.consumedAt = :now
        WHERE reauthToken.tokenHash = :tokenHash
            AND reauthToken.user.id = :userId
            AND reauthToken.consumedAt IS NULL
            AND reauthToken.expiresAt > :now
        """)
    int consumeIfValidForUser(
        @Param("tokenHash") String tokenHash,
        @Param("userId") Long userId,
        @Param("now") LocalDateTime now);
}
```

- **트레이드오프 — 원자적 UPDATE(채택) vs 조회 후 `consume()` 호출 후 저장(레이스 컨디션 가능):** 이 저장소는 이미 `RefreshTokenRepository.revokeIfActiveAndNotExpired(id, now)`로 "조건에 맞는 행만 UPDATE, 영향받은 행 수로 성공 판정" 패턴을 확립해 두었다(`refresh()`·`logout()`이 동일하게 사용). 같은 `reauthToken`으로 동시에 두 요청이 들어오는 재사용 공격을 막으려면 "조회 → `consumedAt` 세팅 → save"는 두 스레드가 모두 `consumedAt == null`을 읽은 뒤 각자 저장해 둘 다 성공 판정을 받을 수 있는 TOCTOU(Time-Of-Check-vs-Time-Of-Use) 레이스가 있다. 조건부 UPDATE는 DB 레벨에서 원자적으로 처리되므로 이 레이스가 구조적으로 불가능하다. 기존 패턴을 그대로 재사용하는 편이 새 패턴을 만드는 것보다 안전하고 일관적이다.
- **`user.id` 조건을 같은 쿼리 안에 포함한 이유:** 별도로 `findByTokenHash`로 조회한 뒤 애플리케이션에서 `socialAccount.getUser().getId().equals(userId)`를 비교하는 방식(Issue #53의 `reauthenticate`가 `SocialAccount` 소유자 확인에 쓰는 방식)도 가능했지만, 그렇게 하면 원자적 UPDATE 앞에 별도 조회가 필요해 두 번의 DB 왕복이 생기고, "조회 시점엔 내 소유였지만 UPDATE 시점엔 아니다" 같은 이론적 레이스도 새로 생긴다. 소유자 조건을 WHERE 절에 직접 넣으면 한 번의 원자적 쿼리로 "존재하고, 내 것이고, 아직 안 썼고, 안 만료됐음"을 동시에 확인해 다른 회원의 `reauthToken`을 도용하는 것도 구조적으로 차단한다.
- **실패 사유를 구분하지 않는 이유:** `consumeIfValidForUser`가 반환하는 영향받은 행 수가 1이 아니면(토큰 미존재, 다른 회원 소유, 이미 소비됨, 만료됨 중 어느 쪽이든) 서비스는 모두 403 `REAUTHENTICATION_FAILED`로 처리한다. Issue #53이 이미 "다른 계정/미연결 provider/state 위·변조" 등 서로 다른 재인증 실패 사유를 하나의 403으로 통일한 선례를 따른다 — 실패 사유별로 다른 응답을 주면 공격자가 토큰의 어느 속성이 잘못됐는지 추측할 수 있는 정보를 얻는다.
- **`findByTokenHash` 같은 별도 읽기 전용 조회 메서드는 추가하지 않는다.** 이번 이슈의 유일한 사용처가 소비 하나뿐이고, 조회 따로·소비 따로로 나누면 위에서 설명한 레이스만 다시 만든다. 향후 다른 기능이 "소비하지 않고 상태만 확인" 같은 별도 요구를 명확히 가져오면 그때 추가한다(conventions의 "세 번째 중복이 보이고 책임이 명확할 때만 공통화" 원칙과 동일선상 — 지금은 불필요한 메서드를 미리 만들지 않는다).
- **엔티티에 `consume()` 인스턴스 메서드를 추가하지 않는다.** `RefreshToken` 엔티티도 `revokedAt`을 세팅하는 인스턴스 메서드가 없다 — `RefreshTokenRepository.revokeIfActiveAndNotExpired`가 JPQL 벌크 UPDATE로 엔티티를 거치지 않고 직접 컬럼을 바꾼다. `ReauthToken`도 같은 선례를 따라 `consumeIfValidForUser`가 유일한 소비 경로이며 엔티티에 대응하는 `consume(now)` 메서드를 두지 않는다. (엔티티의 `create()` 정적 팩토리와 `Getter`는 Issue #53에서 이미 있고 그대로 유지한다.)
- **향후 재사용(#55 등)을 고려한 일반적인 메서드 이름:** `consumeIfValidForUser(tokenHash, userId, now)`는 "닉네임 변경"이라는 이번 이슈의 용도를 이름에 넣지 않았다 — 어떤 재인증 소비 시나리오(이메일 변경 등)에서도 동일한 시그니처로 재사용 가능하다. 반면 재인증 검증 로직 자체(가입 방식 판별 → 비밀번호 대조 또는 토큰 소비 → 실패 시 403)를 별도 헬퍼 클래스(`ReauthValidator` 같은)로 지금 추출하지는 않는다 — 이번 이슈가 최초의 소비 사례이고 아직 두 번째 호출부가 없어(conventions "세 번째 중복" 원칙), `AuthService.changeNickname` 안에 인라인으로 둔다. #55가 병합되어 같은 로직이 반복되면 그때 추출을 재검토한다.

### D3. `User.changeNickname(nickname, now)` — setter 대신 의도가 드러나는 인스턴스 메서드

```java
public void changeNickname(String nickname, LocalDateTime now) {
    this.nickname = nickname;
    this.updatedAt = now;
}
```

- conventions의 Entity 규칙("setter를 두지 않는다. 상태 변경은 `fill`, `revoke`, `consume`처럼 의도가 드러나는 메서드로만 한다")을 따른다.
- `updatedAt`을 함께 갱신한다 — 다른 필드(이메일 등)를 건드리지 않는다.

### D4. 닉네임 중복 확인은 "본인 제외 사전 확인 + 저장 시점 예외 캐치"를 함께 쓴다 (기존 `saveUser`/`saveOAuthUser` 패턴과 동일)

```java
// UserRepository에 추가
boolean existsByNicknameAndIdNot(String nickname, Long id);
```

```java
// AuthService.changeNickname 내부
if (!user.getNickname().equals(newNickname)
    && userRepository.existsByNicknameAndIdNot(newNickname, userId)) {
    throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
}
user.changeNickname(newNickname, now);
try {
    userRepository.saveAndFlush(user);
} catch (DataIntegrityViolationException ex) {
    throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
}
```

- **본인 제외(`IdNot`)가 필요한 이유:** 단순 `existsByNickname(newNickname)`을 쓰면, 사용자가 "새 닉네임"으로 자신의 현재 닉네임을 그대로 제출했을 때도 이미 DB에 그 닉네임(자기 자신의 행)이 존재하므로 거짓으로 409가 난다. `existsByNicknameAndIdNot`으로 자기 자신의 행을 제외해야 "실제로 다른 회원이 쓰는 닉네임"만 걸러진다.
- **사전 확인만으로 끝내지 않고 저장 시점 예외도 잡는 이유:** 두 요청이 동시에 같은 새 닉네임으로 변경을 시도하면 사전 확인 사이의 경쟁 상태로 둘 다 통과할 수 있다 — `users.nickname` UNIQUE 제약(기존 스키마)이 최종 방어선이므로, 기존 `saveUser`/`saveOAuthUser`와 동일하게 `DataIntegrityViolationException`을 `DUPLICATE_RESOURCE`로 변환한다.
- 저장은 `saveAndFlush`로 트랜잭션 커밋을 기다리지 않고 즉시 제약 위반을 확인한다(기존 패턴과 동일).
- **새 닉네임이 자신의 현재 닉네임과 같은 경우:** 이슈 본문·PRD가 이 경우를 명시하지 않는다. 이번 계획은 이를 "변경 없는 성공"(재인증 증명은 여전히 요구, 닉네임 값은 그대로, `updatedAt`만 갱신)으로 처리하기로 했다 — 굳이 자기 자신의 값을 "중복"으로 거부하면 혼란스러운 UX가 되고, PRD가 "새 nickname"에 엄격한 상이성을 요구한다고 명시하지도 않았기 때문이다(아래 "미확정" 참고).

### D5. 응답은 `MemberResponse`(Issue #8과 동일 DTO)를 재사용한다 — 별도 최소 응답 DTO를 새로 만들지 않는다

```java
@Transactional
public MemberResponse changeNickname(
    Long userId, String newNickname, String currentPassword, String reauthToken) {
    LocalDateTime now = LocalDateTime.now(clock);
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    SignupMethod signupMethod = socialAccountRepository.findByUserId(userId)
        .map(socialAccount -> SignupMethod.fromProvider(socialAccount.getProvider()))
        .orElse(SignupMethod.EMAIL);

    if (signupMethod == SignupMethod.EMAIL) {
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "이메일 회원은 현재 비밀번호가 필요합니다.");
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
        }
    } else {
        if (reauthToken == null || reauthToken.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "OAuth 회원은 재인증 토큰이 필요합니다.");
        }
        int consumed = reauthTokenRepository.consumeIfValidForUser(sha256(reauthToken), userId, now);
        if (consumed != 1) {
            throw new BusinessException(ErrorCode.REAUTHENTICATION_FAILED);
        }
    }

    if (!user.getNickname().equals(newNickname)
        && userRepository.existsByNicknameAndIdNot(newNickname, userId)) {
        throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
    }
    user.changeNickname(newNickname, now);
    try {
        userRepository.saveAndFlush(user);
    } catch (DataIntegrityViolationException ex) {
        throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
    }

    return MemberResponse.from(user, signupMethod);
}
```

- `MemberResponse(id, email, nickname, signupMethod)`는 Issue #8에서 이미 "비밀번호 해시·OAuth 제공자 식별자·재인증 토큰을 담지 않는다"는 계약이 고정돼 있다. 새 최소 응답(`{"nickname":"..."}` 같은) DTO를 따로 만들면 같은 정보를 두 가지 형태로 반환하는 DTO가 늘어날 뿐이라 재사용을 택한다.
- 클라이언트는 PATCH 응답만으로 마이페이지 상태를 갱신할 수 있어 별도 `GET /api/auth/me` 재호출이 필요 없다는 부수 이점도 있다(요구되진 않았지만 무료로 따라오는 이점이며, 이 때문에 DTO를 설계하지는 않았다).
- 응답 경로 어디에도 `currentPassword`·`reauthToken`·`passwordHash`·소셜 제공자 식별자가 나타나지 않는다(구조적으로 `MemberResponse`에 그런 필드가 없다).

### D6. 트랜잭션 경계 — 재인증 증명 검증과 닉네임 변경을 하나의 `@Transactional` 메서드로 묶는다

- `changeNickname` 전체가 `@Transactional`이다. `reauthTokenRepository.consumeIfValidForUser`(토큰 소비)와 `userRepository.saveAndFlush`(닉네임 변경)가 같은 트랜잭션 안에서 실행되므로, 닉네임 저장이 실패(중복 등)하면 토큰 소비도 함께 롤백된다 — 실패한 시도로 유효한 토큰을 낭비하지 않는다.
- 이메일 회원 경로는 DB 쓰기가 비밀번호 검증에 없으므로(단순 조회+대조) 롤백 대상이 애초에 없지만, 트랜잭션 범위를 분기 없이 메서드 전체로 통일해 두 경로의 원자성 보장 방식을 동일하게 유지한다(이슈 지시 #3 — "재인증 증명과 입력값을 검증한 뒤 닉네임 변경을 한 트랜잭션으로 처리").
- `accountService`·`refreshTokenRepository`·`socialAccountRepository.save`는 이 메서드에서 절대 호출하지 않는다 — 계좌·시드머니·소셜 연결은 그대로 유지되어야 한다(이슈 지시 #4). 통합 테스트로 행 수·값 불변을 직접 검증한다(Task 4).

### D7. 새 `ErrorCode`를 추가하지 않는다 — 기존 `VALIDATION_ERROR`·`REAUTHENTICATION_FAILED`·`DUPLICATE_RESOURCE`·`UNAUTHORIZED`만 재사용한다

| 상황 | 코드 | 근거 |
|---|---|---|
| Access Bearer 없음·만료·변조 | 401 `UNAUTHORIZED` | 기존 Security 필터 계약, 변경 없음 |
| 유효 Access Token의 주체가 DB에 없음(극단적 경쟁 상태) | 401 `UNAUTHORIZED` | Issue #8 `getMe`와 동일한 방어적 처리(계정 존재 여부 미노출) |
| 가입 방식에 필요한 필드(`currentPassword`/`reauthToken`) 누락·공백 | 400 `VALIDATION_ERROR` | 구조적으로 요청이 불완전 — 아직 증명 자체를 시도하지 않음 |
| 이메일 회원의 비밀번호 불일치 | 403 `REAUTHENTICATION_FAILED` | 아래 "미확정" 1번 참고 — 기존 `REAUTHENTICATION_FAILED`를 "재인증 증명 실패"라는 일반 의미로 확장 재사용 |
| OAuth 회원의 `reauthToken`이 없음/만료/이미 소비/타인 소유 | 403 `REAUTHENTICATION_FAILED` | Issue #53과 동일한 코드, 실패 사유 비공개 |
| 새 닉네임이 다른 회원의 것과 중복 | 409 `DUPLICATE_RESOURCE` | 기존 회원가입 중복 처리와 동일한 코드 |

- **왜 이메일 회원의 비밀번호 불일치에도 `REAUTHENTICATION_FAILED`를 쓰는가:** `login()`은 비밀번호 불일치를 401 `UNAUTHORIZED`로 처리하지만, 그 이유는 "이메일이 가입돼 있는지 자체를 숨기기 위해"다(회원 존재 여부 미노출). 이번 엔드포인트는 이미 유효한 Access Bearer로 신원이 확정된 상태에서 "본인이 맞다는 재인증 증명"만 확인하는 것이므로 `login()`과 위협 모델이 다르다. `REAUTHENTICATION_FAILED`(403)는 이름·기본 메시지("재인증에 실패했습니다.")가 이미 이 의미에 정확히 들어맞고, 이슈 본문도 "잘못된 비밀번호, 만료·재사용 토큰은 정보 변경 없이 거부됩니다"라고 두 실패를 한 문장에 나란히 적어 같은 그룹으로 취급할 근거를 준다. 새 `ErrorCode`(예: `INVALID_CURRENT_PASSWORD`)를 추가하는 대안도 있었지만, 의미가 이미 있는 코드를 두고 사실상 같은 개념의 코드를 하나 더 만드는 것은 불필요한 확장이라 기각했다.

---

## HTTP 계약

| 항목 | 내용 |
|---|---|
| Method / Path | `PATCH /api/auth/me/nickname` |
| 인증 | `Authorization: Bearer <유효한 Access JWT>` (기존 보호, 화이트리스트 추가 없음) |
| 요청 본문(이메일 회원) | `{"nickname":"새닉네임","currentPassword":"현재비밀번호"}` |
| 요청 본문(OAuth 회원) | `{"nickname":"새닉네임","reauthToken":"<Issue #53이 발급한 원문 토큰>"}` |
| 성공 | 200 `{"id":42,"email":"user@finplay.com","nickname":"새닉네임","signupMethod":"EMAIL"}` (`MemberResponse`, Issue #8과 동일 스키마) |
| 닉네임 누락·공백·50자 초과, 가입 방식에 필요한 필드 누락 | 400 `VALIDATION_ERROR` |
| Access Token 없음·만료·변조·잘못된 타입 | 401 `UNAUTHORIZED` |
| 비밀번호 불일치, `reauthToken` 만료·이미 소비·타인 소유·형식 오류 | 403 `REAUTHENTICATION_FAILED` |
| 다른 회원이 쓰는 닉네임 | 409 `DUPLICATE_RESOURCE` |

`ResponseEntity.ok(...)`로 200을 반환한다(conventions "수정 200" 규칙).

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/auth/dto/request/NicknameUpdateRequest.java`

### Production files to modify

- `src/main/java/com/finplay/api/auth/domain/User.java` — `changeNickname(nickname, now)` 인스턴스 메서드 추가(D3).
- `src/main/java/com/finplay/api/auth/repository/UserRepository.java` — `existsByNicknameAndIdNot(nickname, id)` 추가(D4).
- `src/main/java/com/finplay/api/auth/repository/ReauthTokenRepository.java` — `consumeIfValidForUser(tokenHash, userId, now)` 원자적 소비 쿼리 추가(D2).
- `src/main/java/com/finplay/api/auth/service/AuthService.java` — `changeNickname(userId, newNickname, currentPassword, reauthToken)` 추가. 기존 생성자·필드는 변경 없음(모든 의존성이 이미 주입돼 있음).
- `src/main/java/com/finplay/api/auth/controller/AuthController.java` — `@PatchMapping("/me/nickname")` 추가.

### Test files to modify

- `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java` — `changeNickname` 단위 테스트(Mockito) 추가.
- `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java` — `PATCH /me/nickname` WebMvc 슬라이스 테스트 추가.
- `src/test/java/com/finplay/api/auth/repository/ReauthTokenRepositoryTest.java` — `consumeIfValidForUser`의 성공·이미 소비·만료·타인 소유 케이스를 `@DataJpaTest`로 추가(기존 저장·UNIQUE·FK 테스트는 그대로 유지).
- `src/test/java/com/finplay/api/auth/repository/UserRepositoryTest.java` — 파일이 이미 존재한다(저장소 확인 완료). `existsByNicknameAndIdNot` 케이스만 추가한다.

### Test files to create

- `src/test/java/com/finplay/api/auth/service/NicknameChangeIntegrationTest.java` — 실제 MySQL로 이메일/OAuth 각각의 성공·재사용 거부·중복 거부와 계좌·잔액 불변을 검증(Task 4 참고).

### Documentation files to modify after implementation

- `docs/api-routes.md` — `PATCH /api/auth/me/nickname` 라우트·상세 계약 추가.
- `docs/specs/002-auth-account/tasks.md` — "JWT·Security" 항목 아래 Issue #54 작업 항목 절 추가.
- `docs/specs/002-auth-account/run-log.md` — implementer·reviewer가 실행한 명령과 근거만 기록(사람이 직접 쓰지 않음).

### 수정하지 않을 파일

- `src/main/resources/db/migration/V1__init.sql` ~ `V5__create_reauth_tokens_table.sql` — 새 마이그레이션 없음(D6 범위 제외, ADR-0004).
- `src/main/java/com/finplay/api/auth/domain/ReauthToken.java` — 새 인스턴스 메서드(`consume()`)를 추가하지 않는다(D2).
- `src/main/java/com/finplay/api/auth/service/OAuthCallbackService.java`, `OAuthAuthorizationService.java`, `AuthService.reauthenticate` — Issue #53의 발급 로직은 한 글자도 바꾸지 않는다.
- `src/main/java/com/finplay/api/common/ErrorCode.java` — 새 코드 추가 없음(D7).
- `src/main/java/com/finplay/api/auth/config/SecurityConfig.java` — `PATCH /api/auth/me/nickname`은 화이트리스트에 없어 이미 `anyRequest().authenticated()`로 보호된다. 확인만 하고 수정하지 않는다.
- `src/main/java/com/finplay/api/auth/dto/response/MemberResponse.java` — 그대로 재사용, 필드 추가 없음(D5).

---

## Task 1: `ReauthTokenRepository` 원자적 소비 쿼리와 `UserRepository` 본인 제외 중복 확인

**Files**

- Modify: `src/main/java/com/finplay/api/auth/repository/ReauthTokenRepository.java`
- Modify: `src/main/java/com/finplay/api/auth/repository/UserRepository.java`
- Modify: `src/test/java/com/finplay/api/auth/repository/ReauthTokenRepositoryTest.java`
- Modify: `src/test/java/com/finplay/api/auth/repository/UserRepositoryTest.java`

**Interfaces**

- Produces: `int ReauthTokenRepository.consumeIfValidForUser(String tokenHash, Long userId, LocalDateTime now)`
- Produces: `boolean UserRepository.existsByNicknameAndIdNot(String nickname, Long id)`

- [ ] **Step 1: `@DataJpaTest` 실패 테스트를 먼저 작성한다**
  - `consumeIfValidForUserSucceedsAndReturnsOneForOwnedUnexpiredUnconsumedToken`
  - `consumeIfValidForUserReturnsZeroWhenAlreadyConsumed` — 먼저 한 번 소비한 뒤 같은 호출을 반복하면 0을 반환하고(재사용 방지), `consumedAt`이 최초 소비 시각으로 유지된다.
  - `consumeIfValidForUserReturnsZeroWhenExpired`
  - `consumeIfValidForUserReturnsZeroWhenOwnedByDifferentUser`
  - `existsByNicknameAndIdNotReturnsFalseForOwnNickname` / `existsByNicknameAndIdNotReturnsTrueForOtherUsersNickname`
- [ ] **Step 2: 대상 테스트 실패를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*ReauthTokenRepositoryTest" --tests "*UserRepositoryTest" --no-daemon --max-workers=1
  ```

  Expected: 메서드 부재로 컴파일 실패 또는 신규 케이스 FAIL.

- [ ] **Step 3: 최소 구현을 추가한다**
  - `ReauthTokenRepository.consumeIfValidForUser`는 D2의 JPQL `@Modifying @Query` 그대로.
  - `UserRepository.existsByNicknameAndIdNot`는 Spring Data 파생 쿼리 메서드 한 줄.
- [ ] **Step 4: 같은 대상 테스트를 다시 실행해 PASS를 확인한다**
- [ ] **Step 5: 논리 커밋한다**

  ```powershell
  git add src/main/java/com/finplay/api/auth/repository/ReauthTokenRepository.java src/main/java/com/finplay/api/auth/repository/UserRepository.java src/test/java/com/finplay/api/auth/repository/ReauthTokenRepositoryTest.java src/test/java/com/finplay/api/auth/repository/UserRepositoryTest.java
  git commit -m "feat: 재인증 토큰 원자적 소비와 닉네임 본인 제외 중복 확인 쿼리 추가"
  ```

## Task 2: `User.changeNickname`과 `AuthService.changeNickname` 서비스 로직

**Files**

- Modify: `src/main/java/com/finplay/api/auth/domain/User.java`
- Modify: `src/main/java/com/finplay/api/auth/service/AuthService.java`
- Modify: `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java`

**Interfaces**

- Produces: `void User.changeNickname(String nickname, LocalDateTime now)`
- Produces: `MemberResponse AuthService.changeNickname(Long userId, String newNickname, String currentPassword, String reauthToken)`

- [ ] **Step 1: 서비스 실패 테스트를 작성한다(Mockito)**
  - `changeNicknameSucceedsForEmailUserWithCorrectPassword` — 비밀번호 일치, 닉네임 미중복이면 `MemberResponse(signupMethod=EMAIL)`을 반환하고 `userRepository.saveAndFlush`가 변경된 닉네임으로 호출됨을 검증. `reauthTokenRepository`는 호출되지 않음(`verifyNoInteractions`류).
  - `changeNicknameFailsWithReauthenticationFailedForEmailUserWithWrongPassword` — `passwordEncoder.matches`가 `false`, 저장 호출 없음.
  - `changeNicknameFailsWithValidationErrorWhenEmailUserOmitsCurrentPassword`
  - `changeNicknameSucceedsForOAuthUserWithValidReauthToken` — `socialAccountRepository.findByUserId`가 `SocialAccount` 반환, `reauthTokenRepository.consumeIfValidForUser`가 1 반환 시 `MemberResponse(signupMethod=KAKAO 또는 NAVER)`를 반환. `passwordEncoder`는 호출되지 않음.
  - `changeNicknameFailsWithReauthenticationFailedForOAuthUserWhenConsumeReturnsZero` — 만료·이미 소비·타인 소유를 대표해 `consumeIfValidForUser`가 0을 반환하는 경우.
  - `changeNicknameFailsWithValidationErrorWhenOAuthUserOmitsReauthToken`
  - `changeNicknameFailsWithDuplicateResourceWhenNicknameOwnedByAnotherUser` — `existsByNicknameAndIdNot`이 `true`.
  - `changeNicknameFailsWithDuplicateResourceOnConcurrentUniqueViolation` — 사전 확인은 통과했지만 `saveAndFlush`가 `DataIntegrityViolationException`을 던지는 경쟁 상태 시뮬레이션.
  - `changeNicknameSucceedsAsNoOpWhenNewNicknameEqualsCurrentNickname` — 자기 자신의 현재 닉네임을 그대로 제출해도 `existsByNicknameAndIdNot` 호출 없이(또는 호출되더라도 결과와 무관하게) 성공.
  - `changeNicknameFailsWithUnauthorizedWhenUserNotFound`
- [ ] **Step 2: 대상 테스트 실패를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*AuthServiceTest" --no-daemon --max-workers=1
  ```

- [ ] **Step 3: 최소 구현을 추가한다**
  - `User.changeNickname`은 D3 그대로.
  - `AuthService.changeNickname`은 D5의 구현 그대로 — 기존 생성자 필드(`userRepository`, `socialAccountRepository`, `reauthTokenRepository`, `passwordEncoder`, `clock`)만 사용, 새 의존성 추가 없음.
- [ ] **Step 4: 같은 대상 테스트를 다시 실행해 PASS를 확인한다**
- [ ] **Step 5: 논리 커밋한다**

  ```powershell
  git add src/main/java/com/finplay/api/auth/domain/User.java src/main/java/com/finplay/api/auth/service/AuthService.java src/test/java/com/finplay/api/auth/service/AuthServiceTest.java
  git commit -m "feat: 재인증 기반 닉네임 변경 서비스 추가"
  ```

## Task 3: 보호된 `PATCH /api/auth/me/nickname` HTTP 계약

**Files**

- Create: `src/main/java/com/finplay/api/auth/dto/request/NicknameUpdateRequest.java`
- Modify: `src/main/java/com/finplay/api/auth/controller/AuthController.java`
- Modify: `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java`

**Interfaces**

- Consumes: `NicknameUpdateRequest`, Access Bearer의 `AuthenticatedUser`
- Produces: 200 `MemberResponse`

- [ ] **Step 1: WebMvc 실패 테스트를 작성한다**
  - `updateNicknameReturnsOkWithMemberResponseForEmailUser` — `authService.changeNickname(...)`이 반환한 `MemberResponse`를 그대로 200으로 반환, `jsonPath`로 필드값 확인.
  - `updateNicknameReturnsOkWithMemberResponseForOAuthUser`
  - `updateNicknameRejectsBlankNicknameWithoutCallingService` — 400, 서비스 미호출.
  - `updateNicknameRejectsNicknameOverMaxLengthWithoutCallingService`
  - `updateNicknameMapsServiceValidationErrorToBadRequest` — 서비스가 `BusinessException(VALIDATION_ERROR, "...")`를 던지면 400.
  - `updateNicknameMapsServiceReauthenticationFailedToForbidden` — 403.
  - `updateNicknameMapsServiceDuplicateResourceToConflict` — 409.
  - `updateNicknameRejectsMissingAccessTokenWithoutCallingService` — 401, 서비스 미호출.
  - `updateNicknameRejectsRefreshBearerWithoutCallingService` — 401(logout/me 테스트의 `jwtTokenProvider.parseAccessToken(...).thenReturn(Optional.empty())` 패턴 재사용).
  - 응답 JSON에 `currentPassword`·`reauthToken`·`passwordHash` 키가 없음을 확인.
- [ ] **Step 2: 대상 테스트 실패를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*AuthControllerTest" --no-daemon --max-workers=1
  ```

  Expected: `PATCH /me/nickname` 매핑 부재로 404 또는 신규 검증 FAIL.

- [ ] **Step 3: Controller 매핑만 구현한다**
  - `@PatchMapping("/me/nickname")`에 `@AuthenticationPrincipal AuthenticatedUser principal`, `@Valid @RequestBody NicknameUpdateRequest request`.
  - `authService.changeNickname(principal.userId(), request.nickname(), request.currentPassword(), request.reauthToken())` 호출 뒤 `ResponseEntity.ok(...)`.
  - `SecurityConfig`는 수정하지 않는다(D7 확인 항목).
- [ ] **Step 4: 같은 대상 테스트를 다시 실행해 PASS를 확인한다**
- [ ] **Step 5: 논리 커밋한다**

  ```powershell
  git add src/main/java/com/finplay/api/auth/dto/request/NicknameUpdateRequest.java src/main/java/com/finplay/api/auth/controller/AuthController.java src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java
  git commit -m "feat: 재인증 기반 닉네임 변경 API 추가"
  ```

## Task 4: 실제 MySQL 통합 검증 — 이메일/OAuth 재인증과 금융 데이터 불변

**Files**

- Create: `src/test/java/com/finplay/api/auth/service/NicknameChangeIntegrationTest.java`

- [ ] **Step 1: 실제 MySQL 통합 테스트를 작성한다**

  기존 `LoginIntegrationTest`(이메일 가입→로그인), `FakeOAuthFlowIntegrationTest`/`OAuthReauthCallbackIntegrationTest`(Fake OAuth 신규 가입 + Issue #53 재인증 콜백으로 `reauthToken` 발급) 준비 흐름을 재사용한다.

  - `changeNicknameUpdatesOnlyNicknameForEmailUserAndKeepsAccountsUnchanged` — 이메일 회원가입 → 로그인 → 두 계좌(STOCK, CRYPTO)의 `cashBalance`·`seedMoney`·`realizedPnl`을 변경 전 스냅샷으로 저장 → 올바른 현재 비밀번호로 `changeNickname` 호출 → `users.nickname`만 바뀌고 `users.email`, 두 `Account`의 잔액·시드머니·손익, `updated_at` 외 다른 계좌 컬럼이 스냅샷과 동일함을 확인.
  - `changeNicknameUpdatesOnlyNicknameForOAuthUserAndConsumesReauthTokenOnce` — Fake OAuth 신규 가입 → Issue #53 재인증 콜백으로 `reauthToken` 발급 → 그 토큰으로 `changeNickname` 성공 → 같은 토큰으로 재호출하면 403 `REAUTHENTICATION_FAILED`(재사용 거부) → 계좌 불변 확인.
  - `changeNicknameRejectsWrongPasswordWithoutAnyChange` — 잘못된 비밀번호 제출 시 닉네임·이메일·계좌 어느 것도 바뀌지 않음(실제 재조회로 확인).
  - `changeNicknameRejectsDuplicateNicknameWithoutAnyChange` — 다른 회원이 이미 쓰는 닉네임으로 시도하면 409, 두 회원의 닉네임 모두 변경 전과 동일.
  - **미확정(아래 절 참고):** 주문·체결(Order/Execution) 데이터 불변 검증은 해당 도메인 엔티티·테스트 픽스처가 현재 코드베이스에 없어 이번 이슈에서는 계좌(`Account`)·시드머니·잔액까지만 검증 범위로 좁힌다.
- [ ] **Step 2: 대상 테스트를 실행해 PASS를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*NicknameChangeIntegrationTest" --no-daemon --max-workers=1
  ```

- [ ] **Step 3: 논리 커밋한다**

  ```powershell
  git add src/test/java/com/finplay/api/auth/service/NicknameChangeIntegrationTest.java
  git commit -m "test: 재인증 기반 닉네임 변경과 금융 데이터 불변 통합 검증"
  ```

## Task 5: 전체 회귀·문서 동기화

**Files**

- Modify: `docs/api-routes.md`
- Modify: `docs/specs/002-auth-account/tasks.md`
- Modify during feature workflow: `docs/specs/002-auth-account/run-log.md`

- [ ] **Step 1: 실제 Controller 매핑으로 API 문서를 동기화한다**
  - 라우트 목록에 `PATCH /api/auth/me/nickname` 행 추가.
  - 상세 표에 이메일/OAuth 요청 본문 두 형태, 200 `MemberResponse`, 400/401/403/409 오류 계약 기록.
- [ ] **Step 2: tasks 상태를 갱신한다**
  - "JWT·Security" 항목 아래 Issue #54 작업 항목 절을 추가하고 완료 표시.
- [ ] **Step 3: Spotless와 대상 테스트를 실행한다**

  ```powershell
  .\gradlew.bat spotlessApply
  .\gradlew.bat test --tests "*AuthServiceTest" --tests "*AuthControllerTest" --tests "*ReauthTokenRepositoryTest" --tests "*UserRepositoryTest" --tests "*NicknameChangeIntegrationTest" --no-daemon --max-workers=1
  ```

- [ ] **Step 4: 전체 게이트를 실행한다**

  ```powershell
  .\gradlew.bat build --no-daemon --max-workers=1
  ```

- [ ] **Step 5: 현재 diff와 라우트 일치를 확인한다**

  ```powershell
  git diff --check
  git diff -- src/main/java/com/finplay/api/auth/controller/AuthController.java docs/api-routes.md
  git status --short
  ```

- [ ] **Step 6: 문서 변경을 논리 커밋한다**

  ```powershell
  git add docs/api-routes.md docs/specs/002-auth-account/tasks.md docs/specs/002-auth-account/run-log.md
  git commit -m "docs: 재인증 기반 닉네임 변경 API 계약 동기화"
  ```

---

## 완료 체크리스트

- [ ] `PATCH /api/auth/me/nickname`은 유효한 Access Bearer를 요구한다.
- [ ] 이메일 회원은 올바른 현재 비밀번호로, OAuth 회원은 유효한 `reauthToken`으로 닉네임을 변경할 수 있다.
- [ ] 잘못된 비밀번호, 만료·이미 소비·타인 소유 `reauthToken`은 403 `REAUTHENTICATION_FAILED`이며 정보가 변경되지 않는다.
- [ ] 가입 방식에 필요한 필드가 누락되면 400 `VALIDATION_ERROR`다.
- [ ] 중복 닉네임은 409 `DUPLICATE_RESOURCE`이며 정보가 변경되지 않는다(사전 확인 + 저장 시점 예외 캐치 둘 다로 경쟁 상태까지 방어).
- [ ] 같은 `reauthToken`의 재사용은 원자적 UPDATE로 구조적으로 차단된다.
- [ ] 다른 회원의 `reauthToken`으로는 자신의 닉네임을 바꿀 수 없다.
- [ ] 성공 응답(`MemberResponse`)에 `passwordHash`·재인증 토큰·OAuth 제공자 식별자가 어떤 이름으로도 없다.
- [ ] 변경 전후로 이메일·계좌 2개(STOCK·CRYPTO)·시드머니·잔액이 실제 MySQL 통합 테스트로 불변임이 확인된다.
- [ ] 새 Flyway 마이그레이션·새 `ErrorCode`를 추가하지 않았다(D6·D7).
- [ ] `docs/api-routes.md`가 실제 Controller 매핑과 일치한다.
- [ ] `.\gradlew.bat build --no-daemon --max-workers=1`(Spotless·SpotBugs·JaCoCo 포함)이 통과한다.

---

## 미확정·PRD 불일치 (임의로 정하고 보고하는 항목)

이슈 본문·PRD가 세부를 명시하지 않아 이번 계획에서 아래처럼 결정했다. 팀 리뷰에서 다르게 정하면 이 문서를 갱신한다.

1. **비밀번호 불일치에 신규 `ErrorCode` 대신 기존 `REAUTHENTICATION_FAILED`(403)를 재사용:** 이슈는 "잘못된 비밀번호, 만료·재사용 토큰은 정보 변경 없이 거부됩니다"라고만 적었고 구체적 코드를 지정하지 않았다. 기존 `login()`의 401 `UNAUTHORIZED`(계정 존재 은닉 목적)와는 위협 모델이 다르다고 판단해 재사용하지 않고, 대신 이미 있는 `REAUTHENTICATION_FAILED`를 "재인증 증명 실패"라는 일반 의미로 확장 재사용하기로 했다(D7). PRD에 이 코드 선택이 명시돼 있지는 않다.
2. **요청 필드 누락(400)과 증명 실패(403)를 구분:** 이슈·PRD 어디에도 "필드가 아예 없을 때"와 "필드는 있지만 값이 틀릴 때"를 다른 코드로 나누라는 지시가 없다. 이번 계획은 전자를 구조적 요청 문제(400 `VALIDATION_ERROR`), 후자를 재인증 증명 실패(403 `REAUTHENTICATION_FAILED`)로 나누기로 했다 — 회원가입 등 기존 엔드포인트가 "필수값 누락은 400" 원칙을 따르는 것과 일관되게 하기 위함이다.
3. **새 닉네임이 자신의 현재 닉네임과 동일한 경우 처리:** 이슈·PRD가 이 경우를 명시하지 않는다. "변경 없는 성공"(재인증은 여전히 요구, `existsByNicknameAndIdNot` 결과와 무관하게 자신의 닉네임과 같으면 통과, `updatedAt`만 갱신)으로 처리하기로 했다(D4) — 자기 자신의 값을 "중복"으로 거부하는 것이 오히려 직관에 어긋난다고 판단했다.
4. **가입 방식에 필요하지 않은 다른 필드(예: 이메일 회원이 `reauthToken`도 같이 제출)를 검증하거나 거부하지 않고 무시:** 이슈가 이 조합을 명시하지 않는다. 거부하는 대안도 있었지만 클라이언트 구현을 불필요하게 복잡하게 만들 뿐이라 무시하는 쪽을 택했다.
5. **`reauthToken` 소비 검증 순서 — 소유자 확인을 `SocialAccount`가 아니라 `ReauthToken.user_id`로 직접 확인:** Issue #53의 `reauthenticate`는 `SocialAccount`의 소유자로 확인했지만, 이번 이슈는 이미 발급된 `ReauthToken.user`가 소유자 정보를 그대로 갖고 있어 별도 `SocialAccount` 재조회 없이 `reauthTokenRepository.consumeIfValidForUser`의 `WHERE user.id = :userId` 조건 하나로 충분하다고 판단했다(D2). 이슈 본문이 이 세부 구현을 지정하지 않는다.
6. **주문·체결(Order/Execution) 데이터 불변 검증 범위 축소:** 이슈의 테스트 기준은 "계좌 2개·잔액·주문·체결 데이터가 동일함을 실제 MySQL 통합 테스트로 검증"이라고 명시한다. 그러나 현재 코드베이스(`src/main/java/com/finplay/api`)에는 주문(Order)·체결(Execution) 도메인 엔티티·리포지토리·테스트 픽스처가 아직 존재하지 않는다(계좌 도메인만 있음, `com.finplay.api.account.domain.Account`). 따라서 이번 계획의 통합 테스트(Task 4)는 계좌(`Account`)의 `cashBalance`·`seedMoney`·`realizedPnl` 불변까지만 검증하고, 주문·체결 불변 검증은 해당 도메인이 구현된 이후 별도로 추가하기로 했다 — 존재하지 않는 도메인에 대한 테스트를 추측으로 작성하지 않는다.
