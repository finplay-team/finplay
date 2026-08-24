# Issue #8 내 정보 조회 API 구현 계획

> **For agentic workers:** 각 Task는 실패 테스트를 먼저 작성한 뒤 구현한다. Step은 체크박스(`- [ ]`)로 추적한다.

**Goal:** Access Bearer 인증 사용자가 `GET /api/auth/me`를 호출하면 본인의 `id`·`email`·`nickname`·가입 방식(`signupMethod`: EMAIL·KAKAO·NAVER)을 반환하고, 비밀번호 해시 등 민감정보는 노출하지 않는다. 인증 누락·만료는 401이며, 경로·쿼리에 대상 식별자가 없어 다른 회원 정보는 구조적으로 조회할 수 없다.

**관련 정본:** GitHub Issue #8, PRD `AUTH-005`, `spec.md`, `plan.md`(API 설계 표에 `GET /api/auth/me` 행 기존 존재), ADR-0002, `docs/conventions.md`

**선행:** Issue #5 병합 완료. 현재 `origin/dev`에는 `JwtTokenProvider.parseAccessToken`, `JwtAuthenticationFilter`, `SecurityConfig`, `AuthenticatedUser(userId, role)`가 있고 `AuthController`가 `@AuthenticationPrincipal AuthenticatedUser`를 로그아웃(#7 구현 완료)에서 이미 쓰고 있다. `AuthService`는 이미 `SocialAccountRepository`를 생성자 필드로 갖고 있다(`oauthLogin`에서 사용).

**Architecture:** 기존 `AuthController → AuthService → UserRepository`/`SocialAccountRepository` 흐름만 확장한다. Controller는 `@AuthenticationPrincipal AuthenticatedUser`에서 `userId`를 꺼내 서비스에 전달하고 `MemberResponse`를 그대로 반환한다. `AuthService`는 새 읽기 전용 메서드 하나로 `UserRepository.findById`로 회원을 조회하고, `SocialAccountRepository.findByUserId`로 소셜 계정 존재 여부를 확인해 가입 방식을 판별한 뒤 `MemberResponse.from(user, signupMethod)`로 변환한다. 새 컨트롤러·Security 컴포넌트는 만들지 않는다.

**Tech Stack:** Java 17, Spring Boot 4.1, Spring Security, Spring Data JPA, MySQL 8.4 Testcontainers, JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

---

## 요구사항 ID와 수용 기준

### 대상 요구사항

- PRD `AUTH-005 내 정보 조회·수정` (조회 부분만) — **정본 기준**
  - 조회는 인증 사용자의 `id`, `email`, `nickname`, **가입 방식**만 반환하고 비밀번호 해시·OAuth 제공자 식별자를 노출하지 않는다.
- PRD §5 공통 오류표
  - 인증 없음·만료는 401 `UNAUTHORIZED`.
- `plan.md` API 설계 표
  - `GET /api/auth/me` → `MemberResponse (id, email, nickname)`. 인증 필요. **표기에 가입 방식이 누락돼 있어 이번 계획에서 `plan.md` 표만 최소 갱신한다** (아래 "PRD·Issue 정합성 메모" 참조).
- GitHub Issue #8
  - 인증 주체의 id·email·nickname을 반환하고 민감정보는 숨긴다. **원문에도 가입 방식이 누락돼 있으나 PRD가 상위 문서이므로 PRD를 따른다.**
  - 토큰 소유자 정보만 반환, 인증 누락·만료는 401.
  - 정상 조회·401·타인 격리·민감 필드 미노출 테스트 필요.

### 수용 시나리오

