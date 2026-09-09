package com.finplay.api.domain.education.marketpractice.entity;

import java.math.BigDecimal;

public enum ExitPreset {

	CAUTIOUS("2", "3"),
	BALANCED("3", "5"),
	RELAXED("5", "8");

	public static final ExitPreset DEFAULT = BALANCED;

	private final BigDecimal stopLossRate;
	private final BigDecimal takeProfitRate;

	ExitPreset(String stopLossRate, String takeProfitRate) {
		this.stopLossRate = new BigDecimal(stopLossRate);
		this.takeProfitRate = new BigDecimal(takeProfitRate);
	}

	public BigDecimal stopLossRate() {
		return this.stopLossRate;
	}

	public BigDecimal takeProfitRate() {
		return this.takeProfitRate;
	}
}
