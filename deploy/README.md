# 배포 스택 실행 방법 (수동 배포)

`compose.deploy.yaml` + `deploy/nginx.conf` + `Dockerfile`로 구성된 동일 오리진 스택을 EC2에서 수동으로 띄우는 절차다.
설계 배경과 완료 조건은 `docs/specs/010-deployment/spec.md`, 동일 오리진(A안) 결정 근거는 이슈 #108에 있다.

## 구성

```
브라우저 ──▶ nginx :80 ──┬─▶ /            정적 파일 (프론트 dist/, SPA 폴백)
                         └─▶ /api, /actuator, /v3/api-docs, /swagger-ui ─▶ app :8080
                                                                            ├─▶ RDS (MySQL)        ┐ EC2 밖
                                                                            └─▶ ElastiCache (Redis)┘ 관리형 서비스
```

앱 컨테이너는 호스트에 포트를 열지 않는다. 외부 진입점은 nginx 하나뿐이라 프론트와 API가 같은 오리진이고, 따라서 백엔드에 CORS 설정이 필요 없다.

**DB·캐시는 이 스택 안에 없다 (ADR-0020, 이슈 #326).** 예전에는 `compose.deploy.yaml`이 mysql·redis 컨테이너를 함께 띄웠지만, EC2를 종료하면 그 볼륨의 원장이 함께 사라지는 문제 때문에 RDS·ElastiCache로 분리했다. 그래서 이 스택이 띄우는 컨테이너는 **app·nginx 두 개뿐이고**, 접속 정보는 전부 `.env`에서 온다. 로컬 개발(`compose.yaml` + `bootRun`)은 바뀌지 않았다 — 여전히 컨테이너 mysql·redis를 쓴다.

## 절차

1. **프론트 빌드 산출물을 호스트에 올린다.**
   `finplay-frontend`에서 `npm run build`로 만든 `dist/`를 이 저장소 루트의 `frontend-dist/`에 둔다.
   다른 경로를 쓰려면 `.env`의 `FRONTEND_DIST_PATH`에 그 경로를 적는다.

2. **`.env`를 만든다.** `.env.example`을 복사해 값을 채운다. 배포에 필요한 값은 다음과 같다.
   - 시크릿 — `JWT_SECRET`, `OAUTH_STATE_SECRET`, `EMAIL_VERIFICATION_SECRET`, `PASSWORD_RESET_SECRET`
   - DB(RDS) — `DB_URL`(RDS 엔드포인트), `DB_USERNAME`(root 불가), `DB_PASSWORD`.
     **compose가 덮어쓰지 않으므로 여기 값이 그대로 쓰인다.**
   - 캐시(ElastiCache) — `REDIS_HOST`(기본 엔드포인트), `REDIS_PORT`,
     그리고 **`SPRING_DATA_REDIS_SSL_ENABLED=true`**. 아래 "알려진 함정" 참고.
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

## 알려진 함정 — ElastiCache 접속 실패는 네트워크 문제처럼 보인다

`SPRING_DATA_REDIS_SSL_ENABLED`를 빠뜨리면 **DNS 해석·보안 그룹·TCP 연결이 전부 정상인데** 앱만 기동에 실패한다.

```
io.lettuce.core.RedisConnectionException: Unable to connect to <엔드포인트>/<unresolved>:6379
Caused by: io.lettuce.core.RedisCommandTimeoutException: Connection initialization timed out after 2 second(s)
```

`<unresolved>`라는 표기 때문에 DNS 문제로 읽히지만 아니다. ElastiCache의 "전송 중 암호화"가 켜져 있으면 서버가 TLS 핸드셰이크를 요구하는데 Lettuce 기본 설정은 평문으로 붙어서, **TCP는 연결되고 Redis 핸드셰이크만 타임아웃된다** (2026-08-11 실측).

컨테이너 안에서 이렇게 확인할 수 있다.

```bash
docker exec finplay-deploy-app-1 bash -c 'timeout 5 cat < /dev/null > /dev/tcp/<엔드포인트>/6379 && echo TCP_OK'
```

`TCP_OK`가 나오는데 앱이 위 예외로 죽는다면 네트워크가 아니라 `.env`의 `SPRING_DATA_REDIS_SSL_ENABLED=true`가 빠진 것이다. 전송 중 암호화는 클러스터 생성 후 끌 수 없으므로 클라이언트를 맞추는 것 외의 방법이 없다.

## S3 업로드 이미지 저장소 설정 (ADR-0020, 이슈 #330)

`community.storage.S3FileStorageService`(`@Profile("prod")`)가 커뮤니티 게시물 첨부 이미지를 저장하는 곳이다. 로컬 개발(`!prod`)은 여전히 `LocalFileStorageService`로 디스크에 저장한다 — 아래는 배포(prod 프로필)에서만 필요한 AWS 콘솔 설정이다.

**버킷 — S3**

- [ ] 버킷(예: `finplay-community-images`)을 생성한다. **퍼블릭 액세스 차단(Block Public Access) 4개 옵션을 모두 켠 채로 유지한다** — 이미지는 앱의 다운로드 엔드포인트(`GET /api/community/posts/images/{imageId}/file`)로만 노출되고 버킷을 직접 공개하지 않는다.
- [ ] 버킷 이름을 `.env`의 `COMMUNITY_S3_BUCKET`에 넣는다 — `application-prod.yml`이 기본값 없이 참조하므로(fail-fast) 빠뜨리면 `prod` 기동이 즉시 실패한다.

**IAM 역할·EC2 인스턴스 프로파일**

- [ ] 대상 버킷 하나만 한정한 최소 권한 정책을 만든다 — `s3:GetObject`·`s3:PutObject`·`s3:DeleteObject`, 리소스는 `arn:aws:s3:::<버킷명>/*`.
- [ ] 이 정책을 붙인 IAM 역할을 만들고, EC2 인스턴스에 인스턴스 프로파일로 연결한다(콘솔: EC2 → 인스턴스 선택 → 작업 → 보안 → IAM 역할 수정).
- [ ] **정적 액세스 키를 `.env`·코드 어디에도 두지 않는다.** `S3Client`는 SDK 기본 자격 증명 체인에 맡기므로, 위 인스턴스 프로파일이 배포 환경의 유일한 자격 증명이 된다 — RDS·ElastiCache처럼 로테이션할 시크릿을 새로 만들지 않는 선택이다.
- [ ] 리전은 SDK 기본 리전 프로바이더 체인(`AWS_REGION` 표준 환경변수 또는 인스턴스 메타데이터)에 맡긴다 — 이 프로젝트 전용 리전 설정 키를 추가하지 않는다.
- [ ] `compose.deploy.yaml`의 `app` 서비스에 이미지 볼륨을 새로 붙이지 않는다 — ADR-0020 §결정 3이 "볼륨을 붙이면 그 파일이 다시 인스턴스에 묶여 이 ADR의 목적을 되돌린다"고 명시했다. 이 배포 스택은 볼륨 없이 그대로 유지한다.

## 기존 로컬 업로드 파일 이관 (블루-그린 전환 전 1회)

`CommunityPostImage.storedFilename`은 `UUID+확장자`뿐인 순수 키라 저장소 위치 정보를 담지 않는다 — 이관은 "같은 키로 바이트를 로컬 디스크에서 S3로 복사"하는 것만으로 끝나고, DB 마이그레이션·엔티티 변경은 필요 없다.

1. **이관 대상이 실제로 있는지 먼저 확인한다.** 이 프로젝트는 아직 실사용자 트래픽 이전 단계이고(ADR-0020 §후속), 현재 배포된 EC2가 블루-그린 전환 전 유일한 스택이다. 그 인스턴스에 SSH로 접속해 `${COMMUNITY_IMAGE_STORAGE_DIR}`(compose.deploy.yaml 기준 컨테이너 내부 `./data/community-images`)에 파일이 몇 개나 있는지 확인한다.
   ```bash
   docker exec <app 컨테이너> find /app/data/community-images -type f | wc -l
   ```
2. **판단 기준.**
   - 파일이 없거나 소수(운영 검증용 테스트 데이터 수준)면 — 이관 스크립트를 만들지 않는다. 배포 전환(첫 그린 스택을 S3 프로필로 띄우는 시점) 후 기존 데이터는 폐기하고, 필요하면 사용자에게 재업로드를 안내한다. 1회성 이관 자동화를 만드는 비용이 이 팀 규모에서는 더 크다.
   - 파일이 실사용 데이터 수준으로 있으면 — 아래 1회성 스크립트를 블루-그린 전환 직전에 실행한다.
3. **이관 스크립트 (실사용 데이터가 있을 때만).**
   ```bash
   # EC2 인스턴스 위에서, 블루-그린 전환 직전 1회 실행
   aws s3 sync /path/to/mounted/data/community-images s3://${COMMUNITY_S3_BUCKET}/ \
     --exclude "*" --include "*.jpg" --include "*.png" --include "*.webp"
   ```
   `aws s3 sync`가 로컬 파일명(=`stored_filename`)을 그대로 S3 키로 쓰므로 DB의 `stored_filename` 값과 키가 자동으로 일치한다. 이 동기화가 끝난 뒤에만 새 그린 스택(S3 프로필)으로 트래픽을 넘긴다 — 이관 완료 확인은 블루-그린 헬스체크 절차에 사람이 체크하는 항목으로 추가하고 자동화하지 않는다.
4. 이관 여부와 관계없이 `compose.deploy.yaml`에는 이미지 볼륨을 붙이지 않는다(위 체크리스트와 동일 판단).

## 알려진 제약 — HTTP 배포에서는 OAuth 로그인이 안 된다

이슈 #108은 "급하면 `OAUTH_STATE_COOKIE_SECURE=false`로 내려서 `http://<EC2-IP>/`로도 데모가 돌아간다"를 A안의 근거 중 하나로 들었지만, 실제 코드는 그걸 막는다.

`OAuthStateCookieFactory`(`src/main/java/com/finplay/api/auth/oauth/OAuthStateCookieFactory.java:39`)는 `prod`·`oauth-real` 프로필에서 `secure=false`면 생성자에서 `IllegalStateException`을 던진다. 즉 prod로 띄우면서 이 값을 내리는 선택지는 없다.

그래서 HTTPS를 붙이기 전 상태는 이렇게 정리된다.

- 앱은 기본값 `true`로 정상 기동한다. 프론트·API·SSE·이메일 로그인은 HTTP에서 전부 동작한다.
- 카카오·네이버 OAuth 로그인만 동작하지 않는다 — 브라우저가 `http://`에서 `Secure` 쿠키를 저장하지 않아 state 검증이 실패한다.
- 해결은 HTTPS를 붙이는 것이다. 가드를 완화하는 선택은 별도 이슈에서 판단한다.

## 아직 하지 않은 것

- **HTTPS** — `http://<EC2-IP>/` 기준이다. ADR-0020의 결정은 nginx에 443을 직접 붙이는 대신 **ALB에 ACM 인증서를 붙이는 것**이다(블루-그린 전환과 같은 인프라를 쓴다). 별도 이슈.
- **블루-그린 무중단 배포** — `compose.bluegreen.yaml`(8081/8082)과 ALB 타깃 그룹 2개. 별도 이슈. 지금 이 문서의 절차는 단일 스택 교체(재배포 시 40초 안팎 중단)다.
- **S3 콘솔 설정·기존 파일 이관 실행** — `S3FileStorageService` 구현은 끝났고(이슈 #330), 위 "S3 업로드 이미지 저장소 설정"·"기존 로컬 업로드 파일 이관" 절에 체크리스트·절차를 남겼다. 실제 버킷 생성·IAM 역할 연결·(필요 시) `aws s3 sync` 실행은 다음 배포 시점에 사람이 수행한다.
- 배포 자동화(CI) — 수동 배포로 시작한다 (`docs/specs/010-deployment/spec.md` 범위 제외).
- 프론트 연동·SSE·SPA 폴백의 브라우저 검증 — spec의 "배포 시점 체크" 중 앱 기동·헬스체크는 2026-08-11에 확인했고 브라우저 항목은 남아 있다.
