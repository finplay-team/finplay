// 코인 변동 감시의 종목 단위 Redis 락 — SET NX PX 획득 + Lua check-then-delete 해제로 다중 인스턴스 중복을 막는다 (ADR-0014)
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackCryptoProperties;
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
 * {@code CryptoPriceMoveWatcher.watchOne()}이 z-score 게이트를 통과한 직후부터 카드 저장까지 종목 단위로 잠근다
 * (ADR-0014 §결정). 이 이슈의 유일한 소비자이므로 범용 분산 락 서비스로 일반화하지 않는다(YAGNI, 같은 ADR).
 *
 * <p><b>Redisson 등 새 라이브러리를 쓰지 않는다.</b> 이미 의존성에 있는 {@code StringRedisTemplate}
 * ({@code spring-boot-starter-data-redis})만으로 만든다. {@code market.store.PriceStore}가 쓰는 것과 같은
 * 빈이지만, 코인 시세 데이터가 아니라 감시 로직의 락이라 {@code PriceStore}를 거치지 않고 이 클래스가 직접
 * 주입받는다(§C-6 "market은 서비스를 경유한다"의 대상이 아니다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoWatchLock {

	private static final String KEY_PREFIX = "feedback:crypto-watch:lock:";

	// GET한 값이 넘겨준 토큰과 같을 때만 DEL한다. check-then-delete를 두 명령으로 나누면 그 사이 TTL 만료 후
	// 다른 인스턴스가 새로 잡은 락을 지울 수 있어, 이 Lua 스크립트로 원자적으로 묶는다(ADR-0014 §결정).
	private static final RedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
		"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
		Long.class);

	private final StringRedisTemplate redisTemplate;

	private final FeedbackCryptoProperties cryptoProperties;

	/**
	 * 종목 단위 락을 얻는다. 성공하면 이번 시도를 식별하는 토큰을 반환한다 — 해제할 때 그대로 넘겨야 한다.
	 * TTL은 {@code feedback.crypto.watch-lock-ttl-seconds}다.
	 *
	 * <p>Redis 자체가 예외를 던지면(장애) 획득 실패로 처리한다 — 호출부가 "얻지 못함"과 동일하게 취급해 그
	 * 종목의 이번 틱만 건너뛰고 배치 전체는 죽지 않게 한다(ADR-0014 §결과). 다른 인스턴스가 먼저 잡아
	 * {@code setIfAbsent}가 정상적으로 {@code false}를 반환하는 것(정상 경합)과, Redis 자체가 예외를 던지는
	 * 것(장애)은 원인이 다르므로 로그 레벨도 다르게 남긴다 — 정상 경합은 {@code DEBUG}, 장애는 이 저장소의
	 * 인프라 실패 관례(예: {@code CryptoPriceMoveWatcher.watch()}의 종목 실패 로그)를 따라 {@code WARN}이다.
	 */
	public Optional<String> tryLock(Long instrumentId) {
		String token = UUID.randomUUID().toString();
		try {
			Boolean acquired = redisTemplate
				.opsForValue()
				.setIfAbsent(
					lockKey(instrumentId), token, Duration.ofSeconds(cryptoProperties.watchLockTtlSeconds()));
			if (Boolean.TRUE.equals(acquired)) {
				return Optional.of(token);
			}
			log.debug("코인 감시 락 획득 실패(다른 인스턴스가 이미 보유 중) - instrumentId={}", instrumentId);
			return Optional.empty();
		} catch (RuntimeException ex) {
			log.warn("코인 감시 락 획득 실패(Redis 장애) - instrumentId={}", instrumentId, ex);
			return Optional.empty();
		}
	}

	/**
	 * {@code tryLock}이 반환한 토큰으로만 해제한다. 토큰이 지금 값과 다르면(이미 TTL 만료 후 다른 인스턴스가
	 * 새로 잡은 락이면) 아무 것도 하지 않는다. Redis 자체가 예외를 던지면(장애) {@code WARN}으로 남기고
	 * 삼킨다 — 해제 실패는 TTL이 지나면 스스로 풀리므로 호출부의 나머지 처리를 막지 않는다.
	 */
	public void unlock(Long instrumentId, String token) {
		try {
			redisTemplate.execute(UNLOCK_SCRIPT, List.of(lockKey(instrumentId)), token);
		} catch (RuntimeException ex) {
			log.warn("코인 감시 락 해제 실패(Redis 장애) - instrumentId={}", instrumentId, ex);
		}
	}

	private String lockKey(Long instrumentId) {
		return KEY_PREFIX + instrumentId;
	}
}
