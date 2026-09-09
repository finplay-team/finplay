package com.finplay.api.domain.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.entity.HoldingLot;
import com.finplay.api.domain.portfolio.repository.HoldingLotRepository;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class PortfolioBuyServiceTest {

	private static final LocalDateTime EARLIER = LocalDateTime.of(2026, 7, 28, 9, 0, 0);
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

	private final HoldingRepository holdingRepository = Mockito.mock(HoldingRepository.class);
	private final HoldingLotRepository holdingLotRepository = Mockito.mock(HoldingLotRepository.class);
	private final PortfolioBuyService service = new PortfolioBuyService(holdingRepository, holdingLotRepository);

	@Test
	void applyBuyTradeCreatesNewHoldingWithAveragePriceEqualToTradePriceWhenNoneExists() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Trade trade = testTrade(account, instrument, new BigDecimal("50000"), new BigDecimal("10"));
		when(holdingRepository.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId()))
			.thenReturn(Optional.empty());

		service.applyBuyTrade(
			account, instrument, trade, new BigDecimal("10"), new BigDecimal("50000"), 75L, NOW);

		ArgumentCaptor<Holding> holdingCaptor = ArgumentCaptor.forClass(Holding.class);
		verify(holdingRepository).save(holdingCaptor.capture());
		Holding savedHolding = holdingCaptor.getValue();
		assertThat(savedHolding.getAveragePrice()).isEqualByComparingTo("50000");
		assertThat(savedHolding.getQuantity()).isEqualByComparingTo("10");
		assertThat(savedHolding.isActive()).isTrue();
		assertThat(savedHolding.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	void applyBuyTradeRecalculatesWeightedAveragePriceForExistingHolding() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding existingHolding = Holding.create(account, instrument, EARLIER);
		existingHolding.applyBuy(new BigDecimal("1"), new BigDecimal("1"), EARLIER);
		when(holdingRepository.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId()))
			.thenReturn(Optional.of(existingHolding));
		Trade trade = testTrade(account, instrument, new BigDecimal("2"), new BigDecimal("2"));

		service.applyBuyTrade(account, instrument, trade, new BigDecimal("2"), new BigDecimal("2"), 10L, NOW);

		ArgumentCaptor<Holding> holdingCaptor = ArgumentCaptor.forClass(Holding.class);
		verify(holdingRepository).save(holdingCaptor.capture());
		Holding savedHolding = holdingCaptor.getValue();
		assertThat(savedHolding).isSameAs(existingHolding);
		assertThat(savedHolding.getAveragePrice()).isEqualByComparingTo("1.66666667");
		assertThat(savedHolding.getQuantity()).isEqualByComparingTo("3");
		assertThat(savedHolding.isActive()).isTrue();
		assertThat(savedHolding.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	void applyBuyTradeCreatesLotWithOriginalAndRemainingQuantityEqualToFilledQuantity() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Trade trade = testTrade(account, instrument, new BigDecimal("30000"), new BigDecimal("3.12345678"));
		when(holdingRepository.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId()))
			.thenReturn(Optional.empty());

		service.applyBuyTrade(
			account,
			instrument,
			trade,
			new BigDecimal("3.12345678"),
			new BigDecimal("30000"),
			45L,
			NOW);

		ArgumentCaptor<HoldingLot> lotCaptor = ArgumentCaptor.forClass(HoldingLot.class);
		verify(holdingLotRepository).save(lotCaptor.capture());
		HoldingLot savedLot = lotCaptor.getValue();
		assertThat(savedLot.getOriginalQuantity()).isEqualByComparingTo("3.12345678");
		assertThat(savedLot.getRemainingQuantity()).isEqualByComparingTo("3.12345678");
		assertThat(savedLot.getOriginalQuantity()).isEqualByComparingTo(savedLot.getRemainingQuantity());
		assertThat(savedLot.getUnitCost()).isEqualByComparingTo("30000");
		assertThat(savedLot.getBuyFee()).isEqualTo(45L);
		assertThat(savedLot.getBuyTrade()).isSameAs(trade);
		assertThat(savedLot.getExecutedAt()).isEqualTo(trade.getExecutedAt());
	}

	@Test
	void applyBuyTradeOnExistingHoldingCreatesLotScopedToIncrementalQuantityOnly() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding existingHolding = Holding.create(account, instrument, EARLIER);
		existingHolding.applyBuy(new BigDecimal("5"), new BigDecimal("100"), EARLIER);
		when(holdingRepository.findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId()))
			.thenReturn(Optional.of(existingHolding));
		Trade trade = testTrade(account, instrument, new BigDecimal("200"), new BigDecimal("2"));

		service.applyBuyTrade(account, instrument, trade, new BigDecimal("2"), new BigDecimal("200"), 5L, NOW);

		ArgumentCaptor<HoldingLot> lotCaptor = ArgumentCaptor.forClass(HoldingLot.class);
		verify(holdingLotRepository).save(lotCaptor.capture());
		HoldingLot savedLot = lotCaptor.getValue();
		assertThat(savedLot.getOriginalQuantity()).isEqualByComparingTo("2");
		assertThat(savedLot.getRemainingQuantity()).isEqualByComparingTo("2");
	}

	@Test
	void applyBuyTradeWithLockedHoldingSavesWithoutSeparateLookupForExistingHolding() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding lockedHolding = Holding.create(account, instrument, EARLIER);
		lockedHolding.applyBuy(new BigDecimal("1"), new BigDecimal("1"), EARLIER);
		Trade trade = testTrade(account, instrument, new BigDecimal("2"), new BigDecimal("2"));

		Holding result = service.applyBuyTrade(
			account, instrument, trade, new BigDecimal("2"), new BigDecimal("2"), 10L, NOW, lockedHolding);

		verify(holdingRepository, never()).findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId());
		verify(holdingRepository).save(lockedHolding);
		assertThat(result).isSameAs(lockedHolding);
		assertThat(result.getQuantity()).isEqualByComparingTo("3");
	}

	@Test
	void applyBuyTradeWithLockedHoldingSavesNewlyCreatedHoldingWithoutSeparateLookup() {
		Account account = testAccount();
		Instrument instrument = testInstrument();
		Holding newHolding = Holding.create(account, instrument, NOW);
		Trade trade = testTrade(account, instrument, new BigDecimal("50000"), new BigDecimal("10"));

		Holding result = service.applyBuyTrade(
			account, instrument, trade, new BigDecimal("10"), new BigDecimal("50000"), 75L, NOW, newHolding);

		verify(holdingRepository, never()).findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId());
		verify(holdingRepository).save(newHolding);
		assertThat(result).isSameAs(newHolding);
		assertThat(result.getQuantity()).isEqualByComparingTo("10");
		assertThat(result.getAveragePrice()).isEqualByComparingTo("50000");
	}

	@Test
	void findExistingHoldingsForChunkUpdateDelegatesToRepositoryBulkLockQueryWithoutExtraCall() {
		Account account = testAccount();
		org.springframework.test.util.ReflectionTestUtils.setField(account, "id", 10L);
		Instrument instrument = testInstrument();
		Holding existingHolding = Holding.create(account, instrument, EARLIER);
		List<Long> accountIds = List.of(account.getId(), 999L);
		when(holdingRepository.findByAccountIdInAndInstrumentIdForUpdate(accountIds, instrument.getId()))
			.thenReturn(List.of(existingHolding));

		List<Holding> result = service.findExistingHoldingsForChunkUpdate(accountIds, instrument.getId());

		assertThat(result).containsExactly(existingHolding);
		verify(holdingRepository).findByAccountIdInAndInstrumentIdForUpdate(accountIds, instrument.getId());
		verify(holdingRepository, never()).findByAccountIdAndInstrumentIdForUpdate(account.getId(), instrument.getId());
	}

	private static Account testAccount() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", EARLIER);
		return Account.create(user, Market.STOCK, EARLIER);
	}

	private static Instrument testInstrument() {
		return Instrument.create(
			Market.STOCK,
			"005930",
			"삼성전자",
			new BigDecimal("100"),
			0L,
			true,
			EARLIER);
	}

	private static Trade testTrade(
		Account account, Instrument instrument, BigDecimal price, BigDecimal quantity) {
		Order order = Order.create(
			account.getUser(),
			account,
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			quantity,
			"idem-key",
			"a".repeat(64),
			NOW);
		long amount = price.multiply(quantity).longValue();
		return Trade.of(
			order, account, instrument,
			com.finplay.api.domain.market.entity.StockReplaySession.ready(NOW.toLocalDate(), NOW.toLocalDate(), NOW,
				NOW),
			OrderSide.BUY, price, quantity, amount, 0L, null, NOW, NOW);
	}
}
