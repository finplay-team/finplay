// 짧은 OAuth HTTP timeout이 지연 응답을 공급자 오류로 정규화하는지 검증한다.
package com.finplay.api.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
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
	private URI delayedUri;

	@BeforeEach
	void startDelayedServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "oauth-timeout-test-server");
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(executor);
		server.createContext("/delayed", exchange -> {
			try {
				Thread.sleep(500);
				byte[] body = "{\"access_token\":\"too-late\"}"
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
		server.start();
		delayedUri = URI.create(
			"http://127.0.0.1:" + server.getAddress().getPort() + "/delayed");
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
			factoryProvider(),
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
			factoryProvider(),
			"naver-client-id",
			"naver-client-secret",
			"https://finplay.example/api/auth/oauth/naver/callback");

		assertProviderError(() -> provider.fetchUser("authorization-code", "state"));
	}

	private RestClient.Builder rewritingBuilder() {
		return RestClient.builder()
			.requestInterceptor((request, body, execution) -> execution.execute(new HttpRequestWrapper(request) {
				@Override
				public URI getURI() {
					return delayedUri;
				}
			}, body));
	}

	@SuppressWarnings("unchecked")
	private ObjectProvider<OAuthRestClientFactory> factoryProvider() {
		ObjectProvider<OAuthRestClientFactory> provider = mock(ObjectProvider.class);
		given(provider.getIfAvailable(any()))
			.willReturn(new OAuthRestClientFactory(
				Duration.ofSeconds(1), Duration.ofMillis(50)));
		return provider;
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
