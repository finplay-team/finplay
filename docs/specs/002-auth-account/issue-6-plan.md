# Issue #6 Refresh Token 회전 API 구현 계획

> **For agentic workers:** 각 Task는 실패 테스트를 먼저 작성한 뒤 구현한다. Step은 체크박스(`- [ ]`)로 추적한다.

**Goal:** `POST /api/auth/refresh`가 유효한 Refresh Token을 한 번만 소비해 이전 토큰을 즉시 폐기하고 새 Access/Refresh 토큰 쌍을 원자적으로 발급한다. 만료·폐기·재사용 토큰은 원인과 무관하게 401 `UNAUTHORIZED`로 거부한다.

**관련 정본:** GitHub Issue #6, PRD `AUTH-002`, `spec.md`, `plan.md`, ADR-0002·0003·0004, `docs/conventions.md`

**Architecture:** 기존 `AuthController → AuthService → RefreshTokenRepository` 흐름을 확장한다. Controller는 요청 검증과 HTTP 응답만 담당하고, `AuthService`의 단일 `@Transactional` 메서드가 Refresh JWT 검증, SHA-256 해시 조회, 기존 행의 조건부 폐기, 새 토큰 쌍 발급과 해시 저장을 조정한다. 동시 요청은 `revoked_at IS NULL AND expires_at > :now` 조건부 `UPDATE`의 영향 행 수로 단일 승자를 정한다.

**Tech Stack:** Java 17, Spring Boot 4.1, Spring Security, Spring Data JPA, Bean Validation, JJWT 0.13.0, MySQL 8.4 Testcontainers, JUnit 5, Mockito, MockMvc, Gradle Groovy DSL

---

## 요구사항 ID와 수용 기준

### 대상 요구사항

- PRD `AUTH-002 로그인·토큰`
  - Access Token과 회전형 Refresh Token을 사용한다.
  - Refresh Token 원문은 저장하지 않고 SHA-256 해시만 저장한다.
  - 토큰 재발급 시 이전 Refresh Token을 폐기한다.
- `spec.md` 완료 조건
  - 로그인 → 재발급 → 이전 Refresh Token 재사용 거부(401)가 통과한다.
- GitHub Issue #6
  - Refresh Token 검증과 회전형 새 토큰 쌍 발급을 원자 처리한다.
  - 갱신 후 이전 토큰을 즉시 무효화한다.
  - 만료·폐기·재사용은 동일한 401로 응답한다.
  - 로그인 → 갱신 → 이전 토큰 재사용 거부와 롤백을 테스트한다.

### 수용 시나리오

1. Given 로그인으로 발급되어 DB에 해시가 저장된 유효한 Refresh Token
2. When `POST /api/auth/refresh`에 그 원문을 한 번 제출
3. Then 기존 DB 행의 `revoked_at`이 기록되고, 새 Access/Refresh 토큰 쌍이 200으로 반환되며, 새 Refresh Token은 해시만 새 행에 저장된다.
4. And 같은 이전 원문을 다시 제출하면 401 `UNAUTHORIZED`다.
5. And 같은 원문으로 두 요청이 동시에 시작되면 정확히 하나만 200이고 다른 하나는 401이며, 새 유효 Refresh Token은 하나만 생긴다.
6. And 기존 토큰 폐기 뒤 새 토큰 발급·저장이 실패하면 전체 트랜잭션이 롤백되어 기존 토큰은 다시 사용할 수 있는 상태로 남는다.

---

## 범위와 제외

### 포함

- `POST /api/auth/refresh`
- Refresh JWT의 서명·만료·`tokenType=REFRESH`·subject 검증
- 원문 SHA-256 해시로 기존 `refresh_tokens` 행 확인
- 기존 토큰 조건부 폐기와 새 토큰 쌍 발급·해시 저장의 단일 트랜잭션
- 동일 토큰 동시 요청에서 단일 성공 보장
- 만료·폐기·미존재·재사용·JWT와 DB 사용자 불일치의 동일 401 처리
- Controller, 서비스, JWT 파서, 실제 MySQL 조건부 갱신, 핵심 통합·롤백 테스트
- `SecurityConfig` 공개 POST 경로 및 `docs/api-routes.md` 동기화

