# ADR-0023: 시세 데이터 RestClient는 공유 빈이 아니라 RestClient.builder()를 직접 호출한다

- 상태: 승인됨
- 날짜: 2026-08-14
- 관계: 이슈 #376, PR #377. `BithumbRestTickerPoller`·`BithumbRestCandleProvider`·`KisRestClientConfig` 세 곳에 있던 동일한 설명 주석(PR #377 리뷰 2라운드 권장③)을 이 ADR로 단일화한다.

## 맥락

`springdoc-openapi`·`spring-ai-starter-model-openai`·`jjwt-jackson`이 각각 Jackson 2(`com.fasterxml.jackson`)를 전이 의존성으로 끌어와, Spring Boot 4.1 기본인 Jackson 3(`tools.jackson`)와 앱 클래스패스에 공존한다.

DI로 주입받는 공유 `RestClient.Builder` 빈은 `ApplicationContext`에 등록된 모든 `HttpMessageConverter` 빈을 반영해 구성되므로, 이 두 Jackson 버전이 공존하는 상태에서는 JSON 응답에 어떤 컨버터가 선택될지 예측할 수 없다. 실제 운영에서 `BithumbRestTickerPoller`가 이 경로로 빗썸 ticker 응답 파싱에 100% 실패해 대부분의 코인이 STALE로 표시되는 장애로 이어졌다(이슈 #376).

`RestClient.builder()`를 클래스 안에서 직접 호출하면 공유 빈의 컨텍스트 구성과 무관하게, 클래스패스 기준으로 Jackson 3 컨버터가 결정적으로 선택된다.

## 결정

- **Jackson으로 외부 시세 API 응답을 역직렬화하는 컴포넌트는 공유 `RestClient.Builder` 빈을 DI로 받지 않고, `RestClient.builder()`를 그 컴포넌트 안에서 직접 호출한다.** 대상: `BithumbRestTickerPoller`, `BithumbRestCandleProvider`, `KisRestClientConfig.kisRestClient()`.
- 이 예외는 "시세 데이터 파싱"이라는 좁은 범주에만 적용한다. `NaverNewsCollector`·`DartDisclosureCollector`·OAuth 콜백 제공자·이메일 발송 등 그 외 `RestClient.Builder` 소비자는 저장소의 기존 DI 관례를 그대로 따른다 — 이번에 실측으로 같은 위험이 확인된 것은 위 세 컴포넌트뿐이다.
- 앱 전역 `RestClientCustomizer`로 공유 빈 자체의 컨버터 우선순위를 강제하는 대안은 채택하지 않았다 — 실제로 어떤 컨버터가 충돌하는지 로그로 확정하지 못한 상태에서 전역 빈을 바꾸면 이 세 컴포넌트 밖의 소비자에도 영향이 퍼져 검증되지 않은 회귀 위험을 만든다.

## 근거

- 세 컴포넌트 모두 기존 유닛 테스트가 이미 `RestClient.builder()` 직접 호출 패턴(또는 사전에 완성된 `RestClient` 주입)을 검증하고 있어, 이 방향이 테스트 인프라와 충돌하지 않는다.
- 예외를 "시세 데이터 파싱"으로 좁히면, 향후 이 범주에 새 컴포넌트가 추가될 때만 같은 패턴을 적용하면 되고, OAuth·이메일처럼 이번에 위험이 확인되지 않은 영역까지 검증 없이 건드리지 않는다.

## 결과

- 새로 시세 API(빗썸·KIS 등)를 Jackson으로 파싱하는 REST 클라이언트를 추가할 때는 공유 `RestClient.Builder`를 주입받지 말고 이 ADR의 패턴을 따른다.
- Jackson 2/3 클래스패스 공존 자체는 해소되지 않았다 — springdoc·spring-ai·jjwt 각각이 Jackson 3 지원판을 내놓기 전까지는 계속 공존한다. 다른 도메인에서 같은 증상(응답 파싱 실패)이 발견되면 이 ADR의 대상 목록에 추가하는 새 PR로 반영한다.
- 실제로 컨버터 선택이 어떻게 갈리는지는 운영 로그로 확정하지 못했다 — 로그 접근 권한이 생기면 한 번 검증해 둔다(이슈 #376 후속).
