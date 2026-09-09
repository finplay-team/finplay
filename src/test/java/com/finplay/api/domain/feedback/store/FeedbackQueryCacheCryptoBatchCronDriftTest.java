package com.finplay.api.domain.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.config.FeedbackBatchConfig;
import com.finplay.api.domain.feedback.config.FeedbackBatchProperties;
import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.global.lock.RedisLock;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.scheduling.support.CronExpression;
import tools.jackson.databind.ObjectMapper;

class FeedbackQueryCacheCryptoBatchCronDriftTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 3);

	private static final Long INSTRUMENT_ID = 7L;

	private static final String CRYPTO_SUMMARY_KEY = "feedback:query-cache:v1:crypto-summary:7";

	@Test
	@DisplayName("코인 요약 캐시의 TTL 경계가 feedback.batch.crypto-cron의 다음 실행과 정확히 같다")
	void cryptoCacheTtlBoundaryMatchesTheConfiguredCryptoBatchCron() {
		new ApplicationContextRunner()
			.withSystemProperties("spring.config.additional-location=")
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withUserConfiguration(FeedbackBatchConfig.class)
			.run(context -> {
				String cryptoCron = context.getBean(FeedbackBatchProperties.class).cryptoCron();
				Duration untilNextBatchRun = Duration.between(NOW, CronExpression.parse(cryptoCron).next(NOW));

				assertThat(cryptoSummaryTtlPassedToRedis())
					.as("FeedbackQueryCache의 코인 TTL 경계와 feedback.batch.crypto-cron(%s)이 갈렸다 — "
						+ "둘을 함께 고쳐라(캐시의 CRYPTO_BATCH_MINUTE와 yml 크론의 분).", cryptoCron)
					.isEqualTo(untilNextBatchRun);
			});
	}

	private Duration cryptoSummaryTtlPassedToRedis() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		@SuppressWarnings("unchecked") ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
		RedisLock redisLock = mock(RedisLock.class);
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(redisLock.tryLock(anyString(), any(Duration.class))).thenReturn(Optional.of("lock-token"));

		new FeedbackQueryCache(
			redisTemplate,
			redisLock,
			new ObjectMapper(),
			Clock.fixed(NOW.atZone(KST).toInstant(), KST),
			new FeedbackQueryCacheProperties(true, 1000, 300, 20),
			new FeedbackNewsProperties("0 0/30 * * * *", "0 0/30 8-20 * * MON-FRI", 30, 5, 5, 50, 30, 30))
			.getOrLoadCryptoSummaryText(INSTRUMENT_ID, () -> Optional.of("코인 요약"));

		ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
		verify(valueOperations).set(eq(CRYPTO_SUMMARY_KEY), anyString(), ttl.capture());
		return ttl.getValue();
	}
}
