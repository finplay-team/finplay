package com.finplay.api.domain.market.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.finplay.api.domain.market.config.KisProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@RequiredArgsConstructor
@Slf4j
public class KisHistoricalCandleClientImpl implements KisHistoricalCandleClient {

	private static final String TR_ID_MINUTE_CHART = "FHKST03010230";
	private static final String TOKEN_PATH = "/oauth2/tokenP";
	private static final String CANDLE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice";
	private static final String TOKEN_GRANT_TYPE = "client_credentials";
	private static final String MARKET_DIV_CODE_STOCK = "J";
	private static final String PAST_DATA_NOT_INCLUDED = "N";
	private static final String CUSTOMER_TYPE_PERSONAL = "P";
	private static final String HEADER_APP_KEY = "appkey";
	private static final String HEADER_APP_SECRET = "appsecret";
	private static final String HEADER_TR_ID = "tr_id";
	private static final String HEADER_CUSTOMER_TYPE = "custtype";
	private static final LocalTime MARKET_OPEN_TIME = LocalTime.of(9, 0);
	private static final LocalTime MARKET_CLOSE_TIME = LocalTime.of(15, 30);
	private static final int MAX_PAGES_PER_SYMBOL = 10;
	private static final String RATE_LIMIT_ERROR_CODE = "EGW00201";
	private static final int MAX_RATE_LIMIT_RETRIES = 5;
	private static final long MIN_RATE_LIMIT_BACKOFF_MS = 400L;
	private static final DateTimeFormatter TRADING_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
	private static final DateTimeFormatter CANDLE_TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss");
	private static final DateTimeFormatter TOKEN_EXPIRY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(23);
	private static final Duration TOKEN_EXPIRY_SAFETY_MARGIN = Duration.ofMinutes(5);

	@Qualifier("kisRestClient")
	private final RestClient restClient;
	private final Clock clock;
	private final KisProperties properties;

	private volatile String cachedAccessToken;
	private volatile Instant cachedAccessTokenExpiry;

	@Override
	public List<RawMinuteCandleDto> fetchMinuteCandles(String symbol, LocalDate tradingDate) {
		requireCredentials();
		Map<LocalTime, RawMinuteCandleDto> collected = new LinkedHashMap<>();
		LocalTime cursor = MARKET_CLOSE_TIME;
		for (int page = 0; page < MAX_PAGES_PER_SYMBOL; page++) {
			List<RawMinuteCandleDto> rows = requestPage(symbol, tradingDate, cursor);
			if (rows.isEmpty()) {
				break;
			}
			LocalTime earliestInPage = cursor;
			for (RawMinuteCandleDto candle : rows) {
				collected.putIfAbsent(candle.candleTime(), candle);
				if (candle.candleTime().isBefore(earliestInPage)) {
					earliestInPage = candle.candleTime();
				}
			}
			if (!earliestInPage.isAfter(MARKET_OPEN_TIME)) {
				break;
			}
			cursor = earliestInPage.minusMinutes(1);
		}
		return collected.values().stream()
			.filter(candle -> !candle.candleTime().isBefore(MARKET_OPEN_TIME)
				&& !candle.candleTime().isAfter(MARKET_CLOSE_TIME))
			.sorted(Comparator.comparing(RawMinuteCandleDto::candleTime))
			.toList();
	}

	private void throttleBeforeRequest() {
		long intervalMs = properties.requestIntervalMs();
		if (intervalMs <= 0) {
			return;
		}
		try {
			Thread.sleep(intervalMs);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("KIS 분봉 조회 간격 대기가 중단되었습니다.", ex);
		}
	}

	private List<RawMinuteCandleDto> requestPage(String symbol, LocalDate tradingDate, LocalTime cursor) {
		RestClientResponseException lastRateLimitError = null;
		for (int attempt = 0; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
			throttleBeforeRequest();
			try {
				return requestPageOnce(symbol, tradingDate, cursor);
			} catch (RestClientResponseException ex) {
				if (!isRateLimited(ex)) {
					throw ex;
				}
				lastRateLimitError = ex;
				log.debug("KIS 초당 호출 제한에 걸려 재시도합니다 (symbol={}, cursor={}, 시도 {}/{})", symbol, cursor,
					attempt + 1, MAX_RATE_LIMIT_RETRIES + 1);
				backOffAfterRateLimit(attempt);
			}
		}
		throw lastRateLimitError;
	}

	private static boolean isRateLimited(RestClientResponseException ex) {
		return ex.getResponseBodyAsString().contains(RATE_LIMIT_ERROR_CODE);
	}

	private void backOffAfterRateLimit(int attempt) {
		long base = Math.max(properties.requestIntervalMs(), MIN_RATE_LIMIT_BACKOFF_MS);
		try {
			Thread.sleep(base * (attempt + 1L));
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("KIS 초당 호출 제한 재시도 대기가 중단되었습니다.", ex);
		}
	}

