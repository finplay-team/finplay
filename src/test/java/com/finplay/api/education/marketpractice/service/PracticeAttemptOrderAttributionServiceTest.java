// 샘플 주문의 attempt 귀속과 최초 매수 위험 스냅샷 가격 계산·불변성을 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.auth.domain.User;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.service.PracticeOrderAttributionDto;
import com.finplay.api.order.service.PracticeOrderFillAttributionDto;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class PracticeAttemptOrderAttributionServiceTest {

	private static final Long USER_ID = 7L;
	private static final Long ATTEMPT_ID = 11L;
	private static final Long INSTRUMENT_ID = 21L;
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 12, 0);

	private final PracticeAttemptRepository practiceAttemptRepository = mock(PracticeAttemptRepository.class);
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository = mock(
		PracticeRiskSnapshotRepository.class);
	private final PracticeAttemptOrderAttributionService service = new PracticeAttemptOrderAttributionService(
		practiceAttemptRepository, practiceRiskSnapshotRepository);

	@Test
	void lockForOrderReturnsCurrentAttemptForTutorialSample() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = inProgressAttempt(instrument);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));

		Optional<PracticeOrderAttributionDto> result = service.lockForOrder(USER_ID, instrument);

		assertThat(result).contains(new PracticeOrderAttributionDto(ATTEMPT_ID, 1L));
	}

	@Test
	void lockForOrderLeavesOrdinaryInstrumentUnattributed() {
		Instrument ordinaryInstrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", BigDecimal.ONE, 5_000L, true, NOW);

		Optional<PracticeOrderAttributionDto> result = service.lockForOrder(USER_ID, ordinaryInstrument);

		assertThat(result).isEmpty();
		verifyNoInteractions(practiceAttemptRepository, practiceRiskSnapshotRepository);
	}

	@Test
	void lockForFillReturnsTrueOnlyForCurrentRun() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = inProgressAttempt(instrument);
		when(practiceAttemptRepository.findByIdForUpdate(ATTEMPT_ID)).thenReturn(Optional.of(attempt));

		boolean current = service.lockForFill(
			new PracticeOrderFillAttributionDto(ATTEMPT_ID, 1L, USER_ID, INSTRUMENT_ID));
		boolean stale = service.lockForFill(
			new PracticeOrderFillAttributionDto(ATTEMPT_ID, 2L, USER_ID, INSTRUMENT_ID));

		assertThat(current).isTrue();
		assertThat(stale).isFalse();
	}

	@Test
	void createFirstBuyRiskSnapshotRoundsPricesToScaleEightAndWritesOnlyOnce() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = inProgressAttempt(instrument);
		Order order = attributedBuyOrder(instrument);
		Trade trade = buyTrade(order, instrument, new BigDecimal("100.123456785"));
		PracticeRiskSnapshot existingSnapshot = mock(PracticeRiskSnapshot.class);
		when(practiceAttemptRepository.findByIdForUpdate(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
		when(practiceRiskSnapshotRepository.findByAttemptIdAndRunNumber(ATTEMPT_ID, 1L))
			.thenReturn(Optional.empty(), Optional.of(existingSnapshot));

		service.createFirstBuyRiskSnapshot(order, trade, NOW);
		service.createFirstBuyRiskSnapshot(order, trade, NOW.plusSeconds(1));

		ArgumentCaptor<PracticeRiskSnapshot> snapshotCaptor = ArgumentCaptor.forClass(PracticeRiskSnapshot.class);
		verify(practiceRiskSnapshotRepository).save(snapshotCaptor.capture());
		PracticeRiskSnapshot snapshot = snapshotCaptor.getValue();
		assertThat(snapshot.getEntryPrice()).isEqualByComparingTo("100.12345679");
		assertThat(snapshot.getStopLossPrice()).isEqualByComparingTo("97.11975309");
		assertThat(snapshot.getTakeProfitPrice()).isEqualByComparingTo("105.12962963");
		assertThat(snapshot.getRunNumber()).isEqualTo(1L);
		assertThat(snapshot.getBuyTrade()).isSameAs(trade);
		assertThat(snapshot.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void createFirstBuyRiskSnapshotIgnoresOrdinaryOrder() {
		Instrument instrument = tutorialInstrument();
		User user = testUser();
		Account account = account(user);
		Order ordinaryOrder = Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET, BigDecimal.ONE,
			"ordinary", "b".repeat(64), NOW);
		Trade trade = buyTrade(ordinaryOrder, instrument, BigDecimal.valueOf(100));

		service.createFirstBuyRiskSnapshot(ordinaryOrder, trade, NOW);

		verify(practiceAttemptRepository, never()).findByIdForUpdate(org.mockito.ArgumentMatchers.any());
		verifyNoInteractions(practiceRiskSnapshotRepository);
	}

	private static PracticeAttempt inProgressAttempt(Instrument instrument) {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)1,
			NOW.minusMinutes(10));
		return attempt;
	}

	private static Instrument tutorialInstrument() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "TUTORIAL-BTC", "튜토리얼 비트코인", BigDecimal.ONE, 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", INSTRUMENT_ID);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		return instrument;
	}

	private static Order attributedBuyOrder(Instrument instrument) {
		User user = testUser();
		return Order.createForPracticeAttempt(
			user, account(user), instrument, OrderSide.BUY, OrderType.MARKET, BigDecimal.ONE,
			ATTEMPT_ID, 1L, "attempt-order", "a".repeat(64), NOW);
	}

	private static Trade buyTrade(Order order, Instrument instrument, BigDecimal price) {
		Trade trade = Trade.of(
			order,
			order.getAccount(),
			instrument,
			null,
			OrderSide.BUY,
			price,
			BigDecimal.ONE,
			100L,
			0L,
			null,
			NOW,
			NOW);
		ReflectionTestUtils.setField(trade, "id", 31L);
		return trade;
	}

	private static User testUser() {
		return User.create("attempt-order@finplay.com", "hash", "attempt-order", NOW);
	}

	private static Account account(User user) {
		return Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, NOW);
	}
}
