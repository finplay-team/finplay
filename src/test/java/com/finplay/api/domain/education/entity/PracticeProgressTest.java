package com.finplay.api.domain.education.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeProgressTest {

	@Test
	void completeTransitionsStatusToCompletedAndSetsCompletedAt() {
		PracticeProgress progress = new PracticeProgress();
		ReflectionTestUtils.setField(progress, "tutorialKey", "INVESTMENT_PRACTICE_V1");
		ReflectionTestUtils.setField(progress, "status", PracticeProgressStatus.IN_PROGRESS);
		ReflectionTestUtils.setField(progress, "startedAt", LocalDateTime.of(2026, 8, 1, 9, 0));

		LocalDateTime completedAt = LocalDateTime.of(2026, 8, 10, 10, 0);
		progress.complete(completedAt);

		assertThat(progress.getStatus()).isEqualTo(PracticeProgressStatus.COMPLETED);
		assertThat(progress.getCompletedAt()).isEqualTo(completedAt);
	}

	@Test
	void completeThrowsWhenAlreadyCompleted() {
		PracticeProgress progress = new PracticeProgress();
		ReflectionTestUtils.setField(progress, "tutorialKey", "INVESTMENT_PRACTICE_V1");
		ReflectionTestUtils.setField(progress, "status", PracticeProgressStatus.IN_PROGRESS);
		ReflectionTestUtils.setField(progress, "startedAt", LocalDateTime.of(2026, 8, 1, 9, 0));

		LocalDateTime firstCompletedAt = LocalDateTime.of(2026, 8, 10, 10, 0);
		LocalDateTime secondCompletedAt = firstCompletedAt.plus(1, ChronoUnit.DAYS);
		progress.complete(firstCompletedAt);

		assertThatThrownBy(() -> progress.complete(secondCompletedAt))
			.isInstanceOf(IllegalStateException.class);

		assertThat(progress.getStatus()).isEqualTo(PracticeProgressStatus.COMPLETED);
		assertThat(progress.getCompletedAt()).isEqualTo(firstCompletedAt);
	}
}
