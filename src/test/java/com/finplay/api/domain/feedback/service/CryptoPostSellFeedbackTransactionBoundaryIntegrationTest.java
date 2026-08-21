// 코인 매도 회고에서 캔들 REST 4종이 트랜잭션 밖, 원장 조회 둘(B·C)이 트랜잭션 안이라는 것을 실제 MySQL로 고정한다.
package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.feedback.config.FeedbackCryptoProperties;
import com.finplay.api.domain.feedback.entity.HoldHighBasis;
import com.finplay.api.domain.feedback.entity.NarrativeSource;
import com.finplay.api.domain.feedback.entity.PostSellFeedbackStatus;
import com.finplay.api.domain.feedback.entity.PriceMoveEvent;
import com.finplay.api.domain.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.domain.feedback.dto.response.PeerComparison;
import com.finplay.api.domain.feedback.dto.response.PostSellFeedbackResponse;
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

// **이 클래스에 @Transactional이 없는 것이 이 파일의 존재 이유다** (PostSellFeedbackBoundaryIntegrationTest 선례).
// 클래스 트랜잭션을 걸면 테스트 자신의 트랜잭션이 조회 내내 열려 있어 캔들 조회 시점의
// isActualTransactionActive()가 **구현과 무관하게 언제나 true**가 되고, lazy 초기화 누락도 붙잡혀 초록이 된다.
//
// 관측은 두 겹이다 (spec §FEED-012 결정 5).
//   1) 캔들 REST 4종 — 호출 순간 활성 트랜잭션이 **없어야** 한다. CryptoCandleProvider를 감시용 데코레이터로
//      바꿔(운영 코드에는 이런 스위치가 없다) 호출마다 플래그를 남긴다.
//   2) priceMoves(트랜잭션 B)·peerComparison(트랜잭션 C) — 같은 순간 활성 트랜잭션이 **있어야** 한다.
//      CryptoPostSellFeedbackDbReader를 상속한 감시용 빈이 super 호출 직전에 플래그를 남기므로, 그 시점은
//      트랜잭션 애노테이션의 프록시가 이미 트랜잭션을 연 뒤다. 이 반대 방향 단정이 없으면 "트랜잭션이 통째로
//      사라진 것"과 "정확히 REST 구간에서만 닫힌 것"이 구분되지 않는다.
//
// 그리고 응답이 예외 없이 나오는지도 단정한다 — PostSellFeedbackContextReader의 Hibernate.initialize 둘을
// 빼면 여기서 LazyInitializationException으로 red가 된다(테스트 트랜잭션이 없어 붙잡아 줄 세션이 없다).
//
// 커밋한 행은 @AfterEach가 FK 역순으로 지운다. 날짜(2034)·심볼·이메일은 저장소에서 이 파일만 쓴다
// (ai/agent-mistakes.md 2026-08-04 — UNIQUE 픽스처 충돌은 전체 실행에서만 터진다).
@SpringBootTest
@Import({TestcontainersConfiguration.class,
	CryptoPostSellFeedbackTransactionBoundaryIntegrationTest.TransactionBoundaryTestConfig.class})
