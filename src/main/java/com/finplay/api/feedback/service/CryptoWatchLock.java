// 코인 변동 감시의 종목 단위 Redis 락 — 키 접두사와 TTL만 정하고 획득·해제 메커니즘은 RedisLock에 맡긴다 (ADR-0014, ADR-0015 §4)
package com.finplay.api.feedback.service;

import com.finplay.api.feedback.config.FeedbackCryptoProperties;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>{@code RedisLock}은 <b>빈으로 주입받는다</b>({@code docs/conventions/code.md}의 생성자 주입 관례). 한때
 * 생성자 안에서 {@code new}로 하나 더 만들었는데 — 추출 당시 테스트의 생성자 호출을 건드리지 않으려던
 * 결과였다 — 그러면 <b>테스트 편의가 운영 배선을 결정</b>하고, 지금은 무상태라 무해해도 메트릭·설정이 붙는
 * 순간 두 인스턴스가 갈린다(PR 리뷰 [권장 4]).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoWatchLock {

	private static final String KEY_PREFIX = "feedback:crypto-watch:lock:";

	private final RedisLock redisLock;

	private final FeedbackCryptoProperties cryptoProperties;

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
