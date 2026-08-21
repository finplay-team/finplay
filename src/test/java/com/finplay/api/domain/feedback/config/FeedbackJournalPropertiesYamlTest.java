// application.yml의 feedback.journal 블록이 spec 012 §C-7의 키 경로·값 그대로 존재하는지 검증한다.
package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

// FeedbackJournalPropertiesTest는 record의 @DefaultValue만 보므로, application.yml의 키가 잘못된
// 위치·이름으로 들어가도 기본값에 가려 통과한다. §C-7은 "운영 중 값을 바꿀 때는 항상 이기는 yml만
// 고친다"를 확정했으므로 yml 쪽 키 경로가 실제로 그 경로인지도 단정해야 드리프트가 잡힌다.
//
// FeedbackDetectionPropertiesYamlTest와 같은 형식이다 — @SpringBootTest·Testcontainers를 쓰지 않는다.
// 이 항목이 검증하는 것은 "yml 파일의 키 경로와 값"뿐이라 DB·자동설정이 필요 없고, 컨테이너를 띄우면
// Docker가 없는 환경에서 설정 드리프트를 못 보게 된다. ConfigDataApplicationContextInitializer가
// SpringApplication 부트스트랩과 같은 방식으로 application.yml만 Environment에 얹어 준다.
class FeedbackJournalPropertiesYamlTest {

	// §C-7 feedback.journal 블록. 문자열로 두는 것은 yml에 적힌 표기 그대로를 비교하기 위해서다.
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

	// yml이 항상 이기므로 두 곳이 갈리면 실제 동작값은 yml 쪽이고 record의 @DefaultValue는 죽은 값이 된다.
	// 여기서 바인딩된 빈이 §C-7 값과 같은지까지 봐야 그 상태가 드러난다.
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
