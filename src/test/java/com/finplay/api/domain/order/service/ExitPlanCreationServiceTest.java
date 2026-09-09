package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.PriceQueryService;
import com.finplay.api.domain.market.service.PriceQuoteDto;
import com.finplay.api.domain.market.service.PriceStatus;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanCondition;
import com.finplay.api.domain.order.entity.ExitPlanConditionType;
import com.finplay.api.domain.order.entity.ExitPlanStatus;
import com.finplay.api.domain.order.repository.ExitPlanConditionRepository;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class ExitPlanCreationServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-12T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
	private static final String REQUEST_HASH = "0".repeat(64);

	private final PortfolioSellService portfolioSellService = mock(PortfolioSellService.class);
	private final PriceQueryService priceQueryService = mock(PriceQueryService.class);
	private final ExitPricePolicy exitPricePolicy = mock(ExitPricePolicy.class);
	private final ExitPlanRepository exitPlanRepository = mock(ExitPlanRepository.class);
	private final ExitPlanConditionRepository exitPlanConditionRepository = mock(ExitPlanConditionRepository.class);
	private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

	private final ExitPlanCreationService service = new ExitPlanCreationService(
		portfolioSellService, priceQueryService, exitPricePolicy, exitPlanRepository, exitPlanConditionRepository,
		clock);

	private User user;
	private Account account;
	private Instrument instrument;
	private Holding holding;

	@BeforeEach
	void setUp() {
		user = User.create("exit-plan-engine@finplay.com", "hash", "engine-tester", NOW);
		ReflectionTestUtils.setField(user, "id", 1L);
		account = Account.create(user, Market.CRYPTO, NOW);
		ReflectionTestUtils.setField(account, "id", 10L);
		instrument = Instrument.create(
			Market.CRYPTO, "ETH", "이더리움", new BigDecimal("0.00000001"), 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 100L);
		holding = Holding.create(account, instrument, NOW);
		holding.applyBuy(new BigDecimal("10.00000000"), new BigDecimal("100000.00000000"), NOW);
		ReflectionTestUtils.setField(holding, "id", 1000L);
	}

	@Test
	@DisplayName("검증 순서는 4단계(EXIT_PLAN_ALREADY_EXISTS)가 5단계(INSUFFICIENT_QTY)보다 먼저다 — 기존 예약이 수량 부족의 진짜 원인을 가리지 않게 한다")
	void validatesExistingPendingPlanBeforeAvailableQuantityToAvoidMaskingTheRealCause() {
		when(portfolioSellService.getHoldingForUpdateForExitPlanCreation(account, instrument)).thenReturn(holding);
		when(exitPlanRepository.existsByHoldingIdAndStatus(holding.getId(), ExitPlanStatus.PENDING)).thenReturn(true);
		ExitPlanCreateCommandDto command = generalCommand(new BigDecimal("100.00000000"));

		assertThatThrownBy(() -> service.create(command))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.EXIT_PLAN_ALREADY_EXISTS));

		verifyNoInteractions(exitPricePolicy, priceQueryService, exitPlanConditionRepository);
		verify(exitPlanRepository, never()).save(any());
	}

	@Test
	@DisplayName("PENDING plan이 없고 availableQuantity가 부족하면 INSUFFICIENT_QTY로 거부된다")
	void rejectsWithInsufficientQtyWhenNoPendingPlanButAvailableQuantityIsShort() {
		when(portfolioSellService.getHoldingForUpdateForExitPlanCreation(account, instrument)).thenReturn(holding);
		when(exitPlanRepository.existsByHoldingIdAndStatus(holding.getId(), ExitPlanStatus.PENDING))
			.thenReturn(false);
		ExitPlanCreateCommandDto command = generalCommand(new BigDecimal("100.00000000"));

		assertThatThrownBy(() -> service.create(command))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.INSUFFICIENT_QTY));

		verifyNoInteractions(exitPricePolicy, priceQueryService, exitPlanConditionRepository);
		verify(exitPlanRepository, never()).save(any());
	}

	@Test
	@DisplayName("가격 계산까지 통과했지만 유효 현재가가 없으면 PRICE_UNAVAILABLE로 거부되고 plan·condition·예약 흔적이 남지 않는다")
	void rejectsWithPriceUnavailableAndLeavesNoTraceWhenNoValidQuote() {
		when(portfolioSellService.getHoldingForUpdateForExitPlanCreation(account, instrument)).thenReturn(holding);
		when(exitPlanRepository.existsByHoldingIdAndStatus(holding.getId(), ExitPlanStatus.PENDING))
			.thenReturn(false);
		ExitPlanCreateCommandDto command = generalCommand(new BigDecimal("1.00000000"));
		ExitPriceLinesDto lines = new ExitPriceLinesDto(new BigDecimal("95000.00000000"),
			new BigDecimal("110000.00000000"));
		when(exitPricePolicy.resolve(command.priceInput())).thenReturn(lines);
		when(priceQueryService.getPrice(instrument.getId()))
			.thenThrow(new BusinessException(ErrorCode.PRICE_UNAVAILABLE));

		BigDecimal reservedBefore = holding.getReservedQuantity();

		assertThatThrownBy(() -> service.create(command))
			.isInstanceOf(BusinessException.class)
			.satisfies(ex -> assertThat(((BusinessException)ex).getErrorCode())
				.isEqualTo(ErrorCode.PRICE_UNAVAILABLE));

		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(reservedBefore);
		verify(exitPlanRepository, never()).save(any());
		verifyNoInteractions(exitPlanConditionRepository);
	}

	@Test
	@DisplayName("일반 경로 성공 시 holding 예약 1회, exit_plans 1행, exit_plan_conditions 2행(손절·익절)이 저장된다")
	void succeedsWithOneReservationOnePlanRowAndTwoConditionRowsForGeneralPath() {
		when(portfolioSellService.getHoldingForUpdateForExitPlanCreation(account, instrument)).thenReturn(holding);
		when(exitPlanRepository.existsByHoldingIdAndStatus(holding.getId(), ExitPlanStatus.PENDING))
			.thenReturn(false);
		BigDecimal requestedQuantity = new BigDecimal("1.00000000");
		ExitPlanCreateCommandDto command = generalCommand(requestedQuantity);
		ExitPriceLinesDto lines = new ExitPriceLinesDto(new BigDecimal("95000.00000000"),
			new BigDecimal("110000.00000000"));
		when(exitPricePolicy.resolve(command.priceInput())).thenReturn(lines);
		PriceQuoteDto quote = new PriceQuoteDto(
			new BigDecimal("100500.00000000"), NOW, PriceStatus.AVAILABLE, LocalDate.of(2026, 8, 12));
		when(priceQueryService.getPrice(instrument.getId())).thenReturn(quote);
		when(exitPlanRepository.save(any(ExitPlan.class))).thenAnswer(invocation -> invocation.getArgument(0));

		BigDecimal reservedBefore = holding.getReservedQuantity();

		ExitPlan result = service.create(command);

		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(reservedBefore.add(requestedQuantity));
		assertThat(result.getIntentionId()).isNull();
		assertThat(result.getBuyTrade()).isNull();
		assertThat(result.getBaselinePrice()).isEqualByComparingTo(quote.price());
		assertThat(result.getStatus()).isEqualTo(ExitPlanStatus.PENDING);

		verify(exitPlanRepository, times(1)).save(any(ExitPlan.class));

		ArgumentCaptor<ExitPlanCondition> conditionCaptor = ArgumentCaptor.forClass(ExitPlanCondition.class);
		verify(exitPlanConditionRepository, times(2)).save(conditionCaptor.capture());
		assertThat(conditionCaptor.getAllValues())
			.extracting(ExitPlanCondition::getConditionType)
			.containsExactly(ExitPlanConditionType.STOP_LOSS, ExitPlanConditionType.TAKE_PROFIT);
		assertThat(conditionCaptor.getAllValues())
			.extracting(ExitPlanCondition::getTriggerPrice)
			.usingElementComparator(BigDecimal::compareTo)
			.containsExactly(lines.stopLossPrice(), lines.takeProfitPrice());
	}

	@Test
	@DisplayName("holding 잠금은 015가 이미 구현한 PortfolioSellService.getHoldingForUpdateForExitPlanCreation을 재사용한다 — 새 원장을 만들지 않는다")
	void locksHoldingThroughExistingPortfolioSellServiceInsteadOfNewLedger() {
		when(portfolioSellService.getHoldingForUpdateForExitPlanCreation(account, instrument)).thenReturn(holding);
		when(exitPlanRepository.existsByHoldingIdAndStatus(holding.getId(), ExitPlanStatus.PENDING)).thenReturn(true);
		ExitPlanCreateCommandDto command = generalCommand(new BigDecimal("1.00000000"));

		assertThatThrownBy(() -> service.create(command)).isInstanceOf(BusinessException.class);

		verify(portfolioSellService, times(1)).getHoldingForUpdateForExitPlanCreation(account, instrument);
	}

	private ExitPlanCreateCommandDto generalCommand(BigDecimal quantity) {
		ExitPriceInputDto priceInput = ExitPriceInputDto.ofPrice(
			holding.getAveragePrice(), new BigDecimal("95000.00000000"), new BigDecimal("110000.00000000"));
		return ExitPlanCreateCommandDto.general(user, holding, quantity, priceInput, REQUEST_HASH);
	}
}
