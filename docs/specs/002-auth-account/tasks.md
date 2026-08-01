# Tasks: 인증과 계좌

구현 작업 후보는 9개다. 실제 GitHub Issue의 굵기(API별 또는 기능 묶음별)는 팀 회의에서 결정한다. Issue 하나가 여러 작업 항목이나 여러 논리적 커밋을 포함할 수 있으며, 아직 9개 Issue로 확정하지 않는다.

- [ ] 마이그레이션(users·social_accounts·refresh_tokens·accounts·email_verifications) + 엔티티 + Repository (+ @DataJpaTest UNIQUE 제약 검증)
- [ ] EmailSender 인터페이스 + FakeEmailSender + ResendEmailSender(RestClient, 운영 프로필 전용 빈) — RESEND_API_KEY·EMAIL_FROM 없이 기동·테스트가 통과하는지 확인 (+ 단위 테스트)
- [ ] 인증번호 발송 API — 발송 제한(60초·1시간 5회·하루 10회)·기존 회원 409·이전 코드 무효화·HMAC-SHA-256 저장 (+ 단위·@WebMvcTest)
- [x] 인증번호 확인 API — 시도 5회 제한과 초과 시 429·만료 판정·signupVerificationToken 발급(SHA-256 저장) (+ 단위·@WebMvcTest)
- [x] 회원가입 API — 가입 토큰 원자 소비·토큰 이메일 일치 검증·중복 409·계좌 2개 원자 생성 (+ 단위·@WebMvcTest)
- [x] JWT·Security — JwtTokenProvider + Bearer 인증 필터 + 401/403 공통 포맷 + 로그인·GET /api/auth/me (+ 단위·@WebMvcTest) — **Issue #5에서 로그인·Bearer 인증 완료, Issue #8에서 `GET /api/auth/me`(id·email·nickname·signupMethod, 회원 간 격리, 민감 필드 미노출) 완료, Issue #54에서 `PATCH /api/auth/me/nickname`(재인증 기반 닉네임 변경) 완료**
- [x] Refresh 회전·로그아웃 — 해시 저장·폐기·재사용 거부
  - [x] **Issue #6 완료:** `POST /api/auth/refresh`, 해시 조회·원자 회전·이전 토큰 재사용 401·동시 요청 단일 성공·롤백 검증
  - [x] **Issue #7 완료:** logout 시 제출한 Refresh Token 폐기
- [ ] OAuth 어댑터 — OAuthProvider 인터페이스 + Kakao·Naver·Fake 구현 + authorize/callback API + 신규 가입 트랜잭션 + OAUTH_EMAIL_REQUIRED(400)·ACCOUNT_LINK_REQUIRED(409) 분기 (+ 단위 테스트)
- [ ] 통합 테스트(인증→가입→계좌 2개, 가입 토큰 재사용 거부, 가입 실패 후 같은 토큰 재시도 성공, Fake OAuth 신규 가입·기존 이메일 409 거부, 토큰 회전) + docs/api-routes.md 갱신 — **Issue #6에서 토큰 회전 MySQL 통합·롤백 테스트와 라우트 동기화 완료, 나머지 시나리오 잔여**

## Issue #10 OAuth callback 작업 항목 (5개)

- [x] callback state 상수 시간 검증·정상 브라우저 callback 응답의 state 쿠키 만료·공개 GET Controller/Security 계약 구현 — 서버 state 저장 기반 raw cookie 재전송 차단은 범위 밖
- [x] PR #49 후속: Fake authorize의 state별 고유 code 발급과 thread-safe store 기반 `(provider, code, state)` 원자적 1회 소비·재사용/동시성 400, 잘못된 provider 거부·grant 비소비 구현 — 기존 Kakao/Naver 실제 Provider 계약은 변경하지 않음
- [x] `finplay-` + 무작위 소문자 hex 12자리 nickname(식별정보 미포함, 충돌 시 최대 5회 재생성)과 기존 소셜 로그인·신규 User·SocialAccount·계좌 2개·Refresh Token 원자 트랜잭션 및 롤백 구현
- [x] Fake OAuth generated code 1회/재사용/동시성·교차-provider 거부 후 원 provider 1회 성공·authorize URI 고유 code·첫 callback 200 후 동일 `(provider, code, state)`와 raw cookie 재전송 400 및 DB 불변 회귀, 전체 build, API 문서 동기화
- [x] `oauth-real` 카카오·네이버 각각 authorize→callback→코드 교환→사용자 정보→신규/기존→FinPlay JWT 및 신규 SocialAccount·계좌 2개 DB 스모크 검증 — **KAKAO·NAVER PASS. 두 공급자 모두 기존 회원 2차 응답 본문은 Chrome `ERR_BLOCKED_BY_CLIENT`로 직접 확인하지 못한 제한을 run-log에 기록**

