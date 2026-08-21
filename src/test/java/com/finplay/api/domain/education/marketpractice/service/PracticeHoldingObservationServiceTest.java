// PracticeHoldingObservationService의 오케스트레이션(소유권 확인 -> chain 재해석 -> 참조 가격선 계산 -> 시세 조회 -> evidence 판정 -> 저장) 분기를 검증하는 단위 테스트다.
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.education.marketpractice.entity.PracticeBoundary;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeEvidenceType;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeHoldingObservationResponse;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
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
		// 이슈 #420 회귀: 전량 매도(sellTrade 존재, 잔량 0) 이후에도 관찰은 그대로 저장돼야 한다. 026 spec.md
		// "비즈니스 규칙"이 "관찰은 ... holding이 존재하는 한 언제든 호출 가능하며 매도로 수량이 0이 되어도 계속
		// 호출 가능하다"로 못박았고, 031 spec.md 도입부가 그 원칙을 변경 없이 상속한다고 명시한다. 매도 직후
		// 관찰이 409 PRACTICE_STEP_LOCKED로 막히면 evidence A·B를 못 채운 채 매도한 사용자는 복기가 영구히
		// PRACTICE_EVIDENCE_MISSING이 되어 튜토리얼을 완료할 방법이 없어진다.
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
		// 이슈 #421의 매매 결과 4값은 관찰 저장 경로가 읽지 않으므로(진행 조회만 쓴다) 이 테스트에서도 null로 둔다 — 같은 파일 위쪽 fixture와 같은 관례다.
		ResolvedPracticeAttemptEvidenceDto soldOutEvidence = new ResolvedPracticeAttemptEvidenceDto(
			snapshot, snapshot, HOLDING_ID, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, mock(Trade.class), null,
			null,
			null, null, null);
		when(practiceAttemptEvidenceService.requireCurrentRun(attempt, USER_ID, HOLDING_ID))
			.thenReturn(soldOutEvidence);
		BigDecimal canonicalPrice = new BigDecimal("100.50000000");
		when(canonicalPriceService.canonicalPriceForMutation(USER_ID, instrument, OBSERVED_AT))
			.thenReturn(canonicalPrice);
		// snapshot 이후 관찰 2건은 매도 여부와 무관하게 evidence B(3회 + 2분 범위) 누적 대상으로 남는다.
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
		// 036-remove-crypto-stale-status: getPrice()가 표시 경로라 코인이 연결 유지 상태면 관측 시각이
		// 얼마나 오래됐든(과거 032 시절엔 stale) 항상 AVAILABLE로 마지막 실제 가격을 반환한다 — 이 서비스는
		// status를 따로 확인하지 않고 그 가격을 그대로 관찰 근거로 쓴다(PR #360 리뷰 차단 사항 후속 승계).
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
