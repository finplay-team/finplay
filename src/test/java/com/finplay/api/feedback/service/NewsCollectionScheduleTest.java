// 수집 스케줄 2종의 zone·크론 참조와, 크론이 `전장` 구간을 빠짐없이 덮는지를 CronExpression으로 검증한다.
package com.finplay.api.feedback.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

// 기대값의 정본은 spec.md §C-1(크론 값·zone 규칙)과 §C-2(`전장` 구간 [D-1 15:30, D 09:00])다.
//
// 저장 테스트로는 이 축이 절대 드러나지 않는다. 장중만 도는 크론이어도 "Fake가 준 기사가 저장된다"는 통과하고,
// 운영에서만 전장 구간이 통째로 빈다 — 그래서 표현식 자체를 직접 단정한다.
//
// yml과 이 상수의 일치는 NewsCollectionPropertiesIntegrationTest(항목 1)가 Environment로 이미 대조한다.
class NewsCollectionScheduleTest {

	// §C-1 뉴스 수집 크론 (24시간 30분 간격)
	private static final String SPEC_COLLECT_CRON = "0 0/30 * * * *";

	// §C-1 공시 수집 크론
	private static final String SPEC_DISCLOSURE_CRON = "0 0/30 8-20 * * MON-FRI";

	// §C-2 `전장` — D-1 15:30부터 D 09:00까지. 2026-08-03(월)을 D로 잡으면 D-1은 2026-07-31(금)이 아니라
	// 달력상 전일이어야 크론 검증이 요일에 흔들리지 않으므로, 평일 연속인 화(D-1)~수(D)를 쓴다.
	private static final LocalDateTime PRE_MARKET_START = LocalDateTime.of(2026, 8, 4, 15, 30);
	private static final LocalDateTime PRE_MARKET_END = LocalDateTime.of(2026, 8, 5, 9, 0);

	// §완료 조건이 지목한 세 시각 — 저녁·심야·아침
	private static final LocalDateTime EVENING = LocalDateTime.of(2026, 8, 4, 19, 40);
	private static final LocalDateTime MIDNIGHT = LocalDateTime.of(2026, 8, 5, 2, 10);
	private static final LocalDateTime MORNING = LocalDateTime.of(2026, 8, 5, 8, 30);

	private static final Duration COLLECT_INTERVAL = Duration.ofMinutes(30);

	// §C-1 — cron 기반 @Scheduled에 zone을 빠뜨리면 배포 JVM 기본 타임존이 UTC라 예외도 로그도 없이 엉뚱한
	// 시각에 돈다. 붙어 있는지는 애노테이션을 직접 읽어야만 알 수 있다.
	@Test
	@DisplayName("수집 스케줄 2종에 zone = \"Asia/Seoul\"이 붙어 있다")
	void bothScheduledMethodsDeclareSeoulZone() throws NoSuchMethodException {
		assertThat(scheduled("collectNews").zone()).isEqualTo("Asia/Seoul");
		assertThat(scheduled("collectDisclosures").zone()).isEqualTo("Asia/Seoul");
	}

	// 크론 값을 코드에 상수로 박지 않는다 — 정본은 §C-1이고 운영 조정은 application.yml에서 한다(§C-7).
	@Test
	@DisplayName("크론 값을 코드에 박지 않고 feedback.news.* 프로퍼티를 참조한다")
	void bothScheduledMethodsReferenceConfiguredCronProperties() throws NoSuchMethodException {
		assertThat(scheduled("collectNews").cron()).isEqualTo("${feedback.news.collect-cron}");
		assertThat(scheduled("collectDisclosures").cron())
			.isEqualTo("${feedback.news.disclosure-cron}");
	}

	// ② 저녁·심야·아침 세 시각에 발행된 기사가 그날 안에 수집된다 — 각 시각 이후 30분 안에 실행이 있어야 한다.
	@Test
	@DisplayName("저녁·심야·아침에 발행된 기사가 30분 안의 실행으로 수집된다")
	void collectCronRunsWithinThirtyMinutesOfEveningMidnightAndMorning() {
		CronExpression cron = CronExpression.parse(SPEC_COLLECT_CRON);

		for (LocalDateTime published : List.of(EVENING, MIDNIGHT, MORNING)) {
			LocalDateTime next = cron.next(published);
			assertThat(next).as("%s 발행 기사를 집을 실행이 있어야 한다", published).isNotNull();
			assertThat(Duration.between(published, next))
				.as("%s 이후 첫 실행까지의 간격", published)
				.isLessThanOrEqualTo(COLLECT_INTERVAL);
		}
	}

