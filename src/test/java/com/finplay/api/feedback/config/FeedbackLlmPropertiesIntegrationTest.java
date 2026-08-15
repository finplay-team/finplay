// application.yml의 feedback.llm 블록이 실제 스프링 컨텍스트에서 §C-7 값으로 바인딩되는지 검증하는 통합 테스트다.
package com.finplay.api.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;

// 단위 테스트(FeedbackLlmPropertiesTest)는 record의 @DefaultValue만 확인하므로, application.yml의 키가
// 잘못된 위치·이름으로 들어가도 기본값에 가려 통과한다. 여기서는 실제 기동 컨텍스트의 Environment에
// 여섯 키가 그 경로로 실제 존재하는지까지 단정해 "튜닝은 application.yml 수정"(§튜닝)이 성립함을 보장한다.
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
