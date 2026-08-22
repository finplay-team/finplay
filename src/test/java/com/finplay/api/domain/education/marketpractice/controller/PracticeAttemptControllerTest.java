// 튜토리얼 attempt 진입·종목 선택 API의 인증, JSON 계약, 입력 검증과 오류 매핑을 검증한다.
package com.finplay.api.domain.education.marketpractice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.education.marketpractice.dto.response.ExitPresetResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.ExitRateBoundsResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.ExitRates;
import com.finplay.api.domain.education.marketpractice.service.PracticeAttemptDeadlockRetryService;
import com.finplay.api.domain.education.marketpractice.service.PracticeAttemptService;
import com.finplay.api.domain.education.marketpractice.service.PracticeExitPlanReservationService;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.dto.response.ExitPlanResponse;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.entity.ExitPriceType;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PracticeAttemptController.class)
@Import(SecurityConfig.class)
class PracticeAttemptControllerTest {

	private static final String TOKEN = "access-token";
	private static final long USER_ID = 7L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PracticeAttemptService practiceAttemptService;

	// 진입은 재시도 경계(PracticeAttemptDeadlockRetryService)를 거친다 (이슈 #491).
	@MockitoBean
	private PracticeAttemptDeadlockRetryService practiceAttemptDeadlockRetryService;

	// 052 EXITFREE-020 — 사용자 주도 예약 생성.
	@MockitoBean
	private PracticeExitPlanReservationService practiceExitPlanReservationService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void ensureAttemptRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(put("/api/education/practice/attempts/STOCK"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(practiceAttemptDeadlockRetryService);
	}

	@Test
	void ensureAttemptReturnsActiveAttemptJson() throws Exception {
		authenticate();
		PracticeAttemptResponse response = new PracticeAttemptResponse(
			11L, "STOCK", 1L, "ACTIVE", "SELECTING_INSTRUMENT", null, null, null, null, null,
			10_000_000L, 10_000_000L, 0L,
			"BALANCED", false, ExitPresetResponse.all(),
			new BigDecimal("3"), new BigDecimal("5"), ExitRateBoundsResponse.current());
		when(practiceAttemptDeadlockRetryService.ensureAttempt(USER_ID, Market.STOCK)).thenReturn(response);

		mockMvc.perform(put("/api/education/practice/attempts/STOCK")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attemptId").value(11))
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.runNumber").value(1))
			.andExpect(jsonPath("$.mode").value("ACTIVE"))
			.andExpect(jsonPath("$.status").value("SELECTING_INSTRUMENT"))
			.andExpect(jsonPath("$.tutorialCashBalance").value(10_000_000))
			.andExpect(jsonPath("$.tutorialAvailableCash").value(10_000_000))
			.andExpect(jsonPath("$.tutorialRealizedPnl").value(0));

		verify(practiceAttemptDeadlockRetryService).ensureAttempt(USER_ID, Market.STOCK);
	}

	@Test
	void ensureAttemptRejectsInvalidMarketPath() throws Exception {
		authenticate();

		mockMvc.perform(put("/api/education/practice/attempts/FOREX")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(practiceAttemptDeadlockRetryService);
	}

	@Test
	void selectInstrumentReturnsInProgressAttemptJson() throws Exception {
		authenticate();
		PracticeAttemptResponse response = new PracticeAttemptResponse(
			11L,
			"CRYPTO",
			1L,
			"ACTIVE",
			"IN_PROGRESS",
			21L,
			LocalDateTime.of(2026, 8, 14, 12, 0),
			LocalDate.of(2026, 8, 14),
			null,
			null,
			0L,
			0L,
			0L,
			"BALANCED", false, ExitPresetResponse.all(),
			new BigDecimal("3"), new BigDecimal("5"), ExitRateBoundsResponse.current());
		when(practiceAttemptService.selectInstrument(USER_ID, Market.CRYPTO, 21L)).thenReturn(response);

		mockMvc.perform(put("/api/education/practice/attempts/CRYPTO/instrument")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"instrumentId\":21}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attemptId").value(11))
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.instrumentId").value(21))
			.andExpect(jsonPath("$.anchorAt").value("2026-08-14T12:00:00"))
			.andExpect(jsonPath("$.tutorialDate").value("2026-08-14"))
			// TUTORIAL-CASH-ISOL-011 범위는 진입·재시작 응답 한정 — 종목 선택 응답은 튜토리얼 계좌를
			// 다시 조회하지 않으므로 세 필드 모두 0을 반환하는 것이 설계 의도다(api-contracts.md 명시).
			.andExpect(jsonPath("$.tutorialCashBalance").value(0))
			.andExpect(jsonPath("$.tutorialAvailableCash").value(0))
			.andExpect(jsonPath("$.tutorialRealizedPnl").value(0));

		verify(practiceAttemptService).selectInstrument(USER_ID, Market.CRYPTO, 21L);
	}

	@Test
	void selectInstrumentRejectsMissingInstrumentId() throws Exception {
		authenticate();

		mockMvc.perform(put("/api/education/practice/attempts/STOCK/instrument")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("종목 ID는 필수입니다."));

		verifyNoInteractions(practiceAttemptService);
	}

	@Test
	void selectInstrumentRejectsNonPositiveInstrumentId() throws Exception {
		authenticate();

		mockMvc.perform(put("/api/education/practice/attempts/STOCK/instrument")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"instrumentId\":0}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").value("종목 ID는 양수여야 합니다."));

		verifyNoInteractions(practiceAttemptService);
	}

	// 042 EXITPRESET-003 — 정의 밖 문자열은 Jackson이 열거형으로 못 바꿔 400 VALIDATION_ERROR가 된다.
	@Test
	void selectExitPresetReturnsUpdatedAttempt() throws Exception {
		authenticate();
		PracticeAttemptResponse response = new PracticeAttemptResponse(
			11L, "CRYPTO", 1L, "ACTIVE", "IN_PROGRESS", 21L, null, null, null, null,
			0L, 0L, 0L,
			"CAUTIOUS", false, ExitPresetResponse.all(),
			new BigDecimal("3"), new BigDecimal("5"), ExitRateBoundsResponse.current());
		when(practiceAttemptService.selectExitPreset(USER_ID, Market.CRYPTO, ExitPreset.CAUTIOUS))
			.thenReturn(response);

		mockMvc.perform(put("/api/education/practice/attempts/CRYPTO/exit-preset")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"preset\":\"CAUTIOUS\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.selectedExitPreset").value("CAUTIOUS"))
			.andExpect(jsonPath("$.exitPresetLocked").value(false))
			.andExpect(jsonPath("$.availableExitPresets.length()").value(3))
			.andExpect(jsonPath("$.availableExitPresets[0].preset").value("CAUTIOUS"))
			.andExpect(jsonPath("$.availableExitPresets[0].stopLossRate").value(2));
	}

