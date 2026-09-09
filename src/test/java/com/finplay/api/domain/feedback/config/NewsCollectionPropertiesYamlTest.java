package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

class NewsCollectionPropertiesYamlTest {

	private static final String SPEC_COLLECT_CRON = "0 0/30 * * * *";

	private static final String SPEC_DISCLOSURE_CRON = "0 0/30 8-20 * * MON-FRI";

	private static final String SPEC_MAX_ITEMS_PER_NEWS_LIST = "50";

	private static final String SPEC_MAX_ITEMS_PER_BRIEFING = "30";

	private static final String SPEC_MAX_ITEMS_PER_SUMMARY = "30";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withSystemProperties("spring.config.additional-location=")
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(NewsCollectionPropertiesConfig.class);

	@Test
	@DisplayName("application.yml에 feedback.news 두 크론 키가 §C-1 값으로 실제 존재한다")
	void applicationYmlDeclaresEveryFeedbackNewsCronKey() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			assertThat(environment.getProperty("feedback.news.collect-cron"))
				.isEqualTo(SPEC_COLLECT_CRON);
			assertThat(environment.getProperty("feedback.news.disclosure-cron"))
				.isEqualTo(SPEC_DISCLOSURE_CRON);
		});
	}

	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 §C-1 크론 값을 갖는다")
	void boundBeanMatchesSpecCronValuesWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			FeedbackNewsProperties properties = context.getBean(FeedbackNewsProperties.class);
			assertThat(properties.collectCron()).isEqualTo(SPEC_COLLECT_CRON);
			assertThat(properties.disclosureCron()).isEqualTo(SPEC_DISCLOSURE_CRON);
		});
	}

	@Test
	@DisplayName("application.yml에 feedback.news 목록 상한 3키가 §C-7 값으로 실제 존재한다")
	void applicationYmlDeclaresEveryFeedbackNewsItemLimitKey() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			assertThat(environment.getProperty("feedback.news.max-items-per-news-list"))
				.isEqualTo(SPEC_MAX_ITEMS_PER_NEWS_LIST);
			assertThat(environment.getProperty("feedback.news.max-items-per-briefing"))
				.isEqualTo(SPEC_MAX_ITEMS_PER_BRIEFING);
			assertThat(environment.getProperty("feedback.news.max-items-per-summary"))
				.isEqualTo(SPEC_MAX_ITEMS_PER_SUMMARY);
		});
	}

	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 record 기본값과 같은 §C-7 목록 상한을 갖는다")
	void boundBeanMatchesSpecItemLimitsWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			FeedbackNewsProperties properties = context.getBean(FeedbackNewsProperties.class);
			assertThat(properties.maxItemsPerNewsList())
				.isEqualTo(Integer.parseInt(SPEC_MAX_ITEMS_PER_NEWS_LIST));
			assertThat(properties.maxItemsPerBriefing())
				.isEqualTo(Integer.parseInt(SPEC_MAX_ITEMS_PER_BRIEFING));
			assertThat(properties.maxItemsPerSummary())
				.isEqualTo(Integer.parseInt(SPEC_MAX_ITEMS_PER_SUMMARY));
		});
	}
}
