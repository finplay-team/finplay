package com.finplay.api.domain.education.priceruntime.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.education.priceruntime.service.PracticeLimitOrderService;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PracticeLimitOrderController.class)
@Import(SecurityConfig.class)
class PracticeLimitOrderControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;

	@Autowired
	private MockMvc mockMvc;
	@MockitoBean
	private PracticeLimitOrderService practiceLimitOrderService;
	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void createOrderReturnsCreatedWithBuySideAndPendingStatus() throws Exception {
		authenticate();
		when(practiceLimitOrderService.createOrder(eq(USER_ID), any())).thenReturn(sampleResponse());

		mockMvc.perform(post("/api/education/practice/limit-orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"practicePriceSessionId\":100,\"instrumentId\":10,\"quantity\":0.1,\"limitPrice\":9500}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.orderId").value(55))
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.instrumentId").value(10))
			.andExpect(jsonPath("$.side").value("BUY"))
			.andExpect(jsonPath("$.orderType").value("LIMIT"))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.quantity").value(0.1))
			.andExpect(jsonPath("$.limitPrice").value(9500));
	}

	@Test
	void createOrderRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(post("/api/education/practice/limit-orders")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"practicePriceSessionId\":100,\"instrumentId\":10,\"quantity\":0.1,\"limitPrice\":9500}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
		verifyNoInteractions(practiceLimitOrderService);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidCreateBodies")
	void createOrderRejectsMissingOrNonPositiveOrMissingFields(String name, String body) throws Exception {
		authenticate();
		mockMvc.perform(post("/api/education/practice/limit-orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(practiceLimitOrderService);
	}

	@Test
	void createOrderMapsMissingOrOtherOwnerSessionToNotFound() throws Exception {
		authenticate();
		when(practiceLimitOrderService.createOrder(anyLong(), any()))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(post("/api/education/practice/limit-orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"practicePriceSessionId\":100,\"instrumentId\":10,\"quantity\":0.1,\"limitPrice\":9500}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void createOrderMapsClosedSessionToConflict() throws Exception {
		authenticate();
		when(practiceLimitOrderService.createOrder(anyLong(), any()))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_PRICE_SESSION_CLOSED));

		mockMvc.perform(post("/api/education/practice/limit-orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"practicePriceSessionId\":100,\"instrumentId\":10,\"quantity\":0.1,\"limitPrice\":9500}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRACTICE_PRICE_SESSION_CLOSED"));
	}

	@Test
	void createOrderMapsSessionMismatchToConflict() throws Exception {
		authenticate();
		when(practiceLimitOrderService.createOrder(anyLong(), any()))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_PRICE_SESSION_MISMATCH));

		mockMvc.perform(post("/api/education/practice/limit-orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"practicePriceSessionId\":100,\"instrumentId\":10,\"quantity\":0.1,\"limitPrice\":9500}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRACTICE_PRICE_SESSION_MISMATCH"));
	}

	@Test
	void createOrderMapsAlreadyPendingSessionOrderToConflict() throws Exception {
		authenticate();
		when(practiceLimitOrderService.createOrder(anyLong(), any()))
			.thenThrow(new BusinessException(ErrorCode.PRACTICE_LIMIT_ORDER_ALREADY_PENDING));

		mockMvc.perform(post("/api/education/practice/limit-orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"practicePriceSessionId\":100,\"instrumentId\":10,\"quantity\":0.1,\"limitPrice\":9500}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRACTICE_LIMIT_ORDER_ALREADY_PENDING"));
	}

	@Test
	void createOrderMapsNonTradableInstrumentToConflict() throws Exception {
		authenticate();
		when(practiceLimitOrderService.createOrder(anyLong(), any()))
			.thenThrow(new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE));

		mockMvc.perform(post("/api/education/practice/limit-orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"practicePriceSessionId\":100,\"instrumentId\":10,\"quantity\":0.1,\"limitPrice\":9500}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSTRUMENT_NOT_TRADABLE"));
	}

	@Test
	void createOrderMapsInsufficientCashToConflict() throws Exception {
		authenticate();
		when(practiceLimitOrderService.createOrder(anyLong(), any()))
			.thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_CASH));

		mockMvc.perform(post("/api/education/practice/limit-orders")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"practicePriceSessionId\":100,\"instrumentId\":10,\"quantity\":0.1,\"limitPrice\":9500}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSUFFICIENT_CASH"));
	}

	private static Stream<Arguments> invalidCreateBodies() {
		return Stream.of(
			Arguments.of("missing practicePriceSessionId",
				"{\"instrumentId\":10,\"quantity\":0.1,\"limitPrice\":9500}"),
			Arguments.of("zero practicePriceSessionId",
				"{\"practicePriceSessionId\":0,\"instrumentId\":10,\"quantity\":0.1,\"limitPrice\":9500}"),
			Arguments.of("negative practicePriceSessionId",
				"{\"practicePriceSessionId\":-1,\"instrumentId\":10,\"quantity\":0.1,\"limitPrice\":9500}"),
			Arguments.of("missing instrumentId",
				"{\"practicePriceSessionId\":100,\"quantity\":0.1,\"limitPrice\":9500}"),
			Arguments.of("zero instrumentId",
				"{\"practicePriceSessionId\":100,\"instrumentId\":0,\"quantity\":0.1,\"limitPrice\":9500}"),
			Arguments.of("missing quantity",
				"{\"practicePriceSessionId\":100,\"instrumentId\":10,\"limitPrice\":9500}"),
			Arguments.of("missing limitPrice",
				"{\"practicePriceSessionId\":100,\"instrumentId\":10,\"quantity\":0.1}"));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	private LimitOrderResponse sampleResponse() {
		return new LimitOrderResponse(
			55L, "CRYPTO", 10L, "BUY", "LIMIT", "PENDING",
			new BigDecimal("0.1"), new BigDecimal("9500"), LocalDateTime.of(2026, 8, 11, 10, 0));
	}
}
