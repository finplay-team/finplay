package com.finplay.api.domain.order.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "order.limit-fill-executor")
public record LimitOrderFillExecutorProperties(
	@DefaultValue("true")
	boolean enabled,
	@DefaultValue("8")
	int partitionCount,
	@DefaultValue("200")
	int queueCapacityPerPartition,
	@DefaultValue("50")
	int batchSize) {

	public LimitOrderFillExecutorProperties {
		if (partitionCount < 1) {
			throw new IllegalArgumentException("order.limit-fill-executor.partition-count는 1 이상이어야 합니다.");
		}
		if (queueCapacityPerPartition < 1) {
			throw new IllegalArgumentException(
				"order.limit-fill-executor.queue-capacity-per-partition은 1 이상이어야 합니다.");
		}
		if (batchSize < 1) {
			throw new IllegalArgumentException("order.limit-fill-executor.batch-size는 1 이상이어야 합니다.");
		}
	}
}
