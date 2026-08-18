// 사용자·시장별 튜토리얼 계좌 조회·잠금·유일성 제약을 실제 MySQL에서 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.account.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.domain.TutorialAccount;
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
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TutorialAccountRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TutorialAccountRepository tutorialAccountRepository;

	private User user;
	private TutorialAccount stockAccount;
	private TutorialAccount cryptoAccount;

	@BeforeEach
	void setUp() {
		user = userRepository
			.saveAndFlush(User.create("tutorial-trader@finplay.com", "password-hash", "tutorial-trader", NOW));
		stockAccount = tutorialAccountRepository.saveAndFlush(TutorialAccount.create(user, Market.STOCK, NOW));
		cryptoAccount = tutorialAccountRepository.saveAndFlush(TutorialAccount.create(user, Market.CRYPTO, NOW));
	}

	@Test
	void findByUserIdAndMarketReturnsTheMatchingMarketAccount() {
		Optional<TutorialAccount> result = tutorialAccountRepository.findByUserIdAndMarket(user.getId(), Market.STOCK);

		assertThat(result).isPresent();
		assertThat(result.get().getId()).isEqualTo(stockAccount.getId());
		assertThat(result.get().getMarket()).isEqualTo(Market.STOCK);
		assertThat(result.get().getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void findByUserIdAndMarketDistinguishesBetweenTheTwoMarketAccountsOfTheSameUser() {
		Optional<TutorialAccount> result = tutorialAccountRepository.findByUserIdAndMarket(user.getId(), Market.CRYPTO);

		assertThat(result).isPresent();
		assertThat(result.get().getId()).isEqualTo(cryptoAccount.getId());
		assertThat(result.get().getMarket()).isEqualTo(Market.CRYPTO);
	}

	@Test
	void findByUserIdAndMarketReturnsEmptyWhenNoMatchingAccount() {
		Optional<TutorialAccount> result = tutorialAccountRepository.findByUserIdAndMarket(999_999L, Market.STOCK);

		assertThat(result).isEmpty();
	}

	@Test
	void findByUserIdAndMarketForUpdateReturnsTheMatchingMarketAccount() {
		Optional<TutorialAccount> result = tutorialAccountRepository.findByUserIdAndMarketForUpdate(user.getId(),
			Market.CRYPTO);

		assertThat(result).isPresent();
		assertThat(result.get().getId()).isEqualTo(cryptoAccount.getId());
	}

	@Test
	void findByUserIdAndMarketForUpdateReturnsEmptyWhenNoMatchingAccount() {
		Optional<TutorialAccount> result = tutorialAccountRepository.findByUserIdAndMarketForUpdate(999_999L,
			Market.CRYPTO);

		assertThat(result).isEmpty();
	}

	@Test
	void savingDuplicateUserAndMarketFailsWithUniqueConstraint() {
		assertThatThrownBy(() -> tutorialAccountRepository.saveAndFlush(
			TutorialAccount.create(user, Market.STOCK, NOW.plusSeconds(1))))
			.isInstanceOf(DataIntegrityViolationException.class);
	}
}
