package com.finplay.api.domain.education.marketpractice.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.education.marketpractice.service.PracticeAttemptOrderQueryService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.response.OrderListItemResponse;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PracticeAttemptOrderController.class)
@Import(SecurityConfig.class)
class PracticeAttemptOrderControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 12, 0);

	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private PracticeAttemptOrderQueryService orderQueryService;
	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void getOrdersRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(get("/api/education/practice/attempts/CRYPTO/orders"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(orderQueryService);
	}

	@Test
	void getOrdersReturnsCurrentRunOrderListJson() throws Exception {
		authenticate();
		OrderListItemResponse order = new OrderListItemResponse(
			100L, "CRYPTO", 42L, "BUY", "LIMIT", "PENDING",
			new BigDecimal("1"), new BigDecimal("70000000"), NOW, 11L, 3L);
		when(orderQueryService.getCurrentRunOrders(USER_ID, Market.CRYPTO)).thenReturn(List.of(order));

		mockMvc.perform(get("/api/education/practice/attempts/CRYPTO/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].orderId").value(100))
			.andExpect(jsonPath("$[0].market").value("CRYPTO"))
			.andExpect(jsonPath("$[0].instrumentId").value(42))
			.andExpect(jsonPath("$[0].side").value("BUY"))
			.andExpect(jsonPath("$[0].orderType").value("LIMIT"))
			.andExpect(jsonPath("$[0].status").value("PENDING"))
			.andExpect(jsonPath("$[0].quantity").value(1))
			.andExpect(jsonPath("$[0].limitPrice").value(70000000))
			.andExpect(jsonPath("$[0].practiceAttemptId").value(11))
			.andExpect(jsonPath("$[0].practiceAttemptRunNumber").value(3));
		verify(orderQueryService).getCurrentRunOrders(USER_ID, Market.CRYPTO);
	}

	@Test
	void getOrdersReturnsEmptyArrayJsonWhenNoAttemptExists() throws Exception {
		authenticate();
		when(orderQueryService.getCurrentRunOrders(USER_ID, Market.STOCK)).thenReturn(List.of());

		mockMvc.perform(get("/api/education/practice/attempts/STOCK/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void getOrdersRejectsInvalidMarketPath() throws Exception {
		authenticate();
		mockMvc.perform(get("/api/education/practice/attempts/FOREX/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(orderQueryService);
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}
}
