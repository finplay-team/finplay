// 튜토리얼 대본 파일 하나하나를 가리키는 식별자 — attempt가 어느 대본을 쓰는지 이 값으로 정해진다
package com.finplay.api.market.service;

import com.finplay.api.market.domain.Market;
import java.util.Arrays;

// 시장 하나에 대본이 여럿이므로 Market을 키로 쓸 수 없다(049 plan §1). 이 이름은 practice_attempts의
// 컬럼 값으로 영속되므로 **구간 id와 같은 등급의 스키마**다 — 배포된 뒤에는 바꾸지 않는다.
public enum TutorialScenarioScriptId {

	// 선언 순서가 곧 단계 순서다 — firstScriptId(market)가 그 시장의 첫 대본을 고른다.
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
