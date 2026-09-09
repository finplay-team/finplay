package com.finplay.api.domain.feedback.service;

import com.finplay.api.domain.feedback.config.FeedbackLlmProperties;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.dto.response.PostSellFlow;
import com.finplay.api.domain.feedback.dto.response.TradeShareSummaryResponse;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.TradeFeedback;
import com.finplay.api.domain.feedback.repository.TradeFeedbackRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.service.SellAllocationSummaryDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostSellFeedbackService {

	private final PostSellFeedbackReader postSellFeedbackReader;

	private final PostSellFeedbackContextReader postSellFeedbackContextReader;

	private final PostSellJournalReader postSellJournalReader;

	private final NarrativeService narrativeService;

	private final TradeFeedbackWriter tradeFeedbackWriter;

	private final TradeFeedbackRepository tradeFeedbackRepository;

	private final FeedbackLlmProperties feedbackLlmProperties;

	private final Clock clock;

	public PostSellFeedbackResponse getPostSellFeedback(Long userId, Long tradeId) {
		PostSellFeedbackResponse facts = postSellFeedbackReader.read(userId, tradeId);
		JournalDigestDto journals = postSellJournalReader.read(tradeId);
		NarrativeResultDto narrative = resolveNarrative(userId, tradeId, facts, journals);
		return facts.withNarrative(
			narrative.narrative(), narrative.source(), PostSellFeedbackStatus.READY);
	}

	public TradeShareSummaryResponse getTradeShareSummary(Long userId, Long tradeId) {
		PostSellFeedbackContext context = postSellFeedbackContextReader.loadContext(userId, tradeId);
		Trade trade = context.trade();
		SellAllocationSummaryDto allocation = context.allocation();
		Instrument instrument = trade.getInstrument();
		long buyBasis = allocation.allocatedCost() + allocation.allocatedBuyFee();
		BigDecimal returnRate = PostSellArithmetic.returnRate(trade.getRealizedPnl(), buyBasis);
		return new TradeShareSummaryResponse(
			instrument.getSymbol(),
			instrument.getName(),
			instrument.getMarket(),
			allocation.buyPrice(),
			trade.getPrice(),
			trade.getQuantity(),
			trade.getRealizedPnl(),
			returnRate);
	}

	private NarrativeResultDto resolveNarrative(
		Long userId, Long tradeId, PostSellFeedbackResponse facts, JournalDigestDto journals) {
		Optional<TradeFeedback> found = tradeFeedbackRepository.findByTradeId(tradeId);
		if (found.isEmpty()) {
			return createNarrative(userId, tradeId, facts, journals);
		}

		TradeFeedback existing = found.get();
		NarrativeResultDto stored = new NarrativeResultDto(existing.getNarrative(), existing.getNarrativeSource());
		RegenerationReasons reasons = regenerationReasons(tradeId, existing, facts, journals);
		return reasons.any() ? regenerateNarrative(tradeId, facts, journals, stored, reasons) : stored;
	}

	private NarrativeResultDto createNarrative(
		Long userId, Long tradeId, PostSellFeedbackResponse facts, JournalDigestDto journals) {
		NarrativeResultDto resolved = narrativeService.resolvePostSellNarrative(toPromptInput(facts, journals));
		try {
			tradeFeedbackWriter.create(userId, tradeId, resolved, journals.fingerprint(), LocalDateTime.now(clock));
		} catch (DataIntegrityViolationException e) {
			absorbOnlyDuplicateRow(tradeId, e);
		}
		return resolved;
	}

	private void absorbOnlyDuplicateRow(Long tradeId, DataIntegrityViolationException e) {
		if (tradeFeedbackRepository.findByTradeId(tradeId).isPresent()) {
			log.debug("매도 회고 서술이 이미 저장돼 있어 이번 저장은 건너뛴다. tradeId={}", tradeId);
			return;
		}
		log.warn(
			"매도 회고 서술 저장이 무결성 위반으로 실패했고 행도 없다. 이 체결은 조회마다 서술을 다시 만든다. tradeId={}",
			tradeId,
			e);
	}

	private RegenerationReasons regenerationReasons(
		Long tradeId, TradeFeedback existing, PostSellFeedbackResponse facts, JournalDigestDto journals) {
		return new RegenerationReasons(
			shouldRegenerateForJournal(tradeId, existing, journals), shouldRegenerate(existing, facts));
	}

	private boolean shouldRegenerateForJournal(
		Long tradeId, TradeFeedback existing, JournalDigestDto journals) {
		if (Objects.equals(existing.getJournalFingerprint(), journals.fingerprint())) {
			return false;
		}
		if (existing.getJournalRegenerations() >= feedbackLlmProperties.maxJournalRegeneration()) {
			log.debug(
				"투자일기가 바뀌었지만 재생성 상한에 닿아 기존 서술을 재사용한다. tradeId={} journalRegenerations={}",
				tradeId,
				existing.getJournalRegenerations());
			return false;
		}
		return true;
	}

	private boolean shouldRegenerate(TradeFeedback existing, PostSellFeedbackResponse facts) {
		if (existing.isNarrativeFinalized()) {
			return false;
		}
		if (existing.getRegenerationAttempts() >= feedbackLlmProperties.maxNarrativeRetry()) {
			return false;
		}
		return isRegenerationGateOpen(facts);
	}

	private static boolean isRegenerationGateOpen(PostSellFeedbackResponse facts) {
		PostSellFlow flow = facts.postSellFlow();
		PeerComparison peer = facts.peerComparison();
		return flow != null
			&& flow.status() == PostSellFeedbackStatus.READY
			&& peer != null
			&& peer.status() != PostSellFeedbackStatus.NOT_YET;
	}

	private NarrativeResultDto regenerateNarrative(
		Long tradeId,
		PostSellFeedbackResponse facts,
		JournalDigestDto journals,
		NarrativeResultDto stored,
		RegenerationReasons reasons) {
		NarrativeResultDto resolved = narrativeService.resolvePostSellNarrative(toPromptInput(facts, journals));
		if (resolved.source() != NarrativeSource.LLM) {
			log.debug(
				"매도 회고 서술 재생성이 템플릿으로 폴백해 기존 서술을 유지한다. tradeId={} reasons={}", tradeId, reasons);
			tradeFeedbackWriter.recordFailedRegeneration(tradeId, reasons);
			return stored;
		}
		tradeFeedbackWriter.applyRegenerated(
			tradeId, resolved, journals.fingerprint(), reasons, LocalDateTime.now(clock));
		return resolved;
	}

	private static PostSellPromptDto toPromptInput(PostSellFeedbackResponse facts, JournalDigestDto journals) {
		PostSellFlow flow = facts.postSellFlow();
		PeerComparison peer = facts.peerComparison();
		return new PostSellPromptDto(
			facts.name(),
			facts.buyAt(),
			facts.buyPrice(),
			facts.sellAt(),
			facts.sellPrice(),
			facts.quantity(),
			facts.returnRate(),
			facts.realizedPnl() == null ? 0L : facts.realizedPnl(),
			facts.holdHighPrice(),
			facts.holdHighAt(),
			facts.sellVsHighRate(),
			facts.holdLowPrice(),
			facts.holdLowAt(),
			facts.sellVsLowRate(),
			facts.buyToNewsMinutes(),
			firstNewsAt(facts.priceMoves()),
			toPromptPriceMoves(facts.priceMoves()),
			flow == null ? null : flow.closePrice(),
			flow == null ? null : flow.sellToCloseRate(),
			peer == null ? null : peer.holderCount(),
			peer == null ? null : peer.soldWithin30MinRate(),
			peer == null ? null : peer.medianMinutesToSell(),
			peer == null ? null : peer.yourMinutesToSell(),
			facts.sameSessionCompleted() && !facts.buyAt().toLocalDate().equals(facts.sellAt().toLocalDate()),
			facts.holdHighBasis(),
			toPromptJournals(journals),
			journals.sellJournalContent());
	}

	private static List<BuyJournalLineDto> toPromptJournals(JournalDigestDto journals) {
		return journals.buyJournals()
			.stream()
			.map(journal -> new BuyJournalLineDto(journal.buyAt(), journal.content()))
			.toList();
	}

	private static LocalDateTime firstNewsAt(List<HeldPriceMoveItem> priceMoves) {
		return priceMoves.stream()
			.flatMap(move -> move.sources().stream())
			.map(NewsItem::publishedAt)
			.min(Comparator.naturalOrder())
			.orElse(null);
	}

	private static List<HeldPriceMoveDto> toPromptPriceMoves(List<HeldPriceMoveItem> priceMoves) {
		return priceMoves.stream()
			.map(move -> new HeldPriceMoveDto(
				move.windowStart(),
				move.windowEnd(),
				move.changeRate(),
				move.minutesAfterBuy(),
				move.minutesBeforeSell(),
				move.sources().stream()
					.map(source -> new NewsSourceDto(
						source.title(),
						source.publisher(),
						source.publishedAt(),
						source.type() == MarketNewsItemType.DISCLOSURE))
					.toList()))
			.toList();
	}
}
