// LimitOrderFillService.fillIfPending의 BUY/SELL 체결·중복 이벤트 no-op을 검증하는 단위 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.account.domain.TutorialAccount;
import com.finplay.api.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.account.service.TutorialAccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.service.PortfolioBuyService;
import com.finplay.api.portfolio.service.PortfolioSellService;
import com.finplay.api.portfolio.service.SellAllocationDto;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class LimitOrderFillServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-05T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);

	private final OrderRepository orderRepository = mock(OrderRepository.class);
	private final TradeRepository tradeRepository = mock(TradeRepository.class);
	private final AccountService accountService = mock(AccountService.class);
	private final TutorialAccountService tutorialAccountService = mock(TutorialAccountService.class);
	private final PortfolioBuyService portfolioBuyService = mock(PortfolioBuyService.class);
	private final PortfolioSellService portfolioSellService = mock(PortfolioSellService.class);
	private final PracticeOrderAttributionPort practiceOrderAttributionPort = mock(
		PracticeOrderAttributionPort.class);
	private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
	private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

	private final LimitOrderFillService service = new LimitOrderFillService(
		orderRepository, tradeRepository, accountService, tutorialAccountService, portfolioBuyService,
		portfolioSellService, practiceOrderAttributionPort, clock, eventPublisher);

	@Test
	void fillIfPendingFillsBuyOrderConfirmsReservedCashAndAppliesBuyTrade() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		// quantity=0.1 * limitPrice=1,000,000 => amount=100,000, fee=floor(100,000*0.0005)=50
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillIfPending(order.getId());

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L - 100_050L);

		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository).save(tradeCaptor.capture());
		Trade savedTrade = tradeCaptor.getValue();
		assertThat(savedTrade.getPrice()).isEqualByComparingTo("1000000");
		assertThat(savedTrade.getAmount()).isEqualTo(100_000L);
		assertThat(savedTrade.getFee()).isEqualTo(50L);
		assertThat(savedTrade.getRealizedPnl()).isNull();

		verify(portfolioBuyService).applyBuyTrade(
			account, instrument, savedTrade, new BigDecimal("0.1"), new BigDecimal("1000000"), 50L, NOW);
		verifyNoInteractions(portfolioSellService);
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void fillIfPendingLocksAttemptBeforeAttributedOrder() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = attributedLimitPendingBuyOrder(account, instrument);
		PracticeOrderFillAttributionDto attribution = new PracticeOrderFillAttributionDto(
			20L, 1L, 30L, instrument.getId());
		when(orderRepository.findPracticeFillAttribution(order.getId())).thenReturn(Optional.of(attribution));
		when(practiceOrderAttributionPort.lockForFill(attribution, NOW))
			.thenReturn(new PracticeOrderFillContextDto(true, new BigDecimal("900000")));
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillIfPending(order.getId());

		InOrder lockOrder = org.mockito.Mockito.inOrder(orderRepository, practiceOrderAttributionPort);
		lockOrder.verify(orderRepository).findPracticeFillAttribution(order.getId());
		lockOrder.verify(practiceOrderAttributionPort).lockForFill(attribution, NOW);
		lockOrder.verify(orderRepository).findByIdForUpdate(order.getId());
		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository).save(tradeCaptor.capture());
		assertThat(tradeCaptor.getValue().getPrice()).isEqualByComparingTo("900000");
		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L - 90_045L);
	}

	@Test
	void fillIfPendingSkipsAttemptLockForOrdinaryOrder() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findPracticeFillAttribution(order.getId())).thenReturn(Optional.empty());
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillIfPending(order.getId());

		verify(practiceOrderAttributionPort, never()).lockForFill(any(), any());
	}

	@Test
	void fillIfPendingRejectsAttributedOrderFromStaleRunAfterOrderLock() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = attributedLimitPendingBuyOrder(account, instrument);
		PracticeOrderFillAttributionDto attribution = new PracticeOrderFillAttributionDto(
			20L, 1L, 30L, instrument.getId());
		when(orderRepository.findPracticeFillAttribution(order.getId())).thenReturn(Optional.of(attribution));
		when(practiceOrderAttributionPort.lockForFill(attribution, NOW))
			.thenReturn(new PracticeOrderFillContextDto(false, null));
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

		assertThatThrownBy(() -> service.fillIfPending(order.getId()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_STEP_LOCKED));

		verifyNoInteractions(accountService, tradeRepository, portfolioBuyService, portfolioSellService);
	}

	@Test
	void fillIfPendingBuyConfirmsReservedCashInTutorialAccountOnlyWhenInstrumentIsTutorialSample() {
		// 047 TUTORIAL-CASH-ISOL-002: 샌드박스 종목 지정가 매수 체결은 실제 Account가 아니라 같은 사용자·
		// 시장의 튜토리얼 계좌에서 예약을 확정(confirmReservedCash)한다 — 실제 Account.cashBalance·
		// reservedCash는 전혀 변하지 않는다. spec 033 SANDBOX-EXCL-006 call site #4(폐지는 tasks.md 항목6
		// 몫)는 현재도 유지되어 sandboxCashAdjustment에 음수로 누적된다.
		Instrument instrument = cryptoInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account();
		TutorialAccount tutorialAccount = tutorialAccount();
		// quantity=0.1 * limitPrice=1,000,000 => amount=100,000, fee=floor(100,000*0.0005)=50
		tutorialAccount.reserveCash(100_050L); // 생성 시점(LimitOrderCreationService)에 이미 예약된 상태를 재현
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);
		when(tutorialAccountService.getOrCreateForUpdate(any(), eq(Market.CRYPTO), eq(NOW)))
			.thenReturn(tutorialAccount);

		service.fillIfPending(order.getId());

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(tutorialAccount.getReservedCash()).isZero();
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L - 100_050L);
		assertThat(account.getReservedCash()).isZero(); // 실제 계좌는 예약된 적이 없다
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L); // 실제 계좌 현금은 전혀 변하지 않는다
		assertThat(account.getSandboxCashAdjustment()).isEqualTo(-100_050L);
	}

	@Test
	void fillIfPendingBuyForAttributedTutorialSampleReleasesAndDeductsInTutorialAccountOnly() {
		// 047 TUTORIAL-CASH-ISOL-002: canonicalPracticeFill(attempt 귀속 체결) 분기도 튜토리얼 계좌만
		// 움직여야 한다 — fillIfPendingLocksAttemptBeforeAttributedOrder(실제 계좌 경로)와 동일한 수치를
		// 튜토리얼 계좌 기준으로 검증한다.
		Instrument instrument = cryptoInstrument();
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		Account account = account();
		TutorialAccount tutorialAccount = tutorialAccount();
		tutorialAccount.reserveCash(100_050L);
		Order order = attributedLimitPendingBuyOrder(account, instrument);
		PracticeOrderFillAttributionDto attribution = new PracticeOrderFillAttributionDto(
			20L, 1L, 30L, instrument.getId());
		when(orderRepository.findPracticeFillAttribution(order.getId())).thenReturn(Optional.of(attribution));
		when(practiceOrderAttributionPort.lockForFill(attribution, NOW))
			.thenReturn(new PracticeOrderFillContextDto(true, new BigDecimal("900000")));
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);
		when(tutorialAccountService.getOrCreateForUpdate(any(), eq(Market.CRYPTO), eq(NOW)))
			.thenReturn(tutorialAccount);

		service.fillIfPending(order.getId());

		// executionPrice=900,000, amount=0.1*900,000=90,000, fee=floor(90,000*0.0005)=45
		assertThat(tutorialAccount.getReservedCash()).isZero();
		assertThat(tutorialAccount.getCashBalance()).isEqualTo(10_000_000L - 90_045L);
		assertThat(account.getReservedCash()).isZero();
		assertThat(account.getCashBalance()).isEqualTo(10_000_000L);
	}

	@Test
	void fillIfPendingBuyDoesNotAccumulateSandboxCashAdjustmentWhenInstrumentIsReal() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillIfPending(order.getId());

		assertThat(account.getSandboxCashAdjustment()).isEqualTo(0L);
		verifyNoInteractions(tutorialAccountService); // 047 회귀 방지: 실제 종목 체결은 튜토리얼 계좌를 전혀 조회하지 않는다
	}

	@Test
	void fillIfPendingFillsBuyOrderWhenNoExistingHoldingForNewInstrument() {
		// 신규 종목 첫 매수: LimitOrderFillService는 holding을 조회·잠그지 않고 전량 PortfolioBuyService에
		// 위임한다(holding 신규 생성은 PortfolioBuyService.applyBuyTrade 내부의 orElseGet이 처리, plan.md).
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillIfPending(order.getId());

		verify(portfolioBuyService).applyBuyTrade(
			eq(account), eq(instrument), any(Trade.class), eq(new BigDecimal("0.1")), eq(new BigDecimal("1000000")),
			eq(50L), eq(NOW));
		verifyNoInteractions(portfolioSellService);
	}

	@Test
	void fillIfPendingFillsSellOrderReleasesReservedQuantityAndAppliesRealizedPnl() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Holding holding = Holding.create(account, instrument, NOW.minusDays(1));
		holding.applyBuy(new BigDecimal("1"), new BigDecimal("900000"), NOW.minusDays(1));
		holding.reserveQuantity(new BigDecimal("0.1"));
		Order order = limitPendingOrder(account, instrument, OrderSide.SELL, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);
		when(portfolioSellService.getHoldingForUpdate(account, instrument)).thenReturn(holding);
		SellAllocationDto allocation = new SellAllocationDto(90_000L, 40L);
		when(portfolioSellService.applySellTrade(eq(holding), any(Trade.class), eq(new BigDecimal("0.1")), eq(NOW)))
			.thenReturn(allocation);
		when(portfolioSellService.finalizeSellRealizedPnl(eq(account), any(Trade.class), eq(100_000L), eq(50L),
			eq(allocation))).thenReturn(9_910L);

		service.fillIfPending(order.getId());

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);

		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository).save(tradeCaptor.capture());
		Trade savedTrade = tradeCaptor.getValue();
		assertThat(savedTrade.getPrice()).isEqualByComparingTo("1000000");
		assertThat(savedTrade.getAmount()).isEqualTo(100_000L);
		assertThat(savedTrade.getFee()).isEqualTo(50L);

		verify(portfolioSellService).applySellTrade(holding, savedTrade, new BigDecimal("0.1"), NOW);
		verify(portfolioSellService).finalizeSellRealizedPnl(account, savedTrade, 100_000L, 50L, allocation);
		ArgumentCaptor<RealizedPnlUpdatedEvent> eventCaptor = ArgumentCaptor.forClass(RealizedPnlUpdatedEvent.class);
		verify(eventPublisher).publishEvent(eventCaptor.capture());
		assertThat(eventCaptor.getValue().accountId()).isEqualTo(account.getId());
		verifyNoInteractions(portfolioBuyService);
	}

	@Test
	void fillIfPendingSecondCallDoesNotDuplicateFirstCallSideEffectsOnSameOrder() {
		// 같은 주문 인스턴스에 fillIfPending을 연속 두 번 호출한다(중복 이벤트 도착 시나리오).
		// 두 번째 호출 시점에는 order가 이미 FILLED이므로, 현금·거래 저장·매수 반영이 두 번째에는 전혀 일어나지
		// 않아야 한다(완료조건 "동시 체결 경합" — 예약 이중 반환·중복 체결 방지).
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillIfPending(order.getId());
		long cashAfterFirstCall = account.getCashBalance();
		long reservedCashAfterFirstCall = account.getReservedCash();

		service.fillIfPending(order.getId());

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(account.getCashBalance()).isEqualTo(cashAfterFirstCall);
		assertThat(account.getReservedCash()).isEqualTo(reservedCashAfterFirstCall);
		verify(tradeRepository, org.mockito.Mockito.times(1)).save(any(Trade.class));
		verify(portfolioBuyService, org.mockito.Mockito.times(1)).applyBuyTrade(
			any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
		// 두 번째 호출에서는 계좌 락 재획득 자체가 일어나지 않는다(order 상태를 먼저 확인하고 즉시 반환).
		verify(accountService, org.mockito.Mockito.times(1)).getAccountByIdForUpdate(account.getId());
	}

	@Test
	void fillIfPendingIsNoOpWhenOrderAlreadyFilled() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		order.markFilled();
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

		service.fillIfPending(order.getId());

		verify(accountService, never()).getAccountByIdForUpdate(any());
		verifyNoInteractions(tradeRepository, portfolioBuyService, portfolioSellService, eventPublisher);
	}

	// ADR-0025 — fillBatch(List<Long>)는 청크 안 각 주문에 fillIfPending과 동일한 체결 로직(fillOnePending)을
	// 순서대로 적용한다. 단위 테스트에서는 목만으로 검증 가능한 "순서대로 처리한다"만 본다 — 청크 원자적
	// 롤백(한 건 실패 시 전체 롤백)은 실제 DB 커밋이 필요해 LimitOrderFillBatchAtomicityIntegrationTest가 맡는다.
	@Test
	void fillBatchFillsEachOrderInGivenOrder() {
		// account()는 항상 id=10L을 부여하므로(테스트 헬퍼 관례), 두 주문에 서로 다른 Account 인스턴스를 쓰면
		// getAccountByIdForUpdate(10L) 스텁이 나중 것으로 덮어써져 첫 주문도 두 번째 계좌를 참조하게 된다 —
		// 하나의 계좌를 공유하고 두 주문의 예약 합계를 누적해서 이 문제를 피한다.
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order first = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		Order second = limitPendingOrder(account, instrument, OrderSide.BUY, "0.2", "1000000");
		ReflectionTestUtils.setField(first, "id", 101L);
		ReflectionTestUtils.setField(second, "id", 102L);
		account.reserveCash(100_050L + 200_100L);
		when(orderRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(first));
		when(orderRepository.findByIdForUpdate(102L)).thenReturn(Optional.of(second));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillBatch(List.of(101L, 102L));

		InOrder order = inOrder(orderRepository);
		order.verify(orderRepository).findByIdForUpdate(101L);
		order.verify(orderRepository).findByIdForUpdate(102L);
		assertThat(first.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(second.getStatus()).isEqualTo(OrderStatus.FILLED);
	}

	@Test
	void fillBatchSkipsOrderThatIsNoLongerPending() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order alreadyFilled = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		alreadyFilled.markFilled();
		ReflectionTestUtils.setField(alreadyFilled, "id", 103L);
		when(orderRepository.findByIdForUpdate(103L)).thenReturn(Optional.of(alreadyFilled));

		service.fillBatch(List.of(103L));

		verify(accountService, never()).getAccountByIdForUpdate(any());
		verifyNoInteractions(tradeRepository, portfolioBuyService, portfolioSellService, eventPublisher);
	}

	private static Order limitPendingOrder(
		Account account, Instrument instrument, OrderSide side, String quantity, String limitPrice) {
		Order order = Order.createLimitPending(
			account.getUser(), account, instrument, side, new BigDecimal(quantity), new BigDecimal(limitPrice),
			"idem-fill-" + side, "a".repeat(64), NOW);
		ReflectionTestUtils.setField(order, "id", 100L);
		return order;
	}

	private static Order attributedLimitPendingBuyOrder(Account account, Instrument instrument) {
		Order order = Order.createPracticeLimitPendingBuyForAttempt(
			account.getUser(), account, instrument, new BigDecimal("0.1"), new BigDecimal("1000000"),
			40L, 20L, 1L, "idem-attributed-fill", "c".repeat(64), NOW);
		ReflectionTestUtils.setField(order, "id", 101L);
		return order;
	}

	private static Instrument cryptoInstrument() {
		Instrument instrument = Instrument.create(
			com.finplay.api.market.domain.Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 1L);
		return instrument;
	}

	private static Account account() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		Account account = Account.create(user, Market.CRYPTO, NOW);
		ReflectionTestUtils.setField(account, "id", 10L);
		return account;
	}

	private static TutorialAccount tutorialAccount() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		return TutorialAccount.create(user, Market.CRYPTO, NOW);
	}
}
