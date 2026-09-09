package com.finplay.api.domain.feedback.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.entity.PriceMoveEventType;
import com.finplay.api.domain.feedback.entity.PriceMovePeerStat;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class PriceMovePeerStatRepositoryTest {

	@Autowired
	private PriceMovePeerStatRepository priceMovePeerStatRepository;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 8, 3);
	private static final LocalDate FIRST_SERVICE_DATE = LocalDate.of(2026, 8, 4);
	private static final LocalDate SECOND_SERVICE_DATE = LocalDate.of(2026, 8, 11);
	private static final LocalDateTime AGGREGATED_AT = LocalDateTime.of(2026, 8, 4, 15, 40, 0);

	private Instrument instrument;
	private PriceMoveEvent event;

	@BeforeEach
	void setUp() {
		instrument = instrumentRepository.save(Instrument.create(
			Market.STOCK, "PEER001", "테스트종목", new BigDecimal("100"), 70000, true, LocalDateTime.now()));
		event = priceMoveEventRepository.save(newEvent(LocalTime.of(9, 0)));
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
			AGGREGATED_AT);
	}

	private PriceMovePeerStat newStat(
		PriceMoveEvent priceMoveEvent, LocalDate serviceDate, Integer medianMinutesToSell) {
		return PriceMovePeerStat.create(
			priceMoveEvent, serviceDate, 12, 5, medianMinutesToSell, AGGREGATED_AT);
	}

	@Test
	@DisplayName("같은 카드라도 서비스 날짜가 다르면 집계 2행이 공존한다 — 재재생 시 첫날 집계가 덮이지 않는다")
	void statsForTheSameCardCoexistAcrossDifferentServiceDates() {
		priceMovePeerStatRepository.saveAndFlush(newStat(event, FIRST_SERVICE_DATE, 18));
		priceMovePeerStatRepository.saveAndFlush(newStat(event, SECOND_SERVICE_DATE, 24));

		List<PriceMovePeerStat> all = priceMovePeerStatRepository.findAll();

		assertThat(all).hasSize(2);
		assertThat(all).extracting(PriceMovePeerStat::getServiceDate)
			.containsExactlyInAnyOrder(FIRST_SERVICE_DATE, SECOND_SERVICE_DATE);
		assertThat(all).extracting(PriceMovePeerStat::getMedianMinutesToSell)
			.containsExactlyInAnyOrder(18, 24);
	}

	@Test
	@DisplayName("같은 (카드, 서비스 날짜) 2건째는 유니크 제약에 걸린다")
	void databaseRejectsDuplicateEventAndServiceDate() {
		priceMovePeerStatRepository.saveAndFlush(newStat(event, FIRST_SERVICE_DATE, 18));

		PriceMovePeerStat duplicate = newStat(event, FIRST_SERVICE_DATE, 24);

		assertThatThrownBy(() -> priceMovePeerStatRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("같은 카드가 두 서비스 날짜에 행을 가져도 조회는 그 서비스 날짜 행만 돌려준다")
	void findsOnlyTheStatRowForTheRequestedServiceDateWhenTheSameCardHasRowsOnTwoServiceDates() {
		priceMovePeerStatRepository.saveAndFlush(newStat(event, FIRST_SERVICE_DATE, 18));
		priceMovePeerStatRepository.saveAndFlush(newStat(event, SECOND_SERVICE_DATE, 24));

		PriceMovePeerStat found = priceMovePeerStatRepository
			.findByPriceMoveEventIdAndServiceDate(event.getId(), SECOND_SERVICE_DATE)
			.orElseThrow();

		assertThat(found.getServiceDate()).isEqualTo(SECOND_SERVICE_DATE);
		assertThat(found.getMedianMinutesToSell()).isEqualTo(24);
		assertThat(found.getMedianMinutesToSell()).isNotEqualTo(18);
	}

	@Test
	@DisplayName("그 서비스 날짜에 확정 집계 행이 없으면(다른 날짜에는 있어도) 빈 Optional이다")
	void findsNothingForAServiceDateWithoutAConfirmedRowEvenWhenAnotherServiceDateHasOne() {
		priceMovePeerStatRepository.saveAndFlush(newStat(event, FIRST_SERVICE_DATE, 18));

		Optional<PriceMovePeerStat> found = priceMovePeerStatRepository
			.findByPriceMoveEventIdAndServiceDate(event.getId(), SECOND_SERVICE_DATE);

		assertThat(found).isEmpty();
	}

	@Test
	@DisplayName("카드가 다르면 같은 서비스 날짜여도 그 카드의 행만 돌려준다")
	void findsOnlyTheRequestedCardsRowEvenWhenAnotherCardSharesTheServiceDate() {
		PriceMoveEvent anotherEvent = priceMoveEventRepository.save(newEvent(LocalTime.of(10, 0)));
		priceMovePeerStatRepository.saveAndFlush(newStat(event, FIRST_SERVICE_DATE, 18));
		priceMovePeerStatRepository.saveAndFlush(newStat(anotherEvent, FIRST_SERVICE_DATE, 22));

		PriceMovePeerStat found = priceMovePeerStatRepository
			.findByPriceMoveEventIdAndServiceDate(event.getId(), FIRST_SERVICE_DATE)
			.orElseThrow();

		assertThat(found.getPriceMoveEvent().getId()).isEqualTo(event.getId());
		assertThat(found.getMedianMinutesToSell()).isEqualTo(18);
	}

	@Test
	@DisplayName("카드가 다르면 같은 서비스 날짜에 집계 2행이 공존한다")
	void statsForDifferentCardsCoexistOnTheSameServiceDate() {
		PriceMoveEvent anotherEvent = priceMoveEventRepository.save(newEvent(LocalTime.of(10, 0)));

		priceMovePeerStatRepository.saveAndFlush(newStat(event, FIRST_SERVICE_DATE, 18));
		priceMovePeerStatRepository.saveAndFlush(newStat(anotherEvent, FIRST_SERVICE_DATE, 22));

		assertThat(priceMovePeerStatRepository.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("median_minutes_to_sell이 NULL인 행(보유자 전원 미매도)이 저장된다 — 0이 아니라 NULL인 것이 의도다")
	void statRowWithNullMedianIsPersistedWhenNobodySold() {
		Long id = priceMovePeerStatRepository.saveAndFlush(
			PriceMovePeerStat.create(event, FIRST_SERVICE_DATE, 7, 0, null, AGGREGATED_AT)).getId();

		PriceMovePeerStat found = priceMovePeerStatRepository.findById(id).orElseThrow();

		assertThat(found.getMedianMinutesToSell()).isNull();
		assertThat(found.getHolderCount()).isEqualTo(7);
		assertThat(found.getSoldWithin30MinCount()).isZero();
		Map<String, Object> row = jdbcTemplate.queryForMap(
			"select median_minutes_to_sell, sold_within_30min_count from price_move_peer_stats where id = ?", id);
		assertThat(row.get("median_minutes_to_sell")).isNull();
		assertThat(((Number)row.get("sold_within_30min_count")).intValue()).isZero();
	}

	@Test
	@DisplayName("표본이 0명인 집계 행도 저장된다 — 표본 부족 판정은 조회 시점 몫이다")
	void statRowWithZeroHoldersIsPersisted() {
		Long id = priceMovePeerStatRepository.saveAndFlush(
			PriceMovePeerStat.create(event, FIRST_SERVICE_DATE, 0, 0, null, AGGREGATED_AT)).getId();

		PriceMovePeerStat found = priceMovePeerStatRepository.findById(id).orElseThrow();

		assertThat(found.getHolderCount()).isZero();
		assertThat(found.getMedianMinutesToSell()).isNull();
	}

	@Test
	@DisplayName("저장한 집계를 다시 읽으면 카드 참조와 집계값이 그대로 복원된다")
	void savedStatRoundTripsAllFields() {
		Long id = priceMovePeerStatRepository.saveAndFlush(newStat(event, FIRST_SERVICE_DATE, 18)).getId();

		PriceMovePeerStat found = priceMovePeerStatRepository.findById(id).orElseThrow();

		assertThat(found.getPriceMoveEvent().getId()).isEqualTo(event.getId());
		assertThat(found.getServiceDate()).isEqualTo(FIRST_SERVICE_DATE);
		assertThat(found.getHolderCount()).isEqualTo(12);
		assertThat(found.getSoldWithin30MinCount()).isEqualTo(5);
		assertThat(found.getMedianMinutesToSell()).isEqualTo(18);
		assertThat(found.getAggregatedAt()).isEqualTo(AGGREGATED_AT);
	}

	@Test
	@DisplayName("집계 테이블에 회원 식별자 컬럼이 없다")
	void statTableHasNoMemberIdentifierColumn() {
		List<String> columns = jdbcTemplate.queryForList(
			"select column_name from information_schema.columns "
				+ "where table_schema = database() and table_name = 'price_move_peer_stats'",
			String.class);

		assertThat(columns).isNotEmpty();
		assertThat(columns).noneMatch(column -> column.toLowerCase().contains("user")
			|| column.toLowerCase().contains("member")
			|| column.toLowerCase().contains("account"));
	}
}
