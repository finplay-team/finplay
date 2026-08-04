// feedback.news.*·naver-search.*·dart.* 프로퍼티가 spec 012 §C-1·§C-7대로 바인딩되는지 검증한다.
package com.finplay.api.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.support.CronExpression;

// 기대값의 정본은 docs/specs/012-ai-feedback/spec.md다 — 크론 2종은 §C-1 표, 자격증명 3종의 키 경로와
// "시크릿에는 @DefaultValue를 두지 않는다"는 §C-7이다. 구현 파일이 아니라 spec에서 값을 가져와야
// record와 yml이 함께 틀어지는 드리프트가 잡힌다.
class NewsCollectionPropertiesTest {

	// §C-1 뉴스 수집 크론 (24시간 30분 간격)
	private static final String SPEC_COLLECT_CRON = "0 0/30 * * * *";

	// §C-1 공시 수집 크론
	private static final String SPEC_DISCLOSURE_CRON = "0 0/30 8-20 * * MON-FRI";

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

	// 값이 문자열이라 오타가 나도 바인딩은 통과하고, 실제 실패는 @Scheduled가 붙는 5번 항목의 기동 시점으로
	// 미뤄진다. 기본값이 파싱 가능한 표현식인지는 여기서 미리 확정한다.
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

	// §C-7 — 자격증명 3종은 시크릿이라 @DefaultValue를 붙이지 않는다. 코드에 값이 박히면 이 테스트가 깨진다.
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

	// §C-7이 확정한 키 경로는 feedback.* 밖 최상위다. naver-search 프리픽스를 naver로 되돌리거나
	// feedback 아래로 옮기면 이 테스트가 먼저 깨진다.
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

	// §외부 API 호출 상세 — 검색 키는 OAuth 로그인 키와 별개 애플리케이션의 값이다. oauth.naver 쪽 키를
	// 아무리 채워도 검색 프로퍼티는 비어 있어야 한다. 프리픽스를 naver로 바꾸는 순간 이 테스트가 깨진다.
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
