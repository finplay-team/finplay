// 카드와 근거가 한 트랜잭션에 커밋되는지를 실제 커밋·롤백으로 검증한다 — 근거 0건 카드가 남지 않는다는 보장이다.
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

// 트랜잭션 경계를 보는 테스트라 테스트 자신이 트랜잭션 안에 있으면 안 된다. @DataJpaTest의 기본
// @Transactional(롤백)을 NOT_SUPPORTED로 덮어, writer의 @Transactional이 실제로 커밋·롤백하는 진짜
// 경계가 되게 한다. 그래서 이 클래스만 스스로 뒷정리한다(@AfterEach).
//
// 단정의 의미 — writer에 경계가 없거나 자기호출로 프록시를 못 타면, save(card)가 자기 트랜잭션으로 먼저
// 커밋되어 근거 저장이 실패해도 카드 행이 남는다. 그 카드는 다음 실행에서 중복 판정에 걸려 영영 고쳐지지
// 않고, PriceMoveEventSource가 못박은 "근거 0건 카드는 존재하지 않는다"가 깨진다.
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
		// V7 시드(005930 등)와 겹치지 않는 테스트 전용 심볼 — UNIQUE(symbol) 충돌 방지.
		instrument = instrumentRepository.save(Instrument.create(
			Market.STOCK, "WRITE01", "테스트종목A", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
	}

	// 이 클래스는 실제로 커밋하므로 롤백에 기댈 수 없다. 두 카드 테이블은 테스트만 쓰므로 통째로 비우고,
	// 시드가 있는 instruments는 이 테스트가 만든 행만 지운다.
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

	// 대조군 — 정상 경로에서는 두 테이블에 함께 커밋된다. 아래 롤백 단정이 "원래 아무것도 저장되지 않는
	// 상태"를 본 것이 아님을 보인다.
	@Test
	@DisplayName("정상 경로에서는 카드 1건과 근거 연결이 함께 커밋된다")
	void commitsTheCardAndItsSourcesTogether() {
		List<MarketNewsItem> sources = List.of(saveNews("기사1"), saveNews("기사2"));

		PriceMoveEvent saved = priceMoveCardWriter.persist(newCard(), sources);

		assertThat(priceMoveEventRepository.findById(saved.getId())).isPresent();
		assertThat(priceMoveEventSourceRepository.count()).isEqualTo(2);
	}

	// 근거 저장이 깨지는 자리를 실제로 만든다 — price_move_event_sources의
	// UNIQUE(price_move_event_id, market_news_item_id)를 같은 기사 2건으로 위반시킨다.
	// 경계가 없거나 프록시를 못 타면 카드 행이 남아 이 단정이 깨진다.
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
