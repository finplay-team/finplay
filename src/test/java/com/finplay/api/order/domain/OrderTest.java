// Order의 지정가 생성 팩토리·체결 확정 메서드를 검증하는 순수 단위 테스트다.
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

class OrderTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0, 0);

	@Test
	void createLimitPendingCreatesOrderWithLimitTypeAndPendingStatus() {
		Order order = limitPendingOrder(BigDecimal.valueOf(70_000_000));

		assertThat(order.getOrderType()).isEqualTo(OrderType.LIMIT);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(order.getLimitPrice()).isEqualByComparingTo(BigDecimal.valueOf(70_000_000));
	}

	@Test
	void createDoesNotSetLimitPriceAndAlwaysCreatesMarketFilledOrder() {
		User user = testUser();
		Account account = Account.create(user, Market.CRYPTO, NOW);
		Instrument instrument = testInstrument();

		Order order = Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(1), "idem-market", "a".repeat(64), NOW);

		assertThat(order.getOrderType()).isEqualTo(OrderType.MARKET);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(order.getLimitPrice()).isNull();
	}

	@Test
	void markFilledTransitionsPendingOrderToFilled() {
		Order order = limitPendingOrder(BigDecimal.valueOf(70_000_000));

		order.markFilled();

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
	}

	@Test
	void markFilledThrowsIllegalStateExceptionWhenCalledTwice() {
		Order order = limitPendingOrder(BigDecimal.valueOf(70_000_000));
		order.markFilled();

		assertThatThrownBy(order::markFilled).isInstanceOf(IllegalStateException.class);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
	}

	@Test
	void cancelTransitionsPendingOrderToCancelled() {
		Order order = limitPendingOrder(BigDecimal.valueOf(70_000_000));

		order.cancel();

		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
	}

	@Test
	void cancelThrowsIllegalStateExceptionWhenOrderAlreadyCancelled() {
		Order order = limitPendingOrder(BigDecimal.valueOf(70_000_000));
		order.cancel();

		assertThatThrownBy(order::cancel).isInstanceOf(IllegalStateException.class);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
	}

	@Test
	void cancelThrowsIllegalStateExceptionWhenOrderAlreadyFilled() {
		Order order = limitPendingOrder(BigDecimal.valueOf(70_000_000));
		order.markFilled();

		assertThatThrownBy(order::cancel).isInstanceOf(IllegalStateException.class);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
	}

	private static Order limitPendingOrder(BigDecimal limitPrice) {
		User user = testUser();
		Account account = Account.create(user, Market.CRYPTO, NOW);
		Instrument instrument = testInstrument();
		return Order.createLimitPending(
			user, account, instrument, OrderSide.BUY,
			BigDecimal.valueOf(1), limitPrice, "idem-limit", "b".repeat(64), NOW);
	}

	private static User testUser() {
		return User.create("trader@finplay.com", "password-hash", "trader", NOW);
	}

	private static Instrument testInstrument() {
		return Instrument.create(
			com.finplay.api.market.domain.Market.CRYPTO, "BTC", "비트코인",
			BigDecimal.valueOf(1000), 5_000L, true, NOW);
	}
}
