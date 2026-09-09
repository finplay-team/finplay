package com.finplay.api.domain.feedback.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.finplay.api.domain.feedback.config.NaverSearchProperties;
import com.finplay.api.domain.feedback.service.NewsSearchQueryBuilder;
import com.finplay.api.domain.feedback.service.NewsTitleFilter;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

class NaverNewsCollectorTest {

	private static final String BASE_URL = "https://naverapihub.apigw.ntruss.com";
	private static final String SEARCH_PATH = "/search/v1/news";
	private static final String CLIENT_ID = "test-search-client-id";
	private static final String CLIENT_SECRET = "test-search-client-secret";

	private static final String SNIPPET = "비트코인이 사상 최고가를 경신했다. 기관 자금 유입이 이어지며 시장은...";

	private static final List<String> CRYPTO_NAMES = List.of(
		"비트코인", "이더리움", "리플", "솔라나", "도지코인", "에이다",
		"트론", "아발란체", "체인링크", "폴카닷", "비트코인캐시", "이더리움클래식");

	private RestClient.Builder builder;

	private MockRestServiceServer server;

	private NaverNewsCollector collector;

	@BeforeEach
	void setUp() {
		builder = RestClient.builder().baseUrl(BASE_URL);
		server = MockRestServiceServer.bindTo(builder).build();
		collector = new NaverNewsCollector(
			builder.build(),
			new NaverSearchProperties(CLIENT_ID, CLIENT_SECRET),
			new NewsSearchQueryBuilder(),
			new NewsTitleFilter());
	}

	@Test
	@DisplayName("고정 응답이 제목·언론사·원문 URL·발행시각 네 값으로 매핑된다")
	void mapsFixedResponseToTitlePublisherUrlAndPublishedAt() {
		respondWith(items(item(
			"<b>비트코인</b> 사상 최고가 &quot;랠리&quot;",
			"https://www.hankyung.com/article/2026080312345",
			"https://n.news.naver.com/mnews/article/015/0005123456",
			SNIPPET,
			"Mon, 03 Aug 2026 14:23:00 +0900")));

		List<CollectedNewsDto> collected = collector.collect(crypto("비트코인"), CRYPTO_NAMES);

		assertThat(collected).hasSize(1);
		CollectedNewsDto news = collected.get(0);
		assertThat(news.title()).isEqualTo("비트코인 사상 최고가 \"랠리\"");
		assertThat(news.publisher()).isEqualTo("hankyung.com");
		assertThat(news.url()).isEqualTo("https://www.hankyung.com/article/2026080312345");
		assertThat(news.publishedAt()).isEqualTo(LocalDateTime.of(2026, 8, 3, 14, 23));
	}

	@Test
	@DisplayName("응답의 요약 스니펫이 결과 어디에도 담기지 않는다")
	void neverCarriesDescriptionSnippetIntoTheApplication() {
		respondWith(items(item(
			"비트코인 사상 최고가",
			"https://www.hankyung.com/article/2026080312345",
			"https://n.news.naver.com/mnews/article/015/0005123456",
			SNIPPET,
			"Mon, 03 Aug 2026 14:23:00 +0900")));

		List<CollectedNewsDto> collected = collector.collect(crypto("비트코인"), CRYPTO_NAMES);

		assertThat(collected).hasSize(1);
		assertThat(collected.get(0).toString()).doesNotContain(SNIPPET);
		assertThat(Arrays.stream(CollectedNewsDto.class.getRecordComponents())
			.map(RecordComponent::getName)
			.toList())
			.containsExactly("title", "publisher", "url", "publishedAt");
	}

	@Test
	@DisplayName("§외부 API 호출 상세의 엔드포인트·질의 파라미터·자격증명 헤더로 호출한다")
	void callsSpecifiedEndpointWithQueryDisplaySortAndCredentialHeaders() {
		server.expect(naverSearchRequest())
			.andExpect(method(HttpMethod.GET))
			.andExpect(decodedQueryContains("query=비트코인 BTC"))
			.andExpect(decodedQueryContains("display=100"))
			.andExpect(decodedQueryContains("sort=date"))
			.andExpect(header("X-NCP-APIGW-API-KEY-ID", CLIENT_ID))
			.andExpect(header("X-NCP-APIGW-API-KEY", CLIENT_SECRET))
			.andRespond(withSuccess(items(), MediaType.APPLICATION_JSON));

		collector.collect(crypto("비트코인"), CRYPTO_NAMES);

		server.verify();
	}

