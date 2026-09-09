package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import com.finplay.api.domain.market.service.TutorialPriceSeriesDto;
import com.finplay.api.domain.market.service.TutorialScenarioScriptLoader;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeAttemptCanonicalPriceServiceTest {

	private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 8, 14, 12, 0);
	private final PracticeAttemptCanonicalPriceService service = new PracticeAttemptCanonicalPriceService(
		mock(PracticeAttemptRepository.class), new TutorialPriceGenerator(),
		new TutorialScenarioScriptLoader(new tools.jackson.databind.ObjectMapper()));

	@Test
	void publishedMinuteChangesOnlyOnThreeSecondBoundariesAndClampsClockReversalToZero() {
		PracticeAttempt attempt = selectedAttempt();

		assertThat(service.publishedMinute(attempt, ANCHOR.minusSeconds(10))).isZero();
		assertThat(service.publishedMinute(attempt, ANCHOR)).isZero();
		assertThat(service.publishedMinute(attempt, ANCHOR.plusSeconds(2))).isZero();
		assertThat(service.publishedMinute(attempt, ANCHOR.plusSeconds(3))).isEqualTo(1L);
		assertThat(service.publishedMinute(attempt, ANCHOR.plusSeconds(5))).isEqualTo(1L);
		assertThat(service.publishedMinute(attempt, ANCHOR.plusSeconds(6))).isEqualTo(2L);
	}

	@Test
	void canonicalPriceIsExactlyStableWithinSameVirtualMinute() {
		PracticeAttempt attempt = selectedAttempt();

		BigDecimal atBoundary = service.canonicalPrice(attempt, ANCHOR.plusSeconds(3));
		BigDecimal beforeNextBoundary = service.canonicalPrice(attempt, ANCHOR.plusSeconds(5));

		assertThat(beforeNextBoundary).isEqualByComparingTo(atBoundary);
		assertThat(service.priceSeries(attempt, ANCHOR.plusSeconds(5)).canonicalPrice())
			.isEqualByComparingTo(atBoundary);
	}

	@Test
	void scenarioSeriesKeepsHistoryAndCurrentCandleInTheSameStoryScriptBand() {
		PracticeAttempt attempt = scenarioAttempt();

		TutorialPriceSeriesDto series = service.priceSeries(attempt, ANCHOR);

		assertThat(series.candles()).hasSize(30);
		assertThat(series.canonicalPrice()).isEqualByComparingTo("10000.00000000");
		assertThat(series.candles().subList(0, 29)).allSatisfy(candle -> {
			assertThat(candle.current()).isFalse();
			assertThat(candle.low()).isGreaterThan(new BigDecimal("8000"));
			assertThat(candle.high()).isLessThan(new BigDecimal("12000"));
		});
		assertThat(series.candles().get(29).current()).isTrue();
		assertThat(series.candles().get(29).close()).isEqualByComparingTo("10000.00000000");
	}

	@Test
	void scriptRunWithoutPersistedScriptIdStillGetsTheStoryScriptPrice() {
		PracticeAttempt attempt = scenarioAttempt(null);
		putCursor(attempt, "ACT2_RUMOR", 21L);

		assertThat(attempt.scenarioScriptId()).isEqualTo(TutorialScenarioScriptId.CRYPTO_STORY_V1);
		assertThat(service.canonicalPrice(attempt, ANCHOR)).isEqualByComparingTo("9750.00000000");
	}

	@Test
	void scriptRunWithPersistedOrderBasicsIdGetsThatScriptsPrice() {
		PracticeAttempt attempt = scenarioAttempt(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		putCursor(attempt, "ORDER_BASICS", 15L);

		assertThat(service.canonicalPrice(attempt, ANCHOR)).isEqualByComparingTo("112000.00000000");
		assertThat(service.priceSeries(attempt, ANCHOR).candles())
			.allSatisfy(candle -> assertThat(candle.low()).isGreaterThan(new BigDecimal("80000")));
	}

	private static void putCursor(PracticeAttempt attempt, String stageId, long elapsedSeconds) {
		ReflectionTestUtils.setField(attempt, "scenarioStageId", stageId);
		ReflectionTestUtils.setField(attempt, "scenarioStageElapsedSeconds", elapsedSeconds);
	}

	private static PracticeAttempt scenarioAttempt() {
		return scenarioAttempt(null);
	}

	private static PracticeAttempt scenarioAttempt(TutorialScenarioScriptId scriptId) {
		PracticeAttempt attempt = PracticeAttempt.create(7L, Market.CRYPTO, ANCHOR.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", 11L);
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, ANCHOR);
		ReflectionTestUtils.setField(instrument, "id", 21L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt.selectInstrument(
			instrument, ANCHOR, ANCHOR.toLocalDate(), 123_456_789L, TutorialPriceGenerator.VERSION_2, scriptId,
			ANCHOR);
		return attempt;
	}

	private static PracticeAttempt selectedAttempt() {
		PracticeAttempt attempt = PracticeAttempt.create(7L, Market.CRYPTO, ANCHOR.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", 11L);
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, ANCHOR);
		ReflectionTestUtils.setField(instrument, "id", 21L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt.selectInstrument(instrument, ANCHOR, ANCHOR.toLocalDate(), 123_456_789L, (short)1, null, ANCHOR);
		return attempt;
	}
}
