package com.finplay.api.domain.auth.oauth.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.domain.auth.oauth.OAuthProviderName;
import com.finplay.api.domain.auth.oauth.OAuthRestClientFactory;
import com.finplay.api.domain.auth.oauth.OAuthUserDto;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
public final class KakaoOAuthCallbackProvider implements OAuthCallbackProvider {

	private static final String TOKEN_ENDPOINT = "https://kauth.kakao.com/oauth/token";
	private static final String USER_INFO_ENDPOINT = "https://kapi.kakao.com/v2/user/me";
	private static final String AUTHORIZATION_CODE_GRANT = "authorization_code";

	private final RestClient restClient;
	private final String clientId;
	private final String clientSecret;
	private final String redirectUri;

	public KakaoOAuthCallbackProvider(
		RestClient.Builder builder,
		@Value("${oauth.kakao.client-id}")
		String clientId,
		@Value("${oauth.kakao.client-secret}")
		String clientSecret,
		@Value("${oauth.kakao.redirect-uri}")
		String redirectUri) {
		this(builder.build(), clientId, clientSecret, redirectUri);
	}

	@Autowired
	public KakaoOAuthCallbackProvider(
		RestClient.Builder builder,
		ObjectProvider<OAuthRestClientFactory> restClientFactoryProvider,
		@Value("${oauth.kakao.client-id}")
		String clientId,
		@Value("${oauth.kakao.client-secret}")
		String clientSecret,
		@Value("${oauth.kakao.redirect-uri}")
		String redirectUri) {
		this(
			restClientFactoryProvider.getIfAvailable(OAuthRestClientFactory::new).create(builder),
			clientId,
			clientSecret,
			redirectUri);
	}

	private KakaoOAuthCallbackProvider(
		RestClient restClient, String clientId, String clientSecret, String redirectUri) {
		this.restClient = restClient;
		this.clientId = requireConfigured(clientId, "카카오 OAuth clientId가 설정되지 않았습니다.");
		this.clientSecret = requireConfigured(clientSecret, "카카오 OAuth clientSecret이 설정되지 않았습니다.");
		this.redirectUri = requireConfigured(redirectUri, "카카오 OAuth redirectUri가 설정되지 않았습니다.");
	}

	@Override
	public boolean supports(OAuthProviderName provider) {
		return provider == OAuthProviderName.KAKAO;
	}

	@Override
	public OAuthUserDto fetchUser(String authorizationCode, String state) {
		String accessToken = exchangeAccessToken(authorizationCode);
		KakaoUserResponse user = fetchUserInfo(accessToken);
		if (user == null || user.id() == null) {
			throw providerError();
		}
		String email = user.kakao_account() == null ? null : user.kakao_account().email();

		return new OAuthUserDto(user.id().toString(), email);
	}

	private String exchangeAccessToken(String authorizationCode) {
		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("grant_type", AUTHORIZATION_CODE_GRANT);
		form.add("client_id", clientId);
		form.add("redirect_uri", redirectUri);
		form.add("code", authorizationCode);
		form.add("client_secret", clientSecret);

		try {
			KakaoTokenResponse token = restClient
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
				.body(KakaoTokenResponse.class);
			if (token == null || isBlank(token.access_token())) {
				throw providerError();
			}
			return token.access_token();
		} catch (BusinessException ex) {
			throw ex;
		} catch (RestClientException ex) {
			throw providerError();
		}
	}

	private KakaoUserResponse fetchUserInfo(String accessToken) {
		try {
			return restClient
				.get()
				.uri(USER_INFO_ENDPOINT)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, response) -> {
					throw providerError();
				})
				.body(KakaoUserResponse.class);
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
	private record KakaoTokenResponse(String access_token) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record KakaoUserResponse(Long id, KakaoAccount kakao_account) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record KakaoAccount(String email) {
	}
}
