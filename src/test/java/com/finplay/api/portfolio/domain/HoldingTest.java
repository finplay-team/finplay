// Holding의 매도 반영 메서드를 검증하는 순수 단위 테스트다.
package com.finplay.api.portfolio.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.domain.User;
import com.finplay.api.market.domain.Instrument;
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

	private static Holding holdingWithQuantityAndPrice(BigDecimal quantity, BigDecimal price) {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		Account account = Account.create(user, Market.STOCK, NOW);
		Instrument instrument = Instrument.create(
			com.finplay.api.market.domain.Market.STOCK, "TEST01", "테스트종목",
			BigDecimal.valueOf(100), 10_000L, true, NOW);
		Holding holding = Holding.create(account, instrument, NOW);
		holding.applyBuy(quantity, price, NOW);
		return holding;
	}
}
