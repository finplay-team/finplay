package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.domain.feedback.entity.HoldHighBasis;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.repository.PriceMoveEventRepository;
import com.finplay.api.domain.feedback.repository.PriceMovePeerStatRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.service.CandleInterval;
import com.finplay.api.domain.market.service.CryptoCandleDto;
import com.finplay.api.domain.market.service.CryptoCandleProvider;
import com.finplay.api.domain.market.service.FakeCryptoCandleProvider;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.entity.TradeAllocation;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import com.finplay.api.domain.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@Import({TestcontainersConfiguration.class,
	CryptoPostSellFeedbackTransactionBoundaryIntegrationTest.TransactionBoundaryTestConfig.class})
class CryptoPostSellFeedbackTransactionBoundaryIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final String SYMBOL = "TXB282";
	private static final String EMAIL = "post-sell-tx-boundary@finplay.com";

	private static final LocalDate SELL_DATE = LocalDate.of(2034, 8, 6);

	private static final LocalDateTime BUY_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(19, 0));
	private static final LocalDateTime SELL_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(21, 0));
	private static final LocalDateTime CARD_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(20, 0));
	private static final LocalDateTime AFTER_SELL_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(22, 0));

	static final LocalDateTime VIEW_AT = LocalDateTime.of(SELL_DATE.plusDays(1), LocalTime.of(9, 0));

	private static final BigDecimal QUANTITY = new BigDecimal("10");
	private static final BigDecimal BUY_PRICE = new BigDecimal("70000");
	private static final BigDecimal SELL_PRICE = new BigDecimal("68500");

	private static final long ALLOCATED_COST = 700_000L;
	private static final long ALLOCATED_BUY_FEE = 105L;
	private static final long SELL_FEE = 342L;
	private static final long REALIZED_PNL = -15_447L;

	@Autowired
	private PostSellFeedbackReader postSellFeedbackReader;

	@Autowired
	private TransactionProbe transactionProbe;

	@Autowired
	private FakeCryptoCandleProvider fakeCryptoCandleProvider;

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
	private HoldingRepository holdingRepository;

	@Autowired
	private HoldingLotRepository holdingLotRepository;

	@Autowired
	private TradeAllocationRepository tradeAllocationRepository;

	@Autowired
	private PriceMoveEventRepository priceMoveEventRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long userId;
	private Long sellTradeId;
	private Instrument coin;

	@BeforeEach
	void setUp() {
		fakeCryptoCandleProvider.reset();
		fakeCryptoCandleProvider.setCandles(SYMBOL, CandleInterval.ONE_MINUTE, List.of(
			candle(BUY_AT, "70000"),
			candle(CARD_AT, "70800"),
			candle(SELL_AT, "68500"),
			candle(AFTER_SELL_AT, "69100")));
		fakeCryptoCandleProvider.setCandles(SYMBOL, CandleInterval.ONE_DAY, List.of(
			candle(SELL_DATE.atStartOfDay(), "69200")));

		User owner = userRepository.saveAndFlush(User.create(EMAIL, "hash", "txb282", BUY_AT));
		userId = owner.getId();
		coin = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, SYMBOL, "경계테스트코인", BigDecimal.valueOf(1), 5_000L, true, BUY_AT));
		Account account = accountRepository.saveAndFlush(
			Account.create(owner, Market.CRYPTO, BUY_AT));
		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, coin, BUY_AT));

		Trade buyTrade = saveTrade(owner, account, OrderSide.BUY, BUY_PRICE, ALLOCATED_COST, ALLOCATED_BUY_FEE,
			null, BUY_AT);
		HoldingLot lot = holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, QUANTITY, BUY_PRICE, ALLOCATED_BUY_FEE, BUY_AT, BUY_AT));
		Trade sellTrade = saveTrade(owner, account, OrderSide.SELL, SELL_PRICE, 685_000L, SELL_FEE,
			REALIZED_PNL, SELL_AT);
		sellTradeId = sellTrade.getId();
		lot.consume(QUANTITY);
		holdingLotRepository.saveAndFlush(lot);
		tradeAllocationRepository.saveAndFlush(
			TradeAllocation.create(sellTrade, lot, QUANTITY, ALLOCATED_COST, ALLOCATED_BUY_FEE, SELL_AT));

		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createCrypto(
			coin, CARD_AT, new BigDecimal("0.021000"), new BigDecimal("3.2500"),
			"20시부터 2.1% 상승했습니다.", NarrativeSource.TEMPLATE, CARD_AT));

		transactionProbe.reset();
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.update("delete from price_move_peer_stats where price_move_event_id in "
			+ "(select id from price_move_events where instrument_id in "
			+ "(select id from instruments where symbol = ?))", SYMBOL);
		jdbcTemplate.update("delete from price_move_event_sources where price_move_event_id in "
			+ "(select id from price_move_events where instrument_id in "
			+ "(select id from instruments where symbol = ?))", SYMBOL);
		jdbcTemplate.update(
			"delete from price_move_events where instrument_id in (select id from instruments where symbol = ?)",
			SYMBOL);
		jdbcTemplate.update("delete from trade_feedbacks where trade_id in "
			+ "(select id from trades where instrument_id in (select id from instruments where symbol = ?))", SYMBOL);
		jdbcTemplate.update("delete from trade_allocations where sell_trade_id in "
			+ "(select id from trades where instrument_id in (select id from instruments where symbol = ?))", SYMBOL);
		jdbcTemplate.update("delete from holding_lots where buy_trade_id in "
			+ "(select id from trades where instrument_id in (select id from instruments where symbol = ?))", SYMBOL);
		jdbcTemplate.update(
			"delete from holdings where instrument_id in (select id from instruments where symbol = ?)", SYMBOL);
		jdbcTemplate.update(
			"delete from trades where instrument_id in (select id from instruments where symbol = ?)", SYMBOL);
		jdbcTemplate.update(
			"delete from orders where instrument_id in (select id from instruments where symbol = ?)", SYMBOL);
		jdbcTemplate.update("delete from accounts where user_id in (select id from users where email = ?)", EMAIL);
		jdbcTemplate.update("delete from users where email = ?", EMAIL);
		jdbcTemplate.update("delete from instruments where symbol = ?", SYMBOL);
	}

	@Test
	@DisplayName("캔들 REST 4종은 활성 트랜잭션 밖에서, 원장 조회 둘은 트랜잭션 안에서 일어난다")
	void callsCandleRestOutsideAnyTransactionAndReadsTheLedgerInsideOne() {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive())
			.as("이 테스트의 전제 — 운영과 같이 바깥 트랜잭션이 없다")
			.isFalse();

		PostSellFeedbackResponse response = postSellFeedbackReader.read(userId, sellTradeId);

		assertThat(transactionProbe.observationsOf(TransactionProbe.CANDLE))
			.as("캔들 조회 4종 전부가 트랜잭션 밖이어야 한다")
			.hasSize(4)
			.allSatisfy(active -> assertThat(active).isFalse());
		assertThat(transactionProbe.observationsOf(TransactionProbe.PRICE_MOVES))
			.as("트랜잭션 B(보유 구간 카드 조회)")
			.containsExactly(true);
		assertThat(transactionProbe.observationsOf(TransactionProbe.PEER_COMPARISON))
			.as("트랜잭션 C(집단 비교 조회)")
			.containsExactly(true);
		assertThat(transactionProbe.labels()).containsExactly(
			TransactionProbe.PRICE_MOVES,
			TransactionProbe.CANDLE,
			TransactionProbe.CANDLE,
			TransactionProbe.CANDLE,
			TransactionProbe.CANDLE,
			TransactionProbe.PEER_COMPARISON);

		assertThat(response.tradeId()).isEqualTo(sellTradeId);
		assertThat(response.symbol()).isEqualTo(SYMBOL);
		assertThat(response.name()).isEqualTo("경계테스트코인");
		assertThat(response.instrumentId()).isEqualTo(coin.getId());
		assertThat(response.realizedPnl()).isEqualTo(REALIZED_PNL);
		assertThat(response.holdingMinutes()).isEqualTo(120);
		assertThat(response.holdHighBasis()).isEqualTo(HoldHighBasis.MINUTE);
		assertThat(response.holdHighPrice()).isEqualByComparingTo("70800");
		assertThat(response.priceMoves()).hasSize(1);
		assertThat(response.postSellFlow().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.postSellFlow().closePrice()).isEqualByComparingTo("69200");
		assertThat(response.postSellFlow().postSellHighPrice()).isEqualByComparingTo("69100");
		assertThat(response.counterfactuals().status()).isEqualTo(PostSellFeedbackStatus.READY);
		assertThat(response.counterfactuals().atFirstMoveAfterBuy()).isNotNull();
		assertThat(response.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
	}


	private static CryptoCandleDto candle(LocalDateTime sourceTime, String close) {
		BigDecimal price = new BigDecimal(close);
		return new CryptoCandleDto(
			sourceTime, price, price.add(new BigDecimal("500")), price.subtract(new BigDecimal("500")),
			price, new BigDecimal("1.5"));
	}

	private Trade saveTrade(
		User owner, Account account, OrderSide side, BigDecimal price, long amount, long fee, Long realizedPnl,
		LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, account, coin, side, OrderType.MARKET, QUANTITY,
			"idem-" + System.nanoTime(), "a".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, coin, null, side, price, QUANTITY, amount, fee, realizedPnl, executedAt, executedAt));
	}

	static final class TransactionProbe {

		static final String CANDLE = "candle";
		static final String PRICE_MOVES = "priceMoves";
		static final String PEER_COMPARISON = "peerComparison";

		private final List<Observation> observations = new CopyOnWriteArrayList<>();

		void record(String label) {
			observations.add(new Observation(label, TransactionSynchronizationManager.isActualTransactionActive()));
		}

		void reset() {
			observations.clear();
		}

		List<String> labels() {
			return observations.stream().map(Observation::label).toList();
		}

		List<Boolean> observationsOf(String label) {
			return observations.stream()
				.filter(observation -> observation.label().equals(label))
				.map(Observation::transactionActive)
				.toList();
		}

		private record Observation(String label, boolean transactionActive) {
		}
	}

	@TestConfiguration
	static class TransactionBoundaryTestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(VIEW_AT.atZone(KST).toInstant(), KST);
		}

		@Bean
		TransactionProbe transactionProbe() {
			return new TransactionProbe();
		}

		@Bean
		@Primary
		CryptoCandleProvider observingCryptoCandleProvider(
			FakeCryptoCandleProvider delegate, TransactionProbe probe) {
			return (symbol, interval, from, to) -> {
				probe.record(TransactionProbe.CANDLE);
				return delegate.getCandles(symbol, interval, from, to);
			};
		}

		@Bean
		@Primary
		CryptoPostSellFeedbackDbReader observingCryptoPostSellFeedbackDbReader(
			PriceMoveEventRepository priceMoveEventRepository,
			PriceMoveSourceLoader priceMoveSourceLoader,
			PriceMovePeerStatRepository priceMovePeerStatRepository,
			FeedbackCryptoProperties cryptoProperties,
			TransactionProbe probe) {
			return new ObservingCryptoPostSellFeedbackDbReader(
				priceMoveEventRepository, priceMoveSourceLoader, priceMovePeerStatRepository, cryptoProperties,
				probe);
		}
	}

	static class ObservingCryptoPostSellFeedbackDbReader extends CryptoPostSellFeedbackDbReader {

		private final TransactionProbe probe;

		ObservingCryptoPostSellFeedbackDbReader(
			PriceMoveEventRepository priceMoveEventRepository,
			PriceMoveSourceLoader priceMoveSourceLoader,
			PriceMovePeerStatRepository priceMovePeerStatRepository,
			FeedbackCryptoProperties cryptoProperties,
			TransactionProbe probe) {
			super(priceMoveEventRepository, priceMoveSourceLoader, priceMovePeerStatRepository, cryptoProperties);
			this.probe = probe;
		}

		@Override
		List<HeldPriceMoveItem> findHeldPriceMoves(Trade trade, LocalDateTime buyAt, LocalDateTime sellAt) {
			probe.record(TransactionProbe.PRICE_MOVES);
			return super.findHeldPriceMoves(trade, buyAt, sellAt);
		}

		@Override
		PeerComparison buildPeerComparison(List<HeldPriceMoveItem> priceMoves) {
			probe.record(TransactionProbe.PEER_COMPARISON);
			return super.buildPeerComparison(priceMoves);
		}
	}
}
