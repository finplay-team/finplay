// 매수 파이프라인(이슈 #13)으로 생성한 실제 원장·시세 데이터를 AccountService.getAccountSummary로 검증하는 통합 테스트다.
package com.finplay.api.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.dto.response.AccountSummaryResponse;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.domain.StockCandle;
import com.finplay.api.market.domain.StockReplaySession;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.repository.StockCandleRepository;
import com.finplay.api.market.repository.StockReplaySessionRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.service.OrderService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

// 이 테스트는 InstrumentRepositoryTest·StockReplaySessionRepositoryTest 등 다른 슬라이스 테스트가
// 공유 MySQL 컨테이너(ADR-0003)에서 종목·재생세션 전체 개수를 단정하므로, 클래스 트랜잭션으로 감싸 각 테스트
// 종료 시 자동 롤백시켜 생성한 사용자·계좌·종목·주문 데이터가 다른 테스트 클래스로 새어나가지 않게 한다.
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, AccountSummaryIntegrationTest.FixedClockTestConfig.class})
class AccountSummaryIntegrationTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	// 2026-07-29는 수요일이고 holidays-2026.txt에도 없어 재생세션만 READY면 개장 상태로 계산된다.
	private static final LocalDate TRADING_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 7, 29, 10, 0, 0);
	private static final LocalTime FIRST_CANDLE_TIME = LocalTime.of(9, 59);
	private static final LocalTime SECOND_CANDLE_TIME = LocalTime.of(10, 0);

	@Autowired
	private AccountService accountService;

	@Autowired
	private OrderService orderService;

	@Autowired
	private Clock clock;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	@Autowired
	private StockCandleRepository stockCandleRepository;

	@Autowired
	private PriceStore priceStore;

	@BeforeEach
	void setUp() {
		((MutableClock)clock).set(BASE_NOW);
		stockReplaySessionRepository
			.findByServiceDate(TRADING_DATE)
			.orElseGet(() -> stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW)));
	}

	@Test
	void emptyAccountImmediatelyAfterSignupReturnsAllZeroExceptSeedMoneyCashBalance() {
		User user = createUser("summary-signup");
		accountService.createAccountsFor(user);

		AccountSummaryResponse response = accountService.getAccountSummary(
			user.getId(), com.finplay.api.account.domain.Market.STOCK);

		assertThat(response.cashBalance()).isEqualTo(10_000_000L);
		assertThat(response.holdingsValue()).isZero();
		assertThat(response.totalValue()).isEqualTo(10_000_000L);
		assertThat(response.realizedPnl()).isZero();
		assertThat(response.unrealizedPnl()).isZero();
		assertThat(response.returnRate()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void stockBuyThenSummaryMatchesLedgerAndLatestQuoteExactly() {
		User user = createUser("summary-buy");
		createAccount(user);
		Instrument instrument = createStockInstrument("SUMM");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));

		// 10:00 시각 → 09:59 분봉(70000)이 체결가. amount=700000, fee=105 → 차감 700105.
		orderService.createOrder(user.getId(), "summary-buy-idem", buyRequest(instrument.getId(), "10"));

		// 10:01로 시각 이동 → 10:00 분봉(80000)이 최신 시세가 되어 평가에 반영된다.
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("80000"));
		((MutableClock)clock).set(BASE_NOW.plusMinutes(1));

		AccountSummaryResponse response = accountService.getAccountSummary(
			user.getId(), com.finplay.api.account.domain.Market.STOCK);

		long expectedCashBalance = 10_000_000L - 700_105L;
		// 원가 = 10 * 70000 = 700000, 평가액 = 10 * 80000 = 800000 → 미실현손익 100000.
		long expectedHoldingsValue = 800_000L;
		long expectedUnrealizedPnl = 100_000L;
		long expectedTotalValue = expectedCashBalance + expectedHoldingsValue;

		assertThat(response.cashBalance()).isEqualTo(expectedCashBalance);
		assertThat(response.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(response.totalValue()).isEqualTo(expectedTotalValue);
		assertThat(response.realizedPnl()).isZero();
		assertThat(response.unrealizedPnl()).isEqualTo(expectedUnrealizedPnl);
		// returnRate = (totalValue - seedMoney) / seedMoney = (10099895 - 10000000) / 10000000 = 0.0100 (scale 4, HALF_UP)
		assertThat(response.returnRate()).isEqualByComparingTo(new BigDecimal("0.0100"));
	}

	@Test
	void includesUnavailablePricedHoldingAtCostBasisWithoutPnl() {
		User user = createUser("summary-badpx");
		createAccount(user, com.finplay.api.account.domain.Market.CRYPTO);
		String symbol = "SUMBTC" + UUID.randomUUID().toString().substring(0, 6);
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "요약코인", BigDecimal.ONE, 0L, true, BASE_NOW));

		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(symbol, new BigDecimal("1000000"), BASE_NOW);

		// 시세가 유효한 시점에 매수를 실행해 실제 보유를 만든다.
		orderService.createOrder(
			user.getId(), "summary-badpx-idem",
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", new BigDecimal("1")));

		// amount=1000000, fee=1000000*0.0005=500 → 차감 1000500.
		long expectedCashBalance = 10_000_000L - 1_000_500L;

		// 매수 후 피드 연결이 끊겨 해당 종목 시세가 무효(UNAVAILABLE)가 된 상황을 재현한다.
		priceStore.saveConnectionStatus(FeedConnectionStatus.DISCONNECTED);

		AccountSummaryResponse response = accountService.getAccountSummary(
			user.getId(), com.finplay.api.account.domain.Market.CRYPTO);

		// PR #96 리뷰 반영 정책: 시세 무효 보유는 costBasis(quantity*averagePrice=1*1000000=1000000)만큼
		// holdingsValue에 가산하고 unrealizedPnl에는 기여하지 않는다(AccountService.getAccountSummary 참고).
		long expectedHoldingsValue = 1_000_000L;
		long expectedTotalValue = expectedCashBalance + expectedHoldingsValue;

		assertThat(response.cashBalance()).isEqualTo(expectedCashBalance);
		assertThat(response.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(response.unrealizedPnl()).isZero();
		assertThat(response.totalValue()).isEqualTo(expectedTotalValue);
		// returnRate = (9999500 - 10000000) / 10000000 = -0.00005 → HALF_UP 스케일4는 0에서 먼 방향으로 반올림 → -0.0001
		assertThat(response.returnRate()).isEqualByComparingTo(new BigDecimal("-0.0001"));
	}

	@Test
	void otherUsersAccountPurchaseDoesNotLeakIntoOwnSummary() {
		User owner = createUser("summary-owner");
		createAccount(owner);
		User other = createUser("summary-other");
		createAccount(other);
		Instrument instrument = createStockInstrument("OTHR");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("70000"));

		orderService.createOrder(other.getId(), "summary-other-idem", buyRequest(instrument.getId(), "10"));

		AccountSummaryResponse ownerSummary = accountService.getAccountSummary(
			owner.getId(), com.finplay.api.account.domain.Market.STOCK);

		assertThat(ownerSummary.cashBalance()).isEqualTo(10_000_000L);
		assertThat(ownerSummary.holdingsValue()).isZero();
		assertThat(ownerSummary.totalValue()).isEqualTo(10_000_000L);
		assertThat(ownerSummary.unrealizedPnl()).isZero();
	}

	private OrderCreateRequest buyRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.STOCK, instrumentId, OrderSide.BUY, "MARKET", new BigDecimal(quantity));
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
	}

	private Account createAccount(User user) {
		return createAccount(user, com.finplay.api.account.domain.Market.STOCK);
	}

	private Account createAccount(User user, com.finplay.api.account.domain.Market market) {
		return accountRepository.saveAndFlush(Account.create(user, market, BASE_NOW));
	}

	private Instrument createStockInstrument(String symbolPrefix) {
		String symbol = symbolPrefix + UUID.randomUUID().toString().substring(0, 6);
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, symbol, symbolPrefix + "종목", BigDecimal.ONE, 0L, true, BASE_NOW));
	}

	private void createCandle(Instrument instrument, LocalTime candleTime, BigDecimal price) {
		stockCandleRepository.saveAndFlush(StockCandle.create(
			instrument, TRADING_DATE, candleTime, price, price, price, price, 0L, "TEST", BASE_NOW));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "");
	}

	// 전역 Clock 빈(ClockConfig, Asia/Seoul 실시각)을 이 테스트 컨텍스트에서만 고정 시각으로 교체한다.
	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockTestConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return new MutableClock(BASE_NOW.atZone(KST).toInstant(), KST);
		}
	}

	// 매수 이후 평가 시점을 다른 분봉으로 이동시키기 위해 시각을 전진시킬 수 있는 Clock 구현.
	private static final class MutableClock extends Clock {

		private final ZoneId zone;
		private volatile Instant instant;

		private MutableClock(Instant instant, ZoneId zone) {
			this.instant = instant;
			this.zone = zone;
		}

		void set(LocalDateTime localDateTime) {
			this.instant = localDateTime.atZone(zone).toInstant();
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return new MutableClock(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
