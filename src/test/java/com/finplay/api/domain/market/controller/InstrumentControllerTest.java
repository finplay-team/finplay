// 종목 목록 조회 API의 인증, 시장 필터링, 검증 실패, 응답 DTO 계약을 검증하는 WebMvc 슬라이스 테스트다.
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
	void getPriceReturnsAvailableStatusWithLastKnownPriceForCryptoInstrumentWhenObservationIsHoursOld()
		throws Exception {
		// 036-remove-crypto-stale-status: 연결이 살아있고 수신 이력이 있으면, 관측 시각이 몇 시간 지나도
		// (과거 032 시절엔 STALE) 여전히 409가 아니라 200 + status=AVAILABLE이다.
		authenticate();
		LocalDateTime sourceTime = LocalDateTime.of(2026, 7, 28, 6, 30, 0);
		when(priceQueryService.getPrice(17L)).thenReturn(
			new PriceQuoteDto(BigDecimal.valueOf(95000000), sourceTime, PriceStatus.AVAILABLE, null));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/price", 17L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.price").value(95000000))
			.andExpect(jsonPath("$.sourceTime").value("2026-07-28T06:30:00"))
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
		// 이슈 #143(013) 1단계: 1d는 더 이상 400이 아니다. 048: 응답은 항상 봉투(content/nextCursor/hasNext)다.
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
		// "1M"(월봉)이 "1m"(분봉)과 구분되는 별개의 유효 interval로 처리됨을 API 계약 수준에서 고정한다.
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
		// "1D"는 spec상 여전히 400이어야 한다(대소문자 미정규화) — 서비스가 던진 VALIDATION_ERROR를 그대로 매핑하는지 확인한다.
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
	void getCandlesRejectsMissingAuthenticationForOneDayIntervalWithoutCallingService() throws Exception {
		// 401 유지 회귀: 새 interval 값에서도 인증 실패가 여전히 서비스 호출 전에 막혀야 한다.
		mockMvc.perform(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "1d"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.error.requestId").isNotEmpty());

		verifyNoInteractions(candleQueryService);
	}

	@Test
	void getCandlesReturnsCommonValidationErrorWhenIntervalParamIsMissing() throws Exception {
		// interval은 필수 쿼리 파라미터다 — 누락 시 MissingServletRequestParameterException을
		// GlobalExceptionHandler가 400 VALIDATION_ERROR로 매핑하는지 고정한다.
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
		// from이 ISO-8601 LocalDateTime 형식이 아니면 MethodArgumentTypeMismatchException을
		// GlobalExceptionHandler가 400 VALIDATION_ERROR로 매핑하는지 고정한다.
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
		// 회귀 고정(MKT-008, 이슈 #20): 예전에는 코인 instrumentId를 서비스에 묻기도 전에 400으로 거부했다.
		// 이제는 서비스에 위임하고 서비스가 반환한 대로 응답한다 — 여기서는 정상 200 코인 캔들 계약을 검증한다.
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
			// 코인 volume은 소수 수량이다 — long이었다면 0으로 잘렸을 값이 그대로 노출돼야 한다.
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
		// MKT-008: 빗썸 캔들 조회 실패(타임아웃·비정상 상태코드·파싱 불가)는 502 MARKET_DATA_PROVIDER_ERROR다 —
		// 빈 배열 200으로 성공을 위장하지 않는다. 048: 커서 도입으로 새 오류 코드가 생기지 않는다(CANDLE-PAGE-011).
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
		// 재생세션 미준비·아직 공개된 분봉이 없는 경우에도 가격 API(409 PRICE_UNAVAILABLE)와 달리 예외 없이 200 + 빈 봉투여야 한다.
		authenticate();
		when(candleQueryService.getCandles(1L, "1m", null, null, null))
			.thenReturn(CandleListResponse.of(List.of(), null, false));

		mockMvc.perform(authorized(get("/api/instruments/{instrumentId}/candles", 1L).param("interval", "1m")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(0));

		verify(candleQueryService).getCandles(1L, "1m", null, null, null);
	}

	// =====================================================================================
	// 커서 페이지네이션(048, CANDLE-PAGE-001~012·025·026) — @WebMvcTest 계약 검증
	// =====================================================================================

	@Test
	void getCandlesReturnsNextCursorEqualToOldestContentCandleSourceTimeAsExactString() throws Exception {
		// nextCursor는 content의 가장 오래된 봉(content[0])의 sourceTime과 정확히 같은 문자열이어야 한다(CANDLE-PAGE-001).
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
		// CANDLE-PAGE-009: ISO-8601 LocalDateTime으로 파싱되지 않는 cursor는 400 VALIDATION_ERROR다.
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
		// 검증 순서(CANDLE-PAGE-010): 잘못된 interval은 커서가 유효해도 여전히 400이다.
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
		// 검증 순서(CANDLE-PAGE-010): 없는 종목은 종목 존재(404) 판정이 커서 형식 검증(400)보다 먼저이므로 여전히 404다.
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
		// 코인 외부 조회 실패는 커서 유무와 무관하게 502다 — 커서 도입이 새 오류 경로를 만들지 않는다.
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
		// 인증 실패는 커서 유무와 무관하게 여전히 401이고 서비스는 호출되지 않는다.
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
		// CANDLE-PAGE-026: 주식 1m은 cursor를 받아도(400이 아니다) 항상 hasNext=false·nextCursor=null이다.
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
