package com.finplay.api.domain.feedback.service;

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
import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class CryptoWatchLockTest {

	private static final Long INSTRUMENT_ID = 1L;
	private static final String LOCK_KEY = "feedback:crypto-watch:lock:1";

	private static final int IRRELEVANT_WATCH_LOCK_TTL_SECONDS = 30;

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

	private final FeedbackCryptoProperties defaultTtlProperties = new FeedbackCryptoProperties(30, 6, 5, 24, 100, 35,
		IRRELEVANT_WATCH_LOCK_TTL_SECONDS);

	private CryptoWatchLock cryptoWatchLock(FeedbackCryptoProperties properties) {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		return new CryptoWatchLock(new RedisLock(redisTemplate), properties);
	}

	@Test
	void tryLockReturnsTokenWhenSetIfAbsentSucceeds() {
		CryptoWatchLock lock = cryptoWatchLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class))).thenReturn(true);

		Optional<String> token = lock.tryLock(INSTRUMENT_ID);

		assertThat(token).isPresent();
	}

	@Test
	void tryLockPassesConfiguredWatchLockTtlSecondsAsTheExpirationDuration() {
		FeedbackCryptoProperties shortTtl = new FeedbackCryptoProperties(30, 6, 5, 24, 100, 35, 5);
		CryptoWatchLock lock = cryptoWatchLock(shortTtl);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), eq(Duration.ofSeconds(5)))).thenReturn(true);

		lock.tryLock(INSTRUMENT_ID);

		verify(valueOperations).setIfAbsent(eq(LOCK_KEY), any(), eq(Duration.ofSeconds(5)));
	}

	@Test
	void tryLockReturnsEmptyWhenSetIfAbsentFailsBecauseKeyIsAlreadyLocked() {
		CryptoWatchLock lock = cryptoWatchLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class))).thenReturn(false);

		Optional<String> token = lock.tryLock(INSTRUMENT_ID);

		assertThat(token).isEmpty();
	}

	@Test
	void tryLockReturnsEmptyWhenRedisThrowsRuntimeException() {
		CryptoWatchLock lock = cryptoWatchLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class)))
			.thenThrow(new RuntimeException("Redis 장애"));

		Optional<String> token = lock.tryLock(INSTRUMENT_ID);

		assertThat(token).isEmpty();
	}

	@Test
	void tryLockGeneratesADifferentTokenOnEachSuccessfulCall() {
		CryptoWatchLock lock = cryptoWatchLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class))).thenReturn(true);

		Optional<String> first = lock.tryLock(INSTRUMENT_ID);
		Optional<String> second = lock.tryLock(INSTRUMENT_ID);

		assertThat(first).isPresent();
		assertThat(second).isPresent();
		assertThat(first.get()).isNotEqualTo(second.get());
	}

	@Test
	@SuppressWarnings("unchecked")
	void unlockExecutesTheCheckThenDeleteScriptWithTheLockKeyAndGivenToken() {
		CryptoWatchLock lock = cryptoWatchLock(defaultTtlProperties);
		String token = "some-token";
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any())).thenReturn(1L);

		lock.unlock(INSTRUMENT_ID, token);

		verify(redisTemplate).execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq(token));
	}

	@Test
	@SuppressWarnings("unchecked")
	void unlockWarnsThatNothingWasDeletedWhenScriptReturnsNullInsteadOfFailingOnUnboxing() {
		CryptoWatchLock lock = cryptoWatchLock(defaultTtlProperties);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any())).thenReturn(null);

		List<ILoggingEvent> logs = capturingLogs(() -> lock.unlock(INSTRUMENT_ID, "some-token"));

		assertThat(logs).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).contains("지우지 못했다");
			assertThat(event.getFormattedMessage()).contains(String.valueOf(INSTRUMENT_ID));
		});
		verify(redisTemplate)
			.execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("some-token"));
	}

	private static List<ILoggingEvent> capturingLogs(Runnable action) {
		Logger logger = (Logger)LoggerFactory.getLogger(CryptoWatchLock.class);
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
		CryptoWatchLock lock = cryptoWatchLock(defaultTtlProperties);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any())).thenReturn(0L);

		assertThatCode(() -> lock.unlock(INSTRUMENT_ID, "stale-token")).doesNotThrowAnyException();

		verify(redisTemplate)
			.execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("stale-token"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void unlockSwallowsExceptionWhenRedisThrowsAndDoesNotPropagateIt() {
		CryptoWatchLock lock = cryptoWatchLock(defaultTtlProperties);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any()))
			.thenThrow(new RuntimeException("Redis 장애"));

		assertThatCode(() -> lock.unlock(INSTRUMENT_ID, "token")).doesNotThrowAnyException();
	}
}
