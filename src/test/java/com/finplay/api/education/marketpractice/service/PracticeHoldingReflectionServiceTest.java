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
import static org.mockito.Mockito.when;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.domain.PracticeProgress;
import com.finplay.api.education.domain.PracticeProgressStatus;
import com.finplay.api.education.marketpractice.domain.PracticeCompletion;
import com.finplay.api.education.marketpractice.domain.PracticeEvidenceType;
import com.finplay.api.education.marketpractice.domain.PracticeMarketObservation;
import com.finplay.api.education.marketpractice.domain.PracticeMarketReflection;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.education.marketpractice.repository.PracticeMarketReflectionRepository;
import com.finplay.api.education.repository.PracticeProgressRepository;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
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
	private final MarketPracticeChainResolutionService chainResolutionService = mock(
		MarketPracticeChainResolutionService.class);
	private final PracticeProgressRepository practiceProgressRepository = mock(PracticeProgressRepository.class);
	private final PracticeMarketObservationRepository practiceMarketObservationRepository = mock(
		PracticeMarketObservationRepository.class);
	private final PracticeMarketReflectionRepository practiceMarketReflectionRepository = mock(
		PracticeMarketReflectionRepository.class);
	private final PracticeCompletionRepository practiceCompletionRepository = mock(
		PracticeCompletionRepository.class);
	private final Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

	private final PracticeHoldingReflectionService service = new PracticeHoldingReflectionService(
		holdingService, chainResolutionService, practiceProgressRepository, practiceMarketObservationRepository,
		practiceMarketReflectionRepository, practiceCompletionRepository, clock);

	private Holding holding;
	private Instrument instrument;
	private PracticeProgress progress;

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
			NOW.minusHours(1), new BigDecimal("100"), 999L, null, null);
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
			NOW.minusHours(1), new BigDecimal("100"), HOLDING_ID, null, null);
	}

	private PracticeHoldingReflectionCreateRequest request() {
		return new PracticeHoldingReflectionCreateRequest(HOLDING_ID, ANSWER);
	}
}
