package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.entity.MarketNewsItem;
import com.finplay.api.domain.feedback.entity.MarketNewsItemType;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.repository.MarketNewsItemRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMoveEventSourceRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, PriceMoveCardWriter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PriceMoveCardWriterTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 28);

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 8, 45);

	@Autowired
	private PriceMoveCardWriter priceMoveCardWriter;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private PriceMoveEventSourceRepository priceMoveEventSourceRepository;

	@Autowired
	private MarketNewsItemRepository marketNewsItemRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	private Instrument instrument;

	@BeforeEach
	void setUp() {
		instrument = instrumentRepository.save(Instrument.create(
			Market.STOCK, "WRITE01", "테스트종목A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
	}

	@AfterEach
	void tearDown() {
		priceMoveEventSourceRepository.deleteAllInBatch();
		priceMoveEventRepository.deleteAllInBatch();
		marketNewsItemRepository.deleteAllInBatch();
		instrumentRepository.deleteById(instrument.getId());
	}

	private MarketNewsItem saveNews(String title) {
		return marketNewsItemRepository.save(MarketNewsItem.create(
			instrument,
			MarketNewsItemType.NEWS,
			title,
			"테스트경제",
			"https://news.example.com/" + title,
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)),
			NOW));
	}

	private PriceMoveEvent newCard() {
		return PriceMoveEvent.createStock(
			instrument,
			PriceMoveEventType.INTRADAY,
			ORIGIN_TRADE_DATE,
			LocalTime.of(11, 20),
			LocalTime.of(11, 25),
			new BigDecimal("-0.018200"),
			new BigDecimal("3.2500"),
			"반도체 업황 우려로 하락했습니다.",
			NarrativeSource.LLM,
			LocalTime.of(11, 26),
			NOW);
	}

	@Test
	@DisplayName("정상 경로에서는 카드 1건과 근거 연결이 함께 커밋된다")
	void commitsTheCardAndItsSourcesTogether() {
		List<MarketNewsItem> sources = List.of(saveNews("기사1"), saveNews("기사2"));

		PriceMoveEvent saved = priceMoveCardWriter.persist(newCard(), sources);

		assertThat(priceMoveEventRepository.findById(saved.getId())).isPresent();
		assertThat(priceMoveEventSourceRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("근거 저장이 실패하면 카드 행도 남지 않는다")
	void rollsBackTheCardWhenSavingItsSourcesFails() {
		MarketNewsItem sameArticle = saveNews("기사1");

		assertThatThrownBy(() -> priceMoveCardWriter.persist(newCard(), List.of(sameArticle, sameArticle)))
			.isInstanceOf(DataAccessException.class);

		assertThat(priceMoveEventRepository.count()).isZero();
		assertThat(priceMoveEventSourceRepository.count()).isZero();
	}
}
