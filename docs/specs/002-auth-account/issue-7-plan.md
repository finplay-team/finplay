# Issue #7 로그아웃 API 구현 계획

> **For agentic workers:** 각 Task는 실패 테스트를 먼저 작성한 뒤 구현한다. Step은 체크박스(`- [ ]`)로 추적한다.

**Goal:** Access Bearer 인증 사용자가 `POST /api/auth/logout`에 본인의 Refresh Token을 제출하면 해당 토큰 하나만 폐기하고 204를 반환한다. 폐기된 토큰의 이후 `/api/auth/refresh` 사용은 401로 거부하며, 다른 사용자의 Refresh Token은 폐기하지 않는다.

**관련 정본:** GitHub Issue #7, PRD `AUTH-002`와 §5 공통 오류표, `spec.md`, `plan.md`, ADR-0002, `docs/conventions.md`

**선행:** Issue #6 병합 완료. 현재 `origin/dev`에는 Refresh JWT 검증, SHA-256 해시 조회, 활성 토큰 조건부 폐기, 회전 API와 실제 MySQL 테스트가 있다.

**Architecture:** 기존 `AuthController → AuthService → RefreshTokenRepository` 흐름을 확장한다. Controller는 `@AuthenticationPrincipal AuthenticatedUser`와 검증된 `RefreshRequest`를 서비스에 전달하고 204를 만든다. `AuthService`의 단일 `@Transactional` 메서드는 Refresh JWT, 저장 해시, Access Token 주체와 토큰 소유자를 대조한 뒤 Issue #6의 조건부 폐기 쿼리로 제출된 행 하나만 폐기한다. Repository·엔티티·스키마는 변경하지 않는다.

**Tech Stack:** Java 17, Spring Boot 4.1, Spring Security, Spring Data JPA, Bean Validation, JJWT 0.13.0, MySQL 8.4 Testcontainers, JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

---

## 요구사항 ID와 수용 기준

### 대상 요구사항

- PRD `AUTH-002 로그인·토큰`
  - 로그아웃 시 이전 Refresh Token을 폐기한다.
  - 인증 필요 API는 `Authorization: Bearer <accessToken>`을 요구한다.
  - Refresh Token 원문은 저장하지 않고 해시만 저장한다.
- PRD §5 공통 오류표
  - 인증 없음·만료는 401 `UNAUTHORIZED`.
  - 소유권·권한 없음은 403 `FORBIDDEN`.
- `spec.md`
  - 사용자는 로그아웃하면 기존 Refresh Token을 더 이상 쓸 수 없다.
  - 폐기된 Refresh Token 재사용은 401이다.
- GitHub Issue #7
  - `POST /api/auth/logout`, 요청 `RefreshRequest`, 정상 204.
  - 본인 Refresh Token만 폐기한다.
  - 로그인 → 로그아웃 → 갱신 거부와 타인 토큰 비폐기를 검증한다.
  - 성공·실패 응답은 공통 오류 형식을 따른다.

### 수용 시나리오

1. Given 사용자 A가 로그인해 유효한 Access Token과 Refresh Token을 발급받은 상태
2. When A의 Access Bearer와 A의 Refresh Token으로 `POST /api/auth/logout`
3. Then 본문 없이 204를 반환하고 제출한 Refresh Token 행의 `revoked_at`만 기록한다.
4. And 같은 Refresh Token으로 `/api/auth/refresh`를 호출하면 401 `UNAUTHORIZED` 공통 오류 형식이다.
5. Given 사용자 A가 사용자 B의 활성 Refresh Token을 알고 있더라도
6. When A의 Access Bearer와 B의 Refresh Token으로 로그아웃
7. Then 403 `FORBIDDEN` 공통 오류 형식이며 B의 토큰은 폐기되지 않아 B가 계속 회전할 수 있다.
8. And Access Bearer가 없거나 만료·변조됐거나 Refresh Token을 Bearer로 제출하면 Security에서 401 `UNAUTHORIZED`로 거부하고 서비스는 호출되지 않는다.

---

## 범위와 제외

### 포함

