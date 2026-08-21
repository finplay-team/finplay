// feedback.query-cache.* 프로퍼티가 설정 없이도 ADR-0015 기본값으로 바인딩되는지, 방어를 조용히 무력화하는 값이면 기동이 실패하는지 검증한다.
package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// FeedbackCryptoPropertiesTest와 같은 짝의 앞쪽이다 — 여기서는 record의 @DefaultValue와 검증 블록만 본다.
// application.yml 쪽 키 경로는 FeedbackQueryCachePropertiesYamlTest가 맡는다.
//
// 기대값의 정본은 ai/adr/0015-feedback-query-cache.md다. 구현 파일이 아니라 ADR에서 값을 가져와야 record와
// yml이 함께 틀어지는 드리프트가 잡힌다.
//
// FeedbackCryptoPropertiesTest와 달리 로컬 테스트 전용 설정을 만들지 않는다 — production 등록 설정
// (FeedbackQueryCacheConfig)이 실제로 있고, 그것을 그대로 올려야 "등록이 빠져 있으면 여기서 깨진다"가 성립한다.
class FeedbackQueryCachePropertiesTest {

	// ADR-0015 §4·§후속
	private static final boolean ADR_ENABLED = true;

	private static final long ADR_LOCK_TTL_MILLIS = 1000;

	private static final long ADR_WAIT_MILLIS = 300;

	private static final long ADR_POLL_MILLIS = 20;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(FeedbackQueryCacheConfig.class);

	@Test
	@DisplayName("feedback.query-cache 설정을 하나도 주지 않아도 ADR-0015 기본값으로 바인딩된다")
	void bindsAdrDefaultsWhenNoFeedbackQueryCachePropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(FeedbackQueryCacheProperties.class);

			FeedbackQueryCacheProperties properties = context.getBean(FeedbackQueryCacheProperties.class);
			assertThat(properties.enabled()).isEqualTo(ADR_ENABLED);
			assertThat(properties.lockTtlMillis()).isEqualTo(ADR_LOCK_TTL_MILLIS);
			assertThat(properties.waitMillis()).isEqualTo(ADR_WAIT_MILLIS);
			assertThat(properties.pollMillis()).isEqualTo(ADR_POLL_MILLIS);
		});
	}

	@Test
	@DisplayName("feedback.query-cache.* 케밥케이스 키를 주면 네 값이 모두 덮어써진다")
	void bindsEveryPropertyFromKebabCaseKeys() {
		contextRunner
			.withPropertyValues(
				"feedback.query-cache.enabled=false",
				"feedback.query-cache.lock-ttl-millis=2000",
				"feedback.query-cache.wait-millis=500",
				"feedback.query-cache.poll-millis=50")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackQueryCacheProperties properties = context.getBean(FeedbackQueryCacheProperties.class);
				assertThat(properties.enabled()).isFalse();
				assertThat(properties.lockTtlMillis()).isEqualTo(2000);
				assertThat(properties.waitMillis()).isEqualTo(500);
				assertThat(properties.pollMillis()).isEqualTo(50);
			});
	}

	@Test
	@DisplayName("일부 값만 덮어써도 나머지는 ADR-0015 기본값을 유지한다")
	void keepsAdrDefaultsForPropertiesThatAreNotGiven() {
		contextRunner
			.withPropertyValues("feedback.query-cache.enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackQueryCacheProperties properties = context.getBean(FeedbackQueryCacheProperties.class);
				assertThat(properties.enabled()).isFalse();
				assertThat(properties.lockTtlMillis()).isEqualTo(ADR_LOCK_TTL_MILLIS);
				assertThat(properties.waitMillis()).isEqualTo(ADR_WAIT_MILLIS);
				assertThat(properties.pollMillis()).isEqualTo(ADR_POLL_MILLIS);
			});
	}

	// 0·음수면 Duration.ofMillis가 Redis 명령 오류를 유발하고 RedisLock.tryLock의 catch(RuntimeException)이
	// 이를 삼켜 락이 영구히 획득 실패가 된다 — 모든 요청이 대기 후 fail-open으로 DB에 직행하는데 로그는
	// DEBUG 한 줄뿐이다.
	@Test
	@DisplayName("lock-ttl-millis가 1 미만이면 기동이 실패한다")
	void failsWhenLockTtlMillisIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.query-cache.lock-ttl-millis=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("lock-ttl-millis"));
	}

	@Test
	@DisplayName("wait-millis가 음수이면 기동이 실패한다")
	void failsWhenWaitMillisIsNegative() {
		contextRunner
			.withPropertyValues("feedback.query-cache.wait-millis=-1")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("wait-millis"));
	}

	// 0은 "대기하지 않고 즉시 DB 직행"이라 유효한 값이다 — 음수만 막는다는 record 주석을 고정한다.
	@Test
	@DisplayName("wait-millis가 0이면 유효한 값으로 바인딩된다")
	void acceptsZeroWaitMillisBecauseItMeansGoingStraightToTheLoader() {
		contextRunner
			.withPropertyValues("feedback.query-cache.wait-millis=0")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(FeedbackQueryCacheProperties.class).waitMillis()).isZero();
			});
	}

	// 0이면 폴링이 바쁜 대기가 되어 대기 스레드가 CPU를 태운다.
	@Test
	@DisplayName("poll-millis가 1 미만이면 기동이 실패한다")
	void failsWhenPollMillisIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.query-cache.poll-millis=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("poll-millis"));
	}
}
