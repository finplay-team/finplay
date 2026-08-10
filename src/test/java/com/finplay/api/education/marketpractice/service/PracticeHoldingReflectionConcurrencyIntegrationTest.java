// 완결된 chain에서 같은 사용자의 동시 복기 요청 2건이 practice_progresses 잠금으로 직렬화되어 한 건만
// 성공하고(practice_market_reflections/practice_completions 각 정확히 1행), 나머지는 409
// PRACTICE_ALREADY_COMPLETED로 거부되며 아무것도 추가로 저장하지 않음을 실제 MySQL로 검증한다.
package com.finplay.api.education.marketpractice.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingObservationCreateRequest;
import com.finplay.api.education.marketpractice.dto.request.PracticeHoldingReflectionCreateRequest;
import com.finplay.api.education.marketpractice.dto.response.PracticeHoldingReflectionResponse;
import com.finplay.api.education.service.PracticeIntentionService;
import com.finplay.api.favorite.service.FavoriteService;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.market.store.FeedConnectionStatus;
import com.finplay.api.market.store.PriceStore;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.dto.request.OrderCreateRequest;
import com.finplay.api.order.service.OrderService;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class PracticeHoldingReflectionConcurrencyIntegrationTest {

	private static final LocalDateTime BASE_NOW = LocalDateTime.of(2026, 8, 10, 10, 0, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("0.1");
	private static final BigDecimal ENTRY_PRICE = new BigDecimal("100000");
	private static final BigDecimal STOP_LOSS = new BigDecimal("90000");
	private static final BigDecimal TAKE_PROFIT = new BigDecimal("120000");

	@Autowired
	private PracticeHoldingReflectionService practiceHoldingReflectionService;
	@Autowired
	private PracticeHoldingObservationService practiceHoldingObservationService;
	@Autowired
	private FavoriteService favoriteService;
	@Autowired
	private PracticeIntentionService practiceIntentionService;
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
	private HoldingRepository holdingRepository;
	@Autowired
	private PriceStore priceStore;
	@Autowired
	private StringRedisTemplate redisTemplate;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private User user;
	private Instrument instrument;
	private Holding holding;
	private Long accountId;
	private String symbol;

	@BeforeEach
	void setUp() {
		clock.set(BASE_NOW);
		String scenario = "reflection-race";
		user = userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "password-hash", uniqueNickname(scenario), BASE_NOW));
		Account account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, BASE_NOW));
		accountId = account.getId();

		symbol = "RCE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, scenario + "코인", new BigDecimal("0.00000001"), 0L, true,
				BASE_NOW));
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		priceStore.saveTick(symbol, ENTRY_PRICE, BASE_NOW);

		favoriteService.createFavorite(user.getId(), instrument.getId());

		clock.set(BASE_NOW.plusSeconds(1));
		practiceIntentionService.createIntention(user.getId(),
			new PracticeIntentionCreateRequest(instrument.getId(), QUANTITY, STOP_LOSS, TAKE_PROFIT));

		clock.set(BASE_NOW.plusSeconds(2));
		orderService.createOrder(user.getId(), "reflection-race-" + UUID.randomUUID(),
			new OrderCreateRequest(Market.CRYPTO, instrument.getId(), OrderSide.BUY, "MARKET", QUANTITY));

		holding = holdingRepository.findByAccountIdAndInstrumentId(account.getId(), instrument.getId())
			.orElseThrow();

		// evidence A(경계 접근)를 만족시키는 관찰 1건을 미리 쌓아, 두 스레드 모두 evidence 검증은 통과하고
		// practice_progresses 잠금에서만 갈리게 한다.
		priceStore.saveTick(symbol, new BigDecimal("95000"), BASE_NOW.plusMinutes(1));
		practiceHoldingObservationService.createObservation(
			user.getId(), new PracticeHoldingObservationCreateRequest(holding.getId()));
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update(
			"DELETE FROM practice_completions WHERE user_id = ?", user.getId());
		jdbcTemplate.update(
			"DELETE FROM practice_market_reflections WHERE user_id = ?", user.getId());
		jdbcTemplate.update(
			"DELETE FROM practice_market_observations WHERE user_id = ?", user.getId());
		jdbcTemplate.update(
			"DELETE FROM holding_lots WHERE holding_id IN (SELECT id FROM holdings WHERE account_id = ?)",
			accountId);
		jdbcTemplate.update("DELETE FROM trades WHERE account_id = ?", accountId);
		jdbcTemplate.update("DELETE FROM orders WHERE account_id = ?", accountId);
		jdbcTemplate.update("DELETE FROM holdings WHERE account_id = ?", accountId);
		jdbcTemplate.update("DELETE FROM practice_progresses WHERE user_id = ?", user.getId());
		jdbcTemplate.update("DELETE FROM accounts WHERE user_id = ?", user.getId());
		jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", instrument.getId());
		userRepository.deleteById(user.getId());
		priceStore.saveConnectionStatus(FeedConnectionStatus.CONNECTED);
		redisTemplate.delete("price:crypto:" + symbol);
	}

	@Test
	void concurrentReflectionsSerializeToOneSuccessAndOneAlreadyCompleted() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		Callable<PracticeHoldingReflectionResponse> request = () -> {
			ready.countDown();
			start.await();
			return practiceHoldingReflectionService.createReflection(
				user.getId(), new PracticeHoldingReflectionCreateRequest(holding.getId(), "동시 복기 시도."));
		};

		var executor = Executors.newFixedThreadPool(2);
		Future<PracticeHoldingReflectionResponse> first;
		Future<PracticeHoldingReflectionResponse> second;
		try {
			first = executor.submit(request);
			second = executor.submit(request);
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			int successCount = 0;
			int conflictCount = 0;
			for (Future<PracticeHoldingReflectionResponse> future : List.of(first, second)) {
				try {
					future.get(10, TimeUnit.SECONDS);
					successCount++;
				} catch (java.util.concurrent.ExecutionException executionException) {
					assertThat(executionException.getCause()).isInstanceOfSatisfying(BusinessException.class,
						exception -> assertThat(exception.getErrorCode())
							.isEqualTo(ErrorCode.PRACTICE_ALREADY_COMPLETED));
					conflictCount++;
				}
			}

			assertThat(successCount).isEqualTo(1);
			assertThat(conflictCount).isEqualTo(1);
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}

		Long reflectionCount = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM practice_market_reflections WHERE user_id = ?", Long.class, user.getId());
		Long completionCount = jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM practice_completions WHERE user_id = ?", Long.class, user.getId());
		assertThat(reflectionCount).isEqualTo(1L);
		assertThat(completionCount).isEqualTo(1L);

		String status = jdbcTemplate.queryForObject(
			"SELECT status FROM practice_progresses WHERE user_id = ? AND tutorial_key = ?", String.class,
			user.getId(), PracticeIntentionService.COIN_TUTORIAL_KEY);
		assertThat(status).isEqualTo("COMPLETED");
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