1. Given 사용자 A가 로그인해 유효한 Access Token을 가진 상태
2. When A의 Access Bearer로 `GET /api/auth/me`
3. Then 200과 함께 A의 `id`, `email`, `nickname`, `signupMethod`만 담긴 본문을 반환한다 (다른 필드 없음).
4. Given 사용자 A, B가 각각 로그인해 서로 다른 Access Token을 가진 상태
5. When 각자의 Bearer로 `GET /api/auth/me`를 호출하면
6. Then A는 A의 정보만, B는 B의 정보만 받는다 — 경로·쿼리에 대상 식별자가 없으므로 요청자 본인 외 조회 자체가 불가능하다.
7. And Access Bearer가 없거나 만료·변조됐거나 Refresh Token을 Bearer로 제출하면 Security에서 401 `UNAUTHORIZED`로 거부하고 서비스는 호출되지 않는다.
8. And 응답 본문에 `passwordHash`, OAuth 제공자·`providerUserId` 등 민감 필드는 어떤 이름으로도 존재하지 않는다.
9. Given 이메일로 가입한 회원과 카카오·네이버로 가입한 회원이 각각 존재하는 상태
10. When 각자 `GET /api/auth/me`를 호출하면
11. Then 이메일 가입자는 `signupMethod: "EMAIL"`을, 카카오·네이버 가입자는 각각 `"KAKAO"`·`"NAVER"`를 받는다.

---

## 범위와 제외

### 포함

- `GET /api/auth/me`
- `Authorization: Bearer <accessToken>` 필수 인증 (기존 `SecurityConfig` 기본 보호 재사용, 화이트리스트 추가 없음)
- `MemberResponse(id, email, nickname, signupMethod)` 응답 DTO 신설
- `SignupMethod` enum(EMAIL·KAKAO·NAVER) 신설
- `SocialAccountRepository`에 `findByUserId` 조회 메서드 추가
- `AuthService`에 읽기 전용 조회 메서드 1개 추가 — 회원 조회 + 가입 방식 판별
- 정상 200, 공통 401 오류 형식
- Controller·Service 단위/슬라이스 테스트와 실제 MySQL 통합 테스트(타인 격리·가입 방식별 값 포함)
- 구현 뒤 `ai/api-routes.md`·`tasks.md` 동기화, `plan.md` API 설계 표의 `GET /api/auth/me` 응답 칸 최소 갱신

### 제외

- 내 정보 수정(닉네임·이메일 변경, 현재 비밀번호·`reauthToken` 검증) — AUTH-005의 별도 후속 Issue.
- `UserQueryService` 확장·재사용 — D1 그대로 유지, 회원 조회는 `AuthService` 자신의 `UserRepository`로 한다.
- 소셜 계정 다중 연결(한 회원이 여러 provider에 연결되는 경우) 판별 — 명시적 연결 기능이 PRD 1차 범위 밖이라 한 회원당 `SocialAccount`는 최대 1건이라는 현재 가입 흐름의 전제를 그대로 쓴다.
- Security 화이트리스트 변경, 새 필터·엔트리포인트.
- 회원 탈퇴, 프로필 이미지, 파일 업로드, 계좌·주문·투자일기 등 다른 도메인 데이터.

### PRD·Issue 정합성 메모 (사용자 확인 완료 — 가입 방식 포함으로 확정)

- `spec.md` AUTH-005는 "조회는 ... `id`, `email`, `nickname`, **가입 방식**만 반환"이라고 명시한다. PRD가 이 spec의 상위 정본이다.
- `plan.md`의 기존 API 설계 표와 GitHub Issue #8 원문은 `id`, `email`, `nickname`만 적혀 있어 가입 방식이 빠져 있다 — 사용자 확인 결과 이 두 문서 쪽의 누락으로 판단했다.
- 따라서 이 계획은 **PRD·spec.md를 따라 가입 방식(`signupMethod`: EMAIL·KAKAO·NAVER)을 응답에 포함한다.** `plan.md` 표는 이 계획에서 응답 칸만 최소 갱신하고 다른 내용은 건드리지 않는다(오케스트레이터 지시).
- `User` 엔티티에는 가입 방식 전용 컬럼이 없으므로 판별 로직이 필요하다 — D3 참조.

---

## 설계 결정

### D1. 회원 조회는 `AuthService`가 자신의 `UserRepository`로 직접 수행한다 (`UserQueryService` 재사용 안 함)

`UserQueryService.getUser(Long)`(`src/main/java/com/finplay/api/auth/service/UserQueryService.java`)가 이미 존재하고 동일한 "없으면 401" 시맨틱을 갖고 있지만, 파일 첫 줄 주석이 "**다른 도메인**에 인증 사용자 엔티티 조회 경계를 제공하는 서비스"라고 명시한다. 즉 이 서비스는 `community` 같은 외부 도메인이 auth의 `UserRepository`를 직접 참조하지 않도록 만든 경계이지, auth 도메인 내부용이 아니다.

