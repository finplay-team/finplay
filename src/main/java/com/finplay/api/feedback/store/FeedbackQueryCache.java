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
import org.springframework.scheduling.support.CronExpression;
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
	 * <i>후</i> 목록이므로, 설정을 바꿔 재배포해도 키가 갈리지 않으면 Redis에 남은 옛 길이 목록이 TTL까지 그대로
	 * 나간다 — 예외도 로그도 없이 화면 목록 길이만 틀리는 형태다. 값을 바꾸면 키가 자연히 갈려 옛 목록은 아무도
	 * 읽지 않고 TTL로 사라진다.
	 *
	 * <p><b>이 항목만 만료가 "다음 개장"이 아니라 "다음 수집"이다</b>(PR 리뷰 [권장 2]). 텍스트 4종은 배치가
	 * 만들면 그날 안 바뀌지만, 이 목록은 {@code market_news_items}에서 <b>재구성되는 값</b>이고 그 테이블은
	 * {@code feedback.news.collect-cron}이 30분마다 계속 쓴다. 네이버가 뒤늦게 색인한 기사가 전장 구간
	 * {@code [D-1 15:30, D 09:00]} 안으로 들어오면 익일 09:00까지 목록에 나타나지 않는데 <b>예외도 로그도
	 * 없다</b> — 캐시 전에는 30분 안에 반영되던 것이다. 그래서 이 값의 도메인 경계는 다음 수집 실행이며, 같은
	 * 클래스가 빈 목록을 캐시하지 않는 이유(수집이 뒤늦게 채울 수 있다)와 정확히 같은 논리를 비어 있지 않은
	 * 목록에도 적용한 것이다. 임의 숫자가 아니라 도메인 경계이므로 ADR-0015 §2와 어긋나지 않는다.
	 */
	public List<BriefingNewsItem> getOrLoadStockBriefingItems(LocalDate originTradeDate,
		Supplier<List<BriefingNewsItem>> loader) {
		String suffix = STOCK_BRIEFING_ITEMS_ITEM + KEY_DELIMITER + originTradeDate + KEY_DELIMITER
			+ newsProperties.maxItemsPerBriefing();
		return getOrLoad(suffix, nextNewsCollectionTime(), loader, this::readBriefingItems, this::writeBriefingItems);
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
		// 진입 시점에 만료 경계가 앞에 있었는지를 붙잡아 둔다 — 저장 시점에 TTL이 0 이하가 됐을 때 "로더가 도는
		// 사이에 경계를 넘은 것"(정상)과 "계산된 경계가 처음부터 과거였던 것"(버그)을 가르는 유일한 근거다.
		boolean expiryWasAheadAtEntry = expiresAt.isAfter(LocalDateTime.now(clock));
		CacheRead<T> cached = read(key, decoder);
		if (cached.value().isPresent()) {
			return cached.value().get();
		}
		// Redis가 응답하지 못하는 상태면 락도 대기도 순수한 낭비다 — 아무도 캐시를 채울 수 없으므로 기다려 봐야
		// wait-millis를 통째로 태운 뒤 어차피 여기로 온다. 장애를 캐시 미스로 취급하되(ADR-0015 §6) 미스 중에서도
		// "곧 채워질 미스"와 구분해 즉시 원본으로 내려간다.
		if (!cached.redisHealthy()) {
			return loader.get();
		}
		Optional<String> token = redisLock.tryLock(
			LOCK_KEY_PREFIX + suffix, Duration.ofMillis(properties.lockTtlMillis()));
		if (token.isPresent()) {
			try {
				// 락을 얻은 직후 한 번 더 읽는다(double-checked). **위에서 미스를 방금 확인했는데 왜 또 읽나 —
				// 지우지 마라.** 위 확인과 이 시점 사이에 먼저 락을 쥔 요청이 로더 실행·저장·해제까지 마쳤을 수
				// 있고, 그러면 내 tryLock이 성공한다. 이 읽기가 없으면 나는 대기 경로(awaitCachedValue)를 거치지
				// 않고 곧장 로더로 들어가 원본을 두 번째로 부른다 — 완료 조건 "동시 요청 N건에서 원본 1회"가
				// 깨지는 유일한 경로다(PR 리뷰 [권장 1]).
				CacheRead<T> filledWhileAcquiringLock = read(key, decoder);
				if (filledWhileAcquiringLock.value().isPresent()) {
					return filledWhileAcquiringLock.value().get();
				}
				T loaded = loader.get();
				encoder.apply(loaded).ifPresent(value -> write(key, value, expiresAt, expiryWasAheadAtEntry));
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
			CacheRead<T> cached = read(key, decoder);
			if (cached.value().isPresent()) {
				return cached.value();
			}
			// 대기 도중 Redis가 죽었으면 더 기다려도 채워지지 않는다.
			if (!cached.redisHealthy()) {
				return Optional.empty();
			}
		}
		return Optional.empty();
	}

	// 값 없음(진짜 미스)과 Redis 불건전을 구분해 돌려준다 — 둘 다 캐시 미스로 취급하지만 후자는 대기가 무의미하다.
	// 역직렬화 실패는 "건전한 미스"다(형식이 바뀐 옛 값). Redis는 멀쩡하므로 락을 쥔 요청이 새 값으로 덮어쓴다.
	private <T> CacheRead<T> read(String key, Function<String, Optional<T>> decoder) {
		try {
			String value = redisTemplate.opsForValue().get(key);
			return CacheRead.healthy(value == null ? Optional.empty() : decoder.apply(value));
		} catch (RuntimeException ex) {
			log.warn("조회 캐시 읽기 실패(Redis 장애) - key={}", key, ex);
			return CacheRead.unhealthy();
		}
	}

	/**
	 * TTL이 0 이하면 저장하지 않는다 — 음수 TTL은 Redis 명령 오류가 되고, 이미 만료된 값을 넣을 이유도 없다.
	 *
	 * <p><b>성격이 다른 두 가지가 이 분기로 함께 들어온다.</b> {@code expiresAt}은 조회 진입 시점에 계산되는데
	 * 여기서는 로더가 끝난 뒤 {@code now}를 다시 읽으므로, 그 사이에 경계를 넘으면 <b>정상 동작에서도</b>
	 * TTL이 0 이하가 된다.
	 *
	 * <ul>
	 * <li><b>로더가 도는 사이에 경계를 넘었다 → 정상이라 {@code DEBUG}다.</b> 코인은 매시 05분 직전, 주식
	 * {@code PRE_MARKET}은 15:30 직전에 들어온 요청에서 <b>실제로 일어난다</b> — 특히 코인은 매시 경계마다
	 * 나올 수 있어 {@code WARN}으로 두면 운영 로그에 잡음이 된다. 저장을 건너뛰는 것이 옳은 동작이고(이미
	 * 만료된 값이다) 다음 요청이 새 경계로 다시 채운다.</li>
	 * <li><b>진입 시점에 이미 만료가 과거였다 → 비정상이라 {@code WARN}이다.</b> 아직 아무 일도 하기 전인데
	 * 경계가 과거라는 것은 경계 계산이 틀렸다는 뜻이다(예: 15:30이 지났는데 {@code scope}가 여전히
	 * {@code PRE_MARKET}이다). 이 상태는 저장이 계속 실패해 그 키의 캐시가 영영 비므로 신호가 남아야 한다.</li>
	 * </ul>
	 *
	 * <p>둘을 가르는 근거가 {@code expiryWasAheadAtEntry}다. <b>시간 차의 크기로 어림하지 않는다</b> — 임의의
	 * 임계값이 생기고, 느린 로더와 계산 버그를 그 숫자로는 구분할 수 없다.
	 */
	private void write(String key, String value, LocalDateTime expiresAt, boolean expiryWasAheadAtEntry) {
		Duration ttl = Duration.between(LocalDateTime.now(clock), expiresAt);
		if (ttl.isZero() || ttl.isNegative()) {
			if (expiryWasAheadAtEntry) {
				log.debug("조회 캐시 만료 경계를 로더가 도는 사이에 넘어 저장하지 않는다 - key={}, expiresAt={}",
					key, expiresAt);
				return;
			}
			log.warn("조회 캐시 TTL이 진입 시점부터 0 이하라 저장하지 않는다(만료 경계 계산 확인 필요) - "
				+ "key={}, expiresAt={}", key, expiresAt);
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

	// 다음 뉴스 수집 실행 시각. 주기를 숫자로 다시 적지 않고 설정된 크론에서 직접 얻는다 — 리터럴을 두면
	// collect-cron을 바꿨을 때 이 경계만 조용히 옛 주기에 남는다.
	private LocalDateTime nextNewsCollectionTime() {
		LocalDateTime now = LocalDateTime.now(clock);
		try {
			LocalDateTime next = CronExpression.parse(newsProperties.collectCron()).next(now);
			// 다음 실행이 없는 크론(지나간 특정 일자만 지정 등)이면 개장 경계로 떨어진다.
			return next == null ? nextMarketOpenTime() : next;
		} catch (IllegalArgumentException ex) {
			// 수집이 꺼져 있거나(Scheduled.CRON_DISABLED = "-" — 이 저장소의 테스트 설정이 실제로 쓴다) 표현식이
			// 잘못된 경우다. **여기서 예외를 밖으로 내보내면 조회가 500이 된다** — 캐시가 조회를 실패시키지
			// 않는다는 ADR-0015 §6을 지켜 개장 경계로 떨어진다. 수집이 돌지 않으면 목록도 바뀌지 않으므로
			// 그 경계가 곧 옳은 값이기도 하다.
			return nextMarketOpenTime();
		}
	}

	// now보다 뒤인 가장 가까운 정시 05분. now가 10:03이면 10:05, 10:07이면 11:05다(ADR-0015 §2).
	private LocalDateTime nextCryptoBatchTime() {
		LocalDateTime now = LocalDateTime.now(clock);
		LocalDateTime candidate = now.truncatedTo(ChronoUnit.HOURS).plusMinutes(CRYPTO_BATCH_MINUTE);
		return candidate.isAfter(now) ? candidate : candidate.plusHours(1);
	}

	/** 캐시 읽기 한 번의 결과. {@code redisHealthy=false}면 값이 없는 이유가 "아직 안 채워짐"이 아니라 장애다. */
	private record CacheRead<T>(Optional<T> value, boolean redisHealthy) {

		private static <T> CacheRead<T> healthy(Optional<T> value) {
			return new CacheRead<>(value, true);
		}

		private static <T> CacheRead<T> unhealthy() {
			return new CacheRead<>(Optional.empty(), false);
		}
	}
}
