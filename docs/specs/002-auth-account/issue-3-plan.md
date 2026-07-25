# 이메일 인증번호 확인 API 구현 계획

> **에이전트 작업 필수:** 저장소 `feature` 워크플로와 `superpowers:test-driven-development`를 적용한다. tester가 실패 테스트를 먼저 작성·실행하고, production 코드는 동일한 implementer 한 명만 작성한다.

**목표:** `POST /api/auth/email-verifications/confirm`에서 인증번호의 상태와 시도 횟수를 검증하고, 성공 시 원문을 저장하지 않는 30분 일회용 가입 토큰을 발급한다.

**아키텍처:** 기존 `EmailVerificationController → EmailVerificationService → EmailVerificationRepository` 흐름과 `EmailVerification` 엔티티를 확장한다. 확인 전용 서비스나 범용 Manager는 추가하지 않으며, 기존 V2 스키마 컬럼을 그대로 사용한다.

**기술 스택:** Java 17, Spring Boot 4.1, Spring Data JPA, Bean Validation, JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

## 전역 제약

- 코드 불일치 1~5회는 `attempt_count`를 저장하고 400 `EMAIL_VERIFICATION_FAILED`로 응답한다.
- 6번째 시도는 코드 일치 여부를 검사하기 전에 차단하며, `attempt_count` 증가와 즉시 무효화를 저장하고 429 `TOO_MANY_REQUESTS`로 응답한다.
- 미발급·만료·이미 확인된 인증번호는 400 `EMAIL_VERIFICATION_FAILED`다.
- 가입 토큰은 `SecureRandom` 32바이트를 URL-safe Base64(패딩 없음)로 인코딩하고, SHA-256 해시만 DB에 저장한다.
- 가입 토큰 만료는 발급 시각부터 30분이며 응답의 `expiresInSeconds`는 `1800`이다.
- 기존 `V2__create_auth_account_tables.sql`은 수정하지 않는다.
- 새 Java 파일 첫 줄에는 파일 역할을 설명하는 한 줄 한국어 주석을 둔다.
- controller 변경과 함께 `docs/api-routes.md`를 동기화한다.
- production 코드는 implementer 한 명만 수정하고, tester·reviewer는 `src/main`을 수정하지 않는다.

---

### Task 1: 인증번호 확인 및 가입 토큰 발급 도메인 흐름

**파일**

- 수정: `src/test/java/com/finplay/api/auth/service/EmailVerificationServiceTest.java`
- 수정: `src/main/java/com/finplay/api/auth/domain/EmailVerification.java`
- 수정: `src/main/java/com/finplay/api/auth/repository/EmailVerificationRepository.java`
- 수정: `src/main/java/com/finplay/api/auth/service/EmailVerificationService.java`
- 생성: `src/main/java/com/finplay/api/auth/dto/response/SignupTokenResponse.java`

**인터페이스**

- 생성: `SignupTokenResponse(String signupVerificationToken, long expiresInSeconds)`
- 생성: `EmailVerificationService.confirmVerificationCode(String email, String code)`
- 생성: `EmailVerificationRepository.findFirstByEmailOrderByCreatedAtDesc(String email)`
- 엔티티 상태 변경 메서드는 실패 시도 기록·한도 초과 무효화·인증 성공을 이름으로 드러낸다.

- [ ] **1단계: 실패 테스트 작성**

  tester는 다음 소비자 행동을 독립 테스트로 추가한다.

  - 정상 코드가 가입 토큰 원문과 `1800`을 반환하고, 저장 엔티티의 `tokenHash`가 원문 SHA-256과 같으며 `verifiedAt`·`tokenExpiresAt=now+30분`이 기록된다.
  - 코드 불일치 1~5회는 각각 `EMAIL_VERIFICATION_FAILED`이며 `attemptCount`가 누적된다.
  - 실패 5회 이후의 6번째 요청은 코드가 맞아도 `TOO_MANY_REQUESTS`이고 `attemptCount=6`, `expiresAt=now`가 된다.
  - 최신 행 미존재, 만료, 이미 확인된 행은 `EMAIL_VERIFICATION_FAILED`다.

- [ ] **2단계: RED 검증**

  실행:

  ```powershell
  .\gradlew.bat test --tests "com.finplay.api.auth.service.EmailVerificationServiceTest"
  ```

  기대: 새 확인 메서드·응답 타입·엔티티 상태 변경이 아직 없어 컴파일 또는 테스트가 실패한다.

