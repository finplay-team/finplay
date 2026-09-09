package com.finplay.api.domain.ranking.dto.response;

import com.finplay.api.domain.ranking.entity.RankingStatus;

public record MyRankingResponse(String market, RankingStatus status, Integer rank, String nickname,
	long realizedPnl) {
}
