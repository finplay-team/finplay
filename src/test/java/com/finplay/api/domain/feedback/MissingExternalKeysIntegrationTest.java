package com.finplay.api.domain.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.collector.DisclosureCollector;
import com.finplay.api.domain.feedback.collector.FakeDisclosureCollector;
import com.finplay.api.domain.feedback.collector.FakeNewsCollector;
import com.finplay.api.domain.feedback.collector.NewsCollector;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.service.NewsCollectionService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;

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
		assertThat(environment.getProperty("naver-search.client-id")).isEmpty();
		assertThat(environment.getProperty("naver-search.client-secret")).isEmpty();
		assertThat(environment.getProperty("dart.api-key")).isEmpty();
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
		assertThat(instrumentService.getInstrumentEntities(Market.STOCK)).isNotEmpty();
		assertThat(instrumentService.getInstrumentEntities(Market.CRYPTO)).isNotEmpty();

		long before = marketNewsItemRepository.count();

		assertThatCode(() -> {
			newsCollectionService.collectNews();
			newsCollectionService.collectDisclosures();
		}).doesNotThrowAnyException();

		assertThat(marketNewsItemRepository.count()).isEqualTo(before);
	}
}
