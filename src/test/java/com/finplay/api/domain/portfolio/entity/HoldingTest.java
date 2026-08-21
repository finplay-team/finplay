// Holding의 매도 반영 메서드를 검증하는 순수 단위 테스트다.
package com.finplay.api.domain.portfolio.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class HoldingTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalDateTime LATER = LocalDateTime.of(2026, 7, 29, 11, 0, 0);

	@Test
	void applySellReducesQuantityAndKeepsAveragePriceUnchanged() {
		Holding holding = holdingWithQuantityAndPrice(BigDecimal.valueOf(10), BigDecimal.valueOf(70000));

		holding.applySell(BigDecimal.valueOf(4), LATER);

		assertThat(holding.getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(6));
		assertThat(holding.getAveragePrice()).isEqualByComparingTo(BigDecimal.valueOf(70000));
		assertThat(holding.isActive()).isTrue();
		assertThat(holding.getUpdatedAt()).isEqualTo(LATER);
	}

	@Test
	void applySellDeactivatesHoldingWhenQuantityReachesZero() {
		Holding holding = holdingWithQuantityAndPrice(BigDecimal.valueOf(10), BigDecimal.valueOf(70000));

		holding.applySell(BigDecimal.valueOf(10), LATER);

		assertThat(holding.getQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(holding.isActive()).isFalse();
	}

	@Test
	void applySellThrowsIllegalStateExceptionWhenQuantityExceedsHolding() {
		Holding holding = holdingWithQuantityAndPrice(BigDecimal.valueOf(10), BigDecimal.valueOf(70000));

		assertThatThrownBy(() -> holding.applySell(BigDecimal.valueOf(11), LATER))
			.isInstanceOf(IllegalStateException.class);
		assertThat(holding.getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
	}

	@Test
	void getAvailableQuantityReturnsQuantityMinusReservedQuantity() {
		Holding holding = holdingWithQuantityAndPrice(BigDecimal.valueOf(10), BigDecimal.valueOf(70000));

		holding.reserveQuantity(BigDecimal.valueOf(4));

		assertThat(holding.getAvailableQuantity()).isEqualByComparingTo(BigDecimal.valueOf(6));
	}

	@Test
	void reserveQuantityIncreasesReservedQuantityWithoutChangingQuantity() {
		Holding holding = holdingWithQuantityAndPrice(BigDecimal.valueOf(10), BigDecimal.valueOf(70000));

		holding.reserveQuantity(BigDecimal.valueOf(4));

		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(BigDecimal.valueOf(4));
		assertThat(holding.getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
	}

	@Test
	void reserveQuantityThrowsIllegalStateExceptionWhenExceedsAvailableQuantity() {
		Holding holding = holdingWithQuantityAndPrice(BigDecimal.valueOf(10), BigDecimal.valueOf(70000));

		assertThatThrownBy(() -> holding.reserveQuantity(BigDecimal.valueOf(11)))
			.isInstanceOf(IllegalStateException.class);
		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void releaseReservedQuantityDecreasesReservedQuantity() {
		Holding holding = holdingWithQuantityAndPrice(BigDecimal.valueOf(10), BigDecimal.valueOf(70000));
		holding.reserveQuantity(BigDecimal.valueOf(4));

		holding.releaseReservedQuantity(BigDecimal.valueOf(4));

		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(holding.getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(10));
	}

	@Test
	void releaseReservedQuantityThrowsIllegalStateExceptionWhenExceedsReservedQuantity() {
		Holding holding = holdingWithQuantityAndPrice(BigDecimal.valueOf(10), BigDecimal.valueOf(70000));
		holding.reserveQuantity(BigDecimal.valueOf(4));

		assertThatThrownBy(() -> holding.releaseReservedQuantity(BigDecimal.valueOf(5)))
			.isInstanceOf(IllegalStateException.class);
		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(BigDecimal.valueOf(4));
	}

	private static Holding holdingWithQuantityAndPrice(BigDecimal quantity, BigDecimal price) {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		Account account = Account.create(user, Market.STOCK, NOW);
		Instrument instrument = Instrument.create(
			Market.STOCK, "TEST01", "테스트종목",
			BigDecimal.valueOf(100), 10_000L, true, NOW);
		Holding holding = Holding.create(account, instrument, NOW);
		holding.applyBuy(quantity, price, NOW);
		return holding;
	}
}
