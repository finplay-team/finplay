// PracticeAttempt의 재시작 전이(완료 attempt 포함)를 검증한다.
package com.finplay.api.education.marketpractice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeAttemptTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 17, 10, 0, 0);

	@Test
	void restartFromCompletedIncrementsRunAndResetsToSelectingInstrument() {
		PracticeAttempt attempt = selectedAttempt();
		ReflectionTestUtils.setField(attempt, "status", PracticeAttemptStatus.COMPLETED);
		ReflectionTestUtils.setField(attempt, "completedAt", NOW.minusDays(1));

		attempt.restart(NOW);

		assertThat(attempt.getStatus()).isEqualTo(PracticeAttemptStatus.SELECTING_INSTRUMENT);
		assertThat(attempt.getRunNumber()).isEqualTo(2L);
		assertThat(attempt.getCompletedAt()).isNull();
		assertThat(attempt.getInstrument()).isNull();
		assertThat(attempt.getAnchorAt()).isNull();
		assertThat(attempt.getTutorialDate()).isNull();
		assertThat(attempt.getPriceSeed()).isNull();
		assertThat(attempt.getGeneratorVersion()).isNull();
		assertThat(attempt.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	void restartFromInProgressIncrementsRunAndResetsSelectionState() {
		PracticeAttempt attempt = selectedAttempt();

		attempt.restart(NOW);

		assertThat(attempt.getStatus()).isEqualTo(PracticeAttemptStatus.SELECTING_INSTRUMENT);
		assertThat(attempt.getRunNumber()).isEqualTo(2L);
		assertThat(attempt.getInstrument()).isNull();
	}

	private static PracticeAttempt selectedAttempt() {
		PracticeAttempt attempt = PracticeAttempt.create(1L, Market.CRYPTO, NOW.minusHours(1));
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 21L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)1,
			NOW.minusMinutes(10));
		return attempt;
	}
}
