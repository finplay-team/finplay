package com.finplay.api.domain.market.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "market.crypto")
public record MarketCryptoProperties(
	@DefaultValue("0 * * * * *")
	String priceSnapshotCron,
	@DefaultValue("24")
	int sigmaLookbackHours) {

	public MarketCryptoProperties {
		if (sigmaLookbackHours < 1) {
			throw new IllegalArgumentException("market.crypto.sigma-lookback-hours는 1 이상이어야 합니다.");
		}
	}
}
