// application.yml의 feedback.news·naver-search·dart 블록이 실제 스프링 컨텍스트에서 spec 012 값·키 경로로
// 바인딩되는지 검증하는 통합 테스트다.
package com.finplay.api.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

// 단위 테스트(NewsCollectionPropertiesTest)는 record의 @DefaultValue만 확인하므로, application.yml의 키가
// 잘못된 위치·이름으로 들어가도 기본값에 가려 통과한다. @Scheduled(cron = "${feedback.news.collect-cron}")는
// record가 아니라 Environment에서 값을 읽으므로(§C-1 선언 예시) 두 값이 갈리면 운영 크론만 조용히 바뀐다.
// 여기서는 기동 컨텍스트의 Environment에 키가 그 경로로 실제 존재하는지까지 단정한다 —
// FeedbackLlmPropertiesIntegrationTest와 같은 의도다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class NewsCollectionPropertiesIntegrationTest {

	// §C-1 뉴스 수집 크론 (24시간 30분 간격)
	private static final String SPEC_COLLECT_CRON = "0 0/30 * * * *";

	// §C-1 공시 수집 크론
	private static final String SPEC_DISCLOSURE_CRON = "0 0/30 8-20 * * MON-FRI";

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
	@DisplayName("기동한 컨텍스트의 FeedbackNewsProperties 빈이 §C-1 크론 값을 갖는다")
	void feedbackNewsPropertiesBeanHoldsSpecCronValues() {
		assertThat(newsProperties.collectCron()).isEqualTo(SPEC_COLLECT_CRON);
		assertThat(newsProperties.disclosureCron()).isEqualTo(SPEC_DISCLOSURE_CRON);
	}

	@Test
	@DisplayName("application.yml에 feedback.news 두 크론 키가 §C-1 값으로 실제 존재한다")
	void applicationYmlDeclaresEveryFeedbackNewsCronKey() {
		assertThat(environment.getProperty("feedback.news.collect-cron"))
			.isEqualTo(SPEC_COLLECT_CRON);
		assertThat(environment.getProperty("feedback.news.disclosure-cron"))
			.isEqualTo(SPEC_DISCLOSURE_CRON);
	}

	// 근거 매칭 3키(이슈 #180 항목 3)는 크론과 달리 Environment가 아니라 record 빈으로 읽지만, §C-7이
	// "yml과 @DefaultValue 양쪽에 값을 둔다"로 정했으므로 두 곳이 갈리지 않는지 여기서 대조한다.
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

	// 시크릿이라 값 자체는 단정하지 않는다(환경마다 다르다). 대신 §C-7이 확정한 키 경로가 Environment에
	// 실제 존재하고, record 빈이 그 경로에서 값을 받아 오는지를 본다 — 키 경로가 갈리면 여기서 깨진다.
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
