// kisRestClient() 빈이 RestClient.builder()를 직접 호출해 실제 로컬 HTTP 서버 응답을 Jackson 3으로
// 정상 파싱하는지 검증한다 — 공유 RestClient.Builder DI로 되돌아가는 회귀를 잡는다 (PR #377 리뷰 2라운드 권장②).
package com.finplay.api.domain.market.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class KisRestClientConfigTest {

	private HttpServer server;
	private String tokenUrl;

	@BeforeEach
	void startLocalServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/oauth2/tokenP", exchange -> {
			byte[] body = """
				{"access_token":"test-access-token","access_token_token_expired":"2099-01-01 00:00:00"}
				""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		tokenUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/oauth2/tokenP";
	}

	@AfterEach
	void stopLocalServer() {
		server.stop(0);
	}

	@Test
	void kisRestClientParsesRealHttpResponseThroughDirectRestClientBuilder() {
		RestClient restClient = new KisRestClientConfig().kisRestClient();

		TokenResponse response = restClient.get()
			.uri(tokenUrl)
			.retrieve()
			.body(TokenResponse.class);

		assertThat(response).isNotNull();
		assertThat(response.access_token()).isEqualTo("test-access-token");
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record TokenResponse(String access_token, String access_token_token_expired) {
	}
}
