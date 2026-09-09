package com.finplay.api.domain.feedback.collector;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.domain.feedback.config.NaverSearchProperties;
import com.finplay.api.domain.feedback.service.NewsSearchQueryBuilder;
import com.finplay.api.domain.feedback.service.NewsTitleFilter;
import com.finplay.api.domain.market.entity.Instrument;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
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

@Slf4j
@Component
@Profile({"prod", "news-real"})
public class NaverNewsCollector implements NewsCollector {

	private static final String NAVER_API_HUB_BASE_URL = "https://naverapihub.apigw.ntruss.com";
	private static final String SEARCH_PATH = "/search/v1/news";
	private static final int DISPLAY = 100;
	private static final String SORT_BY_DATE = "date";
	private static final String HEADER_CLIENT_ID = "X-NCP-APIGW-API-KEY-ID";
	private static final String HEADER_CLIENT_SECRET = "X-NCP-APIGW-API-KEY";
	private static final int TITLE_MAX_LENGTH = 500;
	private static final int URL_MAX_LENGTH = 500;
	private static final int PUBLISHER_MAX_LENGTH = 100;
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
	private static final String WWW_PREFIX = "www.";
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
	private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

	private final RestClient restClient;

	private final NaverSearchProperties properties;

	private final NewsSearchQueryBuilder queryBuilder;

	private final NewsTitleFilter titleFilter;

	@Autowired
	public NaverNewsCollector(
		RestClient.Builder builder,
		NaverSearchProperties properties,
		NewsSearchQueryBuilder queryBuilder,
		NewsTitleFilter titleFilter) {
		this(applyTimeouts(builder).baseUrl(NAVER_API_HUB_BASE_URL).build(), properties, queryBuilder, titleFilter);
	}

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

	private static Optional<CollectedNewsDto> toCollectedNews(NaverNewsItem item) {
		Optional<SourceLink> source = firstUsableLink(item);
		if (source.isEmpty()) {
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

	private record SourceLink(String url, String publisher) {
	}

	static String cleanTitle(String rawTitle) {
		if (rawTitle == null) {
			return "";
		}
		String stripped = HTML_TAG.matcher(rawTitle).replaceAll("");
		String unescaped = HtmlUtils.htmlUnescape(stripped).trim();
		if (unescaped.length() <= TITLE_MAX_LENGTH) {
			return unescaped;
		}
		int end = TITLE_MAX_LENGTH;
		if (Character.isHighSurrogate(unescaped.charAt(end - 1))) {
			end--;
		}
		return unescaped.substring(0, end);
	}

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

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record NaverNewsSearchResponse(List<NaverNewsItem> items) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record NaverNewsItem(String title, String originallink, String link, String pubDate) {
	}
}
