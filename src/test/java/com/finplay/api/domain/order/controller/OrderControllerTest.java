package com.finplay.api.domain.order.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.request.LimitOrderUpdateRequest;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.dto.response.OrderListItemResponse;
import com.finplay.api.domain.order.dto.response.OrderListResponse;
import com.finplay.api.domain.order.dto.response.OrderResponse;
import com.finplay.api.domain.order.service.LimitOrderCancelService;
import com.finplay.api.domain.order.service.LimitOrderModifyService;
import com.finplay.api.domain.order.service.LimitOrderService;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
	private static final String VALID_LIMIT_BODY = """
		{"market":"CRYPTO","instrumentId":1,"side":"BUY","quantity":"1","limitPrice":"70000000"}
		""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrderService orderService;

	@MockitoBean
	private LimitOrderService limitOrderService;

	@MockitoBean
	private LimitOrderCancelService limitOrderCancelService;

	@MockitoBean
	private LimitOrderModifyService limitOrderModifyService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void createOrderReturnsCreatedWithEveryResponseField() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		LocalDateTime now = LocalDateTime.of(2026, 7, 29, 9, 0);
		OrderResponse response = new OrderResponse(
			1L, "STOCK", 1L, "BUY", "MARKET", "FILLED", new BigDecimal("10"), now,
			1L, new BigDecimal("70000"), 700_000L, 105L, null, now);
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
			.andExpect(jsonPath("$.realizedPnl").doesNotExist())
			.andExpect(jsonPath("$.executedAt").value("2026-07-29T09:00:00"));

		verify(orderService).createOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(OrderCreateRequest.class));
	}

	@Test
	void createOrderReturnsCreatedWithRealizedPnlWhenSideIsSell() throws Exception {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));
		LocalDateTime now = LocalDateTime.of(2026, 7, 29, 9, 0);
		OrderResponse response = new OrderResponse(
			2L, "STOCK", 1L, "SELL", "MARKET", "FILLED", new BigDecimal("10"), now,
			2L, new BigDecimal("70000"), 700_000L, 105L, 15_000L, now);
		when(orderService.createOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(OrderCreateRequest.class)))
			.thenReturn(response);

		mockMvc.perform(post("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"market":"STOCK","instrumentId":1,"side":"SELL","orderType":"MARKET","quantity":"10"}
				"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.orderId").value(2))
			.andExpect(jsonPath("$.side").value("SELL"))
			.andExpect(jsonPath("$.status").value("FILLED"))
			.andExpect(jsonPath("$.tradeId").value(2))
			.andExpect(jsonPath("$.amount").value(700000))
			.andExpect(jsonPath("$.fee").value(105))
			.andExpect(jsonPath("$.realizedPnl").value(15000));

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

	private void stubAuthenticatedUser() {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(java.util.Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	@Test
	void createLimitOrderReturnsCreatedWithEveryResponseField() throws Exception {
		stubAuthenticatedUser();
		LocalDateTime now = LocalDateTime.of(2026, 8, 5, 9, 0);
		LimitOrderResponse response = new LimitOrderResponse(
			1L, "CRYPTO", 1L, "BUY", "LIMIT", "PENDING", new BigDecimal("1"), new BigDecimal("70000000"), now);
		when(limitOrderService.createLimitOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(LimitOrderCreateRequest.class)))
			.thenReturn(response);

		mockMvc.perform(post("/api/orders/limit")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_LIMIT_BODY))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.orderId").value(1))
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.instrumentId").value(1))
			.andExpect(jsonPath("$.side").value("BUY"))
			.andExpect(jsonPath("$.orderType").value("LIMIT"))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.quantity").value(1))
			.andExpect(jsonPath("$.limitPrice").value(70000000))
			.andExpect(jsonPath("$.requestedAt").value("2026-08-05T09:00:00"));

		verify(limitOrderService).createLimitOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY),
			any(LimitOrderCreateRequest.class));
	}

	@Test
	void createLimitOrderRejectsMissingIdempotencyKeyWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/orders/limit")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_LIMIT_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(limitOrderService);
	}

	@Test
	void createLimitOrderRejectsMissingLimitPriceWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/orders/limit")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"market":"CRYPTO","instrumentId":1,"side":"BUY","quantity":"1"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(limitOrderService);
	}

	@Test
	void createLimitOrderRejectsMissingQuantityWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/orders/limit")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"market":"CRYPTO","instrumentId":1,"side":"BUY","limitPrice":"70000000"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(limitOrderService);
	}

	@Test
	void createLimitOrderRejectsInvalidMarketLiteralWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/orders/limit")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"market":"FOREX","instrumentId":1,"side":"BUY","quantity":"1","limitPrice":"70000000"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(limitOrderService);
	}

	@Test
	void createLimitOrderReturnsValidationErrorWhenServiceRejectsStockMarket() throws Exception {
		stubAuthenticatedUser();
		when(limitOrderService.createLimitOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(LimitOrderCreateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 종목만 지정가 주문을 지원합니다."));

		mockMvc.perform(post("/api/orders/limit")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_LIMIT_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderService).createLimitOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY),
			any(LimitOrderCreateRequest.class));
	}

	@Test
	void createLimitOrderReturnsInsufficientCashWhenServiceRejectsCashShortage() throws Exception {
		stubAuthenticatedUser();
		when(limitOrderService.createLimitOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(LimitOrderCreateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_CASH));

		mockMvc.perform(post("/api/orders/limit")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_LIMIT_BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_CASH"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderService).createLimitOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY),
			any(LimitOrderCreateRequest.class));
	}

	@Test
	void createLimitOrderReturnsInsufficientQtyWhenServiceRejectsQuantityShortage() throws Exception {
		stubAuthenticatedUser();
		when(limitOrderService.createLimitOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(LimitOrderCreateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_QTY));

		mockMvc.perform(post("/api/orders/limit")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"market":"CRYPTO","instrumentId":1,"side":"SELL","quantity":"1","limitPrice":"70000000"}
				"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_QTY"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderService).createLimitOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY),
			any(LimitOrderCreateRequest.class));
	}

	@Test
	void createLimitOrderReturnsIdempotencyConflictWhenServiceRejectsConflictingReplay() throws Exception {
		stubAuthenticatedUser();
		when(limitOrderService.createLimitOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(LimitOrderCreateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT));

		mockMvc.perform(post("/api/orders/limit")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_LIMIT_BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderService).createLimitOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY),
			any(LimitOrderCreateRequest.class));
	}

	@Test
	void createLimitOrderReturnsStageLockedWhenServiceRejectsSkippedStage() throws Exception {
		stubAuthenticatedUser();
		when(limitOrderService.createLimitOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(LimitOrderCreateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_STAGE_LOCKED));

		mockMvc.perform(post("/api/orders/limit")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_LIMIT_BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRACTICE_STAGE_LOCKED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderService).createLimitOrder(eq(USER_ID), eq(IDEMPOTENCY_KEY),
			any(LimitOrderCreateRequest.class));
	}

	@Test
	void createLimitOrderRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/orders/limit")
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_LIMIT_BODY))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(limitOrderService);
	}

	@Test
	void getMyOrdersReturnsOkWithEveryFieldWhenMarketIsStock() throws Exception {
		stubAuthenticatedUser();
		LocalDateTime requestedAt = LocalDateTime.of(2026, 7, 29, 9, 0);
		OrderListItemResponse item = new OrderListItemResponse(
			1L, "STOCK", 1L, "BUY", "MARKET", "FILLED", new BigDecimal("10"), null, requestedAt, null, null);
		OrderListResponse response = OrderListResponse.of(List.of(item), "2026-07-29T09:00:00_1", true);
		when(orderService.getMyOrders(USER_ID, Market.STOCK, null, 20)).thenReturn(response);

		mockMvc.perform(get("/api/orders")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].orderId").value(1))
			.andExpect(jsonPath("$.content[0].market").value("STOCK"))
			.andExpect(jsonPath("$.content[0].instrumentId").value(1))
			.andExpect(jsonPath("$.content[0].side").value("BUY"))
			.andExpect(jsonPath("$.content[0].orderType").value("MARKET"))
			.andExpect(jsonPath("$.content[0].status").value("FILLED"))
			.andExpect(jsonPath("$.content[0].quantity").value(10))
			.andExpect(jsonPath("$.content[0].limitPrice").value(nullValue()))
			.andExpect(jsonPath("$.content[0].requestedAt").value("2026-07-29T09:00:00"))
			.andExpect(jsonPath("$.content[0].practiceAttemptId").value(nullValue()))
			.andExpect(jsonPath("$.content[0].practiceAttemptRunNumber").value(nullValue()))
			.andExpect(jsonPath("$.nextCursor").value("2026-07-29T09:00:00_1"))
			.andExpect(jsonPath("$.hasNext").value(true));

		verify(orderService).getMyOrders(USER_ID, Market.STOCK, null, 20);
	}

	@Test
	void getMyOrdersReturnsOkWithEmptyContentWhenMarketIsCrypto() throws Exception {
		stubAuthenticatedUser();
		OrderListResponse response = OrderListResponse.of(List.of(), null, false);
		when(orderService.getMyOrders(USER_ID, Market.CRYPTO, null, 20)).thenReturn(response);

		mockMvc.perform(get("/api/orders")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isEmpty())
			.andExpect(jsonPath("$.nextCursor").doesNotExist())
			.andExpect(jsonPath("$.hasNext").value(false));

		verify(orderService).getMyOrders(USER_ID, Market.CRYPTO, null, 20);
	}

	@Test
	void getMyOrdersUsesDefaultLimitWhenLimitIsOmitted() throws Exception {
		stubAuthenticatedUser();
		when(orderService.getMyOrders(eq(USER_ID), eq(Market.STOCK), isNull(), eq(20)))
			.thenReturn(OrderListResponse.of(List.of(), null, false));

		mockMvc.perform(get("/api/orders")
			.param("market", "STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(orderService).getMyOrders(USER_ID, Market.STOCK, null, 20);
	}

	@Test
	void getMyOrdersPassesCursorAndLimitToService() throws Exception {
		stubAuthenticatedUser();
		when(orderService.getMyOrders(eq(USER_ID), eq(Market.STOCK), any(), eq(10)))
			.thenReturn(OrderListResponse.of(List.of(), null, false));

		mockMvc.perform(get("/api/orders")
			.param("market", "STOCK")
			.param("cursor", "2026-07-29T09:00:00_1")
			.param("limit", "10")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(orderService).getMyOrders(USER_ID, Market.STOCK, "2026-07-29T09:00:00_1", 10);
	}

	@Test
	void getMyOrdersRejectsMissingMarketWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void getMyOrdersRejectsInvalidMarketLiteralWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/orders")
			.param("market", "FOREX")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void getMyOrdersRejectsLimitBelowMinimumWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/orders")
			.param("market", "STOCK")
			.param("limit", "0")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void getMyOrdersRejectsLimitAboveMaximumWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/orders")
			.param("market", "STOCK")
			.param("limit", "101")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void getMyOrdersReturnsBadRequestWhenServiceRejectsMalformedCursor() throws Exception {
		stubAuthenticatedUser();
		when(orderService.getMyOrders(eq(USER_ID), eq(Market.STOCK), eq("garbage"), eq(20)))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다."));

		mockMvc.perform(get("/api/orders")
			.param("market", "STOCK")
			.param("cursor", "garbage")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(orderService).getMyOrders(USER_ID, Market.STOCK, "garbage", 20);
	}

	@Test
	void getMyOrdersRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/orders")
			.param("market", "STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void getMyPendingOrdersReturnsOkWithEveryFieldWhenMarketIsCrypto() throws Exception {
		stubAuthenticatedUser();
		LocalDateTime requestedAt = LocalDateTime.of(2026, 8, 6, 9, 0);
		OrderListItemResponse item = new OrderListItemResponse(
			1L, "CRYPTO", 1L, "BUY", "LIMIT", "PENDING", new BigDecimal("1"),
			new BigDecimal("70000000"), requestedAt, 91L, 3L);
		OrderListResponse response = OrderListResponse.of(List.of(item), "2026-08-06T09:00:00_1", true);
		when(orderService.getMyPendingOrders(USER_ID, Market.CRYPTO, null, 20)).thenReturn(response);

		mockMvc.perform(get("/api/orders/pending")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[0].orderId").value(1))
			.andExpect(jsonPath("$.content[0].market").value("CRYPTO"))
			.andExpect(jsonPath("$.content[0].instrumentId").value(1))
			.andExpect(jsonPath("$.content[0].side").value("BUY"))
			.andExpect(jsonPath("$.content[0].orderType").value("LIMIT"))
			.andExpect(jsonPath("$.content[0].status").value("PENDING"))
			.andExpect(jsonPath("$.content[0].quantity").value(1))
			.andExpect(jsonPath("$.content[0].limitPrice").value(70000000))
			.andExpect(jsonPath("$.content[0].requestedAt").value("2026-08-06T09:00:00"))
			.andExpect(jsonPath("$.content[0].practiceAttemptId").value(91))
			.andExpect(jsonPath("$.content[0].practiceAttemptRunNumber").value(3))
			.andExpect(jsonPath("$.nextCursor").value("2026-08-06T09:00:00_1"))
			.andExpect(jsonPath("$.hasNext").value(true));

		verify(orderService).getMyPendingOrders(USER_ID, Market.CRYPTO, null, 20);
	}

	@Test
	void getMyPendingOrdersReturnsOkWithEmptyContentWhenNoPendingOrders() throws Exception {
		stubAuthenticatedUser();
		OrderListResponse response = OrderListResponse.of(List.of(), null, false);
		when(orderService.getMyPendingOrders(USER_ID, Market.CRYPTO, null, 20)).thenReturn(response);

		mockMvc.perform(get("/api/orders/pending")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isEmpty())
			.andExpect(jsonPath("$.nextCursor").doesNotExist())
			.andExpect(jsonPath("$.hasNext").value(false));

		verify(orderService).getMyPendingOrders(USER_ID, Market.CRYPTO, null, 20);
	}

	@Test
	void getMyPendingOrdersUsesDefaultLimitWhenLimitIsOmitted() throws Exception {
		stubAuthenticatedUser();
		when(orderService.getMyPendingOrders(eq(USER_ID), eq(Market.CRYPTO), isNull(), eq(20)))
			.thenReturn(OrderListResponse.of(List.of(), null, false));

		mockMvc.perform(get("/api/orders/pending")
			.param("market", "CRYPTO")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(orderService).getMyPendingOrders(USER_ID, Market.CRYPTO, null, 20);
	}

	@Test
	void getMyPendingOrdersPassesCursorAndLimitToService() throws Exception {
		stubAuthenticatedUser();
		when(orderService.getMyPendingOrders(eq(USER_ID), eq(Market.CRYPTO), any(), eq(10)))
			.thenReturn(OrderListResponse.of(List.of(), null, false));

		mockMvc.perform(get("/api/orders/pending")
			.param("market", "CRYPTO")
			.param("cursor", "2026-08-06T09:00:00_1")
			.param("limit", "10")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk());

		verify(orderService).getMyPendingOrders(USER_ID, Market.CRYPTO, "2026-08-06T09:00:00_1", 10);
	}

	@Test
	void getMyPendingOrdersRejectsMissingMarketWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/orders/pending")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void getMyPendingOrdersRejectsInvalidMarketLiteralWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/orders/pending")
			.param("market", "FOREX")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void getMyPendingOrdersRejectsLimitBelowMinimumWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/orders/pending")
			.param("market", "CRYPTO")
			.param("limit", "0")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void getMyPendingOrdersRejectsLimitAboveMaximumWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/orders/pending")
			.param("market", "CRYPTO")
			.param("limit", "101")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void getMyPendingOrdersReturnsBadRequestWhenServiceRejectsMalformedCursor() throws Exception {
		stubAuthenticatedUser();
		when(orderService.getMyPendingOrders(eq(USER_ID), eq(Market.CRYPTO), eq("garbage"), eq(20)))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다."));

		mockMvc.perform(get("/api/orders/pending")
			.param("market", "CRYPTO")
			.param("cursor", "garbage")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(orderService).getMyPendingOrders(USER_ID, Market.CRYPTO, "garbage", 20);
	}

	@Test
	void getMyPendingOrdersRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/orders/pending")
			.param("market", "CRYPTO"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(orderService);
	}

	@Test
	void cancelLimitOrderReturnsNoContentWithEmptyBody() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(delete("/api/orders/{orderId}", 1L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(limitOrderCancelService).cancelOrder(USER_ID, 1L);
	}

	@Test
	void cancelLimitOrderReturnsNotFoundWhenOrderDoesNotExist() throws Exception {
		stubAuthenticatedUser();
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.NOT_FOUND))
			.when(limitOrderCancelService).cancelOrder(USER_ID, 999L);

		mockMvc.perform(delete("/api/orders/{orderId}", 999L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderCancelService).cancelOrder(USER_ID, 999L);
	}

	@Test
	void cancelLimitOrderReturnsForbiddenWhenNotOwner() throws Exception {
		stubAuthenticatedUser();
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.FORBIDDEN))
			.when(limitOrderCancelService).cancelOrder(USER_ID, 2L);

		mockMvc.perform(delete("/api/orders/{orderId}", 2L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderCancelService).cancelOrder(USER_ID, 2L);
	}

	@Test
	void cancelLimitOrderReturnsOrderAlreadyFilledWhenAlreadyFilled() throws Exception {
		stubAuthenticatedUser();
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.ORDER_ALREADY_FILLED))
			.when(limitOrderCancelService).cancelOrder(USER_ID, 3L);

		mockMvc.perform(delete("/api/orders/{orderId}", 3L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ORDER_ALREADY_FILLED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderCancelService).cancelOrder(USER_ID, 3L);
	}

	@Test
	void cancelLimitOrderReturnsOrderAlreadyCancelledWhenAlreadyCancelled() throws Exception {
		stubAuthenticatedUser();
		org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.ORDER_ALREADY_CANCELLED))
			.when(limitOrderCancelService).cancelOrder(USER_ID, 4L);

		mockMvc.perform(delete("/api/orders/{orderId}", 4L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ORDER_ALREADY_CANCELLED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderCancelService).cancelOrder(USER_ID, 4L);
	}

	@Test
	void cancelLimitOrderRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(delete("/api/orders/{orderId}", 1L))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(limitOrderCancelService);
	}

	@Test
	void modifyLimitOrderReturnsOkWithEveryResponseField() throws Exception {
		stubAuthenticatedUser();
		LocalDateTime requestedAt = LocalDateTime.of(2026, 8, 6, 9, 0);
		LimitOrderResponse response = new LimitOrderResponse(
			1L, "CRYPTO", 1L, "BUY", "LIMIT", "PENDING", new BigDecimal("0.5"), new BigDecimal("75000000"),
			requestedAt);
		when(limitOrderModifyService.modifyOrder(eq(USER_ID), eq(1L), any(LimitOrderUpdateRequest.class)))
			.thenReturn(response);

		mockMvc.perform(patch("/api/orders/{orderId}", 1L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"limitPrice":"75000000","quantity":"0.5"}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.orderId").value(1))
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.instrumentId").value(1))
			.andExpect(jsonPath("$.side").value("BUY"))
			.andExpect(jsonPath("$.orderType").value("LIMIT"))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.quantity").value(0.5))
			.andExpect(jsonPath("$.limitPrice").value(75000000))
			.andExpect(jsonPath("$.requestedAt").value("2026-08-06T09:00:00"));

		verify(limitOrderModifyService).modifyOrder(eq(USER_ID), eq(1L), any(LimitOrderUpdateRequest.class));
	}

	@Test
	void modifyLimitOrderReturnsOkForPartialUpdateWithOnlyQuantity() throws Exception {
		stubAuthenticatedUser();
		LocalDateTime requestedAt = LocalDateTime.of(2026, 8, 6, 9, 0);
		LimitOrderResponse response = new LimitOrderResponse(
			1L, "CRYPTO", 1L, "SELL", "LIMIT", "PENDING", new BigDecimal("0.2"), new BigDecimal("70000000"),
			requestedAt);
		when(limitOrderModifyService.modifyOrder(eq(USER_ID), eq(1L), any(LimitOrderUpdateRequest.class)))
			.thenReturn(response);

		mockMvc.perform(patch("/api/orders/{orderId}", 1L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"quantity":"0.2"}
				"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quantity").value(0.2))
			.andExpect(jsonPath("$.limitPrice").value(70000000));

		verify(limitOrderModifyService).modifyOrder(eq(USER_ID), eq(1L), any(LimitOrderUpdateRequest.class));
	}

	@Test
	void modifyLimitOrderReturnsValidationErrorWhenBodyHasNeitherField() throws Exception {
		stubAuthenticatedUser();
		when(limitOrderModifyService.modifyOrder(eq(USER_ID), eq(1L), any(LimitOrderUpdateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "변경할 값이 없습니다."));

		mockMvc.perform(patch("/api/orders/{orderId}", 1L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderModifyService).modifyOrder(eq(USER_ID), eq(1L), any(LimitOrderUpdateRequest.class));
	}

	@Test
	void modifyLimitOrderReturnsValidationErrorWhenLimitPriceIsZeroWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(patch("/api/orders/{orderId}", 1L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"limitPrice":"0"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(limitOrderModifyService);
	}

	@Test
	void modifyLimitOrderReturnsValidationErrorWhenQuantityIsNegativeWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(patch("/api/orders/{orderId}", 1L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"quantity":"-1"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(limitOrderModifyService);
	}

	@Test
	void modifyLimitOrderReturnsNotFoundWhenOrderDoesNotExist() throws Exception {
		stubAuthenticatedUser();
		when(limitOrderModifyService.modifyOrder(eq(USER_ID), eq(999L), any(LimitOrderUpdateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(patch("/api/orders/{orderId}", 999L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"limitPrice":"75000000"}
				"""))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderModifyService).modifyOrder(eq(USER_ID), eq(999L), any(LimitOrderUpdateRequest.class));
	}

	@Test
	void modifyLimitOrderReturnsForbiddenWhenNotOwner() throws Exception {
		stubAuthenticatedUser();
		when(limitOrderModifyService.modifyOrder(eq(USER_ID), eq(2L), any(LimitOrderUpdateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		mockMvc.perform(patch("/api/orders/{orderId}", 2L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"limitPrice":"75000000"}
				"""))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderModifyService).modifyOrder(eq(USER_ID), eq(2L), any(LimitOrderUpdateRequest.class));
	}

	@Test
	void modifyLimitOrderReturnsOrderAlreadyFilledWhenAlreadyFilled() throws Exception {
		stubAuthenticatedUser();
		when(limitOrderModifyService.modifyOrder(eq(USER_ID), eq(3L), any(LimitOrderUpdateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.ORDER_ALREADY_FILLED));

		mockMvc.perform(patch("/api/orders/{orderId}", 3L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"limitPrice":"75000000"}
				"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ORDER_ALREADY_FILLED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderModifyService).modifyOrder(eq(USER_ID), eq(3L), any(LimitOrderUpdateRequest.class));
	}

	@Test
	void modifyLimitOrderReturnsOrderAlreadyCancelledWhenAlreadyCancelled() throws Exception {
		stubAuthenticatedUser();
		when(limitOrderModifyService.modifyOrder(eq(USER_ID), eq(4L), any(LimitOrderUpdateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.ORDER_ALREADY_CANCELLED));

		mockMvc.perform(patch("/api/orders/{orderId}", 4L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"limitPrice":"75000000"}
				"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("ORDER_ALREADY_CANCELLED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderModifyService).modifyOrder(eq(USER_ID), eq(4L), any(LimitOrderUpdateRequest.class));
	}

	@Test
	void modifyLimitOrderReturnsInsufficientCashWhenServiceRejectsCashShortage() throws Exception {
		stubAuthenticatedUser();
		when(limitOrderModifyService.modifyOrder(eq(USER_ID), eq(5L), any(LimitOrderUpdateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_CASH));

		mockMvc.perform(patch("/api/orders/{orderId}", 5L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"limitPrice":"999999999"}
				"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_CASH"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderModifyService).modifyOrder(eq(USER_ID), eq(5L), any(LimitOrderUpdateRequest.class));
	}

	@Test
	void modifyLimitOrderReturnsInsufficientQtyWhenServiceRejectsQuantityShortage() throws Exception {
		stubAuthenticatedUser();
		when(limitOrderModifyService.modifyOrder(eq(USER_ID), eq(6L), any(LimitOrderUpdateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_QTY));

		mockMvc.perform(patch("/api/orders/{orderId}", 6L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"quantity":"999999999"}
				"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_QTY"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(limitOrderModifyService).modifyOrder(eq(USER_ID), eq(6L), any(LimitOrderUpdateRequest.class));
	}

	@Test
	void modifyLimitOrderRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(patch("/api/orders/{orderId}", 1L)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"limitPrice":"75000000"}
				"""))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(limitOrderModifyService);
	}
}
