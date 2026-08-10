// 시장가/지정가 매매 기반 실습 2단계의 favorite -> intention -> buyTrade -> holding evidence chain을 해석하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.domain.PracticeIntention;
import com.finplay.api.education.repository.PracticeIntentionRepository;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.favorite.dto.response.FavoriteResponse;
import com.finplay.api.favorite.service.FavoriteService;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.TradeService;
import com.finplay.api.portfolio.service.HoldingService;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * docs/specs/026-market-order-practice-tutorial plan.md "2단계 chain 해석" 절의 규칙을 구현한다. favorite·
 * intention은 ADR-0012에 따라 인메모리이므로 같은 education 도메인의 {@link PracticeIntentionRepository}를 직접
 * 쓰지만(같은 도메인 내부이므로 ADR-0002 위반 아님), buyTrade·holding 조회는 각 도메인의 조회 전용 서비스
 * 메서드만 거친다(order/portfolio repository를 이 클래스에서 직접 주입하지 않는다).
 *
 * <p>이 클래스는 chain 해석만 담당한다. 3단계 참조 가격선 계산·evidence A/B 판정·Controller 매핑은 이 spec의
 * 다른 작업 항목(tasks.md 2~5번)이 이어받는다 — 여기서는 favorite·intention·buyTrade·holding 중 하나라도
 * 없거나 owner·instrument·수량이 불일치하면 {@link Optional#empty()}를 반환할 뿐, 409 매핑은 하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class MarketPracticeChainResolutionService {

	private final FavoriteService favoriteService;
	private final PracticeIntentionRepository practiceIntentionRepository;
	private final TradeService tradeService;
	private final HoldingService holdingService;

	/**
	 * {@code tutorialKey}({@link PracticeIntentionService#TUTORIAL_KEY}/{@link
	 * PracticeIntentionService#COIN_TUTORIAL_KEY})가 대상으로 하는 market에 속한 본인 favorite마다 chain을
	 * 시도해, 완성된 chain 중 {@code buyTrade.executedAt} 오름차순(동률이면 {@code favorite.createdAt}
	 * 오름차순)으로 하나를 고른다. 완성된 chain이 하나도 없으면 빈 값을 반환한다.
	 *
	 * <p>"qualifying observation이 있는 chain 우선" 규칙(plan.md 5번)은 관찰 저장이 아직 없는 이 작업 항목의
	 * 범위 밖이라 적용하지 않는다 — 다음 작업 항목이 이어받도록 이 메서드 시그니처(tutorialKey 기준 전체 chain
	 * 목록을 우선순위대로 재정렬해야 할 수 있음)를 바꾸지 않고 남겨둔다.
	 */
	@Transactional(readOnly = true)
	public Optional<ResolvedPracticeChainDto> resolve(Long userId, String tutorialKey) {
		Market targetMarket = resolveTargetMarket(tutorialKey);

		List<ResolvedPracticeChainDto> completedChains = favoriteService.getFavorites(userId).content().stream()
			.filter(favorite -> targetMarket.name().equals(favorite.market()))
			.map(favorite -> resolveForFavorite(userId, favorite))
			.flatMap(Optional::stream)
			.toList();

		return completedChains.stream()
			.sorted(
				Comparator.comparing(ResolvedPracticeChainDto::buyTradeExecutedAt)
					.thenComparing(ResolvedPracticeChainDto::favoriteCreatedAt))
			.findFirst();
	}

	private Optional<ResolvedPracticeChainDto> resolveForFavorite(Long userId, FavoriteResponse favorite) {
		Optional<PracticeIntention> earliestIntention = practiceIntentionRepository.findByUserId(userId).stream()
			.filter(intention -> intention.instrumentId().equals(favorite.instrumentId()))
			.min(Comparator.comparing(PracticeIntention::createdAt));
		if (earliestIntention.isEmpty()) {
			return Optional.empty();
		}
		PracticeIntention intention = earliestIntention.get();

		Optional<Trade> buyTrade = tradeService.findEarliestFilledBuyTradeMatching(
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
			holdingId.get()));
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
