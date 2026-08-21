// 코인 지정가 매수·매도 주문의 검증·예약(에스크로)·PENDING 저장을 하나의 트랜잭션으로 처리하는 서비스
package com.finplay.api.domain.order.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.dto.request.LimitOrderCreateRequest;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.service.PortfolioSellService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LimitOrderCreationService {

	private final UserQueryService userQueryService;
	private final AccountService accountService;
	private final TutorialAccountService tutorialAccountService;
	private final InstrumentService instrumentService;
	private final PortfolioSellService portfolioSellService;
	private final OrderRepository orderRepository;
	private final PracticeOrderAttributionPort practiceOrderAttributionPort;
	private final PracticeOrderSettlementService practiceOrderSettlementService;
	private final Clock clock;

	// plan.md "지정가 생성 흐름" — BUY는 account 락만, SELL은 holding 락만 잡는다.
	@Transactional
	public LimitOrderResponse execute(
		Long userId, String idempotencyKey, String requestHash, LimitOrderCreateRequest request) {
		validateMarketIsCrypto(request.market());
		Instrument instrument = getValidatedInstrument(request.market(), request.instrumentId());
		validateQuantityFormat(request.quantity());
		validateLimitPrice(request.limitPrice());
		validateMinOrderAmount(request.quantity(), request.limitPrice(), instrument);
		Optional<PracticeOrderAttributionDto> practiceAttribution = practiceOrderAttributionPort
			.lockForOrder(userId, instrument, OrderType.LIMIT);

		return request.side() == OrderSide.SELL
			? createSellOrder(userId, idempotencyKey, requestHash, request, instrument, practiceAttribution)
			: createBuyOrder(userId, idempotencyKey, requestHash, request, instrument, practiceAttribution);
	}

	private LimitOrderResponse createBuyOrder(
		Long userId, String idempotencyKey, String requestHash, LimitOrderCreateRequest request,
		Instrument instrument, Optional<PracticeOrderAttributionDto> practiceAttribution) {
		BigDecimal quantity = request.quantity();
		BigDecimal limitPrice = request.limitPrice();

		// LMT-001 BUY: account 락만 잡는다(holding은 건드리지 않는다). Order·Trade의 계좌 FK는 항상 실제
		// Account를 가리켜야 하므로 이 조회는 종목 종류와 무관하게 유지한다.
		Account account = accountService.getAccountForUpdate(userId, request.market());

		long cashRequired = LimitOrderFeeCalculator.calculate(quantity, limitPrice).total();
		// 샌드박스(튜토리얼) 종목의 지정가 매수 예약은 실제 Account 대신 튜토리얼 계좌를 대상으로 한다
		// (047 TUTORIAL-CASH-ISOL-002·005). PracticeAttemptOrderAttributionService.lockForOrder는
		// instrument.isTutorialSample()이 아니면 항상 Optional.empty()를 반환하므로, 이 일반 지정가
		// 경로로 들어오는 샌드박스 종목 매수도 실거래와 동일하게 정상적으로 다뤄야 하는 경로다 — 이
		// 예약을 실제 Account에 남기면 이후 LimitOrderFillService.fillBuy가 튜토리얼 계좌에서 확정을
		// 시도해 예약 불일치 예외가 난다.
		if (instrument.isTutorialSample()) {
			LocalDateTime now = LocalDateTime.now(clock);
			TutorialAccount tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
				userId, request.market(), now);
			if (tutorialAccount.getAvailableCash() < cashRequired) {
				throw new BusinessException(ErrorCode.TUTORIAL_INSUFFICIENT_CASH);
			}
			tutorialAccount.reserveCash(cashRequired);
		} else {
			if (account.getAvailableCash() < cashRequired) {
				throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
			}
			account.reserveCash(cashRequired);
		}

		Order order = saveLimitPendingOrder(
			userId, idempotencyKey, requestHash, request, instrument, account, quantity, limitPrice,
			practiceAttribution);
		return LimitOrderResponse.from(order);
	}

	private LimitOrderResponse createSellOrder(
		Long userId, String idempotencyKey, String requestHash, LimitOrderCreateRequest request,
		Instrument instrument, Optional<PracticeOrderAttributionDto> practiceAttribution) {
		BigDecimal quantity = request.quantity();
		BigDecimal limitPrice = request.limitPrice();

		// LMT-001 SELL: 계좌 락 없이 holding 락만 잡는다 — 두 자원을 동시에 들지 않으므로 ABBA 위험이 없다(plan.md).
		Account account = accountService.getAccountFor(userId, request.market());
		// 042 EXITPRESET-016 — 튜토리얼 자동 예약이 체결 수량 전량을 잡고 있으면 availableQuantity가 0이라
		// 아래 검증이 매도를 거부한다. 시장가 매도(OrderExecutionService)와 같은 처리를 지정가에도 한다 —
		// 043이 튜토리얼 지정가 예약 카드를 계약으로 갖고 있어 이 경로가 실제로 쓰인다. 같은 트랜잭션이라
		// 매도 접수가 실패하면 취소도 함께 롤백된다. holding을 이 호출 뒤에 처음 잡는 이유이기도 하다 —
		// 취소 서비스가 flush 후 holding을 detach한다.
		practiceAttribution.ifPresent(attribution -> practiceOrderSettlementService.cancelCurrentRunExitPlans(
			userId, attribution.attemptId(), attribution.runNumber()));
		Holding holding = portfolioSellService.getHoldingForUpdateOrThrow(account, instrument, quantity);
		holding.reserveQuantity(quantity);

		Order order = saveLimitPendingOrder(
			userId, idempotencyKey, requestHash, request, instrument, account, quantity, limitPrice,
			practiceAttribution);
		return LimitOrderResponse.from(order);
	}

	private Order saveLimitPendingOrder(
		Long userId, String idempotencyKey, String requestHash, LimitOrderCreateRequest request,
		Instrument instrument, Account account, BigDecimal quantity, BigDecimal limitPrice,
		Optional<PracticeOrderAttributionDto> practiceAttribution) {
		User user = userQueryService.getUser(userId);
		LocalDateTime now = LocalDateTime.now(clock);

		Order order = practiceAttribution
			.map(attribution -> Order.createLimitPendingForPracticeAttempt(
				user, account, instrument, request.side(), quantity, limitPrice,
				attribution.attemptId(), attribution.runNumber(), idempotencyKey, requestHash, now))
			.orElseGet(() -> Order.createLimitPending(
				user, account, instrument, request.side(), quantity, limitPrice, idempotencyKey, requestHash, now));
		orderRepository.save(order);
		return order;
	}

	private void validateMarketIsCrypto(Market market) {
		if (market != Market.CRYPTO) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 종목만 지정가 주문을 지원합니다.");
		}
	}

	private Instrument getValidatedInstrument(Market market, Long instrumentId) {
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		if (instrument.getMarket() != market) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "요청한 시장과 종목의 시장이 일치하지 않습니다.");
		}
		return instrument;
	}

	// PracticeLimitOrderCreationService(030)가 교육 지정가 검증에 그대로 재사용한다 — 패키지 전용 접근이라
	// order.service 밖으로는 노출되지 않는다(plan.md "기존 수량·가격·최소금액·현금 예약 검증 재사용").
	static void validateQuantityFormat(BigDecimal quantity) {
		if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "수량은 0보다 커야 합니다.");
		}
		if (quantity.stripTrailingZeros().scale() > 8) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 수량은 소수점 8자리 이하여야 합니다.");
		}
	}

	static void validateLimitPrice(BigDecimal limitPrice) {
		if (limitPrice.compareTo(BigDecimal.ZERO) <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "지정가는 0보다 커야 합니다.");
		}
	}

	// spec.md: 수량×지정가(내림 전 금액) 기준으로 기존 ORD-003 최소주문금액 규칙을 재사용한다.
	static void validateMinOrderAmount(BigDecimal quantity, BigDecimal limitPrice, Instrument instrument) {
		BigDecimal rawAmount = quantity.multiply(limitPrice);
		if (rawAmount.compareTo(BigDecimal.valueOf(instrument.getMinOrderAmount())) < 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 최소 주문금액에 미달합니다.");
		}
	}
}
