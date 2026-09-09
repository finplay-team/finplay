package com.finplay.api.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.service.StockReplaySessionScheduler;
import com.finplay.api.domain.ranking.service.RankingRebuildService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RankingRebuildScheduleDisabledIntegrationTest {

	private static final String PRODUCTION_CRON = "0 20 4 * * *";

	private static final String RANKING_REBUILD_SCHEDULE = scheduledMethodName(RankingRebuildService.class,
		"rebuildOnSchedule");

	private static final String CONTROL_SCHEDULE = scheduledMethodName(StockReplaySessionScheduler.class,
		"resolveTodaySession");

	private final ScheduledTaskHolder scheduledTaskHolder;

	private final Environment environment;

	@Autowired
	RankingRebuildScheduleDisabledIntegrationTest(ScheduledTaskHolder scheduledTaskHolder, Environment environment) {
		this.scheduledTaskHolder = scheduledTaskHolder;
		this.environment = environment;
	}

	@Test
	@DisplayName("테스트 컨텍스트에서 ranking.rebuild.cron이 Scheduled.CRON_DISABLED로 덮여 있다")
	void testContextOverridesTheRankingRebuildCronWithCronDisabled() {
		assertThat(environment.getProperty("ranking.rebuild.cron")).isEqualTo(Scheduled.CRON_DISABLED);
	}

	@Test
	@DisplayName("기동한 컨텍스트에 랭킹 재구성 크론 트리거가 등록되지 않는다")
	void noCronTriggerIsRegisteredForTheRankingRebuild() {
		assertThat(registeredCronTaskNames())
			.as("크론이 꺼졌는데도 트리거가 등록되면 04:20에 도는 전체 빌드에서 공유 Redis의 랭킹 키가 교체된다")
			.doesNotContain(RANKING_REBUILD_SCHEDULE);
	}

	@Test
	@DisplayName("랭킹 밖의 크론 스케줄은 그대로 등록된다 — 스케줄링 전체가 꺼진 것이 아니다")
	void otherDomainCronTriggersAreStillRegistered() {
		assertThat(registeredCronTaskNames()).contains(CONTROL_SCHEDULE);
	}

	@Test
	@DisplayName("등록된 크론 표현식 중 랭킹 재구성 운영 크론(04:20)이 없다")
	void noRegisteredCronExpressionMatchesTheRankingRebuildCron() {
		assertThat(scheduledTaskHolder.getScheduledTasks())
			.filteredOn(task -> task.getTask() instanceof CronTask)
			.extracting(task -> ((CronTask)task.getTask()).getExpression())
			.doesNotContain(PRODUCTION_CRON);
	}

	private List<String> registeredCronTaskNames() {
		return scheduledTaskHolder.getScheduledTasks().stream()
			.map(ScheduledTask::getTask)
			.filter(CronTask.class::isInstance)
			.map(Object::toString)
			.toList();
	}

	private static String scheduledMethodName(Class<?> type, String methodName) {
		try {
			type.getMethod(methodName);
		} catch (NoSuchMethodException ex) {
			throw new IllegalStateException(
				type.getSimpleName() + "." + methodName + "이 없다 — 스케줄 메서드 이름이 바뀌었다", ex);
		}
		return type.getName() + "." + methodName;
	}
}
