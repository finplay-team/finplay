// @Autowired 생성자 경로(RestClient.builder() 직접 호출)를 실제 로컬 HTTP 서버로 검증한다 — 이 경로가
// 공유 RestClient.Builder DI로 되돌아가는 회귀를 잡기 위한 테스트다 (PR #377 리뷰 권장②, 이슈 #376).
package com.finplay.api.market.feed;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.PriceStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BithumbRestTickerPollerAutowiredConstructorTest {

	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 7, 31, 10, 0, 0);

	@Mock
	private InstrumentRepository instrumentRepository;

	@Mock
	private PriceStore priceStore;

	private HttpServer server;
	private ExecutorService executor;
	private String tickerEndpoint;
	private String serverBaseUrl;

	@BeforeEach
	void startLocalServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		executor = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "bithumb-ticker-autowired-test-server");
			thread.setDaemon(true);
			return thread;
		});
		server.setExecutor(executor);
		server.createContext("/v1/ticker", exchange -> {
			byte[] body = """
				[{"market": "KRW-BTC", "trade_price": 91234000, "opening_price": 1}]
				""".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		serverBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		tickerEndpoint = serverBaseUrl + "/v1/ticker";
	}

	@AfterEach
	void stopLocalServer() {
		server.stop(0);
		executor.shutdownNow();
	}

	@Test
	@DisplayName("@Autowired 생성자(RestClient.builder() 직접 호출)로 만든 폴러가 실제 HTTP 응답을 정상 파싱한다")
	void autowiredConstructorParsesRealHttpResponseThroughDirectRestClientBuilder() {
		when(instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO))
			.thenReturn(
				List.of(Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 1000, true, FIXED_NOW)));
		Clock clock = Clock.fixed(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

		BithumbRestTickerPoller poller = new BithumbRestTickerPoller(
			instrumentRepository, priceStore, clock, 2000L, 3000L, tickerEndpoint);
		poller.pollTickers();

		verify(priceStore).recordObservation(eq("BTC"), eq(new BigDecimal("91234000")), eq(FIXED_NOW));
		verifyNoMoreInteractions(priceStore);
	}

	@Test
	@DisplayName("@Autowired 생성자로 만든 폴러도 존재하지 않는 경로(404)면 예외를 전파하지 않는다")
	void autowiredConstructorSwallowsNotFoundWithoutRecordingObservation() {
		when(instrumentRepository.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO))
			.thenReturn(
				List.of(Instrument.create(Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 1000, true, FIXED_NOW)));
		Clock clock = Clock.fixed(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
		// /v1/ticker 컨텍스트가 prefix 매칭이라 그 아래 경로는 그대로 잡힌다 — 등록되지 않은 별도 경로로 404를 유도한다.
		BithumbRestTickerPoller poller = new BithumbRestTickerPoller(
			instrumentRepository, priceStore, clock, 2000L, 3000L, serverBaseUrl + "/no-such-endpoint");

		poller.pollTickers();

		verifyNoMoreInteractions(priceStore);
	}
}
