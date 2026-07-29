// KIS Open API 과거 1분봉 조회(REST, 주식일별분봉조회)의 유일한 HTTP 구현체 — 인증 토큰 캐싱과 120건 상한 페이징을 이 클래스 안에만 둔다.
package com.finplay.api.market.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.market.config.KisProperties;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

// output2의 개별 필드명(stck_cntg_hour·stck_oprc·stck_hgpr·stck_lwpr·stck_prpr·cntg_vol)과 분봉 timestamp 기준(구간
// 시작/종료)은 실제 KIS 응답으로 확인하지 못했다(Decision Gate, spec.md·plan.md 참고) — KIS 다른 시세 API의 명명 관례를
// 따른 최선 추정으로 구현했다. 이 매핑은 toRawMinuteCandle 메서드 한 곳에만 있으므로, 외부 스모크로 실제 응답을 확인한
// 뒤에는 이 지점만 교정하면 된다. 페이징 방향(역방향 — FID_INPUT_HOUR_1을 조회 상한 시각으로 보고 그 이전 데이터를
// 반환한다고 가정, 응답의 가장 이른 시각-1분을 다음 호출의 상한으로 삼아 09:00에 도달할 때까지 반복)도 같은 이유로
// requestPage/fetchMinuteCandles 안에만 격리했다.
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
	// 09:00~15:30(390분)을 한 번에 최대 120건씩 역방향으로 당겨오면 약 4회면 충분하다 — 이상 응답으로 인한 무한루프를 막는 안전 상한.
	private static final int MAX_PAGES_PER_SYMBOL = 10;
	private static final DateTimeFormatter TRADING_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
	private static final DateTimeFormatter CANDLE_TIME_FORMAT = DateTimeFormatter.ofPattern("HHmmss");
	private static final DateTimeFormatter TOKEN_EXPIRY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 실제 KIS 토큰 유효기간은 약 24시간이라고 확인됐다 — 응답에 만료시각이 없거나 파싱 실패 시의 안전 기본값(여유를 둔 값).
	private static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(23);
	// 만료 임박 시 재사용 대신 미리 재발급해 호출 도중 토큰이 끊기는 것을 방지하는 안전 여유.
	private static final Duration TOKEN_EXPIRY_SAFETY_MARGIN = Duration.ofMinutes(5);

	// 타임아웃이 적용된 완성된 RestClient를 그대로 주입받는다 — 빌드 로직은 KisRestClientConfig가 담당(SpotBugs EI_EXPOSE_REP2 회피).
	private final RestClient restClient;
	private final Clock clock;
	private final KisProperties properties;

	private volatile String cachedAccessToken;
	private volatile Instant cachedAccessTokenExpiry;

	@Override
	public List<RawMinuteCandle> fetchMinuteCandles(String symbol, LocalDate tradingDate) {
		requireCredentials();
		Map<LocalTime, RawMinuteCandle> collected = new LinkedHashMap<>();
		LocalTime cursor = MARKET_CLOSE_TIME;
		for (int page = 0; page < MAX_PAGES_PER_SYMBOL; page++) {
			List<RawMinuteCandle> rows = requestPage(symbol, tradingDate, cursor);
			if (rows.isEmpty()) {
				break;
			}
			LocalTime earliestInPage = cursor;
			for (RawMinuteCandle candle : rows) {
				collected.putIfAbsent(candle.candleTime(), candle);
				if (candle.candleTime().isBefore(earliestInPage)) {
					earliestInPage = candle.candleTime();
				}
			}
			if (!earliestInPage.isAfter(MARKET_OPEN_TIME)) {
				break;
			}
			LocalTime nextCursor = earliestInPage.minusMinutes(1);
			if (!nextCursor.isBefore(cursor)) {
				log.warn("KIS 분봉 페이징이 더 이상 진행되지 않아 중단합니다 (symbol={}, tradingDate={})", symbol, tradingDate);
				break;
			}
			cursor = nextCursor;
		}
		return collected.values().stream()
			.filter(candle -> !candle.candleTime().isBefore(MARKET_OPEN_TIME)
				&& !candle.candleTime().isAfter(MARKET_CLOSE_TIME))
			.sorted(Comparator.comparing(RawMinuteCandle::candleTime))
			.toList();
	}

	private List<RawMinuteCandle> requestPage(String symbol, LocalDate tradingDate, LocalTime cursor) {
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
		return response.output2().stream().map(KisHistoricalCandleClientImpl::toRawMinuteCandle).toList();
	}

	private static RawMinuteCandle toRawMinuteCandle(Output2Row row) {
		LocalTime candleTime = LocalTime.parse(row.stck_cntg_hour(), CANDLE_TIME_FORMAT);
		return new RawMinuteCandle(
			candleTime,
			new BigDecimal(row.stck_oprc()),
			new BigDecimal(row.stck_hgpr()),
			new BigDecimal(row.stck_lwpr()),
			new BigDecimal(row.stck_prpr()),
			Long.parseLong(row.cntg_vol()));
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

	// 이중 검사 락(double-checked locking) — 동시 호출 시 토큰 발급 요청이 중복으로 나가지 않게 한다.
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
		String stck_cntg_hour,
		String stck_oprc,
		String stck_hgpr,
		String stck_lwpr,
		String stck_prpr,
		String cntg_vol) {
	}
}
