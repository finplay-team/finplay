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
class FeedbackLlmPropertiesIntegrationTest {

	private final FeedbackLlmProperties properties;

	private final Environment environment;

	@Autowired
	FeedbackLlmPropertiesIntegrationTest(
		FeedbackLlmProperties properties, Environment environment) {
		this.properties = properties;
		this.environment = environment;
	}

	@Test
	@DisplayName("기동한 컨텍스트의 FeedbackLlmProperties 빈이 §C-7 값을 갖는다")
	void feedbackLlmPropertiesBeanHoldsSpecValues() {
		assertThat(properties.model()).isEqualTo("gpt-5.4-mini");
		assertThat(properties.timeoutSeconds()).isEqualTo(20);
		assertThat(properties.maxTokens()).isEqualTo(1024);
		assertThat(properties.maxRegeneration()).isEqualTo(1);
		assertThat(properties.maxNarrativeRetry()).isEqualTo(3);
		assertThat(properties.maxJournalRegeneration()).isEqualTo(3);
	}

	@Test
	@DisplayName("application.yml에 feedback.llm 여섯 키가 §C-7 값으로 실제 존재한다")
	void applicationYmlDeclaresEveryFeedbackLlmKey() {
		assertThat(environment.getProperty("feedback.llm.model")).isEqualTo("gpt-5.4-mini");
		assertThat(environment.getProperty("feedback.llm.timeout-seconds")).isEqualTo("20");
		assertThat(environment.getProperty("feedback.llm.max-tokens")).isEqualTo("1024");
		assertThat(environment.getProperty("feedback.llm.max-regeneration")).isEqualTo("1");
		assertThat(environment.getProperty("feedback.llm.max-narrative-retry")).isEqualTo("3");
		assertThat(environment.getProperty("feedback.llm.max-journal-regeneration")).isEqualTo("3");
	}
}
