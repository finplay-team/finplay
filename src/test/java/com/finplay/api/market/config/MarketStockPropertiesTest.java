// application.yml의 market.stock.collect-lock-ttl-seconds가 MarketStockProperties의 @DefaultValue와 어긋나지 않는지 검증한다.
package com.finplay.api.market.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

// FeedbackCryptoPropertiesYamlTest·RankingRebuildCronPropertiesTest와 같은 성격의 드리프트 테스트다 —
// MarketStockPropertiesTest(record 자체의 단위 테스트)가 없는 이유는 MarketStockProperties에 검증 로직(compact
// constructor)이 없어서다(market.crypto.sigma-lookback-hours처럼 다른 프로퍼티와 값을 맞춰야 하는 짝이 없다).
// 이 클래스가 지키는 것은 "yml 키가 실제로 그 경로에 있는가"와 "yml이 적용된 빈이 record 기본값과 같은 값을
// 갖는가" 둘이다 — record의 @DefaultValue만 보는 테스트는 yml 쪽 키 경로가 잘못돼도 기본값에 가려 통과한다.
//
// ConfigDataApplicationContextInitializer가 SpringApplication 부트스트랩과 같은 방식으로 application.yml만
// Environment에 얹어 준다 — Docker 없는 환경에서도 돈다.
//
// spring.config.additional-location을 비워 build.gradle이 test 태스크 전체에 거는 크론 비활성화용 additional
// yml(ranking-rebuild-schedule-disabled-for-tests.yml 등)을 배제한다 — market.stock.retry-cron(COLLECT-STAB-003)이
// 생긴 지금도 이 4개 파일 중 market.stock 키를 건드리는 파일은 없어 실제로는 영향받지 않지만(2026-08-14 확인,
// build.gradle의 spring.config.additional-location 목록을 직접 대조함), 다른 PropertiesYamlTest들과 같은 격리
// 방침을 유지한다.
class MarketStockPropertiesTest {

	// docs/specs/035-stock-collector-reliability/plan.md §락 설계 세부 확정값. 기대값의 정본은 그 문서다.
	private static final String SPEC_COLLECT_LOCK_TTL_SECONDS = "600";

	// docs/specs/035-stock-collector-reliability/plan.md §재시도 스케줄 근거 확정값. 기대값의 정본은 그 문서다.
	private static final String SPEC_RETRY_CRON = "0 15,30,45 8-10 * * MON-FRI";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withSystemProperties("spring.config.additional-location=")
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(MarketStockConfig.class);

	@Test
	@DisplayName("application.yml에 market.stock.collect-lock-ttl-seconds가 plan.md 확정값(600)으로 실제 존재한다")
	void applicationYmlDeclaresTheCollectLockTtlSecondsKey() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			assertThat(environment.getProperty("market.stock.collect-lock-ttl-seconds"))
				.isEqualTo(SPEC_COLLECT_LOCK_TTL_SECONDS);
		});
	}

	// yml이 항상 이기므로 두 곳이 갈리면 실제 동작값은 yml 쪽이고 record의 @DefaultValue는 죽은 값이 된다.
	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 record 기본값과 같은 락 TTL 값을 갖는다")
	void boundBeanMatchesDefaultValueWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			MarketStockProperties properties = context.getBean(MarketStockProperties.class);
			assertThat(properties.collectLockTtlSeconds())
				.isEqualTo(Integer.parseInt(SPEC_COLLECT_LOCK_TTL_SECONDS));
		});
	}

	// 아래 둘은 위 두 테스트(TTL)와 같은 성격의 드리프트 대조를 retry-cron(COLLECT-STAB-003)에도 적용한다 —
	// MarketStockProperties.java 클래스 주석이 "yml과 @DefaultValue 양쪽에 값을 두고 드리프트 테스트로 대조한다"를
	// 필드 하나가 아니라 레코드 전체의 방침으로 선언하고 있다.
	@Test
	@DisplayName("application.yml에 market.stock.retry-cron이 plan.md 확정값으로 실제 존재한다")
	void applicationYmlDeclaresTheRetryCronKey() {
		contextRunner.run(context -> {
			Environment environment = context.getEnvironment();

			assertThat(environment.getProperty("market.stock.retry-cron")).isEqualTo(SPEC_RETRY_CRON);
		});
	}

	@Test
	@DisplayName("application.yml을 얹은 컨텍스트의 빈이 record 기본값과 같은 재시도 크론 값을 갖는다")
	void boundBeanMatchesDefaultValueForRetryCronWhenApplicationYmlIsApplied() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();

			MarketStockProperties properties = context.getBean(MarketStockProperties.class);
			assertThat(properties.retryCron()).isEqualTo(SPEC_RETRY_CRON);
		});
	}

	// application.yml을 아예 얹지 않은(ConfigDataApplicationContextInitializer 없이) 순수 컨텍스트에서는
	// record의 @DefaultValue(TTL 600·재시도 크론 "0 15,30,45 8-10 * * MON-FRI")가 그대로 적용되어야 한다 — yml이
	// 없어도 기동은 실패하지 않는다는 것과, 위 테스트들이 "우연히 yml 값과 같아 보이는" 상황이 아니라는 것을
	// 함께 확인한다.
	@Test
	@DisplayName("market.stock 키가 전혀 없으면 record의 @DefaultValue(TTL 600·재시도 크론)로 바인딩된다")
	void fallsBackToRecordDefaultValueWhenYmlKeyIsAbsent() {
		new ApplicationContextRunner()
			.withUserConfiguration(MarketStockConfig.class)
			.run(context -> {
				assertThat(context).hasNotFailed();

				MarketStockProperties properties = context.getBean(MarketStockProperties.class);
				assertThat(properties.collectLockTtlSeconds()).isEqualTo(600);
				assertThat(properties.retryCron()).isEqualTo(SPEC_RETRY_CRON);
			});
	}
}
