// 실제 MySQL에 가입한 회원이 같은 자격증명으로 로그인하고 Refresh Token이 해시로만 남는지 검증하는 통합 테스트다.
package com.finplay.api.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.finplay.api.auth.domain.RefreshToken;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.dto.response.SignupTokenResponse;
import com.finplay.api.auth.dto.response.TokenResponse;
import com.finplay.api.auth.email.FakeEmailSender;
import com.finplay.api.auth.repository.RefreshTokenRepository;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class LoginIntegrationTest {

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
	private RefreshTokenRepository refreshTokenRepository;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@BeforeEach
	void clearSentEmails() {
		fakeEmailSender.clear();
	}

	@Test
	void signupThenLoginReturnsTokenPairForSameCredentials() {
		String email = uniqueEmail("success");
		signup(email, uniqueNickname("success"));

		TokenResponse response = authService.login(email, PASSWORD);

		assertThat(response.accessToken()).isNotBlank();
		assertThat(response.refreshToken()).isNotBlank();
		assertThat(response.accessTokenExpiresInSeconds()).isEqualTo(3600L);
		assertThat(response.refreshTokenExpiresInSeconds()).isEqualTo(1_209_600L);
	}

	@Test
	void loginPersistsAdditionalRefreshTokenRowAsSha256Hash() {
		String email = uniqueEmail("refresh");
		long rowsBeforeSignup = refreshTokenRepository.count();
		TokenResponse signupTokens = signup(email, uniqueNickname("refresh"));
		assertThat(refreshTokenRepository.count()).isEqualTo(rowsBeforeSignup + 1);

		TokenResponse loginTokens = authService.login(email, PASSWORD);

		// 가입 1행 + 로그인 1행. 로그인은 행을 추가만 하고 기존 행을 지우지 않는다.
		assertThat(refreshTokenRepository.count()).isEqualTo(rowsBeforeSignup + 2);

		String signupTokenHash = sha256(signupTokens.refreshToken());
		String loginTokenHash = sha256(loginTokens.refreshToken());
		assertThat(refreshTokenRepository.findAll())
			.extracting(RefreshToken::getTokenHash)
			.contains(signupTokenHash, loginTokenHash)
			.doesNotContain(signupTokens.refreshToken(), loginTokens.refreshToken());

		// D9 — 로그인이 기존 Refresh Token을 폐기하지 않는다. 해시가 이 회원의 행을 식별한다(subject에 회원 id가 들어간다).
		assertThat(refreshTokenRepository.findAll())
			.filteredOn(token -> token.getTokenHash().equals(signupTokenHash)
				|| token.getTokenHash().equals(loginTokenHash))
			.hasSizeGreaterThanOrEqualTo(2)
			.allSatisfy(token -> assertThat(token.getRevokedAt()).isNull());
	}

	@Test
	void loginFailsWithUnauthorizedForWrongPassword() {
		String email = uniqueEmail("wrong-pw");
		signup(email, uniqueNickname("wrong-pw"));

		BusinessException failure = catchThrowableOfType(
			BusinessException.class,
			() -> authService.login(email, "wrong-" + PASSWORD));

		assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
	}

	@Test
	void issuedAccessTokenIsAcceptedByJwtTokenProvider() {
		String email = uniqueEmail("access-token");
		signup(email, uniqueNickname("access-token"));
		User user = userRepository.findByEmail(email).orElseThrow();

		TokenResponse response = authService.login(email, PASSWORD);

		assertThat(jwtTokenProvider.parseAccessToken(response.accessToken()))
			.contains(new AuthenticatedUser(user.getId(), "USER"));
		// Refresh Token으로는 보호 API에 접근할 수 없다 (D5).
		assertThat(jwtTokenProvider.parseAccessToken(response.refreshToken())).isEmpty();
	}

	private TokenResponse signup(String email, String nickname) {
		return authService.signup(email, nickname, PASSWORD, issueSignupToken(email));
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
