// 실 DB 위에서 매매 카드 공유 게시물 생성·조회 핵심 시나리오를 검증하는 Testcontainers 통합 테스트다.
package com.finplay.api.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.community.dto.response.CommunityPostResponse;
import com.finplay.api.community.service.CommunityPostService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.domain.HoldingLot;
import com.finplay.api.portfolio.domain.TradeAllocation;
import com.finplay.api.portfolio.repository.HoldingLotRepository;
import com.finplay.api.portfolio.repository.HoldingRepository;
import com.finplay.api.portfolio.repository.TradeAllocationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

// spec 046 완료 조건 4개(코인·주식 각 1건, 소유권 403, 매수체결 400, imageId+sharedTradeId 동시 지정 400)를
// 여기서 함께 본다. 클래스 트랜잭션으로 감싸 각 테스트가 끝나면 자동 롤백된다(선례: CryptoPostSellFeedbackGate
// IntegrationTest) — 이 파일은 트랜잭션 경계 자체를 검증하지 않으므로 PostSellFeedbackBoundaryIntegrationTest처럼
// @Transactional을 뺄 이유가 없다.
@SpringBootTest
@Transactional
@Import(TestcontainersConfiguration.class)
class CommunityPostShareTradeIntegrationTest {

	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate SERVICE_DATE = LocalDate.of(2031, 8, 7);
	private static final String STOCK_SYMBOL = "TEST046S";
	private static final String CRYPTO_SYMBOL = "TEST046C";

