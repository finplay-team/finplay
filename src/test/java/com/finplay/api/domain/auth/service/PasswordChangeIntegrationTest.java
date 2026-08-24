// 실제 MySQL에서 비밀번호 변경이 새 비밀번호 로그인만 허용하고 요청 기기를 제외한 나머지 기기만 로그아웃시키는지 검증하는 통합 테스트다.
package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.dto.response.TokenResponse;
import com.finplay.api.domain.auth.entity.SocialAccount;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;
import com.finplay.api.domain.auth.repository.SocialAccountRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PasswordChangeIntegrationTest {

	private static final String PASSWORD = "password123";
	private static final String NEW_PASSWORD = "new-password456";

	@Autowired
	private AuthService authService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private SocialAccountRepository socialAccountRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private AccountService accountService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Clock clock;

	@Test
	void changePasswordAllowsLoginWithNewPasswordAndRejectsOldPassword() {
		User user = persistEmailUser("login-switch");

		TokenResponse reissued = authService.changePassword(user.getId(), PASSWORD, NEW_PASSWORD);

		assertThat(reissued.accessToken()).isNotBlank();
		assertThat(reissued.refreshToken()).isNotBlank();

		// 저장되는 값은 원문이 아니라 새 비밀번호로만 대조되는 해시다.
		User reloaded = userRepository.findById(user.getId()).orElseThrow();
		assertThat(reloaded.getPasswordHash()).isNotEqualTo(NEW_PASSWORD);
		assertThat(reloaded.getPasswordHash()).isNotEqualTo(user.getPasswordHash());
		assertThat(passwordEncoder.matches(NEW_PASSWORD, reloaded.getPasswordHash())).isTrue();
		assertThat(passwordEncoder.matches(PASSWORD, reloaded.getPasswordHash())).isFalse();

		assertThat(authService.login(user.getEmail(), NEW_PASSWORD).accessToken()).isNotBlank();

		BusinessException oldPasswordFailure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.login(user.getEmail(), PASSWORD));
		assertThat(oldPasswordFailure.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
	}

	// D5의 핵심 — 폐기가 발급보다 먼저여야 요청 기기가 살아남는다. 단위 테스트의 InOrder는 호출 순서만 잡으므로
	// 실제 벌크 UPDATE가 방금 INSERT된 행까지 폐기하는지는 여기서만 드러난다.
	@Test
	void changePasswordRevokesOtherDeviceRefreshTokensButKeepsIssuedOne() {
		User user = persistEmailUser("multi-device");
		TokenResponse deviceA = authService.login(user.getEmail(), PASSWORD);
		TokenResponse deviceB = authService.login(user.getEmail(), PASSWORD);
		assertThat(activeRefreshTokenHashes(user.getId()))
			.containsExactlyInAnyOrder(sha256(deviceA.refreshToken()), sha256(deviceB.refreshToken()));

		TokenResponse reissued = authService.changePassword(user.getId(), PASSWORD, NEW_PASSWORD);

		// 응답으로 받은 토큰 하나만 활성으로 남는다 — 기기 A·B의 기존 토큰은 폐기됐다.
		assertThat(reissued.refreshToken()).isNotEqualTo(deviceA.refreshToken());
		assertThat(reissued.refreshToken()).isNotEqualTo(deviceB.refreshToken());
		assertThat(activeRefreshTokenHashes(user.getId()))
			.containsExactly(sha256(reissued.refreshToken()));

		assertThat(refreshFailureCode(deviceB.refreshToken())).isEqualTo(ErrorCode.UNAUTHORIZED);
		assertThat(refreshFailureCode(deviceA.refreshToken())).isEqualTo(ErrorCode.UNAUTHORIZED);

		// 요청한 기기는 재로그인 없이 이어서 쓴다 — 새 토큰으로 재발급이 정상 동작해야 한다.
		TokenResponse rotated = authService.refresh(reissued.refreshToken());
		assertThat(rotated.refreshToken()).isNotBlank();
		assertThat(rotated.refreshToken()).isNotEqualTo(reissued.refreshToken());
		assertThat(activeRefreshTokenHashes(user.getId()))
			.containsExactly(sha256(rotated.refreshToken()));
	}

	@Test
	void changePasswordRejectsWrongCurrentPasswordWithoutAnyChange() {
		User user = persistEmailUser("wrong-current");
		TokenResponse device = authService.login(user.getEmail(), PASSWORD);
		String passwordHashBefore = user.getPasswordHash();
		LocalDateTime updatedAtBefore = user.getUpdatedAt();

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.changePassword(user.getId(), "wrong-" + PASSWORD, NEW_PASSWORD));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.REAUTHENTICATION_FAILED);
		assertUserUnchanged(user, passwordHashBefore, updatedAtBefore);

		// 기존 세션은 유지된다 — 실패한 시도가 Refresh Token을 폐기하지 않는다.
		assertThat(activeRefreshTokenHashes(user.getId()))
			.containsExactly(sha256(device.refreshToken()));
		assertThat(authService.refresh(device.refreshToken()).accessToken()).isNotBlank();
	}

	@Test
	void changePasswordRejectsSameNewPasswordWithoutAnyChange() {
		User user = persistEmailUser("same-password");
		TokenResponse device = authService.login(user.getEmail(), PASSWORD);
		String passwordHashBefore = user.getPasswordHash();
		LocalDateTime updatedAtBefore = user.getUpdatedAt();

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.changePassword(user.getId(), PASSWORD, PASSWORD));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
		assertThat(failure.getMessage()).isEqualTo("새 비밀번호는 현재 비밀번호와 달라야 합니다.");
		assertUserUnchanged(user, passwordHashBefore, updatedAtBefore);
		assertThat(activeRefreshTokenHashes(user.getId()))
			.containsExactly(sha256(device.refreshToken()));
	}

	@Test
	void changePasswordRejectsOAuthOnlyUserWithoutAnyChange() {
		OAuthUserDto oauthUser = new OAuthUserDto(
			uniqueProviderUserId("oauth-only"), uniqueEmail("oauth-only"));
		TokenResponse signupTokens = authService.oauthLogin(OAuthProviderName.KAKAO, oauthUser);
		User user = userRepository.findByEmail(oauthUser.email()).orElseThrow();
		String passwordHashBefore = user.getPasswordHash();
		LocalDateTime updatedAtBefore = user.getUpdatedAt();
		SocialAccount linkBefore = socialAccountRepository.findByUserId(user.getId()).orElseThrow();

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.changePassword(user.getId(), PASSWORD, NEW_PASSWORD));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_ERROR);
		assertThat(failure.getMessage()).isEqualTo("OAuth 전용 회원은 비밀번호를 변경할 수 없습니다.");

		// sentinel password_hash·Refresh Token·social_accounts가 모두 그대로여야 한다.
		assertUserUnchanged(user, passwordHashBefore, updatedAtBefore);
		assertThat(activeRefreshTokenHashes(user.getId()))
			.containsExactly(sha256(signupTokens.refreshToken()));
		SocialAccount linkAfter = socialAccountRepository.findByUserId(user.getId()).orElseThrow();
		assertThat(linkAfter.getId()).isEqualTo(linkBefore.getId());
		assertThat(linkAfter.getProvider()).isEqualTo(linkBefore.getProvider());
		assertThat(linkAfter.getProviderUserId()).isEqualTo(linkBefore.getProviderUserId());
	}

	@Test
	void changePasswordKeepsEmailNicknameAndAccountsUnchanged() {
		User user = persistEmailUser("invariance");
		List<AccountSnapshot> accountsBefore = snapshotAccounts(user.getId());
		assertThat(accountsBefore)
			.hasSize(2)
			.extracting(AccountSnapshot::market)
			.containsExactlyInAnyOrder(Market.STOCK, Market.CRYPTO);
		assertThat(accountsBefore).allSatisfy(account -> {
			assertThat(account.cashBalance()).isEqualTo(10_000_000L);
			assertThat(account.seedMoney()).isEqualTo(10_000_000L);
			assertThat(account.realizedPnl()).isZero();
		});

		authService.changePassword(user.getId(), PASSWORD, NEW_PASSWORD);

		User reloaded = userRepository.findById(user.getId()).orElseThrow();
		assertThat(reloaded.getEmail()).isEqualTo(user.getEmail());
		assertThat(reloaded.getNickname()).isEqualTo(user.getNickname());
		assertThat(reloaded.getRole()).isEqualTo(user.getRole());
		assertThat(reloaded.getStatus()).isEqualTo(user.getStatus());
		assertThat(reloaded.getCreatedAt()).isEqualTo(user.getCreatedAt());
		assertThat(snapshotAccounts(user.getId())).isEqualTo(accountsBefore);

		// 실패 경로도 계좌를 건드리지 않는다 — 비밀번호가 이미 바뀌었으므로 같은 요청은 이제 403이다.
		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.changePassword(user.getId(), PASSWORD, NEW_PASSWORD));
		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.REAUTHENTICATION_FAILED);
		assertThat(snapshotAccounts(user.getId())).isEqualTo(accountsBefore);
	}

	private void assertUserUnchanged(
		User user, String passwordHashBefore, LocalDateTime updatedAtBefore) {
		User reloaded = userRepository.findById(user.getId()).orElseThrow();
		assertThat(reloaded.getPasswordHash()).isEqualTo(passwordHashBefore);
		assertThat(reloaded.getUpdatedAt()).isEqualTo(updatedAtBefore);
		assertThat(reloaded.getEmail()).isEqualTo(user.getEmail());
		assertThat(reloaded.getNickname()).isEqualTo(user.getNickname());
	}

	private ErrorCode refreshFailureCode(String refreshToken) {
		return catchThrowableOfType(
			BusinessException.class,
			() -> authService.refresh(refreshToken)).getErrorCode();
	}

	private List<String> activeRefreshTokenHashes(Long userId) {
		return jdbcTemplate.queryForList(
			"select token_hash from refresh_tokens where user_id = ? and revoked_at is null",
			String.class,
			userId);
	}

	// 저장 직후 인스턴스는 LocalDateTime을 나노초까지 들고 있지만 MySQL DATETIME(6)은 마이크로초까지만 남긴다.
	// 불변 단정의 기준값이 DB 정밀도를 갖도록 다시 읽어서 돌려준다.
	private User persistEmailUser(String scenario) {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = userRepository.saveAndFlush(User.create(
			uniqueEmail(scenario), passwordEncoder.encode(PASSWORD), uniqueNickname(scenario), now));
		accountService.createAccountsFor(user);
		return userRepository.findById(user.getId()).orElseThrow();
	}

	private List<AccountSnapshot> snapshotAccounts(Long userId) {
		return accountRepository.findAllByUserId(userId).stream()
			.map(AccountSnapshot::from)
			.sorted(Comparator.comparing(AccountSnapshot::market))
			.toList();
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	private static String uniqueProviderUserId(String scenario) {
		return "provider-" + scenario + "-" + UUID.randomUUID().toString().replace("-", "");
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private record AccountSnapshot(
		Long id,
		Market market,
		long cashBalance,
		long seedMoney,
		long realizedPnl,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {

		private static AccountSnapshot from(Account account) {
			return new AccountSnapshot(
				account.getId(),
				account.getMarket(),
				account.getCashBalance(),
				account.getSeedMoney(),
				account.getRealizedPnl(),
				account.getCreatedAt(),
				account.getUpdatedAt());
		}
	}
}
