// 튜토리얼 attempt의 멱등 진입·완료 replay·시장별 샘플 종목 선택 규칙을 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.TutorialAccount;
import com.finplay.api.account.service.TutorialAccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.domain.PracticeCompletion;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.education.repository.PracticeProgressRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
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
	private final TutorialAccountService tutorialAccountService = mock(TutorialAccountService.class);
	private final PracticeAttemptService service = new PracticeAttemptService(
		practiceAttemptRepository,
		practiceCompletionRepository,
		practiceRiskSnapshotRepository,
		practiceProgressRepository,
		instrumentService,
		tutorialAccountService,
		Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

	@Test
	void ensureAttemptReturnsExistingRunWithoutRestartingIt() {
		PracticeAttempt attempt = selectingAttempt(Market.STOCK);
		Instrument instrument = tutorialInstrument(Market.STOCK, true);
		attempt.selectInstrument(instrument, NOW.minusMinutes(3), NOW.toLocalDate(), 123L, (short)1,
			NOW.minusMinutes(3));
		when(practiceAttemptRepository.insertIfAbsent(USER_ID, Market.STOCK.name(), NOW)).thenReturn(0);
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		// 최초 진입 — 튜토리얼 계좌가 이번에 새로 생성됐다고 가정해 초기값(1000만원/1000만원/0원)을 스텁한다.
		stubTutorialAccount(com.finplay.api.account.domain.Market.STOCK, freshTutorialAccount());

		PracticeAttemptResponse response = service.ensureAttempt(USER_ID, Market.STOCK);

		assertThat(response.runNumber()).isEqualTo(1L);
		assertThat(response.status()).isEqualTo("IN_PROGRESS");
		assertThat(response.instrumentId()).isEqualTo(INSTRUMENT_ID);
		assertThat(response.anchorAt()).isEqualTo(NOW.minusMinutes(3));
		// TUTORIAL-CASH-ISOL-011 — 최초 진입 응답은 신규 생성된 튜토리얼 계좌의 초기값을 그대로 반영한다.
		assertThat(response.tutorialCashBalance()).isEqualTo(10_000_000L);
		assertThat(response.tutorialAvailableCash()).isEqualTo(10_000_000L);
		assertThat(response.tutorialRealizedPnl()).isZero();
		verify(practiceAttemptRepository, never()).save(org.mockito.ArgumentMatchers.any());
		verify(tutorialAccountService)
			.getOrCreateForUpdate(USER_ID, com.finplay.api.account.domain.Market.STOCK, NOW);
	}

	// TUTORIAL-CASH-ISOL-011 — 이미 매매로 값이 바뀐 튜토리얼 계좌(신규 생성이 아닌 재진입)를 다시 조회하면
	// 그 시점의 실제 cashBalance·availableCash(=cashBalance-reservedCash)·realizedPnl이 그대로 반영돼야 한다.
	// 예약 현금(코인 지정가 매수 대기 중)이 있는 상태에서도 availableCash 공식이 정확한지 함께 확인한다.
	@Test
	void ensureAttemptOnReentryReflectsMutatedTutorialAccountIncludingReservedCash() {
		PracticeAttempt attempt = selectingAttempt(Market.STOCK);
		Instrument instrument = tutorialInstrument(Market.STOCK, true);
		attempt.selectInstrument(instrument, NOW.minusMinutes(3), NOW.toLocalDate(), 123L, (short)1,
			NOW.minusMinutes(3));
		when(practiceAttemptRepository.insertIfAbsent(USER_ID, Market.STOCK.name(), NOW)).thenReturn(0);
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));
		when(practiceRiskSnapshotRepository.findByAttemptIdAndRunNumber(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		TutorialAccount mutated = freshTutorialAccount();
		mutated.deductCash(2_000_000L); // 매수 체결로 800만원까지 감소
		mutated.reserveCash(500_000L); // 코인 지정가 매수 대기 중 — 예약 현금 50만원
		mutated.addRealizedPnl(300_000L); // 이전 매도로 누적된 실현손익 30만원
		stubTutorialAccount(com.finplay.api.account.domain.Market.STOCK, mutated);

		PracticeAttemptResponse response = service.ensureAttempt(USER_ID, Market.STOCK);

		assertThat(response.tutorialCashBalance()).isEqualTo(8_000_000L);
		assertThat(response.tutorialAvailableCash()).isEqualTo(7_500_000L); // 800만원 - 예약 50만원
		assertThat(response.tutorialRealizedPnl()).isEqualTo(300_000L);
	}

	@Test
	void ensureCompletedAttemptReturnsReplayWithoutWritingAttempt() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		attempt.selectInstrument(tutorialInstrument(Market.CRYPTO, true), NOW.minusDays(2),
			NOW.minusDays(2).toLocalDate(), 456L, (short)1, NOW.minusDays(2));
		ReflectionTestUtils.setField(attempt, "status", PracticeAttemptStatus.COMPLETED);
		ReflectionTestUtils.setField(attempt, "completedAt", NOW.minusDays(1));
		when(practiceAttemptRepository.insertIfAbsent(USER_ID, Market.CRYPTO.name(), NOW)).thenReturn(0);
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		stubTutorialAccount(com.finplay.api.account.domain.Market.CRYPTO, freshTutorialAccount());

		PracticeAttemptResponse response = service.ensureAttempt(USER_ID, Market.CRYPTO);

		assertThat(response.mode()).isEqualTo("REPLAY");
		assertThat(response.status()).isEqualTo("COMPLETED");
		assertThat(response.completedAt()).isEqualTo(NOW.minusDays(1));
		verify(practiceAttemptRepository, never()).save(org.mockito.ArgumentMatchers.any());
		verify(tutorialAccountService)
			.getOrCreateForUpdate(USER_ID, com.finplay.api.account.domain.Market.CRYPTO, NOW);
	}

	@ParameterizedTest
	@EnumSource(value = PracticeAttemptStatus.class, names = {"SELECTING_INSTRUMENT", "IN_PROGRESS", "EXPIRED"})
	void ensureAttemptWithCoexistingCompletionAndNonCompletedAttemptReturnsCurrentStateWithoutError(
		PracticeAttemptStatus status) {
		PracticeAttempt attempt = selectingAttempt(Market.STOCK);
		Instrument instrument = tutorialInstrument(Market.STOCK, true);
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 999L, (short)1,
			NOW.minusMinutes(10));
		ReflectionTestUtils.setField(attempt, "status", status);
		PracticeCompletion completion = mock(PracticeCompletion.class);
		when(practiceAttemptRepository.insertIfAbsent(USER_ID, Market.STOCK.name(), NOW)).thenReturn(0);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, "INVESTMENT_PRACTICE_V1"))
			.thenReturn(Optional.of(completion));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		stubTutorialAccount(com.finplay.api.account.domain.Market.STOCK, freshTutorialAccount());

		PracticeAttemptResponse response = service.ensureAttempt(USER_ID, Market.STOCK);

		assertThat(response.status()).isEqualTo(status.name());
		assertThat(response.instrumentId()).isEqualTo(INSTRUMENT_ID);
		assertThat(response.anchorAt()).isEqualTo(NOW.minusMinutes(10));
		verify(practiceAttemptRepository, never()).save(org.mockito.ArgumentMatchers.any());
		verify(tutorialAccountService)
			.getOrCreateForUpdate(USER_ID, com.finplay.api.account.domain.Market.STOCK, NOW);
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

	private void stubTutorialAccount(com.finplay.api.account.domain.Market market, TutorialAccount account) {
		when(tutorialAccountService.getOrCreateForUpdate(USER_ID, market, NOW)).thenReturn(account);
	}

	private static TutorialAccount freshTutorialAccount() {
		User user = User.create("tutorial-trader@finplay.com", "password-hash", "tutorial-trader", NOW);
		return TutorialAccount.create(user, com.finplay.api.account.domain.Market.STOCK, NOW);
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
