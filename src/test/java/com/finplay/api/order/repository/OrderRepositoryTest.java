// 사용자별 최신순 주문 조회 쿼리 메서드를 검증하는 슬라이스 테스트 (docs/specs/006-portfolio-query)
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
class OrderRepositoryTest {

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
	private EntityManager entityManager;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	private User owner;
	private Account ownerAccount;
	private Instrument instrument;

	@BeforeEach
	void setUp() {
		owner = userRepository.saveAndFlush(User.create("owner@finplay.com", "hash", "owner", NOW));
		ownerAccount = accountRepository.saveAndFlush(
			Account.create(owner, com.finplay.api.account.domain.Market.STOCK, NOW));
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, NOW));
	}

	@Test
	@DisplayName("다른 사용자의 주문은 제외하고 본인 주문만 반환한다")
	void returnsOnlyOwnerOrders() {
		User other = userRepository.saveAndFlush(User.create("other@finplay.com", "hash", "other", NOW));
		Account otherAccount = accountRepository.saveAndFlush(
			Account.create(other, com.finplay.api.account.domain.Market.STOCK, NOW));

		Order ownerOrder = orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "owner-idem-1", "a".repeat(64), NOW));
		orderRepository.saveAndFlush(Order.create(
			other, otherAccount, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "other-idem-1", "b".repeat(64), NOW));

		List<Order> result = orderRepository.findAllByUserIdOrderByRequestedAtDescIdDesc(owner.getId());

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getId()).isEqualTo(ownerOrder.getId());
	}

	@Test
	@DisplayName("requestedAt 내림차순, 동시각이면 id 내림차순으로 정렬해 반환한다")
	void ordersSortedByRequestedAtThenIdDescending() {
		Order older = orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(1), "sort-idem-1", "c".repeat(64), NOW.minusMinutes(10)));
		Order sameTimeFirst = orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(2), "sort-idem-2", "d".repeat(64), NOW));
		Order sameTimeSecond = orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, instrument, OrderSide.SELL, OrderType.MARKET,
			BigDecimal.valueOf(3), "sort-idem-3", "e".repeat(64), NOW));

		List<Order> result = orderRepository.findAllByUserIdOrderByRequestedAtDescIdDesc(owner.getId());

		assertThat(result).extracting(Order::getId)
			.containsExactly(sameTimeSecond.getId(), sameTimeFirst.getId(), older.getId());
	}

	@Test
	@DisplayName("주문이 없는 사용자는 빈 목록을 반환한다")
	void returnsEmptyListWhenNoOrders() {
		User noOrderUser = userRepository.saveAndFlush(User.create("noorder@finplay.com", "hash", "noorder", NOW));

		List<Order> result = orderRepository.findAllByUserIdOrderByRequestedAtDescIdDesc(noOrderUser.getId());

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("JOIN FETCH로 instrument를 함께 조회해 추가 쿼리 없이(N+1 없이) 접근할 수 있다")
	void findAllByUserIdFetchesInstrumentInOneQueryAfterPersistenceContextClear() {
		orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(1), "fetch-idem-1", "f".repeat(64), NOW.minusMinutes(1)));
		orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, instrument, OrderSide.SELL, OrderType.MARKET,
			BigDecimal.valueOf(2), "fetch-idem-2", "g".repeat(64), NOW));
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();

		List<Order> result = orderRepository.findAllByUserIdOrderByRequestedAtDescIdDesc(owner.getId());
		assertThat(result).extracting(order -> order.getInstrument().getSymbol())
			.containsExactly(instrument.getSymbol(), instrument.getSymbol());

		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
	}
}
