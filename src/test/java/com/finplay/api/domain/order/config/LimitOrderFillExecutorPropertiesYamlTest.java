package com.finplay.api.domain.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

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
