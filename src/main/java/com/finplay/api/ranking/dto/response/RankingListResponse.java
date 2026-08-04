// 시장별 랭킹 목록 응답 wrapper (최소 스텁 — 다음 작업 항목에서 정식화)
package com.finplay.api.ranking.dto.response;

import java.util.List;

public record RankingListResponse(String market, List<RankingListItemResponse> content) {

	public RankingListResponse {
		content = List.copyOf(content);
	}
}
