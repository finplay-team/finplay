// feedback.journal.* 프로퍼티가 설정 없이도 spec 012 §C-7 기본값으로 바인딩되는지 검증한다.
package com.finplay.api.feedback.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// FeedbackJournalPropertiesYamlTest와 같은 짝의 앞쪽이다 — 여기서는 record의 @DefaultValue만 본다.
// application.yml 쪽 키 경로는 FeedbackJournalPropertiesYamlTest가 맡는다
// (선례: FeedbackDetectionPropertiesTest·FeedbackDetectionPropertiesYamlTest).
//
// 기대값의 정본은 ai/specs/012-ai-feedback/spec.md §C-7의 feedback.journal 블록이다(4차 신설, §FEED-013
// 결정 4). 구현 파일이 아니라 spec에서 값을 가져와야 record와 yml이 함께 틀어지는 드리프트가 잡힌다.
class FeedbackJournalPropertiesTest {

	// §C-7 feedback.journal 블록
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

	// 덮어쓰는 값은 전부 §C-7 기본값과 달라야 한다 — 같으면 바인딩이 아예 안 돼도 기본값에 가려 통과한다.
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

	// --- 하한 검증 (FeedbackDetectionProperties·FeedbackNewsProperties와 같은 처방) ---

	// 두 값 모두 잘못된 설정이 예외도 로그도 없이 **정반대 결과**로만 나타나므로 기동에서 막는다.
	// 0 이하이면 선별 루프의 상한 비교가 걸리지 않아 배분된 매수 일기가 전부 프롬프트에 실린다 —
	// 기능을 끄려고 0을 넣은 운영자가 상한이 통째로 무효가 된 결과를 받는다.
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

	// 음수면 절단이 substring(0, -N)으로 StringIndexOutOfBoundsException을 던져 "매도 회고 조회는 항상
	// 200·READY"(§C-4)가 500으로 깨진다. 0은 본문이 통째로 빈 문자열이 되어 일기를 실은 의미가 사라진다.
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

	// 하한이 1인지 2인지를 가른다 — 검증을 `< 2`로 잘못 쓰면 정상 설정이 기동을 막는다.
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
