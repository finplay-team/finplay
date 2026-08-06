// CryptoWatchLock이 mock Redis 응답을 그대로 Optional/무시로 옮기는지, Redis 장애 시 예외를 삼키는지 검증하는 단위 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.feedback.config.FeedbackCryptoProperties;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

// 실제 Lua 스크립트의 원자적 판정(토큰 일치 여부 → check-then-delete)과 TTL 자동 만료는 mock으로 흉내 내면 검증이
// 아니라 동어반복이 된다 — 두 시나리오는 CryptoWatchLockIntegrationTest(실제 Redis)가 맡는다. 여기서는 이
// 컴포넌트가 Redis 응답을 그대로 옮기는 로직과, Redis 자체가 예외를 던졌을 때(장애) 삼켜서 배치를 죽이지 않는
// 경로만 mock으로 본다(tasks-244.md 항목 2, ADR-0014 §실패 처리).
class CryptoWatchLockTest {

	private static final Long INSTRUMENT_ID = 1L;
	private static final String LOCK_KEY = "feedback:crypto-watch:lock:1";

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

	private final FeedbackCryptoProperties defaultTtlProperties = new FeedbackCryptoProperties(30, 6, 5, 24, 100, 35,
		30);

	private CryptoWatchLock cryptoWatchLock(FeedbackCryptoProperties properties) {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		return new CryptoWatchLock(redisTemplate, properties);
	}

	@Test
	void tryLockReturnsTokenWhenSetIfAbsentSucceeds() {
		CryptoWatchLock lock = cryptoWatchLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), eq(Duration.ofSeconds(30)))).thenReturn(true);

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

		lock.unlock(INSTRUMENT_ID, token);

		verify(redisTemplate).execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq(token));
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
