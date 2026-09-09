package com.finplay.api.domain.ranking.dto.response;

import com.finplay.api.domain.ranking.entity.RankingStatus;
import java.util.List;

public record RankingListResponse(String market, RankingStatus status, List<RankingListItemResponse> content) {

	public RankingListResponse {
		content = List.copyOf(content);
	}
}
