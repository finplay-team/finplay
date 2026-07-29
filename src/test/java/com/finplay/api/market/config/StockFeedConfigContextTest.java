// stock-feed.* 프로퍼티 조합에 따라 스프링 컨텍스트 기동 성공/실패와 StockPriceProvider 빈 선택이 spec.md MKT-007대로 되는지 검증
package com.finplay.api.market.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.service.KisRealtimePriceProvider;
import com.finplay.api.market.service.KrxReplayPriceProvider;
import com.finplay.api.market.service.StockPriceProvider;
import com.finplay.api.market.service.StockReplayService;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Timeout(30)
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

	// StockFeedConfig의 @Bean kisRealtimePriceProvider(...)가 요구하는 의존성을 채워주는 최소 구성.
	// appKey·appSecret 프로퍼티를 빈 값으로 주면 connect()(initMethod)가 KIS 서버에 실제로 연결을 시도하지 않고
	// 즉시 반환하므로(로그만 남김), 아래 mock InstrumentRepository는 실제로는 호출되지 않는다.
	@Configuration(proxyBeanMethods = false)
	static class KisRealtimeDependenciesMockConfig {
		@Bean
		InstrumentRepository instrumentRepository() {
			return mock(InstrumentRepository.class);
		}

		@Bean
		Clock clock() {
			return Clock.systemUTC();
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}

		@Bean
		RestClient.Builder restClientBuilder() {
			return RestClient.builder();
		}
	}

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(
			StockFeedConfig.class, StockReplayServiceMockConfig.class, KisRealtimeDependenciesMockConfig.class)
		.withPropertyValues(
			"stock-feed.kis.app-key=", "stock-feed.kis.app-secret=", "stock-feed.kis.approval-url=",
			"stock-feed.kis.websocket-url=");

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

	// PRIVATE+KIS_REALTIME 조합이면 KisRealtimePriceProvider가 StockPriceProvider로 선택된다 (spec.md 완료 조건,
	// tasks.md 14번째 줄 — Provider 선택 테스트). KisRealtimePriceProvider가 아직 없던 시점에는 이 조합에서
	// StockPriceProvider 빈이 아예 없다고 검증했으나, 구현이 추가된 지금은 빈이 선택되는 것까지 확인한다(회귀 갱신).
	@Test
	void privateKisRealtimeBootsAndExposesKisRealtimePriceProviderBean() {
		contextRunner
			.withPropertyValues(
				"stock-feed.provider=KIS_REALTIME", "stock-feed.service-exposure=PRIVATE",
				"stock-feed.kis-public-display-approved=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(StockPriceProvider.class);
				assertThat(context.getBean(StockPriceProvider.class)).isInstanceOf(KisRealtimePriceProvider.class);
				// KRX_REPLAY 조건(@ConditionalOnProperty)이 매치되지 않으므로 KrxReplayPriceProvider 빈은 없다.
				assertThat(context).doesNotHaveBean(KrxReplayPriceProvider.class);
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
