package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStageProgressResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.ExitPlanCreateCommandDto;
import com.finplay.api.domain.order.service.ExitPlanCreationService;
import com.finplay.api.domain.order.service.PracticeOrderAttributionDto;
import com.finplay.api.domain.order.service.PracticeOrderFillAttributionDto;
import com.finplay.api.domain.order.service.PracticeOrderFillContextDto;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.HoldingService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeAttemptOrderAttributionServiceTest {

	private static final Long USER_ID = 7L;
	private static final Long ATTEMPT_ID = 11L;
	private static final Long INSTRUMENT_ID = 21L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 12, 0);

	private final PracticeAttemptRepository practiceAttemptRepository = mock(PracticeAttemptRepository.class);
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository = mock(
		PracticeRiskSnapshotRepository.class);
	private final PracticeAttemptCanonicalPriceService canonicalPriceService = mock(
		PracticeAttemptCanonicalPriceService.class);
	private final TradeService tradeService = mock(TradeService.class);
	private final HoldingService holdingService = mock(HoldingService.class);
	private final ExitPlanCreationService exitPlanCreationService = mock(ExitPlanCreationService.class);
	private final PracticeStageProgressCalculationService practiceStageProgressCalculationService = mock(
		PracticeStageProgressCalculationService.class);
	private final PracticeAttemptOrderAttributionService service = new PracticeAttemptOrderAttributionService(
		practiceAttemptRepository, practiceRiskSnapshotRepository, canonicalPriceService,
		new ReferencePriceCalculator(), tradeService, holdingService, exitPlanCreationService,
		practiceStageProgressCalculationService,
		java.time.Clock.fixed(NOW.toInstant(java.time.ZoneOffset.UTC), java.time.ZoneOffset.UTC));

	@Test
	void lockForOrderReturnsCurrentAttemptForTutorialSample() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = inProgressAttempt(instrument);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(canonicalPriceService.canonicalPrice(attempt, NOW)).thenReturn(new BigDecimal("100.00000000"));

		Optional<PracticeOrderAttributionDto> result = service.lockForOrder(USER_ID, instrument, OrderType.MARKET);

		assertThat(result).contains(new PracticeOrderAttributionDto(ATTEMPT_ID, 1L, new BigDecimal("100.00000000")));
	}

	@Test
	void lockForOrderLeavesOrdinaryInstrumentUnattributed() {
		Instrument ordinaryInstrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 5_000L, true, NOW);

		Optional<PracticeOrderAttributionDto> result = service.lockForOrder(USER_ID, ordinaryInstrument,
			OrderType.MARKET);

		assertThat(result).isEmpty();
		verifyNoInteractions(practiceAttemptRepository, practiceRiskSnapshotRepository);
	}

	@Test
	void lockForOrderAlwaysAllowsMarketOrderEvenWhenStageIsLocked() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = scriptAttempt(instrument, TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(canonicalPriceService.canonicalPrice(attempt, NOW)).thenReturn(new BigDecimal("100.00000000"));

		Optional<PracticeOrderAttributionDto> result = service.lockForOrder(USER_ID, instrument, OrderType.MARKET);

		assertThat(result).isPresent();
		verifyNoInteractions(practiceStageProgressCalculationService);
	}

	@Test
	void lockForOrderRejectsLimitOrderWhenMarketRoundTripNotCompleted() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = scriptAttempt(instrument, TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(practiceStageProgressCalculationService.calculate(attempt))
			.thenReturn(new PracticeStageProgressResponse(false, false, false));

		assertThatThrownBy(() -> service.lockForOrder(USER_ID, instrument, OrderType.LIMIT))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_STAGE_LOCKED));
	}

	@Test
	void lockForOrderAllowsLimitOrderWhenMarketRoundTripCompleted() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = scriptAttempt(instrument, TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(practiceStageProgressCalculationService.calculate(attempt))
			.thenReturn(new PracticeStageProgressResponse(true, false, false));
		when(canonicalPriceService.canonicalPrice(attempt, NOW)).thenReturn(new BigDecimal("100.00000000"));

		Optional<PracticeOrderAttributionDto> result = service.lockForOrder(USER_ID, instrument, OrderType.LIMIT);

		assertThat(result).isPresent();
	}

	@Test
	void lockForOrderAllowsLimitOrderForNonScenarioScriptAttempt() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = inProgressAttempt(instrument);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(canonicalPriceService.canonicalPrice(attempt, NOW)).thenReturn(new BigDecimal("100.00000000"));

		Optional<PracticeOrderAttributionDto> result = service.lockForOrder(USER_ID, instrument, OrderType.LIMIT);

		assertThat(result).isPresent();
		verifyNoInteractions(practiceStageProgressCalculationService);
	}

	@Test
	void lockForFillReturnsTrueOnlyForCurrentRun() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = inProgressAttempt(instrument);
		when(practiceAttemptRepository.findByIdForUpdate(ATTEMPT_ID)).thenReturn(Optional.of(attempt));

		when(canonicalPriceService.canonicalPrice(attempt, NOW)).thenReturn(new BigDecimal("100.00000000"));
		PracticeOrderFillContextDto current = service.lockForFill(
			new PracticeOrderFillAttributionDto(ATTEMPT_ID, 1L, USER_ID, INSTRUMENT_ID), NOW);
		PracticeOrderFillContextDto stale = service.lockForFill(
			new PracticeOrderFillAttributionDto(ATTEMPT_ID, 2L, USER_ID, INSTRUMENT_ID), NOW);

		assertThat(current.currentRun()).isTrue();
		assertThat(current.canonicalPrice()).isEqualByComparingTo("100.00000000");
		assertThat(stale.currentRun()).isFalse();
		assertThat(stale.canonicalPrice()).isNull();
	}

	@Test
	void createRiskSnapshotOnBuyFillRoundsPricesToScaleEightAndWritesOncePerEntry() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = inProgressAttempt(instrument);
		Order order = attributedBuyOrder(instrument);
		Trade trade = buyTrade(order, instrument, new BigDecimal("100.123456785"));
		when(practiceAttemptRepository.findByIdForUpdate(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
		when(practiceRiskSnapshotRepository.countByAttemptIdAndRunNumber(ATTEMPT_ID, 1L)).thenReturn(0L, 1L);
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L))
			.thenReturn(trade.getQuantity(), trade.getQuantity().add(trade.getQuantity()));
		when(holdingService.findHoldingId(USER_ID, Market.CRYPTO, INSTRUMENT_ID)).thenReturn(Optional.of(77L));
		when(holdingService.findHoldingForOwner(USER_ID, 77L)).thenReturn(Optional.of(mock(Holding.class)));
		when(canonicalPriceService.canonicalPrice(attempt, NOW)).thenReturn(new BigDecimal("100.00000000"));

		service.createRiskSnapshotOnBuyFill(order, trade, NOW);
		service.createRiskSnapshotOnBuyFill(order, trade, NOW.plusSeconds(1));

		ArgumentCaptor<PracticeRiskSnapshot> snapshotCaptor = ArgumentCaptor.forClass(PracticeRiskSnapshot.class);
		verify(practiceRiskSnapshotRepository).save(snapshotCaptor.capture());
		PracticeRiskSnapshot snapshot = snapshotCaptor.getValue();
		assertThat(snapshot.getEntryPrice()).isEqualByComparingTo("100.12345679");
		assertThat(snapshot.getStopLossPrice()).isEqualByComparingTo("97.11975309");
		assertThat(snapshot.getTakeProfitPrice()).isEqualByComparingTo("105.12962963");
		assertThat(snapshot.getRunNumber()).isEqualTo(1L);
		assertThat(snapshot.getBuyTrade()).isSameAs(trade);
		assertThat(snapshot.getCreatedAt()).isEqualTo(NOW);
		assertThat(snapshot.getExitPreset()).isEqualTo(ExitPreset.BALANCED);
		assertThat(snapshot.getEntrySequence()).isEqualTo(1);

		ArgumentCaptor<ExitPlanCreateCommandDto> commandCaptor = ArgumentCaptor
			.forClass(ExitPlanCreateCommandDto.class);
		verify(exitPlanCreationService).create(commandCaptor.capture());
		ExitPlanCreateCommandDto command = commandCaptor.getValue();
		assertThat(command.isPracticePath()).isTrue();
		assertThat(command.practiceOrigin().attemptId()).isEqualTo(ATTEMPT_ID);
		assertThat(command.practiceOrigin().runNumber()).isEqualTo(1L);
		assertThat(command.practiceOrigin().baselinePrice()).isEqualByComparingTo("100.00000000");
		assertThat(command.priceInput().entryPrice()).isEqualByComparingTo("100.12345679");
		assertThat(command.priceInput().stopLossRate()).isEqualByComparingTo("3");
		assertThat(command.priceInput().takeProfitRate()).isEqualByComparingTo("5");
		assertThat(command.requestHash()).hasSize(64);
	}

	@Test
	void createRiskSnapshotOnBuyFillIgnoresOrdinaryOrder() {
		Instrument instrument = tutorialInstrument();
		User user = testUser();
		Account account = account(user);
		Order ordinaryOrder = Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET, BigDecimal.ONE,
			"ordinary", "b".repeat(64), NOW);
		Trade trade = buyTrade(ordinaryOrder, instrument, BigDecimal.valueOf(100));

		service.createRiskSnapshotOnBuyFill(ordinaryOrder, trade, NOW);

		verify(practiceAttemptRepository, never()).findByIdForUpdate(org.mockito.ArgumentMatchers.any());
		verifyNoInteractions(practiceRiskSnapshotRepository);
	}

	@Test
	void createRiskSnapshotOnBuyFillSkipsOnlyTheExitPlanForTheOrderBasicsScript() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = scriptAttempt(instrument, TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		Order order = attributedBuyOrder(instrument);
		Trade trade = buyTrade(order, instrument, new BigDecimal("100000.00000000"));
		when(practiceAttemptRepository.findByIdForUpdate(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
		when(practiceRiskSnapshotRepository.countByAttemptIdAndRunNumber(ATTEMPT_ID, 1L)).thenReturn(0L);
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L)).thenReturn(trade.getQuantity());

		service.createRiskSnapshotOnBuyFill(order, trade, NOW);

		ArgumentCaptor<PracticeRiskSnapshot> snapshotCaptor = ArgumentCaptor.forClass(PracticeRiskSnapshot.class);
		verify(practiceRiskSnapshotRepository).save(snapshotCaptor.capture());
		PracticeRiskSnapshot snapshot = snapshotCaptor.getValue();
		assertThat(snapshot.getEntrySequence()).isEqualTo(1);
		assertThat(snapshot.getExitPreset()).isEqualTo(ExitPreset.DEFAULT);
		assertThat(snapshot.getEntryPrice()).isEqualByComparingTo("100000.00000000");
		assertThat(snapshot.getStopLossPrice()).isEqualByComparingTo("97000.00000000");
		assertThat(snapshot.getTakeProfitPrice()).isEqualByComparingTo("105000.00000000");
		verifyNoInteractions(exitPlanCreationService);
	}

	@Test
	void createRiskSnapshotOnBuyFillNoLongerCreatesTheExitPlanForTheStoryScript() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = scriptAttempt(instrument, TutorialScenarioScriptId.CRYPTO_STORY_V1);
		Order order = attributedBuyOrder(instrument);
		Trade trade = buyTrade(order, instrument, new BigDecimal("10180.00000000"));
		when(practiceAttemptRepository.findByIdForUpdate(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
		when(practiceRiskSnapshotRepository.countByAttemptIdAndRunNumber(ATTEMPT_ID, 1L)).thenReturn(0L);
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L)).thenReturn(trade.getQuantity());

		service.createRiskSnapshotOnBuyFill(order, trade, NOW);

		ArgumentCaptor<PracticeRiskSnapshot> snapshotCaptor = ArgumentCaptor.forClass(PracticeRiskSnapshot.class);
		verify(practiceRiskSnapshotRepository).save(snapshotCaptor.capture());
		assertThat(snapshotCaptor.getValue().getStopLossPrice()).isEqualByComparingTo("9874.60000000");
		verifyNoInteractions(exitPlanCreationService);
	}

	private static PracticeAttempt scriptAttempt(Instrument instrument, TutorialScenarioScriptId scriptId) {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)2, scriptId,
			NOW.minusMinutes(10));
		return attempt;
	}

	private static PracticeAttempt inProgressAttempt(Instrument instrument) {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)1, null,
			NOW.minusMinutes(10));
		return attempt;
	}

	private static Instrument tutorialInstrument() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		return instrument;
	}

	private static Order attributedBuyOrder(Instrument instrument) {
		User user = testUser();
		return Order.createForPracticeAttempt(
			user, account(user), instrument, OrderSide.BUY, OrderType.MARKET, BigDecimal.ONE,
			ATTEMPT_ID, 1L, "attempt-order", "a".repeat(64), NOW);
	}

	private static Trade buyTrade(Order order, Instrument instrument, BigDecimal price) {
		Trade trade = Trade.of(
			order,
			order.getAccount(),
			instrument,
			null,
			OrderSide.BUY,
			price,
			BigDecimal.ONE,
			100L,
			0L,
			null,
			NOW,
			NOW);
		ReflectionTestUtils.setField(trade, "id", 31L);
		return trade;
	}

	private static User testUser() {
		return User.create("attempt-order@finplay.com", "hash", "attempt-order", NOW);
	}

	private static Account account(User user) {
		return Account.create(user, Market.CRYPTO, NOW);
	}
}
