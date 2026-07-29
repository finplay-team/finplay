# API 라우트 지도

전체 엔드포인트를 한눈에 보는 레지스트리다. **controller를 추가/변경하면 이 문서의 라우트 목록과 `docs/api-contracts.md`의 해당 절을 같은 커밋에서 함께 갱신한다** (CLAUDE.md 규칙, reviewer 리뷰 모드 점검 항목).

- 엔드포인트별 요청·응답·오류 계약은 `docs/api-contracts.md`에 있다.
- 실행 중인 앱의 자동 생성 문서는 Swagger UI에서 확인한다 — `http://localhost:8080/swagger-ui.html`

## 라우트 목록

| Method | URL | 도메인 | 요약 | Spec |
|---|---|---|---|---|
| POST | /api/auth/email-verifications | auth | 인증번호 발송 (202, 본문 없음). 발송 제한·중복 이메일 검사 | 002 AUTH-004 |
| POST | /api/auth/email-verifications/confirm | auth | 인증번호 확인 후 `signupVerificationToken` 발급 | 002 AUTH-004 |
| POST | /api/auth/signup | auth | 가입 인증 토큰을 소비하는 이메일 회원가입 (201, 토큰 발급) | 002 Issue #4 |
| POST | /api/auth/login | auth | 이메일·비밀번호 검증 후 Access·Refresh 토큰 발급 | 002 AUTH-002 |
| POST | /api/auth/refresh | auth | 유효한 Refresh Token을 회전하고 새 Access·Refresh 토큰 발급 | 002 AUTH-002 |
| POST | /api/auth/logout | auth | 본인의 Refresh Token을 폐기하고 로그아웃 (204, 본문 없음) | 002 AUTH-002 |
| GET | /api/auth/me | auth | 인증 사용자 본인의 id·email·nickname·가입 방식 조회 | 002 AUTH-005, Issue #8 |
| PATCH | /api/auth/me/nickname | auth | 재인증(현재 비밀번호 또는 reauthToken) 후 본인 닉네임 변경 | 002 AUTH-005, Issue #54 |
| POST | /api/auth/email-changes | auth | 인증 사용자의 새 이메일 변경 인증번호 발송 (202, 본문 없음). 재인증 증명·중복 이메일·발송 제한 검사 | 002 AUTH-005, Issue #55 |
| POST | /api/auth/email-changes/confirm | auth | 새 이메일 인증번호 확인 후 users.email 원자적 변경, 성공 시 기존 Refresh Token 전체 폐기 | 002 AUTH-005, Issue #56 |
| GET | /api/auth/oauth/{provider}/authorize | auth | 카카오·네이버 OAuth 인가 시작. `purpose` 생략/`login`은 공개 302, `purpose=reauth`는 인증 필요 200 JSON | 002 AUTH-003, Issue #9, Issue #53 |
| GET | /api/auth/oauth/{provider}/callback | auth | OAuth 콜백. 서명된 `state`의 purpose에 따라 로그인 토큰 또는 `reauthToken` 반환 | 002 AUTH-003, Issue #10, Issue #53 |
| GET | /api/instruments?market= | market | 인증 사용자의 종목 목록 조회 (market 선택: STOCK·CRYPTO, 생략 시 전체) | 003 MKT-001, Issue #14 |
| GET | /api/instruments/{instrumentId} | market | 인증 사용자의 종목 단건 조회 | 003 MKT-001, Issue #15 |
| GET | /api/instruments/{instrumentId}/price | market | 인증 사용자의 종목 현재가 조회. 주식은 StockPriceProvider(재생/실시간 공급자 불문), 코인은 PriceStore(Redis)에서 유효한 최신 가격만 반환 | 003 MKT-002/MKT-003/MKT-004, Issue #16 |
| POST | /api/community/posts | community | 인증 사용자의 텍스트 게시물 작성 | 008 COM-001, Issue #23 |
| GET | /api/community/posts?page=&size= | community | 인증 사용자의 게시물 목록을 최신순 페이지네이션으로 조회 | 008 COM-001, Issue #24 |
| GET | /api/community/posts/{postId} | community | 인증 사용자의 커뮤니티 게시물 단건 조회 | 008 COM-001, Issue #25 |
| PATCH | /api/community/posts/{postId} | community | 본인 소유 커뮤니티 게시물의 제목·본문 수정 | 008 COM-001, Issue #26 |
| DELETE | /api/community/posts/{postId} | community | 본인 소유 커뮤니티 게시물 삭제 (204, 본문 없음, 댓글도 함께 삭제) | 008 COM-001, Issue #27 |
| GET | /api/community/posts/{postId}/comments | community | 인증 사용자가 게시물의 평면 댓글을 오래된 순으로 조회 | 008 COM-002, Issue #29 |
| POST | /api/community/posts/{postId}/comments | community | 인증 사용자의 평면 댓글 작성 | 008 COM-002, Issue #28 |
| DELETE | /api/community/comments/{commentId} | community | 본인 소유 댓글 삭제 (204, 본문 없음) | 008 COM-002, Issue #30 |
| POST | /api/orders | order | 인증 사용자의 시장가 매수 주문을 검증·즉시 전량 체결하고 주문+체결 결과 반환 (201). `Idempotency-Key` 헤더 필수(존재 검증만, 재현 방지는 #22) | 004 ORD-001~004·006(부분), Issue #13 |

## 시스템 엔드포인트

| Method | URL | 요약 |
|---|---|---|
| GET | /actuator/health | 헬스체크 |
| GET | /swagger-ui.html | API 문서 (springdoc) |
| GET | /v3/api-docs | OpenAPI JSON |

## 인증 규칙

Spring Security는 세션을 만들지 않는 Bearer 인증을 사용한다. 현재 `SecurityConfig`의 공개 경로는 다음과 같고, 여기에 없는 모든 요청은 기본적으로 인증이 필요하다.

| 구분 | Method | 경로 |
|---|---|---|
| 공개 | POST | `/api/auth/signup` |
| 공개 | POST | `/api/auth/login` |
| 공개 | POST | `/api/auth/refresh` |
| 공개 | POST | `/api/auth/email-verifications` |
| 공개 | POST | `/api/auth/email-verifications/confirm` |
| 조건부 공개 | GET | `/api/auth/oauth/*/authorize` — `purpose`가 없거나 공백이거나 대소문자 무관 `login`일 때만 공개. 그 밖의 값(`reauth` 포함)은 `anyRequest().authenticated()`로 떨어져 인증 필요 |
| 공개 | GET | `/api/auth/oauth/*/callback` |
| 공개 | GET | `/actuator/health` |
| 공개 | GET | `/swagger-ui.html`, `/swagger-ui/**` |
| 공개 | GET | `/v3/api-docs/**` |
| 보호 | POST | `/api/auth/logout` |
| 보호 | GET | `/api/auth/me` |
| 보호 | PATCH | `/api/auth/me/nickname` |
| 보호 | POST | `/api/auth/email-changes` |
| 보호 | POST | `/api/auth/email-changes/confirm` |
| 보호 | 모든 Method | 위 공개 목록을 제외한 모든 경로 (`anyRequest().authenticated()`) |

- 보호 경로는 `Authorization: Bearer <accessToken>`을 요구한다. 헤더가 없거나 Access Token이 만료·변조됐거나 Refresh Token이면 401 `UNAUTHORIZED`다.
- 인증된 사용자가 권한 없는 리소스에 접근하면 403 `FORBIDDEN`이다.
- 401·403은 모두 `{"error":{"code":"<UNAUTHORIZED|FORBIDDEN>","message":"<기본 메시지>","requestId":"<요청 ID>"}}` 공통 형식이며 `X-Request-Id` 응답 헤더를 포함한다.
- `/api/auth/me`는 `SecurityConfig` 공개 목록에 추가하지 않고 기본 보호(`anyRequest().authenticated()`)만으로 인증을 요구한다.
- authorize의 조건부 공개는 `AndRequestMatcher`(경로+메서드 매처 + `purpose` 파라미터 람다)로 구현한다. 모르는 `purpose` 값은 공개 대상이 아니므로 기본적으로 인증을 요구한다(안전한 기본값). Spring Security 7에는 `AntPathRequestMatcher`가 없어 `PathPatternRequestMatcher`를 쓴다.
