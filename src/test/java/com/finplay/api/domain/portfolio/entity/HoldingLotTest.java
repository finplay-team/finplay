package com.finplay.api.domain.portfolio.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

class HoldingLotTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

	@Test
	void consumePartiallyReducesRemainingQuantity() {
		HoldingLot lot = lotWithQuantity(BigDecimal.valueOf(10));

		lot.consume(BigDecimal.valueOf(4));

		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.valueOf(6));
		assertThat(lot.getOriginalQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
	}

	@Test
	void consumeExactRemainingQuantityLeavesZero() {
		HoldingLot lot = lotWithQuantity(BigDecimal.valueOf(10));

		lot.consume(BigDecimal.valueOf(10));

		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void consumeThrowsIllegalStateExceptionWhenQuantityExceedsRemaining() {
		HoldingLot lot = lotWithQuantity(BigDecimal.valueOf(10));

		assertThatThrownBy(() -> lot.consume(BigDecimal.valueOf(11)))
			.isInstanceOf(IllegalStateException.class);
		assertThat(lot.getRemainingQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
	}

	private static HoldingLot lotWithQuantity(BigDecimal quantity) {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		Account account = Account.create(user, Market.STOCK, NOW);
		Instrument instrument = Instrument.create(
			Market.STOCK, "TEST01", "테스트종목",
			BigDecimal.valueOf(100), 10_000L, true, NOW);
		Holding holding = Holding.create(account, instrument, NOW);
		Order order = Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			quantity, "idem-key", "a".repeat(64), NOW);
		Trade buyTrade = Trade.of(
			order, account, instrument,
			com.finplay.api.domain.market.entity.StockReplaySession.ready(NOW.toLocalDate(), NOW.toLocalDate(), NOW,
				NOW),
			OrderSide.BUY,
			BigDecimal.valueOf(70000), quantity,
			70000L * quantity.longValueExact(), 100L, null, NOW, NOW);
		return HoldingLot.create(holding, buyTrade, quantity, BigDecimal.valueOf(70000), 100L, NOW, NOW);
	}
}
