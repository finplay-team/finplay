// TradeCursor의 파싱·인코딩·왕복 변환을 검증하는 순수 단위 테스트다.
package com.finplay.api.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.domain.User;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
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
			com.finplay.api.market.domain.Market.STOCK, "TEST01", "테스트종목",
			BigDecimal.valueOf(100), 10_000L, true, NOW);
		Order order = Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "idem-key", "a".repeat(64), NOW);
		Trade trade = Trade.of(
			order, account, instrument, OrderSide.BUY,
			BigDecimal.valueOf(75000), BigDecimal.valueOf(10),
			750_000L, 100L, null, executedAt, NOW);
		ReflectionTestUtils.setField(trade, "id", id);
		return trade;
	}
}
