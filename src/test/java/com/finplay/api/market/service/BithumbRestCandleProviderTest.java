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

		provider.getCandles("BTC", null, null);

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

		List<CryptoCandleDto> result = provider.getCandles("BTC", null, null);

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

		List<CryptoCandleDto> result = provider.getCandles("BTC", null, null);

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

		CryptoCandleDto candle = provider.getCandles("BTC", null, null).get(0);

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

		CryptoCandleDto candle = provider.getCandles("BTC", null, null).get(0);

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

		provider.getCandles("BTC", null, null);

		server.verify();
	}

	@Test
	void getCandlesSendsCountTwoHundredWithGivenToParamWhenOnlyToProvided() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		LocalDateTime to = LocalDateTime.of(2026, 7, 30, 9, 30);

		server.expect(requestTo(startsWith(ENDPOINT)))
			.andExpect(queryParam("count", "200"))
			// KST 09:30 -> UTC 00:30 (KST = UTC+9)
			.andExpect(queryParam("to", "2026-07-30T00:30:00"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", null, to);

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

		provider.getCandles("BTC", from, null);

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
			.andExpect(queryParam("to", "2026-07-30T00:10:00"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", from, to);

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
			.andExpect(queryParam("to", "2026-07-30T00:00:00"))
			.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		provider.getCandles("BTC", from, to);

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

		assertThatThrownBy(() -> provider.getCandles("BTC", null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));
	}

	@Test
	void getCandlesThrowsProviderErrorOnNonSuccessStatusInsteadOfReturningEmptyList() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(ENDPOINT))).andRespond(withServerError());

		assertThatThrownBy(() -> provider.getCandles("BTC", null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));
	}

	@Test
	void getCandlesThrowsProviderErrorOnNonJsonBadRequestStatus() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(ENDPOINT)))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).body("bad request"));

		assertThatThrownBy(() -> provider.getCandles("BTC", null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));
	}

	@Test
	void getCandlesThrowsProviderErrorOnMalformedJsonInsteadOfReturningEmptyList() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(ENDPOINT)))
			.andRespond(withSuccess("{malformed", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> provider.getCandles("BTC", null, null))
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

		assertThatThrownBy(() -> provider.getCandles("BTC", null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));
	}

	@Test
	void getCandlesThrowsProviderErrorWhenBodyIsNull() {
		BithumbRestCandleProvider provider = providerAt(LocalDateTime.of(2026, 7, 30, 11, 43));
		server.expect(requestTo(startsWith(ENDPOINT)))
			.andRespond(withSuccess("null", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> provider.getCandles("BTC", null, null))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.MARKET_DATA_PROVIDER_ERROR));
	}

	private static org.springframework.test.web.client.RequestMatcher noToParam() {
		return request -> assertThat(request.getURI().getQuery()).doesNotContain("to=");
	}
}
