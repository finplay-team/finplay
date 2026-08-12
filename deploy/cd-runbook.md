# CD 런북 — `dev` 머지 자동 배포

> **이 문서는 아직 "돌고 있는 파이프라인"의 기록이 아니다.** 2026-08-12 기준 `.github/workflows/deploy.yml`은 존재하지 않고, 아래 AWS 설정도 하나도 만들어지지 않았다. 이 문서는 [ADR-0021](../docs/adr/0021-continuous-deployment.md)이 결정한 목표 구조를 **구축 순서와 실패 대응까지 포함해 옮긴 것**이며, 각 항목은 실제로 수행한 시점에 체크한다.
>
> 결정의 근거·대안은 ADR-0021이 정본이다. 배포 아키텍처(EC2 + RDS·ElastiCache·S3 + 블루-그린) 자체는 [ADR-0020](../docs/adr/0020-managed-service-deployment.md)이 정본이다. **수동 배포 절차는 폐기하지 않는다** — 파이프라인이 막혔을 때의 폴백으로 [`README.md`](README.md)에 남아 있다.

## 이 파이프라인이 하는 일

```
사람: dev 대상 PR 머지          ← 사람의 개입 지점은 여기 하나뿐이다
  │
  ▼ push: dev
[GitHub Actions · ubuntu-24.04-arm]
  ① ./gradlew bootJar
  ② 런타임 이미지 빌드 → ECR push (태그 = 커밋 SHA)
  ③ OIDC → IAM 역할 임시 자격 증명
  │
  ▼ SSM Send Command
[EC2 t4g.small]
  ④ ALB 리스너에서 라이브 색 판별 → 유휴 색을 새 이미지로 기동
  ⑤ 컨테이너 헬스체크 통과 대기
  │
  ▼ ALB API
  ⑥ 유휴 색 타깃 그룹 healthy 확인 → 리스너 기본 작업 전환
  ⑦ 이전 색은 그대로 남긴다 (다음 배포까지 롤백 경로)
```

프론트(별도 레포 `finplay-team/finplay-frontend`)는 `dist/`를 S3 아티팩트 버킷에 올린 뒤 `repository_dispatch`로 이 워크플로우를 부른다. ④에서 유휴 색의 정적 디렉터리에 그 아티팩트를 푼다 — **배포 단위는 "백엔드 이미지 + 프론트 dist" 한 쌍**이다 (ADR-0021 §결정 8).

## 선행 조건 — 이게 없으면 파이프라인을 만들 수 없다

- [ ] **ALB + 타깃 그룹 2개(blue 8081 / green 8082) + ACM 인증서** — ADR-0020 §후속의 별도 이슈. ⑥의 "리스너 전환"이 성립하려면 리스너가 먼저 있어야 한다.
- [ ] `compose.bluegreen.yaml`이 ECR 이미지를 참조하도록 전환 (현재는 `build:` 컨텍스트 기반).
- [ ] 런타임 전용 Dockerfile 분리 (ADR-0021 §결정 4).

## AWS 콘솔 설정 (사람이 1회 수행)

IaC를 쓰지 않으므로 **이 절이 사실상 유일한 정본이다** (ADR-0020 §결과가 이미 지적한 문제). 값을 바꾸면 여기도 고친다.

### 1. GitHub OIDC 자격 증명 공급자

- [ ] IAM → 자격 증명 공급자 → OpenID Connect 추가
  - 공급자 URL: `https://token.actions.githubusercontent.com`
  - 대상(Audience): `sts.amazonaws.com`

### 2. 배포용 IAM 역할

- [ ] 위 OIDC 공급자를 신뢰하는 역할을 만든다. **신뢰 정책의 `sub` 조건을 브랜치까지 못박는다** — 레포까지만 제한하면 어떤 브랜치의 워크플로우든 이 역할을 가져간다 (ADR-0021 §결정 2).
  ```
  "token.actions.githubusercontent.com:sub": "repo:finplay-team/finplay-backend:ref:refs/heads/dev"
  "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
  ```
