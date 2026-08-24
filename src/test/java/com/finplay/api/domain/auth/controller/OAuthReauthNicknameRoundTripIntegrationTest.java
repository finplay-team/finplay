// Fake OAuth 재인증 인가→콜백 302→reauth-exchange→닉네임 변경까지 전체 왕복을 실제 MySQL·HTTP로 검증한다.
package com.finplay.api.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.entity.SocialAccount;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.repository.SocialAccountRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
class OAuthReauthNicknameRoundTripIntegrationTest {

	private static final String FAKE_PROVIDER_USER_ID = "fake-oauth-user";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private SocialAccountRepository socialAccounts;

	@Autowired
	private AccountService accountService;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private Clock clock;

	@Test
	void reauthorizeCallbackExchangeAndNicknameChangeSucceedEndToEnd() throws Exception {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = users.saveAndFlush(User.create(
			uniqueEmail(), "password-hash", uniqueNickname(), now));
		accountService.createAccountsFor(user);
		socialAccounts.saveAndFlush(
			SocialAccount.create(user, OAuthProviderName.KAKAO, FAKE_PROVIDER_USER_ID, now));
		String accessToken = jwtTokenProvider.issue(user.getId(), user.getRole()).accessToken();

		// 1. 인가 시작 — REAUTH purpose는 인증된 사용자만 부를 수 있다.
		MvcResult authorizeResult = mockMvc.perform(get("/api/auth/oauth/kakao/authorize")
			.param("purpose", "reauth")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.authorizationUri").isNotEmpty())
			.andReturn();
		String authorizationUri = JsonPath.read(
			authorizeResult.getResponse().getContentAsString(), "$.authorizationUri");

		// Fake 인가 URI 자체가 이미 code·state를 실은 콜백 주소다(FakeOAuthAuthorizationProvider).
		MvcResult callbackResult = mockMvc.perform(get(URI.create(authorizationUri)))
			.andExpect(status().isFound())
			.andExpect(header().string(HttpHeaders.LOCATION,
				Matchers.startsWith("https://www.finplay.site/oauth/reauth-callback?code=")))
			.andReturn();
		String exchangeCode = extractQueryParam(
			callbackResult.getResponse().getHeader(HttpHeaders.LOCATION), "code");

		// 3. 콜백이 실어 보낸 1회용 교환 코드를 실제 reauthToken으로 바꾼다.
		MvcResult exchangeResult = mockMvc.perform(post("/api/auth/oauth/reauth-exchange")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"" + exchangeCode + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reauthToken").isNotEmpty())
			.andReturn();
		String reauthToken = JsonPath.read(
			exchangeResult.getResponse().getContentAsString(), "$.reauthToken");

		// 4. 그 reauthToken으로 닉네임을 바꾼다.
		String newNickname = uniqueNickname();
		mockMvc.perform(patch("/api/auth/me/nickname")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"nickname\":\"" + newNickname + "\",\"reauthToken\":\"" + reauthToken + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nickname").value(newNickname));

		assertThat(users.findById(user.getId()).orElseThrow().getNickname()).isEqualTo(newNickname);
	}

	private static String extractQueryParam(String location, String name) {
		String query = URI.create(location).getRawQuery();
		return query.substring((name + "=").length());
	}

	private static String uniqueEmail() {
		return "reauth-roundtrip-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.test";
	}

	private static String uniqueNickname() {
		return "rt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
