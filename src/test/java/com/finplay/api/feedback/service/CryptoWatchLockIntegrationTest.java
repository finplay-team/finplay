// CryptoWatchLock이 실제 Redis(Testcontainers)에서 락을 걸고 풀며, 다른 토큰으로는 안 풀리고, TTL 경과 후 자동 해제되는지 검증한다 (ADR-0014)
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.feedback.config.FeedbackCryptoProperties;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

// tasks-244.md 항목 2 — SET NX PX 획득과 Lua check-then-delete 해제의 원자성, TTL 자동 만료는 mock으로 흉내 내면
// 검증이 아니라 동어반복이 된다. CryptoWatchLockTest(단위)는 응답 매핑·예외 처리만 mock으로 보고, 여기서는 실제
// Redis(compose.yaml과 같은 redis:7.4, TestcontainersConfiguration의 공유 싱글턴)로 세 가지를 확인한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CryptoWatchLockIntegrationTest {

	private static final Long LOCK_RELEASE_INSTRUMENT_ID = 9001L;
	private static final Long WRONG_TOKEN_INSTRUMENT_ID = 9002L;
	private static final Long TTL_EXPIRY_INSTRUMENT_ID = 9003L;

	@Autowired
	private StringRedisTemplate redisTemplate;

	private CryptoWatchLock cryptoWatchLock(int ttlSeconds) {
		FeedbackCryptoProperties properties = new FeedbackCryptoProperties(30, 6, 5, 24, 100, 35, ttlSeconds);
		return new CryptoWatchLock(redisTemplate, properties);
	}

	@AfterEach
	void cleanUpRedis() {
		redisTemplate.delete("feedback:crypto-watch:lock:" + LOCK_RELEASE_INSTRUMENT_ID);
		redisTemplate.delete("feedback:crypto-watch:lock:" + WRONG_TOKEN_INSTRUMENT_ID);
		redisTemplate.delete("feedback:crypto-watch:lock:" + TTL_EXPIRY_INSTRUMENT_ID);
	}

	@Test
	void secondTryLockFailsWhileLockedThenSucceedsAfterUnlock() {
		CryptoWatchLock lock = cryptoWatchLock(30);

		Optional<String> firstToken = lock.tryLock(LOCK_RELEASE_INSTRUMENT_ID);
		assertThat(firstToken).isPresent();

		Optional<String> whileLocked = lock.tryLock(LOCK_RELEASE_INSTRUMENT_ID);
		assertThat(whileLocked).isEmpty();

		lock.unlock(LOCK_RELEASE_INSTRUMENT_ID, firstToken.get());

		Optional<String> afterUnlock = lock.tryLock(LOCK_RELEASE_INSTRUMENT_ID);
		assertThat(afterUnlock).isPresent();
	}

	@Test
	void unlockWithWrongTokenDoesNotReleaseTheLock() {
		CryptoWatchLock lock = cryptoWatchLock(30);

		Optional<String> token = lock.tryLock(WRONG_TOKEN_INSTRUMENT_ID);
		assertThat(token).isPresent();

		lock.unlock(WRONG_TOKEN_INSTRUMENT_ID, "wrong-token-that-does-not-match");

		Optional<String> stillLocked = lock.tryLock(WRONG_TOKEN_INSTRUMENT_ID);
		assertThat(stillLocked).isEmpty();
	}

	@Test
	void lockAutomaticallyExpiresAndCanBeReacquiredAfterTtlElapses() throws InterruptedException {
		CryptoWatchLock lock = cryptoWatchLock(1);

		Optional<String> token = lock.tryLock(TTL_EXPIRY_INSTRUMENT_ID);
		assertThat(token).isPresent();

		Thread.sleep(1500);

		Optional<String> afterTtl = lock.tryLock(TTL_EXPIRY_INSTRUMENT_ID);
		assertThat(afterTtl).isPresent();
	}
}
