// 전체 포트폴리오 합산 요약 서비스의 합산·수익률 재계산 로직을 검증하는 단위 테스트다.
package com.finplay.api.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.dto.response.AccountSummaryResponse;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.portfolio.dto.response.PortfolioSummaryResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PortfolioServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 30, 0, 0);
	private static final Long USER_ID = 1L;

	@Test
	void getPortfolioSummaryAggregatesBothMarketsAndRecalculatesReturnRateInsteadOfAveraging() {
		AccountService accountService = mock(AccountService.class);
		PortfolioService portfolioService = new PortfolioService(accountService);

		User user = User.create("user@finplay.com", "password-hash", "finplayer", NOW);
		Account stockAccount = Account.create(user, Market.STOCK, NOW);
		Account cryptoAccount = Account.create(user, Market.CRYPTO, NOW);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(stockAccount);
		when(accountService.getAccountFor(USER_ID, Market.CRYPTO)).thenReturn(cryptoAccount);

		// 두 시장의 개별 returnRate를 우연히 동일(0.5)하게 만들지만, seedMoney 합계(20,000,000) 기준으로
		// 재계산하면 실제 결과(0.25)는 그 값과 달라야 한다 — 단순 합/평균을 쓰지 않았음을 반증한다.
		AccountSummaryResponse stockSummary = AccountSummaryResponse.of(
			0L, 0L, 0L, 15_000_000L, 0L, 0L, BigDecimal.valueOf(0.5));
		AccountSummaryResponse cryptoSummary = AccountSummaryResponse.of(
			0L, 0L, 0L, 10_000_000L, 500_000L, 300_000L, BigDecimal.valueOf(0.5));
		when(accountService.getAccountSummary(USER_ID, Market.STOCK)).thenReturn(stockSummary);
		when(accountService.getAccountSummary(USER_ID, Market.CRYPTO)).thenReturn(cryptoSummary);

		PortfolioSummaryResponse result = portfolioService.getPortfolioSummary(USER_ID);

		assertThat(result.totalValue()).isEqualTo(25_000_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(300_000L);
		assertThat(result.realizedPnl()).isEqualTo(500_000L);
		// (25,000,000 - 20,000,000) / 20,000,000 = 0.25 — 개별 returnRate(0.5)의 합(1.0)도, 평균(0.5)도 아니다.
		assertThat(result.returnRate()).isEqualByComparingTo(BigDecimal.valueOf(0.2500));

		verify(accountService, times(1)).getAccountFor(USER_ID, Market.STOCK);
		verify(accountService, times(1)).getAccountFor(USER_ID, Market.CRYPTO);
		verify(accountService, times(1)).getAccountSummary(USER_ID, Market.STOCK);
		verify(accountService, times(1)).getAccountSummary(USER_ID, Market.CRYPTO);
	}

	@Test
	void getPortfolioSummaryTreatsMarketWithoutHoldingsAsZeroContribution() {
		AccountService accountService = mock(AccountService.class);
		PortfolioService portfolioService = new PortfolioService(accountService);

		User user = User.create("user@finplay.com", "password-hash", "finplayer", NOW);
		Account stockAccount = Account.create(user, Market.STOCK, NOW);
		Account cryptoAccount = Account.create(user, Market.CRYPTO, NOW);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(stockAccount);
		when(accountService.getAccountFor(USER_ID, Market.CRYPTO)).thenReturn(cryptoAccount);

		// STOCK만 보유 종목이 있어 평가손익이 발생하고, CRYPTO는 현금만 있어 0으로 기여한다.
		AccountSummaryResponse stockSummary = AccountSummaryResponse.of(
			5_000_000L, 0L, 6_000_000L, 11_000_000L, 200_000L, 1_000_000L, BigDecimal.valueOf(0.1000));
		AccountSummaryResponse cryptoSummary = AccountSummaryResponse.of(
			10_000_000L, 0L, 0L, 10_000_000L, 0L, 0L, BigDecimal.ZERO);
		when(accountService.getAccountSummary(USER_ID, Market.STOCK)).thenReturn(stockSummary);
		when(accountService.getAccountSummary(USER_ID, Market.CRYPTO)).thenReturn(cryptoSummary);

		PortfolioSummaryResponse result = portfolioService.getPortfolioSummary(USER_ID);

		assertThat(result.totalValue()).isEqualTo(21_000_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(1_000_000L);
		assertThat(result.realizedPnl()).isEqualTo(200_000L);
		// (21,000,000 - 20,000,000) / 20,000,000 = 0.05
		assertThat(result.returnRate()).isEqualByComparingTo(BigDecimal.valueOf(0.0500));
	}

	@Test
	void getPortfolioSummaryReturnsZeroReturnRateWhenBothMarketsHaveNoHoldings() {
		AccountService accountService = mock(AccountService.class);
		PortfolioService portfolioService = new PortfolioService(accountService);

		User user = User.create("user@finplay.com", "password-hash", "finplayer", NOW);
		Account stockAccount = Account.create(user, Market.STOCK, NOW);
		Account cryptoAccount = Account.create(user, Market.CRYPTO, NOW);
		when(accountService.getAccountFor(USER_ID, Market.STOCK)).thenReturn(stockAccount);
		when(accountService.getAccountFor(USER_ID, Market.CRYPTO)).thenReturn(cryptoAccount);

		AccountSummaryResponse stockSummary = AccountSummaryResponse.of(
			10_000_000L, 0L, 0L, 10_000_000L, 0L, 0L, BigDecimal.ZERO);
		AccountSummaryResponse cryptoSummary = AccountSummaryResponse.of(
			10_000_000L, 0L, 0L, 10_000_000L, 0L, 0L, BigDecimal.ZERO);
		when(accountService.getAccountSummary(USER_ID, Market.STOCK)).thenReturn(stockSummary);
		when(accountService.getAccountSummary(USER_ID, Market.CRYPTO)).thenReturn(cryptoSummary);

		PortfolioSummaryResponse result = portfolioService.getPortfolioSummary(USER_ID);

		assertThat(result.totalValue()).isEqualTo(20_000_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(0L);
		assertThat(result.realizedPnl()).isEqualTo(0L);
		assertThat(result.returnRate()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void getPortfolioSummaryPropagatesNotFoundWhenAccountMissing() {
		AccountService accountService = mock(AccountService.class);
		PortfolioService portfolioService = new PortfolioService(accountService);

		when(accountService.getAccountFor(USER_ID, Market.STOCK))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		assertThatThrownBy(() -> portfolioService.getPortfolioSummary(USER_ID))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}
}
