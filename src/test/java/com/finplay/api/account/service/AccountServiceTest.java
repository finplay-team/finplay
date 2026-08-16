// 회원의 시장별 초기 계좌 생성·조회 상태를 검증하는 단위 테스트다.
package com.finplay.api.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

	// 랭킹 재구성(이슈 #279)이 쓰는 배치 조회. getAccountsWithUser와 달리 User를 fetch join하지 않는다는 것이
	// 이 메서드가 따로 존재하는 유일한 이유다 — 재구성은 닉네임을 쓰지 않고 (id, realizedPnl)만 필요하다.
	// fetch join 버전으로 갈아타면 계좌 수만큼 users 조인이 붙어도 결과가 같아 조용히 통과하므로,
	// findAllByIdInFetchUser를 부르지 않았다는 것까지 단정한다.
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
		BigDecimal expectedReturnRate = BigDecimal.valueOf(expectedTotalValue - 10_000_000L)
			.divide(BigDecimal.valueOf(10_000_000L), 8, java.math.RoundingMode.HALF_UP);

		assertThat(result.cashBalance()).isEqualTo(expectedCashBalance);
		assertThat(result.reservedCash()).isEqualTo(1_500_000L);
		assertThat(result.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(result.totalValue()).isEqualTo(expectedTotalValue);
		assertThat(result.realizedPnl()).isEqualTo(50_000L);
		assertThat(result.unrealizedPnl()).isEqualTo(expectedUnrealizedPnl);
		assertThat(result.returnRate()).isEqualByComparingTo(expectedReturnRate);
	}

	// 이슈 #390 — 시드머니(1,000만원) 대비 작은 실현손실은 scale 4에서 반올림으로 0%가 됐다(실제 리포트 케이스:
	// 솔라나 매수 106,800원 → 매도 106,600원, 실현손실 306원). scale 8로 올린 뒤에는 0이 아닌 값이어야 한다.
	@Test
	void getAccountSummaryPreservesSmallRealizedLossInReturnRateInsteadOfRoundingToZero() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		User user = User.create("user@finplay.com", "password-hash", "finplayer",
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		Account account = Account.create(user, Market.CRYPTO,
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		// 매도 체결이 실제로 반영하는 두 값 — 순현금 감소(306원)와 원장 realizedPnl(-306원)을 함께 반영한다.
		account.deductCash(306L);
		account.addRealizedPnl(-306L);
		when(accountRepository.findByUserIdAndMarket(1L, Market.CRYPTO)).thenReturn(Optional.of(account));
		when(holdingValuationService.evaluateActiveHoldingsForAccount(any())).thenReturn(List.of());

		AccountSummaryResponse result = accountService.getAccountSummary(1L, Market.CRYPTO);

		// -306 ÷ 10,000,000 = -0.0000306 — scale 4였다면 반올림으로 0.0000이 됐을 값이다.
		BigDecimal expectedReturnRate = BigDecimal.valueOf(-306L)
			.divide(BigDecimal.valueOf(10_000_000L), 8, java.math.RoundingMode.HALF_UP);

		assertThat(result.totalValue()).isEqualTo(9_999_694L);
		assertThat(result.realizedPnl()).isEqualTo(-306L);
		assertThat(result.returnRate()).isEqualByComparingTo(expectedReturnRate);
		assertThat(result.returnRate()).isNotEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void getAccountSummaryIncludesCostBasisForUnavailablePricedHoldingsWithoutPnlContribution() {
		// PR #96 리뷰 차단 반영: 시세 무효(휴장 등) 보유는 evaluationAmount 대신 costBasis를
		// holdingsValue에 반영하고 unrealizedPnl에는 기여하지 않는다(휴장 시간대 전종목 UNAVAILABLE로
		// holdingsValue=0·수익률 대폭 마이너스가 되는 오류 재현·수정, plan.md "이슈 #81" 절 참고).
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

		long expectedHoldingsValue = 15_000L + 500_000L; // available.evaluationAmount + unavailable.costBasis
		assertThat(result.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(result.unrealizedPnl()).isEqualTo(5_000L); // unavailable은 0 기여
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
		assertThat(result.returnRate()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	// SANDBOX-EXCL-007: sandboxCashAdjustment가 있으면 totalValue·returnRate가 그만큼 줄고,
	// cashBalance·holdingsValue·realizedPnl·unrealizedPnl은 그대로여야 한다.
	@Test
	void getAccountSummarySubtractsSandboxCashAdjustmentFromTotalValueAndReturnRateOnly() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		HoldingValuationService holdingValuationService = mock(HoldingValuationService.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, holdingValuationService, fixedClock);
		User user = User.create("user@finplay.com", "password-hash", "finplayer",
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		Account account = Account.create(user, Market.STOCK,
			LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC));
		account.addSandboxCashAdjustment(5_000_000L);
		when(accountRepository.findByUserIdAndMarket(1L, Market.STOCK)).thenReturn(Optional.of(account));
		when(holdingValuationService.evaluateActiveHoldingsForAccount(any())).thenReturn(List.of());

		AccountSummaryResponse result = accountService.getAccountSummary(1L, Market.STOCK);

		long expectedTotalValue = 10_000_000L - 5_000_000L;
		BigDecimal expectedReturnRate = BigDecimal.valueOf(expectedTotalValue - 10_000_000L)
			.divide(BigDecimal.valueOf(10_000_000L), 8, java.math.RoundingMode.HALF_UP);
		assertThat(result.cashBalance()).isEqualTo(10_000_000L);
		assertThat(result.holdingsValue()).isZero();
		assertThat(result.totalValue()).isEqualTo(expectedTotalValue);
		assertThat(result.returnRate()).isEqualByComparingTo(expectedReturnRate);
		assertThat(result.realizedPnl()).isZero();
		assertThat(result.unrealizedPnl()).isZero();
	}

	// SANDBOX-EXCL-007 회귀: sandboxCashAdjustment가 0이면 이 spec 이전과 결과가 완전히 같아야 한다.
	@Test
	void getAccountSummaryMatchesPreExistingBehaviorWhenSandboxCashAdjustmentIsZero() {
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
		assertThat(result.returnRate()).isEqualByComparingTo(BigDecimal.valueOf(expectedTotalValue - 10_000_000L)
			.divide(BigDecimal.valueOf(10_000_000L), 8, java.math.RoundingMode.HALF_UP));
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
