// Mock HTTP로 KisHistoricalCandleClientImpl의 페이징(120건 경계)·응답 매핑·토큰 캐싱·자격증명 검증을 검증하는 단위 테스트다.
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import com.finplay.api.market.config.KisProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class KisHistoricalCandleClientImplTest {

	private static final String BASE_URL = "https://mock-kis.example";
	private static final String CANDLE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice";
	private static final String TOKEN_PATH = "/oauth2/tokenP";
	private static final String SYMBOL = "005930";
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 22);
	private static final String APP_KEY = "test-app-key";
	private static final String APP_SECRET = "test-app-secret";
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss");

	// 실제 KIS 토큰 유효기간(약 24시간)과 무관하게, 테스트에서는 만료시각을 충분히 먼 미래로 고정해 재발급 분기를 타지 않게 한다.
	private static final String FAR_FUTURE_EXPIRY = "2099-01-01 00:00:00";

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
			builder.build(), clock, new KisProperties(BASE_URL, appKey, appSecret));
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

	// 09:00~15:30 범위 내 fromInclusive~toInclusive(1분 간격)의 output2 응답 JSON을 만든다. 값은 매핑 검증용 고정값.
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
		// 첫 호출: cursor=15:30:00(장 마감), 13:31~15:30(120건, 상한 그대로)을 반환한다.
		server.expect(requestTo(BASE_URL + CANDLE_PATH
			+ "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + SYMBOL
			+ "&FID_INPUT_HOUR_1=153000&FID_INPUT_DATE_1=20260722&FID_PW_DATA_INCU_YN=N&FID_FAKE_TICK_INCU_YN="))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				candlePageJson(LocalTime.of(13, 31), LocalTime.of(15, 30)), MediaType.APPLICATION_JSON));
		// 두 번째 호출: 이전 페이지 최초 시각(13:31) - 1분 = 13:30을 다음 cursor로 삼는다.
		server.expect(requestTo(BASE_URL + CANDLE_PATH
			+ "?FID_COND_MRKT_DIV_CODE=J&FID_INPUT_ISCD=" + SYMBOL
			+ "&FID_INPUT_HOUR_1=133000&FID_INPUT_DATE_1=20260722&FID_PW_DATA_INCU_YN=N&FID_FAKE_TICK_INCU_YN="))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				candlePageJson(LocalTime.of(9, 0), LocalTime.of(9, 5)), MediaType.APPLICATION_JSON));

		List<RawMinuteCandle> candles = client.fetchMinuteCandles(SYMBOL, TRADING_DATE);

		// 120건(13:31~15:30) + 6건(09:00~09:05) = 126건, 중복·누락 없이 시각 오름차순.
		assertThat(candles).hasSize(126);
		assertThat(candles.get(0).candleTime()).isEqualTo(LocalTime.of(9, 0));
		assertThat(candles.get(candles.size() - 1).candleTime()).isEqualTo(LocalTime.of(15, 30));
		assertThat(candles).extracting(RawMinuteCandle::candleTime).doesNotHaveDuplicates();
		assertThat(candles).isSortedAccordingTo((a, b) -> a.candleTime().compareTo(b.candleTime()));
		server.verify();
	}

	@Test
	void fetchMinuteCandlesMapsOutput2FieldsToRawMinuteCandle() {
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

		List<RawMinuteCandle> candles = client.fetchMinuteCandles(SYMBOL, TRADING_DATE);

		assertThat(candles).hasSize(1);
		RawMinuteCandle candle = candles.get(0);
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

		List<RawMinuteCandle> candles = client.fetchMinuteCandles(SYMBOL, TRADING_DATE);

		assertThat(candles).isEmpty();
		server.verify();
	}

	@Test
	void accessTokenIsCachedAndReusedAcrossCalls() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisHistoricalCandleClientImpl client = newClient(builder, APP_KEY, APP_SECRET);

		// 토큰 발급은 딱 한 번만 등록한다 — 두 번째 fetch에서 다시 요청하면 미등록 요청으로 테스트가 실패한다.
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
}
