// ExitPlanCancelService.cancel의 존재→소유→상태 검증 순서와 예약 반환·조건 종결을 검증하는 단위 테스트다.
package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.ExitPlan;
import com.finplay.api.domain.order.entity.ExitPlanCondition;
import com.finplay.api.domain.order.entity.ExitPlanConditionStatus;
import com.finplay.api.domain.order.entity.ExitPlanConditionType;
import com.finplay.api.domain.order.entity.ExitPriceType;
import com.finplay.api.domain.order.repository.ExitPlanConditionRepository;
import com.finplay.api.domain.order.repository.ExitPlanRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ExitPlanCancelServiceTest {

	private static final Instant FIXED_INSTANT = Instant.parse("2026-08-13T10:00:00Z");
	private static final LocalDateTime NOW = LocalDateTime.ofInstant(FIXED_INSTANT, ZoneOffset.UTC);
	private static final Long OWNER_USER_ID = 1L;
	private static final Long OTHER_USER_ID = 2L;
	private static final Long PLAN_ID = 500L;

	private final ExitPlanRepository exitPlanRepository = mock(ExitPlanRepository.class);
	private final ExitPlanConditionRepository exitPlanConditionRepository = mock(ExitPlanConditionRepository.class);
	private final PortfolioSellService portfolioSellService = mock(PortfolioSellService.class);
	private final Clock clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
	private final EntityManager entityManager = mock(EntityManager.class);

	private final ExitPlanCancelService service = new ExitPlanCancelService(
		exitPlanRepository, exitPlanConditionRepository, portfolioSellService, clock, entityManager);

	@Test
	void cancelReleasesReservedQuantityAndCancelsPendingConditionsForPendingPlan() {
		Holding holding = holdingWithReservation();
		ExitPlan plan = pendingPlan(holding);
		when(exitPlanRepository.findByIdAndUserId(PLAN_ID, OWNER_USER_ID)).thenReturn(Optional.of(plan));
		when(portfolioSellService.getHoldingForUpdate(holding.getAccount(), plan.getInstrument())).thenReturn(holding);
		when(exitPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));
		ExitPlanCondition stopLoss = pendingCondition(plan, ExitPlanConditionType.STOP_LOSS);
		ExitPlanCondition takeProfit = pendingCondition(plan, ExitPlanConditionType.TAKE_PROFIT);
		when(exitPlanConditionRepository.findByExitPlanIdOrderByIdAsc(PLAN_ID))
			.thenReturn(List.of(stopLoss, takeProfit));

		service.cancel(OWNER_USER_ID, PLAN_ID);

		assertThat(plan.isPending()).isFalse();
		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(stopLoss.getStatus()).isEqualTo(ExitPlanConditionStatus.CANCELLED);
		assertThat(takeProfit.getStatus()).isEqualTo(ExitPlanConditionStatus.CANCELLED);
	}

	@Test
	void cancelThrowsExitPlanNotFoundWhenPlanDoesNotExist() {
		when(exitPlanRepository.findByIdAndUserId(PLAN_ID, OWNER_USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.cancel(OWNER_USER_ID, PLAN_ID))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EXIT_PLAN_NOT_FOUND);
		verifyNoInteractions(portfolioSellService, exitPlanConditionRepository);
	}

	@Test
	void cancelThrowsExitPlanNotFoundWhenRequesterIsNotOwner() {
		// findByIdAndUserId 자체가 소유자 조건을 포함하므로 타인 소유는 존재를 숨겨 같은 404다(021 spec, 존재 은닉).
		when(exitPlanRepository.findByIdAndUserId(PLAN_ID, OTHER_USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.cancel(OTHER_USER_ID, PLAN_ID))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EXIT_PLAN_NOT_FOUND);
		verifyNoInteractions(portfolioSellService, exitPlanConditionRepository);
	}

	@Test
	void cancelThrowsExitPlanNotPendingWhenPlanIsAlreadyTerminal() {
		Holding holding = holdingWithReservation();
		ExitPlan plan = pendingPlan(holding);
		plan.cancel(NOW.minusMinutes(1)); // 이미 CANCELLED로 종결된 plan
		when(exitPlanRepository.findByIdAndUserId(PLAN_ID, OWNER_USER_ID)).thenReturn(Optional.of(plan));
		when(portfolioSellService.getHoldingForUpdate(holding.getAccount(), plan.getInstrument())).thenReturn(holding);
		when(exitPlanRepository.findByIdForUpdate(PLAN_ID)).thenReturn(Optional.of(plan));

		assertThatThrownBy(() -> service.cancel(OWNER_USER_ID, PLAN_ID))
			.isInstanceOf(BusinessException.class)
			.extracting(ex -> ((BusinessException)ex).getErrorCode())
			.isEqualTo(ErrorCode.EXIT_PLAN_NOT_PENDING);
		verifyNoInteractions(exitPlanConditionRepository);
		// 이미 종결된 plan을 다시 취소하려는 시도는 예약을 건드리지 않는다 — 원래 취소 시점에 이미 반환됐어야 할
		// 예약이 이 실패 경로에서 중복 해제되지 않는다.
		assertThat(holding.getReservedQuantity()).isEqualByComparingTo(new BigDecimal("1"));
	}

	private static Holding holdingWithReservation() {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "BTC", "비트코인", new BigDecimal("1000"), 5_000L, true, NOW);
		ReflectionTestUtils.setField(instrument, "id", 1L);
		Account account = Account.create(owner(), Market.CRYPTO, NOW);
		ReflectionTestUtils.setField(account, "id", 10L);
		Holding holding = Holding.create(account, instrument, NOW);
		ReflectionTestUtils.setField(holding, "id", 100L);
		holding.applyBuy(new BigDecimal("10"), new BigDecimal("100000"), NOW);
		holding.reserveQuantity(new BigDecimal("1"));
		return holding;
	}

	private static ExitPlan pendingPlan(Holding holding) {
		ExitPlan plan = ExitPlan.createGeneral(
			owner(), holding, holding.getInstrument(), new BigDecimal("1"), holding.getAveragePrice(),
			ExitPriceType.PRICE, null, null, new BigDecimal("95000"), new BigDecimal("110000"),
			new BigDecimal("100500"), NOW, "h".repeat(64), NOW);
		ReflectionTestUtils.setField(plan, "id", PLAN_ID);
		return plan;
	}

	private static ExitPlanCondition pendingCondition(ExitPlan plan, ExitPlanConditionType type) {
		BigDecimal triggerPrice = type == ExitPlanConditionType.STOP_LOSS
			? new BigDecimal("95000")
			: new BigDecimal("110000");
		return ExitPlanCondition.create(plan, type, triggerPrice, NOW);
	}

	private static User owner() {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		ReflectionTestUtils.setField(user, "id", OWNER_USER_ID);
		return user;
	}
}
