package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.support.CronExpression;

class NewsCollectionPropertiesTest {

	private static final String SPEC_COLLECT_CRON = "0 0/30 * * * *";

	private static final String SPEC_DISCLOSURE_CRON = "0 0/30 8-20 * * MON-FRI";

	private static final int SPEC_MAX_ITEMS_PER_NEWS_LIST = 50;

	private static final int SPEC_MAX_ITEMS_PER_BRIEFING = 30;

	private static final int SPEC_MAX_ITEMS_PER_SUMMARY = 30;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(NewsCollectionPropertiesConfig.class);

	@Test
	@DisplayName("feedback.news 설정을 하나도 주지 않아도 §C-1 크론 2종으로 바인딩된다")
	void bindsSpecCronDefaultsWhenNoFeedbackNewsPropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(FeedbackNewsProperties.class);

			FeedbackNewsProperties properties = context.getBean(FeedbackNewsProperties.class);
			assertThat(properties.collectCron()).isEqualTo(SPEC_COLLECT_CRON);
			assertThat(properties.disclosureCron()).isEqualTo(SPEC_DISCLOSURE_CRON);
		});
	}

	@Test
	@DisplayName("feedback.news.* 케밥케이스 키를 주면 크론 2종이 덮어써진다")
	void bindsEveryCronFromKebabCaseKeys() {
		contextRunner
			.withPropertyValues(
				"feedback.news.collect-cron=0 0/10 * * * *",
				"feedback.news.disclosure-cron=0 15 9 * * MON-FRI")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackNewsProperties properties = context.getBean(FeedbackNewsProperties.class);
				assertThat(properties.collectCron()).isEqualTo("0 0/10 * * * *");
				assertThat(properties.disclosureCron()).isEqualTo("0 15 9 * * MON-FRI");
			});
	}

	@Test
	@DisplayName("한쪽 크론만 덮어써도 나머지는 §C-1 기본값을 유지한다")
	void keepsSpecCronDefaultForPropertyThatIsNotGiven() {
		contextRunner
			.withPropertyValues("feedback.news.collect-cron=0 0/10 * * * *")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackNewsProperties properties = context.getBean(FeedbackNewsProperties.class);
				assertThat(properties.collectCron()).isEqualTo("0 0/10 * * * *");
				assertThat(properties.disclosureCron()).isEqualTo(SPEC_DISCLOSURE_CRON);
			});
	}

	@Test
	@DisplayName("§C-1 크론 2종 기본값이 실제로 파싱 가능한 cron 표현식이다")
	void specCronDefaultsAreParsableCronExpressions() {
		contextRunner.run(context -> {
			FeedbackNewsProperties properties = context.getBean(FeedbackNewsProperties.class);

			assertThatCode(() -> CronExpression.parse(properties.collectCron()))
				.doesNotThrowAnyException();
			assertThatCode(() -> CronExpression.parse(properties.disclosureCron()))
				.doesNotThrowAnyException();
		});
	}

	@Test
	@DisplayName("feedback.news 설정을 하나도 주지 않아도 §C-7 근거 매칭 3키가 기본값으로 바인딩된다")
	void bindsSpecMatchingDefaultsWhenNoFeedbackNewsPropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			FeedbackNewsProperties properties = context.getBean(FeedbackNewsProperties.class);
			assertThat(properties.matchBeforeMinutes()).isEqualTo(30);
			assertThat(properties.matchAfterMinutes()).isEqualTo(5);
			assertThat(properties.maxSourcesPerCard()).isEqualTo(5);
		});
	}

	@Test
	@DisplayName("근거 매칭 3키를 케밥케이스로 주면 모두 덮어써지고 크론은 그대로다")
	void bindsEveryMatchingPropertyFromKebabCaseKeys() {
		contextRunner
			.withPropertyValues(
				"feedback.news.match-before-minutes=45",
				"feedback.news.match-after-minutes=10",
				"feedback.news.max-sources-per-card=3")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackNewsProperties properties = context.getBean(FeedbackNewsProperties.class);
				assertThat(properties.matchBeforeMinutes()).isEqualTo(45);
				assertThat(properties.matchAfterMinutes()).isEqualTo(10);
				assertThat(properties.maxSourcesPerCard()).isEqualTo(3);
				assertThat(properties.collectCron()).isEqualTo(SPEC_COLLECT_CRON);
				assertThat(properties.disclosureCron()).isEqualTo(SPEC_DISCLOSURE_CRON);
			});
	}

	@Test
	@DisplayName("근거창 폭이 음수이면 기동이 실패한다 — 근거창이 항상 비는 상태로 굳지 않는다")
	void failsWhenMatchWindowWidthIsNegative() {
		contextRunner
			.withPropertyValues("feedback.news.match-before-minutes=-1")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class));
		contextRunner
			.withPropertyValues("feedback.news.match-after-minutes=-1")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("max-sources-per-card가 1 미만이면 기동이 실패한다")
	void failsWhenMaxSourcesPerCardIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.news.max-sources-per-card=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-sources-per-card"));
	}

	@Test
	@DisplayName("feedback.news 설정을 하나도 주지 않아도 §C-7 목록 상한 3키가 기본값으로 바인딩된다")
	void bindsSpecItemLimitDefaultsWhenNoFeedbackNewsPropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			FeedbackNewsProperties properties = context.getBean(FeedbackNewsProperties.class);
			assertThat(properties.maxItemsPerNewsList()).isEqualTo(SPEC_MAX_ITEMS_PER_NEWS_LIST);
			assertThat(properties.maxItemsPerBriefing()).isEqualTo(SPEC_MAX_ITEMS_PER_BRIEFING);
			assertThat(properties.maxItemsPerSummary()).isEqualTo(SPEC_MAX_ITEMS_PER_SUMMARY);
		});
	}

	@Test
	@DisplayName("목록 상한 3키를 케밥케이스로 주면 모두 덮어써지고 근거 매칭 3키는 그대로다")
	void bindsEveryItemLimitPropertyFromKebabCaseKeys() {
		contextRunner
			.withPropertyValues(
				"feedback.news.max-items-per-news-list=11",
				"feedback.news.max-items-per-briefing=12",
				"feedback.news.max-items-per-summary=13")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackNewsProperties properties = context.getBean(FeedbackNewsProperties.class);
				assertThat(properties.maxItemsPerNewsList()).isEqualTo(11);
				assertThat(properties.maxItemsPerBriefing()).isEqualTo(12);
				assertThat(properties.maxItemsPerSummary()).isEqualTo(13);
				assertThat(properties.matchBeforeMinutes()).isEqualTo(30);
				assertThat(properties.matchAfterMinutes()).isEqualTo(5);
				assertThat(properties.maxSourcesPerCard()).isEqualTo(5);
			});
	}

	@Test
	@DisplayName("max-items-per-news-list가 1 미만이면 기동이 실패한다")
	void failsWhenMaxItemsPerNewsListIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.news.max-items-per-news-list=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-items-per-news-list"));
	}

	@Test
	@DisplayName("max-items-per-briefing이 1 미만이면 기동이 실패한다")
	void failsWhenMaxItemsPerBriefingIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.news.max-items-per-briefing=0")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-items-per-briefing"));
	}

	@Test
	@DisplayName("max-items-per-summary가 1 미만이면 기동이 실패한다")
	void failsWhenMaxItemsPerSummaryIsBelowOne() {
		contextRunner
			.withPropertyValues("feedback.news.max-items-per-summary=-1")
			.run(context -> assertThat(context)
				.hasFailed()
				.getFailure()
				.rootCause()
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-items-per-summary"));
	}

	@Test
	@DisplayName("자격증명 3종은 설정이 없으면 비어 있다 — 시크릿 기본값이 코드에 박히지 않는다")
	void credentialsHaveNoBakedInDefaultValue() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			NaverSearchProperties naverSearch = context.getBean(NaverSearchProperties.class);
			assertThat(naverSearch.clientId()).isNullOrEmpty();
			assertThat(naverSearch.clientSecret()).isNullOrEmpty();
			assertThat(context.getBean(DartProperties.class).apiKey()).isNullOrEmpty();
		});
	}

	@Test
	@DisplayName("§C-7이 정한 최상위 키 경로로 자격증명 3종이 바인딩된다")
	void bindsCredentialsFromTopLevelKeyPaths() {
		contextRunner
			.withPropertyValues(
				"naver-search.client-id=search-id",
				"naver-search.client-secret=search-secret",
				"dart.api-key=dart-key")
			.run(context -> {
				assertThat(context).hasNotFailed();

				NaverSearchProperties naverSearch = context.getBean(NaverSearchProperties.class);
				assertThat(naverSearch.clientId()).isEqualTo("search-id");
				assertThat(naverSearch.clientSecret()).isEqualTo("search-secret");
				assertThat(context.getBean(DartProperties.class).apiKey()).isEqualTo("dart-key");
			});
	}

	@Test
	@DisplayName("oauth.naver 키를 채워도 네이버 검색 자격증명은 채워지지 않는다")
	void doesNotReuseOauthNaverCredentials() {
		contextRunner
			.withPropertyValues(
				"oauth.naver.client-id=login-id",
				"oauth.naver.client-secret=login-secret",
				"naver.client-id=login-id",
				"naver.client-secret=login-secret")
			.run(context -> {
				assertThat(context).hasNotFailed();

				NaverSearchProperties naverSearch = context.getBean(NaverSearchProperties.class);
				assertThat(naverSearch.clientId()).isNullOrEmpty();
				assertThat(naverSearch.clientSecret()).isNullOrEmpty();
			});
	}
}
