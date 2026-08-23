// application.yml의 ranking.rebuild.lock-ttl-seconds가 RankingRebuildProperties의 @DefaultValue와 어긋나지 않는지 검증한다.
package com.finplay.api.domain.ranking.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

// MarketStockPropertiesTest와 같은 성격의 드리프트 테스트다 — 이 클래스가 지키는 것은 "yml 키가 실제로 그
// 경로에 있는가"와 "yml이 적용된 빈이 record 기본값과 같은 값을 갖는가" 둘이다. record의 @DefaultValue만 보는
// 테스트는 yml 쪽 키 경로가 잘못돼도 기본값에 가려 통과한다.
//
// ConfigDataApplicationContextInitializer가 SpringApplication 부트스트랩과 같은 방식으로 application.yml만
// Environment에 얹어 준다 — Docker 없는 환경에서도 돈다.
//
// spring.config.additional-location을 비워 build.gradle이 test 태스크 전체에 거는 크론 비활성화용 additional
// yml(ranking-rebuild-schedule-disabled-for-tests.yml 등)을 배제한다 — RankingRebuildCronPropertiesTest와
// 같은 이유다.
class RankingRebuildPropertiesTest {

	// 이슈 #539 결정값. 기대값의 정본은 그 이슈이며, application.yml의 근거 주석도 같은 값을 가리킨다.
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

	// yml이 항상 이기므로 두 곳이 갈리면 실제 동작값은 yml 쪽이고 record의 @DefaultValue는 죽은 값이 된다.
	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 record 기본값과 같은 락 TTL 값을 갖는다")
	void boundBeanMatchesDefaultValueWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			RankingRebuildProperties properties = context.getBean(RankingRebuildProperties.class);
			assertThat(properties.lockTtlSeconds()).isEqualTo(Integer.parseInt(LOCK_TTL_SECONDS));
		});
	}

	// application.yml을 아예 얹지 않은(ConfigDataApplicationContextInitializer 없이) 순수 컨텍스트에서는
	// record의 @DefaultValue(600)가 그대로 적용되어야 한다 — yml이 없어도 기동은 실패하지 않는다는 것과, 위
	// 테스트가 "우연히 yml 값과 같아 보이는" 상황이 아니라는 것을 함께 확인한다.
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
