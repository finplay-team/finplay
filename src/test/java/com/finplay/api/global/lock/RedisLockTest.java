// RedisLock이 Redis 응답을 UnlockResult 세 값으로 정확히 가르는지, 장애와 토큰 불일치를 섞지 않는지 mock으로 검증하는 단위 테스트다.
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

/**
 * {@code RedisLockIntegrationTest}(실 Redis)가 상호 배제·Lua 원자성·TTL 만료를 맡고, 여기서는 <b>Redis 응답을
 * 세 결과값으로 가르는 분기</b>만 본다 — 실 Redis로는 장애 분기에 들어갈 수 없기 때문이다.
 *
 * <p><b>왜 이 분기를 소비자가 아니라 여기서 고정하는가.</b> {@code UnlockResult}의 값이 틀려도 소비자 테스트는
 * 대부분 통과한다 — {@code CryptoWatchLockTest}는 "예외를 던지지 않는다"만 보고 조회 캐시는 반환값을 무시한다.
 * 그런데 <b>{@code REDIS_FAILURE}를 {@code NOT_HELD}로 잘못 반환하면 {@code CryptoWatchLock}이 Redis 장애를
 * "토큰 불일치 — TTL이 이미 만료돼 다른 인스턴스가 락을 새로 잡았을 수 있다"로 기록한다.</b> 그 로그가
 * ADR-0014 §후속의 TTL 재조정 근거인데, 원인이 다른 두 사건이 같은 문장으로 섞이면 그 근거가 오염되고
 * 아무 테스트도 깨지지 않는다. {@code RedisLockIntegrationTest} 헤더가 "메커니즘의 회귀는 소비자가 아니라
 * 여기서 잡혀야 한다"고 선언한 그 자리다.
 */
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

	// ── unlock의 세 결과값 ───────────────────────────────────────────────────────────

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

	/*
	 * 반환값 null — Redis 응답이 비었거나 드라이버가 값을 못 옮긴 경우다. `deleted != null` 가드가 없으면
	 * `deleted == 1L`에서 Long 언박싱 NPE가 나고, 그 NPE가 catch(RuntimeException)에 잡혀 REDIS_FAILURE로
	 * 잘못 분류된다. **반환값을 직접 단정해야 그 오분류가 드러난다** — 예외 유무로는 가드가 있으나 없으나
	 * "예외 없이 끝난다"가 똑같다.
	 */
	@Test
	@DisplayName("스크립트가 null을 반환해도 언박싱 NPE 없이 NOT_HELD다 — REDIS_FAILURE로 새지 않는다")
	void unlockReturnsNotHeldWithoutUnboxingWhenTheScriptReturnsNull() {
		givenUnlockScriptReturns(null);

		assertThat(redisLock.unlock(KEY, TOKEN))
			.as("가드가 사라지면 NPE가 삼켜져 REDIS_FAILURE가 된다")
			.isEqualTo(RedisLock.UnlockResult.NOT_HELD);
	}

	// 이 값이 NOT_HELD로 바뀌면 CryptoWatchLock이 Redis 장애를 "TTL이 이미 만료"로 기록한다(클래스 주석 참조).
	@Test
	@DisplayName("Redis가 예외를 던지면 REDIS_FAILURE다 — NOT_HELD와 섞이면 TTL 재조정 근거가 오염된다")
	void unlockReturnsRedisFailureWhenRedisThrows() {
		givenUnlockScriptThrows();

		assertThat(redisLock.unlock(KEY, TOKEN)).isEqualTo(RedisLock.UnlockResult.REDIS_FAILURE);
	}

	// 장애의 원인은 이 클래스 안에 있으므로 여기서 WARN으로 남긴다. 소비자가 남기는 "지우지 못했다"와 문장이
	// 달라야 로그로 두 사건을 구분할 수 있다.
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

	// ── tryLock ─────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("setIfAbsent가 성공하면 토큰을 주고, 시도마다 토큰이 다르다")
	void tryLockReturnsAFreshTokenOnEachSuccess() {
		when(valueOperations.setIfAbsent(eq(KEY), any(), any(Duration.class))).thenReturn(true);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		Optional<String> first = redisLock.tryLock(KEY, Duration.ofSeconds(1));
		Optional<String> second = redisLock.tryLock(KEY, Duration.ofSeconds(1));

		assertThat(first).isPresent();
		assertThat(second).isPresent();
		// 토큰이 같으면 남의 락을 내 토큰으로 풀 수 있게 된다 — check-then-delete가 무의미해진다.
		assertThat(first.get()).isNotEqualTo(second.get());
	}

	@Test
	@DisplayName("이미 다른 보유자가 잡고 있으면 빈 값이다")
	void tryLockReturnsEmptyWhenTheKeyIsAlreadyLocked() {
		when(valueOperations.setIfAbsent(eq(KEY), any(), any(Duration.class))).thenReturn(false);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		assertThat(redisLock.tryLock(KEY, Duration.ofSeconds(1))).isEmpty();
	}

	// 정상 경합(false)과 장애(예외)는 원인이 달라 로그 레벨이 다르지만(DEBUG/WARN) 획득 결과는 같다 —
	// 락이 장애가 되어 호출부를 죽이지 않게 한다(ADR-0014 §실패 처리).
	@Test
	@DisplayName("Redis가 예외를 던져도 획득 실패로 삼킨다")
	void tryLockReturnsEmptyWhenRedisThrows() {
		when(valueOperations.setIfAbsent(eq(KEY), any(), any(Duration.class)))
			.thenThrow(new RuntimeException("Redis 장애"));
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);

		assertThat(redisLock.tryLock(KEY, Duration.ofSeconds(1))).isEmpty();
	}

	// ── isHeld ──────────────────────────────────────────────────────────────────────

	@Test
	@DisplayName("키가 있으면 보유 중이고 없으면 아니다")
	void isHeldFollowsKeyExistence() {
		when(redisTemplate.hasKey(KEY)).thenReturn(true, false);

		assertThat(redisLock.isHeld(KEY)).isTrue();
		assertThat(redisLock.isHeld(KEY)).isFalse();
	}

	// 확인할 수 없으면 기다리게 두는 것보다 대기를 끊는 편이 낫다 — 조회 캐시의 fail-open 방향과 같다.
	@Test
	@DisplayName("Redis가 예외를 던지면 보유 중이 아니라고 본다")
	void isHeldReturnsFalseWhenRedisThrows() {
		when(redisTemplate.hasKey(KEY)).thenThrow(new RuntimeException("Redis 장애"));

		assertThat(redisLock.isHeld(KEY)).isFalse();
	}

	// 로그가 유일한 외부 관찰점이라 임시 appender를 붙인다 (CryptoWatchLockTest와 같은 방식).
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
