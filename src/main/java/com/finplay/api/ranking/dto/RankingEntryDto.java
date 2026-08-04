// RankingStore와 RankingService 사이에서 계좌별 랭킹 점수를 전달하는 내부 DTO
package com.finplay.api.ranking.dto;

public record RankingEntryDto(Long accountId, long score) {
}
