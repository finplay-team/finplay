// 방어군 — feedback.query-cache.enabled=true에서 같은 조회의 원본 호출이 실제로 줄고, 캐시에 저장된 값이 Boot ObjectMapper로 그대로 왕복하는지 확인한다 (tasks.md 항목 3·4).
package com.finplay.api.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.finplay.api.feedback.config.FeedbackNewsProperties;
import com.finplay.api.feedback.config.FeedbackQueryCacheProperties;
import com.finplay.api.feedback.domain.FeedbackContentStatus;
import com.finplay.api.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.feedback.service.RedisLock;
import com.finplay.api.market.domain.Market;
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

// 짝인 FeedbackQueryCacheDisabledWiringIntegrationTest와 시나리오가 완전히 같고 기대 숫자만 다르다 — 두 클래스의
// 차이가 곧 캐시의 효과다(대조 대상은 캐시이며, 락이 아니다).
@TestPropertySource(properties = "feedback.query-cache.enabled=true")
class FeedbackQueryCacheEnabledWiringIntegrationTest extends FeedbackQueryCacheWiringSupport {

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private FeedbackNewsProperties newsProperties;

	@Autowired
	private Clock clock;

	// ── 원본 호출 횟수 대조 ───────────────────────────────────────────────────────────

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

	// ── 캐시하지 않기로 한 목록은 그대로 매 요청 DB로 간다 ────────────────────────────

	// 여기서 숫자가 줄면 §C-5 노출 게이트가 캐시에 걸린 것이다 — 기사가 재생 시각을 따라 풀리지 않고
	// 캐시된 시점에 멈춘다. 대조군과 같은 숫자여야 한다.
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

	// ── Boot ObjectMapper 왕복 ────────────────────────────────────────────────────────

	/*
	 * 단위 테스트는 new ObjectMapper()로 왕복을 봤지만 운영은 Boot가 만든 매퍼를 주입받는다 — 그쪽은
	 * WRITE_DATES_AS_TIMESTAMPS가 꺼져 있어 LocalDateTime 표현이 다르다. 표현이 달라도 왕복만 되면 되지만,
	 * 타임존이 끼어들면 publishedAt이 9시간 밀린 채로 캐시에서 돌아온다 — 예외도 로그도 없이 목록의 시각만
	 * 틀리는 형태다. 적중 응답과 미적중 응답을 통째로 비교해 그 경우를 못박는다.
	 */
	@Test
	@DisplayName("캐시에 저장된 items가 Boot ObjectMapper로 그대로 왕복해 적중 응답이 미적중 응답과 완전히 같다")
	void cachedBriefingItemsRoundTripThroughBootsObjectMapperUnchanged() {
		// 초 단위까지 넣는다 — 초를 버리는 직렬화나 타임존 변환이 있으면 여기서 갈린다.
		saveStockNews("전일 저녁 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(18, 7, 33)));
		saveStockNews("전일 밤 기사", LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(23, 59, 59)));
		saveStockBriefing("간밤 기사가 이어졌습니다.");

		MarketBriefingResponse cold = marketBriefingService.getBriefing(Market.STOCK);
		assertThat(redisTemplate.keys(STOCK_BRIEFING_ITEMS_KEY_PREFIX + "*"))
			.as("캐시에 실제로 저장돼야 다음 조회가 적중이다")
			.hasSize(1);
		clearInvocations(marketNewsItemRepository, marketBriefingRepository);

		MarketBriefingResponse warm = marketBriefingService.getBriefing(Market.STOCK);

		// 적중임을 먼저 확정한다 — DB를 다시 불렀다면 아래 동일성은 캐시에 대해 아무것도 말해 주지 않는다.
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

	// ── Redis가 죽었을 때 대기를 건너뛴다 ────────────────────────────────────────────

	/*
	 * 소요를 System.nanoTime()으로 잰다 — 이 테스트의 Clock은 고정이라 Clock으로 재면 언제나 0이다(#198).
	 *
	 * wait-millis를 3초로 크게 잡아 두고 1초 미만을 단정한다. Redis 불건전 판정이 없으면 이 호출은 폴링을
	 * 3초 내내 돌다가 어차피 fail-open으로 같은 답을 내므로, "정답이 나온다"만으로는 두 구현이 구분되지 않는다.
	 * 갈리는 것은 소요뿐이다.
	 */
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

	// 방금 닫은 포트라 확실히 비어 있다 — 리터럴 포트를 박으면 그 포트를 다른 프로세스가 쓰고 있을 때
	// "Redis가 죽어 있다"는 전제가 조용히 깨진다.
	private static int closedPort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	// ── 캐시 키 격리 ─────────────────────────────────────────────────────────────────

	// 조회가 실제로 남긴 키를 눈으로 확인한다 — 위 호출 횟수 단정들은 "줄었다"만 말하고 무엇이 저장됐는지는
	// 말해 주지 않는다. 키가 깨져도 값이 안 남으면 호출 횟수는 그대로 대조군과 같아지므로 짝으로 둔다.
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
