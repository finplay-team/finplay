// V34 마이그레이션의 realized_pnl 재계산 UPDATE(033-exclude-tutorial-sandbox-data, 이슈 #366)를 실제
// 마이그레이션 파일에서 읽어 재실행하는 방식으로 검증한다. Flyway는 컨텍스트 기동 시 이미 빈 테이블에
// V34를 적용해 두므로, 이 테스트는 "마이그레이션 적용 전 오염된 상태"를 흉내낸 데이터를 심은 뒤 같은
// UPDATE 문을 다시 실행해 배포 시점 백필을 시뮬레이션한다.
//
// V34는 UPDATE를 2개 실행했다 — sandbox_cash_adjustment 재계산과 realized_pnl 재계산. 앞의 것은 이슈
// #459 PR-B(V47)로 그 컬럼 자체가 삭제되면서 재실행하면 "Unknown column" 오류가 나므로 제외했다(클래스
// 이름도 SandboxCashAdjustmentBackfillMigrationTest에서 이 이름으로 바꿨다). realized_pnl 재계산은
// 컬럼과 무관하게 계속 유효한 회귀 커버리지라 그대로 남긴다.
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

	// V34 파일 안의 realized_pnl 재계산 UPDATE만 골라 다시 실행한다. sandbox_cash_adjustment UPDATE는
	// V47이 그 컬럼을 삭제해 재실행 대상에서 제외했고, ALTER TABLE은 Flyway가 이미 적용해 재실행하면
	// duplicate column 오류가 난다.
	private void runRealizedPnlBackfillUpdate() {
		// jdbcTemplate의 UPDATE는 영속성 컨텍스트를 거치지 않고 DB를 직접 바꾼다 — 이미 로드된 Account가
		// 1차 캐시에 남아 있으면 findById가 갱신 전 값을 그대로 돌려주므로, 재조회 전에 반드시 비운다.
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
			// SQL 줄 주석(--)을 먼저 제거해야 "ALTER TABLE ..." 문 앞에 붙은 여러 줄 주석이 같은 세미콜론
			// 구간에 섞여 startsWith 판별을 방해하지 않는다.
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
		// STOCK 시장의 실제 종목 체결에는 재생세션이 필수다(Trade.validateStockReplaySession). 샌드박스
		// 종목은 튜토리얼 전용이라 세션 없이도 허용된다 — 둘 다에 같은 세션을 넘겨도 무방하다.
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

		// 실제 종목: 매수 후 5,000원 이익으로 매도.
		createTrade(account, realInstrument, OrderSide.BUY, 100_000L, 100L, null);
		createTrade(account, realInstrument, OrderSide.SELL, 120_000L, 120L, 5_000L);

		// 샌드박스 종목: 매수 후 1,500원 이익으로 매도 — realized_pnl에는 반영되면 안 된다.
		createTrade(account, sandboxInstrument, OrderSide.BUY, 10_000L, 10L, null);
		createTrade(account, sandboxInstrument, OrderSide.SELL, 12_000L, 12L, 1_500L);

		// 마이그레이션 이전 상태를 흉내낸다: 그 시절 버그로 realized_pnl에 샌드박스 매도 손익까지 합산됐었다
		// (5,000 + 1,500 = 6,500).
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
