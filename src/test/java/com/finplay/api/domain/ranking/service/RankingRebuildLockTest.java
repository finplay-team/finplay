package com.finplay.api.domain.ranking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.ranking.config.RankingRebuildProperties;
import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RankingRebuildLockTest {

	private static final Market MARKET = Market.CRYPTO;
	private static final String LOCK_KEY = "ranking:rebuild:lock:CRYPTO";

	private static final int IRRELEVANT_LOCK_TTL_SECONDS = 30;

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

	private final RankingRebuildProperties defaultTtlProperties = new RankingRebuildProperties(
		IRRELEVANT_LOCK_TTL_SECONDS);

	private RankingRebuildLock rankingRebuildLock(RankingRebuildProperties properties) {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		return new RankingRebuildLock(new RedisLock(redisTemplate), properties);
	}

	@Test
	void tryLockReturnsTokenWhenSetIfAbsentSucceeds() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class))).thenReturn(true);

		Optional<String> token = lock.tryLock(MARKET);

		assertThat(token).isPresent();
	}

	@Test
	void tryLockPassesConfiguredLockTtlSecondsAsTheExpirationDuration() {
		RankingRebuildProperties shortTtl = new RankingRebuildProperties(5);
		RankingRebuildLock lock = rankingRebuildLock(shortTtl);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), eq(Duration.ofSeconds(5)))).thenReturn(true);

		lock.tryLock(MARKET);

		verify(valueOperations).setIfAbsent(eq(LOCK_KEY), any(), eq(Duration.ofSeconds(5)));
	}

	@Test
	void tryLockReturnsEmptyWhenSetIfAbsentFailsBecauseKeyIsAlreadyLocked() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class))).thenReturn(false);

		Optional<String> token = lock.tryLock(MARKET);

		assertThat(token).isEmpty();
	}

	@Test
	void tryLockReturnsEmptyWhenRedisThrowsRuntimeException() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class)))
			.thenThrow(new RuntimeException("Redis 장애"));

		Optional<String> token = lock.tryLock(MARKET);

		assertThat(token).isEmpty();
	}

	@Test
	void tryLockGeneratesADifferentTokenOnEachSuccessfulCall() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class))).thenReturn(true);

		Optional<String> first = lock.tryLock(MARKET);
		Optional<String> second = lock.tryLock(MARKET);

		assertThat(first).isPresent();
		assertThat(second).isPresent();
		assertThat(first.get()).isNotEqualTo(second.get());
	}

	@Test
	@SuppressWarnings("unchecked")
	void unlockExecutesTheCheckThenDeleteScriptWithTheLockKeyAndGivenToken() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		String token = "some-token";
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any())).thenReturn(1L);

		lock.unlock(MARKET, token);

		verify(redisTemplate).execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq(token));
	}

	@Test
	@SuppressWarnings("unchecked")
	void unlockWarnsThatNothingWasDeletedWhenScriptReturnsNullInsteadOfFailingOnUnboxing() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any())).thenReturn(null);

		List<ILoggingEvent> logs = capturingLogs(() -> lock.unlock(MARKET, "some-token"));

		assertThat(logs).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).contains("지우지 못했다");
			assertThat(event.getFormattedMessage()).contains(MARKET.name());
		});
		verify(redisTemplate)
			.execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("some-token"));
	}

	private static List<ILoggingEvent> capturingLogs(Runnable action) {
		Logger logger = (Logger)LoggerFactory.getLogger(RankingRebuildLock.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level originalLevel = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		try {
			action.run();
			return List.copyOf(appender.list);
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(originalLevel);
			appender.stop();
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void unlockDoesNotThrowWhenScriptDeletesNothingBecauseTheTokenNoLongerMatches() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any())).thenReturn(0L);

		assertThatCode(() -> lock.unlock(MARKET, "stale-token")).doesNotThrowAnyException();

		verify(redisTemplate)
			.execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("stale-token"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void unlockSwallowsExceptionWhenRedisThrowsAndDoesNotPropagateIt() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any()))
			.thenThrow(new RuntimeException("Redis 장애"));

		assertThatCode(() -> lock.unlock(MARKET, "token")).doesNotThrowAnyException();
	}
}