	private List<RawMinuteCandleDto> requestPageOnce(String symbol, LocalDate tradingDate, LocalTime cursor) {
		String accessToken = ensureAccessToken();
		String uri = UriComponentsBuilder
			.fromUriString(properties.baseUrl() + CANDLE_PATH)
			.queryParam("FID_COND_MRKT_DIV_CODE", MARKET_DIV_CODE_STOCK)
			.queryParam("FID_INPUT_ISCD", symbol)
			.queryParam("FID_INPUT_HOUR_1", cursor.format(CANDLE_TIME_FORMAT))
			.queryParam("FID_INPUT_DATE_1", tradingDate.format(TRADING_DATE_FORMAT))
			.queryParam("FID_PW_DATA_INCU_YN", PAST_DATA_NOT_INCLUDED)
			.queryParam("FID_FAKE_TICK_INCU_YN", "")
			.build()
			.toUriString();

		CandleChartResponse response = restClient
			.get()
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header(HEADER_APP_KEY, properties.appKey())
			.header(HEADER_APP_SECRET, properties.appSecret())
			.header(HEADER_TR_ID, TR_ID_MINUTE_CHART)
			.header(HEADER_CUSTOMER_TYPE, CUSTOMER_TYPE_PERSONAL)
			.accept(MediaType.APPLICATION_JSON)
			.retrieve()
			.body(CandleChartResponse.class);

		if (response == null || response.output2() == null) {
			return List.of();
		}
		return response.output2().stream().map(KisHistoricalCandleClientImpl::toRawMinuteCandleDto).toList();
	}

	private static RawMinuteCandleDto toRawMinuteCandleDto(Output2Row row) {
		LocalTime candleTime = LocalTime.parse(row.candleTime(), CANDLE_TIME_FORMAT);
		return new RawMinuteCandleDto(
			candleTime,
			new BigDecimal(row.open()),
			new BigDecimal(row.high()),
			new BigDecimal(row.low()),
			new BigDecimal(row.close()),
			Long.parseLong(row.volume()));
	}

	private String ensureAccessToken() {
		Instant now = clock.instant();
		if (isTokenValid(now)) {
			return cachedAccessToken;
		}
		return issueAccessToken(now);
	}

	private boolean isTokenValid(Instant now) {
		return cachedAccessToken != null && cachedAccessTokenExpiry != null
			&& now.isBefore(cachedAccessTokenExpiry.minus(TOKEN_EXPIRY_SAFETY_MARGIN));
	}

	private synchronized String issueAccessToken(Instant now) {
		if (isTokenValid(now)) {
			return cachedAccessToken;
		}
		Map<String, String> requestBody = Map.of(
			"grant_type", TOKEN_GRANT_TYPE,
			"appkey", properties.appKey(),
			"appsecret", properties.appSecret());
		TokenResponse response = restClient
			.post()
			.uri(properties.baseUrl() + TOKEN_PATH)
			.contentType(MediaType.APPLICATION_JSON)
			.body(requestBody)
			.retrieve()
			.body(TokenResponse.class);
		if (response == null || isBlank(response.access_token())) {
			throw new IllegalStateException("KIS 토큰 발급 응답이 비어 있습니다.");
		}
		cachedAccessToken = response.access_token();
		cachedAccessTokenExpiry = parseExpiry(response.access_token_token_expired(), now);
		return cachedAccessToken;
	}

	private static Instant parseExpiry(String rawExpiry, Instant now) {
		if (isBlank(rawExpiry)) {
			return now.plus(DEFAULT_TOKEN_TTL);
		}
		try {
			return LocalDateTime.parse(rawExpiry, TOKEN_EXPIRY_FORMAT).atZone(KST).toInstant();
		} catch (DateTimeParseException ex) {
			return now.plus(DEFAULT_TOKEN_TTL);
		}
	}

	private void requireCredentials() {
		if (isBlank(properties.appKey()) || isBlank(properties.appSecret())) {
			throw new IllegalStateException("KIS_APP_KEY·KIS_APP_SECRET이 설정되지 않아 과거 분봉을 조회할 수 없습니다.");
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record TokenResponse(String access_token, String access_token_token_expired) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record CandleChartResponse(List<Output2Row> output2) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Output2Row(
		@JsonProperty("stck_cntg_hour")
		String candleTime,
		@JsonProperty("stck_oprc")
		String open,
		@JsonProperty("stck_hgpr")
		String high,
		@JsonProperty("stck_lwpr")
		String low,
		@JsonProperty("stck_prpr")
		String close,
		@JsonProperty("cntg_vol")
		String volume) {
	}
}
