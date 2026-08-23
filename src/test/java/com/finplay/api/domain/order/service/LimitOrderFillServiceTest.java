// LimitOrderFillService.fillIfPending의 BUY/SELL 체결·중복 이벤트 no-op을 검증하는 단위 테스트다.
package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.event.RealizedPnlUpdatedEvent;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioBuyService;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.domain.portfolio.service.SellAllocationDto;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
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
		// reservedCash는 전혀 변하지 않는다.
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
	void fillIfPendingBuyDoesNotTouchTutorialAccountWhenInstrumentIsReal() {
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
		when(accountService.getAccountByIdForUpdate(account.getId())).thenReturn(account);

		service.fillIfPending(order.getId());

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
			eq(allocation), eq(NOW))).thenReturn(9_910L);

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
		verify(portfolioSellService).finalizeSellRealizedPnl(account, savedTrade, 100_000L, 50L, allocation, NOW);
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

	// ADR-0025 — fillBatch(List<Long>)는 청크 안 각 주문에 fillIfPending과 동일한 체결 로직을 순서대로
	// 적용한다. 054-limit-order-fill-bulk-lock부터는 order→account→holding을 개별 왕복이 아니라 벌크 FOR
	// UPDATE 조회로 묶으므로, 여기서는 벌크 조회 3종(findByIdInForUpdate·getAccountsByIdsForUpdate·
	// findHoldingsForUpdate)을 스텁한다. 청크 원자적 롤백(한 건 실패 시 전체 롤백)은 실제 DB 커밋이 필요해
	// LimitOrderFillBatchAtomicityIntegrationTest가 맡는다.
	@Test
	void fillBatchFillsEachOrderInGivenOrder() {
		// account()는 항상 id=10L을 부여하므로(테스트 헬퍼 관례), 두 주문에 서로 다른 Account 인스턴스를 쓰면
		// getAccountsByIdsForUpdate(List.of(10L)) 스텁이 나중 것으로 덮어써져 첫 주문도 두 번째 계좌를 참조하게
		// 된다 — 하나의 계좌를 공유하고 두 주문의 예약 합계를 누적해서 이 문제를 피한다.
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order first = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		Order second = limitPendingOrder(account, instrument, OrderSide.BUY, "0.2", "1000000");
		ReflectionTestUtils.setField(first, "id", 101L);
		ReflectionTestUtils.setField(second, "id", 102L);
		account.reserveCash(100_050L + 200_100L);
		when(orderRepository.findByIdInForUpdate(List.of(101L, 102L))).thenReturn(List.of(first, second));
		when(accountService.getAccountsByIdsForUpdate(List.of(10L))).thenReturn(List.of(account));
		when(portfolioBuyService.findHoldingsForUpdate(List.of(10L), instrument.getId())).thenReturn(List.of());
		when(portfolioBuyService.applyBuyTrade(
			eq(account), eq(instrument), any(Trade.class), any(BigDecimal.class), eq(new BigDecimal("1000000")),
			anyLong(), eq(NOW), any(Holding.class)))
			.thenAnswer(invocation -> invocation.getArgument(7));

		service.fillBatch(List.of(101L, 102L));

		assertThat(first.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(second.getStatus()).isEqualTo(OrderStatus.FILLED);
	}

	@Test
	void fillBatchSkipsOrderThatIsNoLongerPending() {
		// PENDING이 아닌 주문만 있는 청크는 계좌·holding 벌크 조회 자체를 생략한다(fillBatch의
		// pendingOrders.isEmpty() 분기) — account·holding 관련 협력자를 전혀 건드리지 않아야 한다.
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order alreadyFilled = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		alreadyFilled.markFilled();
		ReflectionTestUtils.setField(alreadyFilled, "id", 103L);
		when(orderRepository.findByIdInForUpdate(List.of(103L))).thenReturn(List.of(alreadyFilled));

		service.fillBatch(List.of(103L));

		verify(accountService, never()).getAccountsByIdsForUpdate(any());
		verifyNoInteractions(tradeRepository, portfolioBuyService, portfolioSellService, eventPublisher);
	}

	@Test
	void fillBatchLocksBulkResourcesInOrderAccountHoldingSequence() {
		// plan.md "4": 벌크 락은 order→account→holding 순서로 걸려야 한다(위험 요소 1·3). 각 단계는 AccountService·
		// PortfolioBuyService만 거쳐 호출한다(ADR-0002 — LimitOrderFillService가 AccountRepository·
		// HoldingRepository를 직접 주입하지 않는다).
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(100_050L);
		Order order = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		ReflectionTestUtils.setField(order, "id", 101L);
		when(orderRepository.findByIdInForUpdate(List.of(101L))).thenReturn(List.of(order));
		when(accountService.getAccountsByIdsForUpdate(List.of(10L))).thenReturn(List.of(account));
		when(portfolioBuyService.findHoldingsForUpdate(List.of(10L), instrument.getId())).thenReturn(List.of());
		when(portfolioBuyService.applyBuyTrade(
			eq(account), eq(instrument), any(Trade.class), eq(new BigDecimal("0.1")), eq(new BigDecimal("1000000")),
			eq(50L), eq(NOW), any(Holding.class)))
			.thenAnswer(invocation -> invocation.getArgument(7));

		service.fillBatch(List.of(101L));

		InOrder bulkLockOrder = inOrder(orderRepository, accountService, portfolioBuyService);
		bulkLockOrder.verify(orderRepository).findByIdInForUpdate(List.of(101L));
		bulkLockOrder.verify(accountService).getAccountsByIdsForUpdate(List.of(10L));
		bulkLockOrder.verify(portfolioBuyService).findHoldingsForUpdate(List.of(10L), instrument.getId());
		assertThat(order.getStatus()).isEqualTo(OrderStatus.FILLED);
	}

	@Test
	@SuppressWarnings("unchecked")
	void fillBatchLocksOrderIdsAscendingButProcessesGivenSequenceAndReusesHoldingCreatedInSameChunk() {
		// spec.md 비즈니스 규칙: 벌크 락을 위한 ID 오름차순 정렬은 잠그는 쿼리에만 적용되고, 실제 체결 처리
		// 순서는 fillBatch에 전달된 원래 순서(requestedAt asc, id asc, LMT-002 계약)를 그대로 따라야 한다.
		// 또한 같은 청크·같은 계좌·같은 신규 종목에 매수가 2건 걸려 있으면(위험 요소 2) 먼저 처리된 주문이 만든
		// holding을 나중 주문이 재사용해야 한다(두 번째 Holding.create + INSERT가 없어야 uk_holdings_account_
		// instrument 위반을 피한다).
		Instrument instrument = cryptoInstrument();
		Account account = account();
		account.reserveCash(200_100L + 100_050L);
		Order order101 = limitPendingOrder(account, instrument, OrderSide.BUY, "0.1", "1000000");
		ReflectionTestUtils.setField(order101, "id", 101L);
		Order order102 = limitPendingOrder(account, instrument, OrderSide.BUY, "0.2", "1000000");
		ReflectionTestUtils.setField(order102, "id", 102L);
		when(orderRepository.findByIdInForUpdate(List.of(101L, 102L))).thenReturn(List.of(order101, order102));
		when(accountService.getAccountsByIdsForUpdate(List.of(10L))).thenReturn(List.of(account));
		when(portfolioBuyService.findHoldingsForUpdate(List.of(10L), instrument.getId())).thenReturn(List.of());
		// 실제 PortfolioBuyService.applyBuyTrade(..., holding)처럼 넘겨받은 holding 인스턴스를 그대로
		// 반환한다(저장 시뮬레이션) — 맵 재사용 여부를 인스턴스 동일성으로 검증할 수 있게 한다.
		when(portfolioBuyService.applyBuyTrade(
			eq(account), eq(instrument), any(Trade.class), any(BigDecimal.class), eq(new BigDecimal("1000000")),
			anyLong(), eq(NOW), any(Holding.class)))
			.thenAnswer(invocation -> invocation.getArgument(7));

		// 처리 순서를 102 → 101로 뒤집어 전달한다 — 락 쿼리의 오름차순 정렬과 처리 순서가 다르다는 것을
		// 드러내기 위해서다.
		service.fillBatch(List.of(102L, 101L));

		ArgumentCaptor<List<Long>> lockIdsCaptor = ArgumentCaptor.forClass(List.class);
		verify(orderRepository).findByIdInForUpdate(lockIdsCaptor.capture());
		assertThat(lockIdsCaptor.getValue()).containsExactly(101L, 102L); // 락 쿼리는 항상 ID 오름차순

		ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
		verify(tradeRepository, org.mockito.Mockito.times(2)).save(tradeCaptor.capture());
		List<Trade> savedTrades = tradeCaptor.getAllValues();
		assertThat(savedTrades.get(0).getQuantity()).isEqualByComparingTo("0.2"); // 102가 먼저 체결(처리 순서 보존)
		assertThat(savedTrades.get(1).getQuantity()).isEqualByComparingTo("0.1"); // 101이 나중 체결

		ArgumentCaptor<Holding> holdingCaptor = ArgumentCaptor.forClass(Holding.class);
		verify(portfolioBuyService, org.mockito.Mockito.times(2)).applyBuyTrade(
			eq(account), eq(instrument), any(Trade.class), any(BigDecimal.class), eq(new BigDecimal("1000000")),
			anyLong(), eq(NOW), holdingCaptor.capture());
		List<Holding> holdingsPassedIn = holdingCaptor.getAllValues();
		assertThat(holdingsPassedIn.get(1)).isSameAs(holdingsPassedIn.get(0)); // 두 번째 호출이 첫 호출의 holding을 재사용

		assertThat(order101.getStatus()).isEqualTo(OrderStatus.FILLED);
		assertThat(order102.getStatus()).isEqualTo(OrderStatus.FILLED);
	}

	@Test
	void fillBatchThrowsSameMessageAsFindByIdForUpdateWhenBulkOrderLockOmitsRequestedId() {
		// spec.md 비즈니스 규칙: 벌크 조회가 조용히 빠뜨린 존재하지 않는 orderId는 처리 루프가 직접 예외를
		// 던져야 한다 — 단건 경로(findByIdForUpdate의 orElseThrow)와 동일한 타입·메시지여야 기존
		// LimitOrderFillBatchAtomicityIntegrationTest의 "청크 전체 롤백" 시나리오가 그대로 성립한다.
		when(orderRepository.findByIdInForUpdate(List.of(999L))).thenReturn(List.of());

		assertThatThrownBy(() -> service.fillBatch(List.of(999L)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("체결 대상 주문을 찾을 수 없습니다. orderId=999");

		verifyNoInteractions(accountService, tradeRepository, portfolioBuyService, portfolioSellService);
	}

	@Test
	void fillBatchThrowsSameMessageAsGetHoldingForUpdateWhenSellHoldingMissingFromBulkMap() {
		// SELL은 신규 생성이 없으므로 holdingsByAccountId 맵에 없으면 실제로 holding이 없는 것이다 —
		// PortfolioSellService.getHoldingForUpdate(단건 경로)와 동일한 메시지의 예외를 던져야 한다.
		Instrument instrument = cryptoInstrument();
		Account account = account();
		Order order = limitPendingOrder(account, instrument, OrderSide.SELL, "0.1", "1000000");
		ReflectionTestUtils.setField(order, "id", 104L);
		when(orderRepository.findByIdInForUpdate(List.of(104L))).thenReturn(List.of(order));
		when(accountService.getAccountsByIdsForUpdate(List.of(10L))).thenReturn(List.of(account));
		when(portfolioBuyService.findHoldingsForUpdate(List.of(10L), instrument.getId())).thenReturn(List.of());

		assertThatThrownBy(() -> service.fillBatch(List.of(104L)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage(
				"체결 대상 holding을 찾을 수 없습니다. accountId=" + account.getId() + ", instrumentId=" + instrument.getId());

		verifyNoInteractions(tradeRepository, portfolioSellService, eventPublisher);
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
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), 5_000L, true,
			NOW);
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
