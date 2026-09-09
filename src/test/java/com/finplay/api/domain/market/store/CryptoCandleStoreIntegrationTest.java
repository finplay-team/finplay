package com.finplay.api.domain.market.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.service.CryptoCandleDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CryptoCandleStoreIntegrationTest {

	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime MINUTE_START = LocalDateTime.of(2026, 8, 6, 15, 37, 0);

	@Autowired
	private StringRedisTemplate redisTemplate;

	private CryptoCandleStore storeAt(LocalDateTime now) {
		Clock fixedClock = Clock.fixed(now.atZone(ZONE).toInstant(), ZONE);
		return new CryptoCandleStore(redisTemplate, fixedClock);
	}

	@AfterEach
	void cleanUpRedis() {
		redisTemplate.keys("candle:crypto:TESTCOIN*").forEach(redisTemplate::delete);
	}

	@Test
	void singleTradeCreatesCandleWithOpenHighLowCloseEqualToPrice() {
		CryptoCandleStore store = storeAt(MINUTE_START);

		store.recordTrade("TESTCOIN", MINUTE_START.plusSeconds(5), new BigDecimal("91839000"),
			new BigDecimal("0.00016332"));

		List<CryptoCandleDto> candles = store.getCandles("TESTCOIN", MINUTE_START, MINUTE_START);
		assertThat(candles).hasSize(1);
		CryptoCandleDto candle = candles.get(0);
		assertThat(candle.open()).isEqualByComparingTo("91839000");
		assertThat(candle.high()).isEqualByComparingTo("91839000");
		assertThat(candle.low()).isEqualByComparingTo("91839000");
		assertThat(candle.close()).isEqualByComparingTo("91839000");
		assertThat(candle.volume()).isEqualByComparingTo("0.00016332");
	}

	@Test
	void multipleTradesInSameMinuteProduceCorrectOhlcv() {
		CryptoCandleStore store = storeAt(MINUTE_START);

		store.recordTrade("TESTCOIN", MINUTE_START.plusSeconds(1), new BigDecimal("100"), new BigDecimal("1"));
		store.recordTrade("TESTCOIN", MINUTE_START.plusSeconds(2), new BigDecimal("105"), new BigDecimal("2"));
		store.recordTrade("TESTCOIN", MINUTE_START.plusSeconds(3), new BigDecimal("98"), new BigDecimal("3"));
		store.recordTrade("TESTCOIN", MINUTE_START.plusSeconds(4), new BigDecimal("102"), new BigDecimal("4"));

		CryptoCandleDto candle = store.getCandles("TESTCOIN", MINUTE_START, MINUTE_START).get(0);
		assertThat(candle.open()).isEqualByComparingTo("100");
		assertThat(candle.high()).isEqualByComparingTo("105");
		assertThat(candle.low()).isEqualByComparingTo("98");
		assertThat(candle.close()).isEqualByComparingTo("102");
		assertThat(candle.volume()).isEqualByComparingTo("10");
	}

	@Test
	void tradesInDifferentMinutesProduceSeparateCandles() {
		CryptoCandleStore store = storeAt(MINUTE_START);

		store.recordTrade("TESTCOIN", MINUTE_START, new BigDecimal("100"), new BigDecimal("1"));
		store.recordTrade("TESTCOIN", MINUTE_START.plusMinutes(1), new BigDecimal("200"), new BigDecimal("1"));

		List<CryptoCandleDto> candles = store.getCandles("TESTCOIN", MINUTE_START, MINUTE_START.plusMinutes(1));
		assertThat(candles).hasSize(2);
		assertThat(candles.get(0).sourceTime()).isEqualTo(MINUTE_START);
		assertThat(candles.get(1).sourceTime()).isEqualTo(MINUTE_START.plusMinutes(1));
	}

	@Test
	void tradeForAlreadyPassedMinuteIsIgnored() {
		CryptoCandleStore store = storeAt(MINUTE_START.plusMinutes(5));

		store.recordTrade("TESTCOIN", MINUTE_START, new BigDecimal("100"), new BigDecimal("1"));

		List<CryptoCandleDto> candles = store.getCandles("TESTCOIN", MINUTE_START, MINUTE_START);
		assertThat(candles).isEmpty();
	}

	@Test
	void minuteWithNoTradesIsAbsentFromResultNotZeroFilled() {
		CryptoCandleStore store = storeAt(MINUTE_START);

		store.recordTrade("TESTCOIN", MINUTE_START, new BigDecimal("100"), new BigDecimal("1"));
		store.recordTrade("TESTCOIN", MINUTE_START.plusMinutes(2), new BigDecimal("200"), new BigDecimal("1"));

		List<CryptoCandleDto> candles = store.getCandles("TESTCOIN", MINUTE_START, MINUTE_START.plusMinutes(2));

		assertThat(candles).hasSize(2);
		assertThat(candles).extracting(CryptoCandleDto::sourceTime)
			.containsExactly(MINUTE_START, MINUTE_START.plusMinutes(2));
	}

	@Test
	void quantityWithMoreThanEightDecimalPlacesIsExcludedFromAggregation() {
		CryptoCandleStore store = storeAt(MINUTE_START);

		store.recordTrade("TESTCOIN", MINUTE_START, new BigDecimal("100"), new BigDecimal("0.123456789"));

		List<CryptoCandleDto> candles = store.getCandles("TESTCOIN", MINUTE_START, MINUTE_START);
		assertThat(candles).isEmpty();
	}

	@Test
	void quantityWithExactlyEightDecimalPlacesRoundTripsExactly() {
		CryptoCandleStore store = storeAt(MINUTE_START);

		store.recordTrade("TESTCOIN", MINUTE_START, new BigDecimal("100"), new BigDecimal("0.00016332"));

		CryptoCandleDto candle = store.getCandles("TESTCOIN", MINUTE_START, MINUTE_START).get(0);
		assertThat(candle.volume()).isEqualByComparingTo("0.00016332");
	}

	@Test
	void concurrentTradesToSameMinuteLoseNoVolumeAndProduceCorrectHighLow() throws InterruptedException {
		CryptoCandleStore store = storeAt(MINUTE_START);
		int threadCount = 50;
		ExecutorService executor = Executors.newFixedThreadPool(threadCount);
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threadCount);

		for (int i = 1; i <= threadCount; i++) {
			int price = 100 + i;
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					store.recordTrade("TESTCOIN", MINUTE_START.plusSeconds(1), new BigDecimal(price),
						new BigDecimal("1"));
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
		}

		ready.await();
		start.countDown();
		done.await(10, TimeUnit.SECONDS);
		executor.shutdown();

		CryptoCandleDto candle = store.getCandles("TESTCOIN", MINUTE_START, MINUTE_START).get(0);
		assertThat(candle.volume()).isEqualByComparingTo(String.valueOf(threadCount));
		assertThat(candle.high()).isEqualByComparingTo("150");
		assertThat(candle.low()).isEqualByComparingTo("101");
	}

	@Test
	void concurrentReadDuringWritesNeverObservesPartiallyUpdatedOrInconsistentCandle() throws InterruptedException {
		CryptoCandleStore store = storeAt(MINUTE_START);
		int writeCount = 2000;
		AtomicBoolean writingDone = new AtomicBoolean(false);
		List<String> inconsistencies = new CopyOnWriteArrayList<>();

		Thread writer = new Thread(() -> {
			for (int i = 1; i <= writeCount; i++) {
				store.recordTrade("TESTCOIN", MINUTE_START.plusSeconds(1), new BigDecimal(100 + (i % 50)),
					new BigDecimal("1"));
			}
			writingDone.set(true);
		});

		Thread reader = new Thread(() -> {
			while (!writingDone.get()) {
				try {
					List<CryptoCandleDto> candles = store.getCandles("TESTCOIN", MINUTE_START, MINUTE_START);
					if (!candles.isEmpty()) {
						CryptoCandleDto candle = candles.get(0);
						if (candle.high().compareTo(candle.low()) < 0 || candle.high().compareTo(candle.open()) < 0
							|| candle.high().compareTo(candle.close()) < 0
							|| candle.low().compareTo(candle.open()) > 0
							|| candle.low().compareTo(candle.close()) > 0) {
							inconsistencies.add("OHLC 대소관계 붕괴: " + candle);
						}
					}
				} catch (RuntimeException ex) {
					inconsistencies.add("쓰는 도중 읽어서 예외 발생: " + ex);
				}
			}
		});

		writer.start();
		reader.start();
		writer.join(15_000);
		reader.join(1_000);

		assertThat(inconsistencies).isEmpty();
	}

	@Test
	void touchSinceAndGetSinceRoundTrip() {
		CryptoCandleStore store = storeAt(MINUTE_START);

		store.touchSince("TESTCOIN", MINUTE_START);

		assertThat(store.getSince("TESTCOIN")).contains(MINUTE_START);
	}

	@Test
	void getSinceReturnsEmptyWhenNeverTouched() {
		CryptoCandleStore store = storeAt(MINUTE_START);

		assertThat(store.getSince("TESTCOIN_NEVER_TOUCHED")).isEmpty();
	}

	@Test
	void recordedCandleHasTtlSet() {
		CryptoCandleStore store = storeAt(MINUTE_START);

		store.recordTrade("TESTCOIN", MINUTE_START, new BigDecimal("100"), new BigDecimal("1"));

		Long ttl = redisTemplate
			.getExpire("candle:crypto:TESTCOIN:1m:" + (MINUTE_START.atZone(ZONE).toEpochSecond() / 60));
		assertThat(ttl).isGreaterThan(0);
	}
}
