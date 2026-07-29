// 시장가 매수 주문 생성 API의 인증, 검증, 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.service.OrderService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;
	private static final String IDEMPOTENCY_KEY = "11111111-1111-1111-1111-111111111111";
	private static final String VALID_BODY = """
		{"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET","quantity":"10"}
		""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrderService orderService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void createOrderReturnsCreatedWithEveryResponseField() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		LocalDateTime now = LocalDateTime.of(2026, 7, 29, 9, 0);
		OrderResponse response = new OrderResponse(
			1L, "STOCK", 1L, "BUY", "MARKET", "FILLED", new BigDecimal("10"), now,
			1L, new BigDecimal("70000"), 700_000L, 105L, now);
		when(orderService.createOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(OrderCreateRequest.class)))
			.thenReturn(response);

		mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.orderId").value(1))
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.instrumentId").value(1))
			.andExpect(jsonPath("$.side").value("BUY"))
			.andExpect(jsonPath("$.orderType").value("MARKET"))
			.andExpect(jsonPath("$.status").value("FILLED"))
			.andExpect(jsonPath("$.quantity").value(10))
			.andExpect(jsonPath("$.requestedAt").value("2026-07-29T09:00:00"))
			.andExpect(jsonPath("$.tradeId").value(1))
			.andExpect(jsonPath("$.price").value(70000))
			.andExpect(jsonPath("$.amount").value(700000))
			.andExpect(jsonPath("$.fee").value(105))
			.andExpect(jsonPath("$.executedAt").value("2026-07-29T09:00:00"));

		verify(orderService).createOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(OrderCreateRequest.class));
	}

	@Test
	void createOrderRejectsMissingIdempotencyKeyWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void createOrderRejectsBlankIdempotencyKeyWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", "   ")
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void createOrderRejectsIdempotencyKeyOverMaxLengthWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", "a".repeat(101))
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void createOrderRejectsMissingQuantityWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void createOrderRejectsNonNumericQuantityWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"MARKET","quantity":"abc"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void createOrderRejectsInvalidMarketLiteralWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"market":"FOREX","instrumentId":1,"side":"BUY","orderType":"MARKET","quantity":"10"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void createOrderRejectsInvalidSideLiteralWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));

		mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"market":"STOCK","instrumentId":1,"side":"HOLD","orderType":"MARKET","quantity":"10"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void createOrderReturnsUnsupportedOrderTypeWhenServiceRejectsOrderType() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(orderService.createOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(OrderCreateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.UNSUPPORTED_ORDER_TYPE));

		mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"market":"STOCK","instrumentId":1,"side":"BUY","orderType":"LIMIT","quantity":"10"}
				"""))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(jsonPath("$.error.code").value("UNSUPPORTED_ORDER_TYPE"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(orderService).createOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(OrderCreateRequest.class));
	}

	@Test
	void createOrderReturnsInsufficientCashWhenServiceRejectsCashShortage() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		when(orderService.createOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(OrderCreateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_CASH));

		mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_CASH"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(orderService).createOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(OrderCreateRequest.class));
	}

	@Test
	void createOrderRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/orders")
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_BODY))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void getMyOrdersReturnsOkWithEveryFieldAndNoTradeOnlyField() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		LocalDateTime requestedAt = LocalDateTime.of(2026, 7, 29, 9, 0);
		OrderListItemResponse item = new OrderListItemResponse(
			1L, "STOCK", 1L, "BUY", "MARKET", "FILLED", new BigDecimal("10"), requestedAt);
		when(orderService.getMyOrders(USER_ID)).thenReturn(List.of(item));

		mockMvc.perform(get("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].orderId").value(1))
			.andExpect(jsonPath("$[0].market").value("STOCK"))
			.andExpect(jsonPath("$[0].instrumentId").value(1))
			.andExpect(jsonPath("$[0].side").value("BUY"))
			.andExpect(jsonPath("$[0].orderType").value("MARKET"))
			.andExpect(jsonPath("$[0].status").value("FILLED"))
			.andExpect(jsonPath("$[0].quantity").value(10))
			.andExpect(jsonPath("$[0].requestedAt").value("2026-07-29T09:00:00"))
			.andExpect(jsonPath("$[0].tradeId").doesNotExist())
			.andExpect(jsonPath("$[0].price").doesNotExist())
			.andExpect(jsonPath("$[0].amount").doesNotExist())
			.andExpect(jsonPath("$[0].fee").doesNotExist())
			.andExpect(jsonPath("$[0].executedAt").doesNotExist());

		verify(orderService).getMyOrders(USER_ID);
	}

	@Test
	void getMyOrdersRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/orders"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}
}
