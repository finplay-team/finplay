package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.finplay.api.domain.market.config.KisProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class KisDailyCandleClientImplTest {

	private static final String BASE_URL = "https://mock-kis.example";
	private static final String CANDLE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
	private static final String TOKEN_PATH = "/oauth2/tokenP";
	private static final String SYMBOL = "005930";
	private static final String APP_KEY = "test-app-key";
	private static final String APP_SECRET = "test-app-secret";
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
	private static final String RATE_LIMIT_BODY = "{\"rt_cd\":\"1\",\"msg1\":\"초당 거래건수를 초과하였습니다.\",\"msg_cd\":\"EGW00201\"}";
	private static final String DOMAIN_MISMATCH_BODY = "{\"rt_cd\":\"1\",\"msg1\":\"실전투자 도메인은 모의투자 앱키로 호출하실 수 없습니다.\",\"msg_cd\":\"EGW02004\"}";

	private static final String FAR_FUTURE_EXPIRY = "2099-01-01 00:00:00";

	private Clock clock;

	@BeforeEach
	void setUp() {
		clock = Clock.fixed(Instant.parse("2026-08-20T08:00:00Z"), ZoneOffset.UTC);
	}

	private RestClient.Builder newBuilder() {
		return RestClient.builder();
	}

	private KisDailyCandleClientImpl newClient(RestClient.Builder builder) {
		return new KisDailyCandleClientImpl(
			builder.build(), clock, new KisProperties(BASE_URL, APP_KEY, APP_SECRET, 0L));
	}

	private void expectTokenExchange(MockRestServiceServer server) {
		server.expect(requestTo(BASE_URL + TOKEN_PATH))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess(
				"""
					{"access_token":"test-access-token","access_token_token_expired":"%s"}
					""".formatted(FAR_FUTURE_EXPIRY),
				MediaType.APPLICATION_JSON));
	}

	private static String dailyCandleUri(LocalDate from, LocalDate cursorEnd) {
		return BASE_URL + CANDLE_PATH
			+ "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + SYMBOL
			+ "&FID_INPUT_DATE_1=" + from.format(DATE_FORMAT)
			+ "&FID_INPUT_DATE_2=" + cursorEnd.format(DATE_FORMAT)
			+ "&FID_PERIOD_DIV_CODE=D&FID_ORG_ADJ_PRC=0";
	}

	private static String dailyRow(
		LocalDate date, String open, String high, String low, String close, String volume) {
		return """
			{"stck_bsop_date":"%s","stck_oprc":"%s","stck_hgpr":"%s","stck_lwpr":"%s","stck_clpr":"%s","acml_vol":"%s"}
			""".formatted(date.format(DATE_FORMAT), open, high, low, close, volume);
	}

	private static String dailyPageJson(String... rows) {
		return "{\"output2\":[" + String.join(",", rows) + "]}";
	}

	@Test
	void fetchDailyCandlesMapsOutput2FieldsAndAlwaysRequestsAdjustedPrice() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 20);

		expectTokenExchange(server);
		server.expect(requestTo(dailyCandleUri(from, to)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				dailyPageJson(dailyRow(from, "71000", "71500", "70900", "71200", "123456")),
				MediaType.APPLICATION_JSON));

		List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, to);

		assertThat(candles).hasSize(1);
		RawDailyCandleDto candle = candles.get(0);
		assertThat(candle.tradingDate()).isEqualTo(from);
		assertThat(candle.open()).isEqualByComparingTo("71000");
		assertThat(candle.high()).isEqualByComparingTo("71500");
		assertThat(candle.low()).isEqualByComparingTo("70900");
		assertThat(candle.close()).isEqualByComparingTo("71200");
		assertThat(candle.volume()).isEqualTo(123456L);
		server.verify();
	}

	@Test
	void fetchDailyCandlesStitchesMultiplePagesUntilReachingFromDate() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 24);
		LocalDate day20 = LocalDate.of(2026, 7, 20);
		LocalDate day21 = LocalDate.of(2026, 7, 21);
		LocalDate day22 = LocalDate.of(2026, 7, 22);
		LocalDate day23 = LocalDate.of(2026, 7, 23);
		LocalDate day24 = LocalDate.of(2026, 7, 24);

		expectTokenExchange(server);
		server.expect(requestTo(dailyCandleUri(from, day24)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				dailyPageJson(
					dailyRow(day24, "72000", "72500", "71900", "72200", "100"),
					dailyRow(day23, "71500", "72000", "71400", "71800", "100"),
					dailyRow(day22, "71000", "71500", "70900", "71300", "100")),
				MediaType.APPLICATION_JSON));
		server.expect(requestTo(dailyCandleUri(from, day21)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				dailyPageJson(
					dailyRow(day21, "70800", "71200", "70700", "71000", "100"),
					dailyRow(day20, "70500", "70900", "70400", "70800", "100")),
				MediaType.APPLICATION_JSON));

		List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, to);

		assertThat(candles).hasSize(5);
		assertThat(candles).extracting(RawDailyCandleDto::tradingDate)
			.containsExactly(day20, day21, day22, day23, day24);
		server.verify();
	}

	@Test
	void fetchDailyCandlesStopsRequestingFurtherPagesWhenAPageReturnsEmptyOutput2() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2026, 1, 1);
		LocalDate to = LocalDate.of(2026, 7, 24);

		expectTokenExchange(server);
		server.expect(requestTo(dailyCandleUri(from, to)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"output2\":[]}", MediaType.APPLICATION_JSON));

		List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, to);

		assertThat(candles).isEmpty();
		server.verify();
	}

	@Test
	void fetchDailyCandlesStopsAfterTwelvePagesEvenWhenFromDateIsNotYetReached() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2000, 1, 1);
		LocalDate to = LocalDate.of(2026, 8, 20);

		expectTokenExchange(server);
		for (int page = 0; page < 12; page++) {
			LocalDate cursorEnd = to.minusDays(page);
			server.expect(requestTo(dailyCandleUri(from, cursorEnd)))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(
					dailyPageJson(dailyRow(cursorEnd, "71000", "71500", "70900", "71200", "100")),
					MediaType.APPLICATION_JSON));
		}

		List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, to);

		assertThat(candles).hasSize(12);
		assertThat(candles.get(0).tradingDate()).isEqualTo(to.minusDays(11));
		assertThat(candles.get(candles.size() - 1).tradingDate()).isEqualTo(to);
		server.verify();
	}

	@Test
	void fetchDailyCandlesLogsPageLimitWarningOnlyWhenAllTwelvePagesAreExhausted() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2000, 1, 1);
		LocalDate to = LocalDate.of(2026, 8, 20);

		expectTokenExchange(server);
		for (int page = 0; page < 12; page++) {
			LocalDate cursorEnd = to.minusDays(page);
			server.expect(requestTo(dailyCandleUri(from, cursorEnd)))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(
					dailyPageJson(dailyRow(cursorEnd, "71000", "71500", "70900", "71200", "100")),
					MediaType.APPLICATION_JSON));
		}

		List<ILoggingEvent> logs = capturingLogs(() -> client.fetchDailyCandles(SYMBOL, from, to));

		assertThat(logs)
			.anyMatch(event -> event.getFormattedMessage().contains("KIS 일봉 페이지 상한(12)에 도달"));
		server.verify();
	}

	@Test
	void fetchDailyCandlesDoesNotLogPageLimitWarningWhenTerminatedNaturallyByEmptyPageAfterPartialData() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2000, 1, 1);
		LocalDate day1 = LocalDate.of(2026, 8, 19);
		LocalDate day2 = LocalDate.of(2026, 8, 20);

		expectTokenExchange(server);
		server.expect(requestTo(dailyCandleUri(from, day2)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				dailyPageJson(
					dailyRow(day2, "71000", "71500", "70900", "71200", "100"),
					dailyRow(day1, "70500", "70900", "70400", "70800", "100")),
				MediaType.APPLICATION_JSON));
		server.expect(requestTo(dailyCandleUri(from, day1.minusDays(1))))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"output2\":[]}", MediaType.APPLICATION_JSON));

		List<ILoggingEvent> logs = capturingLogs(() -> {
			List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, day2);
			assertThat(candles).hasSize(2);
		});

		assertThat(logs)
			.noneMatch(event -> event.getFormattedMessage().contains("페이지 상한"));
		server.verify();
	}

	private static List<ILoggingEvent> capturingLogs(Runnable action) {
		Logger logger = (Logger)LoggerFactory.getLogger(KisDailyCandleClientImpl.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level originalLevel = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		try {
			action.run();
			return List.copyOf(appender.list);
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(originalLevel);
			appender.stop();
		}
	}

	@Test
	void fetchDailyCandlesDiscardsRowWithNonPositivePriceButKeepsValidRowInSamePage() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 21);

		expectTokenExchange(server);
		server.expect(requestTo(dailyCandleUri(from, to)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				dailyPageJson(
					dailyRow(from, "71000", "71500", "70900", "71200", "100"),
					dailyRow(to, "0", "71500", "70900", "71200", "100")),
				MediaType.APPLICATION_JSON));

		List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, to);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).tradingDate()).isEqualTo(from);
		server.verify();
	}

	@Test
	void fetchDailyCandlesDiscardsRowWhereLowExceedsOpenOrClose() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 21);

		expectTokenExchange(server);
		server.expect(requestTo(dailyCandleUri(from, to)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				dailyPageJson(
					dailyRow(from, "71000", "71500", "70900", "71200", "100"),
					dailyRow(to, "71000", "73000", "72000", "71500", "100")),
				MediaType.APPLICATION_JSON));

		List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, to);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).tradingDate()).isEqualTo(from);
		server.verify();
	}

	@Test
	void fetchDailyCandlesDiscardsRowWhereHighIsBelowOpenOrClose() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 21);

		expectTokenExchange(server);
		server.expect(requestTo(dailyCandleUri(from, to)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				dailyPageJson(
					dailyRow(from, "71000", "71500", "70900", "71200", "100"),
					dailyRow(to, "71000", "71000", "70000", "71500", "100")),
				MediaType.APPLICATION_JSON));

		List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, to);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).tradingDate()).isEqualTo(from);
		server.verify();
	}

	@Test
	void fetchDailyCandlesDiscardsRowWithNegativeVolume() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 21);

		expectTokenExchange(server);
		server.expect(requestTo(dailyCandleUri(from, to)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				dailyPageJson(
					dailyRow(from, "71000", "71500", "70900", "71200", "100"),
					dailyRow(to, "71000", "71500", "70900", "71200", "-5")),
				MediaType.APPLICATION_JSON));

		List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, to);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).tradingDate()).isEqualTo(from);
		server.verify();
	}

	@Test
	void fetchDailyCandlesDiscardsRowWithTradingDateOutsideRequestedRange() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 21);
		LocalDate beforeFrom = LocalDate.of(2026, 7, 10);

		expectTokenExchange(server);
		server.expect(requestTo(dailyCandleUri(from, to)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				dailyPageJson(
					dailyRow(from, "71000", "71500", "70900", "71200", "100"),
					dailyRow(beforeFrom, "70000", "70500", "69900", "70200", "100")),
				MediaType.APPLICATION_JSON));

		List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, to);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).tradingDate()).isEqualTo(from);
		server.verify();
	}

	@Test
	void fetchDailyCandlesDiscardsUnparsableRow() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 21);

		expectTokenExchange(server);
		server.expect(requestTo(dailyCandleUri(from, to)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				dailyPageJson(
					dailyRow(from, "71000", "71500", "70900", "71200", "100"),
					dailyRow(to, "abc", "71500", "70900", "71200", "100")),
				MediaType.APPLICATION_JSON));

		List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, to);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).tradingDate()).isEqualTo(from);
		server.verify();
	}

	@Test
	void fetchDailyCandlesRetriesWhenKisRejectsWithPerSecondRateLimit() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 20);

		expectTokenExchange(server);
		server.expect(requestTo(dailyCandleUri(from, to)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(RATE_LIMIT_BODY)
				.contentType(MediaType.APPLICATION_JSON));
		server.expect(requestTo(dailyCandleUri(from, to)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				dailyPageJson(dailyRow(from, "71000", "71500", "70900", "71200", "123456")),
				MediaType.APPLICATION_JSON));

		List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, to);

		assertThat(candles).hasSize(1);
		server.verify();
	}

	@Test
	void fetchDailyCandlesDoesNotRetryNonRateLimitErrors() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 20);

		expectTokenExchange(server);
		server.expect(requestTo(dailyCandleUri(from, to)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(DOMAIN_MISMATCH_BODY)
				.contentType(MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.fetchDailyCandles(SYMBOL, from, to))
			.isInstanceOf(RestClientResponseException.class);
		server.verify();
	}
}
