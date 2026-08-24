// LimitOrderTriggerListener의 종목 조회 관용 처리·후보 순회·실행기 위임(ADR-0024)·건별 예외 격리를 검증하는 단위 테스트다.
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

	// order.limit-fill-executor.enabled=false(폴백) 경로를 검증하는 리스너 — 실행기를 아예 거치지 않고 이
	// 스레드에서 그대로 순차 동기 처리해야 한다(ADR-0024, 킬 스위치).
	private final LimitOrderTriggerListener syncFallbackListener = new LimitOrderTriggerListener(
		instrumentService, orderRepository, limitOrderFillService, limitOrderFillExecutorRouter,
		new LimitOrderFillExecutorProperties(false, 8, 200, 50));

	// enabled=true(기본값) 경로를 검증하는 리스너 — batchSize(50)가 후보 건수(2건)보다 커서 청크 하나로
	// 묶인다. 후보를 라우터에 위임만 하고 이 스레드에서 fillBatch를 직접 부르지 않아야 한다.
	private final LimitOrderTriggerListener asyncListener = new LimitOrderTriggerListener(
		instrumentService, orderRepository, limitOrderFillService, limitOrderFillExecutorRouter,
		new LimitOrderFillExecutorProperties(true, 8, 200, 50));

	// ADR-0025 청크 분할 자체를 검증하는 리스너 — batchSize=1로 후보 1건마다 청크(=submit 1건)가 갈리게 만든다.
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

		// 실행기를 아예 거치지 않는다 — 킬 스위치가 폴백(기존 동기 순차) 동작을 그대로 재현함을 못박는다.
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

		// 실행기에 위임만 하고 이 스레드(피드 스레드)에서 직접 체결을 부르지 않는다 — 이번 개선의 핵심.
		verifyNoInteractions(limitOrderFillService);
		// batchSize(50)가 후보 건수(2건)보다 커서 청크 하나 = submit 1건으로 묶인다(ADR-0025).
		ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
		verify(limitOrderFillExecutorRouter, times(1)).submit(eq(1L), taskCaptor.capture());

		// 실제 파티션 스레드 대신 캡처한 작업을 이 자리에서 실행해 보면(라우터 자체는 목이므로 진짜 스레드가
		// 개입하지 않는다) 청크 안 orderId 순서 그대로 fillBatch에 위임됨을 확인할 수 있다.
		taskCaptor.getValue().run();
		verify(limitOrderFillService).fillBatch(List.of(10L, 20L));
	}

	@Test
	void onPriceUpdatedSubmitsOneChunkPerCandidateWhenBatchSizeIsOne() {
		// ADR-0025 청크 분할 자체를 검증한다 — batchSize=1이면 후보 2건이 각자 별도 청크(=submit 별도 호출)로
		// 나뉘어야 한다.
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
		// 파티션 전용 스레드에서 fillBatch가 던진 예외가 그 스레드 밖으로 전파되면 안 된다(ADR-0024 §결정 4를
		// 청크 단위로 그대로 유지, ADR-0025 §결정 3) — 캡처한 작업을 직접 실행해 예외가 삼켜지는지 확인한다.
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
