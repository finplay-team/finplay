package com.finplay.api.global.lock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

class RedisLockTest {

	private static final String KEY = "test:lock:key";

	private static final String TOKEN = "some-token";

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

	private final RedisLock redisLock = new RedisLock(redisTemplate);

	@SuppressWarnings("unchecked")
	private void givenUnlockScriptReturns(Long deleted) {
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any()))
			.thenReturn(deleted);
	}

	@SuppressWarnings("unchecked")
	private void givenUnlockScriptThrows() {
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any()))
			.thenThrow(new RuntimeException("Redis 장애"));
	}


	@Test
	@DisplayName("스크립트가 1을 반환하면 RELEASED다")
	void unlockReturnsReleasedWhenTheScriptDeletedTheKey() {
		givenUnlockScriptReturns(1L);

		assertThat(redisLock.unlock(KEY, TOKEN)).isEqualTo(RedisLock.UnlockResult.RELEASED);
	}

	@Test
	@DisplayName("스크립트가 0을 반환하면 NOT_HELD다 — 토큰이 달라 아무것도 지우지 못했다")
	void unlockReturnsNotHeldWhenTheScriptDeletedNothing() {
		givenUnlockScriptReturns(0L);

		assertThat(redisLock.unlock(KEY, TOKEN)).isEqualTo(RedisLock.UnlockResult.NOT_HELD);
	}

	@Test
	@DisplayName("스크립트가 null을 반환해도 언박싱 NPE 없이 NOT_HELD다 — REDIS_FAILURE로 새지 않는다")
	void unlockReturnsNotHeldWithoutUnboxingWhenTheScriptReturnsNull() {
		givenUnlockScriptReturns(null);

		assertThat(redisLock.unlock(KEY, TOKEN))
			.as("가드가 사라지면 NPE가 삼켜져 REDIS_FAILURE가 된다")
			.isEqualTo(RedisLock.UnlockResult.NOT_HELD);
	}

	@Test
	@DisplayName("Redis가 예외를 던지면 REDIS_FAILURE다 — NOT_HELD와 섞이면 TTL 재조정 근거가 오염된다")
	void unlockReturnsRedisFailureWhenRedisThrows() {
		givenUnlockScriptThrows();

		assertThat(redisLock.unlock(KEY, TOKEN)).isEqualTo(RedisLock.UnlockResult.REDIS_FAILURE);
	}

	@Test
	@DisplayName("Redis 장애로 해제에 실패하면 이 클래스가 WARN에 예외를 함께 남긴다")
	void unlockLogsTheRedisFailureWithTheThrowable() {
		givenUnlockScriptThrows();

		List<ILoggingEvent> logs = capturingLogs(() -> redisLock.unlock(KEY, TOKEN));

		assertThat(logs).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).contains("Redis 장애");
			assertThat(event.getThrowableProxy()).isNotNull();
		});
	}


	@Test
	@DisplayName("setIfAbsent가 성공하면 토큰을 주고, 시도마다 토큰이 다르다")
	void tryLockReturnsAFreshTokenOnEachSuccess() {
		when(valueOperations.setIfAbsent(eq(KEY), any(), any(Duration.class))).thenReturn(true);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		Optional<String> first = redisLock.tryLock(KEY, Duration.ofSeconds(1));
		Optional<String> second = redisLock.tryLock(KEY, Duration.ofSeconds(1));

		assertThat(first).isPresent();
		assertThat(second).isPresent();
		assertThat(first.get()).isNotEqualTo(second.get());
	}

	@Test
	@DisplayName("이미 다른 보유자가 잡고 있으면 빈 값이다")
	void tryLockReturnsEmptyWhenTheKeyIsAlreadyLocked() {
		when(valueOperations.setIfAbsent(eq(KEY), any(), any(Duration.class))).thenReturn(false);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		assertThat(redisLock.tryLock(KEY, Duration.ofSeconds(1))).isEmpty();
	}

	@Test
	@DisplayName("Redis가 예외를 던져도 획득 실패로 삼킨다")
	void tryLockReturnsEmptyWhenRedisThrows() {
		when(valueOperations.setIfAbsent(eq(KEY), any(), any(Duration.class)))
			.thenThrow(new RuntimeException("Redis 장애"));
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		assertThat(redisLock.tryLock(KEY, Duration.ofSeconds(1))).isEmpty();
	}


	@Test
	@DisplayName("키가 있으면 보유 중이고 없으면 아니다")
	void isHeldFollowsKeyExistence() {
		when(redisTemplate.hasKey(KEY)).thenReturn(true, false);

		assertThat(redisLock.isHeld(KEY)).isTrue();
		assertThat(redisLock.isHeld(KEY)).isFalse();
	}

	@Test
	@DisplayName("Redis가 예외를 던지면 보유 중이 아니라고 본다")
	void isHeldReturnsFalseWhenRedisThrows() {
		when(redisTemplate.hasKey(KEY)).thenThrow(new RuntimeException("Redis 장애"));

		assertThat(redisLock.isHeld(KEY)).isFalse();
	}

	private static List<ILoggingEvent> capturingLogs(Runnable action) {
		Logger logger = (Logger)LoggerFactory.getLogger(RedisLock.class);
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
}
