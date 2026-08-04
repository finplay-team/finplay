// application.yml의 feedback.news 목록 상한 3키가 spec 012 §C-7의 키 경로·값 그대로 존재하는지 검증한다.
package com.finplay.api.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

// NewsCollectionPropertiesTest는 record의 @DefaultValue만 보므로, application.yml의 키가 잘못된
// 위치·이름으로 들어가도 기본값에 가려 통과한다. §C-7은 "운영 중 값을 바꿀 때는 항상 이기는 yml만
// 고친다"를 확정했으므로 yml 쪽 키 경로가 실제로 그 경로인지도 단정해야 드리프트가 잡힌다.
//
// @SpringBootTest·Testcontainers를 쓰지 않는 이유는 FeedbackDetectionPropertiesYamlTest와 같다 —
// 여기서 보는 것은 "yml 파일의 키 경로와 값"뿐이라 DB·자동설정이 필요 없고, 컨테이너를 띄우면
// Docker가 없는 환경에서 설정 드리프트를 못 보게 된다. ConfigDataApplicationContextInitializer가
// SpringApplication 부트스트랩과 같은 방식으로 application.yml만 Environment에 얹어 준다.
class NewsCollectionPropertiesYamlTest {

	// §C-7 feedback.news 목록 상한 3종. 문자열로 두는 것은 yml에 적힌 표기 그대로를 비교하기 위해서다.
	private static final String SPEC_MAX_ITEMS_PER_NEWS_LIST = "50";

	private static final String SPEC_MAX_ITEMS_PER_BRIEFING = "30";

	private static final String SPEC_MAX_ITEMS_PER_SUMMARY = "30";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(NewsCollectionPropertiesConfig.class);

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

	// yml이 항상 이기므로 두 곳이 갈리면 실제 동작값은 yml 쪽이고 record의 @DefaultValue는 죽은 값이 된다.
	// 여기서 바인딩된 빈이 §C-7 값과 같은지까지 봐야 그 상태가 드러난다.
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
