// RedisLock이 실제 Redis(Testcontainers)에서 상호 배제를 하는지 — 같은 키 두 번째 획득 실패, 토큰 불일치 해제가 아무것도 지우지 않음, TTL 만료 후 재획득 — 을 검증한다 (ADR-0015 §4)
package com.finplay.api.feedback.service;

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

// tasks.md 항목 1 — SET NX PX의 상호 배제, Lua check-then-delete의 토큰 판정, TTL 자동 만료는 mock으로 흉내 내면
// 검증이 아니라 동어반복이 된다(CryptoWatchLockIntegrationTest가 같은 이유로 실제 Redis를 쓴다). CryptoWatchLock
// 위에서 간접적으로 보던 것을 추출된 RedisLock 자체에 대해 직접 고정한다 — 소비자가 둘 이상이 되었으므로
// 메커니즘의 회귀는 소비자가 아니라 여기서 잡혀야 한다.
//
// 키를 테스트마다 다르게 두고 @AfterEach에서 지운다 — Testcontainers Redis는 실행 전체가 공유하는 싱글턴이라
// 잔여 키가 남으면 다음 실행의 "획득 성공" 단정이 조용히 깨진다.
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

	// 토큰이 다르면 지우지 않는다는 것이 Lua check-then-delete의 존재 이유다 — 이 단정이 없으면 스크립트를
	// 무조건 DEL로 바꿔도 통과한다. NOT_HELD 반환과 "락이 그대로 남아 있다"를 함께 본다(반환값만 보면 실제로
	// 지웠는지 알 수 없고, 남아 있는지만 보면 반환값 계약이 고정되지 않는다).
	@Test
	@DisplayName("토큰이 다르면 unlock이 아무것도 지우지 않고 NOT_HELD를 반환하며 락은 그대로 유지된다")
	void unlockWithADifferentTokenDeletesNothingAndLeavesTheLockHeld() {
		Optional<String> token = redisLock.tryLock(WRONG_TOKEN_KEY, LONG_ENOUGH_TTL);
		assertThat(token).isPresent();

		assertThat(redisLock.unlock(WRONG_TOKEN_KEY, "token-that-does-not-match"))
			.isEqualTo(RedisLock.UnlockResult.NOT_HELD);

		assertThat(redisTemplate.opsForValue().get(WRONG_TOKEN_KEY)).isEqualTo(token.get());
		assertThat(redisLock.tryLock(WRONG_TOKEN_KEY, LONG_ENOUGH_TTL)).isEmpty();

		// 진짜 토큰으로는 여전히 풀린다 — 위 시도가 락 상태를 망가뜨리지 않았다는 뜻이다.
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

		// 만료된 뒤 남에게 넘어간 락을 옛 토큰으로 지울 수 없다 — TTL 만료가 check-then-delete를 우회하지 않는다.
		assertThat(redisLock.unlock(TTL_EXPIRY_KEY, token.get())).isEqualTo(RedisLock.UnlockResult.NOT_HELD);
	}
}
