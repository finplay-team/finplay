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
// yml과 이 상수의 일치는 NewsCollectionPropertiesYamlTest가 Environment로 이미 대조한다. @SpringBootTest 쪽이
// 아니라 그 yml 전용 테스트인 이유는, 테스트 컨텍스트에서는 크론이 "-"로 덮여 있기 때문이다
// (build.gradle의 feedback-schedules-disabled-for-tests.yml). 이 파일은 애노테이션 문자열만 읽으므로 무관하다.
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
	// 수집 진입점은 @Scheduled 2종뿐이고, 그 외에 이 서비스를 부르는 코드는 재생 경로가 아니어야 한다는 것을
	// 소스에서 직접 본다.
	//
	// 주석은 검사 대상에서 뺀다(이슈 #180). 파일 전체를 문자열로 훑으면 "수집은 이 배치가 하지 않는다"처럼
	// 그 클래스를 언급하는 설명 주석만으로 실패해 문서화를 막는데, 지키려는 것은 코드가 이 서비스를 부르지
	// 않는다는 것이므로 주석은 애초에 대상이 아니다. 문자열 리터럴은 그대로 둔다 — 코드가 아니지만 남겨 두는
	// 쪽이 안전한 방향이고(거짓 양성은 눈에 띄고 거짓 음성은 안 띈다), 리터럴 안의 //로 뒤 코드가 잘려
	// 실제 호출을 놓치는 일도 없어야 하기 때문이다.
	//
	// 예외: CryptoPriceMoveWatcher.java(ADR-0017)는 의도적으로 허용한다. 이 테스트가 막으려는 것은
	// "수집이 재생 시점에 일어나는 것"이지 "@Scheduled 2종 외 호출 전부"가 아니다 — 지금까지는 그 둘이
	// 우연히 같았을 뿐이다. 코인 감시는 재생 세션 시간축이 없고 `LocalDateTime.now(clock)`으로 실제 현재
	// 시각을 쓰는 실시간 배치(`feedback.batch.crypto-watch-cron`, 매 분)라 재생 시점 수집 문제가 애초에
	// 생기지 않는다. 다음에 또 다른 파일이 이 스캔에 걸리면, "재생 경로가 아니고 실시간 시각을 쓰는가"를
	// 기준으로 예외 추가 여부를 판단한다 — 두 조건 중 하나라도 아니면 예외로 추가하지 말고 실제 위반으로
	// 다뤄야 한다.
	@Test
	@DisplayName("수집 서비스를 부르는 코드가 스케줄 진입점 외에 없다 — 재생 경로가 수집을 부르지 않는다")
	void noOtherSourceFileTriggersCollection() throws IOException {
		Path serviceFile = Path.of(
			"src/main/java/com/finplay/api/feedback/service/NewsCollectionService.java");
		Path cryptoWatcherFile = Path.of(
			"src/main/java/com/finplay/api/feedback/service/CryptoPriceMoveWatcher.java");
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

	// 위 스캔이 약해지지 않았는지를 직접 단정한다 — 주석만 빠지고 코드 형태는 전부 남아야 한다.
	@Test
	@DisplayName("주석 제거가 코드 참조는 남기고 설명 주석만 지운다")
	void stripCommentsRemovesOnlyCommentsAndKeepsEveryCodeReference() {
		// 주석 3종 — 이 형태들만으로는 스캔에 걸리지 않아야 한다.
		assertThat(stripComments("// 수집은 NewsCollectionService가 맡는다\nclass A {}"))
			.doesNotContain("NewsCollectionService");
		assertThat(stripComments("/* NewsCollectionService 참고 */\nclass A {}"))
			.doesNotContain("NewsCollectionService");
		assertThat(stripComments("/** {@link NewsCollectionService} */\nclass A {}"))
			.doesNotContain("NewsCollectionService");

		// 코드 형태 — 전부 그대로 남아야 한다.
		assertThat(stripComments("import com.finplay.api.feedback.service.NewsCollectionService;"))
			.contains("NewsCollectionService");
		assertThat(stripComments("private final NewsCollectionService collector;"))
			.contains("NewsCollectionService");
		assertThat(stripComments("void f(NewsCollectionService s) { s.collectNews(); }"))
			.contains("NewsCollectionService");

		// 리터럴 안의 //가 뒤 코드를 삼키면 실제 호출을 놓친다. 삼키지 않아야 한다.
		assertThat(stripComments("String u = \"http://x\"; NewsCollectionService s;"))
			.contains("NewsCollectionService");
		// 텍스트 블록도 같다 — src/main에 실제로 쓰이는 형태다(@Query JPQL).
		assertThat(stripComments("String q = \"\"\"\n  a // b\n  \"\"\"; NewsCollectionService s;"))
			.contains("NewsCollectionService");
		// 주석 안의 따옴표가 이후 코드를 문자열로 잘못 물면 안 된다.
		assertThat(stripComments("// 그 서비스는 \"수집\"만 한다\nNewsCollectionService s;"))
			.contains("NewsCollectionService");
	}

	/**
	 * 자바 소스에서 주석만 지우고 나머지는 그대로 둔다. 문자열·문자·텍스트 블록 리터럴 안의 {@code //}를
	 * 주석 시작으로 잘못 읽으면 그 줄의 실제 코드가 통째로 사라져 스캔이 조용히 약해지므로 리터럴 경계를
	 * 함께 추적한다.
	 */
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
