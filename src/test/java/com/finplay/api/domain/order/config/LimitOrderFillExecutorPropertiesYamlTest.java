// application.yml의 order.limit-fill-executor 블록이 ADR-0024의 키 경로·값 그대로 존재하는지 검증한다.
package com.finplay.api.domain.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

// FeedbackQueryCachePropertiesYamlTest와 같은 짝이다 — LimitOrderFillExecutorPropertiesTest는 record의
// @DefaultValue만 보므로, application.yml의 키가 잘못된 위치·이름으로 들어가도 기본값에 가려 통과한다. yml이
// 항상 이기므로 두 곳이 갈리면 실제 동작값은 yml 쪽이고 record의 @DefaultValue는 죽은 값이 된다 — 특히
// enabled는 운영 킬 스위치라, 키 경로가 틀리면 "false로 내렸는데 실행기가 계속 켜져 있는" 상태가 조용히 생긴다.
//
// ConfigDataApplicationContextInitializer가 SpringApplication 부트스트랩과 같은 방식으로 application.yml만
// Environment에 얹어 준다 — Docker 없는 환경에서도 돈다.
class LimitOrderFillExecutorPropertiesYamlTest {

	private static final String ADR_ENABLED = "true";

	private static final String ADR_PARTITION_COUNT = "8";

	private static final String ADR_QUEUE_CAPACITY_PER_PARTITION = "200";

	private static final String ADR_BATCH_SIZE = "50";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(LimitOrderFillExecutorConfig.class);

	@Test
	@DisplayName("application.yml에 order.limit-fill-executor 네 키가 ADR 값으로 실제 존재한다")
	void applicationYmlDeclaresEveryLimitOrderFillExecutorKey() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			assertThat(environment.getProperty("order.limit-fill-executor.enabled")).isEqualTo(ADR_ENABLED);
			assertThat(environment.getProperty("order.limit-fill-executor.partition-count"))
				.isEqualTo(ADR_PARTITION_COUNT);
			assertThat(environment.getProperty("order.limit-fill-executor.queue-capacity-per-partition"))
				.isEqualTo(ADR_QUEUE_CAPACITY_PER_PARTITION);
			assertThat(environment.getProperty("order.limit-fill-executor.batch-size")).isEqualTo(ADR_BATCH_SIZE);
		});
	}

	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 record 기본값과 같은 ADR 값을 갖는다")
	void boundBeanMatchesAdrValuesWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			LimitOrderFillExecutorProperties properties = context.getBean(LimitOrderFillExecutorProperties.class);
			assertThat(properties.enabled()).isEqualTo(Boolean.parseBoolean(ADR_ENABLED));
			assertThat(properties.partitionCount()).isEqualTo(Integer.parseInt(ADR_PARTITION_COUNT));
			assertThat(properties.queueCapacityPerPartition())
				.isEqualTo(Integer.parseInt(ADR_QUEUE_CAPACITY_PER_PARTITION));
			assertThat(properties.batchSize()).isEqualTo(Integer.parseInt(ADR_BATCH_SIZE));
		});
	}
}
