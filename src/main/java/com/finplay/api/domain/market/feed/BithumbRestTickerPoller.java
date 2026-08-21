// prod·crypto-real 프로필에서 빗썸 공개 ticker REST를 주기 조회해 PriceStore의 관측 시각을 갱신하는 폴러 (이슈 #107·#369)
package com.finplay.api.domain.market.feed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.PriceStore;
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

	private static final String DEFAULT_TICKER_ENDPOINT = "https://api.bithumb.com/v1/ticker";
	private static final String KRW_MARKET_PREFIX = "KRW-";
	// PriceStore의 stale 기준 10초보다 짧아야 한다 — 표시·체결 판정(036 이후)은 더 이상 이 기준을 안 쓰지만,
	// CryptoPriceSnapshotService.isPriceAvailable()이 변동 카드 재료 신뢰도 게이트로 여전히 이 기준을 쓴다.
	private static final long POLL_INTERVAL_MS = 3000;

	private final RestClient restClient;
	private final InstrumentRepository instrumentRepository;
	private final PriceStore priceStore;
	private final Clock clock;
	// mock 서버 기반 회귀 테스트가 실제 엔드포인트 URL을 로컬 서버로 바꿔치기할 수 있도록 외부화한다 — PR #377 리뷰 권장.
	private final String tickerEndpoint;

	// RestClient.Builder를 DI로 받지 않고 RestClient.builder()를 직접 호출하는 이유는 ADR-0023 참고
	// (Jackson 2/3 클래스패스 공존으로 공유 빈의 컨버터 선택이 불확실했던 문제, 이슈 #369 후속).
	@Autowired
	public BithumbRestTickerPoller(
		InstrumentRepository instrumentRepository,
		PriceStore priceStore,
		Clock clock,
		@Value("${bithumb.feed.ticker.connect-timeout-ms:2000}")
		long connectTimeoutMs,
		@Value("${bithumb.feed.ticker.read-timeout-ms:3000}")
		long readTimeoutMs,
		@Value("${bithumb.feed.ticker.endpoint-url:" + DEFAULT_TICKER_ENDPOINT + "}")
		String tickerEndpoint) {
		this(applyTimeouts(RestClient.builder(), connectTimeoutMs, readTimeoutMs).build(), instrumentRepository,
			priceStore, clock, tickerEndpoint);
	}

	// 테스트 전용: MockRestServiceServer로 이미 구성된 RestClient를 직접 주입한다 (타임아웃 팩토리를 거치지 않는다).
	BithumbRestTickerPoller(RestClient restClient, InstrumentRepository instrumentRepository,
		PriceStore priceStore, Clock clock) {
		this(restClient, instrumentRepository, priceStore, clock, DEFAULT_TICKER_ENDPOINT);
	}

	// 테스트 전용: @Autowired 생성자 경로(RestClient.builder() 직접 호출)를 실제 로컬 서버로 검증할 때
	// 엔드포인트까지 함께 바꿔치기한다.
	BithumbRestTickerPoller(RestClient restClient, InstrumentRepository instrumentRepository,
		PriceStore priceStore, Clock clock, String tickerEndpoint) {
		this.restClient = restClient;
		this.instrumentRepository = instrumentRepository;
		this.priceStore = priceStore;
		this.clock = clock;
		this.tickerEndpoint = tickerEndpoint;
	}

	// 조회 실패·타임아웃·비정상 상태코드·파싱 불가는 이번 회차를 건너뛰고 로그만 남긴다. 예외를 밖으로 던지면
	// 스케줄러가 죽으므로 절대 전파하지 않으며, 임의값·마지막 값으로 대체하지도 않는다 (MKT-004) — 표시·체결
	// 판정(PriceQueryService)은 036 이후 관측 시각과 무관하게 항상 AVAILABLE이라, 웹소켓 연결이 살아있는 한
	// 이 폴러가 조용히 계속 실패해도 409로 드러나지 않는다(PR #380 리뷰 참고). 다만 CryptoPriceSnapshotService의
	// isPriceAvailable() 게이트는 여전히 관측 시각(10초 기준)을 쓰므로, 그쪽 변동 카드 재료로는 stale 취급된다.
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
		URI uri = UriComponentsBuilder.fromUriString(tickerEndpoint)
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
