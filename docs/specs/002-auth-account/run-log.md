# Run Log: 002-auth-account

## Issue #10

### 계획 승인

- 2026-07-26 — 신규 OAuth nickname 생성·충돌 정책과 `OAUTH_AUTHORIZATION_FAILED`(400)·`OAUTH_PROVIDER_ERROR`(502) 오류 분류를 사용자 승인으로 확정했다. 시크릿·토큰·authorization code 값은 기록하지 않았다.

### 검증 기록 계약 (implementer·reviewer가 실행 결과로 갱신)

| 구분 | 공급자/명령 | 결과 | 검증 수준·사유 |
|---|---|---|---|
| 자동 회귀 | Fake OAuth 대상 테스트 | 미실행 (계획 시점) | Fake Provider 결과이며 실제 OAuth와 구분 |
| 전체 게이트 | `.\gradlew.bat build --no-daemon --max-workers=1` | 미실행 (계획 시점) | 실행 시 HEAD와 결과 기록 |
| 실제 OAuth | KAKAO | NOT RUN (계획 시점) | authorize→callback→코드 교환→사용자 정보→신규/기존→JWT와 신규 SocialAccount·계좌 2개 DB 검증을 한 결과/사유로 갱신 |
| 실제 OAuth | NAVER | NOT RUN (계획 시점) | authorize→callback→코드 교환→사용자 정보→신규/기존→JWT와 신규 SocialAccount·계좌 2개 DB 검증을 한 결과/사유로 갱신 |

- 실제 카카오·네이버는 각각 `PASS`여야 Issue #10 PR 완료 조건을 충족한다. 미검증 공급자는 실제 연동 완료로 주장하지 않는다.
- 환경변수·개발자 콘솔 Callback URL·이메일 동의가 부족하면 추측하지 않고 공급자별 `NOT RUN` 사유를 기록한다.
- 브라우저 로그인·동의 단계는 사용자 조작 완료를 기다린다.
- 자동 회귀와 실제 공급자 스모크는 서로 대체하지 않고 run-log와 PR에 별도 기록한다.
- Client ID/Secret, Provider Access Token, authorization code는 명령·결과·로그·PR에 기록하지 않는다.

## Issue #7

### 최종 검증

| 구분 | 실행 명령·근거 | 결과 | 검증 수준 |
|---|---|---|---|
| 서비스 구현 컴파일 | `.\gradlew.bat compileJava --no-daemon --max-workers=1`, 커밋 `d33103f` | `BUILD SUCCESSFUL` (30초) | `AuthService.logout`의 Refresh JWT·해시·소유권 검증과 조건부 폐기 컴파일 |
| Controller 구현 컴파일 | `.\gradlew.bat compileJava --no-daemon --max-workers=1`, 커밋 `e16f9d2` | `BUILD SUCCESSFUL` (18초) | 보호된 `POST /api/auth/logout`와 204 빈 응답 컴파일 |
| 서비스 대상 테스트 | `.\gradlew.bat test --tests "*AuthServiceTest" --no-daemon --max-workers=1` | 27/27 통과 | JWT·DB 사용자 불일치 401, Access·DB 소유권 불일치 403, 조건부 폐기 검증 |
| WebMvc·Security 대상 테스트 | `.\gradlew.bat test --tests "*AuthControllerTest" --tests "*SecurityConfigTest" --no-daemon --max-workers=1` | `AuthControllerTest` 42/42, `SecurityConfigTest` 21/21 통과 | 204·요청 검증·공통 오류와 logout 보호 경로 검증 |
| logout 통합 테스트 | `.\gradlew.bat test --tests "*LogoutIntegrationTest" --no-daemon --max-workers=1` | MySQL 8.4 통합 3/3 통과 | 폐기 후 refresh 401, 타인 토큰 403·상태 보존, 선택 토큰만 폐기 검증 |
| 최종 전체 빌드 | `.\gradlew.bat build --no-daemon --max-workers=1`, HEAD `c6c04f41c33843e98d12c8eb999d7e1ecbc7c56f` | `BUILD SUCCESSFUL` (2분 3초), 13 tasks(9 executed, 4 up-to-date) | 전체 tests·JaCoCo·SpotBugs·Spotless 게이트 통과 |
| 리뷰 | reviewer가 Issue #7 변경 검토 | 코드·테스트 차단 0건, 문서 동기화 1건 | production·테스트 정적 diff와 API 문서 일치 여부 검토 |

### 구현 근거

