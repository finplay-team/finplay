// 주식·코인 계좌를 합산한 전체 포트폴리오 요약을 조회하는 서비스
package com.finplay.api.portfolio.service;

import com.finplay.api.account.domain.Market;
import com.finplay.api.account.dto.response.AccountSummaryResponse;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.portfolio.dto.response.PortfolioSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PortfolioService {

	private final AccountService accountService;

	@Transactional(readOnly = true)
	public PortfolioSummaryResponse getPortfolioSummary(Long userId) {
		AccountSummaryResponse stockSummary = accountService.getAccountSummary(userId, Market.STOCK);
		AccountSummaryResponse cryptoSummary = accountService.getAccountSummary(userId, Market.CRYPTO);

		long totalValue = stockSummary.totalValue() + cryptoSummary.totalValue();
		long unrealizedPnl = stockSummary.unrealizedPnl() + cryptoSummary.unrealizedPnl();
		long realizedPnl = stockSummary.realizedPnl() + cryptoSummary.realizedPnl();

		return PortfolioSummaryResponse.of(totalValue, unrealizedPnl, realizedPnl);
	}
}
