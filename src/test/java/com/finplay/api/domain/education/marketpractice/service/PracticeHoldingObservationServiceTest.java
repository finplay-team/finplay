package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingObservationResponse;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeBoundary;
import com.finplay.api.domain.education.marketpractice.entity.PracticeEvidenceType;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.priceruntime.service.PracticePriceObservationService;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.market.service.PriceQuoteDto;
import com.finplay.api.domain.market.service.PriceStatus;
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
import org.mockito.InOrder;
import org.mockito.Mockito;

class PracticeHoldingObservationServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long HOLDING_ID = 40L;
	private static final Long INSTRUMENT_ID = 100L;
	private static final LocalDateTime OBSERVED_AT = LocalDateTime.of(2026, 8, 10, 10, 0);

	private final HoldingService holdingService = mock(HoldingService.class);
	private final PracticeAttemptRepository practiceAttemptRepository = mock(PracticeAttemptRepository.class);
	private final PracticeAttemptEvidenceService practiceAttemptEvidenceService = mock(
		PracticeAttemptEvidenceService.class);
	private final MarketPracticeChainResolutionService chainResolutionService = mock(
		MarketPracticeChainResolutionService.class);
	private final PriceQueryService priceQueryService = mock(PriceQueryService.class);
	private final PracticeAttemptCanonicalPriceService canonicalPriceService = mock(
		PracticeAttemptCanonicalPriceService.class);
	private final PracticePriceObservationService practicePriceObservationService = mock(
		PracticePriceObservationService.class);
	private final ReferencePriceCalculator referencePriceCalculator = mock(ReferencePriceCalculator.class);
	private final EvidenceJudgmentService evidenceJudgmentService = mock(EvidenceJudgmentService.class);
	private final PracticeMarketObservationRepository observationRepository = mock(
		PracticeMarketObservationRepository.class);
	private final Clock clock = Clock.fixed(OBSERVED_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

	private final PracticeHoldingObservationService service = new PracticeHoldingObservationService(
		holdingService, practiceAttemptRepository, practiceAttemptEvidenceService, chainResolutionService,
		priceQueryService, canonicalPriceService,
		practicePriceObservationService,
		referencePriceCalculator, evidenceJudgmentService, observationRepository, clock);

	private Holding holding;
	private Instrument instrument;

	@BeforeEach
	void setUp() {
		instrument = mock(Instrument.class);
		when(instrument.getId()).thenReturn(INSTRUMENT_ID);
		when(instrument.getMarket()).thenReturn(Market.STOCK);

		holding = mock(Holding.class);
		when(holding.getId()).thenReturn(HOLDING_ID);
		when(holding.getInstrument()).thenReturn(instrument);
	}

	@Test
	void createObservationThrowsNotFoundWhenHoldingMissingOrNotOwned() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createObservation(
			USER_ID, new PracticeHoldingObservationCreateRequest(HOLDING_ID)))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));

		verify(chainResolutionService, never()).resolveForInstrument(any(), any(), any());
	}

	@Test
	void createObservationThrowsEvidenceMissingWhenChainResolutionFails() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createObservation(
			USER_ID, new PracticeHoldingObservationCreateRequest(HOLDING_ID)))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		verify(referencePriceCalculator, never()).calculate(any());
		verify(priceQueryService, never()).getPrice(any());
		verify(practicePriceObservationService, never()).findObservationPrice(any(), any(), any());
	}

	@Test
	void createObservationThrowsEvidenceMissingWhenResolvedChainHoldingIdDiffersFromRequestedHolding() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		ResolvedPracticeChainDto mismatchedChain = new ResolvedPracticeChainDto(
			10L, OBSERVED_AT.minusDays(1), 20L, OBSERVED_AT.minusHours(2), new BigDecimal("90"),
			new BigDecimal("120"), 30L, OBSERVED_AT.minusHours(1), new BigDecimal("100"), 999L, null, null, false);
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.of(mismatchedChain));

		assertThatThrownBy(() -> service.createObservation(
			USER_ID, new PracticeHoldingObservationCreateRequest(HOLDING_ID)))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		verify(referencePriceCalculator, never()).calculate(any());
	}

	@Test
	void createObservationThrowsEvidenceMissingWhenReferencePriceCalculationFails() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		ResolvedPracticeChainDto chain = completedChain();
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.of(chain));
		when(referencePriceCalculator.calculate(chain)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createObservation(
			USER_ID, new PracticeHoldingObservationCreateRequest(HOLDING_ID)))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRACTICE_EVIDENCE_MISSING));

		verify(priceQueryService, never()).getPrice(any());
		verify(practicePriceObservationService, never()).findObservationPrice(any(), any(), any());
	}

	@Test
	void createObservationSavesObservationAndReturnsResponseOnHappyPath() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		ResolvedPracticeChainDto chain = completedChain();
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.of(chain));

		ReferencePriceLines referenceLines = new ReferencePriceLines(new BigDecimal("90"), new BigDecimal("120"));
		when(referencePriceCalculator.calculate(chain)).thenReturn(Optional.of(referenceLines));

		when(practicePriceObservationService.findObservationPrice(USER_ID, chain.buyTradeId(), INSTRUMENT_ID))
			.thenReturn(Optional.empty());
		PriceQuoteDto priceQuote = new PriceQuoteDto(new BigDecimal("95"), OBSERVED_AT, PriceStatus.AVAILABLE, null);
		when(priceQueryService.getPrice(INSTRUMENT_ID)).thenReturn(priceQuote);

		List<PracticeMarketObservation> existing = List.of();
		when(observationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, HOLDING_ID))
			.thenReturn(existing);

		ObservationEvidenceJudgment judgment = new ObservationEvidenceJudgment(
			true, PracticeBoundary.STOP_LOSS, PracticeEvidenceType.CLOSER_TO_BOUNDARY);
		when(evidenceJudgmentService.judgeObservationEvidence(
			chain.buyTradeEntryPrice(), referenceLines.referenceStopLossPrice(),
			referenceLines.referenceTakeProfitPrice(), priceQuote.price(), existing, OBSERVED_AT))
			.thenReturn(judgment);

		PracticeMarketObservation saved = PracticeMarketObservation.create(
			USER_ID, holding, INSTRUMENT_ID, priceQuote.price(), judgment.closerToBoundary(),
			judgment.closerBoundary(), judgment.evidenceType(), OBSERVED_AT);
		when(observationRepository.save(any(PracticeMarketObservation.class))).thenReturn(saved);

		PracticeHoldingObservationResponse response = service.createObservation(
			USER_ID, new PracticeHoldingObservationCreateRequest(HOLDING_ID));

		assertThat(response.holdingId()).isEqualTo(HOLDING_ID);
		assertThat(response.currentPrice()).isEqualByComparingTo("95");
		assertThat(response.closerToBoundary()).isTrue();
		assertThat(response.closerBoundary()).isEqualTo("STOP_LOSS");
		assertThat(response.evidenceType()).isEqualTo("CLOSER_TO_BOUNDARY");

		InOrder inOrder = Mockito.inOrder(referencePriceCalculator, priceQueryService);
		inOrder.verify(referencePriceCalculator).calculate(chain);
		inOrder.verify(priceQueryService).getPrice(INSTRUMENT_ID);
	}

	@Test
	void createObservationUsesCanonicalPriceForTutorialSampleAndSkipsOtherPriceSources() {
		when(instrument.isTutorialSample()).thenReturn(true);
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		PracticeAttempt attempt = mock(PracticeAttempt.class);
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK)).thenReturn(Optional.of(attempt));
		PracticeRiskSnapshot snapshot = mock(PracticeRiskSnapshot.class);
		when(snapshot.getEntryPrice()).thenReturn(new BigDecimal("100"));
		when(snapshot.getStopLossPrice()).thenReturn(new BigDecimal("90"));
		when(snapshot.getTakeProfitPrice()).thenReturn(new BigDecimal("120"));
		when(snapshot.getCreatedAt()).thenReturn(OBSERVED_AT.minusSeconds(1));
		ResolvedPracticeAttemptEvidenceDto evidence = new ResolvedPracticeAttemptEvidenceDto(
			snapshot, snapshot, HOLDING_ID, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, null, null, null,
			null, null);
		when(practiceAttemptEvidenceService.requireCurrentRun(attempt, USER_ID, HOLDING_ID)).thenReturn(evidence);
		BigDecimal canonicalPrice = new BigDecimal("10932.45600000");
		when(canonicalPriceService.canonicalPriceForMutation(USER_ID, instrument, OBSERVED_AT))
			.thenReturn(canonicalPrice);
		when(observationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, HOLDING_ID))
			.thenReturn(List.of());
		ObservationEvidenceJudgment judgment = new ObservationEvidenceJudgment(false, null, null);
		when(evidenceJudgmentService.judgeObservationEvidence(
			snapshot.getEntryPrice(), snapshot.getStopLossPrice(), snapshot.getTakeProfitPrice(), canonicalPrice,
			List.of(), OBSERVED_AT))
			.thenReturn(judgment);
		when(observationRepository.save(any(PracticeMarketObservation.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		PracticeHoldingObservationResponse response = service.createObservation(
			USER_ID, new PracticeHoldingObservationCreateRequest(HOLDING_ID));

		assertThat(response.currentPrice()).isEqualByComparingTo(canonicalPrice);
		verify(priceQueryService, never()).getPrice(any());
		verify(practicePriceObservationService, never()).findObservationPrice(any(), any(), any());
	}

	@Test
	void createObservationSavesAfterFullSellSoEvidenceKeepsAccumulating() {
		when(instrument.getMarket()).thenReturn(Market.CRYPTO);
		when(instrument.isTutorialSample()).thenReturn(true);
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		PracticeAttempt attempt = mock(PracticeAttempt.class);
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.CRYPTO)).thenReturn(Optional.of(attempt));
		PracticeRiskSnapshot snapshot = mock(PracticeRiskSnapshot.class);
		when(snapshot.getEntryPrice()).thenReturn(new BigDecimal("100"));
		when(snapshot.getStopLossPrice()).thenReturn(new BigDecimal("97"));
		when(snapshot.getTakeProfitPrice()).thenReturn(new BigDecimal("105"));
		when(snapshot.getCreatedAt()).thenReturn(OBSERVED_AT.minusMinutes(3));
		ResolvedPracticeAttemptEvidenceDto soldOutEvidence = new ResolvedPracticeAttemptEvidenceDto(
			snapshot, snapshot, HOLDING_ID, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, mock(Trade.class), null,
			null,
			null, null, null);
		when(practiceAttemptEvidenceService.requireCurrentRun(attempt, USER_ID, HOLDING_ID))
			.thenReturn(soldOutEvidence);
		BigDecimal canonicalPrice = new BigDecimal("100.50000000");
		when(canonicalPriceService.canonicalPriceForMutation(USER_ID, instrument, OBSERVED_AT))
			.thenReturn(canonicalPrice);
		List<PracticeMarketObservation> existing = List.of(
			observationAt(OBSERVED_AT.minusMinutes(2)), observationAt(OBSERVED_AT.minusMinutes(1)));
		when(observationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, HOLDING_ID))
			.thenReturn(existing);
		ObservationEvidenceJudgment judgment = new ObservationEvidenceJudgment(
			false, null, PracticeEvidenceType.TIMED_REPETITION);
		when(evidenceJudgmentService.judgeObservationEvidence(
			snapshot.getEntryPrice(), snapshot.getStopLossPrice(), snapshot.getTakeProfitPrice(), canonicalPrice,
			existing, OBSERVED_AT))
			.thenReturn(judgment);
		when(observationRepository.save(any(PracticeMarketObservation.class)))
			.thenAnswer(invocation -> invocation.getArgument(0));

		PracticeHoldingObservationResponse response = service.createObservation(
			USER_ID, new PracticeHoldingObservationCreateRequest(HOLDING_ID));

		assertThat(response.evidenceType()).isEqualTo("TIMED_REPETITION");
		assertThat(response.currentPrice()).isEqualByComparingTo(canonicalPrice);
		verify(observationRepository).save(any(PracticeMarketObservation.class));
	}

	@Test
	void createObservationUsesLastKnownPriceRegardlessOfObservationAge() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		ResolvedPracticeChainDto chain = completedChain();
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.of(chain));

		ReferencePriceLines referenceLines = new ReferencePriceLines(new BigDecimal("90"), new BigDecimal("120"));
		when(referencePriceCalculator.calculate(chain)).thenReturn(Optional.of(referenceLines));

		when(practicePriceObservationService.findObservationPrice(USER_ID, chain.buyTradeId(), INSTRUMENT_ID))
			.thenReturn(Optional.empty());
		PriceQuoteDto oldQuote = new PriceQuoteDto(
			new BigDecimal("95"), OBSERVED_AT.minusHours(3), PriceStatus.AVAILABLE, null);
		when(priceQueryService.getPrice(INSTRUMENT_ID)).thenReturn(oldQuote);

		List<PracticeMarketObservation> existing = List.of();
		when(observationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, HOLDING_ID))
			.thenReturn(existing);

		ObservationEvidenceJudgment judgment = new ObservationEvidenceJudgment(
			true, PracticeBoundary.STOP_LOSS, PracticeEvidenceType.CLOSER_TO_BOUNDARY);
		when(evidenceJudgmentService.judgeObservationEvidence(
			chain.buyTradeEntryPrice(), referenceLines.referenceStopLossPrice(),
			referenceLines.referenceTakeProfitPrice(), oldQuote.price(), existing, OBSERVED_AT))
			.thenReturn(judgment);

		PracticeMarketObservation saved = PracticeMarketObservation.create(
			USER_ID, holding, INSTRUMENT_ID, oldQuote.price(), judgment.closerToBoundary(),
			judgment.closerBoundary(), judgment.evidenceType(), OBSERVED_AT);
		when(observationRepository.save(any(PracticeMarketObservation.class))).thenReturn(saved);

		PracticeHoldingObservationResponse response = service.createObservation(
			USER_ID, new PracticeHoldingObservationCreateRequest(HOLDING_ID));

		assertThat(response.currentPrice()).isEqualByComparingTo("95");
	}

	@Test
	void createObservationUsesSessionPriceAndSkipsRealPriceLookupWhenBuyTradeHasPracticeSession() {
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		ResolvedPracticeChainDto chain = completedChain();
		when(chainResolutionService.resolveForInstrument(USER_ID, PracticeIntentionService.TUTORIAL_KEY, INSTRUMENT_ID))
			.thenReturn(Optional.of(chain));

		ReferencePriceLines referenceLines = new ReferencePriceLines(new BigDecimal("90"), new BigDecimal("120"));
		when(referencePriceCalculator.calculate(chain)).thenReturn(Optional.of(referenceLines));

		BigDecimal sessionPrice = new BigDecimal("101.5");
		when(practicePriceObservationService.findObservationPrice(USER_ID, chain.buyTradeId(), INSTRUMENT_ID))
			.thenReturn(Optional.of(sessionPrice));

		List<PracticeMarketObservation> existing = List.of();
		when(observationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, HOLDING_ID))
			.thenReturn(existing);

		ObservationEvidenceJudgment judgment = new ObservationEvidenceJudgment(
			false, null, null);
		when(evidenceJudgmentService.judgeObservationEvidence(
			chain.buyTradeEntryPrice(), referenceLines.referenceStopLossPrice(),
			referenceLines.referenceTakeProfitPrice(), sessionPrice, existing, OBSERVED_AT))
			.thenReturn(judgment);

		PracticeMarketObservation saved = PracticeMarketObservation.create(
			USER_ID, holding, INSTRUMENT_ID, sessionPrice, judgment.closerToBoundary(),
			judgment.closerBoundary(), judgment.evidenceType(), OBSERVED_AT);
		when(observationRepository.save(any(PracticeMarketObservation.class))).thenReturn(saved);

		PracticeHoldingObservationResponse response = service.createObservation(
			USER_ID, new PracticeHoldingObservationCreateRequest(HOLDING_ID));

		assertThat(response.currentPrice()).isEqualByComparingTo("101.5");
		verify(priceQueryService, never()).getPrice(any());
	}

	@Test
	void createObservationResolvesCoinTutorialKeyForCryptoInstrument() {
		when(instrument.getMarket()).thenReturn(Market.CRYPTO);
		when(holdingService.findHoldingForOwner(USER_ID, HOLDING_ID)).thenReturn(Optional.of(holding));
		when(chainResolutionService.resolveForInstrument(
			eq(USER_ID), eq(PracticeIntentionService.COIN_TUTORIAL_KEY), eq(INSTRUMENT_ID)))
			.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.createObservation(
			USER_ID, new PracticeHoldingObservationCreateRequest(HOLDING_ID)))
			.isInstanceOf(BusinessException.class);

		verify(chainResolutionService).resolveForInstrument(
			USER_ID, PracticeIntentionService.COIN_TUTORIAL_KEY, INSTRUMENT_ID);
	}

	private PracticeMarketObservation observationAt(LocalDateTime observedAt) {
		return PracticeMarketObservation.create(
			USER_ID, holding, INSTRUMENT_ID, new BigDecimal("100"), false, null, null, observedAt);
	}

	private ResolvedPracticeChainDto completedChain() {
		return new ResolvedPracticeChainDto(
			10L, OBSERVED_AT.minusDays(1), 20L, OBSERVED_AT.minusHours(2), new BigDecimal("90"),
			new BigDecimal("120"), 30L, OBSERVED_AT.minusHours(1), new BigDecimal("100"), HOLDING_ID, null, null,
			false);
	}
}