- `d33103f` — `AuthService.logout`에 Refresh Token 검증·소유권 판정·조건부 폐기를 추가했다.
- `e16f9d2` — 인증이 필요한 `POST /api/auth/logout`와 204 빈 응답을 추가했다.

### 검증 범위와 남은 위험

- 통합 테스트는 MySQL 8.4 Testcontainers를 사용했으며 운영 DB 검증 결과가 아니다.
- 실제 외부 API, 운영 환경 연동, 실행 서버 대상 블랙박스 API QA는 실행하지 않았다.
- reviewer의 문서 동기화 1건은 코드·테스트 차단과 구분되는 문서 범위 지적이다.

## Issue #6

### 최종 검증

증분 비교 기준은 로컬 `dev`가 아니라 `origin/dev`의 `4c399a6`이며, 최종 구현 HEAD는 `e42f0be`다.

| 구분 | 실행 명령·근거 | 결과 | 검증 수준 |
|---|---|---|---|
| JWT 대상 테스트 | `.\gradlew.bat test --tests "*JwtTokenProviderTest" --no-daemon --max-workers=1` | 25/25 통과 | Access·Refresh JWT 서명·만료·변조·토큰 타입·subject 파싱 단위 검증 |
| Repository 대상 테스트 | `.\gradlew.bat test --tests "*RefreshTokenRepositoryTest" --no-daemon --max-workers=1` | 4/4 통과 | MySQL 8.4 Testcontainers 기반 활성 토큰 조건부 폐기 쿼리 검증 |
| 서비스·통합·롤백 대상 테스트 | `.\gradlew.bat test --tests "*AuthServiceTest" --tests "*RefreshTokenIntegrationTest" --tests "*RefreshTokenRollbackIntegrationTest" --no-daemon --max-workers=1` | `AuthServiceTest` 20/20, MySQL 통합 2/2, MySQL 롤백 1/1 통과 | 해시 조회, 단일 회전, 이전 토큰 재사용 401, 동시 요청 단일 성공, 발급 실패 시 폐기 롤백 |
| Controller 대상 테스트 | `.\gradlew.bat test --tests "*AuthControllerTest" --no-daemon --max-workers=1` | 33/33 통과 | 요청 누락·빈 값·4,096자 초과 400, 범위 내 무효 토큰 401, 200 응답과 공개 POST 경로 MVC 검증 |
| 최종 전체 빌드 | `.\gradlew.bat build --no-daemon --max-workers=1`, HEAD `e42f0be` | `BUILD SUCCESSFUL` (2분 17초) | 전체 tests·JaCoCo·SpotBugs·Spotless 게이트 통과 |
| 최초 리뷰 | reviewer가 `origin/dev` `4c399a6` 대비 HEAD `e42f0be` 검토 | production 차단 0건, 문서 동기화 차단 1건 | Controller·서비스·Repository·트랜잭션·동시성 구현 검토. 문서 차단은 후속 동기화에서 반영 |
| 최종 재리뷰 | reviewer가 `origin/dev` `4c399a6` 대비 HEAD `c050aff` 전체 diff 재검토 | 차단 0건, 권장 0건, 참고 2건. 이전 문서 차단 해소 확인 | 문서 동기화 포함 최종 정적 diff 검토. 이 재리뷰에서는 Gradle·블랙박스 QA를 다시 실행하지 않음 |

### 구현 근거

- `6abfe0d` — Refresh JWT 검증 추가.
- `9e2d538` — 활성·미만료 Refresh Token의 조건부 원자 폐기 쿼리 추가.
- `3349c40` — 폐기와 새 토큰 발급·해시 저장의 단일 트랜잭션, 재사용·동시 요청·롤백 검증 추가.
- `e42f0be` — `POST /api/auth/refresh`, 최대 4,096자 Bean Validation, 공개 POST 경로 추가.

### 검증 범위와 남은 위험

- Repository·통합·롤백 테스트는 MySQL 8.4 Testcontainers를 사용했으며 운영 DB 검증 결과가 아니다.
- 운영 환경, 외부 API 연동, 실행 서버 대상 블랙박스 API QA는 실행하지 않았다.
- logout은 Issue #7, `GET /api/auth/me`는 Issue #8의 잔여 범위다.
- 최종 재리뷰의 참고 2건은 차단·권장 사항이 아니며, 재리뷰 시 Gradle·QA는 재실행하지 않았다.

## Issue #5

### 최종 검증

