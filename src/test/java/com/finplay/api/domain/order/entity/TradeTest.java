package com.finplay.api.domain.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.StockReplaySession;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class TradeTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

	@Test
	void fillRealizedPnlSetsValueWhenNotYetFilled() {
		Trade trade = sellTradeWithNullRealizedPnl();

		trade.fillRealizedPnl(49_900L);

		assertThat(trade.getRealizedPnl()).isEqualTo(49_900L);
	}

	@Test
	void fillRealizedPnlThrowsIllegalStateExceptionWhenAlreadyFilled() {
		Trade trade = sellTradeWithNullRealizedPnl();
		trade.fillRealizedPnl(49_900L);

		assertThatThrownBy(() -> trade.fillRealizedPnl(10_000L))
			.isInstanceOf(IllegalStateException.class);
		assertThat(trade.getRealizedPnl()).isEqualTo(49_900L);
	}

	@Test
	void createsStockTradeWithReplaySession() {
		TradeFixture fixture = fixture(Market.STOCK);
		StockReplaySession session = readySession();

		Trade trade = createTrade(fixture, session);

		assertThat(trade.getStockReplaySession()).isSameAs(session);
	}

	@Test
	void createsCryptoTradeWithoutReplaySession() {
		Trade trade = createTrade(fixture(Market.CRYPTO), null);

		assertThat(trade.getStockReplaySession()).isNull();
	}

	@Test
	void rejectsStockTradeWithoutReplaySession() {
		TradeFixture fixture = fixture(Market.STOCK);

		assertThatThrownBy(() -> createTrade(fixture, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("주식 체결에는 재생세션이 필수입니다.");
	}

	@Test
	void rejectsCryptoTradeWithReplaySession() {
		TradeFixture fixture = fixture(Market.CRYPTO);

		assertThatThrownBy(() -> createTrade(fixture, readySession()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("코인 체결에는 재생세션을 지정할 수 없습니다.");
	}

	@Test
	void rejectsRealStockTradeWithoutReplaySession() {
		TradeFixture fixture = fixture(Market.STOCK);

		assertThatThrownBy(() -> createTrade(fixture, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("주식 체결에는 재생세션이 필수입니다.");
	}

	@Test
	void allowsTutorialSampleStockTradeWithoutReplaySession() {
		TradeFixture fixture = tutorialSampleFixture(Market.STOCK);

		Trade trade = createTrade(fixture, null);

		assertThat(trade.getStockReplaySession()).isNull();
	}

	@Test
	void rejectsTutorialSampleCryptoTradeWithReplaySession() {
		TradeFixture fixture = tutorialSampleFixture(Market.CRYPTO);

		assertThatThrownBy(() -> createTrade(fixture, readySession()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("코인 체결에는 재생세션을 지정할 수 없습니다.");
	}

	private static Trade sellTradeWithNullRealizedPnl() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		Account account = Account.create(user, Market.STOCK, NOW);
		Instrument instrument = Instrument.create(
			Market.STOCK, "TEST01", "테스트종목",
			BigDecimal.valueOf(100), 10_000L, true, NOW);
		Order order = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET,
			BigDecimal.valueOf(10), "idem-key", "a".repeat(64), NOW);
		return Trade.of(
			order, account, instrument, readySession(), OrderSide.SELL,
			BigDecimal.valueOf(75000), BigDecimal.valueOf(10),
			750_000L, 100L, null, NOW, NOW);
	}

	private static TradeFixture fixture(com.finplay.api.domain.market.entity.Market market) {
		User user = User.create("fixture@finplay.com", "password-hash", "fixture", NOW);
		Market accountMarket = Market.valueOf(market.name());
		Account account = Account.create(user, accountMarket, NOW);
		Instrument instrument = Instrument.create(
			market, market == Market.STOCK ? "STOCK1" : "BTC", "종목",
			BigDecimal.ONE, 0L, true, NOW);
		Order order = Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.ONE, "fixture-idem", "b".repeat(64), NOW);
		return new TradeFixture(order, account, instrument);
	}

	private static TradeFixture tutorialSampleFixture(com.finplay.api.domain.market.entity.Market market) {
		TradeFixture fixture = fixture(market);
		org.springframework.test.util.ReflectionTestUtils.setField(
			fixture.instrument(), "tutorialSample", true);
		return fixture;
	}

	private static Trade createTrade(TradeFixture fixture, StockReplaySession session) {
		return Trade.of(
			fixture.order(), fixture.account(), fixture.instrument(), session, OrderSide.BUY,
			BigDecimal.valueOf(100), BigDecimal.ONE, 100L, 0L, null, NOW, NOW);
	}

	private static StockReplaySession readySession() {
		return StockReplaySession.ready(NOW.toLocalDate(), NOW.toLocalDate().minusDays(1), NOW, NOW);
	}

	private record TradeFixture(Order order, Account account, Instrument instrument) {
	}
}
