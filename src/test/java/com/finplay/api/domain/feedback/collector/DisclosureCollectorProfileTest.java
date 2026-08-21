// 프로필이 DisclosureCollector 구현을 가르는지, prod 빈이 실제로 조립되는지 보증하는 회귀 테스트.
package com.finplay.api.domain.feedback.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.feedback.config.DartProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

// NewsCollectorProfileTest와 같은 형태다. @Profile("prod") 빈은 기본 프로필로 도는 어떤 테스트도 인스턴스화하지
// 않아 생성자 주입이 깨져도 빌드가 초록으로 통과한다 — prod 컨텍스트를 실제로 띄워 생성자를 돌리는 것이
// 이 파일의 핵심이다.
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
				// 생성자 주입이 깨져 있으면 여기서 실패한다 — 이 단정이 이 테스트의 존재 이유다.
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(DisclosureCollector.class);
				assertThat(context.getBean(DisclosureCollector.class))
					.isInstanceOf(DartDisclosureCollector.class);
				assertThat(context).doesNotHaveBean(FakeDisclosureCollector.class);
			});
	}

	// 수집기가 RestClient를 빈으로 등록하면 RestClient 타입 빈이 둘이 되어 kisRestClient를 받던 주입이
	// NoUniqueBeanDefinitionException으로 깨지고 컨텍스트 전체가 기동하지 않는다 (agent-mistakes.md 2026-08-04).
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
