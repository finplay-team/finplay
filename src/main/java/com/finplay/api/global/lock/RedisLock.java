package com.finplay.api.global.lock;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLock {

	private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
		Long.class);

	private final StringRedisTemplate redisTemplate;

	public Optional<String> tryLock(String key, Duration ttl) {
		String token = UUID.randomUUID().toString();
		try {
			Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
			if (Boolean.TRUE.equals(acquired)) {
				return Optional.of(token);
			}
			log.debug("Redis 락 획득 실패(다른 보유자가 이미 잡고 있다) - key={}", key);
			return Optional.empty();
		} catch (RuntimeException ex) {
			log.warn("Redis 락 획득 실패(Redis 장애) - key={}", key, ex);
			return Optional.empty();
		}
	}

	public boolean isHeld(String key) {
		try {
			return Boolean.TRUE.equals(redisTemplate.hasKey(key));
		} catch (RuntimeException ex) {
			log.warn("Redis 락 보유 확인 실패(Redis 장애) - key={}", key, ex);
			return false;
		}
	}

	public UnlockResult unlock(String key, String token) {
		try {
			Long deleted = redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
			return deleted != null && deleted == 1L ? UnlockResult.RELEASED : UnlockResult.NOT_HELD;
		} catch (RuntimeException ex) {
			log.warn("Redis 락 해제 실패(Redis 장애) - key={}", key, ex);
			return UnlockResult.REDIS_FAILURE;
		}
	}

	public enum UnlockResult {

		RELEASED,

		NOT_HELD,

		REDIS_FAILURE
	}
}
