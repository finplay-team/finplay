// /api/cryptos/stream 엔드포인트의 인증, createEmitter→sendSnapshot→activate 순서 위임·반환값 전달, 재접속 시 재호출을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.market.controller;

import static org.mockito.Mockito.inOrder;
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
import com.finplay.api.market.service.CryptoPriceStreamService;
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

// SseEmitter는 완료되지 않는 한 MockMvc의 asyncDispatch가 발생하지 않는다 — MvcResult.getAsyncResult()·
// request().asyncResult(...)는 내부적으로 그 디스패치를 무한정(spring.mvc.async.request-timeout=-1) 기다리므로
// 이 클래스의 테스트에서는 절대 호출하지 않는다(request().asyncStarted()는 플래그만 동기 확인해 안전하다).
// 혹시 모를 회귀에 대비해 테스트당 타임아웃도 걸어둔다. (StockPriceSseControllerTest와 동일 패턴.)
@WebMvcTest(CryptoPriceSseController.class)
@Import(SecurityConfig.class)
@Timeout(10)
class CryptoPriceSseControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 1L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CryptoPriceStreamService cryptoPriceStreamService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void streamRejectsMissingAuthenticationWithoutSubscribing() throws Exception {
		mockMvc.perform(get("/api/cryptos/stream"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(cryptoPriceStreamService);
	}

	@Test
	void streamReturnsEventStreamContentTypeWhenAuthenticated() throws Exception {
		authenticate();
		SseEmitter emitter = new SseEmitter();
		when(cryptoPriceStreamService.createEmitter()).thenReturn(emitter);

		mockMvc.perform(authorized(get("/api/cryptos/stream")))
			.andExpect(request().asyncStarted())
			.andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
				.string(HttpHeaders.CONTENT_TYPE, "text/event-stream"));
	}

	@Test
	void streamCallsCreateEmitterThenSendSnapshotThenActivateInOrderAndReturnsTheEmitter() throws Exception {
		authenticate();
		SseEmitter emitter = new SseEmitter();
		when(cryptoPriceStreamService.createEmitter()).thenReturn(emitter);

		mockMvc.perform(authorized(get("/api/cryptos/stream")))
			.andExpect(request().asyncStarted());

		// 컨트롤러는 createEmitter()가 만든 emitter로 sendSnapshot()을 먼저 호출(프록시를 거치는 트랜잭션 메서드)한
		// 뒤에만 activate()로 브로드캐스트 대상에 추가해야 한다 — 순서가 바뀌면 새 구독자가 snapshot보다 price를
		// 먼저 받는 경합이 재발한다 (PR #94 후속 리뷰, StockPriceSseController와 동일 계약).
		InOrder inOrder = inOrder(cryptoPriceStreamService);
		inOrder.verify(cryptoPriceStreamService).createEmitter();
		inOrder.verify(cryptoPriceStreamService).sendSnapshot(emitter);
		inOrder.verify(cryptoPriceStreamService).activate(emitter);
	}

	@Test
	void streamResubscribesOnEveryNewSubscriptionForReconnection() throws Exception {
		authenticate();
		SseEmitter firstEmitter = new SseEmitter();
		SseEmitter secondEmitter = new SseEmitter();
		when(cryptoPriceStreamService.createEmitter()).thenReturn(firstEmitter).thenReturn(secondEmitter);

		mockMvc.perform(authorized(get("/api/cryptos/stream"))).andExpect(request().asyncStarted());
		mockMvc.perform(authorized(get("/api/cryptos/stream"))).andExpect(request().asyncStarted());

		verify(cryptoPriceStreamService, times(2)).createEmitter();
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
