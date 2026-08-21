// 실제 MySQL에서 내 정보 조회의 가입 방식 판별·회원 간 격리·민감 필드 미노출을 검증하는 통합 테스트다.
package com.finplay.api.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.SignupMethod;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.dto.response.MemberResponse;
import com.finplay.api.domain.auth.dto.response.SignupTokenResponse;
import com.finplay.api.domain.auth.email.FakeEmailSender;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;
import com.finplay.api.domain.auth.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MeIntegrationTest {

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
	private ObjectMapper objectMapper;

	@Test
	void meReturnsOwnIdEmailNicknameAndSignupMethodEmailAfterEmailSignup() {
		String email = uniqueEmail("me-email");
		String nickname = uniqueNickname("me-email");
		User user = signupWithEmail(email, nickname);

		MemberResponse response = authService.getMe(user.getId());

		assertThat(response.id()).isEqualTo(user.getId());
		assertThat(response.email()).isEqualTo(email);
		assertThat(response.nickname()).isEqualTo(nickname);
		assertThat(response.signupMethod()).isEqualTo(SignupMethod.EMAIL);
	}

	@Test
	void meReturnsSignupMethodKakaoAfterFakeKakaoSignup() {
		User user = signupWithOAuth(OAuthProviderName.KAKAO, "me-kakao");

		MemberResponse response = authService.getMe(user.getId());

		assertThat(response.id()).isEqualTo(user.getId());
		assertThat(response.email()).isEqualTo(user.getEmail());
		assertThat(response.nickname()).isEqualTo(user.getNickname());
		assertThat(response.signupMethod()).isEqualTo(SignupMethod.KAKAO);
	}

	@Test
	void meReturnsSignupMethodNaverAfterFakeNaverSignup() {
		User user = signupWithOAuth(OAuthProviderName.NAVER, "me-naver");

		MemberResponse response = authService.getMe(user.getId());

		assertThat(response.id()).isEqualTo(user.getId());
		assertThat(response.email()).isEqualTo(user.getEmail());
		assertThat(response.nickname()).isEqualTo(user.getNickname());
		assertThat(response.signupMethod()).isEqualTo(SignupMethod.NAVER);
	}

	@Test
	void meIsolatesDifferentUsersFromEachOther() {
		String emailMemberEmail = uniqueEmail("isolation-email");
		String emailMemberNickname = uniqueNickname("isolation-email");
		User emailMember = signupWithEmail(emailMemberEmail, emailMemberNickname);
		User socialMember = signupWithOAuth(OAuthProviderName.KAKAO, "isolation-social");

		MemberResponse emailMemberResponse = authService.getMe(emailMember.getId());
		MemberResponse socialMemberResponse = authService.getMe(socialMember.getId());

		assertThat(emailMemberResponse)
			.isEqualTo(new MemberResponse(
				emailMember.getId(), emailMemberEmail, emailMemberNickname, SignupMethod.EMAIL));
		assertThat(socialMemberResponse)
			.isEqualTo(new MemberResponse(
				socialMember.getId(),
				socialMember.getEmail(),
				socialMember.getNickname(),
				SignupMethod.KAKAO));
		assertThat(emailMemberResponse.id()).isNotEqualTo(socialMemberResponse.id());
	}

	@Test
	void meResponseNeverContainsPasswordHash() {
		String email = uniqueEmail("no-password");
		User user = signupWithEmail(email, uniqueNickname("no-password"));
		String storedPasswordHash = user.getPasswordHash();
		assertThat(storedPasswordHash).isNotBlank();

		String json = objectMapper.writeValueAsString(authService.getMe(user.getId()));

		assertThat(json).doesNotContain(storedPasswordHash).doesNotContain("passwordHash");
		assertThat(objectMapper.<Map<String, Object>>readValue(json, new TypeReference<>() {}))
			.containsOnlyKeys("id", "email", "nickname", "signupMethod");
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

	private User signupWithOAuth(OAuthProviderName provider, String scenario) {
		String email = uniqueEmail(scenario);

		authService.oauthLogin(provider, new OAuthUserDto("provider-" + scenario + "-"
			+ UUID.randomUUID().toString().replace("-", ""), email));

		return userRepository.findByEmail(email).orElseThrow();
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "");
	}
}