| 구분 | 실행 명령·근거 | 결과 | 검증 수준 |
|---|---|---|---|
| 최초 전체 빌드 | `.\gradlew.bat build --no-daemon --max-workers=1`, HEAD `61cd74f` | `BUILD SUCCESSFUL` (1분 40초) | Issue #5 당시 전체 Gradle 게이트 통과 |
| 직전 전체 테스트 | 전체 테스트 실행 | 201개 중 기존 test helper 관련 1건 실패. 원인을 수정했으며 이 실행 자체는 통과로 간주하지 않음 | 실패 이력을 보존한 회귀 테스트 실행 |
| JWT 대상 테스트 | `.\gradlew.bat test --tests "*JwtTokenProviderTest" --no-daemon --max-workers=1` | 16/16 통과 | 발급별 `jti`, 만료·변조·토큰 타입 검증 |
| 로그인 통합 테스트 | `.\gradlew.bat test --tests "*LoginIntegrationTest" --no-daemon --max-workers=1` | 5/5 통과 | MySQL Testcontainers 기반 로그인·Refresh Token 해시 저장 통합 검증 |
| 최종 전체 빌드 | `.\gradlew.bat build --no-daemon --max-workers=1`, HEAD `7915077` | `BUILD SUCCESSFUL` (16초), 13 tasks. Spotless 실행, test·coverage·SpotBugs는 up-to-date | 최종 소스 상태의 전체 Gradle 게이트 |
| 최종 리뷰 | reviewer 최종 검토 | 차단 0건 | Issue #5 범위 코드·계약 검토 |

### 수정 근거

- `4d8716f` — JWT 발급별 고유 `jti`를 추가해 토큰 차단 문제를 수정했다.
- `7915077` — JWT 서명 변조 테스트를 안정화했으며 최종 검증 HEAD다.

### 검증 범위와 남은 위험

- `LoginIntegrationTest`는 MySQL Testcontainers를 사용했으며 운영 DB를 검증한 결과가 아니다.
- 403 `RestAccessDeniedHandler`의 전용 계약 테스트는 없다. 현재 reviewer 비차단 권장 사항으로 남긴다.
- Refresh Token 회전·폐기·재사용 거부, logout, `GET /api/auth/me`는 Issue #5 범위 밖이며 아직 검증하지 않았다.

## Issue #4

### 최종 검증

| 구분 | 실행 명령 | 결과 | 검증 수준 |
|---|---|---|---|
| 대상 테스트 | `.\gradlew.bat test --tests "*JwtTokenProviderTest" --tests "*AccountServiceTest" --tests "*AuthServiceTest" --tests "*AuthControllerTest" --tests "*SignupPersistenceRepositoryTest" --tests "*SignupIntegrationTest"` | `BUILD SUCCESSFUL` (68.4초), XML 6 suites·38 tests·failures 0·errors 0·skipped 0 | JWT·계좌·가입 서비스 단위 테스트, MVC 슬라이스, MySQL 8.4·Redis Testcontainers 기반 영속성·통합 테스트 |
| 전체 빌드 1차 | `.\gradlew.bat build` | 실패: SpotBugs `CT_CONSTRUCTOR_THROW` 1건 | `JwtTokenProvider`를 `final`로 수정한 커밋 `0c134b8` 후 `spotbugsMain` warnings 0 확인 |
| 전체 빌드 2차 | `.\gradlew.bat build` | 실패: mixed EOL로 `spotlessJavaCheck` 실패 | `.\gradlew.bat spotlessApply`로 줄바꿈 정규화 |
| 최종 전체 빌드 | `.\gradlew.bat build --rerun-tasks` | `BUILD SUCCESSFUL` (1분 38초), XML 20 suites·98 tests·failures 0·errors 0·skipped 0, HEAD `4f5ac48` | Spotless·SpotBugs·JaCoCo 통과. SHA-256 사전 해시+BCrypt 장문·다바이트와 실제 MySQL 소비 후 롤백·동일 토큰 재시도·2-thread 경합 검증 포함 |

### 검증 범위와 남은 위험

- 대상 테스트는 MySQL 8.4와 Redis Testcontainers를 사용했으며 운영 DB나 운영 Redis를 검증한 결과가 아니다.
- 실제 Resend 메일 발송, 외부 API, 운영 환경 연동은 실행하지 않았다.
- SpotBugs 분석에는 비차단 `org.jetbrains.annotations.Nullable` missing auxiliary class 메시지가 남아 있다.

