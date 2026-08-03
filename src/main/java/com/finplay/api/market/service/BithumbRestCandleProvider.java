// 빗썸 공개 캔들 REST API(GET /v1/candles/{minutes/1|days|weeks|months})를 요청 시점에 호출해 코인 1분·일·주·월봉을 중계하는 CryptoCandleProvider 구현 — 저장·캐시 없음
package com.finplay.api.market.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
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
@Profile({"prod", "crypto-real"})
public class BithumbRestCandleProvider implements CryptoCandleProvider {

	private static final String MINUTE_CANDLE_ENDPOINT = "https://api.bithumb.com/v1/candles/minutes/1";
	private static final String DAY_CANDLE_ENDPOINT = "https://api.bithumb.com/v1/candles/days";
	private static final String WEEK_CANDLE_ENDPOINT = "https://api.bithumb.com/v1/candles/weeks";
	private static final String MONTH_CANDLE_ENDPOINT = "https://api.bithumb.com/v1/candles/months";
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
	public List<CryptoCandleDto> getCandles(
		String symbol, CandleInterval interval, LocalDateTime from, LocalDateTime to) {
		String market = KRW_MARKET_PREFIX + symbol;
		int count = resolveCount(interval, from, to);
		String toParam = resolveToParam(to);

		List<BithumbCandleItem> descending = fetchCandles(resolveEndpoint(interval), market, toParam, count);
		List<BithumbCandleItem> ascending = new ArrayList<>(descending);
		// 빗썸 응답은 최신→과거 내림차순이므로 시각 오름차순으로 뒤집는다 (주식 캔들과 동일한 정렬 계약).
		Collections.reverse(ascending);
		return ascending.stream().map(this::toDto).toList();
	}

	// interval별 빗썸 캔들 엔드포인트 (1m은 minutes/1을 그대로 유지). 서버는 빗썸 봉의 버킷 경계를 재계산하지 않는다.
	private static String resolveEndpoint(CandleInterval interval) {
		return switch (interval) {
			case ONE_MINUTE -> MINUTE_CANDLE_ENDPOINT;
			case ONE_DAY -> DAY_CANDLE_ENDPOINT;
			case ONE_WEEK -> WEEK_CANDLE_ENDPOINT;
			case ONE_MONTH -> MONTH_CANDLE_ENDPOINT;
		};
	}

	// from·to → to+count 변환 (plan.md "코인 캔들 설계" 표). from만 있으면 지금(clock) 기준, 둘 다 있으면 from~to 기준으로
	// interval 단위 개수를 세되 양 끝을 포함하도록 +1 하고 MAX_COUNT로 캡한다. 주·월은 각각 그 주 월요일·그 달 1일로
	// 정렬한 뒤 단위를 센다(빗썸의 주·월봉 버킷 경계와 맞추기 위함, 실제 응답 버킷 자체는 재계산하지 않는다).
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

	// 빗썸 to 파라미터는 UTC 기준이며, 그 정확한 경계 시각을 배제(exclusive)한다(이슈 #157 외부 스모크로 실측
	// 확인 — to와 정확히 같은 시각에 시작하는 봉이 응답에서 빠짐). 우리 API의 to는 항상 포함(inclusive)이므로
	// 1초를 더해 빗썸에 보낸다. 빗썸 최소 봉 간격(1분)보다 훨씬 작은 보정값이라 다음 봉을 끌어오지 않으면서
	// 경계 봉만 포함시킨다 — interval별 분기가 필요 없다(1m·1d·1w·1M 공통).
	private String resolveToParam(LocalDateTime to) {
		if (to == null) {
			return null;
		}
		return to.atZone(KST)
			.withZoneSameInstant(ZoneOffset.UTC)
			.toLocalDateTime()
			.plusSeconds(1)
			.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
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