`AuthService`는 이미 `UserRepository`를 필드로 갖고 있고 `login()`이 `userRepository.findByEmail(...).orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED))`와 동일한 패턴을 쓴다. 새 메서드도 같은 클래스, 같은 패턴으로 `userRepository.findById(userId).orElseThrow(...)`를 쓴다.

- 근거 — `AuthService`에 `UserQueryService`를 새 생성자 의존성으로 추가하면 (a) 이미 있는 auth 자신의 리포지토리를 두고 경계 서비스를 한 단계 더 거치는 불필요한 간접화이고, (b) `AuthServiceTest`의 모든 기존 테스트가 새 mock 의존성을 준비해야 해 이번 변경 범위를 넘는 파급이 생긴다. conventions의 "공통화는 세 번째 중복이 보이고 책임이 명확할 때만 검토"에 따라 지금은 추출하지 않는다.
- `UserQueryService`는 이번 Issue에서 수정하지 않는다.

### D2. 응답은 `MemberResponse(Long id, String email, String nickname, SignupMethod signupMethod)` record다

- 위치: `src/main/java/com/finplay/api/auth/dto/response/MemberResponse.java`. conventions의 "단건 응답 → `~Response`" 규칙과 `plan.md`의 기존 표기를 그대로 따른다.
- 정적 팩토리 `MemberResponse.from(User user, SignupMethod signupMethod)`를 둔다(conventions — 엔티티 매핑이 있는 응답 DTO 규칙). 가입 방식은 `User` 엔티티 자체에 없는 파생 값이라 팩토리 인자로 받는다 — `User`에서 직접 계산하지 않는다(엔티티는 다른 도메인 리포지토리를 모른다).
- `passwordHash`, `role`, `status`, `createdAt`, `updatedAt`, OAuth `providerUserId` 등 다른 필드는 절대 담지 않는다. 필드 4개 고정.

### D3. 가입 방식 판별은 `SocialAccountRepository` 조회로 하고, `passwordHash` sentinel 비교는 쓰지 않는다

`User` 엔티티에는 가입 방식을 나타내는 전용 컬럼이 없다. 판별 방법은 두 가지가 있었다.

1. `AuthService.OAUTH_ONLY_PASSWORD_SENTINEL`(`"{oauth-only}"`)과 `user.getPasswordHash()`를 비교한다.
2. 해당 `userId`로 `SocialAccountRepository`를 조회해 행이 있으면 그 `provider`, 없으면 EMAIL로 판단한다.

**2번을 채택한다.** 근거.

- `OAUTH_ONLY_PASSWORD_SENTINEL`은 `AuthService.createOAuthUser`가 회원가입 시 `passwordHash` 컬럼을 NOT NULL로 채우기 위해 쓰는 내부 구현 상수일 뿐, "이 회원이 소셜 가입자다"를 표현하려는 의도로 만든 값이 아니다. 문자열 상수를 다른 목적(가입 방식 판별)으로 재사용하면 두 관심사가 몰래 결합되어, 나중에 이메일 회원의 비밀번호 재설정·정책 변경으로 sentinel 값 처리 방식이 바뀌면 가입 방식 판별까지 조용히 깨질 수 있다.
- `SocialAccount(provider, providerUserId)` 존재 자체가 AUTH-003이 정의하는 "이 회원이 어떤 소셜 계정에 연결됐는가"의 유일한 정본 데이터다. 정본 테이블을 직접 조회하는 편이 파생 sentinel 비교보다 더 명확하고 안전하다.
- `SocialAccountRepository`는 이미 `AuthService`의 생성자 필드로 주입돼 있어(`oauthLogin`에서 사용) 새 의존성 추가가 필요 없다.

구현.

```java
// SocialAccountRepository에 추가
Optional<SocialAccount> findByUserId(Long userId);
```

```java
// AuthService에 추가
@Transactional(readOnly = true)
public MemberResponse getMe(Long userId) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    SignupMethod signupMethod = socialAccountRepository.findByUserId(userId)
        .map(socialAccount -> SignupMethod.fromProvider(socialAccount.getProvider()))
        .orElse(SignupMethod.EMAIL);
    return MemberResponse.from(user, signupMethod);
}
```