## Issue #3 Task 3

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 20:35 | main | `.\gradlew.bat build` | Issue #3 전체 컴파일·테스트·Spotless·SpotBugs·JaCoCo 게이트, SHA `bdb888b` |

## Issue #3 Task 2

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 20:26 | implementer | `.\gradlew.bat compileJava` | Task 2 HTTP 계약, Bean Validation, ADR-0002 controller-service 경계, API 경로 문서 동기화 |

## Issue #3 Task 1

| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 20:07 | implementer | `.\gradlew.bat compileJava` | plan.md 인증번호 확인·가입 토큰 발급, ADR-0002 service 트랜잭션 경계, conventions DTO·엔티티 상태 전이 규칙 |

## AI 로그 (에이전트 참조용)
| 시각 | 에이전트 | 실행 명령 | 근거 |
|---|---|---|---|
| 14:30 | implementer | `.\gradlew.bat compileJava` | plan.md 데이터 모델·구성요소 설계, ADR-0002·ADR-0004 |
| 15:10 | implementer | `.\gradlew.bat compileJava` | plan.md 구성요소 설계(EmailSender 어댑터·프로필 분기), conventions.md 시크릿 규칙 |
| 15:45 | implementer | `.\gradlew.bat compileJava` | spec AUTH-004·plan API설계(발송 제한·HMAC·이전 코드 무효화), conventions dto/레이어 규칙, ClockConfig 주입 |
| 16:10 | implementer | `.\gradlew.bat test --tests "*GlobalExceptionHandlerTest" --tests "*EmailVerificationControllerTest"` | @WebMvcTest 슬라이스 스캔 원리(무지정 시 전체 컨트롤러 스캔), 이슈 #32 기존 테스트 |
| 16:40 | reviewer(리뷰) | `git diff dev...HEAD` | conventions.md(시크릿·레이어·DTO), ADR-0002·0003·0004, spec/plan AUTH-004 |
| 18:50 | implementer | `.\gradlew.bat test --tests "*FinPlayApiApplicationTests" --tests "*EmailVerificationServiceTest" --tests "*EmailVerificationControllerTest"` | 리뷰 차단 반영: conventions.md 시크릿 규칙(기본값 금지·fail-fast), JWT_SECRET 패턴 |
| 19:00 | implementer | `.\gradlew.bat build` | 시크릿 주입 중앙화: build.gradle Test 태스크에 더미 env 일괄 공급, @DynamicPropertySource 중복 제거(fail-fast 유지) |
| 21:13 | implementer | `.\gradlew.bat compileJava` | issue-9-plan.md Task 1 Provider 계약·프로필별 URI, ADR-0002·conventions.md |
| 21:18 | implementer | `.\gradlew.bat compileJava --rerun-tasks` | issue-9-plan.md Task 2 SecureRandom state·10분 보안 쿠키 계약 |
| 21:21 | implementer | `.\gradlew.bat compileJava --rerun-tasks` | issue-9-plan.md Task 3 service·302 Controller·라우트 계약 |
| 21:30 | implementer | `.\gradlew.bat compileJava --rerun-tasks` | 리뷰 차단: 실제 OAuth 프로필 Secure 쿠키 강제·Provider 설정 fail-fast |
| 21:53 | 메인 | `.\gradlew.bat build --no-daemon --max-workers=1` | HEAD `35a9feeb00f2330053de8c88b7667b6d1354a538` 최종 게이트, BUILD SUCCESSFUL |
| 17:11 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` | issue-5-plan.md Task 1(D4 Optional 반환·주입 Clock 만료 판정), conventions.md 파일 주석·record 규칙, ADR-0002 |
| 17:20 | implementer | `.\gradlew.bat test --tests "*AuthControllerTest" --tests "*EmailVerificationControllerTest" --tests "*OAuthAuthorizationControllerTest" --tests "*GlobalExceptionHandlerTest" --no-daemon --max-workers=1` | issue-5-plan.md Task 2(D2 필터 순서·D3 핸들러 직접 직렬화·D4), Boot 4.1 실제 자동설정 경로 확인(`SecurityFilterProperties`·`ServletWebSecurityAutoConfiguration`) |
| 17:30 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` | issue-5-plan.md Task 4(D6 issueTokenPair 추출·D7 동일 401·D9 기존 토큰 유지), conventions.md 레이어·트랜잭션 경계 |
| 17:37 | implementer | `.\gradlew.bat compileJava --no-daemon --max-workers=1` | issue-5-plan.md Task 5 HTTP 계약 표(비밀번호 min 금지·D8 200 응답), conventions.md DTO 검증 메시지 규칙, CLAUDE.md 7(api-routes 동기화) |
| 18:01 | implementer | `.\gradlew.bat spotbugsMain --no-daemon --max-workers=1` | SpotBugs EI_EXPOSE_REP2 2건 해소: conventions.md Lombok 표(component는 `@RequiredArgsConstructor`), exclude.xml 첫 줄 규칙(재현된 오탐만 제외) |

