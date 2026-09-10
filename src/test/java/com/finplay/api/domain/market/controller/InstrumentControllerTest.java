package com.finplay.api.domain.market.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finplay.api.domain.auth.config.SecurityConfig;
import com.finplay.api.domain.auth.token.AuthenticatedUser;
import com.finplay.api.domain.auth.token.JwtTokenProvider;
import com.finplay.api.domain.market.dto.response.CandleListResponse;
import com.finplay.api.domain.market.dto.response.CandleResponse;
import com.finplay.api.domain.market.dto.response.InstrumentResponse;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.CandleCursor;
import com.finplay.api.domain.market.service.CandleQueryService;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.market.service.PriceQuoteDto;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
			new InstrumentResponse(1L, "STOCK", "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, false)));

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
	void getInstrumentsResponseExposesIsTutorialSampleFieldForSampleInstruments() throws Exception {
		authenticate();
		when(instrumentService.getInstruments(Market.STOCK)).thenReturn(List.of(
			new InstrumentResponse(101L, "STOCK", "SANDBOX_STK_1", "연습용 주식 A",
				BigDecimal.valueOf(100), 10000L, true, true),
			new InstrumentResponse(102L, "STOCK", "SANDBOX_STK_2", "연습용 주식 B",
				BigDecimal.valueOf(100), 10000L, false, true)));

		mockMvc.perform(authorized(get("/api/instruments")).param("market", "STOCK"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].isTutorialSample").value(true))
			.andExpect(jsonPath("$[0].tradable").value(true))
			.andExpect(jsonPath("$[1].isTutorialSample").value(true))
			.andExpect(jsonPath("$[1].tradable").value(false));

		verify(instrumentService).getInstruments(Market.STOCK);
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
		when(candleQueryService.getCandles(999L, "1m", null, null, null))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 999L).param("interval", "1m")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(999L, "1m", null, null, null);
	}

	@Test
	void getCandlesReturnsCommonValidationErrorForUnsupportedInterval() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(eq(1L), eq("5m"), isNull(), isNull(), isNull()))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "지원하지 않는 캔들 간격입니다. interval=1m만 지원합니다."));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "5m")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(1L, "5m", null, null, null);
	}

	@Test
	void getCandlesReturnsOkWithEmptyEnvelopeForOneDayIntervalNoLongerRejected() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(1L, "1d", null, null, null))
			.thenReturn(CandleListResponse.of(List.of(), null, false));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "1d")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(0))
			.andExpect(jsonPath("$.hasNext").value(false))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());

		verify(candleQueryService).getCandles(1L, "1d", null, null, null);
	}

	@Test
	void getCandlesReturnsOkWithEmptyEnvelopeForOneWeekIntervalNoLongerRejected() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(1L, "1w", null, null, null))
			.thenReturn(CandleListResponse.of(List.of(), null, false));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "1w")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(0));

		verify(candleQueryService).getCandles(1L, "1w", null, null, null);
	}

	@Test
	void getCandlesReturnsOkWithEmptyEnvelopeForUppercaseOneMonthIntervalNoLongerRejected() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(1L, "1M", null, null, null))
			.thenReturn(CandleListResponse.of(List.of(), null, false));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "1M")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(0));

		verify(candleQueryService).getCandles(1L, "1M", null, null, null);
	}

	@Test
	void getCandlesReturnsCommonValidationErrorForUppercaseDIntervalVariant() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(eq(1L), eq("1D"), isNull(), isNull(), isNull()))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "지원하지 않는 캔들 간격입니다."));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "1D")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(1L, "1D", null, null, null);
	}

	@Test
	void getCandlesReturnsCommonValidationErrorForBlankIntervalParam() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(eq(1L), eq(""), isNull(), isNull(), isNull()))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "지원하지 않는 캔들 간격입니다."));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());
	}

	@Test
	void getCandlesReturnsCommonValidationErrorWhenIntervalParamIsMissing() throws Exception {
		authenticate();

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(candleQueryService);
	}

	@Test
	void getCandlesReturnsCommonValidationErrorWhenFromIsNotIsoFormat() throws Exception {
		authenticate();

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L)
			.param("interval", "1m")
			.param("from", "not-a-date")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(candleQueryService);
	}

	@Test
	void getCandlesReturnsCommonValidationErrorWhenFromIsAfterTo() throws Exception {
		authenticate();
		LocalDateTime from = LocalDateTime.of(2026, 7, 27, 10, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 27, 9, 0);
		when(candleQueryService.getCandles(1L, "1m", from, to, null))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "from은 to보다 늦을 수 없습니다."));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L)
			.param("interval", "1m")
			.param("from", "2026-07-27T10:00:00")
			.param("to", "2026-07-27T09:00:00")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(1L, "1m", from, to, null);
	}

	@Test
	void getCandlesReturnsFullContractWithCandlesWhenAvailable() throws Exception {
		authenticate();
		LocalDateTime from = LocalDateTime.of(2026, 7, 27, 9, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 27, 9, 5);
		when(candleQueryService.getCandles(1L, "1m", from, to, null)).thenReturn(CandleListResponse.of(List.of(
			new CandleResponse(LocalDateTime.of(2026, 7, 27, 9, 0), BigDecimal.valueOf(70000),
				BigDecimal.valueOf(70500), BigDecimal.valueOf(69900), BigDecimal.valueOf(70200),
				BigDecimal.valueOf(12345))),
			null, false));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L)
			.param("interval", "1m")
			.param("from", "2026-07-27T09:00:00")
			.param("to", "2026-07-27T09:05:00")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].sourceTime").value("2026-07-27T09:00:00"))
			.andExpect(jsonPath("$.content[0].open").value(70000))
			.andExpect(jsonPath("$.content[0].high").value(70500))
			.andExpect(jsonPath("$.content[0].low").value(69900))
			.andExpect(jsonPath("$.content[0].close").value(70200))
			.andExpect(jsonPath("$.content[0].volume").value(12345))
			.andExpect(jsonPath("$.hasNext").value(false))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());

		verify(candleQueryService).getCandles(1L, "1m", from, to, null);
	}

	@Test
	void getCandlesReturnsFullContractWithNullFromToWhenOmitted() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(1L, "1m", null, null, null)).thenReturn(CandleListResponse.of(List.of(
			new CandleResponse(LocalDateTime.of(2026, 7, 27, 9, 0), BigDecimal.valueOf(70000),
				BigDecimal.valueOf(70500), BigDecimal.valueOf(69900), BigDecimal.valueOf(70200),
				BigDecimal.valueOf(12345))),
			null, false));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "1m")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1));

		verify(candleQueryService).getCandles(1L, "1m", null, null, null);
	}

	@Test
	void getCandlesReturnsCommonValidationErrorForCryptoInstrumentIdIsNoLongerRejectedByDefault() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(17L, "1m", null, null, null)).thenReturn(CandleListResponse.of(List.of(
			new CandleResponse(LocalDateTime.of(2026, 7, 30, 11, 43), new BigDecimal("95000000"),
				new BigDecimal("95100000"), new BigDecimal("94900000"), new BigDecimal("95050000"),
				new BigDecimal("0.26725783"))),
			null, false));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 17L).param("interval", "1m")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.content[0].sourceTime").value("2026-07-30T11:43:00"))
			.andExpect(jsonPath("$.content[0].close").value(95050000))
			.andExpect(jsonPath("$.content[0].volume").value(0.26725783))
			.andExpect(jsonPath("$.content[0].sourceTradingDate").doesNotExist());

		verify(candleQueryService).getCandles(17L, "1m", null, null, null);
	}

	@Test
	void getCandlesReturnsCommonValidationErrorForUnsupportedIntervalOnCryptoInstrument() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(eq(17L), eq("5m"), isNull(), isNull(), isNull()))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "지원하지 않는 캔들 간격입니다. interval=1m만 지원합니다."));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 17L).param("interval", "5m")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(17L, "5m", null, null, null);
	}

	@Test
	void getCandlesReturnsBadGatewayWhenBithumbCandleProviderFails() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(17L, "1m", null, null, null))
			.thenThrow(new BusinessException(ErrorCode.MARKET_DATA_PROVIDER_ERROR));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 17L).param("interval", "1m")))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error.code").value("MARKET_DATA_PROVIDER_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(17L, "1m", null, null, null);
	}

	@Test
	void getCandlesReturnsEmptyEnvelopeWithOkStatusWhenReplaySessionNotReadyOrNoCandleRevealedYet() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(1L, "1m", null, null, null))
			.thenReturn(CandleListResponse.of(List.of(), null, false));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "1m")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(0));

		verify(candleQueryService).getCandles(1L, "1m", null, null, null);
	}

	@Test
	void getCandlesReturnsNextCursorEqualToOldestContentCandleSourceTimeAsExactString() throws Exception {
		authenticate();
		LocalDateTime oldest = LocalDateTime.of(2026, 7, 22, 9, 0);
		when(candleQueryService.getCandles(1L, "1d", null, null, null)).thenReturn(CandleListResponse.of(
			List.of(new CandleResponse(oldest, BigDecimal.valueOf(71000), BigDecimal.valueOf(71500),
				BigDecimal.valueOf(70900), BigDecimal.valueOf(71200), BigDecimal.valueOf(12345))),
			CandleCursor.encode(oldest), true));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "1d")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hasNext").value(true))
			.andExpect(jsonPath("$.content[0].sourceTime").value("2026-07-22T09:00:00"))
			.andExpect(jsonPath("$.nextCursor").value("2026-07-22T09:00:00"));

		verify(candleQueryService).getCandles(1L, "1d", null, null, null);
	}

	@Test
	void getCandlesReturnsCommonValidationErrorForInvalidCursorFormat() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(1L, "1m", null, null, "not-a-valid-cursor"))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "cursor 형식이 올바르지 않습니다."));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L)
			.param("interval", "1m")
			.param("cursor", "not-a-valid-cursor")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(1L, "1m", null, null, "not-a-valid-cursor");
	}

	@Test
	void getCandlesReturnsCommonValidationErrorForUnsupportedIntervalEvenWithValidCursor() throws Exception {
		authenticate();
		String cursor = "2026-07-22T09:00:00";
		when(candleQueryService.getCandles(eq(1L), eq("5m"), isNull(), isNull(), eq(cursor)))
			.thenThrow(new BusinessException(ErrorCode.VALIDATION_ERROR, "지원하지 않는 캔들 간격입니다."));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L)
			.param("interval", "5m")
			.param("cursor", cursor)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(1L, "5m", null, null, cursor);
	}

	@Test
	void getCandlesReturnsCommonNotFoundErrorFormatForMissingInstrumentEvenWithInvalidCursorFormat() throws Exception {
		authenticate();
		when(candleQueryService.getCandles(999L, "1m", null, null, "not-a-valid-cursor"))
			.thenThrow(new BusinessException(ErrorCode.NOT_FOUND));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 999L)
			.param("interval", "1m")
			.param("cursor", "not-a-valid-cursor")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(999L, "1m", null, null, "not-a-valid-cursor");
	}

	@Test
	void getCandlesReturnsBadGatewayWhenBithumbCandleProviderFailsEvenWithCursor() throws Exception {
		authenticate();
		String cursor = "2026-07-30T11:43:00";
		when(candleQueryService.getCandles(17L, "1m", null, null, cursor))
			.thenThrow(new BusinessException(ErrorCode.MARKET_DATA_PROVIDER_ERROR));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 17L)
			.param("interval", "1m")
			.param("cursor", cursor)))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error.code").value("MARKET_DATA_PROVIDER_ERROR"))
			.andExpect(jsonPath("$.error.message").isNotEmpty())
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verify(candleQueryService).getCandles(17L, "1m", null, null, cursor);
	}

	@Test
	void getCandlesRejectsMissingAuthenticationWithCursorParamWithoutCallingService() throws Exception {
		mockMvc.perform(get("/api/instruments/{instrumentId}/candles", 1L)
			.param("interval", "1m")
			.param("cursor", "2026-07-22T09:00:00"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(candleQueryService);
	}

	@Test
	void getCandlesReturnsOkWithHasNextFalseForStockOneMinuteWithCursor() throws Exception {
		authenticate();
		String cursor = "2026-07-27T09:00:00";
		when(candleQueryService.getCandles(1L, "1m", null, null, cursor)).thenReturn(CandleListResponse.of(
			List.of(new CandleResponse(LocalDateTime.of(2026, 7, 27, 9, 0), BigDecimal.valueOf(70000),
				BigDecimal.valueOf(70500), BigDecimal.valueOf(69900), BigDecimal.valueOf(70200),
				BigDecimal.valueOf(12345))),
			null, false));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L)
			.param("interval", "1m")
			.param("cursor", cursor)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.hasNext").value(false))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());

		verify(candleQueryService).getCandles(1L, "1m", null, null, cursor);
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
				true,
				false))
			.toList();
	}
}
