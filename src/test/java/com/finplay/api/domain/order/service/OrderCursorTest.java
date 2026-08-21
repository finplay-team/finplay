// OrderCursor의 파싱·인코딩·왕복 변환을 검증하는 순수 단위 테스트다.
package com.finplay.api.domain.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OrderCursorTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

	@Test
	void parseReturnsCursorWithRequestedAtAndId() {
		OrderCursor cursor = OrderCursor.parse("2026-07-18T10:00:00_42");

		assertThat(cursor).isNotNull();
		assertThat(cursor.requestedAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 10, 0, 0));
		assertThat(cursor.id()).isEqualTo(42L);
	}

	@Test
	void parseReturnsNullWhenRawIsNull() {
		assertThat(OrderCursor.parse(null)).isNull();
	}

	@Test
	void parseReturnsNullWhenRawIsBlank() {
		assertThat(OrderCursor.parse("")).isNull();
		assertThat(OrderCursor.parse("   ")).isNull();
	}

	@Test
	void parseThrowsValidationErrorWhenSeparatorMissing() {
		assertThatThrownBy(() -> OrderCursor.parse("2026-07-18T10:00:00"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void parseThrowsValidationErrorWhenDateTimePartIsInvalid() {
		assertThatThrownBy(() -> OrderCursor.parse("not-a-date_42"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void parseThrowsValidationErrorWhenIdPartIsInvalid() {
		assertThatThrownBy(() -> OrderCursor.parse("2026-07-18T10:00:00_not-a-number"))
			.isInstanceOf(BusinessException.class)
			.satisfies(exception -> assertThat(((BusinessException)exception).getErrorCode())
				.isEqualTo(ErrorCode.VALIDATION_ERROR));
	}

	@Test
	void encodeThenParseRoundTripsToOriginalRequestedAtAndId() {
		Order order = buyOrderWithId(77L, LocalDateTime.of(2026, 7, 18, 10, 0, 0));

		String encoded = OrderCursor.encode(order);
		OrderCursor parsed = OrderCursor.parse(encoded);

		assertThat(parsed).isNotNull();
		assertThat(parsed.requestedAt()).isEqualTo(order.getRequestedAt());
		assertThat(parsed.id()).isEqualTo(order.getId());
	}

	private static Order buyOrderWithId(long id, LocalDateTime requestedAt) {
		User user = User.create("trader@finplay.com", "password-hash", "trader", NOW);
		Account account = Account.create(user, Market.STOCK, NOW);
		Instrument instrument = Instrument.create(
			Market.STOCK, "TEST01", "테스트종목",
			BigDecimal.valueOf(100), 10_000L, true, NOW);
		Order order = Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "idem-key", "a".repeat(64), requestedAt);
		ReflectionTestUtils.setField(order, "id", id);
		return order;
	}
}
