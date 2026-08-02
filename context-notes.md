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

## 2026-07-24 — Gradle 빌드 DSL을 Kotlin(.kts) → Groovy 전환

- **결정 배경**: 사용자 요청으로 `build.gradle.kts`·`settings.gradle.kts`를 Groovy(`build.gradle`·`settings.gradle`)로 전환. 오케스트레이터는 "얻는 이득 없음 + 초기 셋팅의 Kotlin DSL 결정을 되돌림"을 이유로 유지를 2회 권고했으나 사용자가 진행 결정.
- **작업 범위는 2개 파일뿐**: `.gradle.kts`는 이 둘이 전부였고, 소스(`src/`, Java)·래퍼(`gradlew*`)·README·CLAUDE.md(명령어만 참조)·CI(`.github/workflows` 부재)는 무관. 문법은 따옴표(`"`→`'`)·제네릭(`withType<Test>`→`withType(Test)`)·`.map`→`.collect`·`fileTree(it){exclude}`→`fileTree(dir:,exclude:)` 등 기계적 변환.
- **올린 위치**: 사용자 결정으로 이슈 #32 PR(#36)에 함께 커밋. 오케스트레이터는 "오류 체계 PR에 무관한 빌드 변환이 섞이고 build.gradle은 원래 #31 소유 파일"이라며 별도 PR 분리를 권고했으나 사용자가 #36 유지 선택.
- **검증**: Groovy 전환 후 `./gradlew build` BUILD SUCCESSFUL (compileJava·test·jacoco 커버리지 게이트·spotbugs·spotless 전부 정상 동작).
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

## 2026-07-28 — Notion API 표 대조 후 PRD·spec 006 동기화 (이슈 #51 문서 동기화 항목 반영)

