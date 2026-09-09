package com.finplay.api.domain.feedback.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.finplay.api.domain.feedback.config.DartProperties;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

class DartDisclosureCollectorTest {

	private static final String BASE_URL = "https://opendart.fss.or.kr";
	private static final String LIST_PATH = "/api/list.json";
	private static final String API_KEY = "test-dart-api-key";
	private static final String SYMBOL = "005930";

	private static final String CORP_CODE = "00999999";

	private static final LocalDate COLLECTION_DATE = LocalDate.of(2026, 8, 4);

	private RestClient.Builder builder;

	private MockRestServiceServer server;

	private DartCorpCodeRegistry corpCodeRegistry;

	private DartDisclosureCollector collector;

	@BeforeEach
	void setUp() {
		builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		corpCodeRegistry = mock(DartCorpCodeRegistry.class);
		when(corpCodeRegistry.findCorpCode(SYMBOL)).thenReturn(Optional.of(CORP_CODE));
		collector = new DartDisclosureCollector(
			builder.build(), new DartProperties(API_KEY), corpCodeRegistry);
	}

	@Test
	@DisplayName("고정 응답이 제목·DART·공시 원문 URL·접수일자 00:00:00으로 매핑된다")
	void mapsFixedResponseToDisclosureFields() {
		respondWith(okResponse(
			item("주요사항보고서(유상증자결정)", "20260804000123", "20260804"),
			item("분기보고서", "20260803000777", "20260803")));

		List<CollectedNewsDto> collected = collector.collect(stock(), COLLECTION_DATE);

		assertThat(collected).hasSize(2);
		CollectedNewsDto first = collected.get(0);
		assertThat(first.title()).isEqualTo("주요사항보고서(유상증자결정)");
		assertThat(first.publisher()).isEqualTo("DART");
		assertThat(first.url()).isEqualTo("https://dart.fss.or.kr/dsaf001/main.do?rcpNo=20260804000123");
		assertThat(first.publishedAt()).isEqualTo(LocalDateTime.of(2026, 8, 4, 0, 0));

		assertThat(collected.get(1).publishedAt()).isEqualTo(LocalDateTime.of(2026, 8, 3, 0, 0));
		assertThat(collected).extracting(CollectedNewsDto::publisher).containsOnly("DART");
	}

	@Test
	@DisplayName("등재된 종목은 종목코드가 아니라 corp_code로, 전일~당일 구간으로 조회한다")
	void queriesWithCorpCodeAndYesterdayToTodayRange() {
		server.expect(dartListRequest())
			.andExpect(method(HttpMethod.GET))
			.andExpect(decodedQueryContains("crtfc_key=" + API_KEY))
			.andExpect(decodedQueryContains("corp_code=" + CORP_CODE))
			.andExpect(decodedQueryContains("bgn_de=20260803"))
			.andExpect(decodedQueryContains("end_de=20260804"))
			.andExpect(decodedQueryDoesNotContain(SYMBOL))
			.andRespond(withSuccess(okResponse(), MediaType.APPLICATION_JSON));

		collector.collect(stock(), COLLECTION_DATE);

		server.verify();
	}

	@Test
	@DisplayName("corp_code 매핑에 없는 종목은 예외 없이, 호출도 없이 건너뛴다")
	void skipsInstrumentWithoutCorpCodeMappingAndMakesNoCall() {
		Instrument unmapped = stock("000660");

		assertThatCode(() -> assertThat(collector.collect(unmapped, COLLECTION_DATE)).isEmpty())
			.doesNotThrowAnyException();
		server.verify();
	}

