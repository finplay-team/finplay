package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.feedback.dto.response.NewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
		assertThat(sources).doesNotContainKey(3L);
	}
}
