package com.finplay.api.domain.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finplay.api.domain.market.config.KisProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class KisHistoricalCandleClientImplTest {

	private static final String BASE_URL = "https://mock-kis.example";
	private static final String CANDLE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice";
	private static final String TOKEN_PATH = "/oauth2/tokenP";
	private static final String SYMBOL = "005930";
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 22);
	private static final String APP_KEY = "test-app-key";
	private static final String APP_SECRET = "test-app-secret";
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss");

	private static final String FAR_FUTURE_EXPIRY = "2099-01-01 00:00:00";

	private static final String RATE_LIMIT_BODY = "{\"rt_cd\":\"1\",\"msg1\":\"초당 거래건수를 초과하였습니다.\",\"msg_cd\":\"EGW00201\"}";
	private static final String DOMAIN_MISMATCH_BODY = "{\"rt_cd\":\"1\",\"msg1\":\"실전투자 도메인은 모의투자 앱키로 호출하실 수 없습니다.\",\"msg_cd\":\"EGW02004\"}";

	private Clock clock;

	@BeforeEach
	void setUp() {
		clock = Clock.fixed(Instant.parse("2026-07-22T08:00:00Z"), ZoneOffset.UTC);
	}

	private RestClient.Builder newBuilder() {
		return RestClient.builder();
	}

	private KisHistoricalCandleClientImpl newClient(RestClient.Builder builder, String appKey, String appSecret) {
		return new KisHistoricalCandleClientImpl(
			builder.build(), clock, new KisProperties(BASE_URL, appKey, appSecret, 0L));
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

	private static String candleRow(
		LocalTime time, String open, String high, String low, String close, String volume) {
		return """
			{"stck_cntg_hour":"%s","stck_oprc":"%s","stck_hgpr":"%s","stck_lwpr":"%s","stck_prpr":"%s","cntg_vol":"%s"}
			""".formatted(time.format(TIME_FORMAT), open, high, low, close, volume);
	}

	private static String candlePageJson(LocalTime fromInclusive, LocalTime toInclusive) {
		StringBuilder rows = new StringBuilder();
		LocalTime cursor = fromInclusive;
		boolean first = true;
		while (!cursor.isAfter(toInclusive)) {
			if (!first) {
				rows.append(",");
			}
			rows.append(candleRow(cursor, "70000", "70100", "69900", "70050", "100"));
			first = false;
			cursor = cursor.plusMinutes(1);
		}
		return "{\"output2\":[" + rows + "]}";
	}

	@Test
	void fetchMinuteCandlesStitchesMultiplePagesAcross120RecordBoundaryWithoutDuplicatesOrGaps() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisHistoricalCandleClientImpl client = newClient(builder, APP_KEY, APP_SECRET);

		expectTokenExchange(server);
		server.expect(requestTo(BASE_URL + CANDLE_PATH
			+ "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + SYMBOL
			+ "&FID_INPUT_HOUR_1=153000&FID_INPUT_DATE_1=20260722&FID_PW_DATA_INCU_YN=N&FID_FAKE_TICK_INCU_YN="))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				candlePageJson(LocalTime.of(13, 31), LocalTime.of(15, 30)), MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE_URL + CANDLE_PATH
			+ "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + SYMBOL
			+ "&FID_INPUT_HOUR_1=133000&FID_INPUT_DATE_1=20260722&FID_PW_DATA_INCU_YN=N&FID_FAKE_TICK_INCU_YN="))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				candlePageJson(LocalTime.of(9, 0), LocalTime.of(9, 5)), MediaType.APPLICATION_JSON));

		List<RawMinuteCandleDto> candles = client.fetchMinuteCandles(SYMBOL, TRADING_DATE);

		assertThat(candles).hasSize(126);
		assertThat(candles.get(0).candleTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(candles.get(candles.size() - 1).candleTime()).isEqualTo(LocalTime.of(15, 30));
		assertThat(candles).extracting(RawMinuteCandleDto::candleTime).doesNotHaveDuplicates();
		assertThat(candles).isSortedAccordingTo((a, b) -> a.candleTime().compareTo(b.candleTime()));
		server.verify();
	}

	@Test
	void fetchMinuteCandlesMapsOutput2FieldsToRawMinuteCandleDto() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisHistoricalCandleClientImpl client = newClient(builder, APP_KEY, APP_SECRET);

		expectTokenExchange(server);
		server.expect(requestTo(BASE_URL + CANDLE_PATH
			+ "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + SYMBOL
			+ "&FID_INPUT_HOUR_1=153000&FID_INPUT_DATE_1=20260722&FID_PW_DATA_INCU_YN=N&FID_FAKE_TICK_INCU_YN="))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				"""
					{"output2":[{"stck_cntg_hour":"090000","stck_oprc":"71000","stck_hgpr":"71500",
					"stck_lwpr":"70900","stck_prpr":"71200","cntg_vol":"123456"}]}
					""",
				MediaType.APPLICATION_JSON));

		List<RawMinuteCandleDto> candles = client.fetchMinuteCandles(SYMBOL, TRADING_DATE);

		assertThat(candles).hasSize(1);
		RawMinuteCandleDto candle = candles.get(0);
		assertThat(candle.candleTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(candle.open()).isEqualByComparingTo(new BigDecimal("71000"));
		assertThat(candle.high()).isEqualByComparingTo(new BigDecimal("71500"));
		assertThat(candle.low()).isEqualByComparingTo(new BigDecimal("70900"));
		assertThat(candle.close()).isEqualByComparingTo(new BigDecimal("71200"));
		assertThat(candle.volume()).isEqualTo(123456L);
		server.verify();
	}

	@Test
	void fetchMinuteCandlesReturnsEmptyListWhenOutput2IsEmpty() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisHistoricalCandleClientImpl client = newClient(builder, APP_KEY, APP_SECRET);

		expectTokenExchange(server);
		server.expect(requestTo(BASE_URL + CANDLE_PATH
			+ "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + SYMBOL
			+ "&FID_INPUT_HOUR_1=153000&FID_INPUT_DATE_1=20260722&FID_PW_DATA_INCU_YN=N&FID_FAKE_TICK_INCU_YN="))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"output2\":[]}", MediaType.APPLICATION_JSON));

		List<RawMinuteCandleDto> candles = client.fetchMinuteCandles(SYMBOL, TRADING_DATE);

		assertThat(candles).isEmpty();
		server.verify();
	}

	@Test
	void accessTokenIsCachedAndReusedAcrossCalls() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisHistoricalCandleClientImpl client = newClient(builder, APP_KEY, APP_SECRET);

		expectTokenExchange(server);
		server.expect(requestTo(BASE_URL + CANDLE_PATH
			+ "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + SYMBOL
			+ "&FID_INPUT_HOUR_1=153000&FID_INPUT_DATE_1=20260722&FID_PW_DATA_INCU_YN=N&FID_FAKE_TICK_INCU_YN="))
			.andExpect(method(HttpMethod.GET))
			.andRespond(
				withSuccess(candlePageJson(LocalTime.of(9, 0), LocalTime.of(9, 0)), MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE_URL + CANDLE_PATH
			+ "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + SYMBOL
			+ "&FID_INPUT_HOUR_1=153000&FID_INPUT_DATE_1=20260723&FID_PW_DATA_INCU_YN=N&FID_FAKE_TICK_INCU_YN="))
			.andExpect(method(HttpMethod.GET))
			.andRespond(
				withSuccess(candlePageJson(LocalTime.of(9, 0), LocalTime.of(9, 0)), MediaType.APPLICATION_JSON));

		client.fetchMinuteCandles(SYMBOL, TRADING_DATE);
		client.fetchMinuteCandles(SYMBOL, TRADING_DATE.plusDays(1));

		server.verify();
	}

	@Test
	void fetchMinuteCandlesThrowsIllegalStateExceptionWhenCredentialsAreMissing() {
		RestClient.Builder builderMissingKey = newBuilder();
		MockRestServiceServer serverMissingKey = MockRestServiceServer.bindTo(builderMissingKey).build();
		KisHistoricalCandleClientImpl clientMissingKey = newClient(builderMissingKey, "", APP_SECRET);

		assertThatThrownBy(() -> clientMissingKey.fetchMinuteCandles(SYMBOL, TRADING_DATE))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("KIS_APP_KEY");
		serverMissingKey.verify();

		RestClient.Builder builderMissingSecret = newBuilder();
		MockRestServiceServer serverMissingSecret = MockRestServiceServer.bindTo(builderMissingSecret).build();
		KisHistoricalCandleClientImpl clientMissingSecret = newClient(builderMissingSecret, APP_KEY, "");

		assertThatThrownBy(() -> clientMissingSecret.fetchMinuteCandles(SYMBOL, TRADING_DATE))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("KIS_APP_SECRET");
		serverMissingSecret.verify();
	}

	@Test
	void fetchMinuteCandlesRetriesWhenKisRejectsWithPerSecondRateLimit() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisHistoricalCandleClientImpl client = newClient(builder, APP_KEY, APP_SECRET);

		expectTokenExchange(server);
		server.expect(requestTo(candleUri("153000")))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(RATE_LIMIT_BODY)
				.contentType(MediaType.APPLICATION_JSON));
		server.expect(requestTo(candleUri("153000")))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				candlePageJson(LocalTime.of(9, 0), LocalTime.of(15, 30)), MediaType.APPLICATION_JSON));

		List<RawMinuteCandleDto> candles = client.fetchMinuteCandles(SYMBOL, TRADING_DATE);

		assertThat(candles).hasSize(391);
		server.verify();
	}

	@Test
	void fetchMinuteCandlesDoesNotRetryNonRateLimitErrors() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisHistoricalCandleClientImpl client = newClient(builder, APP_KEY, APP_SECRET);

		expectTokenExchange(server);
		server.expect(requestTo(candleUri("153000")))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(DOMAIN_MISMATCH_BODY)
				.contentType(MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.fetchMinuteCandles(SYMBOL, TRADING_DATE))
			.isInstanceOf(RestClientResponseException.class);
		server.verify();
	}

	private static String candleUri(String hour) {
		return BASE_URL + CANDLE_PATH
			+ "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + SYMBOL
			+ "&FID_INPUT_HOUR_1=" + hour
			+ "&FID_INPUT_DATE_1=20260722&FID_PW_DATA_INCU_YN=N&FID_FAKE_TICK_INCU_YN=";
	}
}
