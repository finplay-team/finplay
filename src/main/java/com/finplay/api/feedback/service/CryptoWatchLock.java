// 코인 변동 감시의 종목 단위 Redis 락 — 키 접두사와 TTL만 정하고 획득·해제 메커니즘은 RedisLock에 맡긴다 (ADR-0014, ADR-0015 §4)
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackCryptoProperties;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code CryptoPriceMoveWatcher.watchOne()}이 z-score 게이트를 통과한 직후부터 카드 저장까지 종목 단위로 잠근다
 * (ADR-0014 §결정).
 *
 * <p><b>SET NX PX 획득과 Lua check-then-delete 해제는 {@link RedisLock}으로 옮겼다</b>(ADR-0015 §4). 이 클래스에
 * 남는 것은 코인 감시 전용 결정 둘뿐이다 — 키 접두사 {@code feedback:crypto-watch:lock:}과 TTL
 * {@code feedback.crypto.watch-lock-ttl-seconds}(LLM 타임아웃 기준 45초). 그 둘이 박혀 있어 조회 경로(#245)가
 * 이 클래스를 그대로 재사용할 수 없었고, 그래서 메커니즘만 추출했다.
 *
 * <p>{@code RedisLock}을 빈으로 주입받지 않고 생성자에서 직접 조립한다 — {@code StringRedisTemplate}을 받는
 * 이 생성자 시그니처를 {@code CryptoPriceMoveWatcher}와 #244의 테스트들이 그대로 쓰고 있어 바꾸지 않았다.
 * {@code RedisLock}은 템플릿 하나만 감싸는 무상태 컴포넌트라 인스턴스가 하나 더 생겨도 동작 차이가 없다.
 * (필드 대입이 아니라 새 객체 조립이라 Lombok {@code @RequiredArgsConstructor}를 쓸 수 없어 생성자를 손으로 썼다.)
 */
@Slf4j
@Component
public class CryptoWatchLock {

	private static final String KEY_PREFIX = "feedback:crypto-watch:lock:";

	private final RedisLock redisLock;

	private final FeedbackCryptoProperties cryptoProperties;

	public CryptoWatchLock(StringRedisTemplate redisTemplate, FeedbackCryptoProperties cryptoProperties) {
		this.redisLock = new RedisLock(redisTemplate);
		this.cryptoProperties = cryptoProperties;
	}

	/**
	 * 종목 단위 락을 얻는다. 성공하면 이번 시도를 식별하는 토큰을 반환한다 — 해제할 때 그대로 넘겨야 한다.
	 * TTL은 {@code feedback.crypto.watch-lock-ttl-seconds}다.
	 *
	 * <p>다른 인스턴스가 먼저 잡았거나 Redis 자체가 예외를 던지면(장애) 모두 획득 실패다 — 호출부가 그 종목의
	 * 이번 틱만 건너뛰고 배치 전체는 죽지 않게 한다(ADR-0014 §결과). 두 경우의 로그 레벨 구분(정상 경합
	 * {@code DEBUG}, 장애 {@code WARN})은 {@link RedisLock}이 그대로 갖고 있다.
	 */
	public Optional<String> tryLock(Long instrumentId) {
		return redisLock.tryLock(
			lockKey(instrumentId), Duration.ofSeconds(cryptoProperties.watchLockTtlSeconds()));
	}

	/**
	 * {@code tryLock}이 반환한 토큰으로만 해제한다. 지울 것이 없었으면(이미 TTL 만료 후 다른 인스턴스가 새로
	 * 잡은 락이면) {@code WARN}으로 남긴다 — TTL이 실제로 부족했다는 신호이고, ADR-0014 §후속의 TTL 재조정
	 * 근거가 된다. Redis 장애는 {@link RedisLock}이 이미 {@code WARN}으로 남겼으므로 여기서 다시 남기지 않는다
	 * (원인이 다른 두 사건을 같은 문장으로 기록하면 로그로 구분할 수 없다).
	 */
	public void unlock(Long instrumentId, String token) {
		if (redisLock.unlock(lockKey(instrumentId), token) == RedisLock.UnlockResult.NOT_HELD) {
			log.warn(
				"코인 감시 락 해제가 아무 것도 지우지 못했다(토큰 불일치 - TTL이 이미 만료돼 다른 "
					+ "인스턴스가 락을 새로 잡았을 수 있다) - instrumentId={}",
				instrumentId);
		}
	}

	private String lockKey(Long instrumentId) {
		return KEY_PREFIX + instrumentId;
	}
}
