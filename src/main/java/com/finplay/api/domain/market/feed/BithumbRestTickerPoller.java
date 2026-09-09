package com.finplay.api.domain.market.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.PriceStore;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
@Profile("prod | crypto-real")
@ConditionalOnProperty(prefix = "bithumb.feed.ticker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BithumbRestTickerPoller {

	private static final String DEFAULT_TICKER_ENDPOINT = "https://api.bithumb.com/v1/ticker";
	private static final String KRW_MARKET_PREFIX = "KRW-";
	private static final long POLL_INTERVAL_MS = 3000;

	private final RestClient restClient;
	private final InstrumentRepository instrumentRepository;
	private final PriceStore priceStore;
	private final Clock clock;
	private final String tickerEndpoint;

	@Autowired
	public BithumbRestTickerPoller(
		InstrumentRepository instrumentRepository,
		PriceStore priceStore,
		Clock clock,
		@Value("${bithumb.feed.ticker.connect-timeout-ms:2000}")
		long connectTimeoutMs,
		@Value("${bithumb.feed.ticker.read-timeout-ms:3000}")
		long readTimeoutMs,
		@Value("${bithumb.feed.ticker.endpoint-url:" + DEFAULT_TICKER_ENDPOINT + "}")
		String tickerEndpoint) {
		this(applyTimeouts(RestClient.builder(), connectTimeoutMs, readTimeoutMs).build(), instrumentRepository,
			priceStore, clock, tickerEndpoint);
	}

	BithumbRestTickerPoller(RestClient restClient, InstrumentRepository instrumentRepository,
		PriceStore priceStore, Clock clock) {
		this(restClient, instrumentRepository, priceStore, clock, DEFAULT_TICKER_ENDPOINT);
	}

	BithumbRestTickerPoller(RestClient restClient, InstrumentRepository instrumentRepository,
		PriceStore priceStore, Clock clock, String tickerEndpoint) {
		this.restClient = restClient;
		this.instrumentRepository = instrumentRepository;
		this.priceStore = priceStore;
		this.clock = clock;
		this.tickerEndpoint = tickerEndpoint;
	}

	@Scheduled(fixedRate = POLL_INTERVAL_MS)
	public void pollTickers() {
		try {
			List<Instrument> cryptoInstruments = instrumentRepository
				.findByMarketAndTradableTrueAndTutorialSampleFalseOrderByIdAsc(Market.CRYPTO);
			if (cryptoInstruments.isEmpty()) {
				return;
			}
			String markets = cryptoInstruments.stream()
				.map(instrument -> KRW_MARKET_PREFIX + instrument.getSymbol())
				.collect(Collectors.joining(","));

			BithumbTickerItem[] response = fetchTickers(markets);
			if (response == null) {
				log.warn("빗썸 ticker 응답이 비어 있어 이번 회차를 건너뛴다 (markets={})", markets);
				return;
			}
			emitTicks(response);
		} catch (RuntimeException ex) {
			log.warn("빗썸 ticker 조회 실패 — 이번 회차를 건너뛴다: {}", ex.toString());
		}
	}

	private BithumbTickerItem[] fetchTickers(String markets) {
		URI uri = UriComponentsBuilder.fromUriString(tickerEndpoint)
			.queryParam("markets", markets)
			.build()
			.toUri();

		return restClient
			.get()
			.uri(uri)
			.retrieve()
			.onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
				throw new IllegalStateException("빗썸 ticker 응답 상태 코드 " + httpResponse.getStatusCode());
			})
			.body(BithumbTickerItem[].class);
	}

	private void emitTicks(BithumbTickerItem[] response) {
		LocalDateTime observedAt = LocalDateTime.now(clock);
		for (BithumbTickerItem item : response) {
			if (item == null || item.market() == null || item.trade_price() == null
				|| !item.market().startsWith(KRW_MARKET_PREFIX)) {
				log.warn("빗썸 ticker 항목이 올바르지 않아 건너뛴다: {}", item);
				continue;
			}
			String symbol = item.market().substring(KRW_MARKET_PREFIX.length());
			if (symbol.isEmpty()) {
				log.warn("빗썸 ticker 항목의 심볼이 비어 있어 건너뛴다: {}", item.market());
				continue;
			}
			priceStore.recordObservation(symbol, item.trade_price(), observedAt);
		}
	}

	private static RestClient.Builder applyTimeouts(RestClient.Builder builder, long connectTimeoutMs,
		long readTimeoutMs) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
		requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
		return builder.requestFactory(requestFactory);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record BithumbTickerItem(String market, BigDecimal trade_price) {
	}
}