- **전제(제외 항목에도 명시):** 한 회원당 `SocialAccount`는 최대 1건이다 — 현재 가입 흐름(이메일 가입 또는 OAuth 신규 가입 중 하나만 발생, 명시적 소셜 연결 기능 없음)에서는 한 회원에게 두 번째 `SocialAccount`가 생성될 경로가 없다. 따라서 `findByUserId`가 단건 조회로 충분하다. 이 전제가 깨지는 기능(다중 소셜 연결)이 추가되면 이 조회는 재설계가 필요하다 — 이번 Issue 범위 밖.

### D4. `SignupMethod` enum은 `auth/domain`에 두고 `OAuthProviderName`과는 별개 타입으로 유지한다

- 위치: `src/main/java/com/finplay/api/auth/domain/SignupMethod.java`. `User`와 같은 패키지에 둔다 — 회원의 가입 경로를 나타내는 도메인 개념이지만 별도 컬럼으로 영속하지 않는 파생 값이다(D3).
- 값: `EMAIL`, `KAKAO`, `NAVER`.
- `OAuthProviderName`(`KAKAO`, `NAVER`만 존재, `auth/oauth` 패키지)과 통합하지 않는다 — `OAuthProviderName`은 `/api/auth/oauth/{provider}/...` 경로 변수, `OAuthProvider`/`OAuthAuthorizationProvider` 어댑터 계약 등 **OAuth 흐름 전용** 타입이다. 여기에 `EMAIL`을 추가하면 "이메일도 OAuth provider 중 하나"라는 잘못된 의미가 생기고, provider 경로 파싱(`OAuthProviderName.from(String)`)이 `EMAIL`을 유효한 경로 값으로 오인할 위험이 생긴다. 응답 전용의 상위집합 enum을 분리하는 편이 안전하다.
- 변환은 `SignupMethod.fromProvider(OAuthProviderName provider)` 정적 메서드 하나로 한정한다.

```java
public enum SignupMethod {
    EMAIL,
    KAKAO,
    NAVER;

    public static SignupMethod fromProvider(OAuthProviderName provider) {
        return switch (provider) {
            case KAKAO -> KAKAO;
            case NAVER -> NAVER;
        };
    }
}
```

### D5. 조회 메서드는 읽기 전용 트랜잭션이다

`getMe`는 `@Transactional(readOnly = true)`로 D3의 구현 그대로다. `readOnly = true`는 이 코드베이스에 이미 있는 패턴(`UserQueryService`, `CommunityPostService`)과 일치한다.

회원이 존재하지 않는 경우(현재 시스템에는 회원 탈퇴 기능이 없어 실제로는 도달하기 어려운 경로이지만, 유효 서명의 Access Token이 가리키는 사용자가 사라진 상태에 대한 방어)도 `login()`과 동일하게 `UNAUTHORIZED`로 응답한다. 원인별로 다른 코드를 만들지 않는다(계정 존재 여부를 노출하지 않는 기존 원칙과 동일선상).

### D6. Security 설정은 변경하지 않는다

`SecurityConfig`의 `PUBLIC_GET_PATHS`에 `/api/auth/me`를 추가하지 않는다. 화이트리스트에 없는 모든 경로는 이미 `anyRequest().authenticated()`로 보호되므로, Access Bearer 없음·만료·변조·`REFRESH` 타입은 자동으로 401이다. `JwtAuthenticationFilter`, `RestAuthenticationEntryPoint` 등 인증 인프라도 수정하지 않는다.

### D7. HTTP 계약

| 항목 | 내용 |
|---|---|
| Method / Path | `GET /api/auth/me` |
| 인증 | `Authorization: Bearer <유효한 Access JWT>` |
| 요청 본문 | 없음 |
| 성공 | 200 `{"id":42,"email":"user@finplay.com","nickname":"finplayer","signupMethod":"EMAIL"}` |
| Access Token 없음·만료·변조·잘못된 타입 | 401 `UNAUTHORIZED` 공통 포맷 |

