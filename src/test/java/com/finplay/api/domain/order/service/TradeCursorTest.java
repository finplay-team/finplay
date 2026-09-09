package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class TradeCursorTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

	@Test
	void parseReturnsCursorWithExecutedAtAndId() {
		TradeCursor cursor = TradeCursor.parse("2026-07-18T10:00:00_42");

		assertThat(cursor).isNotNull();
		assertThat(cursor.executedAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 0, 0));
		assertThat(cursor.id()).isEqualTo(42L);
	}

	@Test
	void parseReturnsNullWhenRawIsNull() {
		assertThat(TradeCursor.parse(null)).isNull();
	}

	@Test
	void parseReturnsNullWhenRawIsBlank() {
		assertThat(TradeCursor.parse("")).isNull();
		assertThat(TradeCursor.parse("   ")).isNull();
	}

	@Test
	void parseThrowsValidationErrorWhenSeparatorMissing() {
		assertThatThrownBy(() -> TradeCursor.parse("2026-07-18T10:00:00"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void parseThrowsValidationErrorWhenDateTimePartIsInvalid() {
		assertThatThrownBy(() -> TradeCursor.parse("not-a-date_42"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void parseThrowsValidationErrorWhenIdPartIsInvalid() {
		assertThatThrownBy(() -> TradeCursor.parse("2026-07-18T10:00:00_not-a-number"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void encodeThenParseRoundTripsToOriginalExecutedAtAndId() {
		Trade trade = buyTradeWithId(77L, LocalDateTime.of(2026, 7, 18, 10, 0, 0));

		String encoded = TradeCursor.encode(trade);
		TradeCursor parsed = TradeCursor.parse(encoded);

		assertThat(parsed).isNotNull();
		assertThat(parsed.executedAt()).isEqualTo(trade.getExecutedAt());
		assertThat(parsed.id()).isEqualTo(trade.getId());
	}

	private static Trade buyTradeWithId(long id, LocalDateTime executedAt) {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		Account account = Account.create(user, Market.STOCK, NOW);
		Instrument instrument = Instrument.create(
			Market.STOCK, "TEST01", "테스트종목",
			BigDecimal.valueOf(100), 10_000L, true, NOW);
		Order order = Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "idem-key", "a".repeat(64), NOW);
		Trade trade = Trade.of(
			order, account, instrument,
			com.finplay.api.domain.market.entity.StockReplaySession.ready(NOW.toLocalDate(), NOW.toLocalDate(), NOW,
				NOW),
			OrderSide.BUY,
			BigDecimal.valueOf(75000), BigDecimal.valueOf(10),
			750_000L, 100L, null, executedAt, NOW);
		ReflectionTestUtils.setField(trade, "id", id);
		return trade;
	}
}
