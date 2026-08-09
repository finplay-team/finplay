// 테스트 컨텍스트에서 랭킹 재구성 배치(ranking.rebuild.cron) 트리거가 실제로 등록되지 않는지 검증하는 통합 테스트다.
package com.finplay.api.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.service.StockReplaySessionScheduler;
import com.finplay.api.ranking.service.RankingRebuildService;
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

// 왜 끄는가. @SpringBootTest는 컨텍스트를 통째로 띄우므로 테스트 실행 중 크론이 실제로 등록된다. 이 배치는
// 다른 배치들과 성격이 다르다 — MySQL에 행을 "더하는" 것이 아니라 공유 Testcontainers Redis의 ranking:{market}
// 키를 임시 키 RENAME으로 **통째로 교체**한다(RankingStore.replaceAll). 전체 빌드가 04:20 KST에 걸치면
// RankingIntegrationTest가 그 순간 깨진다: 이 클래스는 AFTER_COMMIT 검증 때문에 @Transactional을 붙일 수
// 없어(클래스 상단 주석) 공유 MySQL에 매도 이력이 실제로 남고, 재구성이 그 이력 전부를 ZSET에 되살리므로
// @BeforeEach가 비워 둔 키에 다른 계좌들이 섞여 들어와 hasSize(1)·noneMatch(...) 같은 단정이 무너진다.
// 하루 한 번, 그 시각에만 재현되는 실패라 원인 추적이 가장 어려운 종류다 —
// feedback-schedules-disabled-for-tests.yml 헤더가 경고하는 것과 같은 함정이다.
//
// "-"(Scheduled.CRON_DISABLED)로 덮으면 ScheduledAnnotationBeanPostProcessor가 플레이스홀더를 먼저 해석한
// 뒤 CronTask를 아예 만들지 않으므로, "트리거가 없다"를 직접 단정할 수 있다.
//
// 기동 훅(@EventListener(ApplicationReadyEvent))은 끄지 않는다. 컨텍스트 생성 시점(= 테스트 클래스 경계)에만
// 돌아 테스트 메서드 도중에 끼어들 수 없고, 실제로 배선이 살아 있는지 확인하는 값이 있다. 시각에 묶여
// 테스트 도중 끼어드는 것은 크론뿐이다.
//
// application.yml에 적힌 운영 값 자체의 드리프트는 이 파일이 아니라 RankingRebuildCronPropertiesTest가 본다
// (spring.config.additional-location을 비우고 application.yml만 읽는 형태).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RankingRebuildScheduleDisabledIntegrationTest {

	// plan.md "Decision Gate 확정"의 운영 크론. 어떤 경로로든 이 표현식이 트리거로 살아 있으면 안 된다.
	private static final String PRODUCTION_CRON = "0 20 4 * * *";

	private static final String RANKING_REBUILD_SCHEDULE = scheduledMethodName(RankingRebuildService.class,
		"rebuildOnSchedule");

	// 아래 단정이 "스케줄링 자체가 꺼진 컨텍스트"에서 헛되이 통과하지 않도록 두는 대조군이다. 크론을 코드에
	// 박아 둔 기존 스케줄이라 이 파일의 영향을 받을 이유가 없다(FeedbackScheduleDisabledIntegrationTest와 동일).
	private static final String CONTROL_SCHEDULE = scheduledMethodName(StockReplaySessionScheduler.class,
		"resolveTodaySession");

	private final ScheduledTaskHolder scheduledTaskHolder;

	private final Environment environment;

	@Autowired
	RankingRebuildScheduleDisabledIntegrationTest(ScheduledTaskHolder scheduledTaskHolder, Environment environment) {
		this.scheduledTaskHolder = scheduledTaskHolder;
		this.environment = environment;
	}

	// 아래 단정들의 전제다. 이 키가 덮이지 않으면 크론은 운영 값 그대로이고, 그때는 트리거가 등록된다.
	@Test
	@DisplayName("테스트 컨텍스트에서 ranking.rebuild.cron이 Scheduled.CRON_DISABLED로 덮여 있다")
	void testContextOverridesTheRankingRebuildCronWithCronDisabled() {
		assertThat(environment.getProperty("ranking.rebuild.cron")).isEqualTo(Scheduled.CRON_DISABLED);
	}

	// 애노테이션은 그대로 붙어 있고 프로퍼티 값만 "-"이므로, 실제로 트리거가 만들어지지 않았는지는 등록된
	// ScheduledTask를 직접 열어 봐야만 알 수 있다.
	@Test
	@DisplayName("기동한 컨텍스트에 랭킹 재구성 크론 트리거가 등록되지 않는다")
	void noCronTriggerIsRegisteredForTheRankingRebuild() {
		assertThat(registeredCronTaskNames())
			.as("크론이 꺼졌는데도 트리거가 등록되면 04:20에 도는 전체 빌드에서 공유 Redis의 랭킹 키가 교체된다")
			.doesNotContain(RANKING_REBUILD_SCHEDULE);
	}

	// 위 단정만 있으면 스케줄링 자체가 꺼진 컨텍스트에서도 초록이 된다 — "랭킹 것만 꺼졌다"가 성립하려면
	// 코드에 크론을 박아 둔 기존 스케줄은 여전히 등록돼야 한다.
	@Test
	@DisplayName("랭킹 밖의 크론 스케줄은 그대로 등록된다 — 스케줄링 전체가 꺼진 것이 아니다")
	void otherDomainCronTriggersAreStillRegistered() {
		assertThat(registeredCronTaskNames()).contains(CONTROL_SCHEDULE);
	}

	// 소유 클래스가 바뀌거나 다른 빈이 같은 크론을 물고 등록되는 경로가 생겨도 걸리도록 표현식으로도 본다.
	@Test
	@DisplayName("등록된 크론 표현식 중 랭킹 재구성 운영 크론(04:20)이 없다")
	void noRegisteredCronExpressionMatchesTheRankingRebuildCron() {
		assertThat(scheduledTaskHolder.getScheduledTasks())
			.filteredOn(task -> task.getTask() instanceof CronTask)
			.extracting(task -> ((CronTask)task.getTask()).getExpression())
			.doesNotContain(PRODUCTION_CRON);
	}

	// 등록된 크론 태스크를 "선언 클래스명.메서드명"으로 편다. Task.getRunnable()로는 원본을 꺼낼 수 없고
	// (private OutcomeTrackingRunnable로 감싼다) toString()이 원본으로 위임하는 것이 지원되는 유일한 식별
	// 경로다 — FeedbackScheduleDisabledIntegrationTest의 같은 헬퍼와 근거가 같다.
	private List<String> registeredCronTaskNames() {
		return scheduledTaskHolder.getScheduledTasks().stream()
			.map(ScheduledTask::getTask)
			.filter(CronTask.class::isInstance)
			.map(Object::toString)
			.toList();
	}

	// 문자열을 손으로 적지 않고 조립한다. 메서드 이름이 바뀌면 즉시 드러난다 — 오타 난 이름은 어떤 태스크와도
	// 안 맞아 조용히 통과한다.
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
