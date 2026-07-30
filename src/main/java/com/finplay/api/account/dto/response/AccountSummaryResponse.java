// 시장별 계좌 요약(현금잔고·평가액·손익·수익률)을 노출하는 응답 DTO
package com.finplay.api.account.dto.response;

import java.math.BigDecimal;

public record AccountSummaryResponse(
	long cashBalance,
	long holdingsValue,
	long totalValue,
	long realizedPnl,
	long unrealizedPnl,
	BigDecimal returnRate) {

	public static AccountSummaryResponse of(
		long cashBalance,
		long holdingsValue,
		long totalValue,
		long realizedPnl,
		long unrealizedPnl,
		BigDecimal returnRate) {
		return new AccountSummaryResponse(
			cashBalance, holdingsValue, totalValue, realizedPnl, unrealizedPnl, returnRate);
	}
}
