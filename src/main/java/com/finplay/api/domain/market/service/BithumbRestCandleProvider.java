package com.finplay.api.domain.market.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Profile({"prod", "crypto-real"})
public class BithumbRestCandleProvider implements CryptoCandleProvider {

	private static final String DEFAULT_CANDLE_BASE_URL = "https://api.bithumb.com/v1/candles";
	private static final String KRW_MARKET_PREFIX = "KRW-";
	private static final int MAX_COUNT = 200;

	private final RestClient restClient;
	private final Clock clock;
	private final String candleBaseUrl;

	@Autowired
	public BithumbRestCandleProvider(
		Clock clock,
		@Value("${bithumb.candle.connect-timeout-ms:2000}")
		long connectTimeoutMs,
		@Value("${bithumb.candle.read-timeout-ms:3000}")
		long readTimeoutMs,
		@Value("${bithumb.candle.base-url:" + DEFAULT_CANDLE_BASE_URL + "}")
		String candleBaseUrl) {
		this(applyTimeouts(RestClient.builder(), connectTimeoutMs, readTimeoutMs).build(), clock, candleBaseUrl);
	}

	BithumbRestCandleProvider(RestClient restClient, Clock clock) {
		this(restClient, clock, DEFAULT_CANDLE_BASE_URL);
	}

	BithumbRestCandleProvider(RestClient restClient, Clock clock, String candleBaseUrl) {
		this.restClient = restClient;
		this.clock = clock;
		this.candleBaseUrl = candleBaseUrl;
	}

	@Override
	public List<CryptoCandleDto> getCandles(
		String symbol, CandleInterval interval, LocalDateTime from, LocalDateTime to) {
		String market = KRW_MARKET_PREFIX + symbol;
		int count = resolveCount(interval, from, to);
		String toParam = resolveToParam(to);

		List<BithumbCandleItem> descending = fetchCandles(resolveEndpoint(interval), market, toParam, count);
		List<BithumbCandleItem> ascending = new ArrayList<>(descending);
		Collections.reverse(ascending);
		return ascending.stream().map(this::toDto).toList();
	}

	private String resolveEndpoint(CandleInterval interval) {
		return switch (interval) {
			case ONE_MINUTE -> candleBaseUrl + "/minutes/1";
			case ONE_DAY -> candleBaseUrl + "/days";
			case ONE_WEEK -> candleBaseUrl + "/weeks";
			case ONE_MONTH -> candleBaseUrl + "/months";
		};
	}

	private int resolveCount(CandleInterval interval, LocalDateTime from, LocalDateTime to) {
		if (from == null) {
			return MAX_COUNT;
		}
		LocalDateTime rangeEnd = to != null ? to : LocalDateTime.now(clock);
		long units = switch (interval) {
			case ONE_MINUTE -> ChronoUnit.MINUTES.between(from, rangeEnd) + 1;
			case ONE_DAY -> ChronoUnit.DAYS.between(from.toLocalDate(), rangeEnd.toLocalDate()) + 1;
			case ONE_WEEK -> {
				LocalDate fromMonday = from.toLocalDate().with(DayOfWeek.MONDAY);
				LocalDate toMonday = rangeEnd.toLocalDate().with(DayOfWeek.MONDAY);
				yield ChronoUnit.WEEKS.between(fromMonday, toMonday) + 1;
			}
			case ONE_MONTH -> {
				LocalDate fromFirstDay = from.toLocalDate().withDayOfMonth(1);
				LocalDate toFirstDay = rangeEnd.toLocalDate().withDayOfMonth(1);
				yield ChronoUnit.MONTHS.between(fromFirstDay, toFirstDay) + 1;
			}
		};
		return (int)Math.min(MAX_COUNT, Math.max(1, units));
	}

	private String resolveToParam(LocalDateTime to) {
		if (to == null) {
			return null;
		}
		return to.plusSeconds(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
	}

	private List<BithumbCandleItem> fetchCandles(String endpoint, String market, String toParam, int count) {
		try {
			URI uri = UriComponentsBuilder.fromUriString(endpoint)
				.queryParam("market", market)
				.queryParam("count", count)
				.queryParamIfPresent("to", Optional.ofNullable(toParam))
				.build()
				.toUri();

			BithumbCandleItem[] response = restClient
				.get()
				.uri(uri)
				.retrieve()
				.onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
					throw providerError();
				})
				.body(BithumbCandleItem[].class);
			if (response == null) {
				throw providerError();
			}
			return List.of(response);
		} catch (BusinessException ex) {
			throw ex;
		} catch (RestClientException ex) {
			throw providerError();
		}
	}

	private CryptoCandleDto toDto(BithumbCandleItem item) {
		if (item.candle_date_time_kst() == null
			|| item.opening_price() == null
			|| item.high_price() == null
			|| item.low_price() == null
			|| item.trade_price() == null
			|| item.candle_acc_trade_volume() == null) {
			throw providerError();
		}
		try {
			LocalDateTime sourceTime = LocalDateTime.parse(item.candle_date_time_kst());
			return new CryptoCandleDto(
				sourceTime,
				item.opening_price(),
				item.high_price(),
				item.low_price(),
				item.trade_price(),
				item.candle_acc_trade_volume());
		} catch (RuntimeException ex) {
			throw providerError();
		}
	}

	private static RestClient.Builder applyTimeouts(RestClient.Builder builder, long connectTimeoutMs,
		long readTimeoutMs) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
		requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
		return builder.requestFactory(requestFactory);
	}

	private static BusinessException providerError() {
		return new BusinessException(ErrorCode.MARKET_DATA_PROVIDER_ERROR);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	private record BithumbCandleItem(
		String market,
		String candle_date_time_utc,
		String candle_date_time_kst,
		BigDecimal opening_price,
		BigDecimal high_price,
		BigDecimal low_price,
		BigDecimal trade_price,
		Long timestamp,
		BigDecimal candle_acc_trade_price,
		BigDecimal candle_acc_trade_volume,
		Integer unit) {
	}
}
