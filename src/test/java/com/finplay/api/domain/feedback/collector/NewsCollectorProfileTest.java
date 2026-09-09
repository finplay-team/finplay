package com.finplay.api.domain.feedback.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.feedback.config.NaverSearchProperties;
import com.finplay.api.domain.feedback.service.NewsSearchQueryBuilder;
import com.finplay.api.domain.feedback.service.NewsTitleFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

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
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(NewsCollector.class);
				assertThat(context.getBean(NewsCollector.class)).isInstanceOf(NaverNewsCollector.class);
				assertThat(context).doesNotHaveBean(FakeNewsCollector.class);
			});
	}

	@Test
	@DisplayName("news-real 프로필에서는 prod가 아니어도 NaverNewsCollector가 조립되고 Fake가 제외된다")
	void newsRealProfileAssemblesNaverNewsCollector() {
		contextRunner
			.withSystemProperties("spring.profiles.active=local,news-real")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(NewsCollector.class);
				assertThat(context.getBean(NewsCollector.class)).isInstanceOf(NaverNewsCollector.class);
				assertThat(context).doesNotHaveBean(FakeNewsCollector.class);
			});
	}

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
