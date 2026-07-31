# 배포 스택 실행 방법 (수동 배포)

`compose.deploy.yaml` + `deploy/nginx.conf` + `Dockerfile`로 구성된 동일 오리진 스택을 EC2에서 수동으로 띄우는 절차다.
설계 배경과 완료 조건은 `docs/specs/010-deployment/spec.md`, 동일 오리진(A안) 결정 근거는 이슈 #108에 있다.

## 구성

```
브라우저 ──▶ nginx :80 ──┬─▶ /            정적 파일 (프론트 dist/, SPA 폴백)
                         └─▶ /api, /actuator, /v3/api-docs, /swagger-ui ─▶ app :8080
                                                                            ├─▶ mysql :3306
                                                                            └─▶ redis :6379
```

앱 컨테이너는 호스트에 포트를 열지 않는다. 외부 진입점은 nginx 하나뿐이라 프론트와 API가 같은 오리진이고, 따라서 백엔드에 CORS 설정이 필요 없다.

## 절차

1. **프론트 빌드 산출물을 호스트에 올린다.**
   `finplay-frontend`에서 `npm run build`로 만든 `dist/`를 이 저장소 루트의 `frontend-dist/`에 둔다.
   다른 경로를 쓰려면 `.env`의 `FRONTEND_DIST_PATH`에 그 경로를 적는다.

2. **`.env`를 만든다.** `.env.example`을 복사해 값을 채운다. 배포에 필요한 값은 다음과 같다.
   - 시크릿 — `JWT_SECRET`, `OAUTH_STATE_SECRET`, `EMAIL_VERIFICATION_SECRET`
   - DB — `DB_USERNAME`(root 불가), `DB_PASSWORD`, `MYSQL_ROOT_PASSWORD`
     (`DB_URL`은 compose가 스택 내부 주소로 덮어쓴다)
   - OAuth — `KAKAO_*`, `NAVER_*`. `*_REDIRECT_URI`는 배포 주소 기준으로 적고 각 콘솔에도 같은 값을 등록한다.
   - 메일 — `RESEND_API_KEY`, `EMAIL_FROM`
   - KIS — `KIS_APP_KEY`, `KIS_APP_SECRET` (없어도 기동은 성공한다)
   - `OAUTH_STATE_COOKIE_SECURE`는 **건드리지 않는다(기본 `true`).** prod 프로필에서 `false`를 주면
     `OAuthStateCookieFactory`가 기동 단계에서 예외를 던져 앱이 뜨지 않는다 (2026-07-31 실측).
     아래 "알려진 제약" 참고.

3. **기동한다.**
   ```bash
   docker compose -f compose.deploy.yaml up -d --build
   ```
   nginx는 `app`이 healthy가 될 때까지 기다렸다가 뜬다(Flyway 마이그레이션이 끝나기 전에 nginx가 떠서
   첫 요청이 502가 되는 것을 막는다). 그래서 이 명령은 앱 기동이 끝날 때까지 돌아오지 않는다 — 로컬 실측 40초 안팎이다.

4. **확인한다.**
   ```bash
   docker compose -f compose.deploy.yaml ps
   curl http://<호스트>/actuator/health          # {"status":"UP"}
   curl -N http://<호스트>/api/stocks/stream     # SSE — 20초마다 heartbeat가 흘러야 한다
   ```
   브라우저에서 `/trade` 같은 하위 경로를 새로고침해도 404가 나지 않아야 한다(SPA 폴백).

## 갱신

- 백엔드만 바뀐 경우: `docker compose -f compose.deploy.yaml up -d --build app`
- 프론트만 바뀐 경우: `frontend-dist/`를 새 `dist/`로 교체한다. nginx는 정적 파일을 읽기 전용 마운트로 바로 읽으므로 재기동이 필요 없다.
- `deploy/nginx.conf`를 바꾼 경우: `docker compose -f compose.deploy.yaml restart nginx`

## 알려진 제약 — HTTP 배포에서는 OAuth 로그인이 안 된다

이슈 #108은 "급하면 `OAUTH_STATE_COOKIE_SECURE=false`로 내려서 `http://<EC2-IP>/`로도 데모가 돌아간다"를 A안의 근거 중 하나로 들었지만, 실제 코드는 그걸 막는다.

`OAuthStateCookieFactory`(`src/main/java/com/finplay/api/auth/oauth/OAuthStateCookieFactory.java:39`)는 `prod`·`oauth-real` 프로필에서 `secure=false`면 생성자에서 `IllegalStateException`을 던진다. 즉 prod로 띄우면서 이 값을 내리는 선택지는 없다.

그래서 HTTPS를 붙이기 전 상태는 이렇게 정리된다.

- 앱은 기본값 `true`로 정상 기동한다. 프론트·API·SSE·이메일 로그인은 HTTP에서 전부 동작한다.
- 카카오·네이버 OAuth 로그인만 동작하지 않는다 — 브라우저가 `http://`에서 `Secure` 쿠키를 저장하지 않아 state 검증이 실패한다.
- 해결은 HTTPS를 붙이는 것이다. 가드를 완화하는 선택은 별도 이슈에서 판단한다.

## 아직 하지 않은 것

- HTTPS(인증서) — `http://<EC2-IP>/` 기준이다. 도메인·인증서를 붙일 때 nginx에 443 server 블록을 추가한다.
- 배포 자동화(CI) — 수동 배포로 시작한다 (`docs/specs/010-deployment/spec.md` 범위 제외).
- 실제 배포 환경에서의 동작 검증 — EC2가 준비되면 위 4번과 spec의 배포 시점 체크 항목을 수행한다.
