package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TutorialScenarioScriptStartupIntegrationTest {

	@Autowired
	private TutorialScenarioScriptLoader loader;

	@Test
	void everyAuthoredScriptPassesStartupValidation() {
		assertThat(TutorialScenarioScriptId.values()).hasSize(2);
		assertThat(TutorialScenarioScriptId.values()).allSatisfy(scriptId -> {
			TutorialScenarioScript script = loader.script(scriptId);
			assertThat(script.market()).isEqualTo(scriptId.market());
			assertThat(script.version()).isEqualTo(TutorialPriceGenerator.VERSION_2);
			assertThat(script.basePrice()).isPositive();
			assertThat(script.stages()).isNotEmpty();
		});
		assertThat(loader.hasScript(Market.CRYPTO)).isTrue();
		assertThat(loader.firstScriptId(Market.CRYPTO))
			.isEqualTo(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
	}
}
