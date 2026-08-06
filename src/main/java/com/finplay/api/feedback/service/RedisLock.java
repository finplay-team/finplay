// Redis 분산 락의 획득·해제 메커니즘 — SET NX PX + Lua check-then-delete만 담고 키와 TTL은 소비자가 정한다 (ADR-0015 §4)
package com.finplay.api.feedback.service;

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

/**
 * {@code CryptoWatchLock}(ADR-0014)이 갖고 있던 획득·해제 메커니즘을 그대로 옮겨 온 것이다. ADR-0014 §후속이
 * "재사용이 실제로 필요해지면 그때 추출한다"고 예고했고 이슈 #245가 그 두 번째 소비자다(ADR-0015 §4) — 추측이
 * 아니라 필요가 생긴 뒤의 추출이라 여기에 그 이상의 일반화(재진입·자동 연장 등)를 넣지 않는다.
 *
 * <p><b>키 조립과 TTL 결정은 소비자가 한다.</b> `CryptoWatchLock`을 그대로 재사용하지 않는 이유가 그것이다 —
 * 접두사 {@code feedback:crypto-watch:lock:}과 TTL 45초가 코인 감시 전용으로 박혀 있어, 원본이 수 ms인 조회
 * 경로에는 맞지 않는다(ADR-0015 §대안 (e)).
 *
 * <p><b>Redis 예외는 삼킨다.</b> 획득은 "얻지 못함"으로, 해제는 {@code REDIS_FAILURE}로 처리한다 — 락 자체가
 * 장애가 되어 호출부를 죽이지 않게 한다. 다른 보유자가 이미 잡아 {@code setIfAbsent}가 정상적으로
 * {@code false}를 반환하는 것(정상 경합)과 Redis 자체가 예외를 던지는 것(장애)은 원인이 다르므로 로그 레벨도
 * 다르다 — 정상 경합은 {@code DEBUG}, 장애는 {@code WARN}이다(ADR-0014에서 그대로 옮긴 관례).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisLock {

	// GET한 값이 넘겨준 토큰과 같을 때만 DEL한다. check-then-delete를 두 명령으로 나누면 그 사이 TTL 만료 후
	// 다른 인스턴스가 새로 잡은 락을 지울 수 있어, 이 Lua 스크립트로 원자적으로 묶는다(ADR-0014 §결정).
	private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
		Long.class);

	private final StringRedisTemplate redisTemplate;

	/**
	 * 주어진 키를 {@code ttl} 동안 잠근다. 성공하면 이번 시도를 식별하는 토큰을 반환한다 — 해제할 때 그대로
	 * 넘겨야 한다. 이미 다른 보유자가 잡고 있거나 Redis가 예외를 던지면 빈 값이다.
	 */
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

	/**
	 * 지금 이 키를 누군가 잡고 있는지 본다. <b>판정이 아니라 힌트다</b> — 확인한 다음 순간 TTL로 사라지거나 다른
	 * 요청이 새로 잡을 수 있으므로, 이 결과로 상호 배제를 대신하면 안 된다. 대기 중인 쪽이 "보유자가 이미
	 * 끝났는가"를 알아보는 용도다(보유자가 <b>저장할 것이 없어</b> 끝나면 기다리던 값이 영영 오지 않는다).
	 *
	 * <p>Redis가 예외를 던지면 {@code false}다 — 확인할 수 없으면 기다리게 두는 것보다 대기를 끊고 원본으로
	 * 내려보내는 편이 낫다(이 클래스의 다른 실패 처리와 같은 fail-open 방향이다).
	 */
	public boolean isHeld(String key) {
		try {
			return Boolean.TRUE.equals(redisTemplate.hasKey(key));
		} catch (RuntimeException ex) {
			log.warn("Redis 락 보유 확인 실패(Redis 장애) - key={}", key, ex);
			return false;
		}
	}

	/**
	 * {@code tryLock}이 반환한 토큰으로만 해제한다. 토큰이 지금 값과 다르면(이미 TTL 만료 후 다른 인스턴스가
	 * 새로 잡은 락이면) 스크립트가 {@code 0}을 반환하고 아무 것도 지우지 않는다 — 그 경우 {@code NOT_HELD}다.
	 *
	 * <p><b>결과를 반환하고 여기서 경고하지 않는다.</b> "아무 것도 지우지 못했다"의 심각도는 소비자마다 다르기
	 * 때문이다 — 코인 감시는 TTL이 실제로 부족했다는 신호라 {@code WARN}으로 남겨야 하지만(ADR-0014 §후속의
	 * TTL 재조정 근거), 조회 캐시처럼 fail-open으로 지나가는 소비자에게는 그 수준이 아니다. Redis 장애만 원인이
	 * 이 클래스 안에 있으므로 여기서 {@code WARN}으로 남기고 삼킨다 — 해제 실패는 TTL이 지나면 스스로 풀리므로
	 * 호출부의 나머지 처리를 막지 않는다.
	 */
	public UnlockResult unlock(String key, String token) {
		try {
			Long deleted = redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
			// deleted == null 가드가 없으면 언박싱 NPE가 catch(RuntimeException)에 삼켜져 장애로 잘못 분류된다.
			return deleted != null && deleted == 1L ? UnlockResult.RELEASED : UnlockResult.NOT_HELD;
		} catch (RuntimeException ex) {
			log.warn("Redis 락 해제 실패(Redis 장애) - key={}", key, ex);
			return UnlockResult.REDIS_FAILURE;
		}
	}

	/** 해제 시도의 결과. 소비자가 {@code NOT_HELD}의 심각도를 스스로 정할 수 있게 세 가지를 구분한다. */
	public enum UnlockResult {

		/** 내 토큰이 맞아 락을 지웠다. */
		RELEASED,

		/** 지울 것이 없었다 — 토큰 불일치이거나 TTL이 이미 만료됐다. */
		NOT_HELD,

		/** Redis가 예외를 던져 해제 여부를 알 수 없다. */
		REDIS_FAILURE
	}
}
