package com.finplay.api.domain.market.store;

import com.finplay.api.domain.market.event.CryptoPriceUpdatedEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PriceStore {

	private static final String PRICE_KEY_PREFIX = "price:crypto:";
	private static final String SNAPSHOT_KEY_SUFFIX = ":snapshots";
	private static final String STATUS_KEY = "feed:crypto:status";
	private static final String FIELD_PRICE = "price";
	private static final String FIELD_RECEIVED_AT = "receivedAt";
	private static final String FIELD_OBSERVED_AT = "observedAt";
	private static final Duration STALE_THRESHOLD = Duration.ofSeconds(10);
	private static final String SNAPSHOT_MEMBER_DELIMITER = ":";

	private final StringRedisTemplate redisTemplate;
	private final Clock clock;
	private final ApplicationEventPublisher eventPublisher;

	public void saveTick(String symbol, BigDecimal price, LocalDateTime receivedAt) {
		HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
		String key = priceKey(symbol);
		Optional<LocalDateTime> existingReceivedAt = readReceivedAt(hashOps, key);
		if (existingReceivedAt.isPresent() && !receivedAt.isAfter(existingReceivedAt.get())) {
			return;
		}
		LocalDateTime observedAt = LocalDateTime.now(clock);
		Map<String, String> fields = new HashMap<>();
		fields.put(FIELD_PRICE, price.toPlainString());
		fields.put(FIELD_RECEIVED_AT, receivedAt.toString());
		fields.put(FIELD_OBSERVED_AT, observedAt.toString());
		hashOps.putAll(key, fields);
		eventPublisher.publishEvent(new CryptoPriceUpdatedEvent(symbol, price, receivedAt, observedAt));
	}

	public void recordObservation(String symbol, BigDecimal price, LocalDateTime observedAt) {
		HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
		String key = priceKey(symbol);
		String existingPriceValue = hashOps.get(key, FIELD_PRICE);
		boolean priceChanged = existingPriceValue == null || new BigDecimal(existingPriceValue).compareTo(price) != 0;
		Optional<LocalDateTime> existingReceivedAt = readReceivedAt(hashOps, key);
		Map<String, String> fields = new HashMap<>();
		fields.put(FIELD_OBSERVED_AT, observedAt.toString());
		if (priceChanged) {
			fields.put(FIELD_PRICE, price.toPlainString());
		}
		if (existingReceivedAt.isEmpty()) {
			fields.put(FIELD_RECEIVED_AT, observedAt.toString());
		}
		hashOps.putAll(key, fields);
		if (priceChanged) {
			LocalDateTime receivedAt = existingReceivedAt.orElse(observedAt);
			eventPublisher.publishEvent(new CryptoPriceUpdatedEvent(symbol, price, receivedAt, observedAt));
		}
	}

	public Optional<CryptoPriceDto> getLatestPrice(String symbol) {
		HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
		String key = priceKey(symbol);
		String priceValue = hashOps.get(key, FIELD_PRICE);
		Optional<LocalDateTime> receivedAt = readReceivedAt(hashOps, key);
		if (priceValue == null || receivedAt.isEmpty()) {
			return Optional.empty();
		}
		LocalDateTime observedAt = readObservedAt(hashOps, key).orElse(receivedAt.get());
		return Optional.of(new CryptoPriceDto(symbol, new BigDecimal(priceValue), receivedAt.get(), observedAt));
	}

	public void saveConnectionStatus(FeedConnectionStatus status) {
		redisTemplate.opsForValue().set(STATUS_KEY, status.name());
	}

	public FeedConnectionStatus getConnectionStatus() {
		String value = redisTemplate.opsForValue().get(STATUS_KEY);
		return value == null ? FeedConnectionStatus.DISCONNECTED : FeedConnectionStatus.valueOf(value);
	}

	public boolean isStale(LocalDateTime observedAt) {
		Duration elapsed = Duration.between(observedAt, LocalDateTime.now(clock));
		return elapsed.compareTo(STALE_THRESHOLD) > 0;
	}

	public boolean isPriceAvailable(String symbol) {
		if (getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
			return false;
		}
		return getLatestPrice(symbol)
			.map(price -> !isStale(price.receivedAt()))
			.orElse(false);
	}

	public Map<String, CryptoPriceDto> getLatestPrices(List<String> symbols) {
		if (getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
			return Map.of();
		}
		Map<String, CryptoPriceDto> prices = new HashMap<>();
		for (String symbol : symbols) {
			getLatestPrice(symbol)
				.filter(price -> !isStale(price.receivedAt()))
				.ifPresent(price -> prices.put(symbol, price));
		}
		return prices;
	}

	public void recordSnapshot(String symbol, LocalDateTime recordedAt, BigDecimal price, Duration retention) {
		String key = snapshotKey(symbol);
		long epochMillis = toEpochMillis(recordedAt);
		String member = epochMillis + SNAPSHOT_MEMBER_DELIMITER + price.toPlainString();
		redisTemplate.opsForZSet().add(key, member, epochMillis);
		long cutoffMillis = toEpochMillis(recordedAt.minus(retention));
		redisTemplate.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoffMillis);
	}

	public List<PriceSnapshotDto> getSnapshots(String symbol, LocalDateTime from, LocalDateTime to) {
		String key = snapshotKey(symbol);
		Set<String> members = redisTemplate.opsForZSet().rangeByScore(key, toEpochMillis(from), toEpochMillis(to));
		if (members == null || members.isEmpty()) {
			return List.of();
		}
		List<PriceSnapshotDto> snapshots = new ArrayList<>();
		for (String member : members) {
			int delimiterIndex = member.indexOf(SNAPSHOT_MEMBER_DELIMITER);
			long epochMillis = Long.parseLong(member.substring(0, delimiterIndex));
			BigDecimal price = new BigDecimal(member.substring(delimiterIndex + 1));
			snapshots.add(new PriceSnapshotDto(toLocalDateTime(epochMillis), price));
		}
		snapshots.sort(Comparator.comparing(PriceSnapshotDto::recordedAt));
		return snapshots;
	}

	private Optional<LocalDateTime> readReceivedAt(HashOperations<String, String, String> hashOps, String key) {
		String value = hashOps.get(key, FIELD_RECEIVED_AT);
		return value == null ? Optional.empty() : Optional.of(LocalDateTime.parse(value));
	}

	private Optional<LocalDateTime> readObservedAt(HashOperations<String, String, String> hashOps, String key) {
		String value = hashOps.get(key, FIELD_OBSERVED_AT);
		return value == null ? Optional.empty() : Optional.of(LocalDateTime.parse(value));
	}

	private String priceKey(String symbol) {
		return PRICE_KEY_PREFIX + symbol;
	}

	private String snapshotKey(String symbol) {
		return PRICE_KEY_PREFIX + symbol + SNAPSHOT_KEY_SUFFIX;
	}

	private long toEpochMillis(LocalDateTime dateTime) {
		return dateTime.atZone(clock.getZone()).toInstant().toEpochMilli();
	}

	private LocalDateTime toLocalDateTime(long epochMillis) {
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), clock.getZone());
	}
}
