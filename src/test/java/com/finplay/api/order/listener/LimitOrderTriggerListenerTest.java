// LimitOrderTriggerListener의 종목 조회 관용 처리·후보 순회·건별 예외 격리를 검증하는 단위 테스트다.
package com.finplay.api.order.listener;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.event.CryptoPriceUpdatedEvent;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.service.LimitOrderFillService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class LimitOrderTriggerListenerTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 5, 10, 0, 0);

	private final InstrumentService instrumentService = mock(InstrumentService.class);
	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final LimitOrderFillService limitOrderFillService = mock(LimitOrderFillService.class);

	private final LimitOrderTriggerListener listener = new LimitOrderTriggerListener(
		instrumentService, orderRepository, limitOrderFillService);

	@Test
	void onPriceUpdatedFillsEachCandidateInOrderWhenInstrumentFound() {
		Instrument instrument = cryptoInstrument(1L);
		when(instrumentService.findEntityByMarketAndSymbol(Market.CRYPTO, "BTC")).thenReturn(Optional.of(instrument));
		Order first = candidateOrder(10L);
		Order second = candidateOrder(20L);
		BigDecimal price = new BigDecimal("70000000");
		when(orderRepository.findPendingLimitOrdersToFill(1L, price)).thenReturn(List.of(first, second));

		listener.onPriceUpdated(new CryptoPriceUpdatedEvent("BTC", price, NOW, NOW));

		InOrder order = inOrder(limitOrderFillService);
		order.verify(limitOrderFillService).fillIfPending(10L);
		order.verify(limitOrderFillService).fillIfPending(20L);
	}

	@Test
	void onPriceUpdatedIgnoresEventWithoutFillingAnythingWhenInstrumentNotFound() {
		when(instrumentService.findEntityByMarketAndSymbol(Market.CRYPTO, "UNKNOWN")).thenReturn(Optional.empty());

		assertThatCode(() -> listener.onPriceUpdated(
			new CryptoPriceUpdatedEvent("UNKNOWN", new BigDecimal("100"), NOW, NOW)))
			.doesNotThrowAnyException();

		verifyNoInteractions(orderRepository, limitOrderFillService);
	}

	@Test
	void onPriceUpdatedContinuesProcessingRemainingCandidatesWhenOneThrows() {
		Instrument instrument = cryptoInstrument(1L);
		when(instrumentService.findEntityByMarketAndSymbol(Market.CRYPTO, "BTC")).thenReturn(Optional.of(instrument));
		Order failing = candidateOrder(10L);
		Order succeeding = candidateOrder(20L);
		BigDecimal price = new BigDecimal("70000000");
		when(orderRepository.findPendingLimitOrdersToFill(1L, price)).thenReturn(List.of(failing, succeeding));
		doThrow(new IllegalStateException("체결 실패")).when(limitOrderFillService).fillIfPending(10L);

		assertThatCode(() -> listener.onPriceUpdated(new CryptoPriceUpdatedEvent("BTC", price, NOW, NOW)))
			.doesNotThrowAnyException();

		verify(limitOrderFillService).fillIfPending(10L);
		verify(limitOrderFillService).fillIfPending(20L);
	}

	@Test
	void onPriceUpdatedDoesNotPropagateExceptionWhenInstrumentLookupThrows() {
		when(instrumentService.findEntityByMarketAndSymbol(any(), any()))
			.thenThrow(new RuntimeException("조회 실패"));

		assertThatCode(() -> listener.onPriceUpdated(
			new CryptoPriceUpdatedEvent("BTC", new BigDecimal("100"), NOW, NOW)))
			.doesNotThrowAnyException();

		verifyNoInteractions(orderRepository, limitOrderFillService);
	}

	private static Instrument cryptoInstrument(Long id) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", id);
		return instrument;
	}

	private static Order candidateOrder(Long id) {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		Account account = Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, NOW);
		Instrument instrument = cryptoInstrument(1L);
		Order order = Order.createLimitPending(
			user, account, instrument, OrderSide.BUY, new BigDecimal("0.1"), new BigDecimal("70000000"),
			"idem-candidate-" + id, "a".repeat(64), NOW);
		ReflectionTestUtils.setField(order, "id", id);
		return order;
	}
}
