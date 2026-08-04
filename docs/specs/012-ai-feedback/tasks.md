# Tasks: 012 AI 피드백 — 이슈 #188 (종목 뉴스 요약·개장 전 브리핑 조회 API)

> **이 문서는 이슈 #188의 범위만 담는다.** spec 012 전체(이슈 8개)의 분할은 `./plan.md`가 정본이고, 값·규칙은 `./spec.md` §확정값이 정본이다. 여기에 값을 다시 적지 않는다 — 크론·타임존·스레드 풀은 §C-1, 구간과 `items` 범위 대응은 §C-2, 벽시계와 분봉의 구분은 §C-2-1, 공시 날짜 판정은 §C-3, 상태값과 판정 순서는 §C-4, 노출 게이트는 §C-5, 패키지 배치와 클래스 이름·배치 생성 순서는 §C-6, 설정 방침과 상한 값은 §C-7, 컬럼 타입은 §C-8, 코인의 시각 처리는 §C-9, 목록 상한·정렬·절단은 §뉴스 매칭 범위, 클램프는 §노출 판정, 실패 시 대응은 §실패 처리, 응답 필드는 `docs/api-contracts.md`다.
>
> 항목 하나 = implementer 1회 투입 = 커밋 1개. 테스트 레벨은 ADR-0003을 따른다 — 상태값 판정·구간 계산은 단위, 리포지토리 파인더와 유니크 축은 `@DataJpaTest`, 컨트롤러 계약은 `@WebMvcTest`, 노출 게이트와 배치 종단은 고정 `Clock` + Testcontainers 통합이다. **설정 드리프트만은 통합이 아니라 `FeedbackDetectionPropertiesYamlTest` 형태**(`ConfigDataApplicationContextInitializer`)를 따른다 — `@SpringBootTest` + Testcontainers를 쓰면 Docker 없는 환경에서 드리프트를 못 본다.
>
> **이 이슈는 spec 012의 두 번째·세 번째 컨트롤러를 만든다.** `docs/api-routes.md`·`docs/api-contracts.md` 갱신은 각 컨트롤러 커밋에서 함께 하고(CLAUDE.md 규칙 7), 마지막에 /feature 마무리의 planner(동기화 모드)가 실제 매핑과 두 문서를 대조한다. 8번 항목이 그 자리다.

## 이 이슈 전체에 걸리는 제약

