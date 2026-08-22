// MarketPracticeChainResolutionService의 favorite -> intention -> buyTrade -> holding chain 해석을 검증하는 단위 테스트다.
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.education.marketpractice.entity.PracticeEvidenceType;
import com.finplay.api.domain.education.marketpractice.entity.PracticeMarketObservation;
import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.model.PracticeIntention;
import com.finplay.api.domain.education.repository.PracticeIntentionRepository;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import com.finplay.api.domain.favorite.dto.response.FavoriteListResponse;
import com.finplay.api.domain.favorite.dto.response.FavoriteResponse;
import com.finplay.api.domain.favorite.service.FavoriteService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.service.HoldingService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class MarketPracticeChainResolutionServiceTest {

	private static final Long USER_ID = 1L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 10, 0);

	private final FavoriteService favoriteService = mock(FavoriteService.class);
	private final PracticeIntentionRepository practiceIntentionRepository = mock(PracticeIntentionRepository.class);
	private final TradeService tradeService = mock(TradeService.class);
	private final HoldingService holdingService = mock(HoldingService.class);
	private final PracticeMarketObservationRepository practiceMarketObservationRepository = mock(
		PracticeMarketObservationRepository.class);
	private final InstrumentService instrumentService = mock(InstrumentService.class);

	private final MarketPracticeChainResolutionService service = new MarketPracticeChainResolutionService(
		favoriteService, practiceIntentionRepository, tradeService, holdingService,
		practiceMarketObservationRepository, instrumentService);

	{
		// 이 테스트 파일의 대다수 케이스는 026(실제 종목) chain을 다루므로, 별도 스텁이 없는 instrumentId는
		// 기본으로 실제 종목(isTutorialSample=false)을 반환하게 해 기존 회귀 테스트를 그대로 유지한다.
		when(instrumentService.getInstrumentEntity(org.mockito.ArgumentMatchers.anyLong()))
			.thenReturn(stockInstrument());
	}

	@Test
	void resolveReturnsCompletedChainWhenFavoriteIntentionBuyTradeAndHoldingAllMatch() {
		FavoriteResponse favorite = favorite(10L, 100L, "STOCK", NOW.minusDays(3));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favorite)));

		PracticeIntention intention = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(2));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intention));

		Trade trade = buyTrade(30L, new BigDecimal("100"), new BigDecimal("3"), NOW.minusDays(1));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, intention.quantity(), intention.createdAt()))
			.thenReturn(Optional.of(trade));

		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 100L)).thenReturn(Optional.of(40L));

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isPresent();
		ResolvedPracticeChainDto dto = result.get();
		assertThat(dto.favoriteId()).isEqualTo(10L);
		assertThat(dto.favoriteCreatedAt()).isEqualTo(favorite.createdAt());
		assertThat(dto.intentionId()).isEqualTo(20L);
		assertThat(dto.intentionCreatedAt()).isEqualTo(intention.createdAt());
		assertThat(dto.intentionStopLoss()).isEqualByComparingTo(intention.stopLoss());
		assertThat(dto.intentionTakeProfit()).isEqualByComparingTo(intention.takeProfit());
		assertThat(dto.buyTradeId()).isEqualTo(30L);
		assertThat(dto.buyTradeExecutedAt()).isEqualTo(trade.getExecutedAt());
		assertThat(dto.buyTradeEntryPrice()).isEqualByComparingTo(new BigDecimal("100"));
		assertThat(dto.holdingId()).isEqualTo(40L);
	}

	@Test
	void resolvePassesIntentionQuantityAndCreatedAtAsExecutedAtBoundaryToTradeService() {
		FavoriteResponse favorite = favorite(10L, 100L, "STOCK", NOW.minusDays(3));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favorite)));

		// intention.quantity()는 0.1, buyTrade 조회 결과는 scale이 다른 값(0.10000000)이어도 정규화 비교는
		// tradeService.findEarliestFilledBuyTradeMatching 내부 책임이다 — 이 서비스는 그 값을 있는 그대로
		// 전달만 하는지를 검증한다(정규화 비교 자체의 단위 테스트는 TradeServiceTest가 담당).
		PracticeIntention intention = intention(20L, 100L, new BigDecimal("0.1"), NOW.minusDays(2));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intention));

		when(tradeService.findEarliestFilledBuyTradeMatching(
			eq(USER_ID), eq(100L), eq(new BigDecimal("0.1")), eq(NOW.minusDays(2))))
			.thenReturn(Optional.empty());

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isEmpty();
		verify(tradeService).findEarliestFilledBuyTradeMatching(USER_ID, 100L, new BigDecimal("0.1"), NOW.minusDays(2));
	}

	@Test
	void resolvePicksEarliestIntentionCreatedAtWhenMultipleIntentionsExistForSameFavorite() {
		FavoriteResponse favorite = favorite(10L, 100L, "STOCK", NOW.minusDays(5));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favorite)));

		PracticeIntention laterIntention = intention(21L, 100L, new BigDecimal("5"), NOW.minusDays(2));
		PracticeIntention earlierIntention = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(4));
		when(practiceIntentionRepository.findByUserId(USER_ID))
			.thenReturn(List.of(laterIntention, earlierIntention));

		Trade trade = buyTrade(30L, new BigDecimal("100"), new BigDecimal("3"), NOW.minusDays(1));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, earlierIntention.quantity(), earlierIntention.createdAt()))
			.thenReturn(Optional.of(trade));
		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 100L)).thenReturn(Optional.of(40L));

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isPresent();
		assertThat(result.get().intentionId()).isEqualTo(20L);
		verify(tradeService, org.mockito.Mockito.never()).findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, laterIntention.quantity(), laterIntention.createdAt());
	}

	@Test
	void resolveSelectsChainWithEarliestBuyTradeExecutedAtAmongMultipleCompletedFavoriteChains() {
		FavoriteResponse favoriteA = favorite(10L, 100L, "STOCK", NOW.minusDays(10));
		FavoriteResponse favoriteB = favorite(11L, 200L, "STOCK", NOW.minusDays(9));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favoriteA, favoriteB)));

		PracticeIntention intentionA = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(8));
		PracticeIntention intentionB = intention(21L, 200L, new BigDecimal("2"), NOW.minusDays(7));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intentionA, intentionB));

		Trade laterTrade = buyTrade(30L, new BigDecimal("100"), new BigDecimal("3"), NOW.minusDays(3));
		Trade earlierTrade = buyTrade(31L, new BigDecimal("50"), new BigDecimal("2"), NOW.minusDays(5));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, intentionA.quantity(), intentionA.createdAt()))
			.thenReturn(Optional.of(laterTrade));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 200L, intentionB.quantity(), intentionB.createdAt()))
			.thenReturn(Optional.of(earlierTrade));

		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 100L)).thenReturn(Optional.of(40L));
		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 200L)).thenReturn(Optional.of(41L));

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isPresent();
		assertThat(result.get().favoriteId()).isEqualTo(11L);
		assertThat(result.get().buyTradeId()).isEqualTo(31L);
	}

	@Test
	void resolveSelectsChainWithQualifyingObservationOverEarlierBuyTradeExecutedAtChain() {
		// resolveSelectsChainWithEarliestBuyTradeExecutedAtAmongMultipleCompletedFavoriteChains의 픽스처를
		// 뒤집는다 — favoriteB/earlierTrade(instrument 200)가 buyTradeExecutedAt이 더 이르지만, 이번에는
		// favoriteA/laterTrade(instrument 100)의 holding에 qualifying observation(evidenceType non-null)을
		// 붙인다. qualifying observation이 있는 chain은 buyTradeExecutedAt 순서와 무관하게 최우선이어야 한다.
		FavoriteResponse favoriteA = favorite(10L, 100L, "STOCK", NOW.minusDays(10));
		FavoriteResponse favoriteB = favorite(11L, 200L, "STOCK", NOW.minusDays(9));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favoriteA, favoriteB)));

		PracticeIntention intentionA = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(8));
		PracticeIntention intentionB = intention(21L, 200L, new BigDecimal("2"), NOW.minusDays(7));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intentionA, intentionB));

		Trade laterTrade = buyTrade(30L, new BigDecimal("100"), new BigDecimal("3"), NOW.minusDays(3));
		Trade earlierTrade = buyTrade(31L, new BigDecimal("50"), new BigDecimal("2"), NOW.minusDays(5));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, intentionA.quantity(), intentionA.createdAt()))
			.thenReturn(Optional.of(laterTrade));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 200L, intentionB.quantity(), intentionB.createdAt()))
			.thenReturn(Optional.of(earlierTrade));

		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 100L)).thenReturn(Optional.of(40L));
		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 200L)).thenReturn(Optional.of(41L));

		// holding 40(favoriteA/instrument 100, buyTradeExecutedAt이 더 늦음)에만 qualifying observation을 둔다.
		PracticeMarketObservation qualifyingObservation = mock(PracticeMarketObservation.class);
		when(qualifyingObservation.getEvidenceType()).thenReturn(PracticeEvidenceType.CLOSER_TO_BOUNDARY);
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, 40L))
			.thenReturn(List.of(qualifyingObservation));
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, 41L))
			.thenReturn(List.of());

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isPresent();
		assertThat(result.get().favoriteId()).isEqualTo(10L);
		assertThat(result.get().buyTradeId()).isEqualTo(30L);
		assertThat(result.get().holdingId()).isEqualTo(40L);
	}

	@Test
	void resolveFallsBackToBuyTradeExecutedAtAscWhenNoChainHasQualifyingObservation() {
		// 두 chain 모두 qualifying observation이 없으면(관찰 자체가 없거나 evidenceType이 전부 null) 기존
		// 우선순위(buyTradeExecutedAt ASC)로 폴백한다 —
		// resolveSelectsChainWithEarliestBuyTradeExecutedAtAmongMultipleCompletedFavoriteChains와 동일한 결과를
		// qualifying-observation 조회 경로를 명시적으로 거친 뒤에도 유지하는지 검증한다.
		FavoriteResponse favoriteA = favorite(10L, 100L, "STOCK", NOW.minusDays(10));
		FavoriteResponse favoriteB = favorite(11L, 200L, "STOCK", NOW.minusDays(9));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favoriteA, favoriteB)));

		PracticeIntention intentionA = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(8));
		PracticeIntention intentionB = intention(21L, 200L, new BigDecimal("2"), NOW.minusDays(7));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intentionA, intentionB));

		Trade laterTrade = buyTrade(30L, new BigDecimal("100"), new BigDecimal("3"), NOW.minusDays(3));
		Trade earlierTrade = buyTrade(31L, new BigDecimal("50"), new BigDecimal("2"), NOW.minusDays(5));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, intentionA.quantity(), intentionA.createdAt()))
			.thenReturn(Optional.of(laterTrade));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 200L, intentionB.quantity(), intentionB.createdAt()))
			.thenReturn(Optional.of(earlierTrade));

		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 100L)).thenReturn(Optional.of(40L));
		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 200L)).thenReturn(Optional.of(41L));

		// 관찰이 존재하지만(예: A 미충족 관찰) evidenceType이 non-null인 건이 하나도 없다 — qualifying 아님.
		PracticeMarketObservation nonQualifyingObservation = mock(PracticeMarketObservation.class);
		when(nonQualifyingObservation.getEvidenceType()).thenReturn(null);
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, 40L))
			.thenReturn(List.of(nonQualifyingObservation));
		when(practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(USER_ID, 41L))
			.thenReturn(List.of());

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isPresent();
		assertThat(result.get().favoriteId()).isEqualTo(11L);
		assertThat(result.get().buyTradeId()).isEqualTo(31L);
	}

	@Test
	void resolveForInstrumentReturnsRequestedInstrumentChainEvenWhenAnotherChainWouldWinResolve() {
		// resolve()라면 buyTradeExecutedAt이 더 이른 favoriteB(instrument 200)를 우선순위로 고른다
		// (resolveSelectsChainWithEarliestBuyTradeExecutedAtAmongMultipleCompletedFavoriteChains와 동일 픽스처).
		// resolveForInstrument(instrumentId=100)는 그 우선순위와 무관하게 요청받은 instrument 100의 chain만
		// 반환해야 한다 — PR #300 리뷰가 지적한 "다른 종목이 뽑혀 정상 holding이 오탐 409를 받는" 버그의 회귀 테스트.
		FavoriteResponse favoriteA = favorite(10L, 100L, "STOCK", NOW.minusDays(10));
		FavoriteResponse favoriteB = favorite(11L, 200L, "STOCK", NOW.minusDays(9));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favoriteA, favoriteB)));

		PracticeIntention intentionA = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(8));
		PracticeIntention intentionB = intention(21L, 200L, new BigDecimal("2"), NOW.minusDays(7));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intentionA, intentionB));

		Trade laterTrade = buyTrade(30L, new BigDecimal("100"), new BigDecimal("3"), NOW.minusDays(3));
		Trade earlierTrade = buyTrade(31L, new BigDecimal("50"), new BigDecimal("2"), NOW.minusDays(5));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, intentionA.quantity(), intentionA.createdAt()))
			.thenReturn(Optional.of(laterTrade));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 200L, intentionB.quantity(), intentionB.createdAt()))
			.thenReturn(Optional.of(earlierTrade));

		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 100L)).thenReturn(Optional.of(40L));
		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 200L)).thenReturn(Optional.of(41L));

		Optional<ResolvedPracticeChainDto> resolveResult = service.resolve(USER_ID,
			PracticeIntentionService.TUTORIAL_KEY);
		assertThat(resolveResult).isPresent();
		assertThat(resolveResult.get().favoriteId()).as("resolve()는 우선순위상 instrument 200을 고른다").isEqualTo(11L);

		Optional<ResolvedPracticeChainDto> result = service.resolveForInstrument(
			USER_ID, PracticeIntentionService.TUTORIAL_KEY, 100L);

		assertThat(result).isPresent();
		assertThat(result.get().favoriteId()).isEqualTo(10L);
		assertThat(result.get().buyTradeId()).isEqualTo(30L);
		assertThat(result.get().holdingId()).isEqualTo(40L);
	}

	@Test
	void resolveForInstrumentReturnsEmptyWhenNoFavoriteMatchesInstrument() {
		FavoriteResponse favorite = favorite(10L, 100L, "STOCK", NOW.minusDays(1));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favorite)));

		Optional<ResolvedPracticeChainDto> result = service.resolveForInstrument(
			USER_ID, PracticeIntentionService.TUTORIAL_KEY, 999L);

		assertThat(result).isEmpty();
	}

	@Test
	void resolveFallsBackToFavoriteCreatedAtAscWhenBuyTradeExecutedAtTies() {
		LocalDateTime sameExecutedAt = NOW.minusDays(1);
		FavoriteResponse laterFavorite = favorite(10L, 100L, "STOCK", NOW.minusDays(2));
		FavoriteResponse earlierFavorite = favorite(11L, 200L, "STOCK", NOW.minusDays(4));
		when(favoriteService.getFavorites(USER_ID))
			.thenReturn(new FavoriteListResponse(List.of(laterFavorite, earlierFavorite)));

		PracticeIntention intentionA = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(6));
		PracticeIntention intentionB = intention(21L, 200L, new BigDecimal("2"), NOW.minusDays(7));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intentionA, intentionB));

		Trade tradeA = buyTrade(30L, new BigDecimal("100"), new BigDecimal("3"), sameExecutedAt);
		Trade tradeB = buyTrade(31L, new BigDecimal("50"), new BigDecimal("2"), sameExecutedAt);
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, intentionA.quantity(), intentionA.createdAt()))
			.thenReturn(Optional.of(tradeA));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 200L, intentionB.quantity(), intentionB.createdAt()))
			.thenReturn(Optional.of(tradeB));

		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 100L)).thenReturn(Optional.of(40L));
		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 200L)).thenReturn(Optional.of(41L));

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		// buyTradeExecutedAt이 같으면 favorite.createdAt 오름차순으로 더 이른 쪽(earlierFavorite, id=11)을 고른다.
		assertThat(result).isPresent();
		assertThat(result.get().favoriteId()).isEqualTo(11L);
	}

	@Test
	void resolveReturnsEmptyWhenNoFavoriteMatchesTargetMarket() {
		FavoriteResponse cryptoFavorite = favorite(10L, 100L, "CRYPTO", NOW.minusDays(1));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(cryptoFavorite)));

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isEmpty();
	}

	@Test
	void resolveReturnsEmptyWhenNoIntentionExistsForFavoriteInstrument() {
		FavoriteResponse favorite = favorite(10L, 100L, "STOCK", NOW.minusDays(1));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favorite)));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of());

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isEmpty();
	}

	@Test
	void resolveReturnsEmptyWhenNoMatchingBuyTradeExists() {
		FavoriteResponse favorite = favorite(10L, 100L, "STOCK", NOW.minusDays(2));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favorite)));

		PracticeIntention intention = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(1));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intention));

		// 수량 불일치·미체결 등으로 tradeService가 후보를 찾지 못한 경우를 빈 Optional로 표현한다.
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, intention.quantity(), intention.createdAt()))
			.thenReturn(Optional.empty());

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isEmpty();
	}

	@Test
	void resolveReturnsEmptyWhenNoHoldingExistsForOwnerAndInstrument() {
		FavoriteResponse favorite = favorite(10L, 100L, "STOCK", NOW.minusDays(2));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favorite)));

		PracticeIntention intention = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(1));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intention));

		Trade trade = buyTrade(30L, new BigDecimal("100"), new BigDecimal("3"), NOW);
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, intention.quantity(), intention.createdAt()))
			.thenReturn(Optional.of(trade));

		// owner·instrument 불일치를 포함해 holding이 없는 경우는 holdingService가 이미 빈 값으로 표현한다.
		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 100L)).thenReturn(Optional.empty());

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isEmpty();
	}

	@Test
	void resolveFiltersFavoritesByStockMarketForInvestmentTutorialKey() {
		FavoriteResponse stockFavorite = favorite(10L, 100L, "STOCK", NOW.minusDays(2));
		FavoriteResponse cryptoFavorite = favorite(11L, 200L, "CRYPTO", NOW.minusDays(2));
		when(favoriteService.getFavorites(USER_ID))
			.thenReturn(new FavoriteListResponse(List.of(stockFavorite, cryptoFavorite)));

		PracticeIntention intention = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(1));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intention));

		Trade trade = buyTrade(30L, new BigDecimal("100"), new BigDecimal("3"), NOW);
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, intention.quantity(), intention.createdAt()))
			.thenReturn(Optional.of(trade));
		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 100L)).thenReturn(Optional.of(40L));

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isPresent();
		assertThat(result.get().favoriteId()).isEqualTo(10L);
		verify(holdingService, org.mockito.Mockito.never()).findHoldingId(USER_ID, Market.CRYPTO, 200L);
	}

	@Test
	void resolveFiltersFavoritesByCryptoMarketForCoinTutorialKey() {
		FavoriteResponse stockFavorite = favorite(10L, 100L, "STOCK", NOW.minusDays(2));
		FavoriteResponse cryptoFavorite = favorite(11L, 200L, "CRYPTO", NOW.minusDays(2));
		when(favoriteService.getFavorites(USER_ID))
			.thenReturn(new FavoriteListResponse(List.of(stockFavorite, cryptoFavorite)));

		PracticeIntention intention = intention(21L, 200L, new BigDecimal("2"), NOW.minusDays(1));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intention));

		Trade trade = buyTrade(31L, new BigDecimal("50"), new BigDecimal("2"), NOW);
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 200L, intention.quantity(), intention.createdAt()))
			.thenReturn(Optional.of(trade));
		when(holdingService.findHoldingId(USER_ID, Market.CRYPTO, 200L)).thenReturn(Optional.of(41L));

		Optional<ResolvedPracticeChainDto> result = service.resolve(
			USER_ID, PracticeIntentionService.COIN_TUTORIAL_KEY);

		assertThat(result).isPresent();
		assertThat(result.get().favoriteId()).isEqualTo(11L);
	}

	// 아래는 031-tutorial-sandbox-instruments 매도 chain 해석(resolveForFavorite이 tradeService.
	// findEarliestFilledSellTradeAfter를 buyTrade.executedAt 이후로 조회해 DTO에 채우는 부분)을 검증한다.

	@Test
	void resolveFillsNullSellFieldsWhenNoSellTradeExistsAfterBuyTrade() {
		FavoriteResponse favorite = favorite(10L, 100L, "STOCK", NOW.minusDays(3));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favorite)));

		PracticeIntention intention = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(2));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intention));

		Trade buyTrade = buyTrade(30L, new BigDecimal("100"), new BigDecimal("3"), NOW.minusDays(1));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, intention.quantity(), intention.createdAt()))
			.thenReturn(Optional.of(buyTrade));
		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 100L)).thenReturn(Optional.of(40L));

		// 매도 체결이 아직 없는 경우(findEarliestFilledSellTradeAfter가 빈 값) — chain 자체는 buyTrade·holding
		// 만으로 완성되므로 여전히 반환되지만, 신규 매도 필드 2개는 null이어야 한다.
		when(tradeService.findEarliestFilledSellTradeAfter(USER_ID, 100L, buyTrade.getExecutedAt()))
			.thenReturn(Optional.empty());

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isPresent();
		assertThat(result.get().sellTradeId()).isNull();
		assertThat(result.get().sellTradeExecutedAt()).isNull();
	}

	@Test
	void resolveFillsSellTradeFieldsFromEarliestFilledSellTradeAfterBuyTradeExecutedAt() {
		FavoriteResponse favorite = favorite(10L, 100L, "STOCK", NOW.minusDays(3));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favorite)));

		PracticeIntention intention = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(2));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intention));

		Trade buyTrade = buyTrade(30L, new BigDecimal("100"), new BigDecimal("3"), NOW.minusDays(1));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, intention.quantity(), intention.createdAt()))
			.thenReturn(Optional.of(buyTrade));
		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 100L)).thenReturn(Optional.of(40L));

		// TradeService.findEarliestFilledSellTradeAfter가 이미 "buyTrade.executedAt 이후 여러 매도 중 가장
		// 이른 것"을 골라 반환한다는 계약이다(그 선택 로직 자체는 TradeServiceTest가 검증). 이 서비스는 그
		// 결과를 그대로 DTO에 옮기고, 경계값(after)으로 buyTrade.executedAt을 정확히 넘기는지만 책임진다.
		Trade earliestSellAfterBuy = sellTrade(50L, NOW.minusHours(2));
		when(tradeService.findEarliestFilledSellTradeAfter(USER_ID, 100L, buyTrade.getExecutedAt()))
			.thenReturn(Optional.of(earliestSellAfterBuy));

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isPresent();
		assertThat(result.get().sellTradeId()).isEqualTo(50L);
		assertThat(result.get().sellTradeExecutedAt()).isEqualTo(earliestSellAfterBuy.getExecutedAt());
		verify(tradeService).findEarliestFilledSellTradeAfter(USER_ID, 100L, buyTrade.getExecutedAt());
	}

	// 이슈 #339 tasks.md 6번 통합 테스트 작업 중 발견한 회귀 수정 — 샘플 종목 chain은 만료 후 재도전이 가능해야
	// 하므로(spec.md SANDBOX-007) buyTrade anchor를 "가장 최신"으로 고른다. 실제 종목 chain은 026의 anti-gaming
	// 규칙(가장 이른 체결 고정)을 그대로 유지한다.

	@Test
	void resolvePicksLatestFilledBuyTradeForTutorialSampleInstrumentToAllowRetryAfterExpiry() {
		FavoriteResponse favorite = favorite(10L, 100L, "STOCK", NOW.minusDays(3));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favorite)));
		when(instrumentService.getInstrumentEntity(100L)).thenReturn(tutorialSampleInstrument());

		PracticeIntention intention = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(2));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intention));

		// 첫 매수(만료된 chain의 anchor)와 재도전 매수 둘 다 존재한다 — 샘플 chain은 최신(재도전) 쪽을 골라야 한다.
		Trade expiredBuyTrade = buyTrade(30L, new BigDecimal("100"), new BigDecimal("3"), NOW.minusHours(1));
		Trade retryBuyTrade = buyTrade(31L, new BigDecimal("105"), new BigDecimal("3"), NOW.minusMinutes(1));
		when(tradeService.findLatestFilledBuyTradeMatching(
			USER_ID, 100L, intention.quantity(), intention.createdAt()))
			.thenReturn(Optional.of(retryBuyTrade));
		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 100L)).thenReturn(Optional.of(40L));

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isPresent();
		assertThat(result.get().buyTradeId()).isEqualTo(31L);
		verify(tradeService, org.mockito.Mockito.never()).findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, intention.quantity(), intention.createdAt());
	}

	@Test
	void resolvePicksEarliestFilledBuyTradeForRealInstrumentEvenWhenLaterMatchExists() {
		// 026의 anti-gaming 회귀 확인 — 샘플 종목이 아니면 여전히 가장 이른 매수를 고정 선택한다.
		FavoriteResponse favorite = favorite(10L, 100L, "STOCK", NOW.minusDays(3));
		when(favoriteService.getFavorites(USER_ID)).thenReturn(new FavoriteListResponse(List.of(favorite)));
		when(instrumentService.getInstrumentEntity(100L)).thenReturn(stockInstrument());

		PracticeIntention intention = intention(20L, 100L, new BigDecimal("3"), NOW.minusDays(2));
		when(practiceIntentionRepository.findByUserId(USER_ID)).thenReturn(List.of(intention));

		Trade earliestBuyTrade = buyTrade(30L, new BigDecimal("100"), new BigDecimal("3"), NOW.minusHours(1));
		when(tradeService.findEarliestFilledBuyTradeMatching(
			USER_ID, 100L, intention.quantity(), intention.createdAt()))
			.thenReturn(Optional.of(earliestBuyTrade));
		when(holdingService.findHoldingId(USER_ID, Market.STOCK, 100L)).thenReturn(Optional.of(40L));

		Optional<ResolvedPracticeChainDto> result = service.resolve(USER_ID, PracticeIntentionService.TUTORIAL_KEY);

		assertThat(result).isPresent();
		assertThat(result.get().buyTradeId()).isEqualTo(30L);
		verify(tradeService, org.mockito.Mockito.never()).findLatestFilledBuyTradeMatching(
			USER_ID, 100L, intention.quantity(), intention.createdAt());
	}

	@Test
	void resolveThrowsValidationErrorForUnknownTutorialKey() {
		assertThatThrownBy(() -> service.resolve(USER_ID, "UNKNOWN_TUTORIAL_KEY"))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	private static FavoriteResponse favorite(Long favoriteId, Long instrumentId, String market,
		LocalDateTime createdAt) {
		return new FavoriteResponse(favoriteId, instrumentId, market, "SYM", "종목명", createdAt);
	}

	private static PracticeIntention intention(
		Long intentionId, Long instrumentId, BigDecimal quantity, LocalDateTime createdAt) {
		return new PracticeIntention(
			intentionId, USER_ID, instrumentId, quantity, new BigDecimal("90"), new BigDecimal("110"), createdAt);
	}

	private static Trade buyTrade(Long id, BigDecimal price, BigDecimal quantity, LocalDateTime executedAt) {
		Order order = Order.create(
			testUser(), account(), stockInstrument(), OrderSide.BUY, OrderType.MARKET, quantity,
			"idem-key-" + id, "h".repeat(64), NOW);
		Trade trade = Trade.of(
			order, order.getAccount(), stockInstrument(), stockSession(),
			OrderSide.BUY, price, quantity, price.longValue() * quantity.longValue(), 1L, null, executedAt, NOW);
		ReflectionTestUtils.setField(trade, "id", id);
		return trade;
	}

	private static Trade sellTrade(Long id, LocalDateTime executedAt) {
		Order order = Order.create(
			testUser(), account(), stockInstrument(), OrderSide.SELL, OrderType.MARKET, new BigDecimal("3"),
			"idem-key-sell-" + id, "h".repeat(64), NOW);
		Trade trade = Trade.of(
			order, order.getAccount(), stockInstrument(), stockSession(),
			OrderSide.SELL, new BigDecimal("110"), new BigDecimal("3"), 330L, 1L, 30L, executedAt, NOW);
		ReflectionTestUtils.setField(trade, "id", id);
		return trade;
	}

	private static StockReplaySession stockSession() {
		return StockReplaySession.ready(NOW.toLocalDate(), NOW.toLocalDate(), NOW, NOW);
	}

	private static Instrument stockInstrument() {
		return Instrument.create(Market.STOCK, "005930", "삼성전자", new BigDecimal("100"), 0L, true, NOW);
	}

	private static Instrument tutorialSampleInstrument() {
		Instrument instrument = Instrument.create(Market.STOCK, "SANDBOX_STK_1", "샌드박스 종목", new BigDecimal("100"),
			0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		return instrument;
	}

	private static Account account() {
		return Account.create(testUser(), Market.STOCK, NOW);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}
}
