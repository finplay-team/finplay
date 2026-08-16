# Tasks: OAuth 재인증 왕복이 실제로 동작하게 만들기

순서대로 진행한다. 1번이 2·3번의 입력이고(state 형식·만료 판정이 먼저 있어야 콜백 검증을 재배치할 수 있다),
3번은 2번이 끝난 뒤에야 리다이렉트·교환 엔드포인트를 붙일 자리가 명확해진다. 4번은 1~3번 전체의 통합 확인이다.

- [ ] **1. `OAuthStateGenerator` state 자체 만료시각 + `Clock` 도입 (+ 단위 테스트)**
  payload를 `purpose.userId.nonce.exp` 4필드로 확장한다(`PAYLOAD_FIELD_COUNT` 3→4, `exp`는 4번째 필드).
  `@Autowired` 생성자에 프로젝트 공용 `Clock` 빈을 주입하고, TTL 상수(10분, plan.md §판단 1 근거)로
  `Clock.instant() + TTL`을 서명 전에 계산해 싣는다. 기존 2-인자 package-private 생성자(`SecureRandom, String`)는
  시스템 클럭 버전으로 남기고, 고정 시각 테스트 전용 3-인자 생성자(`SecureRandom, String, Clock`)를 추가한다 —
  기존 호출부(`OAuthStateGeneratorTest`, `OAuthCallbackServiceTest`의 `STATE_GENERATOR` 등)를 건드리지 않는다.
  `verify()`는 서명·purpose·필드개수 검증 뒤 `exp`를 파싱해 현재 시각과 비교하고, 만료면 기존과 동일하게
  `REAUTHENTICATION_FAILED`를 던진다. 단위 테스트: 만료 이전 정상 검증, TTL 경계 직전·직후(고정 `Clock`),
  기존 `verifyRejectsWrongPayloadFieldCount` 등 필드개수 픽스처를 4필드 기준으로 갱신.

- [ ] **2. `OAuthCallbackService` REAUTH 쿠키 이중제출 제거 (+ 단위 테스트)**
  plan.md §판단 2의 순서로 재작성한다 — `queryState` 존재 확인 → `stateGenerator.verify(queryState)`로 `claims`
  획득 → `claims.purpose() == LOGIN`일 때만 쿠키 일치 검사(400 `VALIDATION_ERROR`) → 이후 단계는 변경 없음.
  LOGIN purpose의 기존 동작(쿠키 누락·불일치 시 400)은 회귀 테스트로 고정한다. REAUTH purpose는 쿠키가 없거나
  `queryState`와 달라도 통과함을 새 테스트로 확인한다. `invalidCallbacks()` 파라미터화 픽스처를 이 변경에 맞게
  갱신한다(REAUTH state로 쿠키만 빠진 케이스는 더 이상 400 목록에 있으면 안 된다).

- [ ] **3. `OAuthReauthExchangeStore` + `reauth-exchange` 엔드포인트 + REAUTH 콜백 302 전환 (+ 슬라이스 테스트)**
  `OAuthLoginExchangeStore`를 미러링한 `OAuthReauthExchangeStore`(Redis key prefix
  `auth:oauth-reauth-exchange:v1:`, TTL 60초, `issue(ReauthTokenResponse)`/`consume(String)`)를 만든다.
  `ReauthExchangeRequest`(code) DTO를 추가한다. `OAuthCallbackService`에 `issueReauthExchangeCode`/
  `consumeReauthExchangeCode`를 추가한다. `OAuthCallbackController.callback()`에 `ReauthTokenResponse` 분기를
  추가해 `oauth.reauth-redirect-uri`로 302 리다이렉트하고, 이제 도달 불가능해진 `ResponseEntity.ok(result)`
  fallback을 제거한다. `POST /api/auth/oauth/reauth-exchange` 핸들러를 추가한다. `application.yml`의 `oauth`
  블록과 `.env.example`에 `reauth-redirect-uri`/`OAUTH_REAUTH_REDIRECT_URI`를 추가한다. 컨트롤러·서비스의 잘못된
  주석("재인증 SPA가 이미 열어 둔 팝업이 응답을 직접 읽는다")을 정정한다. `OAuthCallbackControllerTest`에서 REAUTH
  성공이 302이고 `Location`에 `reauthToken` 원문이 없음을, `reauth-exchange` 성공·잘못된 코드 400을 검증한다.

- [ ] **4. 통합 테스트 (핵심 시나리오, Fake OAuth·Testcontainers)**
  `OAuthReauthCallbackIntegrationTest`에 쿠키 없이도 REAUTH 콜백이 성공하는 케이스를 추가하고, 기존 4개
  시나리오는 응답 검증부만 302/신규 엔드포인트에 맞게 갱신한다(회원·소셜계정·계좌·시드머니 불변 검증은 그대로
  유지). 인가 시작 → 콜백 302 → `reauth-exchange` → 받은 `reauthToken`으로
  `PATCH /api/auth/me/nickname` 성공까지 이어지는 전체 왕복과, 다른 provider 계정으로는 여전히 403
  `REAUTHENTICATION_FAILED`로 거부되는 시나리오를 검증한다(기존 `NicknameChangeIntegrationTest` 확장 또는 신규
  테스트 클래스 — 구현 시점에 기존 클래스 구조를 보고 판단).

- [ ] **5. 문서 동기화**
  `docs/api-routes.md`·`docs/api-contracts.md`의 OAuth 재인증 콜백 행(신규 `reauth-exchange` 엔드포인트, REAUTH
  콜백 응답이 200 JSON에서 302 리다이렉트로 바뀌는 것, "REAUTH는... 기존 200 JSON 계약을 그대로 둔다"는 이제
  틀린 서술 정정)을 갱신한다(CLAUDE.md 규칙 7). `docs/prd.md` §3 "구현 현황"은 AUTH-003·AUTH-005가 이미 "완료"로
  묶인 1차 태스크 행(`AUTH-001~006`)의 판정을 바꾸지 않는다 — 이 spec은 기존에 "된다"고 전제된 재인증 왕복을
  실제로 되게 만드는 결함 수정이라 CLAUDE.md 규칙 10의 "기능 제공 범위가 그대로인" 갱신 비대상에 해당한다(계약
  세부는 `docs/api-contracts.md`가, 이 spec 자체의 존재는 `docs/specs/039-oauth-reauth-exchange`가 근거로
  남는다).

- [ ] **6. `./gradlew build` 통과 확인**
