// 시장가/지정가 매매 기반 실습 2단계의 favorite -> intention -> buyTrade -> holding evidence chain을 해석하는 서비스
package com.finplay.api.education.marketpractice.service;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.domain.PracticeIntention;
import com.finplay.api.education.marketpractice.repository.PracticeMarketObservationRepository;
import com.finplay.api.education.repository.PracticeIntentionRepository;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.favorite.dto.response.FavoriteResponse;
import com.finplay.api.favorite.service.FavoriteService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
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
	private final PracticeMarketObservationRepository practiceMarketObservationRepository;
	private final InstrumentService instrumentService;

	/**
	 * {@code tutorialKey}({@link PracticeIntentionService#TUTORIAL_KEY}/{@link
	 * PracticeIntentionService#COIN_TUTORIAL_KEY})가 대상으로 하는 market에 속한 본인 favorite마다 chain을
	 * 시도해 완성된 chain 목록을 만든 뒤, 그중 <b>qualifying observation(해당 chain의 holding에 대해
	 * {@code evidenceType}이 non-null인 관찰이 1건 이상 존재)이 있는 chain을 최우선으로</b> 고르고, 그 안에서
	 * {@code buyTrade.executedAt} 오름차순(동률이면 {@code favorite.createdAt} 오름차순)으로 하나를 선택한다
	 * (이슈 #305, plan.md "2단계 chain 해석" 5번). qualifying observation이 있는 chain이 하나도 없으면 전체
	 * 유효 chain 중 같은 정렬 규칙으로 하나를 고른다(기존 동작). 완성된 chain이 하나도 없으면 빈 값을 반환한다.
	 */
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

	/**
	 * {@code resolve}와 달리 여러 favorite 중 우선순위 1건을 고르지 않고, 요청받은 {@code instrumentId} 하나의
	 * chain만 해석한다. holding 기반 API(관찰·복기)는 사용자가 같은 market에서 종목을 여러 개 완결했을 수 있어
	 * "가장 우선순위 높은 chain"이 아니라 "그 holding이 속한 종목의 chain"을 봐야 한다(PR #300 리뷰에서 확정 —
	 * {@code resolve()}만 쓰면 다른 종목의 chain이 우선순위로 뽑혀 정상 chain을 가진 holding이 오탐으로
	 * PRACTICE_EVIDENCE_MISSING을 받는다).
	 */
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

		// 이슈 #339 통합 테스트 중 발견한 회귀 수정 — 샘플 종목 chain은 만료 후 재도전이 가능해야 하므로(spec.md
		// SANDBOX-007) 가장 최신 매수 체결을 anchor로 쓴다. 실제 종목 chain은 026의 anti-gaming 규칙(가장 이른
		// 체결 고정, TradeServiceTest 회귀 계약)을 그대로 유지한다.
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
