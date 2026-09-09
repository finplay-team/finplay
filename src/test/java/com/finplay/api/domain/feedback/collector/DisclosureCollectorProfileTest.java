package com.finplay.api.domain.feedback.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.feedback.config.DartProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class DisclosureCollectorProfileTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(RestClient.Builder.class, RestClient::builder)
		.withBean(DartProperties.class, () -> new DartProperties(""))
		.withBean(DartCorpCodeRegistry.class, DartCorpCodeRegistry::new)
		.withUserConfiguration(FakeDisclosureCollector.class, DartDisclosureCollector.class);

	@Test
	@DisplayName("기본(비-prod) 프로필에서는 DART 키 없이도 DisclosureCollector가 FakeDisclosureCollector로 주입된다")
	void defaultProfileWiresFakeDisclosureCollectorWithoutDartKey() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(DisclosureCollector.class);
			assertThat(context.getBean(DisclosureCollector.class))
				.isInstanceOf(FakeDisclosureCollector.class);
			assertThat(context).doesNotHaveBean(DartDisclosureCollector.class);
		});
	}

	@Test
	@DisplayName("prod 프로필에서는 DartDisclosureCollector가 실제로 조립되고 Fake가 제외된다")
	void prodProfileAssemblesDartDisclosureCollector() {
		contextRunner
			.withSystemProperties("spring.profiles.active=prod")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(DisclosureCollector.class);
				assertThat(context.getBean(DisclosureCollector.class))
					.isInstanceOf(DartDisclosureCollector.class);
				assertThat(context).doesNotHaveBean(FakeDisclosureCollector.class);
			});
	}

	@Test
	@DisplayName("prod 프로필에서도 공시 수집기가 RestClient 타입 빈을 새로 등록하지 않는다")
	void prodProfileAddsNoRestClientBean() {
		contextRunner
			.withSystemProperties("spring.profiles.active=prod")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(RestClient.class);
			});
	}
}
