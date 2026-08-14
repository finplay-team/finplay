// prod·crypto-real 프로필에서 빗썸 공개 ticker REST를 주기 조회해 PriceStore의 관측 시각을 갱신하는 폴러 (이슈 #107·#369)
package com.finplay.api.market.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.PriceStore;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

// 사용자 대상 스위치는 prod·crypto-real 프로필이다(이슈 #369 — 운영도 이 폴러로 REST 백업 관측을 받는다).
// bithumb.feed.ticker.enabled는 켜고 끄는 용도가 아니라 테스트 격리 전용 프로퍼티다 — 이 빈은 @Scheduled로
// 실제 빗썸을 호출하므로 @ActiveProfiles("crypto-real")·@ActiveProfiles("prod") 통합 테스트에서 빈이 생성되면
// 자동 테스트가 외부 네트워크에 의존하게 된다(PRD C-005 위반).
// BithumbFeedSimulator의 bithumb.feed.simulate.enabled와 완전히 같은 격리 패턴이며, 테스트용 false 주입은
// 별도 설정 파일이 담당한다. 기본은 matchIfMissing=true라 프로필만 켜면 그대로 동작한다.
@Slf4j
@Component
@Profile("prod | crypto-real")
@ConditionalOnProperty(prefix = "bithumb.feed.ticker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BithumbRestTickerPoller {

	private static final String TICKER_ENDPOINT = "https://api.bithumb.com/v1/ticker";
	private static final String KRW_MARKET_PREFIX = "KRW-";
	// PriceStore의 stale 기준 10초보다 짧아야 한다 — 길면 가격이 있는데도 409 PRICE_UNAVAILABLE이 뜬다.
	private static final long POLL_INTERVAL_MS = 3000;

	private final RestClient restClient;
	private final InstrumentRepository instrumentRepository;
	private final PriceStore priceStore;
	private final Clock clock;

	// RestClient.Builder를 DI로 받지 않고 RestClient.builder()를 직접 호출한다 — springdoc·spring-ai·jjwt-jackson이
	// 각자 Jackson 2를 끌어와 앱 클래스패스에 Jackson 2·3이 공존하는데, DI로 주입되는 공유 RestClient.Builder 빈은
	// Spring Boot가 컨텍스트의 모든 HttpMessageConverter 빈(레거시 Jackson 2 컨버터 포함)을 반영해 구성되므로
	// 어떤 컨버터가 선택될지 예측할 수 없다 — 실측 결과 이 경로로는 빗썸 응답이 100% 파싱 실패했다(이슈 #369 후속).
	// RestClient.builder()를 직접 호출하면 그 컨텍스트 구성과 무관하게 클래스패스 기준으로 Jackson 3 컨버터가
	// 결정적으로 선택된다 — 이 클래스의 테스트가 이미 이 방식을 쓰고 있고 실제로 안정적으로 통과한다.
	@Autowired
	public BithumbRestTickerPoller(
		InstrumentRepository instrumentRepository,
		PriceStore priceStore,
		Clock clock,
		@Value("${bithumb.feed.ticker.connect-timeout-ms:2000}")
		long connectTimeoutMs,
		@Value("${bithumb.feed.ticker.read-timeout-ms:3000}")
		long readTimeoutMs) {
		this(applyTimeouts(RestClient.builder(), connectTimeoutMs, readTimeoutMs).build(), instrumentRepository,
			priceStore, clock);
	}

	// 테스트 전용: MockRestServiceServer로 이미 구성된 RestClient를 직접 주입한다 (타임아웃 팩토리를 거치지 않는다).
	BithumbRestTickerPoller(RestClient restClient, InstrumentRepository instrumentRepository,
		PriceStore priceStore, Clock clock) {
		this.restClient = restClient;
		this.instrumentRepository = instrumentRepository;
		this.priceStore = priceStore;
		this.clock = clock;
	}

	// 조회 실패·타임아웃·비정상 상태코드·파싱 불가는 이번 회차를 건너뛰고 로그만 남긴다. 예외를 밖으로 던지면
	// 스케줄러가 죽으므로 절대 전파하지 않으며, 임의값·마지막 값으로 대체하지도 않는다 (MKT-004) —
	// 마지막 값이 10초 뒤 자연히 stale이 되어 PRICE_UNAVAILABLE로 정직하게 드러난다.
	@Scheduled(fixedRate = POLL_INTERVAL_MS)
	public void pollTickers() {
		try {
			List<Instrument> cryptoInstruments = instrumentRepository
				.findByMarketAndTradableTrueOrderByIdAsc(Market.CRYPTO);
			if (cryptoInstruments.isEmpty()) {
				return;
			}
			String markets = cryptoInstruments.stream()
				.map(instrument -> KRW_MARKET_PREFIX + instrument.getSymbol())
				.collect(Collectors.joining(","));

			BithumbTickerItem[] response = fetchTickers(markets);
			if (response == null) {
				log.warn("빗썸 ticker 응답이 비어 있어 이번 회차를 건너뛴다 (markets={})", markets);
				return;
			}
			emitTicks(response);
		} catch (RuntimeException ex) {
			log.warn("빗썸 ticker 조회 실패 — 이번 회차를 건너뛴다: {}", ex.toString());
		}
	}

	private BithumbTickerItem[] fetchTickers(String markets) {
		URI uri = UriComponentsBuilder.fromUriString(TICKER_ENDPOINT)
			.queryParam("markets", markets)
			.build()
			.toUri();

		return restClient
			.get()
			.uri(uri)
			.retrieve()
			.onStatus(HttpStatusCode::isError, (request, httpResponse) -> {
				throw new IllegalStateException("빗썸 ticker 응답 상태 코드 " + httpResponse.getStatusCode());
			})
			.body(BithumbTickerItem[].class);
	}

	// 항목 일부에 필수 필드가 없거나 심볼 형식이 다르면 그 항목만 건너뛰고 나머지는 정상 주입한다.
	// 여기서 넘기는 시각은 "지금 폴링한 시각"일 뿐 실제 체결 시각이 아니므로 receivedAt이 아니라 observedAt으로
	// PriceStore.recordObservation에 넘긴다(PRICE-REST-001·002) — saveTick(체결 시각)과 섞이면 MKT-003 과거틱
	// 가드가 오염된다.
	private void emitTicks(BithumbTickerItem[] response) {
		LocalDateTime observedAt = LocalDateTime.now(clock);
		for (BithumbTickerItem item : response) {
			if (item == null || item.market() == null || item.trade_price() == null
				|| !item.market().startsWith(KRW_MARKET_PREFIX)) {
				log.warn("빗썸 ticker 항목이 올바르지 않아 건너뛴다: {}", item);
				continue;
			}
			String symbol = item.market().substring(KRW_MARKET_PREFIX.length());
			if (symbol.isEmpty()) {
				log.warn("빗썸 ticker 항목의 심볼이 비어 있어 건너뛴다: {}", item.market());
				continue;
			}
			priceStore.recordObservation(symbol, item.trade_price(), observedAt);
		}
	}

	private static RestClient.Builder applyTimeouts(RestClient.Builder builder, long connectTimeoutMs,
		long readTimeoutMs) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
		requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
		return builder.requestFactory(requestFactory);
	}

	// 우리가 쓰는 필드는 market·trade_price 둘뿐이다 (2026-07-31 실제 응답으로 확인).
	@JsonIgnoreProperties(ignoreUnknown = true)
	private record BithumbTickerItem(String market, BigDecimal trade_price) {
	}
}
