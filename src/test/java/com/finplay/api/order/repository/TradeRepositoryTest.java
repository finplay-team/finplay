// 주문별 체결 단건 조회 쿼리 메서드를 검증하는 슬라이스 테스트 (docs/specs/004-order-buy 이슈 #22)
package com.finplay.api.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TradeRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TradeRepository tradeRepository;

	private User owner;
	private Account ownerAccount;
	private Instrument instrument;

	@BeforeEach
	void setUp() {
		owner = userRepository.saveAndFlush(User.create("trade-owner@finplay.com", "hash", "tradeowner", NOW));
		ownerAccount = accountRepository.saveAndFlush(
			Account.create(owner, com.finplay.api.account.domain.Market.STOCK, NOW));
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TRD01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, NOW));
	}

	@Test
	@DisplayName("주문 ID로 체결을 조회한다")
	void findsTradeByOrderIdWhenExists() {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "trade-idem-1", "j".repeat(64), NOW));
		Trade trade = tradeRepository.saveAndFlush(Trade.of(
			order, ownerAccount, instrument, OrderSide.BUY,
			BigDecimal.valueOf(100), BigDecimal.valueOf(10), 1_000L, 1L, null, NOW, NOW));

		var result = tradeRepository.findByOrderId(order.getId());

		assertThat(result).isPresent();
		assertThat(result.get().getId()).isEqualTo(trade.getId());
	}

	@Test
	@DisplayName("체결이 없는 주문 ID는 빈 값을 반환한다")
	void findByOrderIdReturnsEmptyWhenNotFound() {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "trade-idem-2", "k".repeat(64), NOW));

		var result = tradeRepository.findByOrderId(order.getId());

		assertThat(result).isEmpty();
	}
}
