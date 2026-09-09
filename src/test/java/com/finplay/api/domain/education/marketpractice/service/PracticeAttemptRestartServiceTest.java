package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.service.PracticeRunRestartCommand;
import com.finplay.api.domain.order.service.PracticeRunRestartOrderService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeAttemptRestartServiceTest {

	private static final Long USER_ID = 7L;
	private static final Long ATTEMPT_ID = 11L;
	private static final Long INSTRUMENT_ID = 21L;
	private static final Long REAL_INSTRUMENT_ID = 19L;
	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-14T06:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final PracticeAttemptRepository attemptRepository = mock(PracticeAttemptRepository.class);
	private final PracticeRiskSnapshotRepository riskSnapshotRepository = mock(
		PracticeRiskSnapshotRepository.class);
	private final PracticeRunRestartOrderService orderRestartService = mock(PracticeRunRestartOrderService.class);
	private final PracticeAttemptCanonicalPriceService canonicalPriceService = mock(
		PracticeAttemptCanonicalPriceService.class);
	private final TutorialAccountService tutorialAccountService = mock(TutorialAccountService.class);
	private final PracticeAttemptRestartService service = new PracticeAttemptRestartService(
		attemptRepository, riskSnapshotRepository, orderRestartService, canonicalPriceService,
		tutorialAccountService, Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

	private TutorialAccount resetTutorialAccountStub() {
		TutorialAccount account = mock(TutorialAccount.class);
		when(account.getCashBalance()).thenReturn(10_000_000L);
		when(account.getAvailableCash()).thenReturn(10_000_000L);
		when(account.getRealizedPnl()).thenReturn(0L);
		when(tutorialAccountService.getOrCreateForUpdate(
			USER_ID, Market.CRYPTO, NOW))
			.thenReturn(account);
		return account;
	}

	@Test
	void restartIncrementsRunAndResetsSelectedInstrumentState() {
		PracticeAttempt attempt = selectedAttempt();
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(riskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 2L))
			.thenReturn(Optional.empty());
		resetTutorialAccountStub();

		PracticeAttemptResponse response = service.restart(USER_ID, Market.CRYPTO);

		assertThat(response.runNumber()).isEqualTo(2L);
		assertThat(response.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(response.instrumentId()).isNull();
		assertThat(response.anchorAt()).isNull();
		assertThat(response.tutorialDate()).isNull();
		assertThat(response.tutorialCashBalance()).isEqualTo(10_000_000L);
		assertThat(response.tutorialAvailableCash()).isEqualTo(10_000_000L);
		assertThat(response.tutorialRealizedPnl()).isEqualTo(0L);
		ArgumentCaptor<PracticeRunRestartCommand> commandCaptor = ArgumentCaptor.forClass(
			PracticeRunRestartCommand.class);
		verify(orderRestartService).cleanupCurrentRun(commandCaptor.capture());
		assertThat(commandCaptor.getValue().attemptId()).isEqualTo(ATTEMPT_ID);
		assertThat(commandCaptor.getValue().runNumber()).isEqualTo(1L);
		assertThat(commandCaptor.getValue().instrumentId()).isEqualTo(INSTRUMENT_ID);
		assertThat(commandCaptor.getValue().restartedAt()).isEqualTo(NOW);
	}

	@Test
	void restartQueriesTutorialAccountAfterCleanupDelegatesToOrderRestartService() {
		PracticeAttempt attempt = selectedAttempt();
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(riskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 2L))
			.thenReturn(Optional.empty());
		resetTutorialAccountStub();

		service.restart(USER_ID, Market.CRYPTO);

		org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(orderRestartService, tutorialAccountService);
		inOrder.verify(orderRestartService).cleanupCurrentRun(org.mockito.ArgumentMatchers.any());
		inOrder.verify(tutorialAccountService)
			.getOrCreateForUpdate(USER_ID, Market.CRYPTO, NOW);
	}

	@Test
	void restartWithoutInstrumentStillDelegatesEmptyRunCleanupThenIncrementsRun() {
		PracticeAttempt attempt = newAttempt();
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(riskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 2L))
			.thenReturn(Optional.empty());
		resetTutorialAccountStub();

		PracticeAttemptResponse response = service.restart(USER_ID, Market.CRYPTO);

		ArgumentCaptor<PracticeRunRestartCommand> commandCaptor = ArgumentCaptor.forClass(
			PracticeRunRestartCommand.class);
		verify(orderRestartService).cleanupCurrentRun(commandCaptor.capture());
		assertThat(commandCaptor.getValue().instrumentId()).isNull();
		assertThat(response.runNumber()).isEqualTo(2L);
		assertThat(response.status()).isEqualTo("SELECTING_INSTRUMENT");
	}

	@Test
	void restartCompletedAttemptPerformsCleanupAndStartsNewRun() {
		PracticeAttempt attempt = selectedAttempt();
		ReflectionTestUtils.setField(attempt, "status", PracticeAttemptStatus.COMPLETED);
		ReflectionTestUtils.setField(attempt, "completedAt", NOW.minusDays(1));
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(riskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 2L))
			.thenReturn(Optional.empty());
		resetTutorialAccountStub();

		PracticeAttemptResponse response = service.restart(USER_ID, Market.CRYPTO);

		assertThat(response.mode()).isEqualTo("ACTIVE");
		assertThat(response.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(response.runNumber()).isEqualTo(2L);
		assertThat(response.completedAt()).isNull();
		ArgumentCaptor<PracticeRunRestartCommand> commandCaptor = ArgumentCaptor.forClass(
			PracticeRunRestartCommand.class);
		verify(orderRestartService).cleanupCurrentRun(commandCaptor.capture());
		assertThat(commandCaptor.getValue().attemptId()).isEqualTo(ATTEMPT_ID);
		assertThat(commandCaptor.getValue().runNumber()).isEqualTo(1L);
		assertThat(commandCaptor.getValue().instrumentId()).isEqualTo(INSTRUMENT_ID);
	}

	@Test
	void restartLegacyCompletedRealInstrumentAttemptSkipsInstrumentCleanupAndStartsNewRun() {
		PracticeAttempt attempt = legacyCompletedReplayAttempt();
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(riskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 2L))
			.thenReturn(Optional.empty());
		resetTutorialAccountStub();

		PracticeAttemptResponse response = service.restart(USER_ID, Market.CRYPTO);

		ArgumentCaptor<PracticeRunRestartCommand> commandCaptor = ArgumentCaptor.forClass(
			PracticeRunRestartCommand.class);
		verify(orderRestartService).cleanupCurrentRun(commandCaptor.capture());
		assertThat(commandCaptor.getValue().attemptId()).isEqualTo(ATTEMPT_ID);
		assertThat(commandCaptor.getValue().runNumber()).isEqualTo(1L);
		assertThat(commandCaptor.getValue().instrumentId()).isNull();
		assertThat(commandCaptor.getValue().canonicalPrice()).isNull();
		verifyNoInteractions(canonicalPriceService);
		assertThat(response.mode()).isEqualTo("ACTIVE");
		assertThat(response.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(response.runNumber()).isEqualTo(2L);
		assertThat(response.instrumentId()).isNull();
		assertThat(response.completedAt()).isNull();
	}

	private static PracticeAttempt newAttempt() {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		return attempt;
	}

	private static PracticeAttempt selectedAttempt() {
		PracticeAttempt attempt = newAttempt();
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)1, null,
			NOW.minusMinutes(10));
		return attempt;
	}

	private static PracticeAttempt legacyCompletedReplayAttempt() {
		PracticeAttempt attempt = newAttempt();
		Instrument realInstrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(realInstrument, "id", REAL_INSTRUMENT_ID);
		attempt.selectInstrument(realInstrument, NOW.minusDays(1), NOW.toLocalDate().minusDays(1), 456L, (short)1, null,
			NOW.minusDays(1));
		ReflectionTestUtils.setField(attempt, "status", PracticeAttemptStatus.COMPLETED);
		ReflectionTestUtils.setField(attempt, "completedAt", NOW.minusDays(1));
		return attempt;
	}
}
