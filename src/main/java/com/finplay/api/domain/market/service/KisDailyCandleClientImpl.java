// KIS Open API 과거 일봉 조회(REST, 국내주식기간별시세)의 유일한 HTTP 구현체 — 인증 토큰 캐싱과 1회 100건 상한
// 날짜 커서 역방향 페이징을 이 클래스 안에만 둔다. 토큰 캐싱·레이트리밋 재시도는 KisHistoricalCandleClientImpl(1분봉)과
// 같은 패턴을 따르되, 별도 빈이라 토큰 캐시는 공유하지 않는다(빈 하나당 캐시 하나 — 두 배치가 동시에 실행돼도 서로의
// 토큰 재발급을 기다리지 않는다).
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

// output2 필드명(stck_bsop_date·stck_oprc·stck_hgpr·stck_lwpr·stck_clpr·acml_vol)과 FID_ORG_ADJ_PRC=0(수정주가)
// 채택은 2026-08-20 실제 KIS 호출로 확인했다(plan.md "Decision Gate 해소" 절) — 추정이 아니라 실측이다. 1회 호출은
// 최신 날짜가 먼저 오는 내림차순 최대 100건이므로(같은 절 실측), 날짜 커서를 그 페이지의 최고(最古) 거래일 하루
// 전으로 옮겨가며 요청 구간의 시작일에 도달할 때까지 반복한다(1분봉의 시각 커서 역방향 페이징과 같은 모양).
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
	// 수정주가로 고정한다 — 원주가(1)는 액면분할·병합 경계에서 가격이 단절돼 장기 차트에 인위적 급등락을 만든다
	// (2026-08-20 실측: 삼성전자 2018-05-04 50:1 분할 경계에서 원주가가 53,000→2,650,000으로 뛰었다). 사용자 입력 없음.
	private static final String ADJUSTED_PRICE_OPTION = "0";
	private static final String CUSTOMER_TYPE_PERSONAL = "P";
	private static final String HEADER_APP_KEY = "appkey";
	private static final String HEADER_APP_SECRET = "appsecret";
	private static final String HEADER_TR_ID = "tr_id";
	private static final String HEADER_CUSTOMER_TYPE = "custtype";
	// 1회 호출 상한 100행 실측(2026-08-20) 기준, 3년(약 750영업일)을 채우려면 최소 8페이지가 필요하다(plan.md
	// "Decision Gate 해소" 절). 휴장일 배치·페이지 경계 어긋남 여유를 더해 상한을 넉넉히 둔다.
	private static final int MAX_PAGES_PER_SYMBOL = 12;
	// 초당 호출 제한 응답의 KIS 오류 코드 — 이 코드만 재시도 대상으로 삼는다(1분봉 클라이언트와 동일 근거).
	private static final String RATE_LIMIT_ERROR_CODE = "EGW00201";
	private static final int MAX_RATE_LIMIT_RETRIES = 5;
	private static final long MIN_RATE_LIMIT_BACKOFF_MS = 400L;
	private static final DateTimeFormatter TRADING_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
	private static final DateTimeFormatter TOKEN_EXPIRY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 실제 KIS 토큰 유효기간은 약 24시간이라고 확인됐다 — 응답에 만료시각이 없거나 파싱 실패 시의 안전 기본값(여유를 둔 값).
	private static final Duration DEFAULT_TOKEN_TTL = Duration.ofHours(23);
	// 만료 임박 시 재사용 대신 미리 재발급해 호출 도중 토큰이 끊기는 것을 방지하는 안전 여유.
	private static final Duration TOKEN_EXPIRY_SAFETY_MARGIN = Duration.ofMinutes(5);

	// 타임아웃이 적용된 완성된 RestClient를 그대로 주입받는다 — 빌드 로직은 KisRestClientConfig가 담당(SpotBugs EI_EXPOSE_REP2 회피).
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
		// 루프가 break로 끝나면(목표 도달·빈 응답·커서 정체) 이 값이 true가 된다. for 조건(page < 상한)이 자연히
		// 거짓이 돼 끝나는 경우에만 false로 남아, "정말로 페이지 상한을 다 써버린 경우"만 구분할 수 있다
		// (재리뷰 지적 — 빈 응답으로 인한 정상 종료를 상한 도달로 오인하던 문제를 고친다).
		boolean terminatedEarly = false;
		for (int page = 0; page < MAX_PAGES_PER_SYMBOL; page++) {
			List<RawDailyCandleDto> rows = requestPage(symbol, from, cursorEnd);
			if (rows.isEmpty()) {
				// KIS가 그 이전 거래일 데이터를 아예 갖고 있지 않다는 뜻이다(상장 이력이 짧은 종목 등) — 오류가
				// 아니라 정상 종료다(spec.md "거래일 수가 적다는 사실 자체는 오류가 아니다").
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
			LocalDate nextCursorEnd = earliestInPage.minusDays(1);
			if (!nextCursorEnd.isBefore(cursorEnd)) {
				log.warn("KIS 일봉 페이징이 더 이상 진행되지 않아 중단합니다 (symbol={}, from={}, to={})", symbol, from, to);
				terminatedEarly = true;
				break;
			}
			cursorEnd = nextCursorEnd;
		}
		// 요청 구간의 시작(from)에 닿지 못하고 **진짜로 페이지 상한을 다 써서** 끝난 경우에만 경고한다 — 위 세 break
		// 중 하나로 끝난 경우(정상 종료·목표 도달·커서 정체, 각자 이유가 다르거나 이미 자체 로그가 있음)는 페이지
		// 상한과 무관하므로 제외한다. StockDailyCandleCollector의 다음 실행은 "가장 최근 저장 거래일 다음날"부터만
		// 다시 조회하므로(plan.md "결정 1"), 진짜 상한에 막힌 구간은 자동으로 다시 채워지지 않는다 — 운영자가
		// 알아챌 수 있도록 경고만 남긴다(STOCK-DAILY-002 리뷰 지적).
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

	// 종목 간 호출에도 간격을 둔다(STOCK-DAILY-009). kis.request-interval-ms가 0이면(기본값) 대기하지 않는다.
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

	// 초당 호출 제한(EGW00201)만 재시도한다 — 1분봉 클라이언트와 같은 근거(2026-07-30 실측 성공률 약 67%). 다른
	// 오류(인증 실패·도메인 불일치 등)는 재시도해도 달라지지 않으므로 그대로 던진다.
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

	// 재시도 간 대기는 호출 간격의 배수로 늘린다 — 간격 설정이 0이면 최소 대기값을 쓴다.
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

	// plan.md "입력 명세" 표의 응답 검증 규칙을 적용한다 — 위반 행은 그 행만 버리고(null 반환) 나머지 행은 그대로
	// 쓴다(STOCK-DAILY-010의 "부분성공" 근거를 이 경계에서 만든다). 개수가 적다는 사실 자체는 오류가 아니다.
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

	// C-006(정규화된 필드만 저장) — output2의 다른 필드(거래대금·전일대비 등)는 매핑하지 않는다.
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
