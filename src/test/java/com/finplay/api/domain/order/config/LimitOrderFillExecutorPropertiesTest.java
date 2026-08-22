// order.limit-fill-executor.* 프로퍼티가 설정 없이도 ADR-0024 기본값으로 바인딩되는지, 방어를 조용히 무력화하는 값이면 기동이 실패하는지 검증한다.
package com.finplay.api.domain.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// FeedbackQueryCachePropertiesTest와 같은 패턴이다 — 여기서는 record의 @DefaultValue와 검증 블록만 본다.
// application.yml 쪽 키 경로는 LimitOrderFillExecutorPropertiesYamlTest가 맡는다.
//
// enabled·partitionCount·queueCapacityPerPartition의 기대값 정본은 ai/adr/0024-limit-order-fill-executor.md,
// batchSize는 ai/adr/0025-limit-order-fill-batch-commit.md다. 구현 파일이 아니라 ADR에서 값을 가져와야
// record와 yml이 함께 틀어지는 드리프트가 잡힌다.
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

	// 0·음수 파티션은 라우터가 나눗셈(Math.floorMod)에 쓸 분모를 잃어 기동 시점부터 문제가 이어진다.
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

	// 0이면 대기열이 없다는 뜻이라 사실상 매 체결마다 즉시 버려지는 것과 같아 실행기 도입 의미가 없다.
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

	// 0은 청크가 비어 아무 후보도 실행기에 제출되지 않는다는 뜻이라 배치 도입 의미가 없다(ADR-0025).
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
