package com.finplay.api.domain.market.controller;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.market.service.StockPriceStreamService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@WebMvcTest(StockPriceSseController.class)
@Import(SecurityConfig.class)
@Timeout(10)
class StockPriceSseControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 1L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private StockPriceStreamService stockPriceStreamService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void streamRejectsMissingAuthenticationWithoutSubscribing() throws Exception {
		mockMvc.perform(get("/api/stocks/stream"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(stockPriceStreamService);
	}

	@Test
	void streamCallsCreateEmitterThenSendSnapshotThenActivateInOrderAndReturnsTheEmitter() throws Exception {
		authenticate();
		SseEmitter emitter = new SseEmitter();
		when(stockPriceStreamService.createEmitter()).thenReturn(emitter);

		mockMvc.perform(authorized(get("/api/stocks/stream")))
			.andExpect(request().asyncStarted());

		InOrder inOrder = inOrder(stockPriceStreamService);
		inOrder.verify(stockPriceStreamService).createEmitter();
		inOrder.verify(stockPriceStreamService).sendSnapshot(emitter);
		inOrder.verify(stockPriceStreamService).activate(emitter);
	}

	@Test
	void streamResubscribesOnEveryNewSubscriptionForReconnection() throws Exception {
		authenticate();
		SseEmitter firstEmitter = new SseEmitter();
		SseEmitter secondEmitter = new SseEmitter();
		when(stockPriceStreamService.createEmitter()).thenReturn(firstEmitter).thenReturn(secondEmitter);

		mockMvc.perform(authorized(get("/api/stocks/stream"))).andExpect(request().asyncStarted());
		mockMvc.perform(authorized(get("/api/stocks/stream"))).andExpect(request().asyncStarted());

		verify(stockPriceStreamService, times(2)).createEmitter();
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorized(
		org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN);
	}
}
