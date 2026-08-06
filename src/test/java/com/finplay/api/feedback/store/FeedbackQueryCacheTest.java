// FeedbackQueryCache의 키 조립·TTL 경계·음성 결과 미저장·킬 스위치·fail-open·Redis 장애 흡수를 Clock.fixed와 mock Redis로 검증하는 단위 테스트다 (ADR-0015).
package com.finplay.api.feedback.store;

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

import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.feedback.service.RedisLock;
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

// tasks.md 항목 2 — TTL 경계는 "지금"의 함수라 Clock.fixed 없이는 단정할 수 없고, 저장 여부·저장 안 함은 Redis
// 호출 유무가 유일한 관찰점이라 mock으로 본다. 실제 Redis에서의 상호 배제·TTL 만료는 RedisLockIntegrationTest가
// 이미 맡고 있으므로 여기서 다시 보지 않는다(항목 6·7이 실제 Redis로 캐시 경로를 통합 검증한다).
//
// 기대 키를 리터럴로 적는다 — 구현의 상수를 참조해 만들면 접두사가 바뀌어도 테스트가 함께 따라가 아무것도
// 고정하지 못한다. 키는 Redis에 남는 외부 계약이라 바뀌면 여기가 깨져야 한다.
class FeedbackQueryCacheTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final Long INSTRUMENT_ID = 7L;

	private static final LocalDate TRADE_DATE = LocalDate.of(2026, 8, 5);

	private static final String LOCK_TOKEN = "lock-token";

	// 절단 상한 기본값(§C-7 feedback.news.max-items-per-briefing).
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

	// Boot가 주는 것과 같은 종류(Jackson 3)의 진짜 매퍼를 쓴다 — 직렬화를 mock으로 흉내 내면 왕복이 실제로
	// 되는지(BriefingNewsItem.publishedAt 포함)를 이 테스트가 전혀 보지 못한다.
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

	// 대부분의 테스트가 쓰는 조립 — 캐시가 켜져 있고 락은 항상 획득된다(락 경합 자체는 아래 fail-open 테스트가 본다).
	private FeedbackQueryCache cacheAt(LocalDateTime now) {
		return cacheAt(now, MAX_ITEMS_PER_BRIEFING);
	}

	private FeedbackQueryCache cacheAt(LocalDateTime now, int maxItemsPerBriefing) {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(Optional.of(LOCK_TOKEN));
		return new FeedbackQueryCache(redisTemplate, redisLock, objectMapper, clockAt(now), properties(true),
			newsProperties(maxItemsPerBriefing));
	}

	// ── TTL 경계 ──────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("코인 요약 TTL은 다음 정시 05분까지다 — 10:03이면 2분")
	void cryptoSummaryTextExpiresAtTheNextHourlyFiveMinuteMark() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 10, 3));

		cache.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> Optional.of("코인 요약"));

		verify(valueOperations).set(CRYPTO_SUMMARY_KEY, "코인 요약", Duration.ofMinutes(2));
		verify(redisLock).tryLock(CRYPTO_SUMMARY_LOCK_KEY, Duration.ofMillis(1000));
		verify(redisLock).unlock(CRYPTO_SUMMARY_LOCK_KEY, LOCK_TOKEN);
	}

	// 05분을 이미 지났으면 이번 시각의 05분이 아니라 다음 시각의 05분이다 — 여기서 잘못 계산하면 TTL이 음수가
	// 되어 저장이 통째로 건너뛰어지고, 캐시가 켜져 있는데 적중률이 0인 상태가 로그 한 줄로만 남는다.
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

	// items만 만료가 다르다 — 텍스트는 배치가 만들면 그날 안 바뀌지만 이 목록은 market_news_items에서 재구성되고
	// 그 테이블은 feedback.news.collect-cron이 30분마다 계속 쓴다. 익일 09:00까지 잡아 두면 뒤늦게 색인된 전장
	// 기사가 그때까지 목록에 안 나오는데 예외도 로그도 없다(PR 리뷰 [권장 2]).
	@Test
	@DisplayName("주식 브리핑 items의 TTL은 익일 개장이 아니라 다음 수집 실행까지다 — 08:00이면 08:30")
	void stockBriefingItemsExpireAtTheNextNewsCollectionRun() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 8, 0));

		cache.getOrLoadStockBriefingItems(TRADE_DATE, () -> List.of(ITEM));

		verify(valueOperations)
			.set(eq(STOCK_BRIEFING_ITEMS_KEY), anyString(), eq(Duration.ofMinutes(30)));
	}

	// 주기를 리터럴로 다시 적지 않고 설정된 크론에서 얻는지 본다 — 크론을 바꾸면 TTL도 함께 따라와야 한다.
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

	// 정상 경로에서는 15:30 이후에 scope가 FULL이라 이 상태가 되지 않는다. 경계 계산이 틀어져 만료가 과거가
	// 되었을 때 음수 TTL로 Redis 명령 오류를 내지 않고 저장만 건너뛰는지를 고정한다.
	@Test
	@DisplayName("TTL이 이미 과거면 저장하지 않고 로더 결과만 반환한다")
	void doesNotStoreWhenTheComputedTtlIsNotPositive() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 16, 0));

		Optional<String> result = cache.getOrLoadStockSummaryText(
			INSTRUMENT_ID, TRADE_DATE, NewsSummaryScope.PRE_MARKET, () -> Optional.of("개장 전 요약"));

		assertThat(result).contains("개장 전 요약");
		verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
	}

	// ── 절단 상한이 키에 들어간다 ─────────────────────────────────────────────────────

	// 캐시하는 값이 절단 **후** 목록이라, 상한을 바꿔도 키가 같으면 옛 길이 목록이 TTL(최대 익일 09:00)까지
	// 그대로 나간다 — 예외도 로그도 없이 화면 목록 길이만 틀린다. 키가 갈리는 것이 유일한 방어다.
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

	// ── 음성 결과는 캐시하지 않는다 ───────────────────────────────────────────────────

	@Test
	@DisplayName("로더가 '없음'을 반환하면 저장하지 않는다")
	void doesNotStoreWhenTheLoaderReturnsEmpty() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 10, 3));

		Optional<String> result = cache.getOrLoadCryptoSummaryText(INSTRUMENT_ID, Optional::empty);

		assertThat(result).isEmpty();
		verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
		// 저장하지 않아도 락은 반드시 푼다 — 안 그러면 다음 요청들이 TTL 동안 전부 대기한다.
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

	// ── 캐시 적중 ─────────────────────────────────────────────────────────────────────

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

	// 형식이 바뀐 옛 값이 남은 경우다. 예외를 던져 조회를 실패시키면 배포 직후 전 사용자가 500을 본다.
	@Test
	@DisplayName("역직렬화할 수 없는 값이 남아 있으면 캐시 미스로 취급해 로더 결과를 낸다")
	void treatsAnUndecodableCachedValueAsAMiss() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 8, 0));
		when(valueOperations.get(STOCK_BRIEFING_ITEMS_KEY)).thenReturn("이건 JSON 배열이 아니다");

		List<BriefingNewsItem> result = cache.getOrLoadStockBriefingItems(TRADE_DATE, () -> List.of(ITEM));

		assertThat(result).containsExactly(ITEM);
		verify(valueOperations).set(eq(STOCK_BRIEFING_ITEMS_KEY), anyString(), any(Duration.class));
	}

	// ── 킬 스위치 ─────────────────────────────────────────────────────────────────────

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

	// ── 무효화 ────────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("enabled=true면 무효화가 해당 코인 키를 지운다")
	void evictDeletesTheCryptoKeys() {
		FeedbackQueryCache cache = cacheAt(LocalDateTime.of(2026, 8, 5, 10, 3));

		cache.evictCryptoSummaryText(INSTRUMENT_ID);
		cache.evictCryptoBriefingText();

		verify(redisTemplate).delete(CRYPTO_SUMMARY_KEY);
		verify(redisTemplate).delete(CRYPTO_BRIEFING_TEXT_KEY);
	}

	// ── Redis 장애 ────────────────────────────────────────────────────────────────────

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

	// ── 락을 얻지 못했을 때 ───────────────────────────────────────────────────────────

	// 대기 중 락 보유자가 채운 값을 보면 원본을 부르지 않는다 — 이것이 쏠림 방어의 본체다.
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

	// fail-open이 캐시에 쓰면, 늦게 깨어난 이 로더의 옛 값이 락 보유자가 이미 채운 새 값을 덮어쓴다.
	// 락 없는 쓰기라 그 덮어쓰기는 어떤 순서 보장도 받지 못한다 — 그래서 이 경로는 반드시 읽기 전용이다.
	@Test
	@DisplayName("대기가 타임아웃되면 로더 결과를 반환하되 캐시에 쓰지 않는다(fail-open)")
	void failOpenReturnsTheLoaderResultWithoutWritingToTheCache() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(Optional.empty());
		when(valueOperations.get(CRYPTO_SUMMARY_KEY)).thenReturn(null);
		// 대기를 짧게 잡아 테스트가 기본값 300ms를 기다리지 않게 한다 — 검증 대상은 시간이 아니라 "쓰지 않는다"다.
		FeedbackQueryCache cache = new FeedbackQueryCache(redisTemplate, redisLock, objectMapper,
			clockAt(LocalDateTime.of(2026, 8, 5, 10, 3)),
			new FeedbackQueryCacheProperties(true, 1000, 40, 10), newsProperties(MAX_ITEMS_PER_BRIEFING));

		Optional<String> result = cache.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> Optional.of("원본 요약"));

		assertThat(result).contains("원본 요약");
		verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
		// 얻지도 못한 락을 풀면 남의 락을 건드리는 것이다 — RedisLock이 토큰으로 막지만 애초에 부르지 않아야 한다.
		verify(redisLock, never()).unlock(anyString(), anyString());
	}

	/*
	 * 대기 루프 안에서 Redis가 불건전해지는 경로다. skipsTheWaitEntirelyWhenRedisIsDown(통합)이 보는 것은
	 * **첫 읽기** 시점의 불건전이라 이 분기를 지나가지 않는다 — 그쪽은 락을 시도하기도 전에 빠져나간다.
	 *
	 * 여기서 갈리는 것은 소요뿐이다. 이 분기가 없어도 결국 타임아웃 뒤 같은 답(로더 결과)을 내므로
	 * "정답이 나온다"로는 두 구현이 구분되지 않는다. 그래서 wait-millis를 3초로 크게 잡고 1초 미만을 단정한다.
	 */
	@Test
	@DisplayName("대기 도중 Redis가 불건전해지면 남은 wait-millis를 태우지 않고 즉시 원본으로 내려간다")
	void leavesTheWaitImmediatelyWhenRedisTurnsUnhealthyMidWait() {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(Optional.empty());
		// 첫 읽기는 정상적인 미스(null), 대기 루프 안의 두 번째 읽기부터 장애다.
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

	/*
	 * 요청 스레드가 취소된 상황이다. 계속 폴링하는 것이 더 나쁘므로 즉시 원본으로 내려가되,
	 * **인터럽트 상태를 복원해야 한다** — 복원을 빠뜨리면 취소 신호가 이 메서드에서 소멸해 위쪽(서블릿 컨테이너·
	 * 상위 실행자)이 취소를 영영 알지 못한다. 결과값만 보면 복원한 구현과 안 한 구현이 똑같으므로
	 * isInterrupted를 직접 단정한다.
	 *
	 * Thread.interrupted()로 읽으면서 동시에 플래그를 지운다 — 남겨 두면 같은 스레드에서 도는 뒤 테스트들이
	 * 엉뚱하게 InterruptedException을 맞는다.
	 */
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
		// 인터럽트로 빠져나온 경로도 fail-open이므로 캐시에 쓰지 않는다.
		verify(valueOperations, never()).set(anyString(), anyString(), any(Duration.class));
	}

	// 로더 예외가 락을 TTL까지 붙잡고 있으면, 그 사이 같은 키의 모든 요청이 대기 후 fail-open으로 DB에 직행한다.
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
