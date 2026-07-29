// 종목 목록·단건 조회 API의 인증, 시장 필터링, 검증 실패, 응답 DTO 계약을 검증하는 WebMvc 슬라이스 테스트다.
package com.finplay.api.market.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.dto.response.CandleResponse;
import com.finplay.api.market.dto.response.InstrumentResponse;
import com.finplay.api.market.service.CandleQueryService;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.market.service.PriceQuoteDto;
import com.finplay.api.market.service.PriceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InstrumentController.class)
@Import(SecurityConfig.class)
class InstrumentControllerTest {

	private static final String ACCESS_TOKEN = "access-token";
	private static final long USER_ID = 1L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private InstrumentService instrumentService;

	@MockitoBean
	private PriceQueryService priceQueryService;

	@MockitoBean
	private CandleQueryService candleQueryService;

	@MockitoBean
	private JwtTokenProvider jwtTokenProvider;

	@Test
	void getInstrumentsRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/instruments"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(instrumentService);
	}

	@Test
	void getInstrumentsRejectsInvalidBearerTokenWithoutCallingService() throws Exception {
		when(jwtTokenProvider.parseAccessToken("not.a.jwt")).thenReturn(Optional.empty());

		mockMvc.perform(get("/api/instruments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(instrumentService);
	}

	@Test
	void getInstrumentsPassesNullToServiceAndSerializesResponseWhenMarketParamOmitted() throws Exception {
		authenticate();
		when(instrumentService.getInstruments(null))
			.thenReturn(instruments("STOCK", 16));

		mockMvc.perform(authorized(get("/api/instruments")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(16))
			.andExpect(jsonPath("$[0].instrumentId").value(1))
			.andExpect(jsonPath("$[0].market").value("STOCK"))
			.andExpect(jsonPath("$[0].symbol").value("SYM1"))
			.andExpect(jsonPath("$[0].name").value("종목1"))
			.andExpect(jsonPath("$[0].tickSize").value(100))
			.andExpect(jsonPath("$[0].minOrderAmount").value(70000))
			.andExpect(jsonPath("$[0].tradable").value(true));

		verify(instrumentService).getInstruments(null);
	}

	@Test
	void getInstrumentsTreatsBlankMarketParamSameAsOmitted() throws Exception {
		authenticate();
		when(instrumentService.getInstruments(null))
			.thenReturn(instruments("STOCK", 16));

		mockMvc.perform(authorized(get("/api/instruments")).param("market", ""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(16));

		verify(instrumentService).getInstruments(null);
	}

	@Test
	void getInstrumentsPassesStockMarketToServiceAndSerializesResponse() throws Exception {
		authenticate();
		when(instrumentService.getInstruments(Market.STOCK))
			.thenReturn(instruments("STOCK", 16));

		mockMvc.perform(authorized(get("/api/instruments")).param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(16))
			.andExpect(jsonPath("$[0].market").value("STOCK"))
			.andExpect(jsonPath("$[15].market").value("STOCK"));

		verify(instrumentService).getInstruments(Market.STOCK);
	}

	@Test
	void getInstrumentsPassesCryptoMarketToServiceAndSerializesResponse() throws Exception {
		authenticate();
		when(instrumentService.getInstruments(Market.CRYPTO))
			.thenReturn(instruments("CRYPTO", 12));

		mockMvc.perform(authorized(get("/api/instruments")).param("market", "CRYPTO"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(12))
			.andExpect(jsonPath("$[0].market").value("CRYPTO"))
			.andExpect(jsonPath("$[11].market").value("CRYPTO"));

		verify(instrumentService).getInstruments(Market.CRYPTO);
	}

	@Test
	void getInstrumentsReturnsCommonValidationErrorForUnknownMarketValueWithoutCallingService() throws Exception {
		authenticate();

		mockMvc.perform(authorized(get("/api/instruments")).param("market", "FOO"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(instrumentService);
	}

	@Test
	void getInstrumentsResponseExposesOnlyDtoFieldsNotEntityInternals() throws Exception {
		authenticate();
		when(instrumentService.getInstruments(null)).thenReturn(List.of(
			new InstrumentResponse(1L, "STOCK", "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true)));

		mockMvc.perform(authorized(get("/api/instruments")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].instrumentId").exists())
			.andExpect(jsonPath("$[0].market").exists())
			.andExpect(jsonPath("$[0].symbol").exists())
			.andExpect(jsonPath("$[0].name").exists())
			.andExpect(jsonPath("$[0].tickSize").exists())
			.andExpect(jsonPath("$[0].minOrderAmount").exists())
			.andExpect(jsonPath("$[0].tradable").exists())
			.andExpect(jsonPath("$[0].id").doesNotExist())
			.andExpect(jsonPath("$[0].createdAt").doesNotExist());
	}

	@Test
	void getInstrumentReturnsStockInstrumentWithFullContractWhenFound() throws Exception {
		authenticate();
		when(instrumentService.getInstrument(1L)).thenReturn(
			new InstrumentResponse(1L, "STOCK", "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}", 1L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.instrumentId").value(1))
			.andExpect(jsonPath("$.market").value("STOCK"))
			.andExpect(jsonPath("$.symbol").value("005930"))
			.andExpect(jsonPath("$.name").value("삼성전자"))
			.andExpect(jsonPath("$.tickSize").value(100))
			.andExpect(jsonPath("$.minOrderAmount").value(70000))
			.andExpect(jsonPath("$.tradable").value(true));

		verify(instrumentService).getInstrument(1L);
	}

	@Test
	void getInstrumentReturnsCryptoInstrumentWithFullContractWhenFound() throws Exception {
		authenticate();
		when(instrumentService.getInstrument(17L)).thenReturn(
			new InstrumentResponse(17L, "CRYPTO", "BTC", "비트코인", BigDecimal.valueOf(1000), 5000L, true));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}", 17L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.instrumentId").value(17))
			.andExpect(jsonPath("$.market").value("CRYPTO"))
			.andExpect(jsonPath("$.symbol").value("BTC"))
			.andExpect(jsonPath("$.name").value("비트코인"))
			.andExpect(jsonPath("$.tickSize").value(1000))
			.andExpect(jsonPath("$.minOrderAmount").value(5000))
			.andExpect(jsonPath("$.tradable").value(true));

		verify(instrumentService).getInstrument(17L);
	}

	@Test
	void getInstrumentReturnsCommonNotFoundErrorFormatWithoutExposingEntityWhenMissing() throws Exception {
		authenticate();
		when(instrumentService.getInstrument(999L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}", 999L)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty())
			.andExpect(jsonPath("$.id").doesNotExist())
			.andExpect(jsonPath("$.symbol").doesNotExist());

		verify(instrumentService).getInstrument(999L);
	}

	@Test
	void getInstrumentRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/instruments/{instrumentId}", 1L))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(instrumentService);
	}

	@Test
	void getInstrumentReturnsCommonValidationErrorForNonNumericIdWithoutCallingService() throws Exception {
		authenticate();

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}", "abc")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(instrumentService);
	}

	@Test
	void getPriceRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/instruments/{instrumentId}/price", 1L))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(priceQueryService);
	}

	@Test
	void getPriceReturnsCommonNotFoundErrorFormatWhenInstrumentMissing() throws Exception {
		authenticate();
		when(priceQueryService.getPrice(999L)).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/price", 999L)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(priceQueryService).getPrice(999L);
	}

	@Test
	void getPriceReturnsCommonConflictErrorFormatWhenPriceUnavailable() throws Exception {
		authenticate();
		when(priceQueryService.getPrice(1L)).thenThrow(new BusinessException(ErrorCode.PRICE_UNAVAILABLE));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/price", 1L)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("PRICE_UNAVAILABLE"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(priceQueryService).getPrice(1L);
	}

	@Test
	void getPriceReturnsFullContractWhenAvailableForStockInstrument() throws Exception {
		authenticate();
		LocalDateTime sourceTime = LocalDateTime.of(2026, 7, 28, 9, 1, 0);
		LocalDate sourceTradingDate = LocalDate.of(2026, 7, 24);
		when(priceQueryService.getPrice(1L)).thenReturn(
			new PriceQuoteDto(BigDecimal.valueOf(70100), sourceTime, PriceStatus.AVAILABLE, sourceTradingDate));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/price", 1L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.price").value(70100))
			.andExpect(jsonPath("$.sourceTime").value("2026-07-28T09:01:00"))
			.andExpect(jsonPath("$.status").value("AVAILABLE"))
			.andExpect(jsonPath("$.sourceTradingDate").value("2026-07-24"));

		verify(priceQueryService).getPrice(1L);
	}

	@Test
	void getPriceReturnsFullContractWhenAvailableForCryptoInstrumentWithoutSourceTradingDate() throws Exception {
		authenticate();
		LocalDateTime sourceTime = LocalDateTime.of(2026, 7, 28, 10, 30, 0);
		when(priceQueryService.getPrice(17L)).thenReturn(
			new PriceQuoteDto(BigDecimal.valueOf(95000000), sourceTime, PriceStatus.AVAILABLE, null));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/price", 17L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.price").value(95000000))
			.andExpect(jsonPath("$.sourceTime").value("2026-07-28T10:30:00"))
			.andExpect(jsonPath("$.status").value("AVAILABLE"))
			.andExpect(jsonPath("$.sourceTradingDate").doesNotExist());

		verify(priceQueryService).getPrice(17L);
	}

	@Test
	void getPriceReturnsCommonValidationErrorForNonNumericIdWithoutCallingService() throws Exception {
		authenticate();

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/price", "abc")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(priceQueryService);
	}

	@Test
	void getCandlesRejectsMissingAuthenticationWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "1m"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(candleQueryService);
	}

	@Test
	void getCandlesReturnsCommonNotFoundErrorFormatWhenInstrumentMissing() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(999L, "1m", null, null))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 999L).param("interval", "1m")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(999L, "1m", null, null);
	}

	@Test
	void getCandlesReturnsCommonValidationErrorForUnsupportedInterval() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(eq(1L), eq("5m"), isNull(), isNull()))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "지원하지 않는 캔들 간격입니다. interval=1m만 지원합니다."));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "5m")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(1L, "5m", null, null);
	}

	@Test
	void getCandlesReturnsCommonValidationErrorWhenFromIsAfterTo() throws Exception {
		authenticate();
		LocalDateTime from = LocalDateTime.of(2026, 7, 27, 10, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 27, 9, 0);
		when(candleQueryService.getCandles(1L, "1m", from, to))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "from은 to보다 늦을 수 없습니다."));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L)
			.param("interval", "1m")
			.param("from", "2026-07-27T10:00:00")
			.param("to", "2026-07-27T09:00:00")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(1L, "1m", from, to);
	}

	@Test
	void getCandlesReturnsCommonValidationErrorForCryptoInstrumentId() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(eq(17L), eq("1m"), isNull(), isNull()))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "주식 종목만 캔들 조회를 지원합니다."));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 17L).param("interval", "1m")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(17L, "1m", null, null);
	}

	@Test
	void getCandlesReturnsFullContractWithCandlesWhenAvailable() throws Exception {
		authenticate();
		LocalDateTime from = LocalDateTime.of(2026, 7, 27, 9, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 27, 9, 5);
		when(candleQueryService.getCandles(1L, "1m", from, to)).thenReturn(List.of(
			new CandleResponse(LocalDateTime.of(2026, 7, 27, 9, 0), BigDecimal.valueOf(70000),
				BigDecimal.valueOf(70500), BigDecimal.valueOf(69900), BigDecimal.valueOf(70200), 12345L)));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L)
			.param("interval", "1m")
			.param("from", "2026-07-27T09:00:00")
			.param("to", "2026-07-27T09:05:00")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].sourceTime").value("2026-07-27T09:00:00"))
			.andExpect(jsonPath("$[0].open").value(70000))
			.andExpect(jsonPath("$[0].high").value(70500))
			.andExpect(jsonPath("$[0].low").value(69900))
			.andExpect(jsonPath("$[0].close").value(70200))
			.andExpect(jsonPath("$[0].volume").value(12345));

		verify(candleQueryService).getCandles(1L, "1m", from, to);
	}

	@Test
	void getCandlesReturnsFullContractWithNullFromToWhenOmitted() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(1L, "1m", null, null)).thenReturn(List.of(
			new CandleResponse(LocalDateTime.of(2026, 7, 27, 9, 0), BigDecimal.valueOf(70000),
				BigDecimal.valueOf(70500), BigDecimal.valueOf(69900), BigDecimal.valueOf(70200), 12345L)));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "1m")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1));

		verify(candleQueryService).getCandles(1L, "1m", null, null);
	}

	@Test
	void getCandlesReturnsEmptyArrayWithOkStatusWhenReplaySessionNotReadyOrNoCandleRevealedYet() throws Exception {
		// 재생세션 미준비·아직 공개된 분봉이 없는 경우에도 가격 API(409 PRICE_UNAVAILABLE)와 달리 예외 없이 200 + 빈 배열이어야 한다.
		authenticate();
		when(candleQueryService.getCandles(1L, "1m", null, null)).thenReturn(List.of());

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "1m")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(0));

		verify(candleQueryService).getCandles(1L, "1m", null, null);
	}

	private void authenticate() {
		when(jwtTokenProvider.parseAccessToken(ACCESS_TOKEN))
			.thenReturn(Optional.of(new AuthenticatedUser(USER_ID, "USER")));
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder authorized(
		org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
		return builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN);
	}

	private static List<InstrumentResponse> instruments(String market, int count) {
		return IntStream.range(0, count)
			.mapToObj(i -> new InstrumentResponse(
				(long)(i + 1),
				market,
				"SYM" + (i + 1),
				"종목" + (i + 1),
				BigDecimal.valueOf(100),
				70000L,
				true))
			.toList();
	}
}
