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
import com.finplay.api.feedback.domain.MarketNewsItemType;
import com.finplay.api.feedback.domain.PostSellFeedbackStatus;
import com.finplay.api.feedback.dto.response.CounterfactualScenario;
import com.finplay.api.feedback.dto.response.Counterfactuals;
import com.finplay.api.feedback.dto.response.HeldPriceMoveItem;
import com.finplay.api.feedback.dto.response.NewsItem;
import com.finplay.api.feedback.dto.response.PeerComparison;
import com.finplay.api.feedback.dto.response.PostSellFeedbackResponse;
import com.finplay.api.feedback.dto.response.PostSellFlow;
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

	// --- 계약 필드 집합 (완료 조건 18번) ---

	// 중첩 객체까지 필드 수를 못박는다 — 뒤 항목이 값을 채워도 이 수는 바뀌지 않는다. 값이 null이면 사라지는
	// 직렬화 설정이 들어오면 화면이 "필드 유무"로 분기하다 계약이 바뀐 것처럼 보인다.
	@Test
	@DisplayName("매도 후 흐름·반사실·집단 비교가 계약 필드 집합대로 직렬화된다 — priceMoveId·narrativeSource·buyAt·sellAt 포함")
	void serializesEveryContractFieldIncludingTheNestedBlocks() throws Exception {
		authenticate();
		when(postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID)).thenReturn(marketClosedResponse());

		mockMvc.perform(authorized(get(PATH, SELL_TRADE_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(CONTRACT_FIELD_COUNT))
			.andExpect(jsonPath("$.buyAt").value("2026-07-29T09:30:00"))
			.andExpect(jsonPath("$.sellAt").value("2026-07-29T14:40:00"))
			// 매도 후 흐름 — 상태 + 가격 5값.
			.andExpect(jsonPath("$.postSellFlow.length()").value(6))
			.andExpect(jsonPath("$.postSellFlow.status").value("READY"))
			.andExpect(jsonPath("$.postSellFlow.closeAt").value("2026-07-29T15:27:00"))
			.andExpect(jsonPath("$.postSellFlow.postSellHighAt").value("2026-07-29T15:05:00"))
			// 반사실 — 상태 + 시나리오 3종, 각 시나리오는 price·at·returnRate 세 필드다.
			.andExpect(jsonPath("$.counterfactuals.length()").value(4))
			.andExpect(jsonPath("$.counterfactuals.status").value("READY"))
			.andExpect(jsonPath("$.counterfactuals.atClose.length()").value(3))
			.andExpect(jsonPath("$.counterfactuals.atClose.at").value("2026-07-29T15:27:00"))
			// 7번 머지 전까지 returnRate는 null이다 — 필드는 남아 있다.
			.andExpect(jsonPath("$.counterfactuals.atClose.returnRate").isEmpty())
			.andExpect(jsonPath("$.counterfactuals.atHoldHigh.returnRate").isEmpty())
			.andExpect(jsonPath("$.counterfactuals.atFirstMoveAfterBuy.returnRate").isEmpty())
			// 집단 비교 — status만 값이고 priceMoveId를 포함한 지표 5개가 null이다.
			.andExpect(jsonPath("$.peerComparison.length()").value(6))
			.andExpect(jsonPath("$.peerComparison.status").value("NOT_YET"))
			.andExpect(jsonPath("$.peerComparison.priceMoveId").isEmpty())
			.andExpect(jsonPath("$.peerComparison.holderCount").isEmpty())
			.andExpect(jsonPath("$.peerComparison.soldWithin30MinRate").isEmpty())
			.andExpect(jsonPath("$.peerComparison.medianMinutesToSell").isEmpty())
			.andExpect(jsonPath("$.peerComparison.yourMinutesToSell").isEmpty())
			// 보유 구간 카드 — id·구간 두 값·변동률·간격 두 값·서술·근거.
			.andExpect(jsonPath("$.priceMoves.length()").value(1))
			.andExpect(jsonPath("$.priceMoves[0].length()").value(8))
			.andExpect(jsonPath("$.priceMoves[0].id").value(12))
			.andExpect(jsonPath("$.priceMoves[0].windowEnd").value("2026-07-29T11:25:00"))
			.andExpect(jsonPath("$.priceMoves[0].minutesAfterBuy").value(115))
			.andExpect(jsonPath("$.priceMoves[0].minutesBeforeSell").value(195))
			.andExpect(jsonPath("$.priceMoves[0].sources.length()").value(1))
			.andExpect(jsonPath("$.priceMoves[0].sources[0].length()").value(5))
			// 4번 항목 몫이라 아직 null이지만 필드는 계약 집합에 남아 있다.
			.andExpect(jsonPath("$.narrative").isEmpty())
			.andExpect(jsonPath("$.narrativeSource").isEmpty())
			.andExpect(jsonPath("$.narrativeStatus").isEmpty());
	}

	// scale이 있는 파생 비율도 정수로 접히지 않는지 본문 문자열로 확인한다.
	@Test
	@DisplayName("매도 후 흐름·극값의 비율이 scale 4 그대로 직렬화된다")
	void serializesDerivedRatesWithScaleFourIntact() throws Exception {
		authenticate();
		when(postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID)).thenReturn(marketClosedResponse());

		String body = mockMvc.perform(authorized(get(PATH, SELL_TRADE_ID)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		assertThat(body).contains("\"sellToCloseRate\":0.0102");
		assertThat(body).contains("\"sellVsHighRate\":-0.0325");
		assertThat(body).contains("\"sellVsLowRate\":0.0059");
	}

	// 껍데기(status만 담은 객체)를 내리지 않는다 — 세 블록이 자기 자신이 null이다.
	@Test
	@DisplayName("sameSessionCompleted=false면 세 블록이 null로 직렬화되고 필드 수는 그대로다")
	void serializesThePostSellBlocksAsNullWhenTheSessionIsNotCompleted() throws Exception {
		authenticate();
		when(postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID))
			.thenReturn(ledgerOnlyResponse(false));

		mockMvc.perform(authorized(get(PATH, SELL_TRADE_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(CONTRACT_FIELD_COUNT))
			.andExpect(jsonPath("$.sameSessionCompleted").value(false))
			.andExpect(jsonPath("$.postSellFlow").isEmpty())
			.andExpect(jsonPath("$.counterfactuals").isEmpty())
			.andExpect(jsonPath("$.peerComparison").isEmpty())
			.andExpect(jsonPath("$.postSellFlow.status").doesNotExist())
			.andExpect(jsonPath("$.counterfactuals.status").doesNotExist())
			.andExpect(jsonPath("$.peerComparison.status").doesNotExist());
	}

	/**
	 * 장 마감 게이트가 열린 뒤 3번 항목이 돌려주는 형태 — 매도 후 흐름·반사실 가격이 채워지고 반사실
	 * {@code returnRate}와 집단 비교는 {@code plan.md} 7번 몫으로 {@code null}이다. 값은 계약 예시 그대로다.
	 */
	private static PostSellFeedbackResponse marketClosedResponse() {
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
			new BigDecimal("70800"),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)),
			new BigDecimal("68100"),
			LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(14, 20)),
			new BigDecimal("-0.0325"),
			new BigDecimal("0.0059"),
			105,
			List.of(new HeldPriceMoveItem(
				12L,
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 20)),
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 25)),
				new BigDecimal("-0.018200"),
				115,
				195,
				"11시 20분부터 5분간 1.82% 하락했습니다.",
				List.of(new NewsItem(
					MarketNewsItemType.NEWS,
					"생산 차질",
					"hankyung.com",
					"https://news.example.test/1",
					LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 15)))))),
			new PostSellFlow(
				PostSellFeedbackStatus.READY,
				new BigDecimal("69200"),
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 27)),
				new BigDecimal("0.0102"),
				new BigDecimal("69500"),
				LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 5))),
			new Counterfactuals(
				PostSellFeedbackStatus.READY,
				new CounterfactualScenario(
					new BigDecimal("69200"), LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(15, 27)), null),
				new CounterfactualScenario(
					new BigDecimal("70800"), LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 5)), null),
				new CounterfactualScenario(
					new BigDecimal("69300"), LocalDateTime.of(ORIGIN_TRADE_DATE, LocalTime.of(11, 25)), null)),
			new PeerComparison(PostSellFeedbackStatus.NOT_YET, null, null, null, null, null),
			null,
			null,
			null);
	}

	// --- 이슈 #212 4번 항목 — peerComparison 상태별 직렬화 ---

	@Test
	@DisplayName("peerComparison.status=NO_EVENT면 priceMoveId를 포함한 전 필드가 null로 직렬화된다")
	void serializesPeerComparisonAsNoEventWithEveryFieldNull() throws Exception {
		authenticate();
		when(postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID))
			.thenReturn(
				withPeerComparison(new PeerComparison(PostSellFeedbackStatus.NO_EVENT, null, null, null, null, null)));

		mockMvc.perform(authorized(get(PATH, SELL_TRADE_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.peerComparison.length()").value(6))
			.andExpect(jsonPath("$.peerComparison.status").value("NO_EVENT"))
			.andExpect(jsonPath("$.peerComparison.priceMoveId").isEmpty())
			.andExpect(jsonPath("$.peerComparison.holderCount").isEmpty())
			.andExpect(jsonPath("$.peerComparison.soldWithin30MinRate").isEmpty())
			.andExpect(jsonPath("$.peerComparison.medianMinutesToSell").isEmpty())
			.andExpect(jsonPath("$.peerComparison.yourMinutesToSell").isEmpty());
	}

	@Test
	@DisplayName("peerComparison.status=INSUFFICIENT_SAMPLE이면 모집단 지표 3종은 null이고 priceMoveId·yourMinutesToSell만 채워진다")
	void serializesPeerComparisonAsInsufficientSampleWithOnlyYourMinutesToSellFilled() throws Exception {
		authenticate();
		when(postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID)).thenReturn(withPeerComparison(
			new PeerComparison(PostSellFeedbackStatus.INSUFFICIENT_SAMPLE, 12L, null, null, null, 290)));

		mockMvc.perform(authorized(get(PATH, SELL_TRADE_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.peerComparison.status").value("INSUFFICIENT_SAMPLE"))
			.andExpect(jsonPath("$.peerComparison.priceMoveId").value(12))
			.andExpect(jsonPath("$.peerComparison.holderCount").isEmpty())
			.andExpect(jsonPath("$.peerComparison.soldWithin30MinRate").isEmpty())
			.andExpect(jsonPath("$.peerComparison.medianMinutesToSell").isEmpty())
			.andExpect(jsonPath("$.peerComparison.yourMinutesToSell").value(290));
	}

	@Test
	@DisplayName("peerComparison.status=READY면 모집단 지표 3종·priceMoveId·yourMinutesToSell이 모두 채워지고 회원 식별자는 없다")
	void serializesPeerComparisonAsReadyWithEveryMetricFilledAndNoMemberIdentifier() throws Exception {
		authenticate();
		when(postSellFeedbackService.getPostSellFeedback(USER_ID, SELL_TRADE_ID)).thenReturn(withPeerComparison(
			new PeerComparison(
				PostSellFeedbackStatus.READY, 12L, 7, new BigDecimal("0.2857"), 12, 290)));

		String body = mockMvc.perform(authorized(get(PATH, SELL_TRADE_ID)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.peerComparison.length()").value(6))
			.andExpect(jsonPath("$.peerComparison.status").value("READY"))
			.andExpect(jsonPath("$.peerComparison.priceMoveId").value(12))
			.andExpect(jsonPath("$.peerComparison.holderCount").value(7))
			.andExpect(jsonPath("$.peerComparison.medianMinutesToSell").value(12))
			.andExpect(jsonPath("$.peerComparison.yourMinutesToSell").value(290))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);

		// scale 4가 정수로 접히지 않는지, 그리고 회원 식별자(user·member·account·nickname)가 어디에도 없는지 본다.
		assertThat(body).contains("\"soldWithin30MinRate\":0.2857");
		assertThat(body.toLowerCase()).doesNotContain("userid").doesNotContain("memberid").doesNotContain("nickname");
	}

	/**
	 * {@link #marketClosedResponse()}에서 {@code peerComparison}만 갈아 끼운다 — 4번 항목이 실제로 만드는 세
	 * 확정 상태(NO_EVENT·INSUFFICIENT_SAMPLE·READY)의 직렬화를 다른 필드를 건드리지 않고 본다.
	 */
	private static PostSellFeedbackResponse withPeerComparison(PeerComparison peerComparison) {
		PostSellFeedbackResponse base = marketClosedResponse();
		return new PostSellFeedbackResponse(
			base.tradeId(), base.instrumentId(), base.symbol(), base.name(), base.buyAt(), base.sellAt(),
			base.buyPrice(), base.sellPrice(), base.quantity(), base.fee(), base.realizedPnl(), base.returnRate(),
			base.holdingMinutes(), base.sameSessionCompleted(), base.holdHighPrice(), base.holdHighAt(),
			base.holdLowPrice(), base.holdLowAt(), base.sellVsHighRate(), base.sellVsLowRate(),
			base.buyToNewsMinutes(), base.priceMoves(), base.postSellFlow(), base.counterfactuals(), peerComparison,
			base.narrative(), base.narrativeSource(), base.narrativeStatus());
	}

	/**
	 * 1번 항목이 실제로 돌려주는 형태 — 원장 수치와 {@code sameSessionCompleted}만 채우고 나머지는
	 * {@code null}·{@code []}다. 수치는 계약 예시 그대로다. {@code sameSessionCompleted=false}일 때의 형태와도
	 * 같아서(세 블록이 자기 자신이 {@code null}이다) 그 케이스가 이 객체를 함께 쓴다.
	 */
	private static PostSellFeedbackResponse ledgerOnlyResponse() {
		return ledgerOnlyResponse(true);
	}

	private static PostSellFeedbackResponse ledgerOnlyResponse(boolean sameSessionCompleted) {
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
			sameSessionCompleted,
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
