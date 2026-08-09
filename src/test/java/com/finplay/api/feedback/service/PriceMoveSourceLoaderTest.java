// 세 조회 경로가 공유하는 PriceMoveSourceLoader의 카드별 묶기·순서 보존을 검증하는 단위 테스트
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.feedback.domain.MarketNewsItem;
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.NarrativeSource;
import com.finplay.api.feedback.domain.PriceMoveEvent;
import com.finplay.api.feedback.domain.PriceMoveEventSource;
import com.finplay.api.feedback.dto.response.NewsItem;
import com.finplay.api.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

// 이 로더는 PriceMoveQueryService·PostSellFeedbackReader·CryptoPostSellFeedbackReader 세 곳이 공유하므로
// 회귀 지점을 한 곳으로 모아 둔다 (PR #281 리뷰). 세 호출부 테스트도 실제 인스턴스를 태우지만 그쪽 단정은
// 각자의 응답 조립이라 "카드별로 갈리는가 · 순서가 보존되는가"가 직접 드러나지 않는다.
class PriceMoveSourceLoaderTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 15, 0, 0);

	private final PriceMoveEventSourceRepository priceMoveEventSourceRepository = mock(
		PriceMoveEventSourceRepository.class);

	private final PriceMoveSourceLoader loader = new PriceMoveSourceLoader(priceMoveEventSourceRepository);

	private static Instrument instrument() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 50_000_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 42L);
		return instrument;
	}

	private static PriceMoveEvent event(Long id) {
		PriceMoveEvent event = PriceMoveEvent.createCrypto(
			instrument(), NOW, new BigDecimal("0.031000"), new BigDecimal("3.4000"),
			"대형 거래소 상장 소식이 있었습니다.", NarrativeSource.TEMPLATE, NOW);
		ReflectionTestUtils.setField(event, "id", id);
		return event;
	}

	private static PriceMoveEventSource sourceOf(PriceMoveEvent event, String title) {
		MarketNewsItem news = MarketNewsItem.create(
			instrument(), MarketNewsItemType.NEWS, title, "coindesk.com",
			"https://news.example.test/" + title, NOW.minusMinutes(10), NOW);
		return PriceMoveEventSource.of(event, news);
	}

	@Test
	@DisplayName("카드마다 따로 묻지 않고 id 목록으로 한 번만 조회한다")
	void readsAllSourcesInASingleQuery() {
		PriceMoveEvent first = event(1L);
		PriceMoveEvent second = event(2L);
		when(priceMoveEventSourceRepository.findAllByPriceMoveEventIdIn(List.of(1L, 2L))).thenReturn(List.of());

		loader.findSources(List.of(first, second));

		verify(priceMoveEventSourceRepository, times(1)).findAllByPriceMoveEventIdIn(List.of(1L, 2L));
	}

	@Test
	@DisplayName("같은 카드의 기사 2건이 리포지토리가 준 순서 그대로 한 목록에 담긴다")
	void keepsTheRepositoryOrderWithinOneCard() {
		// 정렬 규칙은 이 클래스가 아니라 리포지토리 질의(publishedAt 내림차순 + id 오름차순)에 있다 — 여기서
		// 확인하는 것은 LinkedHashMap·ArrayList가 그 순서를 뒤집지 않는다는 것뿐이다.
		PriceMoveEvent event = event(1L);
		when(priceMoveEventSourceRepository.findAllByPriceMoveEventIdIn(anyList()))
			.thenReturn(List.of(sourceOf(event, "먼저 온 기사"), sourceOf(event, "나중에 온 기사")));

		Map<Long, List<NewsItem>> sources = loader.findSources(List.of(event));

		assertThat(sources.get(1L)).extracting(NewsItem::title)
			.containsExactly("먼저 온 기사", "나중에 온 기사");
	}

	@Test
	@DisplayName("서로 다른 카드의 기사는 카드 id로 갈려 담기고, 기사가 없는 카드는 키 자체가 없다")
	void splitsSourcesByCardIdAndOmitsCardsWithoutSources() {
		PriceMoveEvent withSource = event(1L);
		PriceMoveEvent other = event(2L);
		PriceMoveEvent withoutSource = event(3L);
		when(priceMoveEventSourceRepository.findAllByPriceMoveEventIdIn(anyList()))
			.thenReturn(List.of(sourceOf(withSource, "1번 카드 기사"), sourceOf(other, "2번 카드 기사")));

		Map<Long, List<NewsItem>> sources = loader.findSources(List.of(withSource, other, withoutSource));

		assertThat(sources.get(1L)).extracting(NewsItem::title).containsExactly("1번 카드 기사");
		assertThat(sources.get(2L)).extracting(NewsItem::title).containsExactly("2번 카드 기사");
		// 호출부가 getOrDefault(id, List.of())로 읽으므로 빈 목록을 만들어 두지 않는다.
		assertThat(sources).doesNotContainKey(3L);
	}
}
