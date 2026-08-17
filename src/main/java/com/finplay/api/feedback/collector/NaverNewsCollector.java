// 네이버 뉴스 검색 API로 종목별 기사를 가져오는 운영 프로필 전용 NewsCollector 구현.
package com.finplay.api.feedback.collector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.feedback.config.NaverSearchProperties;
import com.finplay.api.feedback.service.NewsSearchQueryBuilder;
import com.finplay.api.feedback.service.NewsTitleFilter;
import com.finplay.api.market.domain.Instrument;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.HtmlUtils;

/**
 * 엔드포인트·질의 파라미터·헤더·{@code display} 상한은 spec §외부 API 호출 상세가 정본이다. 질의어 조립과
 * 제목 필터는 {@code NewsSearchQueryBuilder}·{@code NewsTitleFilter}를 주입받아 쓴다 — 여기서 다시 만들지 않는다.
 *
 * <p>{@code @Profile({"prod", "news-real"})}로 운영과 <b>로컬 실수집 프로필</b>에서만 등록되고, 그 밖의
 * 로컬·테스트는 {@code FakeNewsCollector}가 대신 뜬다 ({@code BithumbRestCandleProvider}의
 * {@code prod | crypto-real} 선례). 운영에 키가 아직 없는 구간은 호출이 401로 실패하고 §실패 처리의
 * "그 종목만 건너뜀"으로 흡수되므로 별도 키 검사를 두지 않는다.
 *
 * <p><b>{@code news-real}은 로컬에서 실제 기사를 받아 보려고 연 문이다</b> (이슈 #273). 그 전에는 로컬이
 * 무조건 {@code FakeNewsCollector}라 <b>키를 채워도 기사가 0건</b>이었고, 그 0건이 계약대로의 {@code EMPTY}로
 * 나가 수집 결함처럼 읽혔다. 켜는 방법은 {@code SPRING_PROFILES_ACTIVE=local,news-real}이다 — 공시(DART)는
 * 이 프로필로 바뀌지 않고 {@code FakeDisclosureCollector} 그대로다 (공시는 주식 전용이라 이슈 #273 범위 밖).
 *
 * <p><b>{@code publisher}는 {@code originallink}의 호스트에서 {@code www.}만 뗀 도메인이다</b>(§C-8).
 * 네이버 뉴스 검색 응답에는 <b>언론사 이름 필드가 없다</b> — {@code items[]}의 필드는 {@code title}·
 * {@code originallink}·{@code link}·{@code description}·{@code pubDate}가 전부이고, 그중 언론사를 식별하는
 * 값은 원문 링크의 도메인뿐이다. 한글 언론사명으로 보이려면 도메인 → 이름 매핑이 따로 있어야 하는데 국내
 * 언론사가 수백 개라 이 이슈의 범위가 아니다(후속 이슈).
 *
 * <p><b>제목은 태그를 걷어내고 엔티티를 푼 뒤에 필터에 넣는다.</b> 네이버는 질의어와 일치한 부분을
 * {@code <b>}로 감싸 돌려주므로({@code "<b>비트코인</b>캐시 급등"}) 씻지 않은 제목으로 종목명을 찾으면 이름이
 * 태그로 쪼개져 <b>제목 필터가 통째로 무력해진다.</b> 순서도 중요하다 — 태그를 먼저 걷고 그다음 엔티티를 푼다.
 * 반대로 하면 본문에 있던 {@code &lt;b&gt;}가 진짜 태그로 바뀐 뒤 지워진다.
 *
 * <p><b>{@code description}을 매핑하지 않는다.</b> 응답 record에 필드를 두지 않아 요약 스니펫이 애플리케이션
 * 안으로 들어올 경로 자체가 없다 (§정책 전제 — 저작권).
 *
 * <p><b>받은 기사를 발행일자로 거르지 않는다</b>(FEED-001). 최신순으로 받아 그대로 넘기고 구간 필터는
 * 조회·매칭 시점에만 건다.
 */
@Slf4j
@Component
@Profile({"prod", "news-real"})
public class NaverNewsCollector implements NewsCollector {

