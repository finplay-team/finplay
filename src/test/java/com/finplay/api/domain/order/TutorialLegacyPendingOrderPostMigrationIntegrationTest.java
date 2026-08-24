// PR #452 리뷰 권장 1번: 047 배포 이전 코드로 생성돼 실제 계좌에 현금이 예약된 채로 남아 있는 샌드박스
// 지정가 매수 PENDING 주문이, 047 배포 이후(V46 적용 완료·isTutorialSample 분기 코드 적용 완료) 체결·취소될
// 때 실제로 깨지는지를 실제 서비스·MySQL(Testcontainers)로 검증한다. V46은 이제 배포 시점에 이미 존재하는
// 이런 주문을 정리한다(같은 커밋에서 추가된 후속 정리 로직, TutorialAccountBackfillMigrationTest 참고) —
// 이 테스트는 그 정리 로직이 없다면 어떤 실패가 나는지를 문서화하는 역할이다. 무언가 다른 경로로(예: 배포
// 중 구 코드 인스턴스가 만든 주문처럼 V46 적용 이후 새로 유입된 레거시 형태의 주문) 정리되지 않은 채 남으면
// 이 테스트가 재현하는 실패가 여전히 그대로 발생한다.
package com.finplay.api.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.account.repository.TutorialAccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.service.LimitOrderCancelService;
import com.finplay.api.domain.order.service.LimitOrderFillService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TutorialLegacyPendingOrderPostMigrationIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0, 0);
	// 수량 0.1 * 지정가 1,000,000 = 100,000, 수수료 floor(100,000*0.0005) = 50, 총 예약액 100,050.
	private static final long RESERVED_CASH = 100_050L;

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private TutorialAccountRepository tutorialAccountRepository;
	@Autowired
	private LimitOrderFillService limitOrderFillService;
	@Autowired
	private LimitOrderCancelService limitOrderCancelService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final Set<Long> userIds = new HashSet<>();
	private final Set<Long> instrumentIds = new HashSet<>();

	@AfterEach
	void tearDown() {
		for (Long userId : userIds) {
			jdbcTemplate.update("DELETE FROM tutorial_accounts WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM trades WHERE order_id IN (SELECT id FROM orders WHERE user_id = ?)",
				userId);
			jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM holdings WHERE account_id IN "
				+ "(SELECT id FROM accounts WHERE user_id = ?)", userId);
			jdbcTemplate.update("DELETE FROM accounts WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
		}
		instrumentIds.forEach(id -> jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", id));
		userIds.clear();
		instrumentIds.clear();
	}

	@Test
	void fillingLegacyPendingBuyOrderThrowsBecauseReservationStayedOnRealAccount() {
		User user = createUser("legacy-fill");
		Account account = createAccount(user);
		Instrument instrument = createTutorialSampleCryptoInstrument("legacy-fill");
		Order legacyOrder = seedLegacyPendingBuyOrder(user, account, instrument);

		assertThatThrownBy(() -> limitOrderFillService.fillIfPending(legacyOrder.getId()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("튜토리얼 계좌에서 예약된 금액보다 큰 금액을 확정할 수 없습니다");

		// 체결이 예외로 중단되며 트랜잭션 전체가 롤백된다 — 실제 계좌 예약은 풀리지 않고 그대로 남는다
		// (권장 1번이 우려한 "예약이 영구히 묶인다"는 상태 그 자체).
		assertThat(accountRepository.findById(account.getId()).orElseThrow().getReservedCash())
			.isEqualTo(RESERVED_CASH);
		assertThat(orderRepository.findById(legacyOrder.getId()).orElseThrow().getStatus().name())
			.isEqualTo("PENDING");
	}

	@Test
	void cancellingLegacyPendingBuyOrderThrowsForTheSameReason() {
		User user = createUser("legacy-cancel");
		Account account = createAccount(user);
		Instrument instrument = createTutorialSampleCryptoInstrument("legacy-cancel");
		Order legacyOrder = seedLegacyPendingBuyOrder(user, account, instrument);

		assertThatThrownBy(() -> limitOrderCancelService.cancelOrder(user.getId(), legacyOrder.getId()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("튜토리얼 계좌에서 예약된 금액보다 큰 금액을 해제할 수 없습니다");

		assertThat(accountRepository.findById(account.getId()).orElseThrow().getReservedCash())
			.isEqualTo(RESERVED_CASH);
		assertThat(orderRepository.findById(legacyOrder.getId()).orElseThrow().getStatus().name())
			.isEqualTo("PENDING");
	}

	// 047 배포 이전 PracticeLimitOrderCreationService/LimitOrderCreationService가 실제 Account에
	// reserveCash를 걸고 만든 PENDING 지정가 매수 주문을 그대로 재현한다 — 생성 서비스를 거치지 않고
	// 계좌·주문을 직접 심어, tutorial_accounts 행이 아직 하나도 없는 배포 직후 상태를 흉내낸다.
	private Order seedLegacyPendingBuyOrder(User user, Account account, Instrument instrument) {
		account.reserveCash(RESERVED_CASH);
		accountRepository.saveAndFlush(account);
		return orderRepository.saveAndFlush(Order.createLimitPending(
			user, account, instrument, OrderSide.BUY, new BigDecimal("0.1"), new BigDecimal("1000000"),
			"legacy-" + UUID.randomUUID(), "a".repeat(64), NOW));
	}

	private User createUser(String scenario) {
		User user = userRepository.saveAndFlush(User.create(
			uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), NOW));
		userIds.add(user.getId());
		return user;
	}

	private Account createAccount(User user) {
		return accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, NOW));
	}

	private Instrument createTutorialSampleCryptoInstrument(String scenario) {
		Instrument instrument = Instrument.create(
			Market.CRYPTO, "T" + shortRandom(), scenario, BigDecimal.ONE, 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		instrumentRepository.saveAndFlush(instrument);
		instrumentIds.add(instrument.getId());
		return instrument;
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + shortRandom() + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + shortRandom();
	}

	private static String shortRandom() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
	}
}
