package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.store.CryptoCandleStore;
import com.finplay.api.global.exception.BusinessException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CachedCryptoCandleProviderIntegrationTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	private static final String MINUTE_ENDPOINT = "https://api.bithumb.com/v1/candles/minutes/1";
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 6, 15, 40, 0);
	private static final String SYMBOL = "INTEGTEST";

	@Autowired
	private StringRedisTemplate redisTemplate;

	private MockRestServiceServer server;
	private RestClient.Builder builder;

	@BeforeEach
	void setUp() {
		builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
	}

	@AfterEach
	void cleanUpRedis() {
		redisTemplate.keys("candle:crypto:" + SYMBOL + "*").forEach(redisTemplate::delete);
	}

	private CachedCryptoCandleProvider providerAt(LocalDateTime now, CryptoCandleStore candleStore) {
		Clock clock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE);
		BithumbRestCandleProvider restProvider = new BithumbRestCandleProvider(builder.build(), clock);
		return new CachedCryptoCandleProvider(restProvider, candleStore, clock);
	}

	private void recordTradeAt(LocalDateTime tradedAt, String price, String quantity) {
		Clock clockAtTradeTime = Clock.fixed(tradedAt.atZone(ZONE).toInstant(), ZONE);
		new CryptoCandleStore(redisTemplate, clockAtTradeTime).recordTrade(SYMBOL, tradedAt, new BigDecimal(price),
			new BigDecimal(quantity));
	}

	private static String candleItem(String kstTime, String price) {
		return """
			{"market":"KRW-INTEGTEST","candle_date_time_utc":"%s","candle_date_time_kst":"%s",
			 "opening_price":%s,"high_price":%s,"low_price":%s,"trade_price":%s,
			 "timestamp":1753842180000,"candle_acc_trade_price":"1000","candle_acc_trade_volume":"1","unit":1}
			""".formatted(kstTime, kstTime, price, price, price, price);
	}

	@Test
	void stitchesCacheAndDelegateWithoutDuplicateOrGapInSourceTime() {
		Clock fixedClock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
		CryptoCandleStore candleStore = new CryptoCandleStore(redisTemplate, fixedClock);

		LocalDateTime since = NOW.minusMinutes(2);
		candleStore.touchSince(SYMBOL, since);
		recordTradeAt(since, "200", "1");
		recordTradeAt(NOW, "201", "1");

		LocalDateTime from = NOW.minusMinutes(10);
		LocalDateTime delegateTo = since.minusMinutes(1);
		String body = "[" + candleItem(from.toString(), "100") + "," + candleItem(delegateTo.toString(), "101") + "]";
		server.expect(requestTo(org.hamcrest.Matchers.startsWith(MINUTE_ENDPOINT)))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		CachedCryptoCandleProvider provider = providerAt(NOW, candleStore);
		List<CryptoCandleDto> result = provider.getCandles(SYMBOL, CandleInterval.ONE_MINUTE, from, NOW);

		assertThat(result).extracting(CryptoCandleDto::sourceTime)
			.containsExactly(from, delegateTo, since, NOW);
		server.verify();
	}

	@Test
	void bithumbFailureWhenDelegateNeededPropagatesAsProviderError() {
		Clock fixedClock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
		CryptoCandleStore candleStore = new CryptoCandleStore(redisTemplate, fixedClock);

		LocalDateTime since = NOW.minusMinutes(2);
		candleStore.touchSince(SYMBOL, since);

		LocalDateTime from = NOW.minusMinutes(10);
		server.expect(requestTo(org.hamcrest.Matchers.startsWith(MINUTE_ENDPOINT)))
			.andRespond(withServerError());

		CachedCryptoCandleProvider provider = providerAt(NOW, candleStore);

		assertThatThrownBy(() -> provider.getCandles(SYMBOL, CandleInterval.ONE_MINUTE, from, NOW))
			.isInstanceOf(BusinessException.class);
		server.verify();
	}

	@Test
	void bithumbFailureIsIrrelevantWhenRequestIsFullyCoveredByCache() {
		Clock fixedClock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
		CryptoCandleStore candleStore = new CryptoCandleStore(redisTemplate, fixedClock);

		LocalDateTime from = NOW.minusMinutes(2);
		candleStore.touchSince(SYMBOL, NOW.minusMinutes(10));
		recordTradeAt(from, "300", "1");
		recordTradeAt(NOW, "301", "1");

		CachedCryptoCandleProvider provider = providerAt(NOW, candleStore);

		List<CryptoCandleDto> result = provider.getCandles(SYMBOL, CandleInterval.ONE_MINUTE, from, NOW);

		assertThat(result).extracting(CryptoCandleDto::sourceTime).containsExactly(from, NOW);
	}
}
