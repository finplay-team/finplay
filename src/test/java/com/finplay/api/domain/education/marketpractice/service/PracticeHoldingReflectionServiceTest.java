package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.education.entity.PracticeProgress;
import com.finplay.api.domain.education.entity.PracticeProgressStatus;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeCompletion;
import com.finplay.api.domain.education.marketpractice.entity.PracticeEvidenceType;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketReflection;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketReflectionRepository;
import com.finplay.api.domain.education.repository.PracticeProgressRepository;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.HoldingService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PracticeHoldingReflectionServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long HOLDING_ID = 40L;
	private static final Long INSTRUMENT_ID = 100L;
	private static final String ANSWER = "  지금은 손절 라인에 가까워져서 팔지 않기로 했다.  ";
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 10, 0);

	private final HoldingService holdingService = mock(HoldingService.class);
	private final PracticeAttemptRepository practiceAttemptRepository = mock(PracticeAttemptRepository.class);
	private final PracticeAttemptEvidenceService practiceAttemptEvidenceService = mock(
		PracticeAttemptEvidenceService.class);
	private final MarketPracticeChainResolutionService chainResolutionService = mock(
		MarketPracticeChainResolutionService.class);
	private final PracticeProgressRepository practiceProgressRepository = mock(PracticeProgressRepository.class);
	private final PracticeMarketObservationRepository practiceMarketObservationRepository = mock(
		PracticeMarketObservationRepository.class);
	private final PracticeMarketReflectionRepository practiceMarketReflectionRepository = mock(
		PracticeMarketReflectionRepository.class);
	private final PracticeCompletionRepository practiceCompletionRepository = mock(
		PracticeCompletionRepository.class);
	private final AccountService accountService = mock(AccountService.class);
	private final Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

	private final PracticeHoldingReflectionService service = new PracticeHoldingReflectionService(
		holdingService, practiceAttemptRepository, practiceAttemptEvidenceService, chainResolutionService,
		practiceProgressRepository, practiceMarketObservationRepository,
		practiceMarketReflectionRepository, practiceCompletionRepository, accountService, clock);

	private Holding holding;
	private Instrument instrument;
	private PracticeProgress progress;
	private Account account;

	@BeforeEach
	void setUp() {
		instrument = mock(Instrument.class);
		when(instrument.getId()).thenReturn(INSTRUMENT_ID);
		when(instrument.getMarket()).thenReturn(Market.STOCK);

		holding = mock(Holding.class);
		when(holding.getId()).thenReturn(HOLDING_ID);
		when(holding.getInstrument()).thenReturn(instrument);

		progress = mock(PracticeProgress.class);
		when(progress.getStatus()).thenReturn(PracticeProgressStatus.IN_PROGRESS);

		account = mock(Account.class);
		when(accountService.getAccountForUpdate(eq(USER_ID), any())).thenReturn(account);
	}

	@Test
	void createReflectionThrowsNotFoundWhenHoldingMissingOrNotOwned() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

		verify(practiceProgressRepository, never()).findByUserIdAndTutorialKeyForUpdate(any(), any());
	}

	@Test
	void createReflectionThrowsEvidenceMissingWhenProgressRowMissing() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			USER_ID, PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		verify(chainResolutionService, never()).resolveForInstrument(any(), any(), any());
	}

	@Test
	void createReflectionThrowsAlreadyCompletedWhenProgressAlreadyCompleted() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(progress.getStatus()).thenReturn(PracticeProgressStatus.COMPLETED);
		when(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			USER_ID, PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.of(progress));

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_ALREADY_COMPLETED));

		verify(chainResolutionService, never()).resolveForInstrument(any(), any(), any());
		verify(practiceMarketReflectionRepository, never()).save(any());
		verifyNoInteractions(accountService);
	}

	@Test
	void createReflectionThrowsEvidenceMissingWhenChainResolutionFails() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			USER_ID, PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.of(progress));
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		verify(practiceMarketObservationRepository, never())
			.findByUserIdAndHoldingIdOrderByObservedAtAsc(any(), any());
		verify(practiceMarketReflectionRepository, never()).save(any());
	}

	@Test
	void createReflectionThrowsEvidenceMissingWhenResolvedChainHoldingIdDiffersFromRequestedHolding() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			USER_ID, PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.of(progress));
		ResolvedPracticeChainDto mismatchedChain = new ResolvedPracticeChainDto(
			10L, NOW.minusDays(1), 20L, NOW.minusHours(2), new BigDecimal("90"), new BigDecimal("120"), 30L,
			NOW.minusHours(1), new BigDecimal("100"), 999L, null, null, false);
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.of(mismatchedChain));

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		verify(practiceMarketReflectionRepository, never()).save(any());
	}

	@Test
	void createReflectionThrowsEvidenceMissingWhenNoObservationHasEvidenceType() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			USER_ID, PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.of(progress));
		ResolvedPracticeChainDto chain = completedChain();
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.of(chain));

		PracticeMarketObservation noEvidenceObservation = mock(PracticeMarketObservation.class);
		when(noEvidenceObservation.getEvidenceType()).thenReturn(null);
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, HOLDING_ID))
			.thenReturn(List.of(noEvidenceObservation));

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		verify(practiceMarketReflectionRepository, never()).save(any());
		verify(practiceCompletionRepository, never()).save(any());
		verify(progress, never()).complete(any());
	}

	@Test
	void createReflectionSavesReflectionAndCompletionAndCompletesProgressOnHappyPath() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			USER_ID, PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.of(progress));
		ResolvedPracticeChainDto chain = completedChain();
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.of(chain));

		PracticeMarketObservation withEvidence = mock(PracticeMarketObservation.class);
		when(withEvidence.getEvidenceType()).thenReturn(PracticeEvidenceType.CLOSER_TO_BOUNDARY);
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, HOLDING_ID))
			.thenReturn(List.of(withEvidence));

		PracticeMarketReflection savedReflection = PracticeMarketReflection.create(
			USER_ID, holding, PracticeIntentionService.TUTORIAL_KEY, (short)1, ANSWER, NOW);
		when(practiceMarketReflectionRepository.save(any(PracticeMarketReflection.class)))
			.thenReturn(savedReflection);

		PracticeHoldingReflectionResponse response = service.createReflection(USER_ID, request());

		assertThat(response.holdingId()).isEqualTo(HOLDING_ID);
		assertThat(response.answer()).isEqualTo(ANSWER);
		assertThat(response.createdAt()).isEqualTo(NOW);

		ArgumentCaptor<PracticeMarketReflection> reflectionCaptor = ArgumentCaptor
			.forClass(PracticeMarketReflection.class);
		verify(practiceMarketReflectionRepository).save(reflectionCaptor.capture());
		assertThat(reflectionCaptor.getValue().getAnswer()).isEqualTo(ANSWER);
		assertThat(reflectionCaptor.getValue().getTutorialKey()).isEqualTo(PracticeIntentionService.TUTORIAL_KEY);

		ArgumentCaptor<PracticeCompletion> completionCaptor = ArgumentCaptor.forClass(PracticeCompletion.class);
		verify(practiceCompletionRepository).save(completionCaptor.capture());
		assertThat(completionCaptor.getValue().getReflection()).isEqualTo(savedReflection);
		assertThat(completionCaptor.getValue().getTutorialKey()).isEqualTo(PracticeIntentionService.TUTORIAL_KEY);
		assertThat(completionCaptor.getValue().getCompletedAt()).isEqualTo(NOW);

		verify(progress).complete(NOW);
	}

	@Test
	void createReflectionPaysTutorialCompletionRewardToStockAccountOnHappyPath() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			USER_ID, PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.of(progress));
		ResolvedPracticeChainDto chain = completedChain();
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.of(chain));

		PracticeMarketObservation withEvidence = mock(PracticeMarketObservation.class);
		when(withEvidence.getEvidenceType()).thenReturn(PracticeEvidenceType.CLOSER_TO_BOUNDARY);
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, HOLDING_ID))
			.thenReturn(List.of(withEvidence));
		when(practiceMarketReflectionRepository.save(any(PracticeMarketReflection.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		service.createReflection(USER_ID, request());

		verify(accountService).getAccountForUpdate(USER_ID, Market.STOCK);
		verify(account).addCash(5_000_000L);
	}

	@Test
	void createReflectionPaysTutorialCompletionRewardToCryptoAccountWhenInstrumentIsCrypto() {
		when(instrument.getMarket()).thenReturn(Market.CRYPTO);
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			USER_ID, PracticeIntentionService.COIN_TUTORIAL_KEY)).thenReturn(Optional.of(progress));
		ResolvedPracticeChainDto chain = completedChain();
		when(chainResolutionService.resolveForInstrument(
			USER_ID, PracticeIntentionService.COIN_TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.of(chain));

		PracticeMarketObservation withEvidence = mock(PracticeMarketObservation.class);
		when(withEvidence.getEvidenceType()).thenReturn(PracticeEvidenceType.CLOSER_TO_BOUNDARY);
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, HOLDING_ID))
			.thenReturn(List.of(withEvidence));
		when(practiceMarketReflectionRepository.save(any(PracticeMarketReflection.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		service.createReflection(USER_ID, request());

		verify(accountService).getAccountForUpdate(USER_ID, Market.CRYPTO);
		verify(account).addCash(5_000_000L);
	}

	@Test
	void createReflectionStoresAnswerVerbatimWithoutTrimming() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			USER_ID, PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.of(progress));
		ResolvedPracticeChainDto chain = completedChain();
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.of(chain));

		PracticeMarketObservation withEvidence = mock(PracticeMarketObservation.class);
		when(withEvidence.getEvidenceType()).thenReturn(PracticeEvidenceType.TIMED_REPETITION);
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, HOLDING_ID))
			.thenReturn(List.of(withEvidence));

		when(practiceMarketReflectionRepository.save(any(PracticeMarketReflection.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		service.createReflection(USER_ID, request());

		ArgumentCaptor<PracticeMarketReflection> reflectionCaptor = ArgumentCaptor
			.forClass(PracticeMarketReflection.class);
		verify(practiceMarketReflectionRepository).save(reflectionCaptor.capture());
		assertThat(reflectionCaptor.getValue().getAnswer()).isEqualTo(ANSWER);
	}

	@Test
	void createReflectionResolvesCoinTutorialKeyForCryptoInstrument() {
		when(instrument.getMarket()).thenReturn(Market.CRYPTO);
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			eq(USER_ID), eq(PracticeIntentionService.COIN_TUTORIAL_KEY))).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOf(BusinessException.class);

		verify(practiceProgressRepository).findByUserIdAndTutorialKeyForUpdate(
			USER_ID, PracticeIntentionService.COIN_TUTORIAL_KEY);
	}

	private ResolvedPracticeChainDto completedChain() {
		return new ResolvedPracticeChainDto(
			10L, NOW.minusDays(1), 20L, NOW.minusHours(2), new BigDecimal("90"), new BigDecimal("120"), 30L,
			NOW.minusHours(1), new BigDecimal("100"), HOLDING_ID, null, null, false);
	}

	private ResolvedPracticeChainDto sampleChain(
		LocalDateTime buyTradeExecutedAt, Long sellTradeId, LocalDateTime sellTradeExecutedAt) {
		return new ResolvedPracticeChainDto(
			10L, NOW.minusDays(1), 20L, NOW.minusHours(2), new BigDecimal("90"), new BigDecimal("120"), 30L,
			buyTradeExecutedAt, new BigDecimal("100"), HOLDING_ID, sellTradeId, sellTradeExecutedAt, true);
	}

	private void givenChainAndEvidence(ResolvedPracticeChainDto chain) {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			USER_ID, PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.of(progress));
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.of(chain));

		PracticeMarketObservation withEvidence = mock(PracticeMarketObservation.class);
		when(withEvidence.getEvidenceType()).thenReturn(PracticeEvidenceType.CLOSER_TO_BOUNDARY);
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, HOLDING_ID))
			.thenReturn(List.of(withEvidence));
	}

	@Test
	void createReflectionThrowsEvidenceMissingWhenSampleChainHasNoObservationEvidence() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			USER_ID, PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.of(progress));
		ResolvedPracticeChainDto chain = sampleChain(NOW.minusMinutes(1), null, null);
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.of(chain));

		PracticeMarketObservation noEvidenceObservation = mock(PracticeMarketObservation.class);
		when(noEvidenceObservation.getEvidenceType()).thenReturn(null);
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, HOLDING_ID))
			.thenReturn(List.of(noEvidenceObservation));

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		verify(practiceMarketReflectionRepository, never()).save(any());
		verify(progress, never()).complete(any());
	}

	@Test
	void createReflectionThrowsEvidenceMissingWhenSampleChainHasNoSaleWithinFiveMinutes() {
		ResolvedPracticeChainDto chain = sampleChain(NOW.minusMinutes(3), null, null);
		givenChainAndEvidence(chain);

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		verify(practiceMarketReflectionRepository, never()).save(any());
		verify(practiceCompletionRepository, never()).save(any());
		verify(progress, never()).complete(any());
	}

	@Test
	void createReflectionThrowsTimeExpiredWhenSampleChainHasNoSaleAfterFiveMinutes() {
		ResolvedPracticeChainDto chain = sampleChain(NOW.minusMinutes(6), null, null);
		givenChainAndEvidence(chain);

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED));

		verify(practiceMarketReflectionRepository, never()).save(any());
		verify(practiceCompletionRepository, never()).save(any());
		verify(progress, never()).complete(any());
	}

	@Test
	void createReflectionThrowsTimeExpiredWhenSampleChainSaleExecutedAfterFiveMinuteDeadline() {
		ResolvedPracticeChainDto chain = sampleChain(NOW.minusMinutes(10), 40L, NOW);
		givenChainAndEvidence(chain);

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED));

		verify(practiceMarketReflectionRepository, never()).save(any());
		verify(practiceCompletionRepository, never()).save(any());
		verify(progress, never()).complete(any());
	}

	@Test
	void createReflectionSucceedsWhenSampleChainSaleExecutedWithinFiveMinuteDeadline() {
		ResolvedPracticeChainDto chain = sampleChain(NOW.minusMinutes(3), 40L, NOW.minusMinutes(1));
		givenChainAndEvidence(chain);

		PracticeMarketReflection savedReflection = PracticeMarketReflection.create(
			USER_ID, holding, PracticeIntentionService.TUTORIAL_KEY, (short)1, ANSWER, NOW);
		when(practiceMarketReflectionRepository.save(any(PracticeMarketReflection.class)))
			.thenReturn(savedReflection);

		PracticeHoldingReflectionResponse response = service.createReflection(USER_ID, request());

		assertThat(response.holdingId()).isEqualTo(HOLDING_ID);
		verify(practiceMarketReflectionRepository).save(any(PracticeMarketReflection.class));
		verify(practiceCompletionRepository).save(any(PracticeCompletion.class));
		verify(progress).complete(NOW);
	}

	@Test
	void createReflectionSucceedsWhenSampleChainSaleExecutedExactlyAtFiveMinuteBoundary() {
		LocalDateTime buyExecutedAt = NOW.minusMinutes(5);
		ResolvedPracticeChainDto chain = sampleChain(buyExecutedAt, 40L, buyExecutedAt.plusMinutes(5));
		givenChainAndEvidence(chain);

		when(practiceMarketReflectionRepository.save(any(PracticeMarketReflection.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		service.createReflection(USER_ID, request());

		verify(practiceMarketReflectionRepository).save(any(PracticeMarketReflection.class));
		verify(progress).complete(NOW);
	}

	@Test
	void createReflectionSucceedsForRealInstrumentChainRegardlessOfSaleOrFiveMinuteWindow() {
		ResolvedPracticeChainDto realChain = new ResolvedPracticeChainDto(
			10L, NOW.minusDays(1), 20L, NOW.minusHours(2), new BigDecimal("90"), new BigDecimal("120"), 30L,
			NOW.minusHours(1), new BigDecimal("100"), HOLDING_ID, null, null, false);
		givenChainAndEvidence(realChain);

		when(practiceMarketReflectionRepository.save(any(PracticeMarketReflection.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		service.createReflection(USER_ID, request());

		verify(practiceMarketReflectionRepository).save(any(PracticeMarketReflection.class));
		verify(practiceCompletionRepository).save(any(PracticeCompletion.class));
		verify(progress).complete(NOW);
	}

	@Test
	void createAttemptReflectionSavesEvidenceAndPaysRewardWhenNoPriorCompletionExists() {
		PracticeAttempt attempt = givenAttemptEvidence();
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());
		when(practiceMarketReflectionRepository.save(any(PracticeMarketReflection.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		PracticeHoldingReflectionResponse response = service.createReflection(USER_ID, request());

		assertThat(response.rewardGranted()).isTrue();
		assertThat(response.holdingId()).isEqualTo(HOLDING_ID);
		verify(practiceMarketReflectionRepository).save(any(PracticeMarketReflection.class));
		verify(practiceCompletionRepository).save(any(PracticeCompletion.class));
		verify(progress).complete(NOW);
		verify(attempt).complete(NOW);
		verify(accountService).getAccountForUpdate(USER_ID, Market.STOCK);
		verify(account).addCash(5_000_000L);
	}

	@Test
	void createAttemptReflectionSkipsEvidenceWritesAndRewardWhenCompletionAlreadyExists() {
		PracticeAttempt attempt = givenAttemptEvidence();
		PracticeCompletion existingCompletion = mock(PracticeCompletion.class);
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.of(existingCompletion));

		PracticeHoldingReflectionResponse response = service.createReflection(USER_ID, request());

		assertThat(response.rewardGranted()).isFalse();
		assertThat(response.reflectionId()).isNull();
		assertThat(response.holdingId()).isEqualTo(HOLDING_ID);
		assertThat(response.answer()).isEqualTo(ANSWER);
		verify(practiceMarketReflectionRepository, never()).save(any());
		verify(practiceCompletionRepository, never()).save(any());
		verify(progress, never()).complete(any());
		verify(attempt).complete(NOW);
		verifyNoInteractions(accountService);
	}

	@Test
	void createAttemptReflectionCompletesWhenOnlyObservationAfterSellHasEvidence() {
		PracticeAttempt attempt = givenAttemptEvidence(NOW.minusSeconds(30));
		when(practiceCompletionRepository.findByUserIdAndTutorialKey(USER_ID, PracticeIntentionService.TUTORIAL_KEY))
			.thenReturn(Optional.empty());
		when(practiceMarketReflectionRepository.save(any(PracticeMarketReflection.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		PracticeHoldingReflectionResponse response = service.createReflection(USER_ID, request());

		assertThat(response.rewardGranted()).isTrue();
		verify(practiceMarketReflectionRepository).save(any(PracticeMarketReflection.class));
		verify(practiceCompletionRepository).save(any(PracticeCompletion.class));
		verify(progress).complete(NOW);
		verify(attempt).complete(NOW);
	}

	private PracticeAttempt givenAttemptEvidence() {
		return givenAttemptEvidence(NOW.minusMinutes(2));
	}

	private PracticeAttempt givenAttemptEvidence(LocalDateTime observedAt) {
		Trade sellTrade = mock(Trade.class);
		when(sellTrade.getExecutedAt()).thenReturn(NOW.minusMinutes(1));
		return givenAttemptEvidence(observedAt, sellTrade);
	}

	private PracticeAttempt givenAttemptEvidence(LocalDateTime observedAt, Trade sellTrade) {
		when(instrument.isTutorialSample()).thenReturn(true);

		PracticeAttempt attempt = mock(PracticeAttempt.class);
		when(attempt.getStatus()).thenReturn(PracticeAttemptStatus.IN_PROGRESS);
		when(attempt.getMarket()).thenReturn(Market.STOCK);
		when(attempt.getCreatedAt()).thenReturn(NOW.minusDays(1));
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.STOCK))
			.thenReturn(Optional.of(attempt));

		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));

		PracticeRiskSnapshot riskSnapshot = mock(PracticeRiskSnapshot.class);
		when(riskSnapshot.getCreatedAt()).thenReturn(NOW.minusMinutes(4));
		Trade buyTrade = mock(Trade.class);
		when(buyTrade.getExecutedAt()).thenReturn(NOW.minusMinutes(4));
		when(riskSnapshot.getBuyTrade()).thenReturn(buyTrade);

		ResolvedPracticeAttemptEvidenceDto evidence = new ResolvedPracticeAttemptEvidenceDto(
			riskSnapshot, riskSnapshot, HOLDING_ID, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, sellTrade, null,
			null, null,
			null, null);
		when(practiceAttemptEvidenceService.requireCurrentRun(attempt, USER_ID, HOLDING_ID)).thenReturn(evidence);

		PracticeMarketObservation withEvidence = mock(PracticeMarketObservation.class);
		when(withEvidence.getEvidenceType()).thenReturn(PracticeEvidenceType.CLOSER_TO_BOUNDARY);
		when(withEvidence.getObservedAt()).thenReturn(observedAt);
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, HOLDING_ID))
			.thenReturn(List.of(withEvidence));

		when(practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(
			USER_ID, PracticeIntentionService.TUTORIAL_KEY)).thenReturn(Optional.of(progress));

		return attempt;
	}

	@Test
	void attemptReflectionRejectsLateSaleForGeneratorVersionOne() {
		Trade lateSell = mock(Trade.class);
		when(lateSell.getExecutedAt()).thenReturn(NOW.plusMinutes(10));
		PracticeAttempt attempt = givenAttemptEvidence(NOW.minusMinutes(2), lateSell);
		when(attempt.usesScenarioScript()).thenReturn(false);

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED));
	}

	@Test
	void attemptReflectionAcceptsLateSaleForGeneratorVersionTwo() {
		Trade lateSell = mock(Trade.class);
		when(lateSell.getExecutedAt()).thenReturn(NOW.plusMinutes(10));
		PracticeAttempt attempt = givenAttemptEvidence(NOW.minusMinutes(2), lateSell);
		when(attempt.usesScenarioScript()).thenReturn(true);
		when(practiceMarketReflectionRepository.save(org.mockito.ArgumentMatchers.any(PracticeMarketReflection.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		assertThat(service.createReflection(USER_ID, request())).isNotNull();
	}

	@Test
	void attemptReflectionStillRequiresSaleEvidenceForGeneratorVersionTwo() {
		PracticeAttempt attempt = givenAttemptEvidence(NOW.minusMinutes(2), null);
		when(attempt.usesScenarioScript()).thenReturn(true);

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));
	}

	private PracticeHoldingReflectionCreateRequest request() {
		return new PracticeHoldingReflectionCreateRequest(HOLDING_ID, ANSWER);
	}
}
