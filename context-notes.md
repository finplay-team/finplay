# 컨텍스트 노트

세션 간 인수인계용. 결정과 이유를 시간순으로 추가한다.

## 2026-07-22 — 하네스 초기 셋팅

- **프로젝트명 `tradeclass-api`**: 프론트엔드(TradeClass, React)와 짝을 맞춘 백엔드. 별도 레포로 운영.
- **Gradle Kotlin DSL + Java 21 + MySQL 8.4**: 사용자 선택. Spring Initializr가 Boot 4.1.0을 생성함 (Boot 3.x 아님 — 문서 참조 시 주의).
- **H2 대신 Testcontainers**: mock/인메모리만으로 테스트하면 통합 시점 오류를 못 잡는다는 문제의식에서 출발한 셋팅. ADR-0003에 기록.
- **MySQL 이미지 `latest` → `8.4` 고정**: Initializr 기본값이 latest라 재현성 문제로 수정함.
- **ADR + spec-kit 병행**: ADR은 "왜", specs는 "무엇을". AI 규칙은 CLAUDE.md에 집중.
- **프로젝트 위치는 반드시 영문 경로**: 처음 `Desktop\예진님은 천생 아이돌\` 아래 생성했으나 한글 경로에서 Gradle 테스트 워커가 클래스패스를 못 읽어 `ClassNotFoundException` 발생 (컴파일은 됨). `-Dfile.encoding=UTF-8`로도 해결 불가. `Desktop\tradeclass-api`로 이전 후 정상. 팀원에게도 영문 경로 클론 안내 필요.
- **테스트 의존성 이름 주의**: Boot 4.1은 `spring-boot-starter-webmvc-test` 등 스타터별 테스트 아티팩트 구조를 사용함 (Initializr 생성 그대로 둠).

## 2026-07-22 — 하네스 2차 보강

- **Flyway는 `spring-boot-starter-flyway` 필수**: Boot 4의 모듈 분리로 `flyway-core`만 넣으면 자동설정이 안 붙어 조용히 실행 안 됨 (에러도 없음). `flyway_schema_history` 테이블 존재로 실제 실행 여부를 검증했음.
- **compose 포트 13307**: 호스트 3306(로컬 MySQL)과 13306(다른 프로젝트 docker 스택)이 이미 사용 중이라 13307 선택. spring-boot-docker-compose가 매핑 포트를 자동 감지하므로 앱 설정 불필요.
- **PR 자동화는 API 키 없이 로컬 오케스트레이션** (ADR-0005): /feature와 /review-pr 스킬이 진입점. code-reviewer는 `RESULT: 차단 N건` 형식으로 반환해 루프 판정을 기계화함. qa-verifier는 구현 코드를 안 보는 블랙박스 원칙.
- **springdoc 3.0.0**: Boot 4.1 호환 확인 (Swagger UI 200, OpenAPI 3.1). Spotless 8.0.0도 Gradle 9.5에서 정상.

## 2026-07-23 — 이전 하네스(coffee-order-system) 선별 이식

- **가져온 것 5개**: Context Router(작업별 문서 hot path), agent-mistakes 실수 로그, 영향도 기반 CI(dorny/paths-filter — 이전 하네스의 Python 1,490줄 분류기를 Actions 표준 기능 20줄로 대체), MySQL 컨테이너 static 싱글턴, 서브에이전트 최소 packet 계약.
- **의도적으로 안 가져온 것**: evidence 6종 파일 체계, harness_gate.py류 강제 스크립트, 역할 8종/Execution mode, 검증 Level 0~7, 자율 merge 큐. 이유는 유지보수 비용이 본업을 초과했던 전례 (저자 본인도 경량화했음). 필요해지면 개별 재검토.
- **실수 기록 위치 규칙**: 결정은 context-notes, 재현된 실수는 agent-mistakes. 중복 기재 금지.

## 2026-07-23 — PRD 반입과 스택 확정

- **PRD 정본은 `docs/prd.md`**: Notion 원본은 사람용 미러. 에이전트(팀원 세션 포함)가 Notion에 접근 못 하므로 레포가 정본. 반입 시 확정 결정 3건은 prd.md 상단 "정본 안내" 블록에 기록.
- **제품명 FinPlay** (구 TradeClass/Investory), **Java 17** (구 21): ADR-0006. 레포 폴더명(`Desktop\tradeclass-api`)·GitHub 레포명 변경은 클론 경로 영향 때문에 팀 합의 후 수동 진행 예정.
- **에러 응답은 `{"error":{code,message,requestId}}`**, **URL 버저닝 미사용** (`/api/v1` 금지): conventions.md에 반영. PRD 원본의 `/api/v1` 표기보다 conventions가 우선.
- **CI 하네스 전환은 로드맵으로만 기록** (`docs/harness-roadmap.md`): 튜터 제안(이슈 트리거 → 러너 에이전트 → PR → 사람 머지 + 정량 지표). 1차 MVP 진행 후 ADR-0007로 착수. 베이스라인 확보를 위해 지금부터 이슈→PR 흐름으로 작업.
- **ADR 부분 대체 방식**: ADR-0002처럼 일부 결정만 바뀌면 원문은 안 건드리고 상태 줄에 대체 ADR 링크만 표기.

## 2026-07-23 — Codex 자동 리뷰 + 병렬 에이전트 체계

- **Codex PR 자동 리뷰 — 도입 당일 철회** (ADR-0007 폐기): 만들어보니 리뷰가 3중(개발 중 항목별 code-reviewer + Codex CI + 로컬 /review-pr)이라 부담 > 이익. 리뷰는 "개발 중 1번(/feature 루프) + PR에서 1번(/review-pr)"으로 정리. 참고로 claude-code-action은 OpenAI 키 미지원(공식 확인)이라 Codex 키로 Claude 하네스 CI 전환은 원래 불가.
- **하네스 경량화 3건** (무거움 우려에 대한 조정): ① 경량 경로 — 파일 1~2개 규모 작업은 /feature 없이 메인 세션 직접 (CLAUDE.md) ② /feature 항목별 검증은 `test`만, 전체 `build`는 마무리 1회 (커밋 전 spotlessApply 추가) ③ tasks.md 항목 굵기 가이드 (specs/README, 3~7개 권장). **핵심 안전장치(test-writer 분리·블랙박스 QA·리뷰 게이트)는 유지** — 추가 경량화는 감이 아니라 이슈→PR 지표를 보고 결정.
- **정적 분석은 로컬 도구만** (튜터 피드백 "화이트박스 검증" 반영): SpotBugs 6.5.9 + JaCoCo 라인 60% 게이트를 `build`에 포함 — AI가 짠 코드를 사람이 숫자로 검증하는 장치. SonarCloud는 외부 서비스 셋업 비용 때문에 보류 (사용자 결정). 커버리지 제외는 진입점 클래스만, SpotBugs 제외는 재현된 오탐만.
- **병렬화는 spec 단위, 수단은 Agent View 우선** (`docs/parallel-agents.md`): Agent View(`claude agents`)는 내장 + 자동 worktree 격리라 "agentview 만들기" 요청은 문서화로 대체. 에이전트 팀은 실험 플래그로 활성화(팀원 간 직접 메시징)하되 파일 격리가 없어 코드 수정엔 파일 소유권 분리 필수. /feature 루프 내부는 순차 유지 (단계 의존 = 품질 게이트).

## 2026-07-23 — 1차 MVP spec 9개 작성

- **폴더 구성**: PRD §8 태스크 9개와 1:1 — 001-foundation / 002-auth-account / 003-market-data / 004-order-buy / 005-order-sell / 006-portfolio-query / 007-journal / 008-community / 009-integration.
- **깊이는 단계적** (사용자 결정): 001~003만 풀세트(spec+plan+tasks), 004~009는 spec.md만. 이유는 뒤쪽 태스크의 plan을 미리 쓰면 앞 구현 진행으로 낡기 때문. 각 기능 착수 직전에 plan·tasks를 작성한다.
- **튜터 계획서 양식 반영**: 기존 템플릿에 없던 "비즈니스 규칙"(불변 규칙)을 spec.md에, "입력 명세"(필드별 필수/검증 표)를 plan.md에 섹션으로 추가. `_template/`에도 반영해 이후 spec부터 기본 적용. 계획 목표→개요, 성공 기준→완료 조건, 개발 순서→tasks.md, 범위 밖→범위 제외로 기존 구조와 대응됨을 확인.
- **001은 잔여분만**: PRD 태스크 1(프로젝트 기반) 중 하네스 셋팅에서 이미 끝난 것(MySQL·Testcontainers·Flyway·compose·프로필 등)은 제외하고 Redis·QueryDSL·공통 오류 체계·Clock·Kafka 컨테이너 준비만 범위로 잡음.
- **PRD 불일치 1건 발견**: §3 로드맵에 "이메일 인증 흐름"이 있으나 §4에 요구사항 ID·수용 기준이 없음. §4를 정본으로 보고 002 spec의 범위 제외에 명시 (필요 시 PRD 후속 결정으로 추가).

## 2026-07-23 — 서브에이전트 6개 → 4개 통합 (ADR-0008)

- **튜터 피드백 반영**: 에이전트 수 과다 → 4개로 축소 + 계획·문서 전담 에이전트 신설. 구성안은 "기능 병합형" 선택 (역할 삭제가 아니라 병합 — 기존 안전장치를 버리지 않기 위함).
- **최종 로스터**: planner(신설, doc-syncer 흡수) / implementer(유지) / tester(test-writer+test-runner) / reviewer(code-reviewer+qa-verifier). 팀장은 별도 에이전트가 아니라 **메인 세션(오케스트레이터)** — 서브에이전트는 서브에이전트를 투입할 수 없는 Claude Code 구조 때문이며, 팀장 행동 정의는 /feature·/review-pr 스킬에 있음.
- **QA 블랙박스 독립성은 모드 분리로 유지**: reviewer는 리뷰 모드/QA 모드를 별도 투입으로만 수행 (한 투입에서 겸하면 "구현 코드를 읽지 않는다" 원칙이 깨짐). 병합했지만 투입 횟수는 동일.
- **planner 범위는 spec 작성 + api-routes 동기화까지만**: ADR 초안 작성은 범위 외 (사용자 결정). spec이 없는 /feature 요청은 planner 투입 제안 경로로 처리.

## 2026-07-23 — 검증 단계 경량화 (경량 시작 원칙)

- **배경**: 4개 체제 전환 직후 사용자 판단 — 검증이 무거워 보이니 처음은 가볍게 시작. "핵심 안전장치 유지" 기조(위 하네스 경량화 3건)를 한 단계 더 완화한 것.
- **경량화 3건**: ① /feature 항목별 리뷰 제거 → 마무리에 전체 diff 1회 (항목당 에이전트 3회 → 2회) ② /feature에서 QA 모드 제외 → QA는 /review-pr(PR 단계)에서만 1회 ③ JaCoCo 커버리지 게이트 60% → 40% (게이트 장치 자체는 유지 — 없애면 재도입이 어려움).
- **추가 경량화 (같은 날)**: 항목별 테스트 실행을 `--tests` 필터로 이번 항목 테스트만 (Testcontainers 전체 실행 회당 1.5~2분 → 수십 초). 전체 회귀는 마무리 `build` 1회가 잡는다 — 항목 사이 회귀는 spec 끝까지 늦게 발견될 수 있음을 수용.
- **재강화 기준**: 감이 아니라 지표 — 이슈→PR 흐름에서 리뷰/QA가 늦게 잡는 결함이 반복되면 해당 단계를 되돌린다.

## 2026-07-23 — /feature 실측 (001-foundation, 측정 후 브랜치 폐기)

- **목적**: 튜터 피드백("무거워 보인다") 이후 실측 없이 추정만 쌓이는 게 문제라 판단, 001-foundation(tasks 5개) 하나를 PR 직전까지 실제로 돌려 시간을 쟀다. 측정 후 코드는 삭제(`feat/001-foundation-measure` 브랜치 통째로 폐기), agent-mistakes.md 발견 1건만 main에 이식.
- **결과**: 브랜치 생성→마무리(build+리뷰+문서동기화)까지 **약 25분** (항목1 Redis 5:41, 항목2 QueryDSL 2:36, 항목3 공통오류 6:20, 항목4 Clock 5:13\*, build 1:21, 리뷰 2:19, 문서동기화 33초). \*항목4는 에이전트 팀 메시지 릴레이 유실로 2~3분 오버헤드 포함 — 순수 작업은 2분대 추정.
- **이전 추정(60~90분) 대비 훨씬 빠름**: 이유 3가지 — ① tasks 항목이 설정 변경 위주로 얇음(도메인 로직 있는 항목은 더 걸릴 것) ② `--tests` 필터 최적화가 실효 ③ 항목별 리뷰 제거가 병목이 아니었음을 확인(마무리 1회로 차단 0건).
- **한계**: PR 생성·병합, QA 모드는 측정 안 함 (사용자 지시로 PR 전 단계까지만). 로그인/로그아웃처럼 도메인 로직이 있는 spec(002 등)의 실측은 아직 없음 — 이 숫자를 전체 spec에 그대로 외삽하지 말 것.
- **부산물**: Boot 4.1 `@WebMvcTest` 패키지 이동 재현 확인 → agent-mistakes.md에 기록.

## 2026-07-23 — github-workflow-agents 스킬(Codex용) 선별 이식

- **출처**: `C:\Users\user\.codex\skills\github-workflow-agents` — 다른 프로젝트(Agora)의 GitHub 이슈 큐 기반 멀티에이전트 운영 스킬. 역할 구조(Coordinator/Dev/Review/Experiment/Planner)는 우리 4개 체제와 동형이라 역할은 안 가져옴.
- **가져온 것 4개**: ① 이슈 템플릿에 "제외 범위"·"선행/충돌 주의" 필드 (범위 폭주·병렬 충돌 방지) ② PR 템플릿에 "남은 위험/후속" 섹션 + "실측 테스트 결과만 기재" 규칙 ③ 작은/큰 루프 규칙 — 리뷰 지적은 같은 PR에서 수정, 범위 밖은 새 이슈 (conventions.md) ④ "다른 작업자의 변경을 되돌리지 않는다" (parallel-agents.md 팀 규칙).
- **안 가져온 것**: 통합 브랜치 dev(우리는 GitHub Flow main 단일), P0/P1/P2+VERDICT(기존 `RESULT: 차단/권장/참고`와 중복), Issue Planner 진단 모드(경량 시작 원칙 위배), blocked 라벨 자동화(CI 하네스 착수 시 재검토 — harness-roadmap).

## 2026-07-23 — 이전 프로젝트 코드 컨벤션 선별 이식 (conventions.md 확장)

- **출처**: coffee-order-system의 `code-style-guide.md` + `layered-design-policy.md` (레포 외부 — 사용자 로컬). 스파게티 방지 목적.
- **가져온 것**: 계층별 담당/비담당 목록 + 금지 패턴 + 리뷰 체크 질문, Entity 규칙(`@NoArgsConstructor(PROTECTED)`·의도 드러나는 상태 변경 메서드), 응답 DTO `from(entity)` 정적 팩토리, 테스트 네이밍(`XxxTest`/`XxxIntegrationTest`·문장형 camelCase), 상수 UPPER_SNAKE_CASE, wildcard import 금지, 인프라 연동 위치 표(FinPlay용으로 Redis 시세·업비트 WebSocket에 맞게 수정), REST 메서드·상태코드 규칙, QueryDSL 사용 기준.
- **패키지 구조는 계층 하위 패키지로 확정** (사용자 결정): 도메인 안을 `controller/service/repository/domain/dto`로 나눔 (예전 프로젝트 방식). ADR-0002의 "도메인 우선" 결정은 그대로이고 도메인 내부 배치만 구체화한 것이라 ADR 대체 불필요 — 정본은 conventions.md 패키지 구조 절.

## 2026-07-23 — 팀 노션 코드 컨벤션 선별 이식 + 포매터 NAVER 교체

- **출처**: 팀 노션 "Code Convention" 페이지 (JoJoPay 등 팀원 이전 프로젝트 기반 초안). 충돌 3건은 사용자 결정으로 정리.
- **충돌 결정 3건**: ① DTO는 **record 유지** (노션의 `@Getter` 클래스 방식 채택 안 함 — Java 17 표준·불변·간결) ② 응답 포맷은 **PRD 방식 유지** — 성공은 DTO 그대로, 에러만 `{"error":{...}}` (노션의 CommonApiResponse 전면 래핑은 PRD §5·프론트 계약과 충돌) ③ **포매터는 NAVER로 교체** — 단 Spotless 게이트는 유지하고 스타일만 `eclipse().configFile(config/naver-eclipse-formatter.xml)`로 교체. 리포맷 범위는 자바 4파일뿐이라 기능 코드 없는 지금이 교체 적기였음.
- **가져온 것**: DTO 용도별 접미사 표(CreateRequest/UpdateRequest/DetailResponse/ListResponse/ListItemResponse, 내부 전달용만 `~Dto`), `dto/request`·`dto/response` 하위 패키지, 요청 DTO 검증 규칙(Wrapper 타입·`@Size(max)` 필수·한글+마침표 메시지), 엔티티 정적 팩토리 네이밍(create/of, 외부 new 금지)과 DTO 비의존, Service null 반환 금지·즉시 예외, boolean `is~/has~`·시간 `xxxAt`, 테스트 보강(@DisplayName 병기·AssertJ·jsonPath 상세 검증·`mock(Response.class)` 지양).
- **안 가져온 것**: CommonApiResponse(위 ②), @Getter 클래스 DTO(위 ①), 컨트롤러 커버리지 100% 목표(우리 게이트는 40% 시작 — 경량 시작 원칙), JavaDoc @author/@since 헤더(우리는 한 줄 한국어 주석 규칙).
- **안 가져온 것**: 예외 처리·로깅 절 (원문 스스로 "일관되지 않아 규칙 아님"이라 명시 — 우리는 이미 전역 핸들러+ErrorCode로 통일), Redisson·Kafka 연동 위치 (1차 미사용).

## 2026-07-23 — run-log.md 신설 (실행 로그, 001-foundation 실측 직후)

- **배경**: 001-foundation 실측(25분) 직후 사용자가 "에이전트 실행 로그가 있냐"고 질문 — 사람용/AI용 분리, 어떤 명령·근거로 판단했는지. 확인해보니 없었음. context-notes에 이전에 "evidence 6종 파일 체계"를 유지비 문제로 의도적으로 뺐던 기록이 있어 먼저 그 사실을 알리고 재확인 후 진행.
- **범위 축소로 경량 유지** (사용자 결정): 기록 주체는 **implementer·reviewer만** (tester·planner 제외). 파일은 spec당 1개 `run-log.md`, 섹션만 AI용/사람용으로 분리 (파일 2개로 안 쪼갬 — 쓰기 호출 절반으로 줄임). 각 항목 1줄 제한.
- **속도 영향 판단**: 마크다운 append 1회(Edit 호출)라 빌드·테스트가 아니므로 항목당 5~15초 수준, 무시 가능하다고 사용자에게 답변.
- **위치**: `docs/specs/README.md`에 형식 정의, `.claude/agents/implementer.md`·`reviewer.md`(리뷰·QA 모드 둘 다)에 기록 단계 추가. context-router.md는 건드리지 않음 (참조 문서가 아니라 필요시 훑어보는 보조 자료로 명시).

## 2026-07-23 — dev 통합 브랜치 전환 + 레포 공개

- **레포 푸시**: `finplay-team/finplay` (public). ADR-0006 권장명 `finplay-api` 대신 `finplay` — 팀 결정.
- **브랜치 전략 변경** (사용자 결정): GitHub Flow(main 단일) → **dev 통합 브랜치**. main은 배포·시연용(직접 푸시 금지, dev에서 PR로만), dev가 GitHub 기본 브랜치이자 모든 기능 PR 대상. 경량 경로(문서·하네스 잡일)는 dev 직접 커밋 허용. 이전에 github-workflow-agents 이식 때 "dev 안 가져옴(main 단일)"이라 기록했으나 이번 팀 결정으로 뒤집힘.
- **기준 브랜치 참조 일괄 교체**: ci.yml 트리거 `[main, dev]`, /feature·/review-pr·planner의 `git diff main...` → `dev...`, specs/README 예시. 브랜치 보호는 main만 (PR 필수 + 승인 1). required status check는 첫 PR로 CI 잡 이름 확인 후 추가 예정.
- **마일스톤·이슈 17개 생성은 보류** (사용자): 팀에서 "팀원·API별로 이슈를 만들자"는 논의가 있어 굵기 재논의 후 진행.
- **dependabot 버전 업데이트 제거** (사용자 결정): 푸시 직후 자동으로 열린 bump PR 8개가 계기. 단기 MVP에서 주간 버전 최신화는 노이즈 > 이익이라 `dependabot.yml` 삭제 + PR 8개 닫음. 대신 **Dependabot alerts + security updates는 켜둠** — 취약점 발견 시에만 경고·패치 PR. 부산물: 첫 CI 실행이 `gradlew` 실행 비트 누락(Windows 커밋)을 드러내 수정함 (agent-mistakes 기록).

## 2026-07-24 — 튜터 피드백 2건 반영 (CI 제거 · yml 시크릿 전면 금지)

- **CI 워크플로우 제거** (튜터: "CI는 배포 단계에 들어가야지 왜 벌써 만들었나"): `.github/workflows/ci.yml`(빌드·spotless·paths-filter)과 `pr-title.yml` 삭제. 재도입 시점은 배포 단계 — harness-roadmap 상단에 기록. 그 전까지 빌드 게이트는 **PR 작성자의 로컬 `./gradlew build` 의무** + /review-pr의 tester 빌드 검증으로 대체. 머지 조건에서 "CI 통과" 문구 제거 (conventions·README).
- **yml 시크릿 전면 금지** (튜터: "이 키가 왜 들어가있냐"): application-local.yml의 로컬 개발용 JWT 기본값(placeholder)도 제거 — "로컬 전용 기본값" 예외를 폐지하고 **모든 시크릿은 .env/환경변수로만** 주입으로 규칙 강화 (conventions 시크릿 절). compose.yaml 로컬 DB 비밀번호만 유일한 예외로 유지. .env.example의 "로컬은 생략 가능" 문구도 수정. jwt 바인딩 코드가 아직 없어 런타임 영향 없음.
- **레포 재생성 3회차**: 시크릿이 git 히스토리에 남는 문제 때문에 기존 히스토리를 정리하고 새로 푸시 (아래 방식은 사용자 확인 후 결정).

## 2026-07-24 — MVP 범위 재확정 (배포 편입 · 차수 용어 · 마일스톤)

- **배포를 1차 MVP 안으로 편입** (사용자 결정): 기존 PRD §3·§8에 배포가 아예 없었다. 마지막 주에 처음 배포하면 인프라 문제가 발표 직전에 터지므로 1주차에 첫 배포를 시작한다. `010-deployment` spec 신설, PRD §8에 태스크 10 추가. **번호가 착수 순서가 아니다** — 10(배포)이 9(통합 검증)보다 먼저다. 9는 마지막 주 졸업시험 역할로 유지.
- **최소 CI 재도입 시점 확정**: 첫 배포 직전(010). 2026-07-24에 제거했던 CI(위 항목)의 복귀 조건이 충족된 시점이다. `harness-roadmap.md`가 다루는 "이슈 트리거 CI 하네스"와는 다른 것 — 010은 빌드 게이트, roadmap은 에이전트 실행 자동화. 문서 상단에 구분해 적었다.
- **차수 용어 대응표 추가**: 팀 회의의 "MVP / 1차 고도화 / 2차 고도화"가 PRD의 "1차 / 2차 / 3차 MVP"와 같은 범위인데 이름만 달라 C-001 단계 잠금 판정이 흔들릴 수 있었다. PRD §3에 대응표를 넣고 **판정 기준은 PRD 이름**으로 못박았다.
- **마일스톤은 게이트가 아니다**: 주차별 중심 목표는 구분하되 개발·테스트·버그픽스·배포·문서화는 병렬 진행한다. 발표 지표는 개발 초기부터 수집.
- **베이스라인용 인위적 순차 진행은 하지 않기로** (사용자 결정): 발표 비교군을 만들려고 001·002를 일부러 단일 세션으로 돌리자고 제안했으나 철회. MVP 완료가 우선이고, 자연 발생한 순차/병렬 Issue의 지표를 사후 비교한다.
- **역할 구분 합의** (문서 반영은 아직): 사람 Project Coordinator(우선순위·승인·병합·배포 결정) / AI Orchestrator(의존성 분석·배정·worktree 격리·흐름 실행·병합 순서 제안). 새 에이전트를 만드는 것이 아니라 ADR-0008의 "메인 세션 = 오케스트레이터" 위에 사람 통제 계층을 명시하는 것이라 **ADR을 새로 쓰지 않기로** 했다. `parallel-agents.md`+`CLAUDE.md` 반영은 미완.
- **Issue 굵기는 미확정**: API별 / 기능 묶음별 / tasks.md 항목별 3안 중 팀 회의에서 결정한다. 임의 확정 금지 지시가 있어 `002/tasks.md` 상단에도 "9개 Issue로 확정하지 않는다"를 명시했다.

## 2026-07-24 — 이메일 인증을 가입 선행 단계로 편입 (AUTH-004)

- **인증 완료 전에는 회원가입이 성립하지 않는다** (팀 확정): 인증번호 요청 → 확인 → 일회용 `signupVerificationToken` 발급 → 그 토큰으로 signup. **인증 단계에서 `users`·`accounts` 행을 만들지 않는다** — 미인증 유령 계정이 `UNIQUE(email)`을 선점하는 문제를 원천 차단.
- **저장소는 Redis가 아니라 MySQL** (제안 반려): Redis 안이 먼저 나왔으나 두 가지 이유로 뒤집었다. ① PRD §6 "Redis에는 코인 최신 시세·수신시각·연결상태**만** 저장한다"와 정면 충돌 — 채택하면 spec 3개(001·002·003)에 파급된다. ② **토큰의 1회 소비가 가입 트랜잭션과 원자적이어야 한다.** Redis는 롤백이 없어 GETDEL 성공 후 MySQL이 실패하면 토큰이 날아간다. MySQL이면 `UPDATE ... WHERE consumed_at IS NULL`이 같은 트랜잭션에 들어가 함께 롤백된다.
- **가입 실패 시 토큰은 소비되지 않는다**: 닉네임 중복(409) 등으로 트랜잭션이 롤백되면 소비도 롤백되어 남은 30분 안에 같은 토큰으로 재시도할 수 있다. 이걸 비즈니스 규칙으로 명시하지 않으면 구현자가 토큰을 먼저 소비하도록 짜기 쉽다.
- **토큰 이메일과 요청 이메일 일치 검증 필수**: 초안에 빠져 있던 조건. 없으면 A 이메일로 인증받고 B 이메일로 가입할 수 있어 인증 자체가 무의미해진다.
- **해시는 심층방어이지 주 방어가 아니다**: 인증번호는 `EMAIL_VERIFICATION_SECRET` 기반 HMAC-SHA-256, 토큰은 SHA-256. 다만 6자리 숫자는 후보가 10^6이라 secret까지 유출되면 즉시 역산된다. **주 방어는 5분 만료와 5회 시도 제한**임을 문서에 적었다 — "해시했으니 TTL을 늘려도 된다"는 오판을 막기 위함.
- **IP별 발송 제한은 1차 고도화로 미룸** (제안 반려): 배포 환경이 미정인 상태에서 `X-Forwarded-For` 추출이 틀리면 전체 사용자가 한 IP로 묶여 서비스가 통째로 막힌다. 시연 중 터지면 최악이라 이메일별 제한(60초·시간 5회·일 10회)만 채택.
- **만료 데이터 정리 배치는 MVP 제외**: 데이터량이 적다. 필요해지면 003의 `StockCandleCleanupJob` 패턴을 그대로 따르면 된다.
- **이메일 열거는 의도적으로 감수**: 이미 가입된 이메일의 인증 요청은 409로 즉시 거부한다. signup에서 어차피 409가 나오고 닉네임 중복도 노출되므로 MVP에서 완전 차단은 달성 불가능한 목표라고 판단.
- **발송 수단은 Resend** (SES 반려): **SES는 샌드박스가 기본이고 프로덕션 액세스 승인에 하루 이상** 걸려 이번 주 배포 일정을 외부 승인에 묶는다. Resend는 API 키만으로 즉시 시작 가능하고, `spring-boot-starter-webmvc`의 `RestClient`로 호출하므로 **의존성 추가가 0개**다. `EmailSender` 인터페이스를 둬 나중에 SES로 교체 가능.
- **운영 프로필에서만 Resend 활성화**: `RESEND_API_KEY`·`EMAIL_FROM`이 없어도 앱 기동과 `./gradlew build`가 성공해야 한다. JWT처럼 무조건 필요한 값으로 바인딩하지 않는다.
- **`FRONTEND_BASE_URL` 제거**: 초안 환경변수 목록에 있었으나 6자리 코드 입력 방식이라 매직링크용 URL이 필요 없다. 링크 방식의 흔적이 섞여 있던 것.

## 2026-07-24 — OAuth 이메일 자동 계정 연결 제거

- **이메일이 같다는 이유만으로 소셜 계정을 기존 회원에 연결하지 않는다**: 기존 PRD AUTH-003은 자동 병합이었다. 자동 병합은 "제공자 이메일 검증 신뢰"에 의존하는데, 원칙적으로 계정 탈취 경로가 된다. 이제 409 `ACCOUNT_LINK_REQUIRED`로 거부하고 이메일 로그인을 안내한다.
- **명시적 연결 기능도 MVP에서 만들지 않는다** (제안 축소): "기존 계정 로그인 후 명시적 연결"까지 넣으면 연결 전용 콜백 분기·state 관리·프론트 UI가 필요하다. 교육·시연용 MVP에서 한 사람이 같은 이메일로 두 경로를 쓰는 경우는 드물어, **막기만 하고 해결은 1차 고도화로** 미뤘다. 부수 효과로 002가 오히려 단순해졌다 — "연결 또는 신규 생성" 분기에서 병합 경로가 통째로 사라졌다.
- **식별자는 `provider + providerUserId`**, 이메일은 식별자가 아니다. 이건 기존 문서에 이미 있어 변경 없음.
- **이메일 미제공 시 거부** (`OAUTH_EMAIL_REQUIRED` 400): `users.email`을 nullable로 바꾸면 `MemberResponse` 등 모든 곳에 null 분기가 생긴다. NOT NULL을 유지하고 거부하는 쪽이 단순하다. 카카오는 이메일이 선택 동의라 콘솔에서 필수로 설정해야 한다.

## 2026-07-24 — 주식 시세 공급자 2종 분리 (C-007 / MKT-007)

- **`StockPriceProvider` 계약 뒤에 구현 2개**: `KrxReplayPriceProvider`(공개 배포 기본) / `KisRealtimePriceProvider`(개발자 본인 전용). `PriceQueryService`·가격/캔들 API·SSE·모의 체결·평가손익은 **어느 Provider가 동작 중인지 몰라야 한다.** 화면 가격과 체결가격은 항상 같은 Provider에서 나온다.
- **KRX 재생 구현은 삭제하지 않는다**: KIS 도입 여부와 무관하게 공개 서비스의 안전한 대체 수단으로 유지한다.
- **"실시간 수신 가능"과 "공개 표출 가능"은 별개 판단**: 전자는 기술 가능성, 후자는 계약·허가 문제다. 이 구분이 이 정책의 핵심이라 C-007 첫머리에 뒀다.
- **KIS 사용 범위를 개발자 본인 전용으로 한정** (초안 정정): 처음에 "개인 개발·제한 시연"이라고 썼는데, **확인된 것은 개발자 본인의 Open API 사용 가능 여부뿐**이고 제3자 표출 허용 여부는 확인된 바 없다. **팀원·튜터·심사위원도 제3자**이므로 시연 화면은 공개 배포와 동일하게 `KRX_REPLAY`를 쓴다. 전 문서에서 "개인 개발·제한 시연" → "개인 개발·본인 전용 검증"으로 교체.
- **`SERVICE_EXPOSURE=PRIVATE`는 "로그인 필요"가 아니다**: KIS 개인 계정 소유자인 개발자 본인만 접근 가능한 로컬·접근 통제 환경을 뜻한다. 이 환경을 공개 URL이나 다중 사용자 서버로 운영하지 않는다. 팀원이 접근하는 순간 그 환경은 `PUBLIC`이다.
- **fail-fast는 시작 시점 판정**: `PUBLIC` + `KIS_REALTIME` + `KIS_PUBLIC_DISPLAY_APPROVED=false`면 애플리케이션이 기동하지 않는다. 경고 로그만 남기고 뜨는 동작은 금지 — 잘못된 조합으로 공개 서비스가 떠 있는 시간을 0으로 만들기 위함.
- **플래그가 허가를 만들지 않는다**: `KIS_PUBLIC_DISPLAY_APPROVED=true`로 바꾸는 것은 서면 확인 결과를 시스템에 반영하는 수단일 뿐이고, **정본은 한국투자증권의 서면 허가·계약**이다. 현재 이 답변은 받은 적이 없다.
- **Decision Gate 2개를 분리해 기록**: ① 한국투자 서면 답변(공개 Provider 전환) ② KRX 상품 답변(분봉 임계치·파서·수집 시각). 섞이면 "KRX 때문에 003 전체가 막힌다" 같은 오판이 생긴다 — 실제로 003은 샘플 데이터로 진행 가능하고 004~007을 막지 않는다.
- **KIS 틱의 1분 OHLCV 집계는 서버 책임**: 체결 틱을 차트에 쓰려면 봉으로 묶어야 하는데, 클라이언트가 하면 Provider마다 차트가 달라진다. `KisTickAggregator`가 `KrxReplayPriceProvider`와 같은 분봉 모델을 반환한다.

## 2026-07-24 — 검증 흐름 경량화 (전체 build 중복 제거)

- **같은 커밋에서 `./gradlew build`가 2회 돌고 있었다**: `/feature` 마무리(SKILL.md 5번)와 `/review-pr` tester. `conventions.md`의 "PR 전 build 의무"는 규칙 설명이지 별도 실행 단계가 아니므로 실제 중복은 3회가 아니라 2회다 (사용자 정정).
- **로컬 build와 CI build는 목적이 달라 둘 다 필요하다**: 로컬은 "PR 올릴 자격"(빠른 실패), CI는 "머지 자격"(환경 독립성). 로컬은 Windows라 `gradlew` 실행 비트 누락·한글 경로 같은 문제를 못 잡는다 — 실제로 겪은 유형이다. **없애야 하는 건 `/review-pr`의 build**다.
- **재사용 조건에 `merge-base`를 포함**: SHA 일치와 통과 여부만으로는 부족하다. 내 브랜치가 그대로여도 dev에 다른 사람의 마이그레이션이 머지되면 머지 후 빌드가 깨진다. 병렬 운영에서는 이게 예외가 아니라 기본값이라 3번째 조건으로 넣었다.
- **CI 도입 후에는 `gh pr checks` 판독으로 대체**: tester의 역할이 "build 실행"에서 "CI 결과 판독 + 실패 원인 분석"으로 바뀐다. 실패 분석 능력은 그대로 쓰인다.
- **생략 근거를 리뷰 본문에 적는다**: 돌리지 않은 검증을 통과했다고 쓰지 않기 위함 (PRD C-005). 문서 전용 PR / CI 통과 / SHA 인용 중 무엇인지 명시.
- **QA 조건부 투입은 이미 구현돼 있었다**: `/review-pr` SKILL.md가 이미 "controller/API가 포함된 경우에만"이라 변경 불필요했다.
- **커버리지 게이트는 40% 유지** (팀 확정): `checklist.md`가 60%로 적혀 있었으나 정본은 `conventions.md`의 40%다. 도입 당시 60% → 4개 체제 전환 때 40%로 조정한 이력이라, 이력 줄은 남기고 현재값 표기만 고쳤다.