### 제외

- `POST /api/auth/logout` (Issue #7)
- `GET /api/auth/me` (Issue #8)
- OAuth callback 및 OAuth 토큰 발급 경로
- Access Token 블랙리스트 또는 즉시 폐기
- 모든 기기·모든 세션의 Refresh Token 일괄 폐기
- 토큰 패밀리 전체 탐지·계정 전체 강제 로그아웃
- Refresh Token 정리 배치
- 범용 Token Manager, Facade, 별도 회전 프레임워크
- 스키마·엔티티 컬럼·인덱스 변경. 기존 구조로 요구사항을 충족하므로 새 Flyway 마이그레이션을 추가하지 않는다.

---

## 설계 결정

### D1. Refresh JWT와 DB 상태를 모두 검증한다

`JwtTokenProvider`에 `parseRefreshToken(String token)`을 추가하고 기존 `AuthenticatedUser(userId, role)`를 반환 값으로 재사용한다.

검증 순서는 다음과 같다.

1. 현재 서명 키로 서명을 검증한다.
2. 주입된 `Clock` 기준으로 JWT 만료를 검증한다.
3. `tokenType`이 정확히 `REFRESH`인지 확인한다.
4. subject가 `Long userId`로 변환되는지 확인한다.
5. 원문의 SHA-256 해시와 일치하는 DB 행이 정확히 하나인지 확인한다.
6. JWT subject와 DB 행의 `user.id`가 같은지 확인한다.
7. DB 행이 아직 폐기되지 않았고 DB 만료 시각이 현재보다 뒤인지 조건부 갱신으로 최종 확인한다.

JWT만 검증하면 로그아웃·회전으로 폐기된 토큰을 막을 수 없고, DB만 검증하면 변조된 JWT의 서명·타입을 확인하지 못한다. 어느 단계에서 실패해도 외부에는 `BusinessException(ErrorCode.UNAUTHORIZED)`만 노출한다. 토큰 원문과 해시는 로그에 남기지 않는다.

### D2. 기존 토큰 폐기는 조건부 UPDATE의 영향 행 수로 원자화한다

`RefreshTokenRepository`에 다음 의미의 갱신 메서드를 둔다.

```sql
UPDATE refresh_tokens
   SET revoked_at = :now
 WHERE id = :id
   AND revoked_at IS NULL
   AND expires_at > :now
```

- 영향 행이 `1`이면 이 요청이 토큰 소비 권한을 얻었다.
- 영향 행이 `0`이면 만료·폐기·재사용이거나 동시 요청에서 패한 것이므로 401이다.
- 먼저 조회한 엔티티의 `revokedAt` 값만 보고 판단하지 않는다. 두 트랜잭션이 동시에 `NULL`을 읽을 수 있기 때문이다.
- 비관적 잠금용 별도 조회, 애플리케이션 락, Redis 락, `@Version` 컬럼은 추가하지 않는다. MySQL의 단일 조건부 UPDATE가 필요한 경합 지점과 결과를 직접 표현한다.
- 현재 `token_hash`에는 일반 인덱스만 있고 UNIQUE 제약은 없다. 조회 결과가 없거나 둘 이상이면 데이터 이상을 성공 경로로 해석하지 않고 동일 401로 거부한다. 이번 Issue에서 기존 스키마에 UNIQUE를 추가하지 않는다.

MySQL의 UPDATE는 대상 행을 잠그므로 같은 행에 대한 동시 요청 중 첫 요청만 조건을 만족한다. 후속 요청은 잠금 해제 뒤 갱신된 `revoked_at`을 보고 영향 행 `0`을 받는다.

### D3. 폐기와 새 토큰 저장은 하나의 서비스 트랜잭션이다

`AuthService.refresh(String rawRefreshToken)` 전체에 `@Transactional`을 적용하고 다음 순서를 유지한다.

1. Refresh JWT 파싱
2. SHA-256 해시 조회와 사용자 일치 검증
3. 기존 행 조건부 폐기
4. 기존 `issueTokenPair(User, now)`로 새 JWT 쌍 발급
5. 새 Refresh Token SHA-256 해시 행 저장
6. `TokenResponse` 반환

3~5는 같은 트랜잭션이다. JWT 발급 또는 새 행 저장에서 런타임 예외가 발생하면 `revoked_at` 갱신도 롤백된다. 반대로 트랜잭션이 커밋된 뒤에는 이전 토큰이 즉시 401이 된다. 기존 private `issueTokenPair`를 재사용하고 새 발급 서비스나 Manager를 만들지 않는다.

### D4. 새 토큰은 DB 사용자의 현재 권한으로 발급한다

JWT subject는 DB 행의 사용자와 일치 여부를 확인하는 데만 사용한다. 새 토큰 쌍은 기존 `RefreshToken.user` 엔티티의 현재 `id`와 `role`로 발급한다. 오래된 Refresh JWT의 role 클레임을 그대로 복사하지 않는다.

### D5. 재발급 경로는 Access Token 없이 호출 가능한 공개 경로다

`POST /api/auth/refresh` 자체 인증 수단은 요청 본문의 Refresh Token이다. 따라서 `SecurityConfig.PUBLIC_POST_PATHS`에 경로를 추가한다. 유효한 Access Token을 `Authorization` 헤더에 함께 요구하지 않는다. 그렇지 않으면 Access Token 만료 뒤 재발급한다는 용도를 충족할 수 없다.

### D6. 성공과 실패 HTTP 계약

| 항목 | 내용 |
|---|---|
| Method / Path | `POST /api/auth/refresh` |
| 요청 | `{"refreshToken":"<Refresh JWT 원문>"}` |
| 성공 | 200 `{"accessToken":"<JWT>","refreshToken":"<JWT>","accessTokenExpiresInSeconds":3600,"refreshTokenExpiresInSeconds":1209600}` |
| 요청 본문 누락·빈 문자열·4,096자 초과 | 400 `VALIDATION_ERROR` 공통 포맷 |
| 1~4,096자의 비어 있지 않은 변조·만료·폐기·미존재·재사용·잘못된 타입·사용자 불일치 | 401 `UNAUTHORIZED` 공통 포맷 |

응답은 기존 `TokenResponse`를 재사용한다. 회전은 별도 리소스 생성 응답이 아니라 인증 토큰 갱신이므로 200을 사용한다.

### D7. 스키마 변경은 없다

기존 V2에 필요한 구조가 모두 있다.

| 기존 컬럼/인덱스 | 사용 |
|---|---|
| `refresh_tokens.id` | 조건부 UPDATE 대상 식별 |
| `user_id` FK | JWT subject 일치 검증과 새 토큰 사용자 연결 |
| `token_hash` + `idx_refresh_tokens_token_hash` | 원문 미저장 해시 조회 |
| `expires_at` | DB 측 유효기간 조건 |
| `revoked_at` | 1회용 소비·폐기 상태 |
| `created_at` | 새 Refresh Token 행 생성 시각 |

ADR-0004에 따라 병합된 `V2__create_auth_account_tables.sql`은 수정하지 않고 새 마이그레이션도 만들지 않는다.

### D8. `refreshToken` 입력 길이는 최대 4,096자다

`RefreshRequest.refreshToken`에는 `@NotBlank`와 `@Size(max = 4096)`를 적용한다.

- 필드 누락, 빈 문자열, 공백 문자열, 4,096자 초과는 Bean Validation에서 400 `VALIDATION_ERROR`로 거부하고 서비스를 호출하지 않는다.
- 1~4,096자의 비어 있지 않은 입력은 서비스의 토큰 검증 대상으로 전달한다.
- 길이 범위 안이지만 변조·만료·폐기·미존재·재사용된 토큰이면 원인과 무관하게 401 `UNAUTHORIZED`다.

---

## File Map

### Production files to create

- `src/main/java/com/finplay/api/auth/dto/request/RefreshRequest.java` — `@NotBlank`, `@Size(max = 4096)`를 적용한 Refresh Token 요청 record.

### Production files to modify

- `src/main/java/com/finplay/api/auth/token/JwtTokenProvider.java` — `parseRefreshToken` 추가.
- `src/main/java/com/finplay/api/auth/repository/RefreshTokenRepository.java` — 해시 조회와 활성 행 조건부 폐기 쿼리 추가.
- `src/main/java/com/finplay/api/auth/service/AuthService.java` — 원자적 `refresh` 유스케이스 추가, 기존 발급 private 메서드 재사용.
- `src/main/java/com/finplay/api/auth/controller/AuthController.java` — `POST /refresh` 200 매핑 추가.
- `src/main/java/com/finplay/api/auth/config/SecurityConfig.java` — `/api/auth/refresh` 공개 POST 경로 추가.

### Test files to create

- `src/test/java/com/finplay/api/auth/repository/RefreshTokenRepositoryTest.java` — 실제 MySQL 조건부 폐기 쿼리.
- `src/test/java/com/finplay/api/auth/service/RefreshTokenIntegrationTest.java` — 로그인 → 회전 → 재사용 거부와 동시 요청 단일 성공.
- `src/test/java/com/finplay/api/auth/service/RefreshTokenRollbackIntegrationTest.java` — 발급 실패 시 기존 폐기 롤백.

### Test files to modify

- `src/test/java/com/finplay/api/auth/token/JwtTokenProviderTest.java`
- `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java`
- `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java`

### Documentation files to modify

- `docs/api-routes.md` — 실제 Controller 매핑과 공개 경로를 추가.
- `docs/specs/002-auth-account/tasks.md` — Refresh 회전 작업을 Issue #6 완료로 표시하고 logout 잔여 범위를 분리.
- `docs/specs/002-auth-account/run-log.md` — 실행 명령과 통과 수준, 미실행 검증을 기록.

### 만들거나 수정하지 않을 파일

- `src/main/resources/db/migration/V2__create_auth_account_tables.sql`
- 새 `V3__*.sql`
- `RefreshToken` 엔티티의 필드 구조
- logout, `/me`, OAuth callback 관련 Controller·Service

---

## Task 1: Refresh JWT 검증 확장

**Files**

- Modify: `src/main/java/com/finplay/api/auth/token/JwtTokenProvider.java`
- Modify: `src/test/java/com/finplay/api/auth/token/JwtTokenProviderTest.java`

**Interface**

- Produces: `Optional<AuthenticatedUser> JwtTokenProvider.parseRefreshToken(String token)`

- [ ] **Step 1: 실패 테스트를 작성한다**
  - `parseRefreshTokenReturnsUserForValidRefreshToken`
  - `parseRefreshTokenReturnsEmptyForAccessToken`
  - `parseRefreshTokenReturnsEmptyForExpiredToken`
  - `parseRefreshTokenReturnsEmptyForTamperedToken`
  - `parseRefreshTokenReturnsEmptyForMalformedToken`
  - 고정 `Clock`과 테스트 시크릿을 사용하고 토큰 원문을 출력하지 않는다.
- [ ] **Step 2: 대상 테스트 실패를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*JwtTokenProviderTest" --no-daemon --max-workers=1
  ```

  Expected: `parseRefreshToken` 부재로 컴파일 또는 새 테스트 FAIL.

- [ ] **Step 3: 최소 구현한다**
  - 기존 JJWT parser와 `AuthenticatedUser`를 재사용한다.
  - `tokenType=REFRESH`만 허용하고 모든 파싱 실패는 `Optional.empty()`로 통일한다.
  - Access 파싱과 공통되는 코드가 세 번째 중복으로 드러나기 전에는 별도 Parser/Manager를 만들지 않는다.
- [ ] **Step 4: 같은 대상 테스트를 다시 실행해 PASS를 확인한다**
- [ ] **Step 5: 논리 커밋한다**

  ```powershell
  git add src/main/java/com/finplay/api/auth/token/JwtTokenProvider.java src/test/java/com/finplay/api/auth/token/JwtTokenProviderTest.java
  git commit -m "feat: Refresh Token 검증 추가"
  ```

---

## Task 2: 해시 조회와 원자적 단일 폐기

**Files**

- Modify: `src/main/java/com/finplay/api/auth/repository/RefreshTokenRepository.java`
- Create: `src/test/java/com/finplay/api/auth/repository/RefreshTokenRepositoryTest.java`

**Interfaces**

- Produces: 해시가 같은 Refresh Token 행 목록 조회
- Produces: `id`, `now`를 받아 활성·미만료 행만 폐기하고 영향 행 수를 반환하는 갱신 메서드

- [ ] **Step 1: 실제 MySQL 실패 테스트를 작성한다**
  - 유효 행의 첫 조건부 폐기는 `1`, 같은 행의 두 번째 폐기는 `0`.
  - 이미 폐기된 행은 `0`.
  - `expiresAt == now`와 과거 만료 행은 `0`.
  - 저장 원문이 아니라 SHA-256 해시로 행을 찾는다.
  - `@DataJpaTest`에 공유 `TestcontainersConfiguration`을 import하고 H2를 사용하지 않는다.
- [ ] **Step 2: 대상 테스트 실패를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*RefreshTokenRepositoryTest" --no-daemon --max-workers=1
  ```

  Expected: 조회·조건부 갱신 메서드 부재로 컴파일 또는 새 테스트 FAIL.

- [ ] **Step 3: Repository 쿼리만 구현한다**
  - `@Modifying` JPQL 또는 native query로 D2의 조건을 그대로 표현한다.
  - 비즈니스 오류 판단은 Repository에 넣지 않고 영향 행 수만 반환한다.
  - 엔티티·스키마·V2 마이그레이션은 수정하지 않는다.
- [ ] **Step 4: 같은 대상 테스트를 다시 실행해 PASS를 확인한다**
- [ ] **Step 5: 논리 커밋한다**

  ```powershell
  git add src/main/java/com/finplay/api/auth/repository/RefreshTokenRepository.java src/test/java/com/finplay/api/auth/repository/RefreshTokenRepositoryTest.java
  git commit -m "feat: Refresh Token 원자 폐기 쿼리 추가"
  ```

---

## Task 3: 회전 서비스와 트랜잭션·동시성 검증

**Files**

- Modify: `src/main/java/com/finplay/api/auth/service/AuthService.java`
- Modify: `src/test/java/com/finplay/api/auth/service/AuthServiceTest.java`
- Create: `src/test/java/com/finplay/api/auth/service/RefreshTokenIntegrationTest.java`
- Create: `src/test/java/com/finplay/api/auth/service/RefreshTokenRollbackIntegrationTest.java`

**Interface**

- Produces: `TokenResponse AuthService.refresh(String rawRefreshToken)`

- [ ] **Step 1: 서비스 실패 테스트를 작성한다**
  - 유효 JWT + 단일 DB 행 + 조건부 폐기 `1`이면 기존 `issueTokenPair` 경로로 새 쌍을 반환하고 새 해시 행을 저장한다.
  - JWT 파싱 실패, 해시 미존재, 해시 중복, JWT subject와 DB 사용자 불일치, 조건부 폐기 `0`은 모두 `UNAUTHORIZED`.
  - 실패 경로에서는 새 토큰을 발급하거나 새 Refresh Token 행을 저장하지 않는다.
  - 새 Refresh Token 원문이 Repository에 전달되지 않고 SHA-256 해시만 저장되는지 검증한다.
- [ ] **Step 2: 단위 테스트 실패를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*AuthServiceTest" --no-daemon --max-workers=1
  ```

  Expected: `refresh` 부재로 컴파일 또는 새 테스트 FAIL.

- [ ] **Step 3: 단일 `@Transactional` 회전 흐름을 구현한다**
  - D1 순서로 JWT·DB 사용자를 검증한다.
  - 조건부 폐기 영향 행이 정확히 `1`일 때만 기존 `issueTokenPair`를 호출한다.
  - 만료·폐기·재사용·경합 패배의 내부 이유를 응답이나 로그로 구분해 노출하지 않는다.
- [ ] **Step 4: 핵심 실제 MySQL 통합 실패 테스트를 작성한다**
  - 로그인 → refresh 성공 → 이전 원문 재사용 401 → 새 원문은 다시 회전 가능.
  - 회전 뒤 기존 행은 폐기되고 새 해시 행 하나만 활성 상태다.
  - `CountDownLatch`로 두 스레드를 함께 출발시켜 같은 원문을 회전하면 결과가 정확히 `성공 1 + UNAUTHORIZED 1`이고 새 활성 행이 하나다.
  - 각 동시 호출은 별도 Spring 트랜잭션에서 실행되도록 서비스 public 메서드를 스레드에서 직접 호출한다. 테스트 메서드 자체에 전체 롤백 `@Transactional`을 붙이지 않는다.
- [ ] **Step 5: 롤백 통합 실패 테스트를 작성한다**
  - 실제 Testcontainers MySQL과 `@MockitoBean JwtTokenProvider`를 사용하는 별도 컨텍스트에서 유효 파싱은 성공시키고 새 쌍 발급은 런타임 예외로 실패시킨다.
  - `AuthService.refresh` 실패 뒤 새 트랜잭션으로 기존 행을 다시 조회해 `revokedAt == null`임을 확인한다.
  - 이후 발급 mock을 정상화해 같은 원문 회전이 성공함을 확인한다.
  - 단순 Mockito 단위 테스트 성공을 DB 롤백 검증으로 표현하지 않는다.
- [ ] **Step 6: 대상 테스트를 순차 실행해 PASS를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*AuthServiceTest" --tests "*RefreshTokenIntegrationTest" --tests "*RefreshTokenRollbackIntegrationTest" --no-daemon --max-workers=1
  ```

- [ ] **Step 7: 논리 커밋한다**

  ```powershell
  git add src/main/java/com/finplay/api/auth/service/AuthService.java src/test/java/com/finplay/api/auth/service
  git commit -m "feat: Refresh Token 회전 원자 처리"
  ```

---

## Task 4: HTTP 계약과 공개 경로

**Files**

- Create: `src/main/java/com/finplay/api/auth/dto/request/RefreshRequest.java`
- Modify: `src/main/java/com/finplay/api/auth/controller/AuthController.java`
- Modify: `src/main/java/com/finplay/api/auth/config/SecurityConfig.java`
- Modify: `src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java`

**Interfaces**

- Consumes: `{"refreshToken":"<Refresh JWT 원문>"}`
- Produces: 200 `TokenResponse`

- [ ] **Step 1: WebMvc 실패 테스트를 작성한다**
  - 유효 요청은 200과 기존 `TokenResponse` 네 필드를 반환한다.
  - 누락·빈 문자열·공백 문자열·4,097자 입력은 400 `VALIDATION_ERROR`이며 서비스를 호출하지 않는다.
  - 4,096자 입력은 Bean Validation을 통과해 서비스에 그대로 전달된다.
  - 1~4,096자의 비어 있지 않은 입력이지만 서비스가 변조·만료·폐기·미존재·재사용으로 판단하면 401 `UNAUTHORIZED` 공통 오류 포맷과 requestId로 응답한다.
  - Access Bearer 헤더 없이 `/api/auth/refresh`를 호출해도 Security에서 선차단되지 않고 Controller에 도달한다.
- [ ] **Step 2: 대상 테스트 실패를 확인한다**

  ```powershell
  .\gradlew.bat test --tests "*AuthControllerTest" --no-daemon --max-workers=1
  ```

  Expected: DTO·Controller 매핑·공개 경로 부재로 컴파일 또는 새 테스트 FAIL.

- [ ] **Step 3: DTO, Controller, Security 공개 경로를 최소 구현한다**
  - 새 Java 파일 첫 줄에 역할을 설명하는 한국어 한 줄 주석을 둔다.
  - `RefreshRequest.refreshToken`에 `@NotBlank`와 `@Size(max = 4096)`를 적용한다.
  - Controller는 `authService.refresh(request.refreshToken())` 호출과 200 반환만 담당한다.
  - 기존 `PUBLIC_POST_PATHS`에 정확한 경로 하나만 추가한다.
- [ ] **Step 4: 같은 대상 테스트를 다시 실행해 PASS를 확인한다**
- [ ] **Step 5: 논리 커밋한다**

  ```powershell
  git add src/main/java/com/finplay/api/auth/dto/request/RefreshRequest.java src/main/java/com/finplay/api/auth/controller/AuthController.java src/main/java/com/finplay/api/auth/config/SecurityConfig.java src/test/java/com/finplay/api/auth/controller/AuthControllerTest.java
  git commit -m "feat: Refresh Token 재발급 API 추가"
  ```

---

## Task 5: 문서 동기화와 최종 게이트

**Files**

- Modify: `docs/api-routes.md`
- Modify: `docs/specs/002-auth-account/tasks.md`
- Modify: `docs/specs/002-auth-account/run-log.md`

- [ ] **Step 1: 실제 Controller 기준으로 API 문서를 갱신한다**
  - `POST /api/auth/refresh` 요청·200 응답·400·401 계약을 추가한다.
  - 인증 규칙 표의 공개 POST 경로에 `/api/auth/refresh`를 추가한다.
  - “Refresh Token 회전은 아직 구현되지 않았다” 문구를 제거하고 logout·`/me` 잔여 범위만 남긴다.
- [ ] **Step 2: spec 작업 상태를 갱신한다**
  - `tasks.md`의 “Refresh 회전·로그아웃” 항목을 회전 Issue #6 완료와 logout Issue #7 잔여가 구분되도록 보강한다.
  - `run-log.md`에 실제 실행 명령, 통과 수준, 미실행 외부 검증과 남은 위험을 기록한다.
- [ ] **Step 3: 포맷을 적용한다**

  ```powershell
  .\gradlew.bat spotlessApply --no-daemon --max-workers=1
  ```

- [ ] **Step 4: 변경 파일과 diff를 확인한다**

  ```powershell
  git status --short
  git diff --check
  git diff -- src/main src/test docs/api-routes.md docs/specs/002-auth-account
  ```

  범위 밖 파일, V2 수정, 새 마이그레이션, logout·`/me`·OAuth callback 구현이 없어야 한다.

- [ ] **Step 5: 전체 빌드를 단독으로 실행한다**

  ```powershell
  .\gradlew.bat build --no-daemon --max-workers=1
  ```

  현재 저장소의 Gradle 동시 실행 실수 기록에 따라 다른 에이전트의 Gradle 프로세스가 끝난 뒤 단독 실행한다. Mock·단위 테스트와 실제 MySQL 통합 테스트의 통과 수준을 구분해 보고한다.

- [ ] **Step 6: 문서와 최종 정리만 논리 커밋한다**

  ```powershell
  git add docs/api-routes.md docs/specs/002-auth-account/tasks.md docs/specs/002-auth-account/run-log.md
  git commit -m "docs: Refresh Token 재발급 계약 동기화"
  ```

---

## 완료 체크리스트

- [ ] `POST /api/auth/refresh`가 200 `TokenResponse`를 반환한다.
- [ ] `refreshToken` 누락·빈 값·4,096자 초과는 400이고, 1~4,096자의 유효 형식 입력은 서비스 검증으로 전달된다.
- [ ] Refresh JWT의 서명·만료·타입·subject와 DB 해시·사용자를 모두 검증한다.
- [ ] 원문 Refresh Token은 DB와 로그에 남지 않는다.
- [ ] 기존 토큰 조건부 폐기와 새 해시 행 저장이 단일 트랜잭션이다.
- [ ] 1~4,096자의 비어 있지 않은 변조·만료·폐기·미존재·재사용·동시 요청 패배가 동일 401이다.
- [ ] 같은 토큰의 동시 회전은 정확히 하나만 성공한다.
- [ ] 새 토큰 발급·저장 실패 시 기존 폐기가 실제 MySQL에서 롤백된다.
- [ ] logout, `/me`, OAuth callback, 스키마 변경을 포함하지 않는다.
- [ ] 실제 Controller 매핑과 `docs/api-routes.md`가 일치한다.
- [ ] 대상 테스트와 `.\gradlew.bat build --no-daemon --max-workers=1` 결과를 새로 확인한다.
