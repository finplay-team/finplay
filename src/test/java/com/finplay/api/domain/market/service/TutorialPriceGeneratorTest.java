package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TutorialPriceGeneratorTest {

	private static final TutorialPriceGenerationInput INPUT = new TutorialPriceGenerationInput(
		(short)1, 123_456_789L, 42L, 3L, Market.CRYPTO, LocalDate.of(2026, 8, 14));
	private static final TutorialPriceGenerationInput SCENARIO_INPUT = new TutorialPriceGenerationInput(
		TutorialPriceGenerator.VERSION_2, 123_456_789L, 42L, 3L, Market.CRYPTO, LocalDate.of(2026, 8, 14));
	private static final BigDecimal PRE_SCRIPT_CRYPTO_BASE_PRICE = new BigDecimal("10000.00000000");
	private final TutorialPriceGenerator generator = new TutorialPriceGenerator();
	private final TutorialScenarioScriptLoader loader = new TutorialScenarioScriptLoader(new ObjectMapper());
	private final TutorialScenarioScript script = loader.script(TutorialScenarioScriptId.CRYPTO_STORY_V1);
	private final TutorialScenarioScript orderBasicsScript = loader
		.script(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);

	@Test
	void generateMatchesVersionOneGoldenVector() {
		TutorialPriceSeriesDto series = generator.generate(INPUT, 7L);

		assertThat(series.candles().get(0)).isEqualTo(new TutorialPriceCandleDto(
			LocalDate.of(2026, 7, 16),
			new BigDecimal("9958.83400000"),
			new BigDecimal("10207.98311313"),
			new BigDecimal("9521.03642796"),
			new BigDecimal("9536.35200000"),
			false));
		assertThat(series.candles().get(28)).isEqualTo(new TutorialPriceCandleDto(
			LocalDate.of(2026, 8, 13),
			new BigDecimal("9346.40600000"),
			new BigDecimal("9401.93374576"),
			new BigDecimal("9160.26138080"),
			new BigDecimal("9227.56800000"),
			false));
		assertThat(series.candles().get(29)).isEqualTo(new TutorialPriceCandleDto(
			LocalDate.of(2026, 8, 14),
			new BigDecimal("10657.70800000"),
			new BigDecimal("10932.45600000"),
			new BigDecimal("9102.10000000"),
			new BigDecimal("10932.45600000"),
			true));
		assertThat(series.canonicalPrice()).isEqualByComparingTo("10932.45600000");
	}

	@Test
	void generateReturnsOrderedTwentyNineHistoryCandlesAndOneCurrentCandleWithValidScaleEightOhlc() {
		TutorialPriceSeriesDto series = generator.generate(INPUT, 239L);

		assertThat(series.candles()).hasSize(30);
		assertThat(series.candles()).extracting(TutorialPriceCandleDto::date).isSorted();
		assertThat(series.candles().subList(0, 29)).allSatisfy(candle -> assertThat(candle.current()).isFalse());
		assertThat(series.candles().get(29).current()).isTrue();
		assertThat(series.candles()).allSatisfy(candle -> {
			assertThat(candle.open()).isPositive().hasScaleOf(8);
			assertThat(candle.high()).isPositive().hasScaleOf(8)
				.isGreaterThanOrEqualTo(candle.open()).isGreaterThanOrEqualTo(candle.close());
			assertThat(candle.low()).isPositive().hasScaleOf(8)
				.isLessThanOrEqualTo(candle.open()).isLessThanOrEqualTo(candle.close());
			assertThat(candle.close()).isPositive().hasScaleOf(8);
		});
		assertThat(series.candles().get(29).close()).isEqualByComparingTo(series.canonicalPrice());
	}

	@Test
	void canonicalPriceIsDeterministicForSameAttemptRunAndMinuteAndSeparatedByRunAndSeed() {
		BigDecimal first = generator.canonicalPrice(INPUT, 17L);
		BigDecimal repeated = generator.canonicalPrice(INPUT, 17L);
		TutorialPriceGenerationInput otherRun = new TutorialPriceGenerationInput(
			(short)1, INPUT.priceSeed(), INPUT.instrumentId(), 4L, INPUT.market(), INPUT.tutorialDate());
		TutorialPriceGenerationInput otherSeed = new TutorialPriceGenerationInput(
			(short)1, INPUT.priceSeed() + 1L, INPUT.instrumentId(), INPUT.runNumber(), INPUT.market(),
			INPUT.tutorialDate());

		assertThat(repeated).isEqualByComparingTo(first);
		assertThat(generator.canonicalPrice(otherRun, 17L)).isNotEqualByComparingTo(first);
		assertThat(generator.canonicalPrice(otherSeed, 17L)).isNotEqualByComparingTo(first);
	}

	@Test
	void wallClockEntryPointRejectsVersionTwoInput() {
		assertThatThrownBy(() -> generator.canonicalPrice(SCENARIO_INPUT, 7L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("생성기 버전 1 전용");
	}

	@Test
	void cursorEntryPointRejectsVersionOneInput() {
		TutorialScenarioCursor cursor = new TutorialScenarioCursor("ACT1_RISE", 14);

		assertThatThrownBy(() -> generator.canonicalPrice(INPUT, script, cursor))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("생성기 버전 2 전용");
	}

	@Test
	void cursorEntryPointReadsScriptRatioAtMarketBasePrice() {
		BigDecimal price = generator.canonicalPrice(SCENARIO_INPUT, script,
			new TutorialScenarioCursor("ACT1_RISE", 14));

		assertThat(price).isEqualByComparingTo("10180.00000000");
	}

	@Test
	void cursorEntryPointRejectsScriptOfAnotherMarket() {
		TutorialPriceGenerationInput stockInput = new TutorialPriceGenerationInput(
			TutorialPriceGenerator.VERSION_2,
			INPUT.priceSeed(),
			INPUT.instrumentId(),
			INPUT.runNumber(),
			Market.STOCK,
			INPUT.tutorialDate());
		TutorialScenarioCursor cursor = new TutorialScenarioCursor("ACT1_RISE", 14);

		assertThatThrownBy(() -> generator.canonicalPrice(stockInput, script, cursor))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("다른 대본");
	}

	@Test
	void storyScriptCanonicalPriceIsUnchangedAcrossAllOneHundredTwentyScriptPositions() {
		assertThat(generator.canonicalPrice(SCENARIO_INPUT, script, new TutorialScenarioCursor("IDLE_ENTRY", 0)))
			.isEqualByComparingTo("10000.00000000");
		assertThat(generator.canonicalPrice(SCENARIO_INPUT, script, new TutorialScenarioCursor("IDLE_ENTRY", 1)))
			.isEqualByComparingTo("10008.79000000");

		int positions = 0;
		for (TutorialScenarioStage stage : script.stages()) {
			for (int minute = 0; minute < stage.minutes(); minute++) {
				assertThat(generator.canonicalPrice(SCENARIO_INPUT, script,
					new TutorialScenarioCursor(stage.id(), minute)))
					.as("%s %d분", stage.id(), minute)
					.isEqualByComparingTo(PRE_SCRIPT_CRYPTO_BASE_PRICE
						.multiply(stage.ratios().get(minute))
						.setScale(8, RoundingMode.HALF_UP));
				positions++;
			}
		}
		assertThat(positions).isEqualTo(120);
	}

	@Test
	void storyScriptHistoryIsIdenticalToThePreScriptBasePricePath() {
		List<TutorialPriceCandleDto> history = generator.generateHistory(SCENARIO_INPUT, script);

		assertThat(history).hasSize(29).isEqualTo(generator.generateHistory(SCENARIO_INPUT));
		assertThat(history.get(0)).isEqualTo(new TutorialPriceCandleDto(
			LocalDate.of(2026, 7, 16),
			new BigDecimal("10851.68800000"),
			new BigDecimal("11034.83139579"),
			new BigDecimal("9361.56129038"),
			new BigDecimal("9370.43800000"),
			false));
		assertThat(history.get(28)).isEqualTo(new TutorialPriceCandleDto(
			LocalDate.of(2026, 8, 13),
			new BigDecimal("10236.99400000"),
			new BigDecimal("10406.98235647"),
			new BigDecimal("9216.85795368"),
			new BigDecimal("9272.89200000"),
			false));
	}

	@Test
	void orderBasicsScriptHistoryFollowsItsOwnBasePriceInsteadOfTheMarketConstant() {
		List<TutorialPriceCandleDto> history = generator.generateHistory(SCENARIO_INPUT, orderBasicsScript);

		assertThat(history).hasSize(29);
		assertThat(history).allSatisfy(candle -> {
			assertThat(candle.low())
				.as("%s 저가", candle.date())
				.isGreaterThan(new BigDecimal("80000"));
			assertThat(candle.high())
				.as("%s 고가", candle.date())
				.isLessThan(new BigDecimal("120000"));
		});
		assertThat(history).isNotEqualTo(generator.generateHistory(SCENARIO_INPUT));
		assertThat(history.get(0).open()).isEqualByComparingTo("108516.88000000");
	}

	@Test
	void scriptHistoryEntryPointRejectsVersionOneInput() {
		assertThatThrownBy(() -> generator.generateHistory(INPUT, script))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("생성기 버전 2 전용");
	}

	@Test
	void unsupportedGeneratorVersionIsRejected() {
		TutorialPriceGenerationInput unsupported = new TutorialPriceGenerationInput(
			(short)3,
			INPUT.priceSeed(),
			INPUT.instrumentId(),
			INPUT.runNumber(),
			INPUT.market(),
			INPUT.tutorialDate());

		assertThatThrownBy(() -> generator.canonicalPrice(unsupported, 7L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("지원하지 않는 튜토리얼 가격 생성기 버전");
	}
}