Issue #10 완료 시 자동 테스트와 실제 공급자 스모크를 별도 기록한다. 실제 카카오·네이버는 각각 `PASS`여야 하며, 환경변수·개발자 콘솔 Callback URL·이메일 동의 부족 시 추측하지 않고 공급자별 `NOT RUN` 사유를 남긴다. 브라우저 로그인·동의는 사용자 조작을 기다린다. Client ID/Secret, Provider Access Token, authorization code는 기록하지 않는다.

## Issue #53 OAuth 재인증 토큰 작업 항목 (5개)

상세 설계는 `issue-53-plan.md` 참고.

- [x] state 서명·검증 계약(`OAuthPurpose`·`OAuthStateClaims`·`OAuthStateGenerator.generate/verify`)과 `REAUTHENTICATION_FAILED`(403) 오류 코드, `OAUTH_STATE_SECRET` 환경변수 추가 — 기존 `authorize` 302 계약은 변경 없음
- [x] `reauth_tokens` Flyway 마이그레이션·`ReauthToken`·`ReauthTokenRepository`·`ReauthTokenGenerator`와 `AuthService.reauthenticate`(동일 계정 성공/다른 계정·미연결 provider 거부, 회원·계좌·시드머니 불변) 구현
- [x] `OAuthCallbackService`의 purpose 분기(LOGIN→기존 `oauthLogin` 그대로, REAUTH→`reauthenticate`)와 `ReauthTokenResponse` 응답 계약, Fake OAuth 기반 성공·거부 자동 테스트
- [x] `purpose=reauth` authorize 엔드포인트(`OAuthAuthorizationService.authorizeForReauth`, 컨트롤러 `params="purpose=reauth"`)와 `SecurityConfig`의 purpose 기반 인증 분기(reauth만 Bearer 필수)
- [x] 전체 회귀(Issue #9/#10 포함)·`./gradlew build`·`docs/api-routes.md`·`docs/specs/002-auth-account/tasks.md` 동기화

Issue #53은 `reauthToken` 발급까지만 구현한다. 소비(닉네임·이메일 변경 API)는 별도 후속 이슈다.

## Issue #54 재인증 기반 닉네임 변경 작업 항목 (5개)

상세 설계는 `issue-54-plan.md` 참고.

- [x] `ReauthTokenRepository.consumeIfValidForUser`(해시·소유자·미소비·미만료를 한 번의 조건부 UPDATE로 확인)와 `UserRepository.existsByNicknameAndIdNot` 본인 제외 중복 확인 쿼리 추가 (+ `@DataJpaTest`)
- [x] `User.changeNickname`(setter 금지, 닉네임·`updatedAt`만 갱신)과 `AuthService.changeNickname`(DB의 `social_accounts`로 가입 방식 판별, 이메일은 비밀번호 대조·OAuth는 토큰 원자적 소비, 재인증 검증과 닉네임 변경을 한 트랜잭션으로 처리) 구현 (+ 단위 테스트)
- [x] `NicknameUpdateRequest`와 보호된 `PATCH /api/auth/me/nickname`(200 `MemberResponse`, 400/401/403/409 계약, `SecurityConfig` 미변경) 구현 (+ `@WebMvcTest`)
- [x] 실제 MySQL 통합 검증 — 이메일/OAuth 각각 성공, 같은 `reauthToken` 재사용 403, 잘못된 비밀번호·중복 닉네임 거부 시 무변경, 계좌 2개·시드머니·잔액 불변
- [x] 전체 회귀·`./gradlew build`·`docs/api-routes.md`·`docs/specs/002-auth-account/tasks.md` 동기화

Issue #54는 `reauthToken`을 처음으로 소비하는 사례이며 새 Flyway 마이그레이션·새 `ErrorCode`를 추가하지 않는다. 주문·체결(Order/Execution) 불변 검증은 해당 도메인이 아직 없어 계좌까지로 범위를 좁혔다. 이메일 변경·비밀번호 변경은 AUTH-005의 별도 후속 이슈다.

## Issue #55 새 이메일 변경 인증번호 발송 작업 항목 (4개)

상세 설계는 `issue-55-plan.md` 참고.

- [x] `email_change_verifications` Flyway 마이그레이션(V6)·`EmailChangeVerification` 엔티티·`EmailChangeVerificationRepository`와 `ReauthTokenRepository.consumeIfValidForUser(tokenHash, userId, now)` 추가(Issue #54와 조율된 공유 설계) 구현
- [x] `EmailChangeService.requestEmailChange` — EMAIL 회원 비밀번호 검증/OAuth 회원 `reauthToken` 소비 검증(실패 시 403 `REAUTHENTICATION_FAILED` 통일)·새 이메일 중복 409·발송 제한(60초·1시간 5회·하루 10회) 429·재발송 시 같은 회원+같은 새 이메일 이전 코드 무효화 구현
- [x] `EmailChangeController`·`EmailChangeRequest`(`POST /api/auth/email-changes`, 202) 구현 및 `@WebMvcTest`로 성공·검증 실패 400·인증 없음 401·403/409/429 상태 매핑 검증
- [x] Fake `EmailSender` + Testcontainers MySQL 통합 테스트(EMAIL/OAuth 각 성공 흐름, 실패 시 기존 `users`·`accounts`·`orders`·`executions` 불변)·전체 회귀·`./gradlew build`·`docs/api-routes.md`·`docs/specs/002-auth-account/tasks.md` 동기화

Issue #55은 인증번호 발송까지만 구현한다. 확인·실제 `users.email` 변경·Refresh Token 폐기는 별도 후속 이슈다.

## Issue #56 이메일 변경 확인 및 적용 작업 항목 (4개)

상세 설계는 `issue-56-plan.md` 참고.

- [x] `EmailChangeVerification.incrementAttemptCount()`·`consume(now)`, `EmailChangeVerificationRepository.findFirstByUserIdAndNewEmailOrderByCreatedAtDesc`, `RefreshTokenRepository.revokeAllActiveByUserId(userId, now)`, `User.changeEmail(newEmail, now)` 추가
- [x] `EmailChangeService.validateAndConsumeCode` — 요청 없음/소비됨/만료 → 5회 초과(즉시 만료+429) → 코드 불일치 검증 순서 구현(트랜잭션 경계 없음, 호출자 트랜잭션에 편입)
- [x] `AuthService.confirmEmailChange`(`@Transactional(noRollbackFor=BusinessException.class, rollbackFor=EmailChangeConflictException.class)`) — 검증·소비 → `users.email` 변경 → 기존 Refresh Token 전체 폐기, 신규 `EmailChangeConflictException`(코드베이스 최초 `BusinessException` 서브타입)으로 동시 이메일 경합 시 전체 롤백. `EmailChangeController`(`POST /api/auth/email-changes/confirm`, 200 `MemberResponse`) 및 `@WebMvcTest` 검증
- [x] Testcontainers MySQL 통합 테스트(발송→확인 성공, 재사용·5회초과·만료·재발송무효화·타인요청 격리, 확인 성공 후 기존 Refresh Token 401, 계좌·주문·체결·OAuth 연결 불변, 동시 이메일 경합 409+원자적 롤백)·전체 회귀·`./gradlew build`·`docs/api-routes.md`·`docs/specs/002-auth-account/tasks.md` 동기화

Issue #56은 트랜잭션 롤백 정책(새 예외 서브타입으로 `noRollbackFor`/`rollbackFor` depth 매칭 분리)과 서비스 배치(`EmailChangeService`는 인증번호 검증·소비 전용으로 축소, `users.email` 변경과 Refresh Token 폐기는 `AuthService`가 담당)를 사용자 확인을 받아 확정했다 — 상세 근거는 `issue-56-plan.md`의 "미확정·PRD 불일치" 절 참고.

## Issue #114 현재 비밀번호 확인 기반 비밀번호 변경 작업 항목 (4개)

상세 설계는 `issue-114-plan.md` 참고.

- [x] `User.changePassword(newPasswordHash, now)`(setter 금지, `passwordHash`·`updatedAt`만 갱신)와 `AuthService.changePassword`(DB의 `social_accounts`로 OAuth 전용 회원 400 거부 → 기존 `verifyCurrentPassword`로 현재 비밀번호 대조 403 → 새 비밀번호 동일 400 → 해시 교체 → 기존 Refresh Token 전체 폐기 → 요청 기기용 새 토큰 쌍 발급을 한 트랜잭션으로 처리) 구현 (+ 단위 테스트, 폐기→발급 순서를 `InOrder`로 고정)
- [x] `PasswordChangeRequest`(`currentPassword` 필수·최대 100자, `newPassword` 가입과 동일한 8~100자 정책)와 보호된 `PATCH /api/auth/me/password`(200 `TokenResponse`, 400/401/403 계약, `SecurityConfig` 미변경) 구현 (+ `@WebMvcTest`)
- [x] 실제 MySQL 통합 검증 — 변경 후 신규 비밀번호 로그인 성공·기존 비밀번호 401, 다른 기기 Refresh Token 폐기와 응답 토큰 유효, 잘못된 현재 비밀번호·동일 새 비밀번호·OAuth 전용 회원 거부 시 무변경 롤백, 이메일·닉네임·계좌 2개·시드머니·잔액 불변
- [x] `docs/prd.md` AUTH-005에 비밀번호 변경 항목 추가·`docs/api-routes.md`·`docs/api-contracts.md`·`docs/specs/002-auth-account/tasks.md` 동기화·전체 회귀·`./gradlew build`

Issue #114는 착수 전 결정 2건을 팀에서 확정하고 시작했다. ① 변경 성공 시 기존 Refresh Token을 전부 폐기하되 요청한 기기용 새 토큰 쌍을 즉시 발급해 **다른 기기만 로그아웃**시킨다(#56의 "전부 폐기 후 재로그인"과 의도적으로 다르다). ② OAuth 전용 회원은 400 `VALIDATION_ERROR` + "OAuth 전용 회원은 비밀번호를 변경할 수 없습니다."로 거부한다(재인증 실패가 아니라 계정 유형 불일치). 근거는 `issue-114-plan.md`의 D2·D5와 "미확정·PRD 불일치" 절 참고. PRD AUTH-005에는 비밀번호 변경 항목이 아직 없어 이번 이슈에서 함께 추가한다. 비밀번호 분실 재설정은 Issue #115·#116, OAuth 전용 회원의 비밀번호 최초 설정은 범위 밖이다.
## Issue #115 비밀번호 재설정 인증번호 발송 작업 항목 (5개)

상세 설계는 `issue-115-plan.md` 참고.

- [x] `password_reset_verifications` Flyway 마이그레이션(V12, `user_id` FK 없음·`code_hash`/`expires_at`/`last_sent_at` NULL 허용)·`PasswordResetVerification` 엔티티(`create`/`createRejected`/`expire`)·`PasswordResetVerificationRepository`(거부 행 포함 집계, 거부 행 제외 무효화 대상 조회)와 OAuth 전용 회원 거부용 신규 409 `ErrorCode`, `PASSWORD_RESET_SECRET` 환경변수 배선(`.env.example`·`build.gradle` 테스트 환경·`deploy/README.md`) 구현 (+ `@DataJpaTest`) — 신규 `ErrorCode`는 팀 확정대로 `SOCIAL_ACCOUNT_ONLY(HttpStatus.CONFLICT, "소셜 로그인 전용 계정입니다. 카카오 또는 네이버 로그인을 이용해 주세요.")`
- [x] `PasswordResetService.sendResetCode` — 발송 제한(60초·1시간 5회·하루 10회, 이메일 단위) 429 → 미가입 404 `NOT_FOUND` → 비밀번호 없는 OAuth 전용 회원 409 판정 순서, 거부 요청의 집계 행 기록(`@Transactional(noRollbackFor = BusinessException.class)`), 재발송 시 이전 코드 무효화, 전용 시크릿 HMAC-SHA-256 저장 후 발송 구현 (+ 단위 테스트)
- [x] `PasswordResetRequest`·`PasswordResetController`(`POST /api/auth/password-resets`, 202 본문 없음)와 `SecurityConfig.PUBLIC_POST_PATHS` 공개 경로 추가 구현 및 `@WebMvcTest`로 202·400·404/409/429 매핑과 비인증 접근 허용 검증
- [ ] Fake `EmailSender` + Testcontainers MySQL 통합 테스트(원문 미저장·해시만 저장, 거부 행 커밋 후 후속 요청 429, 발송 실패 시 저장·무효화 동반 롤백, `users`·`accounts`·`refresh_tokens` 불변)·전체 회귀·`./gradlew build`
- [ ] `docs/prd.md`에 `AUTH-006` 신설·§5 공통 오류표 신규 409 코드·엔드포인트 목록 추가와 `docs/api-routes.md`·`docs/api-contracts.md`·`docs/specs/002-auth-account/tasks.md` 동기화

Issue #115는 인증번호 발송까지만 구현한다. 확인·실제 `users.password_hash` 교체·Refresh Token 폐기는 후속 이슈 #116이다. `User` 엔티티를 건드리지 않으므로 Issue #114(`User.changePassword`)에 의존하지 않는다. 착수 전 확정된 결정 3건(미가입 404·OAuth 전용 409로 계정 열거 감수, 이메일 단위 발송 제한과 거부 요청 집계 포함, 전용 `PASSWORD_RESET_SECRET`)의 근거와 대가는 `issue-115-plan.md`의 D2·D4·D7에 있다. 미가입 404는 `NOT_FOUND` 코드에 "가입되지 않은 이메일입니다." 메시지를 덮어써 응답한다.
