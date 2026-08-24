// 실제 MySQL에서 재인증 기반 닉네임 변경이 닉네임만 바꾸고 이메일·계좌·시드머니·잔액을 그대로 두는지 검증하는 통합 테스트다.
package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.dto.response.MemberResponse;
import com.finplay.api.domain.auth.dto.response.ReauthTokenResponse;
import com.finplay.api.domain.auth.dto.response.SignupTokenResponse;
import com.finplay.api.domain.auth.email.FakeEmailSender;
import com.finplay.api.domain.auth.entity.SignupMethod;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NicknameChangeIntegrationTest {

	private static final String PASSWORD = "password123";

	@Autowired
	private AuthService authService;

	@Autowired
	private EmailVerificationService emailVerificationService;

	@Autowired
	private FakeEmailSender fakeEmailSender;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Test
	void changeNicknameUpdatesOnlyNicknameForEmailUserAndKeepsAccountsUnchanged() {
		String email = uniqueEmail("email-success");
		User user = signupWithEmail(email, uniqueNickname("email-success"));
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
		String newNickname = uniqueNickname("email-changed");

		MemberResponse response = authService.changeNickname(user.getId(), newNickname, PASSWORD, null);

		assertThat(response)
			.isEqualTo(new MemberResponse(user.getId(), email, newNickname, SignupMethod.EMAIL));
		User reloaded = userRepository.findById(user.getId()).orElseThrow();
		assertThat(reloaded.getNickname()).isEqualTo(newNickname);
		assertThat(reloaded.getEmail()).isEqualTo(email);
		assertThat(reloaded.getPasswordHash()).isEqualTo(user.getPasswordHash());
		assertThat(snapshotAccounts(user.getId())).isEqualTo(accountsBefore);
	}

	@Test
	void changeNicknameUpdatesOnlyNicknameForOAuthUserAndConsumesReauthTokenOnce() {
		OAuthUserDto oauthUser = new OAuthUserDto(
			uniqueProviderUserId("oauth-success"), uniqueEmail("oauth-success"));
		User user = signupWithOAuth(OAuthProviderName.KAKAO, oauthUser);
		ReauthTokenResponse issued = authService.reauthenticate(
			user.getId(), OAuthProviderName.KAKAO, oauthUser);
		List<AccountSnapshot> accountsBefore = snapshotAccounts(user.getId());
		assertThat(accountsBefore).hasSize(2);
		String newNickname = uniqueNickname("oauth-changed");

		MemberResponse response = authService.changeNickname(
			user.getId(), newNickname, null, issued.reauthToken());

		assertThat(response).isEqualTo(new MemberResponse(
			user.getId(), oauthUser.email(), newNickname, SignupMethod.KAKAO));
		assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname())
			.isEqualTo(newNickname);

		// 같은 토큰을 다시 쓰면 이미 소비돼 거부된다.
		String rejectedNickname = uniqueNickname("oauth-reused");
		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.changeNickname(
				user.getId(), rejectedNickname, null, issued.reauthToken()));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.REAUTHENTICATION_FAILED);
		User reloaded = userRepository.findById(user.getId()).orElseThrow();
		assertThat(reloaded.getNickname()).isEqualTo(newNickname);
		assertThat(reloaded.getEmail()).isEqualTo(oauthUser.email());
		assertThat(snapshotAccounts(user.getId())).isEqualTo(accountsBefore);
	}

	@Test
	void changeNicknameRollsBackReauthTokenConsumptionWhenNicknameSaveFailsThenSameTokenSucceeds() {
		OAuthUserDto oauthUser = new OAuthUserDto(
			uniqueProviderUserId("oauth-rollback"), uniqueEmail("oauth-rollback"));
		User user = signupWithOAuth(OAuthProviderName.KAKAO, oauthUser);
		String duplicateNickname = uniqueNickname("oauth-rollback-taken");
		signupWithEmail(uniqueEmail("oauth-rollback-other"), duplicateNickname);
		ReauthTokenResponse issued = authService.reauthenticate(
			user.getId(), OAuthProviderName.KAKAO, oauthUser);

		// 이미 다른 회원이 쓰는 닉네임이라 저장 단계에서 실패해야 한다 — 이때 토큰 소비도 함께 롤백된다.
		BusinessException firstFailure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.changeNickname(
				user.getId(), duplicateNickname, null, issued.reauthToken()));
		assertThat(firstFailure.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname())
			.isEqualTo(user.getNickname());

		// 같은 토큰이 아직 유효해야(롤백 증명) 새 닉네임으로 재시도가 성공한다.
		String newNickname = uniqueNickname("oauth-rollback-changed");
		MemberResponse response = authService.changeNickname(
			user.getId(), newNickname, null, issued.reauthToken());

		assertThat(response.nickname()).isEqualTo(newNickname);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname())
			.isEqualTo(newNickname);
	}

	@Test
	void changeNicknameRejectsWrongPasswordWithoutAnyChange() {
		String email = uniqueEmail("wrong-password");
		String nickname = uniqueNickname("wrong-password");
		User user = signupWithEmail(email, nickname);
		List<AccountSnapshot> accountsBefore = snapshotAccounts(user.getId());

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.changeNickname(
				user.getId(), uniqueNickname("never-applied"), "wrong-" + PASSWORD, null));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.REAUTHENTICATION_FAILED);
		User reloaded = userRepository.findById(user.getId()).orElseThrow();
		assertThat(reloaded.getNickname()).isEqualTo(nickname);
		assertThat(reloaded.getEmail()).isEqualTo(email);
		assertThat(snapshotAccounts(user.getId())).isEqualTo(accountsBefore);
	}

	@Test
	void changeNicknameRejectsDuplicateNicknameWithoutAnyChange() {
		String ownNickname = uniqueNickname("duplicate-own");
		User user = signupWithEmail(uniqueEmail("duplicate-own"), ownNickname);
		String otherNickname = uniqueNickname("duplicate-other");
		User otherUser = signupWithEmail(uniqueEmail("duplicate-other"), otherNickname);
		List<AccountSnapshot> accountsBefore = snapshotAccounts(user.getId());

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.changeNickname(user.getId(), otherNickname, PASSWORD, null));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
		assertThat(userRepository.findById(user.getId()).orElseThrow().getNickname())
			.isEqualTo(ownNickname);
		assertThat(userRepository.findById(otherUser.getId()).orElseThrow().getNickname())
			.isEqualTo(otherNickname);
		assertThat(snapshotAccounts(user.getId())).isEqualTo(accountsBefore);
	}

	private User signupWithEmail(String email, String nickname) {
		fakeEmailSender.clear();
		emailVerificationService.sendVerificationCode(email);
		FakeEmailSender.SentEmail sentEmail = fakeEmailSender.getLastSentEmail();
		SignupTokenResponse verified = emailVerificationService.confirmVerificationCode(
			email, sentEmail.code());

		authService.signup(email, nickname, PASSWORD, verified.signupVerificationToken());

		return userRepository.findByEmail(email).orElseThrow();
	}

	private User signupWithOAuth(OAuthProviderName provider, OAuthUserDto oauthUser) {
		authService.oauthLogin(provider, oauthUser);
		return userRepository.findByEmail(oauthUser.email()).orElseThrow();
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
