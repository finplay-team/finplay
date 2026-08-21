// Fake OAuth 재인증 callback이 실제 MySQL에서 회원·계좌·시드머니 불변과 reauth_tokens 1행 추가를 검증한다.
package com.finplay.api.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.auth.entity.SocialAccount;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.exchange.FakeOAuthGrantStore;
import com.finplay.api.domain.auth.oauth.state.OAuthPurpose;
import com.finplay.api.domain.auth.oauth.state.OAuthStateGenerator;
import com.finplay.api.domain.auth.repository.ReauthTokenRepository;
import com.finplay.api.domain.auth.repository.RefreshTokenRepository;
import com.finplay.api.domain.auth.repository.SocialAccountRepository;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
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
class OAuthReauthCallbackIntegrationTest {

	private static final String FAKE_PROVIDER_USER_ID = "fake-oauth-user";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OAuthStateGenerator stateGenerator;

	@Autowired
	private FakeOAuthGrantStore grantStore;

	@Autowired
	private UserRepository users;

	@Autowired
	private SocialAccountRepository socialAccounts;

	@Autowired
	private AccountRepository accounts;

	@Autowired
	private AccountService accountService;

	@Autowired
	private RefreshTokenRepository refreshTokens;

	@Autowired
	private ReauthTokenRepository reauthTokens;

	@Autowired
	private Clock clock;