- [ ] **3단계: 최소 production 구현**

  implementer는 최신 이메일 인증 행을 조회하고 상태를 검증한다. `@Transactional(noRollbackFor = BusinessException.class)`를 확인 메서드에 적용해 400/429 예외 뒤에도 시도 횟수와 무효화가 커밋되도록 한다. 성공 시 32바이트 토큰을 발급하고 SHA-256 hex 해시, 확인 시각, 30분 만료 시각을 엔티티에 기록한 뒤 원문은 응답으로만 반환한다.

- [ ] **4단계: GREEN 검증**

  실행:

  ```powershell
  .\gradlew.bat test --tests "com.finplay.api.auth.service.EmailVerificationServiceTest"
  .\gradlew.bat compileJava
  ```

  기대: 둘 다 성공한다.

---

### Task 2: HTTP 계약과 API 문서

**파일**

- 수정: `src/test/java/com/finplay/api/auth/controller/EmailVerificationControllerTest.java`
- 생성: `src/main/java/com/finplay/api/auth/dto/request/EmailVerificationConfirmRequest.java`
- 수정: `src/main/java/com/finplay/api/auth/controller/EmailVerificationController.java`
- 수정: `docs/api-routes.md`

**인터페이스**

- 요청: `EmailVerificationConfirmRequest(String email, String code)`
- 성공 응답: HTTP 200, `{"signupVerificationToken":"<원문>","expiresInSeconds":1800}`
- 오류 응답: 기존 전역 공통 오류 포맷

- [ ] **1단계: 실패 테스트 작성**

  tester는 다음 MockMvc 행동을 추가한다.

  - 유효 요청은 200과 가입 토큰 응답 필드를 반환하고 서비스에 이메일·코드를 전달한다.
  - 이메일 누락·형식 오류, 코드 누락·숫자 6자리 위반은 400 `VALIDATION_ERROR`다.
  - 서비스의 `EMAIL_VERIFICATION_FAILED`는 400, `TOO_MANY_REQUESTS`는 429 공통 오류 응답으로 매핑된다.

- [ ] **2단계: RED 검증**

  실행:

  ```powershell
  .\gradlew.bat test --tests "com.finplay.api.auth.controller.EmailVerificationControllerTest"
  ```

  기대: `/confirm` 매핑과 요청 DTO가 없어 새 테스트가 실패한다.

- [ ] **3단계: 최소 production 및 문서 구현**

  implementer는 `@PostMapping("/confirm")`을 추가한다. 요청 record에는 이메일 검증과 숫자 6자리 코드 검증을 선언하고, 서비스 결과를 HTTP 200으로 반환한다. `docs/api-routes.md`에는 공개 인증번호 확인 경로, 요청·성공 응답·400/429 오류 계약을 기록한다.

- [ ] **4단계: GREEN 및 항목 검증**

  실행:

  ```powershell
  .\gradlew.bat test --tests "com.finplay.api.auth.controller.EmailVerificationControllerTest"
  .\gradlew.bat test --tests "com.finplay.api.auth.service.EmailVerificationServiceTest"
  .\gradlew.bat spotlessApply
  ```

  기대: 대상 테스트가 모두 성공하고 포맷 변경 후에도 작업 범위 밖 파일이 수정되지 않는다.

---

### Task 3: 전체 게이트와 리뷰

**파일**

- 수정: `docs/specs/002-auth-account/tasks.md`
- 수정: `docs/specs/002-auth-account/run-log.md`

- [ ] **1단계: 작업 체크와 실행 로그 동기화**

  Issue #3에 해당하는 “인증번호 확인 API” 항목만 완료 처리하고, implementer가 실제 실행한 명령과 근거를 `run-log.md` 형식으로 기록한다.

- [ ] **2단계: 전체 빌드**

  실행:

  ```powershell
  .\gradlew.bat build
  ```

  기대: 컴파일, 테스트, Spotless, SpotBugs, JaCoCo 게이트가 모두 성공한다.

- [ ] **3단계: 새 reviewer의 전체 브랜치 리뷰**

  reviewer는 `git diff origin/dev...HEAD`를 대상으로 정확성, 트랜잭션 지속성, 오류 매핑, 테스트 수준, 문서 동기화를 검토한다. 차단 지적은 같은 implementer 세션으로 돌려보내고 대상 테스트와 build를 다시 실행한다.
