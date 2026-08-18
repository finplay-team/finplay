// 매수 파이프라인(이슈 #13)으로 생성한 실제 원장·시세 데이터를 AccountService.getAccountSummary로 검증하는 통합 테스트다.
package com.finplay.api.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.dto.response.AccountSummaryResponse;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

// 이 테스트는 InstrumentRepositoryTest·StockReplaySessionRepositoryTest 등 다른 슬라이스 테스트가
// 공유 MySQL 컨테이너(ADR-0003)에서 종목·재생세션 전체 개수를 단정하므로, 클래스 트랜잭션으로 감싸 각 테스트
// 종료 시 자동 롤백시켜 생성한 사용자·계좌·종목·주문 데이터가 다른 테스트 클래스로 새어나가지 않게 한다.
// PR #96 리뷰 권장사항 1: 이 `@Transactional`은 JPA(MySQL) 쓰기만 롤백한다 — "실제 커밋 이후 별도 트랜잭션으로
// 재조회"하는 시나리오(예: 이슈 #22 멱등 재요청의 동시 경합, 실제 서비스 호출이 서로 다른 트랜잭션 경계에서
// 실행되는지 여부)는 이 클래스가 검증하지 않는다 — 그런 검증은 `OrderIdempotencyIntegrationTest`처럼 커밋
// 경계를 그대로 두는 통합 테스트의 책임이다. 근본 원인(= `InstrumentRepositoryTest`가 시드 데이터 절대
// 개수를 단정해 공유 컨테이너에 커밋하는 어떤 테스트와도 충돌할 수 있는 구조)은 별도 백로그(task_23e68184)로
// 남겨뒀고 이 클래스에서 고치지 않는다.
@SpringBootTest
@Transactional
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class AccountSummaryIntegrationTest {

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
	private TestClock clock;

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

	@Autowired
	private StringRedisTemplate redisTemplate;

	// PR #96 리뷰 권장사항 2: Redis(feed:crypto:status·price:crypto:*)는 `@Transactional`(JPA) 롤백 대상이
	// 아니라 실제로 남는다. 이 테스트가 쓴 코인 시세 키만 추적해뒀다가 @AfterEach에서 지운다.
	private String cryptoPriceKeyToCleanUp;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
		stockReplaySessionRepository
			.findByServiceDate(TRADING_DATE)
			.orElseGet(() -> stockReplaySessionRepository.saveAndFlush(
				StockReplaySession.ready(TRADING_DATE, TRADING_DATE, BASE_NOW, BASE_NOW)));
	}

	// PR #96 리뷰 권장사항 2: 이 클래스가 공유 Redis(ADR-0003)의 연결상태를 DISCONNECTED로 바꿔둔 채 끝나면
	// 알파벳순으로 이 클래스보다 뒤에 실행되는 다른 스위트가 오염된 상태로 시작한다 — 매 테스트 종료 시
	// CONNECTED로 복구하고, 랜덤 심볼로 저장한 가격 키(TTL 없음)도 함께 지운다.
	@AfterEach
	void tearDown() {
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		if (cryptoPriceKeyToCleanUp != null) {
			redisTemplate.delete(cryptoPriceKeyToCleanUp);
			cryptoPriceKeyToCleanUp = null;
		}
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
		clock.set(BASE_NOW.plusMinutes(1));

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
		// PriceStore의 실제 키 조립 규칙("price:crypto:" + symbol)과 동일하게 맞춰 @AfterEach에서 지운다.
		cryptoPriceKeyToCleanUp = "price:crypto:" + symbol;

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

	// PR #96 리뷰 권장사항 3: 기존 4개 시나리오가 전부 매수뿐이라 realizedPnl은 항상 0으로만 검증됐다.
	// 이슈 #41(매도, 병합됨)의 실제 매도 API로 0이 아닌 realizedPnl을 만들어 계좌 요약에 정확히 반영되는지 검증한다.
	@Test
	void stockPartialSellAfterBuyReflectsRealizedPnlInSummary() {
		User user = createUser("summary-sell");
		createAccount(user);
		Instrument instrument = createStockInstrument("SSELL");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("60000"));
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("100000"));

		// 매수: 10:00 시각 → 09:59 분봉(60000) 체결가. amount=600000, fee=90 → 차감 600090.
		orderService.createOrder(user.getId(), "summary-sell-buy-idem", buyRequest(instrument.getId(), "10"));

		// 매도: 10:01로 시각 이동 → 10:00 분봉(100000) 체결가. 10주 중 4주만 매도(FIFO 단일 lot).
		clock.set(BASE_NOW.plusMinutes(1));
		orderService.createOrder(user.getId(), "summary-sell-sell-idem", sellRequest(instrument.getId(), "4"));

		AccountSummaryResponse response = accountService.getAccountSummary(
			user.getId(), com.finplay.api.account.domain.Market.STOCK);

		// 매수 차감 600090, 매도 유입 amount(400000)-fee(60)=399940 → 9399910+399940=9799850.
		long expectedCashBalance = 9_799_850L;
		// realizedPnl = (매도금액400000-매도수수료60) - (배분원가240000+배분매수수수료36) = 159904
		// (배분원가=단가60000*4=240000, 배분매수수수료=floor(매수수수료90*4/10)=36)
		long expectedRealizedPnl = 159_904L;
		// 잔여 6주 * 평균단가60000 = 원가360000, 현재가(100000) 평가액=600000 → 미실현손익 240000.
		long expectedHoldingsValue = 600_000L;
		long expectedUnrealizedPnl = 240_000L;
		long expectedTotalValue = expectedCashBalance + expectedHoldingsValue;

		assertThat(response.cashBalance()).isEqualTo(expectedCashBalance);
		assertThat(response.realizedPnl()).isEqualTo(expectedRealizedPnl);
		assertThat(response.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(response.unrealizedPnl()).isEqualTo(expectedUnrealizedPnl);
		assertThat(response.totalValue()).isEqualTo(expectedTotalValue);
	}

	@Test
	void totalValueIsComputedAsCashBalancePlusHoldingsValueAfterTutorialCompletionReward() {
		User user = createUser("summary-reward-gain");
		Account account = createAccount(user);

		// 튜토리얼 완료 보상 지급(PracticeHoldingReflectionService.payTutorialCompletionReward)을 재현한다.
		account.addCash(5_000_000L);
		accountRepository.saveAndFlush(account);

		Instrument instrument = createStockInstrument("RWGN");
		createCandle(instrument, FIRST_CANDLE_TIME, new BigDecimal("500000"));

		// 보상금 500만원 전액으로 실제 종목을 매수한다. amount=5,000,000, fee=5,000,000*0.00015=750(FLOOR).
		orderService.createOrder(user.getId(), "summary-reward-gain-idem", buyRequest(instrument.getId(), "10"));

		// 시각 이동 후 시세가 60만원으로 올라 평가차익이 발생한다.
		createCandle(instrument, SECOND_CANDLE_TIME, new BigDecimal("600000"));
		clock.set(BASE_NOW.plusMinutes(1));

		AccountSummaryResponse response = accountService.getAccountSummary(
			user.getId(), com.finplay.api.account.domain.Market.STOCK);

		// cashBalance = 10,000,000(시드) + 5,000,000(보상) - 5,000,000(매수원금) - 750(수수료) = 9,999,250
		long expectedCashBalance = 9_999_250L;
		// holdingsValue = 10주 * 60만원 = 6,000,000, unrealizedPnl = 6,000,000 - 5,000,000(원가) = 1,000,000
		long expectedHoldingsValue = 6_000_000L;
		long expectedUnrealizedPnl = 1_000_000L;
		// totalValue = cashBalance + holdingsValue = 9,999,250 + 6,000,000 = 15,999,250
		long expectedTotalValue = 15_999_250L;

		assertThat(response.cashBalance()).isEqualTo(expectedCashBalance);
		assertThat(response.holdingsValue()).isEqualTo(expectedHoldingsValue);
		assertThat(response.unrealizedPnl()).isEqualTo(expectedUnrealizedPnl);
		assertThat(response.totalValue()).isEqualTo(expectedTotalValue);
	}

	private OrderCreateRequest buyRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.STOCK, instrumentId, OrderSide.BUY, "MARKET", new BigDecimal(quantity));
	}

	private OrderCreateRequest sellRequest(Long instrumentId, String quantity) {
		return new OrderCreateRequest(Market.STOCK, instrumentId, OrderSide.SELL, "MARKET", new BigDecimal(quantity));
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
		// nickname 컬럼은 VARCHAR(50)이다(V2). scenario 접두사가 길어도 잘리지 않도록 UUID 부분을 20자로
		// 제한한다(전체 UUID 32자를 그대로 붙이면 "summary-reward-gain-<32자>"가 50자를 넘겨 저장 시
		// data truncation 오류가 난다).
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
	}

}
