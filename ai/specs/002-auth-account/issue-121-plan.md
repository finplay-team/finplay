# Issue #121 인증번호 시도 횟수 갱신 유실 — 남은 두 서비스 원자성 확보와 공통화 계획

> **For agentic workers:** 이 이슈는 **동작을 바꾸지 않는 수정**이다. 응답 코드·판정 순서·오류 메시지가 한 개라도 달라지면 실패로 본다. 동시성 테스트는 **수정 전 코드에서 실패하는 것을 먼저 실측하고 그 값을 `run-log.md`에 남긴 뒤에** 프로덕션 코드를 고친다 — 통과만 보고하는 것은 검증이 아니다. `PasswordResetService`는 PR #120에서 이미 끝났으므로 이번 범위가 아니다.

**Goal:** `EmailVerificationService.confirmVerificationCode`와 `EmailChangeService.validateAndConsumeCode`의 "잠금 없이 읽고 → 읽은 값으로 한도 판정 → 증가" 경로에 PR #120과 **같은 방식**(`@Lock(LockModeType.PESSIMISTIC_WRITE)`)을 적용해 읽기-판정-증가를 직렬화한다. 그 뒤 세 서비스에 복제된 인증번호 정책 상수와 `generateCode`·`hmac`·`checkSendRateLimit`·`expirePreviousCodes`를 공통 컴포넌트로 추출하되, **용도별 HMAC 시크릿 분리는 그대로 보존한다.**

**관련 정본:** GitHub Issue #121(본문 + 범위 축소 코멘트), PRD `AUTH-004`(`ai/prd.md` 246-259행), `AUTH-005`(272-278행), `AUTH-006`(294-318행), `ai/specs/002-auth-account/spec.md`, `ai/specs/002-auth-account/plan.md`, `issue-116-plan.md`(D1 판정 순서·D3 `noRollbackFor`), `issue-115-plan.md`(U5 공통화 미룸), `issue-56-plan.md`(이메일 변경 확인 구조), `ai/specs/002-auth-account/run-log.md`("QA 경쟁 조건 수정" 절), PR #120 커밋 `385144c`, ADR-0002, ADR-0003, ADR-0004, `docs/conventions.md`

**착수 전 확정된 결정 (다시 논의하지 않는다):**

1. **`PasswordResetService`는 범위 밖이다.** PR #120 `385144c`에서 `@Lock`이 이미 들어갔고 동시성 회귀 테스트 4건이 함께 머지됐다. 이번 이슈는 나머지 두 곳을 여기에 **맞추는** 작업이다.
2. **방식은 `@Lock(LockModeType.PESSIMISTIC_WRITE)`다.** 원자적 `@Modifying UPDATE`는 검토 후 기각됐다 — 한도 판정이 **증가 전 값 기준**이고(5면 증가시켜 6으로 만들고 429) 성공 경로만 증가하지 않는 비대칭이 있어, UPDATE 한 방으로 옮기면 분기별 증가 여부와 판정 순서를 다시 설계해야 한다. `@Lock`은 서비스 본문을 한 줄도 건드리지 않으므로 관찰 가능한 동작이 **정의상** 보존된다. 실제 적용 형태는 `PasswordResetVerificationRepository`에 있다.
3. **공통화 대상은 정책 상수(5분 만료·60초 재발송·1시간 5회·하루 10회·최대 5회 시도)와 `generateCode`·`hmac`·`checkSendRateLimit`·`expirePreviousCodes` 4개 메서드다.** 용도별 HMAC 시크릿 분리(`EMAIL_VERIFICATION_SECRET` / `PASSWORD_RESET_SECRET`)를 깨뜨리면 안 된다.

**선행 (실제 코드 확인 완료):**

| 확인한 것 | 현재 상태 | 이번 이슈에서 |
|---|---|---|
| `PasswordResetVerificationRepository.findFirstByEmailAndCodeHashIsNotNullOrderByCreatedAtDesc` | `@Lock(PESSIMISTIC_WRITE)` **적용됨** (`385144c`) | **수정하지 않는다** — 복제할 원본 |
| `PasswordResetConfirmIntegrationTest` 동시성 4건 | 통과 중 | **수정하지 않는다** — 테스트 형식의 원본 |
| `EmailVerificationRepository.findFirstByEmailOrderByCreatedAtDesc` | 잠금 없음. 프로덕션 호출자 1곳(`EmailVerificationService:86`) | `@Lock` **추가** |
| `EmailChangeVerificationRepository.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc` | 잠금 없음. 프로덕션 호출자 1곳(`EmailChangeService:102`) | `@Lock` **추가** |
| `EmailVerificationService.confirmVerificationCode` | `@Transactional(noRollbackFor = BusinessException.class)` **자기 자신에 선언** | 본문 미수정 |
| `EmailChangeService.validateAndConsumeCode` | `@Transactional` 미선언, 호출자 `AuthService.confirmEmailChange`가 `@Transactional(noRollbackFor=..., rollbackFor=EmailChangeConflictException.class)` | 본문 미수정 |
| `EmailChangeVerification.user` | `@ManyToOne(fetch = FetchType.LAZY)` | 잠금 범위가 `users`로 번지지 않을 근거 (D3) |
| 세 서비스의 정책 상수·`generateCode`·`hmac`·`checkSendRateLimit`·`expirePreviousCodes` | 3중 복제 | 공통 컴포넌트로 추출 (D5·D6) |
| `build.gradle` 테스트 환경 | `EMAIL_VERIFICATION_SECRET`·`PASSWORD_RESET_SECRET`이 **서로 다른 값** | 시크릿 뒤바뀜이 기존 테스트로 잡히는 근거 (D5) |

**신규 Flyway 마이그레이션·신규 `ErrorCode`·신규 엔드포인트는 없다.** 컨트롤러를 건드리지 않으므로 `ai/api-routes.md`·`docs/api-contracts.md`도 **수정하지 않는다.**

---

## Architecture

- 원자성 수정은 **repository 레이어에만** 닿는다. 애노테이션 한 줄씩 두 개이며 서비스·컨트롤러·엔티티·DTO는 한 글자도 바뀌지 않는다.
- 공통화는 **service 레이어 안쪽**에서만 일어난다. 새 컴포넌트는 `com.finplay.api.auth.verification` 패키지에 두고, 세 서비스가 이를 주입·소유한다. repository·controller 시그니처는 그대로다.
- 두 변경이 서로 다른 레이어의 서로 다른 파일에 있어 **파일 충돌이 생기지 않는다.** 이것이 D1(순서 결정)의 핵심 근거다.
- ADR-0002 레이어 규칙 유지 — 새 컴포넌트는 repository를 주입받지 않는 순수 정책 객체이며, 조회는 계속 각 서비스가 자기 repository로 한다.

## Tech Stack

