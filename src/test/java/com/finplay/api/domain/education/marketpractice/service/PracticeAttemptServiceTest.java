package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStageProgressResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeCompletion;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketReflection;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.education.repository.PracticeProgressRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import com.finplay.api.domain.market.service.TutorialScenarioScriptLoader;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeAttemptServiceTest {

	private static final Long USER_ID = 7L;
	private static final Long ATTEMPT_ID = 11L;
	private static final Long INSTRUMENT_ID = 21L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-14T03:04:05Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final PracticeAttemptRepository practiceAttemptRepository = mock(PracticeAttemptRepository.class);
	private final PracticeCompletionRepository practiceCompletionRepository = mock(PracticeCompletionRepository.class);
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository = mock(
		PracticeRiskSnapshotRepository.class);
	private final PracticeProgressRepository practiceProgressRepository = mock(PracticeProgressRepository.class);
	private final InstrumentService instrumentService = mock(InstrumentService.class);
	private final TradeService tradeService = mock(TradeService.class);
	private final TutorialAccountService tutorialAccountService = mock(TutorialAccountService.class);
	private final TutorialScenarioScriptLoader tutorialScenarioScriptLoader = new TutorialScenarioScriptLoader(
		new tools.jackson.databind.ObjectMapper());
	private final PracticeStageProgressCalculationService practiceStageProgressCalculationService = mock(
		PracticeStageProgressCalculationService.class);
	private final PracticeAttemptService service = new PracticeAttemptService(
		practiceAttemptRepository,
		practiceCompletionRepository,
		practiceRiskSnapshotRepository,
		practiceProgressRepository,
		instrumentService,
		tradeService,
		tutorialScenarioScriptLoader,
		tutorialAccountService,
		practiceStageProgressCalculationService,
		Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

	@BeforeEach
	void stubNoHolding() {
		when(tradeService.netFilledQuantity(anyLong(), anyLong())).thenReturn(BigDecimal.ZERO);
	}

	@BeforeEach
	void stubDefaultTutorialAccount() {
		for (com.finplay.api.domain.market.entity.Market accountMarket : com.finplay.api.domain.market.entity.Market
			.values()) {
			stubTutorialAccount(accountMarket, freshTutorialAccount());
		}
	}

	@BeforeEach
	void stubStageProgressFullyUnlockedByDefault() {
		when(practiceStageProgressCalculationService.calculate(org.mockito.ArgumentMatchers.any()))
			.thenReturn(new PracticeStageProgressResponse(true, true, true));
	}

	@Test
	void ensureAttemptReturnsExistingRunWithoutRestartingIt() {
		PracticeAttempt attempt = selectingAttempt(Market.STOCK);
		Instrument instrument = tutorialInstrument(Market.STOCK, true);
		attempt.selectInstrument(instrument, NOW.minusMinutes(3), NOW.toLocalDate(), 123L, (short)1, null,
			NOW.minusMinutes(3));
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		stubTutorialAccount(Market.STOCK, freshTutorialAccount());

		PracticeAttemptResponse response = service.ensureAttempt(USER_ID, Market.STOCK);

		assertThat(response.runNumber()).isEqualTo(1L);
		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.instrumentId()).isEqualTo(INSTRUMENT_ID);
		assertThat(response.anchorAt()).isEqualTo(NOW.minusMinutes(3));
		assertThat(response.tutorialCashBalance()).isEqualTo(10_000_000L);
		assertThat(response.tutorialAvailableCash()).isEqualTo(10_000_000L);
		assertThat(response.tutorialRealizedPnl()).isZero();
		verify(practiceAttemptRepository, never()).save(org.mockito.ArgumentMatchers.any());
		verify(tutorialAccountService)
			.getOrCreateForUpdate(USER_ID, Market.STOCK, NOW);
		verify(practiceAttemptRepository, never()).insertIfAbsent(anyLong(), anyString(), any());
	}

	@Test
	void ensureAttemptOnReentryReflectsMutatedTutorialAccountIncludingReservedCash() {
		PracticeAttempt attempt = selectingAttempt(Market.STOCK);
		Instrument instrument = tutorialInstrument(Market.STOCK, true);
		attempt.selectInstrument(instrument, NOW.minusMinutes(3), NOW.toLocalDate(), 123L, (short)1, null,
			NOW.minusMinutes(3));
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		TutorialAccount mutated = freshTutorialAccount();
		mutated.deductCash(2_000_000L);
		mutated.reserveCash(500_000L);
		mutated.addRealizedPnl(300_000L);
		stubTutorialAccount(Market.STOCK, mutated);

		PracticeAttemptResponse response = service.ensureAttempt(USER_ID, Market.STOCK);

		assertThat(response.tutorialCashBalance()).isEqualTo(8_000_000L);
		assertThat(response.tutorialAvailableCash()).isEqualTo(7_500_000L);
		assertThat(response.tutorialRealizedPnl()).isEqualTo(300_000L);
		verify(practiceAttemptRepository, never()).insertIfAbsent(anyLong(), anyString(), any());

	}

	@Test
	void ensureCompletedAttemptReturnsReplayWithoutWritingAttempt() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		attempt.selectInstrument(tutorialInstrument(Market.CRYPTO, true), NOW.minusDays(2),
			NOW.minusDays(2).toLocalDate(), 456L, (short)1, null, NOW.minusDays(2));
		ReflectionTestUtils.setField(attempt, "status", PracticeAttemptStatus.COMPLETED);
		ReflectionTestUtils.setField(attempt, "completedAt", NOW.minusDays(1));
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		stubTutorialAccount(Market.CRYPTO, freshTutorialAccount());

		PracticeAttemptResponse response = service.ensureAttempt(USER_ID, Market.CRYPTO);

		assertThat(response.mode()).isEqualTo("REPLAY");
		assertThat(response.status()).isEqualTo("COMPLETED");
		assertThat(response.completedAt()).isEqualTo(NOW.minusDays(1));
		verify(practiceAttemptRepository, never()).save(org.mockito.ArgumentMatchers.any());
		verify(tutorialAccountService)
			.getOrCreateForUpdate(USER_ID, Market.CRYPTO, NOW);
	}

	@ParameterizedTest
	@EnumSource(value = PracticeAttemptStatus.class, names = {"SELECTING_INSTRUMENT", "IN_PROGRESS", "EXPIRED"})
	void ensureAttemptWithCoexistingCompletionAndNonCompletedAttemptReturnsCurrentStateWithoutError(
		PracticeAttemptStatus status) {
		PracticeAttempt attempt = selectingAttempt(Market.STOCK);
		Instrument instrument = tutorialInstrument(Market.STOCK, true);
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 999L, (short)1, null,
			NOW.minusMinutes(10));
		ReflectionTestUtils.setField(attempt, "status", status);
		PracticeCompletion completion = mock(PracticeCompletion.class);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, "INVESTMENT_PRACTICE_V1"))
			.thenReturn(Optional.of(completion));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		stubTutorialAccount(Market.STOCK, freshTutorialAccount());

		PracticeAttemptResponse response = service.ensureAttempt(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo(status.name());
		assertThat(response.instrumentId()).isEqualTo(INSTRUMENT_ID);
		assertThat(response.anchorAt()).isEqualTo(NOW.minusMinutes(10));
		verify(practiceAttemptRepository, never()).save(org.mockito.ArgumentMatchers.any());
		verify(tutorialAccountService)
			.getOrCreateForUpdate(USER_ID, Market.STOCK, NOW);
	}

	@ParameterizedTest
	@EnumSource(Market.class)
	void selectInstrumentStartsCurrentRunForTutorialSampleInEachMarket(Market market) {
		PracticeAttempt attempt = selectingAttempt(market);
		Instrument instrument = tutorialInstrument(market, true);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, market))
			.thenReturn(Optional.of(attempt));
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());

		PracticeAttemptResponse response = service.selectInstrument(USER_ID, market, INSTRUMENT_ID);

		assertThat(response.market()).isEqualTo(market.name());
		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.instrumentId()).isEqualTo(INSTRUMENT_ID);
		assertThat(response.anchorAt()).isEqualTo(NOW);
		assertThat(response.tutorialDate()).isEqualTo(NOW.toLocalDate());
		assertThat(attempt.getGeneratorVersion()).isEqualTo(
			market == Market.CRYPTO ? TutorialPriceGenerator.VERSION_2 : TutorialPriceGenerator.VERSION_1);
		assertThat(attempt.getScenarioStageId()).isNull();
		TutorialScenarioScriptId expectedScriptId = market == Market.CRYPTO
			? TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1
			: null;
		assertThat(ReflectionTestUtils.getField(attempt, "scenarioScriptId")).isEqualTo(expectedScriptId);
		assertThat(attempt.scenarioScriptId()).isEqualTo(expectedScriptId);
	}

	@Test
	void stockAttemptGetsNoCryptoScriptIdEvenOnceItsMarketStartsGettingGeneratorVersionTwo() {
		TutorialScenarioScriptLoader loaderWithStockScript = mock(TutorialScenarioScriptLoader.class);
		when(loaderWithStockScript.hasScript(Market.STOCK)).thenReturn(true);
		PracticeAttemptService serviceWithStockScript = new PracticeAttemptService(
			practiceAttemptRepository,
			practiceCompletionRepository,
			practiceRiskSnapshotRepository,
			practiceProgressRepository,
			instrumentService,
			tradeService,
			loaderWithStockScript,
			tutorialAccountService,
			practiceStageProgressCalculationService,
			Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));
		PracticeAttempt attempt = selectingAttempt(Market.STOCK);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(tutorialInstrument(Market.STOCK, true));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());

		serviceWithStockScript.selectInstrument(USER_ID, Market.STOCK, INSTRUMENT_ID);

		assertThat(attempt.getGeneratorVersion()).isEqualTo(TutorialPriceGenerator.VERSION_2);
		assertThat(ReflectionTestUtils.getField(attempt, "scenarioScriptId")).isNull();
	}

	@ParameterizedTest
	@EnumSource(Market.class)
	void selectInstrumentRejectsRealInstrumentInEachMarket(Market market) {
		PracticeAttempt attempt = selectingAttempt(market);
		Instrument realInstrument = Instrument.create(
			market, "REAL-" + market, "실제 종목", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(realInstrument, "id", INSTRUMENT_ID);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, market))
			.thenReturn(Optional.of(attempt));
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(realInstrument);

		assertThatThrownBy(() -> service.selectInstrument(USER_ID, market, INSTRUMENT_ID))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSTRUMENT_NOT_TRADABLE));
	}

	@Test
	void selectInstrumentRejectsSampleFromDifferentMarket() {
		PracticeAttempt attempt = selectingAttempt(Market.STOCK);
		Instrument cryptoSample = tutorialInstrument(Market.CRYPTO, true);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(cryptoSample);

		assertThatThrownBy(() -> service.selectInstrument(USER_ID, Market.STOCK, INSTRUMENT_ID))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSTRUMENT_NOT_TRADABLE));
	}

	@Test
	void selectInstrumentRejectsNonTradableTutorialSample() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		Instrument instrument = tutorialInstrument(Market.CRYPTO, false);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);

		assertThatThrownBy(() -> service.selectInstrument(USER_ID, Market.CRYPTO, INSTRUMENT_ID))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INSTRUMENT_NOT_TRADABLE));
	}

	private void stubTutorialAccount(com.finplay.api.domain.market.entity.Market market, TutorialAccount account) {
		when(tutorialAccountService.getOrCreateForUpdate(USER_ID, market, NOW)).thenReturn(account);
		when(tutorialAccountService.find(USER_ID, market)).thenReturn(Optional.of(account));
	}

	@Test
	void completedReplayInitializationKeepsGeneratorVersionOne() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		Instrument instrument = tutorialInstrument(Market.CRYPTO, true);
		Holding holding = mock(Holding.class);
		when(holding.getInstrument()).thenReturn(instrument);
		PracticeMarketReflection reflection = mock(PracticeMarketReflection.class);
		when(reflection.getHolding()).thenReturn(holding);
		PracticeCompletion completion = mock(PracticeCompletion.class);
		when(completion.getReflection()).thenReturn(reflection);
		when(completion.getCompletedAt()).thenReturn(NOW.minusDays(1));
		when(completion.getId()).thenReturn(77L);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.empty(), Optional.of(attempt));
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, "COIN_PRACTICE_V1"))
			.thenReturn(Optional.of(completion));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		stubTutorialAccount(Market.CRYPTO, freshTutorialAccount());

		PracticeAttemptResponse response = service.ensureAttempt(USER_ID, Market.CRYPTO);

		assertThat(response.mode()).isEqualTo("REPLAY");
		assertThat(attempt.getGeneratorVersion()).isEqualTo(TutorialPriceGenerator.VERSION_1);
	}

	@Test
	void selectExitPresetIsAllowedWhileNothingIsHeld() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		attempt.selectInstrument(tutorialInstrument(Market.CRYPTO, true), NOW, NOW.toLocalDate(), 1L, (short)2, null,
			NOW);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());

		PracticeAttemptResponse response = service.selectExitPreset(USER_ID, Market.CRYPTO, ExitPreset.CAUTIOUS);

		assertThat(attempt.getExitPreset()).isEqualTo(ExitPreset.CAUTIOUS);
		assertThat(response.selectedExitPreset()).isEqualTo("CAUTIOUS");
		assertThat(response.exitPresetLocked()).isFalse();
		assertThat(response.availableExitPresets()).hasSize(3);
	}

	@Test
	void selectExitPresetIsRejectedWhileHolding() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		attempt.selectInstrument(tutorialInstrument(Market.CRYPTO, true), NOW, NOW.toLocalDate(), 1L, (short)2, null,
			NOW);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L)).thenReturn(new BigDecimal("2"));

		assertThatThrownBy(() -> service.selectExitPreset(USER_ID, Market.CRYPTO, ExitPreset.RELAXED))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STEP_LOCKED));
		assertThat(attempt.getExitPreset()).isNull();
	}

	@Test
	void selectExitPresetIsRejectedAfterCompletion() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		attempt.selectInstrument(tutorialInstrument(Market.CRYPTO, true), NOW, NOW.toLocalDate(), 1L, (short)2, null,
			NOW);
		ReflectionTestUtils.setField(attempt, "status", PracticeAttemptStatus.COMPLETED);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));

		assertThatThrownBy(() -> service.selectExitPreset(USER_ID, Market.CRYPTO, ExitPreset.RELAXED))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_ALREADY_COMPLETED));
	}

	@Test
	void selectExitPresetIsRejectedWhenOnlyMarketRoundTripCompleted() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		attempt.selectInstrument(tutorialInstrument(Market.CRYPTO, true), NOW, NOW.toLocalDate(), 1L, (short)2, null,
			NOW);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(practiceStageProgressCalculationService.calculate(attempt))
			.thenReturn(new PracticeStageProgressResponse(true, false, false));

		assertThatThrownBy(() -> service.selectExitPreset(USER_ID, Market.CRYPTO, ExitPreset.RELAXED))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STAGE_LOCKED));
		assertThat(attempt.getExitPreset()).isNull();
	}

	@Test
	void selectExitPresetIsAllowedForNonScenarioScriptAttemptRegardlessOfRoundTrips() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		attempt.selectInstrument(tutorialInstrument(Market.CRYPTO, true), NOW, NOW.toLocalDate(), 1L, (short)1, null,
			NOW);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		org.mockito.Mockito.clearInvocations(practiceStageProgressCalculationService);

		PracticeAttemptResponse response = service.selectExitPreset(USER_ID, Market.CRYPTO, ExitPreset.RELAXED);

		assertThat(response.selectedExitPreset()).isEqualTo("RELAXED");
		org.mockito.Mockito.verifyNoInteractions(practiceStageProgressCalculationService);
	}

	@Test
	void unselectedAttemptReportsTheDefaultPreset() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		Instrument instrument = tutorialInstrument(Market.CRYPTO, true);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());

		PracticeAttemptResponse response = service.selectInstrument(USER_ID, Market.CRYPTO, INSTRUMENT_ID);

		assertThat(attempt.getExitPreset()).isNull();
		assertThat(response.selectedExitPreset()).isEqualTo("BALANCED");
	}

	@Test
	void selectInstrumentReflectsTheCurrentTutorialAccount() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		Instrument instrument = tutorialInstrument(Market.CRYPTO, true);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		TutorialAccount mutated = freshTutorialAccount();
		mutated.deductCash(2_000_000L);
		mutated.reserveCash(500_000L);
		mutated.addRealizedPnl(300_000L);
		stubTutorialAccount(Market.CRYPTO, mutated);

		PracticeAttemptResponse response = service.selectInstrument(USER_ID, Market.CRYPTO, INSTRUMENT_ID);

		assertThat(response.tutorialCashBalance()).isEqualTo(8_000_000L);
		assertThat(response.tutorialAvailableCash()).isEqualTo(7_500_000L);
		assertThat(response.tutorialRealizedPnl()).isEqualTo(300_000L);
		verify(tutorialAccountService).find(USER_ID, Market.CRYPTO);
	}

	@Test
	void reselectingTheSameInstrumentAlsoReflectsTheTutorialAccount() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		Instrument instrument = tutorialInstrument(Market.CRYPTO, true);
		attempt.selectInstrument(instrument, NOW.minusMinutes(3), NOW.toLocalDate(), 1L, (short)2, null,
			NOW.minusMinutes(3));
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID)).thenReturn(instrument);
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		TutorialAccount mutated = freshTutorialAccount();
		mutated.deductCash(1_000_000L);
		stubTutorialAccount(Market.CRYPTO, mutated);

		PracticeAttemptResponse response = service.selectInstrument(USER_ID, Market.CRYPTO, INSTRUMENT_ID);

		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.tutorialCashBalance()).isEqualTo(9_000_000L);
		assertThat(response.tutorialAvailableCash()).isEqualTo(9_000_000L);
	}

	@Test
	void selectInstrumentReadsTheTutorialAccountWithoutLockingIt() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID))
			.thenReturn(tutorialInstrument(Market.CRYPTO, true));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());

		service.selectInstrument(USER_ID, Market.CRYPTO, INSTRUMENT_ID);

		verify(tutorialAccountService).find(USER_ID, Market.CRYPTO);
		verify(tutorialAccountService, never())
			.getOrCreateForUpdate(anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	void selectInstrumentFallsBackToGetOrCreateWhenTheAccountIsMissing() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(instrumentService.getInstrumentEntity(INSTRUMENT_ID))
			.thenReturn(tutorialInstrument(Market.CRYPTO, true));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		when(tutorialAccountService.find(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.empty());

		PracticeAttemptResponse response = service.selectInstrument(USER_ID, Market.CRYPTO, INSTRUMENT_ID);

		assertThat(response.tutorialCashBalance()).isEqualTo(10_000_000L);
		verify(tutorialAccountService)
			.getOrCreateForUpdate(USER_ID, Market.CRYPTO, NOW);
	}

	@Test
	void selectExitPresetReflectsTheCurrentTutorialAccount() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		attempt.selectInstrument(tutorialInstrument(Market.CRYPTO, true), NOW, NOW.toLocalDate(), 1L, (short)2, null,
			NOW);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		TutorialAccount mutated = freshTutorialAccount();
		mutated.deductCash(4_000_000L);
		stubTutorialAccount(Market.CRYPTO, mutated);

		PracticeAttemptResponse response = service.selectExitPreset(USER_ID, Market.CRYPTO, ExitPreset.CAUTIOUS);

		assertThat(response.selectedExitPreset()).isEqualTo("CAUTIOUS");
		assertThat(response.tutorialCashBalance()).isEqualTo(6_000_000L);
		assertThat(response.tutorialAvailableCash()).isEqualTo(6_000_000L);
		verify(tutorialAccountService).find(USER_ID, Market.CRYPTO);
		verify(tutorialAccountService, never())
			.getOrCreateForUpdate(anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	private static TutorialAccount freshTutorialAccount() {
		User user = User.create("tutorial-trader@finplay.com", "password-hash", "tutorial-trader", NOW);
		return TutorialAccount.create(user, Market.STOCK, NOW);
	}

	private static PracticeAttempt selectingAttempt(Market market) {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, market, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		return attempt;
	}

	private static Instrument tutorialInstrument(Market market, boolean tradable) {
		Instrument instrument = Instrument.create(
			market, "SAMPLE-" + market, "튜토리얼 샘플", BigDecimal.ONE, 5_000L, tradable, NOW);
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		return instrument;
	}
}
