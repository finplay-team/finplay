# Spec: OAuth 재인증 왕복이 실제로 동작하게 만들기 (state 만료 + 팝업 교환 코드)

> PRD 근거: AUTH-003("내 정보 수정용 OAuth 재인증은... 5분 유효·일회용 `reauthToken`을 발급한다"), AUTH-005("OAuth
> 전용 회원의 닉네임·이메일 변경은... 유효한 일회용 `reauthToken`을 확인한다"). 이 두 요구사항이 전제하는 "재인증
> 팝업 왕복"은 이미 코드가 있지만(`ai/specs/002-auth-account`), 프론트(`finplay-frontend`, 별도 저장소)가 그
> 왕복을 실제로 구현하는 과정에서 지금 백엔드 설계로는 왕복 자체가 성립할 수 없는 결함 2건이 드러났다. 이 spec은 새
> 기능이 아니라 **AUTH-003·AUTH-005가 이미 "된다"고 전제한 것을 실제로 되게 만드는 결함 수정**이며, 세부 계약은
> 032·034·035·038 선례와 같은 패턴으로 이 spec 전용 네임스페이스 **`OAUTH-REAUTH-*`** 를 쓴다(PRD 본문에 새 절을
> 추가하지 않고 이 spec이 정본).
>
> 대응 GitHub 이슈 없음. 배경 조사와 방향 확정은 이 spec 작성을 요청한 대화 자체에 있다.

## 개요

마이페이지에서 카카오·네이버 전용 회원의 닉네임 변경은 재인증을 요구한다(AUTH-005). 재인증은 "이미 연결된
provider 계정으로 다시 로그인해 `reauthToken`을 받아 오는" 별도 왕복이며, 프론트는 지금까지 이 왕복의 팝업 창을
연 적이 없다. 이번에 그 왕복을 실제로 구현하려고 조사하는 과정에서, 백엔드가 이미 갖고 있던 재인증 경로가 다음 두
가지 이유로 애초에 성립할 수 없다는 것이 드러났다.

1. **state에 자체 만료시각이 없다.** 유일한 만료 장치는 `oauth_state` 쿠키의 10분 `maxAge`뿐인데, 재인증 인가
   요청(`GET /api/auth/oauth/{provider}/authorize?purpose=reauth`)은 로그인 사용자를 식별해야 해서 인증된
   fetch로만 호출할 수 있다(로그인 purpose처럼 브라우저 최상위 이동으로 부를 수 없다). 그 fetch가 심으려는
   쿠키는 프론트(`www.finplay.site`)와 백엔드(`finplay.site`)가 크로스 오리진이고 `ADR-0022 §결정 2`가
   `allowCredentials=false`로 못박아 둔 탓에 브라우저에 안정적으로 저장된다고 보장할 수 없다. 로그인 purpose는
   브라우저 최상위 이동(`window.location.href`)이라 이 문제가 없다 — **재인증 purpose만의 문제다.**
2. **REAUTH 콜백이 크로스 오리진 팝업에서 읽을 수 없는 raw JSON을 그대로 반환한다.** 카카오·네이버 인가 후
   브라우저(재인증 팝업)는 API 도메인(`finplay.site`)의 콜백 URL로 최종 이동한다. 그 순간 팝업은 오프너
   (`www.finplay.site`)와 다른 오리진이 되어, 오프너가 동일 출처 정책(SOP) 때문에 그 팝업의 응답 본문을 절대 읽을
   수 없다. 컨트롤러의 기존 주석은 "재인증 SPA가 이미 열어 둔 팝업이 응답을 직접 읽는다"고 되어 있었으나 이는
   틀린 전제다. LOGIN purpose 콜백은 이미 같은 문제를 겪었고, "성공 시 프론트 콜백 라우트로 302 리다이렉트하며
   1회용 교환 코드를 실어 보낸다 → 프론트가 그 코드를 실제 토큰으로 바꾼다"는 패턴으로 고쳐졌다
   (`OAuthLoginExchangeStore`, `POST /api/auth/oauth/login-exchange`). REAUTH purpose는 이 패턴을 받은 적이
   없다.

이 spec은 두 결함을 고쳐 재인증 팝업 왕복이 실제로 성립하게 만든다. 프론트의 팝업 오픈·`postMessage`·재인증 콜백
라우트 배선은 별도 저장소 작업이며 이 spec의 범위가 아니다 — 이 spec은 그 프론트가 그대로 소비할 수 있는 백엔드
계약만 확정한다.

## 사용자 시나리오

- OAuth 전용 회원이 마이페이지에서 닉네임 변경을 시도하면 프론트가 재인증 팝업을 연다. 사용자가 팝업에서 기존에
  연결한 것과 같은 카카오·네이버 계정으로 다시 로그인하면, 팝업은 프론트의 재인증 콜백 주소로 이동하고(더 이상
  API 응답 화면이 그대로 뜨지 않는다) 그 주소가 백엔드로부터 받은 1회용 코드를 실제 `reauthToken`으로 바꿔 오프너에
  전달할 수 있는 형태가 된다.
- 재인증 인가 요청을 시작한 지 10분이 지나면, 그사이 카카오·네이버 인가를 마치고 돌아와도 재인증은 실패한다 —
  왕복이 무한정 유효한 상태로 남지 않는다.
- 다른 사람의 재인증 state 값을 어떤 경로로든 얻은 공격자는, 피해자가 연결해 둔 것과 같은 카카오·네이버 계정
  자체를 갖고 있지 않으면 재인증을 완료할 수 없다 — `oauth_state` 쿠키가 브라우저에 안정적으로 남지 않는 환경에서도
  이 방어는 그대로 유지된다.
- 로그인 purpose(`GET /api/auth/oauth/{provider}/authorize` 및 그 콜백)를 쓰는 기존 사용자 경험은 이 spec
  전후로 전혀 달라지지 않는다.

## 요구사항

- [x] OAUTH-REAUTH-001: OAuth state는 서명 payload 안에 자체 만료시각을 갖는다. LOGIN·REAUTH purpose 공통으로
  발급 후 일정 시간이 지나면, 서명이 유효하고 쿠키가 일치해도 재인증 실패(403 `REAUTHENTICATION_FAILED`)로
  거부된다. 하나의 서명 검증 함수가 두 purpose를 함께 처리하는 기존 구조를 유지한다.
- [x] OAUTH-REAUTH-002: REAUTH purpose의 콜백 검증은 `oauth_state` 쿠키 이중제출(query state와 cookie state의
  일치)에 의존하지 않는다. LOGIN purpose는 지금과 동일하게 쿠키 이중제출을 그대로 요구한다 — 이 spec은 LOGIN의
  기존 계약을 하나도 바꾸지 않는다.
- [x] OAUTH-REAUTH-003: REAUTH purpose 콜백이 성공하면 `reauthToken`이 담긴 JSON을 그 응답 본문으로 직접 주지
  않는다. 대신 팝업이 로드할 프론트 주소로 302 리다이렉트하며, 실제 `reauthToken`은 그 리다이렉트 URL 어디에도
  노출하지 않고 1회용 교환 코드만 싣는다.
- [x] OAUTH-REAUTH-004: 위 교환 코드를 실제 `reauthToken`(+ 남은 유효시간)으로 바꾸는 공개 엔드포인트가 있다.
  같은 코드를 두 번 쓰거나, 발급 후 일정 시간이 지난 코드를 쓰면 거부된다.
- [x] OAUTH-REAUTH-005: REAUTH purpose 콜백이 **실패**하는 경우(잘못된 provider 계정으로 재인증, state 위조·만료
  등)의 응답 형태는 이 spec에서 바꾸지 않는다 — 오류 JSON을 그대로 반환하는 기존 동작이 유지된다(LOGIN purpose의
  실패 경로가 이미 이 한계를 갖고 있는 것과 같다).

## 비즈니스 규칙

- state의 만료 판정은 **발급 시점 기준 고정된 유효기간**으로 한다. 이 유효기간은 카카오·네이버 인가 화면을 거쳐
  돌아오는 정상적인 왕복 시간을 여유 있게 감당해야 하며, 기존 `oauth_state` 쿠키의 `maxAge`(10분)가 같은 목적으로
  이미 검증된 값이므로 그와 다른 값을 새로 정할 이유가 없다 — 값 자체는 plan.md에서 확정한다.
- REAUTH의 CSRF·재생 방어는 쿠키 이중제출이 아니라 다음 두 가지에 의존한다.
  1. 위에서 추가하는 **state 자체 만료시각** — 탈취한 state도 유효기간이 지나면 못 쓴다.
  2. 이미 `AuthService.reauthenticate()`가 수행하는 **"state에 박힌 userId와 정확히 같은 `provider +
     providerUserId` 소셜 계정으로 재인증을 완료해야만 통과"** 검증 — 공격자가 유효기간 안의 state 값을 어떻게든
     얻어도, 피해자의 카카오·네이버 계정 자체를 갖고 있지 않으면 이 단계를 통과할 수 없다. 이 검증은 이번 spec이
     새로 만드는 것이 아니라 이미 존재하며, 이 spec은 그 위에 방어를 얹지 않고 그 존재를 근거로 쿠키 검증을 뺀다.
- 교환 코드는 1회용이며 TTL이 짧다 — 기존 로그인 교환 코드(`OAuthLoginExchangeStore`, TTL 60초)와 같은 성격의
  값이고, 다른 값을 쓸 이유가 없으면 같은 TTL을 쓴다.
- 이 코드로 얻는 `reauthToken` 자체의 유효기간(5분)·일회 소비 규칙은 AUTH-003이 이미 정한 것이며 이 spec이
  바꾸지 않는다 — 이 spec이 바꾸는 것은 그 토큰을 **어떻게 클라이언트에 전달하느냐**이지, 토큰 자체의 수명이나
  소비 규칙이 아니다.
- LOGIN purpose의 state 서명·쿠키 이중제출·302 리다이렉트·`login-exchange` 계약은 이 spec 전후로 동일하다.

## 범위 제외

- **프론트(`finplay-frontend`, 별도 저장소)의 팝업 창 오픈, `postMessage`, 재인증 콜백 라우트, 마이페이지 닉네임
  섹션 배선.** 이 spec은 그 프론트가 그대로 소비할 수 있는 백엔드 계약만 확정한다.
- **실제 카카오·네이버 키를 쓰는 스모크 검증** — 대상 아님. AUTH-003 기존 방침대로 자동 테스트는 Fake OAuth
  제공자를 쓴다.
- **LOGIN purpose 계약 변경** — 대상 아님. state 만료시각 추가로 인한 payload 형식 변화를 제외하면 LOGIN 경로의
  동작·응답은 이 spec 전후로 동일하다.
- **`reauthToken` 자체의 TTL·소비 규칙 변경** — 대상 아님. AUTH-003·AUTH-005가 정한 5분·일회용 규칙 그대로다.
- **카카오·네이버 개발자 콘솔의 redirect_uri 재설정** — 대상 아님. 이 spec이 바꾸는 콜백 주소(`/api/auth/oauth/
  {provider}/callback`) 자체는 그대로다 — 그 응답의 **형태**만 바뀐다.
- **REAUTH 콜백 실패 경로를 프론트가 안내 문구로 처리할 수 있게 만드는 것** — 대상 아님(OAUTH-REAUTH-005). LOGIN
  purpose도 아직 이 한계를 그대로 갖고 있다.

## 완료 조건

- [x] 발급된 state가 만료 유효기간 이전에는 정상 검증되고, 유효기간을 넘기면 서명·purpose·userId가 모두 정확해도
  403 `REAUTHENTICATION_FAILED`로 거부되는 단위 테스트가 LOGIN·REAUTH 양쪽에서 통과한다.
- [x] REAUTH purpose 콜백이 `oauth_state` 쿠키 없이(또는 쿠키 값이 query state와 달라도) 성공하는 테스트와, LOGIN
  purpose 콜백은 지금처럼 쿠키가 없거나 불일치하면 여전히 400 `VALIDATION_ERROR`로 거부되는 회귀 테스트가 함께
  통과한다.
- [x] REAUTH purpose 콜백 성공이 200 JSON이 아니라 302 리다이렉트이고, 응답 어디에도(Location 쿼리·본문) 원문
  `reauthToken`이 노출되지 않는 테스트가 통과한다.
- [x] 리다이렉트가 실어 보낸 교환 코드로 새 엔드포인트를 호출하면 실제 `reauthToken`과 남은 유효시간을 받고, 같은
  코드를 두 번째로 쓰거나 만료된 코드를 쓰면 거부되는 테스트가 통과한다.
- [x] 다른 provider 계정으로 재인증을 시도하면(기존 `AuthService.reauthenticate()` 검증) state 자체 만료시각
  추가·쿠키 검증 제거 이후에도 여전히 403 `REAUTHENTICATION_FAILED`로 거부되고 회원·소셜계정·계좌·시드머니가
  바뀌지 않는 통합 테스트가 통과한다(Fake OAuth 제공자 사용, ADR-0003).
- [x] Fake OAuth로 인가 시작 → 콜백 → 302 리다이렉트 → 교환 코드로 `reauthToken` 획득 → 그 토큰으로
  `PATCH /api/auth/me/nickname` 성공까지 이어지는 통합 테스트가 통과한다.
- [x] `./gradlew build` 통과.
- [x] `ai/api-routes.md`·`docs/api-contracts.md`의 OAuth 재인증 관련 절이 이번 변경(새 교환 엔드포인트,
  REAUTH 콜백 응답이 200 JSON에서 302 리다이렉트로 바뀌는 것)에 맞게 갱신된다.
