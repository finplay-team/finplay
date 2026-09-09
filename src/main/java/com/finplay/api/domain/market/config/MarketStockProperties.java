package com.finplay.api.domain.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "market.stock")
public record MarketStockProperties(
	@DefaultValue("600")
	int collectLockTtlSeconds,
	@DefaultValue("0 15,30,45 8-10 * * MON-FRI")
	String retryCron) {
}
