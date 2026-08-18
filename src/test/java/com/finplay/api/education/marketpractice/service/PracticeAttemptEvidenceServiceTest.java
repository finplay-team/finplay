// 현재 실행 세대 evidence 해석이 최신 진입과 첫 진입 스냅샷을 각각 어디에 쓰는지 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.PracticeRunTradeSummaryDto;
import com.finplay.api.order.service.TradeService;
import com.finplay.api.portfolio.service.HoldingService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PracticeAttemptEvidenceServiceTest {

	private static final Long USER_ID = 7L;
	private static final Long ATTEMPT_ID = 70L;
	private static final long RUN_NUMBER = 3L;
	private static final Long HOLDING_ID = 40L;
	private static final Long INSTRUMENT_ID = 55L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0);

	@Mock
	private PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;

	@Mock
	private TradeService tradeService;

	@Mock
	private HoldingService holdingService;

	@InjectMocks
	private PracticeAttemptEvidenceService service;

	private PracticeAttempt attempt;
	private Instrument instrument;

	@BeforeEach
	void setUp() {
		instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-CRYPTO", "튜토리얼 샘플 코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		attempt = mock(PracticeAttempt.class);
		when(attempt.getInstrument()).thenReturn(instrument);
		when(attempt.getId()).thenReturn(ATTEMPT_ID);
		when(attempt.getRunNumber()).thenReturn(RUN_NUMBER);
		when(attempt.getMarket()).thenReturn(Market.CRYPTO);
	}

	@Test
	@DisplayName("최신 진입 스냅샷은 riskSnapshot에, 첫 진입 스냅샷은 observationBaseline에 담는다")
	void resolvesLatestEntryAsRiskSnapshotAndFirstEntryAsObservationBaseline() {
		PracticeRiskSnapshot latest = snapshotWithBuyTrade();
		PracticeRiskSnapshot first = mock(PracticeRiskSnapshot.class);
		when(practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, RUN_NUMBER))
			.thenReturn(Optional.of(latest));
		when(practiceRiskSnapshotRepository.findByAttemptIdAndRunNumberAndEntrySequence(
			ATTEMPT_ID, RUN_NUMBER, PracticeRiskSnapshot.FIRST_ENTRY_SEQUENCE))
			.thenReturn(Optional.of(first));
		when(holdingService.findHoldingId(USER_ID, Market.CRYPTO, instrument.getId()))
			.thenReturn(Optional.of(HOLDING_ID));
		when(tradeService.summarizePracticeRun(ATTEMPT_ID, RUN_NUMBER)).thenReturn(emptySummary());

		ResolvedPracticeAttemptEvidenceDto resolved = service.requireCurrentRun(attempt, USER_ID, null);

		assertThat(resolved.riskSnapshot()).isSameAs(latest);
		assertThat(resolved.observationBaseline()).isSameAs(first);
	}

	@Test
	@DisplayName("관찰 필터 기준선은 첫 진입으로 조회한다 — 최신 진입 조회를 재사용하지 않는다")
	void queriesFirstEntryExplicitlyForObservationBaseline() {
		PracticeRiskSnapshot latest = snapshotWithBuyTrade();
		when(practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(ATTEMPT_ID, RUN_NUMBER))
			.thenReturn(Optional.of(latest));
		when(practiceRiskSnapshotRepository.findByAttemptIdAndRunNumberAndEntrySequence(
			ATTEMPT_ID, RUN_NUMBER, PracticeRiskSnapshot.FIRST_ENTRY_SEQUENCE))
			.thenReturn(Optional.of(mock(PracticeRiskSnapshot.class)));
		when(holdingService.findHoldingId(USER_ID, Market.CRYPTO, instrument.getId()))
			.thenReturn(Optional.of(HOLDING_ID));
		when(tradeService.summarizePracticeRun(ATTEMPT_ID, RUN_NUMBER)).thenReturn(emptySummary());

		service.requireCurrentRun(attempt, USER_ID, null);

		verify(practiceRiskSnapshotRepository).findByAttemptIdAndRunNumberAndEntrySequence(
			ATTEMPT_ID, RUN_NUMBER, PracticeRiskSnapshot.FIRST_ENTRY_SEQUENCE);
	}

	private PracticeRiskSnapshot snapshotWithBuyTrade() {
		User user = mock(User.class);
		when(user.getId()).thenReturn(USER_ID);
		Account account = mock(Account.class);
		when(account.getUser()).thenReturn(user);
		Order order = mock(Order.class);
		when(order.getPracticeAttemptId()).thenReturn(ATTEMPT_ID);
		when(order.getPracticeAttemptRunNumber()).thenReturn(RUN_NUMBER);
		Trade buyTrade = mock(Trade.class);
		when(buyTrade.getAccount()).thenReturn(account);
		when(buyTrade.getInstrument()).thenReturn(instrument);
		when(buyTrade.getOrder()).thenReturn(order);
		PracticeRiskSnapshot snapshot = mock(PracticeRiskSnapshot.class);
		when(snapshot.getBuyTrade()).thenReturn(buyTrade);
		return snapshot;
	}

	private PracticeRunTradeSummaryDto emptySummary() {
		return new PracticeRunTradeSummaryDto(
			BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, null, null, null, null, null);
	}
}
