// 사용자·시장별 계좌 단건 조회 쿼리를 실제 MySQL에서 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import java.time.LocalDateTime;
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

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("trader@finplay.com", "password-hash", "trader", NOW));
		accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));
		accountRepository.saveAndFlush(Account.create(user, Market.CRYPTO, NOW));
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
}
