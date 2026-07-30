// 주식·코인 계좌를 합산한 전체 포트폴리오 요약을 조회하는 서비스
package com.finplay.api.portfolio.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.dto.response.AccountSummaryResponse;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.portfolio.dto.response.PortfolioSummaryResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PortfolioService {

	private static final int RETURN_RATE_SCALE = 4;

	private final AccountService accountService;

	@Transactional(readOnly = true)
	public PortfolioSummaryResponse getPortfolioSummary(Long userId) {
		Account stockAccount = accountService.getAccountFor(userId, Market.STOCK);
		Account cryptoAccount = accountService.getAccountFor(userId, Market.CRYPTO);

		AccountSummaryResponse stockSummary = accountService.getAccountSummary(userId, Market.STOCK);
		AccountSummaryResponse cryptoSummary = accountService.getAccountSummary(userId, Market.CRYPTO);

		long totalValue = stockSummary.totalValue() + cryptoSummary.totalValue();
		long unrealizedPnl = stockSummary.unrealizedPnl() + cryptoSummary.unrealizedPnl();
		long realizedPnl = stockSummary.realizedPnl() + cryptoSummary.realizedPnl();
		long seedMoneyTotal = stockAccount.getSeedMoney() + cryptoAccount.getSeedMoney();

		BigDecimal returnRate = seedMoneyTotal == 0
			? BigDecimal.ZERO
			: BigDecimal.valueOf(totalValue - seedMoneyTotal)
				.divide(BigDecimal.valueOf(seedMoneyTotal), RETURN_RATE_SCALE, RoundingMode.HALF_UP);

		return PortfolioSummaryResponse.of(totalValue, returnRate, unrealizedPnl, realizedPnl);
	}
}
