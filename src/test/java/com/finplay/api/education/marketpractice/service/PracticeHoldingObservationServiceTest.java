// PracticeHoldingObservationService의 오케스트레이션(소유권 확인 -> chain 재해석 -> 참조 가격선 계산 -> 시세 조회 -> evidence 판정 -> 저장) 분기를 검증하는 단위 테스트다.
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
import com.finplay.api.education.marketpractice.domain.PracticeBoundary;
import com.finplay.api.education.marketpractice.domain.PracticeEvidenceType;
import com.finplay.api.education.marketpractice.domain.PracticeMarketObservation;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.PracticeHoldingObservationResponse;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.education.priceruntime.service.PracticePriceObservationService;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.market.service.PriceQuoteDto;
import com.finplay.api.market.service.PriceStatus;
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
import org.mockito.InOrder;
import org.mockito.Mockito;

class PracticeHoldingObservationServiceTest {

	private static final Long USER_ID = 1L;
	private static final Long HOLDING_ID = 40L;
	private static final Long INSTRUMENT_ID = 100L;
	private static final LocalDateTime OBSERVED_AT = LocalDateTime.of(2026, 8, 10, 10, 0);

	private final HoldingService holdingService = mock(HoldingService.class);
	private final MarketPracticeChainResolutionService chainResolutionService = mock(
		MarketPracticeChainResolutionService.class);
	private final PriceQueryService priceQueryService = mock(PriceQueryService.class);
	private final PracticePriceObservationService practicePriceObservationService = mock(
		PracticePriceObservationService.class);
	private final ReferencePriceCalculator referencePriceCalculator = mock(ReferencePriceCalculator.class);
	private final EvidenceJudgmentService evidenceJudgmentService = mock(EvidenceJudgmentService.class);
	private final PracticeMarketObservationRepository observationRepository = mock(
		PracticeMarketObservationRepository.class);
	private final Clock clock = Clock.fixed(OBSERVED_AT.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

	private final PracticeHoldingObservationService service = new PracticeHoldingObservationService(
		holdingService, chainResolutionService, priceQueryService, practicePriceObservationService,
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
		// resolveForInstrument는 instrumentId만 보므로, 어떤 이유로든 반환된 chain의 holdingId가 요청 holding과
		// 다르면(예: 데이터 정합성 문제) 여전히 안전하게 거부해야 한다 — 서비스의 .filter(...) 방어선 검증.
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

		// buyTrade에 귀속된 가상 가격 세션이 없는 경우(세션 없는 기존 시장가·실제 지정가) — 기존
		// PriceQueryService 경로로 fallback한다(이슈 #321).
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

		// 참조 가격선 계산이 외부 의존(시세 조회)보다 먼저 실행된다 — PR #302 리뷰 참고사항: 실제 코드 순서와
		// PR 설명이 달랐던 지점이라 순서 자체를 테스트로 고정해 둔다.
		InOrder inOrder = Mockito.inOrder(referencePriceCalculator, priceQueryService);
		inOrder.verify(referencePriceCalculator).calculate(chain);
		inOrder.verify(priceQueryService).getPrice(INSTRUMENT_ID);
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

	private ResolvedPracticeChainDto completedChain() {
		return new ResolvedPracticeChainDto(
			10L, OBSERVED_AT.minusDays(1), 20L, OBSERVED_AT.minusHours(2), new BigDecimal("90"),
			new BigDecimal("120"), 30L, OBSERVED_AT.minusHours(1), new BigDecimal("100"), HOLDING_ID, null, null,
			false);
	}
}
