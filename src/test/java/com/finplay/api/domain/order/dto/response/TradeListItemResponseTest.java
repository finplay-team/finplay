// TradeListItemResponse.from(Trade)의 필드 매핑을 실제 Trade 인스턴스로 검증하는 단위 테스트다.
package com.finplay.api.domain.order.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TradeListItemResponseTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

	@Test
	void fromMapsAllFieldsFromBuyTradeWithNullRealizedPnl() {
		Trade trade = buildTrade(
			101L, OrderSide.BUY, BigDecimal.valueOf(75000), BigDecimal.valueOf(10),
			750_000L, 100L, null, LocalDateTime.of(2026, 7, 18, 10, 0, 0));

		TradeListItemResponse response = TradeListItemResponse.from(trade);

		assertThat(response.tradeId()).isEqualTo(101L);
		assertThat(response.instrumentId()).isEqualTo(trade.getInstrument().getId());
		assertThat(response.side()).isEqualTo("BUY");
		assertThat(response.price()).isEqualByComparingTo(BigDecimal.valueOf(75000));
		assertThat(response.quantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
		assertThat(response.amount()).isEqualTo(750_000L);
		assertThat(response.fee()).isEqualTo(100L);
		assertThat(response.realizedPnl()).isNull();
		assertThat(response.executedAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 0, 0));
	}

	@Test
	void fromMapsRealizedPnlFromSellTrade() {
		Trade trade = buildTrade(
			202L, OrderSide.SELL, BigDecimal.valueOf(80000), BigDecimal.valueOf(5),
			400_000L, 50L, 25_000L, LocalDateTime.of(2026, 7, 20, 11, 30, 0));

		TradeListItemResponse response = TradeListItemResponse.from(trade);

		assertThat(response.tradeId()).isEqualTo(202L);
		assertThat(response.side()).isEqualTo("SELL");
		assertThat(response.realizedPnl()).isEqualTo(25_000L);
		assertThat(response.executedAt()).isEqualTo(LocalDateTime.of(2026, 7, 20, 11, 30, 0));
	}

	private static Trade buildTrade(
		long id,
		OrderSide side,
		BigDecimal price,
		BigDecimal quantity,
		long amount,
		long fee,
		Long realizedPnl,
		LocalDateTime executedAt) {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		Account account = Account.create(user, Market.STOCK, NOW);
		Instrument instrument = Instrument.create(
			Market.STOCK, "TEST01", "테스트종목",
			BigDecimal.valueOf(100), 10_000L, true, NOW);
		Order order = Order.create(
			user, account, instrument, side, OrderType.MARKET,
			quantity, "idem-key-" + id, "a".repeat(64), NOW);
		Trade trade = Trade.of(
			order, account, instrument,
			com.finplay.api.domain.market.entity.StockReplaySession.ready(NOW.toLocalDate(), NOW.toLocalDate(), NOW,
				NOW),
			side, price, quantity, amount, fee, realizedPnl, executedAt, NOW);
		ReflectionTestUtils.setField(trade, "id", id);
		ReflectionTestUtils.setField(instrument, "id", id + 1000);
		return trade;
	}
}
