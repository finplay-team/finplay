package com.finplay.api.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.dto.response.AccountSummaryResponse;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.portfolio.service.HoldingValuationDto;
import com.finplay.api.domain.portfolio.service.HoldingValuationService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
	void getAccountForWithUserReturnsAccountWhenRepositoryFindsIt() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		User user = User.create("user@finplay.com", "password-hash", "finplayer",
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		Account account = Account.create(user, Market.STOCK,
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		when(accountRepository.findByUserIdAndMarketFetchUser(1L, Market.STOCK)).thenReturn(Optional.of(account));

		Account result = accountService.getAccountForWithUser(1L, Market.STOCK);

		assertThat(result).isSameAs(account);
	}

	@Test
	void getAccountForWithUserThrowsNotFoundWhenNoAccountExistsForUserAndMarket() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		when(accountRepository.findByUserIdAndMarketFetchUser(1L, Market.STOCK)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> accountService.getAccountForWithUser(1L, Market.STOCK))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
	}

	@Test
	void findByIdOrEmptyReturnsAccountWhenRepositoryFindsIt() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		User user = User.create("user@finplay.com", "password-hash", "finplayer",
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		Account account = Account.create(user, Market.STOCK,
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

		Optional<Account> result = accountService.findByIdOrEmpty(1L);

		assertThat(result).containsSame(account);
	}

	@Test
	void findByIdOrEmptyReturnsEmptyWhenAccountDoesNotExist() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		when(accountRepository.findById(999L)).thenReturn(Optional.empty());

		Optional<Account> result = accountService.findByIdOrEmpty(999L);

		assertThat(result).isEmpty();
	}

	@Test
	void getAccountsWithUserDelegatesToRepositoryAndReturnsItsResult() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		User user = User.create("user@finplay.com", "password-hash", "finplayer",
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		Account account = Account.create(user, Market.STOCK,
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		List<Long> ids = List.of(1L, 2L);
		when(accountRepository.findAllByIdInFetchUser(ids)).thenReturn(List.of(account));

		List<Account> result = accountService.getAccountsWithUser(ids);

		assertThat(result).containsExactly(account);
		verify(accountRepository).findAllByIdInFetchUser(ids);
	}

	@Test
	void getAccountsByIdsDelegatesToFindAllByIdWithoutFetchingUser() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		User user = User.create("user@finplay.com", "password-hash", "finplayer",
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		Account account = Account.create(user, Market.STOCK,
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		List<Long> ids = List.of(1L, 2L);
		when(accountRepository.findAllById(ids)).thenReturn(List.of(account));

		List<Account> result = accountService.getAccountsByIds(ids);

		assertThat(result).containsExactly(account);
		verify(accountRepository).findAllById(ids);
		verify(accountRepository, never()).findAllByIdInFetchUser(any());
	}

	@Test
	void getAccountSummarySumsOnlyAvailablePricedHoldings() {
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
		account.reserveCash(1_500_000L);
		when(accountRepository.findByUserIdAndMarket(1L, Market.STOCK)).thenReturn(Optional.of(account));

		HoldingValuationDto profitable = new HoldingValuationDto(
			BigDecimal.TEN, BigDecimal.valueOf(1_000), 10_000L, PriceStatus.AVAILABLE, BigDecimal.valueOf(1_200),
			12_000L, 2_000L, BigDecimal.valueOf(0.2000));
		HoldingValuationDto lossy = new HoldingValuationDto(
			BigDecimal.ONE, BigDecimal.valueOf(500_000), 500_000L, PriceStatus.AVAILABLE, BigDecimal.valueOf(400_000),
			400_000L, -100_000L, BigDecimal.valueOf(-0.2000));
		when(holdingValuationService.evaluateActiveHoldingsForAccount(any()))
			.thenReturn(List.of(profitable, lossy));

		AccountSummaryResponse result = accountService.getAccountSummary(1L, Market.STOCK);

		long expectedCashBalance = 7_000_000L;
		long expectedHoldingsValue = 412_000L;
		long expectedTotalValue = expectedCashBalance + expectedHoldingsValue;
		long expectedUnrealizedPnl = -98_000L;

		assertThat(result.cashBalance()).isEqualTo(expectedCashBalance);
		assertThat(result.reservedCash()).isEqualTo(1_500_000L);
		assertThat(result.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(result.totalValue()).isEqualTo(expectedTotalValue);
		assertThat(result.realizedPnl()).isEqualTo(50_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(expectedUnrealizedPnl);
	}

	@Test
	void getAccountSummaryIncludesCostBasisForUnavailablePricedHoldingsWithoutPnlContribution() {
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
			BigDecimal.TEN, BigDecimal.valueOf(1_000), 10_000L, PriceStatus.AVAILABLE, BigDecimal.valueOf(1_500),
			15_000L, 5_000L, BigDecimal.valueOf(0.5000));
		HoldingValuationDto unavailable = new HoldingValuationDto(
			BigDecimal.ONE, BigDecimal.valueOf(500_000), 500_000L, PriceStatus.UNAVAILABLE, null, null, null, null);
		when(holdingValuationService.evaluateActiveHoldingsForAccount(any()))
			.thenReturn(List.of(available, unavailable));

		AccountSummaryResponse result = accountService.getAccountSummary(1L, Market.CRYPTO);

		long expectedHoldingsValue = 15_000L + 500_000L;
		assertThat(result.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(result.unrealizedPnl()).isEqualTo(5_000L);
		assertThat(result.totalValue()).isEqualTo(account.getCashBalance() + expectedHoldingsValue);
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
		assertThat(result.reservedCash()).isZero();
		assertThat(result.cashBalance()).isEqualTo(10_000_000L);
		assertThat(result.totalValue()).isEqualTo(10_000_000L);
		assertThat(result.realizedPnl()).isZero();
	}

	@Test
	void getAccountSummaryComputesTotalValueAsCashPlusHoldingsValue() {
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
			BigDecimal.TEN, BigDecimal.valueOf(1_000), 10_000L, PriceStatus.AVAILABLE, BigDecimal.valueOf(1_200),
			12_000L, 2_000L, BigDecimal.valueOf(0.2000));
		when(holdingValuationService.evaluateActiveHoldingsForAccount(any())).thenReturn(List.of(profitable));

		AccountSummaryResponse result = accountService.getAccountSummary(1L, Market.STOCK);

		long expectedCashBalance = 7_000_000L;
		long expectedHoldingsValue = 12_000L;
		long expectedTotalValue = expectedCashBalance + expectedHoldingsValue;
		assertThat(result.cashBalance()).isEqualTo(expectedCashBalance);
		assertThat(result.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(result.totalValue()).isEqualTo(expectedTotalValue);
		assertThat(result.realizedPnl()).isEqualTo(50_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(2_000L);
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

	@Test
	void getAccountsByIdsForUpdateDelegatesToRepositoryBulkLockQuery() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		User user = User.create("user@finplay.com", "password-hash", "finplayer",
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		Account account = Account.create(user, Market.STOCK,
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		List<Long> requestedIds = List.of(10L, 20L);
		when(accountRepository.findByIdInForUpdate(requestedIds)).thenReturn(List.of(account));

		List<Account> result = accountService.getAccountsByIdsForUpdate(requestedIds);

		assertThat(result).containsExactly(account);
		verify(accountRepository).findByIdInForUpdate(requestedIds);
	}
}
