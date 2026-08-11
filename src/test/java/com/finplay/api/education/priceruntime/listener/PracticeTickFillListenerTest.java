// PracticeTickFillListener가 tick 이벤트를 동기 처리로 위임하고 예외를 삼키지 않음을 검증하는 단위 테스트다.
package com.finplay.api.education.priceruntime.listener;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.finplay.api.education.priceruntime.event.PracticePriceTickAdvancedEvent;
import com.finplay.api.order.service.PracticeOrderSettlementService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PracticeTickFillListenerTest {

	private final PracticeOrderSettlementService practiceOrderSettlementService = mock(
		PracticeOrderSettlementService.class);

	private final PracticeTickFillListener listener = new PracticeTickFillListener(practiceOrderSettlementService);

	@Test
	void onTickAdvancedDelegatesSessionIdPriceAndLastTickFlagToSettlementServiceSynchronously() {
		PracticePriceTickAdvancedEvent event = new PracticePriceTickAdvancedEvent(
			100L, 1L, 10L, 42, new BigDecimal("10500.00000000"), false);

		listener.onTickAdvanced(event);

		verify(practiceOrderSettlementService)
			.settleOnTick(100L, new BigDecimal("10500.00000000"), false);
	}

	@Test
	void onTickAdvancedPassesLastTickFlagThroughOnFinalTick() {
		PracticePriceTickAdvancedEvent event = new PracticePriceTickAdvancedEvent(
			100L, 1L, 10L, 99, new BigDecimal("10200.00000000"), true);

		listener.onTickAdvanced(event);

		verify(practiceOrderSettlementService)
			.settleOnTick(100L, new BigDecimal("10200.00000000"), true);
	}

	@Test
	void onTickAdvancedDoesNotSwallowSettlementServiceExceptionSoTickTransactionRollsBack() {
		PracticePriceTickAdvancedEvent event = new PracticePriceTickAdvancedEvent(
			100L, 1L, 10L, 42, new BigDecimal("10500.00000000"), false);
		IllegalStateException failure = new IllegalStateException("체결 대상 주문을 찾을 수 없습니다.");
		doThrow(failure).when(practiceOrderSettlementService)
			.settleOnTick(100L, new BigDecimal("10500.00000000"), false);

		assertThatThrownBy(() -> listener.onTickAdvanced(event)).isSameAs(failure);
	}
}
