// 회원의 시장별 초기 계좌 생성·조회 상태를 검증하는 단위 테스트다.
package com.finplay.api.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.dto.response.AccountSummaryResponse;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.service.PriceStatus;
import com.finplay.api.portfolio.service.HoldingValuationDto;
import com.finplay.api.portfolio.service.HoldingValuationService;

class AccountServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-25T00:00:00Z");

	@Test
	@SuppressWarnings("unchecked")
	void createAccountsForCreatesStockAndCryptoAccountsWithInitialBalances() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		User user = User.create("user@finplay.com", "password-hash", "finplayer",
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));

		accountService.createAccountsFor(user);

		ArgumentCaptor<Iterable<Account>> accountsCaptor = ArgumentCaptor.forClass(Iterable.class);
		verify(accountRepository).saveAll(accountsCaptor.capture());
		List<Account> accounts = StreamSupport.stream(accountsCaptor.getValue().spliterator(), false).toList();
		assertThat(accounts).extracting(Account::getMarket).containsExactlyInAnyOrder(Market.STOCK, Market.CRYPTO);
		assertThat(accounts).allSatisfy(account -> {
			assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
			assertThat(account.getSeedMoney()).isEqualTo(10_000_000L);
			assertThat(account.getRealizedPnl()).isZero();
		});
	}

	@Test
	void getAccountForReturnsAccountWhenRepositoryFindsIt() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		User user = User.create("user@finplay.com", "password-hash", "finplayer",
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		Account account = Account.create(user, Market.STOCK,
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		when(accountRepository.findByUserIdAndMarket(1L, Market.STOCK)).thenReturn(Optional.of(account));

		Account result = accountService.getAccountFor(1L, Market.STOCK);

		assertThat(result).isSameAs(account);
	}

	@Test
	void getAccountForThrowsNotFoundWhenNoAccountExistsForUserAndMarket() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		when(accountRepository.findByUserIdAndMarket(1L, Market.STOCK)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> accountService.getAccountFor(1L, Market.STOCK))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void getAccountSummarySumsOnlyAvailablePricedHoldingsAndComputesReturnRate() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		User user = User.create("user@finplay.com", "password-hash", "finplayer",
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		Account account = Account.create(user, Market.STOCK,
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		account.deductCash(3_000_000L);
		account.addRealizedPnl(50_000L);
		when(accountRepository.findByUserIdAndMarket(1L, Market.STOCK)).thenReturn(Optional.of(account));

		HoldingValuationDto profitable = new HoldingValuationDto(
			BigDecimal.TEN, BigDecimal.valueOf(1_000), 10_000L, PriceStatus.AVAILABLE, 12_000L, 2_000L,
			BigDecimal.valueOf(0.2000));
		HoldingValuationDto lossy = new HoldingValuationDto(
			BigDecimal.ONE, BigDecimal.valueOf(500_000), 500_000L, PriceStatus.AVAILABLE, 400_000L, -100_000L,
			BigDecimal.valueOf(-0.2000));
		when(holdingValuationService.evaluateActiveHoldingsForAccount(any()))
			.thenReturn(List.of(profitable, lossy));

		AccountSummaryResponse result = accountService.getAccountSummary(1L, Market.STOCK);

		long expectedCashBalance = 7_000_000L;
		long expectedHoldingsValue = 412_000L;
		long expectedTotalValue = expectedCashBalance + expectedHoldingsValue;
		long expectedUnrealizedPnl = -98_000L;
		BigDecimal expectedReturnRate = BigDecimal.valueOf(expectedTotalValue - 10_000_000L)
			.divide(BigDecimal.valueOf(10_000_000L), 4, java.math.RoundingMode.HALF_UP);

		assertThat(result.cashBalance()).isEqualTo(expectedCashBalance);
		assertThat(result.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(result.totalValue()).isEqualTo(expectedTotalValue);
		assertThat(result.realizedPnl()).isEqualTo(50_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(expectedUnrealizedPnl);
		assertThat(result.returnRate()).isEqualByComparingTo(expectedReturnRate);
	}

	@Test
	void getAccountSummaryExcludesUnavailablePricedHoldingsFromSums() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		User user = User.create("user@finplay.com", "password-hash", "finplayer",
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		Account account = Account.create(user, Market.CRYPTO,
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		when(accountRepository.findByUserIdAndMarket(1L, Market.CRYPTO)).thenReturn(Optional.of(account));

		HoldingValuationDto available = new HoldingValuationDto(
			BigDecimal.TEN, BigDecimal.valueOf(1_000), 10_000L, PriceStatus.AVAILABLE, 15_000L, 5_000L,
			BigDecimal.valueOf(0.5000));
		HoldingValuationDto unavailable = new HoldingValuationDto(
			BigDecimal.ONE, BigDecimal.valueOf(500_000), 500_000L, PriceStatus.UNAVAILABLE, null, null, null);
		when(holdingValuationService.evaluateActiveHoldingsForAccount(any()))
			.thenReturn(List.of(available, unavailable));

		AccountSummaryResponse result = accountService.getAccountSummary(1L, Market.CRYPTO);

		assertThat(result.holdingsValue()).isEqualTo(15_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(5_000L);
		assertThat(result.totalValue()).isEqualTo(account.getCashBalance() + 15_000L);
	}

	@Test
	void getAccountSummaryReturnsZeroedValuesWhenNoActiveHoldings() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		User user = User.create("user@finplay.com", "password-hash", "finplayer",
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		Account account = Account.create(user, Market.STOCK,
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		when(accountRepository.findByUserIdAndMarket(1L, Market.STOCK)).thenReturn(Optional.of(account));
		when(holdingValuationService.evaluateActiveHoldingsForAccount(any())).thenReturn(List.of());

		AccountSummaryResponse result = accountService.getAccountSummary(1L, Market.STOCK);

		assertThat(result.holdingsValue()).isZero();
		assertThat(result.unrealizedPnl()).isZero();
		assertThat(result.cashBalance()).isEqualTo(10_000_000L);
		assertThat(result.totalValue()).isEqualTo(10_000_000L);
		assertThat(result.realizedPnl()).isZero();
		assertThat(result.returnRate()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void getAccountSummaryThrowsNotFoundWhenNoAccountExistsForUserAndMarket() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		when(accountRepository.findByUserIdAndMarket(1L, Market.STOCK)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> accountService.getAccountSummary(1L, Market.STOCK))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}
}
