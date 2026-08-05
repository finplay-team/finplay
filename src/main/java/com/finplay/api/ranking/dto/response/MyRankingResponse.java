// 인증 사용자 본인의 시장별 실현손익 순위 단건 응답 (RANK-002)
package com.finplay.api.ranking.dto.response;

public record MyRankingResponse(String market, Integer rank, String nickname, long realizedPnl) {
}
