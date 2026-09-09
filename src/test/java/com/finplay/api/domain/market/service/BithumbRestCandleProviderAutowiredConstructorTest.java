package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BithumbRestCandleProviderAutowiredConstructorTest {

	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 7, 31, 10, 0, 0);

	private HttpServer server;
	private ExecutorService executor;
	private String candleBaseUrl;

	@BeforeEach
	void startLocalServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "bithumb-candle-autowired-test-server");
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(executor);
		server.createContext("/minutes/1", exchange -> {
			byte[] body = """
				[{
				  "market": "KRW-BTC",
				  "candle_date_time_utc": "2026-07-31T01:00:00",
				  "candle_date_time_kst": "2026-07-31T10:00:00",
				  "opening_price": 91000000,
				  "high_price": 91500000,
				  "low_price": 90800000,
				  "trade_price": 91234000,
				  "timestamp": 1753842180000,
				  "candle_acc_trade_price": 123456.7,
				  "candle_acc_trade_volume": 0.0135,
				  "unit": 1
				}]
				""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		candleBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stopLocalServer() {
		server.stop(0);
		executor.shutdownNow();
	}

	@Test
	@DisplayName("@Autowired 생성자(RestClient.builder() 직접 호출)로 만든 provider가 실제 HTTP 응답을 정상 파싱한다")
	void autowiredConstructorParsesRealHttpResponseThroughDirectRestClientBuilder() {
		Clock clock = Clock.fixed(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
		BithumbRestCandleProvider provider = new BithumbRestCandleProvider(clock, 2000L, 3000L, candleBaseUrl);

		List<CryptoCandleDto> candles = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null);

		assertThat(candles).hasSize(1);
		CryptoCandleDto candle = candles.get(0);
		assertThat(candle.sourceTime()).isEqualTo(LocalDateTime.of(2026, 7, 31, 10, 0, 0));
		assertThat(candle.close()).isEqualByComparingTo("91234000");
		assertThat(candle.volume()).isEqualByComparingTo("0.0135");
	}
}