`ResponseEntity.ok(...)`로 200을 반환한다. conventions의 "조회·수정 200" 기준과 일치한다. `signupMethod`는 Jackson 기본 직렬화로 enum 이름 문자열(`"EMAIL"`/`"KAKAO"`/`"NAVER"`)이 된다.

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/auth/domain/SignupMethod.java` — EMAIL·KAKAO·NAVER enum (D4).
- `src/main/java/com/finplay/api/auth/dto/response/MemberResponse.java`

### Production files to modify

- `src/main/java/com/finplay/api/auth/repository/SocialAccountRepository.java` — `findByUserId(Long userId)` 추가.
- `src/main/java/com/finplay/api/auth/service/AuthService.java` — `getMe(Long userId)` 추가. 기존 `socialAccountRepository` 필드를 재사용하고 새 생성자 인자는 추가하지 않는다.
- `src/main/java/com/finplay/api/auth/controller/AuthController.java` — `@AuthenticationPrincipal AuthenticatedUser`를 받는 `GET /me` 추가.

### Test files to modify

- `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java`
- `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java`

### Test files to create

- `src/test/java/com/finplay/api/auth/service/MeIntegrationTest.java` — 실제 MySQL에서 이메일·소셜 회원 가입 → 각자 로그인 → 각자 `GET /me` 결과가 서로 격리되고 가입 방식이 정확함과 민감 필드 미노출 검증.

### Documentation files to modify after implementation

- `ai/api-routes.md` — 실제 Controller 매핑 기준 `GET /api/auth/me` 상세 계약(응답에 `signupMethod` 포함) 추가.
- `ai/specs/002-auth-account/plan.md` — API 설계 표의 `GET /api/auth/me` 응답 칸을 `MemberResponse (id, email, nickname, signupMethod)`로 최소 갱신한다(그 외 내용은 건드리지 않는다).
- `ai/specs/002-auth-account/tasks.md` — "JWT·Security" 항목의 "`GET /api/auth/me` 잔여(Issue #8)" 메모를 완료로 갱신한다. 다른 잔여 작업(OAuth 어댑터, 통합 테스트 나머지 시나리오)은 그대로 둔다.
- `ai/specs/002-auth-account/run-log.md` — implementer·reviewer가 실제 실행한 명령과 검증 수준만 기록한다.

### 만들거나 수정하지 않을 파일

- `src/main/java/com/finplay/api/auth/service/UserQueryService.java` (D1)
- `src/main/java/com/finplay/api/auth/repository/UserRepository.java` — `JpaRepository.findById`로 충분, 새 쿼리 메서드 불필요.
- `src/main/java/com/finplay/api/auth/oauth/OAuthProviderName.java` — `EMAIL`을 추가하지 않는다(D4).
- `src/main/java/com/finplay/api/auth/config/SecurityConfig.java` (D6)
- `src/main/java/com/finplay/api/auth/domain/User.java` — 가입 방식 컬럼을 추가하지 않는다.
- 새 `V{N}__*.sql` — 스키마 변경 없음. `signupMethod`는 저장하지 않고 매 조회 시 파생한다.

---

## Task 1: 가입 방식 판별과 내 정보 조회 서비스

**Files**

- Create: `src/main/java/com/finplay/api/auth/domain/SignupMethod.java`
- Create: `src/main/java/com/finplay/api/auth/dto/response/MemberResponse.java`
- Modify: `src/main/java/com/finplay/api/auth/repository/SocialAccountRepository.java`
- Modify: `src/main/java/com/finplay/api/auth/service/AuthService.java`
- Modify: `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java`

**Interfaces**

- Produces: `enum SignupMethod { EMAIL, KAKAO, NAVER }` + `static SignupMethod fromProvider(OAuthProviderName provider)`
- Produces: `record MemberResponse(Long id, String email, String nickname, SignupMethod signupMethod)` + `static MemberResponse from(User user, SignupMethod signupMethod)`
- Produces: `Optional<SocialAccount> SocialAccountRepository.findByUserId(Long userId)`
- Produces: `MemberResponse AuthService.getMe(Long userId)`

- [ ] **Step 1: 서비스 실패 테스트를 작성한다**
  - `getMeReturnsMemberResponseWithSignupMethodEmailWhenNoSocialAccountExists` — `userRepository.findById`가 회원을 반환하고 `socialAccountRepository.findByUserId`가 빈 `Optional`이면 `signupMethod`가 `EMAIL`인 `MemberResponse`를 반환한다. `id`·`email`·`nickname`도 정확히 일치해야 한다.
  - `getMeReturnsMemberResponseWithSignupMethodKakaoWhenKakaoSocialAccountExists` — `socialAccountRepository.findByUserId`가 `provider=KAKAO`인 `SocialAccount`를 반환하면 `signupMethod`가 `KAKAO`다.
  - `getMeReturnsMemberResponseWithSignupMethodNaverWhenNaverSocialAccountExists` — 동일하게 `NAVER` 검증.
  - `getMeFailsWithUnauthorizedWhenUserNotFound` — `userRepository.findById`가 빈 `Optional`이면 `BusinessException(ErrorCode.UNAUTHORIZED)`이고 `socialAccountRepository`는 호출되지 않는다.
  - `SignupMethodTest`(같은 파일에 간단히 추가하거나 별도 클래스로) — `fromProvider(KAKAO)`·`fromProvider(NAVER)`가 각각 대응 값을 반환한다.
- [ ] **Step 2: 대상 테스트 실패를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*AuthServiceTest" --no-daemon --max-workers=1
  ```

  Expected: `getMe`·`SignupMethod`·`findByUserId` 부재로 컴파일 또는 새 테스트 FAIL.

