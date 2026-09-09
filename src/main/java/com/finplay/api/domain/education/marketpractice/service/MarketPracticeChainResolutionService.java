package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.domain.education.model.PracticeIntention;
import com.finplay.api.domain.education.repository.PracticeIntentionRepository;
import com.finplay.api.domain.education.service.PracticeIntentionService;
import com.finplay.api.domain.favorite.dto.response.FavoriteResponse;
import com.finplay.api.domain.favorite.service.FavoriteService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.portfolio.service.HoldingService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MarketPracticeChainResolutionService {

	private final FavoriteService favoriteService;
	private final PracticeIntentionRepository practiceIntentionRepository;
	private final TradeService tradeService;
	private final HoldingService holdingService;
	private final PracticeMarketObservationRepository practiceMarketObservationRepository;
	private final InstrumentService instrumentService;

	@Transactional(readOnly = true)
	public Optional<ResolvedPracticeChainDto> resolve(Long userId, String tutorialKey) {
		Market targetMarket = resolveTargetMarket(tutorialKey);

		List<ResolvedPracticeChainDto> completedChains = favoriteService.getFavorites(userId).content().stream()
			.filter(favorite -> targetMarket.name().equals(favorite.market()))
			.map(favorite -> resolveForFavorite(userId, favorite))
			.flatMap(Optional::stream)
			.toList();

		Comparator<ResolvedPracticeChainDto> priorityOrder = Comparator
			.comparing(ResolvedPracticeChainDto::buyTradeExecutedAt)
			.thenComparing(ResolvedPracticeChainDto::favoriteCreatedAt);

		Optional<ResolvedPracticeChainDto> qualifyingFirst = completedChains.stream()
			.filter(chain -> hasQualifyingObservation(userId, chain.holdingId()))
			.sorted(priorityOrder)
			.findFirst();
		if (qualifyingFirst.isPresent()) {
			return qualifyingFirst;
		}

		return completedChains.stream().sorted(priorityOrder).findFirst();
	}

	private boolean hasQualifyingObservation(Long userId, Long holdingId) {
		return practiceMarketObservationRepository.findByUserIdAndHoldingIdOrderByObservedAtAsc(userId, holdingId)
			.stream()
			.anyMatch(observation -> observation.getEvidenceType() != null);
	}

	@Transactional(readOnly = true)
	public Optional<ResolvedPracticeChainDto> resolveForInstrument(Long userId, String tutorialKey, Long instrumentId) {
		Market targetMarket = resolveTargetMarket(tutorialKey);

		return favoriteService.getFavorites(userId).content().stream()
			.filter(favorite -> targetMarket.name().equals(favorite.market()))
			.filter(favorite -> favorite.instrumentId().equals(instrumentId))
			.findFirst()
			.flatMap(favorite -> resolveForFavorite(userId, favorite));
	}

	private Optional<ResolvedPracticeChainDto> resolveForFavorite(Long userId, FavoriteResponse favorite) {
		Optional<PracticeIntention> earliestIntention = practiceIntentionRepository.findByUserId(userId).stream()
			.filter(intention -> intention.instrumentId().equals(favorite.instrumentId()))
			.min(Comparator.comparing(PracticeIntention::createdAt));
		if (earliestIntention.isEmpty()) {
			return Optional.empty();
		}
		PracticeIntention intention = earliestIntention.get();

		Instrument instrument = instrumentService.getInstrumentEntity(favorite.instrumentId());
		Optional<Trade> buyTrade = instrument.isTutorialSample()
			? tradeService.findLatestFilledBuyTradeMatching(
				userId, favorite.instrumentId(), intention.quantity(), intention.createdAt())
			: tradeService.findEarliestFilledBuyTradeMatching(
				userId, favorite.instrumentId(), intention.quantity(), intention.createdAt());
		if (buyTrade.isEmpty()) {
			return Optional.empty();
		}
		Trade trade = buyTrade.get();

		Optional<Long> holdingId = holdingService.findHoldingId(
			userId, Market.valueOf(favorite.market()), favorite.instrumentId());
		if (holdingId.isEmpty()) {
			return Optional.empty();
		}

		Optional<Trade> sellTrade = tradeService.findEarliestFilledSellTradeAfter(
			userId, favorite.instrumentId(), trade.getExecutedAt());

		return Optional.of(new ResolvedPracticeChainDto(
			favorite.favoriteId(),
			favorite.createdAt(),
			intention.intentionId(),
			intention.createdAt(),
			intention.stopLoss(),
			intention.takeProfit(),
			trade.getId(),
			trade.getExecutedAt(),
			trade.getPrice(),
			holdingId.get(),
			sellTrade.map(Trade::getId).orElse(null),
			sellTrade.map(Trade::getExecutedAt).orElse(null),
			trade.getInstrument().isTutorialSample()));
	}

	private Market resolveTargetMarket(String tutorialKey) {
		if (PracticeIntentionService.TUTORIAL_KEY.equals(tutorialKey)) {
			return Market.STOCK;
		}
		if (PracticeIntentionService.COIN_TUTORIAL_KEY.equals(tutorialKey)) {
			return Market.CRYPTO;
		}
		throw new BusinessException(ErrorCode.VALIDATION_ERROR);
	}
}
