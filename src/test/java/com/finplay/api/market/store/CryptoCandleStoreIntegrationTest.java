// 실제 Redis(Testcontainers)로 CryptoCandleStore의 원자적 갱신·동시성·조회·수량 스케일링을 검증하는 통합 테스트 (ADR-0003)
package com.finplay.api.market.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.service.CryptoCandleDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CryptoCandleStoreIntegrationTest {

	// KST 기준. 이 zone에서 epochMinute 변환이 일관되게 이뤄지는지도 함께 검증한다.
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
		assertThat(candle.open()).isEqualByComparingTo("100"); // 첫 체결가
		assertThat(candle.high()).isEqualByComparingTo("105"); // 최댓값
		assertThat(candle.low()).isEqualByComparingTo("98"); // 최솟값
		assertThat(candle.close()).isEqualByComparingTo("102"); // 마지막 체결가
		assertThat(candle.volume()).isEqualByComparingTo("10"); // 1+2+3+4
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
		CryptoCandleStore store = storeAt(MINUTE_START.plusMinutes(5)); // "현재"가 5분 뒤로 흘러간 상태

		store.recordTrade("TESTCOIN", MINUTE_START, new BigDecimal("100"), new BigDecimal("1")); // 이미 지난 분

		List<CryptoCandleDto> candles = store.getCandles("TESTCOIN", MINUTE_START, MINUTE_START);
		assertThat(candles).isEmpty(); // 봉이 만들어지지도, 갱신되지도 않는다
	}

	@Test
	void minuteWithNoTradesIsAbsentFromResultNotZeroFilled() {
		CryptoCandleStore store = storeAt(MINUTE_START);

		store.recordTrade("TESTCOIN", MINUTE_START, new BigDecimal("100"), new BigDecimal("1"));
		// MINUTE_START + 1분에는 체결을 기록하지 않는다.
		store.recordTrade("TESTCOIN", MINUTE_START.plusMinutes(2), new BigDecimal("200"), new BigDecimal("1"));

		List<CryptoCandleDto> candles = store.getCandles("TESTCOIN", MINUTE_START, MINUTE_START.plusMinutes(2));

		assertThat(candles).hasSize(2); // 3분 요청했지만 체결 없는 중간 분은 응답에 없음
		assertThat(candles).extracting(CryptoCandleDto::sourceTime)
			.containsExactly(MINUTE_START, MINUTE_START.plusMinutes(2));
	}

	@Test
	void quantityWithMoreThanEightDecimalPlacesIsExcludedFromAggregation() {
		CryptoCandleStore store = storeAt(MINUTE_START);

		store.recordTrade("TESTCOIN", MINUTE_START, new BigDecimal("100"), new BigDecimal("0.123456789"));

		List<CryptoCandleDto> candles = store.getCandles("TESTCOIN", MINUTE_START, MINUTE_START);
		assertThat(candles).isEmpty(); // 유일한 체결이 제외되어 그 분의 봉 자체가 만들어지지 않음
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
			int price = 100 + i; // 101 ~ 150, high=150·low=101 예상
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
		start.countDown(); // 스레드 50개가 동시에 recordTrade를 호출하도록 한 번에 풀어준다
		done.await(10, TimeUnit.SECONDS);
		executor.shutdown();

		CryptoCandleDto candle = store.getCandles("TESTCOIN", MINUTE_START, MINUTE_START).get(0);
		assertThat(candle.volume()).isEqualByComparingTo(String.valueOf(threadCount)); // 유실 0건 — 합계가 정확히 50
		assertThat(candle.high()).isEqualByComparingTo("150");
		assertThat(candle.low()).isEqualByComparingTo("101");
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
