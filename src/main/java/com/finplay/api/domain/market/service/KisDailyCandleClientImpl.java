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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
public class KisDailyCandleClientImpl implements KisDailyCandleClient {

	private static final String TR_ID_DAILY_CHART = "FHKST03010100";
	private static final String TOKEN_PATH = "/oauth2/tokenP";
	private static final String CANDLE_PATH = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice";
	private static final String TOKEN_GRANT_TYPE = "client_credentials";
	private static final String MARKET_DIV_CODE_STOCK = "J";
	private static final String PERIOD_DIV_CODE_DAY = "D";
	private static final String ADJUSTED_PRICE_OPTION = "0";
	private static final String CUSTOMER_TYPE_PERSONAL = "P";
	private static final String HEADER_APP_KEY = "appkey";
	private static final String HEADER_APP_SECRET = "appsecret";
	private static final String HEADER_TR_ID = "tr_id";
	private static final String HEADER_CUSTOMER_TYPE = "custtype";
	private static final int MAX_PAGES_PER_SYMBOL = 12;
	private static final String RATE_LIMIT_ERROR_CODE = "EGW00201";
	private static final int MAX_RATE_LIMIT_RETRIES = 5;
	private static final long MIN_RATE_LIMIT_BACKOFF_MS = 400L;
	private static final DateTimeFormatter TRADING_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
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
	public List<RawDailyCandleDto> fetchDailyCandles(String symbol, LocalDate from, LocalDate to) {
		requireCredentials();
		Map<LocalDate, RawDailyCandleDto> collected = new LinkedHashMap<>();
		LocalDate cursorEnd = to;
		boolean terminatedEarly = false;
		for (int page = 0; page < MAX_PAGES_PER_SYMBOL; page++) {
			List<RawDailyCandleDto> rows = requestPage(symbol, from, cursorEnd);
			if (rows.isEmpty()) {
				terminatedEarly = true;
				break;
			}
			LocalDate earliestInPage = cursorEnd;
			for (RawDailyCandleDto candle : rows) {
				collected.putIfAbsent(candle.tradingDate(), candle);
				if (candle.tradingDate().isBefore(earliestInPage)) {
					earliestInPage = candle.tradingDate();
				}
			}
			if (!earliestInPage.isAfter(from)) {
				terminatedEarly = true;
				break;
			}
			cursorEnd = earliestInPage.minusDays(1);
		}
		if (!terminatedEarly && !collected.isEmpty()) {
			LocalDate earliestCollected = collected.keySet().stream().min(Comparator.naturalOrder()).orElseThrow();
			log.warn(
				"KIS 일봉 페이지 상한({})에 도달해 요청 구간을 다 채우지 못했습니다 — 채워지지 않은 구간은 자동으로"
					+ " 재시도되지 않습니다 (symbol={}, requestedFrom={}, actualEarliest={}, to={})",
				MAX_PAGES_PER_SYMBOL, symbol, from, earliestCollected, to);
		}
		return collected.values().stream()
			.filter(candle -> !candle.tradingDate().isBefore(from) && !candle.tradingDate().isAfter(to))
			.sorted(Comparator.comparing(RawDailyCandleDto::tradingDate))
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
			throw new IllegalStateException("KIS 일봉 조회 간격 대기가 중단되었습니다.", ex);
		}
	}

	private List<RawDailyCandleDto> requestPage(String symbol, LocalDate from, LocalDate cursorEnd) {
		RestClientResponseException lastRateLimitError = null;
		for (int attempt = 0; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
			throttleBeforeRequest();
			try {
				return requestPageOnce(symbol, from, cursorEnd);
			} catch (RestClientResponseException ex) {
				if (!isRateLimited(ex)) {
					throw ex;
				}
				lastRateLimitError = ex;
				log.debug("KIS 초당 호출 제한에 걸려 재시도합니다 (symbol={}, cursorEnd={}, 시도 {}/{})", symbol, cursorEnd,
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

	private List<RawDailyCandleDto> requestPageOnce(String symbol, LocalDate from, LocalDate cursorEnd) {
		String accessToken = ensureAccessToken();
		String uri = UriComponentsBuilder
			.fromUriString(properties.baseUrl() + CANDLE_PATH)
			.queryParam("FID_COND_MRKT_DIV_CODE", MARKET_DIV_CODE_STOCK)
			.queryParam("FID_INPUT_ISCD", symbol)
			.queryParam("FID_INPUT_DATE_1", from.format(TRADING_DATE_FORMAT))
			.queryParam("FID_INPUT_DATE_2", cursorEnd.format(TRADING_DATE_FORMAT))
			.queryParam("FID_PERIOD_DIV_CODE", PERIOD_DIV_CODE_DAY)
			.queryParam("FID_ORG_ADJ_PRC", ADJUSTED_PRICE_OPTION)
			.build()
			.toUriString();

		DailyChartResponse response = restClient
			.get()
			.uri(uri)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
			.header(HEADER_APP_KEY, properties.appKey())
			.header(HEADER_APP_SECRET, properties.appSecret())
			.header(HEADER_TR_ID, TR_ID_DAILY_CHART)
			.header(HEADER_CUSTOMER_TYPE, CUSTOMER_TYPE_PERSONAL)
			.accept(MediaType.APPLICATION_JSON)
			.retrieve()
			.body(DailyChartResponse.class);

		if (response == null || response.output2() == null) {
			return List.of();
		}
		return response.output2().stream()
			.map(row -> toRawDailyCandleDto(row, symbol, from, cursorEnd))
			.filter(Objects::nonNull)
			.toList();
	}

	private static RawDailyCandleDto toRawDailyCandleDto(
		Output2Row row, String symbol, LocalDate from, LocalDate cursorEnd) {
		LocalDate tradingDate;
		BigDecimal open;
		BigDecimal high;
		BigDecimal low;
		BigDecimal close;
		long volume;
		try {
			tradingDate = LocalDate.parse(row.tradingDate(), TRADING_DATE_FORMAT);
			open = new BigDecimal(row.open());
			high = new BigDecimal(row.high());
			low = new BigDecimal(row.low());
			close = new BigDecimal(row.close());
			volume = Long.parseLong(row.volume());
		} catch (RuntimeException ex) {
			log.warn("KIS 일봉 응답 행을 파싱할 수 없어 폐기합니다 (symbol={}, raw={})", symbol, row, ex);
			return null;
		}
		if (tradingDate.isBefore(from) || tradingDate.isAfter(cursorEnd)) {
			log.warn("KIS 일봉 응답 행의 거래일이 요청 구간을 벗어나 폐기합니다 (symbol={}, tradingDate={}, from={}, cursorEnd={})",
				symbol, tradingDate, from, cursorEnd);
			return null;
		}
		if (open.signum() <= 0 || high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0 || volume < 0) {
			log.warn("KIS 일봉 응답 행의 가격·거래량이 유효하지 않아 폐기합니다 (symbol={}, tradingDate={})", symbol, tradingDate);
			return null;
		}
		if (low.compareTo(open) > 0 || low.compareTo(close) > 0 || high.compareTo(open) < 0
			|| high.compareTo(close) < 0) {
			log.warn("KIS 일봉 응답 행의 저가·고가 관계가 유효하지 않아 폐기합니다 (symbol={}, tradingDate={})", symbol, tradingDate);
			return null;
		}
		return new RawDailyCandleDto(tradingDate, open, high, low, close, volume);
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
			throw new IllegalStateException("KIS_APP_KEY·KIS_APP_SECRET이 설정되지 않아 과거 일봉을 조회할 수 없습니다.");
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record TokenResponse(String access_token, String access_token_token_expired) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DailyChartResponse(List<Output2Row> output2) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record Output2Row(
		@JsonProperty("stck_bsop_date")
		String tradingDate,
		@JsonProperty("stck_oprc")
		String open,
		@JsonProperty("stck_hgpr")
		String high,
		@JsonProperty("stck_lwpr")
		String low,
		@JsonProperty("stck_clpr")
		String close,
		@JsonProperty("acml_vol")
		String volume) {
	}
}
