// 주식·코인 계좌를 합산한 전체 포트폴리오 요약을 노출하는 응답 DTO
package com.finplay.api.portfolio.dto.response;

import java.math.BigDecimal;

public record PortfolioSummaryResponse(
	long totalValue, BigDecimal returnRate, long unrealizedPnl, long realizedPnl) {

	public static PortfolioSummaryResponse of(
		long totalValue, BigDecimal returnRate, long unrealizedPnl, long realizedPnl) {
		return new PortfolioSummaryResponse(totalValue, returnRate, unrealizedPnl, realizedPnl);
	}
}
