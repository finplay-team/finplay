// Trade의 실현손익 채우기 메서드를 검증하는 순수 단위 테스트다.
package com.finplay.api.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.domain.User;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.StockReplaySession;
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
		TradeFixture fixture = fixture(com.finplay.api.market.domain.Market.STOCK);
		StockReplaySession session = readySession();

		Trade trade = createTrade(fixture, session);

		assertThat(trade.getStockReplaySession()).isSameAs(session);
	}

	@Test
	void createsCryptoTradeWithoutReplaySession() {
		Trade trade = createTrade(fixture(com.finplay.api.market.domain.Market.CRYPTO), null);

		assertThat(trade.getStockReplaySession()).isNull();
	}

	@Test
	void rejectsStockTradeWithoutReplaySession() {
		TradeFixture fixture = fixture(com.finplay.api.market.domain.Market.STOCK);

		assertThatThrownBy(() -> createTrade(fixture, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("주식 체결에는 재생세션이 필수입니다.");
	}

	@Test
	void rejectsCryptoTradeWithReplaySession() {
		TradeFixture fixture = fixture(com.finplay.api.market.domain.Market.CRYPTO);

		assertThatThrownBy(() -> createTrade(fixture, readySession()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("코인 체결에는 재생세션을 지정할 수 없습니다.");
	}

	// 이슈 #339: PriceQueryService.getOrderExecutionPrice가 튜토리얼 샘플 종목에 대해 의도적으로
	// replaySession=null을 반환하는데, 이 불변식이 실제 종목에만 성립하도록 범위를 좁혔는지 확인한다.

	@Test
	void rejectsRealStockTradeWithoutReplaySession() {
		TradeFixture fixture = fixture(com.finplay.api.market.domain.Market.STOCK);

		assertThatThrownBy(() -> createTrade(fixture, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("주식 체결에는 재생세션이 필수입니다.");
	}

	@Test
	void allowsTutorialSampleStockTradeWithoutReplaySession() {
		TradeFixture fixture = tutorialSampleFixture(com.finplay.api.market.domain.Market.STOCK);

		Trade trade = createTrade(fixture, null);

		assertThat(trade.getStockReplaySession()).isNull();
	}

	@Test
	void rejectsTutorialSampleCryptoTradeWithReplaySession() {
		TradeFixture fixture = tutorialSampleFixture(com.finplay.api.market.domain.Market.CRYPTO);

		assertThatThrownBy(() -> createTrade(fixture, readySession()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("코인 체결에는 재생세션을 지정할 수 없습니다.");
	}

	private static Trade sellTradeWithNullRealizedPnl() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		Account account = Account.create(user, Market.STOCK, NOW);
		Instrument instrument = Instrument.create(
			com.finplay.api.market.domain.Market.STOCK, "TEST01", "테스트종목",
			BigDecimal.valueOf(100), 10_000L, true, NOW);
		Order order = Order.create(
			user, account, instrument, OrderSide.SELL, OrderType.MARKET,
			BigDecimal.valueOf(10), "idem-key", "a".repeat(64), NOW);
		return Trade.of(
			order, account, instrument, readySession(), OrderSide.SELL,
			BigDecimal.valueOf(75000), BigDecimal.valueOf(10),
			750_000L, 100L, null, NOW, NOW);
	}

	private static TradeFixture fixture(com.finplay.api.market.domain.Market market) {
		User user = User.create("fixture@finplay.com", "password-hash", "fixture", NOW);
		Market accountMarket = Market.valueOf(market.name());
		Account account = Account.create(user, accountMarket, NOW);
		Instrument instrument = Instrument.create(
			market, market == com.finplay.api.market.domain.Market.STOCK ? "STOCK1" : "BTC", "종목",
			BigDecimal.ONE, 0L, true, NOW);
		Order order = Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.ONE, "fixture-idem", "b".repeat(64), NOW);
		return new TradeFixture(order, account, instrument);
	}

	private static TradeFixture tutorialSampleFixture(com.finplay.api.market.domain.Market market) {
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
