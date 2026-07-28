# Issue #55 새 이메일 변경 인증번호 발송 API 구현 계획

> **For agentic workers:** 각 작업은 실패 테스트를 먼저 작성한다. Fake 발송기 자동 검증과 실제 발송 스모크를 서로 대체하거나 합쳐 보고하지 않는다.

**Goal:** `POST /api/auth/email-changes`를 신설해 인증 사용자의 새 이메일 소유권 확인을 위한 6자리 인증번호를 발송한다. 이메일 회원은 현재 비밀번호로, OAuth 전용 회원은 Issue #53의 5분 유효·일회용 `reauthToken`으로 재인증하며, 검증에 성공해야만 발송한다. 확인·실제 `users.email` 변경·Refresh Token 폐기는 후속 이슈로 미룬다.

**관련 정본:** GitHub Issue #55, PRD `AUTH-005` (`docs/prd.md` 268-289행), `docs/specs/002-auth-account/spec.md`, `docs/specs/002-auth-account/plan.md`, Issue #53 계획(`issue-53-plan.md`, 재인증 토큰 발급), ADR-0002, ADR-0003, ADR-0004, `docs/conventions.md`

**선행:** Issue #2·#3·#8·#53이 `dev`에 있다 — `User`·`SocialAccount`·`Account` 엔티티/마이그레이션(V1·V2), `GET /api/auth/me`(EMAIL/OAuth 판별 패턴), `AuthService.reauthenticate`로 발급되는 5분 유효·일회용 `reauthToken`과 `reauth_tokens` 테이블(V5, `token_hash`·`expires_at`·`consumed_at`).

**병행 이슈 주의:** Issue #54(닉네임 변경 API)가 이 계획과 동시에 진행 중이며 "이메일 회원=현재 비밀번호, OAuth 회원=`reauthToken`" 재인증 판별·소비 로직이 완전히 동일하게 필요하다. #54 planner와 SendMessage로 조율한 결과, `ReauthTokenRepository`에 추가할 메서드는 **`consumeIfValidForUser(tokenHash, userId, now)`**로 확정했다(#54가 먼저 확정한 이름을 그대로 채택 — 별도 `findByTokenHash` 조회 메서드는 두지 않는다, TOCTOU 레이스 방지 및 `RefreshTokenRepository.revokeIfActiveAndNotExpired`와 동일한 "조건부 원자적 UPDATE, 영향받은 행 수로 성공 판정" 패턴). #54가 먼저 dev에 병합되면 이 메서드가 이미 존재할 가능성이 높으므로, 그 시점에는 재구현하지 않고 그대로 재사용한다.

---

## Architecture

