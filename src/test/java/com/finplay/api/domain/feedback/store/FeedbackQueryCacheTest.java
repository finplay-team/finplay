package com.finplay.api.domain.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.domain.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NewsSummaryScope;
import com.finplay.api.global.lock.RedisLock;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

class FeedbackQueryCacheTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long INSTRUMENT_ID = 7L;

	private static final LocalDate TRADE_DATE = LocalDate.of(2026, 8, 5);

	private static final String LOCK_TOKEN = "lock-token";

	private static final int MAX_ITEMS_PER_BRIEFING = 30;

	private static final String CRYPTO_SUMMARY_KEY = "feedback:query-cache:v1:crypto-summary:7";

	private static final String CRYPTO_SUMMARY_LOCK_KEY = "feedback:query-cache:lock:v1:crypto-summary:7";

	private static final String STOCK_SUMMARY_PRE_MARKET_KEY = "feedback:query-cache:v1:stock-summary:7:2026-08-05:PRE_MARKET";

	private static final String STOCK_SUMMARY_FULL_KEY = "feedback:query-cache:v1:stock-summary:7:2026-08-05:FULL";

	private static final String STOCK_BRIEFING_TEXT_KEY = "feedback:query-cache:v1:stock-briefing-text:2026-08-05";

	private static final String STOCK_BRIEFING_ITEMS_KEY = "feedback:query-cache:v1:stock-briefing-items:2026-08-05:30";

	private static final String CRYPTO_BRIEFING_TEXT_KEY = "feedback:query-cache:v1:crypto-briefing-text";

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

	private final RedisLock redisLock = mock(RedisLock.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private static final BriefingNewsItem ITEM = new BriefingNewsItem(
		INSTRUMENT_ID, "005930", "삼성전자", MarketNewsItemType.NEWS, "브리핑 기사", "테스트경제",
		"https://news.example.com/1", LocalDateTime.of(2026, 8, 4, 16, 0));

	private static FeedbackQueryCacheProperties properties(boolean enabled) {
		return new FeedbackQueryCacheProperties(enabled, 1000, 300, 20);
	}

	private static FeedbackNewsProperties newsProperties(int maxItemsPerBriefing) {
		return new FeedbackNewsProperties(
			"0 0/30 * * * *", "0 0/30 8-20 * * MON-FRI", 30, 5, 5, 50, maxItemsPerBriefing, 30);
	}

	private static Clock clockAt(LocalDateTime now) {
		return Clock.fixed(now.atZone(KST).toInstant(), KST);
	}

	private FeedbackQueryCache cacheAt(LocalDateTime now) {
		return cacheAt(now, MAX_ITEMS_PER_BRIEFING);
	}

	private FeedbackQueryCache cacheAt(LocalDateTime now, int maxItemsPerBriefing) {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(Optional.of(LOCK_TOKEN));
		return new FeedbackQueryCache(redisTemplate, redisLock, objectMapper, clockAt(now), properties(true),
			newsProperties(maxItemsPerBriefing));
	}


	@Test
	@DisplayName("코인 요약 TTL은 다음 정시 05분까지다 — 10:03이면 2분")
	void cryptoSummaryTextExpiresAtTheNextHourlyFiveMinuteMark() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 10, 3));

		cache.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> Optional.of("코인 요약"));

		verify(valueOperations).set(CRYPTO_SUMMARY_KEY, "코인 요약", Duration.ofMinutes(2));
		verify(redisLock).tryLock(CRYPTO_SUMMARY_LOCK_KEY, Duration.ofMillis(1000));
		verify(redisLock).unlock(CRYPTO_SUMMARY_LOCK_KEY, LOCK_TOKEN);
	}

	@Test
	@DisplayName("코인 요약 TTL은 05분을 지난 시각이면 다음 시각 05분으로 넘어간다 — 10:07이면 58분, 정각 10:05면 60분")
	void cryptoSummaryTtlRollsToTheNextHourWhenNowIsAtOrPastFiveMinutes() {
		cacheAt(LocalDateTime.of(2026, 8, 5, 10, 7))
			.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> Optional.of("코인 요약"));
		verify(valueOperations).set(CRYPTO_SUMMARY_KEY, "코인 요약", Duration.ofMinutes(58));

		cacheAt(LocalDateTime.of(2026, 8, 5, 10, 5))
			.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> Optional.of("코인 요약"));
		verify(valueOperations).set(CRYPTO_SUMMARY_KEY, "코인 요약", Duration.ofMinutes(60));
	}

	@Test
	@DisplayName("주식 요약 PRE_MARKET의 TTL은 오늘 15:30까지다")
	void stockSummaryPreMarketExpiresAtTodayMarketClose() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 10, 0));

		cache.getOrLoadStockSummaryText(
			INSTRUMENT_ID, TRADE_DATE, NewsSummaryScope.PRE_MARKET, () -> Optional.of("개장 전 요약"));

		verify(valueOperations)
			.set(STOCK_SUMMARY_PRE_MARKET_KEY, "개장 전 요약", Duration.ofHours(5).plusMinutes(30));
	}

	@Test
	@DisplayName("주식 요약 FULL의 TTL은 익일 09:00까지다")
	void stockSummaryFullExpiresAtTheNextMarketOpen() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 16, 0));

		cache.getOrLoadStockSummaryText(
			INSTRUMENT_ID, TRADE_DATE, NewsSummaryScope.FULL, () -> Optional.of("장 마감 요약"));

		verify(valueOperations).set(STOCK_SUMMARY_FULL_KEY, "장 마감 요약", Duration.ofHours(17));
	}

	@Test
	@DisplayName("주식 브리핑 텍스트의 TTL은 익일 09:00까지다")
	void stockBriefingTextExpiresAtTheNextMarketOpen() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 8, 0));

		cache.getOrLoadStockBriefingText(TRADE_DATE, () -> Optional.of("브리핑 본문"));

		verify(valueOperations).set(STOCK_BRIEFING_TEXT_KEY, "브리핑 본문", Duration.ofHours(25));
	}

	@Test
	@DisplayName("주식 브리핑 items의 TTL은 익일 개장이 아니라 다음 수집 실행까지다 — 08:00이면 08:30")
	void stockBriefingItemsExpireAtTheNextNewsCollectionRun() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 8, 0));

		cache.getOrLoadStockBriefingItems(TRADE_DATE, () -> List.of(ITEM));

		verify(valueOperations)
			.set(eq(STOCK_BRIEFING_ITEMS_KEY), anyString(), eq(Duration.ofMinutes(30)));
	}

	@Test
	@DisplayName("수집 크론을 10분 간격으로 바꾸면 items TTL도 그 주기를 따른다")
	void stockBriefingItemsTtlFollowsTheConfiguredCollectCron() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(Optional.of(LOCK_TOKEN));
		FeedbackNewsProperties everyTenMinutes = new FeedbackNewsProperties(
			"0 0/10 * * * *", "0 0/30 8-20 * * MON-FRI", 30, 5, 5, 50, MAX_ITEMS_PER_BRIEFING, 30);
		FeedbackQueryCache cache = new FeedbackQueryCache(redisTemplate, redisLock, objectMapper,
			clockAt(LocalDateTime.of(2026, 8, 5, 8, 0)), properties(true), everyTenMinutes);

		cache.getOrLoadStockBriefingItems(TRADE_DATE, () -> List.of(ITEM));

		verify(valueOperations)
			.set(eq(STOCK_BRIEFING_ITEMS_KEY), anyString(), eq(Duration.ofMinutes(10)));
	}

	@Test
	@DisplayName("코인 브리핑 텍스트는 종목 구성요소 없는 단일 키이고 TTL은 다음 정시 05분이다")
	void cryptoBriefingTextUsesASingleKeyAndTheHourlyTtl() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 10, 3));

		cache.getOrLoadCryptoBriefingText(() -> Optional.of("코인 브리핑"));

		verify(valueOperations).set(CRYPTO_BRIEFING_TEXT_KEY, "코인 브리핑", Duration.ofMinutes(2));
	}

	@Test
	@DisplayName("TTL이 이미 과거면 저장하지 않고 로더 결과만 반환한다")
	void doesNotStoreWhenTheComputedTtlIsNotPositive() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 16, 0));

		Optional<String> result = cache.getOrLoadStockSummaryText(
			INSTRUMENT_ID, TRADE_DATE, NewsSummaryScope.PRE_MARKET, () -> Optional.of("개장 전 요약"));

		assertThat(result).contains("개장 전 요약");
		verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
	}


	@Test
	@DisplayName("브리핑 items 키에 절단 상한이 들어가 max-items-per-briefing을 바꾸면 다른 키가 된다")
	void stockBriefingItemsKeyIncludesTheTruncationLimitSoChangingItSplitsTheKey() {
		cacheAt(LocalDateTime.of(2026, 8, 5, 8, 0), 30).getOrLoadStockBriefingItems(TRADE_DATE, () -> List.of(ITEM));
		cacheAt(LocalDateTime.of(2026, 8, 5, 8, 0), 10).getOrLoadStockBriefingItems(TRADE_DATE, () -> List.of(ITEM));

		ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
		verify(valueOperations, org.mockito.Mockito.times(2))
			.set(keys.capture(), anyString(), any(Duration.class));

		assertThat(keys.getAllValues())
			.containsExactly(
				"feedback:query-cache:v1:stock-briefing-items:2026-08-05:30",
				"feedback:query-cache:v1:stock-briefing-items:2026-08-05:10");
	}


	@Test
	@DisplayName("로더가 '없음'을 반환하면 저장하지 않는다")
	void doesNotStoreWhenTheLoaderReturnsEmpty() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 10, 3));

		Optional<String> result = cache.getOrLoadCryptoSummaryText(INSTRUMENT_ID, Optional::empty);

		assertThat(result).isEmpty();
		verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
		verify(redisLock).unlock(anyString(), eq(LOCK_TOKEN));
	}

	@Test
	@DisplayName("브리핑 items 로더가 빈 목록을 반환하면 저장하지 않는다")
	void doesNotStoreWhenTheBriefingItemsLoaderReturnsAnEmptyList() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 8, 0));

		List<BriefingNewsItem> result = cache.getOrLoadStockBriefingItems(TRADE_DATE, List::of);

		assertThat(result).isEmpty();
		verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
	}


	@Test
	@DisplayName("캐시에 값이 있으면 로더도 락도 건드리지 않고 그 값을 반환한다")
	void returnsTheCachedTextWithoutCallingTheLoaderOrTheLock() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 10, 3));
		when(valueOperations.get(CRYPTO_SUMMARY_KEY)).thenReturn("캐시된 요약");
		AtomicInteger loaderCalls = new AtomicInteger();

		Optional<String> result = cache.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> {
			loaderCalls.incrementAndGet();
			return Optional.of("원본 요약");
		});

		assertThat(result).contains("캐시된 요약");
		assertThat(loaderCalls).hasValue(0);
		verify(redisLock, never()).tryLock(anyString(), any(Duration.class));
		verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
	}

	@Test
	@DisplayName("브리핑 items는 직렬화한 그대로 왕복한다 — 저장된 문자열을 다시 넣으면 같은 목록이 나온다")
	void briefingItemsRoundTripThroughTheCachedString() {
		FeedbackQueryCache writing = cacheAt(LocalDateTime.of(2026, 8, 5, 8, 0));
		writing.getOrLoadStockBriefingItems(TRADE_DATE, () -> List.of(ITEM));
		ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
		verify(valueOperations).set(eq(STOCK_BRIEFING_ITEMS_KEY), stored.capture(), any(Duration.class));

		when(valueOperations.get(STOCK_BRIEFING_ITEMS_KEY)).thenReturn(stored.getValue());
		List<BriefingNewsItem> result = cacheAt(LocalDateTime.of(2026, 8, 5, 8, 0))
			.getOrLoadStockBriefingItems(TRADE_DATE, () -> {
				throw new AssertionError("캐시 적중이면 로더가 불리면 안 된다");
			});

		assertThat(result).containsExactly(ITEM);
	}

	@Test
	@DisplayName("역직렬화할 수 없는 값이 남아 있으면 캐시 미스로 취급해 로더 결과를 낸다")
	void treatsAnUndecodableCachedValueAsAMiss() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 8, 0));
		when(valueOperations.get(STOCK_BRIEFING_ITEMS_KEY)).thenReturn("이건 JSON 배열이 아니다");

		List<BriefingNewsItem> result = cache.getOrLoadStockBriefingItems(TRADE_DATE, () -> List.of(ITEM));

		assertThat(result).containsExactly(ITEM);
		verify(valueOperations).set(eq(STOCK_BRIEFING_ITEMS_KEY), anyString(), any(Duration.class));
	}


	@Test
	@DisplayName("enabled=false면 조회가 Redis도 락도 전혀 접촉하지 않고 로더 결과를 그대로 낸다")
	void disabledSkipsRedisAndTheLockEntirely() {
		StringRedisTemplate untouchedTemplate = mock(StringRedisTemplate.class);
		RedisLock untouchedLock = mock(RedisLock.class);
		FeedbackQueryCache cache = new FeedbackQueryCache(untouchedTemplate, untouchedLock, objectMapper,
			clockAt(LocalDateTime.of(2026, 8, 5, 10, 3)), properties(false), newsProperties(MAX_ITEMS_PER_BRIEFING));

		assertThat(cache.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> Optional.of("원본"))).contains("원본");
		assertThat(cache.getOrLoadStockSummaryText(
			INSTRUMENT_ID, TRADE_DATE, NewsSummaryScope.FULL, () -> Optional.of("원본"))).contains("원본");
		assertThat(cache.getOrLoadStockBriefingText(TRADE_DATE, () -> Optional.of("원본"))).contains("원본");
		assertThat(cache.getOrLoadStockBriefingItems(TRADE_DATE, () -> List.of(ITEM))).containsExactly(ITEM);
		assertThat(cache.getOrLoadCryptoBriefingText(() -> Optional.of("원본"))).contains("원본");

		verifyNoInteractions(untouchedTemplate, untouchedLock);
	}

	@Test
	@DisplayName("enabled=false면 무효화도 Redis를 접촉하지 않는다")
	void disabledEvictDoesNotTouchRedis() {
		StringRedisTemplate untouchedTemplate = mock(StringRedisTemplate.class);
		FeedbackQueryCache cache = new FeedbackQueryCache(untouchedTemplate, redisLock, objectMapper,
			clockAt(LocalDateTime.of(2026, 8, 5, 10, 3)), properties(false), newsProperties(MAX_ITEMS_PER_BRIEFING));

		cache.evictCryptoSummaryText(INSTRUMENT_ID);
		cache.evictCryptoBriefingText();

		verifyNoInteractions(untouchedTemplate);
	}


	@Test
	@DisplayName("enabled=true면 무효화가 해당 코인 키를 지운다")
	void evictDeletesTheCryptoKeys() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 10, 3));

		cache.evictCryptoSummaryText(INSTRUMENT_ID);
		cache.evictCryptoBriefingText();

		verify(redisTemplate).delete(CRYPTO_SUMMARY_KEY);
		verify(redisTemplate).delete(CRYPTO_BRIEFING_TEXT_KEY);
	}


	@Test
	@DisplayName("Redis 읽기·쓰기가 예외를 던져도 로더 결과가 그대로 나오고 예외가 새지 않는다")
	void swallowsRedisFailuresOnBothReadAndWriteAndStillReturnsTheLoaderResult() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 10, 3));
		when(valueOperations.get(CRYPTO_SUMMARY_KEY)).thenThrow(new RuntimeException("Redis 장애"));
		doThrow(new RuntimeException("Redis 장애"))
			.when(valueOperations).set(anyString(), anyString(), any(Duration.class));

		Optional<String> result = cache.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> Optional.of("원본 요약"));

		assertThat(result).contains("원본 요약");
	}

	@Test
	@DisplayName("무효화 중 Redis가 예외를 던져도 호출부로 새지 않는다")
	void swallowsRedisFailureDuringEvict() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 10, 3));
		when(redisTemplate.delete(anyString())).thenThrow(new RuntimeException("Redis 장애"));

		assertThatCode(() -> cache.evictCryptoSummaryText(INSTRUMENT_ID)).doesNotThrowAnyException();
	}


	@Test
	@DisplayName("대기 중 락 보유자가 채운 값이 보이면 그 값을 쓰고 로더를 부르지 않는다")
	void usesTheValueTheLockHolderWroteDuringTheWait() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(Optional.empty());
		when(valueOperations.get(CRYPTO_SUMMARY_KEY)).thenReturn(null, "락 보유자가 채운 값");
		FeedbackQueryCache cache = new FeedbackQueryCache(redisTemplate, redisLock, objectMapper,
			clockAt(LocalDateTime.of(2026, 8, 5, 10, 3)), properties(true), newsProperties(MAX_ITEMS_PER_BRIEFING));

		Optional<String> result = cache.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> {
			throw new AssertionError("대기 중 값이 채워졌으면 로더가 불리면 안 된다");
		});

		assertThat(result).contains("락 보유자가 채운 값");
	}

	@Test
	@DisplayName("대기가 타임아웃되면 로더 결과를 반환하되 캐시에 쓰지 않는다(fail-open)")
	void failOpenReturnsTheLoaderResultWithoutWritingToTheCache() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(Optional.empty());
		when(valueOperations.get(CRYPTO_SUMMARY_KEY)).thenReturn(null);
		FeedbackQueryCache cache = new FeedbackQueryCache(redisTemplate, redisLock, objectMapper,
			clockAt(LocalDateTime.of(2026, 8, 5, 10, 3)),
			new FeedbackQueryCacheProperties(true, 1000, 40, 10), newsProperties(MAX_ITEMS_PER_BRIEFING));

		Optional<String> result = cache.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> Optional.of("원본 요약"));

		assertThat(result).contains("원본 요약");
		verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
		verify(redisLock, never()).unlock(anyString(), anyString());
	}

	@Test
	@DisplayName("대기 도중 Redis가 불건전해지면 남은 wait-millis를 태우지 않고 즉시 원본으로 내려간다")
	void leavesTheWaitImmediatelyWhenRedisTurnsUnhealthyMidWait() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(Optional.empty());
		when(valueOperations.get(CRYPTO_SUMMARY_KEY))
			.thenReturn(null)
			.thenThrow(new RuntimeException("Redis 장애"));
		FeedbackQueryCache cache = new FeedbackQueryCache(redisTemplate, redisLock, objectMapper,
			clockAt(LocalDateTime.of(2026, 8, 5, 10, 3)),
			new FeedbackQueryCacheProperties(true, 1000, 3000, 20), newsProperties(MAX_ITEMS_PER_BRIEFING));

		long startedAt = System.nanoTime();
		Optional<String> result = cache.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> Optional.of("원본 요약"));
		long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

		assertThat(result).contains("원본 요약");
		assertThat(elapsedMillis)
			.as("wait-millis 3000을 다 채웠다면 대기 루프의 불건전 판정이 동작하지 않은 것이다")
			.isLessThan(1000L);
	}

	@Test
	@DisplayName("대기 중 인터럽트되면 원본으로 내려가면서 인터럽트 상태를 복원한다")
	void restoresTheInterruptFlagWhenTheWaitIsInterrupted() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(Optional.empty());
		when(valueOperations.get(CRYPTO_SUMMARY_KEY)).thenReturn(null);
		FeedbackQueryCache cache = new FeedbackQueryCache(redisTemplate, redisLock, objectMapper,
			clockAt(LocalDateTime.of(2026, 8, 5, 10, 3)),
			new FeedbackQueryCacheProperties(true, 1000, 3000, 20), newsProperties(MAX_ITEMS_PER_BRIEFING));

		Thread.currentThread().interrupt();
		Optional<String> result;
		boolean stillInterrupted;
		try {
			result = cache.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> Optional.of("원본 요약"));
		} finally {
			stillInterrupted = Thread.interrupted();
		}

		assertThat(result).as("취소돼도 응답은 준다").contains("원본 요약");
		assertThat(stillInterrupted).as("인터럽트 상태를 삼키면 취소 신호가 여기서 사라진다").isTrue();
		verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
	}

	@Test
	@DisplayName("로더가 예외를 던져도 락은 풀린다(예외 자체는 호출부로 전파된다)")
	void unlocksEvenWhenTheLoaderThrows() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 10, 3));

		assertThatThrownBy(() -> cache.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> {
			throw new IllegalStateException("DB 장애");
		})).isInstanceOf(IllegalStateException.class);

		verify(redisLock).unlock(CRYPTO_SUMMARY_LOCK_KEY, LOCK_TOKEN);
	}
}
