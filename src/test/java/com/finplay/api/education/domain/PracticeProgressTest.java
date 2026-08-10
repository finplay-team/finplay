// PracticeProgress.complete(LocalDateTime)의 상태·완료시각 전이를 검증하는 단위 테스트다.
package com.finplay.api.education.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
	void completeOverwritesPreviousCompletedAtWhenCalledAgain() {
		// complete()는 재호출을 막지 않는다(가드 없음) — 서비스 계층이 COMPLETED 상태를 먼저 걸러낸다는 전제를
		// 엔티티 자체 동작으로도 확인해 둔다(계약 회귀 방지).
		PracticeProgress progress = new PracticeProgress();
		ReflectionTestUtils.setField(progress, "tutorialKey", "INVESTMENT_PRACTICE_V1");
		ReflectionTestUtils.setField(progress, "status", PracticeProgressStatus.IN_PROGRESS);
		ReflectionTestUtils.setField(progress, "startedAt", LocalDateTime.of(2026, 8, 1, 9, 0));

		LocalDateTime firstCompletedAt = LocalDateTime.of(2026, 8, 10, 10, 0);
		LocalDateTime secondCompletedAt = firstCompletedAt.plus(1, ChronoUnit.DAYS);
		progress.complete(firstCompletedAt);
		progress.complete(secondCompletedAt);

		assertThat(progress.getStatus()).isEqualTo(PracticeProgressStatus.COMPLETED);
		assertThat(progress.getCompletedAt()).isEqualTo(secondCompletedAt);
	}
}
