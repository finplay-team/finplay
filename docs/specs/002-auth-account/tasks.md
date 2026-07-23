# Tasks: 인증과 계좌

- [ ] 마이그레이션(users·social_accounts·refresh_tokens·accounts) + 엔티티 + Repository (+ @DataJpaTest UNIQUE 제약 검증)
- [ ] 회원가입 API — AuthService 가입 트랜잭션(해시·중복 409·계좌 2개 원자 생성) + Controller/DTO (+ 단위·@WebMvcTest)
- [ ] JWT·Security — JwtTokenProvider + Bearer 인증 필터 + 401/403 공통 포맷 + 로그인·GET /auth/me (+ 단위·@WebMvcTest)
- [ ] Refresh 회전·로그아웃 — 해시 저장·폐기·재사용 거부 (+ 단위 테스트)
- [ ] OAuth 어댑터 — OAuthProvider 인터페이스 + Kakao·Naver·Fake 구현 + authorize/callback API + 가입·병합 트랜잭션 (+ 단위 테스트)
- [ ] 통합 테스트(가입→계좌 2개, Fake OAuth 가입·병합, 토큰 회전) + docs/api-routes.md 갱신
