// 코인 최신 시세·수신시각·빗썸 연결상태를 Redis에 저장·조회하는 단일 창구 (key 문자열은 이 클래스에서만 조립)
package com.finplay.api.market.store;

import com.finplay.api.market.event.CryptoPriceUpdatedEvent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PriceStore {

	private static final String PRICE_KEY_PREFIX = "price:crypto:";
	private static final String STATUS_KEY = "feed:crypto:status";
	private static final String FIELD_PRICE = "price";
	private static final String FIELD_RECEIVED_AT = "receivedAt";
	private static final Duration STALE_THRESHOLD = Duration.ofSeconds(10);

	private final StringRedisTemplate redisTemplate;
	private final Clock clock;
	private final ApplicationEventPublisher eventPublisher;

	// 동일 심볼의 과거 틱(수신시각이 현재 저장된 값보다 이전 또는 같음)은 최신 틱을 덮어쓰지 못한다 (spec.md MKT-003).
	// 이 분기를 통과해 실제로 최신값을 갱신했을 때만 CryptoPriceUpdatedEvent를 publish한다 (015-limit-order LMT-002 트리거,
	// spec.md 확정된 설계 결정 3번). 일반 ApplicationEvent다 — 가격 수신이 DB 트랜잭션이 아니므로 AFTER_COMMIT 대상이 없다.
	public void saveTick(String symbol, BigDecimal price, LocalDateTime receivedAt) {
		HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
		String key = priceKey(symbol);
		Optional<LocalDateTime> existingReceivedAt = readReceivedAt(hashOps, key);
		if (existingReceivedAt.isPresent() && !receivedAt.isAfter(existingReceivedAt.get())) {
			return;
		}
		Map<String, String> fields = new HashMap<>();
		fields.put(FIELD_PRICE, price.toPlainString());
		fields.put(FIELD_RECEIVED_AT, receivedAt.toString());
		hashOps.putAll(key, fields);
		eventPublisher.publishEvent(new CryptoPriceUpdatedEvent(symbol, price, receivedAt));
	}

	public Optional<CryptoPriceDto> getLatestPrice(String symbol) {
		HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
		String key = priceKey(symbol);
		String priceValue = hashOps.get(key, FIELD_PRICE);
		Optional<LocalDateTime> receivedAt = readReceivedAt(hashOps, key);
		if (priceValue == null || receivedAt.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new CryptoPriceDto(symbol, new BigDecimal(priceValue), receivedAt.get()));
	}

	public void saveConnectionStatus(FeedConnectionStatus status) {
		redisTemplate.opsForValue().set(STATUS_KEY, status.name());
	}

	// 연결상태를 저장한 적이 없으면 DISCONNECTED로 취급한다 (장애 중 임의 가격 전환 금지 — fail-closed).
	public FeedConnectionStatus getConnectionStatus() {
		String value = redisTemplate.opsForValue().get(STATUS_KEY);
		return value == null ? FeedConnectionStatus.DISCONNECTED : FeedConnectionStatus.valueOf(value);
	}

	// 수신시각이 10초를 초과하면 stale(유효하지 않음)로 판정한다 (spec.md MKT-004). PriceQueryService가 그대로 재사용한다.
	public boolean isStale(LocalDateTime receivedAt) {
		Duration elapsed = Duration.between(receivedAt, LocalDateTime.now(clock));
		return elapsed.compareTo(STALE_THRESHOLD) > 0;
	}

	// 연결이 끊겼거나 최신 틱이 stale이면 유효하지 않은 가격으로 판정한다 (MKT-004).
	public boolean isPriceAvailable(String symbol) {
		if (getConnectionStatus() != FeedConnectionStatus.CONNECTED) {
			return false;
		}
		return getLatestPrice(symbol)
			.map(price -> !isStale(price.receivedAt()))
			.orElse(false);
	}

	// 계좌(=market) 단위로 여러 심볼을 평가할 때 심볼과 무관한 전역 연결상태(getConnectionStatus, Redis 전역 키
	// feed:crypto:status)를 요청당 1회만 조회하고, 심볼별로 실제로 달라지는 개별 가격 조회만 반복한다 (PR #97 리뷰 권장사항).
	// 연결이 끊겨 있으면 개별 가격 조회 없이 즉시 빈 Map을 반환한다 (fail-closed, isPriceAvailable과 동일 정책).
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

	private Optional<LocalDateTime> readReceivedAt(HashOperations<String, String, String> hashOps, String key) {
		String value = hashOps.get(key, FIELD_RECEIVED_AT);
		return value == null ? Optional.empty() : Optional.of(LocalDateTime.parse(value));
	}

	private String priceKey(String symbol) {
		return PRICE_KEY_PREFIX + symbol;
	}
}
