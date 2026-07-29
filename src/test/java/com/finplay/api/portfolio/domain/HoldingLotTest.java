// HoldingLot의 FIFO 소비 메서드를 검증하는 순수 단위 테스트다.
package com.finplay.api.portfolio.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.domain.User;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
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
			com.finplay.api.market.domain.Market.STOCK, "TEST01", "테스트종목",
			BigDecimal.valueOf(100), 10_000L, true, NOW);
		Holding holding = Holding.create(account, instrument, NOW);
		Order order = Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			quantity, "idem-key", "a".repeat(64), NOW);
		Trade buyTrade = Trade.of(
			order, account, instrument, OrderSide.BUY,
			BigDecimal.valueOf(70000), quantity,
			70000L * quantity.longValueExact(), 100L, null, NOW, NOW);
		return HoldingLot.create(holding, buyTrade, quantity, BigDecimal.valueOf(70000), 100L, NOW, NOW);
	}
}
