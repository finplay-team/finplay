package com.finplay.api.domain.feedback.service;

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

class NewsCollectionScheduleTest {

	private static final String SPEC_COLLECT_CRON = "0 0/30 * * * *";

	private static final String SPEC_DISCLOSURE_CRON = "0 0/30 8-20 * * MON-FRI";

	private static final LocalDateTime PRE_MARKET_START = LocalDateTime.of(2026, 8, 4, 15, 30);
	private static final LocalDateTime PRE_MARKET_END = LocalDateTime.of(2026, 8, 5, 9, 0);

	private static final LocalDateTime EVENING = LocalDateTime.of(2026, 8, 4, 19, 40);
	private static final LocalDateTime MIDNIGHT = LocalDateTime.of(2026, 8, 5, 2, 10);
	private static final LocalDateTime MORNING = LocalDateTime.of(2026, 8, 5, 8, 30);

	private static final Duration COLLECT_INTERVAL = Duration.ofMinutes(30);

	@Test
	@DisplayName("수집 스케줄 2종에 zone = \"Asia/Seoul\"이 붙어 있다")
	void bothScheduledMethodsDeclareSeoulZone() throws NoSuchMethodException {
		assertThat(scheduled("collectNews").zone()).isEqualTo("Asia/Seoul");
		assertThat(scheduled("collectDisclosures").zone()).isEqualTo("Asia/Seoul");
	}

	@Test
	@DisplayName("크론 값을 코드에 박지 않고 feedback.news.* 프로퍼티를 참조한다")
	void bothScheduledMethodsReferenceConfiguredCronProperties() throws NoSuchMethodException {
		assertThat(scheduled("collectNews").cron()).isEqualTo("${feedback.news.collect-cron}");
		assertThat(scheduled("collectDisclosures").cron())
			.isEqualTo("${feedback.news.disclosure-cron}");
	}

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

	@Test
	@DisplayName("뉴스 수집 크론은 하루 48회, 자정 직후에도 실행된다")
	void collectCronRunsAllDay() {
		CronExpression cron = CronExpression.parse(SPEC_COLLECT_CRON);
		LocalDateTime dayStart = LocalDateTime.of(2026, 8, 5, 0, 0);

		assertThat(executionsBetween(cron, dayStart.minusSeconds(1), dayStart.plusDays(1)))
			.hasSize(48)
			.startsWith(dayStart)
			.endsWith(LocalDateTime.of(2026, 8, 5, 23, 30));
	}

	@Test
	@DisplayName("공시 수집 크론은 평일 8~20시에만 돌고 주말·새벽에는 돌지 않는다")
	void disclosureCronRunsOnlyOnWeekdayBusinessHours() {
		CronExpression cron = CronExpression.parse(SPEC_DISCLOSURE_CRON);

		assertThat(Duration.between(MORNING, cron.next(MORNING)))
			.isLessThanOrEqualTo(COLLECT_INTERVAL);
		assertThat(cron.next(MIDNIGHT)).isEqualTo(LocalDateTime.of(2026, 8, 5, 8, 0));
		LocalDateTime saturday = LocalDateTime.of(2026, 8, 8, 10, 0);
		assertThat(cron.next(saturday).getDayOfWeek().getValue()).isEqualTo(1);
	}

	@Test
	@DisplayName("수집 서비스를 부르는 코드가 스케줄 진입점 외에 없다 — 재생 경로가 수집을 부르지 않는다")
	void noOtherSourceFileTriggersCollection() throws IOException {
		Path serviceFile = Path.of(
			"src/main/java/com/finplay/api/domain/feedback/service/NewsCollectionService.java");
		Path cryptoWatcherFile = Path.of(
			"src/main/java/com/finplay/api/domain/feedback/service/CryptoPriceMoveWatcher.java");
		try (Stream<Path> sources = Files.walk(Path.of("src/main/java"))) {
			List<Path> callers = sources
				.filter(path -> path.toString().endsWith(".java"))
				.filter(path -> !path.equals(serviceFile))
				.filter(path -> !path.equals(cryptoWatcherFile))
				.filter(path -> stripComments(readString(path)).contains("NewsCollectionService"))
				.toList();

			assertThat(callers)
				.as("수집을 부르는 다른 경로가 생기면 '기사 당일 수집'이 재생 시점 수집으로 바뀔 수 있다")
				.isEmpty();
		}
	}

	@Test
	@DisplayName("주석 제거가 코드 참조는 남기고 설명 주석만 지운다")
	void stripCommentsRemovesOnlyCommentsAndKeepsEveryCodeReference() {
		assertThat(stripComments("// 수집은 NewsCollectionService가 맡는다\nclass A {}"))
			.doesNotContain("NewsCollectionService");
		assertThat(stripComments("/* NewsCollectionService 참고 */\nclass A {}"))
			.doesNotContain("NewsCollectionService");
		assertThat(stripComments("/** {@link NewsCollectionService} */\nclass A {}"))
			.doesNotContain("NewsCollectionService");

		assertThat(
			stripComments("import com.finplay.api.domain.feedback.service.newscollection.NewsCollectionService;"))
			.contains("NewsCollectionService");
		assertThat(stripComments("private final NewsCollectionService collector;"))
			.contains("NewsCollectionService");
		assertThat(stripComments("void f(NewsCollectionService s) { s.collectNews(); }"))
			.contains("NewsCollectionService");

		assertThat(stripComments("String u = \"http://x\"; NewsCollectionService s;"))
			.contains("NewsCollectionService");
		assertThat(stripComments("String q = \"\"\"\n  a // b\n  \"\"\"; NewsCollectionService s;"))
			.contains("NewsCollectionService");
		assertThat(stripComments("// 그 서비스는 \"수집\"만 한다\nNewsCollectionService s;"))
			.contains("NewsCollectionService");
	}

	static String stripComments(String source) {
		StringBuilder out = new StringBuilder(source.length());
		int index = 0;
		while (index < source.length()) {
			char current = source.charAt(index);
			if (source.startsWith("//", index)) {
				while (index < source.length() && source.charAt(index) != '\n') {
					index++;
				}
			} else if (source.startsWith("/*", index)) {
				int end = source.indexOf("*/", index + 2);
				index = end < 0 ? source.length() : end + 2;
			} else if (source.startsWith("\"\"\"", index)) {
				int end = source.indexOf("\"\"\"", index + 3);
				int stop = end < 0 ? source.length() : end + 3;
				out.append(source, index, stop);
				index = stop;
			} else if (current == '"' || current == '\'') {
				index = appendLiteral(source, index, current, out);
			} else {
				out.append(current);
				index++;
			}
		}
		return out.toString();
	}

	private static int appendLiteral(String source, int start, char quote, StringBuilder out) {
		out.append(quote);
		int index = start + 1;
		while (index < source.length()) {
			char current = source.charAt(index);
			out.append(current);
			index++;
			if (current == '\\' && index < source.length()) {
				out.append(source.charAt(index));
				index++;
			} else if (current == quote) {
				break;
			}
		}
		return index;
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
