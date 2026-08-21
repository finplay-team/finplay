// max-items-per-briefing을 바꾸면 캐시 키가 갈려 옛 길이 목록이 재사용되지 않는지 확인한다 (tasks.md 항목 4).
package com.finplay.api.domain.feedback.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.feedback.config.FeedbackNewsProperties;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.dto.response.BriefingNewsItem;
import com.finplay.api.domain.feedback.dto.response.MarketBriefingResponse;
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

/**
 * 상한을 30에서 2로 낮춘 재배포 직후를 재현한다 — Redis에는 옛 상한으로 만든 30건짜리 목록이 TTL(최대 익일
 * 09:00)까지 남아 있다.
 *
 * <p><b>이 상황이 위험한 이유는 아무 신호가 없기 때문이다.</b> 캐시하는 값이 절단 <i>후</i> 목록이라, 키가
 * 갈리지 않으면 조회가 옛 길이 목록을 그대로 내보낸다 — 예외도 로그도 없고 화면 목록 길이만 설정과 다르다.
 *
 * <p>옛 값을 <b>두 곳</b>에 심는다. 옛 상한이 붙은 키({@code :30})와 상한이 아예 없는 키다. 전자는 "상한을 키에
 * 넣긴 했는데 갱신되지 않는" 결함을, 후자는 "상한을 키에서 빠뜨린" 결함을 잡는다 — 한 곳만 심으면 나머지
 * 하나가 통과한다.
 */
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
		// 새 상한으로 자른 최신 2건이다.
		assertThat(response.items())
			.extracting(BriefingNewsItem::title)
			.containsExactly("전장 뉴스 4", "전장 뉴스 3");
		// 새 키가 실제로 생겼다 — 갈린 키로 저장까지 이어져야 다음 조회부터 새 길이가 유지된다.
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
