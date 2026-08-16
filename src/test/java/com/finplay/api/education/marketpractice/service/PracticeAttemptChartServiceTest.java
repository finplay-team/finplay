// 튜토리얼 차트 GET의 무부수효과와 명시적 tick의 지정가 정산·완료 잠금을 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeAttemptStatus;
import com.finplay.api.education.marketpractice.dto.response.PracticeTutorialChartResponse;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.TutorialPriceGenerator;
import com.finplay.api.order.service.PracticeOrderSettlementService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeAttemptChartServiceTest {

	private static final Long USER_ID = 7L;
	private static final Instant NOW_INSTANT = Instant.parse("2026-08-14T03:00:05Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);
	private final PracticeAttemptRepository attemptRepository = mock(PracticeAttemptRepository.class);
	private final PracticeOrderSettlementService settlementService = mock(PracticeOrderSettlementService.class);
	private final PracticeAttemptCanonicalPriceService canonicalPriceService = new PracticeAttemptCanonicalPriceService(
		attemptRepository, new TutorialPriceGenerator());
	private final PracticeAttemptChartService service = new PracticeAttemptChartService(
		attemptRepository, canonicalPriceService, settlementService, Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));

	@Test
	void getChartReturnsExactCanonicalCurrentCloseWithoutSettlingOrders() {
		PracticeAttempt attempt = selectedAttempt();
		when(attemptRepository.findByUserIdAndMarket(USER_ID, Market.CRYPTO)).thenReturn(Optional.of(attempt));

		PracticeTutorialChartResponse response = service.getChart(USER_ID, Market.CRYPTO);

		assertThat(response.candles()).hasSize(30);
		assertThat(response.candles().get(29).close())
			.isEqualByComparingTo(canonicalPriceService.canonicalPrice(attempt, NOW));
		assertThat(response.virtualDateTime()).isEqualTo(attempt.getTutorialDate().atTime(12, 1));
		assertThat(response.secondsPerVirtualMinute()).isEqualTo(3);
		verifyNoInteractions(settlementService);
	}

	@Test
	void tickLocksAttemptAndSettlesCurrentRunBeforeReturningCanonicalChart() {
		PracticeAttempt attempt = selectedAttempt();
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));

		PracticeTutorialChartResponse response = service.tick(USER_ID, Market.CRYPTO);

		verify(settlementService).settleCurrentRun(attempt.getId(), attempt.getRunNumber(), NOW);
		assertThat(response.candles().get(29).close())
			.isEqualByComparingTo(canonicalPriceService.canonicalPrice(attempt, NOW));
	}

	@Test
	void getChartRejectsMissingAttemptAsLocked() {
		when(attemptRepository.findByUserIdAndMarket(USER_ID, Market.STOCK)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getChart(USER_ID, Market.STOCK))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_STEP_LOCKED));
	}

	@Test
	void tickRejectsCompletedAttemptWithoutSettlement() {
		PracticeAttempt attempt = selectedAttempt();
		ReflectionTestUtils.setField(attempt, "status", PracticeAttemptStatus.COMPLETED);
		ReflectionTestUtils.setField(attempt, "completedAt", NOW.minusDays(1));
		when(attemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));

		assertThatThrownBy(() -> service.tick(USER_ID, Market.CRYPTO))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_ALREADY_COMPLETED));

		verify(settlementService, never()).settleCurrentRun(
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.any());
	}

	private static PracticeAttempt selectedAttempt() {
		LocalDateTime anchor = NOW.minusSeconds(5);
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", 11L);
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 21L);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt.selectInstrument(instrument, anchor, NOW.toLocalDate(), 123_456_789L, (short)1, anchor);
		return attempt;
	}
}
