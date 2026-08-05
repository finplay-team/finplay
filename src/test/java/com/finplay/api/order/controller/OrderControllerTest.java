// 시장가 매수 주문 생성 API의 인증, 검증, 응답 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.LimitOrderResponse;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import com.finplay.api.order.dto.response.OrderListResponse;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.service.LimitOrderService;
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
			1L, "STOCK", 1L, "BUY", "MARKET", "FILLED", new BigDecimal("10"), requestedAt);
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
			.andExpect(jsonPath("$.content[0].requestedAt").value("2026-07-29T09:00:00"))
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
}