	@Test
	void reauthCallbackSucceedsAndLeavesUserAccountAndSeedMoneyUnchanged() throws Exception {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = users.saveAndFlush(User.create(
			uniqueEmail(), "password-hash", uniqueNickname(), now));
		accountService.createAccountsFor(user);
		socialAccounts.saveAndFlush(
			SocialAccount.create(user, OAuthProviderName.KAKAO, FAKE_PROVIDER_USER_ID, now));

		String state = stateGenerator.generate(OAuthPurpose.REAUTH, user.getId());
		String code = grantStore.issue(OAuthProviderName.KAKAO, state);

		List<Account> accountsBefore = accounts.findAllByUserId(user.getId());
		long userCount = users.count();
		long socialCount = socialAccounts.count();
		long accountCount = accounts.count();
		long refreshCount = refreshTokens.count();
		long reauthCount = reauthTokens.count();

		MvcResult callbackResult = mockMvc.perform(get("/api/auth/oauth/kakao/callback")
			.param("code", code)
			.param("state", state)
			.cookie(new Cookie("oauth_state", state)))
			.andExpect(status().isFound())
			.andExpect(header().string(HttpHeaders.LOCATION,
				Matchers.startsWith("https://www.finplay.site/oauth/reauth-callback?code=")))
			.andExpect(header().string(
				HttpHeaders.SET_COOKIE,
				Matchers.containsString("; Path=/api/auth/oauth/kakao/callback")))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.containsString("; Max-Age=0")))
			.andReturn();
		String exchangeCode = extractExchangeCode(callbackResult.getResponse().getHeader(HttpHeaders.LOCATION));

		String responseBody = mockMvc.perform(post("/api/auth/oauth/reauth-exchange")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"code\":\"" + exchangeCode + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.reauthToken").isNotEmpty())
			.andExpect(jsonPath("$.expiresInSeconds").value(300))
			.andReturn()
			.getResponse()
			.getContentAsString();
		LocalDateTime afterRequest = LocalDateTime.now(clock);
		String reauthToken = JsonPath.read(responseBody, "$.reauthToken");

		User reloadedUser = users.findById(user.getId()).orElseThrow();
		assertThat(reloadedUser.getEmail()).isEqualTo(user.getEmail());
		assertThat(reloadedUser.getNickname()).isEqualTo(user.getNickname());
		assertThat(users.count()).isEqualTo(userCount);
		assertThat(socialAccounts.count()).isEqualTo(socialCount);
		assertThat(accounts.count()).isEqualTo(accountCount);
		assertThat(refreshTokens.count()).isEqualTo(refreshCount);
		assertThat(accounts.findAllByUserId(user.getId()))
			.usingRecursiveFieldByFieldElementComparator()
			.containsExactlyInAnyOrderElementsOf(accountsBefore);
		assertThat(accounts.findAllByUserId(user.getId())).allSatisfy(account -> {
			assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
			assertThat(account.getSeedMoney()).isEqualTo(10_000_000L);
		});

		assertThat(reauthTokens.count()).isEqualTo(reauthCount + 1);
		var savedReauthToken = reauthTokens.findAll().stream()
			.filter(token -> token.getUser().getId().equals(user.getId()))
			.findFirst()
			.orElseThrow();
		assertThat(savedReauthToken.getTokenHash()).isNotEqualTo(reauthToken);
		assertThat(savedReauthToken.getConsumedAt()).isNull();
		// 실제(고정되지 않은) 시스템 클럭이므로 요청 전후 시각 사이 5분 뒤 구간으로만 검증한다.
		assertThat(savedReauthToken.getExpiresAt())
			.isAfterOrEqualTo(now.plusMinutes(5))
			.isBeforeOrEqualTo(afterRequest.plusMinutes(5));
	}

	@Test
	void reauthCallbackRejectsDifferentAccountAndLeavesEverythingUnchanged() throws Exception {
		LocalDateTime now = LocalDateTime.now(clock);
		User requestingUser = users.saveAndFlush(User.create(
			uniqueEmail(), "password-hash", uniqueNickname(), now));
		accountService.createAccountsFor(requestingUser);
		User otherOwner = users.saveAndFlush(User.create(
			uniqueEmail(), "password-hash", uniqueNickname(), now));
		accountService.createAccountsFor(otherOwner);
		// FAKE_PROVIDER_USER_ID는 요청 사용자가 아니라 다른 회원에게 연결되어 있다.
		socialAccounts.saveAndFlush(
			SocialAccount.create(otherOwner, OAuthProviderName.KAKAO, FAKE_PROVIDER_USER_ID, now));

		String state = stateGenerator.generate(OAuthPurpose.REAUTH, requestingUser.getId());
		String code = grantStore.issue(OAuthProviderName.KAKAO, state);

		assertReauthRejectedWithoutSideEffects(code, state);
	}

	@Test
	void reauthCallbackRejectsUnlinkedProviderAndLeavesEverythingUnchanged() throws Exception {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = users.saveAndFlush(User.create(
			uniqueEmail(), "password-hash", uniqueNickname(), now));
		accountService.createAccountsFor(user);
		// 이 회원은 KAKAO SocialAccount를 전혀 연결하지 않았다.

		String state = stateGenerator.generate(OAuthPurpose.REAUTH, user.getId());
		String code = grantStore.issue(OAuthProviderName.KAKAO, state);

		assertReauthRejectedWithoutSideEffects(code, state);
	}

	@Test
	void reauthCallbackRejectsTamperedStateBeforeConsumingGrantOrChangingData() throws Exception {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = users.saveAndFlush(User.create(
			uniqueEmail(), "password-hash", uniqueNickname(), now));
		accountService.createAccountsFor(user);
		socialAccounts.saveAndFlush(
			SocialAccount.create(user, OAuthProviderName.KAKAO, FAKE_PROVIDER_USER_ID, now));

		String validState = stateGenerator.generate(OAuthPurpose.REAUTH, user.getId());
		String code = grantStore.issue(OAuthProviderName.KAKAO, validState);
		String[] parts = validState.split("\\.");
		String tamperedState = parts[0] + "." + new StringBuilder(parts[1]).reverse();

		long userCount = users.count();
		long socialCount = socialAccounts.count();
		long accountCount = accounts.count();
		long reauthCount = reauthTokens.count();

		mockMvc.perform(get("/api/auth/oauth/kakao/callback")
			.param("code", code)
			.param("state", tamperedState)
			.cookie(new Cookie("oauth_state", tamperedState)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_FAILED"))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.containsString("; Max-Age=0")));

		assertThat(users.count()).isEqualTo(userCount);
		assertThat(socialAccounts.count()).isEqualTo(socialCount);
		assertThat(accounts.count()).isEqualTo(accountCount);
		assertThat(reauthTokens.count()).isEqualTo(reauthCount);

		// state 검증이 fetchUser보다 먼저 실패했다면 grant는 아직 소비되지 않아, 원래의 정상 state로는 성공해야 한다.
		mockMvc.perform(get("/api/auth/oauth/kakao/callback")
			.param("code", code)
			.param("state", validState)
			.cookie(new Cookie("oauth_state", validState)))
			.andExpect(status().isFound())
			.andExpect(header().string(HttpHeaders.LOCATION,
				Matchers.startsWith("https://www.finplay.site/oauth/reauth-callback?code=")));
		assertThat(reauthTokens.count()).isEqualTo(reauthCount + 1);
	}

	@Test
	void reauthCallbackSucceedsWithoutOauthStateCookie() throws Exception {
		LocalDateTime now = LocalDateTime.now(clock);
		User user = users.saveAndFlush(User.create(
			uniqueEmail(), "password-hash", uniqueNickname(), now));
		accountService.createAccountsFor(user);
		socialAccounts.saveAndFlush(
			SocialAccount.create(user, OAuthProviderName.KAKAO, FAKE_PROVIDER_USER_ID, now));

		String state = stateGenerator.generate(OAuthPurpose.REAUTH, user.getId());
		String code = grantStore.issue(OAuthProviderName.KAKAO, state);

		// oauth_state 쿠키를 아예 보내지 않는다 — REAUTH는 쿠키 이중제출에 의존하지 않는다(spec 039 OAUTH-REAUTH-002).
		mockMvc.perform(get("/api/auth/oauth/kakao/callback")
			.param("code", code)
			.param("state", state))
			.andExpect(status().isFound())
			.andExpect(header().string(HttpHeaders.LOCATION,
				Matchers.startsWith("https://www.finplay.site/oauth/reauth-callback?code=")));
	}

	private static String extractExchangeCode(String location) {
		String query = URI.create(location).getRawQuery();
		return query.substring("code=".length());
	}

	private void assertReauthRejectedWithoutSideEffects(String code, String state) throws Exception {
		long userCount = users.count();
		long socialCount = socialAccounts.count();
		long accountCount = accounts.count();
		long refreshCount = refreshTokens.count();
		long reauthCount = reauthTokens.count();

		mockMvc.perform(get("/api/auth/oauth/kakao/callback")
			.param("code", code)
			.param("state", state)
			.cookie(new Cookie("oauth_state", state)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_FAILED"))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, Matchers.containsString("; Max-Age=0")));

		assertThat(users.count()).isEqualTo(userCount);
		assertThat(socialAccounts.count()).isEqualTo(socialCount);
		assertThat(accounts.count()).isEqualTo(accountCount);
		assertThat(refreshTokens.count()).isEqualTo(refreshCount);
		assertThat(reauthTokens.count()).isEqualTo(reauthCount);
	}

	private static String uniqueEmail() {
		return "reauth-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.test";
	}

	private static String uniqueNickname() {
		return "reauth-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
