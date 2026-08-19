// 깨진 대본이 기동을 실패시키는지, 정상 대본은 그대로 읽히는지 검증한다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finplay.api.market.domain.Market;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// 대본 검증을 기동 시점에 두는 이유는 잘못된 대본으로 서비스가 뜨면 사용자가 깨진 이야기를 겪게 되고
// 그 시점에는 되돌릴 방법이 없기 때문이다(041 plan §오류 계약).
class TutorialScenarioScriptLoaderTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void loadsAuthoredCryptoScript() {
		TutorialScenarioScript script = new TutorialScenarioScriptLoader(objectMapper).script(Market.CRYPTO);

		assertThat(script.version()).isEqualTo(TutorialPriceGenerator.VERSION_2);
		assertThat(script.market()).isEqualTo(Market.CRYPTO);
	}

	@Test
	void rejectsMarketWithoutAuthoredScript() {
		TutorialScenarioScriptLoader loader = new TutorialScenarioScriptLoader(objectMapper);

		assertThatThrownBy(() -> loader.script(Market.STOCK))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("대본이 저작되지 않은 시장");
	}

	@Test
	void acceptsMinimalValidScript() {
		assertThat(load("valid.json").stages()).hasSize(2);
	}

	@Test
	void failsWhenScriptFileIsMissing() {
		assertThatThrownBy(() -> load("does-not-exist.json"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("찾을 수 없습니다");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"wrong-version.json",
		"wrong-market.json",
		"ratio-count-mismatch.json",
		"loop-endpoints-differ.json",
		"unknown-event-stage.json",
		"event-impact-overflows.json"
	})
	void failsFastOnBrokenScript(String fileName) {
		assertThatThrownBy(() -> load(fileName))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("튜토리얼 대본이 올바르지 않습니다");
	}

	private TutorialScenarioScript load(String fileName) {
		return new TutorialScenarioScriptLoader(objectMapper, Map.of(Market.CRYPTO, "/tutorial-broken/" + fileName))
			.script(Market.CRYPTO);
	}
}
