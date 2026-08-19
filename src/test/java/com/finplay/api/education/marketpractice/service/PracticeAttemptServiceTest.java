// 튜토리얼 attempt의 멱등 진입·완료 replay·시장별 샘플 종목 선택 규칙을 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.TutorialAccount;
import com.finplay.api.account.service.TutorialAccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.ExitPreset;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.domain.PracticeCompletion;
import com.finplay.api.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.education.repository.PracticeProgressRepository;
import com.finplay.api.education.marketpractice.domain.PracticeMarketReflection;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.order.service.TradeService;
import com.finplay.api.market.service.TutorialPriceGenerator;
import com.finplay.api.market.service.TutorialScenarioScriptLoader;
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
	// 대본이 저작된 시장에서만 생성기 버전 2를 준다 — 실제 로더를 써야 이 판정이 대본 파일과 함께 움직인다.
	private final TutorialScenarioScriptLoader tutorialScenarioScriptLoader = new TutorialScenarioScriptLoader(
		new tools.jackson.databind.ObjectMapper());
	private final PracticeAttemptService service = new PracticeAttemptService(
		practiceAttemptRepository,
		practiceCompletionRepository,
		practiceRiskSnapshotRepository,
		practiceProgressRepository,
		instrumentService,
		tradeService,
		tutorialScenarioScriptLoader,
		tutorialAccountService,
		Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC));

	// 프리셋 잠금 판정이 매 응답에서 순보유수량을 읽는다(042 EXITPRESET-003). 이 테스트들의 대상은 잠금이
	// 아니므로 기본을 "미보유"로 두고, 잠금을 보는 테스트만 따로 덮어쓴다.
	@BeforeEach
	void stubNoHolding() {
		when(tradeService.netFilledQuantity(anyLong(), anyLong())).thenReturn(BigDecimal.ZERO);
	}

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
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
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
		// 041 5번 — 대본이 저작된 시장(CRYPTO)만 생성기 버전 2를 받는다. STOCK 대본은 SCENARIO-024의
		// 후속이라 아직 없고, 시장을 가리지 않고 2를 주면 STOCK 튜토리얼이 가격 조회에서 통째로 터진다.
		assertThat(attempt.getGeneratorVersion()).isEqualTo(
			market == Market.CRYPTO ? TutorialPriceGenerator.VERSION_2 : TutorialPriceGenerator.VERSION_1);
		// 대본 위치는 여전히 비어 있다 — 첫 tick이 대본의 첫 구간으로 초기화한다(041 3번이 남긴 계약).
		assertThat(attempt.getScenarioStageId()).isNull();
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

	// legacy completion만 있는 사용자에게 만들어 주는 읽기 전용 replay는 대본 커서가 없고 tick도 돌지 않는다.
	// 버전 2를 주면 대본 첫 구간 0분에 고정된 평평한 차트가 되므로 기존 재현(버전 1)을 그대로 둔다.
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
		when(practiceAttemptRepository.insertIfAbsent(USER_ID, Market.CRYPTO.name(), NOW)).thenReturn(1);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, "COIN_PRACTICE_V1"))
			.thenReturn(Optional.of(completion));
		when(practiceRiskSnapshotRepository.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty());
		stubTutorialAccount(com.finplay.api.account.domain.Market.CRYPTO, freshTutorialAccount());

		PracticeAttemptResponse response = service.ensureAttempt(USER_ID, Market.CRYPTO);

		assertThat(response.mode()).isEqualTo("REPLAY");
		assertThat(attempt.getGeneratorVersion()).isEqualTo(TutorialPriceGenerator.VERSION_1);
	}

	// 042 EXITPRESET-003 — 잠금 기준은 "최초 매수 여부"가 아니라 "지금 들고 있는가"다. 손절 뒤 재진입
	// 대기 중에는 다시 바꿀 수 있어야 한다.
	@Test
	void selectExitPresetIsAllowedWhileNothingIsHeld() {
		PracticeAttempt attempt = selectingAttempt(Market.CRYPTO);
		attempt.selectInstrument(tutorialInstrument(Market.CRYPTO, true), NOW, NOW.toLocalDate(), 1L, (short)2, NOW);
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
		attempt.selectInstrument(tutorialInstrument(Market.CRYPTO, true), NOW, NOW.toLocalDate(), 1L, (short)2, NOW);
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
		attempt.selectInstrument(tutorialInstrument(Market.CRYPTO, true), NOW, NOW.toLocalDate(), 1L, (short)2, NOW);
		ReflectionTestUtils.setField(attempt, "status", PracticeAttemptStatus.COMPLETED);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));

		assertThatThrownBy(() -> service.selectExitPreset(USER_ID, Market.CRYPTO, ExitPreset.RELAXED))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_ALREADY_COMPLETED));
	}

	// 미선택 사용자의 응답도 기본 프리셋으로 채워 내려간다(EXITPRESET-002) — 클라이언트가 null 분기를
	// 갖지 않고, 화면에 보이는 값과 실제로 적용될 값이 같다.
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
