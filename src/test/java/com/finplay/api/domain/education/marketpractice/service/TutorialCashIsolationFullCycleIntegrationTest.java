// 047 tasks.md 8번(이슈 #450 재현 시나리오 역-검증): 실제 서비스·MySQL(Testcontainers)로 매수→관찰→매도→재시작을
// 여러 차례 반복해도 실제 Account.cashBalance·reservedCash·realizedPnl이 튜토리얼 진입 이전 값과 정확히 동일하게
// 유지되는지 확인하는 통합 테스트다. 다른 047 테스트들과 달리 Order/Trade/Holding을 직접 만들지 않고
// PracticeAttemptService(진입·종목선택)·OrderService(시장가 매수·매도)·PracticeHoldingObservationService(관찰)·
// PracticeAttemptRestartService(재시작) 네 서비스를 실제 이슈 #450 재현 순서 그대로 호출한다 — 이슈 #450 자체가
// "샌드박스 매매·재시작 반복이 실제 현금을 무제한으로 불릴 수 있다"는 결함이었으므로, 이 테스트는 그 반복 자체를
// 실제 API 진입점 수준에서 재현해 격리가 끝까지 유지되는지를 블랙박스에 가깝게 검증하는 것이 목적이다.
package com.finplay.api.domain.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.account.repository.TutorialAccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.dto.request.OrderCreateRequest;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.service.OrderService;
import com.finplay.api.domain.portfolio.entity.Holding;
import com.finplay.api.domain.portfolio.repository.HoldingRepository;
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
class TutorialCashIsolationFullCycleIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0, 0);
	private static final long INITIAL_TUTORIAL_CASH = 10_000_000L;
	private static final int CYCLES = 3;

	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private TutorialAccountRepository tutorialAccountRepository;
	@Autowired
	private PracticeAttemptService practiceAttemptService;
	@Autowired
	private PracticeAttemptRestartService practiceAttemptRestartService;
	@Autowired
	private PracticeHoldingObservationService practiceHoldingObservationService;
	@Autowired
	private OrderService orderService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final Set<Long> userIds = new HashSet<>();
	private final Set<Long> instrumentIds = new HashSet<>();

	// FK 역순(ExitPlanTriggerFillIntegrationTest·TutorialSandboxSellCashIsolationIntegrationTest와 동일 관례) +
	// 이 테스트가 추가로 만드는 practice_market_observations(holding_id FK)·practice_risk_snapshots(attempt_id·
	// buy_trade_id FK)·practice_attempts(user_id·instrument_id FK) 세 테이블을 그 관례 앞뒤에 끼워 넣는다.
	@AfterEach
	void tearDown() {
		for (Long userId : userIds) {
			jdbcTemplate.update("DELETE FROM practice_market_observations WHERE user_id = ?", userId);
			jdbcTemplate.update(
				"DELETE FROM practice_risk_snapshots WHERE attempt_id IN "
					+ "(SELECT id FROM practice_attempts WHERE user_id = ?)",
				userId);
			jdbcTemplate.update("DELETE FROM tutorial_accounts WHERE user_id = ?", userId);
		}
		for (Long userId : userIds) {
			jdbcTemplate.update("DELETE FROM trade_allocations WHERE holding_lot_id IN "
				+ "(SELECT id FROM holding_lots WHERE holding_id IN "
				+ "(SELECT id FROM holdings WHERE account_id IN "
				+ "(SELECT id FROM accounts WHERE user_id = ?)))", userId);
			jdbcTemplate.update("DELETE FROM holding_lots WHERE holding_id IN "
				+ "(SELECT id FROM holdings WHERE account_id IN (SELECT id FROM accounts WHERE user_id = ?))",
				userId);
			// 042 5번부터 튜토리얼 매수가 OCO 예약을 함께 만든다. exit_plans는 holding·order를 모두 참조하므로
			// 둘보다 먼저 지운다(fk_exit_plans_holding).
			jdbcTemplate.update("DELETE FROM exit_plan_conditions WHERE exit_plan_id IN "
				+ "(SELECT id FROM exit_plans WHERE user_id = ?)", userId);
			jdbcTemplate.update("DELETE FROM exit_plans WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM trades WHERE order_id IN "
				+ "(SELECT id FROM orders WHERE user_id = ?)", userId);
			jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM holdings WHERE account_id IN "
				+ "(SELECT id FROM accounts WHERE user_id = ?)", userId);
			jdbcTemplate.update("DELETE FROM practice_attempts WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM accounts WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
		}
		instrumentIds.forEach(id -> jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", id));
		userIds.clear();
		instrumentIds.clear();
	}

	// 이슈 #450 재현 시나리오의 역-검증: 진입(ensureAttempt) → [종목선택 → 매수 → 관찰 → 매도(부분) → 재시작(나머지
	// 보상매도 포함)]을 3회 반복한다. 매 단계·매 사이클 이후 실제 Account.cashBalance·reservedCash·realizedPnl이
	// 튜토리얼 진입 이전 값과 정확히 같아야 한다 — 047 이전이었다면 사이클마다 매도 대금이 실제 계좌에 누적돼
	// cashBalance가 계속 불어났을 시나리오다(이슈 #450 원문 재현 경로). 재시작마다 순체결수량(2 매수 - 1 매도 = 1)이
	// 남아 PracticeRunRestartOrderService.createCompensatingSell(보상매도) 경로도 매 사이클 함께 거친다.
	@Test
	void repeatedBuyObserveSellRestartCyclesNeverChangeRealAccountCashBalance() {
		User user = createUser("issue450-repro");
		Account account = createAccount(user);
		Instrument instrument = createTutorialSampleCryptoInstrument("issue450-repro");

		long realCashBaseline = account.getCashBalance();
		long realReservedBaseline = account.getReservedCash();
		long realRealizedPnlBaseline = account.getRealizedPnl();
		assertThat(realCashBaseline).isEqualTo(10_000_000L);

		PracticeAttemptResponse entry = practiceAttemptService.ensureAttempt(user.getId(), Market.CRYPTO);
		assertThat(entry.tutorialCashBalance()).isEqualTo(INITIAL_TUTORIAL_CASH);
		assertRealAccountUnchanged(account.getId(), realCashBaseline, realReservedBaseline, realRealizedPnlBaseline);

		for (int cycle = 1; cycle <= CYCLES; cycle++) {
			practiceAttemptService.selectInstrument(user.getId(), Market.CRYPTO, instrument.getId());
			assertRealAccountUnchanged(
				account.getId(), realCashBaseline, realReservedBaseline, realRealizedPnlBaseline);

			// 매수 2단위 — 튜토리얼 계좌만 차감돼야 한다.
			orderService.createOrder(user.getId(), "issue450-buy-" + cycle + "-" + UUID.randomUUID(),
				new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET",
					new BigDecimal("2")));
			assertRealAccountUnchanged(
				account.getId(), realCashBaseline, realReservedBaseline, realRealizedPnlBaseline);
			TutorialAccount tutorialAfterBuy = tutorialAccount(user.getId());
			assertThat(tutorialAfterBuy.getCashBalance()).isLessThan(INITIAL_TUTORIAL_CASH);

			// 관찰 — holding이 존재하는 한 언제든 호출 가능하다(026 비즈니스 규칙, 031이 그대로 상속).
			Holding holding = holdingRepository
				.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
				.orElseThrow();
			practiceHoldingObservationService.createObservation(
				user.getId(), new PracticeHoldingObservationCreateRequest(holding.getId()));
			assertRealAccountUnchanged(
				account.getId(), realCashBaseline, realReservedBaseline, realRealizedPnlBaseline);

			// 매도 1단위(부분) — 남은 1단위는 재시작의 보상매도가 정리한다.
			orderService.createOrder(user.getId(), "issue450-sell-" + cycle + "-" + UUID.randomUUID(),
				new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.SELL, "MARKET",
					new BigDecimal("1")));
			assertRealAccountUnchanged(
				account.getId(), realCashBaseline, realReservedBaseline, realRealizedPnlBaseline);

			// 재시작 — 남은 순체결수량(1단위)에 대한 보상매도가 먼저 튜토리얼 계좌를 불렸다가, 같은 트랜잭션
			// 끝의 리셋이 다시 정확히 초기값으로 되돌린다(TUTORIAL-CASH-ISOL-006). 실제 계좌는 보상매도로도
			// 전혀 변하지 않아야 한다.
			PracticeAttemptResponse restartResponse = practiceAttemptRestartService.restart(
				user.getId(), Market.CRYPTO);
			assertThat(restartResponse.tutorialCashBalance()).isEqualTo(INITIAL_TUTORIAL_CASH);
			assertThat(restartResponse.tutorialAvailableCash()).isEqualTo(INITIAL_TUTORIAL_CASH);
			assertThat(restartResponse.tutorialRealizedPnl()).isZero();
			assertThat(restartResponse.runNumber()).isEqualTo(cycle + 1L);

			TutorialAccount tutorialAfterRestart = tutorialAccount(user.getId());
			assertThat(tutorialAfterRestart.getCashBalance()).isEqualTo(INITIAL_TUTORIAL_CASH);
			assertThat(tutorialAfterRestart.getReservedCash()).isZero();
			assertThat(tutorialAfterRestart.getRealizedPnl()).isZero();

			// 이슈 #450의 핵심 주장: "반복할수록 실제 잔고가 불어난다"가 성립하지 않아야 한다 — 사이클이
			// 끝날 때마다 실제 계좌는 튜토리얼 진입 이전 값과 정확히 같은 상태로 돌아온다.
			assertRealAccountUnchanged(
				account.getId(), realCashBaseline, realReservedBaseline, realRealizedPnlBaseline);
		}

		// 최종 확인 — 3회 반복 이후에도 실제 Account.cashBalance는 튜토리얼을 시작하기 전 값과 정확히 같다.
		Account finalAccount = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(finalAccount.getCashBalance()).isEqualTo(realCashBaseline);
		assertThat(finalAccount.getReservedCash()).isEqualTo(realReservedBaseline);
		assertThat(finalAccount.getRealizedPnl()).isEqualTo(realRealizedPnlBaseline);
	}

	private void assertRealAccountUnchanged(
		Long accountId, long expectedCash, long expectedReserved, long expectedRealizedPnl) {
		Account current = accountRepository.findById(accountId).orElseThrow();
		assertThat(current.getCashBalance()).isEqualTo(expectedCash);
		assertThat(current.getReservedCash()).isEqualTo(expectedReserved);
		assertThat(current.getRealizedPnl()).isEqualTo(expectedRealizedPnl);
	}

	private TutorialAccount tutorialAccount(Long userId) {
		return tutorialAccountRepository
			.findByUserIdAndMarket(userId, Market.CRYPTO)
			.orElseThrow();
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

	// 결정적 샌드박스 가격(TutorialPriceGenerator)을 그대로 쓰기 위해 tutorialSample=true·tradable=true인 신규
	// CRYPTO 종목을 만든다(TutorialSandboxSellCashIsolationIntegrationTest의 fixture 관례와 동일). minOrderAmount는
	// 0으로 두어 최소주문금액 제약이 이 테스트의 관심사가 아니게 한다.
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
