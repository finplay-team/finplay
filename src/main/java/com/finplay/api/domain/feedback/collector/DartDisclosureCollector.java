package com.finplay.api.domain.feedback.collector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.domain.feedback.config.DartProperties;
import com.finplay.api.domain.market.entity.Instrument;
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

@Slf4j
@Component
@Profile("prod")
public class DartDisclosureCollector implements DisclosureCollector {

	private static final String DART_BASE_URL = "https://opendart.fss.or.kr";
	private static final String LIST_PATH = "/api/list.json";
	private static final String VIEWER_URL_PREFIX = "https://dart.fss.or.kr/dsaf001/main.do?rcpNo=";
	private static final String STATUS_OK = "000";
	private static final String STATUS_NO_DATA = "013";
	private static final String DISCLOSURE_PUBLISHER = "DART";
	private static final int TITLE_MAX_LENGTH = 500;
	private static final int URL_MAX_LENGTH = 500;
	private static final DateTimeFormatter DART_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
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
			log.debug("OpenDART corp_code 매핑이 없어 공시 수집을 건너뜁니다 (symbol={})", instrument.getSymbol());
			return List.of();
		}
		DartDisclosureListResponse response;
		try {
			response = search(corpCode.get(), collectionDate);
		} catch (RestClientException ex) {
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

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record DartDisclosureItem(String report_nm, String rcept_no, String rcept_dt) {
	}
}
