// 빗썸 공개 캔들 REST 응답의 정렬 반전·필드 매핑·count 산출·장애 정규화를 Mock HTTP로 검증한다 (MKT-008, 이슈 #20)
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
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

	// --- 심볼 변환 ---

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

	// --- 정렬 반전 + 진행 중 분봉 포함(주식과 반대) ---

	@Test
	void getCandlesReversesBithumbDescendingResponseToAscendingOrder() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		// 빗썸은 최신→과거로 내려준다: 11:43(가장 최신), 11:42, 11:41 순.
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
		// 코인은 주식과 반대로 진행 중(아직 마감하지 않은) 분봉을 걸러내지 않는다 — 빗썸이 돌려준 가장 최신 항목이
		// 그대로 결과에 남아 있어야 한다(StockReplayService의 공개 컷오프 로직을 재사용하지 않음).
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43, 6));
		String body = "[" + candleItem("2026-07-30T11:43:00", "100", "110", "90", "105", "1", "1000")
			+ "," + candleItem("2026-07-30T11:42:00", "95", "100", "85", "100", "1", "1000")
			+ "]";
		server.expect(requestTo(startsWith(ENDPOINT))).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		List<CryptoCandleDto> result = provider.getCandles("BTC", CandleInterval.ONE_MINUTE, null, null);

		assertThat(result).hasSize(2);
		assertThat(result.get(result.size() - 1).sourceTime()).isEqualTo(LocalDateTime.of(2026, 7, 30, 11, 43));
	}

	// --- 필드 매핑: 거래대금(candle_acc_trade_price)과 수량(candle_acc_trade_volume) 혼동 없음 ---

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
		// trade_price(종가) - 이름에 속아 현재가로 해석하지 않는다.
		assertThat(candle.close()).isEqualByComparingTo("95050000");
		// candle_acc_trade_volume(수량)이 volume이어야 한다 — candle_acc_trade_price(거래대금)와 섞이면 안 된다.
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

	// --- from·to → to+count 변환 ---

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
			// KST 09:30 -> UTC 00:30, 빗썸의 to 경계 배제(exclusive)를 상쇄하기 위해 +1초
			.andExpect(queryParam("to", "2026-07-30T00:30:01"))
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
			// now~from 50분 + 1(양 끝 포함) = 51
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
			// from~to 10분 + 1 = 11
			.andExpect(queryParam("count", "11"))
			// 경계 배제 상쇄를 위한 +1초
			.andExpect(queryParam("to", "2026-07-30T00:10:01"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		server.verify();
	}

	@Test
	void getCandlesCapsCountAtTwoHundredWhenRangeExceedsTwoHundredMinutes() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		LocalDateTime from = LocalDateTime.of(2026, 7, 30, 0, 0);
		LocalDateTime to = LocalDateTime.of(2026, 7, 30, 9, 0);
		// from~to = 540분 + 1 = 541분 -> 200분 초과이므로 200으로 캡

		server.expect(requestTo(startsWith(ENDPOINT)))
			.andExpect(queryParam("count", "200"))
			// 경계 배제 상쇄를 위한 +1초
			.andExpect(queryParam("to", "2026-07-30T00:00:01"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_MINUTE, from, to);

		server.verify();
	}

	// --- interval별 엔드포인트 분기 ---

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

	// --- count 산출: 주·월봉은 양 끝을 월요일·1일로 정렬 후 단위 차이 + 1 ---

	@Test
	void getCandlesComputesWeekCountByAligningBothEndsToMondayAcrossWeekBoundary() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		// from=수(2026-07-29, 그 주 월요일 2026-07-27) ~ to=수(2026-08-05, 그 주 월요일 2026-08-03)
		// 월요일 정렬 기준으로 1주 차이 -> count = 1 + 1 = 2
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
		// 2026-07-27(월) ~ 2026-08-02(일)은 같은 ISO 주 -> count = 0 + 1 = 1
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
		// from=2026-07-15(1일 정렬: 2026-07-01) ~ to=2026-09-03(1일 정렬: 2026-09-01) -> 2개월 차이 -> count = 2 + 1 = 3
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
		// 2020-01-01 ~ 2026-07-30 : 200주(약 3.8년)를 훌쩍 넘는 범위
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
		// 2005-01-01 ~ 2026-07-30 : 200개월(약 16.7년)을 훌쩍 넘는 범위
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

	// --- to 경계 포함(inclusive) 보정: 빗썸은 to와 정확히 같은 시각의 봉을 배제한다(이슈 #157) ---

	@Test
	void getCandlesShiftsToParamByOneSecondForDayIntervalToIncludeTodaysBoundaryCandle() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 8, 3, 11, 43));
		// to=2026-08-03T00:00:00(오늘 일봉의 시작 시각과 정확히 같음)을 그대로 보내면 빗썸이 오늘 봉을 배제한다.
		// resolveToParam이 +1초를 더해 "2026-08-02T15:00:01"(UTC)로 보내야 오늘 봉이 응답에 남는다.
		LocalDateTime to = LocalDateTime.of(2026, 8, 3, 0, 0);

		server.expect(requestTo(startsWith(DAY_ENDPOINT)))
			// KST 2026-08-03T00:00:00 -> UTC 2026-08-02T15:00:00, +1초 보정
			.andExpect(queryParam("to", "2026-08-02T15:00:01"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_DAY, null, to);

		server.verify();
	}

	@Test
	void getCandlesShiftsToParamByOneSecondForWeekInterval() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 8, 3, 11, 43));
		LocalDateTime to = LocalDateTime.of(2026, 8, 3, 0, 0);

		server.expect(requestTo(startsWith(WEEK_ENDPOINT)))
			.andExpect(queryParam("to", "2026-08-02T15:00:01"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_WEEK, null, to);

		server.verify();
	}

	@Test
	void getCandlesShiftsToParamByOneSecondForMonthInterval() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 8, 3, 11, 43));
		LocalDateTime to = LocalDateTime.of(2026, 8, 3, 0, 0);

		server.expect(requestTo(startsWith(MONTH_ENDPOINT)))
			.andExpect(queryParam("to", "2026-08-02T15:00:01"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_MONTH, null, to);

		server.verify();
	}

	// --- 일/주/월봉 전용 필드가 섞여도 파싱이 깨지지 않고, 그 필드들은 응답에 노출되지 않는다 ---

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
		// candle_date_time_kst를 보정 없이 그대로 sourceTime으로 매핑한다 (일봉 경계를 서버가 재계산하지 않음).
		assertThat(candle.sourceTime()).isEqualTo(LocalDateTime.of(2026, 7, 30, 0, 0));
	}

	// --- 저장·캐시 없음: 동일 호출을 반복해도 항상 HTTP로 새로 조회한다 ---

	@Test
	void getCandlesAlwaysRefetchesFromHttpAcrossRepeatedCallsInsteadOfCaching() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(DAY_ENDPOINT))).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
		server.expect(requestTo(startsWith(DAY_ENDPOINT))).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", CandleInterval.ONE_DAY, null, null);
		provider.getCandles("BTC", CandleInterval.ONE_DAY, null, null);

		server.verify();
	}

	// --- 장애 처리: 빈 배열 200으로 위장하지 않고 502 MARKET_DATA_PROVIDER_ERROR ---

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
