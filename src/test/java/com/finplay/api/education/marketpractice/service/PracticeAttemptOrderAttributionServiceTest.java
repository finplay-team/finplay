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
import com.finplay.api.education.marketpractice.domain.ExitPreset;
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
import com.finplay.api.order.service.ExitPlanCreateCommandDto;
import com.finplay.api.order.service.ExitPlanCreationService;
import com.finplay.api.order.service.PracticeOrderAttributionDto;
import com.finplay.api.order.service.TradeService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.HoldingService;
import com.finplay.api.order.service.PracticeOrderFillAttributionDto;
import com.finplay.api.order.service.PracticeOrderFillContextDto;
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
	private final PracticeAttemptCanonicalPriceService canonicalPriceService = mock(
		PracticeAttemptCanonicalPriceService.class);
	private final TradeService tradeService = mock(TradeService.class);
	private final HoldingService holdingService = mock(HoldingService.class);
	private final ExitPlanCreationService exitPlanCreationService = mock(ExitPlanCreationService.class);
	private final PracticeAttemptOrderAttributionService service = new PracticeAttemptOrderAttributionService(
		practiceAttemptRepository, practiceRiskSnapshotRepository, canonicalPriceService,
		new ReferencePriceCalculator(), tradeService, holdingService, exitPlanCreationService,
		java.time.Clock.fixed(NOW.toInstant(java.time.ZoneOffset.UTC), java.time.ZoneOffset.UTC));

	@Test
	void lockForOrderReturnsCurrentAttemptForTutorialSample() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = inProgressAttempt(instrument);
		when(practiceAttemptRepository.findByUserIdAndMarketForUpdate(USER_ID, Market.CRYPTO))
			.thenReturn(Optional.of(attempt));
		when(canonicalPriceService.canonicalPrice(attempt, NOW)).thenReturn(new BigDecimal("100.00000000"));

		Optional<PracticeOrderAttributionDto> result = service.lockForOrder(USER_ID, instrument);

		assertThat(result).contains(new PracticeOrderAttributionDto(ATTEMPT_ID, 1L, new BigDecimal("100.00000000")));
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

		when(canonicalPriceService.canonicalPrice(attempt, NOW)).thenReturn(new BigDecimal("100.00000000"));
		PracticeOrderFillContextDto current = service.lockForFill(
			new PracticeOrderFillAttributionDto(ATTEMPT_ID, 1L, USER_ID, INSTRUMENT_ID), NOW);
		PracticeOrderFillContextDto stale = service.lockForFill(
			new PracticeOrderFillAttributionDto(ATTEMPT_ID, 2L, USER_ID, INSTRUMENT_ID), NOW);

		assertThat(current.currentRun()).isTrue();
		assertThat(current.canonicalPrice()).isEqualByComparingTo("100.00000000");
		assertThat(stale.currentRun()).isFalse();
		assertThat(stale.canonicalPrice()).isNull();
	}

	@Test
	void createRiskSnapshotOnBuyFillRoundsPricesToScaleEightAndWritesOncePerEntry() {
		Instrument instrument = tutorialInstrument();
		PracticeAttempt attempt = inProgressAttempt(instrument);
		Order order = attributedBuyOrder(instrument);
		Trade trade = buyTrade(order, instrument, new BigDecimal("100.123456785"));
		when(practiceAttemptRepository.findByIdForUpdate(ATTEMPT_ID)).thenReturn(Optional.of(attempt));
		when(practiceRiskSnapshotRepository.countByAttemptIdAndRunNumber(ATTEMPT_ID, 1L)).thenReturn(0L, 1L);
		// 첫 호출은 직전 보유 0(= 이번 체결분만), 두 번째는 이미 들고 있는 상태에서의 추가 매수다.
		when(tradeService.netFilledQuantity(ATTEMPT_ID, 1L))
			.thenReturn(trade.getQuantity(), trade.getQuantity().add(trade.getQuantity()));
		when(holdingService.findHoldingId(USER_ID, Market.CRYPTO, INSTRUMENT_ID)).thenReturn(Optional.of(77L));
		when(holdingService.findHoldingForOwner(USER_ID, 77L)).thenReturn(Optional.of(mock(Holding.class)));
		when(canonicalPriceService.canonicalPrice(attempt, NOW)).thenReturn(new BigDecimal("100.00000000"));

		service.createRiskSnapshotOnBuyFill(order, trade, NOW);
		service.createRiskSnapshotOnBuyFill(order, trade, NOW.plusSeconds(1));

		ArgumentCaptor<PracticeRiskSnapshot> snapshotCaptor = ArgumentCaptor.forClass(PracticeRiskSnapshot.class);
		verify(practiceRiskSnapshotRepository).save(snapshotCaptor.capture());
		PracticeRiskSnapshot snapshot = snapshotCaptor.getValue();
		assertThat(snapshot.getEntryPrice()).isEqualByComparingTo("100.12345679");
		assertThat(snapshot.getStopLossPrice()).isEqualByComparingTo("97.11975309");
		assertThat(snapshot.getTakeProfitPrice()).isEqualByComparingTo("105.12962963");
		assertThat(snapshot.getRunNumber()).isEqualTo(1L);
		assertThat(snapshot.getBuyTrade()).isSameAs(trade);
		assertThat(snapshot.getCreatedAt()).isEqualTo(NOW);
		// 미선택 사용자도 기본 프리셋이 확정된다(EXITPRESET-002) — 값이 여기서 정해져야 뒤에 프리셋을
		// 바꿔도 이미 만들어진 진입의 기준선이 흔들리지 않는다.
		assertThat(snapshot.getExitPreset()).isEqualTo(ExitPreset.BALANCED);
		assertThat(snapshot.getEntrySequence()).isEqualTo(1);

		// 042 5번 — CRYPTO는 같은 트랜잭션에서 예약까지 만든다. 진입당 1회 가드가 두 번째 호출을 막으므로
		// 예약도 한 번만 생긴다(엔진의 validateNoPendingPlan 409로 매수가 통째로 실패하는 것을 예방한다).
		ArgumentCaptor<ExitPlanCreateCommandDto> commandCaptor = ArgumentCaptor
			.forClass(ExitPlanCreateCommandDto.class);
		verify(exitPlanCreationService).create(commandCaptor.capture());
		ExitPlanCreateCommandDto command = commandCaptor.getValue();
		assertThat(command.isPracticePath()).isTrue();
		assertThat(command.practiceOrigin().attemptId()).isEqualTo(ATTEMPT_ID);
		assertThat(command.practiceOrigin().runNumber()).isEqualTo(1L);
		// 대본 canonical price가 baseline이다 — 엔진 기본 경로의 사인파 항시 시세가 아니다.
		assertThat(command.practiceOrigin().baselinePrice()).isEqualByComparingTo("100.00000000");
		// 예약에 넘기는 체결가도 snapshot과 같은 scale 8 값이어야 화면 기준선과 실제 체결선이 갈리지 않는다.
		assertThat(command.priceInput().entryPrice()).isEqualByComparingTo("100.12345679");
		assertThat(command.priceInput().stopLossRate()).isEqualByComparingTo("3");
		assertThat(command.priceInput().takeProfitRate()).isEqualByComparingTo("5");
		assertThat(command.requestHash()).hasSize(64);
	}

	@Test
	void createRiskSnapshotOnBuyFillIgnoresOrdinaryOrder() {
		Instrument instrument = tutorialInstrument();
		User user = testUser();
		Account account = account(user);
		Order ordinaryOrder = Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET, BigDecimal.ONE,
			"ordinary", "b".repeat(64), NOW);
		Trade trade = buyTrade(ordinaryOrder, instrument, BigDecimal.valueOf(100));

		service.createRiskSnapshotOnBuyFill(ordinaryOrder, trade, NOW);

		verify(practiceAttemptRepository, never()).findByIdForUpdate(org.mockito.ArgumentMatchers.any());
		verifyNoInteractions(practiceRiskSnapshotRepository);
	}

	private static PracticeAttempt inProgressAttempt(Instrument instrument) {
		PracticeAttempt attempt = PracticeAttempt.create(USER_ID, Market.CRYPTO, NOW.minusHours(1));
		ReflectionTestUtils.setField(attempt, "id", ATTEMPT_ID);
		attempt.selectInstrument(instrument, NOW.minusMinutes(10), NOW.toLocalDate(), 123L, (short)1, null,
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
