package com.finplay.api.domain.ranking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ranking.rebuild")
public record RankingRebuildProperties(
	@DefaultValue("600")
	int lockTtlSeconds) {
}
