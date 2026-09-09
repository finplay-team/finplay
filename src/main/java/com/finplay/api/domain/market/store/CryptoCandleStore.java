package com.finplay.api.domain.market.store;

import com.finplay.api.domain.market.service.CryptoCandleDto;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoCandleStore {

	private static final String CANDLE_KEY_PREFIX = "candle:crypto:";
	private static final String CANDLE_KEY_SUFFIX = ":1m:";
	private static final String SINCE_KEY_SUFFIX = ":1m:since";
	private static final String FIELD_OPEN = "open";
	private static final String FIELD_HIGH = "high";
	private static final String FIELD_LOW = "low";
	private static final String FIELD_CLOSE = "close";
	private static final String FIELD_VOLUME_SCALED = "volumeScaled";

	private static final long CANDLE_TTL_SECONDS = Duration.ofHours(4).toSeconds();
	private static final int QUANTITY_SCALE = 8;

	private static final RedisScript<Long> RECORD_TRADE_SCRIPT = new DefaultRedisScript<>(
		"""
			local key = KEYS[1]
			local price = ARGV[1]
			local qtyScaled = ARGV[2]
			local ttl = ARGV[3]
			if redis.call('EXISTS', key) == 0 then
			    redis.call('HSET', key, 'open', price, 'high', price, 'low', price, 'close', price, 'volumeScaled', qtyScaled)
			else
			    local high = redis.call('HGET', key, 'high')
			    local low = redis.call('HGET', key, 'low')
			    if tonumber(price) > tonumber(high) then
			        redis.call('HSET', key, 'high', price)
			    end
			    if tonumber(price) < tonumber(low) then
			        redis.call('HSET', key, 'low', price)
			    end
			    redis.call('HSET', key, 'close', price)
			    redis.call('HINCRBY', key, 'volumeScaled', qtyScaled)
			end
			redis.call('EXPIRE', key, ttl)
			return 1
			""",
		Long.class);

	private final StringRedisTemplate redisTemplate;
	private final Clock clock;

	public void recordTrade(String symbol, LocalDateTime tradedAt, BigDecimal price, BigDecimal quantity) {
		long tradeMinute = toEpochMinute(tradedAt);
		long currentMinute = toEpochMinute(LocalDateTime.now(clock));
		if (tradeMinute < currentMinute) {
			log.warn("이미 지난 분에 속한 체결을 무시합니다: symbol={}, tradedAt={}", symbol, tradedAt);
			return;
		}
		if (quantity.stripTrailingZeros().scale() > QUANTITY_SCALE) {
			log.warn("코인 체결 수량의 소수 자릿수가 {}를 초과해 집계에서 제외합니다: symbol={}, quantity={}", QUANTITY_SCALE, symbol,
				quantity);
			return;
		}
		String key = candleKey(symbol, tradeMinute);
		long quantityScaled = quantity.movePointRight(QUANTITY_SCALE).longValueExact();
		redisTemplate.execute(RECORD_TRADE_SCRIPT, List.of(key), price.toPlainString(),
			String.valueOf(quantityScaled), String.valueOf(CANDLE_TTL_SECONDS));
	}

	public List<CryptoCandleDto> getCandles(String symbol, LocalDateTime from, LocalDateTime to) {
		long fromMinute = toEpochMinute(from);
		long toMinute = toEpochMinute(to);
		if (fromMinute > toMinute) {
			return List.of();
		}
		List<String> keys = new ArrayList<>();
		for (long minute = fromMinute; minute <= toMinute; minute++) {
			keys.add(candleKey(symbol, minute));
		}

		List<Object> results = redisTemplate.executePipelined((RedisConnection connection) -> {
			for (String key : keys) {
				connection.hashCommands().hGetAll(key.getBytes(StandardCharsets.UTF_8));
			}
			return null;
		});

		List<CryptoCandleDto> candles = new ArrayList<>();
		for (int i = 0; i < results.size(); i++) {
			@SuppressWarnings("unchecked") Map<String, String> fields = (Map<String, String>)results.get(i);
			if (fields == null || fields.isEmpty()) {
				continue;
			}
			candles.add(toDto(fromEpochMinute(fromMinute + i), fields));
		}
		return candles;
	}

	public void touchSince(String symbol, LocalDateTime now) {
		redisTemplate.opsForValue()
			.set(sinceKey(symbol), String.valueOf(toEpochMinute(now)), Duration.ofSeconds(CANDLE_TTL_SECONDS));
	}

	public Optional<LocalDateTime> getSince(String symbol) {
		String value = redisTemplate.opsForValue().get(sinceKey(symbol));
		return value == null ? Optional.empty() : Optional.of(fromEpochMinute(Long.parseLong(value)));
	}

	private CryptoCandleDto toDto(LocalDateTime sourceTime, Map<String, String> fields) {
		BigDecimal volume = new BigDecimal(fields.get(FIELD_VOLUME_SCALED)).movePointLeft(QUANTITY_SCALE);
		return new CryptoCandleDto(sourceTime, new BigDecimal(fields.get(FIELD_OPEN)),
			new BigDecimal(fields.get(FIELD_HIGH)), new BigDecimal(fields.get(FIELD_LOW)),
			new BigDecimal(fields.get(FIELD_CLOSE)), volume);
	}

	private String candleKey(String symbol, long epochMinute) {
		return CANDLE_KEY_PREFIX + symbol + CANDLE_KEY_SUFFIX + epochMinute;
	}

	private String sinceKey(String symbol) {
		return CANDLE_KEY_PREFIX + symbol + SINCE_KEY_SUFFIX;
	}

	private long toEpochMinute(LocalDateTime dateTime) {
		return dateTime.atZone(clock.getZone()).toEpochSecond() / 60;
	}

	private LocalDateTime fromEpochMinute(long epochMinute) {
		return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochMinute * 60), clock.getZone());
	}
}
