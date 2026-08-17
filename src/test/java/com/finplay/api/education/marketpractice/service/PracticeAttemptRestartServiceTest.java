// 튜토리얼 attempt 재시작의 완료 replay, 실행 세대 증가와 선택 상태 초기화를 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.service.PracticeRunRestartCommand;
import com.finplay.api.order.service.PracticeRunRestartOrderService;
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
	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-14T06:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final PracticeAttemptRepository attemptRepository = mock(PracticeAttemptRepository.class);
	private final PracticeRiskSnapshotRepository riskSnapshotRepository = mock(
		PracticeRiskSnapshotRepository.class);
	private final PracticeRunRestartOrderService orderRestartService = mock(PracticeRunRestartOrderService.class);
	private final PracticeAttemptCanonicalPriceService canonicalPriceService = mock(
		PracticeAttemptCanonicalPriceService.class);
	private final PracticeAttemptRestartService service = new PracticeAttemptRestartService(
		attemptRepository, riskSnapshotRepository, orderRestartService, canonicalPriceService,
		Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

	@Test
	void restartIncrementsRunAndResetsSelectedInstrumentState() {
		PracticeAttempt attempt = selectedAttempt();
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(riskSnapshotRepository.findByAttemptIdAndRunNumber(ATTEMPT_ID, 2L))
			.thenReturn(Optional.empty());

		PracticeAttemptResponse response = service.restart(USER_ID, Market.CRYPTO);

		assertThat(response.runNumber()).isEqualTo(2L);
		assertThat(response.status()).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(response.instrumentId()).isNull();
		assertThat(response.anchorAt()).isNull();
		assertThat(response.tutorialDate()).isNull();
		ArgumentCaptor<PracticeRunRestartCommand> commandCaptor = ArgumentCaptor.forClass(
			PracticeRunRestartCommand.class);
		verify(orderRestartService).cleanupCurrentRun(commandCaptor.capture());
		assertThat(commandCaptor.getValue().attemptId()).isEqualTo(ATTEMPT_ID);
		assertThat(commandCaptor.getValue().runNumber()).isEqualTo(1L);
		assertThat(commandCaptor.getValue().instrumentId()).isEqualTo(INSTRUMENT_ID);
		assertThat(commandCaptor.getValue().restartedAt()).isEqualTo(NOW);
	}

	@Test
	void restartWithoutInstrumentStillDelegatesEmptyRunCleanupThenIncrementsRun() {
		PracticeAttempt attempt = newAttempt();
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(riskSnapshotRepository.findByAttemptIdAndRunNumber(ATTEMPT_ID, 2L))
			.thenReturn(Optional.empty());

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
		when(riskSnapshotRepository.findByAttemptIdAndRunNumber(ATTEMPT_ID, 2L))
			.thenReturn(Optional.empty());

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
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)1,
			NOW.minusMinutes(10));
		return attempt;
	}
}
