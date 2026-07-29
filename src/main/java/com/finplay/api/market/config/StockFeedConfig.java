// STOCK_FEED_PROVIDER·SERVICE_EXPOSURE·KIS_PUBLIC_DISPLAY_APPROVED 조합을 검증하고 StockPriceProvider 빈을 선택하는 설정 (MKT-007)
package com.finplay.api.market.config;

import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.service.KisRealtimePriceProvider;
import com.finplay.api.market.service.KrxReplayPriceProvider;
import com.finplay.api.market.service.StockPriceProvider;
import com.finplay.api.market.service.StockReplayService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

// @Bean 메서드끼리 서로 호출하지 않아 CGLIB 프록시(self-invocation 대응)가 필요 없다 — proxyBeanMethods=false로 끄고
// 클래스를 final로 선언하면 생성자에서 바로 검증해도 SpotBugs CT_CONSTRUCTOR_THROW에 걸리지 않는다.
@Configuration(proxyBeanMethods = false)
public final class StockFeedConfig {

	@Autowired
	StockFeedConfig(
		@Value("${stock-feed.provider}")
		StockFeedProvider stockFeedProvider,
		@Value("${stock-feed.service-exposure}")
		ServiceExposure serviceExposure,
		@Value("${stock-feed.kis-public-display-approved}")
		boolean kisPublicDisplayApproved) {
		validateAllowedCombination(stockFeedProvider, serviceExposure, kisPublicDisplayApproved);
	}

	// 허용 조합 넷(PRIVATE+KIS_REALTIME, PRIVATE+KRX_REPLAY, PUBLIC+KRX_REPLAY, PUBLIC+KIS_REALTIME+승인) 중
	// 유일한 금지 조합(PUBLIC+KIS_REALTIME+미승인)이면 기동 자체를 실패시킨다 (fail-fast, C-007).
	static void validateAllowedCombination(
		StockFeedProvider provider, ServiceExposure exposure, boolean kisPublicDisplayApproved) {
		boolean isForbidden = provider == StockFeedProvider.KIS_REALTIME
			&& exposure == ServiceExposure.PUBLIC
			&& !kisPublicDisplayApproved;
		if (isForbidden) {
			throw new IllegalStateException(
				"PUBLIC 환경에서 KIS_PUBLIC_DISPLAY_APPROVED=false인 상태로 KIS_REALTIME을 사용할 수 없습니다"
					+ " (한국투자증권 서면 허가·계약 확인 전까지 금지된 조합입니다).");
		}
	}

	// KRX_REPLAY일 때만 KrxReplayPriceProvider 빈을 만든다.
	@Bean
	@ConditionalOnProperty(prefix = "stock-feed", name = "provider", havingValue = "KRX_REPLAY", matchIfMissing = true)
	public StockPriceProvider stockPriceProvider(StockReplayService stockReplayService) {
		return new KrxReplayPriceProvider(stockReplayService);
	}

	// KIS_REALTIME일 때만 KisRealtimePriceProvider 빈을 만든다 (개인 개발·본인 전용 검증(PRIVATE) 용, 이슈 #82).
	// initMethod에서 approval_key 발급·WebSocket 연결을 시작하고, destroyMethod에서 연결을 정리한다.
	// KIS 키가 비어 있어도(예: PRIVATE+KRX_REPLAY 환경) 이 빈은 아예 만들어지지 않으므로 기동에 영향이 없다.
	@Bean(initMethod = "connect", destroyMethod = "close")
	@ConditionalOnProperty(prefix = "stock-feed", name = "provider", havingValue = "KIS_REALTIME")
	public KisRealtimePriceProvider kisRealtimePriceProvider(
		InstrumentRepository instrumentRepository,
		Clock clock,
		ObjectMapper objectMapper,
		RestClient.Builder restClientBuilder,
		@Value("${stock-feed.kis.app-key}")
		String appKey,
		@Value("${stock-feed.kis.app-secret}")
		String appSecret,
		@Value("${stock-feed.kis.approval-url}")
		String approvalUrl,
		@Value("${stock-feed.kis.websocket-url}")
		String websocketUrl) {
		return new KisRealtimePriceProvider(
			instrumentRepository, clock, objectMapper, restClientBuilder, appKey, appSecret, approvalUrl, websocketUrl);
	}
}
