// RankingRebuildLock이 mock Redis 응답을 그대로 Optional/무시로 옮기는지, Redis 장애 시 예외를 삼키는지 검증하는 단위 테스트다.
package com.finplay.api.domain.ranking.service;

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
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.ranking.config.RankingRebuildProperties;
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
// 아니라 동어반복이 된다 — 두 시나리오는 RankingRebuildLockConcurrencyIntegrationTest(실제 Redis)가 맡는다.
// 여기서는 이 컴포넌트가 Redis 응답을 그대로 옮기는 로직과, Redis 자체가 예외를 던졌을 때(장애) 삼켜서 배치를
// 죽이지 않는 경로만 mock으로 본다(CryptoWatchLockTest와 같은 범위).
class RankingRebuildLockTest {

	private static final Market MARKET = Market.CRYPTO;
	private static final String LOCK_KEY = "ranking:rebuild:lock:CRYPTO";

	// TTL 값 자체를 단정하는 테스트는 tryLockPassesConfiguredLockTtlSecondsAsTheExpirationDuration 하나뿐이고
	// 그 테스트만 자체 값을 쓴다. 나머지는 TTL을 매처에 걸지 않으므로(any(Duration.class)) 이 값이 무엇이든
	// 결과가 같다 — 기본값(600)과 다른 것은 의도적이며, 여기서 기본값을 다시 단정하지 않는다(그건
	// RankingRebuildPropertiesTest 몫이다).
	private static final int IRRELEVANT_LOCK_TTL_SECONDS = 30;

	private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

	@SuppressWarnings("unchecked")
	private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

	private final RankingRebuildProperties defaultTtlProperties = new RankingRebuildProperties(
		IRRELEVANT_LOCK_TTL_SECONDS);

	// RedisLock은 진짜를 쓴다 — 이 테스트가 보는 것은 mock Redis 응답이 Optional/무시로 옮겨지는 경로 전체이고,
	// 락을 mock으로 바꾸면 SET NX PX·Lua 인자 단정이 사라져 동어반복이 된다.
	private RankingRebuildLock rankingRebuildLock(RankingRebuildProperties properties) {
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		return new RankingRebuildLock(new RedisLock(redisTemplate), properties);
	}

	@Test
	void tryLockReturnsTokenWhenSetIfAbsentSucceeds() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class))).thenReturn(true);

		Optional<String> token = lock.tryLock(MARKET);

		assertThat(token).isPresent();
	}

	@Test
	void tryLockPassesConfiguredLockTtlSecondsAsTheExpirationDuration() {
		RankingRebuildProperties shortTtl = new RankingRebuildProperties(5);
		RankingRebuildLock lock = rankingRebuildLock(shortTtl);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), eq(Duration.ofSeconds(5)))).thenReturn(true);

		lock.tryLock(MARKET);

		verify(valueOperations).setIfAbsent(eq(LOCK_KEY), any(), eq(Duration.ofSeconds(5)));
	}

	@Test
	void tryLockReturnsEmptyWhenSetIfAbsentFailsBecauseKeyIsAlreadyLocked() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class))).thenReturn(false);

		Optional<String> token = lock.tryLock(MARKET);

		assertThat(token).isEmpty();
	}

	@Test
	void tryLockReturnsEmptyWhenRedisThrowsRuntimeException() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class)))
			.thenThrow(new RuntimeException("Redis 장애"));

		Optional<String> token = lock.tryLock(MARKET);

		assertThat(token).isEmpty();
	}

	@Test
	void tryLockGeneratesADifferentTokenOnEachSuccessfulCall() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(valueOperations.setIfAbsent(eq(LOCK_KEY), any(), any(Duration.class))).thenReturn(true);

		Optional<String> first = lock.tryLock(MARKET);
		Optional<String> second = lock.tryLock(MARKET);

		assertThat(first).isPresent();
		assertThat(second).isPresent();
		assertThat(first.get()).isNotEqualTo(second.get());
	}

	// 스크립트 반환값을 반드시 스텁한다 — 스텁하지 않으면 mock 기본값 null이 돌아와 이 테스트가 "정상 해제"라는
	// 이름과 달리 deleted == null(WARN) 분기를 타고 통과한다(CryptoWatchLockTest와 같은 이유).
	@Test
	@SuppressWarnings("unchecked")
	void unlockExecutesTheCheckThenDeleteScriptWithTheLockKeyAndGivenToken() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		String token = "some-token";
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any())).thenReturn(1L);

		lock.unlock(MARKET, token);

		verify(redisTemplate).execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq(token));
	}

	@Test
	@SuppressWarnings("unchecked")
	void unlockWarnsThatNothingWasDeletedWhenScriptReturnsNullInsteadOfFailingOnUnboxing() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any())).thenReturn(null);

		List<ILoggingEvent> logs = capturingLogs(() -> lock.unlock(MARKET, "some-token"));

		assertThat(logs).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).contains("지우지 못했다");
			assertThat(event.getFormattedMessage()).contains(MARKET.name());
		});
		verify(redisTemplate)
			.execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("some-token"));
	}

	private static List<ILoggingEvent> capturingLogs(Runnable action) {
		Logger logger = (Logger)LoggerFactory.getLogger(RankingRebuildLock.class);
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

	// 토큰 불일치(0L) — 이미 TTL이 만료돼 다른 인스턴스가 락을 새로 잡은 경우다. 예외를 던져 호출부의
	// finally를 깨뜨리면 안 된다.
	@Test
	@SuppressWarnings("unchecked")
	void unlockDoesNotThrowWhenScriptDeletesNothingBecauseTheTokenNoLongerMatches() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any())).thenReturn(0L);

		assertThatCode(() -> lock.unlock(MARKET, "stale-token")).doesNotThrowAnyException();

		verify(redisTemplate)
			.execute((RedisScript<Long>)any(RedisScript.class), eq(List.of(LOCK_KEY)), eq("stale-token"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void unlockSwallowsExceptionWhenRedisThrowsAndDoesNotPropagateIt() {
		RankingRebuildLock lock = rankingRebuildLock(defaultTtlProperties);
		when(redisTemplate.execute((RedisScript<Long>)any(RedisScript.class), anyList(), any()))
			.thenThrow(new RuntimeException("Redis 장애"));

		assertThatCode(() -> lock.unlock(MARKET, "token")).doesNotThrowAnyException();
	}
}
