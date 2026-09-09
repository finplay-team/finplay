package com.finplay.api.domain.market.entity;

import java.util.Arrays;

public enum TutorialScenarioScriptId {

	CRYPTO_ORDER_BASICS_V1(Market.CRYPTO, "/tutorial/scenario-crypto-orderbasics-v1.json"),
	CRYPTO_STORY_V1(Market.CRYPTO, "/tutorial/scenario-crypto-v1.json");

	private final Market market;
	private final String resourcePath;

	TutorialScenarioScriptId(Market market, String resourcePath) {
		this.market = market;
		this.resourcePath = resourcePath;
	}

	public Market market() {
		return market;
	}

	public String resourcePath() {
		return resourcePath;
	}

	public static boolean hasAny(Market market) {
		return Arrays.stream(values()).anyMatch(scriptId -> scriptId.market == market);
	}

	public static TutorialScenarioScriptId first(Market market) {
		return Arrays.stream(values())
			.filter(scriptId -> scriptId.market == market)
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("대본이 저작되지 않은 시장입니다: " + market));
	}
}
