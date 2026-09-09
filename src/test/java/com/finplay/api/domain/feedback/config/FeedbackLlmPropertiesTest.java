package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FeedbackLlmPropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(FeedbackLlmConfig.class);

	@Test
	@DisplayName("feedback.llm 설정을 하나도 주지 않아도 §C-7 기본값으로 바인딩된다")
	void bindsSpecDefaultsWhenNoFeedbackLlmPropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(FeedbackLlmProperties.class);

			FeedbackLlmProperties properties = context.getBean(FeedbackLlmProperties.class);
			assertThat(properties.model()).isEqualTo("gpt-5.4-mini");
			assertThat(properties.timeoutSeconds()).isEqualTo(20);
			assertThat(properties.maxTokens()).isEqualTo(1024);
			assertThat(properties.maxRegeneration()).isEqualTo(1);
			assertThat(properties.maxNarrativeRetry()).isEqualTo(3);
			assertThat(properties.maxJournalRegeneration()).isEqualTo(3);
		});
	}

	@Test
	@DisplayName("feedback.llm.* 케밥케이스 키를 주면 여섯 값이 모두 덮어써진다")
	void bindsEveryPropertyFromKebabCaseKeys() {
		contextRunner
			.withPropertyValues(
				"feedback.llm.model=gpt-4.1-mini",
				"feedback.llm.timeout-seconds=45",
				"feedback.llm.max-tokens=2048",
				"feedback.llm.max-regeneration=2",
				"feedback.llm.max-narrative-retry=5",
				"feedback.llm.max-journal-regeneration=7")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackLlmProperties properties = context.getBean(FeedbackLlmProperties.class);
				assertThat(properties.model()).isEqualTo("gpt-4.1-mini");
				assertThat(properties.timeoutSeconds()).isEqualTo(45);
				assertThat(properties.maxTokens()).isEqualTo(2048);
				assertThat(properties.maxRegeneration()).isEqualTo(2);
				assertThat(properties.maxNarrativeRetry()).isEqualTo(5);
				assertThat(properties.maxJournalRegeneration()).isEqualTo(7);
			});
	}

	@Test
	@DisplayName("일부 값만 덮어써도 나머지는 §C-7 기본값을 유지한다")
	void keepsSpecDefaultsForPropertiesThatAreNotGiven() {
		contextRunner
			.withPropertyValues("feedback.llm.max-regeneration=3")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackLlmProperties properties = context.getBean(FeedbackLlmProperties.class);
				assertThat(properties.maxRegeneration()).isEqualTo(3);
				assertThat(properties.model()).isEqualTo("gpt-5.4-mini");
				assertThat(properties.timeoutSeconds()).isEqualTo(20);
				assertThat(properties.maxTokens()).isEqualTo(1024);
				assertThat(properties.maxNarrativeRetry()).isEqualTo(3);
				assertThat(properties.maxJournalRegeneration()).isEqualTo(3);
			});
	}

	@Test
	@DisplayName("숫자 항목에 숫자가 아닌 값이 오면 기동이 실패한다 — 0으로 조용히 넘어가지 않는다")
	void failsFastWhenNumericPropertyIsNotANumber() {
		contextRunner
			.withPropertyValues("feedback.llm.timeout-seconds=twenty")
			.run(context -> assertThat(context).hasFailed());
	}
}