	// spec §외부 API 호출 상세의 엔드포인트. 검색 API가 NAVER API HUB(네이버 클라우드 플랫폼 중개)로 옮겨가
	// 구 openapi.naver.com 호스트와 X-Naver-Client-* 헤더를 쓰지 않는다 — 2026-08-04 키 발급 시 확인.
	private static final String NAVER_API_HUB_BASE_URL = "https://naverapihub.apigw.ntruss.com";
	private static final String SEARCH_PATH = "/search/v1/news";
	// spec §외부 API 호출 상세 — display 상한이 100이고 최신순(date)으로 받는다.
	private static final int DISPLAY = 100;
	private static final String SORT_BY_DATE = "date";
	// 콘솔은 "Client ID/Secret"으로 부르지만 실제 헤더 이름은 API Gateway 규격이다.
	private static final String HEADER_CLIENT_ID = "X-NCP-APIGW-API-KEY-ID";
	private static final String HEADER_CLIENT_SECRET = "X-NCP-APIGW-API-KEY";
	// §C-8의 컬럼 길이. 넘치면 저장 시점이 아니라 여기서 정리한다.
	private static final int TITLE_MAX_LENGTH = 500;
	private static final int URL_MAX_LENGTH = 500;
	private static final int PUBLISHER_MAX_LENGTH = 100;
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
	private static final String WWW_PREFIX = "www.";
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 타임아웃 값은 KisRestClientConfig·OAuthRestClientFactory와 같게 맞췄다 — 외부 REST 호출에 유한 타임아웃을
	// 두는 것이 이 저장소의 기존 방침이다. 수집은 @Scheduled 스레드에서 돌아 무한 대기가 생기면 그 스레드가 묶인다.
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	private final RestClient restClient;

	private final NaverSearchProperties properties;

	private final NewsSearchQueryBuilder queryBuilder;

	private final NewsTitleFilter titleFilter;

	/**
	 * <b>RestClient를 빈으로 등록하지 않고 여기서 만든다.</b> {@code RestClient} 타입 빈을 하나 더 늘리면
	 * {@code KisHistoricalCandleClientImpl}의 주입이 {@code NoUniqueBeanDefinitionException}으로 깨져
	 * <b>애플리케이션 컨텍스트 전체가 기동하지 않는다.</b> 그 클래스의 {@code @Qualifier}는 필드에 붙어 있지만
	 * Lombok {@code @RequiredArgsConstructor}가 생성자 파라미터로 복사해 주지 않아(바이트코드에
	 * {@code RuntimeVisibleParameterAnnotations}가 없다) 실제로는 타입 매칭 하나로 버티고 있다 —
	 * {@code RestClient} 빈이 {@code kisRestClient} 하나뿐이라 지금까지 드러나지 않았을 뿐이다.
	 *
	 * <p>그래서 {@code ResendEmailSender}·{@code BithumbRestCandleProvider}·OAuth 공급자들과 같은 방식으로
	 * {@code RestClient.Builder}를 받아 이 클래스 안에서 완성한다. 저장소의 여섯 중 다섯이 이미 이 형태다.
	 */
	@Autowired
	public NaverNewsCollector(
		RestClient.Builder builder,
		NaverSearchProperties properties,
		NewsSearchQueryBuilder queryBuilder,
		NewsTitleFilter titleFilter) {
		this(applyTimeouts(builder).baseUrl(NAVER_API_HUB_BASE_URL).build(), properties, queryBuilder, titleFilter);
	}

	// 테스트 전용: MockRestServiceServer로 이미 구성된 RestClient를 직접 주입한다 (타임아웃 팩토리를 거치지 않는다).
	NaverNewsCollector(
		RestClient restClient,
		NaverSearchProperties properties,
		NewsSearchQueryBuilder queryBuilder,
		NewsTitleFilter titleFilter) {
		this.restClient = restClient;
		this.properties = properties;
		this.queryBuilder = queryBuilder;
		this.titleFilter = titleFilter;
	}

