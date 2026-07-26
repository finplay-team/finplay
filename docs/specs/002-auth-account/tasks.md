# Tasks: 인증과 계좌

구현 작업 후보는 9개다. 실제 GitHub Issue의 굵기(API별 또는 기능 묶음별)는 팀 회의에서 결정한다. Issue 하나가 여러 작업 항목이나 여러 논리적 커밋을 포함할 수 있으며, 아직 9개 Issue로 확정하지 않는다.

- [ ] 마이그레이션(users·social_accounts·refresh_tokens·accounts·email_verifications) + 엔티티 + Repository (+ @DataJpaTest UNIQUE 제약 검증)
- [ ] EmailSender 인터페이스 + FakeEmailSender + ResendEmailSender(RestClient, 운영 프로필 전용 빈) — RESEND_API_KEY·EMAIL_FROM 없이 기동·테스트가 통과하는지 확인 (+ 단위 테스트)
- [ ] 인증번호 발송 API — 발송 제한(60초·1시간 5회·하루 10회)·기존 회원 409·이전 코드 무효화·HMAC-SHA-256 저장 (+ 단위·@WebMvcTest)
- [x] 인증번호 확인 API — 시도 5회 제한과 초과 시 429·만료 판정·signupVerificationToken 발급(SHA-256 저장) (+ 단위·@WebMvcTest)
- [x] 회원가입 API — 가입 토큰 원자 소비·토큰 이메일 일치 검증·중복 409·계좌 2개 원자 생성 (+ 단위·@WebMvcTest)
- [ ] JWT·Security — JwtTokenProvider + Bearer 인증 필터 + 401/403 공통 포맷 + 로그인·GET /api/auth/me (+ 단위·@WebMvcTest) — **Issue #5에서 로그인·Bearer 인증 완료, `GET /api/auth/me` 잔여(Issue #8)**
- [x] Refresh 회전·로그아웃 — 해시 저장·폐기·재사용 거부
  - [x] **Issue #6 완료:** `POST /api/auth/refresh`, 해시 조회·원자 회전·이전 토큰 재사용 401·동시 요청 단일 성공·롤백 검증
  - [x] **Issue #7 완료:** logout 시 제출한 Refresh Token 폐기
- [ ] OAuth 어댑터 — OAuthProvider 인터페이스 + Kakao·Naver·Fake 구현 + authorize/callback API + 신규 가입 트랜잭션 + OAUTH_EMAIL_REQUIRED(400)·ACCOUNT_LINK_REQUIRED(409) 분기 (+ 단위 테스트)
- [ ] 통합 테스트(인증→가입→계좌 2개, 가입 토큰 재사용 거부, 가입 실패 후 같은 토큰 재시도 성공, Fake OAuth 신규 가입·기존 이메일 409 거부, 토큰 회전) + docs/api-routes.md 갱신 — **Issue #6에서 토큰 회전 MySQL 통합·롤백 테스트와 라우트 동기화 완료, 나머지 시나리오 잔여**

## Issue #10 OAuth callback 작업 항목 (5개)

- [x] callback state 상수 시간 검증·성공/실패 state 쿠키 만료·공개 GET Controller/Security 계약 구현
- [x] Fake·Kakao·Naver callback Provider의 코드 교환·사용자 정보, 사용자 취소·만료/재사용 code 400, 공급자 장애·timeout·malformed response 502, 프로필/환경변수 fail-fast 구현
- [x] `finplay-` + 무작위 소문자 hex 12자리 nickname(식별정보 미포함, 충돌 시 최대 5회 재생성)과 기존 소셜 로그인·신규 User·SocialAccount·계좌 2개·Refresh Token 원자 트랜잭션 및 롤백 구현
- [x] Fake OAuth 신규/기존·이메일 미제공·기존 이메일 충돌·state/공급자 오류 자동 회귀, 전체 build, API 문서 동기화
- [ ] `oauth-real` 카카오·네이버 각각 authorize→callback→코드 교환→사용자 정보→신규/기존→FinPlay JWT 및 신규 SocialAccount·계좌 2개 DB 스모크 검증 — **NAVER PASS, KAKAO NOT RUN(팀원 계정 로그인·동의 대기)**

Issue #10 완료 시 자동 테스트와 실제 공급자 스모크를 별도 기록한다. 실제 카카오·네이버는 각각 `PASS`여야 하며, 환경변수·개발자 콘솔 Callback URL·이메일 동의 부족 시 추측하지 않고 공급자별 `NOT RUN` 사유를 남긴다. 브라우저 로그인·동의는 사용자 조작을 기다린다. Client ID/Secret, Provider Access Token, authorization code는 기록하지 않는다.
