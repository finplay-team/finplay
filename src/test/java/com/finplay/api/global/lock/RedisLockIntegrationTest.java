package com.finplay.api.global.lock;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RedisLockIntegrationTest {

	private static final String MUTUAL_EXCLUSION_KEY = "test:redis-lock:mutual-exclusion";
	private static final String WRONG_TOKEN_KEY = "test:redis-lock:wrong-token";
	private static final String TTL_EXPIRY_KEY = "test:redis-lock:ttl-expiry";

	private static final Duration LONG_ENOUGH_TTL = Duration.ofSeconds(30);

	@Autowired
	private RedisLock redisLock;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@AfterEach
	void cleanUpRedis() {
		redisTemplate.delete(MUTUAL_EXCLUSION_KEY);
		redisTemplate.delete(WRONG_TOKEN_KEY);
		redisTemplate.delete(TTL_EXPIRY_KEY);
	}

	@Test
	@DisplayName("같은 키의 두 번째 tryLock은 실패하고, 해제한 뒤에는 다시 획득된다")
	void secondTryLockOnTheSameKeyFailsWhileHeldAndSucceedsAgainAfterUnlock() {
		Optional<String> firstToken = redisLock.tryLock(MUTUAL_EXCLUSION_KEY, LONG_ENOUGH_TTL);
		assertThat(firstToken).isPresent();

		Optional<String> whileHeld = redisLock.tryLock(MUTUAL_EXCLUSION_KEY, LONG_ENOUGH_TTL);
		assertThat(whileHeld).isEmpty();

		assertThat(redisLock.unlock(MUTUAL_EXCLUSION_KEY, firstToken.get()))
			.isEqualTo(RedisLock.UnlockResult.RELEASED);

		Optional<String> afterUnlock = redisLock.tryLock(MUTUAL_EXCLUSION_KEY, LONG_ENOUGH_TTL);
		assertThat(afterUnlock).isPresent();
		assertThat(afterUnlock.get()).isNotEqualTo(firstToken.get());
	}

	@Test
	@DisplayName("토큰이 다르면 unlock이 아무것도 지우지 않고 NOT_HELD를 반환하며 락은 그대로 유지된다")
	void unlockWithADifferentTokenDeletesNothingAndLeavesTheLockHeld() {
		Optional<String> token = redisLock.tryLock(WRONG_TOKEN_KEY, LONG_ENOUGH_TTL);
		assertThat(token).isPresent();

		assertThat(redisLock.unlock(WRONG_TOKEN_KEY, "token-that-does-not-match"))
			.isEqualTo(RedisLock.UnlockResult.NOT_HELD);

		assertThat(redisTemplate.opsForValue().get(WRONG_TOKEN_KEY)).isEqualTo(token.get());
		assertThat(redisLock.tryLock(WRONG_TOKEN_KEY, LONG_ENOUGH_TTL)).isEmpty();

		assertThat(redisLock.unlock(WRONG_TOKEN_KEY, token.get())).isEqualTo(RedisLock.UnlockResult.RELEASED);
	}

	@Test
	@DisplayName("TTL이 지나면 락이 스스로 풀려 다시 획득된다")
	void lockExpiresOnItsOwnAndCanBeReacquiredAfterTheTtlElapses() throws InterruptedException {
		Optional<String> token = redisLock.tryLock(TTL_EXPIRY_KEY, Duration.ofSeconds(1));
		assertThat(token).isPresent();

		Thread.sleep(1500);

		Optional<String> afterTtl = redisLock.tryLock(TTL_EXPIRY_KEY, Duration.ofSeconds(1));
		assertThat(afterTtl).isPresent();

		assertThat(redisLock.unlock(TTL_EXPIRY_KEY, token.get())).isEqualTo(RedisLock.UnlockResult.NOT_HELD);
	}
}
