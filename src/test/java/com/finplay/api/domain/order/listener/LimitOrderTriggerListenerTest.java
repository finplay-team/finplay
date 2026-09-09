package com.finplay.api.domain.order.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.event.CryptoPriceUpdatedEvent;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.order.config.LimitOrderFillExecutorProperties;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderFillExecutorRouter;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class LimitOrderTriggerListenerTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0, 0);

	private final InstrumentService instrumentService = mock(InstrumentService.class);
	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final LimitOrderFillService limitOrderFillService = mock(LimitOrderFillService.class);
	private final LimitOrderFillExecutorRouter limitOrderFillExecutorRouter = mock(LimitOrderFillExecutorRouter.class);

	private final LimitOrderTriggerListener syncFallbackListener = new LimitOrderTriggerListener(
		instrumentService, orderRepository, limitOrderFillService, limitOrderFillExecutorRouter,
		new LimitOrderFillExecutorProperties(false, 8, 200, 50));

	private final LimitOrderTriggerListener asyncListener = new LimitOrderTriggerListener(
		instrumentService, orderRepository, limitOrderFillService, limitOrderFillExecutorRouter,
		new LimitOrderFillExecutorProperties(true, 8, 200, 50));

	private final LimitOrderTriggerListener singleOrderBatchListener = new LimitOrderTriggerListener(
		instrumentService, orderRepository, limitOrderFillService, limitOrderFillExecutorRouter,
		new LimitOrderFillExecutorProperties(true, 8, 200, 1));

	@Test
	void onPriceUpdatedFillsEachCandidateInOrderOnThisThreadWhenExecutorDisabled() {
		Instrument instrument = cryptoInstrument(1L);
		when(instrumentService.findEntityByMarketAndSymbol(Market.CRYPTO, "BTC")).thenReturn(Optional.of(instrument));
		Order first = candidateOrder(10L);
		Order second = candidateOrder(20L);
		BigDecimal price = new BigDecimal("70000000");
		when(orderRepository.findPendingLimitOrdersToFill(1L, price)).thenReturn(List.of(first, second));

		syncFallbackListener.onPriceUpdated(new CryptoPriceUpdatedEvent("BTC", price, NOW, NOW));

		verifyNoInteractions(limitOrderFillExecutorRouter);
		InOrder order = inOrder(limitOrderFillService);
		order.verify(limitOrderFillService).fillIfPending(10L);
		order.verify(limitOrderFillService).fillIfPending(20L);
	}

	@Test
	void onPriceUpdatedSubmitsAllCandidatesAsOneBatchToExecutorRouterWithInstrumentIdWhenExecutorEnabled() {
		Instrument instrument = cryptoInstrument(1L);
		when(instrumentService.findEntityByMarketAndSymbol(Market.CRYPTO, "BTC")).thenReturn(Optional.of(instrument));
		Order first = candidateOrder(10L);
		Order second = candidateOrder(20L);
		BigDecimal price = new BigDecimal("70000000");
		when(orderRepository.findPendingLimitOrdersToFill(1L, price)).thenReturn(List.of(first, second));

		asyncListener.onPriceUpdated(new CryptoPriceUpdatedEvent("BTC", price, NOW, NOW));

		verifyNoInteractions(limitOrderFillService);
		ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
		verify(limitOrderFillExecutorRouter, times(1)).submit(eq(1L), taskCaptor.capture());

		taskCaptor.getValue().run();
		verify(limitOrderFillService).fillBatch(List.of(10L, 20L));
	}

	@Test
	void onPriceUpdatedSubmitsOneChunkPerCandidateWhenBatchSizeIsOne() {
		Instrument instrument = cryptoInstrument(1L);
		when(instrumentService.findEntityByMarketAndSymbol(Market.CRYPTO, "BTC")).thenReturn(Optional.of(instrument));
		Order first = candidateOrder(10L);
		Order second = candidateOrder(20L);
		BigDecimal price = new BigDecimal("70000000");
		when(orderRepository.findPendingLimitOrdersToFill(1L, price)).thenReturn(List.of(first, second));

		singleOrderBatchListener.onPriceUpdated(new CryptoPriceUpdatedEvent("BTC", price, NOW, NOW));

		ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
		verify(limitOrderFillExecutorRouter, times(2)).submit(eq(1L), taskCaptor.capture());

		List<Runnable> submittedTasks = taskCaptor.getAllValues();
		assertThat(submittedTasks).hasSize(2);
		submittedTasks.get(0).run();
		submittedTasks.get(1).run();
		InOrder order = inOrder(limitOrderFillService);
		order.verify(limitOrderFillService).fillBatch(List.of(10L));
		order.verify(limitOrderFillService).fillBatch(List.of(20L));
	}

	@Test
	void submittedTaskSwallowsExceptionFromFillBatchWhenExecutorEnabled() {
		Instrument instrument = cryptoInstrument(1L);
		when(instrumentService.findEntityByMarketAndSymbol(Market.CRYPTO, "BTC")).thenReturn(Optional.of(instrument));
		Order failing = candidateOrder(10L);
		BigDecimal price = new BigDecimal("70000000");
		when(orderRepository.findPendingLimitOrdersToFill(1L, price)).thenReturn(List.of(failing));
		doThrow(new IllegalStateException("체결 실패")).when(limitOrderFillService).fillBatch(List.of(10L));

		asyncListener.onPriceUpdated(new CryptoPriceUpdatedEvent("BTC", price, NOW, NOW));

		ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
		verify(limitOrderFillExecutorRouter).submit(eq(1L), taskCaptor.capture());
		assertThatCode(() -> taskCaptor.getValue().run()).doesNotThrowAnyException();
		verify(limitOrderFillService).fillBatch(List.of(10L));
	}

	@Test
	void onPriceUpdatedIgnoresEventWithoutFillingAnythingWhenInstrumentNotFound() {
		when(instrumentService.findEntityByMarketAndSymbol(Market.CRYPTO, "UNKNOWN")).thenReturn(Optional.empty());

		assertThatCode(() -> asyncListener.onPriceUpdated(
			new CryptoPriceUpdatedEvent("UNKNOWN", new BigDecimal("100"), NOW, NOW)))
			.doesNotThrowAnyException();

		verifyNoInteractions(orderRepository, limitOrderFillService, limitOrderFillExecutorRouter);
	}

	@Test
	void onPriceUpdatedContinuesProcessingRemainingCandidatesOnThisThreadWhenOneThrowsAndExecutorDisabled() {
		Instrument instrument = cryptoInstrument(1L);
		when(instrumentService.findEntityByMarketAndSymbol(Market.CRYPTO, "BTC")).thenReturn(Optional.of(instrument));
		Order failing = candidateOrder(10L);
		Order succeeding = candidateOrder(20L);
		BigDecimal price = new BigDecimal("70000000");
		when(orderRepository.findPendingLimitOrdersToFill(1L, price)).thenReturn(List.of(failing, succeeding));
		doThrow(new IllegalStateException("체결 실패")).when(limitOrderFillService).fillIfPending(10L);

		assertThatCode(() -> syncFallbackListener.onPriceUpdated(new CryptoPriceUpdatedEvent("BTC", price, NOW, NOW)))
			.doesNotThrowAnyException();

		verify(limitOrderFillService).fillIfPending(10L);
		verify(limitOrderFillService).fillIfPending(20L);
	}

	@Test
	void onPriceUpdatedDoesNotPropagateExceptionWhenInstrumentLookupThrows() {
		when(instrumentService.findEntityByMarketAndSymbol(any(), any()))
			.thenThrow(new RuntimeException("조회 실패"));

		assertThatCode(() -> asyncListener.onPriceUpdated(
			new CryptoPriceUpdatedEvent("BTC", new BigDecimal("100"), NOW, NOW)))
			.doesNotThrowAnyException();

		verifyNoInteractions(orderRepository, limitOrderFillService, limitOrderFillExecutorRouter);
	}

	private static Instrument cryptoInstrument(Long id) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", id);
		return instrument;
	}

	private static Order candidateOrder(Long id) {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		Account account = Account.create(user, Market.CRYPTO, NOW);
		Instrument instrument = cryptoInstrument(1L);
		Order order = Order.createLimitPending(
			user, account, instrument, OrderSide.BUY, new BigDecimal("0.1"), new BigDecimal("70000000"),
			"idem-candidate-" + id, "a".repeat(64), NOW);
		ReflectionTestUtils.setField(order, "id", id);
		return order;
	}
}
