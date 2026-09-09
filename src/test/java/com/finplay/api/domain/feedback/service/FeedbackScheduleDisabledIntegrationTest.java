package com.finplay.api.domain.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.service.StockReplaySessionScheduler;
import java.util.List;
import java.util.Set;
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
class FeedbackScheduleDisabledIntegrationTest {

	private static final List<String> FEEDBACK_SCHEDULES = List.of(
		scheduledMethodName(FeedbackBatchService.class, "runPreMarketBatch"),
		scheduledMethodName(NewsCollectionService.class, "collectNews"),
		scheduledMethodName(NewsCollectionService.class, "collectDisclosures"),
		scheduledMethodName(CryptoFeedbackBatchService.class, "refreshCryptoFeedback"));

	private static final String CONTROL_SCHEDULE = scheduledMethodName(StockReplaySessionScheduler.class,
		"resolveTodaySession");

	private static final List<String> SPEC_CRONS = List.of("0 45 8 * * MON-FRI", "0 0/30 * * * *",
		"0 0/30 8-20 * * MON-FRI", "0 5 * * * *");

	private final ScheduledTaskHolder scheduledTaskHolder;

	private final Environment environment;

	@Autowired
	FeedbackScheduleDisabledIntegrationTest(
		ScheduledTaskHolder scheduledTaskHolder, Environment environment) {
		this.scheduledTaskHolder = scheduledTaskHolder;
		this.environment = environment;
	}

	@Test
	@DisplayName("테스트 컨텍스트에서 feedback 배치·수집 크론 4키가 Scheduled.CRON_DISABLED로 덮여 있다")
	void testContextOverridesEveryFeedbackCronWithCronDisabled() {
		assertThat(environment.getProperty("feedback.batch.cron"))
			.isEqualTo(Scheduled.CRON_DISABLED);
		assertThat(environment.getProperty("feedback.batch.crypto-cron"))
			.isEqualTo(Scheduled.CRON_DISABLED);
		assertThat(environment.getProperty("feedback.news.collect-cron"))
			.isEqualTo(Scheduled.CRON_DISABLED);
		assertThat(environment.getProperty("feedback.news.disclosure-cron"))
			.isEqualTo(Scheduled.CRON_DISABLED);
	}

	@Test
	@DisplayName("기동한 컨텍스트에 feedback 배치·수집의 크론 트리거가 하나도 등록되지 않는다")
	void noCronTriggerIsRegisteredForFeedbackBatchOrCollection() {
		assertThat(registeredCronTaskNames())
			.as("크론이 꺼졌는데도 트리거가 등록되면 테스트 실행 중 배치가 실제로 돈다")
			.doesNotContainAnyElementsOf(FEEDBACK_SCHEDULES);
	}

	@Test
	@DisplayName("feedback 밖의 크론 스케줄은 그대로 등록된다 — 스케줄링 전체가 꺼진 것이 아니다")
	void otherDomainCronTriggersAreStillRegistered() {
		assertThat(registeredCronTaskNames()).contains(CONTROL_SCHEDULE);
	}

	@Test
	@DisplayName("등록된 크론 표현식 중 §C-1의 feedback 크론 3종이 하나도 없다")
	void noRegisteredCronExpressionMatchesTheSpecFeedbackCrons() {
		Set<ScheduledTask> tasks = scheduledTaskHolder.getScheduledTasks();

		assertThat(tasks)
			.filteredOn(task -> task.getTask() instanceof CronTask)
			.extracting(task -> ((CronTask)task.getTask()).getExpression())
			.doesNotContainAnyElementsOf(SPEC_CRONS);
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