	// ②의 본체 — "빠짐없이"는 세 시점이 아니라 구간 전체의 성질이다. 전장 구간 안의 어느 순간에 기사가 나와도
	// 30분 안에 실행이 오는지를 구간 끝까지 훑어 확인한다. 장중 한정 크론이면 15:30~다음 09:00 사이에
	// 몇 시간짜리 공백이 생겨 여기서 깨진다.
	@Test
	@DisplayName("`전장` 구간 전체에서 실행 간격이 30분을 넘지 않는다")
	void collectCronCoversWholePreMarketWindowWithoutGap() {
		CronExpression cron = CronExpression.parse(SPEC_COLLECT_CRON);
		List<LocalDateTime> executions = executionsBetween(cron, PRE_MARKET_START, PRE_MARKET_END);

		assertThat(executions).as("전장 구간에 실행이 하나도 없다").isNotEmpty();
		LocalDateTime previous = PRE_MARKET_START;
		for (LocalDateTime execution : executions) {
			assertThat(Duration.between(previous, execution))
				.as("%s 직후의 수집 공백", previous)
				.isLessThanOrEqualTo(COLLECT_INTERVAL);
			previous = execution;
		}
		assertThat(Duration.between(previous, PRE_MARKET_END))
			.as("전장 구간 끝(09:00)까지 남은 공백")
			.isLessThanOrEqualTo(COLLECT_INTERVAL);
	}

	// 종일 돈다는 것의 다른 표현 — 하루 48회다. 시간대 제한이 붙는 순간 이 값이 줄어든다.
	@Test
	@DisplayName("뉴스 수집 크론은 하루 48회, 자정 직후에도 실행된다")
	void collectCronRunsAllDay() {
		CronExpression cron = CronExpression.parse(SPEC_COLLECT_CRON);
		LocalDateTime dayStart = LocalDateTime.of(2026, 8, 5, 0, 0);

		// next()는 인자 시각을 포함하지 않으므로 하루의 첫 실행(00:00)을 세려면 직전 시각에서 출발한다.
		assertThat(executionsBetween(cron, dayStart.minusSeconds(1), dayStart.plusDays(1)))
			.hasSize(48)
			.startsWith(dayStart)
			.endsWith(LocalDateTime.of(2026, 8, 5, 23, 30));
	}

	// 공시는 주식만이고 OpenDART 접수 시간대에만 새 건이 생긴다 — 뉴스 크론과 달리 시간대 제한이 있는 것이 정상이다.
	@Test
	@DisplayName("공시 수집 크론은 평일 8~20시에만 돌고 주말·새벽에는 돌지 않는다")
	void disclosureCronRunsOnlyOnWeekdayBusinessHours() {
		CronExpression cron = CronExpression.parse(SPEC_DISCLOSURE_CRON);

		// 평일(수) 09:00 발행 → 30분 안에 실행
		assertThat(Duration.between(MORNING, cron.next(MORNING)))
			.isLessThanOrEqualTo(COLLECT_INTERVAL);
		// 평일 새벽 02:10 → 그날 08시대까지 실행이 없다
		assertThat(cron.next(MIDNIGHT)).isEqualTo(LocalDateTime.of(2026, 8, 5, 8, 0));
		// 토요일 10:00 → 다음 실행은 월요일이다
		LocalDateTime saturday = LocalDateTime.of(2026, 8, 8, 10, 0);
		assertThat(cron.next(saturday).getDayOfWeek().getValue()).isEqualTo(1);
	}

	// ①의 뒷면 — "재생 시점이 아니라 기사 당일에 수집한다"는 재생 경로가 수집을 부르지 않아야 성립한다.
	// 수집 진입점은 @Scheduled 2종뿐이고 다른 어떤 코드도 이 서비스를 부르지 않는다는 것을 소스에서 직접 본다.
	@Test
	@DisplayName("수집 서비스를 부르는 코드가 스케줄 진입점 외에 없다 — 재생 경로가 수집을 부르지 않는다")
	void noOtherSourceFileTriggersCollection() throws IOException {
		Path serviceFile = Path.of(
			"src/main/java/com/finplay/api/feedback/service/NewsCollectionService.java");
		try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
			List<Path> callers = sources
				.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> !path.equals(serviceFile))
				.filter(path -> readString(path).contains("NewsCollectionService"))
				.toList();

			assertThat(callers)
				.as("수집을 부르는 다른 경로가 생기면 '기사 당일 수집'이 재생 시점 수집으로 바뀔 수 있다")
				.isEmpty();
		}
	}

	private static String readString(Path path) {
		try {
			return Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static List<LocalDateTime> executionsBetween(
		CronExpression cron, LocalDateTime from, LocalDateTime to) {
		List<LocalDateTime> executions = new ArrayList<>();
		LocalDateTime cursor = cron.next(from);
		while (cursor != null && cursor.isBefore(to)) {
			executions.add(cursor);
			cursor = cron.next(cursor);
		}
		return executions;
	}

	private static Scheduled scheduled(String methodName) throws NoSuchMethodException {
		Scheduled annotation = NewsCollectionService.class.getMethod(methodName).getAnnotation(Scheduled.class);
		assertThat(annotation).as("%s에 @Scheduled가 없다", methodName).isNotNull();
		return annotation;
	}
}
