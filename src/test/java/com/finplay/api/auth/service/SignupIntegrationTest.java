// 실제 MySQL·Redis와 인증번호 흐름으로 회원가입의 원자성과 영속 결과를 검증하는 통합 테스트다.
package com.finplay.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.RefreshToken;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.dto.response.SignupTokenResponse;
import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.email.FakeEmailSender;
import com.finplay.api.auth.repository.RefreshTokenRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SignupIntegrationTest {

	private static final String PASSWORD = "password123";

	@Autowired
	private EmailVerificationService emailVerificationService;

	@Autowired
	private AuthService authService;

	@Autowired
	private FakeEmailSender fakeEmailSender;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private RefreshTokenRepository refreshTokenRepository;

	@BeforeEach
	void clearSentEmails() {
		fakeEmailSender.clear();
	}

	@Test
	void signupCreatesOneUserAndTwoSeededAccounts() {
		String email = uniqueEmail("success");
		String nickname = uniqueNickname("success");
		String signupToken = issueSignupToken(email);

		TokenResponse response = authService.signup(email, nickname, PASSWORD, signupToken);

		assertThat(response.accessToken()).isNotBlank();
		assertThat(response.refreshToken()).isNotBlank();
		assertThat(response.accessTokenExpiresInSeconds()).isEqualTo(3600L);
		assertThat(response.refreshTokenExpiresInSeconds()).isEqualTo(1_209_600L);

		User user = userRepository.findByEmail(email).orElseThrow();
		assertThat(userRepository.findAll()).filteredOn(candidate -> candidate.getEmail().equals(email)).hasSize(1);
		assertThat(accountRepository.findAllByUserId(user.getId()))
			.hasSize(2)
			.extracting(account -> account.getMarket())
			.containsExactlyInAnyOrder(Market.STOCK, Market.CRYPTO);
		assertThat(accountRepository.findAllByUserId(user.getId())).allSatisfy(account -> {
			assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
			assertThat(account.getSeedMoney()).isEqualTo(10_000_000L);
			assertThat(account.getRealizedPnl()).isZero();
		});
	}

	@Test
	void reusingSignupTokenReturnsEmailVerificationRequired() {
		String email = uniqueEmail("reused");
		String nickname = uniqueNickname("reused");
		String signupToken = issueSignupToken(email);
		authService.signup(email, nickname, PASSWORD, signupToken);
		long userCountBeforeReplay = userRepository.count();
		long accountCountBeforeReplay = accountRepository.count();

		BusinessException replayFailure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.signup(email, nickname, PASSWORD, signupToken));

		assertThat(userRepository.count()).isEqualTo(userCountBeforeReplay);
		assertThat(accountRepository.count()).isEqualTo(accountCountBeforeReplay);
		User firstSignupUser = userRepository.findByEmail(email).orElseThrow();
		assertThat(firstSignupUser.getNickname()).isEqualTo(nickname);
		assertThat(accountRepository.findAllByUserId(firstSignupUser.getId())).hasSize(2);
		assertThat(replayFailure.getErrorCode()).isEqualTo(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
	}

	@Test
	void duplicateNicknameFailureLeavesTokenReusable() {
		String duplicateNickname = uniqueNickname("duplicate");
		String existingEmail = uniqueEmail("existing");
		authService.signup(
			existingEmail,
			duplicateNickname,
			PASSWORD,
			issueSignupToken(existingEmail));

		String retryEmail = uniqueEmail("retry");
		String reusableToken = issueSignupToken(retryEmail);

		assertThatThrownBy(() ->
			authService.signup(retryEmail, duplicateNickname, PASSWORD, reusableToken))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
		assertThat(userRepository.findByEmail(retryEmail)).isEmpty();

		String replacementNickname = uniqueNickname("replacement");
		TokenResponse retried = authService.signup(
			retryEmail, replacementNickname, PASSWORD, reusableToken);

		assertThat(retried.accessToken()).isNotBlank();
		User retriedUser = userRepository.findByEmail(retryEmail).orElseThrow();
		assertThat(retriedUser.getNickname()).isEqualTo(replacementNickname);
		assertThat(accountRepository.findAllByUserId(retriedUser.getId())).hasSize(2);
	}

	@Test
	void refreshTokenIsPersistedOnlyAsSha256Hash() {
		String email = uniqueEmail("refresh");
		String signupToken = issueSignupToken(email);

		TokenResponse response = authService.signup(
			email, uniqueNickname("refresh"), PASSWORD, signupToken);

		assertThat(refreshTokenRepository.findAll())
			.extracting(RefreshToken::getTokenHash)
			.contains(sha256(response.refreshToken()))
			.doesNotContain(response.refreshToken());
	}

	private String issueSignupToken(String email) {
		fakeEmailSender.clear();
		emailVerificationService.sendVerificationCode(email);
		FakeEmailSender.SentEmail sentEmail = fakeEmailSender.getLastSentEmail();
		assertThat(sentEmail).isNotNull();
		assertThat(sentEmail.toEmail()).isEqualTo(email);

		SignupTokenResponse response = emailVerificationService.confirmVerificationCode(
			email, sentEmail.code());
		assertThat(response.signupVerificationToken()).isNotBlank();
		return response.signupVerificationToken();
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "");
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
