package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.response.OrderListItemResponse;
import com.finplay.api.domain.order.service.OrderService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeAttemptOrderQueryServiceTest {

	private static final Long USER_ID = 7L;
	private static final Long ATTEMPT_ID = 11L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 12, 0);

	private final PracticeAttemptRepository practiceAttemptRepository = mock(PracticeAttemptRepository.class);
	private final OrderService orderService = mock(OrderService.class);

	private final PracticeAttemptOrderQueryService service = new PracticeAttemptOrderQueryService(
		practiceAttemptRepository, orderService);

	@Test
	void getCurrentRunOrdersDelegatesToOrderServiceWithAttemptIdAndRunNumberWhenAttemptExists() {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW);
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		ReflectionTestUtils.setField(attempt, "runNumber", 3L);
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.CRYPTO)).thenReturn(Optional.of(attempt));
		OrderListItemResponse orderResponse = new OrderListItemResponse(
			100L, "CRYPTO", 42L, "BUY", "LIMIT", "PENDING",
			new BigDecimal("1"), new BigDecimal("70000000"), NOW, ATTEMPT_ID, 3L);
		when(orderService.getPracticeRunOrders(ATTEMPT_ID, 3L)).thenReturn(List.of(orderResponse));

		List<OrderListItemResponse> result = service.getCurrentRunOrders(USER_ID, Market.CRYPTO);

		assertThat(result).containsExactly(orderResponse);
	}

	@Test
	void getCurrentRunOrdersReturnsEmptyListWhenAttemptDoesNotExist() {
		when(practiceAttemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK)).thenReturn(Optional.empty());

		List<OrderListItemResponse> result = service.getCurrentRunOrders(USER_ID, Market.STOCK);

		assertThat(result).isEmpty();
		verifyNoInteractions(orderService);
	}
}
