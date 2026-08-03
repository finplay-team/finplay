// OpenDART 공시검색 API로 종목별 공시를 가져오는 운영 프로필 전용 DisclosureCollector 구현.
package com.finplay.api.feedback.collector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.feedback.config.DartProperties;
import com.finplay.api.market.domain.Instrument;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 엔드포인트·조회 구간·시각 규칙은 spec §외부 API 호출 상세가, 날짜 축의 의미는 §C-3이,
 * {@code publisher} 고정값은 §C-8이 정본이다.
 *
 * <p><b>종목코드가 아니라 {@code corp_code}로 조회한다.</b> 매핑은 {@code DartCorpCodeRegistry}가 리소스에서
 * 읽고, <b>매핑에 없는 종목은 오류가 아니라 건너뛴다</b>(FEED-001).
 *
 * <p><b>조회 구간은 전일부터 당일까지다.</b> 접수 지연분을 잡기 위한 것이며(§외부 API 호출 상세), 그렇게
 * 겹쳐 받아도 저장 단계의 {@code UNIQUE(instrument_id, url)}가 중복을 막는다(FEED-001).
 *
 * <p><b>OpenDART는 실패해도 HTTP 200을 준다.</b> 본문의 {@code status}가 결과를 말하므로 그 값을 봐야 한다 —
 * HTTP 상태만 보면 "조회된 데이터 없음"과 "키가 틀림"이 똑같이 성공으로 보인다. {@code 000}만 정상으로 다루고
 * 나머지는 {@code WARN}과 함께 빈 목록으로 접는다. 조회 결과 없음({@code 013})은 흔한 정상 상태라 조용히 넘긴다.
 *
 * <p><b>DART 호출 실패가 뉴스 경로를 막지 않는다</b>(§실패 처리 — "공시 없이 뉴스만으로 진행").
 *
 * <p>{@code RestClient}를 빈으로 등록하지 않고 {@code RestClient.Builder}로 여기서 완성하는 이유는
 * {@code NaverNewsCollector}와 같다 — 같은 타입 빈이 늘면 {@code KisHistoricalCandleClientImpl}의 주입이
 * {@code NoUniqueBeanDefinitionException}으로 깨져 컨텍스트 전체가 기동하지 않는다
 * ({@code docs/agent-mistakes.md} 2026-08-04 항목).
 */
@Slf4j
@Component
@Profile("prod")
public class DartDisclosureCollector implements DisclosureCollector {

	// spec §외부 API 호출 상세의 엔드포인트.
	private static final String DART_BASE_URL = "https://opendart.fss.or.kr";
	private static final String LIST_PATH = "/api/list.json";
	// 공시 원문 뷰어. rcept_no 하나로 열린다.
	private static final String VIEWER_URL_PREFIX = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=";
	private static final String STATUS_OK = "000";
	// "조회된 데이터가 없습니다" — 공시가 없는 날이 대부분이라 오류로 다루지 않는다.
	private static final String STATUS_NO_DATA = "013";
	// §C-8 — 공시의 publisher는 고정값이다.
	private static final String DISCLOSURE_PUBLISHER = "DART";
	private static final int TITLE_MAX_LENGTH = 500;
	private static final int URL_MAX_LENGTH = 500;
	private static final DateTimeFormatter DART_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
	// 타임아웃 값은 KisRestClientConfig·NaverNewsCollector와 같게 맞췄다.
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	private final RestClient restClient;

	private final DartProperties properties;

	private final DartCorpCodeRegistry corpCodeRegistry;

	@Autowired
	public DartDisclosureCollector(
		RestClient.Builder builder, DartProperties properties, DartCorpCodeRegistry corpCodeRegistry) {
		this(applyTimeouts(builder).baseUrl(DART_BASE_URL).build(), properties, corpCodeRegistry);
	}

	// 테스트 전용: MockRestServiceServer로 이미 구성된 RestClient를 직접 주입한다 (타임아웃 팩토리를 거치지 않는다).
	DartDisclosureCollector(
		RestClient restClient, DartProperties properties, DartCorpCodeRegistry corpCodeRegistry) {
		this.restClient = restClient;
		this.properties = properties;
		this.corpCodeRegistry = corpCodeRegistry;
	}

