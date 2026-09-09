package com.finplay.api.domain.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FeedbackJournalPropertiesTest {

	private static final int SPEC_MAX_BUY_JOURNALS = 3;

	private static final int SPEC_MAX_JOURNAL_CHARS = 500;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(FeedbackJournalConfig.class);

	@Test
	@DisplayName("feedback.journal 설정을 하나도 주지 않아도 §C-7 기본값으로 바인딩된다")
	void bindsSpecDefaultsWhenNoFeedbackJournalPropertyIsGiven() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(FeedbackJournalProperties.class);

			FeedbackJournalProperties properties = context.getBean(FeedbackJournalProperties.class);
			assertThat(properties.maxBuyJournals()).isEqualTo(SPEC_MAX_BUY_JOURNALS);
			assertThat(properties.maxJournalChars()).isEqualTo(SPEC_MAX_JOURNAL_CHARS);
		});
	}

	@Test
	@DisplayName("feedback.journal.* 케밥케이스 키를 주면 두 값이 모두 덮어써진다")
	void bindsEveryPropertyFromKebabCaseKeys() {
		contextRunner
			.withPropertyValues(
				"feedback.journal.max-buy-journals=5", "feedback.journal.max-journal-chars=1200")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackJournalProperties properties = context.getBean(FeedbackJournalProperties.class);
				assertThat(properties.maxBuyJournals()).isEqualTo(5);
				assertThat(properties.maxJournalChars()).isEqualTo(1200);
			});
	}

	@Test
	@DisplayName("일부 값만 덮어써도 나머지는 §C-7 기본값을 유지한다")
	void keepsSpecDefaultsForPropertiesThatAreNotGiven() {
		contextRunner
			.withPropertyValues("feedback.journal.max-buy-journals=8")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackJournalProperties properties = context.getBean(FeedbackJournalProperties.class);
				assertThat(properties.maxBuyJournals()).isEqualTo(8);
				assertThat(properties.maxJournalChars()).isEqualTo(SPEC_MAX_JOURNAL_CHARS);
			});
	}

	@Test
	@DisplayName("숫자 항목에 숫자가 아닌 값이 오면 기동이 실패한다 — 0으로 조용히 넘어가지 않는다")
	void failsFastWhenNumericPropertyIsNotANumber() {
		contextRunner
			.withPropertyValues("feedback.journal.max-buy-journals=three")
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("max-buy-journals가 1 미만이면 기동이 실패한다 — 0도 음수도 막는다")
	void failsWhenMaxBuyJournalsIsBelowOne() {
		for (String invalid : List.of("0", "-1")) {
			contextRunner
				.withPropertyValues("feedback.journal.max-buy-journals=" + invalid)
				.run(context -> assertThat(context)
					.hasFailed()
					.getFailure()
					.rootCause()
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("max-buy-journals"));
		}
	}

	@Test
	@DisplayName("max-journal-chars가 1 미만이면 기동이 실패한다 — 0도 음수도 막는다")
	void failsWhenMaxJournalCharsIsBelowOne() {
		for (String invalid : List.of("0", "-100")) {
			contextRunner
				.withPropertyValues("feedback.journal.max-journal-chars=" + invalid)
				.run(context -> assertThat(context)
					.hasFailed()
					.getFailure()
					.rootCause()
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("max-journal-chars"));
		}
	}

	@Test
	@DisplayName("두 값이 1이면 정상 기동한다 — 하한은 1이다")
	void acceptsOneAsTheLowerBoundOfBothProperties() {
		contextRunner
			.withPropertyValues("feedback.journal.max-buy-journals=1", "feedback.journal.max-journal-chars=1")
			.run(context -> {
				assertThat(context).hasNotFailed();

				FeedbackJournalProperties properties = context.getBean(FeedbackJournalProperties.class);
				assertThat(properties.maxBuyJournals()).isEqualTo(1);
				assertThat(properties.maxJournalChars()).isEqualTo(1);
			});
	}
}
