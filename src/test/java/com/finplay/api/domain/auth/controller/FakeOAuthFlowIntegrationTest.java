// Fake authorize부터 callback·JWT·MySQL 영속까지 OAuth 핵심 흐름을 자동 통합 검증한다.
package com.finplay.api.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.repository.RefreshTokenRepository;
import com.finplay.api.domain.auth.repository.SocialAccountRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.market.entity.Market;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class FakeOAuthFlowIntegrationTest {

	private static final String FAKE_EMAIL = "fake-oauth@finplay.test";
	private static final String EXISTING_EMAIL = "existing-oauth@finplay.test";
	private static final String FAKE_PROVIDER_USER_ID = "fake-oauth-user";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository users;

	@Autowired
	private SocialAccountRepository socialAccounts;

	@Autowired
	private AccountRepository accounts;

	@Autowired
	private RefreshTokenRepository refreshTokens;

	@Autowired
	private JwtTokenProvider jwtTokenProvider;

	@Autowired
	private Clock clock;

	@ParameterizedTest
	@ValueSource(strings = {"kakao", "naver"})
	void authorizeThenCallbackCreatesUserAndSecondFlowLogsIntoSameUser(String provider)
		throws Exception {
		OAuthProviderName providerName = OAuthProviderName.valueOf(provider.toUpperCase(java.util.Locale.ROOT));
		Authorization authorization = authorize(provider);

		MvcResult firstCallback = callback(
			provider,
			authorization.code(),
			authorization.state(),
			authorization.cookie());
		String accessToken = JsonPath.read(
			firstCallback.getResponse().getContentAsString(), "$.accessToken");

		User user = users.findByEmail(FAKE_EMAIL).orElseThrow();
		assertThat(jwtTokenProvider.parseAccessToken(accessToken))
			.contains(new AuthenticatedUser(user.getId(), "USER"));
		assertThat(socialAccounts.findByProviderAndProviderUserId(
			providerName, FAKE_PROVIDER_USER_ID)).isPresent();
		assertThat(accounts.findAllByUserId(user.getId()))
			.hasSize(2)
			.extracting(account -> account.getMarket())
			.containsExactlyInAnyOrder(Market.STOCK, Market.CRYPTO);
		assertThat(accounts.findAllByUserId(user.getId())).allSatisfy(account -> {
			assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
			assertThat(account.getSeedMoney()).isEqualTo(10_000_000L);
		});
		long userCount = users.count();
		long socialCount = socialAccounts.count();
		long accountCount = accounts.count();
		long refreshCount = refreshTokens.count();

		mockMvc.perform(get("/api/auth/oauth/{provider}/callback", provider)
			.param("code", authorization.code())
			.param("state", authorization.state())
			.cookie(authorization.cookie()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code")
				.value("OAUTH_AUTHORIZATION_FAILED"))
			.andExpect(header().string(
				HttpHeaders.SET_COOKIE,
				Matchers.containsString("; Max-Age=0")));
		assertThat(users.count()).isEqualTo(userCount);
		assertThat(socialAccounts.count()).isEqualTo(socialCount);
		assertThat(accounts.count()).isEqualTo(accountCount);
		assertThat(refreshTokens.count()).isEqualTo(refreshCount);

		Authorization secondAuthorization = authorize(provider);
		assertThat(secondAuthorization.code()).isNotEqualTo(authorization.code());
		callback(
			provider,
			secondAuthorization.code(),
			secondAuthorization.state(),
			secondAuthorization.cookie());

		assertThat(users.count()).isEqualTo(userCount);
		assertThat(socialAccounts.count()).isEqualTo(socialCount);
		assertThat(accounts.count()).isEqualTo(accountCount);
		assertThat(refreshTokens.count()).isEqualTo(refreshCount + 1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"fake-code-no-email", "fake-code-existing-email"})
	void fakeFailureCodesReturnExpectedErrorWithoutOAuthPersistence(String fakeCode)
		throws Exception {
		if (fakeCode.equals("fake-code-existing-email")) {
			users.saveAndFlush(User.create(
				EXISTING_EMAIL,
				"password-hash",
				"regular-" + UUID.randomUUID().toString().replace("-", ""),
				LocalDateTime.now(clock)));
		}
		Counts before = counts();
		Authorization authorization = authorize("kakao");

		mockMvc.perform(get("/api/auth/oauth/kakao/callback")
			.param("code", fakeCode)
			.param("state", authorization.state())
			.cookie(authorization.cookie()))
			.andExpect(status().is(
				fakeCode.equals("fake-code-no-email") ? 400 : 409))
			.andExpect(jsonPath("$.error.code").value(
				fakeCode.equals("fake-code-no-email")
					? "OAUTH_EMAIL_REQUIRED"
					: "ACCOUNT_LINK_REQUIRED"))
			.andExpect(header().string(
				HttpHeaders.SET_COOKIE,
				Matchers.allOf(
					Matchers.containsString(
						"; Path=/api/auth/oauth/kakao/callback"),
					Matchers.containsString("; Max-Age=0"))));

		assertThat(counts()).isEqualTo(before);
	}

	@Test
	void missingAndMismatchedStateReturnValidationErrorAndExpireCookie() throws Exception {
		Authorization authorization = authorize("naver");
		Counts before = counts();

		mockMvc.perform(get("/api/auth/oauth/naver/callback")
			.param("code", authorization.code())
			.param("state", authorization.state()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(header().string(
				HttpHeaders.SET_COOKIE, Matchers.containsString("; Max-Age=0")));

		mockMvc.perform(get("/api/auth/oauth/naver/callback")
			.param("code", authorization.code())
			.param("state", "different-state")
			.cookie(authorization.cookie()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(header().string(
				HttpHeaders.SET_COOKIE,
				Matchers.allOf(
					Matchers.containsString(
						"; Path=/api/auth/oauth/naver/callback"),
					Matchers.containsString("; Max-Age=0"))));

		assertThat(counts()).isEqualTo(before);
	}

	private Authorization authorize(String provider) throws Exception {
		MvcResult result = mockMvc.perform(
			get("/api/auth/oauth/{provider}/authorize", provider))
			.andExpect(status().isFound())
			.andExpect(header().exists(HttpHeaders.LOCATION))
			.andExpect(header().string(
				HttpHeaders.SET_COOKIE,
				Matchers.containsString("oauth_state=")))
			.andReturn();
		URI location = URI.create(result.getResponse().getHeader(HttpHeaders.LOCATION));
		Map<String, String> query = queryParameters(location);
		String cookieHeader = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		String cookieState = cookieHeader.substring(
			"oauth_state=".length(), cookieHeader.indexOf(';'));
		assertThat(location.getPath())
			.isEqualTo("/api/auth/oauth/" + provider + "/callback");
		assertThat(query.get("state")).isEqualTo(cookieState);
		Cookie cookie = new Cookie("oauth_state", cookieState);
		cookie.setPath(location.getPath());
		return new Authorization(query.get("code"), query.get("state"), cookie);
	}

	// LOGIN 성공은 이제 토큰을 바로 주지 않고 프론트 콜백 주소로 302 리다이렉트하며 1회용 교환 코드만 싣는다
	// (docs/api/auth.md OAuth callback). 그 코드로 login-exchange를 호출해야 실제 토큰을 받는다.
	private MvcResult callback(
		String provider, String code, String state, Cookie cookie) throws Exception {
		MvcResult redirected = mockMvc.perform(get("/api/auth/oauth/{provider}/callback", provider)
			.param("code", code)
			.param("state", state)
			.cookie(cookie))
			.andExpect(status().isFound())
			.andExpect(header().string(HttpHeaders.LOCATION,
				Matchers.startsWith("https://www.finplay.site/oauth/callback?code=")))
			.andExpect(header().string(
				HttpHeaders.SET_COOKIE,
				Matchers.allOf(
					Matchers.containsString(
						"; Path=/api/auth/oauth/" + provider + "/callback"),
					Matchers.containsString("; Max-Age=0"))))
			.andReturn();

		Map<String, String> query = queryParameters(
			URI.create(redirected.getResponse().getHeader(HttpHeaders.LOCATION)));

		return mockMvc.perform(post("/api/auth/oauth/login-exchange")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"" + query.get("code") + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isNotEmpty())
			.andExpect(jsonPath("$.refreshToken").isNotEmpty())
			.andReturn();
	}

	private Counts counts() {
		return new Counts(
			users.count(), socialAccounts.count(), accounts.count(), refreshTokens.count());
	}

	private static Map<String, String> queryParameters(URI uri) {
		return Arrays.stream(uri.getRawQuery().split("&"))
			.map(parameter -> parameter.split("=", 2))
			.collect(Collectors.toMap(
				parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
				parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
	}

	private record Authorization(String code, String state, Cookie cookie) {
	}

	private record Counts(long users, long socials, long accounts, long refreshTokens) {
	}
}