- **여기서 값을 새로 정하지 않는다.** 상한·크론·구간·상태값·클래스명이 필요하면 위 참조를 읽는다. spec에 없는 값·이름이 필요해 보이면 코드가 아니라 `spec.md`를 먼저 고친다 (CLAUDE.md 규칙 1·2). #167이 `NewsCollectionService`를 §C-6에 추가한 것이 선례다. **이 이슈에서 그렇게 처리한 자리가 둘이고 이미 §C-6에 들어갔다** — `MarketSessionTimes`(개장 시각 상수)와 `BriefingNewsItem`(Part D `items` 요소)이며 2026-08-04에 확정됐다. 그 밖에 이름이 없는 자리를 만나면 같은 절차를 따른다.
- **새 마이그레이션을 내지 않는 것이 기본이다.** V13이 `instrument_news_summaries`(`UNIQUE(instrument_id, origin_trade_date, scope)` + `generated_at` 인덱스)와 `market_briefings`(`UNIQUE(market, origin_trade_date)` + `generated_at` 인덱스)를 **코인 UPSERT와 최신 1행 조회까지 감안해** 이미 만들어 두었다(2026-08-04 확인). 컬럼을 더할 근거를 발견하면 머지된 V13을 고치지 말고 §C-8·§데이터 모델을 확인한 뒤 **새 번호(V17)**로 제안한다 (ADR-0004). 컬럼이 바뀌면 `FeedbackSchemaConstraintsTest`의 기대 맵이 먼저 깨진다(의도된 설계).
- **`FeedbackBatchService`는 빈 자리 두 곳의 본문만 채운다.** `generateMarketBriefing`·`generateNewsSummaries`의 **호출 위치·시그니처·호출 순서를 바꾸지 않는다** — 그 순서(§C-6)가 #180의 완료 조건 배치 ③이고 `FeedbackBatchServiceTest`가 `InOrder`로 단정한다. 요약 쪽은 범위를 인자로 받아 두 자리에서 호출되므로 **분기를 새로 만들 필요가 없다.**
- **배치 실패 격리를 종목 단위로 내린다.** 지금은 호출부 `try/catch`라 단계 단위까지만 보장된다 — 요약을 종목 루프로 채우면 **한 종목이 터질 때 나머지가 함께 날아가도 현재 테스트는 전부 초록이다.** 검증은 메서드 경계를 `doThrow`로 갈아끼우지 말고 **본문이 부르는 협력자를 mock으로 두고 특정 종목에서만 던지게** 한다. `FeedbackBatchServiceTest`의 `continuesWithOtherInstrumentsWhenDetectionThrows`가 그대로 본뜰 형태다.
- **`NarrativeService` 하나만 주입한다**(§C-6). 요약·브리핑은 2단계 경로다 — 생성 → 후검증 → 적발 시 재생성 1회 → 그래도 걸리면 서술 없음 + `NONE`이며, 그 흐름은 이미 `resolveNewsSummaryNarrative`·`resolveMarketBriefingNarrative`에 있다. 생성기·검증기·프롬프트 조립기를 직접 알 필요가 없다.
- **`NewsItem`은 `dto/response/`의 최상위 record다. 재사용하고 중첩으로 복제하지 않는다**(§C-6). Part C `items`가 그대로 이 record다.
- **#180이 만든 `NewsMatcher`와 `MarketNewsItemRepository` 구간 파인더가 이미 있다. 없는 것만 더한다.** 요약·브리핑·`items`가 쓸 질의는 이 이슈 소유이지만, `findByInstrumentIdAndTypeAndPublishedAtBetweenOrderByPublishedAtAsc`·`findDisclosuresReceivedOn`을 같은 뜻으로 다시 만들지 않는다. 파생 쿼리 이름 대신 `@Query`를 쓰는 이유가 기존 파인더 주석에 있다 — 단일 필드 프로젝션은 컴파일을 통과하고 슬라이스 테스트 전까지 드러나지 않는다.
- **`feedback`은 다른 도메인의 repository·store를 직접 주입하지 않는다**(§C-6). `market`의 조회는 #180이 연 `StockReplayService` 경로를 쓴다. **게이트를 우회하는 메서드는 §C-5의 게이트를 통과한 뒤에만** 부른다 — 판정은 호출부 책임이다.
- **정렬 동률 타이브레이커를 `id` 내림차순으로 정하고 계약에 문장으로 적는다.** Part C·D 목록은 §뉴스 매칭 범위대로 발행시각 내림차순인데, 공시는 발행시각이 전부 `00:00:00`이라 동률이 흔하다. **2차 키를 지우는 회귀는 테스트가 반드시 잡지 못한다** — InnoDB가 흔히 PK 순서로 돌려주어 우연히 일치한다. 그래서 파인더 이름과 `docs/api-contracts.md` 문장 양쪽에 남긴다(#180이 카드 정렬에서 같은 지적을 받았다).
- **조회는 쓰지 않는다.** GET이 LLM을 호출하지도 DB에 쓰지도 않는다 (`docs/conventions.md` — GET은 부수효과 없음, FEED-008). 요약·브리핑은 전 회원이 공유하는 배치 산출물이다.
- **`RestClient` 빈을 새로 정의하지 않는다.** 필드 `@Qualifier`가 Lombok 생성자에 복사되지 않아 실효가 없고, 두 번째 빈이 생기는 순간 `NoUniqueBeanDefinitionException`으로 앱 전체가 죽는다 (`docs/agent-mistakes.md` 2026-08-04). 필요하면 `RestClient.Builder`를 받는다. **이 이슈는 새 외부 HTTP 호출을 만들지 않으므로 애초에 해당 자리가 없어야 한다.**
- **외부 API 키가 없어도 기동과 `./gradlew build`가 통과해야 한다** (ADR-0011, §실패 처리). **실제 외부 API를 호출하는 자동 테스트를 만들지 않는다** (PRD C-005).
- **원장에 쓰지 않는다.** 배치와 조회 경로가 `instrument_news_summaries`·`market_briefings` 밖의 테이블에 INSERT·UPDATE 하지 않는다. `instruments`·`market_news_items`·`stock_replay_sessions`는 **읽기만** 한다. 8개 이슈 공통 조건인 "원장 불변"이 이 이슈에서 취하는 형태이며, "GET이 LLM을 호출하지도 DB에 쓰지도 않는다"와 같은 뿌리다.
- **이 이슈의 코인 작업은 요약·브리핑뿐이다.** 코인 변동 탐지·실시간 감시·가격 스냅샷은 `plan.md` 8번 소유이고, FEED-006의 코인 조회 분기도 8번이다.

## 작업 항목

- [x] **1. `feedback.news` 목록 상한 3종과 개장 시각 상수 정리**

  뒤 항목 전부가 읽을 값과 상수를 먼저 한 곳에 세운다. **이 항목은 완료 조건을 단독으로 소유하지 않지만 게이트 ⑧과 5·6번의 전제다.**
  - `max-items-per-news-list`·`max-items-per-briefing`·`max-items-per-summary` 세 키를 §C-7의 방침대로 **yml과 record `@DefaultValue` 양쪽**에 둔다. 값은 §C-7이 정본이며 여기서 정하지 않는다. `FeedbackNewsProperties`에 이미 같은 형태의 검증 생성자가 있으므로 상한이 1 미만이면 거부하는 규칙을 같은 자리에 붙인다 — 0이면 목록이 통째로 비는데 예외도 로그도 남지 않는다.
  - 드리프트 테스트는 **record 기본값**(`FeedbackDetectionPropertiesTest` 형태)과 **yml 키 경로**(`FeedbackDetectionPropertiesYamlTest` 형태) 두 축이다. 후자에 `@SpringBootTest`를 쓰지 않는 이유가 그 파일 주석에 있다.
  - **개장 시각 상수를 `MarketSessionTimes` 하나로 모은다** (§C-6, 2026-08-04 확정). 지금 `PriceMoveCardService`의 `MARKET_OPEN_TIME`과 `NewsMatcher`의 `PRE_MARKET_FROM_TIME`·`PRE_MARKET_TO_TIME`이 독립 선언이고, 이번에 Part C의 `summaryScope` 판정과 Part D의 하한이 **세 번째·네 번째 사용처**가 된다. 위치는 `feedback/service/`이고 **빈이 아니라 상수만 갖는 최소 타입**이다 — `config/`에 두지 않는 이유가 §C-6에 있다.
  - **값의 성격은 §C-2-1이 벽시계로 못박았다** — 기사 구간 경계와 노출 게이트에 쓰는 값이고 **분봉을 찾는 값이 아니므로 "첫/마지막 분봉"으로 바꾸지 않는다.** 그 근거를 상수 주석에 남긴다. 값 자체는 §C-2가 정본이며 여기서 다시 적지 않는다.
  - 검증 — 설정 드리프트 2종 + 기존 `NewsMatcherMatchingWindowTest`·`PriceMoveCardServiceTest`가 상수 이동 후에도 그대로 통과하는지. **상수 이동은 동작을 바꾸지 않는 변경이므로 새 단정을 만들지 않는다.**

- [ ] **2. 테스트 설정에서 배치 크론 비활성화**

  `@SpringBootTest`가 컨텍스트를 통째로 띄우므로 **테스트 실행 중 스케줄이 실제로 등록된다.** 평일 배치 시각에 전체 빌드가 돌면 공유 Testcontainers에 실제 데이터가 생기고, 7번이 매시 05분 코인 배치를 더하면서 노출 빈도가 뛴다. 이 항목은 **완료 조건을 소유하지 않고 뒤 항목의 실행 환경을 고정한다.**
  - 대상은 `feedback.batch.*`와 `feedback.news.*`의 크론 전부다. 키 경로는 §C-1이 정본이고 **여기서 크론 값을 새로 정하는 것이 아니라 테스트에서만 무력화하는 것**이다.
  - 적재 경로는 `build.gradle`의 `spring.config.additional-location`이 이미 열어 둔 형태를 본뜬다 — 그 시스템 프로퍼티는 `ConfigDataEnvironmentPostProcessor`(즉 `@SpringBootTest`)만 해석하므로 `ApplicationContextRunner` 슬라이스 테스트에는 영향이 없다. 그 이유가 `bithumb-feed-simulator-disabled-for-tests.yml` 주석과 `build.gradle`에 적혀 있다.
  - **충돌 하나를 먼저 확인한다.** `NewsCollectionPropertiesIntegrationTest`가 `@SpringBootTest`의 `Environment`에서 `feedback.news.collect-cron`·`disclosure-cron` 값을 §C-1과 대조한다 — 테스트에서 크론을 덮어쓰면 이 단정이 함께 깨진다. **드리프트 단정을 없애지 말고** 1번이 쓰는 yml 전용 형태(`ConfigDataApplicationContextInitializer`)로 옮겨 같은 축을 유지한다.
  - **배치 로직 테스트는 메서드를 직접 호출한다.** 크론이 꺼져도 `FeedbackBatchIntegrationTest`·`FeedbackBatchServiceTest`는 그대로 돌아야 한다.
  - `FeedbackBatchScheduleTest`·`NewsCollectionScheduleTest`는 **애노테이션 문자열과 `pool.size`를 리플렉션으로 읽으므로** 이 변경에 영향받지 않는다. 그대로 통과하는지 확인만 한다.
  - 검증 — 위 두 스케줄 테스트와 드리프트 테스트가 전부 통과하고, `@SpringBootTest` 컨텍스트에 등록된 크론 트리거가 실제로 비는지 단정한다.

- [ ] **3. (확인) Part C `items` 상한에서 `D-1` 접수 공시가 먼저 잘리는지 판정**

  **코드로 먼저 정하지 않는다. 이 항목의 산출물은 판정 결과이고, 재현되면 spec 결정 제안까지다.**
  - 문제는 이렇다. §뉴스 매칭 범위가 목록 상한을 **발행시각 내림차순으로 자른다**로 정했는데, 공시는 `rcept_dt`만 있어 `published_at`이 그날 `00:00:00`이다(§C-3·§C-8). 그러면 `PRE_MARKET` 구간의 `D-1` 접수 공시는 **항상 목록의 맨 아래**라 상한을 넘는 순간 가장 먼저 잘린다. 시가 갭 카드의 근거 절단(이벤트에 가까운 순)에서 #180이 만난 것과 **같은 문제가 방향만 바꿔 재현되는지**를 본다.
  - 판정 방법은 기존 자산으로 충분하다 — §C-3의 공시 조건, §뉴스 매칭 범위의 절단 규칙, `NewsMatcher.sortAndTruncate`의 현재 형태, `max-items-per-news-list` 값(§C-7)과 종목당 실제 기사 수(§튜닝)를 대조한다. **새 코드를 넣어 확인하지 않는다.**
  - 재현되면 **spec 결정을 제안한다** — 정렬·절단 규칙을 §뉴스 매칭 범위에서 어떻게 보정할지이며, 값과 규칙 모두 spec이 정본이므로 tasks.md나 코드에 임시 규칙을 두지 않는다. 재현되지 않으면 그 근거(상한 대비 실제 건수)를 남기고 넘어간다.
  - #180이 같은 자리에서 **spec의 명시 규칙을 그대로 따르고 종류별 우선순위를 임의로 넣지 않았다.** 이 항목도 같은 태도를 유지한다.
  - 산출물 — 판정 보고. 필요하면 `spec.md` §뉴스 매칭 범위 수정 제안 1건. **5번은 이 판정이 끝난 뒤에 시작한다.**

- [ ] **4. 주식 요약·브리핑 생성 — `FeedbackBatchService` 빈 자리 두 곳 채우기**

  개장 전 배치가 하루치 요약·브리핑을 실제로 만들게 한다. **#180이 세운 골격 위에 얹으며 호출 위치·순서를 바꾸지 않는다.**
  - 소유 클래스는 §C-6의 `MarketBriefingService`와 종목 뉴스 요약 쪽 서비스다. 배치는 그것을 부르기만 하고, 카드 확정을 `PriceMoveCardService`로 뺀 것과 같은 이유다(§C-6 마지막 문단).
  - **주식 요약은 `(종목, 원본 거래일, 범위)` 단위로 두 건**이다 — `PRE_MARKET`과 `FULL`. 범위는 §C-2, 공시 조건은 §C-3, LLM 입력 기사 수 상한은 §C-7의 `max-items-per-summary`이며 **자르는 것은 호출부 책임**이라고 `NewsSummaryPromptDto` 주석에 적혀 있다.
  - **브리핑은 `(시장, 원본 거래일)` 단위로 1건**이고 `items`는 저장하지 않는다(FEED-009). 프롬프트 입력은 `MarketBriefingPromptDto`·`BriefingNewsItemDto`가 이미 있으므로 새로 만들지 않는다 — 브리핑은 시장 단일 질의라 기사마다 종목명을 붙인다.
  - **저장은 UPSERT가 아니라 존재 시 건너뜀이다** — 주식은 같은 서비스 날짜에 두 번 실행돼도 중복이 생기지 않아야 하고(배치 ⑤, #180 소유) 유니크 축이 V13에 이미 있다. 코인의 UPSERT는 7번 소유이며 규칙이 다르다(§C-9).
  - **서술이 `NONE`이면 `summary`가 `NULL`인 행을 남긴다**(§C-4·§C-8). 행을 만들지 않으면 `EMPTY`와 `UNAVAILABLE`이 구분되지 않는다 — 그 구분이 5·6번의 완료 조건이다.
  - **종목 단위 격리를 본문에 넣는다.** 위 제약대로 협력자 mock으로 특정 종목에서만 던지게 해 나머지 종목이 계속되는지 단정한다.
  - 검증 — 단위(격리·상한·구간) + `@DataJpaTest`(유니크 축) + 고정 `Clock` + Testcontainers 통합. **8개 이슈 공통 조건인 원장 불변이 이 항목 소유다** — 배치 전후로 주문·체결·계좌·잔액·보유·손익 테이블의 행이 변하지 않고 쓰기가 요약·브리핑 두 테이블 밖으로 나가지 않는다. 기존 `FeedbackBatchIntegrationTest`가 카드에 대해 같은 형태를 이미 갖고 있다.

- [ ] **5. Part C 조회 API — 종목 뉴스 목록·요약 (주식)**

  만들어 둔 요약과 그 시점의 기사 목록을 종목별로 돌려준다. **spec 012의 두 번째 컨트롤러다.**
  - 경로·응답 필드·오류 형식은 `docs/api-contracts.md`의 "종목 뉴스 목록·요약 조회" 행과 FEED-008이 정본이다. 클래스 이름은 §C-6(`InstrumentNewsController`·`InstrumentNewsQueryService`·`InstrumentNewsResponse`)이고 `items`는 **`NewsItem` 재사용**이다.
  - 상태값과 **판정 순서는 §C-4의 표 그대로**다. 순서를 바꾸면 두 조건이 동시에 성립하는 구간(세션 미준비 + 09:00 이전)에서 값이 갈린다.
  - `items` 범위와 `summaryScope` 대응은 §C-2, 공시는 §C-3, 재생 시각 게이트와 클램프는 §C-5·§노출 판정이다. **하한은 요약과 같고 상한만 재생 시각까지 넓다** — 09:00에서 자르면 "재생 시각을 지난 기사만 노출"이 깨진다.
  - 목록 상한은 `max-items-per-news-list`, 정렬은 **발행시각 내림차순 + `id` 내림차순**이다. 파인더 이름에 두 키를 모두 드러내고 `api-contracts.md`에 문장으로 적는다.
  - `MarketNewsItemRepository`·`InstrumentNewsSummaryRepository`에 **이 조회가 요구하는 파인더만** 더한다.
  - **코인 분기는 7번 소유다.** 이 항목은 주식 경로만 만들고, 코인 종목으로 호출됐을 때의 동작을 임의로 정하지 않는다 — 7번이 `ROLLING_24H`와 최신 1행 조회를 더한다.
  - 컨트롤러가 생기므로 **같은 커밋에서 `api-contracts.md`의 해당 소절에서 `(계획)`을 걷고 `api-routes.md`의 2차 계획 절에서 그 행을 실제 라우트 표로 옮긴다** (CLAUDE.md 규칙 7).
  - 검증 — `@WebMvcTest`(계약·401) + 단위(상태값 판정) + 고정 `Clock` + Testcontainers 통합(게이트·범위). **완료 조건 5건이 이 항목 소유다.** 게이트 ⑧의 Part C 절반, 게이트 ⑨(범위 — 다른 원본 거래일 기사가 섞이지 않는다), 게이트 ⑩(장중 조회에서 `summaryScope`가 전장 범위이고 요약에 오후 기사 내용이 없다, 장 마감 이후 재조회하면 `FULL`), 상태값 ②(세션 미준비 시 `NOT_YET`), 상태값 ③(행 없음 + 기사 있음 → `EMPTY`이고 `items` 채움), 상태값 ④(행 있고 `summary`가 `NULL` → `UNAVAILABLE`이고 `items` 채움). 401은 배정 건수에 넣지 않지만 이 엔드포인트에 적용한다.

- [ ] **6. Part D 조회 API — 개장 전 브리핑 (주식) + 두 API 대칭 검증**

  시장 단위 브리핑을 돌려주고 **Part C와 하한이 같은지를 여기서 닫는다.** 두 API가 모두 존재해야 성립하는 조건이라 마지막 조회 항목이 소유한다.
  - 경로·응답 필드·오류 형식은 `docs/api-contracts.md`의 "개장 전 브리핑 조회" 행과 FEED-009가 정본이다. 클래스 이름은 §C-6(`MarketBriefingController`·`MarketBriefingService`·`MarketBriefingResponse`)이다.
  - **`items`는 저장하지 않고 조회 시 같은 구간 질의로 다시 만든다**(FEED-009). 상한 `max-items-per-briefing`과 발행시각 내림차순 + `id` 내림차순이 이 질의에 걸린다.
  - **저장된 행만으로는 `EMPTY`와 `UNAVAILABLE`이 구분되지 않는다**(둘 다 `summary=NULL`). 조회 시 행 존재와 `items` 개수로 §C-4의 순서대로 판정한다.
  - **Part D `items` 요소는 `dto/response/`의 최상위 record `BriefingNewsItem`이다** (§C-6, 2026-08-04 확정). 계약이 `NewsItem`의 다섯 값에 `instrumentId`·`symbol`·`name`을 **평평하게** 더한 여덟 값을 요구하므로 `NewsItem` 재사용으로는 계약을 만족할 수 없고, **중첩 필드로 감싸면 JSON 모양이 계약과 달라진다.** `NewsItem`은 그대로 두고 Part C가 계속 쓴다 — 두 항목 record의 공존이 의도된 형태다.
  - **이름에 `~ListItemResponse`를 붙이지 않는다.** `docs/conventions.md` DTO 표 각주가 spec 012 항목 record의 이름을 §C-6에 위임했고, 같은 응답군에서 접미사가 섞이면 그 위임이 무의미해진다.
  - `MarketBriefingRepository`에 이 조회가 요구하는 파인더만 더한다.
  - **코인 분기는 7번 소유다.** 주식 경로만 만든다.
  - 같은 커밋에서 `api-contracts.md`의 `(계획)`을 걷고 `api-routes.md`의 행을 옮긴다 (CLAUDE.md 규칙 7).
  - 검증 — `@WebMvcTest`(계약·401·`market` 파라미터) + 고정 `Clock` + Testcontainers 통합(게이트·상태값). **완료 조건 5건이 이 항목 소유다.** 게이트 ⑦(**개장 전 한 시각에 두 API를 모두 호출**해 어느 쪽에서도 전장 기사가 나오지 않는다), 게이트 ⑧의 Part D 절반, 게이트 ⑪(브리핑에 장중 기사가 한 건도 없다), 게이트 ⑫(`D` 접수 공시가 개장 직후 조회에 나오지 않고 `FULL`에서만 나오며, `D-1` 접수 공시는 개장 시각에 브리핑·전장 요약·`items` 셋 모두에 나온다 — 5번이 `items` 절반을 선확인하고 여기서 확정한다), 상태값 ①(6단계 판정, 특히 **세션 미준비 시각의 조회가 `EMPTY`·`originTradeDate=null`**이고 `NOT_YET`이 아니다), API 계약(`market`이 없거나 허용 값 밖이면 400). 401은 배정 건수에 넣지 않지만 이 엔드포인트에 적용한다.

- [ ] **7. 코인 경로 — 매시 배치 생성과 두 조회의 코인 분기**

  코인은 '개장 전'도 '거래일 경계'도 없어 한 범위만 쓰고 재생세션과 무관하다. **이 항목의 코인 작업은 요약·브리핑뿐이다** — 탐지·감시는 `plan.md` 8번이다.
  - 진입점은 §C-6의 `CryptoFeedbackBatchService`이고 크론 키·값·`zone`은 §C-1이다. **`cron` 기반 `@Scheduled`에는 `zone`을 붙인다** — 빠뜨리면 배포 JVM 기본이 UTC라 예외도 로그도 없이 엉뚱한 시각에 돈다.
  - **`spring.task.scheduling.pool.size`를 이 이슈가 더하는 스케줄 수(1개)만큼 올린다**(§C-1). 현재 값은 8이고 기본 프로필의 실제 활성 `@Scheduled`도 8개(기존 5 + 수집 2 + 개장 전 배치 1)이므로 이번에 9다. **갱신할 자리가 둘이다** — `application.yml` 주석의 개수 계산과 `application-crypto-real.yml` 주석. 한쪽만 고치면 다음 이슈가 어느 쪽을 근거로 셀지 갈린다(이슈 #125가 그 사고다).
  - **범위는 `ROLLING_24H` 하나뿐**이고 `PRE_MARKET`/`FULL`을 쓰지 않는다(§C-2·FEED-008). 저장은 **UPSERT로 하루 1행**이며 `origin_trade_date`는 **배치 실행 시점의 KST 날짜**다(§C-9) — 이 행에는 `occurred_at`이 없어 카드와 규칙이 다르다.
  - **직전 생성 이후 새 기사가 없으면 LLM을 부르지 않는다.** 판정 기준은 `market_news_items.created_at`이며 **`published_at`으로 비교하면 안 된다** — 수집 주기 때문에 늦게 저장된 기사가 영원히 요약에 못 들어간다(FEED-008).
  - **조회는 `generated_at` 최신 1행**이다. "오늘 날짜 행"으로 찾으면 자정 직후와 배치 실패 시각마다 빈다. 5·6번이 만든 두 조회 서비스에 이 분기를 더하고 **주식 경로의 게이트·판정 순서를 건드리지 않는다.**
  - 코인 `items`의 24시간 창은 조회 시각 기준이고 요약은 마지막 배치 기준이라 최대 65분 어긋난다 — **이 어긋남은 허용된 동작이다**(FEED-008). 맞추려고 조회 시 생성으로 되돌아가지 않는다.
  - 검증 — 단위(재생성 판정·범위) + `@DataJpaTest`(UPSERT 축·최신 1행) + 고정 `Clock` + Testcontainers 통합. **완료 조건 5건이 이 항목 소유다.** 배치 ⑨(조회를 반복해도 LLM 호출이 늘지 않는다), ⑩(새 기사 없으면 미호출, 기준은 `created_at`), ⑪(`generated_at` 최신 1행 — 자정 직후와 배치 실패 시각에도 비지 않는다), ⑫(UPSERT로 하루 1행, `origin_trade_date`는 실행 시점), ⑬(한 범위만 쓴다). 원장 불변은 4번이 소유하되 **코인 배치에서도 쓰기가 두 테이블 밖으로 나가지 않는지 여기서 재확인한다.**

- [ ] **8. 문서 동기화 — `api-routes.md`·`api-contracts.md` 대조**

  /feature 마무리의 planner(동기화 모드)가 **실제 컨트롤러 매핑을 근거로** 두 문서를 맞춘다 (CLAUDE.md 규칙 7). 5·6번이 각자 커밋에서 자기 소절을 이미 갱신했으므로 여기서는 대조와 잔여 정리다.
  - `api-routes.md`의 **2차 계획 라우트 절에 `post-sell` 한 행만 남아야** 한다. 절 머리말의 "아래 3개 경로"도 함께 고친다 — 개수를 고치지 않으면 다음 이슈가 그 문장을 근거로 센다.
  - `api-contracts.md`의 Part C·D 소절 제목에서 `(계획)`이 걷혔는지, 정렬 타이브레이커 문장이 두 소절에 모두 들어갔는지 확인한다. **표시가 남은 소절만 블랙박스 QA 근거에서 제외되므로** 남으면 두 API가 QA 대상에서 빠진다.
  - 두 문서는 **항상 같은 커밋에서 함께 맞춘다.**

## 완료 조건 소유

이슈 #188에 배정된 16건과 8개 이슈 공통 조건 1건이 어디서 검증되는지다 (`plan.md` §완료 조건 배정의 5번 행). **합계 17건이고 아래 표의 행 수와 같다.**

| # | 완료 조건 (`spec.md` §완료 조건) | 항목 |
|---|---|---|
| 1 | (게이트 ⑦) Part C와 Part D의 전장 하한이 같다 — 개장 전 한 시각에 두 API 호출 | **6** |
| 2 | (게이트 ⑧) Part C·D 목록에 `max-items-*` 상한과 발행시각 내림차순 정렬 | **5**(Part C) + **6**(Part D), 상한 값은 1번이 세운다 |
| 3 | (게이트 ⑨) Part C `items`가 §C-2 범위대로이고 다른 원본 거래일 기사가 안 섞인다 | **5** |
| 4 | (게이트 ⑩) Part C 요약이 장중에 장중 기사를 언급하지 않고 장 마감 후 `FULL`로 바뀐다 | **5** (요약 두 건은 4번이 만든다) |
| 5 | (게이트 ⑪) Part D 브리핑에 장중 기사가 한 건도 없다 | **6** |
| 6 | (게이트 ⑫) `D` 접수 공시는 `FULL`만, `D-1` 접수 공시는 개장 시각에 셋 모두 | 5에서 `items` 선확인, **6에서 확정** |
| 7 | (상태값 ①) Part D 6단계 판정 — 세션 미준비는 `EMPTY`·`originTradeDate=null` | **6** |
| 8 | (상태값 ②) Part C는 세션 미준비 시 `NOT_YET` (의도된 차이) | **5** |
| 9 | (상태값 ③) 행 없음 + 기사 있음 → `EMPTY`이고 `items` 채움 | **5** |
| 10 | (상태값 ④) 행 있고 `summary`가 `NULL` → `UNAVAILABLE`이고 `items` 채움 | **5** (`NULL` 행을 남기는 것은 4번) |
| 11 | (배치 ⑨) 코인 요약·브리핑이 배치에서만 생성된다 | **7** |
| 12 | (배치 ⑩) 직전 생성 이후 수집된 기사가 없으면 LLM 미호출 (`created_at` 기준) | **7** |
| 13 | (배치 ⑪) 코인 조회가 `generated_at` 최신 1행을 반환한다 | **7** |
| 14 | (배치 ⑫) 코인 UPSERT로 하루 1행, `origin_trade_date`는 실행 시점 | **7** |
| 15 | (배치 ⑬) 코인 Part C가 한 범위만 쓰고 주식의 두 범위를 쓰지 않는다 | **7** |
| 16 | (API 계약) `market` 파라미터가 없거나 허용 값 밖이면 400 | **6** |
| 17 | (공통) 원장 불변 — 요약·브리핑 두 테이블 밖에 쓰지 않고 GET은 LLM·DB를 건드리지 않는다 | **4** (7에서 코인 배치 재확인, 5·6은 읽기 전용) |

항목별 소유 건수는 **4번 1건 · 5번 5건 · 6번 6건 · 7번 5건 = 17건**이다. 1·2·3·8번은 단독 소유가 없다 — 1번은 값과 상수를 세우고, 2번은 실행 환경을 고정하며, 3번은 판정만 하고, 8번은 문서 대조다.

**인증 없이 호출하면 401**은 네 엔드포인트 공통이며 이 이슈가 만드는 두 엔드포인트에도 적용하되 **배정 건수에 넣지 않는다**(#180과 같은 형태).

## 이 이슈에서 하지 않는 것

- **LLM 서술 생성·후검증·템플릿 폴백** (#147, 머지됨). `NarrativeService`를 **주입해 쓰기만** 한다
- **V13·엔티티·리포지토리 골격** (#160, 머지됨). **머지된 마이그레이션을 수정하지 않는다** (ADR-0004)
- **뉴스·공시 수집 파이프라인** (#167, 머지됨). **이미 저장된 기사를 읽기만** 하고 수집기·수집 크론·질의어·제목 필터를 건드리지 않는다
- **주식 변동 탐지·근거 매칭·개장 전 배치 골격·카드 조회 API** (#180, 머지됨). **배치 골격의 호출 순서와 시그니처를 바꾸지 않는다** — 빈 자리의 본문만 채운다
- **코인 질의어·제목 필터 개선** (#179). 수집 품질 문제다
- **매도 직후 피드백 조회 API** (`plan.md` 6번), **반사실 시뮬레이션·집단 비교와 장 마감 집계 배치** (`plan.md` 7번)
- **코인 변동 탐지·실시간 감시·가격 스냅샷** (`plan.md` 8번). FEED-006의 코인 조회 분기도 8번 소유다
- **노출 게이트 ①~⑥·⑬~⑮, 배치 ①~⑧·⑭⑮, 상태값 ⑤~⑦, 매도 회고 16건, 문구 9건, 탐지 14건** — 배정은 `plan.md` §완료 조건 배정이 정본이다
