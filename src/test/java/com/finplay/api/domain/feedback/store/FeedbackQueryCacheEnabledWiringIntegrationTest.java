package com.finplay.api.domain.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.domain.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.domain.feedback.entity.FeedbackContentStatus;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.global.lock.RedisLock;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = "feedback.query-cache.enabled=true")
class FeedbackQueryCacheEnabledWiringIntegrationTest extends FeedbackQueryCacheWiringSupport {

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private FeedbackNewsProperties newsProperties;

	@Autowired
	private Clock clock;

	@Test
	@DisplayName("주식 요약 조회를 3번 해도 요약 행은 1번만 읽는다")
	void readsTheStockSummaryRowOnlyOnceForThreeQueries() {
		assertThat(stockSummaryRowCallsAcrossThreeQueries()).isEqualTo(1);
	}

	@Test
	@DisplayName("코인 요약 조회를 3번 해도 요약 행은 1번만 읽는다")
	void readsTheCryptoSummaryRowOnlyOnceForThreeQueries() {
		assertThat(cryptoSummaryRowCallsAcrossThreeQueries()).isEqualTo(1);
	}

	@Test
	@DisplayName("주식 브리핑 두 번째 조회는 DB를 한 번도 부르지 않는다(텍스트·items 모두 적중)")
	void secondStockBriefingQueryTouchesTheDatabaseZeroTimes() {
		assertThat(stockBriefingDbCallsOnASecondQuery()).isZero();
	}

	@Test
	@DisplayName("코인 브리핑 두 번째 조회는 브리핑 텍스트 행을 다시 읽지 않는다")
	void secondCryptoBriefingQueryDoesNotReadTheBriefingRowAgain() {
		assertThat(cryptoBriefingTextCallsOnASecondQuery()).isZero();
	}

	@Test
	@DisplayName("주식 요약의 items 수집은 캐시를 켜도 조회마다 3번 그대로다(§C-5 노출 게이트)")
	void keepsCollectingStockSummaryItemsOnEveryQuery() {
		assertThat(stockSummaryItemCallsAcrossThreeQueries()).isEqualTo(3);
	}

	@Test
	@DisplayName("코인 브리핑의 items 수집은 캐시를 켜도 두 번째 조회에서 1번 그대로다(24시간 창은 조회 시각 기준)")
	void keepsCollectingCryptoBriefingItemsOnEveryQuery() {
		assertThat(cryptoBriefingItemCallsOnASecondQuery()).isEqualTo(1);
	}

	@Test
	@DisplayName("캐시에 저장된 items가 Boot ObjectMapper로 그대로 왕복해 적중 응답이 미적중 응답과 완전히 같다")
	void cachedBriefingItemsRoundTripThroughBootsObjectMapperUnchanged() {
		saveStockNews("전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 7, 33)));
		saveStockNews("전일 밤 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(23, 59, 59)));
		saveStockBriefing("간밤 기사가 이어졌습니다.");

		MarketBriefingResponse cold = marketBriefingService.getBriefing(Market.STOCK);
		assertThat(redisTemplate.keys(STOCK_BRIEFING_ITEMS_KEY_PREFIX + "*"))
			.as("캐시에 실제로 저장돼야 다음 조회가 적중이다")
			.hasSize(1);
		clearInvocations(marketNewsItemRepository, marketBriefingRepository);

		MarketBriefingResponse warm = marketBriefingService.getBriefing(Market.STOCK);

		verify(marketNewsItemRepository, never()).findMarketNewsPublishedBetween(any(), any(), any());
		verify(marketBriefingRepository, never()).findByMarketAndOriginTradeDate(any(), any());

		assertThat(warm.items()).isEqualTo(cold.items());
		assertThat(warm.summary()).isEqualTo(cold.summary());
		assertThat(warm.status()).isEqualTo(FeedbackContentStatus.READY);
		assertThat(warm.items())
			.extracting(item -> item.publishedAt())
			.containsExactly(
				LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(23, 59, 59)),
				LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 7, 33)));
	}

	@Test
	@DisplayName("Redis가 죽어 있으면 wait-millis를 다 채우지 않고 즉시 원본으로 내려간다")
	void skipsTheWaitEntirelyWhenRedisIsDown() throws IOException {
		LettuceConnectionFactory deadFactory = new LettuceConnectionFactory(
			new RedisStandaloneConfiguration("127.0.0.1", closedPort()));
		deadFactory.afterPropertiesSet();
		deadFactory.start();
		try {
			StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
			FeedbackQueryCache cacheOnDeadRedis = new FeedbackQueryCache(
				deadTemplate, new RedisLock(deadTemplate), objectMapper, clock,
				new FeedbackQueryCacheProperties(true, 1000, 3000, 20), newsProperties);

			long startedAt = System.nanoTime();
			Optional<String> result = cacheOnDeadRedis.getOrLoadCryptoBriefingText(() -> Optional.of("원본 서술"));
			long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

			assertThat(result).as("Redis가 죽어도 응답은 정상이다").contains("원본 서술");
			assertThat(elapsedMillis)
				.as("wait-millis 3000을 다 채웠다면 불건전 판정이 동작하지 않은 것이다")
				.isLessThan(1000L);
		} finally {
			deadFactory.destroy();
		}
	}

	private static int closedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	@Test
	@DisplayName("주식 브리핑 조회가 텍스트·items 두 키를 남기고 items 키에 절단 상한이 들어간다")
	void leavesBothBriefingKeysWithTheTruncationLimitInTheItemsKey() {
		saveStockNews("전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 0)));
		saveStockBriefing("간밤 기사가 이어졌습니다.");

		marketBriefingService.getBriefing(Market.STOCK);

		Set<String> keys = redisTemplate.keys("feedback:query-cache:v1:stock-briefing-*");
		assertThat(keys).containsExactlyInAnyOrder(
			"feedback:query-cache:v1:stock-briefing-text:" + ORIGIN_TRADE_DATE,
			STOCK_BRIEFING_ITEMS_KEY_PREFIX + ORIGIN_TRADE_DATE + ":" + newsProperties.maxItemsPerBriefing());
	}
}
