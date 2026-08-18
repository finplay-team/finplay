// 튜토리얼 attempt 재시작의 완료 replay, 실행 세대 증가와 선택 상태 초기화를 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.TutorialAccount;
import com.finplay.api.account.service.TutorialAccountService;
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

	// cleanupCurrentRun(TUTORIAL-CASH-ISOL-006)이 같은 트랜잭션 안에서 이미 튜토리얼 계좌를 리셋했다고
	// 가정하고, restart()는 그 결과를 다시 조회(getOrCreateForUpdate)해 응답에 싣는다 — 리셋 직후 값
	// (1000만원/1000만원/0원)을 반환하도록 스텁한다.
	private TutorialAccount resetTutorialAccountStub() {
		TutorialAccount account = mock(TutorialAccount.class);
		when(account.getCashBalance()).thenReturn(10_000_000L);
		when(account.getAvailableCash()).thenReturn(10_000_000L);
		when(account.getRealizedPnl()).thenReturn(0L);
		when(tutorialAccountService.getOrCreateForUpdate(
			USER_ID, com.finplay.api.account.domain.Market.CRYPTO, NOW))
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
		// TUTORIAL-CASH-ISOL-011 — 재시작 응답이 리셋 직후 값(1000만원/1000만원/0원)을 정확히 반영한다.
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

	// restart()는 cleanupCurrentRun(mock) 이후 getOrCreateForUpdate로 재조회한 결과를 그대로 싣는다는 배선을
	// 검증한다 — cleanupCurrentRun 내부에서 실제로 resetForUpdate가 호출되는지는 이 서비스가 mock으로 격리한
	// 협력자이므로 이 단위 테스트로는 검증할 수 없고, TutorialAccountServiceTest·통합 테스트가 담당한다.
	@Test
	void restartQueriesTutorialAccountAfterCleanupDelegatesToOrderRestartService() {
		PracticeAttempt attempt = selectedAttempt();
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(riskSnapshotRepository.findByAttemptIdAndRunNumber(ATTEMPT_ID, 2L))
			.thenReturn(Optional.empty());
		resetTutorialAccountStub();

		service.restart(USER_ID, Market.CRYPTO);

		org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(orderRestartService, tutorialAccountService);
		inOrder.verify(orderRestartService).cleanupCurrentRun(org.mockito.ArgumentMatchers.any());
		inOrder.verify(tutorialAccountService)
			.getOrCreateForUpdate(USER_ID, com.finplay.api.account.domain.Market.CRYPTO, NOW);
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
		// 실제 종목을 정리 대상으로 넘기지 않으므로 보상 매도가 실제 holding에 찍힐 수 없다.
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
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)1,
			NOW.minusMinutes(10));
		return attempt;
	}

	// V32 샌드박스 종목 도입 이전에 실제 종목으로 완료해 진입 시 실제 종목이 심어진 replay attempt다 (이슈 #433).
	private static PracticeAttempt legacyCompletedReplayAttempt() {
		PracticeAttempt attempt = newAttempt();
		Instrument realInstrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(realInstrument, "id", REAL_INSTRUMENT_ID);
		attempt.selectInstrument(realInstrument, NOW.minusDays(1), NOW.toLocalDate().minusDays(1), 456L, (short)1,
			NOW.minusDays(1));
		ReflectionTestUtils.setField(attempt, "status", PracticeAttemptStatus.COMPLETED);
		ReflectionTestUtils.setField(attempt, "completedAt", NOW.minusDays(1));
		return attempt;
	}
}
