package com.finplay.api.domain.feedback.store;

import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.domain.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.domain.feedback.service.MarketSessionTimes;
import com.finplay.api.global.lock.RedisLock;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackQueryCache {

	private static final String KEY_PREFIX = "feedback:query-cache:v1:";

	private static final String LOCK_KEY_PREFIX = "feedback:query-cache:lock:v1:";

	private static final String STOCK_SUMMARY_ITEM = "stock-summary";

	private static final String CRYPTO_SUMMARY_ITEM = "crypto-summary";

	private static final String STOCK_BRIEFING_TEXT_ITEM = "stock-briefing-text";

	private static final String STOCK_BRIEFING_ITEMS_ITEM = "stock-briefing-items";

	private static final String CRYPTO_BRIEFING_TEXT_ITEM = "crypto-briefing-text";

	private static final String KEY_DELIMITER = ":";

	private static final int CRYPTO_BATCH_MINUTE = 5;

	private static final TypeReference<List<BriefingNewsItem>> BRIEFING_ITEMS_TYPE = new TypeReference<>() {};

	private final StringRedisTemplate redisTemplate;

	private final RedisLock redisLock;

	private final ObjectMapper objectMapper;

	private final Clock clock;

	private final FeedbackQueryCacheProperties properties;

	private final FeedbackNewsProperties newsProperties;

	public Optional<String> getOrLoadStockSummaryText(Long instrumentId, LocalDate originTradeDate,
		NewsSummaryScope scope, Supplier<Optional<String>> loader) {
		String suffix = STOCK_SUMMARY_ITEM + KEY_DELIMITER + instrumentId + KEY_DELIMITER + originTradeDate
			+ KEY_DELIMITER + scope.name();
		return getOrLoadText(suffix, stockSummaryExpiresAt(scope), loader);
	}

	public Optional<String> getOrLoadCryptoSummaryText(Long instrumentId, Supplier<Optional<String>> loader) {
		return getOrLoadText(CRYPTO_SUMMARY_ITEM + KEY_DELIMITER + instrumentId, nextCryptoBatchTime(), loader);
	}

	public Optional<String> getOrLoadStockBriefingText(LocalDate originTradeDate, Supplier<Optional<String>> loader) {
		return getOrLoadText(
			STOCK_BRIEFING_TEXT_ITEM + KEY_DELIMITER + originTradeDate, nextMarketOpenTime(), loader);
	}

	public List<BriefingNewsItem> getOrLoadStockBriefingItems(LocalDate originTradeDate,
		Supplier<List<BriefingNewsItem>> loader) {
		String suffix = STOCK_BRIEFING_ITEMS_ITEM + KEY_DELIMITER + originTradeDate + KEY_DELIMITER
			+ newsProperties.maxItemsPerBriefing();
		return getOrLoad(suffix, nextNewsCollectionTime(), loader, this::readBriefingItems, this::writeBriefingItems);
	}

	public Optional<String> getOrLoadCryptoBriefingText(Supplier<Optional<String>> loader) {
		return getOrLoadText(CRYPTO_BRIEFING_TEXT_ITEM, nextCryptoBatchTime(), loader);
	}

	public void evictCryptoSummaryText(Long instrumentId) {
		evict(CRYPTO_SUMMARY_ITEM + KEY_DELIMITER + instrumentId);
	}

	public void evictCryptoBriefingText() {
		evict(CRYPTO_BRIEFING_TEXT_ITEM);
	}

	private Optional<String> getOrLoadText(String suffix, LocalDateTime expiresAt,
		Supplier<Optional<String>> loader) {
		return getOrLoad(suffix, expiresAt, loader, value -> Optional.of(Optional.of(value)), Function.identity());
	}

	private <T> T getOrLoad(String suffix, LocalDateTime expiresAt, Supplier<T> loader,
		Function<String, Optional<T>> decoder, Function<T, Optional<String>> encoder) {
		if (!properties.enabled()) {
			return loader.get();
		}
		String key = KEY_PREFIX + suffix;
		CacheRead<T> cached = read(key, decoder);
		if (cached.value().isPresent()) {
			return cached.value().get();
		}
		if (!cached.redisHealthy()) {
			return loader.get();
		}
		Optional<String> token = redisLock.tryLock(
			LOCK_KEY_PREFIX + suffix, Duration.ofMillis(properties.lockTtlMillis()));
		if (token.isPresent()) {
			try {
				CacheRead<T> filledWhileAcquiringLock = read(key, decoder);
				if (filledWhileAcquiringLock.value().isPresent()) {
					return filledWhileAcquiringLock.value().get();
				}
				T loaded = loader.get();
				encoder.apply(loaded).ifPresent(value -> write(key, value, expiresAt));
				return loaded;
			} finally {
				redisLock.unlock(LOCK_KEY_PREFIX + suffix, token.get());
			}
		}
		Optional<T> awaited = awaitCachedValue(LOCK_KEY_PREFIX + suffix, key, decoder);
		if (awaited.isPresent()) {
			return awaited.get();
		}
		return loader.get();
	}

	private <T> Optional<T> awaitCachedValue(String lockKey, String key, Function<String, Optional<T>> decoder) {
		long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.waitMillis());
		while (System.nanoTime() < deadlineNanos) {
			try {
				Thread.sleep(properties.pollMillis());
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				return Optional.empty();
			}
			CacheRead<T> cached = read(key, decoder);
			if (cached.value().isPresent()) {
				return cached.value();
			}
			if (!cached.redisHealthy()) {
				return Optional.empty();
			}
			if (!redisLock.isHeld(lockKey)) {
				return read(key, decoder).value();
			}
		}
		return Optional.empty();
	}

	private <T> CacheRead<T> read(String key, Function<String, Optional<T>> decoder) {
		try {
			String value = redisTemplate.opsForValue().get(key);
			return CacheRead.healthy(value == null ? Optional.empty() : decoder.apply(value));
		} catch (RuntimeException ex) {
			log.warn("조회 캐시 읽기 실패(Redis 장애) - key={}", key, ex);
			return CacheRead.unhealthy();
		}
	}

	private void write(String key, String value, LocalDateTime expiresAt) {
		Duration ttl = Duration.between(LocalDateTime.now(clock), expiresAt);
		if (ttl.isZero() || ttl.isNegative()) {
			log.debug("조회 캐시 만료 경계를 이미 넘어 저장하지 않는다 - key={}, expiresAt={}", key, expiresAt);
			return;
		}
		try {
			redisTemplate.opsForValue().set(key, value, ttl);
		} catch (RuntimeException ex) {
			log.warn("조회 캐시 저장 실패(Redis 장애) - key={}", key, ex);
		}
	}

	private void evict(String suffix) {
		if (!properties.enabled()) {
			return;
		}
		try {
			redisTemplate.delete(KEY_PREFIX + suffix);
		} catch (RuntimeException ex) {
			log.warn("조회 캐시 무효화 실패(Redis 장애) - key={}", KEY_PREFIX + suffix, ex);
		}
	}

	private Optional<List<BriefingNewsItem>> readBriefingItems(String value) {
		try {
			return Optional.ofNullable(objectMapper.readValue(value, BRIEFING_ITEMS_TYPE));
		} catch (RuntimeException ex) {
			log.warn("조회 캐시 역직렬화 실패 - 캐시 미스로 취급한다", ex);
			return Optional.empty();
		}
	}

	private Optional<String> writeBriefingItems(List<BriefingNewsItem> items) {
		if (items.isEmpty()) {
			return Optional.empty();
		}
		try {
			return Optional.of(objectMapper.writeValueAsString(items));
		} catch (RuntimeException ex) {
			log.warn("조회 캐시 직렬화 실패 - 저장하지 않는다", ex);
			return Optional.empty();
		}
	}

	private LocalDateTime stockSummaryExpiresAt(NewsSummaryScope scope) {
		if (scope == NewsSummaryScope.PRE_MARKET) {
			return LocalDate.now(clock).atTime(MarketSessionTimes.MARKET_CLOSE_TIME);
		}
		return nextMarketOpenTime();
	}

	private LocalDateTime nextMarketOpenTime() {
		return LocalDate.now(clock).plusDays(1).atTime(MarketSessionTimes.MARKET_OPEN_TIME);
	}

	private LocalDateTime nextNewsCollectionTime() {
		LocalDateTime now = LocalDateTime.now(clock);
		try {
			LocalDateTime next = CronExpression.parse(newsProperties.collectCron()).next(now);
			return next == null ? nextMarketOpenTime() : next;
		} catch (IllegalArgumentException ex) {
			return nextMarketOpenTime();
		}
	}

	private LocalDateTime nextCryptoBatchTime() {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDateTime candidate = now.truncatedTo(ChronoUnit.HOURS).plusMinutes(CRYPTO_BATCH_MINUTE);
		return candidate.isAfter(now) ? candidate : candidate.plusHours(1);
	}

	private record CacheRead<T>(Optional<T> value, boolean redisHealthy) {

		private static <T> CacheRead<T> healthy(Optional<T> value) {
			return new CacheRead<>(value, true);
		}

		private static <T> CacheRead<T> unhealthy() {
			return new CacheRead<>(Optional.empty(), false);
		}
	}
}
