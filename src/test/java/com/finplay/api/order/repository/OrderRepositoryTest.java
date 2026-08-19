// 계좌 단위 커서 조회·idempotencyKey 조회 쿼리 메서드를 검증하는 슬라이스 테스트 (docs/specs/018-order-list-pagination)
package com.finplay.api.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.education.priceruntime.domain.PracticePriceSession;
import com.finplay.api.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
	private PracticePriceSessionRepository practicePriceSessionRepository;

	@Autowired
	private PracticeAttemptRepository practiceAttemptRepository;

	@Autowired
	private EntityManager entityManager;

	private User owner;
	private Account ownerAccount;
	private Instrument instrument;
	private int idempotencySequence = 0;

	@BeforeEach
	void setUp() {
		owner = userRepository.saveAndFlush(User.create("owner@finplay.com", "hash", "owner", NOW));
		ownerAccount = accountRepository.saveAndFlush(
			Account.create(owner, com.finplay.api.account.domain.Market.STOCK, NOW));
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "TEST01", "테스트종목", BigDecimal.valueOf(100), 10_000L, true, NOW));
	}

	private Order createOrder(User user, Account account, LocalDateTime requestedAt) {
		idempotencySequence++;
		char hashChar = (char)('a' + idempotencySequence);
		return orderRepository.saveAndFlush(Order.create(
			user, account, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "cursor-order-idem-" + idempotencySequence,
			String.valueOf(hashChar).repeat(64), requestedAt));
	}

	// LMT-004(이슈 #235): 미체결(PENDING) 지정가 주문을 생성한다.
	private Order createPendingOrder(User user, Account account, LocalDateTime requestedAt) {
		idempotencySequence++;
		char hashChar = (char)('a' + idempotencySequence);
		return orderRepository.saveAndFlush(Order.createLimitPending(
			user, account, instrument, OrderSide.BUY,
			BigDecimal.valueOf(1), BigDecimal.valueOf(70_000),
			"cursor-pending-idem-" + idempotencySequence,
			String.valueOf(hashChar).repeat(64), requestedAt));
	}

	private Order createCancelledOrder(User user, Account account, LocalDateTime requestedAt) {
		Order order = createPendingOrder(user, account, requestedAt);
		order.cancel();
		return orderRepository.saveAndFlush(order);
	}

	@Test
	@DisplayName("동일 사용자·동일 idempotencyKey의 주문을 조회한다 (이슈 #22)")
	void findsOrderByUserIdAndIdempotencyKeyWhenExists() {
		Order order = orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "idem-exists", "h".repeat(64), NOW));

		var result = orderRepository.findByUserIdAndIdempotencyKey(owner.getId(), "idem-exists");

		assertThat(result).isPresent();
		assertThat(result.get().getId()).isEqualTo(order.getId());
		assertThat(result.get().getInstrument().getSymbol()).isEqualTo(instrument.getSymbol());
	}

	@Test
	@DisplayName("존재하지 않는 idempotencyKey는 빈 값을 반환한다 (이슈 #22)")
	void findByUserIdAndIdempotencyKeyReturnsEmptyWhenNotFound() {
		var result = orderRepository.findByUserIdAndIdempotencyKey(owner.getId(), "no-such-key");

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("다른 사용자가 동일한 idempotencyKey를 써도 조회되지 않는다 (이슈 #22)")
	void findByUserIdAndIdempotencyKeyDoesNotLeakAcrossUsers() {
		User other = userRepository.saveAndFlush(User.create("other2@finplay.com", "hash", "other2", NOW));
		Account otherAccount = accountRepository.saveAndFlush(
			Account.create(other, com.finplay.api.account.domain.Market.STOCK, NOW));
		orderRepository.saveAndFlush(Order.create(
			other, otherAccount, instrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "shared-idem", "i".repeat(64), NOW));

		var result = orderRepository.findByUserIdAndIdempotencyKey(owner.getId(), "shared-idem");

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("다른 계좌의 주문은 제외하고 계좌 단위로 커서 조회한다")
	void findByAccountIdWithCursorExcludesOtherAccountOrders() {
		User other = userRepository.saveAndFlush(User.create("cursor-other@finplay.com", "hash", "cursorother", NOW));
		Account otherAccount = accountRepository.saveAndFlush(
			Account.create(other, com.finplay.api.account.domain.Market.STOCK, NOW));

		Order ownerOrder = createOrder(owner, ownerAccount, NOW);
		createOrder(other, otherAccount, NOW);

		List<Order> result = orderRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);

		assertThat(result).extracting(Order::getId).containsExactly(ownerOrder.getId());
	}

	// 포트폴리오 화면(체결·주문 내역)에 튜토리얼 샌드박스 종목 주문이 섞여 나오던 누출 수정 —
	// 033-exclude-tutorial-sandbox-data(SANDBOX-EXCL-001)와 동일 원칙을 GET /api/orders,
	// GET /api/orders/pending에도 적용한다.
	@Test
	@DisplayName("샌드박스 종목 주문은 제외하고 실제 종목 주문만 커서 조회한다")
	void findByAccountIdWithCursorExcludesSandboxInstrumentOrders() {
		Instrument sandboxInstrument = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_1")
			.orElseThrow();
		assertThat(sandboxInstrument.isTutorialSample()).isTrue();

		Order realOrder = createOrder(owner, ownerAccount, NOW);
		idempotencySequence++;
		orderRepository.saveAndFlush(Order.create(
			owner, ownerAccount, sandboxInstrument, OrderSide.BUY, OrderType.MARKET,
			BigDecimal.valueOf(10), "cursor-sandbox-idem-" + idempotencySequence,
			String.valueOf((char)('a' + idempotencySequence)).repeat(64), NOW));

		List<Order> result = orderRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);

		assertThat(result).extracting(Order::getId).containsExactly(realOrder.getId());
	}

	@Test
	@DisplayName("requestedAt 내림차순, 동시각이면 id 내림차순으로 정렬해 커서 조회한다")
	void findByAccountIdWithCursorSortedByRequestedAtThenIdDescending() {
		Order older = createOrder(owner, ownerAccount, NOW.minusMinutes(10));
		Order sameTimeFirst = createOrder(owner, ownerAccount, NOW);
		Order sameTimeSecond = createOrder(owner, ownerAccount, NOW);

		List<Order> result = orderRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);

		assertThat(result).extracting(Order::getId)
			.containsExactly(sameTimeSecond.getId(), sameTimeFirst.getId(), older.getId());
	}

	@Test
	@DisplayName("커서로 연속 조회한 결과가 커서 없이 한 번에 조회한 전체 결과와 중복·누락 없이 일치한다")
	void cursorPaginationMatchesFullResultWithoutDuplicatesOrGaps() {
		List<Order> created = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			created.add(createOrder(owner, ownerAccount, NOW.minusMinutes(i)));
		}

		List<Order> fullResult = orderRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);
		assertThat(fullResult).hasSize(5);

		List<Order> firstPage = orderRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 3);
		Order lastOfFirstPage = firstPage.get(firstPage.size() - 1);
		List<Order> secondPage = orderRepository.findByAccountIdWithCursor(
			ownerAccount.getId(), lastOfFirstPage.getRequestedAt(), lastOfFirstPage.getId(), 3);

		List<Long> pagedIds = new ArrayList<>();
		firstPage.forEach(order -> pagedIds.add(order.getId()));
		secondPage.forEach(order -> pagedIds.add(order.getId()));

		assertThat(pagedIds).hasSize(5).doesNotHaveDuplicates();
		assertThat(pagedIds).containsExactlyElementsOf(fullResult.stream().map(Order::getId).toList());
	}

	@Test
	@DisplayName("동일 requestedAt 그룹 안에서 페이지가 나뉘어도 id 내림차순 커서로 중복·누락 없이 이어받는다")
	void cursorPaginationSplitsWithinSameRequestedAtGroupWithoutDuplicatesOrGaps() {
		Order order1 = createOrder(owner, ownerAccount, NOW);
		Order order2 = createOrder(owner, ownerAccount, NOW);
		Order order3 = createOrder(owner, ownerAccount, NOW);
		Order order4 = createOrder(owner, ownerAccount, NOW);

		List<Order> fullResult = orderRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);
		assertThat(fullResult).extracting(Order::getId)
			.containsExactly(order4.getId(), order3.getId(), order2.getId(), order1.getId());

		List<Order> firstPage = orderRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 2);
		Order lastOfFirstPage = firstPage.get(firstPage.size() - 1);
		List<Order> secondPage = orderRepository.findByAccountIdWithCursor(
			ownerAccount.getId(), lastOfFirstPage.getRequestedAt(), lastOfFirstPage.getId(), 2);

		List<Long> pagedIds = new ArrayList<>();
		firstPage.forEach(order -> pagedIds.add(order.getId()));
		secondPage.forEach(order -> pagedIds.add(order.getId()));

		assertThat(pagedIds).hasSize(4).doesNotHaveDuplicates();
		assertThat(pagedIds).containsExactlyElementsOf(fullResult.stream().map(Order::getId).toList());
	}

	@Test
	@DisplayName("JOIN FETCH로 instrument를 함께 조회해 지연 로딩 예외 없이 접근할 수 있다")
	void findByAccountIdWithCursorFetchesInstrumentWithoutLazyInitException() {
		createOrder(owner, ownerAccount, NOW);
		entityManager.clear();

		List<Order> result = orderRepository.findByAccountIdWithCursor(ownerAccount.getId(), null, null, 10);

		assertThat(result).extracting(order -> order.getInstrument().getSymbol())
			.containsExactly(instrument.getSymbol());
	}

	@Test
	@DisplayName("id로 락 조회하면 해당 주문이 반환된다 (015-limit-order LMT-002)")
	void findByIdForUpdateReturnsTheOrderById() {
		Order order = createOrder(owner, ownerAccount, NOW);

		var result = orderRepository.findByIdForUpdate(order.getId());

		assertThat(result).isPresent();
		assertThat(result.get().getId()).isEqualTo(order.getId());
	}

	@Test
	@DisplayName("존재하지 않는 id로 락 조회하면 빈 값을 반환한다")
	void findByIdForUpdateReturnsEmptyWhenNotFound() {
		var result = orderRepository.findByIdForUpdate(999_999L);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("BUY 지정가는 limitPrice <= 현재가일 때 체결 후보로 반환된다")
	void findPendingLimitOrdersToFillReturnsBuyOrderWhenLimitPriceLessThanOrEqualToCurrentPrice() {
		Order buyOrder = orderRepository.saveAndFlush(Order.createLimitPending(
			owner, ownerAccount, instrument, OrderSide.BUY,
			BigDecimal.valueOf(1), BigDecimal.valueOf(70_000), "limit-buy-1", "j".repeat(64), NOW));

		List<Order> result = orderRepository
			.findPendingLimitOrdersToFill(instrument.getId(), BigDecimal.valueOf(70_000));

		assertThat(result).extracting(Order::getId).containsExactly(buyOrder.getId());
	}

	@Test
	@DisplayName("BUY 지정가는 limitPrice가 현재가보다 낮으면 체결 후보에서 제외된다")
	void findPendingLimitOrdersToFillExcludesBuyOrderWhenLimitPriceBelowCurrentPrice() {
		orderRepository.saveAndFlush(Order.createLimitPending(
			owner, ownerAccount, instrument, OrderSide.BUY,
			BigDecimal.valueOf(1), BigDecimal.valueOf(69_000), "limit-buy-2", "k".repeat(64), NOW));

		List<Order> result = orderRepository
			.findPendingLimitOrdersToFill(instrument.getId(), BigDecimal.valueOf(70_000));

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("SELL 지정가는 limitPrice >= 현재가일 때 체결 후보로 반환된다")
	void findPendingLimitOrdersToFillReturnsSellOrderWhenLimitPriceGreaterThanOrEqualToCurrentPrice() {
		Order sellOrder = orderRepository.saveAndFlush(Order.createLimitPending(
			owner, ownerAccount, instrument, OrderSide.SELL,
			BigDecimal.valueOf(1), BigDecimal.valueOf(70_000), "limit-sell-1", "l".repeat(64), NOW));

		List<Order> result = orderRepository
			.findPendingLimitOrdersToFill(instrument.getId(), BigDecimal.valueOf(70_000));

		assertThat(result).extracting(Order::getId).containsExactly(sellOrder.getId());
	}

	@Test
	@DisplayName("이미 FILLED된 지정가 주문은 체결 후보에서 제외된다")
	void findPendingLimitOrdersToFillExcludesAlreadyFilledOrder() {
		Order filledOrder = orderRepository.saveAndFlush(Order.createLimitPending(
			owner, ownerAccount, instrument, OrderSide.BUY,
			BigDecimal.valueOf(1), BigDecimal.valueOf(70_000), "limit-buy-3", "m".repeat(64), NOW));
		filledOrder.markFilled();
		orderRepository.saveAndFlush(filledOrder);

		List<Order> result = orderRepository
			.findPendingLimitOrdersToFill(instrument.getId(), BigDecimal.valueOf(70_000));

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("requestedAt 오름차순, 동시각이면 id 오름차순으로 정렬된다")
	void findPendingLimitOrdersToFillSortedByRequestedAtThenIdAscending() {
		Order older = orderRepository.saveAndFlush(Order.createLimitPending(
			owner, ownerAccount, instrument, OrderSide.BUY,
			BigDecimal.valueOf(1), BigDecimal.valueOf(70_000), "limit-buy-4", "n".repeat(64), NOW.minusMinutes(10)));
		Order sameTimeFirst = orderRepository.saveAndFlush(Order.createLimitPending(
			owner, ownerAccount, instrument, OrderSide.BUY,
			BigDecimal.valueOf(1), BigDecimal.valueOf(70_000), "limit-buy-5", "o".repeat(64), NOW));
		Order sameTimeSecond = orderRepository.saveAndFlush(Order.createLimitPending(
			owner, ownerAccount, instrument, OrderSide.BUY,
			BigDecimal.valueOf(1), BigDecimal.valueOf(70_000), "limit-buy-6", "p".repeat(64), NOW));

		List<Order> result = orderRepository
			.findPendingLimitOrdersToFill(instrument.getId(), BigDecimal.valueOf(70_000));

		assertThat(result).extracting(Order::getId)
			.containsExactly(older.getId(), sameTimeFirst.getId(), sameTimeSecond.getId());
	}

	@Test
	@DisplayName("PENDING 상태로만 필터링해 FILLED·CANCELLED 주문을 제외한다 (LMT-004, 이슈 #235)")
	void findByAccountIdAndStatusWithCursorFiltersOnlyMatchingStatus() {
		Order pendingOrder = createPendingOrder(owner, ownerAccount, NOW);
		createOrder(owner, ownerAccount, NOW.minusMinutes(1)); // FILLED
		createCancelledOrder(owner, ownerAccount, NOW.minusMinutes(2)); // CANCELLED

		List<Order> result = orderRepository.findByAccountIdAndStatusWithCursor(
			ownerAccount.getId(), com.finplay.api.order.domain.OrderStatus.PENDING, null, null, 10);

		assertThat(result).extracting(Order::getId).containsExactly(pendingOrder.getId());
		assertThat(result).extracting(Order::getStatus)
			.containsOnly(com.finplay.api.order.domain.OrderStatus.PENDING);
	}

	@Test
	@DisplayName("다른 계좌의 PENDING 주문은 제외하고 계좌 단위로 조회한다 (LMT-004, 이슈 #235)")
	void findByAccountIdAndStatusWithCursorExcludesOtherAccountOrders() {
		User other = userRepository.saveAndFlush(
			User.create("status-cursor-other@finplay.com", "hash", "statuscursorother", NOW));
		Account otherAccount = accountRepository.saveAndFlush(
			Account.create(other, com.finplay.api.account.domain.Market.STOCK, NOW));

		Order ownerPending = createPendingOrder(owner, ownerAccount, NOW);
		createPendingOrder(other, otherAccount, NOW);

		List<Order> result = orderRepository.findByAccountIdAndStatusWithCursor(
			ownerAccount.getId(), com.finplay.api.order.domain.OrderStatus.PENDING, null, null, 10);

		assertThat(result).extracting(Order::getId).containsExactly(ownerPending.getId());
	}

	@Test
	@DisplayName("샌드박스 종목 PENDING 주문은 제외하고 실제 종목 PENDING 주문만 조회한다")
	void findByAccountIdAndStatusWithCursorExcludesSandboxInstrumentOrders() {
		Instrument sandboxInstrument = instrumentRepository.findByMarketAndSymbol(Market.STOCK, "SANDBOX_STK_1")
			.orElseThrow();
		assertThat(sandboxInstrument.isTutorialSample()).isTrue();

		Order realPending = createPendingOrder(owner, ownerAccount, NOW);
		idempotencySequence++;
		orderRepository.saveAndFlush(Order.createLimitPending(
			owner, ownerAccount, sandboxInstrument, OrderSide.BUY,
			BigDecimal.valueOf(1), BigDecimal.valueOf(70_000),
			"cursor-sandbox-pending-idem-" + idempotencySequence,
			String.valueOf((char)('a' + idempotencySequence)).repeat(64), NOW));

		List<Order> result = orderRepository.findByAccountIdAndStatusWithCursor(
			ownerAccount.getId(), com.finplay.api.order.domain.OrderStatus.PENDING, null, null, 10);

		assertThat(result).extracting(Order::getId).containsExactly(realPending.getId());
	}

	@Test
	@DisplayName("requestedAt 내림차순, 동시각이면 id 내림차순으로 PENDING 주문을 정렬한다 (LMT-004, 이슈 #235)")
	void findByAccountIdAndStatusWithCursorSortedByRequestedAtThenIdDescending() {
		Order older = createPendingOrder(owner, ownerAccount, NOW.minusMinutes(10));
		Order sameTimeFirst = createPendingOrder(owner, ownerAccount, NOW);
		Order sameTimeSecond = createPendingOrder(owner, ownerAccount, NOW);

		List<Order> result = orderRepository.findByAccountIdAndStatusWithCursor(
			ownerAccount.getId(), com.finplay.api.order.domain.OrderStatus.PENDING, null, null, 10);

		assertThat(result).extracting(Order::getId)
			.containsExactly(sameTimeSecond.getId(), sameTimeFirst.getId(), older.getId());
	}

	@Test
	@DisplayName("커서로 연속 조회한 PENDING 결과가 커서 없이 조회한 전체 결과와 중복·누락 없이 일치한다 (LMT-004, 이슈 #235)")
	void findByAccountIdAndStatusWithCursorPaginatesWithoutDuplicatesOrGaps() {
		// 페이지 경계를 동시각 위에 떨어뜨려 커서 WHERE 절의 동점 분기
		// (requestedAt = cursor AND id < cursorId)까지 실행시킨다 (PR #237 리뷰).
		// 정렬 결과는 NOW, NOW-1m, sameTimeSecond, sameTimeFirst, oldest 순이고 limit 3이 동점 쌍을 가른다.
		createPendingOrder(owner, ownerAccount, NOW);
		createPendingOrder(owner, ownerAccount, NOW.minusMinutes(1));
		Order sameTimeFirst = createPendingOrder(owner, ownerAccount, NOW.minusMinutes(2));
		Order sameTimeSecond = createPendingOrder(owner, ownerAccount, NOW.minusMinutes(2));
		Order oldest = createPendingOrder(owner, ownerAccount, NOW.minusMinutes(4));
		// 미체결 목록에 섞이면 안 되는 FILLED·CANCELLED 주문도 함께 만든다.
		createOrder(owner, ownerAccount, NOW);
		createCancelledOrder(owner, ownerAccount, NOW);

		List<Order> fullResult = orderRepository.findByAccountIdAndStatusWithCursor(
			ownerAccount.getId(), com.finplay.api.order.domain.OrderStatus.PENDING, null, null, 10);
		assertThat(fullResult).hasSize(5);

		List<Order> firstPage = orderRepository.findByAccountIdAndStatusWithCursor(
			ownerAccount.getId(), com.finplay.api.order.domain.OrderStatus.PENDING, null, null, 3);
		Order lastOfFirstPage = firstPage.get(firstPage.size() - 1);
		// 경계가 실제로 동점 위에 있는지 못박는다 — 픽스처가 흔들리면 동점 분기가 다시 죽는다.
		assertThat(lastOfFirstPage.getId()).isEqualTo(sameTimeSecond.getId());
		assertThat(sameTimeFirst.getRequestedAt()).isEqualTo(lastOfFirstPage.getRequestedAt());

		List<Order> secondPage = orderRepository.findByAccountIdAndStatusWithCursor(
			ownerAccount.getId(), com.finplay.api.order.domain.OrderStatus.PENDING,
			lastOfFirstPage.getRequestedAt(), lastOfFirstPage.getId(), 3);
		// 동점 분기가 없으면 같은 시각의 sameTimeFirst가 통째로 누락된다.
		assertThat(secondPage).extracting(Order::getId)
			.containsExactly(sameTimeFirst.getId(), oldest.getId());

		List<Long> pagedIds = new ArrayList<>();
		firstPage.forEach(order -> pagedIds.add(order.getId()));
		secondPage.forEach(order -> pagedIds.add(order.getId()));

		assertThat(pagedIds).hasSize(5).doesNotHaveDuplicates();
		assertThat(pagedIds).containsExactlyElementsOf(fullResult.stream().map(Order::getId).toList());
	}

	@Test
	@DisplayName("JOIN FETCH로 instrument를 함께 조회해 지연 로딩 예외 없이 접근할 수 있다 (LMT-004, 이슈 #235)")
	void findByAccountIdAndStatusWithCursorFetchesInstrumentWithoutLazyInitException() {
		createPendingOrder(owner, ownerAccount, NOW);
		entityManager.clear();

		List<Order> result = orderRepository.findByAccountIdAndStatusWithCursor(
			ownerAccount.getId(), com.finplay.api.order.domain.OrderStatus.PENDING, null, null, 10);

		assertThat(result).extracting(order -> order.getInstrument().getSymbol())
			.containsExactly(instrument.getSymbol());
	}

	// 이 이름의 idempotency 접두사는 030의 practice 전용 주문을 다른 테스트의 지정가 주문과 겹치지 않게 구분한다.
	private Order createPracticePendingOrder(
		User user, Account account, BigDecimal limitPrice, Long practicePriceSessionId, String idempotencySuffix) {
		return orderRepository.saveAndFlush(Order.createPracticeLimitPendingBuy(
			user, account, instrument, BigDecimal.valueOf(1), limitPrice, practicePriceSessionId,
			"practice-idem-" + idempotencySuffix, "q".repeat(64), NOW));
	}

	// orders.practice_price_session_id는 practice_price_sessions(id) FK다(V30) — 존재하는 세션 행이 있어야 한다.
	// 같은 owner·instrument로 여러 세션을 만들어야 하므로 ACTIVE 유일 제약(UNIQUE user_id,instrument_id,active_slot)에
	// 걸리지 않게 생성 직후 바로 완료 처리한다 — 이 테스트는 세션 상태가 아니라 주문 FK·조회만 검증한다.
	private Long createPracticeSession(long seed) {
		PracticePriceSession session = practicePriceSessionRepository.saveAndFlush(
			PracticePriceSession.create(
				owner.getId(), instrument.getId(), seed, (short)1, BigDecimal.valueOf(10_000), NOW));
		session.complete(NOW);
		return practicePriceSessionRepository.saveAndFlush(session).getId();
	}

	@Test
	@DisplayName("역방향 오염 차단(030): 지정가가 일치해도 교육 세션 귀속 주문은 실제 시세 체결 후보에서 제외된다")
	void findPendingLimitOrdersToFillExcludesPracticeSessionOrdersEvenWhenLimitPriceMatches() {
		Order normalOrder = orderRepository.saveAndFlush(Order.createLimitPending(
			owner, ownerAccount, instrument, OrderSide.BUY,
			BigDecimal.valueOf(1), BigDecimal.valueOf(70_000), "limit-normal-1", "r".repeat(64), NOW));
		Long sessionId = createPracticeSession(1L);
		createPracticePendingOrder(owner, ownerAccount, BigDecimal.valueOf(70_000), sessionId, "1");

		List<Order> result = orderRepository
			.findPendingLimitOrdersToFill(instrument.getId(), BigDecimal.valueOf(70_000));

		assertThat(result).extracting(Order::getId).containsExactly(normalOrder.getId());
	}

	@Test
	@DisplayName("세션에 PENDING 교육 주문이 있으면 existsByPracticePriceSessionIdAndStatus가 true를 반환한다 (030)")
	void existsByPracticePriceSessionIdAndStatusReturnsTrueWhenPendingOrderExistsForSession() {
		Long sessionId = createPracticeSession(2L);
		createPracticePendingOrder(owner, ownerAccount, BigDecimal.valueOf(70_000), sessionId, "2");

		boolean result = orderRepository.existsByPracticePriceSessionIdAndStatus(
			sessionId, com.finplay.api.order.domain.OrderStatus.PENDING);

		assertThat(result).isTrue();
	}

	@Test
	@DisplayName("다른 세션이거나 PENDING이 아니면 existsByPracticePriceSessionIdAndStatus가 false를 반환한다 (030)")
	void existsByPracticePriceSessionIdAndStatusReturnsFalseWhenNoMatchingPendingOrderForSession() {
		Long sessionId = createPracticeSession(3L);
		Long otherSessionId = createPracticeSession(4L);
		Order cancelled = createPracticePendingOrder(owner, ownerAccount, BigDecimal.valueOf(70_000), sessionId, "3");
		cancelled.cancel();
		orderRepository.saveAndFlush(cancelled);
		createPracticePendingOrder(owner, ownerAccount, BigDecimal.valueOf(70_000), otherSessionId, "4"); // 다른 세션

		boolean result = orderRepository.existsByPracticePriceSessionIdAndStatus(
			sessionId, com.finplay.api.order.domain.OrderStatus.PENDING);

		assertThat(result).isFalse();
	}

	@Test
	@DisplayName("findPendingBySessionIdForUpdate는 해당 세션의 PENDING 주문만 id 오름차순으로 반환한다 (030)")
	void findPendingBySessionIdForUpdateReturnsOnlyThatSessionsPendingOrdersSortedByIdAscending() {
		Long sessionId = createPracticeSession(5L);
		Long otherSessionId = createPracticeSession(6L);
		Order first = createPracticePendingOrder(owner, ownerAccount, BigDecimal.valueOf(70_000), sessionId, "5");
		Order second = createPracticePendingOrder(owner, ownerAccount, BigDecimal.valueOf(71_000), sessionId, "6");
		Order otherSession = createPracticePendingOrder(owner, ownerAccount, BigDecimal.valueOf(70_000), otherSessionId,
			"7");
		Order filledInSameSession = createPracticePendingOrder(owner, ownerAccount, BigDecimal.valueOf(70_000),
			sessionId, "8");
		filledInSameSession.markFilled();
		orderRepository.saveAndFlush(filledInSameSession);

		List<Order> result = orderRepository.findPendingBySessionIdForUpdate(sessionId);

		assertThat(result).extracting(Order::getId).containsExactly(first.getId(), second.getId());
		assertThat(result).extracting(Order::getId).doesNotContain(otherSession.getId());
	}

	// 튜토리얼 attempt 전용 주문 조회(043) — 다른 run/attempt/일반 주문 제외, 정렬, fetch join 검증.
	// orders.practice_attempt_id는 practice_attempts(id) FK다(V38) — 존재하는 attempt 행이 있어야 한다.
	// uk_practice_attempts_user_market(user_id, market) 유일 제약 때문에 "다른 attempt"는 다른 market으로 만든다.
	private Long createPracticeAttempt(Market market) {
		return practiceAttemptRepository.saveAndFlush(
			com.finplay.api.education.marketpractice.domain.PracticeAttempt.create(owner.getId(), market, NOW))
			.getId();
	}

	private Order createPracticeRunPendingOrder(Long attemptId, long runNumber, String idempotencySuffix) {
		idempotencySequence++;
		char hashChar = (char)('a' + idempotencySequence);
		return orderRepository.saveAndFlush(Order.createLimitPendingForPracticeAttempt(
			owner, ownerAccount, instrument, OrderSide.BUY,
			BigDecimal.valueOf(1), BigDecimal.valueOf(70_000),
			attemptId, runNumber,
			"practice-run-idem-" + idempotencySuffix,
			String.valueOf(hashChar).repeat(64), NOW));
	}

	private Order createPracticeRunFilledOrder(Long attemptId, long runNumber, String idempotencySuffix) {
		idempotencySequence++;
		char hashChar = (char)('a' + idempotencySequence);
		return orderRepository.saveAndFlush(Order.createForPracticeAttempt(
			owner, ownerAccount, instrument, OrderSide.SELL, OrderType.MARKET,
			BigDecimal.valueOf(2), attemptId, runNumber,
			"practice-run-idem-" + idempotencySuffix,
			String.valueOf(hashChar).repeat(64), NOW));
	}

	@Test
	@DisplayName("현재 attempt·run 주문만 id 오름차순으로 반환하고 이전 run·다른 attempt·일반 주문은 제외한다 (043)")
	void findPracticeRunOrdersReturnsOnlyCurrentAttemptAndRunOrdersSortedByIdAscending() {
		Long attemptId = createPracticeAttempt(Market.STOCK);
		Long otherAttemptId = createPracticeAttempt(Market.CRYPTO);

		Order currentRunOrder1 = createPracticeRunPendingOrder(attemptId, 2L, "1");
		Order currentRunOrder2 = createPracticeRunFilledOrder(attemptId, 2L, "2");
		createPracticeRunFilledOrder(attemptId, 1L, "3"); // 같은 attempt의 이전 run
		createPracticeRunFilledOrder(otherAttemptId, 2L, "4"); // 다른 attempt, 같은 run 번호
		createOrder(owner, ownerAccount, NOW); // 일반 주문(practiceAttemptId=null)

		List<Order> result = orderRepository.findPracticeRunOrders(attemptId, 2L);

		assertThat(result).extracting(Order::getId)
			.containsExactly(currentRunOrder1.getId(), currentRunOrder2.getId());
	}

	@Test
	@DisplayName("귀속된 주문이 없으면 빈 목록을 반환한다 (043)")
	void findPracticeRunOrdersReturnsEmptyListWhenNoMatchingOrders() {
		Long attemptId = createPracticeAttempt(Market.STOCK);

		List<Order> result = orderRepository.findPracticeRunOrders(attemptId, 1L);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("JOIN FETCH로 instrument를 함께 조회해 지연 로딩 예외 없이 접근할 수 있다 (043)")
	void findPracticeRunOrdersFetchesInstrumentWithoutLazyInitException() {
		Long attemptId = createPracticeAttempt(Market.STOCK);
		createPracticeRunPendingOrder(attemptId, 1L, "5");
		entityManager.clear();

		List<Order> result = orderRepository.findPracticeRunOrders(attemptId, 1L);

		assertThat(result).extracting(order -> order.getInstrument().getSymbol())
			.containsExactly(instrument.getSymbol());
	}

	// 이슈 #441: 043 spec의 완료 조건 — "체결·취소된 뒤에는 같은 주문이 각각 FILLED·CANCELLED 상태로 계속 보인다".
	// 이 쿼리는 status로 필터링하지 않으므로 세 상태가 그대로 노출돼야 한다. 기존 케이스는 PENDING·FILLED만
	// 다뤄 CANCELLED가 비어 있었다.
	@Test
	@DisplayName("취소된 주문도 CANCELLED 상태 그대로 현재 attempt·run 조회 결과에 남는다 (043, 이슈 #441)")
	void findPracticeRunOrdersKeepsCancelledOrdersWithCancelledStatus() {
		Long attemptId = createPracticeAttempt(Market.STOCK);
		Order pending = createPracticeRunPendingOrder(attemptId, 1L, "6");
		Order cancelled = createPracticeRunPendingOrder(attemptId, 1L, "7");
		cancelled.cancel();
		orderRepository.saveAndFlush(cancelled);
		Order filled = createPracticeRunFilledOrder(attemptId, 1L, "8");
		entityManager.clear();

		List<Order> result = orderRepository.findPracticeRunOrders(attemptId, 1L);

		assertThat(result)
			.extracting(Order::getId, Order::getStatus)
			.containsExactly(
				tuple(pending.getId(), com.finplay.api.order.domain.OrderStatus.PENDING),
				tuple(cancelled.getId(), com.finplay.api.order.domain.OrderStatus.CANCELLED),
				tuple(filled.getId(), com.finplay.api.order.domain.OrderStatus.FILLED));
	}
}
