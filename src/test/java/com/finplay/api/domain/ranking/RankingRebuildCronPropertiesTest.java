package com.finplay.api.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;

class RankingRebuildCronPropertiesTest {

	private static final String PLAN_REBUILD_CRON = "0 20 4 * * *";

	@Test
	@DisplayName("application.yml에 ranking.rebuild.cron이 plan.md 확정값으로 실제 존재한다")
	void applicationYmlDeclaresTheRankingRebuildCronKey() {
		runWithApplicationYml(
			environment -> assertThat(environment.getProperty("ranking.rebuild.cron")).isEqualTo(PLAN_REBUILD_CRON));
	}

	@Test
	@DisplayName("yml의 크론이 파싱 가능하고 매일 04:20에 하루 1회만 돈다")
	void ymlCronIsParsableAndRunsOnceADayAt0420() {
		runWithApplicationYml(environment -> {
			String cron = environment.getProperty("ranking.rebuild.cron");
			assertThatCode(() -> CronExpression.parse(cron)).doesNotThrowAnyException();

			LocalDateTime next = CronExpression.parse(cron).next(LocalDateTime.of(2026, 8, 9, 0, 0));
			assertThat(next).isEqualTo(LocalDateTime.of(2026, 8, 9, 4, 20));
			assertThat(CronExpression.parse(cron).next(next)).isEqualTo(LocalDateTime.of(2026, 8, 10, 4, 20));
		});
	}

	@Test
	@DisplayName("랭킹 재구성 크론이 yml의 하루·매시 단위 배치 크론과 같은 시각에 겹치지 않는다")
	void rankingRebuildCronDoesNotCollideWithAnyOtherCronInTheYml() {
		runWithApplicationYml(environment -> {
			CronExpression rebuild = CronExpression.parse(environment.getProperty("ranking.rebuild.cron"));
			LocalDateTime from = LocalDateTime.of(2026, 8, 9, 0, 0);
			LocalDateTime rebuildRun = rebuild.next(from);

			for (String key : OTHER_CRON_KEYS) {
				String expression = environment.getProperty(key);
				assertThat(expression).as("yml 키가 사라졌거나 이름이 바뀌었다: %s", key).isNotNull();

				LocalDateTime other = CronExpression.parse(expression).next(rebuildRun.minusSeconds(1));
				assertThat(other)
					.as("%s(%s)가 랭킹 재구성 크론과 같은 시각에 돈다 — 04:20을 고른 근거가 깨졌다", key, expression)
					.isNotEqualTo(rebuildRun);
			}
		});
	}

	private static final List<String> OTHER_CRON_KEYS = List.of(
		"feedback.batch.cron",
		"feedback.batch.crypto-cron",
		"feedback.batch.peer-stats-cron",
		"feedback.batch.crypto-peer-stats-cron",
		"feedback.news.collect-cron",
		"feedback.news.disclosure-cron");

	private void runWithApplicationYml(Consumer<Environment> assertions) {
		new ApplicationContextRunner()
			.withSystemProperties("spring.config.additional-location=")
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.run(context -> assertions.accept(context.getEnvironment()));
	}
}