- [ ] **Step 3: 최소 구현을 추가한다**
  - `SignupMethod`는 D4대로 `auth/domain` 패키지에 3개 값과 `fromProvider` 정적 메서드만 둔다.
  - `SocialAccountRepository.findByUserId(Long userId)`는 파생 쿼리 메서드 한 줄로 추가한다(D3의 "한 회원당 최대 1건" 전제).
  - `MemberResponse`는 record + `from(User, SignupMethod)` 정적 팩토리만 둔다(D2).
  - `AuthService.getMe`는 D3의 구현 그대로, 기존 `userRepository`·`socialAccountRepository` 필드만 사용한다. 새 생성자 인자를 추가하지 않는다(D1).
- [ ] **Step 4: 같은 대상 테스트를 다시 실행해 PASS를 확인한다**
- [ ] **Step 5: 논리 커밋한다**

  ```powershell
  git add src/main/java/com/finplay/api/auth/domain/SignupMethod.java src/main/java/com/finplay/api/auth/dto/response/MemberResponse.java src/main/java/com/finplay/api/auth/repository/SocialAccountRepository.java src/main/java/com/finplay/api/auth/service/AuthService.java src/test/java/com/finplay/api/auth/service/AuthServiceTest.java
  git commit -m "feat: 내 정보 조회와 가입 방식 판별 서비스 추가"
  ```

---

## Task 2: 보호된 GET /api/auth/me HTTP 계약

**Files**

- Modify: `src/main/java/com/finplay/api/auth/controller/AuthController.java`
- Modify: `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java`

**Interfaces**

- Consumes: Access Bearer의 `AuthenticatedUser`
- Produces: 200 `MemberResponse`

- [ ] **Step 1: WebMvc 실패 테스트를 작성한다**

  기존 `logoutReturnsNoContentAndPassesPrincipalUserIdAndRawRefreshToken`이 쓰는 `stubValidAccessToken()` 패턴을 재사용한다.

  - `meReturnsOkWithIdEmailNicknameSignupMethodForAuthenticatedUser` — 유효 Access Bearer면 `authService.getMe(USER_ID)`가 반환한 `MemberResponse`를 그대로 200으로 반환한다. `jsonPath`로 `id`·`email`·`nickname`·`signupMethod` 값을 단언하고, 응답 JSON에 `passwordHash`·`role`·`status` 등 다른 키가 없는지 `content()` 문자열이나 전체 JSON 키 집합으로 확인한다.
  - `meReturnsSignupMethodKakaoAndNaverForSocialMembers` — `authService.getMe(USER_ID)`가 `signupMethod=KAKAO`·`signupMethod=NAVER`인 `MemberResponse`를 각각 반환하도록 stubbing해 `jsonPath("$.signupMethod")`가 `"KAKAO"`·`"NAVER"` 문자열로 직렬화됨을 확인한다.
  - `meRejectsMissingAccessTokenWithoutCallingService` — Access Bearer가 없으면 401 공통 오류 형식이며 서비스는 호출되지 않는다.
  - `meRejectsRefreshBearerWithoutCallingService` — Refresh Token을 Bearer로 제출하면 401이고 서비스는 호출되지 않는다(logout 테스트의 `jwtTokenProvider.parseAccessToken(...).thenReturn(Optional.empty())` 패턴 재사용).
  - `meMapsServiceUnauthorizedToCommonErrorFormat` — 서비스가 `BusinessException(UNAUTHORIZED)`를 던지면(Access Token은 유효하지만 대상 회원이 존재하지 않는 방어적 경로) 401 공통 오류 형식으로 응답한다.

