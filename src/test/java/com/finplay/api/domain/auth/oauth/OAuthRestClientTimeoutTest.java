// 짧은 OAuth HTTP timeout이 지연 응답을 공급자 오류로 정규화하는지 검증한다.
package com.finplay.api.domain.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.finplay.api.domain.auth.oauth.provider.KakaoOAuthCallbackProvider;
import com.finplay.api.domain.auth.oauth.provider.NaverOAuthCallbackProvider;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestClient;

class OAuthRestClientTimeoutTest {

	private HttpServer server;
	private ExecutorService executor;
	private URI serverBaseUri;

	@BeforeEach
	void startDelayedServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "oauth-timeout-test-server");
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(executor);
		server.createContext("/token", exchange -> {
			try {
				Thread.sleep(500);
				byte[] body = "{\"access_token\":\"provider-access-token\"}"
					.getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			} finally {
				exchange.close();
			}
		});
		server.createContext("/kakao-user", exchange -> respondJson(
			exchange,
			"{\"id\":12345,\"kakao_account\":{\"email\":\"member@kakao.example\"}}"));
		server.createContext("/naver-user", exchange -> respondJson(
			exchange,
			"{\"resultcode\":\"00\",\"response\":{\"id\":\"naver-id\",\"email\":\"member@naver.example\"}}"));
		server.start();
		serverBaseUri = URI.create(
			"http://127.0.0.1:" + server.getAddress().getPort());
	}

	@AfterEach
	void stopDelayedServer() {
		server.stop(0);
		executor.shutdownNow();
	}

	@Test
	void kakaoMapsDelayedTokenResponseToProviderError() {
		RestClient.Builder builder = rewritingBuilder();
		KakaoOAuthCallbackProvider provider = new KakaoOAuthCallbackProvider(
			builder,
			factoryProvider(Duration.ofMillis(50)),
			"kakao-client-id",
			"kakao-client-secret",
			"https://finplay.example/api/auth/oauth/kakao/callback");

		assertProviderError(() -> provider.fetchUser("authorization-code", "state"));
	}

	@Test
	void naverMapsDelayedTokenResponseToProviderError() {
		RestClient.Builder builder = rewritingBuilder();
		NaverOAuthCallbackProvider provider = new NaverOAuthCallbackProvider(
			builder,
			factoryProvider(Duration.ofMillis(50)),
			"naver-client-id",
			"naver-client-secret",
			"https://finplay.example/api/auth/oauth/naver/callback");

		assertProviderError(() -> provider.fetchUser("authorization-code", "state"));
	}

	@Test
	void kakaoSucceedsWithSameResponsesWhenReadTimeoutAllowsDelay() {
		KakaoOAuthCallbackProvider provider = new KakaoOAuthCallbackProvider(
			rewritingBuilder(),
			factoryProvider(Duration.ofSeconds(2)),
			"kakao-client-id",
			"kakao-client-secret",
			"https://finplay.example/api/auth/oauth/kakao/callback");

		assertThat(provider.fetchUser("authorization-code", "state"))
			.isEqualTo(new OAuthUserDto("12345", "member@kakao.example"));
	}

	@Test
	void naverSucceedsWithSameResponsesWhenReadTimeoutAllowsDelay() {
		NaverOAuthCallbackProvider provider = new NaverOAuthCallbackProvider(
			rewritingBuilder(),
			factoryProvider(Duration.ofSeconds(2)),
			"naver-client-id",
			"naver-client-secret",
			"https://finplay.example/api/auth/oauth/naver/callback");

		assertThat(provider.fetchUser("authorization-code", "state"))
			.isEqualTo(new OAuthUserDto("naver-id", "member@naver.example"));
	}

	private RestClient.Builder rewritingBuilder() {
		return RestClient.builder()
			.requestInterceptor((request, body, execution) -> execution.execute(new HttpRequestWrapper(request) {
				@Override
				public URI getURI() {
					String original = request.getURI().toString();
					if (original.contains("/oauth/token") || original.contains("/oauth2.0/token")) {
						return serverBaseUri.resolve("/token");
					}
					return original.contains("kapi.kakao.com")
						? serverBaseUri.resolve("/kakao-user")
						: serverBaseUri.resolve("/naver-user");
				}
			}, body));
	}

	@SuppressWarnings("unchecked")
	private ObjectProvider<OAuthRestClientFactory> factoryProvider(Duration readTimeout) {
		ObjectProvider<OAuthRestClientFactory> provider = mock(ObjectProvider.class);
		given(provider.getIfAvailable(any()))
			.willReturn(new OAuthRestClientFactory(
				Duration.ofSeconds(1), readTimeout));
		return provider;
	}

	private static void respondJson(
		com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
		byte[] body = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private static void assertProviderError(
		org.assertj.core.api.ThrowableAssert.ThrowingCallable invocation) {
		assertThatThrownBy(invocation)
			.isInstanceOfSatisfying(
				BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.OAUTH_PROVIDER_ERROR));
	}
}