## 모니터링 (사람용 요약)
- 14:30 — V2 마이그레이션(auth 5개 테이블) + User·EmailVerification 엔티티/Repository 추가, 컴파일 통과.
- 15:10 — EmailSender 어댑터(Fake=`!prod`·Resend=`prod` RestClient) 추가, prod yml에 resend/email 설정, 컴파일 통과.
- 15:45 — 인증번호 발송 API(`POST /api/auth/email-verifications`) 추가: Controller·EmailVerificationService(발송 제한 3종·HMAC 저장·이전 코드 무효화)·요청 DTO, api-routes 갱신, 컴파일 통과.
- 16:10 — 전체 스위트에서 깨지던 GlobalExceptionHandlerTest를 `@WebMvcTest(controllers = TestController.class)`로 슬라이스 한정, 두 테스트 클래스 통과 확인.
- 16:40 — 리뷰 완료(발송 API 범위). 차단 1건: EMAIL_VERIFICATION_SECRET 코드 내 dev 기본값(시크릿 컨벤션 위반·prod fail-open). 권장 2·참고 2.
- 18:50 — 차단 수정: EMAIL_VERIFICATION_SECRET `@Value` 기본값 제거(fail-fast), contextLoads 테스트에만 `@DynamicPropertySource`로 더미 시크릿 공급. 3개 테스트 통과.
- 19:00 — @SpringBootTest 확장성 문제 해결: 더미 시크릿을 build.gradle Test 태스크 env로 중앙화하고 `@DynamicPropertySource` 제거, 전체 `build` BUILD SUCCESSFUL.
- 21:13 — OAuth Provider 계약과 카카오·네이버 실제/Fake 프로필 구현, 환경변수 바인딩 추가, 컴파일 통과.
- 21:18 — 32바이트 URL-safe/no-padding OAuth state 생성기와 callback 경로 한정 10분 보안 쿠키 팩토리 추가, 컴파일 통과.
- 21:21 — OAuth 인가 service와 302 Controller 추가, 환경변수 예시·API 라우트 동기화, 컴파일 통과.
- 21:30 — prod·oauth-real에서 state 쿠키 Secure=false 기동 차단, 실제 Provider 필수 설정 공백 검증 추가, 컴파일 통과.
- 21:53 — HEAD `35a9feeb00f2330053de8c88b7667b6d1354a538`에서 직렬 최종 build를 실행해 BUILD SUCCESSFUL 확인.
- 17:11 — Issue #5 Task 1: `AuthenticatedUser` record와 `JwtTokenProvider.parseAccessToken`(주입 Clock 기반 만료 판정, 실패 시 `Optional.empty()`) 추가, 컴파일 통과.
- 17:20 — Issue #5 Task 2: Spring Security 체인·Bearer 필터·401/403 핸들러 추가, `RequestIdFilter`에 `@Order` 부여, 기존 슬라이스 4종 정리. `spring-boot-starter-security-test`가 없으면 `@WebMvcTest`에 Security 체인이 아예 붙지 않는 것을 실측해 test 의존성으로 추가.
- 17:30 — Issue #5 Task 4: `AuthService.login`(원인 불문 동일 401, 기존 Refresh Token 유지) 추가하고 signup 말미 발급 블록을 `issueTokenPair` private 메서드로 추출해 공유, 컴파일 통과.
- 17:37 — Issue #5 Task 5: `LoginRequest`(비밀번호 최소 길이 미적용)와 `POST /api/auth/login`(200) 추가, api-routes.md에 라우트·로그인 상세 표 반영, 컴파일 통과.
- 18:01 — 401·403 핸들러 2종의 수기 생성자를 `@RequiredArgsConstructor`로 교체(conventions Lombok 규칙 준수)해 SpotBugs EI_EXPOSE_REP2 2건 해소, `spotbugsMain` Total Warnings 0. exclude.xml·새 의존성은 추가하지 않았다.