- [ ] **Step 2: 대상 테스트 실패를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*AuthControllerTest" --no-daemon --max-workers=1
  ```

  Expected: `GET /me` 매핑 부재로 404 또는 새 검증 FAIL.

- [ ] **Step 3: Controller 매핑만 구현한다**
  - `@GetMapping("/me")`에 `@AuthenticationPrincipal AuthenticatedUser principal`을 받는다.
  - `authService.getMe(principal.userId())` 호출 뒤 `ResponseEntity.ok(...)`로 200을 반환한다.
  - `SecurityConfig`는 수정하지 않는다(D6).
- [ ] **Step 4: 같은 대상 테스트를 다시 실행해 PASS를 확인한다**
- [ ] **Step 5: 논리 커밋한다**

  ```powershell
  git add src/main/java/com/finplay/api/auth/controller/AuthController.java src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java
  git commit -m "feat: 내 정보 조회 API 추가"
  ```

---

## Task 3: 실제 MySQL 조회·타인 격리 통합 검증

**Files**

- Create: `src/test/java/com/finplay/api/auth/service/MeIntegrationTest.java`

- [ ] **Step 1: 실제 MySQL 통합 테스트를 작성한다**

  기존 `LoginIntegrationTest`/`LogoutIntegrationTest`의 가입→로그인 준비 흐름(`FakeEmailSender`)과 Fake OAuth 신규 가입 흐름(Issue #10의 `FakeOAuthFlowIntegrationTest` 패턴)을 재사용한다.

  - `meReturnsOwnIdEmailNicknameAndSignupMethodEmailAfterEmailSignup` — 이메일 회원가입 직후 발급된 Access Token으로 `authService.getMe(userId)`를 호출하면 가입 시 입력한 이메일·닉네임과 저장된 `id`, 그리고 `signupMethod=EMAIL`이 정확히 일치한다.
  - `meReturnsSignupMethodKakaoAfterFakeKakaoSignup` / `meReturnsSignupMethodNaverAfterFakeNaverSignup` — Fake OAuth로 각 provider 신규 가입한 회원의 `getMe` 결과가 해당 `signupMethod`를 반환한다(실제 `SocialAccount` 행이 DB에 저장돼 있어야 판별 가능함을 실제 조회로 확인).
  - `meIsolatesDifferentUsersFromEachOther` — 이메일 회원 A와 소셜 회원 B를 각각 가입시키고 각자의 `userId`로 `getMe`를 호출하면 서로 다른 결과(다른 `id`·`email`·`nickname`·`signupMethod`)가 나오며 A의 결과에 B의 값이 섞이지 않는다(경로에 식별자가 없어 대상은 Access Token 주체로만 결정됨을 서비스 계층에서 재확인).
  - `meResponseNeverContainsPasswordHash` — 반환된 `MemberResponse`를 JSON 직렬화해 문자열에 저장된 `passwordHash` 값이 포함되지 않음을 확인한다(구조적으로는 필드가 없어 불가능하지만, DTO 필드 실수 추가에 대한 회귀 고정).
- [ ] **Step 2: 대상 테스트를 실행해 PASS를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*MeIntegrationTest" --no-daemon --max-workers=1
  ```

- [ ] **Step 3: 논리 커밋한다**

  ```powershell
  git add src/test/java/com/finplay/api/auth/service/MeIntegrationTest.java
  git commit -m "test: 내 정보 조회와 회원 간 격리 통합 검증"
  ```

---

## Task 4: API 문서 동기화와 전체 검증

**Files**

- Modify: `ai/api-routes.md`
- Modify: `ai/specs/002-auth-account/plan.md`
- Modify: `ai/specs/002-auth-account/tasks.md`
- Modify during feature workflow: `ai/specs/002-auth-account/run-log.md`

