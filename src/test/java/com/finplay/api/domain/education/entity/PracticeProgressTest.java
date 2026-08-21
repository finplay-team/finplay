// PracticeProgress.complete(LocalDateTime)의 상태·완료시각 전이를 검증하는 단위 테스트다.
package com.finplay.api.domain.education.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeProgressTest {

	// PracticeProgress는 정적 팩토리 없이 native insert(PracticeProgressRepository.insertIfAbsent)로만
	// 생성되므로, protected 기본 생성자(같은 패키지 접근 가능)로 빈 인스턴스를 만들고 필드를 직접 세팅해
	// complete() 메서드 자체의 상태 전이만 단위로 검증한다.
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
		// PR #304 리뷰 권장 반영: 완료는 불변이므로 재호출은 엔티티 스스로 막는다 — 서비스 계층의
		// COMPLETED 사전 검사가 없더라도 completedAt이 조용히 덮어써지지 않는다.
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
