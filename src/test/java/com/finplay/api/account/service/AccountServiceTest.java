// 회원의 시장별 초기 계좌 생성 상태를 검증하는 단위 테스트다.
package com.finplay.api.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;

class AccountServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-07-25T00:00:00Z");

	@Test
	@SuppressWarnings("unchecked")
	void createAccountsForCreatesStockAndCryptoAccountsWithInitialBalances() {
		AccountRepository accountRepository = mock(AccountRepository.class);
		Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
		AccountService accountService = new AccountService(accountRepository, fixedClock);
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
}
