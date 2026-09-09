package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class NewsCollectionPropertiesIntegrationTest {

	private final FeedbackNewsProperties newsProperties;

	private final NaverSearchProperties naverSearchProperties;

	private final DartProperties dartProperties;

	private final Environment environment;

	@Autowired
	NewsCollectionPropertiesIntegrationTest(
		FeedbackNewsProperties newsProperties,
		NaverSearchProperties naverSearchProperties,
		DartProperties dartProperties,
		Environment environment) {
		this.newsProperties = newsProperties;
		this.naverSearchProperties = naverSearchProperties;
		this.dartProperties = dartProperties;
		this.environment = environment;
	}

	@Test
	@DisplayName("application.yml에 feedback.news 근거 매칭 3키가 §C-7 값으로 실제 존재한다")
	void applicationYmlDeclaresEveryFeedbackNewsMatchingKey() {
		assertThat(environment.getProperty("feedback.news.match-before-minutes")).isEqualTo("30");
		assertThat(environment.getProperty("feedback.news.match-after-minutes")).isEqualTo("5");
		assertThat(environment.getProperty("feedback.news.max-sources-per-card")).isEqualTo("5");
	}

	@Test
	@DisplayName("기동한 컨텍스트의 FeedbackNewsProperties 빈이 §C-7 근거 매칭 값을 갖는다")
	void feedbackNewsPropertiesBeanHoldsSpecMatchingValues() {
		assertThat(newsProperties.matchBeforeMinutes()).isEqualTo(30);
		assertThat(newsProperties.matchAfterMinutes()).isEqualTo(5);
		assertThat(newsProperties.maxSourcesPerCard()).isEqualTo(5);
	}

	@Test
	@DisplayName("application.yml에 §C-7의 자격증명 3종 키 경로가 존재하고 record 빈이 그 값을 받는다")
	void applicationYmlDeclaresEveryCredentialKeyPath() {
		assertThat(environment.containsProperty("naver-search.client-id")).isTrue();
		assertThat(environment.containsProperty("naver-search.client-secret")).isTrue();
		assertThat(environment.containsProperty("dart.api-key")).isTrue();

		assertThat(naverSearchProperties.clientId())
			.isEqualTo(environment.getProperty("naver-search.client-id"));
		assertThat(naverSearchProperties.clientSecret())
			.isEqualTo(environment.getProperty("naver-search.client-secret"));
		assertThat(dartProperties.apiKey()).isEqualTo(environment.getProperty("dart.api-key"));
	}
}
