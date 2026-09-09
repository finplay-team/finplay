package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class TutorialScenarioScriptLoaderTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void loaderIsWiredWithTheObjectMapperBootProvides() {
		new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
			.withUserConfiguration(TutorialScenarioScriptLoader.class)
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(TutorialScenarioScriptLoader.class);
				assertThat(context
					.getBean(TutorialScenarioScriptLoader.class)
					.script(TutorialScenarioScriptId.CRYPTO_STORY_V1)
					.stages())
					.hasSize(8);
			});
	}

	@Test
	void loadsAuthoredCryptoScript() {
		TutorialScenarioScript script = new TutorialScenarioScriptLoader(objectMapper)
			.script(TutorialScenarioScriptId.CRYPTO_STORY_V1);

		assertThat(script.version()).isEqualTo(TutorialPriceGenerator.VERSION_2);
		assertThat(script.market()).isEqualTo(Market.CRYPTO);
	}

	@Test
	void loadsEveryAuthoredScriptIdWithItsOwnBasePrice() {
		TutorialScenarioScriptLoader loader = new TutorialScenarioScriptLoader(objectMapper);

		assertThat(TutorialScenarioScriptId.values())
			.allSatisfy(scriptId -> assertThat(loader.script(scriptId).market()).isEqualTo(scriptId.market()));
		assertThat(loader.script(TutorialScenarioScriptId.CRYPTO_STORY_V1).basePrice())
			.isEqualByComparingTo("10000.00000000");
		assertThat(loader.script(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1).basePrice())
			.isEqualByComparingTo("100000.00000000");
	}

	@Test
	void cryptoStartsAtTheOrderBasicsScript() {
		assertThat(new TutorialScenarioScriptLoader(objectMapper).firstScriptId(Market.CRYPTO))
			.isEqualTo(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
	}

	@Test
	void rejectsUnknownScriptId() {
		TutorialScenarioScriptLoader loader = new TutorialScenarioScriptLoader(objectMapper);

		assertThatThrownBy(() -> loader.script(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("대본이 저작되지 않은 식별자");
	}

	@Test
	void rejectsMarketWithoutAuthoredScript() {
		TutorialScenarioScriptLoader loader = new TutorialScenarioScriptLoader(objectMapper);

		assertThatThrownBy(() -> loader.firstScriptId(Market.STOCK))
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
		"stage-boundary-gap.json",
		"unknown-event-stage.json",
		"reveal-delay-zero.json",
		"event-impact-overflows.json",
		"last-stage-is-loop.json",
		"missing-base-price.json",
		"zero-base-price.json"
	})
	void failsFastOnBrokenScript(String fileName) {
		assertThatThrownBy(() -> load(fileName))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("튜토리얼 대본이 올바르지 않습니다");
	}

	private TutorialScenarioScript load(String fileName) {
		return TutorialScenarioScriptLoader.load(objectMapper, Market.CRYPTO, "/tutorial-broken/" + fileName);
	}
}
