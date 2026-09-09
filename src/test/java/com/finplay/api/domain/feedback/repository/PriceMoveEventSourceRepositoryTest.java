package com.finplay.api.domain.feedback.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class PriceMoveEventSourceRepositoryTest {

	@Autowired
	private PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 3);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 3, 9, 10, 0);

	private Instrument instrument;
	private PriceMoveEvent event;
	private MarketNewsItem newsItem;

	@BeforeEach
	void setUp() {
		instrument = instrumentRepository.save(Instrument.create(
			Market.STOCK, "SRC001", "테스트종목A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
		event = priceMoveEventRepository.save(newEvent(LocalTime.of(9, 0)));
		newsItem = marketNewsItemRepository.save(newNewsItem("https://news.example.com/article/1001"));
	}

	private PriceMoveEvent newEvent(LocalTime windowStart) {
		return PriceMoveEvent.createStock(
			instrument,
			PriceMoveEventType.INTRADAY,
			ORIGIN_TRADE_DATE,
			windowStart,
			windowStart.plusMinutes(5),
			new BigDecimal("0.052500"),
			new BigDecimal("3.2500"),
			"반도체 업황 우려로 하락했습니다.",
			NarrativeSource.LLM,
			LocalTime.of(9, 20),
			NOW);
	}

	private MarketNewsItem newNewsItem(String url) {
		return MarketNewsItem.create(
			instrument,
			MarketNewsItemType.NEWS,
			"반도체 업황 둔화",
			"테스트경제",
			url,
			LocalDateTime.of(2026, 8, 3, 8, 40, 0),
			NOW);
	}

	@Test
	@DisplayName("근거 연결이 카드·기사 양쪽 FK로 저장되고 다시 읽힌다")
	void sourceLinkIsStoredWithBothForeignKeys() {
		Long id = priceMoveEventSourceRepository.saveAndFlush(PriceMoveEventSource.of(event, newsItem)).getId();

		PriceMoveEventSource found = priceMoveEventSourceRepository.findById(id).orElseThrow();

		assertThat(found.getPriceMoveEvent().getId()).isEqualTo(event.getId());
		assertThat(found.getMarketNewsItem().getId()).isEqualTo(newsItem.getId());
	}

	@Test
	@DisplayName("같은 (카드, 기사) 쌍 2건째는 유니크 제약에 걸린다")
	void databaseRejectsDuplicateEventAndNewsItemPair() {
		priceMoveEventSourceRepository.saveAndFlush(PriceMoveEventSource.of(event, newsItem));

		PriceMoveEventSource duplicate = PriceMoveEventSource.of(event, newsItem);

		assertThatThrownBy(() -> priceMoveEventSourceRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("카드 하나가 기사 여러 건을 근거로 둘 수 있다")
	void oneCardCanReferenceMultipleNewsItems() {
		MarketNewsItem another = marketNewsItemRepository.save(
			newNewsItem("https://news.example.com/article/1002"));

		priceMoveEventSourceRepository.saveAndFlush(PriceMoveEventSource.of(event, newsItem));
		priceMoveEventSourceRepository.saveAndFlush(PriceMoveEventSource.of(event, another));

		List<PriceMoveEventSource> all = priceMoveEventSourceRepository.findAll();

		assertThat(all).hasSize(2);
		assertThat(all).extracting(link -> link.getMarketNewsItem().getId())
			.containsExactlyInAnyOrder(newsItem.getId(), another.getId());
	}

	@Test
	@DisplayName("기사 하나가 카드 여러 건의 근거가 될 수 있다")
	void oneNewsItemCanBeEvidenceForMultipleCards() {
		PriceMoveEvent anotherEvent = priceMoveEventRepository.save(newEvent(LocalTime.of(10, 0)));

		priceMoveEventSourceRepository.saveAndFlush(PriceMoveEventSource.of(event, newsItem));
		priceMoveEventSourceRepository.saveAndFlush(PriceMoveEventSource.of(anotherEvent, newsItem));

		List<PriceMoveEventSource> all = priceMoveEventSourceRepository.findAll();

		assertThat(all).hasSize(2);
		assertThat(all).extracting(link -> link.getPriceMoveEvent().getId())
			.containsExactlyInAnyOrder(event.getId(), anotherEvent.getId());
	}
}