Java 17, Spring Boot 4.1, Spring Data JPA(`@Lock`), MySQL 8.4(Testcontainers, InnoDB row lock), JUnit 5, Mockito, AssertJ, `ExecutorService`/`CountDownLatch`, `TransactionTemplate`(`PROPAGATION_REQUIRES_NEW`), Gradle Groovy DSL

---

## 요구사항 ID와 수용 기준

### PRD 근거

- `AUTH-004`(249·251·255행) — 인증번호 6자리·5분 만료·**최대 5회 시도**·5회 초과 시 즉시 무효화 + 429, 60초 재발송·1시간 5회·하루 10회, 전용 시크릿 HMAC-SHA-256 저장.
- `AUTH-005`(278행) — 이메일 변경 인증번호에 같은 정책(5분·5회·60초·1시간 5회·하루 10회·재발송 시 이전 번호 무효화)을 적용.
- `AUTH-006`(297-298·310행) — 같은 정책 + "인증번호 검증에 실패해도 시도 횟수 증가는 커밋한다. 실패와 함께 되돌리면 시도 5회 제한이 무력화되어 무차별 대입을 막지 못한다."

**PRD가 "최대 5회"라고 적은 것은 곧 "동시 요청에서도 5회"라는 뜻이다.** 지금은 병렬 요청 N건이 시도 1회로 계산되어 이 문장이 사실이 아니다. 이번 이슈는 PRD 문구를 바꾸지 않고 **구현을 PRD에 맞춘다.**

### Issue #121 수용 기준

1. 같은 인증번호에 N개 요청을 동시에 보내도 시도 횟수가 정확히 집계되어 5회를 넘겨 시도할 수 없다.
2. 세 서비스가 같은 방식으로 동작하며 구현이 갈리지 않는다.
3. 기존 응답 계약에 회귀가 없다 — 가입 인증·이메일 변경·비밀번호 재설정의 400·409·429와 5회 초과 시 즉시 만료가 그대로다.
4. 공통화 후에도 용도별 HMAC 시크릿 분리가 유지된다.
5. **동시성 테스트가 수정 전 코드에서는 실패하고 수정 후 통과한다** (방어선이 실재함을 확인).

---

## 범위와 제외

### 포함

- `EmailVerificationRepository.findFirstByEmailOrderByCreatedAtDesc`에 `@Lock(PESSIMISTIC_WRITE)` 적용.
- `EmailChangeVerificationRepository.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc`에 `@Lock(PESSIMISTIC_WRITE)` 적용.
- 두 경로의 동시성 회귀 테스트 추가 — **수정 전 실패 실측을 포함한다.**
- 잠금 쿼리를 트랜잭션 밖에서 직접 호출하는 기존 테스트 호출부 교정 (D4).
- 정책 상수·`generateCode`·`hmac`·`checkSendRateLimit`·`expirePreviousCodes` 공통화 (D5·D6).
- `run-log.md`에 실측값 기록, `tasks.md` 체크, PRD 정책 서술 대조.

### 제외