	@Test
	void selectExitPresetRejectsUnknownPresetWithoutCallingService() throws Exception {
		authenticate();

		mockMvc.perform(put("/api/education/practice/attempts/CRYPTO/exit-preset")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"preset\":\"AGGRESSIVE\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(practiceAttemptService);
	}

	@Test
	void selectExitPresetRejectsMissingPresetWithoutCallingService() throws Exception {
		authenticate();

		mockMvc.perform(put("/api/education/practice/attempts/CRYPTO/exit-preset")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(practiceAttemptService);
	}

	@Test
	void selectInstrumentMapsBusinessErrorToConflict() throws Exception {
		authenticate();
		when(practiceAttemptService.selectInstrument(USER_ID, Market.STOCK, 21L))
			.thenThrow(new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE));

		mockMvc.perform(put("/api/education/practice/attempts/STOCK/instrument")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"instrumentId\":21}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("INSTRUMENT_NOT_TRADABLE"))
			.andExpect(jsonPath("$.error.message").value("거래할 수 없는 종목입니다."));
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	// --- 052 EXITFREE-020: POST .../exit-plan ---

	@Test
	void createExitPlanRejectsMissingAuthentication() throws Exception {
		mockMvc.perform(post("/api/education/practice/attempts/CRYPTO/exit-plan")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"stopLossRate\":3.0,\"takeProfitRate\":5.0}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(practiceExitPlanReservationService);
	}

	/**
	 * 201이어야 한다 — 이 경로는 자연 멱등이 아니라 <b>진입당 한 번만</b> 성공하는 생성이다
	 * (052 EXITFREE-020 write-once). 200으로 내리면 화면이 재시도해도 되는 요청으로 오해한다.
	 */
	@Test
	void createExitPlanReturnsCreatedWithTheReservationJson() throws Exception {
		authenticate();
		when(practiceExitPlanReservationService.create(eq(USER_ID), eq(Market.CRYPTO), any()))
			.thenReturn(exitPlanResponse());

		mockMvc.perform(post("/api/education/practice/attempts/CRYPTO/exit-plan")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"stopLossRate\":2.5,\"takeProfitRate\":7.5}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(41))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.stopLossPrice").value(9700.00000000))
			.andExpect(jsonPath("$.takeProfitPrice").value(10500.00000000))
			// **가격·비율은 JSON 문자열이 아니라 숫자로 나간다.** 이 저장소는 Jackson을 손대지 않아
			// BigDecimal이 기본대로 숫자로 직렬화된다 — 문자열로 바뀌면 클라이언트가 그대로 계산할 때
			// 문자열 연결이 되어 금액 표시가 통째로 깨진다. 문서의 jsonc 예시가 따옴표를 쓰는 것은
			// scale을 보이려는 표기일 뿐 전송 형식이 아니므로, 그 오해를 여기서 고정한다.
			.andExpect(jsonPath("$.stopLossPrice").isNumber())
			.andExpect(jsonPath("$.takeProfitPrice").isNumber())
			.andExpect(jsonPath("$.entryPrice").isNumber())
			.andExpect(jsonPath("$.quantity").isNumber())
			.andExpect(jsonPath("$.stopLossRate").isNumber());

		ArgumentCaptor<ExitRates> captor = ArgumentCaptor.forClass(ExitRates.class);
		verify(practiceExitPlanReservationService).create(eq(USER_ID), eq(Market.CRYPTO), captor.capture());
		// 프리셋 3개 어디에도 없는 조합이 그대로 서비스에 전달된다 — 컨트롤러가 프리셋으로 환원하지 않는다.
		assertThat(captor.getValue().stopLossRate()).isEqualByComparingTo("2.5");
		assertThat(captor.getValue().takeProfitRate()).isEqualByComparingTo("7.5");
	}

	/**
	 * 검증은 {@code PUT .../exit-rates}와 <b>같은 요청 DTO</b>가 한다 — 구간·소수 자릿수 규칙이 두 경로에서
	 * 갈리면 화면이 입력할 수 있는 값이 한쪽에서만 400이 된다.
	 */
	@ParameterizedTest
	@ValueSource(strings = {
		"{\"stopLossRate\":1.9,\"takeProfitRate\":5}",
		"{\"stopLossRate\":5.1,\"takeProfitRate\":5}",
		"{\"stopLossRate\":3,\"takeProfitRate\":2.9}",
		"{\"stopLossRate\":3,\"takeProfitRate\":8.1}",
		"{\"stopLossRate\":3.05,\"takeProfitRate\":5}",
		"{\"stopLossRate\":3,\"takeProfitRate\":5.25}",
		"{\"takeProfitRate\":5}",
		"{\"stopLossRate\":3}"})
	void createExitPlanRejectsRatesOutsideTheSharedContract(String body) throws Exception {
		authenticate();

		mockMvc.perform(post("/api/education/practice/attempts/CRYPTO/exit-plan")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

		verifyNoInteractions(practiceExitPlanReservationService);
	}

	// 경계 4값은 통과한다 — 프리셋 3종(2/3·3/5·5/8)이 전부 이 구간의 경계·내부 값이라 배제하면 예전에
	// 고를 수 있던 기준을 고를 수 없게 된다(052 EXITFREE-002).
	@ParameterizedTest
	@ValueSource(strings = {
		"{\"stopLossRate\":2,\"takeProfitRate\":3}",
		"{\"stopLossRate\":5,\"takeProfitRate\":8}",
		"{\"stopLossRate\":2,\"takeProfitRate\":8}",
		"{\"stopLossRate\":5,\"takeProfitRate\":3}"})
	void createExitPlanAcceptsBothEndsOfTheAllowedRange(String body) throws Exception {
		authenticate();
		when(practiceExitPlanReservationService.create(eq(USER_ID), eq(Market.CRYPTO), any()))
			.thenReturn(exitPlanResponse());

		mockMvc.perform(post("/api/education/practice/attempts/CRYPTO/exit-plan")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body))
			.andExpect(status().isCreated());
	}

	// 오류 코드를 새로 만들지 않았다 — 전부 기존 집합이며 409로 나간다(052 plan §오류 응답).
	@ParameterizedTest
	@EnumSource(value = ErrorCode.class, names = {
		"EXIT_PLAN_ALREADY_EXISTS", "PRACTICE_STEP_LOCKED", "PRACTICE_STAGE_LOCKED", "PRACTICE_ALREADY_COMPLETED"})
	void createExitPlanMapsBusinessRejectionsToConflict(ErrorCode errorCode) throws Exception {
		authenticate();
		when(practiceExitPlanReservationService.create(eq(USER_ID), eq(Market.CRYPTO), any()))
			.thenThrow(new BusinessException(errorCode));

		mockMvc.perform(post("/api/education/practice/attempts/CRYPTO/exit-plan")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"stopLossRate\":3,\"takeProfitRate\":5}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value(errorCode.name()));
	}

	private static ExitPlanResponse exitPlanResponse() {
		return new ExitPlanResponse(
			41L, 77L, null, null, 9L, new BigDecimal("0.50000000"), new BigDecimal("10000.00000000"),
			ExitPriceType.PERCENT, new BigDecimal("2.5"), new BigDecimal("7.5"),
			new BigDecimal("9700.00000000"), new BigDecimal("10500.00000000"), new BigDecimal("10000.00000000"),
			LocalDateTime.of(2026, 8, 21, 10, 0), ExitPlanStatus.PENDING, LocalDateTime.of(2026, 8, 21, 10, 0),
			null, null, null);
	}

}
