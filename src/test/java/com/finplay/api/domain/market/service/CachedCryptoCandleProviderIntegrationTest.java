// 실제 Redis(Testcontainers) + Mock HTTP(MockRestServiceServer)로 CachedCryptoCandleProvider의 캐시·위임 구간 이어붙이기와 빗썸 장애 분기를 검증하는 통합 테스트 (PR #255 리뷰 대응)
package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.domain.market.store.CryptoCandleStore;
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

	// CryptoCandleStore.recordTrade는 "현재 시각(clock)보다 과거 분의 체결은 버린다"는 가드가 있다(늦은 체결
	// 거부). 그래서 과거 시각의 체결을 테스트 데이터로 심으려면, NOW로 고정된 store로 넣으면 전부 거부된다 —
	// 그 체결이 "실제로 일어난 시각"에 맞춰 clock을 고정한 별도 store로 넣어야 한다(실제 운영에서 체결이
	// 그 시각에 도착하는 것과 같다).
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

		// since = NOW - 2분: [NOW-2, NOW]는 캐시가 정본, [NOW-10, NOW-3]은 빗썸 위임 구간이다.
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

		// 빗썸(위임) 2개 + 캐시 2개 = 4개, 시각 오름차순, 중복·누락 없음.
		assertThat(result).extracting(CryptoCandleDto::sourceTime)
			.containsExactly(from, delegateTo, since, NOW);
		server.verify();
	}

	@Test
	void bithumbFailureWhenDelegateNeededPropagatesAsProviderError() {
		Clock fixedClock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
		CryptoCandleStore candleStore = new CryptoCandleStore(redisTemplate, fixedClock);

		LocalDateTime since = NOW.minusMinutes(2);
		candleStore.touchSince(SYMBOL, since); // since가 있어도 요청 구간 앞부분은 여전히 위임 대상

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

		// since를 요청 구간보다 훨씬 이전으로 잡아 전체 구간이 캐시로 덮이게 한다.
		LocalDateTime from = NOW.minusMinutes(2);
		candleStore.touchSince(SYMBOL, NOW.minusMinutes(10));
		recordTradeAt(from, "300", "1");
		recordTradeAt(NOW, "301", "1");

		// MockRestServiceServer에 어떤 expectation도 등록하지 않는다 — delegate가 실제로 호출되면
		// "예상치 못한 요청"으로 즉시 실패하므로, 이 테스트가 통과한다는 사실 자체가 빗썸 미호출을 증명한다.
		CachedCryptoCandleProvider provider = providerAt(NOW, candleStore);

		List<CryptoCandleDto> result = provider.getCandles(SYMBOL, CandleInterval.ONE_MINUTE, from, NOW);

		assertThat(result).extracting(CryptoCandleDto::sourceTime).containsExactly(from, NOW);
	}
}
