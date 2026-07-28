// STOCK_FEED_PROVIDER·SERVICE_EXPOSURE·KIS_PUBLIC_DISPLAY_APPROVED 조합을 검증하고 StockPriceProvider 빈을 선택하는 설정 (MKT-007)
package com.finplay.api.market.config;

import com.finplay.api.market.service.KrxReplayPriceProvider;
import com.finplay.api.market.service.StockPriceProvider;
import com.finplay.api.market.service.StockReplayService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StockFeedConfig {

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
	private static void validateAllowedCombination(
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

	// KRX_REPLAY일 때만 KrxReplayPriceProvider 빈을 만든다. KIS_REALTIME 선택 시의 빈 구성은 KisRealtimePriceProvider가
	// 추가되는 후속 이슈에서 완성한다 — 지금은 빈을 만들지 않는다(허용 조합 PRIVATE+KIS_REALTIME도 컨텍스트 기동은 성공해야 하므로
	// 여기서 예외를 던지지 않는다).
	@Bean
	@ConditionalOnProperty(prefix = "stock-feed", name = "provider", havingValue = "KRX_REPLAY", matchIfMissing = true)
	public StockPriceProvider stockPriceProvider(StockReplayService stockReplayService) {
		return new KrxReplayPriceProvider(stockReplayService);
	}
}
