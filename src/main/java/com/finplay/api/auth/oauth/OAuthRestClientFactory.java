// 실제 OAuth 공급자 호출에 유한 연결·응답 timeout을 적용한 RestClient를 만든다.
package com.finplay.api.auth.oauth;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public final class OAuthRestClientFactory {

	private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

	private final Duration connectTimeout;
	private final Duration readTimeout;

	public OAuthRestClientFactory() {
		this(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
	}

	OAuthRestClientFactory(Duration connectTimeout, Duration readTimeout) {
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
	}

	RestClient create(RestClient.Builder builder) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(connectTimeout);
		requestFactory.setReadTimeout(readTimeout);
		return builder.requestFactory(requestFactory).build();
	}
}
