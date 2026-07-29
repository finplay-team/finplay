// Trade의 실현손익 채우기 메서드를 검증하는 순수 단위 테스트다.
package com.finplay.api.order.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.domain.User;
import com.finplay.api.market.domain.Instrument;
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
			order, account, instrument, OrderSide.SELL,
			BigDecimal.valueOf(75000), BigDecimal.valueOf(10),
			750_000L, 100L, null, NOW, NOW);
	}
}
