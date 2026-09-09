package com.finplay.api.domain.ranking.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

class RankingRebuildPropertiesTest {

	private static final String LOCK_TTL_SECONDS = "600";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withSystemProperties("spring.config.additional-location=")
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(RankingRebuildConfig.class);

	@Test
	@DisplayName("application.yml에 ranking.rebuild.lock-ttl-seconds가 이슈 #539 확정값(600)으로 실제 존재한다")
	void applicationYmlDeclaresTheLockTtlSecondsKey() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			assertThat(environment.getProperty("ranking.rebuild.lock-ttl-seconds")).isEqualTo(LOCK_TTL_SECONDS);
		});
	}

	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 record 기본값과 같은 락 TTL 값을 갖는다")
	void boundBeanMatchesDefaultValueWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			RankingRebuildProperties properties = context.getBean(RankingRebuildProperties.class);
			assertThat(properties.lockTtlSeconds()).isEqualTo(Integer.parseInt(LOCK_TTL_SECONDS));
		});
	}

	@Test
	@DisplayName("ranking.rebuild.lock-ttl-seconds 키가 전혀 없으면 record의 @DefaultValue(600)로 바인딩된다")
	void fallsBackToRecordDefaultValueWhenYmlKeyIsAbsent() {
		new ApplicationContextRunner()
			.withUserConfiguration(RankingRebuildConfig.class)
			.run(context -> {
				assertThat(context).hasNotFailed();

				RankingRebuildProperties properties = context.getBean(RankingRebuildProperties.class);
				assertThat(properties.lockTtlSeconds()).isEqualTo(600);
			});
	}
}