	private static RestClient.Builder applyTimeouts(RestClient.Builder builder) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
		requestFactory.setReadTimeout(READ_TIMEOUT);
		return builder.requestFactory(requestFactory);
	}

	@Override
	public List<CollectedNewsDto> collect(Instrument instrument, List<String> sameMarketNames) {
		String query = queryBuilder.build(instrument);
		NaverNewsSearchResponse response;
		try {
			response = search(query);
		} catch (RestClientException ex) {
			// 그 종목만 건너뛰고 나머지는 계속한다 (§실패 처리). 예외를 밖으로 내보내면 수집 배치가 통째로
			// 멈춰 분봉 수집·재생세션 확정까지 말려든다.
			log.warn("네이버 뉴스 검색에 실패해 이 종목을 건너뜁니다 (symbol={}, query={}): {}",
				instrument.getSymbol(), query, ex.toString());
			return List.of();
		}
		if (response == null || response.items() == null) {
			return List.of();
		}
		List<CollectedNewsDto> collected = new ArrayList<>();
		for (NaverNewsItem item : response.items()) {
			toCollectedNews(item)
				.filter(news -> titleFilter.isRelevant(instrument, sameMarketNames, news.title()))
				.ifPresent(collected::add);
		}
		return List.copyOf(collected);
	}

	private NaverNewsSearchResponse search(String query) {
		return restClient
			.get()
			.uri(uriBuilder -> uriBuilder
				.path(SEARCH_PATH)
				.queryParam("query", query)
				.queryParam("display", DISPLAY)
				.queryParam("sort", SORT_BY_DATE)
				.build())
			.header(HEADER_CLIENT_ID, properties.clientId())
			.header(HEADER_CLIENT_SECRET, properties.clientSecret())
			.accept(MediaType.APPLICATION_JSON)
			.retrieve()
			.body(NaverNewsSearchResponse.class);
	}

	// 저장할 수 없는 항목은 예외 대신 건너뛴다 — 한 건이 망가졌다고 그 종목의 나머지 99건을 버리지 않는다.
	private static Optional<CollectedNewsDto> toCollectedNews(NaverNewsItem item) {
		Optional<SourceLink> source = firstUsableLink(item);
		if (source.isEmpty()) {
			// 근거 기사 1건이 카드 생성 여부를 가르므로 버리는 사실을 반드시 남긴다 — 조용히 줄어드는 것이
			// 이 기능에서 가장 알아채기 어려운 실패다.
			log.warn("원문·네이버 링크 어느 쪽에서도 언론사를 얻지 못해 이 기사를 건너뜁니다"
				+ " (originallink={}, link={})", item.originallink(), item.link());
			return Optional.empty();
		}
		String title = cleanTitle(item.title());
		if (title.isEmpty()) {
			return Optional.empty();
		}
		return parsePublishedAt(item.pubDate())
			.map(publishedAt -> new CollectedNewsDto(
				title, source.get().publisher(), source.get().url(), publishedAt));
	}

	/**
	 * 원문 URL을 우선한다 — 원문 링크로 트래픽을 언론사에 보내는 구조다(§정책 전제). 네이버가
	 * {@code originallink}를 비워 보내는 항목이 있어 그때만 네이버 뉴스 링크로 폴백한다.
	 *
	 * <p><b>URL 선택과 언론사 추출을 한 루프에서 함께 판정한다.</b> 둘을 나눠 "URL 먼저 고르고 그다음 호스트
	 * 파싱"으로 두면, {@code originallink}에 공백·{@code |}·{@code []}가 섞이거나 호스트에 언더스코어가 있어
	 * 호스트를 못 뽑을 때 <b>멀쩡한 {@code link}가 있는데도 폴백이 타지 않아</b> 기사가 통째로 사라진다.
	 * {@code publisher}는 {@code NOT NULL}이라 호스트를 못 뽑은 URL은 애초에 쓸 수 없는 후보다.
	 *
	 * <p>길이가 컬럼을 넘으면 <b>자르지 않고 다음 후보로 넘어간다.</b> {@code url}은
	 * {@code UNIQUE(instrument_id, url)}의 축이라 자르면 쿼리 파라미터만 다른 다른 기사와 같은 값이 되어 근거
	 * 기사를 조용히 잡아먹고(§C-8), 잘린 링크는 열리지도 않는다.
	 */
	private static Optional<SourceLink> firstUsableLink(NaverNewsItem item) {
		for (String candidate : new String[] {item.originallink(), item.link()}) {
			if (candidate == null || candidate.isBlank() || candidate.length() > URL_MAX_LENGTH) {
				continue;
			}
			String publisher = toPublisher(candidate);
			if (publisher != null) {
				return Optional.of(new SourceLink(candidate, publisher));
			}
		}
		return Optional.empty();
	}

	private static String toPublisher(String url) {
		String host;
		try {
			host = URI.create(url).getHost();
		} catch (IllegalArgumentException ex) {
			return null;
		}
		if (host == null || host.isBlank()) {
			return null;
		}
		String publisher = host.startsWith(WWW_PREFIX) ? host.substring(WWW_PREFIX.length()) : host;
		return publisher.length() > PUBLISHER_MAX_LENGTH ? null : publisher;
	}

	// 저장 가능한 URL과 거기서 뽑은 언론사는 항상 짝이다 — 따로 들고 다니면 위 폴백이 다시 갈라진다.
	private record SourceLink(String url, String publisher) {
	}

	// 태그를 먼저 걷고 그다음 엔티티를 푼다. 순서를 바꾸면 &lt;b&gt;가 태그로 바뀐 뒤 지워진다.
	// 패키지 전용이다. 이슈 #179의 측정(CoinNewsFilterMeasurementTest)이 이 손질을 그대로 거쳐야 운영과 같은
	// 문자열을 필터에 넣는다 — 측정 쪽에서 다시 구현하면 두 벌이 되어 조용히 갈라지고, 그러면 운영과 다른
	// 규칙을 재게 된다. MarketSessionTimes를 넓힌 것과 같은 판단이다.
	static String cleanTitle(String rawTitle) {
		if (rawTitle == null) {
			return "";
		}
		String stripped = HTML_TAG.matcher(rawTitle).replaceAll("");
		String unescaped = HtmlUtils.htmlUnescape(stripped).trim();
		if (unescaped.length() <= TITLE_MAX_LENGTH) {
			return unescaped;
		}
		// 서로게이트 쌍을 가르지 않는다 (이슈 #408). length()·substring()은 UTF-16 코드 단위 기준이라 경계가
		// 이모지·일부 한자 한 글자의 가운데면 짝 없는 서로게이트가 남고, utf8mb4가 그 문자열을 거부해 저장이
		// 예외로 실패한다. 한 글자를 덜 담는 쪽이 맞다 — 제목은 필터·표시용이고 500자 경계 한 글자에 의미가
		// 걸려 있지 않다.
		int end = TITLE_MAX_LENGTH;
		if (Character.isHighSurrogate(unescaped.charAt(end - 1))) {
			end--;
		}
		return unescaped.substring(0, end);
	}

	// pubDate는 RFC 1123 형식이다(예: "Mon, 03 Aug 2026 14:23:00 +0900"). 오프셋이 무엇으로 오든 KST 벽시계로
	// 맞춰 저장한다 — 이 서비스의 모든 시간축이 KST다.
	private static Optional<LocalDateTime> parsePublishedAt(String pubDate) {
		if (pubDate == null || pubDate.isBlank()) {
			return Optional.empty();
		}
		try {
			return Optional.of(ZonedDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME)
				.withZoneSameInstant(KST)
				.toLocalDateTime());
		} catch (DateTimeParseException ex) {
			log.warn("네이버 기사 발행시각을 해석하지 못해 이 기사를 건너뜁니다 (pubDate={})", pubDate);
			return Optional.empty();
		}
	}

	// description(요약 스니펫)을 일부러 두지 않는다 — 매핑하지 않으면 들어올 경로가 없다 (§정책 전제).
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record NaverNewsSearchResponse(List<NaverNewsItem> items) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record NaverNewsItem(String title, String originallink, String link, String pubDate) {
	}
}