	@Test
	@DisplayName("DART 호출이 실패해도 예외 없이 빈 목록으로 끝난다")
	void returnsEmptyListWithoutThrowingWhenCallFails() {
		respondWithStatus(HttpStatus.INTERNAL_SERVER_ERROR);

		assertThatCode(() -> assertThat(collector.collect(stock(), COLLECTION_DATE)).isEmpty())
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("한 종목의 실패가 다음 종목의 수집을 막지 않는다")
	void keepsCollectingOtherInstrumentsAfterOneFails() {
		when(corpCodeRegistry.findCorpCode("000660")).thenReturn(Optional.of("00888888"));
		respondWithStatus(HttpStatus.INTERNAL_SERVER_ERROR);
		respondWith(okResponse(item("분기보고서", "20260804000999", "20260804")));

		assertThat(collector.collect(stock(), COLLECTION_DATE)).isEmpty();
		assertThat(collector.collect(stock("000660"), COLLECTION_DATE)).hasSize(1);
	}

	@Test
	@DisplayName("본문 status 013(조회 결과 없음)은 경고 없이 빈 목록이다")
	void treatsNoDataStatusAsQuietEmptyResult() {
		respondWith("{\"status\":\"013\",\"message\":\"조회된 데이터가 없습니다.\"}");

		List<ILoggingEvent> logs = collectCapturingLogs(() -> collector.collect(stock(), COLLECTION_DATE));

		assertThat(logs).noneMatch(event -> event.getLevel() == Level.WARN);
	}

	@Test
	@DisplayName("본문 status가 오류면 빈 목록이되 status를 담은 경고를 남긴다")
	void warnsWithStatusWhenBodyStatusIsNotOk() {
		respondWith("{\"status\":\"020\",\"message\":\"등록되지 않은 키입니다.\"}");

		List<ILoggingEvent> logs = collectCapturingLogs(() -> collector.collect(stock(), COLLECTION_DATE));

		assertThat(logs)
			.filteredOn(event -> event.getLevel() == Level.WARN)
			.singleElement()
			.satisfies(event -> assertThat(event.getFormattedMessage()).contains("020"));
	}

	@Test
	@DisplayName("status 000이지만 목록이 없는 응답은 빈 목록이다")
	void returnsEmptyListWhenOkResponseHasNoList() {
		respondWith("{\"status\":\"000\",\"message\":\"정상\"}");

		assertThat(collector.collect(stock(), COLLECTION_DATE)).isEmpty();
	}

	@Test
	@DisplayName("접수번호가 없거나 접수일자가 깨진 항목만 건너뛰고 나머지는 남는다")
	void skipsOnlyUnusableItems() {
		respondWith(okResponse(
			item("접수번호 없는 공시", "", "20260804"),
			item("접수일자 깨진 공시", "20260804000001", "2026-08-04"),
			item("정상 공시", "20260804000002", "20260804")));

		List<CollectedNewsDto> collected = collector.collect(stock(), COLLECTION_DATE);

		assertThat(collected).extracting(CollectedNewsDto::title).containsExactly("정상 공시");
	}

	private static List<ILoggingEvent> collectCapturingLogs(Supplier<List<CollectedNewsDto>> action) {
		Logger logger = (Logger)LoggerFactory.getLogger(DartDisclosureCollector.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		Level originalLevel = logger.getLevel();
		logger.setLevel(Level.DEBUG);
		logger.addAppender(appender);
		try {
			assertThat(action.get()).isEmpty();
			return List.copyOf(appender.list);
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(originalLevel);
			appender.stop();
		}
	}

	private void respondWith(String body) {
		server.expect(dartListRequest()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	private void respondWithStatus(HttpStatus status) {
		server.expect(dartListRequest()).andRespond(withStatus(status));
	}

	private static RequestMatcher dartListRequest() {
		return request -> {
			assertThat(request.getURI().getScheme()).isEqualTo("https");
			assertThat(request.getURI().getHost()).isEqualTo("opendart.fss.or.kr");
			assertThat(request.getURI().getPath()).isEqualTo(LIST_PATH);
		};
	}

	private static RequestMatcher decodedQueryContains(String expected) {
		return request -> assertThat(decodedQuery(request.getURI().getRawQuery())).contains(expected);
	}

	private static RequestMatcher decodedQueryDoesNotContain(String unexpected) {
		return request -> assertThat(decodedQuery(request.getURI().getRawQuery())).doesNotContain(unexpected);
	}

	private static String decodedQuery(String rawQuery) {
		return URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);
	}

	private static String okResponse(String... itemJson) {
		return "{\"status\":\"000\",\"message\":\"정상\",\"list\":[" + String.join(",", itemJson) + "]}";
	}

	private static String item(String reportName, String receiptNo, String receiptDate) {
		return """
			{"corp_code":"%s","corp_name":"삼성전자","stock_code":"%s","report_nm":"%s",\
			"rcept_no":"%s","flr_nm":"삼성전자","rcept_dt":"%s","rm":""}
			""".formatted(CORP_CODE, SYMBOL, reportName, receiptNo, receiptDate);
	}

	private static Instrument stock() {
		return stock(SYMBOL);
	}

	private static Instrument stock(String symbol) {
		return Instrument.create(
			Market.STOCK, symbol, "삼성전자", new BigDecimal("100"), 70000, true, LocalDateTime.now());
	}
}
