package com.finplay.api.domain.feedback.dto.response;

import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import java.time.LocalDate;
import java.util.List;

public record PriceMoveListResponse(LocalDate originTradeDate, FeedbackContentStatus status,
	List<PriceMoveItem> moves) {

	public PriceMoveListResponse {
		moves = List.copyOf(moves);
	}

	public static PriceMoveListResponse notYet() {
		return new PriceMoveListResponse(null, FeedbackContentStatus.NOT_YET, List.of());
	}

	public static PriceMoveListResponse of(LocalDate originTradeDate, List<PriceMoveItem> moves) {
		FeedbackContentStatus status = moves.isEmpty() ? FeedbackContentStatus.EMPTY : FeedbackContentStatus.READY;
		return new PriceMoveListResponse(originTradeDate, status, moves);
	}
}
