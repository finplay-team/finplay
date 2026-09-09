package com.finplay.api.domain.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "kis")
public record KisProperties(
	String baseUrl,
	String appKey,
	String appSecret,
	@DefaultValue("0")
	long requestIntervalMs) {
}
