package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class BithumbRestCandleProviderTest {

	private static final String ENDPOINT = "https://api.bithumb.com/v1/candles/minutes/1";
	private static final String DAY_ENDPOINT = "https://api.bithumb.com/v1/candles/days";
	private static final String WEEK_ENDPOINT = "https://api.bithumb.com/v1/candles/weeks";
	private static final String MONTH_ENDPOINT = "https://api.bithumb.com/v1/candles/months";
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private MockRestServiceServer server;
	private RestClient.Builder builder;

	@BeforeEach
	void setUp() {
		builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
	}

	private BithumbRestCandleProvider providerAt(LocalDateTime nowKst) {
		Clock clock = Clock.fixed(nowKst.atZone(KST).toInstant(), KST);
		return new BithumbRestCandleProvider(builder.build(), clock);
	}

	private static String candleItem(
		String kstTime, String open, String high, String low, String tradePrice, String accVolume,
		String accTradePrice) {
		return """
			{
			  "market": "KRW-BTC",
			  "candle_date_time_utc": "%s",
			  "candle_date_time_kst": "%s",
			  "opening_price": %s,
			  "high_price": %s,
			  "low_price": %s,
			  "trade_price": %s,
			  "timestamp": 1753842180000,
			  "candle_acc_trade_price": %s,
			  "candle_acc_trade_volume": %s,
			  "unit": 1
			}
			""".formatted(kstTime, kstTime, open, high, low, tradePrice, accTradePrice, accVolume);
	}


	@Test
	void getCandlesConvertsInstrumentSymbolToKrwMarketCode() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(ENDPOINT)))
			.andExpect(method(HttpMethod.GET))
			.andExpect(queryParam("market", "KRW-BTC"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null);

		server.verify();
	}


	@Test
	void getCandlesReversesBithumbDescendingResponseToAscendingOrder() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		String body = "[" + candleItem("2026-07-30T11:43:00", "100", "110", "90", "105", "1", "1000")
			+ "," + candleItem("2026-07-30T11:42:00", "95", "100", "85", "100", "1", "1000")
			+ "," + candleItem("2026-07-30T11:41:00", "90", "95", "80", "95", "1", "1000")
			+ "]";
		server.expect(requestTo(startsWith(ENDPOINT))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null);

		assertThat(result).extracting(CryptoCandleDto::sourceTime)
			.containsExactly(
				LocalDateTime.of(2026, 7, 30, 11, 41),
				LocalDateTime.of(2026, 7, 30, 11, 42),
				LocalDateTime.of(2026, 7, 30, 11, 43));
	}

	@Test
	void getCandlesIncludesTheMostRecentInProgressCandleUnlikeStock() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43, 6));
		String body = "[" + candleItem("2026-07-30T11:43:00", "100", "110", "90", "105", "1", "1000")
			+ "," + candleItem("2026-07-30T11:42:00", "95", "100", "85", "100", "1", "1000")
			+ "]";
		server.expect(requestTo(startsWith(ENDPOINT))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null);

		assertThat(result).hasSize(2);
		assertThat(result.get(result.size() - 1).sourceTime()).isEqualTo(LocalDateTime.of(2026, 7, 30, 11, 43));
	}


	@Test
	void getCandlesMapsFieldsWithoutConfusingVolumeAndTradeAmount() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		String body = "[" + candleItem(
			"2026-07-30T11:43:00", "95000000", "95100000", "94900000", "95050000",
			"0.12345678", "12345678901.23") + "]";
		server.expect(requestTo(startsWith(ENDPOINT))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		CryptoCandleDto candle = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null).get(0);

		assertThat(candle.open()).isEqualByComparingTo("95000000");
		assertThat(candle.high()).isEqualByComparingTo("95100000");
		assertThat(candle.low()).isEqualByComparingTo("94900000");
		assertThat(candle.close()).isEqualByComparingTo("95050000");
		assertThat(candle.volume()).isEqualByComparingTo("0.12345678");
		assertThat(candle.volume()).isNotEqualByComparingTo("12345678901.23");
	}

	@Test
	void getCandlesPreservesFractionalVolumeWithoutTruncation() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		String body = "[" + candleItem(
			"2026-07-30T11:43:00", "100", "110", "90", "105", "0.26725783", "1000") + "]";
		server.expect(requestTo(startsWith(ENDPOINT))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		CryptoCandleDto candle = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null).get(0);

		assertThat(candle.volume()).isEqualByComparingTo("0.26725783");
	}


	@Test
	void getCandlesSendsCountTwoHundredWithoutToParamWhenFromAndToAreBothOmitted() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(ENDPOINT)))
			.andExpect(queryParam("count", "200"))
			.andExpect(noToParam())
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null);

		server.verify();
	}

	@Test
	void getCandlesSendsCountTwoHundredWithGivenToParamWhenOnlyToProvided() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		LocalDateTime to = LocalDateTime.of(2026, 7, 30, 9, 30);

		server.expect(requestTo(startsWith(ENDPOINT)))
			.andExpect(queryParam("count", "200"))
			.andExpect(queryParam("to", "2026-07-30T09:30:01"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, to);

		server.verify();
	}

	@Test
	void getCandlesSendsCountBasedOnFromToNowWhenOnlyFromProvided() {
		LocalDateTime now = LocalDateTime.of(2026, 7, 30, 11, 0);
		BithumbRestCandleProvider provider = providerAt(now);
		LocalDateTime from = now.minusMinutes(50);

		server.expect(requestTo(startsWith(ENDPOINT)))
			.andExpect(queryParam("count", "51"))
			.andExpect(noToParam())
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, null);

		server.verify();
	}

	@Test
	void getCandlesSendsCountBasedOnFromToToRangeWhenBothProvided() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		LocalDateTime from = LocalDateTime.of(2026, 7, 30, 9, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 30, 9, 10);

		server.expect(requestTo(startsWith(ENDPOINT)))
			.andExpect(queryParam("count", "11"))
			.andExpect(queryParam("to", "2026-07-30T09:10:01"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		server.verify();
	}

	@Test
	void getCandlesCapsCountAtTwoHundredWhenRangeExceedsTwoHundredMinutes() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		LocalDateTime from = LocalDateTime.of(2026, 7, 30, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 30, 9, 0);

		server.expect(requestTo(startsWith(ENDPOINT)))
			.andExpect(queryParam("count", "200"))
			.andExpect(queryParam("to", "2026-07-30T09:00:01"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		server.verify();
	}


	@Test
	void getCandlesCallsDaysEndpointForOneDayInterval() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(DAY_ENDPOINT)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_DAY, null, null);

		server.verify();
	}

	@Test
	void getCandlesCallsWeeksEndpointForOneWeekInterval() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(WEEK_ENDPOINT)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_WEEK, null, null);

		server.verify();
	}

	@Test
	void getCandlesCallsMonthsEndpointForOneMonthInterval() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(MONTH_ENDPOINT)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_MONTH, null, null);

		server.verify();
	}


	@Test
	void getCandlesComputesWeekCountByAligningBothEndsToMondayAcrossWeekBoundary() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		LocalDateTime from = LocalDateTime.of(2026, 7, 29, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 8, 5, 0, 0);

		server.expect(requestTo(startsWith(WEEK_ENDPOINT)))
			.andExpect(queryParam("count", "2"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_WEEK, from, to);

		server.verify();
	}

	@Test
	void getCandlesComputesWeekCountAsOneWhenBothEndsFallInSameIsoWeek() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		LocalDateTime from = LocalDateTime.of(2026, 7, 27, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 8, 2, 0, 0);

		server.expect(requestTo(startsWith(WEEK_ENDPOINT)))
			.andExpect(queryParam("count", "1"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_WEEK, from, to);

		server.verify();
	}

	@Test
	void getCandlesComputesMonthCountByAligningBothEndsToFirstDayAcrossMonthBoundary() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		LocalDateTime from = LocalDateTime.of(2026, 7, 15, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 9, 3, 0, 0);

		server.expect(requestTo(startsWith(MONTH_ENDPOINT)))
			.andExpect(queryParam("count", "3"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_MONTH, from, to);

		server.verify();
	}

	@Test
	void getCandlesCapsWeekCountAtTwoHundredWhenRangeExceedsTwoHundredWeeks() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		LocalDateTime from = LocalDateTime.of(2020, 1, 1, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 30, 0, 0);

		server.expect(requestTo(startsWith(WEEK_ENDPOINT)))
			.andExpect(queryParam("count", "200"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_WEEK, from, to);

		server.verify();
	}

	@Test
	void getCandlesCapsMonthCountAtTwoHundredWhenRangeExceedsTwoHundredMonths() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		LocalDateTime from = LocalDateTime.of(2005, 1, 1, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 30, 0, 0);

		server.expect(requestTo(startsWith(MONTH_ENDPOINT)))
			.andExpect(queryParam("count", "200"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_MONTH, from, to);

		server.verify();
	}

	@Test
	void getCandlesSendsCountTwoHundredForDayIntervalWithoutFromAndTo() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(DAY_ENDPOINT)))
			.andExpect(queryParam("count", "200"))
			.andExpect(noToParam())
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_DAY, null, null);

		server.verify();
	}


	@Test
	void getCandlesShiftsToParamByOneSecondForDayIntervalToIncludeTodaysBoundaryCandle() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 8, 3, 11, 43));
		LocalDateTime to = LocalDateTime.of(2026, 8, 3, 0, 0);

		server.expect(requestTo(startsWith(DAY_ENDPOINT)))
			.andExpect(queryParam("to", "2026-08-03T00:00:01"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_DAY, null, to);

		server.verify();
	}

	@Test
	void getCandlesShiftsToParamByOneSecondForWeekInterval() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 8, 3, 11, 43));
		LocalDateTime to = LocalDateTime.of(2026, 8, 3, 0, 0);

		server.expect(requestTo(startsWith(WEEK_ENDPOINT)))
			.andExpect(queryParam("to", "2026-08-03T00:00:01"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_WEEK, null, to);

		server.verify();
	}

	@Test
	void getCandlesShiftsToParamByOneSecondForMonthInterval() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 8, 3, 11, 43));
		LocalDateTime to = LocalDateTime.of(2026, 8, 3, 0, 0);

		server.expect(requestTo(startsWith(MONTH_ENDPOINT)))
			.andExpect(queryParam("to", "2026-08-03T00:00:01"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_MONTH, null, to);

		server.verify();
	}


	@Test
	void getCandlesParsesDayCandleIgnoringPeriodOnlyFieldsWithoutExposingThem() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		String body = """
			[{
			  "market": "KRW-BTC",
			  "candle_date_time_utc": "2026-07-29T15:00:00",
			  "candle_date_time_kst": "2026-07-30T00:00:00",
			  "opening_price": 100,
			  "high_price": 120,
			  "low_price": 90,
			  "trade_price": 110,
			  "timestamp": 1753842180000,
			  "candle_acc_trade_price": 500000,
			  "candle_acc_trade_volume": 4.5,
			  "prev_closing_price": 95,
			  "change_price": 15,
			  "change_rate": 0.157,
			  "first_day_of_period": "2026-07-30"
			}]
			""";
		server.expect(requestTo(startsWith(DAY_ENDPOINT))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		CryptoCandleDto candle = provider.getCandles("BTC", CandleInterval.ONE_DAY, null, null).get(0);

		assertThat(candle.open()).isEqualByComparingTo("100");
		assertThat(candle.high()).isEqualByComparingTo("120");
		assertThat(candle.low()).isEqualByComparingTo("90");
		assertThat(candle.close()).isEqualByComparingTo("110");
		assertThat(candle.volume()).isEqualByComparingTo("4.5");
		assertThat(candle.sourceTime()).isEqualTo(LocalDateTime.of(2026, 7, 30, 0, 0));
	}


	@Test
	void getCandlesAlwaysRefetchesFromHttpAcrossRepeatedCallsInsteadOfCaching() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(DAY_ENDPOINT))).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
		server.expect(requestTo(startsWith(DAY_ENDPOINT))).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_DAY, null, null);
		provider.getCandles("BTC", CandleInterval.ONE_DAY, null, null);

		server.verify();
	}


	@Test
	void getCandlesThrowsProviderErrorOnConnectionFailureInsteadOfReturningEmptyList() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(ENDPOINT)))
			.andRespond(request -> {
				throw new IOException("connection timed out");
			});

		assertThatThrownBy(() -> provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));
	}

	@Test
	void getCandlesThrowsProviderErrorOnNonSuccessStatusInsteadOfReturningEmptyList() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(ENDPOINT))).andRespond(withServerError());

		assertThatThrownBy(() -> provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));
	}

	@Test
	void getCandlesThrowsProviderErrorOnNonJsonBadRequestStatus() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(ENDPOINT)))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).body("bad request"));

		assertThatThrownBy(() -> provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));
	}

	@Test
	void getCandlesThrowsProviderErrorOnMalformedJsonInsteadOfReturningEmptyList() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(ENDPOINT)))
			.andRespond(withSuccess("{malformed", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));
	}

	@Test
	void getCandlesThrowsProviderErrorWhenRequiredFieldIsMissing() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		String bodyMissingTradePrice = """
			[{
			  "market": "KRW-BTC",
			  "candle_date_time_utc": "2026-07-30T02:43:00",
			  "candle_date_time_kst": "2026-07-30T11:43:00",
			  "opening_price": 100,
			  "high_price": 110,
			  "low_price": 90,
			  "candle_acc_trade_price": 1000,
			  "candle_acc_trade_volume": 1,
			  "unit": 1
			}]
			""";
		server.expect(requestTo(startsWith(ENDPOINT)))
			.andRespond(withSuccess(bodyMissingTradePrice, MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));
	}

	@Test
	void getCandlesThrowsProviderErrorWhenBodyIsNull() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(ENDPOINT)))
			.andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));
	}

	@Test
	void getCandlesThrowsProviderErrorOnNonSuccessStatusForWeekIntervalInsteadOfReturningEmptyList() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(WEEK_ENDPOINT))).andRespond(withServerError());

		assertThatThrownBy(() -> provider.getCandles("BTC", CandleInterval.ONE_WEEK, null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));
	}

	private static org.springframework.test.web.client.RequestMatcher noToParam() {
		return request -> assertThat(request.getURI().getQuery()).doesNotContain("to=");
	}
}
