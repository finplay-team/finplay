// 실제 네이버 OAuth 인가 코드를 사용자 식별자와 이메일로 교환한다.
package com.finplay.api.auth.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@Profile({"prod", "oauth-real"})
public final class NaverOAuthCallbackProvider implements OAuthCallbackProvider {

	private static final String TOKEN_ENDPOINT = "https://nid.naver.com/oauth2.0/token";
	private static final String USER_INFO_ENDPOINT = "https://openapi.naver.com/v1/nid/me";
	private static final String AUTHORIZATION_CODE_GRANT = "authorization_code";
	private static final String SUCCESS_RESULT_CODE = "00";
	private static final String SERVER_ERROR = "server_error";
	private static final String TEMPORARILY_UNAVAILABLE = "temporarily_unavailable";

	private final RestClient restClient;
	private final String clientId;
	private final String clientSecret;

	public NaverOAuthCallbackProvider(
		RestClient.Builder builder,
		@Value("${oauth.naver.client-id}")
		String clientId,
		@Value("${oauth.naver.client-secret}")
		String clientSecret,
		@Value("${oauth.naver.redirect-uri}")
		String redirectUri) {
		this(builder.build(), clientId, clientSecret, redirectUri);
	}

	@Autowired
	public NaverOAuthCallbackProvider(
		RestClient.Builder builder,
		ObjectProvider<OAuthRestClientFactory> restClientFactoryProvider,
		@Value("${oauth.naver.client-id}")
		String clientId,
		@Value("${oauth.naver.client-secret}")
		String clientSecret,
		@Value("${oauth.naver.redirect-uri}")
		String redirectUri) {
		this(
			restClientFactoryProvider.getIfAvailable(OAuthRestClientFactory::new).create(builder),
			clientId,
			clientSecret,
			redirectUri);
	}

	private NaverOAuthCallbackProvider(
		RestClient restClient, String clientId, String clientSecret, String redirectUri) {
		this.restClient = restClient;
		this.clientId = requireConfigured(clientId, "네이버 OAuth clientId가 설정되지 않았습니다.");
		this.clientSecret = requireConfigured(clientSecret, "네이버 OAuth clientSecret이 설정되지 않았습니다.");
		requireConfigured(redirectUri, "네이버 OAuth redirectUri가 설정되지 않았습니다.");
	}

	@Override
	public boolean supports(OAuthProviderName provider) {
		return provider == OAuthProviderName.NAVER;
	}

	@Override
	public OAuthUserDto fetchUser(String authorizationCode, String state) {
		String accessToken = exchangeAccessToken(authorizationCode, state);
		NaverUserResponse user = fetchUserInfo(accessToken);
		if (user == null
			|| !SUCCESS_RESULT_CODE.equals(user.resultcode())
			|| user.response() == null
			|| isBlank(user.response().id())) {
			throw providerError();
		}

		return new OAuthUserDto(user.response().id(), user.response().email());
	}

	private String exchangeAccessToken(String authorizationCode, String state) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", AUTHORIZATION_CODE_GRANT);
		form.add("client_id", clientId);
		form.add("client_secret", clientSecret);
		form.add("code", authorizationCode);
		form.add("state", state);

		try {
			NaverTokenResponse token = restClient
				.post()
				.uri(TOKEN_ENDPOINT)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.onStatus(status -> status.value() == 400, (request, response) -> {
					throw authorizationFailed();
				})
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					throw providerError();
				})
				.body(NaverTokenResponse.class);
			if (token == null) {
				throw providerError();
			}
			if (!isBlank(token.error())) {
				if (SERVER_ERROR.equals(token.error()) || TEMPORARILY_UNAVAILABLE.equals(token.error())) {
					throw providerError();
				}
				throw authorizationFailed();
			}
			String accessToken = token.access_token();
			if (isBlank(accessToken)) {
				throw providerError();
			}
			return accessToken;
		} catch (BusinessException ex) {
			throw ex;
		} catch (RestClientException ex) {
			throw providerError();
		}
	}

	private NaverUserResponse fetchUserInfo(String accessToken) {
		try {
			return restClient
				.get()
				.uri(USER_INFO_ENDPOINT)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					throw providerError();
				})
				.body(NaverUserResponse.class);
		} catch (BusinessException ex) {
			throw ex;
		} catch (RestClientException ex) {
			throw providerError();
		}
	}

	private static String requireConfigured(String value, String message) {
		if (isBlank(value)) {
			throw new IllegalArgumentException(message);
		}
		return value;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static BusinessException authorizationFailed() {
		return new BusinessException(ErrorCode.OAUTH_AUTHORIZATION_FAILED);
	}

	private static BusinessException providerError() {
		return new BusinessException(ErrorCode.OAUTH_PROVIDER_ERROR);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record NaverTokenResponse(String access_token, String error) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record NaverUserResponse(String resultcode, NaverUser response) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record NaverUser(String id, String email) {
	}
}