- 기존 `EmailVerificationService`(가입 전 인증)와 별도로 `EmailChangeService`를 신설한다 — 같은 도메인(auth) 안에서도 생명주기가 다른 기능은 분리한다는 기존 선례(`AuthService` vs `EmailVerificationService`)를 따른다.
- `EmailChangeController → EmailChangeService → EmailChangeVerificationRepository`로 레이어를 나누고, 재인증 판별을 위해 `UserRepository`·`SocialAccountRepository`·`ReauthTokenRepository`·`PasswordEncoder`를 조회 전용으로 참조한다.
- `AuthService`는 수정하지 않는다 — `reauthenticate`가 이미 `reauthToken`을 발급하는 지점이고, 이번 이슈는 그 토큰을 "소비"만 한다.
- `ReauthTokenRepository`에 원자적 소비 메서드 `consumeIfValidForUser(tokenHash, userId, now)`를 추가한다 (현재 `JpaRepository` 상속만 있음; #54와 조율된 이름, 별도 조회 메서드는 두지 않는다).

## Tech Stack

Java 17, Spring Boot 4.1, Spring Data JPA, MySQL 8.4(Testcontainers), Spring Security(`@AuthenticationPrincipal`), BCrypt(`PasswordEncoder`), JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

---

## 요구사항 ID와 수용 기준

### PRD `AUTH-005` (이메일 변경 발송 부분만 — 확인·실변경·Refresh Token 폐기는 범위 밖)

- 이메일 회원의 이메일 변경은 현재 비밀번호 확인을 요구한다.
- OAuth 전용 회원의 이메일 변경은 AUTH-003/#53에서 발급한 5분 유효·일회용 `reauthToken`을 요구한다.
- 새 이메일 요청은 새 이메일과 재인증 증명을 검증한 뒤 새 이메일로 6자리 인증번호를 발송한다. 확인 전까지 기존 이메일을 유지한다.
- 인증번호는 5분 유효, 최대 5회 시도, 60초 재발송 간격, 1시간 5회·하루 10회 제한을 적용한다. 재발송하면 이전 번호는 즉시 무효화한다.
- 이미 다른 회원이 사용하는 이메일은 409 `DUPLICATE_RESOURCE`로 거부한다.
- 재인증 토큰과 인증번호 원문은 저장하지 않고 해시만 저장하며, 성공 시 한 번만 소비한다.

### Issue #55 수용 시나리오

1. Given 이메일 회원이 올바른 현재 비밀번호를 제출하거나 OAuth 전용 회원이 유효한 `reauthToken`을 제출한 상태
2. When 사용 가능한 새 이메일로 `POST /api/auth/email-changes` 호출
3. Then 202로 발송 요청이 수락되고, 새 이메일로 6자리 인증번호가 발송되며 해당 회원의 `users.email`은 그대로 유지된다.
4. And 잘못된 현재 비밀번호·만료/재사용/타인 소유 `reauthToken`은 403 `REAUTHENTICATION_FAILED`이며 아무 데이터도 변하지 않는다.
5. And 다른 회원이 이미 사용 중인 새 이메일은 409 `DUPLICATE_RESOURCE`다.
6. And 60초 내 재발송, 1시간 5회·하루 10회 초과는 429 `TOO_MANY_REQUESTS`다.
7. And 재발송에 성공하면 같은 회원·같은 새 이메일의 이전 인증번호는 즉시 무효화되어 유효한 인증번호는 최대 1개다.
8. And 발송 성공·실패와 무관하게 기존 계좌·잔액·주문·체결·투자일기는 변하지 않는다.

---

## 범위와 제외

### 포함

- `POST /api/auth/email-changes` — Bearer 인증 필수, 재인증 증명 검증, 새 이메일 중복 검사, 발송 제한 판정, 인증번호 생성·HMAC 저장·발송.
- `email_change_verifications` Flyway 마이그레이션, `EmailChangeVerification` 엔티티, `EmailChangeVerificationRepository`.
- `ReauthTokenRepository.consumeIfValidForUser(tokenHash, userId, now)` 추가(Issue #54와 조율 완료된 공유 설계).
- `docs/api-routes.md` 동기화.

### 제외

- 인증번호 확인 엔드포인트, 실제 `users.email` 변경, 이메일 변경 완료 시 기존 Refresh Token 일괄 폐기(후속 이슈).
- 닉네임 변경 API(Issue #54, 병행 진행 — 별도 스펙).
- 기존 이메일 회원과 OAuth 계정 자동 연결·병합, 비밀번호 변경·재설정, 관리자·1차 이후 고도화.

---

## 설계 결정

### D1. `email_change_verifications`를 신규 테이블로 분리한다 (기존 `email_verifications` 재사용 안 함)

- `email_verifications`는 **미가입** 이메일(회원 없음)을 대상으로 하고 성공 시 `signupVerificationToken`을 발급하는 가입 전 인증 전용 테이블이다. 이번 이슈는 **이미 로그인된 회원**의 새 이메일을 대상으로 하고, PRD는 확인 성공 시 별도 토큰을 발급하지 않고 바로 `users.email`을 원자적으로 변경한다고 명시한다 — 목적·주체·성공 후 동작이 모두 달라 같은 테이블에 nullable 컬럼을 더 쌓기보다 분리가 더 읽기 쉽다.
- `user_id` FK(회원 삭제 시 참조 무결성)와 `new_email`이 필요해 `email_verifications`의 `email` 단일 컬럼 구조와 맞지 않는다.

### D2. 컬럼 구성 — `attempt_count`는 이번 이슈에서 쓰지 않지만 스키마에 포함한다

```sql
CREATE TABLE email_change_verifications (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    user_id       BIGINT       NOT NULL,
    new_email     VARCHAR(255) NOT NULL,
    code_hash     VARCHAR(255) NOT NULL,
    attempt_count INT          NOT NULL DEFAULT 0,
    expires_at    DATETIME(6)  NOT NULL,
    last_sent_at  DATETIME(6)  NOT NULL,
    consumed_at   DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_email_change_verifications_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_email_change_verifications_user_created_at (user_id, created_at)
);
```

- `attempt_count`는 후속 확인 이슈(5회 시도 제한)를 위해 미리 컬럼만 만든다 — Issue #53의 `reauth_tokens.consumed_at`(소비 로직은 후속이지만 컬럼은 선반영)과 같은 선례를 따른다. 이번 이슈의 `EmailChangeVerification` 엔티티에는 매핑 필드만 두고 증가 메서드는 추가하지 않는다(사용처 없음).
- `verified_at`·`token_hash`·`token_expires_at`은 넣지 않는다 — `email_verifications`와 달리 확인 성공 시 별도 토큰을 발급하지 않고 바로 이메일을 바꾸는 구조라서 불필요하다(D1).
- `consumed_at`은 이번 이슈에서 쓰지 않고(항상 NULL로 생성) 후속 확인 이슈가 "이미 소비된 인증번호" 판정에 쓴다.
- `V6__create_email_change_verifications_table.sql`, 기존 V1~V5는 수정하지 않는다.

### D3. 재인증 증명 판별과 소비

- EMAIL 회원 여부는 `getMe`와 동일한 패턴으로 `socialAccountRepository.findByUserId(userId)`가 비어 있으면 EMAIL, 있으면 OAuth 전용으로 판별한다.
- EMAIL 회원: `currentPassword`를 `passwordEncoder.matches(currentPassword, user.getPasswordHash())`로 검증한다. 비밀번호는 소비 개념이 없어 별도 저장·무효화가 없다.
- OAuth 회원: `reauthToken`을 SHA-256 해시해 `ReauthTokenRepository.consumeIfValidForUser(tokenHash, userId, now)`로 원자적 1회 소비를 시도한다. 영향받은 행이 1이 아니면(미존재·타인 소유·만료·이미 소비) 403 `REAUTHENTICATION_FAILED`다.
- 두 경우 모두 실패 사유를 구분하지 않고 403 `REAUTHENTICATION_FAILED`로 통일한다 — Issue #54 planner와 SendMessage로 조율한 결과와 동일한 결정이다(이메일 회원 비밀번호 불일치도 401 `UNAUTHORIZED`가 아니라 403 `REAUTHENTICATION_FAILED`로 통일 — 이미 Access Bearer로 신원이 확정된 상태이므로 로그인 실패와 위협 모델이 다르다는 논리).
- `reauthToken`은 **이번 발송 단계에서 소비된다**(후속 확인 단계로 넘기지 않음). PRD가 확인 단계에서는 재인증 증명을 다시 요구하지 않으므로, 재인증이 필요한 유일한 시점인 발송 단계에서 소비하는 것이 토큰을 불필요하게 오래 살려두지 않는 더 안전한 선택이다.
- `ReauthTokenRepository`에 다음 메서드를 추가한다(현재 `save()` 외 커스텀 메서드 없음) — **Issue #54와 SendMessage로 조율해 확정한 이름·시그니처**이며, 별도 `findByTokenHash` 조회 메서드는 추가하지 않는다(조회 후 저장은 TOCTOU 레이스가 생겨 기각, 기존 `RefreshTokenRepository.revokeIfActiveAndNotExpired(id, now)`와 동일한 "조건부 원자적 UPDATE, 영향받은 행 수로 성공 판정" 패턴을 그대로 재사용):

```java
@Modifying
@Query("""
    UPDATE ReauthToken r SET r.consumedAt = :now
    WHERE r.tokenHash = :tokenHash AND r.user.id = :userId
        AND r.consumedAt IS NULL AND r.expiresAt > :now
    """)
int consumeIfValidForUser(String tokenHash, Long userId, LocalDateTime now);
```

  `user.id` 조건을 같은 WHERE 절에 넣어 다른 회원에게 발급된 토큰의 재사용을 리포지터리 레벨에서 차단한다. 파라미터 바인딩은 프로젝트의 `-parameters` 컴파일 옵션 여부를 implementer가 확인해 필요하면 기존 `EmailVerificationRepository.consumeValidToken`처럼 `@Param`을 붙인다(#54의 메시지에는 `@Param` 없이 적혀 있었으나 이는 설계 설명이지 최종 코드가 아닐 수 있어, 실제 컴파일 통과 여부로 최종 판단한다). `ReauthToken` 엔티티에는 `consume(now)` 같은 인스턴스 메서드를 추가하지 않는다 — `RefreshToken`도 그런 메서드 없이 리포지터리 벌크 UPDATE만 쓰는 기존 선례를 따른다.

### D4. `EmailChangeService.requestEmailChange` — 검증 순서와 트랜잭션

```java
@Transactional
public void requestEmailChange(Long userId, String newEmail, String currentPassword, String reauthToken) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

    verifyReauthProof(user, currentPassword, reauthToken); // 403

    if (userRepository.existsByEmail(newEmail)) {
        throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE); // 409
    }

    LocalDateTime now = LocalDateTime.now(clock);
    checkSendRateLimit(userId, now); // 429
    expirePreviousCodes(userId, newEmail, now);

    String code = generateCode();
    EmailChangeVerification verification = EmailChangeVerification.create(
        user, newEmail, hmac(code), now.plusMinutes(CODE_TTL_MINUTES), now);
    emailChangeVerificationRepository.save(verification);

    emailSender.sendVerificationCode(newEmail, code); // 저장 이후 발송 — 실패 시 트랜잭션 롤백으로 저장·소비도 되돌아감
}
```

- 검증 순서는 "재인증 증명 → 중복 이메일 → 발송 제한"이다. 본인 확인(403)을 이메일 중복 여부(409)보다 먼저 판정해 재인증에 실패한 요청자에게 "그 이메일이 이미 쓰이는지" 정보가 새지 않게 한다.
- `userRepository.existsByEmail(newEmail)`은 새 이메일이 본인의 현재 이메일과 같은 경우도 true이므로 "변경할 필요 없음" 케이스를 별도 분기 없이 409로 자연스럽게 처리한다.
- `checkSendRateLimit`·`expirePreviousCodes`·`generateCode`·`hmac`는 `EmailVerificationService`와 동일한 구현(60초/1시간5회/하루10회, HMAC-SHA-256)을 새 리포지터리·엔티티에 맞춰 그대로 옮긴다. 이번 이슈에서 공통 유틸로 추출하지 않는다 — 중복이 아직 2곳뿐이고(가입 인증·이메일 변경 인증) 대상 리포지터리·엔티티 타입이 달라 추출 이득보다 결합 비용이 커 보인다(conventions.md "세 번째 중복이 보일 때만 공통화").
- `EmailChangeVerification.create`가 `EMAIL_VERIFICATION_SECRET`과 동일한 HMAC 시크릿을 재사용할지 별도 시크릿을 쓸지는 "미확정" 절 참고.

### D5. 발송 제한 집계 범위 — `userId` 전체 vs `(userId, newEmail)` 쌍

- **재발송 60초 간격·1시간 5회·하루 10회 제한은 `userId` 기준으로 전체 새 이메일 시도를 합산한다.** 이 엔드포인트는 인증 사용자가 임의의 제3자 이메일로 실제 메일을 보내게 만들 수 있어(대상 이메일 소유자가 회원일 필요 없음), 대상 이메일을 바꿔가며 요청하면 시간당 발송 총량 제한을 우회할 수 있는 어뷰징 경로가 생긴다. `email_verifications`(가입 전, 회원이 없어 이메일 단위 집계가 유일한 선택지)와 달리 이번 이슈는 회원이 이미 식별돼 있으므로 회원 단위 집계가 자연스럽고 어뷰징에도 더 안전하다.
- **"재발송 시 이전 인증번호 무효화"는 `(userId, newEmail)` 쌍 단위로 좁힌다.** PRD 수용 기준이 "동일 회원의 유효한 변경 인증번호는 새 이메일별 최대 하나"라고 명시해, 회원이 서로 다른 새 이메일을 시도하면 각각 별도의 유효한 코드를 가질 수 있다는 뜻으로 읽었다.
- 이 구분(제한은 회원 전체, 무효화는 회원+대상 이메일)은 PRD에 명시돼 있지 않은 해석이다 — "미확정" 절 참고.

---

## HTTP 계약

| 구분 | Method / Path | 인증 | 입력 | 성공 응답 | 오류 응답 |
|---|---|---|---|---|---|
| 이메일 변경 인증번호 발송 | POST `/api/auth/email-changes` | `Authorization: Bearer` 필수 | `{"newEmail":"new@finplay.com","currentPassword":"...","reauthToken":"..."}` (`EmailChangeRequest`, 회원 유형에 따라 둘 중 하나만 사용) | 202 (본문 없음) | 인증 헤더 없음·만료·변조 401 `UNAUTHORIZED`; `newEmail` 형식·길이 위반 400 `VALIDATION_ERROR`; 잘못된 현재 비밀번호·만료/재사용/타인 소유 `reauthToken` 403 `REAUTHENTICATION_FAILED`; 다른 회원이 사용 중인 새 이메일 409 `DUPLICATE_RESOURCE`; 60초 재발송·1시간 5회·하루 10회 초과 429 `TOO_MANY_REQUESTS` 공통 오류 형식 |

- 202를 쓰는 이유: 기존 `POST /api/auth/email-verifications`(가입 전 인증번호 발송)와 동일하게 "요청은 수락됐고 실제 메일 발송은 비동기·외부 인프라 의존"이라는 의미를 통일한다. 이 엔드포인트도 즉시 어떤 리소스를 생성·반환하지 않고 부수효과(발송)만 트리거하므로 200보다 202가 의미에 맞는다.

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/auth/domain/EmailChangeVerification.java`
- `src/main/java/com/finplay/api/auth/repository/EmailChangeVerificationRepository.java`
- `src/main/java/com/finplay/api/auth/service/EmailChangeService.java`
- `src/main/java/com/finplay/api/auth/controller/EmailChangeController.java`
- `src/main/java/com/finplay/api/auth/dto/request/EmailChangeRequest.java`
- `src/main/resources/db/migration/V6__create_email_change_verifications_table.sql`

### Production files to modify

- `src/main/java/com/finplay/api/auth/repository/ReauthTokenRepository.java` — `consumeIfValidForUser(tokenHash, userId, now)` 추가(Issue #54와 조율 완료된 공유 설계, D3)

### 수정하지 않을 파일

- `src/main/resources/db/migration/V1__init.sql` ~ `V5__create_reauth_tokens_table.sql`
- `AuthService`(`reauthenticate`·`login`·`getMe` 등 기존 로직), `EmailVerificationService`, `EmailVerification`, `EmailVerificationRepository`
- `ReauthToken`(엔티티에 인스턴스 메서드 추가 없음, D3)
- `SecurityConfig`(`/api/auth/email-changes`는 공개 목록에 없어 기본 `anyRequest().authenticated()`로 자동 보호됨 — 변경 불필요)

### Test files

- `EmailChangeVerificationRepositoryTest`(`@DataJpaTest`) — 저장, `fk_email_change_verifications_user`, `user_id`+`created_at` 기간 집계 쿼리 검증
- `ReauthTokenRepositoryTest` — `consumeIfValidForUser` 성공/이미 소비/만료/타인 `userId` 거부(원자적 1회 소비 포함) 추가 케이스(리베이스 시점에 Issue #54가 이미 같은 메서드를 추가·테스트했다면 중복 추가하지 않고 재사용)
- `EmailChangeServiceTest`(Mockito) — EMAIL 회원 비밀번호 성공/실패, OAuth 회원 `reauthToken` 성공/실패(미존재·만료·타인 소유·이미 소비), 중복 이메일 409, 60초·1시간·하루 제한 429, 재발송 시 이전 코드 무효화, 실패 시 저장·발송 미실행 검증
- `EmailChangeControllerTest`(`@WebMvcTest`) — 202 성공, `newEmail` 검증 실패 400, 인증 없음 401, Service 예외별 403/409/429 HTTP 매핑
- `EmailChangeIntegrationTest`(Testcontainers MySQL, Fake `EmailSender`) — EMAIL/OAuth 각 성공 흐름 전체, 실패 시나리오 전후 `users`·`accounts`·`orders`·`executions` 등 불변, `email_change_verifications`에 해시만 저장되고 원문 미저장 확인

### Documentation files to modify after implementation

- `docs/api-routes.md`
- `docs/specs/002-auth-account/tasks.md`

---

## Task 1: `email_change_verifications` 스키마와 Repository, `ReauthTokenRepository` 소비 메서드

- [ ] `V6__create_email_change_verifications_table.sql`, `EmailChangeVerification` 엔티티(정적 팩토리 `create`, `expire(now)`만 — `attempt_count` 증가 메서드는 이번 이슈에서 추가하지 않음), `EmailChangeVerificationRepository`(발송 제한 집계·재발송 무효화 대상 조회 쿼리 메서드)를 실패 테스트로 먼저 고정한다.
- [ ] `ReauthTokenRepository`에 `consumeIfValidForUser(tokenHash, userId, now)`을 추가하고 `@DataJpaTest`로 원자적 소비·타인 `userId` 거부·만료 거부·이미 소비된 토큰 거부를 검증한다(구현 착수 전 `git fetch origin`으로 #54가 이미 dev에 이 메서드를 추가했는지 먼저 확인 — 있으면 재구현하지 않고 재사용).
- [ ] `@DataJpaTest`로 `email_change_verifications` 저장, FK, 기간별 집계 쿼리를 검증한다.

## Task 2: `EmailChangeService.requestEmailChange` 재인증·중복·발송 제한 판정

- [ ] EMAIL 회원 비밀번호 검증, OAuth 회원 `reauthToken` 소비 검증(둘 다 실패 시 403 `REAUTHENTICATION_FAILED`로 통일)을 실패 테스트로 먼저 고정한다.
- [ ] 중복 이메일 409, 60초/1시간 5회/하루 10회 제한 429, 재발송 시 같은 회원+같은 새 이메일의 이전 코드 무효화를 구현하고 단위 테스트로 검증한다.
- [ ] 각 실패 경로(403/409/429)에서 `EmailChangeVerificationRepository.save`와 `EmailSender.sendVerificationCode`가 호출되지 않음을 Mockito로 검증한다.

## Task 3: `EmailChangeController`와 HTTP 계약

- [ ] `EmailChangeRequest`(`newEmail` 필수 검증, `currentPassword`/`reauthToken` 선택)와 `EmailChangeController`(`POST /api/auth/email-changes`, 202)를 구현한다.
- [ ] `@WebMvcTest`로 202 성공, `newEmail` 검증 실패 400, 인증 헤더 없음 401, 서비스 예외의 403/409/429 HTTP 상태 매핑을 검증한다.

## Task 4: 통합 테스트·전체 회귀·문서 동기화

- [ ] Fake `EmailSender` + Testcontainers MySQL로 EMAIL/OAuth 각 성공 흐름과 실패 시나리오 전후 `users`·`accounts`·`orders`·`executions` 등 기존 데이터 불변, `email_change_verifications`에 해시만 저장됨을 검증한다.
- [ ] 대상 단위·슬라이스·통합 테스트 전체와 기존 회귀 스위트, Spotless, `./gradlew build`를 실행한다.
- [ ] `docs/api-routes.md`에 `POST /api/auth/email-changes` 라우트를 추가하고 `docs/specs/002-auth-account/tasks.md`에 Issue #55 작업 항목 절을 추가한다.

---

## 완료 체크리스트

- [ ] EMAIL 회원 현재 비밀번호 성공/실패, OAuth 회원 `reauthToken` 성공/실패(미존재·만료·타인 소유·이미 소비)가 모두 403 `REAUTHENTICATION_FAILED`로 자동 테스트를 통과한다.
- [ ] 다른 회원이 사용 중인 새 이메일이 409 `DUPLICATE_RESOURCE`로 거부된다.
- [ ] 60초 재발송·1시간 5회·하루 10회 제한이 429 `TOO_MANY_REQUESTS`로 검증된다.
- [ ] 재발송 시 같은 회원·같은 새 이메일의 이전 인증번호가 즉시 무효화되어 유효한 코드가 최대 1개임이 검증된다.
- [ ] 발송 성공·실패와 무관하게 기존 `users.email`·계좌·잔액·주문·체결·투자일기가 불변임이 MySQL 통합 테스트로 확인된다.
- [ ] `email_change_verifications`가 인증번호 원문을 저장하지 않고 HMAC 해시만 저장한다.
- [ ] `docs/api-routes.md`가 실제 Controller 매핑과 일치한다.
- [ ] `./gradlew build`(Spotless·SpotBugs·JaCoCo 포함)가 통과한다.

---

## 미확정·PRD 불일치 (임의로 정하고 보고하는 항목)

이슈 본문·PRD가 세부를 명시하지 않아 이번 계획에서 아래처럼 결정했다. 팀 리뷰나 Issue #54 구현 결과에 따라 다르게 정하면 이 문서를 갱신한다.

1. **성공 상태코드 202**: 기존 `POST /api/auth/email-verifications`(가입 전 인증번호 발송)와 동일한 "수락됨, 실제 발송은 부수효과" 의미로 통일했다. PRD가 이 엔드포인트의 상태코드를 명시하지는 않는다.
2. **새 이메일 자체 형식 검증**: `@Email` + `@Size(max = 255)`만 적용하고, 본인의 현재 이메일과 동일한 경우는 별도 분기 없이 `existsByEmail`이 true가 되어 409로 처리되도록 뒀다. "변경할 필요 없음"을 별도 오류로 구분해야 한다는 요구는 PRD에 없다.
3. **EMAIL 회원 판별 방법**: `socialAccountRepository.findByUserId(userId)` 존재 여부로 판별한다(`getMe`와 동일 패턴). 이슈 본문이 판별 로직을 지정하지 않아 기존 코드와의 일관성을 근거로 선택했다.
4. **`ReauthTokenRepository` 조회/소비 메서드 시그니처 (해결됨)**: SendMessage로 #54 planner에게 문의해 `consumeIfValidForUser(tokenHash, userId, now)` 원자적 UPDATE(별도 `findByTokenHash` 조회 메서드 없음)로 확정했다(D3). #54가 먼저 dev에 병합될 예정이라 이 메서드가 이미 존재할 가능성이 높으며, 그 경우 재구현 없이 재사용한다. `@Param` 애노테이션 부착 여부만 실제 컴파일 결과로 최종 확인이 필요하다(#54의 설명 메시지에는 생략돼 있었음).
5. **신규 vs 기존 테이블**: `email_verifications`를 재사용하지 않고 `email_change_verifications`를 신규로 만들었다(D1) — 대상이 익명 이메일이 아니라 인증된 회원이고, 확인 성공 시 별도 토큰을 발급하지 않고 바로 `users.email`을 변경하는 다른 라이프사이클이라는 점을 근거로 들었다. 이슈 본문은 "목적별로 분리 검토"만 요청했고 최종 스키마는 PRD에 없다.
6. **신규 `ErrorCode` 필요 여부**: 없음. `VALIDATION_ERROR`(400)·`UNAUTHORIZED`(401)·`REAUTHENTICATION_FAILED`(403)·`DUPLICATE_RESOURCE`(409)·`TOO_MANY_REQUESTS`(429)를 모두 재사용한다.
7. **재인증 실패와 발송 제한 실패의 오류 코드·순서 구분**: 재인증 증명 실패는 403(D3), 발송 제한 초과는 429(기존 `EmailVerificationService`와 동일 코드)로 구분했다. 검증 순서는 "재인증 → 중복 이메일 → 발송 제한"으로 정해 재인증 실패자에게 이메일 중복 여부가 노출되지 않게 했다(D4) — 이 순서는 PRD에 명시돼 있지 않다.
8. **발송 제한 집계 범위**: 60초/1시간/하루 제한은 `userId` 전체(대상 이메일 무관)로 합산하고, "이전 코드 무효화"는 `(userId, newEmail)` 쌍 단위로 좁혔다(D5) — 어뷰징 방지와 PRD의 "새 이메일별 최대 하나" 문구를 각각 근거로 들었지만 PRD가 명시적으로 구분하지는 않는다.
9. **`attempt_count` 컬럼 선반영**: 이번 이슈는 사용하지 않지만 후속 확인 이슈를 위해 스키마에 미리 포함했다(D2, Issue #53 `reauth_tokens.consumed_at` 선례). "당장 안 쓰는 컬럼을 미리 만들지 않는다"는 단순성 원칙과 다소 긴장 관계이므로, 팀이 원하면 이번 이슈에서 컬럼을 빼고 후속 이슈에서 `ALTER TABLE`로 추가하는 것으로 바꿀 수 있다.
10. **`currentPassword`/`reauthToken`에 `@NotBlank` 미적용**: 회원 유형에 따라 둘 중 하나만 필수라 Bean Validation으로 구조적 강제를 하지 않고 서비스 계층 판단(D3)에 맡겼다. 둘 다 비어 있으면 403 `REAUTHENTICATION_FAILED`로 처리되며 400으로 별도 구분하지 않는다.
11. **`EmailChangeService`를 `AuthService`에 통합하지 않고 분리**: `EmailVerificationService`가 `AuthService`와 분리된 기존 선례를 따랐다. Issue #54가 같은 재인증 판별 로직을 별도 서비스에 두는지 `AuthService`에 두는지에 따라 공통화 여지가 있을 수 있다.