	@Test
	@DisplayName("발행 시각이 제각각이어도 한 건도 걸러지지 않고 그대로 나온다")
	void keepsEveryArticleRegardlessOfPublishedDate() {
		respondWith(items(
			item("비트코인 저녁 기사", "https://www.hankyung.com/a/1", "https://n.news.naver.com/1",
				SNIPPET, "Sun, 02 Aug 2026 19:40:00 +0900"),
			item("비트코인 심야 기사", "https://www.hankyung.com/a/2", "https://n.news.naver.com/2",
				SNIPPET, "Mon, 03 Aug 2026 02:10:00 +0900"),
			item("비트코인 아침 기사", "https://www.hankyung.com/a/3", "https://n.news.naver.com/3",
				SNIPPET, "Mon, 03 Aug 2026 08:30:00 +0900"),
			item("비트코인 오래된 기사", "https://www.hankyung.com/a/4", "https://n.news.naver.com/4",
				SNIPPET, "Mon, 11 May 2026 11:00:00 +0900")));

		List<CollectedNewsDto> collected = collector.collect(crypto("비트코인"), CRYPTO_NAMES);

		assertThat(collected).extracting(CollectedNewsDto::publishedAt).containsExactly(
			LocalDateTime.of(2026, 8, 2, 19, 40),
			LocalDateTime.of(2026, 8, 3, 2, 10),
			LocalDateTime.of(2026, 8, 3, 8, 30),
			LocalDateTime.of(2026, 5, 11, 11, 0));
	}

	@Test
	@DisplayName("오프셋이 다른 발행시각도 KST 벽시계로 맞춰 담는다")
	void convertsPublishedAtToKoreanWallClock() {
		respondWith(items(item(
			"비트코인 해외발 기사",
			"https://www.hankyung.com/a/5",
			"https://n.news.naver.com/5",
			SNIPPET,
			"Sun, 02 Aug 2026 22:00:00 +0000")));

		List<CollectedNewsDto> collected = collector.collect(crypto("비트코인"), CRYPTO_NAMES);

		assertThat(collected).hasSize(1);
		assertThat(collected.get(0).publishedAt()).isEqualTo(LocalDateTime.of(2026, 8, 3, 7, 0));
	}