- **`PasswordResetService`·`PasswordResetVerificationRepository`의 잠금 수정** — PR #120에서 완료. 공통화 대상으로만 참여한다.
- 원자적 `@Modifying UPDATE` 방식으로의 재설계 (확정된 결정 2).
- IP·디바이스 단위 요청 제한 (#115 D4에서 범위 밖으로 확정).
- 확인 엔드포인트에 대한 발송 제한 신설 (#116 U4의 한계를 그대로 둔다).
- 인증번호 자릿수·만료 시간 등 **정책값 변경** — 상수의 위치만 옮기고 값은 그대로다.
- 세 번째 HMAC 시크릿(`EMAIL_CHANGE_SECRET`) 신설 (U2).
- 계정 잠금·의심 로그인 알림, 재설정 완료 알림 메일.
- `ai/api-routes.md`·`docs/api-contracts.md` — 컨트롤러·응답 계약이 바뀌지 않는다.
- 신규 Flyway 마이그레이션·신규 `ErrorCode`.

---

## 설계 결정

### D1. 순서 — **원자성 먼저, 공통화 나중**

**결론: Task 1·2(원자성) → Task 3·4(공통화) 순서로 간다.** 근거는 네 가지다.

1. **"수정 전 실패"를 증명할 수 있는 창이 지금뿐이다.** 수용 기준 5는 "수정 전 코드에서 실패"를 요구한다. 공통화를 먼저 하면 그 시점의 "수정 전 코드"는 이미 리팩터링된 코드이고, 실패를 실측해도 *원래 코드에 결함이 있었다*가 아니라 *리팩터링한 코드에 결함이 있다*를 증명하게 된다. 이슈가 요구한 증명이 성립하지 않는다.
2. **파일이 겹치지 않아 "같은 파일을 두 번 헤집는" 비용이 실제로 없다.** 이슈 본문은 "원자성 수정이 세 곳을 모두 건드리니 같은 작업에서 공통화까지 하는 것이 자연스럽다"고 적었지만, `@Lock` 방식으로 확정된 지금 원자성 수정이 닿는 파일은 **repository 2개뿐**이고 공통화가 닿는 파일은 **service 3개**다. 교집합이 없다. 이슈 본문의 그 근거는 조건부 UPDATE 방식을 전제한 것이라 더 이상 성립하지 않는다.
3. **보안 수정이 리팩터링 뒤에 줄 서면 안 된다.** 공통화는 3개 서비스를 관통하는 구조 변경이라 리뷰에서 되돌아올 여지가 있다. 방어선 복구가 그 논의에 인질로 잡히면 안 된다. 원자성 수정은 애노테이션 한 줄씩이라 단독 리뷰·단독 롤백이 가능하다.
4. **PR #120이 이미 이 순서를 밟았다.** `385144c`는 `@Lock` + 테스트만 담았고 공통화는 손대지 않았다(`#121`로 명시적으로 넘김). 같은 순서를 유지하면 세 서비스의 커밋 이력이 같은 모양이 된다.

**대가:** 공통화 커밋이 원자성 커밋 위에 쌓이므로 공통화 시점에 동시성 테스트가 이미 존재한다. 이건 대가가 아니라 이득이다 — 공통화가 판정 순서나 한도 계산을 실수로 바꾸면 그 테스트가 바로 잡는다.

### D2. 적용 지점 — 조회 쿼리 2개, 애노테이션 2줄

```java
// EmailVerificationRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<EmailVerification> findFirstByEmailOrderByCreatedAtDesc(String email);

// EmailChangeVerificationRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
Optional<EmailChangeVerification> findFirstByUserIdAndNewEmailOrderByCreatedAtDesc(Long userId, String newEmail);
```

- 두 쿼리 모두 **프로덕션 호출자가 확인 경로 1곳뿐**이다(`EmailVerificationService:86`, `EmailChangeService:102`). 발송 경로가 쓰는 집계·무효화 쿼리(`countBy...`, `findBy...ExpiresAtAfter`)에는 잠금을 걸지 않는다 — 그쪽은 갱신 유실 문제가 아니다.
- 잠금 유지 구간(커밋까지)이 확보되는지 확인했다.
  - `EmailVerificationService.confirmVerificationCode`는 **자기 자신이** `@Transactional(noRollbackFor = BusinessException.class)`다. 조회부터 커밋까지가 한 트랜잭션이다.
  - `EmailChangeService.validateAndConsumeCode`는 `@Transactional` 미선언이고, 유일한 호출자 `AuthService.confirmEmailChange`가 트랜잭션 경계를 갖는다. `PasswordResetService`와 같은 구조다. **`validateAndConsumeCode`에 `@Transactional`을 새로 붙이지 않는다** — 붙이면 이메일 변경 전체의 원자성(#56 D2)이 깨진다.
- `PasswordResetService`가 그랬듯 **서비스 본문은 한 줄도 바꾸지 않는다.** 판정 순서, 증가 시점, 오류 코드, `expire(now)` 호출이 전부 그대로 남아야 관찰 가능한 동작 보존이 자동으로 따라온다.

### D3. 잠금 범위가 넓어지지 않는지 확인 — `EmailChangeVerification`만의 추가 검증

`EmailChangeVerification`은 `user`를 `@ManyToOne`으로 들고 있고 조회 조건이 `UserId`다. 여기서 두 가지를 **생성 SQL로 직접 확인해야 한다.**

- `... for update`가 실제로 붙는가 (PR #120이 MySQL 8.4 Testcontainers 실기동으로 확인한 것과 같은 절차).
- `users`로의 **조인이 생기지 않는가.** 조인이 생기면 `FOR UPDATE`가 `users` 행까지 잠가 잠금 범위가 회원 단위로 번지고, 같은 회원의 다른 요청까지 대기시킨다. `fetch = FetchType.LAZY`이고 `user_id`가 같은 테이블의 FK 컬럼이므로 조인 없이 `user_id = ?`로 렌더링될 것으로 보지만, **추정으로 끝내지 말고 로그로 확인한다.** 조인이 생기면 `@Query`로 `where v.user.id = :userId`를 명시해 조인을 없앤 뒤 다시 확인한다.
- `EmailVerification`에는 연관관계가 없어 이 확인이 필요 없다.

락 획득 순서도 확인했다 — `AuthService.confirmEmailChange`는 `userRepository.findById`(잠금 없는 읽기) → `validateAndConsumeCode`(X 락) 순서이고, 역순으로 잠그는 경로가 없어 데드락 조합이 만들어지지 않는다.

### D4. 잠금 쿼리를 트랜잭션 밖에서 부르는 기존 테스트 호출부를 먼저 교정한다

`@Lock(PESSIMISTIC_WRITE)` 쿼리를 활성 트랜잭션 없이 호출하면 Spring Data가 `SimpleJpaRepository`의 클래스 레벨 `@Transactional(readOnly = true)`로 감싸고, MySQL은 read-only 트랜잭션에서 `SELECT ... FOR UPDATE`를 거부한다. **잠금을 붙이는 순간 기존 테스트가 깨진다.**

실제 호출부를 전수 조사했다.

| 파일 | 위치 | 트랜잭션 | 조치 |
|---|---|---|---|
| `EmailChangeConfirmIntegrationTest`(`@SpringBootTest`, 클래스 `@Transactional` **없음**) | 152·164·185·201·247·287행 | **없음 — 깨진다** | jdbcTemplate 조회 또는 `TransactionTemplate`(REQUIRES_NEW) 안으로 옮긴다 |
| `EmailVerificationTransactionIntegrationTest` | 98행 (`loadStateInNewTransaction`) | `TransactionTemplate`(REQUIRES_NEW, 쓰기) | 그대로 둔다 |
| `EmailChangeVerificationRepositoryTest`, `EmailVerificationRepositoryTest` | `@DataJpaTest` | 트랜잭션 있음(쓰기) | 그대로 둔다 |
| `EmailChangeServiceTest`, `EmailVerificationServiceTest` | Mockito 스텁 | DB 없음 | 그대로 둔다 |

교정 방식은 **PR #120의 선례를 따른다** — `PasswordResetConfirmIntegrationTest`는 상태 확인 헬퍼(`attemptCountOf`·`consumedAtOf`·`latestSentRow`)를 `jdbcTemplate` + `findById`로 짜 두어 잠금 쿼리를 테스트에서 부르지 않는다. 같은 모양의 헬퍼를 `EmailChangeConfirmIntegrationTest`에 만들고 6곳을 그리로 돌린다.

**주의:** 이 교정은 Task 2 안에서 `@Lock` 추가와 **같은 커밋**에 들어간다. 따로 커밋하면 중간 커밋에서 테스트가 깨진 상태가 된다. 다만 순서는 지킨다 — 동시성 테스트로 실패를 먼저 실측하고, 그다음 `@Lock` + 호출부 교정을 함께 넣는다.

### D5. 공통화 구조 — 컴포넌트 2개, 시크릿은 각 서비스가 계속 소유한다

새 패키지 `com.finplay.api.auth.verification`에 둘을 만든다.

**(1) `VerificationCodeHasher` — 스프링 빈이 아니라 각 서비스가 생성자에서 직접 만드는 평범한 final 클래스로 둔다.**

```java
// 인증번호를 용도별 시크릿으로 HMAC-SHA-256 해싱하는 값 객체
public final class VerificationCodeHasher {
    public VerificationCodeHasher(String secret) { ... }
    public String hmac(String code) { ... }
}
```

각 서비스는 지금 있는 `@Value` 파라미터를 **그대로 유지하고** 필드만 바꾼다.

```java
// EmailVerificationService / EmailChangeService — 생성자 파라미터 변경 없음
this.codeHasher = new VerificationCodeHasher(emailVerificationSecret);  // EMAIL_VERIFICATION_SECRET
// PasswordResetService
this.codeHasher = new VerificationCodeHasher(passwordResetSecret);      // PASSWORD_RESET_SECRET
```

- **빈 2개 + `@Qualifier` 방식을 채택하지 않은 이유:** 각 서비스가 자기 `@Value`로 자기 시크릿을 읽는 지금 구조가 이미 분리를 강제한다. 빈으로 바꾸면 주입 대상을 잘못 고를 수 있는 새 경로가 생기고, `@Configuration` 파일과 한정자가 늘어난다. 현재 배선을 **한 글자도 바꾸지 않는 쪽**이 시크릿 분리를 깨뜨릴 확률이 가장 낮다.
- 각 서비스에서 사라지는 것은 `HMAC_ALGORITHM` 상수, `hmacKey` 필드, `private String hmac(...)` 메서드 본문 3벌이다. 호출부는 `hmac(code)` → `codeHasher.hmac(code)`로만 바뀐다.
- 예외 메시지가 서비스마다 다르다(`"인증번호 HMAC 계산에 실패했습니다."` vs `"비밀번호 재설정 인증번호 HMAC 계산에 실패했습니다."`). 공통 클래스에서는 하나로 통일된다. `IllegalStateException`이고 정상 경로에서 도달 불가능하며 응답 계약이 아니므로 회귀로 보지 않는다 — 다만 이 메시지를 단정하는 테스트가 있는지 확인하고, 있으면 함께 고친다.
- `sha256`(가입 토큰·재인증 토큰용)은 **공통화 대상이 아니다.** 이슈가 나열하지 않았고 용도·입력이 다르다.

**(2) `VerificationCodePolicy` — 정책 상수와 정책 판정을 담는 `@Component`.**

```java
// 인증번호 생성·만료·시도 한도·발송 제한을 한곳에서 판정하는 정책 컴포넌트
@Component
public class VerificationCodePolicy {
    public static final int CODE_TTL_MINUTES = 5;
    public static final int RESEND_INTERVAL_SECONDS = 60;
    public static final int HOURLY_LIMIT = 5;
    public static final int DAILY_LIMIT = 10;
    public static final int MAX_VERIFICATION_ATTEMPTS = 5;

    public String generateCode();                                  // 6자리 SecureRandom
    public LocalDateTime expiresAt(LocalDateTime now);             // now.plusMinutes(CODE_TTL_MINUTES)
    public boolean isAttemptLimitReached(int attemptCount);        // attemptCount >= MAX_VERIFICATION_ATTEMPTS
    public void checkSendRateLimit(LocalDateTime now, ToLongFunction<LocalDateTime> countCreatedAfter);
}
```

- `checkSendRateLimit`이 세 서비스에서 다른 점은 **집계 키뿐이다** — `countByEmailAndCreatedAtAfter(email, since)`(가입 인증·재설정) vs `countByUserIdAndCreatedAtAfter(userId, since)`(이메일 변경). 창(60초/1시간/하루)과 비교 연산자(`> 0` / `>= HOURLY_LIMIT` / `>= DAILY_LIMIT`)와 던지는 예외(`TOO_MANY_REQUESTS`)는 완전히 같다. 그래서 **세는 함수만 인자로 받는다.**
  ```java
  policy.checkSendRateLimit(now, since -> repository.countByEmailAndCreatedAtAfter(email, since));
  policy.checkSendRateLimit(now, since -> repository.countByUserIdAndCreatedAtAfter(userId, since));
  ```
- **판정 순서(60초 → 1시간 → 하루)와 비교 연산자를 그대로 옮긴다.** 첫 창만 `> 0`이고 나머지는 `>= LIMIT`인 비대칭이 의도된 것이다(60초 창은 "한 건이라도 있으면 거부"). 이 비대칭을 "정리"하면 재발송 간격 정책이 바뀐다.
- `isAttemptLimitReached`가 `>=`인 것도 그대로다 — `attemptCount >= 5`일 때 증가시켜 6으로 만들고 429다(#116 D1). 이 경계를 건드리면 동시성 테스트의 "6번째가 429" 단정이 깨진다.
- `SecureRandom`은 이 컴포넌트가 하나만 갖는다(현재는 서비스마다 하나씩 3개).

### D6. `expirePreviousCodes` 공통화 — 실익이 가장 얕은 항목, 경계를 명시한다

세 서비스의 `expirePreviousCodes`에서 **실제로 같은 부분은 `for (x : rows) x.expire(now);` 세 줄뿐이다.** 조회 조건은 서로 다르고, 다른 것이 우연이 아니다.

| 서비스 | 무효화 대상 조회 | "이미 쓴 행"의 판별 컬럼 |
|---|---|---|
| `EmailVerificationService` | `findByEmailAndVerifiedAtIsNullAndExpiresAtAfter` | `verified_at` |
| `EmailChangeService` | `findByUserIdAndNewEmailAndConsumedAtIsNullAndExpiresAtAfter` | `consumed_at` |
| `PasswordResetService` | `findByEmailAndCodeHashIsNotNullAndConsumedAtIsNullAndExpiresAtAfter` | `consumed_at` + 거부 행 제외 |

**그래서 조회는 각 서비스에 남기고 만료 적용만 공통화한다.**

```java
// 인증번호 엔티티가 공통으로 갖는 무효화 계약
public interface ExpirableVerification {
    void expire(LocalDateTime now);
}
// VerificationCodePolicy
public void expireAll(List<? extends ExpirableVerification> rows, LocalDateTime now);
```

세 엔티티(`EmailVerification`·`EmailChangeVerification`·`PasswordResetVerification`)에 `implements ExpirableVerification`만 붙인다. 기존 `expire(LocalDateTime)` 시그니처가 이미 셋 다 같으므로 메서드 본문 변경은 없다.

**이 항목만 리뷰에서 "과한 추상화"로 판정되면 Task 4에서 빼도 된다** — 원자성 수정과도, 나머지 세 공통화 항목과도 독립적이다. `docs/conventions.md`의 리뷰 질문("공통화가 책임을 명확하게 만들었는가, 아니면 숨겼는가?")에 정면으로 걸리는 유일한 항목이라 미리 적어 둔다 (U3).

### D7. 동시성 테스트를 어떻게 쓰고, 수정 전 실패를 어떻게 확인하는가

**작성 형식은 PR #120 `PasswordResetConfirmIntegrationTest`를 그대로 따른다.**

- `@SpringBootTest` + Testcontainers MySQL. **클래스에 `@Transactional`을 붙이지 않는다** — 롤백시키면 "예외가 나가고도 커밋되는가"라는 검증 자체가 사라진다.
- `ExecutorService` + `CountDownLatch` 출발 게이트로 N개 스레드를 동시에 출발시키고, 각 결과(상태 코드 또는 `ErrorCode`)를 모아 단정한다.
- **호출 지점은 각 테스트 파일의 기존 방식에 맞춘다.** `PasswordResetConfirmIntegrationTest`가 MockMvc였던 것은 그 파일이 원래 MockMvc 기반이라서다. 이번 두 곳은 트랜잭션 경계 진입점을 직접 부르는 것이 기존 스타일이고 커넥션 풀 여유도 크다.
  - 가입 인증 → `emailVerificationService.confirmVerificationCode(email, wrongCode)` (경계가 이 메서드 자신)
  - 이메일 변경 → `authService.confirmEmailChange(userId, newEmail, wrongCode)` (경계가 이 메서드, Bearer 토큰 불필요)
  - 각 스레드는 `BusinessException`을 잡아 `getErrorCode()`를 수집한다.
- 동시 요청 수는 커넥션 풀 기본값(10) 아래로 잡는다 — 5·6·8.

**검증할 시나리오 3종 (각 경로마다).**

| # | 시나리오 | 단정 | 수정 전이면 |
|---|---|---|---|
| 1 | 클린 상태에서 오답 5건 동시 | 전부 `EMAIL_VERIFICATION_FAILED`, `attempt_count == 5`, 소비/확인 안 됨 | `attempt_count`가 1~2로 뭉개짐 |
| 2 | 오답 6건 동시 | `EMAIL_VERIFICATION_FAILED` 5건 + `TOO_MANY_REQUESTS` 1건, `attempt_count == 6`, `expires_at <= now` | 429가 한 건도 안 나옴 |
| 3 | 오답 8건 버스트 | 429가 **최소 1건**, 전부 400 또는 429, `attempt_count >= 6`, 인증번호 무효화됨, 이후 **정답도 거부** | 429 없음, 무효화 안 됨 |

시나리오 3에서 개수를 정확히 단정하지 않는 이유는 PR #120이 남긴 그대로다 — `now`가 행 잠금을 잡기 전에 찍히므로 꼬리 응답이 429일 수도 400일 수도 있다. 그래서 "최종 상태"만 단정한다.

**수정 전 실패 확인 절차 (수용 기준 5). 이 순서를 지키지 않으면 그 항목은 미완료다.**

1. 동시성 테스트만 먼저 작성한다. **프로덕션 코드는 아직 건드리지 않는다.**
2. 해당 테스트를 실행한다 → **실패해야 한다.** 실패 시 나온 **실측값**(예: 기대 5, 실측 1)을 그대로 기록해 둔다. 통과해 버리면 테스트가 경쟁 조건을 재현하지 못한 것이므로, 동시 요청 수를 늘리거나 출발 게이트를 점검해 재현될 때까지 고친다. **재현 못 한 채로 넘어가지 않는다.**
3. repository에 `@Lock` 한 줄을 넣는다(+ D4의 호출부 교정).
4. 같은 테스트를 다시 실행한다 → 통과해야 한다.
5. 생성 SQL에 `... for update`가 붙는지 로그로 확인한다(D3의 조인 여부 포함).
6. **2번과 4번 두 실행의 명령과 실측값을 `ai/specs/002-auth-account/run-log.md`에 남긴다.** PR #120이 "QA 실측(동시 5건 → `attempt_count` 1)"을 남긴 것과 같은 형식이다. 통과 사실만 적는 것은 근거가 아니다.
7. (권장) 커밋 직전에 프로덕션 변경만 임시로 되돌려 다시 실패하는지 한 번 더 확인한다 — 테스트가 잠금이 아닌 다른 이유로 통과하고 있지 않다는 최종 확인이다.

Task 3·4(공통화) 이후에도 이 동시성 테스트가 통과하는지 다시 돌린다. 공통화가 한도 판정 경계를 건드렸다면 여기서 잡힌다.

### D8. 응답 계약 회귀를 무엇으로 막는가

`@Lock`은 서비스 본문을 건드리지 않으므로 계약 회귀의 주 위험은 **Task 3·4의 공통화**다. 다음을 기존 테스트로 고정한 채 진행한다.

| 보존 대상 | 고정하는 기존 테스트 |
|---|---|
| 가입 인증 확인 400/429, 5회 초과 즉시 만료 | `EmailVerificationServiceTest`, `EmailVerificationControllerTest`, `EmailVerificationTransactionIntegrationTest` |
| 이메일 변경 확인 400/409/429, 재사용·만료·재발송 무효화·타인 격리 | `EmailChangeServiceTest`, `EmailChangeControllerTest`, `EmailChangeConfirmIntegrationTest`, `EmailChangeIntegrationTest` |
| 재설정 확인 400/409/429, 실패 시 증가 커밋 | `PasswordResetServiceTest`, `PasswordResetControllerTest`, `PasswordResetConfirmIntegrationTest`, `PasswordResetIntegrationTest` |
| 발송 제한 429(60초·1시간·하루) 3종 경계 | 위 서비스 테스트들의 발송 제한 케이스 |
| **HMAC 시크릿 분리** | `EmailVerificationTransactionIntegrationTest`(`SECRET = "test-email-verification-secret"`로 직접 해시 계산), `PasswordResetIntegrationTest`·`PasswordResetConfirmIntegrationTest`(재설정 시크릿 기반 흐름) — `build.gradle`이 두 시크릿에 **다른 값**을 주므로 배선이 뒤바뀌면 이 테스트들이 곧바로 깨진다 |

여기에 시크릿 분리 전용 가드를 하나 추가한다 — **같은 인증번호에 대해 `EMAIL_VERIFICATION_SECRET` 해시와 `PASSWORD_RESET_SECRET` 해시가 서로 다르고, 한쪽 시크릿으로 만든 코드가 다른 쪽 경로에서 통하지 않는다**는 단정이다. 기존 테스트가 간접적으로 잡아 주더라도 의도를 명시한 테스트가 하나 있어야 나중에 배선을 만지는 사람이 이유를 안다.

**공통화 커밋에서 기존 테스트의 기대값을 고치는 일이 생기면 그 자체가 회귀 신호다.** 스텁 대상 이름이 바뀌어 컴파일이 깨지는 것은 허용하되(예: `hmac` → `codeHasher.hmac`), **기대하는 상태 코드·횟수·순서를 바꾸는 수정은 하지 않는다.**

---

## File Map

### Production files to modify — 원자성 (Task 1·2)

- `src/main/java/com/finplay/api/auth/repository/EmailVerificationRepository.java` — `findFirstByEmailOrderByCreatedAtDesc`에 `@Lock(PESSIMISTIC_WRITE)` + 이유 주석 (D2)
- `src/main/java/com/finplay/api/auth/repository/EmailChangeVerificationRepository.java` — `findFirstByUserIdAndNewEmailOrderByCreatedAtDesc`에 `@Lock(PESSIMISTIC_WRITE)` + 이유 주석 (D2·D3)

### Production files to create — 공통화 (Task 3·4)

- `src/main/java/com/finplay/api/auth/verification/VerificationCodeHasher.java` (D5)
- `src/main/java/com/finplay/api/auth/verification/VerificationCodePolicy.java` (D5·D6)
- `src/main/java/com/finplay/api/auth/verification/ExpirableVerification.java` (D6, 이 항목을 유지할 경우)

### Production files to modify — 공통화 (Task 3·4)

- `src/main/java/com/finplay/api/auth/service/EmailVerificationService.java` — 상수 5개·`hmacKey`·`SecureRandom`·`generateCode`·`hmac`·`checkSendRateLimit`·`expirePreviousCodes` 제거 후 공통 컴포넌트 사용. `SIGNUP_TOKEN_*` 상수·`generateSignupVerificationToken`·`sha256`은 **남긴다**(가입 토큰 전용). `confirmVerificationCode`의 판정 순서·오류 코드 불변.
- `src/main/java/com/finplay/api/auth/service/EmailChangeService.java` — 같은 치환. `verifyReauthProof`·`sha256`은 **남긴다**(재인증 토큰 전용).
- `src/main/java/com/finplay/api/auth/service/PasswordResetService.java` — 같은 치환. `NOT_FOUND_MESSAGE`·거부 행 저장 로직은 **남긴다**.
- `src/main/java/com/finplay/api/auth/domain/EmailVerification.java`·`EmailChangeVerification.java`·`PasswordResetVerification.java` — `implements ExpirableVerification`만 추가 (D6). **메서드 본문 변경 없음.**

### 수정하지 않을 파일

- `src/main/java/com/finplay/api/auth/repository/PasswordResetVerificationRepository.java` — PR #120에서 이미 잠금이 적용됐다.
- `src/main/java/com/finplay/api/auth/service/AuthService.java` — 트랜잭션 경계·롤백 정책 그대로 (#56 D2, #116 D3).
- `controller/`·`dto/`·`config/SecurityConfig.java`·`common/ErrorCode.java` — 엔드포인트·요청·응답·오류 코드가 전혀 바뀌지 않는다.
- `src/main/resources/db/migration/*` — 신규 마이그레이션 없음 (ADR-0004).
- `build.gradle`·`.env.example`·`deploy/README.md` — 두 시크릿 환경변수는 이미 배선돼 있고 이름도 바뀌지 않는다.
- `ai/api-routes.md`·`docs/api-contracts.md` — **컨트롤러 변경이 없으므로 건드리지 않는다.**

### Test files

- `EmailVerificationConcurrencyIntegrationTest`(신규 또는 `EmailVerificationTransactionIntegrationTest`에 케이스 추가) — D7의 시나리오 1·2·3.
- `EmailChangeConfirmIntegrationTest`(기존 파일) — ~~D7의 시나리오 1·2·3 추가~~ + **D4의 호출부 6곳 교정**(152·164·185·201·247·287행) + jdbcTemplate 기반 상태 헬퍼 추가.
- `EmailChangeConcurrencyIntegrationTest`(신규) — **실제 구현은 계획과 다르게 갔다.** D7은 이 시나리오를 `EmailChangeConfirmIntegrationTest`에 추가하고 `authService.confirmEmailChange`를 직접 호출하도록 설계했으나, 신규 파일 + MockMvc + 실제 가입·로그인·Bearer 플로우로 작성했다. 이유는 픽스처를 **프로덕션에 실제로 존재하는 형태**로 만들기 위해서다 — #115에서 픽스처가 프로덕션에 없는 형태여서 테스트 48건이 통과하는데도 결함이 살아 있었다(`context-notes.md` 2026-08-02). Bearer 경로를 그대로 태우면 Security 필터·컨트롤러·`@Valid`·예외 매핑까지 함께 지난다. PR #120의 `PasswordResetConfirmIntegrationTest`와 같은 모양이다.
- `VerificationCodePolicyTest`(신규, 순수 단위) — 6자리 형식·`expiresAt`·`isAttemptLimitReached` 경계(4/5/6)·`checkSendRateLimit` 3창 경계와 판정 순서(60초 `> 0`, 1시간·하루 `>= LIMIT`).
- `VerificationCodeHasherTest`(신규, 순수 단위) — 같은 코드·같은 시크릿이면 같은 해시, **다른 시크릿이면 다른 해시**, 알고리즘·인코딩(HMAC-SHA-256 hex)이 기존 결과와 바이트 단위로 동일한지(회귀 방지용 고정값 1건).
- 시크릿 분리 가드 — 한쪽 시크릿으로 만든 코드가 다른 쪽 확인 경로에서 통하지 않음 (D8).
- 기존 회귀 스위트 전체 — `EmailVerificationServiceTest`, `EmailChangeServiceTest`, `PasswordResetServiceTest`, 세 컨트롤러 테스트, 관련 통합 테스트.

### Documentation files to modify after implementation

- `ai/specs/002-auth-account/run-log.md` — D7 절차 2번·4번 실행의 명령과 **실측값** 기록, D3의 생성 SQL 확인 결과, 공통화 이후 재실행 결과.
- `ai/specs/002-auth-account/tasks.md` — Issue #121 절 체크.
- `ai/prd.md` — **문구 변경은 원칙적으로 없다.** 상수의 코드상 위치만 바뀌고 값·정책은 그대로다. 다만 Task 5에서 `AUTH-004`(249·251)·`AUTH-005`(278)·`AUTH-006`(297-298)의 정책 서술과 `VerificationCodePolicy`의 상수값이 하나씩 일치하는지 대조하고, 어긋나는 것이 나오면 **고치지 말고 보고한다.**

---

## Task 1: 가입 인증 확인 경로 원자성 — `EmailVerificationRepository`에 행 잠금

- [ ] D7의 시나리오 1·2·3 동시성 테스트를 **먼저** 작성하고 `@Lock` 없이 실행해 **실패를 실측한다.** 재현되지 않으면 동시 요청 수·출발 게이트를 고쳐 재현될 때까지 반복한다. 실측값(기대 vs 실제)을 기록해 둔다.
- [ ] `findFirstByEmailOrderByCreatedAtDesc`에 `@Lock(LockModeType.PESSIMISTIC_WRITE)`와 이유 주석을 추가한다 (`PasswordResetVerificationRepository`의 주석 형식을 따른다). **`EmailVerificationService` 본문은 한 줄도 바꾸지 않는다.**
- [ ] 같은 테스트를 재실행해 통과를 확인하고, 생성 SQL에 `... for update`가 붙는지 로그로 확인한다.
- [ ] `EmailVerificationServiceTest`·`EmailVerificationControllerTest`·`EmailVerificationTransactionIntegrationTest`·`EmailVerificationRepositoryTest`·`SignupIntegrationTest`를 돌려 400·429·5회 초과 즉시 만료·실패 시 증가 커밋 계약이 그대로임을 확인한다.
- [ ] 두 실행의 명령과 실측값을 `run-log.md`에 남긴다.

## Task 2: 이메일 변경 확인 경로 원자성 — `EmailChangeVerificationRepository`에 행 잠금 + 기존 호출부 교정

- [x] D7의 시나리오 1·2·3 동시성 테스트를 **먼저** 작성하고 `@Lock` 없이 실행해 **실패를 실측한다.** ~~`EmailChangeConfirmIntegrationTest`에 추가, `authService.confirmEmailChange` 직접 호출, Bearer 불필요~~ → 신규 `EmailChangeConcurrencyIntegrationTest` + MockMvc + Bearer로 변경(사유는 위 File Map 참고).
- [ ] `findFirstByUserIdAndNewEmailOrderByCreatedAtDesc`에 `@Lock(LockModeType.PESSIMISTIC_WRITE)`와 이유 주석을 추가한다. **`EmailChangeService`·`AuthService` 본문은 바꾸지 않으며, `validateAndConsumeCode`에 `@Transactional`을 새로 붙이지 않는다** (D2).
- [ ] 같은 커밋에서 D4의 호출부 6곳(152·164·185·201·247·287행)을 jdbcTemplate 기반 헬퍼 또는 쓰기 트랜잭션 안으로 옮긴다.
- [ ] 생성 SQL을 확인한다 — `... for update`가 붙는지, 그리고 **`users`로의 조인이 생기지 않는지**(D3). 조인이 생기면 `@Query`로 `v.user.id` 조건을 명시해 없앤 뒤 다시 확인한다.
- [ ] `EmailChangeServiceTest`·`EmailChangeControllerTest`·`EmailChangeConfirmIntegrationTest`·`EmailChangeIntegrationTest`·`EmailChangeVerificationRepositoryTest`를 돌려 400·409·429·재사용·만료·재발송 무효화·타인 격리·동시 이메일 경합 409 계약이 그대로임을 확인한다.
- [ ] 두 실행의 명령과 실측값, SQL 확인 결과를 `run-log.md`에 남긴다.

## Task 3: 정책 상수·`generateCode`·`hmac` 공통화 (시크릿 분리 보존)

- [ ] `VerificationCodeHasher`(생성자에서 시크릿을 받는 final 클래스)와 `VerificationCodePolicy`(`@Component`, 상수 5개 + `generateCode`·`expiresAt`·`isAttemptLimitReached`)를 만든다 (D5). 새 파일 첫 줄에 역할을 적은 한국어 주석을 넣는다.
- [ ] `VerificationCodePolicyTest`·`VerificationCodeHasherTest`를 실패 테스트로 먼저 고정한다 — 6자리 형식, `isAttemptLimitReached` 경계(4/5/6), **같은 코드라도 시크릿이 다르면 해시가 다름**, 기존 구현과 바이트 단위로 같은 해시가 나오는 고정값 1건.
- [ ] 세 서비스에서 상수 5개·`HMAC_ALGORITHM`·`hmacKey`·`SecureRandom`·`generateCode`·`hmac`을 제거하고 공통 컴포넌트로 치환한다. **각 서비스의 `@Value` 생성자 파라미터는 그대로 두어 시크릿 소유 관계를 바꾸지 않는다** (`EmailVerificationService`·`EmailChangeService` → `EMAIL_VERIFICATION_SECRET`, `PasswordResetService` → `PASSWORD_RESET_SECRET`).
- [ ] `SIGNUP_TOKEN_*`·`generateSignupVerificationToken`·`sha256`·`NOT_FOUND_MESSAGE`는 각 서비스에 남긴다 (공통화 대상 아님).
- [ ] 한쪽 시크릿으로 만든 인증번호가 다른 쪽 확인 경로에서 통하지 않는 가드 테스트를 추가한다 (D8).
- [ ] 세 도메인의 기존 회귀 스위트 전체와 Task 1·2의 동시성 테스트를 다시 돌려 **상태 코드·횟수·순서 기대값을 하나도 고치지 않고** 통과함을 확인한다.

## Task 4: `checkSendRateLimit`·`expirePreviousCodes` 공통화

- [ ] `VerificationCodePolicy.checkSendRateLimit(now, ToLongFunction<LocalDateTime>)`을 추가하고, **60초 `> 0` → 1시간 `>= HOURLY_LIMIT` → 하루 `>= DAILY_LIMIT` 순서와 비교 연산자를 그대로 옮긴다** (D5). 세 창의 경계값 테스트를 먼저 고정한다.
- [ ] 세 서비스의 `checkSendRateLimit`을 제거하고 각자의 집계 쿼리를 람다로 넘기도록 바꾼다(`countByEmailAndCreatedAtAfter` / `countByUserIdAndCreatedAtAfter`).
- [ ] `ExpirableVerification` 인터페이스와 `VerificationCodePolicy.expireAll(rows, now)`을 추가하고 세 엔티티에 `implements`만 붙인다. **조회 쿼리는 각 서비스에 그대로 남긴다** — 세 쿼리의 조건이 서로 다른 것이 의도된 차이다 (D6).
- [ ] 발송 제한 429 경계(60초 직전/직후, 1시간 5회째, 하루 10회째)와 재발송 시 이전 코드 무효화가 세 도메인 모두에서 그대로인지 기존 테스트로 확인한다.
- [ ] D6 항목(`expirePreviousCodes` 공통화)이 책임을 숨긴다고 판단되면 **이 항목만 빼고** 나머지를 완료한 뒤 그 판단을 보고에 남긴다.

## Task 5: 전체 회귀·빌드·문서 동기화

- [ ] `./gradlew build`(Spotless·SpotBugs·JaCoCo 포함)를 실제로 실행해 통과시킨다.
- [ ] Task 1·2의 동시성 테스트를 공통화 이후 상태에서 다시 돌려 통과를 확인한다.
- [ ] `ai/specs/002-auth-account/run-log.md`에 이번 이슈 절을 추가한다 — **수정 전 실패 실측값과 수정 후 통과 결과를 두 경로 각각에 대해**, 생성 SQL 확인 결과, 공통화 전후 회귀 결과.
- [ ] `ai/prd.md` `AUTH-004`(249·251·255행)·`AUTH-005`(278행)·`AUTH-006`(297-298행)의 정책 서술과 `VerificationCodePolicy` 상수값을 하나씩 대조한다. **어긋나는 것이 있으면 고치지 말고 보고한다.**
- [ ] `ai/specs/002-auth-account/tasks.md`의 Issue #121 절을 체크한다.
- [ ] `ai/api-routes.md`·`docs/api-contracts.md`를 **건드리지 않았음**을 확인한다 (컨트롤러 변경 없음).

---

## 완료 체크리스트

- [ ] 두 경로 각각에서 동시성 테스트가 **수정 전 실패 → 수정 후 통과**로 확인됐고, 두 실행의 실측값이 `run-log.md`에 남았다.
- [ ] 같은 인증번호에 동시 5건을 보내면 `attempt_count`가 정확히 5이고, 6건을 보내면 429가 1건 나오며 인증번호가 즉시 무효화된다 — 가입 인증·이메일 변경 **양쪽 모두**.
- [ ] 무효화 이후에는 정답을 넣어도 거부된다.
- [ ] 세 서비스가 모두 `@Lock(PESSIMISTIC_WRITE)` 방식으로 통일됐고 구현이 갈리지 않는다.
- [ ] 두 조회 쿼리의 생성 SQL에 `... for update`가 붙고, `email_change_verifications` 조회가 `users`를 함께 잠그지 않음이 확인됐다.
- [ ] 400·409·429 매핑과 5회 초과 시 즉시 만료, 실패 시 `attempt_count` 증가 커밋이 세 도메인에서 그대로다 — **기존 테스트의 기대값을 하나도 고치지 않고** 통과한다.
- [ ] 발송 제한 3창(60초·1시간 5회·하루 10회)의 경계 동작과 재발송 시 이전 코드 무효화가 그대로다.
- [ ] `EMAIL_VERIFICATION_SECRET`과 `PASSWORD_RESET_SECRET`이 여전히 분리돼 있고, 한쪽 코드가 다른 쪽 경로에서 통하지 않음이 테스트로 고정됐다.
- [ ] 정책 상수·`generateCode`·`hmac`·`checkSendRateLimit`(+ 선택적으로 `expirePreviousCodes`)의 3중 복제가 사라졌다.
- [ ] 신규 Flyway 마이그레이션·신규 `ErrorCode`·컨트롤러 변경이 없다.
- [ ] `ai/api-routes.md`·`docs/api-contracts.md`가 수정되지 않았다.
- [ ] `./gradlew build`가 통과한다.

---

## 후속 검토 (PR #145 리뷰 참고 2)

`VerificationCodePolicy`가 판정(`isAttemptLimitReached`, boolean 반환)과 강제(`checkSendRateLimit`, `BusinessException` throw) 두 스타일을 섞는다. 이번 PR에서 정리하지 않은 이유는 예외 발생 지점이 옮겨져 "동작 불변" 조건을 위협하기 때문이다. 정리한다면 이름으로 구분하는 선(`is~` / `require~`)이 적절하다. 리뷰어도 보류 판단이 타당하다고 확인했다.

## 미확정·PRD 불일치 (임의로 정하지 않고 보고하는 항목)

**방식(`@Lock`), 범위(두 서비스), 공통화 대상 4종, 시크릿 분리 보존은 착수 전 확정된 결정이며 여기 목록에 포함하지 않는다.**

**U1. 이슈 본문과 코멘트가 서로 다른 방식을 지시한다 — 코멘트가 최신이다.** 본문 "구현 범위" 첫 항목은 조건부 `UPDATE ... WHERE attempt_count < :limit` + 영향 행 수 판정을 예시로 들고 `RefreshTokenRepository.revokeIfActiveAndNotExpired` 등 기존 선례를 근거로 제시한다. 하지만 범위 축소 코멘트는 그 방식을 **검토 후 기각**하고 `@Lock`으로 확정했다. 이 계획은 코멘트를 따른다. 본문이 그대로 남아 있어 리뷰어가 "왜 기존 조건부 UPDATE 관용구를 안 썼나"를 물을 수 있으므로, PR 본문에 기각 근거(증가 전 값 기준 한도 판정 + 성공 경로만 미증가라는 비대칭)를 다시 적는다.

**U2. "용도별 시크릿 분리"는 실제로는 3분할이 아니라 2분할이다.** `EmailVerificationService`(가입 인증)와 `EmailChangeService`(이메일 변경)가 **둘 다 `EMAIL_VERIFICATION_SECRET`을 쓰고**, `PasswordResetService`만 `PASSWORD_RESET_SECRET`을 쓴다. PRD 255행은 "인증번호는 전용 시크릿 기반 HMAC-SHA-256"이라고만 적어 이 공유를 명시하지도, 금지하지도 않는다. 이번 공통화는 **현 상태를 그대로 보존**한다(새 클래스가 시크릿을 바꾸지 않는다). 세 번째 시크릿(`EMAIL_CHANGE_SECRET`)을 신설할지는 별도 판단이 필요하며, 하려면 환경변수·배포 문서·기존 저장 해시 마이그레이션이 딸려 오므로 이번 범위 밖으로 둔다.

**U3. `expirePreviousCodes` 공통화의 실익이 가장 얕다 (D6).** 세 서비스에서 실제로 같은 코드는 `for` 루프 세 줄이고, 조회 조건은 셋 다 다르며 그 차이가 의도된 것이다(`verified_at` vs `consumed_at` vs 거부 행 제외). 공통화하려면 엔티티 3개에 인터페이스를 붙여야 하는데, 이는 `docs/conventions.md`의 "공통화가 책임을 명확하게 만들었는가, 아니면 숨겼는가?"에 걸릴 수 있다. 이슈가 명시적으로 나열한 항목이라 계획에 포함했으나, **리뷰에서 과한 추상화로 판정되면 이 항목만 빼도 나머지 결과에 영향이 없다.**

**U4. `checkSendRateLimit`을 함수 인자로 공통화하는 것이 가독성을 해칠 수 있다.** 세 창의 판정 로직은 동일하지만 집계 키가 이메일/회원으로 갈려 `ToLongFunction<LocalDateTime>` 람다를 넘기는 형태가 된다. 대안은 상수만 공통화하고 메서드 3벌은 그대로 두는 것이다. 이 계획은 이슈가 `checkSendRateLimit`을 명시적으로 나열했으므로 공통화하는 쪽을 택했다. 람다 전달이 오히려 읽기 어렵다는 판단이 서면 Task 4에서 상수 공통화까지만 하고 보고한다.

**U5. 잠금 대기가 길어질 때의 동작을 PRD·이슈 어느 쪽도 정하지 않았다.** 확인 경로에는 요청 횟수 제한이 없으므로(#116 U4) 대량 동시 버스트가 오면 요청들이 행 잠금 앞에 줄을 선다. MySQL `innodb_lock_wait_timeout`(기본 50초)을 넘기면 예외가 나고 500이 된다. 실제로는 앞선 요청들이 곧 한도에 도달해 인증번호를 무효화하므로 뒤 요청들은 빠르게 400으로 끝나지만, "잠금 대기 타임아웃 시 어떤 응답을 줄 것인가"는 정해진 바가 없다. 이번 범위에서 재시도·타임아웃 튜닝을 넣지 않으며, 필요하면 별도 이슈다.

**U6. 세 서비스의 "잠글 행"을 고르는 기준이 완전히 같지는 않다.** `PasswordResetVerification`만 `code_hash IS NOT NULL` 필터가 있고(거부 행이 섞이기 때문, #115 D6), `EmailVerification`·`EmailChangeVerification`에는 거부 행 개념이 없어 필터가 없다. 즉 "같은 방식"은 **잠금 방식이 같다는 뜻이지 쿼리가 같아진다는 뜻이 아니다.** 수용 기준 2("구현이 갈리지 않는다")를 쿼리 문자열까지 통일하라는 뜻으로 읽지 않는다.

**U7. `hmac` 실패 시의 `IllegalStateException` 메시지가 공통화로 하나로 통일된다.** 현재는 서비스마다 문구가 다르다(`"인증번호 HMAC 계산에 실패했습니다."` / `"비밀번호 재설정 인증번호 HMAC 계산에 실패했습니다."`). 정상 경로에서 도달 불가능한 방어 분기이고 응답 계약이 아니므로 회귀로 보지 않았으나, 로그에서 어느 경로인지 구분하던 정보는 사라진다. 구분이 필요하다면 생성자에 용도 라벨을 하나 더 받는 방법이 있다.
