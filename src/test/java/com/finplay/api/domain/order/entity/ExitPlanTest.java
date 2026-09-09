package com.finplay.api.domain.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.portfolio.entity.Holding;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExitPlanTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 12, 10, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("1.00000000");
	private static final BigDecimal ENTRY_PRICE = new BigDecimal("100000.00000000");
	private static final BigDecimal STOP_LOSS_PRICE = new BigDecimal("95000.00000000");
	private static final BigDecimal TAKE_PROFIT_PRICE = new BigDecimal("110000.00000000");
	private static final BigDecimal BASELINE_PRICE = new BigDecimal("100500.00000000");
	private static final String REQUEST_HASH = "0".repeat(64);

	private final User user = User.create("exit-plan-domain@finplay.com", "hash", "domain-tester", NOW);
	private final Account account = Account.create(user, Market.CRYPTO, NOW);
	private final Instrument instrument = Instrument.create(Market.CRYPTO, "BTC", "비트코인", new BigDecimal("0.00000001"),
		0L, true, NOW);
	private final Holding holding = holdingWithBuy();

	private Holding holdingWithBuy() {
		Holding created = Holding.create(account, instrument, NOW);
		created.applyBuy(new BigDecimal("10.00000000"), ENTRY_PRICE, NOW);
		return created;
	}

	@Test
	@DisplayName("createEducational은 intentionId·intentionInstanceKey·buyTrade 중 하나라도 null이면 거부한다")
	void createEducationalRejectsWhenAnyOfThreeRequiredFieldsIsNull() {
		assertThatThrownBy(() -> ExitPlan.createEducational(
			user, holding, null, "instance-key", null, instrument, QUANTITY, ENTRY_PRICE, ExitPriceType.PRICE, null,
			null, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, BASELINE_PRICE, NOW, REQUEST_HASH, NOW))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> ExitPlan.createEducational(
			user, holding, 1L, null, null, instrument, QUANTITY, ENTRY_PRICE, ExitPriceType.PRICE, null, null,
			STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, BASELINE_PRICE, NOW, REQUEST_HASH, NOW))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> ExitPlan.createEducational(
			user, holding, 1L, "instance-key", null, instrument, QUANTITY, ENTRY_PRICE, ExitPriceType.PRICE, null,
			null, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, BASELINE_PRICE, NOW, REQUEST_HASH, NOW))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("buyTrade");
	}

	@Test
	@DisplayName("PRICE 방식은 rate snapshot이 하나라도 있으면 두 팩토리 모두 거부한다")
	void bothFactoriesRejectPriceTypeWithAnyRateSnapshot() {
		assertThatThrownBy(() -> ExitPlan.createGeneral(
			user, holding, instrument, QUANTITY, ENTRY_PRICE, ExitPriceType.PRICE, new BigDecimal("0.05"), null,
			STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, BASELINE_PRICE, NOW, REQUEST_HASH, NOW))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> ExitPlan.createGeneral(
			user, holding, instrument, QUANTITY, ENTRY_PRICE, ExitPriceType.PRICE, null, new BigDecimal("0.10"),
			STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, BASELINE_PRICE, NOW, REQUEST_HASH, NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("PERCENT 방식은 rate snapshot이 하나라도 없으면 두 팩토리 모두 거부한다")
	void bothFactoriesRejectPercentTypeWithMissingRateSnapshot() {
		assertThatThrownBy(() -> ExitPlan.createGeneral(
			user, holding, instrument, QUANTITY, ENTRY_PRICE, ExitPriceType.PERCENT, new BigDecimal("0.05"), null,
			STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, BASELINE_PRICE, NOW, REQUEST_HASH, NOW))
			.isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> ExitPlan.createGeneral(
			user, holding, instrument, QUANTITY, ENTRY_PRICE, ExitPriceType.PERCENT, null, null, STOP_LOSS_PRICE,
			TAKE_PROFIT_PRICE, BASELINE_PRICE, NOW, REQUEST_HASH, NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("PRICE 방식은 rate snapshot 없이, PERCENT 방식은 rate snapshot과 함께 정상 생성된다")
	void createsSuccessfullyWhenRateSnapshotMatchesExitPriceType() {
		ExitPlan pricePlan = ExitPlan.createGeneral(
			user, holding, instrument, QUANTITY, ENTRY_PRICE, ExitPriceType.PRICE, null, null, STOP_LOSS_PRICE,
			TAKE_PROFIT_PRICE, BASELINE_PRICE, NOW, REQUEST_HASH, NOW);
		ExitPlan percentPlan = ExitPlan.createGeneral(
			user, holding, instrument, QUANTITY, ENTRY_PRICE, ExitPriceType.PERCENT, new BigDecimal("0.05"),
			new BigDecimal("0.10"), STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, BASELINE_PRICE, NOW, REQUEST_HASH, NOW);

		assertThat(pricePlan.getStopLossRate()).isNull();
		assertThat(pricePlan.getTakeProfitRate()).isNull();
		assertThat(percentPlan.getStopLossRate()).isEqualByComparingTo("0.05");
		assertThat(percentPlan.getTakeProfitRate()).isEqualByComparingTo("0.10");
	}

	@Test
	@DisplayName("createEducational도 rate snapshot 불변식을 그대로 적용한다")
	void createEducationalAlsoAppliesRateSnapshotInvariant() {
		assertThatThrownBy(() -> ExitPlan.createEducational(
			user, holding, 1L, "instance-key", buyTradeStub(), instrument, QUANTITY, ENTRY_PRICE,
			ExitPriceType.PRICE, new BigDecimal("0.05"), null, STOP_LOSS_PRICE, TAKE_PROFIT_PRICE, BASELINE_PRICE,
			NOW, REQUEST_HASH, NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private Trade buyTradeStub() {
		Order order = Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET, QUANTITY, "idem-key", REQUEST_HASH, NOW);
		return Trade.of(
			order, account, instrument, null, OrderSide.BUY, ENTRY_PRICE, QUANTITY, 100_000L, 0L, null, NOW, NOW);
	}
}
