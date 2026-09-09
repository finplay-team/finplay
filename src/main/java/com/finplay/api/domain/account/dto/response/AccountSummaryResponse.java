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
