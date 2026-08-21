// Order의 지정가 생성 팩토리·체결 확정 메서드를 검증하는 순수 단위 테스트다.
package com.finplay.api.domain.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
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

	@Test
	void modifyUpdatesQuantityAndLimitPriceWhenOrderIsPending() {
		Order order = limitPendingOrder(BigDecimal.valueOf(70_000_000));

		order.modify(BigDecimal.valueOf(2), BigDecimal.valueOf(80_000_000));

		assertThat(order.getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(2));
		assertThat(order.getLimitPrice()).isEqualByComparingTo(BigDecimal.valueOf(80_000_000));
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING); // 상태는 그대로 유지된다
	}

	@Test
	void modifyThrowsIllegalStateExceptionWhenOrderAlreadyFilled() {
		Order order = limitPendingOrder(BigDecimal.valueOf(70_000_000));
		order.markFilled();

		assertThatThrownBy(() -> order.modify(BigDecimal.valueOf(2), BigDecimal.valueOf(80_000_000)))
			.isInstanceOf(IllegalStateException.class);
		assertThat(order.getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(1)); // 값이 바뀌지 않는다
		assertThat(order.getLimitPrice()).isEqualByComparingTo(BigDecimal.valueOf(70_000_000));
	}

	@Test
	void modifyThrowsIllegalStateExceptionWhenOrderAlreadyCancelled() {
		Order order = limitPendingOrder(BigDecimal.valueOf(70_000_000));
		order.cancel();

		assertThatThrownBy(() -> order.modify(BigDecimal.valueOf(2), BigDecimal.valueOf(80_000_000)))
			.isInstanceOf(IllegalStateException.class);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
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
			Market.CRYPTO, "BTC", "비트코인",
			BigDecimal.valueOf(1000), 5_000L, true, NOW);
	}
}
