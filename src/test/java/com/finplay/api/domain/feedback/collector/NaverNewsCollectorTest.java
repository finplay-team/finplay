// 네이버 뉴스 검색 응답 매핑·제목 세척·발행일자 무필터·호출 실패 흡수를 MockRestServiceServer로 검증한다.
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

// 실제 네이버 API를 부르지 않는다 (PRD C-005 — 자동 테스트의 외부 네트워크 의존 금지). 응답은
// MockRestServiceServer로 고정한다 (KisHistoricalCandleClientImplTest 선례).
//
// 기대값의 정본은 spec.md다 — 엔드포인트·질의 파라미터·헤더는 §외부 API 호출 상세, publisher 규칙과 컬럼 길이는
// §C-8, 저장 범위(본문·스니펫 금지)는 §정책 전제, 발행일자 무필터와 실패 흡수는 FEED-001·§실패 처리다.
//
// 질의어 조립과 제목 필터는 진짜 구현(NewsSearchQueryBuilder·NewsTitleFilter)을 주입한다. mock으로 바꾸면
// "씻은 제목이 필터에 들어가는가"라는 이 항목의 핵심 축이 검증에서 빠진다.
class NaverNewsCollectorTest {

	// §외부 API 호출 상세의 엔드포인트
	private static final String BASE_URL = "https://naverapihub.apigw.ntruss.com";
	private static final String SEARCH_PATH = "/search/v1/news";
	private static final String CLIENT_ID = "test-search-client-id";
	private static final String CLIENT_SECRET = "test-search-client-secret";

	// §정책 전제 — 이 문자열이 애플리케이션 안으로 들어오면 안 된다.
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

	// ① 고정 응답이 제목·언론사·URL·발행시각으로 매핑된다.
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