	private static final LocalTime BUY_TIME = LocalTime.of(9, 30);
	private static final LocalTime SELL_TIME = LocalTime.of(14, 40);
	private static final LocalDateTime NOW = LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 0));

	private static final BigDecimal QUANTITY = new BigDecimal("10");
	private static final BigDecimal BUY_PRICE = new BigDecimal("70000");
	private static final BigDecimal SELL_PRICE = new BigDecimal("68500");
	private static final long BUY_FEE = 105L;
	private static final Long REALIZED_PNL = -15_207L;

	@Autowired
	private CommunityPostService communityPostService;

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
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	private Long ownerId;
	private Long otherUserId;

	@BeforeEach
	void setUp() {
		User owner = userRepository.saveAndFlush(User.create("share-owner-046@finplay.com", "hash", "share046", NOW));
		ownerId = owner.getId();
		User other = userRepository.saveAndFlush(User.create("share-other-046@finplay.com", "hash", "other046", NOW));
		otherUserId = other.getId();
	}

	@Test
	@DisplayName("본인 소유 주식 매도 체결로 게시물을 만들면 sharedTrade가 정확한 수치로 채워진다")
	void createPostAttachesSharedTradeSummaryForOwnStockSellTrade() {
		Trade sellTrade = givenOwnStockSellTrade(ownerId);

		CommunityPostResponse response = communityPostService.createPost(
			ownerId, "title", "content", null, null, sellTrade.getId());

		assertThat(response.sharedTrade()).isNotNull();
		assertThat(response.sharedTrade().symbol()).isEqualTo(STOCK_SYMBOL);
		assertThat(response.sharedTrade().market()).isEqualTo(Market.STOCK);
		assertThat(response.sharedTrade().buyPrice()).isEqualByComparingTo(BUY_PRICE);
		assertThat(response.sharedTrade().sellPrice()).isEqualByComparingTo(SELL_PRICE);
		assertThat(response.sharedTrade().quantity()).isEqualByComparingTo(QUANTITY);
		assertThat(response.sharedTrade().realizedPnl()).isEqualTo(REALIZED_PNL);
		assertThat(response.sharedTrade().returnRate()).isEqualByComparingTo(expectedReturnRate());

		// 다른 사용자(otherUserId)가 조회해도 같은 값이 보인다 — sharedTrade 계산의 userId는 뷰어가 아니라
		// 게시물 작성자다(TRADESHARE-002).
		CommunityPostResponse reloaded = communityPostService.getPost(response.postId(), otherUserId);
		assertThat(reloaded.sharedTrade()).isNotNull();
		assertThat(reloaded.sharedTrade().returnRate()).isEqualByComparingTo(expectedReturnRate());
	}

	@Test
	@DisplayName("본인 소유 코인 매도 체결로 게시물을 만들면 sharedTrade가 정확한 수치로 채워진다")
	void createPostAttachesSharedTradeSummaryForOwnCryptoSellTrade() {
		Trade sellTrade = givenOwnCryptoSellTrade(ownerId);

		CommunityPostResponse response = communityPostService.createPost(
			ownerId, "title", "content", null, null, sellTrade.getId());

		assertThat(response.sharedTrade()).isNotNull();
		assertThat(response.sharedTrade().symbol()).isEqualTo(CRYPTO_SYMBOL);
		assertThat(response.sharedTrade().market()).isEqualTo(Market.CRYPTO);
		assertThat(response.sharedTrade().buyPrice()).isEqualByComparingTo(BUY_PRICE);
		assertThat(response.sharedTrade().sellPrice()).isEqualByComparingTo(SELL_PRICE);
		assertThat(response.sharedTrade().quantity()).isEqualByComparingTo(QUANTITY);
		assertThat(response.sharedTrade().realizedPnl()).isEqualTo(REALIZED_PNL);
		assertThat(response.sharedTrade().returnRate()).isEqualByComparingTo(expectedReturnRate());
	}

	@Test
	@DisplayName("타인 소유 매도 체결을 공유하려 하면 403이고 게시물이 만들어지지 않는다")
	void createPostFailsWithForbiddenWhenSharedTradeBelongsToAnotherUser() {
		Trade othersSellTrade = givenOwnCryptoSellTrade(otherUserId);

		assertThatThrownBy(() -> communityPostService.createPost(
			ownerId, "title", "content", null, null, othersSellTrade.getId()))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.FORBIDDEN);
	}

	@Test
	@DisplayName("매수 체결을 공유하려 하면 400이고 게시물이 만들어지지 않는다")
	void createPostFailsWithValidationErrorWhenSharedTradeIsABuyTrade() {
		Trade buyTrade = givenOwnCryptoBuyTrade(ownerId);

		assertThatThrownBy(() -> communityPostService.createPost(
			ownerId, "title", "content", null, null, buyTrade.getId()))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
	}

	@Test
	@DisplayName("이미지와 매매 카드를 동시에 지정하면 400이고 게시물이 만들어지지 않는다")
	void createPostFailsWithValidationErrorWhenImageAndSharedTradeAreBothProvided() {
		Trade sellTrade = givenOwnCryptoSellTrade(ownerId);

		// 충돌 검증이 이미지 조회보다 먼저라 존재하지 않는 imageId로도 재현된다(구현 순서에 대한 실제 통합 근거).
		assertThatThrownBy(() -> communityPostService.createPost(
			ownerId, "title", "content", null, 999_999L, sellTrade.getId()))
			.isInstanceOf(BusinessException.class)
			.extracting(exception -> ((BusinessException)exception).getErrorCode())
			.isEqualTo(ErrorCode.VALIDATION_ERROR);
	}

	// realizedPnl ÷ (배분된 매수원가 합 + 배분된 매수수수료 합), scale 4 HALF_UP — PostSellArithmetic과 같은 식.
	private static BigDecimal expectedReturnRate() {
		BigDecimal buyBasis = BUY_PRICE.multiply(QUANTITY).add(BigDecimal.valueOf(BUY_FEE));
		return BigDecimal.valueOf(REALIZED_PNL).divide(buyBasis, 4, RoundingMode.HALF_UP);
	}

	// --- 픽스처 ---

	private Trade givenOwnStockSellTrade(Long userId) {
		User owner = userRepository.findById(userId).orElseThrow();
		Account account = accountRepository.saveAndFlush(
			Account.create(owner, com.finplay.api.account.domain.Market.STOCK, NOW));
		Instrument stock = instrumentRepository.saveAndFlush(Instrument.create(
			Market.STOCK, STOCK_SYMBOL, "테스트종목046", BigDecimal.valueOf(100), 10_000L, true, NOW));
		LocalDateTime resolvedAt = LocalDateTime.of(SERVICE_DATE, LocalTime.of(8, 40));
		StockReplaySession session = stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.ready(SERVICE_DATE, ORIGIN_TRADE_DATE, resolvedAt, resolvedAt));
		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, stock, NOW));

		saveCandle(stock, BUY_TIME, "70000");
		saveCandle(stock, SELL_TIME, SELL_PRICE.toPlainString());

		Trade buyTrade = saveStockTrade(
			owner, account, stock, session, OrderSide.BUY, BUY_PRICE, null, LocalDateTime.of(SERVICE_DATE, BUY_TIME));
		HoldingLot lot = holdingLotRepository.saveAndFlush(HoldingLot.create(
			holding, buyTrade, QUANTITY, BUY_PRICE, BUY_FEE, LocalDateTime.of(SERVICE_DATE, BUY_TIME), NOW));
		Trade sellTrade = saveStockTrade(
			owner, account, stock, session, OrderSide.SELL, SELL_PRICE, REALIZED_PNL,
			LocalDateTime.of(SERVICE_DATE, SELL_TIME));
		tradeAllocationRepository.saveAndFlush(TradeAllocation.create(
			sellTrade, lot, QUANTITY, BUY_PRICE.multiply(QUANTITY).longValueExact(), BUY_FEE, NOW));
		return sellTrade;
	}

	private Trade givenOwnCryptoSellTrade(Long userId) {
		User owner = userRepository.findById(userId).orElseThrow();
		Account account = accountRepository.saveAndFlush(
			Account.create(owner, com.finplay.api.account.domain.Market.CRYPTO, NOW));
		Instrument coin = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, CRYPTO_SYMBOL, "테스트코인046", BigDecimal.ONE, 5_000L, true, NOW));
		Holding holding = holdingRepository.saveAndFlush(Holding.create(account, coin, NOW));

		LocalDateTime buyAt = LocalDateTime.of(SERVICE_DATE, BUY_TIME);
		LocalDateTime sellAt = LocalDateTime.of(SERVICE_DATE, SELL_TIME);
		Trade buyTrade = saveCryptoTrade(owner, account, coin, OrderSide.BUY, BUY_PRICE, null, buyAt);
		HoldingLot lot = holdingLotRepository.saveAndFlush(
			HoldingLot.create(holding, buyTrade, QUANTITY, BUY_PRICE, BUY_FEE, buyAt, NOW));
		Trade sellTrade = saveCryptoTrade(owner, account, coin, OrderSide.SELL, SELL_PRICE, REALIZED_PNL, sellAt);

		lot.consume(QUANTITY);
		holdingLotRepository.saveAndFlush(lot);
		tradeAllocationRepository.saveAndFlush(TradeAllocation.create(
			sellTrade, lot, QUANTITY, BUY_PRICE.multiply(QUANTITY).longValueExact(), BUY_FEE, NOW));
		return sellTrade;
	}

	private Trade givenOwnCryptoBuyTrade(Long userId) {
		User owner = userRepository.findById(userId).orElseThrow();
		Account account = accountRepository.saveAndFlush(
			Account.create(owner, com.finplay.api.account.domain.Market.CRYPTO, NOW));
		Instrument coin = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, CRYPTO_SYMBOL + "B", "테스트코인046매수", BigDecimal.ONE, 5_000L, true, NOW));
		return saveCryptoTrade(
			owner, account, coin, OrderSide.BUY, BUY_PRICE, null, LocalDateTime.of(SERVICE_DATE, BUY_TIME));
	}

	private void saveCandle(Instrument stock, LocalTime candleTime, String close) {
		BigDecimal price = new BigDecimal(close);
		stockCandleRepository.saveAndFlush(StockCandle.create(
			stock, ORIGIN_TRADE_DATE, candleTime, price, price.add(new BigDecimal("300")),
			price.subtract(new BigDecimal("300")), price, 1_000L, "TEST", NOW));
	}

	private Trade saveStockTrade(
		User owner, Account account, Instrument stock, StockReplaySession session, OrderSide side, BigDecimal price,
		Long realizedPnl, LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, account, stock, side, OrderType.MARKET, QUANTITY, "idem-046-" + System.nanoTime(),
			"a".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, stock, session, side, price, QUANTITY, price.multiply(QUANTITY).longValueExact(), BUY_FEE,
			realizedPnl, executedAt, executedAt));
	}

	private Trade saveCryptoTrade(
		User owner, Account account, Instrument coin, OrderSide side, BigDecimal price, Long realizedPnl,
		LocalDateTime executedAt) {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, account, coin, side, OrderType.MARKET, QUANTITY, "idem-046-" + System.nanoTime(),
			"b".repeat(64), executedAt));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, coin, null, side, price, QUANTITY, price.multiply(QUANTITY).longValueExact(), BUY_FEE,
			realizedPnl, executedAt, executedAt));
	}
}
