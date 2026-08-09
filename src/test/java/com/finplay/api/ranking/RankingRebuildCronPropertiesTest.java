// application.yml의 ranking.rebuild.cron이 plan.md 확정값(매일 04:20 KST)과 갈리지 않는지 검증한다.
package com.finplay.api.ranking;

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

// 왜 별도 파일인가. RankingRebuildServiceTest는 @Scheduled(cron = "${ranking.rebuild.cron}")라는 **참조
// 형태**만 리플렉션으로 확인하고, 04:20 단정은 그 클래스가 스스로 들고 있는 상수(EXPECTED_CRON)를 파싱한다 —
// application.yml의 실제 값이 바뀌어도 그 테스트는 초록이다. @Scheduled는 record가 아니라 Environment에서
// 값을 읽으므로 실제 스케줄을 정하는 것은 yml이고, 그 드리프트를 잡는 자리가 여기다.
//
// spring.config.additional-location을 비워 두고 돌린다. build.gradle이 test 태스크 전체에 이 시스템
// 프로퍼티를 걸어 ranking-rebuild-schedule-disabled-for-tests.yml을 얹는데(테스트 중 크론이 실제로 등록되는
// 것을 막는다), ConfigDataApplicationContextInitializer는 그 프로퍼티를 @SpringBootTest와 똑같이 해석하므로
// 비우지 않으면 여기서도 값이 "-"로 덮여 보인다. withSystemProperties는 run() 동안만 적용하고 끝나면
// 원래 값을 되돌린다(FeedbackBatchPropertiesTest가 같은 이유로 같은 형태를 쓴다).
//
// 컨테이너는 띄우지 않는다 — 이 항목에 DB·Redis가 필요 없다.
class RankingRebuildCronPropertiesTest {

	// plan.md "Decision Gate 확정"의 값. 기대값의 정본은 그 문서다.
	private static final String PLAN_REBUILD_CRON = "0 20 4 * * *";

	@Test
	@DisplayName("application.yml에 ranking.rebuild.cron이 plan.md 확정값으로 실제 존재한다")
	void applicationYmlDeclaresTheRankingRebuildCronKey() {
		runWithApplicationYml(
			environment -> assertThat(environment.getProperty("ranking.rebuild.cron")).isEqualTo(PLAN_REBUILD_CRON));
	}

	// 값이 문자열이라 오타가 나도 기동 전까지는 드러나지 않는다 — 파싱 가능성부터 확정한다.
	@Test
	@DisplayName("yml의 크론이 파싱 가능하고 매일 04:20에 하루 1회만 돈다")
	void ymlCronIsParsableAndRunsOnceADayAt0420() {
		runWithApplicationYml(environment -> {
			String cron = environment.getProperty("ranking.rebuild.cron");
			assertThatCode(() -> CronExpression.parse(cron)).doesNotThrowAnyException();

			LocalDateTime next = CronExpression.parse(cron).next(LocalDateTime.of(2026, 8, 9, 0, 0));
			assertThat(next).isEqualTo(LocalDateTime.of(2026, 8, 9, 4, 20));
			// 다음 실행이 정확히 하루 뒤여야 한다 — "0 20 4 * * *"에서 한 글자만 어긋나 매시 24회로
			// 넓어지는 회귀(feedback의 매시 05분 크론과 같은 함정)가 여기서 걸린다.
			assertThat(CronExpression.parse(cron).next(next)).isEqualTo(LocalDateTime.of(2026, 8, 10, 4, 20));
		});
	}

	// 04:20을 고른 근거는 "기존 크론과 같은 분에 걸치지 않는다"였다(application.yml 주석). 근거가 주석에만
	// 있으면 나중에 다른 크론이 04:20으로 옮겨 와도 아무도 모른다 — yml에 실제로 적힌 다른 크론들과 대조한다.
	//
	// 매 분 계열은 대조에서 뺀다. market.crypto.price-snapshot-cron("0 * * * * *")은 매 분 0초에 도므로
	// 04:20:00에 **반드시** 겹친다 — 어떤 시각을 골라도 피할 수 없다(실측으로 확인했고, 그래서
	// application.yml 주석의 "매 분 계열과는 초까지 포함해 어긋난다"는 이 크론에 대해서는 사실이 아니다).
	// 회피 대상이 아니므로 단정하지 않는다. feedback.batch.crypto-watch-cron("30 * * * * *")도 같은 이유로
	// 뺀다 — 지금은 초가 30이라 우연히 어긋날 뿐 매 분 계열이라는 성격은 같다. 겹침이 실제로 문제가 되는
	// 것은 하루/매시/평일 단위로 무겁게 도는 배치들이고, 이 테스트가 지키는 것은 그쪽이다.
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

				// 재구성 실행 시각 직전에서 출발해 그 크론의 다음 실행을 본다 — 정확히 같은 시각이면 충돌이다.
				LocalDateTime other = CronExpression.parse(expression).next(rebuildRun.minusSeconds(1));
				assertThat(other)
					.as("%s(%s)가 랭킹 재구성 크론과 같은 시각에 돈다 — 04:20을 고른 근거가 깨졌다", key, expression)
					.isNotEqualTo(rebuildRun);
			}
		});
	}

	// application.yml에 실제로 존재하는 하루·매시 단위 배치 크론 키들(값이 아니라 키로 읽어 각 spec의 정본을
	// 중복 선언하지 않는다). 키가 사라지면 위 테스트가 null로 즉시 실패해 목록이 썩는 것을 막는다.
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
