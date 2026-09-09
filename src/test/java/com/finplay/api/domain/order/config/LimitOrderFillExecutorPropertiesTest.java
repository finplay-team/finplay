package com.finplay.api.domain.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LimitOrderFillExecutorPropertiesTest {

	private static final boolean ADR_ENABLED = true;

	private static final int ADR_PARTITION_COUNT = 8;

	private static final int ADR_QUEUE_CAPACITY_PER_PARTITION = 200;

	private static final int ADR_BATCH_SIZE = 50;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(LimitOrderFillExecutorConfig.class);

	@Test
	@DisplayName("order.limit-fill-executor 설정을 하나도 주지 않아도 ADR-0024 기본값으로 바인딩된다")
	void bindsAdrDefaultsWhenNoPropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(LimitOrderFillExecutorProperties.class);

			LimitOrderFillExecutorProperties properties = context.getBean(LimitOrderFillExecutorProperties.class);
			assertThat(properties.enabled()).isEqualTo(ADR_ENABLED);
			assertThat(properties.partitionCount()).isEqualTo(ADR_PARTITION_COUNT);
			assertThat(properties.queueCapacityPerPartition()).isEqualTo(ADR_QUEUE_CAPACITY_PER_PARTITION);
			assertThat(properties.batchSize()).isEqualTo(ADR_BATCH_SIZE);
		});
	}

	@Test
	@DisplayName("order.limit-fill-executor.* 케밥케이스 키를 주면 네 값이 모두 덮어써진다")
	void bindsEveryPropertyFromKebabCaseKeys() {
		contextRunner
			.withPropertyValues(
				"order.limit-fill-executor.enabled=false",
				"order.limit-fill-executor.partition-count=4",
				"order.limit-fill-executor.queue-capacity-per-partition=50",
				"order.limit-fill-executor.batch-size=10")
			.run(context -> {
				assertThat(context).hasNotFailed();

				LimitOrderFillExecutorProperties properties = context.getBean(LimitOrderFillExecutorProperties.class);
				assertThat(properties.enabled()).isFalse();
				assertThat(properties.partitionCount()).isEqualTo(4);
				assertThat(properties.queueCapacityPerPartition()).isEqualTo(50);
				assertThat(properties.batchSize()).isEqualTo(10);
			});
	}

	@Test
	@DisplayName("일부 값만 덮어써도 나머지는 ADR 기본값을 유지한다")
	void keepsAdrDefaultsForPropertiesThatAreNotGiven() {
		contextRunner
			.withPropertyValues("order.limit-fill-executor.enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();

				LimitOrderFillExecutorProperties properties = context.getBean(LimitOrderFillExecutorProperties.class);
				assertThat(properties.enabled()).isFalse();
				assertThat(properties.partitionCount()).isEqualTo(ADR_PARTITION_COUNT);
				assertThat(properties.queueCapacityPerPartition()).isEqualTo(ADR_QUEUE_CAPACITY_PER_PARTITION);
				assertThat(properties.batchSize()).isEqualTo(ADR_BATCH_SIZE);
			});
	}

	@Test
	@DisplayName("partition-count가 1 미만이면 기동이 실패한다")
	void failsWhenPartitionCountIsBelowOne() {
		contextRunner
			.withPropertyValues("order.limit-fill-executor.partition-count=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("partition-count"));
	}

	@Test
	@DisplayName("queue-capacity-per-partition이 1 미만이면 기동이 실패한다")
	void failsWhenQueueCapacityPerPartitionIsBelowOne() {
		contextRunner
			.withPropertyValues("order.limit-fill-executor.queue-capacity-per-partition=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("queue-capacity-per-partition"));
	}

	@Test
	@DisplayName("batch-size가 1 미만이면 기동이 실패한다")
	void failsWhenBatchSizeIsBelowOne() {
		contextRunner
			.withPropertyValues("order.limit-fill-executor.batch-size=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("batch-size"));
	}
}