- [ ] 권한은 다음 네 가지로 한정한다. `*` 리소스를 쓰지 않는다.
  | 용도 | 필요한 동작 | 리소스 |
  |---|---|---|
  | ECR push | `ecr:GetAuthorizationToken`(리소스 지정 불가) + `ecr:BatchCheckLayerAvailability`·`InitiateLayerUpload`·`UploadLayerPart`·`CompleteLayerUpload`·`PutImage` | 해당 ECR 리포지터리 |
  | EC2 명령 실행 | `ssm:SendCommand`·`GetCommandInvocation`·`ListCommandInvocations` | 배포 대상 인스턴스 + `AWS-RunShellScript` 문서 |
  | ALB 전환 | `elasticloadbalancing:DescribeListeners`·`DescribeTargetHealth`·`ModifyListener` | 해당 리스너·타깃 그룹 |
  | 프론트 아티팩트 조회 | `s3:GetObject`·`ListBucket` | 아티팩트 버킷 |
- [ ] 역할 ARN을 GitHub 리포지터리 **Variable**(시크릿 아님 — ARN은 비밀이 아니다)로 등록한다.

### 3. ECR 리포지터리

- [ ] 리포지터리 생성(예: `finplay-api`). **이미지 스캔을 켠다.**
- [ ] 수명 주기 정책 — 태그가 커밋 SHA라 무한히 쌓인다. **최근 10개만 유지**하도록 규칙을 넣는다 (ADR-0021 §결과의 비용 항목).

### 4. EC2 인스턴스 프로파일에 권한 추가

