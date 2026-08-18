// 코인 튜토리얼 가상 가격 세션에 귀속된 교육 전용 지정가 BUY 주문의 검증·예약·PENDING 저장을 담당하는 서비스
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.TutorialAccount;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.account.service.TutorialAccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.dto.response.LimitOrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.Optional;
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

	// education의 PracticeLimitOrderService가 세션을 owner 스코프로 잠근 뒤 같은 트랜잭션에서 호출한다
	// (plan.md 잠금 순서: session → account → order insert). side는 항상 BUY로 고정한다.
	// 멱등키는 클라이언트 헤더 없이 서버가 practice:{sessionId}:{UUID} 형태로 합성한다(plan.md 확정).
	@Transactional
	public LimitOrderResponse createSessionBuyOrder(
		Long userId, Long practicePriceSessionId, Long instrumentId, BigDecimal quantity, BigDecimal limitPrice) {
		Instrument instrument = getValidatedInstrument(instrumentId);
		Optional<PracticeOrderAttributionDto> practiceAttribution = practiceOrderAttributionPort
			.lockForOrder(userId, instrument);
		LimitOrderCreationService.validateQuantityFormat(quantity);
		LimitOrderCreationService.validateLimitPrice(limitPrice);
		LimitOrderCreationService.validateMinOrderAmount(quantity, limitPrice, instrument);

		if (orderRepository.existsByPracticePriceSessionIdAndStatus(practicePriceSessionId, OrderStatus.PENDING)) {
			throw new BusinessException(ErrorCode.PRACTICE_LIMIT_ORDER_ALREADY_PENDING);
		}

		// Order·Trade의 계좌 FK는 항상 실제 Account를 가리켜야 하므로 이 조회는 종목 종류와 무관하게
		// 유지한다(plan.md "호출부 변경 지점" 3번).
		Account account = accountService.getAccountForUpdate(userId, com.finplay.api.account.domain.Market.CRYPTO);
		long cashRequired = LimitOrderFeeCalculator.calculate(quantity, limitPrice).total();

		LocalDateTime now = LocalDateTime.now(clock);
		// 이 세션(030 코인 연습)은 샌드박스 종목뿐 아니라 실제 종목(BTC·ETH)도 다룬다 — 현금 검증·예약도
		// LimitOrderCreationService·LimitOrderFillService와 동일하게 instrument.isTutorialSample()로
		// 분기해야 한다. 무조건 튜토리얼 계좌로 보내면 실제 종목 체결·취소가 실제 Account(예약 0원)를
		// 대상으로 확정·해제를 시도해 예약 불일치 예외가 난다(047 TUTORIAL-CASH-ISOL-002·005 후속 회귀, 이슈 #450).
		if (instrument.isTutorialSample()) {
			TutorialAccount tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
				userId, com.finplay.api.account.domain.Market.CRYPTO, now);
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

	// LimitOrderService.calculateRequestHash와 같은 알고리즘 — 이 서비스는 Idempotency-Key 헤더가 없어
	// 재요청 재현에 쓰지 않지만(플랜 확정), Order.requestHash 컬럼은 NOT NULL이라 결정적으로 채운다.
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
