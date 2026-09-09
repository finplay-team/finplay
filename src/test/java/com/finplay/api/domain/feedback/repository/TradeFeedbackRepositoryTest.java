package com.finplay.api.domain.feedback.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.TradeFeedback;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.repository.StockReplaySessionRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
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
class TradeFeedbackRepositoryTest {

	@Autowired
	private TradeFeedbackRepository tradeFeedbackRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 3, 10, 0, 0);
	private static final LocalDateTime GENERATED_AT = LocalDateTime.of(2026, 8, 3, 10, 1, 0);
	private static final String NO_JOURNAL = null;
	private static final String NARRATIVE = "급등 직후 매도해 수익을 확정했습니다.";

	private User user;
	private Account account;
	private Instrument instrument;
	private Trade trade;
	private StockReplaySession session;
	private int sequence = 0;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("feedback-owner@finplay.com", "hash", "feedbackowner", NOW));
		account = accountRepository.saveAndFlush(
			Account.create(user, Market.STOCK, NOW));
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "FB001", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, NOW));
		session = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(NOW.toLocalDate().plusYears(30), NOW.toLocalDate(), NOW, NOW));
		trade = createSellTrade();
	}

	private Trade createSellTrade() {
		sequence++;
		Order order = orderRepository.saveAndFlush(Order.create(
			user,
			account,
			instrument,
			OrderSide.SELL,
			OrderType.MARKET,
			BigDecimal.valueOf(10),
			"feedback-idem-" + sequence,
			String.valueOf((char)('a' + sequence)).repeat(64),
			NOW));
		return tradeRepository.saveAndFlush(Trade.of(
			order,
			account,
			instrument,
			session,
			OrderSide.SELL,
			BigDecimal.valueOf(100),
			BigDecimal.valueOf(10),
			1_000L,
			1L,
			null,
			NOW,
			NOW));
	}


	@Test
	@DisplayName("같은 체결에 피드백 2건째는 유니크 제약에 걸린다 — 체결 1건당 1행이다")
	void databaseRejectsSecondFeedbackForTheSameTrade() {
		tradeFeedbackRepository.saveAndFlush(
			TradeFeedback.create(trade, NARRATIVE, NarrativeSource.LLM, NO_JOURNAL, GENERATED_AT));

		TradeFeedback duplicate = TradeFeedback.create(trade, NARRATIVE, NarrativeSource.TEMPLATE, NO_JOURNAL,
			GENERATED_AT);

		assertThatThrownBy(() -> tradeFeedbackRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("체결이 다르면 피드백 2건이 공존한다")
	void feedbacksForDifferentTradesCoexist() {
		Trade anotherTrade = createSellTrade();

		tradeFeedbackRepository.saveAndFlush(
			TradeFeedback.create(trade, NARRATIVE, NarrativeSource.LLM, NO_JOURNAL, GENERATED_AT));
		tradeFeedbackRepository.saveAndFlush(
			TradeFeedback.create(anotherTrade, NARRATIVE, NarrativeSource.LLM, NO_JOURNAL, GENERATED_AT));

		assertThat(tradeFeedbackRepository.count()).isEqualTo(2);
	}


	@Test
	@DisplayName("팩토리로 만든 피드백은 실제 컬럼에서 narrative_finalized=false, regeneration_attempts=0이다")
	void newlyCreatedFeedbackStartsUnfinalizedWithZeroAttempts() {
		Long id = tradeFeedbackRepository.saveAndFlush(
			TradeFeedback.create(trade, NARRATIVE, NarrativeSource.LLM, NO_JOURNAL, GENERATED_AT)).getId();

		Map<String, Object> row = jdbcTemplate.queryForMap(
			"select narrative_finalized, regeneration_attempts from trade_feedbacks where id = ?", id);

		assertThat(row.get("narrative_finalized")).isEqualTo(false);
		assertThat(((Number)row.get("regeneration_attempts")).intValue()).isZero();
		TradeFeedback found = tradeFeedbackRepository.findById(id).orElseThrow();
		assertThat(found.isNarrativeFinalized()).isFalse();
		assertThat(found.getRegenerationAttempts()).isZero();
	}

	@Test
	@DisplayName("저장한 피드백을 다시 읽으면 서술·출처·생성 시각이 그대로 복원된다")
	void savedFeedbackRoundTripsAllFields() {
		Long id = tradeFeedbackRepository.saveAndFlush(
			TradeFeedback.create(trade, NARRATIVE, NarrativeSource.TEMPLATE, NO_JOURNAL, GENERATED_AT)).getId();

		TradeFeedback found = tradeFeedbackRepository.findById(id).orElseThrow();

		assertThat(found.getTrade().getId()).isEqualTo(trade.getId());
		assertThat(found.getNarrative()).isEqualTo(NARRATIVE);
		assertThat(found.getNarrativeSource()).isEqualTo(NarrativeSource.TEMPLATE);
		assertThat(found.getGeneratedAt()).isEqualTo(GENERATED_AT);
	}

	@Test
	@DisplayName("narrative_source가 이름 문자열로 저장된다")
	void narrativeSourceIsStoredAsItsName() {
		tradeFeedbackRepository.saveAndFlush(
			TradeFeedback.create(trade, NARRATIVE, NarrativeSource.TEMPLATE, NO_JOURNAL, GENERATED_AT));

		assertThat(jdbcTemplate.queryForObject("select narrative_source from trade_feedbacks", String.class))
			.isEqualTo("TEMPLATE");
	}

	@Test
	@DisplayName("narrative는 varchar(255)를 넘는 서술도 잘리지 않고 그대로 복원된다")
	void narrativeColumnKeepsTextLongerThanTwoHundredFiftyFiveCharacters() {
		String longNarrative = "급등 직후 매도해 수익을 확정했습니다. ".repeat(30);
		assertThat(longNarrative.length()).isGreaterThan(255);

		Long id = tradeFeedbackRepository.saveAndFlush(
			TradeFeedback.create(trade, longNarrative, NarrativeSource.LLM, NO_JOURNAL, GENERATED_AT)).getId();

		assertThat(tradeFeedbackRepository.findById(id).orElseThrow().getNarrative()).isEqualTo(longNarrative);
	}


	@Test
	@DisplayName("피드백을 저장해도 참조한 체결 원장 행이 한 컬럼도 변하지 않는다")
	void savingFeedbackLeavesTheReferencedTradeRowUntouched() {
		Map<String, Object> before = jdbcTemplate.queryForMap("select * from trades where id = ?", trade.getId());
		long tradeCountBefore = jdbcTemplate.queryForObject("select count(*) from trades", Long.class);

		tradeFeedbackRepository.saveAndFlush(
			TradeFeedback.create(trade, NARRATIVE, NarrativeSource.LLM, NO_JOURNAL, GENERATED_AT));

		Map<String, Object> after = jdbcTemplate.queryForMap("select * from trades where id = ?", trade.getId());
		assertThat(after).isEqualTo(before);
		assertThat(jdbcTemplate.queryForObject("select count(*) from trades", Long.class))
			.isEqualTo(tradeCountBefore);
	}

	@Test
	@DisplayName("피드백을 지워도 체결 원장 행은 그대로 남는다")
	void deletingFeedbackDoesNotCascadeIntoTheTradeLedger() {
		TradeFeedback feedback = tradeFeedbackRepository.saveAndFlush(
			TradeFeedback.create(trade, NARRATIVE, NarrativeSource.LLM, NO_JOURNAL, GENERATED_AT));

		tradeFeedbackRepository.delete(feedback);
		tradeFeedbackRepository.flush();

		assertThat(tradeFeedbackRepository.count()).isZero();
		assertThat(tradeRepository.findById(trade.getId())).isPresent();
	}
}