- [ ] 기존 인스턴스 프로파일(이슈 #330에서 S3 정책을 붙인 그 역할)에 다음을 **추가**한다. 역할을 새로 만들지 않는다.
  - `AmazonSSMManagedInstanceCore` — SSM Agent가 명령을 받아 가려면 필요하다.
  - ECR **읽기** 권한(`ecr:GetAuthorizationToken`·`BatchGetImage`·`GetDownloadUrlForLayer`) — EC2는 pull만 한다.
  - 프론트 아티팩트 버킷 읽기 권한.
- [ ] EC2에서 SSM Agent가 살아 있는지 확인한다.
  ```bash
  sudo systemctl status amazon-ssm-agent
  ```
- [ ] 콘솔의 **Systems Manager → Fleet Manager**에 이 인스턴스가 나타나는지 확인한다. 안 보이면 아직 SSM으로 명령을 보낼 수 없다 (권한 또는 아웃바운드 문제).

### 5. 프론트 아티팩트 S3 버킷

- [ ] 버킷 생성(예: `finplay-frontend-artifacts`). **퍼블릭 액세스 차단 4개를 모두 켠 채로 유지한다** — 커뮤니티 이미지 버킷과 같은 방침이다(`README.md` S3 절).
- [ ] 키 규칙: `dist/<커밋SHA>.tar.gz`. 이 규칙이 프론트 레포와의 계약이므로 양쪽 문서에 같은 값을 적는다.
- [ ] 수명 주기 규칙 — 30일 지난 아티팩트 삭제.

## 사람이 개입하는 순간

정상 흐름에서는 **없다.** 아래는 전부 실패 경로다.

### 파이프라인이 ⑤(헬스체크)에서 실패했다

- **사용자 영향 없음.** 라이브 색을 건드린 적이 없다.
- 워크플로우 로그에서 SSM 명령 출력을 본다 → 대개 앱 기동 실패다. `.env` 값(특히 `SPRING_DATA_REDIS_SSL_ENABLED`)·마이그레이션·이미지 아키텍처 순으로 확인한다 (아래 "오진하기 쉬운 실패" 참고).
- 고친 뒤 **워크플로우를 재실행**한다. EC2에 직접 들어가 고치면 그 수정이 다음 배포에서 사라진다.

### 파이프라인이 ⑥(전환) 이후 실패했다

- 파이프라인이 리스너를 이전 색으로 되돌린다(ADR-0021 §결정 6). **되돌아갔는지 눈으로 확인한다** — ALB 리스너의 기본 작업이 이전 색 타깃 그룹인지.
- 되돌아가지 않았다면 콘솔에서 직접 리스너 기본 작업을 이전 색으로 바꾼다. 이것이 가장 빠른 복구다.

### 더 이전 버전으로 돌아가야 한다

이 파이프라인의 자동 롤백은 **직전 색까지**다. 그보다 과거로 가려면 사람이 한다.

- [ ] ECR에서 되돌아갈 커밋 SHA 태그를 확인한다.
- [ ] 그 SHA로 워크플로우를 수동 실행한다(`workflow_dispatch` 입력으로 태그를 받도록 만든다 — 후속 이슈의 워크플로우 요구사항).
- **DB 스키마는 되돌아가지 않는다.** 구버전 앱이 신버전 스키마에서 도는 것을 전제로 마이그레이션을 짠다 (ADR-0021 §결정 7). 파괴적 마이그레이션이 이미 들어갔다면 롤백으로 풀 수 없다 — 그때는 앞으로 고치는 수밖에 없다.

### 파이프라인 자체가 막혔다 (GitHub 장애, OIDC 실패 등)

- [`README.md`](README.md)의 수동 배포 절차로 배포한다. **폴백은 폐기하지 않았다.**
- 단, 수동으로 띄운 상태와 파이프라인이 아는 상태가 갈라진다. 파이프라인은 ④에서 ALB 리스너를 읽어 라이브 색을 판별하므로 **리스너만 정확하면 다음 자동 배포가 정상 복귀한다** — 수동 배포에서도 리스너 전환을 빠뜨리지 않는다.

## 오진하기 쉬운 실패

이 저장소에서 실제로 겪었거나(출처 표기) 이 구조에서 구조적으로 나올 수 있는 것들이다.

| 증상 | 진짜 원인 | 확인 |
|---|---|---|
| 컨테이너가 즉시 죽고 `exec format error` | **이미지 아키텍처 불일치.** EC2가 `t4g`(arm64)인데 x86 러너에서 만든 이미지를 올렸다 | `docker image inspect <이미지> --format '{{.Architecture}}'` → `arm64`여야 한다 |
| 앱만 기동 실패, DNS·보안그룹·TCP는 정상 | `.env`의 `SPRING_DATA_REDIS_SSL_ENABLED=true` 누락 (2026-08-11 실측, ADR-0020 §결정 2) | `README.md` "알려진 함정" 절 |
| `docker ps`는 `(healthy)`인데 호스트 `curl :8080`이 실패 | **앱 컨테이너는 호스트에 포트를 열지 않는다**(설계). 호스트에서 부르면 앱 상태와 무관하게 실패한다 (2026-08-11 실측, `docs/agent-mistakes.md`) | `docker exec <앱컨테이너> curl -fsS http://localhost:8080/actuator/health` |
| SSM 명령이 `DeliveryTimedOut`으로 끝난다 | SSM Agent가 죽었거나 인스턴스 프로파일에 `AmazonSSMManagedInstanceCore`가 없다. 네트워크가 아니다 | Fleet Manager 목록에 인스턴스가 보이는지 |
| OIDC 단계에서 `Not authorized to perform sts:AssumeRoleWithWebIdentity` | 신뢰 정책의 `sub`가 실제 브랜치와 다르다. `refs/heads/dev`로 못박았으므로 **다른 브랜치에서 돌린 워크플로우는 반드시 여기서 막힌다**(의도된 동작) | 역할 신뢰 정책의 `sub` 값 |
| 전환 후에도 옛 화면이 보인다 | 프론트 `dist/`가 유휴 색 디렉터리에 안 풀렸다. 백엔드 이미지만 새것이다 | 색상별 디렉터리(`FRONTEND_DIST_*_PATH`)의 `index.html` 해시 |
| 두 배포가 서로를 덮어썼다 | `concurrency` 그룹 누락. 백엔드 push와 프론트 `repository_dispatch`가 동시에 들어왔다 (ADR-0021 §결정 8) | 워크플로우 실행 시각 겹침 |

## 첫 구축 후 확인할 것 (아직 아무것도 실행하지 않았다)

- [ ] `ubuntu-24.04-arm` 러너가 이 레포에서 실제로 잡히는가
- [ ] 머지 → 전환 완료까지 총 소요 시간 (수동 배포는 앱 기동만 40초 안팎이었다 — `README.md`)
- [ ] 전환 순간 `/api/stocks/stream`(SSE) 연결이 어떻게 끊기고 브라우저가 재연결하는가
- [ ] 롤백 경로를 **일부러 한 번 실패시켜** 확인 (헬스체크 실패 시 전환하지 않는지)
- [ ] `t4g.small`에서 app 2벌 동시 기동 시 실메모리 (`docker stats`) — 이슈 #332가 남긴 미확인 항목
