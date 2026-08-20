// Mock HTTP로 KisDailyCandleClientImpl의 날짜 커서 역방향 페이징 종료 조건·응답 검증 규칙(행 단위 폐기)·페이지 상한
// 도달 시 중단을 검증하는 단위 테스트다 (tasks.md 3번).
package com.finplay.api.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finplay.api.market.config.KisProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

class KisDailyCandleClientImplTest {

	private static final String BASE_URL = "https://mock-kis.example";
	private static final String CANDLE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
	private static final String TOKEN_PATH = "/oauth2/tokenP";
	private static final String SYMBOL = "005930";
	private static final String APP_KEY = "test-app-key";
	private static final String APP_SECRET = "test-app-secret";
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
	// KIS가 실제로 내려주는 오류 본문 그대로 (2026-07-30 실측, KisHistoricalCandleClientImplTest와 동일 근거).
	private static final String RATE_LIMIT_BODY = "{\"rt_cd\":\"1\",\"msg1\":\"초당 거래건수를 초과하였습니다.\",\"msg_cd\":\"EGW00201\"}";
	private static final String DOMAIN_MISMATCH_BODY = "{\"rt_cd\":\"1\",\"msg1\":\"실전투자 도메인은 모의투자 앱키로 호출하실 수 없습니다.\",\"msg_cd\":\"EGW02004\"}";

	// 실제 KIS 토큰 유효기간(약 24시간)과 무관하게, 테스트에서는 만료시각을 충분히 먼 미래로 고정해 재발급 분기를 타지 않게 한다.
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
		// 호출 간격 0 — 테스트가 실제로 대기하지 않게 한다.
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

	// --- 매핑 + 고정 요청 파라미터(FID_ORG_ADJ_PRC=0 수정주가) ---

	@Test
	void fetchDailyCandlesMapsOutput2FieldsAndAlwaysRequestsAdjustedPrice() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 20);

		expectTokenExchange(server);
		// 쿼리스트링에 FID_ORG_ADJ_PRC=0이 고정으로 들어가는지도 URI 매칭으로 함께 검증한다.
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

	// --- 페이징 종료 조건 ---

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
		// 첫 호출: cursorEnd=to(07-24), 07-22~07-24 3건을 반환한다. 최고(最古) 07-22는 아직 from(07-20)보다 늦으므로 계속한다.
		server.expect(requestTo(dailyCandleUri(from, day24)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess(
				dailyPageJson(
					dailyRow(day24, "72000", "72500", "71900", "72200", "100"),
					dailyRow(day23, "71500", "72000", "71400", "71800", "100"),
					dailyRow(day22, "71000", "71500", "70900", "71300", "100")),
				MediaType.APPLICATION_JSON));
		// 두 번째 호출: 이전 페이지 최고(最古) 07-22 - 1일 = 07-21을 다음 cursorEnd로 삼는다. 07-20(=from)에 도달하면 중단한다.
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
		// 빈 응답 하나만 등록한다 — 코드가 다음 페이지를 요청하면 미등록 요청으로 server.verify()가 실패한다.
		server.expect(requestTo(dailyCandleUri(from, to)))
			.andExpect(method(HttpMethod.GET))
			.andRespond(withSuccess("{\"output2\":[]}", MediaType.APPLICATION_JSON));

		List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, to);

		assertThat(candles).isEmpty();
		server.verify();
	}

	// --- 페이지 상한 도달 시 중단(MAX_PAGES_PER_SYMBOL=12) ---

	@Test
	void fetchDailyCandlesStopsAfterTwelvePagesEvenWhenFromDateIsNotYetReached() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		// from을 아주 먼 과거로 두어, 응답이 계속돼도 "from에 도달해 중단"하는 경로를 타지 않게 한다 —
		// 오직 페이지 상한(12)만으로 루프가 끝나는지를 격리해서 검증한다.
		LocalDate from = LocalDate.of(2000, 1, 1);
		LocalDate to = LocalDate.of(2026, 8, 20);

		expectTokenExchange(server);
		// 페이지마다 그 페이지의 cursorEnd 하루치 데이터만 반환한다 — 다음 cursorEnd는 정확히 하루씩만 과거로 이동한다.
		// 12개(page 0~11)만 등록하므로, 코드가 13번째를 요청하면 미등록 요청으로 실패한다.
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

	// --- 응답 검증 규칙(행 단위 폐기 — 종목 전체가 아니라 위반 행만 버린다) ---

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
					// 유효한 행 — 거래일이 from과 같아 이 페이지 하나로 루프가 끝난다.
					dailyRow(from, "71000", "71500", "70900", "71200", "100"),
					// 시가가 0 이하 — 폐기 대상.
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
					// 저가(72000)가 시가(71000)보다 높음 — low <= open 위반, 폐기 대상.
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
					// 고가(71000)가 종가(71500)보다 낮음 — high >= close 위반, 폐기 대상.
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
					// 요청 구간(from~to) 이전 거래일 — 폐기 대상.
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
					// 시가가 숫자가 아님 — BigDecimal 파싱 실패로 폐기 대상.
					dailyRow(to, "abc", "71500", "70900", "71200", "100")),
				MediaType.APPLICATION_JSON));

		List<RawDailyCandleDto> candles = client.fetchDailyCandles(SYMBOL, from, to);

		assertThat(candles).hasSize(1);
		assertThat(candles.get(0).tradingDate()).isEqualTo(from);
		server.verify();
	}

	// --- 레이트리밋 재시도 (KisHistoricalCandleClientImplTest의 동일 이름 테스트와 같은 근거·구조) ---

	@Test
	void fetchDailyCandlesRetriesWhenKisRejectsWithPerSecondRateLimit() {
		RestClient.Builder builder = newBuilder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		KisDailyCandleClientImpl client = newClient(builder);
		LocalDate from = LocalDate.of(2026, 7, 20);
		LocalDate to = LocalDate.of(2026, 7, 20);

		expectTokenExchange(server);
		// 첫 시도는 초당 제한으로 거부되고, 같은 페이지(from~to)로 재시도해 성공한다.
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

	// 인증 실패·도메인 불일치(EGW02004) 등은 재시도해도 결과가 같으므로 즉시 던져야 한다(1분봉 클라이언트와 동일 근거 —
	// 무의미한 대기로 08:25 배치가 불필요하게 길어지지 않게 한다).
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
		// expect를 1건만 등록했으므로 재시도가 있었다면 verify가 실패한다.
		server.verify();
	}
}
