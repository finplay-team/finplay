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

- **PRD 정본은 `ai/prd.md`**: Notion 원본은 사람용 미러. 에이전트(팀원 세션 포함)가 Notion에 접근 못 하므로 레포가 정본. 반입 시 확정 결정 3건은 prd.md 상단 "정본 안내" 블록에 기록.
- **제품명 FinPlay** (구 TradeClass/Investory), **Java 17** (구 21): ADR-0006. 레포 폴더명(`Desktop\tradeclass-api`)·GitHub 레포명 변경은 클론 경로 영향 때문에 팀 합의 후 수동 진행 예정.
- **에러 응답은 `{"error":{code,message,requestId}}`**, **URL 버저닝 미사용** (`/api/v1` 금지): conventions.md에 반영. PRD 원본의 `/api/v1` 표기보다 conventions가 우선.
- **CI 하네스 전환은 로드맵으로만 기록** (`ai/harness-roadmap.md`): 튜터 제안(이슈 트리거 → 러너 에이전트 → PR → 사람 머지 + 정량 지표). 1차 MVP 진행 후 ADR-0007로 착수. 베이스라인 확보를 위해 지금부터 이슈→PR 흐름으로 작업.
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

## 2026-07-24 — Gradle 빌드 DSL을 Kotlin(.kts) → Groovy 전환

- **결정 배경**: 사용자 요청으로 `build.gradle.kts`·`settings.gradle.kts`를 Groovy(`build.gradle`·`settings.gradle`)로 전환. 오케스트레이터는 "얻는 이득 없음 + 초기 셋팅의 Kotlin DSL 결정을 되돌림"을 이유로 유지를 2회 권고했으나 사용자가 진행 결정.
- **작업 범위는 2개 파일뿐**: `.gradle.kts`는 이 둘이 전부였고, 소스(`src/`, Java)·래퍼(`gradlew*`)·README·CLAUDE.md(명령어만 참조)·CI(`.github/workflows` 부재)는 무관. 문법은 따옴표(`"`→`'`)·제네릭(`withType<Test>`→`withType(Test)`)·`.map`→`.collect`·`fileTree(it){exclude}`→`fileTree(dir:,exclude:)` 등 기계적 변환.
- **올린 위치**: 사용자 결정으로 이슈 #32 PR(#36)에 함께 커밋. 오케스트레이터는 "오류 체계 PR에 무관한 빌드 변환이 섞이고 build.gradle은 원래 #31 소유 파일"이라며 별도 PR 분리를 권고했으나 사용자가 #36 유지 선택.
- **검증**: Groovy 전환 후 `./gradlew build` BUILD SUCCESSFUL (compileJava·test·jacoco 커버리지 게이트·spotbugs·spotless 전부 정상 동작).
- **병렬화는 spec 단위, 수단은 Agent View 우선** (`ai/parallel-agents.md`): Agent View(`claude agents`)는 내장 + 자동 worktree 격리라 "agentview 만들기" 요청은 문서화로 대체. 에이전트 팀은 실험 플래그로 활성화(팀원 간 직접 메시징)하되 파일 격리가 없어 코드 수정엔 파일 소유권 분리 필수. /feature 루프 내부는 순차 유지 (단계 의존 = 품질 게이트).

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
- **위치**: `ai/specs/README.md`에 형식 정의, `.claude/agents/implementer.md`·`reviewer.md`(리뷰·QA 모드 둘 다)에 기록 단계 추가. context-router.md는 건드리지 않음 (참조 문서가 아니라 필요시 훑어보는 보조 자료로 명시).

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

## 2026-07-28 — Notion API 표 대조 후 PRD·spec 006 동기화 (이슈 #51 문서 동기화 항목 반영)

