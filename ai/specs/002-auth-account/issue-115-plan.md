# Issue #115 비밀번호 재설정 인증번호 발송 API 구현 계획

> **For agentic workers:** 각 작업은 실패 테스트를 먼저 작성한다. Fake 발송기 자동 검증과 실제 발송 스모크를 서로 대체하거나 합쳐 보고하지 않는다. 이 이슈는 **발송까지만** 구현한다 — 인증번호 확인과 `users.password_hash` 교체는 후속 이슈 #116이며, 이번 범위에서 `users`·`accounts`·Refresh Token은 한 행도 바뀌지 않는다.

**Goal:** `POST /api/auth/password-resets`를 비인증 공개 경로로 신설해, 비밀번호를 잊은 이메일 회원의 가입 이메일로 6자리 인증번호를 발송한다. 인증번호는 원문을 저장하지 않고 전용 시크릿 기반 HMAC-SHA-256 해시만 저장하며, 5분 만료·60초 재발송 간격·1시간 5회·하루 10회 제한과 재발송 시 이전 코드 즉시 무효화를 적용한다. 확인·실제 비밀번호 교체·Refresh Token 폐기는 후속 이슈로 미룬다.

**관련 정본:** GitHub Issue #115, PRD `AUTH-004`(가입 인증번호 흐름 원형, `docs/prd.md` 246-259행), PRD `AUTH-002`(소셜 전용 회원의 비밀번호 부재 판별, 224행), PRD §5 공통 오류표(570-593행), `docs/specs/002-auth-account/spec.md`, `docs/specs/002-auth-account/plan.md`, Issue #55 계획(`issue-55-plan.md`, 발송 제한·이전 코드 무효화 구조의 직전 선례), Issue #56 계획(`issue-56-plan.md`, 확인 단계 구조 — 후속 #116이 참고), ADR-0002, ADR-0003, ADR-0004, `docs/conventions.md`

**PRD 상태:** PRD에는 비밀번호 재설정 요구사항이 **아직 없다.** 이 이슈에서 `AUTH-006`을 신설한다 (문구 초안은 "미확정·PRD 불일치" 절 U1).

**선행:** Issue #2·#3·#4·#5·#55가 `dev`에 있다 — `User`(`password_hash` NULL 허용, V2), `EmailVerification`·`EmailVerificationService`(6자리 생성·HMAC 저장·3종 발송 제한·이전 코드 무효화 원형), `EmailSender`/`FakeEmailSender`/`ResendEmailSender`, `SecurityConfig.PUBLIC_POST_PATHS` 공개 경로 패턴, `email_change_verifications`(V6).

**브랜치 주의:** Issue #114(로그인 상태 비밀번호 변경)는 아직 `dev`에 병합되지 않았다. #114가 추가할 `User.changePassword`는 이 계획의 어떤 작업에도 필요하지 않다 — 이번 범위는 발송뿐이라 `User` 엔티티를 전혀 건드리지 않는다. #114와 파일 충돌이 생길 지점은 없다(#114는 `AuthService`·`User`, 이번 이슈는 신규 `PasswordResetService`·신규 엔티티). 실제 비밀번호 교체를 하는 후속 #116은 #114의 `changePassword` 재사용 여부를 그 시점에 판단한다.

**Flyway 번호 주의:** `dev` 기준 최신 마이그레이션은 `V11__create_market_data_imports.sql`이므로 이번 이슈는 **V12**다. 구현 착수 시 `git fetch origin && git ls-tree -r origin/dev --name-only -- src/main/resources/db/migration`으로 다른 브랜치가 V12를 선점하지 않았는지 먼저 확인한다 (ADR-0004 — 머지된 마이그레이션 수정 금지).

---

## Architecture

- `PasswordResetController → PasswordResetService → PasswordResetVerificationRepository`로 레이어를 나눈다. 대상 회원 조회를 위해 `UserRepository`를, 발송을 위해 `EmailSender`를 조회·호출 전용으로 참조한다.
- `EmailVerificationService`(가입 전 인증)·`EmailChangeService`(회원의 새 이메일 인증)와 별도로 `PasswordResetService`를 신설한다 — 같은 auth 도메인 안에서도 대상·생명주기가 다르면 서비스를 분리한다는 기존 선례(`AuthService` vs `EmailVerificationService` vs `EmailChangeService`)를 그대로 따른다.
- `AuthService`·`EmailVerificationService`·`EmailChangeService`는 수정하지 않는다.
- `SecurityConfig.PUBLIC_POST_PATHS`에 `/api/auth/password-resets`를 추가한다 — 이 계획에서 유일하게 기존 파일을 고치는 프로덕션 코드다.

## Tech Stack

Java 17, Spring Boot 4.1, Spring Data JPA, MySQL 8.4(Testcontainers), Spring Security(공개 경로), JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

---

## 요구사항 ID와 수용 기준

### PRD `AUTH-006` (이 이슈에서 신설 — U1의 초안 문구 기준, 발송 부분만)