class CryptoPostSellFeedbackTransactionBoundaryIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private static final String SYMBOL = "TXB282";
	private static final String EMAIL = "post-sell-tx-boundary@finplay.com";

	private static final LocalDate SELL_DATE = LocalDate.of(2034, 8, 6);

	// 보유 120분이라 1분봉 정밀도다(200봉 상한 안). 매도 21:00은 자정까지 179분이라 매도 후 최고가 조회도
	// 상한 안에 든다 — 이 둘이 어긋나면 캔들 호출이 4회가 아니라 2~3회로 줄어 관측 자체가 비어 버린다.
	private static final LocalDateTime BUY_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(19, 0));
	private static final LocalDateTime SELL_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(21, 0));
	private static final LocalDateTime CARD_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(20, 0));
	private static final LocalDateTime AFTER_SELL_AT = LocalDateTime.of(SELL_DATE, LocalTime.of(22, 0));

	// §C-5 게이트가 열린 뒤 조회 — 매도 후 흐름·반사실이 READY라야 캔들 조회 넷이 전부 일어난다.
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

		// 보유 구간 카드 1건이 있어야 트랜잭션 C의 집계 조회와 네 번째 캔들 조회(atFirstMoveAfterBuy)가 실제로
		// 일어난다 — 카드가 0건이면 둘 다 조용히 건너뛴다.
		priceMoveEventRepository.saveAndFlush(PriceMoveEvent.createCrypto(
			coin, CARD_AT, new BigDecimal("0.021000"), new BigDecimal("3.2500"),
			"20시부터 2.1% 상승했습니다.", NarrativeSource.TEMPLATE, CARD_AT));

		// 픽스처를 만드는 동안의 저장도 트랜잭션 안에서 일어나므로 관측을 여기서 비운다.
		transactionProbe.reset();
	}

	// 커밋한 행을 FK 역순으로 지운다 — 이 클래스가 만든 것만 좁혀 지우고 남의 행은 건드리지 않는다.
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

		// 이슈 #282가 막으려는 것 그 자체다 — 빗썸 REST를 부르는 동안 커넥션을 쥔 트랜잭션이 열려 있으면 안 된다.
		assertThat(transactionProbe.observationsOf(TransactionProbe.CANDLE))
			.as("캔들 조회 4종 전부가 트랜잭션 밖이어야 한다")
			.hasSize(4)
			.allSatisfy(active -> assertThat(active).isFalse());
		// 반대 방향 — 트랜잭션이 통째로 사라진 것이 아니라 REST 구간에서만 닫힌 것이다.
		assertThat(transactionProbe.observationsOf(TransactionProbe.PRICE_MOVES))
			.as("트랜잭션 B(보유 구간 카드 조회)")
			.containsExactly(true);
		assertThat(transactionProbe.observationsOf(TransactionProbe.PEER_COMPARISON))
			.as("트랜잭션 C(집단 비교 조회)")
			.containsExactly(true);
		// 순서까지 못박는다 — 셋 중 하나라도 자리를 옮기면 경계가 REST 구간을 다시 가로지른다.
		assertThat(transactionProbe.labels()).containsExactly(
			TransactionProbe.PRICE_MOVES,
			TransactionProbe.CANDLE,
			TransactionProbe.CANDLE,
			TransactionProbe.CANDLE,
			TransactionProbe.CANDLE,
			TransactionProbe.PEER_COMPARISON);

		// 경계를 나눠도 응답이 그대로 나온다. lazy 연관(instrument)을 값으로 바꾼 필드에 닿기 전에
		// Hibernate.initialize가 빠지면 여기까지 오지 못하고 LazyInitializationException이 난다.
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
		// 확정 집계 행을 심지 않았으므로 NOT_YET이다 — 조회가 실제로 일어났다는 것은 관측이 말한다.
		assertThat(response.peerComparison().status()).isEqualTo(PostSellFeedbackStatus.NOT_YET);
	}

	// --- 픽스처 ---

	private static CryptoCandleDto candle(LocalDateTime sourceTime, String close) {
		BigDecimal price = new BigDecimal(close);
		return new CryptoCandleDto(
			sourceTime, price, price.add(new BigDecimal("500")), price.subtract(new BigDecimal("500")),
			price, new BigDecimal("1.5"));
	}

	// 코인 체결이라 재생세션이 null이다 — Trade.of가 코인에 세션을 주면 거부한다.
	private Trade saveTrade(
		User owner, Account account, OrderSide side, BigDecimal price, long amount, long fee, Long realizedPnl,
		LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, account, coin, side, OrderType.MARKET, QUANTITY,
			"idem-" + System.nanoTime(), "a".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, coin, null, side, price, QUANTITY, amount, fee, realizedPnl, executedAt, executedAt));
	}

	/** 관측 지점마다 "그 순간 활성 트랜잭션이 있었는가"를 호출 순서대로 쌓는다. */
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

		// 운영 코드에 관측 스위치를 넣지 않는다 — 데코레이터는 이 @TestConfiguration에만 있고 조회 결과는
		// FakeCryptoCandleProvider가 그대로 준다 (FeedbackQueryCacheConnectionHoldingIntegrationTest 선례).
		@Bean
		@Primary
		CryptoCandleProvider observingCryptoCandleProvider(
			FakeCryptoCandleProvider delegate, TransactionProbe probe) {
			return (symbol, interval, from, to) -> {
				probe.record(TransactionProbe.CANDLE);
				return delegate.getCandles(symbol, interval, from, to);
			};
		}

		// 위임이 아니라 상속이어야 한다 — 위임하면 실제 빈의 트랜잭션 프록시를 거치기 **전에** 관측하게 되어
		// 트랜잭션 B·C가 열려 있어도 언제나 false로 보인다. 상속하면 이 빈 자신이 프록시되므로 아래 override는
		// 트랜잭션이 이미 열린 뒤에 실행된다.
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