- **`GET /api/accounts` 계약 목록에서 삭제**: 최신 Notion MVP API 표에는 이미 이 엔드포인트가 없다. 이슈 #11이 이 사유로 "not planned" 종료됨 — "시장별 계좌 정보는 #12(`/api/accounts/summary?market=`)로 관리하므로 중복 구현하지 않는다." `ai/prd.md` §5 계약 목록만 뒤늦게 반영이 안 돼 있었다.
- **포트폴리오 합산 조회 경로 `/api/portfolio/summary` → `/api/portfolio`**: 이슈 #51에 팀이 이미 "최신 Notion 계약과 저장소 PRD/spec의 `/api/portfolio/summary`가 충돌하므로 구현 전에 문서를 먼저 동기화한다"고 명시해뒀고, 그 동기화 체크박스가 미완료 상태였다. `ai/prd.md` §5와 `ai/specs/006-portfolio-query/spec.md`(ACCT-003)의 경로를 Notion 확정값인 `/api/portfolio`로 맞췄다. 아직 컨트롤러가 없어 `ai/api-routes.md`는 영향 없음 — 구현 시 이 경로로 만들면 된다.
- **Notion 쪽이 낡은 것은 레포에서 고치지 않음**: 매수 투자일기 API의 Notion 단계 표기("1차 고도화")는 2026-07-24/27 팀 결정(PRD·이슈 #52로 이미 "1차 MVP" 확정)보다 낡았고, 닉네임 수정 API의 Notion 상태("진행 중")도 이슈 #54 병합·종료보다 낡다. 이 둘은 Notion 편집 권한이 없어 레포에서 대신 고치지 않고 팀에 공유만 한다.
- **미해결로 남긴 것**: 체결내역(`/api/trades`) Notion 비고의 "market 생략 시 전체 시장 통합 조회 지원 필요" 요구사항은 PRD·spec 006·이슈 #22 어디에도 없다. 새 요구사항인지 팀 확인이 필요해 임의로 spec에 추가하지 않았다.

## 2026-07-28 — 체결내역 통합 조회 제안 반려 (사용자 결정)

- **주식·코인은 항상 시장별로 나눠서 조회한다**: 바로 위 미해결 항목(Notion 비고의 "market 생략 시 전체 시장 통합 조회 지원 필요")을 사용자가 즉시 반려했다. `market`은 `GET /api/trades`의 필수 파라미터로 유지하고 생략 시 통합 조회 기능은 만들지 않는다.
- **반영 위치**: `ai/prd.md`(PORT-002, §5 계약 목록), `ai/specs/006-portfolio-query/spec.md`(PORT-002), 이슈 #22(수용 기준 추가 + 코멘트).

## 2026-07-28 — 매수 투자일기 작성 API를 1차 MVP → 2차(1차 고도화)로 하향 (사용자 결정, 바로 위 2026-07-28 노트의 판정을 뒤집음)

- **"Notion이 맞다"**: 위 노트에서 "Notion 단계 표기가 낡았다"고 판단했던 것과 반대로, 사용자가 최신 Notion 표(1차 고도화)를 정본으로 확정했다. 2026-07-24/27에 이미 내렸던 "매수 체결별 투자일기 작성은 1차 MVP" 결정을 이걸로 뒤집는다 — PRD C-001 단계 잠금 판정 기준이 다시 Notion 기준으로 돌아간 사례.
- **`ai/prd.md` 전면 반영**: §0 변경표, §3 로드맵(1차 목록에서 삭제 → 2차 목록에 추가, 1차 명시적 제외 범위에 "작성"도 추가), §4 PORT-004 요구사항 블록 삭제(요구사항 초안은 `ai/specs/007-journal/spec.md`에만 유지), §5 API 계약에서 `POST /api/trades/{buyTradeId}/journal` 제외, §6 데이터모델에서 `trade_journals` 테이블·`UNIQUE` 제약 삭제, §7 `portfolio` 패키지 설명·트랜잭션 경계 목록에서 투자일기 항목 삭제, §8 태스크 순서 7번을 취소선 처리(스펙 폴더 번호·이후 태스크 8·9·10 번호는 유지), §9 필수 자동 검증·핵심 E2E에서 투자일기 관련 문구 삭제.
- **`ai/specs/007-journal/spec.md`**: 헤더 단계를 "2차 MVP (1차 고도화)"로 변경, 2차 착수 전까지 plan·tasks 작성과 구현 보류를 명시. 파일 자체는 삭제하지 않음 (요구사항 초안 보존용).
- **이슈 #52**: 제목 `[MVP]` → `[1차 고도화]`로 변경, 본문의 "결정 반영" 체크리스트를 취소선 처리하고 2026-07-28 정정 배경을 상단에 추가. 지금 구현 착수하지 않고 2차 시점에 재확정.
- **닉네임 API의 Notion "진행 중" 표기는 그대로 둠** (사용자: "그냥 넘어가") — 실제로는 완료(이슈 #54 종료)이지만 레포·Notion 어느 쪽도 지금 수정하지 않는다.

## 2026-07-28 — 주식 과거 데이터 소스를 KRX → 한국투자증권(KIS) Open API로 전환 (사용자 결정, 이슈 #17)

- **KRX 서면 안내 대기를 더 기다리지 않는다**: 한국투자증권 Open API 키를 발급받아 과거 1분봉도 KIS로 조회하기로 결정했다. 한국투자증권에 문의해 "과거 데이터는 공공데이터이므로 자유롭게 사용 가능"이라는 답변을 받아, PRD C-006의 KRX 서면 허가 대기(Decision Gate)가 과거 데이터 쪽에서는 해소됐다. **실시간 시세의 제3자 표출(C-007)은 별개로, 아직 확인된 바 없어 기존 제약을 그대로 유지한다** — 개발자 본인 전용 검증 환경에서만 `KIS_REALTIME`을 쓰고, 공개 배포·시연은 여전히 과거 데이터 재생(`KIS_HISTORICAL`, 구 `KRX_REPLAY`)을 쓴다.
- **네이밍**: `STOCK_FEED_PROVIDER=KRX_REPLAY` → `KIS_HISTORICAL`, `KrxReplayPriceProvider` → `KisHistoricalReplayPriceProvider`, `KrxFileImporter` → `KisHistoricalCandleCollector`(파일 임포트가 아니라 API 호출이므로 이름도 교체). 단, 이슈 #16에서 **이미 병합된 코드**(`KrxReplayPriceProvider`, `KRX_REPLAY` enum 값)는 이번 문서 작업에서 리네이밍하지 않았다 — `ai/specs/003-market-data/tasks.md`에 별도 결정 필요로 남겨뒀다.
- **팀원 Claude의 이슈 #17 범위 지적을 반영해 3분할**: 이슈 #17(MVP)은 캔들 조회 API + KIS Open API 기반 과거 데이터 수집의 기본 동작(`UNIQUE` 제약 기반 멱등성, 성공/부분성공/실패 판정)까지만 남기고, ① KIS 실시간 틱 집계(`KisRealtimePriceProvider`·`KisTickAggregator`)는 이슈 [#82](https://github.com/finplay-team/finplay/issues/82), ② 수집 파이프라인 장기운영 방어 로직(동일 거래일 재수집 세부 정책·`StockCandleCleanupJob`)은 이슈 [#83](https://github.com/finplay-team/finplay/issues/83)으로 분리했다. 근거: ①은 `PUBLIC`+`KIS_HISTORICAL`이 공개 배포 기본값인 한 MVP 데모 경로에서 한 줄도 실행되지 않고, ②는 MVP 데모 기간에는 20영업일치가 쌓이지도 않고 재수집 시나리오도 반복 운영해야 의미가 생긴다.
- **반영 위치**: `ai/prd.md`(C-006·C-007·MKT-002·005·006·007), `ai/specs/003-market-data/{spec,plan,tasks}.md`, `ai/specs/009-integration/spec.md`, `ai/specs/010-deployment/spec.md`, GitHub 이슈 #17(본문 수정) + #82·#83(신규 생성). 브랜치 `docs/017-krx-to-kis-transition`에서 작업 후 PR 예정.
- **이슈 본문에는 "KRX"를 아예 언급하지 않는다** (사용자 피드백): 처음에 "KRX가 아니라 한국투자증권을 쓴다"고 대비해서 썼다가 반려당함 — 대외적으로 보이는 이슈에는 이전 방식을 언급하지 않고 현재 결정만 명시한다. 내부 계획 문서(PRD·spec·plan·context-notes)는 의사결정 맥락 보존을 위해 KRX 언급을 유지했다.

## 2026-07-30 — 코인 차트를 빗썸 실제 캔들로 연동 (사용자 결정, MKT-008 신설 · 이슈 #20 교체)

- **발견**: 이슈 #20이 "빗썸에서 실제 차트를 연동해 보여주는" 것으로 이해되고 있었으나, 실제 #20의 범위는 `/api/cryptos/stream` SSE(현재가 틱)뿐이었다. `ai/specs/003-market-data/spec.md`의 범위 제외에 "코인 과거 틱·코인 캔들 영구 저장 (PRD 1차 계약의 candles는 주식 1분봉 기반 — 코인은 최신 틱만)"이 있어 **코인 차트 데이터가 1차 설계에 존재하지 않았다.** 캔들 API도 코인 `instrumentId`를 400으로 거부하고 있었다(이슈 #17 계약).
- **결정**: 코인 1분봉 차트를 1차 범위에 넣고 요구사항 `MKT-008`을 신설했다. 데이터 출처는 **빗썸 공개 캔들 REST API**(`GET https://api.bithumb.com/v1/candles/minutes/1?market=KRW-BTC&to=&count=`, 인증·API Key 불필요, `count` 최대 200).
- **왜 REST 조회·중계인가 (틱 집계를 택하지 않은 이유)**: WebSocket 체결 틱을 서버에서 1분 OHLCV로 집계하는 방식(KIS의 `KisTickAggregator`와 같은 접근)은 서버가 오래 떠 있어야 과거 봉이 쌓여, 데모 시작 직후에는 빈 차트가 된다. 빗썸 REST는 과거 200개를 즉시 주므로 기동 직후에도 차트가 그려진다. 대신 **저장하지 않는다** — 코인 캔들 테이블도, Redis 캐시 키도 만들지 않아 "Redis에는 최신 시세·수신시각·연결상태만"이라는 기존 불변식(PRD §6)을 그대로 유지한다.
- **실제 응답을 조회해 확인한 사실** (추측 아님): 응답은 최신→과거 **내림차순**이고, 가장 최신 봉은 **아직 마감하지 않은 진행 중 분봉**이다. 필드는 `trade_price`가 종가(현재가가 아니다), `candle_acc_trade_volume`이 코인 수량, `candle_acc_trade_price`가 거래대금이다. 따라서 정렬 반전 + 진행 중 봉 제외 + 필드 혼동 방지를 계약에 명시했다.
- **파생 결정 3건**: ① `CandleResponse.volume`을 `long` → `BigDecimal`로 확대 — 코인 수량이 `0.26725783`처럼 소수라 `long`이면 0으로 잘린다(주식 응답 표현은 불변, 회귀 테스트로 고정). ② 이슈 #17이 넣은 "코인 instrumentId면 400 `VALIDATION_ERROR`" 거부를 제거 — 프론트에 이 400을 분기하던 코드가 있으면 함께 정리해야 한다. ③ 빗썸 조회 실패용 `ErrorCode.MARKET_DATA_PROVIDER_ERROR`(502) 신설, 기존 `OAUTH_PROVIDER_ERROR`(502)와 같은 "외부 공급자 오류" 패턴. **빈 배열 200으로 성공을 위장하지 않는다** — 주식의 200 `[]`("아직 공개할 분봉이 없다"는 정상 상태)와 성격이 다르다.
- **코인 캔들과 코인 현재가는 완전히 독립 경로다**: 캔들은 REST → `CryptoCandleProvider` → 캔들 API, 현재가는 WebSocket → `PriceStore`(Redis) → `PriceQueryService`. 따라서 WebSocket이 끊겨 현재가가 409여도 차트는 200일 수 있고, 반대로 차트가 502여도 현재가·주문은 정상이다. 이 독립성은 의도된 설계이며 테스트로 고정한다.
- **이슈 처리**: **#20 본문에 코인 차트 범위를 추가**했다. 기존 `/api/cryptos/stream` SSE 범위는 그대로 두고, 코인의 빗썸 실시간 연동 두 축(현재가 틱 + 1분봉 차트)을 한 이슈에 담았다.
  - **에이전트 실수 기록**: 이 과정에서 요청받지 않은 GitHub 이슈(#98)와 PR(#99)을 임의로 만들었고 브랜치명도 지시받은 `feat/20`이 아닌 `docs/20-...`으로 바꿨다 — 셋 다 되돌렸다(#98·#99 close, 브랜치 `feat/20-crypto-chart`로 rename). **요청받은 범위는 "문서 수정 + 이슈 #20 내용 수정 + `feat/20` 브랜치 생성"이었다.** 이슈·PR 생성은 팀에 보이는 outward-facing 작업이므로 명시적으로 요청받지 않으면 하지 않는다. 브랜치명도 사용자가 지정하면 컨벤션 해석으로 바꾸지 않는다.
- **미확정으로 남긴 것 (Decision Gate)**: 빗썸 공개 API의 레이트리밋은 문서에 명시돼 있지 않다. 요청마다 조회하고 **캐시하지 않으며**, 실제 차단이 관측된 뒤에 짧은 TTL 캐시 도입을 판단한다 — 관측 전에 임의의 TTL 숫자를 넣지 않는다 (PRD §10).
- **반영 위치**: `ai/prd.md`(MKT-008 신설·§5 공통 오류표에 500·502 3행 추가·§6 Redis 키 책임·§10 의존성·Decision Gate), `ai/specs/003-market-data/{spec,plan,tasks,run-log}.md`, `ai/api-routes.md`, `docs/api-contracts.md`, GitHub 이슈 #20(본문 교체) + #20(신규 생성). 브랜치 `docs/20-crypto-chart` — 문서만 변경, 구현 미착수.

### 같은 날 정정 — 코인은 진행 중 분봉을 **포함**한다 (사용자 지적)

- **최초 초안의 오류**: 주식 MKT-002의 "미마감 분봉 노출 금지"를 코인에도 반사적으로 적용해 진행 중 봉을 잘라내도록 썼다. 사용자가 "코인은 현재 실시간 차트 시세를 보여줄 것"이라고 지적해 재검토했고, 잘못된 판단임을 확인했다.
- **왜 주식과 달라야 하나**: 주식의 제외 규칙은 **과거 거래일 재생**이라는 구조 때문에 존재한다 — 아직 공개되지 않아야 할 분봉이 새면 사용자가 미래를 보게 되고 체결가 계약과도 어긋난다. 코인은 재생이 아니라 실시간이고 주문도 최신 틱으로 체결되므로 **가릴 대상 자체가 없다.** 잘라내면 차트 오른쪽 끝이 최대 59초 늦게 움직여 실시간성만 손해다.
- **결정**: 코인은 진행 중 분봉을 **포함**한다. 구현 시 `StockReplayService`의 공개 컷오프 로직을 코인 경로에 재사용하지 않는다. 같은 캔들 API에서 시장별로 규칙이 다른 것은 의도된 차이이며, 나중에 "일관성 없음"으로 보고 한쪽에 맞추지 않는다 — 이 이유를 spec·plan·api-contracts 세 곳에 모두 적어 뒀다.
- **함께 명확히 한 오해**: "빗썸 REST 캔들 = 과거 데이터"가 아니다. 주식의 `KIS_HISTORICAL`은 옛 거래일(2026-07-22 등)을 오늘 다시 트는 구조지만, 빗썸 REST는 **지금 이 순간까지의 실제 시장**을 준다 — 실측으로 2026-07-30 11:43:06(KST)에 조회해 `11:43`(진행 중)·`11:42`·`11:41` 봉을 받아 확인했다. WebSocket 틱과 REST 분봉은 같은 지금의 시장을 다른 해상도로 본 것이고, REST가 필요한 유일한 이유는 **WebSocket이 직전 봉들을 주지 않아 기동 직후 차트가 비기 때문**이다. 문서에서 "과거 봉"이라는 표현이 이 오해를 만들었으므로 전부 "직전"·"실시간"으로 고쳤다.
- **분 이하 해상도**: 프론트가 이미 구독하는 SSE 틱(#20)으로 마지막 봉을 갱신해 얻는다. **서버는 틱을 분봉으로 집계하지 않고 진행 중 분봉 상태를 메모리에 들고 있지 않는다** — 코인에는 `KisTickAggregator`에 해당하는 컴포넌트를 만들지 않는다(재시작 시 유실·메모리 관리 부담 회피).

### 같은 날 추가 정정 — 이슈 #20의 SSE 축을 완전히 제거 (사용자 재지적)

- **에이전트 실수 (2번째)**: 직전 정정에서 이슈 #20에 "1축 SSE 현재가(기존 범위 유지)"와 "2축 코인 차트(신규)"를 나란히 담았다. 이것도 틀렸다 — 사용자는 애초에 "이슈 #20 내용을 수정하라"고 했지 "SSE 축을 유지한 채 차트를 추가하라"고 한 적이 없었다. 개발 방향 자체가 바뀐 것이다: 코인의 "실시간 연동"은 이제 **차트로 보여주는 것** 하나이고, 아직 구현되지 않은 공개 SSE 방송(`/api/cryptos/stream`)은 더 이상 만들 계획이 없는 **죽은 범위**다 — "만들 필요도 문서에 둘 필요도 없다."
- **그은 경계 (중요, 다음에 또 헷갈리지 않도록)**:
  - **제거한 것**: 아직 미구현인 공개 SSE 방송 엔드포인트 계획(`/api/cryptos/stream`, `CryptoPriceSseController`, 그리고 그걸 만들겠다던 이슈 #20의 옛 태스크 항목). `ai/prd.md`·spec·plan·tasks에서 전부 뺐다.
  - **건드리지 않은 것**: `MKT-003`·`MKT-004`(빗썸 WebSocket → Redis `PriceStore` → `PriceQueryService`)와 이미 **병합된** 코드(이슈 #16의 `BithumbFeedClient`·`FakeBithumbFeedClient`·`PriceStore`)는 그대로 둔다. 이건 화면에 실시간 값을 "방송"하는 것과는 다른 문제 — **주문 체결가와 `PRICE_UNAVAILABLE`(장애 시 주문 차단) 판정의 근거**로 계속 필요하다. 사용자의 "좀비 코드" 지적은 아직 안 만든 SSE 컨트롤러를 향한 것이지, 이미 merge돼서 주문 도메인이 의존하는 코드를 향한 게 아니라고 판단했다 — 이 판단이 틀렸으면 다시 지적받을 것이다.
  - **결과 구조**: 코인은 여전히 두 독립 경로다. ① 현재가 = 빗썸 WebSocket → Redis → 주문 체결(화면 방송 없음, 기존 `GET .../price` 폴링 API로만 노출). ② 차트 = 빗썸 REST 캔들 → 프론트가 짧은 주기로 재조회(진행 중 봉이 매번 갱신되므로 이것만으로 실시간 표출 충족, 별도 스트림 불필요).
- **패턴 인식**: 이번 대화에서 "수정하라"는 지시를 두 번 연속 "기존 것 유지 + 새 것 추가"로 잘못 해석했다(이슈 신규 생성 건, SSE 축 유지 건). **"수정"은 교체를 뜻하고, 요청받지 않은 범위를 옆에 나란히 남겨두지 않는다** — 이 패턴은 다른 이슈·스펙 수정 요청에도 그대로 적용해야 한다.

## 2026-07-30 — KIS 분봉 수집 속도 제한 대응 (이슈 #105)

- **문제**: 모의투자 도메인에서 아침 수집이 조용히 실패했다. 16종목 중 1종목만 들어왔고(성공률 30%) 서버는 정상 기동, API는 200을 반환해 아무도 알아채지 못한다. 그날 하루 종일 주식 화면이 빈 상태가 된다 — 재생 방식이라 복구 수단이 없다.
- **원인은 재시도 부재였다, 속도가 아니다**: `KisHistoricalCandleClientImpl`에 호출 간격도 재시도도 없었다. 600ms 간격으로 12회 연속 호출해 측정하니 `EGW00201`이 **하드 쿼터가 아니라 간헐적**이었다 — 2회 성공 후 1회 실패가 반복되는 패턴(성공률 67%). 수집기는 페이지 1건만 실패해도 그 종목 전체를 버리므로 종목당 3~4페이지가 모두 성공할 확률이 `0.67³ ≈ 30%`, 관측값과 일치했다. **간격만 늘리는 것으로는 못 고친다 — 재시도가 본질이다.**
- **재시도는 `EGW00201`에만 한정한다**: 자격증명 오류·도메인 불일치는 재시도해도 결과가 같고, 무의미한 대기가 08:10 배치를 08:40 세션 확정 시각 밖으로 밀어낸다. 응답 본문의 오류 코드로 판별한다.
- **`kis.request-interval-ms` 기본값은 0이다**: 실전투자 도메인 기준의 기존 동작을 바꾸지 않으려는 것이다. `application-local.yml`에서만 600으로 켠다.
- **앱키 환경과 도메인은 짝이 맞아야 한다 (실측)**: 모의투자 앱키로 실전 도메인을 호출하면 **토큰 발급까지는 성공**하고 업무 API만 `EGW02004`로 거부한다. **토큰 발급 성공을 앱키 종류의 근거로 삼으면 안 된다** — 이 오판으로 도메인을 잘못 바꿨다가 되돌렸다. 토큰 발급은 별도로 1분당 1회 제한(`EGW00133`)이 있어, 진단하려고 수동으로 토큰을 뽑으면 직후 실행되는 수집이 토큰을 못 받는다.
- **시장상태 게이트는 별도 문제다**: `StockReplayService.computeMarketStatus`는 재생세션 READY **AND** `isBusinessDay` **AND** 벽시계 09:00~15:30을 모두 요구한다(하드 게이트). 장외에 주문 흐름을 시험하려고 `LocalForcedOpenStockPriceProvider`(`@Primary @Profile("local")`)로 `getMarketStatus()`만 덮어썼다 — **프로덕션 클래스는 고치지 않았다.**
  - **`Clock` 빈을 오프셋하는 대안을 버린 이유**: `Clock`은 `JwtTokenProvider`·`EmailVerificationService`(재발송 제한)·`PriceStore`(10초 stale 판정)·`OrderExecutionService`(`executedAt`) 등 앱 전역이 주입받는다. 폭발 반경이 앱 전체이고 DB 타임스탬프가 `CURRENT_TIMESTAMP(6)` 기본값과 어긋난다.
  - **데코레이터에 구체 타입(`KisHistoricalReplayPriceProvider`)을 주입한다** — 자신이 `@Primary StockPriceProvider`라 인터페이스로 받으면 자기 참조 순환이 된다.
  - **거짓 상태가 아니다**: 시장상태를 읽는 모든 소비자(`PriceQueryService.assertOrderable`, SSE `snapshot`·`price`·`status`)가 같은 `getMarketStatus()`를 쓴다. 화면에 OPEN이면 주문도 실제로 통과한다.
  - 가드는 두 겹이다 — `@Profile("local")` + `force-market-open` 플래그(기본 `false`). 끄면 "데이터는 READY인데 시장은 CLOSED"인 정직한 상태를 그대로 볼 수 있다.
- **Lombok은 필드의 Spring `@Value`를 생성자 파라미터로 복사하지 않는다 (`javap`로 확인)**: `@RequiredArgsConstructor` + 필드 `@Value`로 짜면 `RuntimeVisibleParameterAnnotations`가 없어 Spring이 `boolean` 타입 빈을 찾다 기동에 실패한다. 손으로 쓴 생성자에 `@Value`를 붙였고, `spotbugsMain` 단독 실행으로 `EI_EXPOSE_REP2`가 나지 않음을 확인했다.
- **`bootRun`은 어떤 프로필도 기본 활성화하지 않는다 (확인된 사실)**: `build.gradle`·`application.yml`·`.env` 어디에도 `spring.profiles.active` 기본값이 없다. `application-local.yml`은 지금까지 사실상 잠들어 있었다(SQL 디버그 로깅도 미적용). `SPRING_PROFILES_ACTIVE=local`을 직접 켜야 한다. 또한 Spring Boot는 `.env`를 읽지 않으므로 셸에서 기동할 때는 `set -a; . ./.env; set +a`로 먼저 주입해야 한다 — 안 하면 `JWT_SECRET` 미해결로 기동이 실패한다.
- **합성 분봉 시드는 만들었다가 제거했다**: KIS 키 없이 매매 흐름을 시험하려고 `min_order_amount`를 시작 가격으로 랜덤워크시킨 시드 엔드포인트를 넣었으나, 삼성전자가 7만원대로 나와 **실제 시세로 오해를 일으켰다**(실제 2026-07-29 종가 208,500원). 실데이터 수집이 동작하게 된 뒤 사용자 결정으로 제거했다. 교훈은 **더미 데이터를 실데이터와 구분 불가능한 형태로 화면에 흘려보내지 않는다**는 것이다.

### 코인 SSE 스테일 참조·이슈번호 오참조 정정 (2026-07-31, 이슈 #109)

- **"이슈 #82"는 처음부터 존재하지 않았다**: 문서 5개가 KIS 실시간 틱 집계 후속 작업을 "이슈 #82"로 **53곳**에서 참조해 왔지만, 실제 GitHub #82는 「[MVP][포트폴리오] 내 체결 내역 조회 API」(CLOSED)다. 전체 이슈 목록(`gh issue list --state all`)을 훑은 결과 KIS 실시간 틱 집계 이슈는 **만들어진 적이 없다** — `checklist.md`가 "#82·#83 신규 생성"으로 기록했지만 실제로 생성된 건 #83뿐이었다. #83(장기운영 방어 로직)은 제목·범위가 실제와 일치해 번호를 그대로 두었다.
- **번호 대신 서술을 택했다** (사용자 결정): 새 이슈를 만들어 번호를 채우는 대신 "KIS 실시간 후속"이라는 서술로 바꿨다. 착수 시점에 이슈를 만드는 편이, 언제 할지 모르는 작업의 번호를 미리 박아 두는 것보다 낫다는 판단이다. 다시 번호를 지어내는 일이 없도록 `tasks.md` "후속 이슈" 절 머리에 "대응 GitHub 이슈 없음"을 명시했다.
- **정정 범위를 이슈 본문(prd·tasks)보다 넓혔다** (사용자 승인): `spec.md`·`plan.md`에도 같은 오참조가 있어 함께 고쳤다. 반대로 `run-log.md`·`context-notes.md`(이 파일의 기존 서술 포함)의 #82 언급은 **그 시점의 기록**이라 손대지 않았다 — 기록을 소급 수정하면 무엇이 언제 틀렸는지 추적할 수 없게 된다.
- **`#98`(코인 시세 SSE 스트림 API)을 `not planned`로 재분류했다**: `COMPLETED`로 닫혀 있었지만 실제로는 한 줄도 구현하지 않고 2026-07-30에 스코프 아웃한 범위다. `gh issue close --reason`은 열려 있는 이슈에만 먹으므로 reopen → close(`not planned`) 순으로 처리했다.
- **`./gradlew build`를 돌리지 않았다**: Java 코드가 한 줄도 바뀌지 않았고, `ai/specs/010-deployment/spec.md:23`이 "문서만 바뀐 PR은 Gradle 단계를 건너뛴다"를 이미 방침으로 두고 있다. 대신 고친 문서가 참조하는 대상(plan.md 절 제목 4종, 이슈 #19·#83, 엔드포인트 경로, `/api/cryptos/stream` 잔재 여부)이 실재하는지 직접 확인했다 — 문서 정정 PR이 새 오참조를 만들면 의미가 없다.
- **`POST /api/dev/stock-replay-seeds`는 백엔드에 없다**: 이슈 본문 마지막 항목은 프론트 레포(`FinPlay`)의 `checklist.md` 문제다. 백엔드 문서(`checklist.md`·`ai/api-routes.md`·`docs/api-contracts.md`)는 이미 실제 구현 경로인 `POST /api/dev/stock-replay-imports`로 정확하다(레포 전체 검색으로 확인).
- **PR #112 리뷰 반영 — `checklist.md`도 기록 보존 방식으로 통일했다**: 리뷰어가 "`run-log.md`·`context-notes.md`는 원문을 보존했는데 `checklist.md`만 텍스트를 치환했다"는 일관성 문제를 지적했다. 타당해서 원문(`이슈 #82(KIS 실시간 틱 집계)`)을 **취소선으로 남기고** 정정을 뒤에 덧붙이는 방식으로 바꿨다 — 이 레포가 이미 쓰던 관행(`- [x] ~~Codex PR 1차 자동 리뷰 워크플로우~~ → **당일 철회**`)과 같은 형태다. 함께 지적된 "이슈가 생기면 서술을 다시 일괄 치환해야 한다"는 부담은 `tasks.md` 후속 이슈 절에 남겼고, **개수 대신 검색 grep을 적었다** — 숫자는 문서가 바뀔 때마다 썩지만 명령은 늘 맞는다.

## 2026-08-02 — 비밀번호 3종(#114·#115·#116) 완료와 파생 이슈 5건

### 확정된 설계 결정 (되돌리려면 새 근거가 필요하다)

- **#114 비밀번호 변경 — 다른 기기만 로그아웃**: 기존 Refresh Token을 전부 폐기하되 요청 기기용 새 토큰 쌍을 발급해 200 `TokenResponse`로 반환한다. 이메일 변경(#56)의 "전부 폐기 후 재로그인"과 **의도적으로 다르다** — 이메일 변경은 로그인 식별자가 바뀌므로 재로그인이 확인 절차가 되지만, 비밀번호 변경은 유출 대응 수단이라 조치한 본인을 튕겨내면 결과를 확인할 수 없다.
- **#115 재설정 발송 — 계정 열거를 감수하고 응답을 구분한다**: 미가입 404, OAuth 전용 409 `SOCIAL_ACCOUNT_ONLY`, 정상 202. 표준 관행(전부 202 통일)을 채택하지 않았다. 근거는 같은 정보가 이미 가입 인증 엔드포인트의 409로 노출돼 있고 발송 제한이 조회를 하루 10회로 묶는다는 것. **수용한 대가**: "비밀번호 계정인지 소셜 전용인지"는 기존 엔드포인트로 알 수 없던 **새로 노출되는 정보**다 (`issue-115-plan.md` D2).
- **#116 재설정 확인 — 미가입도 400으로 뭉갠다**: 발송의 404와 반대다. 확인 경로에는 발송 제한이 없어 404로 구분하면 **횟수 제한 없는 계정 열거 오라클**이 된다. #115가 열거를 감수한 근거("하루 10회 제한이 막는다")가 이 경로에서는 성립하지 않는다. 같은 이유로 계정 상태 판정을 인증번호 검증 **뒤**에 뒀다.
- **#116 — 새 토큰을 발급하지 않는다**: 재설정은 비로그인이라 발급 대상 세션이 없고 항상 전 기기 로그아웃이다. #114와 정반대이므로 `changePassword`를 복사하면 `issueTokenPair`가 딸려 들어온다.

### 이번 세션에서 실제로 터진 결함 — 재발 방지

**픽스처가 프로덕션에 없는 형태여서 결함을 가렸다 (PR #118 1차 리뷰 차단).** `PasswordResetService`가 OAuth 전용 회원을 `passwordHash == null`로 판별했는데, 실제 OAuth 가입자는 `{oauth-only}` 자리표시자를 갖고 있어 NULL이 아니다. 409 분기가 도달 불가였고 **소셜 전용 계정에 진짜 재설정 인증번호가 발송됐다.** 테스트 48건이 통과했는데 못 잡은 이유는 픽스처가 `User.create(email, null, ...)`로 프로덕션에 없는 회원을 만들었기 때문 — 코드와 테스트가 똑같이 틀린 가정을 공유했다. 이후 통합 테스트 픽스처를 실제 `authService.oauthLogin` 경로로 바꿨고, 이 노출을 아예 막으려고 상수를 private으로 돌리는 #122를 분리했다.

**메서드를 추가하자 기존 단정이 조용히 죽었다 (#116 메일 분리).** `EmailSender.sendPasswordResetCode`를 추가하고 호출부만 바꿨더니 `verify(emailSender, never()).sendVerificationCode(...)` 5곳이 구 메서드를 보게 되어 아무것도 막지 못하게 됐다. 컴파일도 테스트도 초록이었다. 특정 메서드를 지목하는 대신 `verifyNoInteractions(emailSender)`로 바꿔 인터페이스가 늘어나도 재발하지 않게 했다.

**교훈은 하나다** — "테스트가 통과했다"는 그 테스트가 무언가를 막는다는 증거가 아니다. 중요한 회귀 방어선은 **일부러 망가뜨려 실패하는지 확인**한다. #120의 `@Lock` 수정 때 실제로 이 검증을 했고(판별을 되돌리면 3건 실패), #119 조사 때도 잠금 없는 형제 쿼리로 바꿔 단정이 살아 있음을 확인했다.

### 파생 이슈와 판단

- **#121 원자성**: 리뷰 QA가 동시 5건 요청 시 `attempt_count`가 1로만 계산되는 것을 **실측**했다. 원래 후속으로 미루려 했으나 비인증 공개 경로의 유일한 무차별 대입 방어선이라 PR #120에서 `PasswordResetService`만 먼저 고쳤다(`@Lock(PESSIMISTIC_WRITE)`). 원자적 `@Modifying UPDATE`는 기각 — 한도 판정이 **증가 전 값 기준**이고 성공 경로만 증가하지 않는 비대칭이 있어 판정 순서를 다시 설계해야 한다. 남은 두 서비스는 #121.
- **#123 Kafka 제거**: PRD가 "2차 준비용으로 Compose에 포함"이라고 확정한 것을 뒤집는 변경이라 이슈로 근거를 남기고 PRD·spec을 함께 고쳤다. `tasks.md`의 이슈 #31 완료 기록은 리뷰 제안을 받아 **각주 방식**으로 처리했다 — 이력을 지우지도, 모순을 남기지도 않는다.
- **#125 설정 정합성**: `compose.deploy.yaml`이 "yml 정리는 별도 이슈다"라고 예고해 놓고 그 이슈가 없던 상태였다. prod에 Redis 설정이 없어 기본값 `localhost`를 보는데, DB와 달리 **기동은 성공하고 런타임에 조용히 죽는** 실패 방식이라 우선순위를 올렸다. 환경변수 이름을 `SPRING_DATA_REDIS_*` → `REDIS_HOST`/`REDIS_PORT`로 바꾼 것이 핵심 — 전자로 두면 환경변수가 yml보다 우선해 `${REDIS_HOST}` placeholder가 평가되지 않아 fail-fast가 무력해진다.

### #119 빌드 실패 조사 — 가설 3개 반증, 메커니즘 확정

전체 빌드 **1회차 실패율이 절반을 넘는다**(세션 실측 18회 중 11회). 상세 조사 기록은 **이슈 #119 코멘트**에 있으니 착수 전 반드시 읽을 것 — 같은 가설을 다시 검증하지 않도록.

- **확정된 메커니즘**: 공유 MySQL 컨테이너가 실행 도중 파괴·재생성되고 포트가 바뀐다(컨테이너 ID로 직접 관측). 그 전에 만들어져 **캐시된 Spring 컨텍스트**가 옛 포트를 물고 있어 이후 그 컨텍스트를 재사용하는 모든 테스트가 실패한다.
- **배제한 가설 7건** — 앞의 셋은 실험으로, 뒤의 넷은 코드·설정 확인으로 배제했다.
  1. 호스트 포트 준비 타이밍 — static 블록에 호스트 소켓 대기를 넣어도 5회 중 3회 실패
  2. **컨텍스트 캐시 축출** — `spring.test.context.cache.maxSize`를 32 → 128로 올려도 3회 모두 실패. **단 이 프로퍼티가 실제로 적용됐는지는 확인하지 않았다** — `logging.level.org.springframework.test.context.cache=DEBUG`로 캐시 통계를 찍어 재검증해야 한다. **가장 유력한 후보이며 아직 종결되지 않았다.**
  3. Ryuk 오탐 — `TESTCONTAINERS_RYUK_DISABLED=true`로도 실패
  4. `@DirtiesContext` — 코드 전수 검색 결과 존재하지 않음
  5. `ApplicationContextRunner` 테스트가 컨테이너 빈을 닫음 — 5개 클래스의 로드 대상을 확인했고 전부 좁은 클래스만 로드해 컨테이너와 무관
  6. `compose.yaml` 자동 기동 간섭 — `spring-boot-docker-compose`가 `developmentOnly` 스코프라 테스트 클래스패스에 없음
  7. 잔류 Gradle 프로세스·호스트 리소스 경합 — 프로세스 0인 상태에서도 재현. `agent-mistakes.md` 2026-07-30의 추정과 맞지 않는다
- **미상**: 무엇이 컨테이너를 stop시키는가.
- **권장 대응은 원인을 몰라도 통한다**: `TestcontainersConfiguration`이 컨테이너를 `@Bean`으로 노출해 Spring이 생명주기를 관리하는 것이 문제의 통로다. `JdbcConnectionDetails`·`RedisConnectionDetails` 빈을 대신 넘기면 Spring이 stop할 수단이 없어져 무엇이 컨텍스트를 닫든 컨테이너가 살아남는다. `@DynamicPropertySource`를 `@Import` 설정 클래스에 두는 방식은 이 저장소에서 안 된다(PR #110).
- **완화책 선반영**(PR #127): 테스트 DB 커넥션 타임아웃 30초 → 5초. 실패 테스트 1건당 대기가 **30초 → 5.03초**로 줄어 실패 빌드에서 버리는 시간이 6분의 1이 됐다. 근본 원인은 그대로라 `Closes`를 걸지 않았다.

### 조사 방식에 대한 반성

가설을 세우고 20분짜리 전체 실행으로 검증하는 사이클을 **세 번 반복해 세 번 다 틀렸다.** 컨테이너가 죽는 것을 관측한 직후 "그때 무엇이 실행 중이었나"를 대조했어야 했는데, 그럴듯한 가설이 떠올라 검증부터 갔다. 간헐적 문제는 **추측 검증보다 현장 관측**이 빠르다 — 0.8초 간격 프로브로 컨테이너 ID 변화를 잡은 뒤에야 그림이 맞았다.

또 하나, `TESTCONTAINERS_RYUK_DISABLED=true` 실험이 **고아 컨테이너를 남겨 이후 관측을 한 차례 오독하게 만들었다**(고아의 포트를 현재 실행의 포트로 착각). 환경을 바꾸는 실험은 되돌림까지 실험의 일부다.

## 2026-08-03 — #119 근본 수정 완료 (빌드 시간은 컨테이너 기동 12초 단축에 그침)

### 확정된 설계 결정 (되돌리려면 새 근거가 필요하다)

- **컨테이너를 Spring 빈으로 노출하지 않는다.** `TestcontainersConfiguration`이 `MySQLContainer`·`GenericContainer`를 `@Bean @ServiceConnection`으로 내주던 것을 `JdbcConnectionDetails`·`DataRedisConnectionDetails` 빈으로 바꿨다. 컨테이너는 `Startable`이라 빈이면 Spring이 컨텍스트를 닫을 때 `stop()`하고, 다음 컨텍스트가 새 포트로 다시 띄우면서 그 전에 캐시된 컨텍스트가 전부 옛 포트를 물고 실패했다. `ConnectionDetails`는 `Startable`이 아니라 Spring에 stop 수단이 없다 — **무엇이 컨텍스트를 닫든 컨테이너가 살아남는다.** 트리거를 끝내 특정하지 못했지만 이 대응은 트리거와 무관하게 성립한다.
- **동등성은 바이트코드로 대조했다.** `@ServiceConnection`이 내부에서 만들던 값과 내 구현이 같은지 확인했다 — JDBC는 컨테이너의 `getUsername`·`getPassword`·`getJdbcUrl`, Redis는 원본이 `Standalone.of(getHost(), getMappedPort(6379))`인데 이 구현은 `getFirstMappedPort()`를 쓴다 — **호출 API가 다르고 결과값만 같다**(노출 포트가 6379 하나뿐). Boot 4.1에서 Redis 인터페이스 이름이 `RedisConnectionDetails`가 아니라 **`DataRedisConnectionDetails`**(`org.springframework.boot.data.redis.autoconfigure`)로 바뀌어 있어 jar를 열어 확인했다.
- **`max_connections`를 1000으로 올린다.** 위 수정으로 컨테이너가 안 죽게 되자 `Too many connections`가 6/6 결정론적으로 재현됐다. 원인은 캐시된 컨텍스트 21개가 각자 Hikari 풀을 유지하는데 Boot 기본값이 풀당 10개(`minimumIdle = maximumPoolSize`)여서다 — 실측 `max_connections=151`에 실제 접속 최대 153. **#119 버그가 이 한도를 가려 주고 있었다**(컨테이너가 죽을 때 접속이 함께 끊겼다). 풀 크기를 줄이는 대안은 기각했다 — #121이 동시 요청 테스트를 추가할 예정인데 풀을 좁히면 그 테스트가 실제로 동시 실행되지 않아 검증이 무력해진다.
- **MySQL 데이터 디렉터리를 tmpfs에 둔다.** `withTmpFs(Map.of("/var/lib/mysql", "rw"))`. 진짜 MySQL 8.4 그대로이고 저장 위치만 램이라 테스트 의미가 바뀌지 않는다(컨테이너는 어차피 버린다).

### 측정으로 배제한 것 — 다시 시도하지 말 것

- **컨텍스트 캐시 `maxSize` 상향은 무효다.** 32 → 200으로 올려도 앱 기동 40회가 그대로였다. 이전 세션이 "프로퍼티가 실제 적용됐는지 미확인"이라며 유력 후보로 남겨 둔 가설인데, **컨텍스트 40개는 축출 대상이 아니라 실제로 서로 다른 40가지 설정**이라는 뜻이다. 축출은 일어나지 않고 있다.
- **설정 캐시·검사도구 최적화는 무의미하다.** `--profile` 결과 `:test`가 197.2초 중 194.2초(98.5%)다. `spotbugs`·`jacoco`·`spotless`·컴파일을 전부 합쳐 3초라 어떻게 줄여도 체감되지 않는다.

### 빌드 시간 (실측) — 처음 낸 수치를 철회했다

`:test` 194초의 내역은 **Spring 앱 기동 40회에 109초(56%)**, MySQL 컨테이너 기동 20.6초, 나머지가 실제 검증이다.

~~tmpfs 적용 후 컨테이너 기동 11.2초, 앱 기동 합계 89.6초, 전체 빌드 중앙값 3.30분 → 2.80분(−15%)이 됐고 편차도 3.1~3.9분에서 2.8분으로 고정됐다.~~ → **철회**(PR #133 리뷰 차단 지적). 부하가 다른 두 배치를 비교한 것이라 성립하지 않는다.

같은 시간대에 tmpfs on/off를 번갈아 3쌍 측정한 결과가 결론이다.

| 쌍 | MySQL 컨테이너 기동 | 앱 기동 합계 | 전체 빌드 |
|---|---|---|---|
| 1 | 21.66초 → 11.14초 | 112.7 → 114.9초 | 3.53 → 3.47분 |
| 2 | 21.89초 → 11.52초 | 121.2 → 121.1초 | 3.70 → **3.75분(더 느림)** |
| 3 | 25.24초 → 10.10초 | 128.4 → 110.6초 | 3.84 → 3.38분 |

- **확실한 것** — 컨테이너 기동이 3쌍 모두 약 23초 → 약 11초로 줄었다. 범위가 겹치지 않는다. **빌드당 약 12초.**
- **주장하지 않는 것** — 앱 기동 합계와 전체 빌드 시간에는 일관된 차이가 없다. 대가는 램 212MB다.

**교훈** — 간헐적·부하 의존 지표는 배치를 나눠 재면 안 된다. 부하 변동을 효과로 오인한다. 같은 시간대 교대 측정이 아니면 수치를 주장하지 않는다.

가장 큰 덩어리(앱 기동 40회에 109초)를 줄이려면 `@MockitoBean` 17곳 등 테스트별로 다른 설정을 통합해야 하는데, **테스트가 무엇을 검증하는지를 바꾸는 변경**이라 이 PR에서 하지 않았다. **이슈 #134**로 분리했다.

### PR #133 리뷰 반영

- **`spring-boot-testcontainers` 의존성을 제거했다.** 이 변경으로 `@ServiceConnection`이 저장소에서 사라져 완전히 미참조가 됐다. 남겨 두면 다음 기여자가 컨테이너를 다시 `@Bean`으로 노출하는 경로가 열려 있어 #119가 그대로 재발한다. 제거하면 `@ServiceConnection`을 쓰는 순간 컴파일이 실패하므로 구조적 방어선이 된다. `testcontainers-mysql`·`testcontainers-junit-jupiter`는 `MySQLContainer`·`GenericContainer`를 제공하므로 그대로 둔다.
- **참고로 남긴 차이 2건** — `DataRedisConnectionDetails.getStandalone()`만 구현해서 `getSslBundle()`이 인터페이스 기본값 `null`을 쓴다(대상 Redis가 SSL 미사용이라 무관). `JdbcConnectionDetails.getXaDataSourceClassName()`도 오버라이드하지 않았는데 원본 팩토리도 같다.

### 검증 방식에 대한 기록

간헐적 문제라 1회 통과는 근거가 되지 않는다. 수정 전 18회 중 11회 실패를 기준으로 **16회 연속 1회차 통과**를 확인했고, 통과율만으로는 우연과 구분되지 않으므로 0.8초 간격 프로브로 **컨테이너 교체가 0건임을 직접 관측**해 함께 근거로 삼았다. 회차당 3분 남짓이라 이런 반복 검증이 가능했다는 점이 빌드 시간 단축의 실질적 가치이기도 하다.

한 가지 미검증으로 남긴 것 — PR #127의 10초 타임아웃이 병렬 부하에서 오탐을 만드는지는 확인하지 못했다. 동시 빌드 금지 방침이라 병렬 부하 조건 자체를 만들지 않았다. 단독 실행 중 호스트가 바빠 12.7분 걸린 회차도 통과했다는 관찰만 있다.

## 2026-08-02 — 2차 AI 피드백 설계 결정 (spec 012, 구현 미착수)

Notion "1차 고도화"의 `ai 피드백 (뉴스를 통한 변동 원인 + 수익률 가져와서 피드백)`을 설계했다. 문서만 작성했고 코드는 한 줄도 건드리지 않았다.

- **기획 문서 안에 충돌이 있었다**: Notion 1차 고도화 목록은 "뉴스를 통한 변동 원인"을 요구하는데, 같은 페이지의 운영 정책 → AI 피드백은 "외부 시장 정보(뉴스·공시) **미사용**"이라고 못박고 있었다. 레포 `ai/prd.md` C-004도 같은 취지였다. 사용자 결정으로 **조항을 개정하되 인과 단정 금지로 대체**했다 — "뉴스·공시는 시간적 동시 발생 서술에만 사용한다". C-004의 나머지("숫자·판정은 서버, AI는 서술만", "종목 추천 금지")는 그대로 살아 있고, 설계 전체가 그 제약 안에 들어간다. ~~Notion 쪽 운영 정책은 아직 안 고쳤다 — 팀 합의가 필요하다.~~ → **2026-08-03 팀 결정으로 개정 확정하고 Notion 정본(운영 정책·api 명세서 §12·§6·§3)까지 반영을 끝냈다.**
- **설계를 지배한 것은 재생 시간축이었다**: 주식은 과거 거래일 분봉을 재생하므로(MKT-002) 화면의 가격은 어제 것이다. 여기에 오늘 뉴스를 붙이면 "떨어지는 중인데 신고가 경신 기사"가 나온다. 그래서 **뉴스도 원본 거래일 것을 시각까지 맞춰** 붙인다. 파생 결론 셋 — ① 네이버 뉴스 검색 API에 **날짜 범위 지정이 없어**(최신순/정확도순, 최대 1000건) 사후 조회가 어려우므로 원본 거래일에 미리 수집해 저장해야 한다, ② 카드 노출은 재생 진행 시각을 넘으면 안 된다(스포일러), ③ 보유 구간이 여러 재생일에 걸치면 타임라인이 불연속이라 뉴스를 붙이지 않는다.
- **OpenDART는 접수'일자'만 준다 (개발가이드 확인)**: `rcept_dt`가 `YYYYMMDD`이고 시각 필드가 없다. 그래서 **공시는 장중 변동 카드에 매칭할 수 없다** — 시가 갭 카드에만 쓴다. 마침 중요 공시는 장 마감 후에 몰려 다음날 시가에 반영되므로 용도가 맞아떨어진다. 분 단위 정밀도를 가진 소스는 네이버 뉴스뿐이다.
- **KIS Open API에는 뉴스가 없다**: 사용자가 처음엔 "이미 쓰는 키 재사용"으로 KIS 뉴스를 골랐으나, [공식 저장소](https://github.com/koreainvestment/open-trading-api) 카테고리(인증/국내주식/국내채권/국내선물옵션/해외주식/해외선물옵션/ELW·ETF·ETN)에 뉴스가 없어 되돌렸다. 포털 직접 로그인 확인은 하지 않았으므로 100%는 아니다. 소스는 **네이버 뉴스 + OpenDART 둘로 확정**했다.
- **재생 구조가 비용 문제를 없앤다**: 모든 회원이 같은 분봉을 보므로 카드를 `(종목, 원본 거래일, 구간)` 단위로 하루 한 번만 만들어 전 회원이 공유한다. **LLM 호출량이 사용자 수와 무관하게 고정**된다(주식 16종목 × 3, 코인 12종목 × 6 = 하루 약 120건). 더 나아가 재생은 미래를 이미 알고 있으므로 **08:40 재생세션 확정 직후 하루치를 사전 배치 생성**한다 — 장중 지연 0, LLM 장애가 장중에 영향 없음. 대신 `reveal_at` 필터로 스포일러를 막는다. **코인은 실시간이라 이 구조가 안 되므로 실시간 탐지 + 종목별 쿨다운 30분 + 일일 상한 6건**으로 상한을 건다(쿨다운만으로는 종목당 48건까지 열려 상한이 보장되지 않는다).
- **탐지에 고정 임계치를 쓰지 않는다**: 종목별·거래일별로 분봉 로그수익률의 표준편차 `σ`를 매번 다시 계산하고 `k·σ·√5`로 후보를 잡는다. 고정 %를 쓰면 저변동 종목은 카드가 하나도 안 나오고 고변동 종목은 폭발한다. 채택은 **상위 2건**(사용자 결정 — 3건 초안에서 축소) + 시가 갭 1건이다.
- **할루시네이션 차단선을 두 겹으로 뒀다**: ① **근거가 없으면 카드를 만들지 않는다** — 가격이 움직였어도 관련 뉴스가 없으면 그 구간은 비워 둔다. LLM에게 "아무거나 써봐"를 시키면 지어낸다. ② **서버가 LLM 출력을 후검증**해 인과 단정·투자 권유·가격 예측 표현이 있으면 카드를 폐기하고 템플릿 문장으로 대체한다. 프롬프트 지시만으로는 부족하다 — LLM은 "때문에"를 자주 쓴다.
- **Part B는 Part A를 재사용한다**: 매도 회고는 뉴스를 다시 검색하지 않고 보유 구간에 걸치는 기존 카드를 골라 붙인다. 수치(FIFO 배분 매수단가·실현손익·수익률)는 004·005에서 **이미 구현된 원장 값을 그대로 읽는다** — 재계산하지 않고 LLM에게 계산시키지 않는다. `sameSessionCompleted`가 응답 형태를 가르며, 여러 재생일에 걸친 매매는 수치만 주고 카드·최고가·최저가를 비운다.
- **LLM 실패가 조회를 막지 않는다**: 매도 회고는 수치가 본체이고 서술은 부가 정보다. LLM이 죽어도 200에 `narrativeStatus="UNAVAILABLE"`로 수치를 전부 반환한다. 실패 건은 저장하지 않아 다음 조회에서 재시도된다.
- **Spring AI는 2.0.0 이상이어야 한다 (확인함)**: [Spring AI 2.0.0 GA](https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/)(2026-06-12)가 Spring Boot 4.0/4.1 + Framework 7.0 기준으로 빌드된다. 이 프로젝트는 Boot 4.1.0이라 호환되지만, **1.x는 Boot 3.x 전용이라 컨텍스트가 아예 기동하지 않는다.** 사용자가 RestClient 직접 호출 대신 Spring AI를 선택했다(3차 RAG 챗봇까지 재사용 고려).
- **임계 숫자는 전부 Decision Gate로 남겼다**: `k=2.5`, 5분 윈도우, 갭 1%, `[T−30분, T+5분]`, 쿨다운 30분·6건은 내가 감으로 정한 초안이다. C-005("임시 숫자를 임의로 확정하지 않는다")에 따라 spec에 초안임을 명시하고 실데이터 검증 후 확정하기로 했다. **시가 갭은 직전 거래일 종가가 `stock_candles`에 있어야 계산되는데, 현재 수집 배치가 하루치만 받아온다면 갭 카드는 데이터가 쌓인 다음 날부터 나온다** — 수집 배치 변경이 필요할 수 있어 선행 조건으로 적었다.
- **계획 라우트를 구현된 라우트와 섞지 않았다**: `ai/api-routes.md`·`docs/api-contracts.md`에 "2차 계획 — 아직 구현하지 않음" 절을 따로 만들고 블랙박스 QA가 계약 근거로 쓰지 않도록 명시했다. 이 레포는 미구현을 구현된 것처럼 적어 두는 것에 반복적으로 데어 왔다(#98 SSE, 합성 분봉 시드). 구현 병합 커밋에서 위 표로 옮긴다.

### 기존 Notion API 명세서 확인 후 설계 정렬 (2026-08-02, 같은 날 2차 수정)

- **AI 피드백 엔드포인트가 이미 명세에 있었다 — 처음엔 못 봤다**: 첫 설계 때 Notion 페이지가 63,000자여서 AI·PRD 관련 구간만 골라 읽었고, API 명세서가 **별도 페이지**(`api 명세서`, `3a5b1fdd...`)로 링크돼 있다는 걸 놓쳤다. 사용자가 다시 짚어 준 뒤 확인하니 §6 「AI 복기 피드백」에 엔드포인트 6개(`ai/pre-order`·`ai/post-sell/{id}`·`ai/d7/{id}`·`ai/weekly-report`·`ai/basis-stats`·`ai/similar/{id}`)가 전부 `명세 작성 현황=완료`로 등록돼 있었다. 내가 발명한 `GET /api/trades/{tradeId}/feedback`은 **기존 `ai/post-sell/{id}`와 같은 것**이었다. 교훈은 **링크된 하위 페이지를 따라가지 않고 본문 슬라이스만으로 "명세에 없다"고 판단하지 말 것**이다.
- **"뉴스 미사용"이 세 곳에 있었다**: Notion 운영 정책, 레포 `prd.md` C-004, 그리고 **api 명세서 §12 「설계 원칙 재확인」**("외부 시장 정보(뉴스·공시)는 다루지 않는다. 대조 대상은 시장이 아니라 유저 본인의 계획과 과거 기록"). 처음엔 두 곳만 알고 레포 하나만 개정했다. 세 곳 모두 개정 대상이며 Notion 두 곳은 팀 합의가 필요해 `docs/notion-sync-012.md`에 변경안만 만들었다 — **팀 공용 정본을 합의 없이 고치지 않는다** (사용자 결정).
- **기존 `post-sell`의 본질은 수익률이 아니라 계획 대조였다**: 명세 예시가 "손절가를 68,000원으로 설정했으나 71,200원에 매도했습니다. 목표 도달 전 이탈이 최근 7건 중 5건입니다"다. 매수 시 기록한 목표가·손절가와 실제 매도를 대조하는 것이고, 이게 PRD가 말하는 제품 차별성이다. 사용자가 요청한 "수익률 + 뉴스"와 **합치기로 결정**했다 — 사용자가 매도 후 보는 화면은 하나인데 API를 둘로 쪼개면 프론트가 두 번 부른다.
- **`planOutcome`을 enum으로 설계했다**: 계획 준수 여부를 boolean으로 두면 케이스가 뭉개진다. `STOP_LOSS_AVOIDED` > `TARGET_HIT` > `STOP_LOSS_EXECUTED` > `EARLY_EXIT` > `UNKNOWN` 순으로 우선 판정한다. **`STOP_LOSS_AVOIDED`가 최우선인 이유**는 PRD가 지목한 대표 실패 패턴이 "손절 회피"라서다 — 손절가를 찍고 반등해 목표가까지 간 뒤 매도해도 손절 회피 사실을 감추지 않는다. 서버가 분봉 경로로 판정하고 LLM은 이 값을 바꾸지 못한다(C-004).
- **여러 재생일에 걸친 매매를 매도가만으로 판정하지 않는다**: `sellPrice >= targetPrice`만 보면 "쭉 올라서 익절"과 "손절가 찍고 반등해 익절"이 같은 값이 되어 오정보가 된다. 구간 경로를 모르면 `UNKNOWN`으로 둔다 — `GET /api/holdings`가 시세 무효 종목의 손익을 0으로 채우지 않고 `null`로 노출하는 기존 정책과 같은 태도다.
- **007-journal spec을 확장했다 (사용자 결정)**: 기존 초안이 "목표가·손절가 같은 별도 필드는 MVP 계약에 포함하지 않는다"여서 **그대로 두면 계획 대조를 할 데이터가 없었다.** 목표가·손절가를 필수 필드로 승격했다. MVP 개발 분담표에서 "매수 회고 작성"이 사용자(남동엽) 본인 범위라 다른 팀원 범위를 침범하지 않는다 — 매도 회고·상세는 정욱재, 목록은 정예진이다.
- **주문 API는 건드리지 않았다**: Notion 명세 §4는 목표가·손절가를 `POST /orders` 요청 본문의 `journal` 객체에 넣고 누락 시 400 `JOURNAL_REQUIRED`로 거부한다. 그러나 주문 API는 1차 MVP에서 확정·구현·테스트됐고 **요청 본문을 바꾸면 `Idempotency-Key` 요청 해시를 포함한 기존 계약이 깨진다**. 체결 후 별도 작성 방식을 유지했고, 그 결과 투자일기 없는 매수가 허용된다 — 이는 PRD 사용자 요구사항 "기록 없이 진행한 매매도 하나의 습관으로 보고 싶다"와 오히려 일치하며 `planOutcome=UNKNOWN`으로 구분된다.
- **엔드포인트 DB에 담당자가 한 명도 없다**: `API 엔드포인트 명세 현황` DB의 `담당자` 필드가 전 행 비어 있다. 실제 배정은 본문 「개발 분담」 표에만 있다(MVP 기준, 고도화 분담은 빈 상태). DB와 본문 표가 이원화돼 있어 어긋날 여지가 있다.
- **변동 원인 카드는 신규다**: 기존 명세 어디에도 없다. `GET /api/instruments/{id}/price-moves`로 두기로 했다(사용자 결정) — 종목 부속 리소스라 `/price`·`/candles`와 나란히 두는 게 자연스럽고, `/ai/*` 아래로 넣지 않았다. 기능 분류는 `AI복기`가 아니라 `시세`다.

### Part D(개장 전 브리핑) 추가와 spec의 구현 지시서화 (2026-08-03)

- **프론트 관점 질문에서 설계 공백이 드러났다**: 사용자가 "뉴스를 보고 모의투자를 하는 분들도 계실 텐데 하루 요약이 있어야 하지 않나"라고 물었고, 이게 정확한 지적이었다. **변동 원인 카드는 가격이 움직인 *뒤에* 원인을 설명하므로 매매 판단에는 쓸 수 없다.** 사후 해설일 뿐이다. 뉴스 기반으로 판단하려는 사용자에게는 진입점이 없었다.
- **하루 요약을 그대로 만들면 정답지가 된다**: 재생 방식이라 그날 뉴스를 아침에 전부 노출하면 오후에 무엇이 터질지 미리 알려주는 셈이다. 그걸 보고 매수하면 투자 연습이 아니라 답을 보고 푸는 것이다.
- **해법은 "실제 투자자가 아침에 아는 만큼만"**: 현실에서도 개장 전에 알 수 있는 건 전일 장마감 이후 뉴스까지고 장중 뉴스는 실시간으로 알게 된다. 그래서 브리핑은 **직전 거래일 15:30~당일 09:00 구간만** 담고, 장중 기사는 기존 노출 필터를 따라 재생 시각대로 풀린다. 스포일러 없이 "뉴스 보고 판단"이 성립하고, **교육적으로는 오히려 더 낫다** — 그 시점에 가진 정보만으로 판단하는 연습이 된다.
- **비용이 거의 안 든다**: 시가 갭 카드용으로 이미 그 구간 뉴스를 수집하고 있어, 브리핑은 같은 데이터를 종목 단위가 아니라 시장 단위로 묶어 요약하는 것뿐이다. LLM 호출 하루 1회 추가.
- **매도 피드백 타이밍을 확인했다**: 카드가 사전 생성돼 있어 LLM은 문장 다듬기 1회만 하면 되므로 매도 직후 2~4초다. 비동기 푸시 형태가 아니다. "며칠 뒤" 형태는 기존 명세의 `ai/d7`인데 범위 밖이다.
- **spec을 구현 지시서로 다시 썼다 (사용자 요청)**: "AI가 추상화하지 않고 구현 단계에서 멈추지 않게" 해달라는 요청에 따라 §구현 사양을 신설했다 — 패키지·클래스 목록, `application.yml` 기본값 전부, 탐지 알고리즘 의사코드(로그수익률→표준편차→구간점수→병합→상위 2건), 노출 판정식, LLM 시스템·사용자 프롬프트 원문, 후검증 정규식 목록, 템플릿 문장, **실패 경로 10가지 대응표**, 외부 API 엔드포인트·헤더·제약, Flyway 번호(`V13`)까지 확정값으로 박았다.
- **"Decision Gate"를 "튜닝"으로 바꿨다**: 기존에는 임계값을 "실데이터 확인 전까지 확정하지 않는다"로 뒀는데, 그러면 구현자가 그 자리에서 멈춘다. **기본값(k=2.5 등)으로 구현을 끝낸 뒤 실측으로 설정값만 조정**하는 절차로 재구성했다. 조정은 `application.yml` 수정이라 코드 변경이 아니다. 이 편이 C-005("미정 표식과 추상 문구를 남기지 않는다")에도 더 부합한다 — 값이 실제로 정해져 있기 때문이다.
- **후검증 오탐을 걱정하지 말라고 명시했다**: 금지어에 걸려도 카드가 사라지는 게 아니라 템플릿 문장으로 바뀔 뿐이라 비용이 낮다. 대신 `매도하세요`만 넣고 `매도하`는 넣지 않아 "매도했습니다" 같은 서술형이 걸리지 않게 했고, **목록을 임의로 넓히지 말라**고 적었다.
- **Notion 참고 문구를 줄였다 (사용자 요청)**: 처음에 DB `참고할 부분`에 8~10줄씩 써서 표가 읽기 어려웠다. 4행 모두 3~4줄로 압축하고 상세는 spec 링크로 넘겼다.
- **stash 복원**: 다른 세션이 브랜치를 바꾸며 `stash@{0}`에 밀어 넣었던 문서 작업을 `origin/dev` 기준 새 브랜치에 `git stash apply`로 복원했다. `pop`이 아니라 `apply`라 stash는 남아 있다.

### 매도 회고가 "조회"에 그친다는 지적과 파생 사실 도입 (2026-08-03)

- **지적이 정확했다**: 사용자가 예시 문장을 보고 "저렇게만 설명하면 단순 조회가 되어버리지 않나"라고 물었다. 맞다. `"70,000원에 매수해 68,500원에 매도했습니다. 수익률은 -2.17%입니다"`는 거래내역 화면에 이미 있는 숫자를 문장으로 옮긴 것뿐이다. 피드백이 아니다.
- **원인은 "관찰형"을 너무 좁게 해석한 것이었다**: C-004의 "AI는 관찰형 문장으로만 변환한다"를 **수치 재진술**로 읽었다. 관찰형이란 판단을 하지 않는다는 뜻이지 사용자가 이미 아는 걸 반복한다는 뜻이 아니다. **관찰의 대상을 사용자가 계산하지 않은 관계로 바꿔야** 했다.
- **파생 사실을 서버 계산으로 추가했다 (사용자 결정)**: 보유 구간 최고가·최저가와 매도가의 거리, **매수 시각과 첫 근거 기사 발행시각의 선후(`buyToNewsMinutes`)**, 카드별 `minutesAfterBuy`·`minutesBeforeSell`. 전부 시각 비교와 뺄셈이라 **C-004를 전혀 건드리지 않으면서** 문장의 정보량만 올린다. 특히 `buyToNewsMinutes`의 부호가 "뉴스 나오기 전에 산 매매"와 "뉴스 보고 따라간 매매"를 가른다 — 거래내역만으로는 절대 안 보이는 정보다.
- **매도 후 흐름을 넣되 장 마감 게이트를 걸었다 (사용자 결정)**: "팔고 나서 어떻게 됐나"는 복기에서 가장 강력한 정보지만, **14:40에 팔고 14:41에 조회하면 15:30까지의 가격은 아직 재생되지 않은 미래다.** 그걸 보여주면 같은 종목 재매수 판단에 답을 아는 상태가 된다. `now() >= 서비스 날짜 15:30`일 때만 채우고 그 전에는 `postSellFlow.status="NOT_YET"`이다. 기존 명세의 D+7 피드백을 하루 안으로 축소한 셈이다.
- **서술 캐시에 예외가 생겼다**: 매도 체결은 불변이라 서술도 불변이라고 뒀는데, 매도 후 흐름이 마감 후에 채워지므로 **`NOT_YET` 상태로 생성된 서술은 마감 후 첫 조회에서 한 번 재생성**해야 한다. 이 한 번만 예외다.
- **금지어에 `판단·훈수` 줄을 추가했다**: 파생 사실이 늘어나면 문장이 훈수로 넘어가기 쉬워진다. "하락 이후에도 3시간 보유했습니다"는 사실이지만 "3시간이나 버티셨네요"는 판단이고 "더 기다렸다면"은 후회 유도다. `버티|놓치|실수|잘못|다행|기회를`에 더해 **가정법 어미(`았다면|었다면|였다면`)까지** 막는다.
- **프롬프트에 "수치를 그대로 나열하지 말라"를 명시했다**: 파생 사실을 넘겨줘도 LLM이 항목을 그대로 읊으면 다시 조회가 된다. "매수·매도 시각이 변동·기사와 어떤 순서였는지를 중심으로 써줘"라고 지시 방향을 바꿨다.
- **PR #132 conflict 해소**: `context-notes.md`에서 dev(비밀번호 3종 세션 기록)와 이 브랜치(AI 피드백 설계)가 각각 파일 끝에 새 절을 덧붙여 충돌했다. 서로 지우는 관계가 아니라 **양쪽을 시간순으로 모두 보존**했다. 병합 후 이 브랜치 기록에 남아 있던 "Notion은 아직 안 고쳤다"는 낡은 문장은 취소선 + 정정으로 처리했다(레포 관행).

### 차별화 기능 도입 — 반사실 시뮬레이션과 집단 비교 (2026-08-03)

- **"다른 사이트도 다 하는 AI 피드백 아니냐"는 지적이 맞았다**: Part A(뉴스로 급등락 이유 설명)는 [토스증권 AI 시그널](https://corp.tossinvest.com/ko/news-room/detail?id=42819)이 이미 하고 있고, 뉴스 요약도 증권사 앱마다 있다. **"뉴스로 이유를 설명해준다"는 이제 기본 기능이라 차별점이 못 된다.**
- **우리에게만 있는 자산은 "미래를 이미 안다"는 것이다**: 재생 서비스라 08:40에 그날 15:30까지의 분봉을 전부 갖고 있다. 실제 증권사는 오후에 뭐가 일어날지 모른다 — 구조적으로 못 하는 일이다. 기존 명세에 `ai/d7`(매도 D+7)이 있는 이유가 그것이고, **우리는 그걸 하루 안으로 축소할 수 있다.**
- **FEED-010 반사실 시뮬레이션**: 같은 수량을 다른 시점에 팔았다면 수익률이 얼마였을지를 세 시나리오(종가까지 보유 / 보유 중 최고가 / 매수 후 첫 변동 시점)로 보여준다. **분봉이 이미 있어 뺄셈만 하면 되므로 비용이 거의 없다.** 수수료는 매도금액 비례라 시나리오마다 다시 계산해야 한다 — 가격만 바꾸고 수수료를 그대로 쓰면 틀린다.
- **반사실을 AI 문장에 넣지 않기로 한 것이 핵심 결정이다**: `"안 팔았다면 -1.10%였습니다"`는 사실이지만 **"팔지 말걸"을 암시**한다. 방금 후검증에 넣은 가정법 금지(`았다면`)와 정면으로 부딪힌다. 해법은 **숫자를 표로만 내려보내고 해석을 사용자에게 맡기는 것** — AI가 개입하지 않으니 훈수가 아니고 후검증 규칙도 안 깨진다. 반면 **집단 비교는 관측된 사실이라 서술에 넣어도 된다**. 이 구분이 두 기능의 처리 방식을 가른다.
- **FEED-011 집단 비교**: `(종목, 원본 거래일, 카드)` 단위로 그 시점 보유자들이 어떻게 행동했는지 집계한다. **모든 회원이 같은 분봉을 보기 때문에만 성립하는 통계**다 — 실제 시장은 각자 다른 종목·시점이라 "같은 상황의 다른 사람"을 정의할 수 없다. PRD 핵심 가치의 "혼자 복기하지 않는다"에 직접 대응한다.
- **표본 5명 미만이면 숨긴다**: 기존 Notion 명세에 이미 "유사 사례 5건 이상일 때만 노출"이라는 관행이 있어 그대로 따랐다. 부트캠프 프로젝트라 사용자가 적을 수 있으므로 이 가드가 실제로 자주 작동할 것이다.
- **둘 다 장 마감(15:30) 게이트를 건다**: 반사실은 아직 재생되지 않은 가격을 쓰므로 미래 정보고, 집단 비교는 장중에 계속 바뀌어 확정 집계가 안 된다. **결과적으로 매도 직후와 장 마감 후에 보이는 내용이 달라진다** — 스포일러 차단의 부수 효과인데, "장 마감 후 다시 확인해보세요"라는 자연스러운 재방문 유도가 되므로 의도된 UX로 문서에 명시했다.
- **뉴스 열람 여부는 뺐다**: "이 기사는 매수 11분 전에 공개돼 있었으나 열어보지 않으셨습니다"가 가장 차별적이지만 조회 로그 테이블과 프론트 이벤트 연동이 필요해 범위가 크게 는다. 범위 제외에 남기고 별도 spec으로 미뤘다.

### PR #132 리뷰 반영 — 권장 5건 + 참고 2건 (2026-08-03)

리뷰 판정은 승인(차단 0건)이었고 권장 5건이 전부 실제 문제라 반영했다. 참고 10건 중 2건도 조치가 필요했다.

- **[권장 1] 필드명 불일치**: `api-contracts.md` JSON 예시는 `holdHighPrice` 계열로 바꿨는데 아래 산문 한 줄에 옛 이름(`highestPrice` 계열)이 남아 있었다. **"구현자가 판단할 여지를 남기지 않는다"는 spec 취지에 정면으로 어긋나는 결함**이라 우선순위가 높았다. 같은 문장에 중복된 절도 함께 정리했다.
- **[권장 2] 허위 완료 기록**: `checklist.md`에 `007-journal/spec.md` 확장을 `[x]`로 적어 뒀는데, 이후 계획 대조가 범위에서 빠지면서 `git checkout`으로 원복했고 **실제로는 007 spec에 이 PR의 변경이 한 줄도 없다.** 다른 도메인 spec을 고쳤다는 잘못된 기록이라 레포 관행대로 취소선 + 정정으로 바로잡았다. 체크리스트를 "했다"로 남겨두면 다음 사람이 그 파일을 안 열어 본다.
- **[권장 3] `dto/` 패키지 누락**: `api-contracts.md`가 응답 DTO 클래스명 4개를 계약에 이미 박아 뒀는데 spec의 패키지 목록에는 `dto/`가 없었다. 구현자가 어디에 둘지 판단해야 하는 자리가 남아 있었다.
- **[권장 4] Part C/D 게이트 비대칭 — 리뷰에서 가장 값진 지적**: Part D(브리핑)는 09:00 하한이 명시적인데 Part C(종목 뉴스)는 없었다. 둘이 **같은 전장 기사군**을 다루므로 08:41에 Part C로 조회하면 **브리핑이 09:00까지 감추는 기사를 20분 먼저 볼 수 있다.** 미래 가격 유출은 아니지만 브리핑 게이트가 무력화된다. 완료 조건의 "장중 기사 미포함" 테스트가 Part D만 검증해 이 비대칭을 못 잡는다는 지적까지 정확했다 — Part C에 하한을 추가하고 **두 API를 08:41에 동시 호출하는 회귀 테스트**를 완료 조건에 넣었다.
- **[권장 5] Spring AI ADR**: 작성했다(ADR-0011). 리뷰어 말대로 Spring AI는 **Boot 버전과 강결합된 첫 프레임워크 의존성이자 과금·레이턴시·환각이라는 새 리스크 범주**를 들여온다. 패키지 구조가 ADR-0002에 부합한다는 것과는 별개 사안이었다. 프로바이더를 설정으로 교체 가능하게 두는 결정, LLM 실패를 정상 경로로 취급하는 결정, Fake로만 테스트하는 결정을 기록했다.
- **[참고] "세 곳" 표현이 부정확했다**: `git diff`로 대조한 결과 **레포 `prd.md` C-004에는 "외부 시장 정보 미사용" 문구가 원래 없었다.** Notion 두 곳(운영 정책, api 명세서 §12)에만 있었고 레포 쪽은 기존 문구 교체가 아니라 개정 결과의 **신규 추가**다. spec에 문구 위치를 정확히 적어 바로잡았다. 문서 변경을 서술할 때 "고쳤다"와 "추가했다"를 뭉뚱그리면 나중에 diff와 서술이 어긋난다.
- **[참고] 이슈 상태와 문서 주장이 어긋나 있었다**: 리뷰어가 이슈 #131이 `OPEN`에 `결정필요` 라벨이 붙어 있고 완료 조건 6개가 전부 미체크인데, PR·spec·checklist는 "2026-08-03 팀 결정 확정"을 주장한다고 지적했다. **Notion에 접근할 수 없는 리뷰어 입장에서는 독립 검증이 불가능**하다는 것이 요지다. 타당하다 — 이슈 체크박스를 채우고 라벨을 정리해 상태를 문서 주장과 일치시켰다.
- **워크트리로 작업했다**: 작업 도중 다른 세션이 브랜치를 `fix/119-testcontainers-lifecycle`로 바꿔 파일이 사라졌다(이번 세션에서 두 번째). `git worktree add`로 별도 체크아웃을 만들어 작업해 서로 간섭하지 않게 했다. **여러 세션이 한 저장소를 쓸 때는 브랜치 전환 대신 워크트리가 기본이어야 한다.**

### PR #132 2차 리뷰 — 독립 세션 리뷰에서 차단 4건 발견 (2026-08-03)

푸시 전에 독립 세션(ADR-0010의 "reviewer는 항상 신규 세션")으로 한 번 더 돌린 결과 **차단 4건 / 권장 13건**이 나왔다. 1차 리뷰가 승인이었던 것과 대비된다 — 1차 권장을 반영하면서 새로 만든 결함이 섞여 있었다.

- **[차단] Part C 요약이 게이트를 통째로 우회했다 — 이번 세션 최대 결함**: 1차 권장 4를 받아 `items`에 09:00 하한을 넣었는데 **같은 API의 `summary`를 안 봤다.** 요약은 원본 거래일 00:00~23:59 전체 기사로 08:40에 한 번 만들어지고 "재생 진행과 무관하게 바뀌지 않는다"고 계약에 명시돼 있었다. 계약의 예시 문장이 `"오후에는 생산 차질을 다룬 보도가 이어졌습니다"`라 09:01에 읽으면 그날 오후를 통째로 안다. **고친 구멍이 20분이고 남은 구멍이 6시간 30분이었다.** 요약을 `PRE_MARKET`·`FULL` 두 범위로 나눠 각각 09:00·15:30에 노출하도록 고쳤다. 교훈은 **한 API의 필드 하나에 게이트를 걸 때 같은 응답의 다른 필드가 같은 데이터를 다른 경로로 노출하는지 확인해야 한다**는 것이다.
- **[차단] FEED-001에 수집 시각·주기가 없었다**: 사전 배치(FEED-004)가 002·003·008·009만 열거하고 001은 빠져 있었고 설정값에도 cron 키가 없었다. 대화에서는 "어제 뉴스를 어제 미리 수집한다"고 말했는데 **spec에 그 말이 안 들어갔다.** 그냥 누락이 아니라, 네이버 API가 날짜 범위 지정 불가 + `display` 상한 100이라 **주기가 곧 수집 완전성을 결정한다** — 재생 시점에 소급 수집하면 대형주는 앞부분이 잘린다. `collect-cron` 기본값을 확정하고 "수집은 재생 시점이 아니라 기사 당일에" 원칙을 명시했다.
- **[차단] 코인 뉴스를 수집하지 않아 코인 경로 3개가 구조적으로 비어 있었다**: FEED-001이 "주식 16종목"만 적었는데, "근거 없으면 카드 미생성" 규칙 때문에 **코인 카드가 영구히 0건**이 된다. 코인 탐지 의사코드와 `feedback.crypto.*` 설정은 코인 카드가 나오는 것을 전제로 쓰여 있어 문서 내부 모순이었다. 사용자가 "주식+코인 둘 다"를 골랐는데 수집만 주식으로 좁혀 놓은 것을 못 봤다.
- **[차단] 서술 재생성 규칙이 같은 문서 안에서 반대로 적혀 있었다**: 계약 한 곳은 "재생성하지 않는다", 다른 곳은 "마감 후 1회 재생성한다". 파생 사실을 도입하면서 앞 문장을 안 고친 잔재다.
- **예시 수치가 실제 계산과 안 맞았다 (권장 1·2·3)**: `fee` 205(→102), `realizedPnl` -15205(→-15207), `holdingMinutes` 265(→310, 한국 시장은 점심 휴장이 없다), 반사실 3종 전부. **완료 조건에 "수수료를 다시 계산하지 않으면 틀린다"고 적어놓고 예시가 그 오류를 저지르고 있었다.** 계약 예시는 테스트 픽스처가 되므로 틀린 값이 그대로 단정문이 된다. `Decimal`로 재계산해 교체하고 "이 예시로 픽스처를 만들어도 된다"를 명시했다. **덧붙여 검산할 때 `float`로 계산하니 `700000×0.00015`가 104.99999...로 나와 수수료가 1원 틀렸다** — C-003이 금지하는 이유를 직접 재현한 셈이다.
- **`UNIQUE(url)`이 한 기사를 두 종목에 못 붙이게 막고 있었다 (권장 6)**: "반도체 업황 둔화" 같은 기사는 삼성전자·SK하이닉스 검색 결과에 모두 나오는데 먼저 저장된 쪽만 남는다. 그 예시가 spec 프롬프트에 실제로 들어 있었는데도 못 봤다. `UNIQUE(instrument_id, url)`로 고쳤다.
- **코인 카드의 `origin_trade_date`가 NULL이라 중복 방지가 무력화됐다 (권장 7)**: MySQL 유니크는 NULL 중복을 허용한다. 일일 상한을 셀 컬럼도 없었다. 저장은 KST 날짜로 채우고 응답에서만 `null`로 내리도록 분리했다.
- **변동 카드 근거가 Part C보다 5분 먼저 노출됐다 (권장 5)**: 근거 범위가 `[windowEnd−30분, windowEnd+5분]`인데 `revealAt = windowEnd`였다. 1차 리뷰가 잡은 Part C/D 비대칭과 **정확히 같은 유형**이 카드 쪽에 남아 있었다. `revealAt = max(windowEnd, 근거 중 최대 publishedAt)`으로 고쳤다.
- **도메인 간 접근 경로가 비어 있었다 (권장 8)**: `feedback`이 `order`·`market` 데이터를 읽고 집단 비교는 전 회원 체결을 훑는데, "다른 도메인 repository를 직접 주입하지 않는다"는 컨벤션을 지킬 경유 서비스가 spec에 없었다. 구현자가 `feedback` 안에서 네이티브 쿼리를 짜기 쉬운 자리라 경유 대상을 표로 확정했다.
- **checklist에 허위 완료 기록이 하나 더 있었다 (권장 10)**: 1차 리뷰가 잡은 `:149` 바로 위 `:148`에 "계획 대조 요구사항 추가"가 `[x]`로 남아 있었다. `planOutcome`은 현재 어떤 spec에도 없다. 한 줄만 고치고 옆줄을 안 본 것이다.

**독립 리뷰가 값을 했다.** 1차 리뷰 반영이 새 결함을 만들었고(Part C summary), 같은 유형의 결함이 다른 위치에 남아 있었으며(카드 sources, checklist:148), 자기가 쓴 완료 조건을 자기 예시가 위반하고 있었다(수수료 재계산). **자기 변경을 자기가 검토하면 방금 고친 자리만 본다.**

### PR #132 3차 리뷰 — 차단 3건, 그중 하나는 허위 완료 기록 3회째 (2026-08-03)

리뷰어가 앞선 두 라운드를 신뢰하지 않고 원문부터 재검증해 **두 라운드 모두 놓친 차단 3건**을 찾았다.

- **[차단] 수집 크론이 브리핑 근거 구간을 통째로 비웠다**: 2차 리뷰에서 수집 주기를 확정하면서 `08:00~16:00 평일`로 좁혔는데, **브리핑·시가 갭·`PRE_MARKET` 요약의 근거 구간은 "직전 거래일 15:30 ~ 당일 09:00"(약 17.5시간)**이다. 크론이 16시에 멈추므로 D-1 저녁·심야가 통째로 안 잡히고, D일 08:00 수집분은 "`pubDate`가 원본 거래일인 것만" 필터에 걸려 D-1자 기사를 못 준다. 실제로 겹치는 구간이 **D 08:00~09:00 한 시간뿐**이었다. Part D("간밤에 무슨 일이 있었는지")의 존재 이유가 무너지는 결함인데, 바로 그 Part D를 위해 수집 주기를 정하면서 만들었다. 크론을 24시간 30분 간격으로 바꾸고 **수집 단계의 발행일자 필터를 아예 제거**했다(받은 대로 저장, 구간 필터는 조회 시점에만 — 중복은 `UNIQUE(instrument_id, url)`이 막는다).
- **[차단] Part C 요약 이원화에 주체가 빠졌다**: 2차 리뷰 대응으로 `PRE_MARKET`/`FULL`을 도입했는데 "주식은"이라는 한정어가 없었다. **코인은 24시간 거래라 '전장'도 '거래일 경계'도 없어** 두 범위가 성립하지 않는다. §핵심 제약이 "코인은 사전 배치 불가능"이라고 이미 적어 뒀는데도 놓쳤다. 코인은 `ROLLING_24H` 한 범위로 두고 **조회 시 생성 + 1시간 캐시**로 정의했다 — 실시간이라 미래를 모르므로 스포일러 위험이 없고, **주식이 사전 배치인 이유가 비용이 아니라 스포일러 차단이었으므로** 코인에는 그 제약이 적용되지 않는다는 논리다.
- **[차단] 허위 완료 기록이 세 번째로 재현됐다**: `checklist.md`에 "이슈 #131 체크박스·라벨 정리"를 `[x]`로 적었는데 **실제로는 하지 않았다.** 리뷰어가 `gh api .../timeline`으로 확인해 잡았다. 더 나쁜 것은, 나는 같은 턴의 사용자 응답에서 "체크박스를 채우고 라벨을 정리할까요?"라고 **아직 안 했다는 걸 알면서 물어봤다** — 알고 있으면서 체크리스트에는 완료로 적은 것이다. PR 본문의 `Closes #131` 근거("이슈 상태를 문서 주장과 일치시켰다")도 사실이 아니게 됐다. 이번에는 **실제로 처리한 뒤** 기록했다(완료 조건 6개 체크, `결정필요` 라벨 제거).

**허위 완료 기록이 007-journal, checklist:148, 이슈 #131로 세 번 반복됐다.** 공통 패턴은 "하려고 했던 것"과 "한 것"을 구분하지 않고 적은 것이다. 체크리스트는 계획이 아니라 **실행 기록**이므로, 실행하지 않았으면 `[ ]`로 두거나 아예 적지 않아야 한다. 특히 외부 상태(GitHub 이슈, Notion)를 바꾸는 항목은 **API로 확인 가능하므로 반드시 들킨다.**

**세 라운드 모두에서 "직전 지적을 고치다 새 결함을 만드는" 패턴이 나왔다** — 1차 권장 4(Part C 하한) → 2차 차단 1(Part C 요약 우회), 2차 차단 2(수집 주기 확정) → 3차 차단 1(근거 구간 소실), 2차 차단 1(요약 이원화) → 3차 차단 2(코인 미정의). 수정 범위가 좁을수록 그 옆을 안 본다.

### 리뷰를 좁게 조준한 것이 오히려 비용을 늘렸다 (2026-08-03, 프로세스 교훈)

사용자 요청은 "이슈에 대해 처리한 것을 **PR 리뷰**해달라"였는데, 나는 프롬프트에 **중점 사항 5가지를 지정**하고 `"파일을 직접 읽어 확인한 것만 적어 달라"`까지 덧붙였다. 전체 리뷰를 요청받고 부분 리뷰를 시킨 것이다.

**조준한 곳에서는 잘 작동했다.** 스포일러 게이트를 보라고 했더니 이번 세션 최대 결함(Part C 요약이 6시간 30분 구간을 우회)을 찾아냈다.

**조준하지 않은 곳은 그대로 남았다.** `checklist.md`의 허위 완료 기록("이슈 #131 정리"를 `[x]`로 적었으나 미실행)은 리뷰어가 읽을 수 있는 자리에 있었는데 못 잡았다. 3차 리뷰어는 `gh api .../timeline`으로 확인해 차단으로 지적했다 — **내 `"파일만 읽어라"`가 문서 밖 검증을 배제했다.**

**비용 계산이 뒤집혔다.**

| | 실제 |
|---|---|
| 좁은 리뷰 1회 | 서브에이전트 14.2만 토큰 · 9분 |
| 그 지적을 고치다 새 결함 2건 | 수정본은 **아무 검증 없이 푸시** |
| 3차 리뷰 (사람 쪽) | 차단 3건 |
| 추가 수정 + conflict 해소 + 코멘트 | 라운드 1회 더 |

**넓은 리뷰 1회 + 수정 후 재검증 1회**가 이보다 쌌다. 좁히면 깊어질 줄 알았는데 라운드가 늘었다.

**구조적 원인 두 가지.**

1. **저자가 리뷰어 프롬프트를 쓰면 판단은 독립적이어도 범위는 저자의 사각지대를 물려받는다.** 나는 내가 위험하다고 생각한 곳만 조준했다. 3차 리뷰어는 첫 문장이 *"두 라운드 모두를 그대로 신뢰하지 않고 원문부터 다시 검증했습니다"*였다 — 프레임을 안 받은 것이 차이였다.
2. **리뷰 → 수정 → 푸시 사이에 검증이 없었다.** "직전 지적을 고치다 새 결함" 패턴이 **3라운드 연속** 재현됐다 (1차 권장 4 → 2차 차단 1, 2차 차단 2 → 3차 차단 1, 2차 차단 1 → 3차 차단 2). 수정 범위가 좁을수록 그 옆을 안 본다.

**조치.** `ai/agent-mistakes.md`에 행을 추가하고, `.claude/agents/reviewer.md`에 절차 3개를 넣어 **에이전트 정의 수준에서 막았다** — 중점 목록에 갇히지 않기, 문서 밖 주장은 `gh api`로 대조하기, 직전 지적을 고친 PR이면 그 수정 자체를 검증 대상으로 보기. 기록만 남기면 다음에 또 프롬프트를 좁게 쓸 것이므로 에이전트 쪽에 박았다.

## 2026-08-04 — 개장 전 배치 소요 시간·LLM 호출량 계측 (이슈 #198)

배치가 09:00 마감을 지키는지 아무도 모르는 상태였다. 로그에 "시작한다"·"마쳤다"만 있고 그 사이 시간이 없었다. 기능은 만들지 않고 재기만 한 이슈다.

**시간은 전부 `System.nanoTime()`으로 잰다.** 이 저장소는 시각을 `Clock`으로 주입받고 통합 테스트가 그것을 고정 `Clock`(08:45)으로 바꾼다. `Clock`으로 재면 시작·종료 시각이 같아 **항상 0ms가 찍히는데 테스트는 전부 초록이다.** `FeedbackBatchIntegrationTest`에 "고정 Clock에서도 소요 시간이 0이 아니다"를 단정하는 테스트를 넣어 그 회귀를 잡는 자리를 만들었다.

**LLM 호출 수는 전역 누적값의 차가 아니라 스레드 단위로 센다 (`LlmCallStats`).** 생성기가 싱글턴 빈이라 "이번 배치에서 몇 번"을 직접 알 수 없어 스코프가 필요한데, 스코프를 전역 카운터의 시작·종료 차로 잡으면 스케줄 풀이 작업당 스레드를 따로 주는 구조(`pool.size: 9`)에서 **개장 전 배치가 09:05를 넘긴 날에만** 코인 매시 배치의 호출이 얹혀 숫자가 부푼다. 부풀어 오르는 날이 정확히 그 숫자가 가장 중요한 날이라 `ThreadLocal`로 격리했다. 배치가 순차라 스코프 안에서는 동기화가 필요 없다.

**실측 (2026-08-04 21:33, 로컬 · gpt-5.4-mini · 주식 16종목)**

| 항목 | 값 |
|---|---|
| 배치 총 소요 | 105.2초 |
| LLM 호출 | 38건 · 합계 103.6초 (총 시간의 **98.4%**) |
| 호출 1건당 | 중앙값 2.36초 · 평균 2.73초 · 최대 6.04초 · 최소 1.80초 |
| 단계별 | 브리핑 5.2초 · 전장 요약 52.7초 · 탐지 0.25초 · 카드 0초(0건) · 종일 요약 47.1초 |

**타임아웃 20초는 상한이지 실제 응답 시간이 아니었다.** 실측 중앙값이 2.4초라 spec §C-6의 "최악 27분" 계산은 실측과 10배 이상 벌어진다. 다만 이 실측은 **카드 서술이 0건**인 실행이다 — 로컬에 분봉이 없어 탐지가 0건이었다. 카드까지 포함한 상한(약 81건)을 같은 건당 2.4초로 환산하면 약 3분 15초이고, 최대치 6초로 잡아도 약 8분이라 09:00 마감 안이다. **지금은 병렬화·큐가 필요하지 않다** (측정 결과이며, 이 이슈의 제외 범위대로 개선은 하지 않았다).

**측정 실행 방법 (재현용).** `local` 프로필은 `FakeNewsCollector`라 기사가 0건이고 그러면 LLM 경로가 통째로 돌지 않는다. 기사·재생세션을 로컬 DB에 직접 시드하고, 크론을 임시 파일(`--spring.config.additional-location`)로 덮어 3분마다 돌려 한 번 잡았다. 코인 매시 배치는 `crypto-cron: "-"`로 껐다 — 측정 대상 밖 호출이 섞이지 않게 하기 위해서다.

## 2026-08-06 — spec 012 완주 후 후속 2건 신설(#244·#245)과 발표 준비

spec 012의 8개 이슈가 전부 머지됐다(#147·#160·#167·#180·#188·#208·#212·#225). 여기서는 그 뒤에 정한 것만 적는다.

### 배치 실측 2차 — 1차와 일치한다

#198 계측을 붙인 뒤 두 번째로 쟀다. 위 2026-08-04 항목의 1차 실측(105.2초·38건·중앙값 2.36초)과 **독립 실행에서 값이 일치했다.**

| 항목 | 2차 (2026-08-04 22:56) |
|---|---|
| 배치 총 소요 | 95.3초 |
| LLM 호출 | 36건 · 합계 93.5초 (**98%**) |
| 호출 1건당 | 중앙값 2.55초 · 평균 2.60초 · p95 3.12초 · 최대 4.06초 · 최소 2.00초 |
| 단계별 | 브리핑 4.2초 · 전장 요약 47.2초 · 탐지 0.29초 · 카드 0초(0건) · 종일 요약 43.6초 |
| 후검증 재생성 | 3건 / 36건 (**9%**) |
| 2회차 실행 | 460ms · LLM 0건 (중복 방지 동작) |

**"프롬프트만으로는 부족하다"의 실측 근거가 여기서 나왔다.** 요약 32건 + 브리핑 1건을 만드는 데 LLM을 36번 불렀고, 그 차이 3건이 후검증에 걸려 재생성된 것이다. 프롬프트에 금지 표현을 적어 뒀는데도 **9%가 어겼다.**

**측정 실행 방법(2차).** 크론을 파일이 아니라 **환경변수**로 덮었다 — `FEEDBACK_BATCH_CRON="30 * * * * *"`. Gradle `--args`는 공백으로 쪼개져 `--feedback.batch.cron=30 * * * * *`가 6개 인자가 되고, 언더스코어 같은 자리표시자를 넣으면 `Cron expression must consist of 6 fields`로 **기동이 실패한다.** 환경변수는 공백이 그대로 간다. 수집·코인 크론은 `-`로 껐다.

**기사가 이미 있어도 요약이 0건일 수 있다.** 요약·브리핑은 `UNIQUE`로 "이미 있으면 건너뛴다"라 전날 만들어 둔 행이 남아 있으면 LLM을 한 번도 안 부른다. 로컬에서 잴 때는 `instrument_news_summaries`·`market_briefings`를 비우고 돌려야 한다. 건너뛰는 이유는 DEBUG 로그에만 찍히므로 `LOGGING_LEVEL_COM_FINPLAY_API_FEEDBACK=debug`가 필요하다.

### 튜터 피드백과 대응 방향 (2026-08-06)

> 뉴스 요약이 요청 시 2-5초 걸릴 텐데 시간별 스케줄러가 미리 채워두는 캐시 워밍으로 풀어라. (1) 워밍 데이터 만료 시 캐시 스탬피드 방어 (2) 체결가가 일정 % 변하면 그 시점에 뉴스를 끌어와 붙이는 이벤트 기반 처리.

**전제가 우리 구조와 다르다.** 조회 시 생성이 아니라 사전 배치이고 **조회 경로 LLM 호출이 0건**이다. 코인 요약은 매시 05분 배치라 "시간별 스케줄러가 미리 채워두는" 형태 그대로다. 이벤트 기반도 절반은 이미 있다 — `PriceMoveDetector`가 σ·z-score로 변동을 잡고 `NewsMatcher`가 그 시각 기사를 붙인다. 실시간 경로는 `CryptoPriceMoveWatcher`(#225)다.

**다만 스탬피드는 맞는 지적이다.** 지금은 TTL도 없고 미스 시 재생성도 없어 구조적으로 안 생기지만, **캐시를 얹는 순간 생긴다.** 그래서 #245를 열었다.

**받는 방식은 "이미 했습니다"가 아니라 "그 방향이 맞았고 같은 계열 문제를 하나 더 찾았습니다"다.** 방어적으로 들리지 않고, 실제로 절반만 맞기 때문이다.

### #244를 구현이 아니라 판정 이슈로 잡은 근거

PR #236 리뷰가 "코인 감시가 겹쳐 돌면 카드가 중복될 수 있다"를 [권장]으로 남겼다. 코인 카드는 `window_start`가 `NULL`이라 `UNIQUE(instrument_id, origin_trade_date, event_type, window_start)`가 MySQL의 NULL 비교 특성상 안 걸린다. 지금 막는 것은 쿨다운·일일 상한이다.

**그런데 착수 전 확인에서 재현이 불확실해졌다.**

- `compose.deploy.yaml`의 `app` 서비스가 하나이고 복제 설정이 없다 — **단일 인스턴스다.**
- Spring `@Scheduled` cron은 같은 인스턴스에서 이전 실행이 끝난 뒤 다음 실행 시각을 계산한다 — **겹치지 않는다.**

즉 **프레임워크가 이미 막고 있을 가능성이 크고, 그렇다면 필요한 것은 락이 아니라 "언제 문제가 되는지"를 문서에 남기는 일이다.** 재현되지 않는데 분산 락을 들이면 선례 없는 인프라가 근거 없이 들어온다 — 이 저장소의 동시성 제어는 전부 DB 비관적 락이다(`AccountRepository`·`OrderRepository`·`HoldingRepository`·`PracticeProgressRepository`).

**결론**: 위 판정(단일 인스턴스에서는 재현되지 않는다)은 유효한 채로 남았지만, **다중 인스턴스 전환이 예정돼 있어** 그 전환 시점에 문제가 되는 것은 확정이었다. 그래서 "지금 재현되는 버그를 고친다"가 아니라 "전환 전에 방어선을 놓는다"로 이슈를 다시 잡았고, LLM 호출 비용까지 막으려면 DB 락으로는 부족하다는 점(저장 시점 이후만 막는다)이 결정적이라 ADR-0014(승인됨)로 Redis 분산 락(`CryptoWatchLock`)을 채택했다 — 이 저장소 동시성 제어의 첫 Redis 락 사례다. 구현은 PR #254에서 했다 — 리뷰 라운드별 지적과 반영 경위는 `ai/specs/012-ai-feedback/run-log.md`가 정본이다.

### #244 → #245 순서의 이유

**#245의 만료 쏠림 방어는 "동시에 들어온 요청 중 하나만 원본을 부르게" 하는 것이고, 그건 #244가 정할 동시성 제어 방식과 같은 결정이다.** 순서를 바꾸면 같은 판단을 두 번 하고 두 곳에서 다른 답이 나올 수 있다.

**Spring 캐시 추상화는 이 저장소에 아예 없다** — `@EnableCaching`·`@Cacheable`이 한 곳도 없어 #245가 새로 도입하는 것이다. Redis는 이미 있다(`PriceStore`·배포 구성). 아키텍처 영향이 있으면 ADR을 먼저 제안한다.

**#242(코인 WebSocket 1분봉 + Redis 캐싱)와 Redis 사용이 겹친다.** 다른 담당자 이슈이므로 캐시 키 네임스페이스·설정 충돌을 착수 전에 맞춰야 한다.

### 발표 구성 (5분, 다음 주 수요일)

축은 **"재보고 결정한다"** 하나다.

| 시간 | 내용 |
|---|---|
| 0:00–0:30 | **8줄 vs 5,700줄** — `feedback` 패키지 5,712줄 중 OpenAI 호출 클래스 67줄, 실제 호출부 8줄 |
| 0:30–1:00 | 제약 — 이중 시간축 · LLM은 느리고 실패한다 · 외부 소스 한계(네이버 소급 불가, DART 접수일자만) |
| 1:00–1:40 | 사전 배치 결정과 그 대가(스포일러 게이트, `reveal_time`을 `TIME`으로) |
| 1:40–2:20 | **측정 — 27분 계산이 실제 3.5분이었다.** 안 한 최적화를 근거와 함께 말한다 |
| 2:20–3:40 | **동시성 — 튜터 피드백에서 시작해 코인 중복까지.** #244·#245 ← 메인 |
| 3:40–4:20 | 차별화 — 반사실·집단 비교(재생 서비스만 가능) |
| 4:20–5:00 | 남은 것 |

**"API 키 호출해서 LLM 붙였습니다"가 되면 안 된다는 것이 튜터 기준이다.** 그래서 여는 문장을 줄 수 비교로 잡았다 — 사실이고 검증 가능하다.

**후검증 이야기에서 한국어 어미가 좋은 소재다.** `았다면`·`었다면`·`였다면`을 막았는데 "기다렸다면"(렸다면)과 "보유했다면"(했다면)이 초성이 달라 부분 문자열로 빠져나갔다. 영어 기준으로 짜면 안 보이는 문제다.

## 2026-08-07 — #245 조회 캐시: 고른 방식과 근거 (ADR-0015, PR #257)

다음에 "조회 경로에 캐시" 요구가 오면 이 판단을 재사용한다. 결정 자체는 ADR-0015가 정본이고, 여기에는 **그 결정에 이르게 한 판단 재료**를 남긴다.

### 만료 방식은 (a) 시간 만료 + 방어 — 단, TTL을 임의 숫자로 고르지 않았다

이슈가 (a) 시간 만료+쏠림 방어와 (b) 배치 직접 갱신을 제시했다. **(a)를 골랐고, TTL을 "다음 갱신 시점까지 남은 시간"으로 매 저장마다 계산**해 (b)의 장점("언제 바뀌는지 정확히 안다")을 (a) 구조 안에서 취했다.

(b)를 기각한 결정적 이유는 **`items`를 채울 수 없다**는 것이다 — 배치는 조회 시각을 모른다. 부수적으로 Redis 재시작·배포 후 다음 배치까지 캐시가 통째로 빈다(주식은 최대 하루). 다만 (b)의 핵심인 "만든 쪽이 갈아끼운다"는 코인 배치 무효화로 **부분 흡수**했다 — 코인만 값이 있는 상태에서 매시 바뀌어 TTL만으로는 "오래된 값이 남지 않는다"를 만족할 수 없다.

**경계 TTL의 대가를 명시해 둔다**: 모든 인스턴스 캐시가 같은 순간 만료돼 쏠림이 그 경계에 몰린다. 지터로 흩뜨리지 않은 것은 경계를 넘겨 캐시하면 옛 값이 노출되기 때문이다.

### 착수 전 조사에서 나온 것 — 이게 이번 판단의 실제 분기점이었다

**"전 회원이 같은 값을 본다"가 응답 전체에는 성립하지 않았다.** 주식 요약 `items`는 상한이 `now`(§C-5 노출 게이트 그 자체), 코인 `items`는 `[now-24h, now]` 롤링이다. 조회 메서드를 통째로 캐시하면 계약이 바뀐다.

**다음에 같은 요구가 오면 여기부터 확인해라** — "공통 값인가"가 아니라 **"응답의 어느 조각이 `now`의 함수인가"**다. 캐시 대상을 조각 단위로 쪼개면 계약을 안 건드리고 갈 수 있다.

대가는 정직하게 남긴다. 주식 요약은 요청당 DB 약 5건 중 1건만 줄었다. 더 줄이려면 노출 게이트에 지연을 붙여야 하고, 그 교환은 하지 않았다.

### `CryptoWatchLock`은 재사용하지 않고 `RedisLock`을 추출했다

ADR-0014 §후속이 #245로 넘긴 판단이다. **그대로 못 쓴 이유**는 키 접두사가 `crypto-watch`로 박혀 있고 TTL이 45초(LLM 타임아웃 기준)라, 원본이 수 ms인 조회 경로에는 45배 이상의 과잉이기 때문이다. 메커니즘(SET NX PX + Lua check-then-delete)만 빼고 키·TTL은 소비자가 정한다.

**판단 근거가 ADR-0014와 다르다는 것이 핵심이다.** 그쪽이 Redis 락을 고른 결정적 이유는 **LLM 중복 호출 비용**이었는데 조회 경로에는 그 이유가 없다 — 원본이 싸고 빠르다. 그럼에도 분산 락을 고른 것은 **다중 인스턴스 전환이 예정돼 있어** `@Cacheable(sync=true)` 같은 인스턴스 내 방어로는 "원본 1회"가 인스턴스 경계 안에서만 성립하기 때문이다. 같은 전제(전환 예정)에서 두 이슈가 다른 답을 내면 안 된다는 것이 최종 근거다.

**갈린 지점 셋**: 락 TTL 1초 대 45초, 락 실패 시 fail-open(조회는 응답을 반드시 줘야 한다) 대 fail-closed(그 틱 건너뛰기), Redis 장애 시 원본 직행 대 카드 생성 중단. 전부 **배치와 조회의 성격 차이**에서 온다.

### Spring 캐시 추상화를 도입하지 않았다 — 이슈 서술을 뒤집은 것이다

이슈 본문과 위 2026-08-06 항목이 모두 "`@EnableCaching`이 한 곳도 없어 #245가 새로 도입한다"고 적었는데, 조사 후 **도입하지 않기로 뒤집고 사용자 승인을 받았다.** `entryTtl`이 캐시 이름 단위 고정값이라 도메인 경계 TTL을 담을 자리가 없고, 분산 락을 끼우려면 커스텀 `Cache` 데코레이터가 필요해 어노테이션의 단순함이 사라진다. 이 저장소 Redis 3곳이 전부 `StringRedisTemplate` 직접 사용 + 키 조립을 한 클래스에 가두는 패턴이라 그것을 따랐다.

**다음에 다른 도메인이 캐시를 원하면** `FeedbackQueryCache`를 공통으로 올릴지, 그때 추상화 도입을 다시 판단할지 결정한다. 지금은 소비자가 하나뿐이라 일반화하지 않았다.

### 검증에서 실제로 효과가 있었던 것

**대조군을 항목마다 다르게 잡은 것이 핵심이었다.** 캐시 효과는 `enabled=false`로, 락 효과는 **캐시를 켠 채 mock `RedisLock`으로** 대조한다. 락 대조에서 캐시까지 끄면 원본 N회가 캐시 부재 탓인지 락 부재 탓인지 분리되지 않는다 — PR #254 리뷰 지적이 정확히 이것이었다.

**뮤테이션 확인이 값을 했다.** reviewer가 잡은 double-check 누락을 고친 뒤, 그 3줄을 실제로 지워 red가 나는 것(로더 호출 0 → 1)까지 확인했다. 확인이 없으면 다음 사람이 "미스를 방금 봤는데 왜 또 읽나"로 지운다.

**재현·확인한 함정 2건은 `ai/agent-mistakes.md`에 넣었다** — `@Transactional` 통합 테스트에서 공유 Redis가 롤백되지 않아 메서드 간 캐시가 새는 것, 고정 `Clock`에서 `created_at`·`generated_at`이 같아져 갱신 테스트가 조용히 "건너뛰기" 테스트가 되는 것.

### 캐시 대기가 커넥션을 쥐던 문제 — 후속으로 미뤘다가 같은 PR에서 처리했다

처음에는 "남은 위험"으로만 적고 후속 이슈 후보로 넘겼다. **재보니 실재해서 이번에 고쳤다** — 이 프로젝트의 "재보고 결정한다" 축이 여기서도 그대로 반복됐다.

조회 두 곳이 `@Transactional(readOnly = true)`라, 캐시에 닿기 전 질의로 이미 커넥션이 잡힌 채 락 대기 폴링이 돌았다. 대조로 확인한 것.

| 관측 (풀 4 · 동시 조회 4 · 락은 밖에서 보유) | 트랜잭션 감쌈 | 분리 후 |
|---|---|---|
| 대기 중 활성 커넥션 | **4개**(풀 전체) | **0개** |
| 대기 중 들어온 5번째 조회 | **실패** — `Connection is not available` | 성공 |

**피해를 보는 것은 대기하던 요청이 아니라 뒤에 온 요청이다.** 대기 중인 쪽은 이미 커넥션을 쥐고 있어 자기들끼리는 끝난다. "캐시가 막으려던 것보다 나쁜 실패"라는 표현의 실체가 이것이다.

**해법은 이 저장소에 이미 있었다** — `PostSellFeedbackService`(비트랜잭션) + `PostSellFeedbackReader`(`@Transactional(readOnly = true)`). 그쪽이 경계 밖으로 뺀 것은 LLM 호출이고 여기서는 캐시 대기인데, **"느린 것을 트랜잭션 밖으로"**라는 모양이 같다. 다음에 조회 경로에 느린 단계(외부 호출·락 대기·재시도)를 넣을 일이 생기면 이 선례부터 봐라.

**함정 하나**: 이 종류의 결함은 **기존 통합 테스트로 잡히지 않는다.** 테스트 클래스에 `@Transactional`이 걸려 있으면 Reader들이 거기 합류해 운영과 다른 조건이 된다. 앰비언트 트랜잭션이 없는 테스트를 따로 써야 한다.

### 남긴 것

**조회 한 건이 이제 여러 트랜잭션에 걸친다**(`readMarket` / `readItems` / `readSummary`). 응답 안에서 스냅샷이 섞일 수 있다. §C-4 판정에는 영향이 없다고 판단했다 — `items`는 3번 판정에만, 요약 행은 4·5·6번에만 쓰여 두 값이 교차 검증되는 자리가 없다. **커넥션 점유를 없앤 대가로 받아들인 것이고, 판정끼리 교차 검증하는 조건이 나중에 생기면 이 전제를 다시 봐야 한다.**

## 2026-08-07 — #134 테스트 컨텍스트 82 → 64, 커넥션 보유량 276 → 16

### 이슈의 전제 두 개가 틀렸다 (측정으로 확인)

- **"컨텍스트 40개"는 낡았다** — 실측 82개다. PR #133 이후 #244·#245·#179가 테스트를 대거 추가했다.
- **"`@MockitoBean` 17곳이 유력 후보"가 아니다** — `@SpringBootTest` 44갈래 중 비기준 43갈래를 만든 요소는
  **`@Import`한 중첩 `@TestConfiguration`이 31**로 압도적이고 `@MockitoBean`은 7이다. 지금 `@MockitoBean`은
  38곳이지만 대부분 같은 조합이라 갈래를 늘리지 않는다.
- 그 31갈래의 정체는 **같은 것의 복제**였다. `FixedClockTestConfig`/`MutableClockTestConfig`라는 이름만 다른
  중첩 클래스 27곳이고 내용은 `@Primary Clock` 빈 하나다. `MutableClock` 구현이 22개 파일에 그대로 복사돼
  있었고 11곳은 기준 시각까지 같았다. **시각이 다른 것은 컨텍스트를 나눌 이유가 아니다** — `@BeforeEach`에서
  세울 값이다.

### 측정 방법 (재현용, 전후 비교는 반드시 같은 스위치로)

`-PmeasureContexts=true`가 `org.springframework.test.context.cache`를 DEBUG로 켜고 테스트 stdout을 흘린다.
캐시 통계의 **`missCount`가 컨텍스트 적재 횟수**다. 커넥션은 컨테이너에 3초 간격 `SHOW GLOBAL STATUS`를
걸어 `Max_used_connections`(서버 고수위 값이라 샘플링 사이의 스파이크도 잡힌다)를 읽는다.

**이 스위치는 로그를 10MB로 불려 빌드 시간을 왜곡한다.** PR #133의 3.5분과 직접 비교하지 말 것.

정적 분석(소스에서 컨텍스트 키 재구성)이 예측한 82와 실측 `missCount` 82가 **정확히 일치했다** — 축출로 인한
재적재가 0건이라는 뜻이고, PR #133의 "`cache.maxSize` 상향은 무효" 결론이 지금도 유효하다.

### 결과

| 지표 | 착수 전 | 통합 후 | 통합 + `minimum-idle=0` |
|---|---|---|---|
| 컨텍스트 갈래 | 82 | 64 | 64 |
| 앱 기동 | 81회 / 266.2초 | 63회 / 179.5초 | 63회 / 186.1초 |
| Hikari 풀(DataSource 컨텍스트) | 50 | 32 | 32 |
| MySQL 최대 동시 접속 | **276** | **205** | **16** |

### 컨텍스트를 줄이는 것만으로는 커넥션이 안 줄어든다 (이 이슈의 핵심 오해)

착수 전 피크 276은 **캐시에 동시에 살아 있는 컨텍스트 수(`maxSize` 32)에 묶여 있었다** — 276 ≈ 풀 28개 × 10개다.
총 갈래를 줄여도 DataSource 컨텍스트가 32개 이상 남아 캐시를 채우는 한 피크는 그대로다. 실제로 82 → 64로
줄였을 때 피크는 276 → 205에 그쳤다(풀이 50 → 32로 줄어 캐시를 딱 채우는 선까지 내려온 만큼만).

**보유량을 없앤 것은 `minimum-idle=0` + `idle-timeout=10초`다** (테스트에만, `build.gradle`). 노는 컨텍스트가
커넥션을 반납한다. `maximum-pool-size`는 기본 10 그대로라 **동시성 테스트는 여전히 10개를 실제로 동시에
쓴다** — 이슈 #121이 우려해 PR #133이 기각했던 "풀 축소"와는 다른 레버다. 커넥션 고갈을 직접 재고 단정하는
`FeedbackQueryCacheConnectionHoldingIntegrationTest`가 초록인 것으로 확인했다.

그래서 **`--max-connections=1000`을 제거하고 MySQL 기본값 151로 되돌렸다.** PR #133이 남긴 부채가 끝났다.

### 통합이 검증을 줄이지 않았다는 증거

말로 때우지 않고 두 번 망가뜨려 red를 봤다.

1. `OrderBuyIntegrationTest`의 `clock.set(BASE_NOW)`를 빼면 **5건 중 3건 red** → 클래스별 시각 세팅이 실제
   방어선이다(공유 시계의 유일한 새 위험이 여기다).
2. 프로덕션 `StockReplayService`의 `now(clock)`을 실시각으로 바꾸면 `JournalListIntegrationTest` 13건 중 7건,
   `OrderBuyIntegrationTest` 5건 중 3건 red → 시각 고정이 통합 후에도 여전히 무언가를 지킨다.

둘 다 원복했다.

### 하지 않은 것

- **`@WebMvcTest` 31갈래는 건드리지 않았다.** 각자 다른 컨트롤러를 겨냥한 슬라이스라 갈리는 게 정상이고,
  DataSource가 없어 커넥션과 무관하다. 합치려면 모든 컨트롤러를 한 컨텍스트에 올려야 해 격리가 줄어든다.
- **여러 워크트리 동시 빌드에서의 접속 피크는 재지 않았다.** 16은 단독 실행 1회 값이다.

## 이슈 #273 — "코인 뉴스만 0건"이 아니라 "로컬은 애초에 수집하지 않는다" (2026-08-08)

### 이슈의 전제를 먼저 깨야 했다

이슈는 "주식 192건은 정상 적재, 코인만 0건"을 전제로 코인 쪽 후보 넷을 남겼다. 그 전제를 확인하려고 로컬 MySQL을
직접 봤더니 **192건이 전부 같은 시각(`2026-08-04 12:20:52.899050`)** 이고 종목당 정확히 12건씩 균일했다. URL은
`https://news.example.test/198/...` — **이슈 #198(LLM 응답 시간 실측)의 QA 픽스처**다. 실수집 분포가 아니다.

즉 이 DB에서 수집 배치가 저장에 성공한 적이 한 번도 없다. **주식이 되고 코인이 안 된 것이 아니라 둘 다 0건**이고,
주식에만 픽스처가 남아 있어서 비대칭으로 보였을 뿐이다. 이슈 본문 SQL의 `GROUP BY`만 봐서는 이 구분이 안 된다 —
`created_at`과 `url`을 같이 봐야 픽스처인지 수집분인지 갈린다.

### 후보 넷은 추측으로 지우지 않고 실측으로 지웠다

레포에 이미 있는 측정 도구(`tools/coin-news-measure/fetch.py` + `CoinNewsFilterMeasurementTest`)를 그대로 썼다.
실제 네이버 호출로 12종목 **1,200건 수신**, 운영 `NewsSearchQueryBuilder`·`NewsTitleFilter`를 통과한 것이 **275건**
(2026-08-07 스냅샷 261건과 같은 자릿수). 검색어도 필터도 코인에서 0을 만들지 않는다. 저장 단계는 애초에 도달하지
않는다 — `NewsCollectionService.save`가 `collected.isEmpty()`에서 즉시 반환한다.

### 왜 문서 정정이 아니라 프로필을 열었나

`.env.example`은 이미 "수집기 선택은 키 유무가 아니라 프로필이 가른다"고 정확히 적어 두고 있었다. **문서가 틀린 게
아니라 문이 없었다.** `oauth-real`·`crypto-real`은 있는데 뉴스만 없어서, 로컬에서 실기사를 보려면 `prod`로 띄우는
수밖에 없었다(그건 배포 설정을 로컬에 끌어오는 일이라 아무도 하지 않는다). 그래서 같은 컨벤션으로 `news-real`을
추가했다 — 기본 로컬·테스트는 그대로 Fake라 "키 없이 기동·`build` 정상"(spec §실패 처리)은 건드리지 않는다.

### 남는 한계

- **공시(DART)는 `news-real`로 바뀌지 않는다.** 이슈 제외 범위라 손대지 않았다. 로컬에서 공시까지 실데이터로
  보려면 별도 판단이 필요하다 — 지금은 `news-real`을 켜도 공시만 Fake라는 절반 상태다.
- **`price_move_events` 0건은 판정하지 않았다.** 뉴스가 선행 조건이라 같이 풀릴 수 있지만 별개 원인일 수 있다.
- 코인 요약·브리핑은 뉴스가 쌓인 뒤 배치가 한 번 더 돌아야 `READY`가 된다. 뉴스 적재 직후 즉시 `READY`가 아니다.

## 2026-08-09 — 코인 매도 회고 열기 (이슈 #275, 1단계 결정)

이슈 #275의 본체는 구현이 아니라 **2차에서 미정으로 남긴 4개 항목을 결정하는 것**이었다. 결정과 근거는
`ai/specs/012-ai-feedback/spec.md` §FEED-012에 정본으로 적었고, 여기에는 **왜 그 선택지가 남았는지**만 남긴다.

### 결정을 좁힌 관찰 두 가지

1. **주식 15:30 게이트의 존재 이유는 스포일러 차단이다.** 재생 서비스라 매도 후 가격이 "아직 오지 않은 미래"이고,
   그걸 보여주면 재매수 판단에 답을 아는 상태가 된다. **코인은 실시간이라 그 위험이 아예 없다.** 그래서 게이트가
   필요한 이유는 "종가·집단 집계가 언제 확정되는가" 하나로 줄어든다 — 게이트를 옮기는 문제가 아니라 **하루의 끝을
   정의하는 문제**였다.
2. **코인 체결은 `stockReplaySession`이 `null`로 강제된다**(`Trade.validateStockReplaySession`). 주식 경로가
   원본 거래일 ↔ 서비스 날짜를 오가며 하는 변환이 코인에서는 전부 항등이다. 그래서 `sameSessionCompleted`·
   `service_date` 같은 재생 전용 개념을 **코인에서 어떻게 읽을지**를 따로 정해야 했다.

### 사용자 결정 (2026-08-09)

- **하루의 끝 = KST 자정.** 후보였던 "매도 후 24시간"은 `atClose`가 회원마다 다른 시각이 되어 종가라 부를 수 없고,
  집단 비교를 매시 롤링으로 재집계해야 해 배치 모델이 무너진다. "게이트 없음"은 정보 관점에서는 정당하지만
  `counterfactuals`가 코인에서만 2키가 되어 프론트 고정 3키 전제를 깬다. **자정 안은 코인 일봉이 이미 그 경계로
  존재해 "그날"의 정의를 우리가 만들지 않아도 된다는 점**이 결정적이었다.
- **200분 초과 보유의 극값은 일봉으로 근사.** "null로 비우기"도 제시했으나(이 spec의 기존 원칙 —
  틀린 사실 대신 없음을 낸다), 코인은 24시간 거래라 장기 보유가 흔해 반사실 표가 대부분 비게 된다는 점에서
  근사를 골랐다.

### 근사를 "거짓말하지 않는 근사"로 좁힌 방법

일봉 근사를 그냥 "매수일~매도일 일봉 close 최댓값"으로 두면 **매도일 일봉의 close(매도 다음날 자정)가 보유하지
않은 구간의 가격**이라 "안 팔았다면 얻을 수 있었던 값"이 아니게 된다. 그래서 **매수 시각 이후·매도 시각 이전에
확정된 일봉 close**만 쓰도록 좁혔다(= 매수일 ~ 매도 전날). 남는 값은 전부 보유 중 실제로 존재했던 가격이므로
**표본이 성길 뿐 틀리지 않는다** — 결과는 실제 최고가 **이하**다. 이 성질을 계약에 적고 `holdHighBasis`를 응답에
실어 화면이 근사 여부를 알 수 있게 했다.

같은 이유로 **`postSellHighPrice`와 `atFirstMoveAfterBuy`에는 일봉 근사를 쓰지 않는다.** 전자는 그 일봉이 매도
전 시간대를 통째로 포함해 근사가 아니라 오답이 되고, 후자는 특정 분의 종가라 일 단위 표본으로 대체할 대상이
아니다. **"근사 가능"과 "근사하면 안 됨"이 한 응답 안에 섞여 있다는 점이 이 결정에서 가장 놓치기 쉬운 자리다.**

### 계약을 시장별로 가르지 않은 이유

이슈가 명시적으로 물은 항목이다. 결정 결과 갈라지는 것은 **값뿐이고 키 집합이 아니다** — `counterfactuals` 3키,
`PostSellFeedbackStatus` 4값이 그대로다. 새로 더한 `holdHighBasis`도 주식·코인 공용으로 뒀다(주식은 언제나
`"MINUTE"`). 코인에만 필드를 더하면 프론트가 시장으로 분기해야 하는데, **그 분기는 한 번 생기면 이후 모든 필드
추가가 같은 질문을 다시 받는다.**

### 새 ADR은 만들지 않았다

새 크론 1개(`crypto-peer-stats-cron`)는 기존 배치 5개와 같은 모양이라 결정할 구조가 없고, Redis 보존기간도 그대로다
(정밀도를 가르는 쪽을 골랐기 때문에 캐시를 늘릴 필요가 없었다). **200봉 상한을 조정하거나 코인 분봉을 장기
보관하기로 하면 그때는 인프라 결정이므로 ADR을 다시 판정한다.**

## 2026-08-09 — Issue #280 price-moves 상태 필드

### B안(`status`)을 고른 이유

이슈는 A안(`market` 필드)과 B안(`status` enum)을 동등하게 제시했고 A가 변경 범위가 더 작다. 그런데도 B를 고른 것은
**A가 판별을 호출부의 추론에 남기기 때문이다.** `market=STOCK`이고 `originTradeDate=null`이면 "재생세션 미준비"라는
추론은 계약 문서를 읽어야 성립하고, 그 추론은 응답 자체가 말해주는 것이 아니다. 이슈가 지목한 문제가 정확히
"응답이 자기 완결적이지 않다"였으므로 `market`은 결합의 위치만 옮긴다.

`FeedbackContentStatus`를 새로 만들지 않고 재사용했다 — 형제 세 경로가 이미 이 열거형을 공유하고, `postSellFlow.status`가
4값 중 2값만 쓰는 선례가 있다(§C-4). **네 값 중 `UNAVAILABLE`이 빠지는 것은 의도된 차이라 spec §C-4에 적었다** —
요약·브리핑은 템플릿이 없어 LLM 실패 시 서술이 `NULL`로 남지만 카드는 템플릿으로 대체되므로 서술 없는 카드가
존재하지 않는다. `NOT_YET`이 코인에 안 나오는 것도 `summaryStatus`와 같은 이유다.

### `status`를 호출부가 넘기지 않게 했다

`of(originTradeDate, moves)`가 `moves.isEmpty()`로 `status`를 직접 정한다. 인자로 받으면 목록과 상태가 어긋난 응답을
만들 수 있고, 그 불일치는 컴파일러도 테스트도 안 잡는다. `NOT_YET`만 별도 팩터리(`notYet()`)로 두면 그 값이 나올 수
있는 자리가 서비스의 재생세션 분기 한 곳으로 고정된다.

**`empty()`를 `notYet()`으로 개명한 것이 이번 변경의 핵심 신호다.** 기존 이름이 "값이 비었다"만 말해서 주식 미준비와
코인 카드 0건이 같은 팩터리를 쓰고 있었고(`PriceMoveQueryGateIntegrationTest`의 코인 테스트가 실제로
`PriceMoveListResponse.empty()`와 비교하고 있었다), 그것이 두 상황이 구별되지 않는다는 사실을 테스트가 오히려
고정하고 있던 자리다.

### 회귀를 세 층위로 나눠 고정했다

`status`가 빠지면 두 응답의 JSON이 값까지 같아진다. 그래서 WebMvc 테스트는 필드 단정이 아니라 **응답 문자열 두 개를
직접 비교**한다 — 필드 단정만 두면 `status`를 지웠을 때 그 단정만 지우면 통과하는 테스트가 된다. 실 DB에서 두 상황을
나란히 만드는 것은 `PriceMoveQueryGateIntegrationTest`가 유일하게 할 수 있어 거기에 대조 테스트를 넣었다.

**처음에는 근거를 "`null`이면 필드째 사라지므로"라고 적었는데 틀렸다** (PR #283 리뷰에서 지적, 2026-08-09).
`PriceMoveListResponse`에는 `@JsonInclude(NON_NULL)`이 없고 전역 inclusion 설정도 없다 — 이 레포는 그 애노테이션을
SSE DTO 3개(`MarketPriceEvent`·`MarketStatusEvent`·`MarketSnapshotEvent`)에 클래스 단위로만 붙인다. 실제 응답은
`{"originTradeDate":null,...}`로 키가 남는다. 결론(두 응답이 같았다)은 그대로지만 **계약 문서에 잘못된 기전이 남으면
프론트가 "키 부재"로 분기할 여지가 생기므로** 문서 3곳을 고쳤다.

기존 테스트가 이 오해를 걸러내지 못한 이유가 있다. `jsonPath(...).doesNotExist()`는 **JsonPath가 `null`을 부재와 같게
다뤄 두 경우 모두 통과한다** — `@DisplayName`이 "필드가 사라지지 않는다"인데 단정은 정반대를 말하고 있었고 그래도
초록이었다. 그 자리를 `content().string(containsString("\"originTradeDate\":null"))`로 바꿔 키가 남는다는 사실 자체를
고정했다.

### `ai/prd.md`는 건드리지 않았다

§3 구현 현황 193·197행이 이미 **완료**이고 제공하는 기능이 그대로다 — CLAUDE.md 규칙 10의 "갱신 비대상"(제공 기능이
같은 변경)에 해당한다. **판정이 바뀌지 않는데 근거 칸에 PR 번호만 덧붙이면 표의 근거가 무엇을 가리키는지 흐려진다.**

### 리뷰 3라운드 동안 차단이 계속 나온 이유 (2026-08-09)

코드는 1라운드 이후 한 글자도 바뀌지 않았다 — QA PASS 13/FAIL 0이고, 3라운드에서 리뷰어가 `d44bc30`과
`ecf73fc1`의 소스 blob SHA를 대조해 다섯 파일 모두 동일함을 확인했다. **차단·권장은 전부 내가 쓴 문서
문장에서 나왔고, 세 번 다 같은 종류다 — 전칭 명제를 확인 없이 단정한 것.**

라운드 번호는 **그 문장이 차단으로 드러난 라운드**다(문구가 처음 들어온 라운드가 아니다).

| 라운드 | 내가 쓴 문장 | 실제 |
|---|---|---|
| 1 | "`null`은 필드째 빠진다" | `@JsonInclude`가 없어 키가 남는다 |
| 2 | "개장했지만 첫 `revealTime` 전에 Part C는 `NOT_YET`" | `NOT_YET`은 09:00 이전 **또는 재생세션 미준비**에서 나온다 |
| 3 | "개장 후에는 두 값이 갈리지 않는다" | 09:05에 Part C `READY`+카드 `EMPTY`가 정상 |
| 4 | "개장 후에는 Part C가 `NOT_YET`이 될 수 없다" | 세션 미준비 분기에는 시각 조건이 없다 (`InstrumentNewsQueryService:81`) |
| 5 | "08:45 준비 배치가 실패한 날" | 08:45는 피드백 배치다. 세션 확정은 08:40 `StockReplaySessionScheduler`이고, 피드백 배치는 세션을 읽기만 한다 |

**5번은 리뷰어가 지적문에 쓴 예시를 그대로 옮긴 것이다** (리뷰어도 5라운드에 "근거를 확인하지 않고 배치 이름을
적었다"고 정정했다 — 다만 그 문장을 계약 문서에 들인 판단은 내 몫이다). 1·3라운드에서도 같은 일이 있었고,
PR #287 답변에 "제안 문구도
검증하고 반영한다"고 적어 놓고 바로 다음 문장에서 또 그랬다. **남이 준 문장이라도 내 문서에 들어가면 내 주장이다** —
크론·시각·배치 이름을 예시로 들 때는 그 크론 문자열을 grep해 소유 클래스를 확인한다(`grep -rn "0 45 8"`이면 5초다).

1~4번은 **"~뿐이다"·"~하지 않는다" 형태**다. 근거로 삼은 사례는 맞았는데 그 사례가 전부라고 단정한 자리에서
틀렸다. **4번은 3번을 고치면서 새로 들어왔고, 이 표의 2행 자체도 같은 과일반화를 담고 있었다** — 재발 방지
메모가 재발의 근거가 되고 있었다는 뜻이라 4라운드에 함께 고쳤다. 2라운드는 리뷰어의 부정확한 지적을 그대로 반영한 탓도 있지만, **같은 문서 안의 다른 절(`api-contracts.md`의 "종목 뉴스 목록·요약 조회")과
대조하지 않은 것은 내 몫이다.**

**다음 세션에 남기는 규칙.** 계약·spec에 `한 곳뿐`·`항상`·`절대`·`~이 아니다`를 쓸 때는 그 주장을 만드는
코드 분기를 grep으로 전수해 근거 줄 번호를 함께 적는다. 전수하지 못하면 범위를 좁혀 쓴다(예: "두 값이 갈리지
않는다"가 아니라 "이 조합은 다시 나오지 않는다"). **이 문서들이 블랙박스 QA의 근거라 과한 단정 하나가
다음 QA의 잘못된 FAIL로 이어진다** — 3라운드 차단이 정확히 그 위험으로 분류됐다.

## 2026-08-12 — 이슈 #345 완전 자동 배포(CD) 결정

### "자동 배포 금지"를 뒤집은 게 아니라, 한 줄에 묶여 있던 둘을 나눴다

자동 배포는 네 문서에서 제외돼 있었다(ADR-0005 §한계, ADR-0013 §범위 밖, 010 spec, `deploy/README.md`).
그런데 **그중 어디에도 "배포 자동화가 위험하다"는 판단은 없었다.** ADR-0005의 논점은 API 키 없이
에이전트를 서버에서 못 돌린다는 것이었고, ADR-0013은 이슈 트리거 하네스의 범위 선언이며, 거기서
"자동 배포·자동 머지"가 **한 줄에 함께** 적혔을 뿐이다.

그래서 ADR-0021은 그 줄을 둘로 나눈다 — **머지 버튼은 계속 사람이 누르고, 그 이후는 사람이 손대지 않는다.**
자동 머지 금지는 그대로 유지했고, 두 ADR의 표기도 "자동 배포만 대체, 자동 머지는 유지"로 명시했다.
이렇게 적지 않으면 다음 사람이 ADR-0021을 근거로 자동 머지까지 열 수 있다.

### 그 과정에서 확인한 것 — ADR-0005는 자동 배포를 금지한 적이 없다

`grep -n "머지\|배포" ai/adr/0005-local-agent-orchestration.md` 결과가 **0건**이다. 그런데 010 spec은
"자동 배포·자동 머지는 하지 않는다 **(ADR-0005)**"로, ADR-0013은 "ADR-0005의 '자동 머지 금지' 원칙 유지"로
그 문서에 귀속시켜 왔다. **인용이 원문보다 넓었던 자리다.**

자동 머지 금지는 실체가 있다 — ADR-0013 §승인·머지가 직접 결정했다. 그래서 ADR-0021은 "ADR-0013의
자동 배포 제외를 대체한다"로 적고, ADR-0005에는 **상태 줄에 이 사실만 주의로 남겼다**(본문은 건드리지 않았다).
ADR-0013 본문의 잘못된 귀속 문장은 이번 PR에서 고치지 않았다 — ADR 본문 수정이라 판단을 사람에게 남긴다.

### 진짜 동기는 "블루-그린이 수동 배포의 성격을 바꿨다"는 것이다

단일 스택 시절 수동 배포는 명령 한 줄이라 틀릴 자리가 거의 없었다. 블루-그린은 사람이 매번 지켜야 하는
순서가 다섯 단계이고, 그중 1번(**지금 어느 색이 라이브인가**)은 EC2가 아니라 ALB 리스너에만 있는 정보다.
1번을 틀리면 라이브 색을 재기동하게 되고, 그건 다운타임 있는 배포보다 나쁘다(요청이 재기동 중인 스택으로
계속 간다). **상태 판별과 순서 반복은 기계가 더 잘하는 종류의 일**이라는 게 이 결정의 실제 근거다.

그래서 §결정 5를 "파이프라인이 ALB 리스너를 읽어 색을 정한다 — 사람이 색을 입력하지 않는다"로 못박았다.
입력받는 순간 위 실패가 그대로 돌아온다.

### SSH를 안 쓴 이유는 취향이 아니라 ADR-0020 §5와의 충돌이다

ADR-0020 §5는 "네트워크 경계는 CIDR이 아니라 보안 그룹 참조로 정의한다"고 결정했다. **GitHub Actions
러너의 IP는 고정이 아니라서 그 규칙과 SSH가 양립하지 않는다** — SSH로 배포하려면 22번을 넓게 열거나
GitHub IP 목록을 따라다녀야 한다. SSM은 EC2가 아웃바운드로 붙는 방향이라 인바운드 규칙이 0개다.
대안 (b)를 기각한 근거를 "장기 크리덴셜이 싫어서"가 아니라 이 충돌로 적은 이유다.

### ECR 태그를 SHA로 고정한 것이 롤백 정의의 전제다

`compose.bluegreen.yaml`의 두 색은 지금 각자 `build: context: .`로 빌드한다. 즉 blue와 green이 서로 다른
소스에서 만들어질 수 있고, 그러면 "직전 색으로 롤백"이 **무엇으로 돌아가는 것인지 특정할 수 없다.**
`latest` 태그도 같은 문제를 만든다. 롤백 대상은 이름을 가져야 한다는 것이 §결정 4의 핵심이고,
§결정 6의 "자동 롤백은 직전 색까지"라는 좁은 정의도 여기서 나온다.

### 이번에 새로 생긴 제약 — 파괴적 마이그레이션 금지

롤백이 앱만 되돌리고 스키마는 되돌리지 않으므로(Flyway는 앞으로만 간다) **구버전 앱이 신버전 스키마에서
돌 수 있어야 한다.** 컬럼 삭제·이름 변경·타입 축소·NOT NULL 승격을 한 배포에 담으면 §결정 6의 롤백 경로가
"앱은 돌아가지만 데이터가 깨진다"가 된다.

이 제약은 **ADR-0021 안에만 적으면 지켜지지 않는다** — 마이그레이션을 쓰는 사람은 ADR-0004를 읽지
ADR-0021을 읽지 않는다(`ai/context-router.md`의 "엔티티/스키마 변경" 행이 0004만 지목한다).
그래서 ADR-0004 상태 줄 아래, `CLAUDE.md` 규칙 8, `AGENTS.md`에 같은 제약을 넣었다.
**규칙은 그것을 어길 사람이 실제로 읽는 문서에 있어야 한다.**

### `main`의 역할이 바뀐 것을 문서에 남겼다

`dev` 머지를 트리거로 고른 순간 `main`은 배포 소스가 아니게 된다. `docs/conventions/git.md`·`README.md`·
`AGENTS.md`가 전부 "`main`은 배포·시연용"이라고 적고 있었으므로 셋 다 "시연·심사 스냅샷"으로 고쳤다.
**트리거 선택의 부수 효과라 놓치기 쉬운 자리다** — 안 고쳤으면 문서가 배포 브랜치를 두 개로 말하게 된다.

동시에 대가도 같은 자리에 적었다: **`dev`에 머지된 것은 즉시 공개 URL에 뜬다.** 노출 통제 수단은
머지 시점 하나뿐이고, 그래서 머지 조건 절에 "머지 버튼이 곧 배포 실행 버튼"이라고 넣었다.

### 이 세션이 하지 않은 것

워크플로우 파일도, compose의 ECR 전환도, AWS 콘솔 설정도 하지 않았다. `deploy/cd-runbook.md` 머리말에
**"이 문서는 돌고 있는 파이프라인의 기록이 아니다"** 를 굵게 적은 이유다 — 이 저장소는 과거에 문서가
미실행 절차를 실행된 사실처럼 적어 리뷰에서 차단된 적이 여러 번 있다(2026-08-03·08-09 항목).
`ubuntu-24.04-arm` 러너 가용성도 "공개 레포라 쓸 수 있다"까지만 적고 **실행 확인은 미확인 항목으로 남겼다.**

## 2026-08-15 — AI 피드백에 투자일기 반영 (spec 012 §FEED-013, 문서만)

정책 변경으로 매도 직후 피드백이 "뉴스 기반 변동 원인 + 수익률"에 더해 **투자일기까지** 재료로 쓴다.
이 세션은 **spec 작성까지이고 코드는 한 줄도 바꾸지 않았다.**

### 결정과 이유

- **잠금이 아니라 재생성.** `007-journal`이 "AI 피드백이 일기를 읽게 되면 잠금이냐 재생성이냐를 그 spec에서
  정하라"고 남긴 후속 조건을 여기서 해제했다. 사용자 동선이 매도 → 피드백 조회 → 일기 작성이라
  "피드백 생성 후 잠금"은 **일기를 쓰기도 전에 잠기는** 규칙이 된다. `007-journal` 세 곳(JOUR-004 후속 조건,
  JOUR-002 "지금은 없음" 문단, 범위 제외)을 이 결정으로 갱신했다 — 편도 참조를 남기지 않는다.
- **트리거는 조회 시 대조.** 일기 저장 시 밀어 넣기는 ① 저장이 LLM 지연에 묶이거나 ② 비동기로 빼면
  "생성 중" 상태가 생겨 `narrativeStatus`는 항상 `READY`라는 §C-4 보장이 깨지고 ③ 매수 일기 1건이 매도 N건에
  걸려 아무도 안 볼 서술까지 만든다. 조회 시 지문 대조는 셋 다 피하고 **`journal` 도메인을 건드리지 않는다.**
- **카운터를 분리한다.** `narrative_finalized`는 흐름·집단 게이트 전용이다. 일기 판정에 얹으면 게이트를 이미
  통과한 체결에서 **일기가 영원히 반영되지 않는데 예외도 로그도 없다.** 그래서 `journal_fingerprint`·
  `journal_regenerations` 두 컬럼을 새로 둔다.
- **문장 수 3~4 → 6~8 (일기가 있을 때만).** 사용자 요청이며 근거는 "재료가 늘었는데 분량이 그대로면 반영할
  자리가 한 문장뿐"이다. 문장 수만 늘리면 모델이 반복으로 채우므로 **네 덩어리 구성 지시**를 함께 넣는다.
  `max-tokens`를 512 → 1024로 올린 이유는 잘린 문장이 §후검증을 통과해 **조용히 나가기** 때문이다.
- **일기가 없으면 3차 동작 그대로.** AI 서술을 일기 작성자 전용으로 좁히자는 안도 검토했으나, 이미 배포된
  기능을 줄이는 변경이라 택하지 않았다. 유인은 "안 쓰면 못 받는다"가 아니라 문장 수 차이로 만든다.

### 새로 생긴 위험

일기 본문은 이 spec에서 **처음 들어가는 사용자 자유 텍스트**다. 프롬프트 인젝션과 후검증 금지 표현의 반사
둘 다 대응을 시스템 프롬프트 2줄로 넣었고, **금지어 목록은 넓히지 않았다**(§후검증의 기존 원칙). 실제로 새는지는
§튜닝의 `TEMPLATE` 비율을 일기 유무로 나눠 세서 확인한다.

### 이 세션이 하지 않은 것

구현 전부(마이그레이션·`PostSellJournalReader`·프롬프트 조립·`journal`/`portfolio` 조회 경로), `application.yml`
값 반영, `docs/api-contracts.md`의 재생성 소절 갱신, `ai/prd.md` §3 행 추가. 마지막 둘은 **구현 PR과 같은
커밋에서** 해야 한다(CLAUDE.md 규칙 7·10) — 지금 미리 적으면 문서가 미구현을 구현된 것처럼 말하게 된다.

## 2026-08-16 — PR #381 dev 리베이스·리뷰 반영 및 요구사항 14개 검증, 배포 실패 발견

- **PR #381(튜토리얼 흐름 재설계)을 dev 위로 리베이스해 충돌 4건 해소, 머지 완료**: dev가 80커밋 앞서 있어 `CONFLICTING` 상태였다. 충돌은 전부 의미 보존 방식으로 해결 — `LimitOrderFillService.java`는 dev의 ADR-0025 배치 실행기 구조(`fillBatch`/`fillOnePending`)와 이 PR의 attempt/run 기반 canonical 가격 체결 로직을 병합했다(단일 `fillIfPending(orderId)`는 `LocalDateTime.now(clock)`으로, `fillIfPending(orderId, pricedAt)`는 `settleCurrentRun`이 명시 시각으로 호출). `docs/api-contracts.md`·`ai/prd.md`는 두 브랜치의 신규 문장을 모두 보존.
- **spec 번호 충돌을 `036`→`039`로 해소**: 리뷰(namdongyeob)가 예고한 대로 PR #380이 `036-remove-crypto-stale-status`를 먼저 차지했는데, 리뷰가 예상한 이동 대상 `037`도 그 사이 다른 PR(`037-limit-order-async-fill`)이 차지해 실제 빈 번호는 `039`였다. **번호 재조정은 머지 시점의 실제 dev 상태를 다시 확인해야 한다** — 리뷰가 남긴 이동 대상 숫자를 그대로 믿으면 틀릴 수 있다.
- **PR #381 review 권장사항 반영**: `PracticeOrderSettlementService.java` 헤더 주석에 `settleCurrentRun`(attempt/run 기반 정산) 책임 추가.
- **프론트 companion PR #30을 리뷰 0건·CI 없음 상태로 머지**(사용자 명시 지시 "리뷰는 나중에"): `finplay-frontend`는 PR에 CI 워크플로가 붙어 있지 않다(배포용 `deploy.yml`만 존재, push 트리거).
- **PRD 사용자 요구사항 14개(재시작 전 화면 무관 노출, 미체결 취소·예약 반환, 보상매도, 완료 불변, 사전의도 제거, 자동 -3%/+5% 위험 스냅샷, 단일 차트, 29+1 캔들, 12:00 가상 시작, 3초=1분, 새로고침 복원, 다시체험하기 재사용 등)를 백엔드·프론트 각각 서브에이전트로 검증**: 백엔드 12/12 PASS(코드+전용 테스트 근거), 프론트 8개 중 7 PASS·1건은 "기존 `TutorialReplay.tsx` 재사용"이 아니라 "삭제 후 `AttemptTutorialFlow.tsx` 안에 재작성"으로 확인됐지만, 옛 컴포넌트가 이번에 함께 삭제된 `IntentionStep`·`FavoriteStep`에 의존하고 있어 그대로 재사용이 애초에 불가능했던 상황 — 기능적 결함은 아니라고 판단.
- **⚠️ 발견: PR #381 머지 직후 백엔드 배포가 실패했고, 이후 머지(#371·#391·#392)도 전부 연쇄 실패해 프로덕션이 여전히 PR #380/#387 시점 코드로 멈춰 있다.** `gh run list --workflow=deploy.yml`로 확인 — `e671d083`(#381 머지 커밋) 배포가 컨테이너 헬스체크에서 `unhealthy` 판정으로 실패(블루-그린이라 라이브 색은 안 건드림, 롤백 불필요하나 신규 기능도 배포 안 됨). 사용자가 라이브 사이트(`finplay-frontend` S3)에서 튜토리얼을 테스트했을 때 "프론트가 반영 안 된 것 같다"고 느낀 원인은 **프론트가 아니라 백엔드 미배포**였다 — 프론트 PR #30은 정상 배포됐고(`deploy.yml` 성공, 08:46:46) 코드도 소스 레벨에서 전부 확인됨.
- **원인 미확정 — AWS 접근 권한 없어 컨테이너 로그 직접 확인 불가**: `deploy.yml`의 SSM 스텝은 `docker inspect Health.Status`만 폴링하고 실패 시 앱 stdout을 job 로그에 남기지 않는다. `application.yml`/`application-prod.yml` diff는 PR #381에서 **변화 없음**(설정 문제 가능성 낮음) — 유력 가설은 `V36__create_practice_attempts_and_risk_snapshots.sql`의 `orders` 테이블 `ALTER`(컬럼 2개+FK+CHECK+복합 인덱스 1건, 단일 statement)가 실제 프로덕션 `orders` 테이블 행 수 기준으로 헬스체크 타임아웃(`start_period 90s` + `retries 6 × interval 10s` ≈ 150s)보다 오래 걸렸을 가능성 — 확정하려면 EC2 SSM 또는 RDS 슬로우 쿼리 로그 확인이 필요하다(이 세션엔 `aws` CLI 미설치·자격증명 없음).
- **다음 조치는 사용자 판단 필요**: workflow_dispatch로 재배포를 재시도하거나(단순 타임아웃이면 두 번째 시도는 성공할 수 있음), AWS 콘솔/SSM으로 실패 시점 컨테이너 로그를 직접 확인해야 한다. 이건 프로덕션에 영향을 주는 배포 트리거라 사용자 확인 없이 재시도하지 않았다.

## 2026-08-19 — 041 1~3번: 튜토리얼 대본·생성기 V2·attempt 진행 컬럼 (이슈 #467)

- **새 attempt를 아직 V2로 올리지 않았다.** `PracticeAttemptService.GENERATOR_VERSION`은 `1` 그대로다. 커서를
  전진시키는 진행 계산이 041 4·5번이라, 지금 올리면 새 사용자의 가격이 0막 0분에 고정되고 지정가·OCO 정산도
  대본 위치를 못 읽는다. **전환은 tick 통합 PR에서 한 줄로 한다** — 그때 이 줄을 지운다.
- **생성기 V2 진입점을 `canonicalPrice(input, script, cursor)`로 뒀다.** 대본을 호출자가 넘기고 변환기는
  정적 순수 함수다. 진입점이 대본 로더에 의존하면 버전 1만 쓰는 기존 호출부(`PracticeAttemptChartService` 등)까지
  로더를 함께 들고 다녀야 해서 피했다. 5번에서 `PracticeAttemptCanonicalPriceService`가 로더를 주입받아
  `observedAt` 자리에 커서를 넣으면 된다.
- **`selectInstrument()`·`restart()`는 대본 컬럼을 `null`로 지운다.** plan은 "select에서 `IDLE_ENTRY`, 0으로"라고
  적었지만 엔티티에 대본 구간 id 리터럴을 박지 않았다. 버전 1 attempt도 같은 메서드를 타므로 의미 없는 값이
  들어간다. **`null` = 미시작으로 읽고, 4번의 진행 계산이 첫 tick에서 대본의 첫 구간으로 초기화한다.**
- ~~**`progress_updated_at`이 plan의 컬럼 표에 없다.**~~ **결정됨 — 이슈 #472에서 별도 컬럼
  `scenario_progress_updated_at`(V52)을 추가했다.** 아래 원문은 그 판단의 근거로 남긴다.
- **`progress_updated_at`이 plan의 컬럼 표에 없다.** §tick 알고리즘은 `remaining = min(now - progress_updated_at,
  MAX_TICK_GAP)`을 쓰는데 §데이터 모델의 컬럼은 5개뿐이고 그 안에 없다. 이번 PR은 문서대로 5개만 넣었으므로
  **4·5번이 컬럼을 하나 더 추가하거나 `updated_at`을 쓸지 정해야 한다.** `updated_at`은 attempt를 건드리는 모든
  경로가 갱신하므로 그대로 쓰면 delta가 짧아진다 — 별도 컬럼 추가를 권한다.
- **배율은 키프레임 보간 + 결정적 미세 진동(진행 구간 ±0.12%, 1막 ±0.08%)으로 만들고 구간 극값으로 clamp했다.**
  clamp 덕에 각 구간의 min/max가 문서 극값과 정확히 일치하고, 그 성질이 도달 부등식을 자동으로 보호한다 —
  예를 들어 루머 구간의 어떤 분도 0.975 밑으로 내려갈 수 없어 BALANCED가 소문 단계에서 털리지 않는다.
  생성 스크립트는 커밋하지 않았다(저작 도구). 배율을 손볼 때는 파일을 직접 고치고 정합성 테스트를 돌린다.
- **대본 파일에는 한 줄 한국어 헤더 주석을 넣지 못했다** — JSON은 주석을 허용하지 않는다(CLAUDE.md 규칙 6의 예외).
- **구간 경계 연속성(앞 구간 끝 배율 = 다음 구간 첫 배율)은 로더가 아니라 정합성 테스트가 검사한다.** plan이 기동
  검증 항목으로 넷만 열거해 그 범위를 넓히지 않았다. 대본이 하나뿐인 지금은 테스트가 같은 보호를 한다.

## 2026-08-19 — 042 1·2번: 프리셋 상수와 스키마 (이슈 #470)

- **도달 부등식 판정을 042 한 곳으로 모았다.** 041 1번이 `TutorialScenarioScriptIntegrityTest`에 프리셋 값을
  리터럴로 넣어 뒀고 042 tasks 1번은 "같은 대상을 반대편에서 검사한다"고 적었는데, 그대로 하면 같은 조건이 두
  곳에 남아 **한쪽만 고쳐도 초록이 유지된다.** 프리셋이 걸린 부등식은 전부
  `ExitPresetScenarioReachabilityTest`로 옮기고 041 테스트에는 대본 내부 성질만 남겼다. 041 tasks.md·checklist.md에도
  이동을 적었다 — 대본 배율을 손보는 사람이 041 문서만 읽기 때문이다.
- **비율은 분수가 아니라 퍼센트 수다**(3%는 `3`). `ReferencePriceCalculator.calculateFromPercent`가 내부에서
  100으로 나누므로 `0.03`을 넘기면 예외 없이 100배 틀린 값이 나온다. 이 메서드는 이번이 첫 production 사용이다.
- **`calculateFromPreset`이 체결가를 scale 8로 먼저 반올림한다.** EXITPRESET-002의 "현행과 정확히 같은 값"이
  현행 코드의 선반올림 위에 서 있어서, 전제를 호출자에게 맡기면 4번에서 조용히 깨진다.
  **⚠️ 5번(자동 예약)이 부를 `ExitPricePolicy`의 PERCENT 경로는 선정규화를 하지 않는다** — 그쪽에 넘기는
  체결가도 scale 8이어야 화면 기준선(snapshot)과 실제 체결선(`exit_plan_conditions`)이 갈리지 않는다.
- **plan에 없는 것 둘을 더했다.** `exit_preset` 값 집합 CHECK(V38의 `market`·`status` 방식)와
  `idx_exit_plans_practice_attempt_run_status`(6번의 PENDING 예약 조회용). 인덱스를 FK보다 먼저 만들어 여분
  단일 컬럼 인덱스가 남지 않게 했고, 그것을 스키마 테스트가 단언한다.
- **프리셋 표시 이름(조심스럽게·보통·느긋하게)은 만들지 않았다.** plan §API 계약의 `availableExitPresets`가
  식별자·비율만 내려보내므로 서버가 쓰지 않는 문구를 열거형에 두지 않았다. **3번에서 응답 계약과 함께 정한다.**
- ~~**`GENERATOR_VERSION`은 `1` 그대로다**(041 4·5번 소관).~~ **이슈 #472에서 전환했다** — 다만
  시장을 가리지 않고 2를 주는 것이 아니라 **대본이 저작된 시장(CRYPTO)만**이다. 아래 원문은 유지한다.
- **`GENERATOR_VERSION`은 `1` 그대로다**(041 4·5번 소관). `ai/prd.md` §3도 갱신하지 않았다 — 제공 기능이
  그대로이고 EXITPRESET 행 신설은 042 7번 소관이다.
- **`SNAP-2` 체크박스가 실제 상태와 어긋나 있어 바로잡았다** — V49로 이미 머지됐는데(PR #458) `[ ]`로 남아
  있었다.


## 2026-08-19 — 041 4·5번: 진행 계산 서비스와 tick 통합·시간 게이트 제거 (이슈 #472)

- **`GENERATOR_VERSION`을 시장 조건부로 올렸다 — 문서와 다른 유일한 실질 변경이다.** plan·tasks는
  "2로 올린다"라고만 적었는데 저작된 대본이 CRYPTO 하나뿐이라, 시장을 가리지 않고 주면 STOCK 튜토리얼이
  모든 가격 조회에서 `IllegalArgumentException("대본이 저작되지 않은 시장입니다: STOCK")`으로 터진다.
  `PracticeAttemptCompletionFlowIntegrationTest`의 STOCK 파라미터가 실제로 잡았다 — **단위 테스트는 전부
  초록이었다.** `TutorialScenarioScriptLoader.hasScript(market)`로 판정하므로 STOCK 대본(SCENARIO-024)이
  들어오면 판정이 자동으로 따라간다. 호출부에 시장 목록을 두면 대본이 추가될 때 그 목록을 함께 고치지
  않아 조용히 버전 1에 머문다.
- **`canonicalPrice` 커서화를 택했다.** 오버로드 대안은 `lockForFill`이 여전히 `pricedAt`에서 가격을
  파생하므로 그 경로까지 고쳐야 하고, 그러면 `order`의 공개 계약만 넓어진다. 커서화는 시그니처를 유지한
  채 내부 파생만 바꾼다. 판정 근거 전문은 041 `run-log.md`에 있다.
- **`remaining`을 clamp하는 것과 기준 시각을 미는 것을 분리했다.** clamp되지 않은 tick은
  `progressUpdatedAt + 경과 초`로 밀어 1초 미만 나머지를 다음 tick으로 넘긴다. `now`로 밀면 3초의 배수가
  아닌 간격에서 매 tick 나머지가 버려져 대본이 조금씩 느려진다 — 초 단위 누적을 택한 이유(2초 간격 tick)와
  같은 종류의 결함이 한 층 아래에 또 있다. clamp된 tick만 `now`로 민다(안 그러면 30초 clamp가 무의미해진다).
- **대기 탈출 이동을 절단보다 먼저 한다.** "체결 시각 기준으로 delta를 자른다"를 그대로 구현하면 체결이
  방금(= now) 일어난 경우 남은 시간이 0이라 그 tick에서는 이동조차 하지 않아 사용자가 한 tick을 더
  기다린다. 이동 자체는 시간을 소비하지 않으므로 먼저 하고, 남은 시간이 0이면 진행 구간 0분에서 멈춘다.
- **순보유수량은 엔티티가 아니라 스칼라 쿼리로 읽는다.** `ExitPlanFillService`·`ExitPlanCancelService`가
  `flush()` 후 `detach(holding)`을 하므로 엔티티를 캐시하면 낡은 수량을 읽는다. JPQL 스칼라 프로젝션은
  flush 후 DB에서 읽어 detach와 무관하고, "매 분 전체 체결 재스캔 금지"도 함께 만족한다
  (`HoldingRepository.findQuantityByOwnerAndInstrument`, 가상 분당 인덱스 조회 1회).
- **`virtualDateTime`의 버전 2 정의는 plan에 없다.** 벽시계 파생을 그대로 뒀다 — 커서에서 파생하면 대기
  루프에서 표시 시계가 되감긴다. clamp된 만큼 표시 시계가 커서보다 앞설 수 있다는 사실을
  `docs/api-contracts.md`에 적었다. **사건의 `revealedAtVirtualMinute`을 내리는 041 6번이 이 값을 다시
  봐야 할 수 있다** — 사건 공개 시점은 커서 기준인데 화면 시계는 벽시계 기준이기 때문이다.
- **`ai/prd.md` §3 SANDBOX 행을 갱신했다.** 처음에는 `041 tasks.md` 7번에 미뤘는데, 1차 리뷰가 "라이브
  엔드포인트의 제공 동작이 바뀌었으므로 규칙 10의 갱신 대상"이라고 지적해 반영했다. 행을 다시 쓰지 않고
  근거 칸 끝에 한 문장만 덧붙였다 — CRYPTO 버전 2는 마감 폐지, STOCK·legacy chain은 유지. **7번에 남은
  것은 SCENARIO 행 신설뿐이다.**

### 041 4·5번 질문지에 대한 사용자 결정 (2026-08-19)

- **화면 가상 시계는 벽시계 파생을 유지한다.** 커서 기반으로 바꾸면 대기 루프에서 시계가 되감긴다.
  **041 6번은 사건에 절대 시각을 붙이지 말고 상대 표현("방금", "조금 전")으로 내린다** — 그러면 두 시계를
  맞출 필요 자체가 없어진다.
- **대본 종료를 사용자에게 알리는 것은 041 6번의 `scenarioStage=FINISHED`로 한다.** 프론트에 완료 축하·
  500만원 지급 화면이 이미 있으므로 그 화면을 이 값에 연결만 하면 되고 서버에 새 필드가 필요 없다.
- **튜토리얼에서 지정가 주문을 막지 않는다 — 이전 판단을 정정한다.** 기획 2단계가 시장가·지정가를 직접
  해보는 자리이고 그래프 위에서 체결을 눈으로 확인하게 하는 것이 목적이다. 지정가를 빼면 학습 목표가
  사라진다. **대신 교착(대기 밴드 밖 지정가가 영구 미체결) 완화는 "현재가에서 너무 먼 지정가를 주문 접수
  시 거부"로 한다** — 대본이 도달할 가격인지로 판정하면 4막 저점(7,900원)이라는 미래 정보가 샌다.
- **보유 목록 불일치는 별도 이슈가 필요 없다.** 2차 리뷰가 "`GET /api/holdings`가 튜토리얼 종목을 그대로
  노출한다"고 했는데 **부정확하다** — 033 SANDBOX-EXCL-001이 이미 그 목록에서 제외하고 있고 주문·체결
  내역도 같다. attempt 경로(차트·주문 접수·체결·관찰·재시작 보상매도)는 전수 확인 결과 전부 대본 가격을
  쓴다. **사인파가 남은 자리는 042의 `ExitPlanCreationService` 예약 기준가 하나뿐이고 041 plan이 이미
  042에 배정해 뒀다.**
- **STOCK 대본은 만들지 않고 주식 튜토리얼 진입을 잠시 막는다.** 시간이 남으면 푼다. **차단은 이 PR이
  아니라 다음 PR로 한다** — 이 diff는 리뷰를 네 번 받았고, 차단은 성격이 다른 동작 변경인 데다 "막혔을 때
  무엇을 보여줄지"를 프론트와 맞춰야 한다. **`dev` 머지가 곧 배포이므로 차단 PR을 먼저 준비해 두고 둘을
  연달아 머지하는 편이 노출 공백을 줄인다.**

## 2026-08-20 — 042 3~7번: 프리셋 선택 API·자동 OCO 예약·tick 정산 (이슈 #477)

- **순보유수량은 `holdings` 행이 아니라 실행 세대의 체결 원장에서 읽는다.** 이것이 이번 작업에서 가장
  중요한 정정이다. holding 수량은 실행 세대를 넘어 누적되므로, 재시작이 청산하지 못한 이전 보유가 있으면
  **새 실행의 첫 매수인데도 "이미 들고 있다"로 판정돼 손절·익절 기준선이 만들어지지 않는다.** 단위 테스트는
  전부 초록이었고 통합 테스트가 잡았다. `TradeService.netFilledQuantity`가 그 한 곳이며 042의 프리셋
  잠금·진입 가드와 041의 대기 구간 탈출 판정이 함께 쓴다. **041이 만든
  `HoldingService.findNetQuantity`는 지웠다** — 041 4·5번 당시의 판단이 이 발견으로 뒤집혔다.
- **매도 전 예약 취소를 `PracticeOrderAttributionPort`에 두면 순환 참조가 된다.** 정산 서비스 → 체결
  서비스 → 포트 → 정산 서비스로 돌아 컨텍스트가 아예 뜨지 않는다. 호출부가 `lockForOrder`로 이미 받은
  귀속 정보를 쓰면 포트를 넓히지 않고 해결된다.
- **`ExitPlanCreationService`(공용 엔진)를 직접 부른다.** 047이 넣은 샌드박스 차단은 호출부
  `ExitPlanService`에만 있고, 021 RISK-OCO-014가 교육 경로를 위해 엔진을 의도적으로 비워 뒀다.
  **누가 그 차단을 엔진으로 옮기면 042가 통째로 깨진다** — 근거를 코드 주석으로 남겼다.
- **예약 baseline에 대본 canonical price를 주입했다.** 엔진 기본 경로는 샘플 종목이면 031의 사인파 항시
  시세로 분기해 대본과 무관한 값을 `baseline_price`에 영속한다. 041 plan이 "042가 주입한다"고 배정한 항목.
- **진입별 대조 배열은 041 6번으로 넘겼다. 새 이슈를 만들지 않았다** — 041 tasks 6번이 이미 그 배열을
  명시하고 있어, 같은 일이 두 문서에 적히면 한쪽만 고쳐도 초록이 남는다(042 1번의 도달 부등식과 같은
  문제다). **넘긴 대가**: 그때까지 재진입한 사용자의 완료 화면은 첫 매도만 가리킨다. 금액은 맞고, 틀리는
  것은 2막 손절 → 3막 익절이 손절 하나로 보이는 것이다.
- **`exitPresetLocked`에 기본값을 두지 않은 것이 실제로 값을 했다.** 컴파일러가 호출 지점 8곳에서 판단을
  강제해, 각 자리가 "여기서 잠금을 알 수 있는가"를 명시적으로 답하게 됐다. 계좌 잔고 3필드처럼 0으로
  흘려보냈다면 완료 replay·종목 미선택 경로가 조용히 false를 내렸을 것이다.

### 042 사전 리뷰가 잡은 것 (이슈 #477)

- **자동 청산 매도에 attempt 귀속을 붙여야 한다.** `ExitPlanFillService.executeMarketSell`이
  `Order.create(...)`를 쓰면 042의 모든 판정(순보유수량·진입 가드·매도 원인·재시작)이 그 매도를 못 본다.
  **이 결함은 "holding 수량 → 실행 세대 체결 원장" 전환이 드러낸 것이다** — 두 판정 모두 구멍이 있었고
  (holding은 이전 실행 잔여를 세고, 원장은 귀속 없는 매도를 놓친다), 올바른 해법은 귀속을 붙이는 것이다.
  `Order.createForPracticeAttempt`가 이미 있었다.
- **매도 전 예약 취소는 시장가·지정가 둘 다 필요하다.** 043이 튜토리얼 지정가 예약 카드를 계약으로 갖고
  있어 지정가 매도가 실제 경로다.
- **튜토리얼 예약을 일반 OCO 화면에서 걸러야 한다.** 042 전에는 `create` 차단 때문에 튜토리얼
  `exit_plans` 행 자체가 없어서 이 문제가 없었다. 목록 제외 + 취소 거부를 **호출부**에 뒀다(엔진에 두면
  042의 내부 취소가 막힌다 — 021 RISK-OCO-014와 같은 이유).
- **단위 테스트만으로는 이 종류를 못 잡는다.** 두 리뷰어가 각각 같은 결론을 냈다. 예약 원장이 얽히는
  경로는 042 plan이 이미 "통합 테스트가 정본"이라고 못박아 뒀는데 그걸 안 지킨 것이 원인이다.

## 2026-08-20 — 041 6~7번: 사건 노출·진입별 완료 대조·통합 완주 (이슈 #488)

- **사건에 절대 시각을 붙이지 않기로 사용자와 합의했다.** `virtualDateTime`은 `anchorAt` 기준 벽시계
  파생인데 사건 공개 시점은 **대본 커서** 기준이라 두 시계가 어긋난다. 시각을 내려보내려면 두 시계를
  맞춰야 하고, 맞추지 않은 채 내보내면 사건이 가격보다 앞서거나 뒤처져 보이는 조합이 생긴다. 대신
  **목록 순서가 곧 공개 순서**이고 화면이 "방금"·"조금 전"으로 그린다. **plan은 이 자리에
  `revealedAtVirtualMinute`을 적어 뒀지만 구현하지 않았다** — 그 값은 원점이 필요하고 원점을 정하는 순간
  두 시계를 맞추는 문제로 되돌아온다. spec SCENARIO-020의 "같은 가상 시간축"은 이 결정으로 완화됐다.
- **`causeStatus`는 막이 아니라 대본 구간으로 판정한다.** 2막은 세 구간(루머·속임수 반등·확정)이 같은
  `ACT2` 라벨을 쓰는데, 막 단위로 보면 루머의 원인이 열린 뒤 **속임수 반등 구간까지 `REVEALED`가 된다.**
  그러면 "원인 없는 변동"이라는 그 구간의 설계 의도(plan §사건 배치, SCENARIO-005·016)가 통째로 사라진다.
  구현 중에 발견해 회귀 테스트로 못박았다.
- **공개 판정에는 clamp하지 않은 분을 쓴다.** 가격용 커서는 마지막 구간에서 마지막 분으로 clamp되는데
  (FINISHED), 그 값을 공개 판정에 쓰면 그 구간의 사건 하나가 **영영 열리지 않는다.**
- **`priceAfterSell`의 기준이 완료 여부로 갈린다.** 진행 중에는 현재 대본가이고 완료 응답에서는 대본의
  마지막 진행 구간 끝 가격이다. 완료 값을 진행과 무관하게 두는 것이 SCENARIO-021의 요구이고(손절 뒤
  재매수하지 않고 나간 사용자도 같은 대조를 얻어야 한다), **진행 중에 종점 가격을 쓰지 않는 이유는 그것이
  4막 폭락을 2막에서 미리 알려주기 때문**이다 — 사건 노출 게이트보다 큰 누설이 된다.
- **`unrealizedPnlIfHeld`의 곱하는 수량은 매수 수량이 아니라 팔린 수량이다.** 빼는 항인 `soldBuyBasis`가
  **팔린 수량에 배분된** 매수원가라 두 항의 기준이 같아야 한다. 그리고 매도 수수료를 빼지 않으면 비교
  대상인 `realizedPnl`(수수료가 모두 반영된 원장 값)보다 가상 쪽이 항상 조금 유리해 보인다.
- **`PostSellArithmetic`을 재사용하지 못했다.** plan은 "재사용한다"고 적었지만 그 클래스는 feedback
  패키지 전용(package-private)이고 다른 도메인이다. `PracticeTradeResultCalculator`가 이미 같은 이유로
  "식·정밀도만 맞춘다"는 선례를 남겨 뒀고 그대로 따랐다. 시장별 수수료율은 이 레포에 이미 네 벌이 있으며
  (`OrderExecutionService`·`PostSellArithmetic`·`PracticeRunRestartOrderService`·`LimitOrderFeeCalculator`)
  **다섯 번째가 됐다** — 한곳으로 모으는 것은 이 PR의 범위를 넘어 손대지 않았고, 상수 주석에 출처를 적었다.
- **진입별 배열은 대본 여부와 무관하게 채운다.** 042의 재진입은 시장을 가리지 않으므로 버전 1 실행에도
  진입이 둘 생길 수 있고 "첫 매도만 보인다"는 결함이 그쪽에도 있다. 대본이 없으면 `priceAfterSell`이
  null이라 `unrealizedPnlIfHeld`만 비어 나간다.
- **진입 안에서는 첫 매도를 쓴다.** 실행 전체(`tradeResult`)가 첫 매도를 쓰는 규칙을 진입 범위로 좁힌
  것이다. 한 진입에 매도가 둘 이상 생기는 경로는 부분 수동 매도뿐이고, 그때도 `sellPrice`는 수량
  가중평균·`realizedPnl`은 합이라 금액은 전부 반영된다.
- **통합 완주 테스트에서 두 가지를 조정했다.** (a) 매수 수량을 1.0으로 올렸다 — 재진입 매수는 대본이
  0.87배까지 내려간 뒤라 0.5로는 종목 최소 주문금액 5,000원에 걸린다. (b) 공개 게이트 경계를 볼 때만
  tick을 9초로 잘게 썼다 — 30초(가상 10분)씩 밀면 공개 분을 지나쳐 "열리기 전"을 관찰할 수 없다.
  시간 분산 관찰(2분 이상 3회)은 벽시계를 대본보다 크게 밀어(70초) 만들었다. 초과분은 clamp돼 대본이
  건너뛰지 않는다.

## 2026-08-20 — 튜토리얼 5단계 진행 판정 (이슈 #502·#503)

- **`steps[]`를 확장하지 않고 `tutorialStageProgress`를 새로 뒀다.** 기존 `steps[]`는 026의 1~4단계
  (즐겨찾기→매수→관찰→매도/복기) 의미가 이미 박혀 있어, 같은 배열에 "주문 유형을 배웠는가"를 섞으면
  한 배열이 두 의미를 갖는다. 프론트가 새 필드 하나를 읽는 비용이 그보다 싸다.
- **자동 예약이 발동시킨 매도는 시장가 매도로 세지 않는다.** `ExitPlanFillService.executeMarketSell`이
  손절·익절 청산을 `OrderType.MARKET`으로 만들기 때문에, 원장의 주문 유형만 보면 **프리셋 손절만 당한
  사용자가 "시장가로 팔아봤다"로 판정된다.** `exit_plans.triggered_order_id`가 가리키는 주문을 제외한다
  — `PracticeExitPlanQueryService.findTriggeredSellOrderStatuses`가 그 집합을 이미 주므로 새 쿼리가 없다.
- **재시작 보상매도는 판정에 들어오지 않는다.** `PracticeAttemptRestartService`가 `attempt.restart()`
  **이전의** run 번호로 `cleanupCurrentRun`을 부르므로 그 `MARKET SELL`은 직전 세대에 귀속되고, 새 run의
  판정 범위 밖이다. 확인하고 넘어간 것이지 방어 코드를 넣은 것이 아니다.
- **E-1의 잔액은 비잠금 조회로 읽고 없을 때만 get-or-create로 떨어진다.** 처음에는 기존
  `getOrCreateForUpdate`를 재사용했으나(재시작 경로의 선례가 있었다) 사전 리뷰가 **계좌를 한 글자도
  바꾸지 않는 응답이 X 잠금을 건다**고 지적했다. 교착이 되지는 않지만(모든 경로가 attempt를 먼저
  잠근다) 지정가 취소·정정이나 예약 청산 정산과 경합해 대기한다 — attempt 잠금이 그 트랜잭션들을
  직렬화하지 못하기 때문이다. `TutorialAccountService.find`를 새로 두고 폴백만 남겼다.
- **프리셋 단계 판정은 "적용한 진입이 있는가"가 아니라 "골랐는가"다.** 전자는 두 방향으로 틀렸다.
  고르지 않은 사용자의 진입에도 snapshot에 기본값 `BALANCED`가 박히므로 앞 단계를 기본값으로 마친
  사용자가 **세 보기 중 "보통"을 고르는 순간** 재진입 없이 통과했고, 반대로 이미 통과한 사용자가
  다음 진입을 준비하며 프리셋을 바꾸면 통과가 취소돼 화면이 이미 연 단계를 되잠갔다. 필드 이름도
  `exitPresetSelected`로 바로잡았다 — "applied"는 진입까지 했다는 뜻으로 읽힌다.
- **`GET /api/education/practice`의 `attempt` 필드는 계속 0이다.** tick과 함께 폴링되는 경로라 호출마다
  계좌를 한 번 더 읽는 대가가 종목 선택(사용자가 한 번 부르는 호출)과 다르다.

## 2026-08-20 — spec 049 초안: 2단계 대본 분리와 순서 강제 (이슈 #479, 구현은 다음 세션)

- **#479의 "±3% 밖 지정가 거부"를 버렸다.** 가격 범위를 화면에 안내하는 순간 필요가 없어진다.
  거부는 정당한 지정가를 서버가 막는 경험을 주고 "걸어놓고 기다리는 것"이라는 지정가의 본질을
  가린다. 대신 서버가 대본의 가격 범위를 내려주고 프론트가 안내한다.
- **범위는 사건이 없는 대본에서만 내린다.** 041 대본에서 같은 값을 내리면 4막 폭락을 미리
  알려주는 셈이라 사건 공개 게이트(SCENARIO-015·020)를 정면으로 뚫는다. 권고가 아니라 규칙이다.
- **#479 원안에서 유일하게 바꾼 것은 길이·반복이다.** "가상 20분 1회 왕복" 대신 가상 20분 주기를
  여덟 번(가상 160분). 같은 모양을 되풀이해야 #479가 원한 "모든 사용자가 같은 장면을 본다"와
  "늦게 건 사용자도 다음 바퀴에서 체결된다"가 동시에 성립한다.
- **폭 12%는 3단계와의 대비 때문이다.** 30~40%로 키우면 3단계의 21% 폭락이 밋밋해 보이고
  프리셋 손절선(2·3·5%)이 우스워진다. 체결 확률은 폭이 아니라 범위 안내가 담당한다.
- **판정(#503)과 강제(049)를 나눈 것이 이 계열의 핵심 구분이다.** 판정만으로도 화면은 정확히
  그려지고, 강제는 주문 생성 경로에 새 검증을 넣는 일이라 spec이 필요했다.
- planner가 남긴 미결 셋(대본 종료 후 회복 수단, 완료 화면이 두 대본을 섞는 문제, 화면 게이팅과
  전환 트리거)은 spec 049 §미결과 #479 코멘트에 적혀 있다.

## 2026-08-20 — 2단계 대본에서 자동 OCO 예약을 만들지 않기로 했다 (spec 049, 이슈 #479)

- **손절·익절은 3단계에서 배우는 것이라 2단계에서 몰래 작동하는 것이 원래 이상했다.** 커리큘럼이
  결정 근거고, 실해는 대본 배율로 확인했다 — ±12%를 가상 20분에 도는 사인파에서 기본 프리셋
  `BALANCED`(−3%/+5%)는 **매수 6~9초 만에 발동한다.** 2단계 학습 목표가 "매수·매도 버튼을 직접 눌러
  보는 것"인데 몇 초 만에 자동 청산되면 목표 자체가 사라지고, 자동 청산 매도는 왕복으로 세지
  않으므로(#503) 다음 단계가 영영 안 열린다.
- **위험 기준선은 계속 만든다. 안 만드는 것은 예약뿐이다.** 기준선까지 빼면
  `PracticeAttemptEvidenceService.requireCurrentRun`이 던져 관찰·복기가 통째로 깨진다.
- **대안 둘을 버린 이유.** "2단계에서는 자동 매도도 센다"는 #503의 규칙을 뒤집고 사용자가 매도 버튼을
  한 번도 안 누른 채 통과하게 한다. "2단계 전용 폭을 둔다"는 예약이 걸려 있는데 절대 안 걸리는
  상태라 문제를 가리고, 3단계에서 배울 값과 다른 값이 2단계에 숨는다.
- **042의 "매수에는 항상 예약이 따라온다" 전제에 실제로 기대는 코드는 없었다.** 프리셋 잠금은
  순보유수량만 보고, 재시작·매도 접수의 예약 취소는 빈 목록 순회라 no-op이며, `PracticeSellCause.from`
  은 예약 없는 매도를 이미 `MANUAL`로 다룬다(STOCK 튜토리얼이 그 선례다). 전수 확인 표는 spec 049
  §비즈니스 규칙에 남겼다 — 다음 사람이 같은 조사를 반복하지 않도록.

## 2026-08-21 — 튜토리얼 진입 교착의 원인을 확정하고 INSERT 구문으로 고쳤다 (이슈 #491)

- **원인은 추정이 아니라 확인됐다.** 이슈는 "INSERT IGNORE 뒤 같은 키를 FOR UPDATE로 재잠그는 패턴"을
  추정으로 적어 두었고 `SHOW ENGINE INNODB STATUS`를 못 봤다고 남겼다. `PracticeAttemptEntryConcurrency
  IntegrationTest`(기존 행 + 동시 2건, 10라운드)로 결정적으로 재현한 뒤 root 커넥션으로 교착 리포트를
  떴다 — **두 트랜잭션이 `uk_practice_attempts_user_market`의 같은 레코드에 `lock mode S`를 HOLD한 채
  서로 `lock_mode X locks rec but not gap`을 WAIT**했다. 추정이 정확히 맞았다.
- **격리수준을 낮추는 방식(ADR-0028)으로는 안 고쳐진다.** 리포트의 `locks rec but not gap`이 근거다 —
  갭 락이 아니라 레코드 락이라 READ COMMITTED로 내려도 그대로 남는다. ADR-0028을 이 이슈에 그대로
  복사하지 않은 이유가 이것이다.
- **핵심은 "S는 공유라 둘이 동시에 쥘 수 있다"는 것이다.** 그래서 고친 것은 구문이 아니라 **순서**다 —
  잠금 조회를 앞에 두고 행이 없을 때만 INSERT한다. 흔한 경로(행이 이미 있음)가 X 하나로 끝나 승격
  자체가 사라지고, 진입마다 나가던 쓰기도 없어진다. 남은 경합 구간(행이 없어 둘 다 INSERT로 가는
  첫 진입)에서는 `ON DUPLICATE KEY UPDATE`가 처음부터 X를 잡아 거기서도 승격이 생기지 않는다.
- **`READ COMMITTED`는 이 순서 변경이 요구하는 짝이다.** REPEATABLE READ에서는 아무 행도 맞히지 못한
  `FOR UPDATE`가 갭 잠금을 잡아, 첫 진입 2건이 각자 갭을 잡고 서로의 INSERT를 기다리는 **다른** 교착이
  생긴다. 순서만 바꾸고 격리수준을 그대로 뒀으면 교착을 옮기기만 했을 것이다.
- **함정 하나를 실제로 밟았다.** 처음에는 순서를 그대로 두고 구문만 `ON DUPLICATE KEY UPDATE`로 바꿨는데,
  MySQL Connector/J가 기본값(`useAffectedRows=false`, CLIENT_FOUND_ROWS)에서 **변경된 행이 아니라 일치한
  행**을 돌려준다. `INSERT IGNORE`가 중복에 0을 주던 자리에서 ODKU는 1을 준다 — `inserted` 판정이 항상
  참이 되어 기존 행까지 완료 replay로 전환됐고, `LegacyPracticeCompletionAttemptCompatibilityIntegration
  Test`가 잡았다(`expected IN_PROGRESS but was COMPLETED`). **`insertIfAbsent`의 반환값을 "이번에
  만들었는가"로 읽으면 안 된다.** 그래서 반환형을 `void`로 바꿔 그 오독 자체를 막았고, 판정은 잠금
  조회가 비어 있었는지로만 한다. `PracticeProgressRepository.insertIfAbsent`가 이미 ODKU를 쓰면서도
  이 함정을 안 밟은 이유는 반환형이 `void`였기 때문이다.
- **`INSERT IGNORE`는 이 커밋으로 저장소에서 사라졌다.**
- **tick ↔ 체결 정산 잠금 순서는 뒤집히지 않는다(확인함).** 이슈가 함께 지목한 우려인데, `practice_
  attempts`를 잠그는 트랜잭션을 전수로 보면 **그 전부가 attempt를 order·account·tutorial account보다 먼저
  잠근다.** (attempt를 아예 잡지 않는 트랜잭션도 있다 — `LimitOrderCancelService`는 order → account →
  tutorial account 순으로만 잠근다. 그런 트랜잭션은 attempt를 축으로 하는 사이클을 만들 수 없어 논거에
  영향이 없다.) 보조 인덱스로 잠그는 경로(tick·진입·종목선택·재시작·대본전환·복기·`lockForOrder`)는 모두
  그것이 그 트랜잭션의 **첫** attempt 잠금이고, PK로 잠그는 경로(`lockForFill`·`createRiskSnapshotOn
  BuyFill`)는 체결 트랜잭션의 첫 잠금이거나 이미 보조 인덱스로 같은 행을 잠근 뒤다. 즉 "clustered를
  쥔 채 secondary를 기다리는" 트랜잭션이 없어 순환이 만들어지지 않는다. 보조 인덱스 레코드를 쥔 채
  같은 레코드를 기다리던 유일한 코드가 바로 이 이슈의 `ensureAttempt`였다.
- **재시도는 원인 수정이 아니라 그물이다.** 이슈 완료조건 2를 위해 `PracticeAttemptEntryService`에
  1회 재시도를 뒀다. 트랜잭션 밖이어야 해서(롤백된 트랜잭션 안에서 다시 부르면 rollback-only)
  별도 빈으로 나눴고, 이는 ADR-0028이 `OrderService` → `OrderExecutionService`로 나눈 구조와 같다.
