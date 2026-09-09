package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.PriceStore;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class BithumbRestTickerPollerConditionalTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(InstrumentRepository.class, () -> mock(InstrumentRepository.class))
		.withBean(PriceStore.class, () -> mock(PriceStore.class))
		.withBean(Clock.class, Clock::systemDefaultZone)
		.withBean(RestClient.Builder.class, RestClient::builder)
		.withUserConfiguration(BithumbRestTickerPoller.class);

	@Test
	@DisplayName("crypto-real 프로필 + 프로퍼티 미지정이면(matchIfMissing=true) 폴러 빈이 생성된다")
	void pollerBeanCreatedOnCryptoRealProfileWhenPropertyMissing() {
		contextRunner.withSystemProperties("spring.profiles.active=crypto-real").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(BithumbRestTickerPoller.class);
		});
	}

	@Test
	@DisplayName("crypto-real 프로필이어도 bithumb.feed.ticker.enabled=false면 폴러 빈이 생성되지 않는다")
	void pollerBeanNotCreatedOnCryptoRealProfileWhenPropertyFalse() {
		contextRunner
			.withSystemProperties("spring.profiles.active=crypto-real")
			.withPropertyValues("bithumb.feed.ticker.enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(BithumbRestTickerPoller.class);
			});
	}

	@Test
	@DisplayName("프로필 미지정(기본)이면 폴러 빈이 생성되지 않는다 — 기본 동작은 시뮬레이터 그대로다")
	void pollerBeanNotCreatedOnDefaultProfile() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(BithumbRestTickerPoller.class);
		});
	}

	@Test
	@DisplayName("prod 프로필이면(#369) 프로퍼티 미지정 상태에서 폴러 빈이 생성된다")
	void pollerBeanCreatedOnProdProfile() {
		contextRunner.withSystemProperties("spring.profiles.active=prod").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(BithumbRestTickerPoller.class);
		});
	}

	@Test
	@DisplayName("prod 프로필이어도 bithumb.feed.ticker.enabled=false면 폴러 빈이 생성되지 않는다 — 격리 프로퍼티는 프로필과 무관하다(PRD C-005)")
	void pollerBeanNotCreatedOnProdProfileWhenPropertyFalse() {
		contextRunner
			.withSystemProperties("spring.profiles.active=prod")
			.withPropertyValues("bithumb.feed.ticker.enabled=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(BithumbRestTickerPoller.class);
			});
	}

	@Test
	@DisplayName("prod와 crypto-real이 함께 켜지면(#369) @Profile(\"prod | crypto-real\")이라 폴러 빈이 생성된다")
	void pollerBeanCreatedWhenProdAndCryptoRealAreBothActive() {
		contextRunner.withSystemProperties("spring.profiles.active=prod,crypto-real").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(BithumbRestTickerPoller.class);
		});
	}
}
