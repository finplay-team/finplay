// 네이버 callback 공급자의 token·사용자 HTTP 계약과 오류 정규화를 Mock HTTP로 검증한다.
package com.finplay.api.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
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

class NaverOAuthCallbackProviderTest {

	private static final String CLIENT_ID = "naver-client-id";
	private static final String CLIENT_SECRET = "naver-client-secret";
	private static final String REDIRECT_URI = "https://finplay.example/api/auth/oauth/naver/callback";
	private static final String AUTHORIZATION_CODE = "naver-authorization-code";
	private static final String STATE = "state-value_123";
	private static final String ACCESS_TOKEN = "naver-provider-access-token";

	private MockRestServiceServer server;
	private NaverOAuthCallbackProvider provider;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		provider = new NaverOAuthCallbackProvider(
			builder, CLIENT_ID, CLIENT_SECRET, REDIRECT_URI);
	}

	@Test
	@DisplayName("네이버 token form과 Bearer 사용자 조회를 보내고 extra 필드를 무시해 id와 email을 매핑한다")
	void fetchUserExchangesCodeAndMapsUser() {
		expectSuccessfulTokenExchange();
		server.expect(requestTo("https://openapi.naver.com/v1/nid/me"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andRespond(withSuccess(
				"""
					{
					  "resultcode": "00",
					  "message": "success",
					  "response": {
					    "id": "naver-user-id",
					    "email": "member@naver.example",
					    "name": "ignored"
					  },
					  "unknown": true
					}
					""",
				MediaType.APPLICATION_JSON));

		OAuthUserDto user = provider.fetchUser(AUTHORIZATION_CODE, STATE);

		assertThat(provider.supports(OAuthProviderName.NAVER)).isTrue();
		assertThat(provider.supports(OAuthProviderName.KAKAO)).isFalse();
		assertThat(user)
			.isEqualTo(new OAuthUserDto("naver-user-id", "member@naver.example"));
		server.verify();
	}

	@Test
	@DisplayName("네이버 token HTTP 400은 code와 credential을 노출하지 않는 인가 실패로 정규화한다")
	void tokenBadRequestBecomesAuthorizationFailureWithoutSensitiveValues() {
		server.expect(requestTo("https://nid.naver.com/oauth2.0/token"))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST)
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
					{"error":"invalid_request","error_description":"%s %s %s"}
					""".formatted(AUTHORIZATION_CODE, CLIENT_ID, CLIENT_SECRET)));

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_AUTHORIZATION_FAILED);
		server.verify();
	}

	@Test
	@DisplayName("네이버 token 200 인가 오류 본문은 인가 실패로 정규화한다")
	void tokenAuthorizationErrorBodyBecomesAuthorizationFailure() {
		server.expect(requestTo("https://nid.naver.com/oauth2.0/token"))
			.andRespond(withSuccess(
				"""
					{"error":"invalid_grant","error_description":"expired authorization code"}
					""",
				MediaType.APPLICATION_JSON));

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_AUTHORIZATION_FAILED);
		server.verify();
	}

	@Test
	@DisplayName("네이버 token 200 공급자 오류 본문은 공급자 오류로 정규화한다")
	void tokenServerErrorBodyBecomesProviderError() {
		server.expect(requestTo("https://nid.naver.com/oauth2.0/token"))
			.andRespond(withSuccess(
				"""
					{"error":"temporarily_unavailable","error_description":"retry later"}
					""",
				MediaType.APPLICATION_JSON));

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("네이버 token 5xx와 연결 실패는 공급자 오류로 정규화한다")
	void tokenServerErrorBecomesProviderError() {
		server.expect(requestTo("https://nid.naver.com/oauth2.0/token"))
			.andRespond(withServerError());

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("네이버 token 연결 실패는 공급자 오류로 정규화한다")
	void tokenConnectionFailureBecomesProviderError() {
		server.expect(requestTo("https://nid.naver.com/oauth2.0/token"))
			.andRespond(request -> {
				throw new IOException("connection failed " + AUTHORIZATION_CODE);
			});

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("네이버 token malformed JSON은 공급자 오류로 정규화한다")
	void malformedTokenResponseBecomesProviderError() {
		server.expect(requestTo("https://nid.naver.com/oauth2.0/token"))
			.andRespond(withSuccess("{malformed", MediaType.APPLICATION_JSON));

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("네이버 token 응답 본문이 없어 null이면 공급자 오류로 정규화한다")
	void nullTokenResponseBecomesProviderError() {
		server.expect(requestTo("https://nid.naver.com/oauth2.0/token"))
			.andRespond(withSuccess("", MediaType.APPLICATION_JSON));

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("네이버 token 응답에 access_token이 없으면 공급자 오류로 정규화한다")
	void missingAccessTokenBecomesProviderError() {
		server.expect(requestTo("https://nid.naver.com/oauth2.0/token"))
			.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("네이버 사용자 5xx는 access token을 노출하지 않는 공급자 오류로 정규화한다")
	void userServerErrorBecomesProviderErrorWithoutAccessToken() {
		expectSuccessfulTokenExchange();
		server.expect(requestTo("https://openapi.naver.com/v1/nid/me"))
			.andRespond(withServerError());

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("네이버 사용자 malformed JSON은 공급자 오류로 정규화한다")
	void malformedUserResponseBecomesProviderError() {
		expectSuccessfulTokenExchange();
		server.expect(requestTo("https://openapi.naver.com/v1/nid/me"))
			.andRespond(withSuccess("{malformed", MediaType.APPLICATION_JSON));

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	@Test
	@DisplayName("네이버 사용자 응답의 성공 resultcode와 response.id는 필수다")
	void missingRequiredUserFieldsBecomesProviderError() {
		expectSuccessfulTokenExchange();
		server.expect(requestTo("https://openapi.naver.com/v1/nid/me"))
			.andRespond(withSuccess(
				"""
					{"resultcode":"01","response":{"email":"member@naver.example"}}
					""",
				MediaType.APPLICATION_JSON));

		assertProviderFailure(
			() -> provider.fetchUser(AUTHORIZATION_CODE, STATE),
			ErrorCode.OAUTH_PROVIDER_ERROR);
		server.verify();
	}

	private void expectSuccessfulTokenExchange() {
		server.expect(requestTo("https://nid.naver.com/oauth2.0/token"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(
				HttpHeaders.CONTENT_TYPE,
				MediaType.APPLICATION_FORM_URLENCODED_VALUE))
			.andExpect(content().string(
				"grant_type=authorization_code"
					+ "&client_id=naver-client-id"
					+ "&client_secret=naver-client-secret"
					+ "&code=naver-authorization-code"
					+ "&state=state-value_123"))
			.andRespond(withSuccess(
				"""
					{"access_token":"naver-provider-access-token","token_type":"bearer","expires_in":"3600"}
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
