// 외부 API 키 4종이 하나도 없는 조합에서 기동·수집이 성립하는지 한자리에서 고정하는 통합 테스트다.
package com.finplay.api.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.collector.DisclosureCollector;
import com.finplay.api.feedback.collector.FakeDisclosureCollector;
import com.finplay.api.feedback.collector.FakeNewsCollector;
import com.finplay.api.feedback.collector.NewsCollector;
import com.finplay.api.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.feedback.service.NewsCollectionService;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;

// tasks.md 6번의 완료 조건 "외부 API 키(네이버 검색·DART·OpenAI) 없이 ./gradlew build 통과"가 이 파일의 목표다.
// 1~5번이 각자 부분적으로 통과시키지만 네 키가 모두 없는 조합을 한자리에서 고정하는 것은 여기다.
//
// 앞선 테스트와 겹치지 않게 범위를 좁혔다.
//  - NewsCollectorProfileTest·DisclosureCollectorProfileTest는 ApplicationContextRunner 슬라이스로 수집기를
//    하나씩 본다. 여기서는 실제 애플리케이션 컨텍스트에서 넷이 동시에 없는 상태를 본다.
//  - NewsCollectionIntegrationTest는 수집기를 @MockitoBean으로 바꿔 저장 경로를 태운다(Fake는 빈 목록이 계약이라
//    저장을 검증할 수 없기 때문이다). 여기서는 반대로 진짜 Fake 빈이 잡힌 채 수집 종단이 빈 결과로 끝나는지 본다.
//  - NewsCollectionPropertiesIntegrationTest는 자격증명 3종의 키 경로가 존재하는지를 본다. 여기서는 OpenAI까지
//    더한 4종이 "없는 값"으로 바인딩된 채 기동하는지를 본다.
//
// 키를 프로퍼티로 명시해 비우는 이유는 결정성 때문이다. 셸에 NAVER_SEARCH_* 등을 export 해 둔 개발자 장비에서는
// 값이 실제로 들어와 단정이 흔들린다. 여기서 주는 값은 환경변수가 없을 때 application.yml이 만들어 내는 값과 같다.
//
// OpenAI만 빈 문자열이 아니라 자리표시자인 것은 의도된 차이다. openai-java-core SDK가 빈 문자열을 거부해
// 컨텍스트가 아예 기동하지 않기 때문이며(ai/agent-mistakes.md 2026-08-03), application.yml이 같은 이유로
// ${OPENAI_API_KEY:not-configured}를 쓴다.
//
// 공유 컨테이너를 더럽히지 않도록 클래스 트랜잭션으로 감싼다 (NewsCollectionIntegrationTest 선례).
@SpringBootTest(properties = {
	"naver-search.client-id=",
	"naver-search.client-secret=",
	"dart.api-key=",
	"spring.ai.openai.api-key=not-configured"
})
@Transactional
@Import(TestcontainersConfiguration.class)
class MissingExternalKeysIntegrationTest {

	@Autowired
	private Environment environment;

	@Autowired
	private NewsCollector newsCollector;

	@Autowired
	private DisclosureCollector disclosureCollector;

	@Autowired
	private NewsCollectionService newsCollectionService;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private InstrumentService instrumentService;

	@Test
	@DisplayName("네이버 검색·DART·OpenAI 키가 하나도 없어도 애플리케이션 컨텍스트가 기동한다")
	void contextStartsWithoutAnyExternalApiKey() {
		// 이 메서드가 실행된다는 것 자체가 기동 성공이다. 네 키가 실제로 비어 있는 상태였는지를 함께 못 박는다.
		assertThat(environment.getProperty("naver-search.client-id")).isEmpty();
		assertThat(environment.getProperty("naver-search.client-secret")).isEmpty();
		assertThat(environment.getProperty("dart.api-key")).isEmpty();
		// OpenAI만 빈 문자열이 아니라 자리표시자다 — SDK가 빈 문자열을 거부한다.
		assertThat(environment.getProperty("spring.ai.openai.api-key")).isEqualTo("not-configured");
	}

	@Test
	@DisplayName("키가 없으면 뉴스·공시 수집기가 모두 Fake 구현으로 주입된다")
	void bothCollectorsFallBackToFakeImplementations() {
		assertThat(newsCollector).isInstanceOf(FakeNewsCollector.class);
		assertThat(disclosureCollector).isInstanceOf(FakeDisclosureCollector.class);
	}

	@Test
	@DisplayName("키가 없으면 뉴스·공시 수집이 예외 없이 한 건도 저장하지 않고 끝난다")
	void collectionEndsEmptyWithoutAnyExternalApiKey() {
		// 수집할 종목이 0건이면 아래 단정이 아무것도 검증하지 않고 통과한다 — 빈 루프를 성공으로 읽지 않도록 먼저 막는다.
		assertThat(instrumentService.getInstrumentEntities(Market.STOCK)).isNotEmpty();
		assertThat(instrumentService.getInstrumentEntities(Market.CRYPTO)).isNotEmpty();

		long before = marketNewsItemRepository.count();

		// 수집 진입점 2종을 실제로 실행한다 — 키가 없을 때 예외가 나지 않는 것까지가 완료 조건이다.
		assertThatCode(() -> {
			newsCollectionService.collectNews();
			newsCollectionService.collectDisclosures();
		}).doesNotThrowAnyException();

		assertThat(marketNewsItemRepository.count()).isEqualTo(before);
	}
}
