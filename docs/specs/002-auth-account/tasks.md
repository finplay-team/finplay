# Tasks: 인증과 계좌

작업 항목 9개. 항목 1개 = 커밋 1개 = Issue 1개 단위로 진행한다.

- [ ] 마이그레이션(users·social_accounts·refresh_tokens·accounts·email_verifications) + 엔티티 + Repository (+ @DataJpaTest UNIQUE 제약 검증)
- [ ] EmailSender 인터페이스 + FakeEmailSender + ResendEmailSender(RestClient, 운영 프로필 전용 빈) — RESEND_API_KEY·EMAIL_FROM 없이 기동·테스트가 통과하는지 확인 (+ 단위 테스트)
- [ ] 인증번호 발송 API — 발송 제한(60초·1시간 5회·하루 10회)·기존 회원 409·이전 코드 무효화·HMAC-SHA-256 저장 (+ 단위·@WebMvcTest)
- [ ] 인증번호 확인 API — 시도 5회 제한과 초과 시 429·만료 판정·signupVerificationToken 발급(SHA-256 저장) (+ 단위·@WebMvcTest)
- [ ] 회원가입 API — 가입 토큰 원자 소비·토큰 이메일 일치 검증·중복 409·계좌 2개 원자 생성 (+ 단위·@WebMvcTest)
- [ ] JWT·Security — JwtTokenProvider + Bearer 인증 필터 + 401/403 공통 포맷 + 로그인·GET /api/auth/me (+ 단위·@WebMvcTest)
- [ ] Refresh 회전·로그아웃 — 해시 저장·폐기·재사용 거부 (+ 단위 테스트)
- [ ] OAuth 어댑터 — OAuthProvider 인터페이스 + Kakao·Naver·Fake 구현 + authorize/callback API + 신규 가입 트랜잭션 + OAUTH_EMAIL_REQUIRED(400)·ACCOUNT_LINK_REQUIRED(409) 분기 (+ 단위 테스트)
- [ ] 통합 테스트(인증→가입→계좌 2개, 가입 토큰 재사용 거부, 가입 실패 후 같은 토큰 재시도 성공, Fake OAuth 신규 가입·기존 이메일 409 거부, 토큰 회전) + docs/api-routes.md 갱신
