package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

class FeedbackJournalPropertiesYamlTest {

	private static final String SPEC_MAX_BUY_JOURNALS = "3";

	private static final String SPEC_MAX_JOURNAL_CHARS = "500";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(FeedbackJournalConfig.class);

	@Test
	@DisplayName("application.yml에 feedback.journal 두 키가 §C-7 값으로 실제 존재한다")
	void applicationYmlDeclaresEveryFeedbackJournalKey() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			assertThat(environment.getProperty("feedback.journal.max-buy-journals"))
				.isEqualTo(SPEC_MAX_BUY_JOURNALS);
			assertThat(environment.getProperty("feedback.journal.max-journal-chars"))
				.isEqualTo(SPEC_MAX_JOURNAL_CHARS);
		});
	}

	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 record 기본값과 같은 §C-7 값을 갖는다")
	void boundBeanMatchesSpecValuesWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			FeedbackJournalProperties properties = context.getBean(FeedbackJournalProperties.class);
			assertThat(properties.maxBuyJournals()).isEqualTo(Integer.parseInt(SPEC_MAX_BUY_JOURNALS));
			assertThat(properties.maxJournalChars()).isEqualTo(Integer.parseInt(SPEC_MAX_JOURNAL_CHARS));
		});
	}
}
