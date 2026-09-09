package com.finplay.api.domain.account.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class RealizedPnlBackfillMigrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 13, 10, 0, 0);
	private static final String MIGRATION_PATH = "/db/migration/V34__add_sandbox_cash_adjustment_and_backfill.sql";

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

	@Autowired
	private com.finplay.api.domain.market.repository.StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	private Instrument realInstrument;
	private Instrument sandboxInstrument;
	private com.finplay.api.domain.market.entity.StockReplaySession session;

	@BeforeEach
	void setUp() {
		realInstrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, "BACKFILL_REAL", "테스트실제종목",
				BigDecimal.valueOf(100), 10_000L, true, NOW));
		sandboxInstrument = instrumentRepository.findByMarketAndSymbol(
			Market.STOCK, "SANDBOX_STK_1")
			.orElseThrow();
		assertThat(sandboxInstrument.isTutorialSample()).isTrue();
		session = stockReplaySessionRepository.saveAndFlush(
			com.finplay.api.domain.market.entity.StockReplaySession.ready(
				NOW.toLocalDate().plusYears(20), NOW.toLocalDate(), NOW, NOW));
	}

	private void runRealizedPnlBackfillUpdate() {
		entityManager.flush();
		for (String statement : readRealizedPnlBackfillStatement()) {
			jdbcTemplate.execute(statement);
		}
		entityManager.clear();
	}

	private List<String> readRealizedPnlBackfillStatement() {
		try {
			String sql = StreamUtils.copyToString(
				new ClassPathResource(MIGRATION_PATH).getInputStream(), StandardCharsets.UTF_8);
			String withoutComments = Arrays.stream(sql.split("\n"))
				.filter(line -> !line.trim().startsWith("--"))
				.reduce("", (a, b) -> a + "\n" + b);
			return Arrays.stream(withoutComments.split(";"))
				.map(String::trim)
				.filter(statement -> !statement.isEmpty())
				.filter(statement -> !statement.toUpperCase().startsWith("ALTER TABLE"))
				.filter(statement -> !statement.toLowerCase().contains("sandbox_cash_adjustment"))
				.toList();
		} catch (IOException e) {
			throw new IllegalStateException("V34 마이그레이션 파일을 읽을 수 없습니다.", e);
		}
	}

	private int tradeSequence = 0;

	private Trade createTrade(
		Account account, Instrument instrument, OrderSide side, long amount, long fee, Long realizedPnl) {
		tradeSequence++;
		String requestHash = String.format("%064d", tradeSequence);
		Order order = orderRepository.saveAndFlush(Order.create(
			account.getUser(), account, instrument, side, OrderType.MARKET, BigDecimal.ONE,
			"backfill-idem-" + tradeSequence, requestHash, NOW));
		return tradeRepository.saveAndFlush(Trade.of(
			order, account, instrument, session, side,
			BigDecimal.valueOf(100), BigDecimal.ONE, amount, fee, realizedPnl, NOW, NOW));
	}

	@Test
	@DisplayName("샌드박스 매매 손익까지 합산돼 오염된 realized_pnl을 실제 종목 매도 합계로 정확히 재계산한다")
	void backfillRecomputesContaminatedRealizedPnlCorrectly() {
		User user = userRepository
			.saveAndFlush(User.create("backfill-contaminated@finplay.com", "hash", "bfcontam", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));

		createTrade(account, realInstrument, OrderSide.BUY, 100_000L, 100L, null);
		createTrade(account, realInstrument, OrderSide.SELL, 120_000L, 120L, 5_000L);

		createTrade(account, sandboxInstrument, OrderSide.BUY, 10_000L, 10L, null);
		createTrade(account, sandboxInstrument, OrderSide.SELL, 12_000L, 12L, 1_500L);

		account.addRealizedPnl(6_500L);
		accountRepository.saveAndFlush(account);

		runRealizedPnlBackfillUpdate();

		Account result = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(result.getRealizedPnl()).isEqualTo(5_000L);
	}

	@Test
	@DisplayName("샌드박스 활동이 전혀 없는 계좌는 백필 이후에도 realized_pnl이 변하지 않는다")
	void backfillLeavesCleanAccountUnchanged() {
		User user = userRepository.saveAndFlush(User.create("backfill-clean@finplay.com", "hash", "bfclean", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));

		createTrade(account, realInstrument, OrderSide.BUY, 100_000L, 100L, null);
		createTrade(account, realInstrument, OrderSide.SELL, 120_000L, 120L, 7_000L);
		account.addRealizedPnl(7_000L);
		accountRepository.saveAndFlush(account);

		runRealizedPnlBackfillUpdate();

		Account result = accountRepository.findById(account.getId()).orElseThrow();
		assertThat(result.getRealizedPnl()).isEqualTo(7_000L);
	}

	@Test
	@DisplayName("백필 UPDATE를 두 번 실행해도(재실행 시뮬레이션) 같은 결과가 나온다 (멱등성)")
	void backfillIsIdempotentAcrossReruns() {
		User user = userRepository.saveAndFlush(User.create("backfill-idempotent@finplay.com", "hash", "bfidem", NOW));
		Account account = accountRepository.saveAndFlush(Account.create(user, Market.STOCK, NOW));

		createTrade(account, realInstrument, OrderSide.BUY, 100_000L, 100L, null);
		createTrade(account, realInstrument, OrderSide.SELL, 120_000L, 120L, 5_000L);
		createTrade(account, sandboxInstrument, OrderSide.BUY, 10_000L, 10L, null);
		createTrade(account, sandboxInstrument, OrderSide.SELL, 12_000L, 12L, 1_500L);
		account.addRealizedPnl(6_500L);
		accountRepository.saveAndFlush(account);

		runRealizedPnlBackfillUpdate();
		Account firstRun = accountRepository.findById(account.getId()).orElseThrow();
		long firstRealizedPnl = firstRun.getRealizedPnl();

		runRealizedPnlBackfillUpdate();
		Account secondRun = accountRepository.findById(account.getId()).orElseThrow();

		assertThat(secondRun.getRealizedPnl()).isEqualTo(firstRealizedPnl);
	}
}
