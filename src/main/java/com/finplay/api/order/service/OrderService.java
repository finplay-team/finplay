// 시장가 매수 주문의 검증·체결·계좌/보유 갱신을 하나의 트랜잭션으로 처리하는 서비스
package com.finplay.api.order.service;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.service.AccountService;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.service.UserQueryService;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.InstrumentService;
import com.finplay.api.market.service.PriceQueryService;
import com.finplay.api.market.service.PriceQuoteDto;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.dto.response.OrderListItemResponse;
import com.finplay.api.order.dto.response.OrderResponse;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import com.finplay.api.portfolio.service.PortfolioBuyService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OrderService {

	private static final String MARKET_ORDER_TYPE = "MARKET";
	// 매직 넘버 금지 컨벤션 — plan.md 설계 노트 5 확정값
	private static final BigDecimal STOCK_FEE_RATE = new BigDecimal("0.00015");
	private static final BigDecimal CRYPTO_FEE_RATE = new BigDecimal("0.0005");

	private final UserQueryService userQueryService;
	private final AccountService accountService;
	private final InstrumentService instrumentService;
	private final PriceQueryService priceQueryService;
	private final PortfolioBuyService portfolioBuyService;
	private final OrderRepository orderRepository;
	private final TradeRepository tradeRepository;
	private final Clock clock;

	@Transactional
	public OrderResponse createBuyOrder(Long userId, String idempotencyKey, OrderCreateRequest request) {
		if (!MARKET_ORDER_TYPE.equals(request.orderType())) {
			throw new BusinessException(ErrorCode.UNSUPPORTED_ORDER_TYPE);
		}
		if (request.side() != OrderSide.BUY) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "매수 주문만 지원합니다.");
		}

		Instrument instrument = instrumentService.getInstrumentEntity(request.instrumentId());

		if (instrument.getMarket() != request.market()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "요청한 시장과 종목의 시장이 일치하지 않습니다.");
		}

		validateQuantityFormat(request.market(), request.quantity());

		// 설계 노트 3: assertOrderable(주식 장외 판별) → getPrice(유효 최신가) 순서 고정
		priceQueryService.assertOrderable(instrument);
		PriceQuoteDto priceQuote = priceQueryService.getPrice(instrument);
		BigDecimal price = priceQuote.price();
		BigDecimal quantity = request.quantity();
		BigDecimal rawAmount = price.multiply(quantity);

		// 설계 노트 5: 코인 최소주문금액은 내림 전 금액으로 비교한다.
		if (request.market() == Market.CRYPTO
			&& rawAmount.compareTo(BigDecimal.valueOf(instrument.getMinOrderAmount())) < 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 최소 주문금액에 미달합니다.");
		}

		long amount = rawAmount.setScale(0, RoundingMode.FLOOR).longValueExact();
		BigDecimal feeRate = request.market() == Market.STOCK ? STOCK_FEE_RATE : CRYPTO_FEE_RATE;
		long fee = BigDecimal.valueOf(amount).multiply(feeRate).setScale(0, RoundingMode.FLOOR).longValueExact();
		long cashRequired = amount + fee;

		// 설계 노트 1: 계좌 조회 시에만 두 Market enum 사이를 값 기반으로 변환한다.
		com.finplay.api.account.domain.Market accountMarket = com.finplay.api.account.domain.Market
			.valueOf(request.market().name());
		Account account = accountService.getAccountFor(userId, accountMarket);
		if (account.getCashBalance() < cashRequired) {
			throw new BusinessException(ErrorCode.INSUFFICIENT_CASH);
		}

		User user = userQueryService.getUser(userId);
		LocalDateTime now = LocalDateTime.now(clock);
		String requestHash = calculateRequestHash(request);

		Order order = Order.create(
			user,
			account,
			instrument,
			request.side(),
			OrderType.MARKET,
			quantity,
			idempotencyKey,
			requestHash,
			now);
		orderRepository.save(order);

		Trade trade = Trade.of(order, account, instrument, request.side(), price, quantity, amount, fee, null, now,
			now);
		tradeRepository.save(trade);

		account.deductCash(cashRequired);

		portfolioBuyService.applyBuyTrade(account, instrument, trade, quantity, price, fee, now);

		return OrderResponse.of(order, trade);
	}

	@Transactional(readOnly = true)
	public List<OrderListItemResponse> getMyOrders(Long userId) {
		return orderRepository.findAllByUserIdOrderByRequestedAtDescIdDesc(userId).stream()
			.map(OrderListItemResponse::from)
			.toList();
	}

	private void validateQuantityFormat(Market market, BigDecimal quantity) {
		if (quantity.compareTo(BigDecimal.ZERO) <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "수량은 0보다 커야 합니다.");
		}
		if (market == Market.STOCK && quantity.stripTrailingZeros().scale() > 0) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "주식 수량은 정수여야 합니다.");
		}
		if (market == Market.CRYPTO && quantity.stripTrailingZeros().scale() > 8) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR, "코인 수량은 소수점 8자리 이하여야 합니다.");
		}
	}

	// 설계 노트 8: market:instrumentId:side:orderType:quantity 형식 문자열을 SHA-256 hex로 해시한다.
	private String calculateRequestHash(OrderCreateRequest request) {
		String raw = "%s:%d:%s:%s:%s".formatted(
			request.market().name(),
			request.instrumentId(),
			request.side().name(),
			request.orderType(),
			request.quantity().toPlainString());
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hashBytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
		}
	}
}