- `POST /api/auth/logout`
- `Authorization: Bearer <accessToken>` 필수 인증
- 기존 `RefreshRequest`의 `@NotBlank`, 최대 4,096자 검증 재사용
- Refresh JWT의 서명·만료·`tokenType=REFRESH`·subject 검증
- Refresh Token 원문의 SHA-256 해시 조회
- Access Token 주체, Refresh JWT subject, DB 토큰 소유자 일치 검증
- 제출한 활성·미만료 Refresh Token 행 하나의 조건부 폐기
- 정상 204, 본문 없음
- 폐기 후 `/api/auth/refresh` 401과 타인 토큰 비폐기 검증
- 공통 400·401·403 오류 형식
- Controller·Service 단위/슬라이스 테스트와 실제 MySQL 통합 테스트
- 구현 뒤 `docs/api-routes.md` 동기화

### 제외

- Access Token 블랙리스트 또는 현재 Access Token의 즉시 폐기. 로그아웃 뒤 Access Token은 자체 만료까지 유효하다.
- 해당 사용자의 모든 기기·모든 세션 Refresh Token 일괄 폐기
- 토큰 패밀리 전체 폐기, 탈취 탐지, 계정 전체 강제 로그아웃
- Refresh Token 정리 배치
- `/api/auth/me`(Issue #8), OAuth callback과 토큰 발급
- 새 Token Manager·Facade·공통 폐기 프레임워크
- Repository 쿼리, `RefreshToken` 엔티티, V2 마이그레이션과 DB 스키마 변경
- 투자일기·AI·랭킹·알림·지정가·Kafka·별도 동시성 기능

---

## 설계 결정

### D1. Access Token과 Refresh Token을 둘 다 검증한다

Controller는 Security가 만든 `AuthenticatedUser`를 `@AuthenticationPrincipal`로 받고 `userId`와 요청의 Refresh Token 원문을 서비스에 전달한다. 서비스는 기존 `JwtTokenProvider.parseRefreshToken`과 `RefreshTokenRepository.findAllByTokenHash`를 재사용해 다음 세 식별자를 대조한다.

1. Access Token에서 인증된 사용자 ID
2. Refresh JWT subject의 사용자 ID
3. DB `refresh_tokens.user_id`

Refresh JWT 파싱 실패, 해시 조회 결과 0건·2건 이상, JWT subject와 DB 사용자가 다른 데이터 이상은 401 `UNAUTHORIZED`다. DB 토큰 소유자는 Refresh JWT claim이 아니라 연관된 `User` 엔티티를 기준으로 판정한다.

### D2. 활성 타인 토큰은 403이고 폐기하지 않는다

유효한 Refresh JWT와 해시 행이 한 건 존재하지만 Access Token 사용자 ID가 DB 토큰 사용자 ID와 다르면 PRD §5의 소유권 규칙에 따라 `BusinessException(ErrorCode.FORBIDDEN)`을 던진다.

- 조건부 폐기 쿼리를 호출하기 전에 소유자를 검사한다.
- 403 경로에서는 `revokeIfActiveAndNotExpired`를 호출하지 않는다.
- 실제 MySQL 통합 테스트는 타인 시도 뒤 `revoked_at`이 null이고 원래 소유자가 그 토큰으로 회전할 수 있음을 확인한다.

### D3. Issue #6의 원자적 단일 폐기 쿼리를 재사용한다

`AuthService.logout(Long authenticatedUserId, String rawRefreshToken)`은 하나의 `@Transactional` 경계에서 기존 `revokeIfActiveAndNotExpired(id, now)`를 호출한다.

- 영향 행 `1`: 폐기 성공, 반환 값 없이 종료.
- 영향 행 `0`: 만료·이미 폐기·동시 중복 로그아웃이므로 401 `UNAUTHORIZED`.
- 새 Refresh Token은 발급하거나 저장하지 않는다.
- 제출한 행 외 다른 Refresh Token 행은 조회·폐기하지 않는다.
- 토큰 원문과 해시는 로그에 남기지 않는다.

이 요구사항은 기존 `refresh_tokens`의 `id`, `user_id`, `token_hash`, `expires_at`, `revoked_at`만으로 충족된다. 병합된 V2 마이그레이션을 수정하거나 새 마이그레이션을 추가하지 않는다.

### D4. 로그아웃은 공개 화이트리스트에 추가하지 않는다

`SecurityConfig`의 `anyRequest().authenticated()`가 `/api/auth/logout`을 보호한다. `PUBLIC_POST_PATHS`에는 logout을 추가하지 않는다.

- Access Token 없음·만료·변조·잘못된 타입은 Controller 진입 전 401이다.
- 기존 `JwtAuthenticationFilter`가 만든 `AuthenticatedUser`를 Controller에서 사용한다.
- Security production 설정 변경은 필요하지 않으며, 회귀 테스트만 추가한다.

### D5. HTTP 계약

| 항목 | 내용 |
|---|---|
| Method / Path | `POST /api/auth/logout` |
| 인증 | `Authorization: Bearer <유효한 Access JWT>` |
| 요청 | `{"refreshToken":"<Refresh JWT 원문>"}` (`RefreshRequest` 재사용) |
| 성공 | 204, 응답 본문 없음 |
| 요청 본문 누락·빈 문자열·4,096자 초과 | 400 `VALIDATION_ERROR` 공통 포맷 |
| Access Token 없음·만료·변조·잘못된 타입 | 401 `UNAUTHORIZED` 공통 포맷 |
| Refresh Token 변조·만료·폐기·미존재·중복 해시·JWT/DB 사용자 불일치 | 401 `UNAUTHORIZED` 공통 포맷 |
| 다른 사용자의 활성 Refresh Token | 403 `FORBIDDEN` 공통 포맷, 토큰 상태 불변 |

204는 `ResponseEntity.noContent().build()`로 만들며 `TokenResponse`나 별도 Logout 응답 DTO를 추가하지 않는다.

---

## File Map

### Production files to modify

- `src/main/java/com/finplay/api/auth/service/AuthService.java`
  - `logout(Long authenticatedUserId, String rawRefreshToken)` 트랜잭션 추가.
  - Issue #6의 Refresh JWT 파싱, SHA-256, 해시 조회와 조건부 폐기 인터페이스 재사용.
- `src/main/java/com/finplay/api/auth/controller/AuthController.java`
  - `@AuthenticationPrincipal AuthenticatedUser`와 `RefreshRequest`를 받는 `POST /logout` 추가.
  - 성공 시 본문 없는 204 반환.

### Test files to modify

- `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java`
  - 본인 폐기, 타인 403·비폐기, Refresh Token 오류 401 서비스 테스트.
- `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java`
  - 유효 Access Bearer + 유효 요청 204, 요청 검증과 401·403 공통 오류 매핑.
- `src/test/java/com/finplay/api/auth/config/SecurityConfigTest.java`
  - logout이 공개 POST 경로가 아니며 Access Bearer 없이는 401인 회귀 테스트.

### Test files to create

- `src/test/java/com/finplay/api/auth/service/LogoutIntegrationTest.java`
  - 실제 MySQL에서 로그인 → 로그아웃 → refresh 401, 타인 토큰 비폐기와 소유자 회전 가능 검증.

### Documentation files to modify after implementation

- `docs/api-routes.md`
  - 실제 Controller 매핑 기준 logout 상세 계약과 보호 경로 표 추가.
- `docs/specs/002-auth-account/tasks.md`
  - Issue #7 logout 항목을 완료 처리하되 Issue #8과 다른 잔여 작업은 그대로 둔다.
- `docs/specs/002-auth-account/run-log.md`
  - implementer·reviewer가 실제 실행한 명령과 검증 수준만 기록한다.

### 만들거나 수정하지 않을 파일

- `src/main/java/com/finplay/api/auth/dto/request/RefreshRequest.java`
- `src/main/java/com/finplay/api/auth/token/JwtTokenProvider.java`
- `src/main/java/com/finplay/api/auth/repository/RefreshTokenRepository.java`
- `src/main/java/com/finplay/api/auth/domain/RefreshToken.java`
- `src/main/java/com/finplay/api/auth/config/SecurityConfig.java`
- `src/main/resources/db/migration/V2__create_auth_account_tables.sql`
- 새 `V3__*.sql`

---

## Task 1: 소유권을 포함한 단일 Refresh Token 폐기 서비스

**Files**

- Modify: `src/main/java/com/finplay/api/auth/service/AuthService.java`
- Modify: `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java`

**Interface**

- Produces: `void AuthService.logout(Long authenticatedUserId, String rawRefreshToken)`

- [x] **Step 1: 서비스 실패 테스트를 작성한다**
  - 유효한 Refresh JWT, 단일 해시 행, Access 사용자=JWT subject=DB 사용자이고 조건부 폐기 결과가 `1`이면 정상 종료한다.
  - JWT 파싱 실패, 해시 미존재·중복, JWT subject와 DB 사용자 불일치는 401 `UNAUTHORIZED`.
  - Access 사용자와 DB 토큰 사용자가 다르면 403 `FORBIDDEN`이고 조건부 폐기 메서드는 호출하지 않는다.
  - 조건부 폐기 결과 `0`은 401 `UNAUTHORIZED`.
  - 성공·실패 모든 경로에서 새 토큰을 발급하거나 Refresh Token 행을 저장하지 않는다.
- [ ] **Step 2: 대상 테스트 실패를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*AuthServiceTest" --no-daemon --max-workers=1
  ```

  Expected: `logout` 부재로 컴파일 또는 새 테스트 FAIL.

- [x] **Step 3: 최소 서비스 구현을 추가한다**
  - `@Transactional` public 메서드 하나에 D1~D3 순서를 구현한다.
  - 기존 private `sha256`, `parseRefreshToken`, `findAllByTokenHash`, `revokeIfActiveAndNotExpired`를 재사용한다.
  - 범용 Token Manager나 별도 로그아웃 전용 Repository를 추가하지 않는다.
- [x] **Step 4: 같은 대상 테스트를 다시 실행해 PASS를 확인한다**
- [x] **Step 5: 논리 커밋한다**

  ```powershell
  git add src/main/java/com/finplay/api/auth/service/AuthService.java src/test/java/com/finplay/api/auth/service/AuthServiceTest.java
  git commit -m "feat: Refresh Token 로그아웃 처리 추가"
  ```

---

## Task 2: 보호된 logout HTTP 계약

**Files**

- Modify: `src/main/java/com/finplay/api/auth/controller/AuthController.java`
- Modify: `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java`
- Modify: `src/test/java/com/finplay/api/auth/config/SecurityConfigTest.java`

**Interfaces**

- Consumes: Access Bearer의 `AuthenticatedUser`, `{"refreshToken":"<Refresh JWT 원문>"}`
- Produces: 204, 본문 없음

- [ ] **Step 1: WebMvc 실패 테스트를 작성한다**
  - 유효 Access Bearer가 `AuthenticatedUser(userId, role)`로 파싱되고 유효 요청이면 서비스에 `userId`와 원문을 전달해 204, 빈 본문을 반환한다.
  - Access Bearer가 없으면 401 공통 오류 형식이며 서비스는 호출되지 않는다.
  - Refresh Token을 Bearer로 제출하면 401이고 서비스는 호출되지 않는다.
  - 요청 `refreshToken` 누락·빈 문자열·공백·4,097자는 400 `VALIDATION_ERROR`이고 서비스는 호출되지 않는다.
  - 서비스의 401 `UNAUTHORIZED`와 403 `FORBIDDEN`을 각각 공통 오류 형식으로 반환한다.
- [ ] **Step 2: Security 회귀 실패 테스트를 추가한다**
  - `POST /api/auth/logout`은 공개 경로 목록에 포함되지 않아 인증 없이는 401이다.
  - 유효 Access Bearer는 Security를 통과해 Controller까지 도달한다.
- [ ] **Step 3: 대상 테스트 실패를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*AuthControllerTest" --tests "*SecurityConfigTest" --no-daemon --max-workers=1
  ```

  Expected: logout Controller 매핑 부재로 404 또는 새 검증 FAIL.

- [ ] **Step 4: Controller 매핑만 구현한다**
  - `@AuthenticationPrincipal AuthenticatedUser`를 받는다.
  - 기존 `RefreshRequest`를 재사용하고 `@Valid @RequestBody`를 유지한다.
  - 서비스 성공 뒤 `ResponseEntity.noContent().build()`를 반환한다.
  - `SecurityConfig.PUBLIC_POST_PATHS`는 수정하지 않는다.
- [ ] **Step 5: 같은 대상 테스트를 다시 실행해 PASS를 확인한다**
- [ ] **Step 6: 논리 커밋한다**

  ```powershell
  git add src/main/java/com/finplay/api/auth/controller/AuthController.java src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java src/test/java/com/finplay/api/auth/config/SecurityConfigTest.java
  git commit -m "feat: 로그아웃 API 추가"
  ```

---

## Task 3: 실제 MySQL 로그아웃·소유권 통합 검증

**Files**

- Create: `src/test/java/com/finplay/api/auth/service/LogoutIntegrationTest.java`

- [ ] **Step 1: 실제 MySQL 통합 테스트를 작성한다**
  - 사용자 A 로그인 → A의 Access 사용자 ID와 Refresh Token으로 logout → 해당 행 `revoked_at` 기록 → 같은 원문 `authService.refresh`가 401.
  - 사용자 A·B 로그인 → A가 B의 Refresh Token으로 logout 시 403 → B 행의 `revoked_at`은 null → B의 원문으로 refresh 성공.
  - A가 여러 번 로그인해 Refresh Token 행이 여러 개면 제출한 한 행만 폐기되고 A의 다른 세션 토큰은 계속 회전 가능하다.
  - 테스트 메서드 전체에 롤백 `@Transactional`을 붙이지 않고 서비스 public 메서드의 실제 트랜잭션 커밋 상태를 새 조회로 확인한다.
- [ ] **Step 2: 대상 테스트를 실행해 PASS를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*LogoutIntegrationTest" --no-daemon --max-workers=1
  ```

- [ ] **Step 3: 논리 커밋한다**

  ```powershell
  git add src/test/java/com/finplay/api/auth/service/LogoutIntegrationTest.java
  git commit -m "test: 로그아웃과 토큰 소유권 통합 검증"
  ```

---

## Task 4: API 문서 동기화와 전체 검증

**Files**

- Modify: `docs/api-routes.md`
- Modify: `docs/specs/002-auth-account/tasks.md`
- Modify during feature workflow: `docs/specs/002-auth-account/run-log.md`

- [ ] **Step 1: 실제 Controller 매핑으로 API 문서를 동기화한다**
  - 엔드포인트 목록에 `POST /api/auth/logout`을 추가한다.
  - 상세 표에 Access Bearer 필수, `RefreshRequest`, 204, 400·401·403 공통 오류를 기록한다.
  - Security 표에서 logout을 보호 경로로 기록하고 “아직 구현되지 않음” 문구를 제거한다.
- [ ] **Step 2: tasks 상태를 갱신한다**
  - Issue #7 logout 하위 항목만 완료 처리한다.
  - `/api/auth/me`, OAuth와 다른 인증 잔여 작업은 완료 처리하지 않는다.
- [ ] **Step 3: Spotless와 대상 테스트를 실행한다**

  ```powershell
  .\gradlew.bat spotlessApply
  .\gradlew.bat test --tests "*AuthServiceTest" --tests "*AuthControllerTest" --tests "*SecurityConfigTest" --tests "*LogoutIntegrationTest" --no-daemon --max-workers=1
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
  git commit -m "docs: 로그아웃 API 계약 동기화"
  ```

---

## 미확정 사항

- **타인 토큰과 무효 토큰 조건이 동시에 성립할 때의 오류 우선순위:** 정본은 활성 타인 토큰의 소유권 거부와 만료·폐기 토큰의 재사용 거부를 각각 규정하지만, “타인의 이미 폐기되었거나 만료된 토큰”을 401과 403 중 무엇으로 반환할지는 정하지 않았다. 이 계획의 필수 수용 테스트는 활성 타인 토큰 403·비폐기까지만 고정한다. 이 중첩 경계의 외부 계약이 필요하면 구현 전에 Issue에 확정한다.

---

## 완료 체크리스트

- [ ] `POST /api/auth/logout`은 유효한 Access Bearer를 요구한다.
- [ ] 요청은 기존 `RefreshRequest` 검증을 재사용한다.
- [ ] 본인의 제출한 활성 Refresh Token 한 행만 폐기하고 204, 빈 본문을 반환한다.
- [ ] 폐기된 토큰의 `/api/auth/refresh` 사용은 401 `UNAUTHORIZED`다.
- [ ] 활성 타인 Refresh Token 시도는 403 `FORBIDDEN`이며 그 토큰은 계속 유효하다.
- [ ] Access 사용자, Refresh JWT subject, DB 토큰 소유자를 대조한다.
- [ ] Refresh Token 원문은 DB나 로그에 남지 않는다.
- [ ] Access Token 블랙리스트, 전체 세션 로그아웃, 스키마 변경을 포함하지 않는다.
- [ ] 실제 Controller 매핑과 `docs/api-routes.md`가 일치한다.
- [ ] 대상 테스트와 `.\gradlew.bat build --no-daemon --max-workers=1` 결과를 새로 확인한다.
