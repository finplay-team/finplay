// 프로필이 NewsCollector 구현을 가르는지, 그리고 prod 빈이 실제로 조립되는지 보증하는 회귀 테스트.
package com.finplay.api.feedback.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.feedback.config.NaverSearchProperties;
import com.finplay.api.feedback.service.NewsSearchQueryBuilder;
import com.finplay.api.feedback.service.NewsTitleFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

// EmailSenderProfileTest 선례를 그대로 본뜬다 — 두 구현을 모두 등록하되 프로필만 다르게 두고, 프로필 분기가
// 실제로 어느 빈을 선택하는지 본다 (tasks.md 3번의 "Fake와 실제 구현의 선택은 프로필로 가른다").
//
// prod 쪽을 반드시 함께 두는 이유가 있다. @Profile("prod") 빈은 기본 프로필로 도는 어떤 테스트도
// 인스턴스화하지 않아 생성자 주입이 깨져도 빌드가 초록으로 통과한다. 여기서 prod 컨텍스트를 실제로 띄워
// NaverNewsCollector의 생성자를 돌리는 것이 이 파일의 핵심이다.
class NewsCollectorProfileTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withBean(RestClient.Builder.class, RestClient::builder)
		.withBean(NaverSearchProperties.class, () -> new NaverSearchProperties("", ""))
		.withBean(NewsSearchQueryBuilder.class, NewsSearchQueryBuilder::new)
		.withBean(NewsTitleFilter.class, NewsTitleFilter::new)
		.withUserConfiguration(FakeNewsCollector.class, NaverNewsCollector.class);

	@Test
	@DisplayName("기본(비-prod) 프로필에서는 네이버 검색 키 없이도 NewsCollector가 FakeNewsCollector로 주입된다")
	void defaultProfileWiresFakeNewsCollectorWithoutSearchKeys() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(NewsCollector.class);
			assertThat(context.getBean(NewsCollector.class)).isInstanceOf(FakeNewsCollector.class);
			assertThat(context).doesNotHaveBean(NaverNewsCollector.class);
		});
	}

	@Test
	@DisplayName("prod 프로필에서는 NaverNewsCollector가 실제로 조립되고 Fake가 제외된다")
	void prodProfileAssemblesNaverNewsCollector() {
		contextRunner
			.withSystemProperties("spring.profiles.active=prod")
			.run(context -> {
				// 생성자 주입이 깨져 있으면 여기서 실패한다 — 이 단정이 이 테스트의 존재 이유다.
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(NewsCollector.class);
				assertThat(context.getBean(NewsCollector.class)).isInstanceOf(NaverNewsCollector.class);
				assertThat(context).doesNotHaveBean(FakeNewsCollector.class);
			});
	}

	// 수집기가 RestClient를 빈으로 등록하면 RestClient 타입 빈이 둘이 되어 kisRestClient를 받던 주입이
	// NoUniqueBeanDefinitionException으로 깨지고 컨텍스트 전체가 기동하지 않는다. RestClient.Builder만 받아
	// 클래스 안에서 완성하는 형태를 여기서 고정한다.
	@Test
	@DisplayName("prod 프로필에서도 수집기가 RestClient 타입 빈을 새로 등록하지 않는다")
	void prodProfileAddsNoRestClientBean() {
		contextRunner
			.withSystemProperties("spring.profiles.active=prod")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(RestClient.class);
			});
	}
}
