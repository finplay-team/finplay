// 랭킹 목록의 항목 하나(순위·닉네임·실현손익)를 담는 응답 DTO
package com.finplay.api.ranking.dto.response;

public record RankingListItemResponse(int rank, String nickname, long realizedPnl) {
}