- 비밀번호를 잊은 이메일 회원은 가입 이메일로 6자리 인증번호를 받아 비밀번호를 재설정한다. 요청 경로는 비인증 공개 경로다.
- 인증번호는 5분 유효, 최대 5회 시도, 60초 재발송 간격, 같은 이메일에 1시간 5회·하루 10회 제한이다. 초과는 429 `TOO_MANY_REQUESTS`다.
- 재발송하면 이전 인증번호는 즉시 무효화된다 — 유효한 인증번호는 항상 최대 1개다.
- 인증번호 원문은 저장하지 않고 **전용 시크릿** 기반 HMAC-SHA-256 해시만 저장한다.
- 가입되지 않은 이메일은 404, 비밀번호가 없는 OAuth 전용 회원은 409로 거부하고 발송하지 않는다 (D2 — 계정 열거 노출을 감수한 결정).
- 발송 단계에서는 `users`·`accounts`·Refresh Token을 만들거나 바꾸지 않는다.

### Issue #115 수용 시나리오

1. Given 비밀번호로 가입한(= `users.password_hash`가 있는) 회원의 이메일
2. When `POST /api/auth/password-resets` 호출
3. Then 202로 요청이 수락되고 해당 이메일로 6자리 인증번호가 발송되며, `password_reset_verifications`에는 HMAC 해시만 남고 원문은 DB에도 로그에도 남지 않는다.
4. And 가입되지 않은 이메일은 404 `NOT_FOUND`이며 메일이 발송되지 않는다.
5. And 비밀번호가 없는 OAuth 전용 회원의 이메일은 409 `SOCIAL_ACCOUNT_ONLY`(D3)이며 메일이 발송되지 않는다.
6. And 60초 내 재요청, 같은 이메일 1시간 5회·하루 10회 초과는 429 `TOO_MANY_REQUESTS`이며, 거부된 404·409 요청도 이 집계에 포함된다.
7. And 재발송에 성공하면 같은 이메일의 이전 인증번호는 즉시 무효화되어 유효한 인증번호는 최대 1개다.
8. And 메일 발송이 실패하면 저장과 이전 코드 무효화가 함께 롤백된다.
9. And 성공·실패와 무관하게 `users`·`accounts`·`refresh_tokens`·주문·체결·투자일기는 전혀 변하지 않는다.
10. And 인증 헤더 없이 호출해도 401이 아니라 정상 처리된다(공개 경로).

---

## 범위와 제외

### 포함

- `POST /api/auth/password-resets` — 이메일 형식 검증, 발송 제한 판정, 대상 회원·가입 방식 판정, 인증번호 생성·HMAC 저장·발송.
- `V12__create_password_reset_verifications_table.sql`, `PasswordResetVerification` 엔티티, `PasswordResetVerificationRepository`.
- `SecurityConfig.PUBLIC_POST_PATHS`에 경로 추가.
- OAuth 전용 회원 거부용 신규 `ErrorCode` `SOCIAL_ACCOUNT_ONLY`(409) 추가 (D3).
- `PASSWORD_RESET_SECRET` 신규 환경변수 배선(`.env.example`·`build.gradle` 테스트 환경·`deploy/README.md`).
- PRD `AUTH-006` 신설과 §5 오류표·엔드포인트 목록 갱신.

### 제외

