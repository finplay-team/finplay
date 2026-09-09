package com.finplay.api.domain.market.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

class MarketStockPropertiesTest {

	private static final String SPEC_COLLECT_LOCK_TTL_SECONDS = "600";

	private static final String SPEC_RETRY_CRON = "0 15,30,45 8-10 * * MON-FRI";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withSystemProperties("spring.config.additional-location=")
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(MarketStockConfig.class);

	@Test
	@DisplayName("application.yml에 market.stock.collect-lock-ttl-seconds가 plan.md 확정값(600)으로 실제 존재한다")
	void applicationYmlDeclaresTheCollectLockTtlSecondsKey() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			assertThat(environment.getProperty("market.stock.collect-lock-ttl-seconds"))
				.isEqualTo(SPEC_COLLECT_LOCK_TTL_SECONDS);
		});
	}

	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 record 기본값과 같은 락 TTL 값을 갖는다")
	void boundBeanMatchesDefaultValueWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			MarketStockProperties properties = context.getBean(MarketStockProperties.class);
			assertThat(properties.collectLockTtlSeconds())
				.isEqualTo(Integer.parseInt(SPEC_COLLECT_LOCK_TTL_SECONDS));
		});
	}

	@Test
	@DisplayName("application.yml에 market.stock.retry-cron이 plan.md 확정값으로 실제 존재한다")
	void applicationYmlDeclaresTheRetryCronKey() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			assertThat(environment.getProperty("market.stock.retry-cron")).isEqualTo(SPEC_RETRY_CRON);
		});
	}

	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 record 기본값과 같은 재시도 크론 값을 갖는다")
	void boundBeanMatchesDefaultValueForRetryCronWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			MarketStockProperties properties = context.getBean(MarketStockProperties.class);
			assertThat(properties.retryCron()).isEqualTo(SPEC_RETRY_CRON);
		});
	}

	@Test
	@DisplayName("market.stock 키가 전혀 없으면 record의 @DefaultValue(TTL 600·재시도 크론)로 바인딩된다")
	void fallsBackToRecordDefaultValueWhenYmlKeyIsAbsent() {
		new ApplicationContextRunner()
			.withUserConfiguration(MarketStockConfig.class)
			.run(context -> {
				assertThat(context).hasNotFailed();

				MarketStockProperties properties = context.getBean(MarketStockProperties.class);
				assertThat(properties.collectLockTtlSeconds()).isEqualTo(600);
				assertThat(properties.retryCron()).isEqualTo(SPEC_RETRY_CRON);
			});
	}
}
