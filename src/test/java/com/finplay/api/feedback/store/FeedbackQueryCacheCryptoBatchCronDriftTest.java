// FeedbackQueryCache의 코인 TTL 경계(정시 05분)가 application.yml의 feedback.batch.crypto-cron과 갈리지 않는지 대조하는 드리프트 테스트다.
package com.finplay.api.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.feedback.config.FeedbackBatchConfig;
import com.finplay.api.feedback.config.FeedbackBatchProperties;
import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.feedback.service.RedisLock;
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

// 코인 캐시의 TTL 경계는 "다음 코인 배치 실행"이다(ADR-0015 §2). 그 시각을 FeedbackQueryCache가 상수(정시 05분)로
// 갖고 있어 application.yml의 feedback.batch.crypto-cron과 값이 이중화돼 있다 — 크론만 바꾸면 캐시 만료가 조용히
// 어긋나 갱신된 요약이 최대 한 주기 늦게 보인다(예외도 로그도 없다). 운영 코드에 크론 파싱을 넣는 대신 이
// 대조 테스트 하나로 막는다(FeedbackBatchPropertiesTest·FeedbackDetectionPropertiesTest와 같은 방식,
// PR 리뷰 [권장 3]).
//
// 구현의 상수를 직접 읽지 않고 **캐시가 실제로 넘긴 TTL**로 대조한다 — 상수를 참조하면 둘이 같은 값을 보게 돼
// 아무것도 고정하지 못하고, 가시성을 테스트 때문에 넓히게 된다.
//
// spring.config.additional-location을 비워 두고 돌린다 — build.gradle이 테스트 전체에 얹는
// feedback-schedules-disabled-for-tests.yml이 크론을 "-"로 덮어 CronExpression.parse가 실패하기 때문이다
// (FeedbackBatchPropertiesTest와 같은 이유·같은 수법).
class FeedbackQueryCacheCryptoBatchCronDriftTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	// 정시 05분 경계의 앞뒤가 갈리는 시각이면 무엇이든 된다 — 기대값을 크론에서 계산하므로 이 값에 05가 없다.
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

	// 캐시가 저장 시 Redis에 넘긴 TTL을 그대로 꺼낸다 — TTL은 외부에서 이것 말고 관찰할 방법이 없다.
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
