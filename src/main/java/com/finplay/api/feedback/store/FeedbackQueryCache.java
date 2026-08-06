// 요약·브리핑 조회의 시각 비의존 조각만 Redis에 캐시하는 단일 창구 — 키 조립·TTL 계산·직렬화·쏠림 방어 락을 전부 여기서만 한다 (ADR-0015)
package com.finplay.api.feedback.store;

import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.feedback.domain.NewsSummaryScope;
import com.finplay.api.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.feedback.service.MarketSessionTimes;
import com.finplay.api.feedback.service.RedisLock;
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
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 요약·브리핑은 전 회원이 같은 값을 보지만 <b>응답 전체가 그런 것은 아니다</b> — 주식 요약 {@code items}와 코인
 * {@code items}는 상한이 {@code now}라 시각의 함수이고, 그 상한이 §C-5 노출 게이트의 구현 그 자체다. 그래서
 * 조회 메서드를 통째로 캐시하지 않고 <b>시각 비의존 조각 5개만</b> 캐시한다(ADR-0015 §1). 캐시 대상이 아닌
 * 목록은 매 요청 DB로 간다 — 그것이 조회 계약을 한 글자도 바꾸지 않는 이유다.
 *
 * <p><b>{@code @EnableCaching}·{@code @Cacheable}을 쓰지 않는다</b>(ADR-0015 §5). 만료가 고정 초가 아니라
 * "다음 갱신 시점까지"라 캐시 이름 단위 TTL로 표현할 자리가 없고, 아래 분산 락을 끼울 자리도 없다. 대신
 * {@code PriceStore}·{@code RankingStore}·{@code CryptoWatchLock}과 같은 형태로 {@code StringRedisTemplate}을
 * 직접 쓰고 <b>키 조립을 이 클래스 하나에 가둔다</b>({@code docs/conventions.md}).
 *
 * <p><b>fail-open이다</b>(ADR-0015 §6). Redis 접촉(조회·저장·락·무효화)에서 나는 {@code RuntimeException}은 전부
 * 삼켜 캐시 미스와 같게 취급하고 원본으로 내려간다. 캐시는 있으면 빠르고 없으면 DB로 가는 것이지, 없으면 장애가
 * 되는 구조가 아니다 — 부가 기능인 배치(#244, fail-closed)와 사용자가 기다리는 조회 응답의 차이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackQueryCache {

	private static final String KEY_PREFIX = "feedback:query-cache:";

	private static final String LOCK_KEY_PREFIX = "feedback:query-cache:lock:";

	private static final String STOCK_SUMMARY_ITEM = "stock-summary";

	private static final String CRYPTO_SUMMARY_ITEM = "crypto-summary";

	private static final String STOCK_BRIEFING_TEXT_ITEM = "stock-briefing-text";

	private static final String STOCK_BRIEFING_ITEMS_ITEM = "stock-briefing-items";

	// 코인 브리핑은 시장 전체에 한 벌뿐이라 키 구성요소가 없다(ADR-0015 §1).
	private static final String CRYPTO_BRIEFING_TEXT_ITEM = "crypto-briefing-text";

	private static final String KEY_DELIMITER = ":";

	// 코인 갱신 배치(feedback.batch.crypto-cron = "0 5 * * * *")가 도는 분.
	private static final int CRYPTO_BATCH_MINUTE = 5;

	private static final TypeReference<List<BriefingNewsItem>> BRIEFING_ITEMS_TYPE = new TypeReference<>() {};

	private final StringRedisTemplate redisTemplate;

	private final RedisLock redisLock;

	// Boot가 제공하는 빈을 그대로 주입받는다 — 직접 new ObjectMapper()를 만들면 java.time 설정이 응답 직렬화와
	// 갈려 BriefingNewsItem.publishedAt이 다른 표현으로 저장된다.
	private final ObjectMapper objectMapper;

	private final Clock clock;

	private final FeedbackQueryCacheProperties properties;

	// items 키에 넣을 절단 상한(max-items-per-briefing)을 읽는 용도로만 쓴다.
	private final FeedbackNewsProperties newsProperties;

	/**
	 * 주식 요약 텍스트. {@code scope}가 키에 있는 것이 장 마감 전후 전환의 정확성을 담당한다 — 15:30을 넘기면
	 * 조회가 {@code FULL} 키를 보므로 {@code PRE_MARKET} 값이 남아 있어도 노출되지 않는다. TTL은 안전망이지
	 * 정확성의 근거가 아니다(ADR-0015 §1).
	 */
	public Optional<String> getOrLoadStockSummaryText(Long instrumentId, LocalDate originTradeDate,
		NewsSummaryScope scope, Supplier<Optional<String>> loader) {
		String suffix = STOCK_SUMMARY_ITEM + KEY_DELIMITER + instrumentId + KEY_DELIMITER + originTradeDate
			+ KEY_DELIMITER + scope.name();
		return getOrLoadText(suffix, stockSummaryExpiresAt(scope), loader);
	}

	/** 코인 요약 텍스트. 매시 05분 배치가 갱신하므로 만료도 다음 정시 05분이다. */
	public Optional<String> getOrLoadCryptoSummaryText(Long instrumentId, Supplier<Optional<String>> loader) {
		return getOrLoadText(CRYPTO_SUMMARY_ITEM + KEY_DELIMITER + instrumentId, nextCryptoBatchTime(), loader);
	}

	/** 주식 브리핑 텍스트. 한 번 생긴 값은 그날 안 바뀌므로 만료는 익일 개장이다. */
	public Optional<String> getOrLoadStockBriefingText(LocalDate originTradeDate, Supplier<Optional<String>> loader) {
		return getOrLoadText(
			STOCK_BRIEFING_TEXT_ITEM + KEY_DELIMITER + originTradeDate, nextMarketOpenTime(), loader);
	}

	/**
	 * 주식 브리핑 {@code items}. 구간이 {@code [D-1 15:30, D 09:00]}로 고정이라 시각 비의존인 유일한 목록이다.
	 *
	 * <p><b>키에 절단 상한({@code feedback.news.max-items-per-briefing})이 들어간다.</b> 캐시하는 값이 절단
	 * <i>후</i> 목록이므로, 설정을 바꿔 재배포해도 키가 갈리지 않으면 Redis에 남은 옛 길이 목록이 TTL(최대 익일
	 * 09:00)까지 그대로 나간다 — 예외도 로그도 없이 화면 목록 길이만 틀리는 형태다. 값을 바꾸면 키가 자연히
	 * 갈려 옛 목록은 아무도 읽지 않고 TTL로 사라진다.
	 */
	public List<BriefingNewsItem> getOrLoadStockBriefingItems(LocalDate originTradeDate,
		Supplier<List<BriefingNewsItem>> loader) {
		String suffix = STOCK_BRIEFING_ITEMS_ITEM + KEY_DELIMITER + originTradeDate + KEY_DELIMITER
			+ newsProperties.maxItemsPerBriefing();
		return getOrLoad(suffix, nextMarketOpenTime(), loader, this::readBriefingItems, this::writeBriefingItems);
	}

	/** 코인 브리핑 텍스트. 시장 전체에 한 벌이라 단일 키다. */
	public Optional<String> getOrLoadCryptoBriefingText(Supplier<Optional<String>> loader) {
		return getOrLoadText(CRYPTO_BRIEFING_TEXT_ITEM, nextCryptoBatchTime(), loader);
	}

	/**
	 * 코인 요약 갱신 배치가 <b>실제로 갱신에 성공했을 때만</b> 호출한다(ADR-0015 §3). 코인은 값이 있는 상태에서
	 * 바뀌는 유일한 경우라 TTL만으로는 "오래된 값이 남지 않는다"를 만족할 수 없다.
	 */
	public void evictCryptoSummaryText(Long instrumentId) {
		evict(CRYPTO_SUMMARY_ITEM + KEY_DELIMITER + instrumentId);
	}

	/** 코인 브리핑 갱신 배치가 실제로 갱신에 성공했을 때만 호출한다. */
	public void evictCryptoBriefingText() {
		evict(CRYPTO_BRIEFING_TEXT_ITEM);
	}

	// 텍스트 4종은 문자열 그대로 저장한다. 로더가 Optional.empty()면 저장하지 않는다 — 그것이 EMPTY/UNAVAILABLE
	// 상태이고, 배치가 아직 안 돈 시각의 "없음"을 캐시하면 배치가 만든 뒤에도 옛 상태가 남는다(ADR-0015 §3).
	private Optional<String> getOrLoadText(String suffix, LocalDateTime expiresAt,
		Supplier<Optional<String>> loader) {
		return getOrLoad(suffix, expiresAt, loader, value -> Optional.of(Optional.of(value)), Function.identity());
	}

	/**
	 * 캐시 확인 → 락 → 로더 → 저장의 공통 경로다.
	 *
	 * <p>{@code decoder}는 Redis에 있던 문자열을 값으로 되돌리고, 되돌릴 수 없으면(형식이 바뀐 옛 값) 빈 값을
	 * 반환해 <b>캐시 미스로 취급</b>된다. {@code encoder}는 저장할 문자열을 만들고, <b>음성 결과면 빈 값을 반환해
	 * 저장을 건너뛴다.</b>
	 */
	private <T> T getOrLoad(String suffix, LocalDateTime expiresAt, Supplier<T> loader,
		Function<String, Optional<T>> decoder, Function<T, Optional<String>> encoder) {
		// 킬 스위치가 내려가 있으면 Redis를 아예 접촉하지 않는다 — 폴백(DB 직행)이 이미 검증된 기존 경로다.
		if (!properties.enabled()) {
			return loader.get();
		}
		String key = KEY_PREFIX + suffix;
		Optional<T> cached = read(key, decoder);
		if (cached.isPresent()) {
			return cached.get();
		}
		Optional<String> token = redisLock.tryLock(
			LOCK_KEY_PREFIX + suffix, Duration.ofMillis(properties.lockTtlMillis()));
		if (token.isPresent()) {
			try {
				T loaded = loader.get();
				encoder.apply(loaded).ifPresent(value -> write(key, value, expiresAt));
				return loaded;
			} finally {
				// 로더가 예외를 던져도 락이 TTL까지 남지 않게 한다.
				redisLock.unlock(LOCK_KEY_PREFIX + suffix, token.get());
			}
		}
		// 락을 쥔 요청이 곧 채우므로 그 값을 기다린다.
		Optional<T> awaited = awaitCachedValue(key, decoder);
		if (awaited.isPresent()) {
			return awaited.get();
		}
		// 대기 타임아웃 → 원본 직행(fail-open). 조회 API는 응답을 반드시 줘야 하므로 락을 못 얻었다고 실패시키지
		// 않는다. **이 경로는 캐시에 쓰지 않는다** — 락을 쥔 쪽이 이미 채웠을 새 값을, 늦게 깨어난 이 로더의 옛
		// 값으로 덮어쓸 수 있기 때문이다(락 없는 쓰기를 만들 이유도 없다).
		return loader.get();
	}

	private <T> Optional<T> awaitCachedValue(String key, Function<String, Optional<T>> decoder) {
		long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.waitMillis());
		while (System.nanoTime() < deadlineNanos) {
			try {
				Thread.sleep(properties.pollMillis());
			} catch (InterruptedException ex) {
				// 인터럽트를 삼키지 않는다 — 상태를 복원하고 즉시 원본으로 내려간다. 요청 스레드가 취소된
				// 상황에서 계속 폴링하는 것이 더 나쁘다.
				Thread.currentThread().interrupt();
				return Optional.empty();
			}
			Optional<T> cached = read(key, decoder);
			if (cached.isPresent()) {
				return cached;
			}
		}
		return Optional.empty();
	}

	private <T> Optional<T> read(String key, Function<String, Optional<T>> decoder) {
		try {
			String value = redisTemplate.opsForValue().get(key);
			return value == null ? Optional.empty() : decoder.apply(value);
		} catch (RuntimeException ex) {
			log.warn("조회 캐시 읽기 실패(Redis 장애) - key={}", key, ex);
			return Optional.empty();
		}
	}

	private void write(String key, String value, LocalDateTime expiresAt) {
		Duration ttl = Duration.between(LocalDateTime.now(clock), expiresAt);
		if (ttl.isZero() || ttl.isNegative()) {
			// 정상 경로에서는 발생하지 않는다(15:30 이후에는 scope가 FULL이다). 경계 계산 버그가 음수 TTL로
			// Redis 명령 오류를 내는 것을 막는 방어다.
			log.warn("조회 캐시 TTL이 0 이하라 저장하지 않는다 - key={}, expiresAt={}", key, expiresAt);
			return;
		}
		try {
			redisTemplate.opsForValue().set(key, value, ttl);
		} catch (RuntimeException ex) {
			log.warn("조회 캐시 저장 실패(Redis 장애) - key={}", key, ex);
		}
	}

	private void evict(String suffix) {
		// 킬 스위치가 내려가 있으면 아무 것도 캐시되지 않으므로 지울 것도 없다 — 조회 경로와 같이 Redis를
		// 접촉하지 않는다. 남아 있던 값은 TTL(코인은 최대 다음 정시 05분)로 사라진다.
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
			// 형식이 바뀐 옛 값이 남은 경우다. 예외를 던져 조회를 실패시키지 않고 캐시 미스로 취급한다.
			log.warn("조회 캐시 역직렬화 실패 - 캐시 미스로 취급한다", ex);
			return Optional.empty();
		}
	}

	private Optional<String> writeBriefingItems(List<BriefingNewsItem> items) {
		// 빈 목록도 음성 결과다 — 수집 배치가 아직 그 구간을 채우지 않은 상태일 수 있고(배포 직후 이틀은 근거
		// 구간이 비는 것이 정상이다, FEED-009), 그 0건을 캐시하면 수집이 뒤늦게 돌아 기사가 들어와도 익일
		// 09:00까지 화면이 빈 목록을 본다.
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

	// PRE_MARKET은 오늘 15:30까지다 — 그 시각을 넘기면 조회가 FULL 키를 보므로 이 값은 남아 있어도 읽히지 않는다.
	private LocalDateTime stockSummaryExpiresAt(NewsSummaryScope scope) {
		if (scope == NewsSummaryScope.PRE_MARKET) {
			return LocalDate.now(clock).atTime(MarketSessionTimes.MARKET_CLOSE_TIME);
		}
		return nextMarketOpenTime();
	}

	private LocalDateTime nextMarketOpenTime() {
		return LocalDate.now(clock).plusDays(1).atTime(MarketSessionTimes.MARKET_OPEN_TIME);
	}

	// now보다 뒤인 가장 가까운 정시 05분. now가 10:03이면 10:05, 10:07이면 11:05다(ADR-0015 §2).
	private LocalDateTime nextCryptoBatchTime() {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDateTime candidate = now.truncatedTo(ChronoUnit.HOURS).plusMinutes(CRYPTO_BATCH_MINUTE);
		return candidate.isAfter(now) ? candidate : candidate.plusHours(1);
	}
}