	private static RestClient.Builder applyTimeouts(RestClient.Builder builder) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
		requestFactory.setReadTimeout(READ_TIMEOUT);
		return builder.requestFactory(requestFactory);
	}

	@Override
	public List<CollectedNewsDto> collect(Instrument instrument, LocalDate collectionDate) {
		Optional<String> corpCode = corpCodeRegistry.findCorpCode(instrument.getSymbol());
		if (corpCode.isEmpty()) {
			// 매핑에 없는 종목은 건너뛴다. 오류가 아니다 (FEED-001).
			log.debug("OpenDART corp_code 매핑이 없어 공시 수집을 건너뜁니다 (symbol={})", instrument.getSymbol());
			return List.of();
		}
		DartDisclosureListResponse response;
		try {
			response = search(corpCode.get(), collectionDate);
		} catch (RestClientException ex) {
			// 공시 없이 뉴스만으로 진행한다 (§실패 처리). 예외를 밖으로 내보내면 뉴스 경로까지 함께 멈춘다.
			log.warn("OpenDART 공시 조회에 실패해 이 종목을 건너뜁니다 (symbol={}, corpCode={}): {}",
				instrument.getSymbol(), corpCode.get(), ex.toString());
			return List.of();
		}
		if (response == null || !STATUS_OK.equals(response.status())) {
			logNonOkStatus(instrument, response);
			return List.of();
		}
		if (response.list() == null) {
			return List.of();
		}
		List<CollectedNewsDto> collected = new ArrayList<>();
		for (DartDisclosureItem item : response.list()) {
			toCollectedDisclosure(item).ifPresent(collected::add);
		}
		return List.copyOf(collected);
	}

	private DartDisclosureListResponse search(String corpCode, LocalDate collectionDate) {
		// 전일부터 당일까지 훑어 접수 지연분을 잡는다 (§외부 API 호출 상세).
		String beginDate = collectionDate.minusDays(1).format(DART_DATE_FORMAT);
		String endDate = collectionDate.format(DART_DATE_FORMAT);
		return restClient
			.get()
			.uri(uriBuilder -> uriBuilder
				.path(LIST_PATH)
				.queryParam("crtfc_key", properties.apiKey())
				.queryParam("corp_code", corpCode)
				.queryParam("bgn_de", beginDate)
				.queryParam("end_de", endDate)
				.build())
			.accept(MediaType.APPLICATION_JSON)
			.retrieve()
			.body(DartDisclosureListResponse.class);
	}

	private static void logNonOkStatus(Instrument instrument, DartDisclosureListResponse response) {
		String status = response == null ? null : response.status();
		if (STATUS_NO_DATA.equals(status)) {
			log.debug("OpenDART 공시 조회 결과가 없습니다 (symbol={})", instrument.getSymbol());
			return;
		}
		log.warn("OpenDART 공시 조회가 정상 상태가 아니어서 이 종목을 건너뜁니다 (symbol={}, status={}, message={})",
			instrument.getSymbol(), status, response == null ? null : response.message());
	}

	// 저장할 수 없는 항목은 예외 대신 건너뛴다 — 한 건이 망가졌다고 그 종목의 나머지를 버리지 않는다.
	private static Optional<CollectedNewsDto> toCollectedDisclosure(DartDisclosureItem item) {
		if (item.rcept_no() == null || item.rcept_no().isBlank() || item.report_nm() == null) {
			return Optional.empty();
		}
		String url = VIEWER_URL_PREFIX + item.rcept_no();
		if (url.length() > URL_MAX_LENGTH) {
			return Optional.empty();
		}
		String title = item.report_nm().trim();
		if (title.isEmpty()) {
			return Optional.empty();
		}
		if (title.length() > TITLE_MAX_LENGTH) {
			title = title.substring(0, TITLE_MAX_LENGTH);
		}
		String finalTitle = title;
		return parseReceiptDate(item.rcept_dt())
			.map(publishedAt -> new CollectedNewsDto(finalTitle, DISCLOSURE_PUBLISHER, url, publishedAt));
	}

	/**
	 * {@code rcept_dt}는 {@code YYYYMMDD}뿐이라 {@code published_at}은 그 날짜의 {@code 00:00:00}이다
	 * (§외부 API 호출 상세). 시각이 없다는 사실 자체가 §C-3이 공시를 datetime 구간이 아니라 <b>날짜</b>로
	 * 판정하는 이유이므로, 여기서 임의의 시각을 채워 넣지 않는다.
	 */
	private static Optional<LocalDateTime> parseReceiptDate(String receiptDate) {
		if (receiptDate == null || receiptDate.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(LocalDate.parse(receiptDate.trim(), DART_DATE_FORMAT).atStartOfDay());
		} catch (DateTimeParseException ex) {
			log.warn("OpenDART 접수일자를 해석하지 못해 이 공시를 건너뜁니다 (rcept_dt={})", receiptDate);
			return Optional.empty();
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DartDisclosureListResponse(String status, String message, List<DartDisclosureItem> list) {
	}

	// OpenDART 응답의 필드명을 그대로 쓴다 (snake_case). 필요한 셋만 매핑한다.
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DartDisclosureItem(String report_nm, String rcept_no, String rcept_dt) {
	}
}
