// attempt seed·run 기반 튜토리얼 가격 생성기의 고정 벡터와 OHLC 불변성을 검증한다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TutorialPriceGeneratorTest {

	private static final TutorialPriceGenerationInput INPUT = new TutorialPriceGenerationInput(
		(short)1, 123_456_789L, 42L, 3L, Market.CRYPTO, LocalDate.of(2026, 8, 14));
	private static final TutorialPriceGenerationInput SCENARIO_INPUT = new TutorialPriceGenerationInput(
		TutorialPriceGenerator.VERSION_2, 123_456_789L, 42L, 3L, Market.CRYPTO, LocalDate.of(2026, 8, 14));
	private final TutorialPriceGenerator generator = new TutorialPriceGenerator();
	private final TutorialScenarioScript script = new TutorialScenarioScriptLoader(new ObjectMapper())
		.script(Market.CRYPTO);

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

	// 생성기 버전 2는 대본 위치에서만 가격이 나온다. 두 진입점이 서로의 입력을 받으면 조용히 다른 가격을
	// 내는 대신 즉시 실패해야 한다.
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
