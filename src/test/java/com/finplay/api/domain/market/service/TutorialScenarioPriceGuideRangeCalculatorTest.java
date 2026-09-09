package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TutorialScenarioPriceGuideRangeCalculatorTest {

	private final TutorialScenarioScriptLoader loader = new TutorialScenarioScriptLoader(new ObjectMapper());

	@Test
	void calculateReturnsNinetyToOneHundredTenThousandRangeForOrderBasicsScript() {
		TutorialScenarioScript script = loader.script(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);

		Optional<TutorialScenarioPriceGuideRangeCalculator.Range> range = TutorialScenarioPriceGuideRangeCalculator
			.calculate(script);

		assertThat(range).isPresent();
		assertThat(range.get().low()).isEqualByComparingTo(new BigDecimal("90000.00000000"));
		assertThat(range.get().high()).isEqualByComparingTo(new BigDecimal("110000.00000000"));
	}

	@Test
	void calculateReturnsNoRangeInsteadOfThrowingWhenScriptHasZeroPriceSpan() {
		TutorialScenarioStage flatStage = new TutorialScenarioStage(
			"FLAT", null, TutorialScenarioStageKind.PROGRESS, 2,
			List.of(new BigDecimal("1.000000"), new BigDecimal("1.000000")));
		TutorialScenarioScript flatScript = new TutorialScenarioScript(
			(short)2, Market.CRYPTO, new BigDecimal("10000.00000000"), List.of(flatStage), List.of());

		Optional<TutorialScenarioPriceGuideRangeCalculator.Range> range = TutorialScenarioPriceGuideRangeCalculator
			.calculate(flatScript);

		assertThat(range).isEmpty();
	}
}
