// 시장별 랭킹 목록 응답 wrapper
package com.finplay.api.domain.ranking.dto.response;

import com.finplay.api.domain.ranking.entity.RankingStatus;
import java.util.List;

// status는 응답 전체의 성질이라 항목(RankingListItemResponse)이 아니라 wrapper에 둔다 — market을 wrapper에만
// 두는 RANK-001 원칙과 같다. 기존 필드(market·content)는 그대로라 필드 추가만으로 하위 호환이 유지된다(이슈 #279).
public record RankingListResponse(String market, RankingStatus status, List<RankingListItemResponse> content) {

	public RankingListResponse {
		content = List.copyOf(content);
	}
}