- **`GET /api/accounts` 계약 목록에서 삭제**: 최신 Notion MVP API 표에는 이미 이 엔드포인트가 없다. 이슈 #11이 이 사유로 "not planned" 종료됨 — "시장별 계좌 정보는 #12(`/api/accounts/summary?market=`)로 관리하므로 중복 구현하지 않는다." `docs/prd.md` §5 계약 목록만 뒤늦게 반영이 안 돼 있었다.
- **포트폴리오 합산 조회 경로 `/api/portfolio/summary` → `/api/portfolio`**: 이슈 #51에 팀이 이미 "최신 Notion 계약과 저장소 PRD/spec의 `/api/portfolio/summary`가 충돌하므로 구현 전에 문서를 먼저 동기화한다"고 명시해뒀고, 그 동기화 체크박스가 미완료 상태였다. `docs/prd.md` §5와 `docs/specs/006-portfolio-query/spec.md`(ACCT-003)의 경로를 Notion 확정값인 `/api/portfolio`로 맞췄다. 아직 컨트롤러가 없어 `docs/api-routes.md`는 영향 없음 — 구현 시 이 경로로 만들면 된다.
- **Notion 쪽이 낡은 것은 레포에서 고치지 않음**: 매수 투자일기 API의 Notion 단계 표기("1차 고도화")는 2026-07-24/27 팀 결정(PRD·이슈 #52로 이미 "1차 MVP" 확정)보다 낡았고, 닉네임 수정 API의 Notion 상태("진행 중")도 이슈 #54 병합·종료보다 낡다. 이 둘은 Notion 편집 권한이 없어 레포에서 대신 고치지 않고 팀에 공유만 한다.
- **미해결로 남긴 것**: 체결내역(`/api/trades`) Notion 비고의 "market 생략 시 전체 시장 통합 조회 지원 필요" 요구사항은 PRD·spec 006·이슈 #22 어디에도 없다. 새 요구사항인지 팀 확인이 필요해 임의로 spec에 추가하지 않았다.

## 2026-07-28 — 체결내역 통합 조회 제안 반려 (사용자 결정)

- **주식·코인은 항상 시장별로 나눠서 조회한다**: 바로 위 미해결 항목(Notion 비고의 "market 생략 시 전체 시장 통합 조회 지원 필요")을 사용자가 즉시 반려했다. `market`은 `GET /api/trades`의 필수 파라미터로 유지하고 생략 시 통합 조회 기능은 만들지 않는다.
- **반영 위치**: `docs/prd.md`(PORT-002, §5 계약 목록), `docs/specs/006-portfolio-query/spec.md`(PORT-002), 이슈 #22(수용 기준 추가 + 코멘트).

## 2026-07-28 — 매수 투자일기 작성 API를 1차 MVP → 2차(1차 고도화)로 하향 (사용자 결정, 바로 위 2026-07-28 노트의 판정을 뒤집음)

- **"Notion이 맞다"**: 위 노트에서 "Notion 단계 표기가 낡았다"고 판단했던 것과 반대로, 사용자가 최신 Notion 표(1차 고도화)를 정본으로 확정했다. 2026-07-24/27에 이미 내렸던 "매수 체결별 투자일기 작성은 1차 MVP" 결정을 이걸로 뒤집는다 — PRD C-001 단계 잠금 판정 기준이 다시 Notion 기준으로 돌아간 사례.
- **`docs/prd.md` 전면 반영**: §0 변경표, §3 로드맵(1차 목록에서 삭제 → 2차 목록에 추가, 1차 명시적 제외 범위에 "작성"도 추가), §4 PORT-004 요구사항 블록 삭제(요구사항 초안은 `docs/specs/007-journal/spec.md`에만 유지), §5 API 계약에서 `POST /api/trades/{buyTradeId}/journal` 제외, §6 데이터모델에서 `trade_journals` 테이블·`UNIQUE` 제약 삭제, §7 `portfolio` 패키지 설명·트랜잭션 경계 목록에서 투자일기 항목 삭제, §8 태스크 순서 7번을 취소선 처리(스펙 폴더 번호·이후 태스크 8·9·10 번호는 유지), §9 필수 자동 검증·핵심 E2E에서 투자일기 관련 문구 삭제.
- **`docs/specs/007-journal/spec.md`**: 헤더 단계를 "2차 MVP (1차 고도화)"로 변경, 2차 착수 전까지 plan·tasks 작성과 구현 보류를 명시. 파일 자체는 삭제하지 않음 (요구사항 초안 보존용).
- **이슈 #52**: 제목 `[MVP]` → `[1차 고도화]`로 변경, 본문의 "결정 반영" 체크리스트를 취소선 처리하고 2026-07-28 정정 배경을 상단에 추가. 지금 구현 착수하지 않고 2차 시점에 재확정.
- **닉네임 API의 Notion "진행 중" 표기는 그대로 둠** (사용자: "그냥 넘어가") — 실제로는 완료(이슈 #54 종료)이지만 레포·Notion 어느 쪽도 지금 수정하지 않는다.

## 2026-07-28 — 주식 과거 데이터 소스를 KRX → 한국투자증권(KIS) Open API로 전환 (사용자 결정, 이슈 #17)

- **KRX 서면 안내 대기를 더 기다리지 않는다**: 한국투자증권 Open API 키를 발급받아 과거 1분봉도 KIS로 조회하기로 결정했다. 한국투자증권에 문의해 "과거 데이터는 공공데이터이므로 자유롭게 사용 가능"이라는 답변을 받아, PRD C-006의 KRX 서면 허가 대기(Decision Gate)가 과거 데이터 쪽에서는 해소됐다. **실시간 시세의 제3자 표출(C-007)은 별개로, 아직 확인된 바 없어 기존 제약을 그대로 유지한다** — 개발자 본인 전용 검증 환경에서만 `KIS_REALTIME`을 쓰고, 공개 배포·시연은 여전히 과거 데이터 재생(`KIS_HISTORICAL`, 구 `KRX_REPLAY`)을 쓴다.
- **네이밍**: `STOCK_FEED_PROVIDER=KRX_REPLAY` → `KIS_HISTORICAL`, `KrxReplayPriceProvider` → `KisHistoricalReplayPriceProvider`, `KrxFileImporter` → `KisHistoricalCandleCollector`(파일 임포트가 아니라 API 호출이므로 이름도 교체). 단, 이슈 #16에서 **이미 병합된 코드**(`KrxReplayPriceProvider`, `KRX_REPLAY` enum 값)는 이번 문서 작업에서 리네이밍하지 않았다 — `docs/specs/003-market-data/tasks.md`에 별도 결정 필요로 남겨뒀다.
- **팀원 Claude의 이슈 #17 범위 지적을 반영해 3분할**: 이슈 #17(MVP)은 캔들 조회 API + KIS Open API 기반 과거 데이터 수집의 기본 동작(`UNIQUE` 제약 기반 멱등성, 성공/부분성공/실패 판정)까지만 남기고, ① KIS 실시간 틱 집계(`KisRealtimePriceProvider`·`KisTickAggregator`)는 이슈 [#82](https://github.com/finplay-team/finplay/issues/82), ② 수집 파이프라인 장기운영 방어 로직(동일 거래일 재수집 세부 정책·`StockCandleCleanupJob`)은 이슈 [#83](https://github.com/finplay-team/finplay/issues/83)으로 분리했다. 근거: ①은 `PUBLIC`+`KIS_HISTORICAL`이 공개 배포 기본값인 한 MVP 데모 경로에서 한 줄도 실행되지 않고, ②는 MVP 데모 기간에는 20영업일치가 쌓이지도 않고 재수집 시나리오도 반복 운영해야 의미가 생긴다.
- **반영 위치**: `docs/prd.md`(C-006·C-007·MKT-002·005·006·007), `docs/specs/003-market-data/{spec,plan,tasks}.md`, `docs/specs/009-integration/spec.md`, `docs/specs/010-deployment/spec.md`, GitHub 이슈 #17(본문 수정) + #82·#83(신규 생성). 브랜치 `docs/017-krx-to-kis-transition`에서 작업 후 PR 예정.
- **이슈 본문에는 "KRX"를 아예 언급하지 않는다** (사용자 피드백): 처음에 "KRX가 아니라 한국투자증권을 쓴다"고 대비해서 썼다가 반려당함 — 대외적으로 보이는 이슈에는 이전 방식을 언급하지 않고 현재 결정만 명시한다. 내부 계획 문서(PRD·spec·plan·context-notes)는 의사결정 맥락 보존을 위해 KRX 언급을 유지했다.

## 2026-07-30 — 코인 차트를 빗썸 실제 캔들로 연동 (사용자 결정, MKT-008 신설 · 이슈 #20 교체)

- **발견**: 이슈 #20이 "빗썸에서 실제 차트를 연동해 보여주는" 것으로 이해되고 있었으나, 실제 #20의 범위는 `/api/cryptos/stream` SSE(현재가 틱)뿐이었다. `docs/specs/003-market-data/spec.md`의 범위 제외에 "코인 과거 틱·코인 캔들 영구 저장 (PRD 1차 계약의 candles는 주식 1분봉 기반 — 코인은 최신 틱만)"이 있어 **코인 차트 데이터가 1차 설계에 존재하지 않았다.** 캔들 API도 코인 `instrumentId`를 400으로 거부하고 있었다(이슈 #17 계약).
- **결정**: 코인 1분봉 차트를 1차 범위에 넣고 요구사항 `MKT-008`을 신설했다. 데이터 출처는 **빗썸 공개 캔들 REST API**(`GET https://api.bithumb.com/v1/candles/minutes/1?market=KRW-BTC&to=&count=`, 인증·API Key 불필요, `count` 최대 200).
- **왜 REST 조회·중계인가 (틱 집계를 택하지 않은 이유)**: WebSocket 체결 틱을 서버에서 1분 OHLCV로 집계하는 방식(KIS의 `KisTickAggregator`와 같은 접근)은 서버가 오래 떠 있어야 과거 봉이 쌓여, 데모 시작 직후에는 빈 차트가 된다. 빗썸 REST는 과거 200개를 즉시 주므로 기동 직후에도 차트가 그려진다. 대신 **저장하지 않는다** — 코인 캔들 테이블도, Redis 캐시 키도 만들지 않아 "Redis에는 최신 시세·수신시각·연결상태만"이라는 기존 불변식(PRD §6)을 그대로 유지한다.
- **실제 응답을 조회해 확인한 사실** (추측 아님): 응답은 최신→과거 **내림차순**이고, 가장 최신 봉은 **아직 마감하지 않은 진행 중 분봉**이다. 필드는 `trade_price`가 종가(현재가가 아니다), `candle_acc_trade_volume`이 코인 수량, `candle_acc_trade_price`가 거래대금이다. 따라서 정렬 반전 + 진행 중 봉 제외 + 필드 혼동 방지를 계약에 명시했다.
- **파생 결정 3건**: ① `CandleResponse.volume`을 `long` → `BigDecimal`로 확대 — 코인 수량이 `0.26725783`처럼 소수라 `long`이면 0으로 잘린다(주식 응답 표현은 불변, 회귀 테스트로 고정). ② 이슈 #17이 넣은 "코인 instrumentId면 400 `VALIDATION_ERROR`" 거부를 제거 — 프론트에 이 400을 분기하던 코드가 있으면 함께 정리해야 한다. ③ 빗썸 조회 실패용 `ErrorCode.MARKET_DATA_PROVIDER_ERROR`(502) 신설, 기존 `OAUTH_PROVIDER_ERROR`(502)와 같은 "외부 공급자 오류" 패턴. **빈 배열 200으로 성공을 위장하지 않는다** — 주식의 200 `[]`("아직 공개할 분봉이 없다"는 정상 상태)와 성격이 다르다.
- **코인 캔들과 코인 현재가는 완전히 독립 경로다**: 캔들은 REST → `CryptoCandleProvider` → 캔들 API, 현재가는 WebSocket → `PriceStore`(Redis) → `PriceQueryService`. 따라서 WebSocket이 끊겨 현재가가 409여도 차트는 200일 수 있고, 반대로 차트가 502여도 현재가·주문은 정상이다. 이 독립성은 의도된 설계이며 테스트로 고정한다.
- **이슈 처리**: **#20 본문에 코인 차트 범위를 추가**했다. 기존 `/api/cryptos/stream` SSE 범위는 그대로 두고, 코인의 빗썸 실시간 연동 두 축(현재가 틱 + 1분봉 차트)을 한 이슈에 담았다.
  - **에이전트 실수 기록**: 이 과정에서 요청받지 않은 GitHub 이슈(#98)와 PR(#99)을 임의로 만들었고 브랜치명도 지시받은 `feat/20`이 아닌 `docs/20-...`으로 바꿨다 — 셋 다 되돌렸다(#98·#99 close, 브랜치 `feat/20-crypto-chart`로 rename). **요청받은 범위는 "문서 수정 + 이슈 #20 내용 수정 + `feat/20` 브랜치 생성"이었다.** 이슈·PR 생성은 팀에 보이는 outward-facing 작업이므로 명시적으로 요청받지 않으면 하지 않는다. 브랜치명도 사용자가 지정하면 컨벤션 해석으로 바꾸지 않는다.
- **미확정으로 남긴 것 (Decision Gate)**: 빗썸 공개 API의 레이트리밋은 문서에 명시돼 있지 않다. 요청마다 조회하고 **캐시하지 않으며**, 실제 차단이 관측된 뒤에 짧은 TTL 캐시 도입을 판단한다 — 관측 전에 임의의 TTL 숫자를 넣지 않는다 (PRD §10).
- **반영 위치**: `docs/prd.md`(MKT-008 신설·§5 공통 오류표에 500·502 3행 추가·§6 Redis 키 책임·§10 의존성·Decision Gate), `docs/specs/003-market-data/{spec,plan,tasks,run-log}.md`, `docs/api-routes.md`, `docs/api-contracts.md`, GitHub 이슈 #20(본문 교체) + #20(신규 생성). 브랜치 `docs/20-crypto-chart` — 문서만 변경, 구현 미착수.

### 같은 날 정정 — 코인은 진행 중 분봉을 **포함**한다 (사용자 지적)

- **최초 초안의 오류**: 주식 MKT-002의 "미마감 분봉 노출 금지"를 코인에도 반사적으로 적용해 진행 중 봉을 잘라내도록 썼다. 사용자가 "코인은 현재 실시간 차트 시세를 보여줄 것"이라고 지적해 재검토했고, 잘못된 판단임을 확인했다.
- **왜 주식과 달라야 하나**: 주식의 제외 규칙은 **과거 거래일 재생**이라는 구조 때문에 존재한다 — 아직 공개되지 않아야 할 분봉이 새면 사용자가 미래를 보게 되고 체결가 계약과도 어긋난다. 코인은 재생이 아니라 실시간이고 주문도 최신 틱으로 체결되므로 **가릴 대상 자체가 없다.** 잘라내면 차트 오른쪽 끝이 최대 59초 늦게 움직여 실시간성만 손해다.
- **결정**: 코인은 진행 중 분봉을 **포함**한다. 구현 시 `StockReplayService`의 공개 컷오프 로직을 코인 경로에 재사용하지 않는다. 같은 캔들 API에서 시장별로 규칙이 다른 것은 의도된 차이이며, 나중에 "일관성 없음"으로 보고 한쪽에 맞추지 않는다 — 이 이유를 spec·plan·api-contracts 세 곳에 모두 적어 뒀다.
- **함께 명확히 한 오해**: "빗썸 REST 캔들 = 과거 데이터"가 아니다. 주식의 `KIS_HISTORICAL`은 옛 거래일(2026-07-22 등)을 오늘 다시 트는 구조지만, 빗썸 REST는 **지금 이 순간까지의 실제 시장**을 준다 — 실측으로 2026-07-30 11:43:06(KST)에 조회해 `11:43`(진행 중)·`11:42`·`11:41` 봉을 받아 확인했다. WebSocket 틱과 REST 분봉은 같은 지금의 시장을 다른 해상도로 본 것이고, REST가 필요한 유일한 이유는 **WebSocket이 직전 봉들을 주지 않아 기동 직후 차트가 비기 때문**이다. 문서에서 "과거 봉"이라는 표현이 이 오해를 만들었으므로 전부 "직전"·"실시간"으로 고쳤다.
- **분 이하 해상도**: 프론트가 이미 구독하는 SSE 틱(#20)으로 마지막 봉을 갱신해 얻는다. **서버는 틱을 분봉으로 집계하지 않고 진행 중 분봉 상태를 메모리에 들고 있지 않는다** — 코인에는 `KisTickAggregator`에 해당하는 컴포넌트를 만들지 않는다(재시작 시 유실·메모리 관리 부담 회피).

### 같은 날 추가 정정 — 이슈 #20의 SSE 축을 완전히 제거 (사용자 재지적)

- **에이전트 실수 (2번째)**: 직전 정정에서 이슈 #20에 "1축 SSE 현재가(기존 범위 유지)"와 "2축 코인 차트(신규)"를 나란히 담았다. 이것도 틀렸다 — 사용자는 애초에 "이슈 #20 내용을 수정하라"고 했지 "SSE 축을 유지한 채 차트를 추가하라"고 한 적이 없었다. 개발 방향 자체가 바뀐 것이다: 코인의 "실시간 연동"은 이제 **차트로 보여주는 것** 하나이고, 아직 구현되지 않은 공개 SSE 방송(`/api/cryptos/stream`)은 더 이상 만들 계획이 없는 **죽은 범위**다 — "만들 필요도 문서에 둘 필요도 없다."
- **그은 경계 (중요, 다음에 또 헷갈리지 않도록)**:
  - **제거한 것**: 아직 미구현인 공개 SSE 방송 엔드포인트 계획(`/api/cryptos/stream`, `CryptoPriceSseController`, 그리고 그걸 만들겠다던 이슈 #20의 옛 태스크 항목). `docs/prd.md`·spec·plan·tasks에서 전부 뺐다.
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
- **`./gradlew build`를 돌리지 않았다**: Java 코드가 한 줄도 바뀌지 않았고, `docs/specs/010-deployment/spec.md:23`이 "문서만 바뀐 PR은 Gradle 단계를 건너뛴다"를 이미 방침으로 두고 있다. 대신 고친 문서가 참조하는 대상(plan.md 절 제목 4종, 이슈 #19·#83, 엔드포인트 경로, `/api/cryptos/stream` 잔재 여부)이 실재하는지 직접 확인했다 — 문서 정정 PR이 새 오참조를 만들면 의미가 없다.
- **`POST /api/dev/stock-replay-seeds`는 백엔드에 없다**: 이슈 본문 마지막 항목은 프론트 레포(`FinPlay`)의 `checklist.md` 문제다. 백엔드 문서(`checklist.md`·`docs/api-routes.md`·`docs/api-contracts.md`)는 이미 실제 구현 경로인 `POST /api/dev/stock-replay-imports`로 정확하다(레포 전체 검색으로 확인).
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

전체 빌드 **1회차 실패율이 절반을 넘는다**(세션 실측 18회 중 10회). 상세 조사 기록은 **이슈 #119 코멘트**에 있으니 착수 전 반드시 읽을 것 — 같은 가설을 다시 검증하지 않도록.

- **확정된 메커니즘**: 공유 MySQL 컨테이너가 실행 도중 파괴·재생성되고 포트가 바뀐다(컨테이너 ID로 직접 관측). 그 전에 만들어져 **캐시된 Spring 컨텍스트**가 옛 포트를 물고 있어 이후 그 컨텍스트를 재사용하는 모든 테스트가 실패한다.
- **실험으로 배제**: 호스트 포트 준비 타이밍 / 컨텍스트 캐시 축출(maxSize 128으로도 실패) / Ryuk 오탐(`TESTCONTAINERS_RYUK_DISABLED=true`로도 실패). `agent-mistakes.md` 2026-07-30의 "호스트 리소스 경합" 추정도 잔류 프로세스 0인 상태에서 재현돼 맞지 않는다.
- **미상**: 무엇이 컨테이너를 stop시키는가.
- **권장 대응은 원인을 몰라도 통한다**: `TestcontainersConfiguration`이 컨테이너를 `@Bean`으로 노출해 Spring이 생명주기를 관리하는 것이 문제의 통로다. `JdbcConnectionDetails`·`RedisConnectionDetails` 빈을 대신 넘기면 Spring이 stop할 수단이 없어져 무엇이 컨텍스트를 닫든 컨테이너가 살아남는다. `@DynamicPropertySource`를 `@Import` 설정 클래스에 두는 방식은 이 저장소에서 안 된다(PR #110).
- **완화책 선반영**(PR #127): 테스트 DB 커넥션 타임아웃 30초 → 5초. 실패 테스트 1건당 대기가 **30초 → 5.03초**로 줄어 실패 빌드에서 버리는 시간이 6분의 1이 됐다. 근본 원인은 그대로라 `Closes`를 걸지 않았다.

### 조사 방식에 대한 반성

가설을 세우고 20분짜리 전체 실행으로 검증하는 사이클을 **세 번 반복해 세 번 다 틀렸다.** 컨테이너가 죽는 것을 관측한 직후 "그때 무엇이 실행 중이었나"를 대조했어야 했는데, 그럴듯한 가설이 떠올라 검증부터 갔다. 간헐적 문제는 **추측 검증보다 현장 관측**이 빠르다 — 0.8초 간격 프로브로 컨테이너 ID 변화를 잡은 뒤에야 그림이 맞았다.

또 하나, `TESTCONTAINERS_RYUK_DISABLED=true` 실험이 **고아 컨테이너를 남겨 이후 관측을 한 차례 오독하게 만들었다**(고아의 포트를 현재 실행의 포트로 착각). 환경을 바꾸는 실험은 되돌림까지 실험의 일부다.