	@Test
	@DisplayName("서버 오류가 나도 예외 없이 그 종목만 빈 목록으로 끝난다")
	void returnsEmptyListWithoutThrowingWhenCallFails() {
		respondWithStatus(HttpStatus.INTERNAL_SERVER_ERROR);

		assertThatCode(() -> assertThat(collector.collect(crypto("비트코인"), CRYPTO_NAMES)).isEmpty())
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("자격증명이 거부돼도(401) 예외 없이 빈 목록으로 끝난다")
	void returnsEmptyListWithoutThrowingWhenUnauthorized() {
		respondWithStatus(HttpStatus.UNAUTHORIZED);

		assertThatCode(() -> assertThat(collector.collect(crypto("비트코인"), CRYPTO_NAMES)).isEmpty())
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("태그를 걷어낸 제목이 필터에 들어가 다른 종목 기사가 제외된다")
	void stripsTagsBeforeTitleFilterSoSiblingArticleIsExcluded() {
		respondWith(items(item(
			"<b>비트코인</b>캐시 급등에 거래량 3배",
			"https://www.hankyung.com/a/6",
			"https://n.news.naver.com/6",
			SNIPPET,
			"Mon, 03 Aug 2026 14:23:00 +0900")));

		List<CollectedNewsDto> collected = collector.collect(crypto("비트코인"), CRYPTO_NAMES);

		assertThat(collected).isEmpty();
	}

	@Test
	@DisplayName("같은 응답을 형제 종목으로 수집하면 태그가 걷힌 제목으로 남는다")
	void keepsSiblingArticleWithTagsStrippedFromTitle() {
		respondWith(items(item(
			"<b>비트코인</b>캐시 급등에 거래량 3배",
			"https://www.hankyung.com/a/6",
			"https://n.news.naver.com/6",
			SNIPPET,
			"Mon, 03 Aug 2026 14:23:00 +0900")));

		List<CollectedNewsDto> collected = collector.collect(crypto("비트코인캐시"), CRYPTO_NAMES);

		assertThat(collected).hasSize(1);
		assertThat(collected.get(0).title()).isEqualTo("비트코인캐시 급등에 거래량 3배");
		assertThat(collected.get(0).title()).doesNotContain("<b>", "&");
	}

	@Test
	@DisplayName("publisher는 originallink 호스트에서 www.만 뗀 도메인이다")
	void derivesPublisherFromOriginallinkHostWithoutWwwPrefix() {
		respondWith(items(
			item("비트코인 기사 하나", "https://www.hankyung.com/a/7", "https://n.news.naver.com/7",
				SNIPPET, "Mon, 03 Aug 2026 09:00:00 +0900"),
			item("비트코인 기사 둘", "https://biz.chosun.com/a/8", "https://n.news.naver.com/8",
				SNIPPET, "Mon, 03 Aug 2026 09:10:00 +0900")));

		List<CollectedNewsDto> collected = collector.collect(crypto("비트코인"), CRYPTO_NAMES);

		assertThat(collected).extracting(CollectedNewsDto::publisher)
			.containsExactly("hankyung.com", "biz.chosun.com");
	}

	@Test
	@DisplayName("originallink가 비면 네이버 뉴스 링크로 폴백하고 publisher도 그 호스트에서 나온다")
	void fallsBackToNaverLinkWhenOriginallinkIsBlank() {
		respondWith(items(item(
			"비트코인 폴백 기사",
			"",
			"https://n.news.naver.com/mnews/article/015/0005123456",
			SNIPPET,
			"Mon, 03 Aug 2026 09:20:00 +0900")));

		List<CollectedNewsDto> collected = collector.collect(crypto("비트코인"), CRYPTO_NAMES);

		assertThat(collected).hasSize(1);
		assertThat(collected.get(0).url())
			.isEqualTo("https://n.news.naver.com/mnews/article/015/0005123456");
		assertThat(collected.get(0).publisher()).isEqualTo("n.news.naver.com");
	}

	@Test
	@DisplayName("originallink에 공백·| 가 섞여 파싱되지 않아도 네이버 링크로 폴백한다")
	void fallsBackToNaverLinkWhenOriginallinkCannotBeParsed() {
		respondWith(items(item(
			"비트코인 깨진 원문링크 기사",
			"https://www.hankyung.com/article/2026 08|03",
			"https://n.news.naver.com/mnews/article/015/0005123456",
			SNIPPET,
			"Mon, 03 Aug 2026 09:30:00 +0900")));

		List<CollectedNewsDto> collected = collector.collect(crypto("비트코인"), CRYPTO_NAMES);

		assertThat(collected).hasSize(1);
		assertThat(collected.get(0).url())
			.isEqualTo("https://n.news.naver.com/mnews/article/015/0005123456");
		assertThat(collected.get(0).publisher()).isEqualTo("n.news.naver.com");
	}

	@Test
	@DisplayName("originallink 호스트에 언더스코어가 있어 호스트를 못 뽑아도 네이버 링크로 폴백한다")
	void fallsBackToNaverLinkWhenOriginallinkHostIsNotExtractable() {
		respondWith(items(item(
			"비트코인 언더스코어 호스트 기사",
			"https://news_site.example.com/article/2026080312345",
			"https://n.news.naver.com/mnews/article/015/0005123456",
			SNIPPET,
			"Mon, 03 Aug 2026 09:40:00 +0900")));

		List<CollectedNewsDto> collected = collector.collect(crypto("비트코인"), CRYPTO_NAMES);

		assertThat(collected).hasSize(1);
		assertThat(collected.get(0).url())
			.isEqualTo("https://n.news.naver.com/mnews/article/015/0005123456");
		assertThat(collected.get(0).publisher()).isEqualTo("n.news.naver.com");
	}

	@Test
	@DisplayName("두 후보 모두 언론사를 못 뽑을 때만 기사를 버리고 나머지는 남긴다")
	void dropsArticleOnlyWhenBothCandidatesAreUnusable() {
		respondWith(items(
			item("비트코인 둘 다 깨진 기사", "https://www.hankyung.com/a b|c",
				"https://news_site.example.com/9", SNIPPET, "Mon, 03 Aug 2026 09:50:00 +0900"),
			item("비트코인 멀쩡한 기사", "https://www.hankyung.com/a/10",
				"https://n.news.naver.com/10", SNIPPET, "Mon, 03 Aug 2026 09:55:00 +0900")));

		List<CollectedNewsDto> collected = collector.collect(crypto("비트코인"), CRYPTO_NAMES);

		assertThat(collected).extracting(CollectedNewsDto::title)
			.containsExactly("비트코인 멀쩡한 기사");
		assertThat(collected).extracting(CollectedNewsDto::publisher).containsExactly("hankyung.com");
	}

	@Test
	@DisplayName("결과가 없는 응답은 오류가 아니라 빈 목록이다")
	void returnsEmptyListWhenResponseHasNoItems() {
		respondWith(items());

		assertThat(collector.collect(crypto("비트코인"), CRYPTO_NAMES)).isEmpty();
	}

	private void respondWith(String body) {
		server.expect(naverSearchRequest())
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
	}

	private void respondWithStatus(HttpStatus status) {
		server.expect(naverSearchRequest()).andRespond(withStatus(status));
	}

	private static RequestMatcher naverSearchRequest() {
		return request -> {
			assertThat(request.getURI().getScheme()).isEqualTo("https");
			assertThat(request.getURI().getHost()).isEqualTo("naverapihub.apigw.ntruss.com");
			assertThat(request.getURI().getPath()).isEqualTo(SEARCH_PATH);
		};
	}

	private static RequestMatcher decodedQueryContains(String expected) {
		return request -> assertThat(
			URLDecoder.decode(request.getURI().getRawQuery(), StandardCharsets.UTF_8))
			.contains(expected);
	}

	private static String items(String... itemJson) {
		return "{\"items\":[" + String.join(",", itemJson) + "]}";
	}

	@Test
	@DisplayName("제목이 500자를 넘고 경계가 서로게이트 쌍의 가운데면 그 글자를 통째로 버린다")
	void doesNotSplitASurrogatePairWhenTruncatingTheTitle() {
		String title = "가".repeat(499) + "🚀" + "나".repeat(10);

		String cleaned = NaverNewsCollector.cleanTitle(title);

		assertThat(cleaned).hasSize(499);
		assertThat(cleaned).isEqualTo("가".repeat(499));
		assertThat(cleaned.chars().anyMatch(unit -> Character.isSurrogate((char)unit))).isFalse();
	}

	@Test
	@DisplayName("경계가 온전한 글자 사이면 500자를 그대로 담는다")
	void keepsFiveHundredCharactersWhenTheBoundaryIsClean() {
		String cleaned = NaverNewsCollector.cleanTitle("가".repeat(600));

		assertThat(cleaned).hasSize(500);
	}

	private static String item(
		String title, String originallink, String link, String description, String pubDate) {
		return """
			{"title":"%s","originallink":"%s","link":"%s","description":"%s","pubDate":"%s"}
			""".formatted(title, originallink, link, description, pubDate);
	}

	private static Instrument crypto(String name) {
		String symbol = switch (name) {
			case "비트코인" -> "BTC";
			case "비트코인캐시" -> "BCH";
			default -> throw new IllegalArgumentException("시드 심볼을 여기 추가해라 — " + name);
		};
		return Instrument.create(
			Market.CRYPTO, symbol, name, new BigDecimal("1000"), 5000, true, LocalDateTime.now());
	}
}
