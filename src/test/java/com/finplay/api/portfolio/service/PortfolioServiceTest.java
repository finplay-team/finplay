// 전체 포트폴리오 합산 요약 서비스의 시장 간 합산 로직을 검증하는 단위 테스트다.
package com.finplay.api.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Market;
import com.finplay.api.account.dto.response.AccountSummaryResponse;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.portfolio.dto.response.PortfolioSummaryResponse;
import org.junit.jupiter.api.Test;

class PortfolioServiceTest {

	private static final Long USER_ID = 1L;

	@Test
	void getPortfolioSummaryAggregatesBothMarketsTotalValueAndPnl() {
		AccountService accountService = mock(AccountService.class);
		PortfolioService portfolioService = new PortfolioService(accountService);

		AccountSummaryResponse stockSummary = AccountSummaryResponse.of(
			0L, 0L, 0L, 15_000_000L, 0L, 0L);
		AccountSummaryResponse cryptoSummary = AccountSummaryResponse.of(
			0L, 0L, 0L, 10_000_000L, 500_000L, 300_000L);
		when(accountService.getAccountSummary(USER_ID, Market.STOCK)).thenReturn(stockSummary);
		when(accountService.getAccountSummary(USER_ID, Market.CRYPTO)).thenReturn(cryptoSummary);

		PortfolioSummaryResponse result = portfolioService.getPortfolioSummary(USER_ID);

		assertThat(result.totalValue()).isEqualTo(25_000_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(300_000L);
		assertThat(result.realizedPnl()).isEqualTo(500_000L);
	}

	@Test
	void getPortfolioSummaryTreatsMarketWithoutHoldingsAsZeroContribution() {
		AccountService accountService = mock(AccountService.class);
		PortfolioService portfolioService = new PortfolioService(accountService);

		// STOCK만 보유 종목이 있어 평가손익이 발생하고, CRYPTO는 현금만 있어 0으로 기여한다.
		AccountSummaryResponse stockSummary = AccountSummaryResponse.of(
			5_000_000L, 0L, 6_000_000L, 11_000_000L, 200_000L, 1_000_000L);
		AccountSummaryResponse cryptoSummary = AccountSummaryResponse.of(
			10_000_000L, 0L, 0L, 10_000_000L, 0L, 0L);
		when(accountService.getAccountSummary(USER_ID, Market.STOCK)).thenReturn(stockSummary);
		when(accountService.getAccountSummary(USER_ID, Market.CRYPTO)).thenReturn(cryptoSummary);

		PortfolioSummaryResponse result = portfolioService.getPortfolioSummary(USER_ID);

		assertThat(result.totalValue()).isEqualTo(21_000_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(1_000_000L);
		assertThat(result.realizedPnl()).isEqualTo(200_000L);
	}

	@Test
	void getPortfolioSummaryReturnsSummedSeedMoneyWhenBothMarketsHaveNoHoldings() {
		AccountService accountService = mock(AccountService.class);
		PortfolioService portfolioService = new PortfolioService(accountService);

		AccountSummaryResponse stockSummary = AccountSummaryResponse.of(
			10_000_000L, 0L, 0L, 10_000_000L, 0L, 0L);
		AccountSummaryResponse cryptoSummary = AccountSummaryResponse.of(
			10_000_000L, 0L, 0L, 10_000_000L, 0L, 0L);
		when(accountService.getAccountSummary(USER_ID, Market.STOCK)).thenReturn(stockSummary);
		when(accountService.getAccountSummary(USER_ID, Market.CRYPTO)).thenReturn(cryptoSummary);

		PortfolioSummaryResponse result = portfolioService.getPortfolioSummary(USER_ID);

		assertThat(result.totalValue()).isEqualTo(20_000_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(0L);
		assertThat(result.realizedPnl()).isEqualTo(0L);
	}

	@Test
	void getPortfolioSummaryPropagatesNotFoundWhenAccountMissing() {
		AccountService accountService = mock(AccountService.class);
		PortfolioService portfolioService = new PortfolioService(accountService);

		when(accountService.getAccountSummary(USER_ID, Market.STOCK))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertThatThrownBy(() -> portfolioService.getPortfolioSummary(USER_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}
}
