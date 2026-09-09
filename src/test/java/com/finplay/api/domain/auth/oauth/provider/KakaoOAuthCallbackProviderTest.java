package com.finplay.api.domain.auth.oauth.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KakaoOAuthCallbackProviderTest {

	private static final String CLIENT_ID = "kakao-client-id";
	private static final String CLIENT_SECRET = "kakao-client-secret";
	private static final String REDIRECT_URI = "https://finplay.example/api/auth/oauth/kakao/callback";
	private static final String AUTHORIZATION_CODE = "kakao-authorization-code";
	private static final String STATE = "state-value_123";
	private static final String ACCESS_TOKEN = "kakao-provider-access-token";

	private MockRestServiceServer server;
	private KakaoOAuthCallbackProvider provider;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		provider = new KakaoOAuthCallbackProvider(builder, CLIENT_ID, CLIENT_SECRET, REDIRECT_URI);
	}

	@Test
	@DisplayName("카카오 token form과 Bearer 사용자 조회를 보내고 extra 필드를 무시해 id와 email을 매핑한다")
	void fetchUserExchangesCodeAndMapsUser() {
		expectSuccessfulTokenExchange();
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andRespond(withSuccess(
				"""
					{
					  "id": 123456789,
					  "connected_at": "2026-07-26T00:00:00Z",
					  "kakao_account": {
					    "email": "member@kakao.example",
					    "is_email_valid": true
					  },
					  "unknown": {"nested": true}
					}
					""",
				MediaType.APPLICATION_JSON));

		OAuthUserDto user = provider.fetchUser(AUTHORIZATION_CODE, STATE);

		assertThat(provider.supports(OAuthProviderName.KAKAO)).isTrue();
		assertThat(provider.supports(OAuthProviderName.NAVER)).isFalse();
		assertThat(user)
			.isEqualTo(new OAuthUserDto("123456789", "member@kakao.example"));
		server.verify();
	}

	@Test
	@DisplayName("카카오 token 400은 code와 credential을 노출하지 않는 인가 실패로 정규화한다")
	void tokenBadRequestBecomesAuthorizationFailureWithoutSensitiveValues() {
		server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
					{"error":"invalid_grant","error_description":"expired %s %s %s"}
					""".formatted(AUTHORIZATION_CODE, CLIENT_ID, CLIENT_SECRET)));

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_AUTHORIZATION_FAILED);
		server.verify();
	}

	@Test
	@DisplayName("카카오 token 5xx는 공급자 오류로 정규화한다")
	void tokenServerErrorBecomesProviderError() {
		server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
			.andRespond(withServerError());

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("카카오 token 연결 실패는 공급자 오류로 정규화한다")
	void tokenConnectionFailureBecomesProviderError() {
		server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
			.andRespond(request -> {
				throw new IOException("connection failed " + AUTHORIZATION_CODE);
			});

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("카카오 token malformed JSON은 공급자 오류로 정규화한다")
	void malformedTokenResponseBecomesProviderError() {
		server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
			.andRespond(withSuccess("{malformed", MediaType.APPLICATION_JSON));

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("카카오 token 응답에 access_token이 없으면 공급자 오류로 정규화한다")
	void missingAccessTokenBecomesProviderError() {
		server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
			.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("카카오 사용자 5xx는 access token을 노출하지 않는 공급자 오류로 정규화한다")
	void userServerErrorBecomesProviderErrorWithoutAccessToken() {
		expectSuccessfulTokenExchange();
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
			.andRespond(withServerError());

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("카카오 사용자 malformed JSON과 필수 id 누락은 공급자 오류로 정규화한다")
	void malformedUserResponseBecomesProviderError() {
		expectSuccessfulTokenExchange();
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
			.andRespond(withSuccess("{malformed", MediaType.APPLICATION_JSON));

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("카카오 사용자 응답의 email은 선택이고 id는 필수다")
	void missingUserIdBecomesProviderError() {
		expectSuccessfulTokenExchange();
		server.expect(requestTo("https://kapi.kakao.com/v2/user/me"))
			.andRespond(withSuccess(
				"""
					{"kakao_account":{"email":"member@kakao.example"}}
					""",
				MediaType.APPLICATION_JSON));

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	private void expectSuccessfulTokenExchange() {
		server.expect(requestTo("https://kauth.kakao.com/oauth/token"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(
				HttpHeaders.CONTENT_TYPE,
				MediaType.APPLICATION_FORM_URLENCODED_VALUE))
			.andExpect(content().string(
				"grant_type=authorization_code"
					+ "&client_id=kakao-client-id"
					+ "&redirect_uri=https%3A%2F%2Ffinplay.example%2Fapi%2Fauth%2Foauth"
					+ "%2Fkakao%2Fcallback"
					+ "&code=kakao-authorization-code"
					+ "&client_secret=kakao-client-secret"))
			.andRespond(withSuccess(
				"""
					{"access_token":"kakao-provider-access-token","token_type":"bearer","expires_in":3600}
					""",
				MediaType.APPLICATION_JSON));
	}

	private static void assertProviderFailure(
		org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation,
		ErrorCode expectedErrorCode) {
		assertThatThrownBy(invocation)
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> {
					assertThat(exception.getErrorCode()).isEqualTo(expectedErrorCode);
					assertThat(exception.getMessage())
						.isEqualTo(expectedErrorCode.getDefaultMessage())
						.doesNotContain(
							AUTHORIZATION_CODE, ACCESS_TOKEN, CLIENT_ID, CLIENT_SECRET);
				});
	}
}
