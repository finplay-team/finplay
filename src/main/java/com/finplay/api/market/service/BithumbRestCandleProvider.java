// 빗썸 공개 캔들 REST API(GET /v1/candles/minutes/1)를 요청 시점에 호출해 코인 1분봉을 중계하는 CryptoCandleProvider 구현 — 저장·캐시 없음
package com.finplay.api.market.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
@Profile("prod")
public class BithumbRestCandleProvider implements CryptoCandleProvider {

	private static final String CANDLE_ENDPOINT = "https://api.bithumb.com/v1/candles/minutes/1";
	private static final String KRW_MARKET_PREFIX = "KRW-";
	// 빗썸 캔들 API의 count 상한 (MKT-008) — from·to 범위가 이를 넘으면 to 기준 최신 count개로 캡한다.
	private static final int MAX_COUNT = 200;
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private final RestClient restClient;
	private final Clock clock;

	@Autowired
	public BithumbRestCandleProvider(
		RestClient.Builder builder,
		Clock clock,
		@Value("${bithumb.candle.connect-timeout-ms:2000}")
		long connectTimeoutMs,
		@Value("${bithumb.candle.read-timeout-ms:3000}")
		long readTimeoutMs) {
		this(applyTimeouts(builder, connectTimeoutMs, readTimeoutMs).build(), clock);
	}

	// 테스트 전용: MockRestServiceServer로 이미 구성된 RestClient를 직접 주입한다 (타임아웃 팩토리를 거치지 않는다).
	BithumbRestCandleProvider(RestClient restClient, Clock clock) {
		this.restClient = restClient;
		this.clock = clock;
	}

	@Override
	public List<CryptoCandleDto> getCandles(String symbol, LocalDateTime from, LocalDateTime to) {
		String market = KRW_MARKET_PREFIX + symbol;
		int count = resolveCount(from, to);
		String toParam = resolveToParam(to);

		List<BithumbCandleItem> descending = fetchCandles(market, toParam, count);
		List<BithumbCandleItem> ascending = new ArrayList<>(descending);
		// 빗썸 응답은 최신→과거 내림차순이므로 시각 오름차순으로 뒤집는다 (주식 캔들과 동일한 정렬 계약).
		Collections.reverse(ascending);
		return ascending.stream().map(this::toDto).toList();
	}

	// from·to → to+count 변환 (plan.md "코인 캔들 설계" 표). from만 있으면 지금(clock) 기준, 둘 다 있으면 from~to 기준으로
	// 분 수를 세되 양 끝을 포함하도록 +1 하고 MAX_COUNT로 캡한다.
	private int resolveCount(LocalDateTime from, LocalDateTime to) {
		if (from == null) {
			return MAX_COUNT;
		}
		LocalDateTime rangeEnd = to != null ? to : LocalDateTime.now(clock);
		long minutes = ChronoUnit.MINUTES.between(from, rangeEnd) + 1;
		return (int)Math.min(MAX_COUNT, Math.max(1, minutes));
	}

	// 빗썸 to 파라미터는 UTC 기준이다 — 우리 내부 from·to는 KST LocalDateTime(주식과 같은 표현)이므로 변환한다.
	private String resolveToParam(LocalDateTime to) {
		if (to == null) {
			return null;
		}
		return to.atZone(KST)
			.withZoneSameInstant(ZoneOffset.UTC)
			.toLocalDateTime()
			.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
	}

	private List<BithumbCandleItem> fetchCandles(String market, String toParam, int count) {
		try {
			URI uri = UriComponentsBuilder.fromUriString(CANDLE_ENDPOINT)
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
				// 빗썸은 종가를 trade_price로 부른다 — 이름에 속아 현재가로 해석하지 않는다.
				item.trade_price(),
				// candle_acc_trade_volume(코인 수량)을 volume으로 매핑한다. candle_acc_trade_price(거래대금)와 혼동하지 않는다.
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
