// 회원가입 영속 계약을 실제 MySQL 쿼리와 제약조건으로 검증하는 슬라이스 테스트다.
package com.finplay.api.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.EmailVerification;
import com.finplay.api.auth.domain.RefreshToken;
import com.finplay.api.auth.domain.User;

import jakarta.persistence.EntityManager;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class SignupPersistenceRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 25, 10, 30, 0);

	@Autowired
	private EmailVerificationRepository emailVerificationRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void consumeValidTokenConsumesVerifiedTokenOnlyOnce() {
		String tokenHash = "verified-token-hash";
		EmailVerification verification = confirmedVerification(
			"user@finplay.com", tokenHash, NOW.plusMinutes(5));
		emailVerificationRepository.saveAndFlush(verification);

		assertThat(emailVerificationRepository.consumeValidToken(tokenHash, NOW)).isEqualTo(1);
		assertThat(emailVerificationRepository.consumeValidToken(tokenHash, NOW.plusSeconds(1))).isZero();

		entityManager.clear();
		EmailVerification consumed = emailVerificationRepository.findByTokenHash(tokenHash).orElseThrow();
		assertThat(consumed.getConsumedAt()).isEqualTo(NOW);
	}

	@Test
	void consumeValidTokenRejectsExpiredAndUnverifiedTokens() {
		String expiredHash = "expired-token-hash";
		EmailVerification expired = confirmedVerification(
			"expired@finplay.com", expiredHash, NOW);
		emailVerificationRepository.save(expired);

		String unverifiedHash = "unverified-token-hash";
		EmailVerification unverified = EmailVerification.create(
			"unverified@finplay.com", "code-hash", NOW.plusMinutes(5), NOW.minusMinutes(1));
		ReflectionTestUtils.setField(unverified, "tokenHash", unverifiedHash);
		ReflectionTestUtils.setField(unverified, "tokenExpiresAt", NOW.plusMinutes(5));
		emailVerificationRepository.saveAndFlush(unverified);

		assertThat(emailVerificationRepository.consumeValidToken(expiredHash, NOW)).isZero();
		assertThat(emailVerificationRepository.consumeValidToken(unverifiedHash, NOW)).isZero();
	}

	@Test
	void refreshTokenPersistsHashAndUserAssociationWithoutRawToken() {
		String rawToken = "raw-refresh-token";
		String tokenHash = "7bcf1a4fa1a17be5a2583fb819616632599027e7eaf9bb9ab85f518501308817";
		User user = userRepository.saveAndFlush(newUser("refresh@finplay.com", "refresh-user"));

		RefreshToken saved = refreshTokenRepository.saveAndFlush(
			RefreshToken.create(user, tokenHash, NOW.plusDays(14), NOW));
		entityManager.clear();

		RefreshToken found = refreshTokenRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getUser().getId()).isEqualTo(user.getId());
		assertThat(found.getTokenHash()).isEqualTo(tokenHash);
		assertThat(found.getTokenHash()).isNotEqualTo(rawToken);
		assertThat(found.getExpiresAt()).isEqualTo(NOW.plusDays(14));
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
		assertThat(found.getRevokedAt()).isNull();

		Number rawTokenRows = (Number)entityManager
			.createNativeQuery("select count(*) from refresh_tokens where token_hash = :tokenHash")
			.setParameter("tokenHash", rawToken)
			.getSingleResult();
		assertThat(rawTokenRows.longValue()).isZero();
	}

	@Test
	void accountMappingPersistsUserMarketAndBalances() {
		User user = userRepository.saveAndFlush(newUser("account@finplay.com", "account-user"));
		accountRepository.saveAll(List.of(
			Account.create(user, Market.STOCK, NOW),
			Account.create(user, Market.CRYPTO, NOW)));
		accountRepository.flush();
		entityManager.clear();

		List<Account> accounts = accountRepository.findAllByUserId(user.getId());

		assertThat(accounts).extracting(Account::getMarket).containsExactlyInAnyOrder(Market.STOCK, Market.CRYPTO);
		assertThat(accounts).allSatisfy(account -> {
			assertThat(account.getUser().getId()).isEqualTo(user.getId());
			assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
			assertThat(account.getSeedMoney()).isEqualTo(10_000_000L);
			assertThat(account.getRealizedPnl()).isZero();
			assertThat(account.getCreatedAt()).isEqualTo(NOW);
			assertThat(account.getUpdatedAt()).isEqualTo(NOW);
		});
	}

	@Test
	void duplicateUserMarketViolatesUniqueConstraint() {
		User user = userRepository.saveAndFlush(newUser("unique@finplay.com", "unique-user"));
		accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));

		assertThatThrownBy(() -> accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private static EmailVerification confirmedVerification(
		String email, String tokenHash, LocalDateTime tokenExpiresAt) {
		EmailVerification verification = EmailVerification.create(
			email, "code-hash", NOW.minusMinutes(1), NOW.minusMinutes(10));
		verification.confirm(NOW.minusMinutes(1), tokenHash, tokenExpiresAt);
		return verification;
	}

	private static User newUser(String email, String nickname) {
		return User.create(email, "password-hash", nickname, NOW);
	}
}
