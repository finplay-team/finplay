package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TutorialOrderBasicsScriptIntegrityTest {

	private static final String STAGE_ID = "ORDER_BASICS";
	private static final int STAGE_MINUTES = 160;
	private final TutorialPriceGenerator generator = new TutorialPriceGenerator();
	private final TutorialScenarioScript script = new TutorialScenarioScriptLoader(new ObjectMapper())
		.script(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
	private final TutorialPriceGenerationInput input = new TutorialPriceGenerationInput(
		TutorialPriceGenerator.VERSION_2, 123_456_789L, 42L, 3L, Market.CRYPTO, LocalDate.of(2026, 8, 14));

	@Test
	void scriptIsOneProgressStageOfOneHundredSixtyMinutesWithNoEvents() {
		assertThat(script.version()).isEqualTo(TutorialPriceGenerator.VERSION_2);
		assertThat(script.market()).isEqualTo(Market.CRYPTO);
		assertThat(script.basePrice()).isEqualByComparingTo("100000.00000000");
		assertThat(script.stages()).singleElement().satisfies(stage -> {
			assertThat(stage.id()).isEqualTo(STAGE_ID);
			assertThat(stage.act()).isNull();
			assertThat(stage.kind()).isEqualTo(TutorialScenarioStageKind.PROGRESS);
			assertThat(stage.minutes()).isEqualTo(STAGE_MINUTES);
			assertThat(stage.ratios()).hasSize(STAGE_MINUTES);
		});
		assertThat(script.events()).isEmpty();
	}

	@Test
	void extremePricesAreExactlyEightyEightAndOneHundredTwelveThousandAndAreReachedEightTimesEach() {
		List<BigDecimal> prices = prices();

		assertThat(prices).hasSize(STAGE_MINUTES);
		assertThat(prices.stream().min(BigDecimal::compareTo).orElseThrow())
			.isEqualByComparingTo("88000.00000000");
		assertThat(prices.stream().max(BigDecimal::compareTo).orElseThrow())
			.isEqualByComparingTo("112000.00000000");
		assertThat(prices.stream().filter(price -> price.compareTo(new BigDecimal("88000.00000000")) == 0).count())
			.as("저점 도달 횟수")
			.isEqualTo(8L);
		assertThat(prices.stream().filter(price -> price.compareTo(new BigDecimal("112000.00000000")) == 0).count())
			.as("고점 도달 횟수")
			.isEqualTo(8L);
	}

	@Test
	void scriptStartsAtItsBasePrice() {
		assertThat(prices().get(0)).isEqualByComparingTo("100000.00000000");
	}

	private List<BigDecimal> prices() {
		return java.util.stream.IntStream.range(0, STAGE_MINUTES)
			.mapToObj(minute -> generator.canonicalPrice(input, script, new TutorialScenarioCursor(STAGE_ID, minute)))
			.toList();
	}
}
