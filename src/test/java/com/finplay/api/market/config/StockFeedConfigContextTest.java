// stock-feed.* 프로퍼티 조합에 따라 스프링 컨텍스트 기동 성공/실패와 StockPriceProvider 빈 선택이 spec.md MKT-007대로 되는지 검증
package com.finplay.api.market.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.finplay.api.market.service.KrxReplayPriceProvider;
import com.finplay.api.market.service.StockPriceProvider;
import com.finplay.api.market.service.StockReplayService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class StockFeedConfigContextTest {

	// StockFeedConfig의 @Bean stockPriceProvider(StockReplayService)가 요구하는 의존성을 mock으로 채워주는 최소 구성.
	// StockReplayService 자체는 repository 3개 + Clock을 필요로 하므로 실제 빈을 올리지 않고 mock으로 대체한다.
	@Configuration(proxyBeanMethods = false)
	static class StockReplayServiceMockConfig {
		@Bean
		StockReplayService stockReplayService() {
			return mock(StockReplayService.class);
		}
	}

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(StockFeedConfig.class, StockReplayServiceMockConfig.class);

	@Test
	void krxReplayProviderBootsAndExposesKrxReplayPriceProviderBean() {
		contextRunner
			.withPropertyValues(
				"stock-feed.provider=KRX_REPLAY", "stock-feed.service-exposure=PUBLIC",
				"stock-feed.kis-public-display-approved=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(StockPriceProvider.class);
				assertThat(context.getBean(StockPriceProvider.class)).isInstanceOf(KrxReplayPriceProvider.class);
			});
	}

	@Test
	void privateKisRealtimeBootsWithoutStockPriceProviderBean() {
		contextRunner
			.withPropertyValues(
				"stock-feed.provider=KIS_REALTIME", "stock-feed.service-exposure=PRIVATE",
				"stock-feed.kis-public-display-approved=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				// KIS_REALTIME 선택 시 KrxReplayPriceProvider 빈 조건(@ConditionalOnProperty)이 매치되지 않으므로
				// StockPriceProvider 빈은 아직 없다 (KisRealtimePriceProvider는 후속 이슈에서 추가).
				assertThat(context).doesNotHaveBean(StockPriceProvider.class);
			});
	}

	@Test
	void privateKrxReplayBootsSuccessfully() {
		contextRunner
			.withPropertyValues(
				"stock-feed.provider=KRX_REPLAY", "stock-feed.service-exposure=PRIVATE",
				"stock-feed.kis-public-display-approved=false")
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Test
	void publicKisRealtimeApprovedBootsSuccessfully() {
		contextRunner
			.withPropertyValues(
				"stock-feed.provider=KIS_REALTIME", "stock-feed.service-exposure=PUBLIC",
				"stock-feed.kis-public-display-approved=true")
			.run(context -> assertThat(context).hasNotFailed());
	}

	// 금지 조합(PUBLIC+KIS_REALTIME+미승인)이면 컨텍스트 기동 자체가 실패해야 한다 (fail-fast, C-007).
	@Test
	void publicKisRealtimeUnapprovedFailsToLoadContext() {
		contextRunner
			.withPropertyValues(
				"stock-feed.provider=KIS_REALTIME", "stock-feed.service-exposure=PUBLIC",
				"stock-feed.kis-public-display-approved=false")
			.run((AssertableApplicationContext context) -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
				assertThat(context.getStartupFailure())
					.hasRootCauseMessage(
						"PUBLIC 환경에서 KIS_PUBLIC_DISPLAY_APPROVED=false인 상태로 KIS_REALTIME을 사용할 수 없습니다"
							+ " (한국투자증권 서면 허가·계약 확인 전까지 금지된 조합입니다).");
			});
	}
}
