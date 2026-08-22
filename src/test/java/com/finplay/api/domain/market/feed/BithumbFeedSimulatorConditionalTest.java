// bithumb.feed.simulate.enabled 프로퍼티·prod 프로필에 따른 BithumbFeedSimulator 빈 생성 여부를 검증하는 슬라이스 테스트
package com.finplay.api.domain.market.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.market.store.PriceStore;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class BithumbFeedSimulatorConditionalTest {

	// FakeBithumbFeedClient가 PriceStore를 필요로 하므로 최소 의존 빈을 함께 등록한다.
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(InstrumentRepository.class, () -> mock(InstrumentRepository.class))
		.withBean(PriceStore.class, () -> mock(PriceStore.class))
		.withBean(Clock.class, Clock::systemDefaultZone)
		.withUserConfiguration(FakeBithumbFeedClient.class, BithumbFeedSimulator.class);

	@Test
	@DisplayName("프로퍼티를 지정하지 않으면(matchIfMissing=true) 기본으로 빈이 생성된다")
	void simulatorBeanCreatedByDefaultWhenPropertyMissing() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(BithumbFeedSimulator.class);
		});
	}

	@Test
	@DisplayName("bithumb.feed.simulate.enabled=true면 빈이 생성된다")
	void simulatorBeanCreatedWhenPropertyExplicitlyTrue() {
		contextRunner.withPropertyValues("bithumb.feed.simulate.enabled=true").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(BithumbFeedSimulator.class);
		});
	}

	@Test
	@DisplayName("bithumb.feed.simulate.enabled=false면 빈이 생성되지 않는다")
	void simulatorBeanNotCreatedWhenPropertyFalse() {
		contextRunner.withPropertyValues("bithumb.feed.simulate.enabled=false").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(BithumbFeedSimulator.class);
		});
	}

	@Test
	@DisplayName("prod 프로필에서는 프로퍼티가 true여도 빈이 생성되지 않는다(@Profile(\"!prod\"))")
	void simulatorBeanNotCreatedOnProdProfileEvenWhenPropertyTrue() {
		contextRunner
			.withPropertyValues("bithumb.feed.simulate.enabled=true")
			.withSystemProperties("spring.profiles.active=prod")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(BithumbFeedSimulator.class);
			});
	}
}
