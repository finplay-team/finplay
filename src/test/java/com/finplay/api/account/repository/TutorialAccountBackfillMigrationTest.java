// V46 마이그레이션(튜토리얼 계좌 신설 + sandbox_cash_adjustment 백필)의 백필 UPDATE 문을 실제
// 마이그레이션 파일에서 읽어 재실행하는 방식으로 검증한다 (047-tutorial-sandbox-cash-isolation,
// tasks.md 1번, 이슈 #450, TUTORIAL-CASH-ISOL-008). Flyway는 컨텍스트 기동 시 이미 빈 테이블에 V46을
// 적용해 두므로, 이 테스트는 "마이그레이션 적용 전 오염된 상태"를 흉내낸 데이터를 심은 뒤 같은 UPDATE
// 문을 다시 실행해 배포 시점 백필과 그 멱등성을 시뮬레이션한다.
package com.finplay.api.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.domain.Market;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderStatus;
import com.finplay.api.order.repository.OrderRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StreamUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class TutorialAccountBackfillMigrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0, 0);
	private static final String MIGRATION_PATH = "/db/migration/V46__create_tutorial_accounts_and_backfill_cash.sql";

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	// jdbcTemplate의 UPDATE는 영속성 컨텍스트를 거치지 않고 DB를 직접 바꾼다 — 이미 로드된 Account가
	// 1차 캐시에 남아 있으면 findById가 갱신 전 값을 그대로 돌려주므로, 재조회 전에 반드시 비운다.
	private void runBackfillUpdate() {
		entityManager.flush();
		for (String statement : readBackfillUpdateStatements()) {
			jdbcTemplate.execute(statement);
		}
		entityManager.clear();
	}

	// Account 엔티티는 더 이상 sandbox_cash_adjustment를 매핑하지 않으므로(#459, PR-A: 엔티티 매핑 제거),
	// 컬럼이 아직 남아 있는 이 단계에서는 백필 이전 오염 상태를 흉내내거나 백필 결과를 확인할 때 raw JDBC로
	// 직접 그 컬럼을 다룬다.
	private void setSandboxCashAdjustment(Long accountId, long amount) {
		jdbcTemplate.update("UPDATE accounts SET sandbox_cash_adjustment = ? WHERE id = ?", amount, accountId);
	}

	private long readSandboxCashAdjustment(Long accountId) {
		return jdbcTemplate.queryForObject(
			"SELECT sandbox_cash_adjustment FROM accounts WHERE id = ?", Long.class, accountId);
	}

	private List<String> readBackfillUpdateStatements() {
		try {
			String sql = StreamUtils.copyToString(
				new ClassPathResource(MIGRATION_PATH).getInputStream(), StandardCharsets.UTF_8);
			// SQL 줄 주석(--)을 먼저 제거해야 CREATE TABLE 문 앞에 붙은 여러 줄 주석이 같은 세미콜론
			// 구간에 섞여 startsWith("UPDATE") 판별을 방해하지 않는다. CREATE TABLE은 Flyway가 컨텍스트
			// 기동 시 이미 적용해 뒀으므로 재실행하면 "table already exists" 오류가 난다 — UPDATE만 골라낸다.
			String withoutComments = Arrays.stream(sql.split("\n"))
				.filter(line -> !line.trim().startsWith("--"))
				.reduce("", (a, b) -> a + "\n" + b);
			return Arrays.stream(withoutComments.split(";"))
				.map(String::trim)
				.filter(statement -> !statement.isEmpty())
				.filter(statement -> statement.toUpperCase().startsWith("UPDATE"))
				.toList();
		} catch (IOException e) {
			throw new IllegalStateException("V46 마이그레이션 파일을 읽을 수 없습니다.", e);
		}
	}

	@Test
	@DisplayName("sandbox_cash_adjustment로 오염된 계좌는 그만큼 cash_balance가 원복되고 adjustment는 0이 된다")
	void backfillRestoresContaminatedAccountCashBalance() {
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-contaminated@finplay.com", "hash", "v46contam", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));
		account.addCash(1_988L);
		accountRepository.saveAndFlush(account);
		setSandboxCashAdjustment(account.getId(), 1_988L);

		runBackfillUpdate();

		Account result = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(result.getCashBalance()).isEqualTo(10_000_000L);
		assertThat(readSandboxCashAdjustment(account.getId())).isZero();
	}

	@Test
	@DisplayName("샌드박스 활동이 없던 계좌(adjustment=0)는 백필 이후에도 cash_balance가 변하지 않는다")
	void backfillLeavesCleanAccountUnchanged() {
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-clean@finplay.com", "hash", "v46clean", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));
		account.deductCash(2_000_000L);
		accountRepository.saveAndFlush(account);

		runBackfillUpdate();

		Account result = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(result.getCashBalance()).isEqualTo(8_000_000L);
		assertThat(readSandboxCashAdjustment(account.getId())).isZero();
	}

	@Test
	@DisplayName("완료 보상으로 늘어난 조정값은 원복 대상에서 제외돼, 보상을 실제 매매에 쓴 계좌도 음수가 되지 않는다")
	void backfillExcludesCompletionRewardSoAccountThatSpentTheRewardDoesNotGoNegative() {
		// PR #452 리뷰 차단 2번 재현: 초기 1000만원 → STOCK 튜토리얼 완료 보상 500만원(adjustment도 함께
		// +500만원 누적, addSandboxCashAdjustment가 종목 조건 없이 항상 호출됐으므로) → 실제 주식을
		// 1200만원어치 매수해 현금 300만원. 옛 백필(adjustment 전액 차감)이면 300만원 - 500만원 = -200만원이
		// 되지만, 완료 보상분(500만원)은 원복 대상이 아니므로 차감량이 0이 돼 300만원 그대로여야 한다.
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-reward@finplay.com", "hash", "v46reward", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));
		account.addCash(5_000_000L);
		account.deductCash(12_000_000L);
		accountRepository.saveAndFlush(account);
		setSandboxCashAdjustment(account.getId(), 5_000_000L);
		insertStockCompletionRecord(account, user, "INVESTMENT_PRACTICE_V1");

		runBackfillUpdate();

		Account result = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(result.getCashBalance()).isEqualTo(3_000_000L);
		assertThat(readSandboxCashAdjustment(account.getId())).isZero();
	}

	@Test
	@DisplayName("완료 보상 이외의 사유로 조정값이 남은 현금보다 커도 cash_balance는 0 밑으로 내려가지 않는다")
	void backfillNeverDrivesCashBalanceBelowZero() {
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-floor@finplay.com", "hash", "v46floor", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));
		// 완료 기록 없이(reward_component=0) adjustment가 남은 현금보다 크게 만든다 — 방어적 GREATEST(0, ...)
		// 없이는 8,000,000 - 9,000,000 = -1,000,000이 된다.
		account.deductCash(2_000_000L);
		accountRepository.saveAndFlush(account);
		setSandboxCashAdjustment(account.getId(), 9_000_000L);

		runBackfillUpdate();

		Account result = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(result.getCashBalance()).isZero();
		assertThat(readSandboxCashAdjustment(account.getId())).isZero();
	}

	// practice_completions(→ practice_market_reflections → holdings → 시드 instrument)까지의 FK 체인을
	// 최소 값으로 채워, V46 백필의 LEFT JOIN이 "그 시장 튜토리얼을 완료한 사용자"로 인식하게 만든다.
	private void insertStockCompletionRecord(Account account, User user, String tutorialKey) {
		Long instrumentId = jdbcTemplate.queryForObject(
			"SELECT id FROM instruments WHERE symbol = '005930'", Long.class);
		jdbcTemplate.update(
			"INSERT INTO holdings (account_id, instrument_id, quantity, average_price, is_active, "
				+ "created_at, updated_at) VALUES (?, ?, 0, 0, false, ?, ?)",
			account.getId(), instrumentId, NOW, NOW);
		Long holdingId = jdbcTemplate.queryForObject(
			"SELECT id FROM holdings WHERE account_id = ? AND instrument_id = ?", Long.class,
			account.getId(), instrumentId);
		jdbcTemplate.update(
			"INSERT INTO practice_market_reflections (user_id, holding_id, tutorial_key, answer, created_at) "
				+ "VALUES (?, ?, ?, 'v46 backfill test fixture', ?)",
			user.getId(), holdingId, tutorialKey, NOW);
		Long reflectionId = jdbcTemplate.queryForObject(
			"SELECT id FROM practice_market_reflections WHERE user_id = ? AND tutorial_key = ?", Long.class,
			user.getId(), tutorialKey);
		jdbcTemplate.update(
			"INSERT INTO practice_completions (user_id, tutorial_key, reflection_id, completed_at) "
				+ "VALUES (?, ?, ?, ?)",
			user.getId(), tutorialKey, reflectionId, NOW);
	}

	@Test
	@DisplayName("배포 시점에 이미 존재하는 샌드박스 지정가 매수 PENDING 주문은 취소되고 실제 계좌 예약이 반환된다")
	void backfillCancelsLegacyPendingSandboxBuyOrdersAndReturnsRealAccountReservation() {
		// PR #452 리뷰 권장 1번: 047 이전 코드가 실제 계좌에 현금을 예약해 만든 PENDING 지정가 매수를 그대로
		// 재현한다 — 정리하지 않으면 TutorialLegacyPendingOrderPostMigrationIntegrationTest가 재현한 대로
		// 체결·취소 모두 IllegalStateException으로 영구히 막힌다.
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-legacy-order@finplay.com", "hash", "v46legacy", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.CRYPTO, NOW));
		Instrument instrument = createTutorialSampleCryptoInstrument("v46-legacy-buy");
		account.reserveCash(100_050L);
		accountRepository.saveAndFlush(account);
		Order legacyBuy = orderRepository.saveAndFlush(Order.createLimitPending(
			user, account, instrument, OrderSide.BUY, new BigDecimal("0.1"), new BigDecimal("1000000"),
			"v46-legacy-buy-" + UUID.randomUUID(), "a".repeat(64), NOW));

		runBackfillUpdate();

		assertThat(orderRepository.findById(legacyBuy.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.CANCELLED);
		assertThat(accountRepository.findById(account.getId()).orElseThrow().getReservedCash()).isZero();
	}

	@Test
	@DisplayName("샌드박스 지정가 매도 PENDING 주문은 현금 예약이 아니라 holdings.reservedQuantity를 쓰므로 백필 대상이 아니다")
	void backfillDoesNotTouchPendingSandboxSellOrders() {
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-legacy-sell@finplay.com", "hash", "v46legacysell", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.CRYPTO, NOW));
		Instrument instrument = createTutorialSampleCryptoInstrument("v46-legacy-sell");
		Order legacySell = orderRepository.saveAndFlush(Order.createLimitPending(
			user, account, instrument, OrderSide.SELL, new BigDecimal("0.1"), new BigDecimal("1000000"),
			"v46-legacy-sell-" + UUID.randomUUID(), "b".repeat(64), NOW));

		runBackfillUpdate();

		assertThat(orderRepository.findById(legacySell.getId()).orElseThrow().getStatus())
			.isEqualTo(OrderStatus.PENDING);
		assertThat(accountRepository.findById(account.getId()).orElseThrow().getReservedCash()).isZero();
	}

	private Instrument createTutorialSampleCryptoInstrument(String scenario) {
		Instrument instrument = Instrument.create(
			com.finplay.api.market.domain.Market.CRYPTO, "T" + UUID.randomUUID().toString().substring(0, 8),
			scenario, BigDecimal.ONE, 0L, true, NOW);
		ReflectionTestUtils.setField(instrument, "tutorialSample", true);
		return instrumentRepository.saveAndFlush(instrument);
	}

	@Test
	@DisplayName("백필 UPDATE를 두 번 실행해도(재실행 시뮬레이션) 같은 결과가 나온다 (TUTORIAL-CASH-ISOL-008 멱등성)")
	void backfillIsIdempotentAcrossReruns() {
		User user = userRepository.saveAndFlush(
			User.create("v46-backfill-idempotent@finplay.com", "hash", "v46idem", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));
		account.addCash(5_012_000L);
		accountRepository.saveAndFlush(account);
		setSandboxCashAdjustment(account.getId(), 5_012_000L);

		runBackfillUpdate();
		Account firstRun = accountRepository.findById(account.getId()).orElseThrow();
		long firstCashBalance = firstRun.getCashBalance();
		long firstAdjustment = readSandboxCashAdjustment(account.getId());
		assertThat(firstCashBalance).isEqualTo(10_000_000L);
		assertThat(firstAdjustment).isZero();

		runBackfillUpdate();
		Account secondRun = accountRepository.findById(account.getId()).orElseThrow();

		assertThat(secondRun.getCashBalance()).isEqualTo(firstCashBalance);
		assertThat(readSandboxCashAdjustment(account.getId())).isEqualTo(firstAdjustment);
	}
}
