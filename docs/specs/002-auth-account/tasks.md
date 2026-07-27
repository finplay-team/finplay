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
