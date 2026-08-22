// 시장별 계좌 요약(현금잔고·평가액·손익)을 노출하는 응답 DTO
package com.finplay.api.domain.account.dto.response;

public record AccountSummaryResponse(
	long cashBalance,
	long reservedCash,
	long holdingsValue,
	long totalValue,
	long realizedPnl,
	long unrealizedPnl) {

	public static AccountSummaryResponse of(
		long cashBalance,
		long reservedCash,
		long holdingsValue,
		long totalValue,
		long realizedPnl,
		long unrealizedPnl) {
		return new AccountSummaryResponse(
			cashBalance, reservedCash, holdingsValue, totalValue, realizedPnl, unrealizedPnl);
	}
}
