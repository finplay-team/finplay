package com.finplay.api.domain.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.domain.feedback.dto.response.MarketBriefingResponse;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.market.entity.Market;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

@TestPropertySource(properties = {
	"feedback.query-cache.enabled=true",
	"feedback.news.max-items-per-briefing=2"})
class FeedbackQueryCacheTruncationLimitIntegrationTest extends FeedbackQueryCacheWiringSupport {

	private static final int NEW_LIMIT = 2;

	private static final int OLD_LIMIT = 30;

	private static final int STALE_ITEM_COUNT = 5;

	private static final String STALE_TITLE_PREFIX = "옛 배포가 남긴 목록 ";

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private FeedbackNewsProperties newsProperties;

	@Test
	@DisplayName("절단 상한을 바꾸면 키가 갈려 옛 길이 목록이 재사용되지 않고 새 길이로 나온다")
	void changingTheTruncationLimitSplitsTheKeySoTheStaleListIsNeverReused() {
		assertThat(newsProperties.maxItemsPerBriefing())
			.as("이 테스트의 전제 — 상한이 실제로 낮춰져 바인딩됐다")
			.isEqualTo(NEW_LIMIT);
		for (int index = 0; index < STALE_ITEM_COUNT; index++) {
			saveStockNews("전장 뉴스 " + index,
				LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(16, 0)).plusMinutes(index));
		}
		saveStockBriefing("간밤 기사가 이어졌습니다.");
		plantStaleList(STOCK_BRIEFING_ITEMS_KEY_PREFIX + ORIGIN_TRADE_DATE + ":" + OLD_LIMIT);
		plantStaleList(STOCK_BRIEFING_ITEMS_KEY_PREFIX + ORIGIN_TRADE_DATE);

		MarketBriefingResponse response = marketBriefingService.getBriefing(Market.STOCK);

		assertThat(response.items()).hasSize(NEW_LIMIT);
		assertThat(response.items())
			.extracting(BriefingNewsItem::title)
			.as("옛 상한으로 만든 목록이 한 건도 섞이면 안 된다")
			.noneMatch(title -> title.startsWith(STALE_TITLE_PREFIX));
		assertThat(response.items())
			.extracting(BriefingNewsItem::title)
			.containsExactly("전장 뉴스 4", "전장 뉴스 3");
		assertThat(redisTemplate.hasKey(STOCK_BRIEFING_ITEMS_KEY_PREFIX + ORIGIN_TRADE_DATE + ":" + NEW_LIMIT))
			.isTrue();
	}

	private void plantStaleList(String key) {
		List<BriefingNewsItem> stale = IntStream.range(0, STALE_ITEM_COUNT)
			.mapToObj(index -> new BriefingNewsItem(
				stock.getId(),
				stock.getSymbol(),
				stock.getName(),
				MarketNewsItemType.NEWS,
				STALE_TITLE_PREFIX + index,
				"테스트경제",
				"https://news.example.test/stale/" + index,
				LocalDateTime.of(PREVIOUS_TRADE_DATE, LocalTime.of(20, 0)).plusMinutes(index)))
			.toList();
		redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(stale), Duration.ofMinutes(10));
	}
}
