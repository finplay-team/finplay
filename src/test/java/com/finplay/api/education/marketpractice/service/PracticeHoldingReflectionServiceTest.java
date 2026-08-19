// PracticeHoldingReflectionService의 오케스트레이션(소유권 확인 -> progress 잠금 -> chain 재해석 ->
// evidence 존재 확인 -> 복기/완료 저장 -> progress 완료 전이) 분기를 검증하는 단위 테스트다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.domain.PracticeProgress;
import com.finplay.api.education.domain.PracticeProgressStatus;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.domain.PracticeCompletion;
import com.finplay.api.education.marketpractice.domain.PracticeEvidenceType;
import com.finplay.api.education.marketpractice.domain.PracticeMarketObservation;
import com.finplay.api.education.marketpractice.domain.PracticeMarketReflection;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketReflectionRepository;
import com.finplay.api.education.repository.PracticeProgressRepository;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.HoldingService;
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

	private final PracticeAttemptCanonicalPriceService canonicalPriceService = mock(
		PracticeAttemptCanonicalPriceService.class);

	private final PracticeHoldingReflectionService service = new PracticeHoldingReflectionService(
		holdingService, practiceAttemptRepository, practiceAttemptEvidenceService, canonicalPriceService,
		chainResolutionService,
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
		// 이슈 #343: 이미 완료(409)로 실패하는 경로에서는 보상 지급 로직(accountService)이 전혀 호출되지 않아야
		// 한다 — 중복 지급 방지의 첫 번째 방어선.
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
		assertThat(response.prompt()).isEqualTo(PracticeHoldingReflectionResponse.PROMPT);
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

	// 이슈 #343: 완료 성공 시 해당 시장(STOCK) 계좌를 잠가 조회하고 500만원을 지급해야 한다.
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

		verify(accountService).getAccountForUpdate(USER_ID, com.finplay.api.account.domain.Market.STOCK);
		verify(account).addCash(5_000_000L);
	}

	// 이슈 #343: instrument.getMarket()이 CRYPTO면 코인 계좌(com.finplay.api.account.domain.Market.CRYPTO)에
	// 지급돼야 한다 — market 도메인 -> account 도메인 변환이 시장별로 독립적으로 이루어지는지 확인.
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

		verify(accountService).getAccountForUpdate(USER_ID, com.finplay.api.account.domain.Market.CRYPTO);
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
		// answer는 서비스에서 trim하지 않고 요청 값 그대로 저장돼야 한다 — 앞뒤 공백 보존 확인.
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

	// 031 SANDBOX-008: 샘플 종목 chain(instrumentIsTutorialSample=true) 전용 4단계 매도 evidence 전제조건.
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

	// (a) 샘플 chain, evidence A/B 없음 -> 409 PRACTICE_EVIDENCE_MISSING (샘플 여부 이전에 evidence 부재로 이미 걸린다).
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

	// (b) 샘플 chain, evidence A/B 있음, 매도 없음, 5분 이내 -> 409 PRACTICE_EVIDENCE_MISSING
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

	// (c) 샘플 chain, evidence A/B 있음, 매도 없음, 5분 초과 -> 409 PRACTICE_SANDBOX_TIME_EXPIRED
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

	// (d) 샘플 chain, 매도 있음, 매도 체결이 buyTrade.executedAt+5분 초과 -> 409 PRACTICE_SANDBOX_TIME_EXPIRED
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

	// (e) 샘플 chain, evidence A/B 있음 + 매도가 5분 이내 -> 201 성공(변경 없음)
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

	// (d)/경계값: 매도가 정확히 5분 시점(경계 포함)이면 성공해야 한다 (isWithinSaleDeadline은 !isAfter 정책).
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

	// (f) 실제 종목 chain(샘플 아님) — 매도·5분과 무관하게 026 기존 전제조건(A/B만)으로 성공해야 한다(회귀 확인).
	// buyTradeExecutedAt이 5분보다 훨씬 이전이고 매도 체결이 전혀 없어도(sellTradeId=null) 샘플 chain이었다면
	// 만료였을 상황이지만, instrumentIsTutorialSample=false이므로 verifySampleChainSaleEvidence 분기 자체를
	// 타지 않고 evidence A/B만으로 통과해야 한다.
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

	// docs/specs/040-tutorial-restart-after-completion TUTORIAL-RESTART-004~007: attempt 기반 완료 경로의
	// 최초 완료/재완료 분기. attempt.getStatus()가 COMPLETED가 아니어야(재시작 후 진행 중) 이 분기에 들어온다.
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
		verify(accountService).getAccountForUpdate(USER_ID, com.finplay.api.account.domain.Market.STOCK);
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

	// 이슈 #420: evidence를 가진 관찰이 매도 체결 이후에만 존재해도 복기 저장이 완료를 확정해야 한다. hasEvidence의 sellTrade 필터가 되살아나면 이 테스트만 409 PRACTICE_EVIDENCE_MISSING으로 깨진다.
	// 매도 전 관찰을 함께 두면 필터가 되살아나도 그 관찰로 통과해 버려 회귀를 못 잡으므로, evidence 관찰을 매도 이후 1건으로만 구성한다(통합 테스트에서 겪은 함정과 같은 종류다).
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

	// attempt 기반 evidence(스냅샷·매도 evidence·5분 기한)와 진입 조건을 함께 세팅한다. 반환값은 검증용 attempt.
	// 매도 체결은 NOW-1분이므로 기본 관찰 시각(NOW-2분)은 매도 이전이다.
	private PracticeAttempt givenAttemptEvidence() {
		return givenAttemptEvidence(NOW.minusMinutes(2));
	}

	// observedAt만 바꿔 매도 전/후 관찰을 모두 구성할 수 있게 한 오버로드다.
	private PracticeAttempt givenAttemptEvidence(LocalDateTime observedAt) {
		Trade sellTrade = mock(Trade.class);
		when(sellTrade.getExecutedAt()).thenReturn(NOW.minusMinutes(1));
		return givenAttemptEvidence(observedAt, sellTrade);
	}

	// 매도 체결 자체를 바꿔야 하는 시간 게이트 테스트용 오버로드다 — null이면 매도가 없는 경우다.
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
			null);
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

	// 041 SCENARIO-014 — 시간 제한 폐지의 실제 강제 지점이 여기다. 응답의 saleDeadlineAt이 아니라 이 서비스의
	// 자체 상수가 완료를 막는다.
	@Test
	void attemptReflectionRejectsLateSaleForGeneratorVersionOne() {
		Trade lateSell = mock(Trade.class);
		when(lateSell.getExecutedAt()).thenReturn(NOW.plusMinutes(10));
		PracticeAttempt attempt = givenAttemptEvidence(NOW.minusMinutes(2), lateSell);
		when(canonicalPriceService.isScenarioVersion(attempt)).thenReturn(false);

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_SANDBOX_TIME_EXPIRED));
	}

	@Test
	void attemptReflectionAcceptsLateSaleForGeneratorVersionTwo() {
		Trade lateSell = mock(Trade.class);
		when(lateSell.getExecutedAt()).thenReturn(NOW.plusMinutes(10));
		PracticeAttempt attempt = givenAttemptEvidence(NOW.minusMinutes(2), lateSell);
		when(canonicalPriceService.isScenarioVersion(attempt)).thenReturn(true);
		when(practiceMarketReflectionRepository.save(org.mockito.ArgumentMatchers.any(PracticeMarketReflection.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		assertThat(service.createReflection(USER_ID, request())).isNotNull();
	}

	// 매도 체결이 없을 때 던지는 PRACTICE_EVIDENCE_MISSING은 유지한다 — 그건 시간이 아니라 evidence 부재다.
	@Test
	void attemptReflectionStillRequiresSaleEvidenceForGeneratorVersionTwo() {
		PracticeAttempt attempt = givenAttemptEvidence(NOW.minusMinutes(2), null);
		when(canonicalPriceService.isScenarioVersion(attempt)).thenReturn(true);

		assertThatThrownBy(() -> service.createReflection(USER_ID, request()))
			.isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));
	}

	private PracticeHoldingReflectionCreateRequest request() {
		return new PracticeHoldingReflectionCreateRequest(HOLDING_ID, ANSWER);
	}
}
