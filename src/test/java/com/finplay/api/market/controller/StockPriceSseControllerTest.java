// /api/stocks/stream 엔드포인트의 인증, register()·sendSnapshot() 위임, 재접속 시 재호출, emitter 정리 배선을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.market.controller;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.StockPriceStreamService;
import com.finplay.api.market.sse.SseEmitterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// SseEmitter는 완료되지 않는 한 MockMvc의 asyncDispatch가 발생하지 않는다 — MvcResult.getAsyncResult()·
// request().asyncResult(...)는 내부적으로 그 디스패치를 무한정(spring.mvc.async.request-timeout=-1) 기다리므로
// 이 클래스의 테스트에서는 절대 호출하지 않는다(request().asyncStarted()는 플래그만 동기 확인해 안전하다).
// 혹시 모를 회귀에 대비해 테스트당 타임아웃도 걸어둔다.
@WebMvcTest(StockPriceSseController.class)
@Import(SecurityConfig.class)
@Timeout(10)
class StockPriceSseControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 1L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SseEmitterRegistry sseEmitterRegistry;

	@MockitoBean
	private StockPriceStreamService stockPriceStreamService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void streamRejectsMissingAuthenticationWithoutRegisteringEmitter() throws Exception {
		mockMvc.perform(get("/api/stocks/stream"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(sseEmitterRegistry, stockPriceStreamService);
	}

	@Test
	void streamRegistersStockEmitterAndDelegatesTheSameInstanceToSendSnapshot() throws Exception {
		authenticate();
		SseEmitter emitter = new SseEmitter();
		when(sseEmitterRegistry.register(Market.STOCK)).thenReturn(emitter);

		mockMvc.perform(authorized(get("/api/stocks/stream")))
			.andExpect(request().asyncStarted());

		// register()가 반환한 emitter가 그대로 sendSnapshot()에 전달돼야(=컨트롤러가 새 emitter를 만들지 않고
		// 그 인스턴스를 그대로 반환해야) 등록 시 배선된 onCompletion/onTimeout/onError 정리 콜백(SseEmitterRegistry
		// 계약)이 실제 요청과 연결된다. MvcResult.getAsyncResult()/request().asyncResult(...)는 이 emitter가
		// 끝내 완료되지 않는 한 asyncDispatch를 무한정 기다리므로(spring.mvc.async.request-timeout=-1) 쓰지 않는다.
		verify(sseEmitterRegistry).register(Market.STOCK);
		verify(stockPriceStreamService).sendSnapshot(emitter);
	}

	@Test
	void streamResendsSnapshotOnEveryNewSubscriptionForReconnection() throws Exception {
		authenticate();
		SseEmitter firstEmitter = new SseEmitter();
		SseEmitter secondEmitter = new SseEmitter();
		when(sseEmitterRegistry.register(Market.STOCK)).thenReturn(firstEmitter).thenReturn(secondEmitter);

		mockMvc.perform(authorized(get("/api/stocks/stream"))).andExpect(request().asyncStarted());
		mockMvc.perform(authorized(get("/api/stocks/stream"))).andExpect(request().asyncStarted());

		verify(sseEmitterRegistry, times(2)).register(Market.STOCK);
		verify(stockPriceStreamService).sendSnapshot(firstEmitter);
		verify(stockPriceStreamService).sendSnapshot(secondEmitter);
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