- [ ] **Step 1: 실제 Controller 매핑으로 API 문서를 동기화한다**
  - 라우트 목록에 `GET /api/auth/me`를 추가한다.
  - 상세 표에 Access Bearer 필수, 요청 본문 없음, 200 `MemberResponse(id, email, nickname, signupMethod)`, 401 공통 오류를 기록한다.
- [ ] **Step 2: `plan.md` API 설계 표를 최소 갱신한다**
  - `GET /api/auth/me` 행의 응답 칸을 `MemberResponse (id, email, nickname, signupMethod)`로 바꾼다.
  - 그 외 표의 다른 행·문구·`plan.md`의 다른 절은 건드리지 않는다.
- [ ] **Step 3: tasks 상태를 갱신한다**
  - "JWT·Security" 항목의 "`GET /api/auth/me` 잔여(Issue #8)" 메모를 완료로 바꾼다.
  - OAuth 어댑터, 통합 테스트 나머지 시나리오 등 다른 잔여 작업은 완료 처리하지 않는다.
- [ ] **Step 4: Spotless와 대상 테스트를 실행한다**

  ```powershell
  .\gradlew.bat spotlessApply
  .\gradlew.bat test --tests "*AuthServiceTest" --tests "*AuthControllerTest" --tests "*MeIntegrationTest" --no-daemon --max-workers=1
  ```

- [ ] **Step 5: 전체 게이트를 실행한다**

  ```powershell
  .\gradlew.bat build --no-daemon --max-workers=1
  ```

- [ ] **Step 6: 현재 diff와 라우트 일치를 확인한다**

  ```powershell
  git diff --check
  git diff -- src/main/java/com/finplay/api/auth/controller/AuthController.java ai/api-routes.md ai/specs/002-auth-account/plan.md
  git status --short
  ```

- [ ] **Step 7: 문서 변경을 논리 커밋한다**

  ```powershell
  git add ai/api-routes.md ai/specs/002-auth-account/plan.md ai/specs/002-auth-account/tasks.md ai/specs/002-auth-account/run-log.md
  git commit -m "docs: 내 정보 조회 API 계약과 가입 방식 응답 동기화"
  ```

---

## 미확정 사항

- **한 회원당 다중 소셜 연결이 생기는 경우의 판별 우선순위:** D3의 `findByUserId` 단건 조회는 현재 가입 흐름에서 한 회원에게 `SocialAccount`가 최대 1건만 생긴다는 전제에 의존한다. 향후 "기존 회원에 소셜 계정 추가 연결" 기능(현재 PRD 1차 명시적 제외)이 생기면 이 조회와 `signupMethod`의 의미(최초 가입 경로 vs 현재 연결된 모든 provider) 자체를 다시 정의해야 한다 — 이번 Issue 범위 밖.
- **회원 삭제/비활성 상태의 `GET /me` 응답:** 이번 Issue 범위에는 회원 탈퇴 기능이 없어 `status != ACTIVE`인 회원이 `GET /me`를 호출하는 시나리오 자체가 발생하지 않는다. 별도 분기를 만들지 않는다.

---

## 완료 체크리스트

- [ ] `GET /api/auth/me`는 유효한 Access Bearer를 요구한다.
- [ ] 정상 조회는 200과 함께 `id`·`email`·`nickname`·`signupMethod`만 반환한다(다른 필드 없음).
- [ ] 이메일 가입자는 `signupMethod=EMAIL`, 카카오·네이버 가입자는 각각 `KAKAO`·`NAVER`를 반환한다.
- [ ] 응답에 `passwordHash`·OAuth 제공자 식별자(`providerUserId`) 등 민감 필드가 어떤 이름으로도 없다.
- [ ] Access Token 없음·만료·변조·`REFRESH` 타입은 401 `UNAUTHORIZED` 공통 포맷이다.
- [ ] 서로 다른 두 회원(이메일·소셜 포함)이 각자의 Bearer로 조회하면 결과가 격리된다(실제 MySQL 통합 테스트로 확인).
- [ ] `SecurityConfig`·`UserQueryService`·`OAuthProviderName`·스키마를 변경하지 않았다.
- [ ] 실제 Controller 매핑과 `ai/api-routes.md`가 일치하고, `plan.md` API 설계 표의 응답 칸이 갱신됐다.
- [ ] 대상 테스트와 `.\gradlew.bat build --no-daemon --max-workers=1` 결과를 새로 확인한다.
