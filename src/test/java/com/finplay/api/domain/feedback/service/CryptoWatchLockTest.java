// CryptoWatchLock이 mock Redis 응답을 그대로 Optional/무시로 옮기는지, Redis 장애 시 예외를 삼키는지 검증하는 단위 테스트다.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.global.lock.RedisLock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

	// TTL 값 자체를 단정하는 테스트는 tryLockPassesConfiguredWatchLockTtlSecondsAsTheExpirationDuration
	// 하나뿐이고 그 테스트만 자체 값을 쓴다. 나머지는 TTL을 매처에 걸지 않으므로(any(Duration.class)) 이 값이
	// 무엇이든 결과가 같다 — §C-7 기본값(45)과 다른 것은 의도적이며, 여기서 기본값을 다시 단정하지 않는다
	// (그건 FeedbackCryptoPropertiesTest 몫이다).
	private static final int IRRELEVANT_WATCH_LOCK_TTL_SECONDS = 30;

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

	private final FeedbackCryptoProperties defaultTtlProperties = new FeedbackCryptoProperties(30, 6, 5, 24, 100, 35,
		IRRELEVANT_WATCH_LOCK_TTL_SECONDS);

	// RedisLock은 진짜를 쓴다 — 이 테스트가 보는 것은 mock Redis 응답이 Optional/무시로 옮겨지는 경로 전체이고,
	// 락을 mock으로 바꾸면 SET NX PX·Lua 인자 단정이 사라져 동어반복이 된다(추출 전과 같은 범위를 유지한다).
	private CryptoWatchLock cryptoWatchLock(FeedbackCryptoProperties properties) {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		return new CryptoWatchLock(new RedisLock(redisTemplate), properties);
	}

	@Test
	void tryLockReturnsTokenWhenSetIfAbsentSucceeds() {
		CryptoWatchLock lock = cryptoWatchLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class))).thenReturn(true);

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

	// 스크립트 반환값을 반드시 스텁한다 — 스텁하지 않으면 mock 기본값 null이 돌아와 이 테스트가 "정상 해제"라는
	// 이름과 달리 deleted == null(WARN) 분기를 타고 통과한다. 그러면 정상 경로(1L)를 어떤 단위 테스트도 커버하지
	// 않게 된다(PR #254 3라운드 리뷰 [권장 1]).
	@Test
	@SuppressWarnings("unchecked")
	void unlockExecutesTheCheckThenDeleteScriptWithTheLockKeyAndGivenToken() {
		CryptoWatchLock lock = cryptoWatchLock(defaultTtlProperties);
		String token = "some-token";
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any())).thenReturn(1L);

		lock.unlock(INSTRUMENT_ID, token);

		verify(redisTemplate).execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq(token));
	}

	// 반환값 null — Redis 응답이 비었거나 드라이버가 값을 못 옮긴 경우다. 이 컴포넌트가 그때 "지우지 못했다"
	// 경고를 남기는지 본다.
	//
	// **이 테스트가 red가 되는 근거는 추출(ADR-0015 §4) 이후 바뀌었다.** 전에는 `unlock`의 언박싱 가드가 이
	// 클래스에 있어 "가드가 없으면 NPE가 'Redis 장애' 경고로 잘못 분류된다"가 근거였다. 지금 그 가드와 장애
	// 경고는 둘 다 RedisLock에 있고, 아래 appender는 CryptoWatchLock 로거에만 붙는다 — 형제 로거라 RedisLock의
	// WARN은 여기 잡히지 않는다. 그러므로 **오분류를 여기서 관찰할 수 없다**(그 단정은 RedisLockTest로 옮겼다).
	//
	// 지금의 근거는 이것이다 — RedisLock의 가드를 지우면 언박싱 NPE가 삼켜져 NOT_HELD 대신 REDIS_FAILURE가
	// 돌아오고, 그러면 이 클래스는 아무 로그도 남기지 않아 아래 singleElement()가 빈 목록에서 실패한다.
	// 즉 여기서 지키는 것은 "NOT_HELD를 받으면 소비자가 WARN을 남긴다"는 이 클래스의 책임이다.
	@Test
	@SuppressWarnings("unchecked")
	void unlockWarnsThatNothingWasDeletedWhenScriptReturnsNullInsteadOfFailingOnUnboxing() {
		CryptoWatchLock lock = cryptoWatchLock(defaultTtlProperties);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any())).thenReturn(null);

		List<ILoggingEvent> logs = capturingLogs(() -> lock.unlock(INSTRUMENT_ID, "some-token"));

		assertThat(logs).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).contains("지우지 못했다");
			// 종목을 남겨야 ADR-0014 §후속의 TTL 재조정 때 어느 종목이 부족했는지 알 수 있다.
			assertThat(event.getFormattedMessage()).contains(String.valueOf(INSTRUMENT_ID));
		});
		verify(redisTemplate)
			.execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("some-token"));
	}

	// 로그가 두 분기의 유일한 외부 관찰점이라 로거에 임시 appender를 붙인다 (DartDisclosureCollectorTest와 같은 방식).
	private static List<ILoggingEvent> capturingLogs(Runnable action) {
		Logger logger = (Logger)LoggerFactory.getLogger(CryptoWatchLock.class);
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

	// 토큰 불일치(0L) — 이미 TTL이 만료돼 다른 인스턴스가 락을 새로 잡은 경우다. 스크립트가 아무 것도 지우지
	// 않고 0을 돌려주며, 이 컴포넌트는 WARN만 남기고 조용히 넘어간다(예외를 던져 호출부의 finally를 깨뜨리면 안
	// 된다). 이 분기를 회귀에 고정한다.
	@Test
	@SuppressWarnings("unchecked")
	void unlockDoesNotThrowWhenScriptDeletesNothingBecauseTheTokenNoLongerMatches() {
		CryptoWatchLock lock = cryptoWatchLock(defaultTtlProperties);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any())).thenReturn(0L);

		assertThatCode(() -> lock.unlock(INSTRUMENT_ID, "stale-token")).doesNotThrowAnyException();

		verify(redisTemplate)
			.execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("stale-token"));
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
