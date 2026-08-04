// 매도 직후 피드백 조회 API의 인증·직렬화·오류 매핑 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.feedback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.auth.config.SecurityConfig;
import com.finplay.api.auth.token.AuthenticatedUser;
import com.finplay.api.auth.token.JwtTokenProvider;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.service.PostSellFeedbackService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// 계약의 정본은 docs/api-contracts.md의 "매도 직후 피드백 조회" 소절이고, 이 파일이 보는 것은 이슈 #208
// 1번 항목의 완료 조건 중 API 계약(404·403·400)과 401이다. 판정 자체(무엇이 400인가)는
// PostSellFeedbackServiceTest가, 실제 원장 위에서의 종단은 PostSellFeedbackIntegrationTest가 맡는다.
//
// 1번 항목이 아직 채우지 않는 필드도 계약의 필드 집합에는 남아 있어야 한다 — 필드를 나중에 더하면 그 사이
// 계약이 깨진 상태로 머지되므로, 필드 개수와 "존재하는데 null"을 여기서 못박는다.
@WebMvcTest(PostSellFeedbackController.class)
@Import(SecurityConfig.class)
class PostSellFeedbackControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 1L;
	private static final long SELL_TRADE_ID = 2L;
	private static final String PATH = "/api/ai/post-sell/{tradeId}";

	// 계약 예시와 같은 원본 거래일·시각이다.
	private static final LocalDate ORIGIN_TRADE_DATE = LocalDate.of(2026, 7, 29);

	// 계약이 정한 응답 필드 수. 뒤 항목이 값을 채워도 이 수는 바뀌지 않는다.
	private static final int CONTRACT_FIELD_COUNT = 28;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PostSellFeedbackService postSellFeedbackService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	// --- 401 (네 엔드포인트 공통, 배정 건수 밖) ---

	@Test
	@DisplayName("토큰 없이 호출하면 401이고 서비스를 부르지 않는다")
	void rejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get(PATH, SELL_TRADE_ID))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(postSellFeedbackService);
	}

	@Test
	@DisplayName("잘못된 Bearer 토큰이면 401이고 서비스를 부르지 않는다")
	void rejectsInvalidBearerTokenWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken("not.a.jwt")).thenReturn(Optional.empty());

		mockMvc.perform(get(PATH, SELL_TRADE_ID).header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

		verifyNoInteractions(postSellFeedbackService);
	}

	// --- 오류 매핑 (완료 조건 17번) ---

	@Test
	@DisplayName("tradeId가 없으면 404 NOT_FOUND 공통 오류 형식이다")
	void mapsMissingTradeToNotFound() throws Exception {
		authenticate();
		when(postSellFeedbackService.getPostSellFeedback(USER_ID, 999L))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(authorized(get(PATH, 999L)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	@DisplayName("타인 체결이면 403 FORBIDDEN 공통 오류 형식이다")
	void mapsOtherUsersTradeToForbidden() throws Exception {
		authenticate();
		when(postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		mockMvc.perform(authorized(get(PATH, SELL_TRADE_ID)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	@DisplayName("매수 체결이면 400 VALIDATION_ERROR 공통 오류 형식이다")
	void mapsBuyTradeToValidationError() throws Exception {
		authenticate();
		when(postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		mockMvc.perform(authorized(get(PATH, SELL_TRADE_ID)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	// 완료 조건 1번의 HTTP 쪽 — 코인은 빈 값을 채운 200이 아니라 400이다.
	@Test
	@DisplayName("코인 체결이면 400 VALIDATION_ERROR이고 본문에 회고 필드가 없다")
	void mapsCryptoTradeToValidationErrorWithoutAnyFeedbackField() throws Exception {
		authenticate();
		when(postSellFeedbackService.getPostSellFeedback(USER_ID, 3L))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR));

		mockMvc.perform(authorized(get(PATH, 3L)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.tradeId").doesNotExist())
			.andExpect(jsonPath("$.sameSessionCompleted").doesNotExist());
	}

	@Test
	@DisplayName("tradeId가 숫자가 아니면 서비스를 부르지 않는다")
	void rejectsNonNumericTradeIdWithoutCallingService() throws Exception {
		authenticate();

		mockMvc.perform(authorized(get("/api/ai/post-sell/abc")))
			.andExpect(status().is4xxClientError());

		verifyNoInteractions(postSellFeedbackService);
	}

	// --- 직렬화 ---

	@Test
	@DisplayName("원장 수치가 계약대로 직렬화된다 — buyAt·sellAt은 원본 거래일 날짜가 붙은 시각이다")
	void serializesLedgerNumbersAccordingToTheContract() throws Exception {
		authenticate();
		when(postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID)).thenReturn(ledgerOnlyResponse());

		mockMvc.perform(authorized(get(PATH, SELL_TRADE_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tradeId").value(2))
			.andExpect(jsonPath("$.instrumentId").value(1))
			.andExpect(jsonPath("$.symbol").value("005930"))
			.andExpect(jsonPath("$.name").value("삼성전자"))
			// 조회한 날짜도 서비스 날짜도 아니라 원본 거래일이다 (계약).
			.andExpect(jsonPath("$.buyAt").value("2026-07-29T09:30:00"))
			.andExpect(jsonPath("$.sellAt").value("2026-07-29T14:40:00"))
			.andExpect(jsonPath("$.fee").value(102))
			.andExpect(jsonPath("$.realizedPnl").value(-15207))
			.andExpect(jsonPath("$.holdingMinutes").value(310))
			.andExpect(jsonPath("$.sameSessionCompleted").value(true))
			.andExpect(jsonPath("$.priceMoves").isArray())
			.andExpect(jsonPath("$.priceMoves.length()").value(0));

		verify(postSellFeedbackService).getPostSellFeedback(USER_ID, SELL_TRADE_ID);
	}

	// buyPrice는 Holding.averagePrice와 같은 scale 8 관례라 JSON에 70000.00000000으로 나간다. jsonPath의 수
	// 비교는 파서가 부동소수로 접는 자리라 scale이 보이지 않으므로, 본문 문자열에서 직접 확인한다.
	@Test
	@DisplayName("buyPrice가 scale 8 그대로 직렬화된다 — 정수로 접히지 않는다")
	void serializesBuyPriceWithScaleEightIntact() throws Exception {
		authenticate();
		when(postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID)).thenReturn(ledgerOnlyResponse());

		String body = mockMvc.perform(authorized(get(PATH, SELL_TRADE_ID)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		assertThat(body).contains("\"buyPrice\":70000.00000000");
		assertThat(body).contains("\"returnRate\":-0.0217");
	}

	// 1번 항목이 채우지 않는 필드가 응답에서 사라지면 안 된다 — 화면이 필드 유무로 분기하면 뒤 항목 머지
	// 시점에 계약이 바뀐 것처럼 보인다.
	@Test
	@DisplayName("아직 채우지 않는 필드도 계약의 필드 집합에 남아 있고 값만 null·[]이다")
	void keepsUnfilledContractFieldsPresentWithNullValues() throws Exception {
		authenticate();
		when(postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID)).thenReturn(ledgerOnlyResponse());

		mockMvc.perform(authorized(get(PATH, SELL_TRADE_ID)))
			.andExpect(status().isOk())
			// isEmpty()는 "경로가 있고 값이 비었다"를 본다 — 필드가 사라지면 경로 자체가 없어 실패한다.
			.andExpect(jsonPath("$.holdHighPrice").isEmpty())
			.andExpect(jsonPath("$.holdHighAt").isEmpty())
			.andExpect(jsonPath("$.holdLowPrice").isEmpty())
			.andExpect(jsonPath("$.holdLowAt").isEmpty())
			.andExpect(jsonPath("$.sellVsHighRate").isEmpty())
			.andExpect(jsonPath("$.sellVsLowRate").isEmpty())
			.andExpect(jsonPath("$.buyToNewsMinutes").isEmpty())
			.andExpect(jsonPath("$.postSellFlow").isEmpty())
			.andExpect(jsonPath("$.counterfactuals").isEmpty())
			.andExpect(jsonPath("$.peerComparison").isEmpty())
			.andExpect(jsonPath("$.narrative").isEmpty())
			.andExpect(jsonPath("$.narrativeSource").isEmpty())
			.andExpect(jsonPath("$.narrativeStatus").isEmpty())
			.andExpect(jsonPath("$.length()").value(CONTRACT_FIELD_COUNT));
	}

	/**
	 * 1번 항목이 실제로 돌려주는 형태 — 원장 수치와 {@code sameSessionCompleted}만 채우고 나머지는
	 * {@code null}·{@code []}다. 수치는 계약 예시 그대로다.
	 */
	private static PostSellFeedbackResponse ledgerOnlyResponse() {
		return new PostSellFeedbackResponse(
			SELL_TRADE_ID,
			1L,
			"005930",
			"삼성전자",
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(9, 30)),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 40)),
			new BigDecimal("70000.00000000"),
			new BigDecimal("68500"),
			new BigDecimal("10"),
			102L,
			-15_207L,
			new BigDecimal("-0.0217"),
			310,
			true,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			List.of(),
			null,
			null,
			null,
			null,
			null,
			null);
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN);
	}
}
