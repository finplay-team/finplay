// 테스트 컨텍스트에서 spec 012의 배치·수집 크론 트리거가 실제로 등록되지 않는지 검증하는 통합 테스트다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.service.StockReplaySessionScheduler;
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

// @SpringBootTest는 컨텍스트를 통째로 띄우므로 테스트 실행 중 스케줄이 실제로 등록된다. 평일 배치 시각이나
// 30분 간격 수집 시각에 전체 빌드가 걸치면 공유 Testcontainers MySQL(ADR-0003)에 아무도 의도하지 않은 데이터가
// 생기고, 그 시각에만 다른 테스트의 개수·유니크 단정이 흔들린다 — 재현이 시계에 묶여 있어 가장 찾기 어려운 종류다.
//
// build.gradle의 spring.config.additional-location이 얹는 feedback-schedules-disabled-for-tests.yml이 그 값을
// "-"(Scheduled.CRON_DISABLED)로 덮는다. ScheduledAnnotationBeanPostProcessor는 플레이스홀더를 먼저 해석한 뒤
// 이 값이면 CronTask를 아예 만들지 않으므로(spring-context 7.0.8 소스에서 확인) "트리거가 없다"를 직접 단정할 수 있다.
// 값을 "먼 미래 크론"으로 두는 방법이었다면 트리거가 등록은 되어 이 단정 자체가 불가능했다.
//
// 애노테이션 문자열과 §C-1 값의 대조는 여기서 하지 않는다 — FeedbackBatchScheduleTest·NewsCollectionScheduleTest가
// 리플렉션으로, NewsCollectionPropertiesYamlTest·FeedbackBatchPropertiesTest가 application.yml 원본으로 맡는다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class FeedbackScheduleDisabledIntegrationTest {

	// 크론이 꺼진 것이 확인돼야 하는 세 스케줄 (§C-1 — 개장 전 배치 1종 + 수집 2종).
	private static final List<String> FEEDBACK_SCHEDULES = List.of(
		scheduledMethodName(FeedbackBatchService.class, "runPreMarketBatch"),
		scheduledMethodName(NewsCollectionService.class, "collectNews"),
		scheduledMethodName(NewsCollectionService.class, "collectDisclosures"));

	// 아래 단정이 "스케줄링 자체가 꺼진 컨텍스트"에서 헛되이 통과하지 않도록 두는 대조군이다. 크론을 코드에
	// 박아 둔 기존 스케줄이라 이 파일의 영향을 받을 이유가 없다.
	private static final String CONTROL_SCHEDULE = scheduledMethodName(StockReplaySessionScheduler.class,
		"resolveTodaySession");

	// §C-1의 실제 크론 값. 어떤 경로로든 이 표현식이 트리거로 살아 있으면 안 된다.
	private static final List<String> SPEC_CRONS = List.of("0 45 8 * * MON-FRI", "0 0/30 * * * *",
		"0 0/30 8-20 * * MON-FRI");

	private final ScheduledTaskHolder scheduledTaskHolder;

	private final Environment environment;

	@Autowired
	FeedbackScheduleDisabledIntegrationTest(
		ScheduledTaskHolder scheduledTaskHolder, Environment environment) {
		this.scheduledTaskHolder = scheduledTaskHolder;
		this.environment = environment;
	}

	// 아래 두 단정의 전제다. 이 파일이 로드되지 않으면 크론은 §C-1 값 그대로이고, 그때는 트리거가 등록된다.
	@Test
	@DisplayName("테스트 컨텍스트에서 feedback 배치·수집 크론 3키가 Scheduled.CRON_DISABLED로 덮여 있다")
	void testContextOverridesEveryFeedbackCronWithCronDisabled() {
		assertThat(environment.getProperty("feedback.batch.cron"))
			.isEqualTo(Scheduled.CRON_DISABLED);
		assertThat(environment.getProperty("feedback.news.collect-cron"))
			.isEqualTo(Scheduled.CRON_DISABLED);
		assertThat(environment.getProperty("feedback.news.disclosure-cron"))
			.isEqualTo(Scheduled.CRON_DISABLED);
	}

	// 항목 2의 완료 조건이다. 애노테이션은 그대로 붙어 있고 프로퍼티 값만 "-"이므로, 실제로 트리거가 만들어지지
	// 않았는지는 등록된 ScheduledTask를 직접 열어 봐야만 알 수 있다.
	@Test
	@DisplayName("기동한 컨텍스트에 feedback 배치·수집의 크론 트리거가 하나도 등록되지 않는다")
	void noCronTriggerIsRegisteredForFeedbackBatchOrCollection() {
		assertThat(registeredCronTaskNames())
			.as("크론이 꺼졌는데도 트리거가 등록되면 테스트 실행 중 배치가 실제로 돈다")
			.doesNotContainAnyElementsOf(FEEDBACK_SCHEDULES);
	}

	// 위 단정만 있으면 스케줄링 자체가 꺼진 컨텍스트에서도 초록이 된다 — 실제로 이 대조군이 없었다면 등록된
	// 크론을 하나도 못 읽던 초기 구현이 그대로 통과했다. "feedback 것만 꺼졌다"가 성립하려면 코드에 크론을
	// 박아 둔 기존 스케줄은 여전히 등록돼야 한다. 이 파일이 다른 도메인까지 끄고 있으면 여기서 깨진다.
	@Test
	@DisplayName("feedback 밖의 크론 스케줄은 그대로 등록된다 — 스케줄링 전체가 꺼진 것이 아니다")
	void otherDomainCronTriggersAreStillRegistered() {
		assertThat(registeredCronTaskNames()).contains(CONTROL_SCHEDULE);
	}

	// 소유 클래스가 바뀌거나 다른 빈이 같은 크론을 물고 등록되는 경로가 생겨도 걸리도록 표현식으로도 본다.
	@Test
	@DisplayName("등록된 크론 표현식 중 §C-1의 feedback 크론 3종이 하나도 없다")
	void noRegisteredCronExpressionMatchesTheSpecFeedbackCrons() {
		Set<ScheduledTask> tasks = scheduledTaskHolder.getScheduledTasks();

		assertThat(tasks)
			.filteredOn(task -> task.getTask() instanceof CronTask)
			.extracting(task -> ((CronTask)task.getTask()).getExpression())
			.doesNotContainAnyElementsOf(SPEC_CRONS);
	}

	// 등록된 크론 태스크를 "선언 클래스명.메서드명"으로 편다. Task.getRunnable()로 ScheduledMethodRunnable을
	// 꺼내려 하면 안 된다 — Task 생성자가 private inner OutcomeTrackingRunnable로 감싸고 델리게이트를 꺼낼
	// 접근자가 없다(javadoc이 "원본 runnable이 아닐 수 있다"고 명시한다). 그 래퍼의 toString()이 원본으로
	// 위임하고 ScheduledMethodRunnable.toString()이 선언 클래스명 + 메서드명이라, 지금은 이것이 지원되는
	// 유일한 식별 경로다. @Transactional 프록시가 걸린 빈도 프록시 서브클래스 이름이 아니라 원래 클래스명으로
	// 나온다(대조군 StockReplaySessionScheduler.resolveTodaySession으로 실측).
	private List<String> registeredCronTaskNames() {
		return scheduledTaskHolder.getScheduledTasks().stream()
			.map(ScheduledTask::getTask)
			.filter(CronTask.class::isInstance)
			.map(Object::toString)
			.toList();
	}

	// 문자열을 손으로 적지 않고 조립한다. 클래스가 옮겨지면 컴파일이 깨지고, 메서드 이름이 바뀌면 여기서
	// 즉시 드러난다 — 오타 난 이름은 어떤 태스크와도 안 맞아 조용히 통과한다.
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
