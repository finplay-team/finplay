package com.finplay.api.domain.order.service;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.AccountService;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.service.UserQueryService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.order.dto.response.LimitOrderResponse;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderStatus;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PracticeLimitOrderCreationService {

	private final UserQueryService userQueryService;
	private final AccountService accountService;
	private final TutorialAccountService tutorialAccountService;
	private final InstrumentService instrumentService;
	private final OrderRepository orderRepository;
	private final PracticeOrderAttributionPort practiceOrderAttributionPort;
	private final Clock clock;

	@Autowired
	public PracticeLimitOrderCreationService(
		UserQueryService userQueryService,
		AccountService accountService,
		TutorialAccountService tutorialAccountService,
		InstrumentService instrumentService,
		OrderRepository orderRepository,
		PracticeOrderAttributionPort practiceOrderAttributionPort,
		Clock clock) {
		this.userQueryService = userQueryService;
		this.accountService = accountService;
		this.tutorialAccountService = tutorialAccountService;
		this.instrumentService = instrumentService;
		this.orderRepository = orderRepository;
		this.practiceOrderAttributionPort = practiceOrderAttributionPort;
		this.clock = clock;
	}

	@Transactional
	public LimitOrderResponse createSessionBuyOrder(
		Long userId, Long practicePriceSessionId, Long instrumentId, BigDecimal quantity, BigDecimal limitPrice) {
		Instrument instrument = getValidatedInstrument(instrumentId);
		Optional<PracticeOrderAttributionDto> practiceAttribution = practiceOrderAttributionPort
			.lockForOrder(userId, instrument, OrderType.LIMIT);
		LimitOrderCreationService.validateQuantityFormat(quantity);
		LimitOrderCreationService.validateLimitPrice(limitPrice);
		LimitOrderCreationService.validateMinOrderAmount(quantity, limitPrice, instrument);

		if (orderRepository.existsByPracticePriceSessionIdAndStatus(practicePriceSessionId, OrderStatus.PENDING)) {
			throw new BusinessException(ErrorCode.PRACTICE_LIMIT_ORDER_ALREADY_PENDING);
		}

		Account account = accountService.getAccountForUpdate(userId,
			Market.CRYPTO);
		long cashRequired = LimitOrderFeeCalculator.calculate(quantity, limitPrice).total();

		LocalDateTime now = LocalDateTime.now(clock);
		if (instrument.isTutorialSample()) {
			TutorialAccount tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
				userId, Market.CRYPTO, now);
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

		User user = userQueryService.getUser(userId);
		String idempotencyKey = "practice:%d:%s".formatted(practicePriceSessionId, UUID.randomUUID());
		String requestHash = calculateRequestHash(practicePriceSessionId, instrumentId, quantity, limitPrice);

		Order order = practiceAttribution
			.map(attribution -> Order.createPracticeLimitPendingBuyForAttempt(
				user,
				account,
				instrument,
				quantity,
				limitPrice,
				practicePriceSessionId,
				attribution.attemptId(),
				attribution.runNumber(),
				idempotencyKey,
				requestHash,
				now))
			.orElseGet(() -> Order.createPracticeLimitPendingBuy(
				user, account, instrument, quantity, limitPrice, practicePriceSessionId, idempotencyKey, requestHash,
				now));
		orderRepository.save(order);
		return LimitOrderResponse.from(order);
	}

	private Instrument getValidatedInstrument(Long instrumentId) {
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		if (instrument.getMarket() != Market.CRYPTO || !instrument.isTradable()) {
			throw new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE);
		}
		return instrument;
	}

	private String calculateRequestHash(
		Long practicePriceSessionId, Long instrumentId, BigDecimal quantity, BigDecimal limitPrice) {
		String raw = "practice:%d:%d:%s:%s".formatted(
			practicePriceSessionId, instrumentId, quantity.toPlainString(), limitPrice.toPlainString());
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashBytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
		}
	}
}