	// ① 본문·요약 스니펫이 어디에도 남지 않는다 (§정책 전제 — 저작권).
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
		// 담을 자리 자체가 없다는 것이 진짜 보증이다 — 필드가 넷뿐이어야 스니펫이 들어올 경로가 생기지 않는다.
		assertThat(Arrays.stream(CollectedNewsDto.class.getRecordComponents())
			.map(RecordComponent::getName)
			.toList())
			.containsExactly("title", "publisher", "url", "publishedAt");
	}

	// §외부 API 호출 상세 — query는 §FEED-001의 조립 결과(코인은 보정 포함), display 100, 최신순(date),
	// 자격증명은 두 헤더로 나간다.
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

	// ② 발행 시각이 제각각인 응답이 하나도 안 걸러진 채 그대로 나온다 (FEED-001 — 수집 단계는 발행일자로 거르지
	// 않는다). 저녁·심야·아침은 §C-2 `전장` 구간이고, 마지막 한 건은 어느 구간에도 걸리지 않는 3개월 전 기사다.
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

	// 이 서비스의 모든 시간축은 KST다(§C-2). 오프셋이 다른 pubDate가 와도 KST 벽시계로 맞춰 담아야 구간 판정이
	// 어긋나지 않는다.
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

	// ③ 호출이 실패해도 예외가 밖으로 나가지 않고 그 종목만 비어 돌아온다 (§실패 처리). 예외가 새면 수집 배치가
	// 통째로 멈춰 분봉 수집·재생세션 확정까지 말려든다(FEED-001).
	@Test
	@DisplayName("서버 오류가 나도 예외 없이 그 종목만 빈 목록으로 끝난다")
	void returnsEmptyListWithoutThrowingWhenCallFails() {
		respondWithStatus(HttpStatus.INTERNAL_SERVER_ERROR);

		assertThatCode(() -> assertThat(collector.collect(crypto("비트코인"), CRYPTO_NAMES)).isEmpty())
			.doesNotThrowAnyException();
	}

	// 운영에 키가 아직 없는 구간은 401로 실패한다 — 같은 표의 "그 종목만 건너뜀"으로 흡수돼야 한다.
	@Test
	@DisplayName("자격증명이 거부돼도(401) 예외 없이 빈 목록으로 끝난다")
	void returnsEmptyListWithoutThrowingWhenUnauthorized() {
		respondWithStatus(HttpStatus.UNAUTHORIZED);

		assertThatCode(() -> assertThat(collector.collect(crypto("비트코인"), CRYPTO_NAMES)).isEmpty())
			.doesNotThrowAnyException();
	}

	// 네이버는 질의어와 일치한 부분을 <b>로 감싸 돌려준다. 씻지 않은 제목으로 종목명을 찾으면 이름이 태그로
	// 쪼개져 제목 필터가 통째로 무력해진다 — 이 응답을 안 씻으면 "비트코인캐시"가 검출되지 않아 그대로 저장된다.
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

	// §C-8 — 뉴스의 publisher는 originallink 호스트에서 www.만 뗀 도메인이다. www가 아닌 서브도메인은 언론사
	// 구분에 쓰이므로 남긴다.
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

	// 회귀 — originallink가 "비어 있는" 경우만이 아니라 "파싱되지 않는" 경우에도 폴백이 타야 한다. 고치기 전에는
	// URI.create가 던지는 순간 멀쩡한 link가 있는데도 그 기사가 로그 한 줄 없이 사라졌다.
	// publisher가 네이버 호스트여야 통과한다 — originallink가 정상 파싱됐다면 hankyung.com이 나와 실패한다.
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

	// 회귀 — URI는 만들어지지만 호스트를 못 뽑는 경우다. 언더스코어가 든 호스트는 getHost()가 null을 준다.
	// publisher는 NOT NULL이라 호스트를 못 뽑은 URL은 애초에 쓸 수 없는 후보이므로 다음 후보로 넘어가야 한다.
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

	// 폴백이 넓어졌다고 쓰레기를 받아들이면 안 된다 — 두 후보 모두에서 언론사를 못 뽑을 때만 버리고,
	// 그 버림이 같은 응답의 멀쩡한 기사까지 데려가지 않는다.
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

	// 회귀(이슈 #408): 제목 절단이 서로게이트 쌍을 가르면 짝 없는 서로게이트가 남고, utf8mb4가 그 문자열을
	// 거부해 저장이 예외로 실패한다. 그 예외는 수집기가 아니라 save에서 나므로 "수집기는 빈 목록을 돌려준다"는
	// 계약으로 막히지 않는다 — 종목 하나의 수집이 통째로 죽는 자리였다.
	@Test
	@DisplayName("제목이 500자를 넘고 경계가 서로게이트 쌍의 가운데면 그 글자를 통째로 버린다")
	void doesNotSplitASurrogatePairWhenTruncatingTheTitle() {
		// 앞 499자는 BMP 문자, 500번째 코드 단위부터 이모지(서로게이트 쌍) — 경계가 정확히 쌍의 가운데다.
		String title = "가".repeat(499) + "🚀" + "나".repeat(10);

		String cleaned = NaverNewsCollector.cleanTitle(title);

		assertThat(cleaned).hasSize(499);
		assertThat(cleaned).isEqualTo("가".repeat(499));
		// 짝 없는 서로게이트가 남으면 이 단정이 깨진다 — 그 문자열은 유효한 UTF-8로 인코딩되지 않는다.
		assertThat(cleaned.chars().anyMatch(unit -> Character.isSurrogate((char)unit))).isFalse();
	}

	@Test
	@DisplayName("경계가 온전한 글자 사이면 500자를 그대로 담는다")
	void keepsFiveHundredCharactersWhenTheBoundaryIsClean() {
		String cleaned = NaverNewsCollector.cleanTitle("가".repeat(600));

		assertThat(cleaned).hasSize(500);
	}

	// 네이버 items[]의 실제 필드 구성이다 — title·originallink·link·description·pubDate가 전부다(§C-8).
	private static String item(
		String title, String originallink, String link, String description, String pubDate) {
		return """
			{"title":"%s","originallink":"%s","link":"%s","description":"%s","pubDate":"%s"}
			""".formatted(title, originallink, link, description, pubDate);
	}

	// 심볼이 질의어에 들어가므로(2026-08-07 개정, 이슈 #179) 자리표시자 대신 V7 시드의 실제 값을 쓴다 —
	// "SYM"으로 두면 질의어 단정이 실제 호출과 다른 문자열을 고정하게 된다.
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
