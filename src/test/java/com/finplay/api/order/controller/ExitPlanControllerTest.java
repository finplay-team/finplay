// OCO 손절·익절 예약 생성·취소 API의 인증, 요청 검증, 예외 매핑을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.order.domain.ExitPlanStatus;
import com.finplay.api.order.domain.ExitPriceType;
import com.finplay.api.order.dto.request.ExitPlanCreateRequest;
import com.finplay.api.order.dto.response.ExitPlanListResponse;
import com.finplay.api.order.dto.response.ExitPlanResponse;
import com.finplay.api.order.service.ExitPlanService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ExitPlanController.class)
@Import(SecurityConfig.class)
class ExitPlanControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 42L;
	private static final String IDEMPOTENCY_KEY = "11111111-1111-1111-1111-111111111111";
	private static final String VALID_PRICE_BODY = """
		{"holdingId":1,"quantity":"1","exitPriceType":"PRICE","stopLoss":"95000000","takeProfit":"110000000"}
		""";
	private static final String VALID_PERCENT_BODY = """
		{"holdingId":1,"quantity":"1","exitPriceType":"PERCENT","stopLossRate":"5","takeProfitRate":"10"}
		""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ExitPlanService exitPlanService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	private void stubAuthenticatedUser() {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	private static ExitPlanResponse sampleResponse() {
		LocalDateTime now = LocalDateTime.of(2026, 8, 13, 9, 0);
		return new ExitPlanResponse(
			1L, 1L, null, null, 1L, new BigDecimal("1"), new BigDecimal("100000000"), ExitPriceType.PRICE,
			null, null, new BigDecimal("95000000"), new BigDecimal("110000000"), new BigDecimal("100500000"),
			now, ExitPlanStatus.PENDING, now, null, null, null);
	}

	@Test
	void createExitPlanReturnsCreatedWithResponseFieldsForPriceMode() throws Exception {
		stubAuthenticatedUser();
		when(exitPlanService.create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(ExitPlanCreateRequest.class)))
			.thenReturn(sampleResponse());

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_PRICE_BODY))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(1))
			.andExpect(jsonPath("$.holdingId").value(1))
			.andExpect(jsonPath("$.intentionId").doesNotExist())
			.andExpect(jsonPath("$.exitPriceType").value("PRICE"))
			.andExpect(jsonPath("$.stopLossPrice").value(95000000))
			.andExpect(jsonPath("$.takeProfitPrice").value(110000000))
			.andExpect(jsonPath("$.status").value("PENDING"));

		verify(exitPlanService).create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(ExitPlanCreateRequest.class));
	}

	@Test
	void createExitPlanReturnsCreatedForPercentMode() throws Exception {
		stubAuthenticatedUser();
		when(exitPlanService.create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(ExitPlanCreateRequest.class)))
			.thenReturn(sampleResponse());

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_PERCENT_BODY))
			.andExpect(status().isCreated());

		verify(exitPlanService).create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(ExitPlanCreateRequest.class));
	}

	@Test
	void createExitPlanRejectsMissingIdempotencyKeyWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_PRICE_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(exitPlanService);
	}

	@Test
	void createExitPlanRejectsBlankIdempotencyKeyWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", "   ")
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_PRICE_BODY))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(exitPlanService);
	}

	@Test
	void createExitPlanRejectsMissingQuantityWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"holdingId":1,"exitPriceType":"PRICE","stopLoss":"95000000","takeProfit":"110000000"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(exitPlanService);
	}

	// PR #368 리뷰 차단 2: PERCENT 모드의 원본 stopLossRate/takeProfitRate는 계산된 가격이 아니라 그 자체가
	// exit_plans.take_profit_rate DECIMAL(8,4)(정수부 4자리) 컬럼에 저장된다 — @Digits 없이는 이 400이 저장
	// 시점 DataIntegrityViolationException(500)으로 샜다.
	@Test
	void createExitPlanRejectsOversizedTakeProfitRateWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"holdingId":1,"quantity":"1","exitPriceType":"PERCENT","stopLossRate":"5","takeProfitRate":"50000"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(exitPlanService);
	}

	// exit_plans.stop_loss_rate DECIMAL(7,4)(정수부 3자리) 기준 — 같은 시나리오를 손절률 쪽에서도 확인한다.
	@Test
	void createExitPlanRejectsOversizedStopLossRateWithoutCallingService() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"holdingId":1,"quantity":"1","exitPriceType":"PERCENT","stopLossRate":"5000","takeProfitRate":"10"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(exitPlanService);
	}

	@Test
	void createExitPlanReturnsValidationErrorWhenServiceRejectsIntentionIdPresent() throws Exception {
		// intentionId를 지정하는 교육 경로는 이 이슈 범위 밖 — 서비스가 400으로 거부한다(컨트롤러는 그대로 전달만 한다).
		stubAuthenticatedUser();
		when(exitPlanService.create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(ExitPlanCreateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "intentionId를 지정하는 교육 경로는 아직 지원하지 않습니다."));

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"intentionId":1,"holdingId":1,"quantity":"1","exitPriceType":"PRICE","stopLoss":"95000000",
				"takeProfit":"110000000"}
				"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(exitPlanService).create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(ExitPlanCreateRequest.class));
	}

	@Test
	void createExitPlanReturnsNotFoundWhenServiceRejectsMissingOrForeignHolding() throws Exception {
		stubAuthenticatedUser();
		when(exitPlanService.create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(ExitPlanCreateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_PRICE_BODY))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(exitPlanService).create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(ExitPlanCreateRequest.class));
	}

	@Test
	void createExitPlanReturnsExitPlanAlreadyExistsWhenServiceRejectsDuplicatePending() throws Exception {
		stubAuthenticatedUser();
		when(exitPlanService.create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(ExitPlanCreateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.EXIT_PLAN_ALREADY_EXISTS));

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_PRICE_BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("EXIT_PLAN_ALREADY_EXISTS"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(exitPlanService).create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(ExitPlanCreateRequest.class));
	}

	@Test
	void createExitPlanReturnsIdempotencyConflictWhenServiceRejectsConflictingReplay() throws Exception {
		stubAuthenticatedUser();
		when(exitPlanService.create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(ExitPlanCreateRequest.class)))
			.thenThrow(new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT));

		mockMvc.perform(post("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_PRICE_BODY))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(exitPlanService).create(eq(USER_ID), eq(IDEMPOTENCY_KEY), any(ExitPlanCreateRequest.class));
	}

	@Test
	void createExitPlanRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/exit-plans")
			.header("Idempotency-Key", IDEMPOTENCY_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID_PRICE_BODY))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(exitPlanService);
	}

	@Test
	void getMyExitPlansReturnsOkWithContentListOnStatusOmitted() throws Exception {
		stubAuthenticatedUser();
		when(exitPlanService.list(eq(USER_ID), isNull()))
			.thenReturn(ExitPlanListResponse.from(List.of(sampleResponse())));

		mockMvc.perform(get("/api/exit-plans")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isArray())
			.andExpect(jsonPath("$.content[0].id").value(1))
			.andExpect(jsonPath("$.content[0].holdingId").value(1))
			.andExpect(jsonPath("$.content[0].intentionId").doesNotExist())
			.andExpect(jsonPath("$.content[0].buyTradeId").doesNotExist())
			.andExpect(jsonPath("$.content[0].status").value("PENDING"));

		verify(exitPlanService).list(eq(USER_ID), isNull());
	}

	@Test
	void getMyExitPlansPassesGivenStatusToService() throws Exception {
		stubAuthenticatedUser();
		when(exitPlanService.list(eq(USER_ID), eq(ExitPlanStatus.CANCELLED)))
			.thenReturn(ExitPlanListResponse.from(List.of()));

		mockMvc.perform(get("/api/exit-plans")
			.param("status", "CANCELLED")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content").isArray())
			.andExpect(jsonPath("$.content").isEmpty());

		verify(exitPlanService).list(eq(USER_ID), eq(ExitPlanStatus.CANCELLED));
	}

	@Test
	void getMyExitPlansRejectsInvalidStatusValue() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(get("/api/exit-plans")
			.param("status", "NOT_A_STATUS")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(exitPlanService);
	}

	@Test
	void getMyExitPlansRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/exit-plans"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(exitPlanService);
	}

	@Test
	void cancelExitPlanReturnsNoContentWithEmptyBody() throws Exception {
		stubAuthenticatedUser();

		mockMvc.perform(delete("/api/exit-plans/{exitPlanId}", 1L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(exitPlanService).cancel(USER_ID, 1L);
	}

	@Test
	void cancelExitPlanReturnsExitPlanNotFoundWhenPlanDoesNotExistOrIsForeign() throws Exception {
		stubAuthenticatedUser();
		doThrow(new BusinessException(ErrorCode.EXIT_PLAN_NOT_FOUND))
			.when(exitPlanService).cancel(USER_ID, 999L);

		mockMvc.perform(delete("/api/exit-plans/{exitPlanId}", 999L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("EXIT_PLAN_NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(exitPlanService).cancel(USER_ID, 999L);
	}

	@Test
	void cancelExitPlanReturnsExitPlanNotPendingWhenPlanIsAlreadyTerminal() throws Exception {
		stubAuthenticatedUser();
		doThrow(new BusinessException(ErrorCode.EXIT_PLAN_NOT_PENDING))
			.when(exitPlanService).cancel(USER_ID, 2L);

		mockMvc.perform(delete("/api/exit-plans/{exitPlanId}", 2L)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("EXIT_PLAN_NOT_PENDING"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(exitPlanService).cancel(USER_ID, 2L);
	}

	@Test
	void cancelExitPlanRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(delete("/api/exit-plans/{exitPlanId}", 1L))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(exitPlanService);
	}
}
