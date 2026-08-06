// application.yml의 feedback.crypto 블록이 spec 012 §C-7의 키 경로·값 그대로 존재하는지, 그리고
// market.crypto.sigma-lookback-hours와 어긋나지 않는지 검증한다.
package com.finplay.api.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.market.config.MarketCryptoConfig;
import com.finplay.api.market.config.MarketCryptoProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

// FeedbackDetectionPropertiesYamlTest와 같은 짝이다 — FeedbackCryptoPropertiesTest는 record의
// @DefaultValue만 보므로, application.yml의 키가 잘못된 위치·이름으로 들어가도 기본값에 가려 통과한다.
// §C-7은 "운영 중 값을 바꿀 때는 항상 이기는 yml만 고친다"를 확정했으므로 yml 쪽 키 경로가 실제로 그
// 경로인지도 단정해야 드리프트가 잡힌다.
//
// ConfigDataApplicationContextInitializer가 SpringApplication 부트스트랩과 같은 방식으로
// application.yml만 Environment에 얹어 준다 — Docker 없는 환경에서도 돈다.
//
// FeedbackCryptoPropertiesTest와 같은 이유로 로컬 테스트 전용 설정을 쓴다(production 등록 설정 클래스가
// 없다 — 아래 클래스 주석 참조).
class FeedbackCryptoPropertiesYamlTest {

	// §C-7 feedback.crypto 블록. 문자열로 두는 것은 yml에 적힌 표기 그대로를 비교하기 위해서다.
	private static final String SPEC_COOLDOWN_MINUTES = "30";

	private static final String SPEC_DAILY_LIMIT = "6";

	private static final String SPEC_ROLLING_WINDOW_MINUTES = "5";

	private static final String SPEC_SIGMA_LOOKBACK_HOURS = "24";

	private static final String SPEC_MIN_SAMPLE_COUNT = "100";

	private static final String SPEC_MATCH_BEFORE_MINUTES = "35";

	private static final String SPEC_WATCH_LOCK_TTL_SECONDS = "45";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(
			FeedbackCryptoPropertiesTest.LocalFeedbackCryptoConfig.class, MarketCryptoConfig.class);

	@Test
	@DisplayName("application.yml에 feedback.crypto 일곱 키가 §C-7 값으로 실제 존재한다")
	void applicationYmlDeclaresEveryFeedbackCryptoKey() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			assertThat(environment.getProperty("feedback.crypto.cooldown-minutes"))
				.isEqualTo(SPEC_COOLDOWN_MINUTES);
			assertThat(environment.getProperty("feedback.crypto.daily-limit"))
				.isEqualTo(SPEC_DAILY_LIMIT);
			assertThat(environment.getProperty("feedback.crypto.rolling-window-minutes"))
				.isEqualTo(SPEC_ROLLING_WINDOW_MINUTES);
			assertThat(environment.getProperty("feedback.crypto.sigma-lookback-hours"))
				.isEqualTo(SPEC_SIGMA_LOOKBACK_HOURS);
			assertThat(environment.getProperty("feedback.crypto.min-sample-count"))
				.isEqualTo(SPEC_MIN_SAMPLE_COUNT);
			assertThat(environment.getProperty("feedback.crypto.match-before-minutes"))
				.isEqualTo(SPEC_MATCH_BEFORE_MINUTES);
			assertThat(environment.getProperty("feedback.crypto.watch-lock-ttl-seconds"))
				.isEqualTo(SPEC_WATCH_LOCK_TTL_SECONDS);
		});
	}

	// yml이 항상 이기므로 두 곳이 갈리면 실제 동작값은 yml 쪽이고 record의 @DefaultValue는 죽은 값이 된다.
	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 record 기본값과 같은 §C-7 값을 갖는다")
	void boundBeanMatchesSpecValuesWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			FeedbackCryptoProperties properties = context.getBean(FeedbackCryptoProperties.class);
			assertThat(properties.cooldownMinutes()).isEqualTo(Integer.parseInt(SPEC_COOLDOWN_MINUTES));
			assertThat(properties.dailyLimit()).isEqualTo(Integer.parseInt(SPEC_DAILY_LIMIT));
			assertThat(properties.rollingWindowMinutes())
				.isEqualTo(Integer.parseInt(SPEC_ROLLING_WINDOW_MINUTES));
			assertThat(properties.sigmaLookbackHours())
				.isEqualTo(Integer.parseInt(SPEC_SIGMA_LOOKBACK_HOURS));
			assertThat(properties.minSampleCount()).isEqualTo(Integer.parseInt(SPEC_MIN_SAMPLE_COUNT));
			assertThat(properties.matchBeforeMinutes())
				.isEqualTo(Integer.parseInt(SPEC_MATCH_BEFORE_MINUTES));
			assertThat(properties.watchLockTtlSeconds())
				.isEqualTo(Integer.parseInt(SPEC_WATCH_LOCK_TTL_SECONDS));
		});
	}

	// tasks.md 항목 2번 — "market.crypto.sigma-lookback-hours와 feedback.crypto.sigma-lookback-hours가
	// 어긋나면 조회 창과 보관 창이 어긋난다"(구현자 주의사항). 두 프로퍼티가 각자 record에 따로
	// @DefaultValue를 갖고 있어 코드 공유로 강제되지 않으므로, 실제 yml 값이 같은지를 여기서 고정한다.
	@Test
	@DisplayName("market.crypto.sigma-lookback-hours와 feedback.crypto.sigma-lookback-hours가 같다")
	void marketAndFeedbackSigmaLookbackHoursStayInSync() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			int feedbackValue = context.getBean(FeedbackCryptoProperties.class).sigmaLookbackHours();
			int marketValue = context.getBean(MarketCryptoProperties.class).sigmaLookbackHours();

			assertThat(feedbackValue).isEqualTo(marketValue);
			assertThat(feedbackValue).isEqualTo(Integer.parseInt(SPEC_SIGMA_LOOKBACK_HOURS));
		});
	}
}
