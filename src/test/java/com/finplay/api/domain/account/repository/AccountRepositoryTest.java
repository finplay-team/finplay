package com.finplay.api.domain.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Market;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class AccountRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	private User user;
	private Account stockAccount;
	private Account cryptoAccount;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("trader@finplay.com", "password-hash", "trader", NOW));
		stockAccount = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));
		cryptoAccount = accountRepository.saveAndFlush(Account.create(user, Market.CRYPTO, NOW));
	}

	@Test
	void findByUserIdAndMarketReturnsTheMatchingMarketAccount() {
		Optional<Account> result = accountRepository.findByUserIdAndMarket(user.getId(), Market.STOCK);

		assertThat(result).isPresent();
		assertThat(result.get().getMarket()).isEqualTo(Market.STOCK);
		assertThat(result.get().getUser().getId()).isEqualTo(user.getId());
	}

	@Test
	void findByUserIdAndMarketDistinguishesBetweenTheTwoMarketAccountsOfTheSameUser() {
		Optional<Account> cryptoAccount = accountRepository.findByUserIdAndMarket(user.getId(), Market.CRYPTO);

		assertThat(cryptoAccount).isPresent();
		assertThat(cryptoAccount.get().getMarket()).isEqualTo(Market.CRYPTO);
	}

	@Test
	void findByUserIdAndMarketReturnsEmptyWhenUserIdDoesNotExist() {
		Optional<Account> result = accountRepository.findByUserIdAndMarket(999_999L, Market.STOCK);

		assertThat(result).isEmpty();
	}

	@Test
	void findByUserIdAndMarketFetchUserReturnsTheMatchingMarketAccountWithUserFetched() {
		Optional<Account> result = accountRepository.findByUserIdAndMarketFetchUser(user.getId(), Market.STOCK);

		assertThat(result).isPresent();
		assertThat(result.get().getId()).isEqualTo(stockAccount.getId());
		assertThat(result.get().getUser().getId()).isEqualTo(user.getId());
		assertThat(result.get().getUser().getNickname()).isEqualTo(user.getNickname());
	}

	@Test
	void findByUserIdAndMarketFetchUserReturnsEmptyWhenNoMatchingAccount() {
		Optional<Account> result = accountRepository.findByUserIdAndMarketFetchUser(999_999L, Market.STOCK);

		assertThat(result).isEmpty();
	}

	@Test
	void findAllByIdInFetchUserReturnsAccountsWithUserFetchedForRequestedIdsOnly() {
		User otherUser = userRepository.saveAndFlush(
			User.create("other@finplay.com", "password-hash", "other", NOW));
		accountRepository.saveAndFlush(Account.create(otherUser, Market.STOCK, NOW));

		List<Account> result = accountRepository
			.findAllByIdInFetchUser(List.of(stockAccount.getId(), cryptoAccount.getId()));

		assertThat(result).hasSize(2);
		assertThat(result)
			.extracting(Account::getId)
			.containsExactlyInAnyOrder(stockAccount.getId(), cryptoAccount.getId());
		assertThat(result).allSatisfy(account -> assertThat(account.getUser().getId()).isEqualTo(user.getId()));
	}

	@Test
	void findByUserIdAndMarketForUpdateReturnsTheMatchingMarketAccount() {
		Optional<Account> result = accountRepository.findByUserIdAndMarketForUpdate(user.getId(), Market.CRYPTO);

		assertThat(result).isPresent();
		assertThat(result.get().getId()).isEqualTo(cryptoAccount.getId());
	}

	@Test
	void findByUserIdAndMarketForUpdateReturnsEmptyWhenNoMatchingAccount() {
		Optional<Account> result = accountRepository.findByUserIdAndMarketForUpdate(999_999L, Market.CRYPTO);

		assertThat(result).isEmpty();
	}

	@Test
	void findByIdForUpdateReturnsTheAccountById() {
		Optional<Account> result = accountRepository.findByIdForUpdate(cryptoAccount.getId());

		assertThat(result).isPresent();
		assertThat(result.get().getMarket()).isEqualTo(Market.CRYPTO);
	}

	@Test
	void findByIdForUpdateReturnsEmptyWhenIdDoesNotExist() {
		Optional<Account> result = accountRepository.findByIdForUpdate(999_999L);

		assertThat(result).isEmpty();
	}

	@Test
	void findByIdInForUpdateReturnsAccountsInAscendingIdOrderRegardlessOfInputOrder() {
		List<Account> result = accountRepository
			.findByIdInForUpdate(List.of(cryptoAccount.getId(), stockAccount.getId()));

		assertThat(result).extracting(Account::getId)
			.containsExactly(stockAccount.getId(), cryptoAccount.getId());
	}

	@Test
	void findByIdInForUpdateSilentlyDropsNonExistentIds() {
		List<Account> result = accountRepository.findByIdInForUpdate(List.of(stockAccount.getId(), 999_999L));

		assertThat(result).extracting(Account::getId).containsExactly(stockAccount.getId());
	}
}
