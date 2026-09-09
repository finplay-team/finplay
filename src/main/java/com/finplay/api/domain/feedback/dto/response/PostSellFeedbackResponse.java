package com.finplay.api.domain.feedback.dto.response;

import com.finplay.api.domain.feedback.entity.HoldHighBasis;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record PostSellFeedbackResponse(
	Long tradeId,
	Long instrumentId,
	String symbol,
	String name,
	LocalDateTime buyAt,
	LocalDateTime sellAt,
	BigDecimal buyPrice,
	BigDecimal sellPrice,
	BigDecimal quantity,
	long fee,
	Long realizedPnl,
	BigDecimal returnRate,
	Integer holdingMinutes,
	boolean sameSessionCompleted,
	BigDecimal holdHighPrice,
	LocalDateTime holdHighAt,
	BigDecimal holdLowPrice,
	LocalDateTime holdLowAt,
	BigDecimal sellVsHighRate,
	BigDecimal sellVsLowRate,
	HoldHighBasis holdHighBasis,
	Integer buyToNewsMinutes,
	List<HeldPriceMoveItem> priceMoves,
	PostSellFlow postSellFlow,
	Counterfactuals counterfactuals,
	PeerComparison peerComparison,
	String narrative,
	NarrativeSource narrativeSource,
	PostSellFeedbackStatus narrativeStatus) {

	public PostSellFeedbackResponse {
		priceMoves = List.copyOf(priceMoves);
	}

	public PostSellFeedbackResponse withNarrative(
		String narrative, NarrativeSource narrativeSource, PostSellFeedbackStatus narrativeStatus) {
		return new PostSellFeedbackResponse(
			tradeId,
			instrumentId,
			symbol,
			name,
			buyAt,
			sellAt,
			buyPrice,
			sellPrice,
			quantity,
			fee,
			realizedPnl,
			returnRate,
			holdingMinutes,
			sameSessionCompleted,
			holdHighPrice,
			holdHighAt,
			holdLowPrice,
			holdLowAt,
			sellVsHighRate,
			sellVsLowRate,
			holdHighBasis,
			buyToNewsMinutes,
			priceMoves,
			postSellFlow,
			counterfactuals,
			peerComparison,
			narrative,
			narrativeSource,
			narrativeStatus);
	}
}
