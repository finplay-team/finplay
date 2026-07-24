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

## 2026-07-24 — 이슈 #31 Redis·QueryDSL·컨테이너 기반 설정

- **이슈 #31은 spec 001-foundation의 부분집합**: 이슈 본문 제외 범위에 `GlobalExceptionHandler`·`Clock`이 명시돼 있어 오류 체계·Clock은 건드리지 않음. 이번 PR은 Redis·QueryDSL·Kafka(compose) 기반만 담당 (파일 소유권 분리 — build.gradle.kts·compose.yaml·TestcontainersConfiguration은 이 PR만 수정). spec tasks.md 항목 3~5(오류 체계·Clock·마무리)는 후속 이슈 몫으로 미완료 유지.
- **Redis Testcontainer는 `GenericContainer` + `@ServiceConnection(name = "redis")`**: 전용 Redis 모듈 추가 없이 core testcontainers만으로 Spring Boot가 Redis 연결로 인식하게 함. MySQL과 같은 static 싱글턴(ADR-0003). 이미지 `redis:7.4` 고정.
- **compose 포트**: MySQL 13307 패턴을 따라 Redis `16379:6379`, Kafka `19092:9092` (로컬 6379·9092 점유 회피). spring-boot-docker-compose가 매핑 포트 자동 감지.
- **Kafka는 compose 전용**: `apache/kafka:3.9.1`(KRaft 단일 노드) 추가하되 `spring-kafka` 미의존이라 앱은 연결 시도 안 함 → "앱 기동 성공 조건에서 제외" 충족. 테스트는 TestcontainersConfiguration만 쓰고 Kafka 컨테이너가 없으므로 "Kafka 없이 테스트 가능"도 충족.
- **QueryDSL은 설정+compileJava까지만** (plan 지시): querydsl 5.1.0 jakarta 분류자 + apt 애노테이션 프로세서. 엔티티가 없어 Q클래스는 생성 안 되지만 compileJava 통과 확인. `JPAQueryFactory` 빈은 공용 기반으로 `com.finplay.api.common.QuerydslConfig`에 미리 제공 (002+ 리포지토리가 바로 주입). Q클래스 생성 검증은 엔티티가 생기는 002부터.
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