- 인증번호 확인과 실제 `users.password_hash` 교체, 재설정 완료 시 Refresh Token 일괄 폐기 (#116).
- 로그인 상태의 비밀번호 변경 (#114).
- 링크(토큰) 클릭 방식 재설정 메일 — 이번에는 6자리 인증번호로 통일한다.
- SMS·본인확인 등 이메일 외 채널, 계정 잠금·의심 로그인 알림.
- IP·디바이스 단위 발송 제한 (D4의 한계 참고 — 이번 범위 밖).

---

## 설계 결정

### D1. `password_reset_verifications`를 신규 테이블로 만든다 (기존 두 인증 테이블 재사용 안 함)

- `email_verifications`는 **미가입** 이메일 전용이고 `EmailVerificationService.sendVerificationCode`가 이미 가입된 이메일을 409 `DUPLICATE_RESOURCE`로 거부한다. 재설정은 정확히 그 반대 조건(가입된 이메일만 허용)이라 같은 테이블·같은 서비스에 조건을 분기해 얹으면 두 흐름의 규칙이 서로를 가린다.
- `email_change_verifications`는 `user_id` FK가 NOT NULL이라 미가입 이메일 요청 행을 담을 수 없다 (D4에서 미가입 요청도 집계 대상이다).
- 확인 성공 후 동작도 다르다 — 가입 인증은 `signupVerificationToken`을 발급하고, 이메일 변경은 `users.email`을 바꾸며, 비밀번호 재설정은 `users.password_hash`를 바꾼다.

### D2. 계정 열거를 감수하고 응답을 구분한다 — 미가입 404, OAuth 전용 409, 정상 202

**착수 전 팀에서 확정한 결정이다. "미확정"이 아니라 확정된 설계와 그 대가로 기록한다.**

- 이슈 본문의 권장안(모든 경우 202 통일)을 채택하지 않았다.
- 근거 1 — 같은 정보가 이미 노출돼 있다. `POST /api/auth/email-verifications`가 이미 가입된 이메일에 409 `DUPLICATE_RESOURCE`를 반환하므로(PRD AUTH-004), 누구나 그 엔드포인트로 "이 이메일이 가입돼 있는가"를 알 수 있다. 재설정만 202로 통일해도 열거 자체는 막히지 않고, 사용자 경험만 나빠진다(오타 입력 시 "메일을 보냈다"고 답하고 실제로는 오지 않는다).
- 근거 2 — D4의 발송 제한이 **같은 이메일 주소에 대한** 반복 조회를 하루 10회로 묶는다.
- **수용한 대가(명시적으로 기록한다):** 누구나 이메일 주소만으로 (a) FinPlay 가입 여부와 (b) 그 계정이 비밀번호 계정인지 소셜 전용 계정인지를 알아낼 수 있다. 특히 (b)는 기존 엔드포인트로는 알 수 없던 **새로 노출되는 정보**다. 서로 다른 주소를 훑는 광범위 스캔은 이메일 단위 제한으로 막히지 않는다(D4 한계 참고). 이 정보 노출이 문제가 되면 응답을 202로 통일하는 변경은 서비스 계층 한 곳(D5의 분기)만 고치면 되므로 되돌리기 비용은 낮다.

### D3. OAuth 전용 회원 거부용 `ErrorCode` — 신규 `SOCIAL_ACCOUNT_ONLY`(409) 추가 (2026-08-01 확정)

기존 409 코드 재사용 가능 여부를 먼저 검토했고, 셋 다 의미가 맞지 않는다.

| 후보 | 현재 의미 | 부적합 사유 |
|---|---|---|
| `DUPLICATE_RESOURCE` (409) | "이미 존재하는 리소스입니다." | 중복이 아니라 계정 **유형**이 맞지 않는 상황이다. |
| `ACCOUNT_LINK_REQUIRED` (409) | "같은 이메일의 일반 회원이 있어 계정 연결이 필요합니다." | 방향이 정반대다 — OAuth 가입이 기존 이메일 회원 때문에 막히는 경우이며, 이번 경우는 그 반대다. |
| `EMAIL_VERIFICATION_REQUIRED` (409) | "이메일 인증이 필요합니다." | 가입 토큰 관련 코드이며 이 상황과 무관하다. |
| `FORBIDDEN` (403) | "접근 권한이 없습니다." | 권한 문제가 아니라 계정 상태 문제이고, 이 엔드포인트는 비인증 공개 경로라 403의 의미와 어긋난다. |
| `NOT_FOUND` (404) | "대상을 찾을 수 없습니다." | 404로 합치면 D2가 구분하기로 한 두 경우가 다시 하나로 뭉개진다. |

**확정된 값:**

```java
SOCIAL_ACCOUNT_ONLY(HttpStatus.CONFLICT, "소셜 로그인 전용 계정입니다. 카카오 또는 네이버 로그인을 이용해 주세요."),
```

- 위치는 `ErrorCode` enum의 409 그룹, `ACCOUNT_LINK_REQUIRED` 바로 다음이다.
- 기각된 대안 — `PASSWORD_LOGIN_UNAVAILABLE`(사유를 감춰 프런트 안내가 무뎌진다), `OAUTH_ONLY_ACCOUNT`(기존 `OAUTH_` 접두 코드들은 전부 OAuth 흐름 중에 나는 오류라 일반 재설정 흐름에서 쓰면 묶음이 오해를 부른다). `SOCIAL_ACCOUNT_ONLY`는 코드베이스가 이미 `SignupMethod`·`SocialAccount`로 "소셜"을 쓰고 있어 용어가 일관되고, 향후 비밀번호 최초 설정 같은 다른 지점에서도 재사용할 수 있다.
- 메시지는 프런트가 그대로 노출할 수 있게 다음 행동(소셜 로그인)을 담았다 — 기존 `ACCOUNT_LINK_REQUIRED`가 안내 문구를 담는 선례를 따른다.
- 확정되면 PRD §5 공통 오류표에 같은 행을 추가한다.

**미가입 404는 기존 `NOT_FOUND` 코드를 쓰되 메시지를 덮어쓴다 (2026-08-01 확정).** 호출부에서 `new BusinessException(ErrorCode.NOT_FOUND, "가입되지 않은 이메일입니다.")`로 던진다. 별도 `USER_NOT_FOUND` 코드를 새로 만들면 PRD §5 표에 도메인별 404 코드가 늘어나기 시작하므로 추가하지 않는다. 메시지를 구체화한 근거는 D2에서 이미 404로 가입 여부를 알려주기로 한 이상 문구를 모호하게 둬도 더 안전해지지 않고, 프런트가 별도 문구를 갖지 않아도 되기 때문이다.

### D4. 발송 제한은 이메일 단위이며 거부된 요청도 함께 집계한다

**착수 전 팀에서 확정한 결정이다.**

- 60초 재발송 간격 · 1시간 5회 · 하루 10회를 **이메일 주소 단위**로 적용한다. #55는 인증된 회원이 임의의 제3자 이메일로 메일을 보낼 수 있어 `userId` 단위 합산이 필요했지만, 이번 엔드포인트는 비로그인이고 발송 대상이 곧 요청 키라 이메일 단위가 유일하게 자연스러운 선택이다. `email_verifications`(가입 전 인증)와 같은 기준이다.
- **미가입·OAuth 전용으로 거부된 요청도 행을 남겨 함께 집계한다.** 거부 응답이 D2에 따라 계정 상태를 드러내므로, 거부 요청을 세지 않으면 같은 주소를 무한히 두드려 상태를 확인할 수 있다.
- **한계(기록):** 이메일 단위 제한은 **같은 주소**의 반복 조회만 막는다. 서로 다른 주소 수천 개를 한 번씩 훑는 스캔은 이 제한으로 전혀 느려지지 않는다. IP·디바이스 단위 제한은 이번 범위 밖이며, D2의 정보 노출은 결국 이 한계와 함께 수용된 것이다.
- 발송 제한 판정을 **가장 먼저** 한다 (D5의 순서). 존재 여부 판정을 먼저 하면 제한을 초과한 요청자도 계정 상태를 알게 되어 위 제한이 무의미해진다. 이 순서는 `EmailVerificationService.sendVerificationCode`(중복 확인 → 발송 제한)와 의도적으로 반대이며, 그 서비스는 응답으로 드러나는 정보(가입 여부)가 이미 열려 있는 흐름이라 순서가 문제되지 않았다.

### D5. `PasswordResetService.sendResetCode` — 검증 순서와 트랜잭션 정책

```java
// 거부(404·409) 요청도 발송 제한 집계용 행으로 남겨야 하므로 BusinessException에 롤백하지 않는다.
@Transactional(noRollbackFor = BusinessException.class)
public void sendResetCode(String email) {
    LocalDateTime now = LocalDateTime.now(clock);
    checkSendRateLimit(email, now); // 429 — 행을 남기지 않는다

    User user = userRepository.findByEmail(email).orElse(null);
    if (user == null) {
        passwordResetVerificationRepository.save(PasswordResetVerification.createRejected(email, now));
        throw new BusinessException(ErrorCode.NOT_FOUND); // 404
    }
    if (user.getPasswordHash() == null) {
        passwordResetVerificationRepository.save(PasswordResetVerification.createRejected(email, now));
        throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_ONLY); // 409 (D3)
    }

    expirePreviousCodes(email, now);

    String code = generateCode();
    PasswordResetVerification verification = PasswordResetVerification.create(
        email, hmac(code), now.plusMinutes(CODE_TTL_MINUTES), now);
    passwordResetVerificationRepository.save(verification);

    // 발송은 저장 이후에 한다. 발송 실패(비 BusinessException)는 트랜잭션을 롤백해 저장·이전 코드 무효화를 함께 되돌린다.
    emailSender.sendVerificationCode(email, code);
}
```

- 검증 순서는 **"발송 제한 → 회원 존재 → 비밀번호 보유 → 이전 코드 무효화 → 저장 → 발송"**이다 (근거는 D4).
- `@Transactional(noRollbackFor = BusinessException.class)`를 쓴다. 404·409에서 기록한 집계 행이 커밋되어야 하기 때문이다. 같은 애노테이션을 `EmailVerificationService.confirmVerificationCode`가 이미 같은 이유(시도 횟수 커밋)로 쓰고 있어 새 패턴이 아니다. 발송 실패는 `BusinessException`이 아닌 예외(`ResendEmailSender`의 런타임 예외)이므로 롤백 규칙에 걸려 정상적으로 전체 롤백된다 — 수용 기준 8이 이 조합으로 만족된다.
- 429는 행을 남기지 않는다. 이미 제한에 걸린 요청까지 세면 하루 10회 제한이 사실상 영구 차단으로 변한다.
- OAuth 전용 판별은 `user.getPasswordHash() == null`이다. `AuthService.login`이 "소셜 전용 가입자"를 같은 방식으로 판별하는 기존 선례를 따르며, `SocialAccountRepository`를 주입하지 않아 의존이 하나 줄어든다. `EmailChangeService`가 `social_accounts` 존재 여부로 판별하는 것과 기준이 다른데, 그 서비스는 "재인증 수단이 무엇인가"를 묻고 이번 서비스는 "재설정할 비밀번호가 있는가"를 물어 질문 자체가 다르다.
- `checkSendRateLimit`·`expirePreviousCodes`·`generateCode`·`hmac`는 `EmailVerificationService`의 구현을 새 리포지터리·엔티티에 맞춰 옮긴다. 이번에도 공통 유틸로 추출하지 않는다 — #55에서 같은 판단을 했고(대상 타입이 달라 추출 이득보다 결합 비용이 크다), 이번이 세 번째 중복이라 `conventions.md`의 "세 번째 중복이 보이고 책임이 명확할 때만 검토" 기준에 막 도달했다. 다만 세 서비스의 상수(5분·60초·5회·10회)와 HMAC 계산이 이제 세 벌이므로 **공통화 검토 자체는 별도 리팩터링 이슈로 제안**한다(U5) — 이번 이슈에서 발송 API 구현과 3-서비스 리팩터링을 같이 하지 않는다.

### D6. 테이블 설계 — `user_id` FK 없음, 거부 요청 행은 코드 컬럼이 NULL

```sql
CREATE TABLE password_reset_verifications (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(255) NOT NULL,
    code_hash     VARCHAR(255) NULL,
    attempt_count INT          NOT NULL DEFAULT 0,
    expires_at    DATETIME(6)  NULL,
    last_sent_at  DATETIME(6)  NULL,
    consumed_at   DATETIME(6)  NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_password_reset_verifications_email_created_at (email, created_at)
);
```

- **`user_id` FK를 두지 않는다.** 미가입 이메일 요청도 행을 남기므로(D4) NOT NULL FK가 성립하지 않고, nullable FK는 "가끔 비어 있는 관계"라는 애매한 의미를 남긴다. `email_verifications`와 같은 이메일 키 구조를 쓰고, 후속 #116은 확인 시점에 `userRepository.findByEmail`로 대상 회원을 다시 조회한다(발송과 확인 사이에 이메일이 변경될 수 있으므로 오히려 재조회가 정확하다).
- **`code_hash`·`expires_at`·`last_sent_at`은 NULL 허용**이다. 세 컬럼이 모두 NULL인 행 = "요청은 있었으나 발송하지 않음"(404·409 거부)이고, 셋 다 값이 있는 행 = 실제 발송 행이다. 별도 상태 컬럼을 두지 않고 이 규약으로 두 종류를 구분한다 — 상태 enum을 추가하면 값 3종(`SENT`/`USER_NOT_FOUND`/`OAUTH_ONLY`)을 정의·매핑·테스트해야 하는데 이번 이슈에서 그 구분을 읽는 코드가 없다.
- `attempt_count`는 이번 이슈에서 쓰지 않고 후속 #116의 5회 시도 제한이 쓴다. 엔티티에 매핑 필드만 두고 증가 메서드는 추가하지 않는다 (#55 D2, #53 `reauth_tokens.consumed_at`과 같은 선례).
- `consumed_at`도 이번 이슈에서는 항상 NULL이며 #116이 쓴다.
- `verified_at`·`token_hash`는 넣지 않는다 — 확인 성공 시 별도 토큰을 발급하지 않고 바로 비밀번호를 바꾸는 구조라 불필요하다.
- 리포지터리 쿼리는 두 개다.

```java
// 발송 제한 집계 — 거부 요청 행까지 포함해 이메일 단위로 센다 (D4).
long countByEmailAndCreatedAtAfter(String email, LocalDateTime createdAt);

// 재발송 시 무효화 대상 — 실제로 발송된(code_hash가 있는) 유효·미소비 행만 고른다 (D6).
List<PasswordResetVerification> findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter(
    String email, LocalDateTime now);
```

### D7. HMAC 시크릿은 전용 `PASSWORD_RESET_SECRET`을 신설한다

**착수 전 팀에서 확정한 결정이다.**

- `EMAIL_VERIFICATION_SECRET`을 공유하지 않는다. 가입 인증번호와 재설정 인증번호는 탈취 시 영향 범위가 다르고(후자는 기존 계정 탈취로 직결된다), 이 저장소는 이미 `JWT_SECRET`·`EMAIL_VERIFICATION_SECRET`·`OAUTH_STATE_SECRET`을 용도별로 분리하는 컨벤션을 지키고 있다.
- 주입은 `EmailVerificationService`와 같은 방식으로 생성자 `@Value("${PASSWORD_RESET_SECRET}")`이다. **yml에 기본값을 두지 않는다** — 값이 없으면 기동이 실패해야 한다 (`conventions.md` 시크릿 규칙, 2026-07-24 튜터 피드백. 과거 `EMAIL_VERIFICATION_SECRET`에 dev 기본값을 뒀다가 리뷰에서 차단당한 사례가 run-log에 있다).
- 배선할 곳은 세 군데다.
  - `.env.example` — 이름과 한 줄 설명만 추가한다(`EMAIL_VERIFICATION_SECRET` 블록 바로 아래, "비밀번호 재설정 (PRD AUTH-006)" 소제목).
  - `build.gradle`의 `tasks.withType(Test).configureEach` 블록 — `environment 'PASSWORD_RESET_SECRET', 'test-password-reset-secret-that-is-at-least-32-bytes'` (63-65행의 기존 세 줄과 같은 자리).
  - `deploy/README.md` 24행의 시크릿 목록에 이름 추가.
- `application.yml`에는 추가하지 않는다 — `EMAIL_VERIFICATION_SECRET`도 yml 키 없이 `@Value`로 환경변수를 직접 읽는 기존 방식과 맞춘다(`OAUTH_STATE_SECRET`만 `oauth.state-secret` 키를 거친다).

---

## HTTP 계약

| 구분 | Method / Path | 인증 | 입력 | 성공 응답 | 오류 응답 |
|---|---|---|---|---|---|
| 비밀번호 재설정 인증번호 발송 | POST `/api/auth/password-resets` | **불필요 (공개 경로)** | `{"email":"member@finplay.com"}` (`PasswordResetRequest`) | 202 (본문 없음) | `email` 누락·형식·길이 위반 400 `VALIDATION_ERROR`; 60초 재발송·1시간 5회·하루 10회 초과 429 `TOO_MANY_REQUESTS`; 가입되지 않은 이메일 404 `NOT_FOUND`; 비밀번호가 없는 OAuth 전용 회원 409 `SOCIAL_ACCOUNT_ONLY`(D3) — 모두 공통 오류 형식 |

- 202를 쓰는 이유는 `POST /api/auth/email-verifications`·`POST /api/auth/email-changes`와 같다 — 리소스를 만들어 반환하지 않고 발송이라는 부수효과만 트리거하며 실제 메일 전달은 외부 인프라에 달려 있다.
- 오류 우선순위는 400(Bean Validation) → 429 → 404 → 409다 (D4·D5).
- `PasswordResetRequest`는 `@NotBlank`·`@Email`·`@Size(max = 255)`를 붙인 단일 컴포넌트 record다 (`conventions.md` DTO 규칙, `EmailVerificationRequest`와 동일한 모양).

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/auth/domain/PasswordResetVerification.java`
- `src/main/java/com/finplay/api/auth/repository/PasswordResetVerificationRepository.java`
- `src/main/java/com/finplay/api/auth/service/PasswordResetService.java`
- `src/main/java/com/finplay/api/auth/controller/PasswordResetController.java`
- `src/main/java/com/finplay/api/auth/dto/request/PasswordResetRequest.java`
- `src/main/resources/db/migration/V12__create_password_reset_verifications_table.sql`

### Production files to modify

- `src/main/java/com/finplay/api/common/ErrorCode.java` — 409 신규 코드 1개 추가 (D3, 이름 확정 후)
- `src/main/java/com/finplay/api/auth/config/SecurityConfig.java` — `PUBLIC_POST_PATHS`에 `/api/auth/password-resets` 추가
- `build.gradle` — 테스트 환경변수 `PASSWORD_RESET_SECRET` 추가 (D7)
- `.env.example`, `deploy/README.md` — 변수 이름만 추가 (D7)

### 수정하지 않을 파일

- `src/main/resources/db/migration/V1__init.sql` ~ `V11__create_market_data_imports.sql` (ADR-0004)
- `User`(이번 이슈는 엔티티를 건드리지 않는다 — #114의 `changePassword`와도 무관), `AuthService`, `EmailVerificationService`, `EmailChangeService`, `EmailVerification`, `EmailChangeVerification`
- `application.yml` 및 프로필 yml (D7 — 시크릿 키를 yml에 두지 않는다)

### Test files

- `PasswordResetVerificationRepositoryTest`(`@DataJpaTest`) — 발송 행·거부 행 저장, `code_hash` NULL 허용, `countByEmailAndCreatedAtAfter`가 거부 행까지 세는지, `findByEmailAndCodeHashIsNotNull...`이 거부 행을 제외하는지
- `PasswordResetServiceTest`(Mockito) — 정상 발송, 미가입 404, OAuth 전용 409, 60초·1시간·하루 429 세 종류, 거부 시 집계 행 저장·발송 미실행, 429 시 저장 미실행, 재발송 시 이전 코드 무효화, 발송 실패 시 예외 전파
- `PasswordResetControllerTest`(`@WebMvcTest`) — 202 성공, `email` 누락·형식 오류 400, 서비스 예외의 404/409/429 매핑, **인증 헤더 없이 호출해도 401이 아님**
- `PasswordResetIntegrationTest`(Testcontainers MySQL, Fake `EmailSender`) — 발송 성공 후 DB에 원문 미저장·해시만 존재, 거부 요청 행이 커밋되고 뒤이은 요청이 429가 되는지, 발송 실패 시 저장·무효화 동반 롤백, 전 과정에서 `users`·`accounts`·`refresh_tokens` 불변

### Documentation files to modify after implementation

- `docs/prd.md` — `AUTH-006` 신설(U1), §5 공통 오류표에 신규 409 코드 행, 엔드포인트 목록에 `POST /api/auth/password-resets`
- `docs/api-routes.md`, `docs/api-contracts.md` — planner 동기화 모드에서 함께 갱신
- `docs/specs/002-auth-account/tasks.md` — Issue #115 절 체크

---

## Task 1: 스키마·엔티티·리포지터리와 신규 `ErrorCode`·`PASSWORD_RESET_SECRET` 배선

- [ ] `V12__create_password_reset_verifications_table.sql`(D6)을 추가한다. 착수 전 `origin/dev`에 V12가 선점되지 않았는지 확인한다.
- [ ] `PasswordResetVerification` 엔티티를 만든다 — 정적 팩토리 `create(email, codeHash, expiresAt, now)`와 `createRejected(email, now)`, 상태 변경은 `expire(now)`만. `attempt_count` 증가·`consume`은 이번 이슈에서 추가하지 않는다.
- [ ] `PasswordResetVerificationRepository`에 D6의 두 쿼리 메서드를 추가한다.
- [ ] `ErrorCode`에 D3에서 확정된 409 코드를 추가한다 (**이름·메시지 확정 전에는 이 항목을 시작하지 않는다**).
- [ ] `PASSWORD_RESET_SECRET`을 `.env.example`·`build.gradle` 테스트 환경·`deploy/README.md`에 배선한다 (D7).
- [ ] `@DataJpaTest`로 발송 행·거부 행 저장, 거부 행 포함 집계, 거부 행 제외 무효화 대상 조회를 검증한다.

## Task 2: `PasswordResetService.sendResetCode` 판정 순서와 트랜잭션 정책

- [ ] 발송 제한(60초·1시간 5회·하루 10회) 429 → 미가입 404 → OAuth 전용 409 순서를 실패 테스트로 먼저 고정한다 (D4·D5).
- [ ] 404·409 경로에서 집계 행이 저장되고 `EmailSender`가 호출되지 않음을, 429 경로에서는 저장도 발송도 없음을 Mockito로 검증한다.
- [ ] 재발송 시 같은 이메일의 이전 유효 코드 무효화, 6자리 생성, `PASSWORD_RESET_SECRET` 기반 HMAC-SHA-256 저장, 저장 이후 발송 순서를 구현한다.
- [ ] `@Transactional(noRollbackFor = BusinessException.class)`가 의도대로 동작하는지(거부 행 커밋 / 발송 실패 롤백)를 단위 수준에서 확인하고, 실제 커밋·롤백 검증은 Task 4의 통합 테스트에 맡긴다.

## Task 3: `PasswordResetController`·요청 DTO와 공개 경로

- [ ] `PasswordResetRequest`(`@NotBlank`·`@Email`·`@Size(max = 255)`)와 `PasswordResetController`(`POST /api/auth/password-resets`, 202 본문 없음)를 구현한다.
- [ ] `SecurityConfig.PUBLIC_POST_PATHS`에 경로를 추가한다.
- [ ] `@WebMvcTest`로 202 성공, `email` 누락·형식 오류 400, 서비스 예외의 429/404/409 매핑, 인증 헤더 없는 요청이 401이 아님을 검증한다.

## Task 4: MySQL 통합 검증과 전체 회귀

- [ ] Fake `EmailSender` + Testcontainers MySQL로 발송 성공 시 원문 미저장·해시만 저장, 거부 요청 행 커밋 후 후속 요청 429, 발송 실패 시 저장·이전 코드 무효화 동반 롤백을 검증한다.
- [ ] 성공·실패 모든 시나리오 전후로 `users`·`accounts`·`refresh_tokens`와 주문·체결·투자일기가 불변임을 확인한다.
- [ ] 인증번호 원문이 로그에 남지 않는지 확인한다.
- [ ] 대상 테스트 전체와 기존 회귀 스위트, Spotless, `./gradlew build`(SpotBugs·JaCoCo 포함)를 실제 실행하고 결과를 기록한다.

## Task 5: PRD 신설과 API 문서 동기화

- [ ] `docs/prd.md`에 `AUTH-006`(U1 초안 기준)을 신설하고, §5 공통 오류표에 신규 409 코드 행을, 엔드포인트 목록에 `POST /api/auth/password-resets`를 추가한다.
- [ ] `docs/api-routes.md`·`docs/api-contracts.md`를 실제 매핑과 일치하게 갱신한다 (planner 동기화 모드).
- [ ] `docs/specs/002-auth-account/tasks.md`의 Issue #115 절을 체크한다.

---

## 완료 체크리스트

- [ ] 비밀번호가 있는 회원 이메일 요청이 202로 수락되고 6자리 인증번호가 발송된다.
- [ ] `password_reset_verifications`에 인증번호 원문이 저장되지 않고 HMAC-SHA-256 해시만 저장되며, 로그에도 원문이 남지 않는다.
- [ ] 미가입 이메일 404, 비밀번호 없는 OAuth 전용 회원 409가 각각 검증되고 두 경우 모두 메일이 발송되지 않는다.
- [ ] 거부된 404·409 요청이 발송 제한 집계에 포함돼 같은 주소의 반복 요청이 429가 된다.
- [ ] 60초·1시간 5회·하루 10회 초과가 429 `TOO_MANY_REQUESTS`로 검증되고, 429 요청은 행을 남기지 않는다.
- [ ] 재발송 시 같은 이메일의 이전 인증번호가 즉시 무효화되어 유효한 코드가 최대 1개다.
- [ ] 발송 실패 시 저장과 이전 코드 무효화가 함께 롤백된다.
- [ ] 인증 헤더 없이 호출해도 401이 아님이 검증된다.
- [ ] `users`·`accounts`·`refresh_tokens`가 전 시나리오에서 불변임이 MySQL 통합 테스트로 확인된다.
- [ ] `PASSWORD_RESET_SECRET`이 없으면 기동이 실패한다(yml 기본값 없음)는 것이 확인된다.
- [ ] `docs/prd.md`·`docs/api-routes.md`·`docs/api-contracts.md`가 실제 구현과 일치한다.
- [ ] `./gradlew build`(Spotless·SpotBugs·JaCoCo 포함)가 통과한다.

---

## 미확정·PRD 불일치 (임의로 정하지 않고 보고하는 항목)

**D2(응답 구분)·D4(이메일 단위 집계)·D7(전용 시크릿)은 착수 전 팀에서 확정된 결정이며 여기 목록에 포함하지 않는다.**

**U1. PRD에 비밀번호 재설정 요구사항이 없다 — `AUTH-006` 신설 필요.** PRD `AUTH-001`~`AUTH-005` 어디에도 재설정이 없고, `AUTH-005`는 로그인 상태의 내 정보 수정만 다룬다. PRD 없이 spec을 확정할 수 없으므로 아래 초안을 제안하며, **문구는 팀 확정 후 Task 5에서 반영한다.**

```md
#### AUTH-006 비밀번호 재설정 (비로그인)

- 비밀번호를 잊은 이메일 회원은 가입 이메일로 6자리 인증번호를 받아 비밀번호를 재설정한다. 요청 경로는 인증이 필요 없는 공개 경로다.
- 가입되지 않은 이메일은 404 NOT_FOUND, 비밀번호가 없는 OAuth 전용 회원은 409 <신규 코드>로 거부하고 발송하지 않는다.
  이 응답 구분은 계정 존재 여부와 가입 방식이 드러나는 것을 감수한 결정이며, 가입 인증 요청이 이미 같은 정보를 409로 드러내고 있다는 점과 아래 발송 제한을 근거로 한다.
- 인증번호는 6자리 숫자이며 유효시간은 5분, 입력 시도는 최대 5회다. 5회를 초과하면 즉시 무효화하고 429 TOO_MANY_REQUESTS로 응답한다.
- 재발송은 60초 간격이며 같은 이메일에 1시간 5회·하루 10회로 제한한다. 거부된 요청도 이 집계에 포함한다. 초과는 429 TOO_MANY_REQUESTS다.
- 재발송하면 이전 인증번호는 즉시 무효화된다 — 유효한 인증번호는 항상 최대 1개다.
- 인증번호 원문은 저장하지 않고 전용 시크릿 기반 HMAC-SHA-256 해시로만 저장한다. 가입 인증번호와 시크릿을 공유하지 않는다.
- 인증번호 확인에 성공하면 users.password_hash를 원자적으로 교체하고 기존 Refresh Token을 모두 폐기해 재로그인을 요구한다.
- 발송 단계에서는 users·accounts·Refresh Token을 만들거나 바꾸지 않는다.
- 자동 테스트와 로컬 실행은 Fake 발송기를 사용한다.
```

**U2. ~~OAuth 전용 회원 409의 `ErrorCode` 이름·메시지 (D3).~~ → 2026-08-01 확정, 미확정 아님.** 기존 409 세 개(`DUPLICATE_RESOURCE`·`ACCOUNT_LINK_REQUIRED`·`EMAIL_VERIFICATION_REQUIRED`) 재사용을 검토했고 모두 의미가 맞지 않아 신규 코드를 추가한다. 확정된 값은 `SOCIAL_ACCOUNT_ONLY(HttpStatus.CONFLICT, "소셜 로그인 전용 계정입니다. 카카오 또는 네이버 로그인을 이용해 주세요.")`다 — 코드베이스가 이미 `SignupMethod`·`SocialAccount`로 "소셜"을 쓰고 있어 용어가 일관되고, 향후 비밀번호 최초 설정 같은 다른 지점에서도 재사용할 수 있다는 것이 선택 근거다(대안이던 `PASSWORD_LOGIN_UNAVAILABLE`·`OAUTH_ONLY_ACCOUNT`는 기각). 미가입 404는 기존 `NOT_FOUND` 코드를 쓰되 `BusinessException(ErrorCode, String)`으로 메시지를 **"가입되지 않은 이메일입니다."로 덮어쓴다** — D2에서 이미 404로 가입 여부를 알려주기로 한 이상 메시지를 모호하게 둬도 더 안전해지지 않고, 프론트가 별도 문구를 갖지 않아도 되기 때문이다.

**U3. 거부 요청 행을 같은 테이블에 NULL 컬럼으로 남기는 설계 (D6).** `code_hash`·`expires_at`·`last_sent_at`을 NULL 허용으로 두고 "세 컬럼이 NULL이면 미발송 요청"이라는 규약을 썼다. 대안은 (a) 상태 컬럼(`SENT`/`USER_NOT_FOUND`/`OAUTH_ONLY`)을 명시적으로 두는 것, (b) 발송 이력 테이블을 따로 만들어 인증번호 테이블은 실제 발송 행만 담게 하는 것이다. 이번에는 그 구분을 읽는 코드가 없어 가장 단순한 (규약) 안을 골랐다. 후속 #116이 "발송 이력 조회"나 "거부 사유별 통계"를 요구하면 (a)로 바꾸는 편이 낫다.

**U4. `user_id` FK 부재 (D6).** 미가입 이메일 요청 행 때문에 FK를 두지 않았다. 그 결과 이 테이블은 `users` 삭제와 참조 무결성으로 묶이지 않으며, 후속 #116은 확인 시점에 이메일로 회원을 다시 조회해야 한다. `email_verifications`와 같은 구조라 새로운 패턴은 아니다.

**U5. 인증번호 발송 로직이 세 번째로 복제된다 (D5).** `EmailVerificationService`·`EmailChangeService`에 이어 `PasswordResetService`가 같은 상수(5분·60초·5회·10회)와 `generateCode`·`hmac`·`checkSendRateLimit`·`expirePreviousCodes`를 갖는다. `conventions.md`의 "세 번째 중복이 보이고 책임이 명확할 때만 공통화 검토" 기준에 도달했지만, 이번 이슈에서 신규 API 구현과 3개 서비스 리팩터링을 함께 하지 않기로 했다. **공통화(예: `VerificationCodePolicy` 값 객체 + HMAC 계산 컴포넌트) 검토는 별도 리팩터링 이슈로 제안한다.**

**U6. 발송 제한 판정을 존재 확인보다 먼저 하는 순서 (D4·D5).** `EmailVerificationService.sendVerificationCode`는 중복 확인 → 발송 제한 순서인데 이 서비스는 반대다. 두 엔드포인트의 순서가 달라지는 것이 팀에게 혼란스럽다면, 기존 서비스 쪽 순서를 맞추는 편이 나을 수 있다(다만 그건 기존 동작 변경이라 별도 이슈여야 한다).

**U7. 재설정 인증번호 메일 본문이 가입 인증번호 메일과 동일하다.** `EmailSender.sendVerificationCode(toEmail, code)` 인터페이스에 용도 구분이 없어, 회원이 받는 메일에 "비밀번호 재설정"이라는 맥락이 표시되지 않는다. 이슈 본문·PRD가 메일 본문을 규정하지 않아 이번에는 기존 인터페이스를 그대로 쓴다. 용도별 문구가 필요하면 `EmailSender`에 메서드를 추가하는 별도 변경이 필요하며, 그 경우 `ResendEmailSender`·`FakeEmailSender`와 기존 테스트가 함께 바뀐다.

**U8. 재설정 요청이 기존 세션에 미치는 영향 없음.** 발송 단계에서는 Refresh Token을 폐기하지 않는다(이슈 범위 명시). 실제 폐기는 #116의 재설정 완료 시점이다. "재설정 요청만으로 다른 기기 세션을 끊어야 한다"는 요구가 있으면 PRD `AUTH-006`에 명시해야 한다.
