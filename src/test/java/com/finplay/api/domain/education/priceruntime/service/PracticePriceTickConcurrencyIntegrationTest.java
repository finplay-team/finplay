package com.finplay.api.domain.education.priceruntime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.priceruntime.dto.response.PracticePriceSessionResponse;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSession;
import com.finplay.api.domain.education.priceruntime.entity.PracticePriceSessionStatus;
import com.finplay.api.domain.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.global.config.TestClock;
import com.finplay.api.global.config.TestClockConfig;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfig.class})
class PracticePriceTickConcurrencyIntegrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 11, 10, 0);

	@Autowired
	private PracticePriceTickService practicePriceTickService;
	@Autowired
	private PracticePriceSessionService practicePriceSessionService;
	@Autowired
	private PracticePriceSessionRepository practicePriceSessionRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private TestClock clock;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private Long createdUserId;
	private Long createdInstrumentId;

	@BeforeEach
	void setUp() {
		clock.set(NOW);
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM practice_price_sessions WHERE user_id = ?", createdUserId);
		jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", createdInstrumentId);
		jdbcTemplate.update("DELETE FROM users WHERE id = ?", createdUserId);
	}

	@Test
	void concurrentAdvanceTickRequestsForSameExpectedTickResultInExactlyOneWinner() throws Exception {
		User user = createUser("tick-race");
		Instrument instrument = createCryptoInstrument("tick-race");
		createdUserId = user.getId();
		createdInstrumentId = instrument.getId();
		PracticePriceSession session = practicePriceSessionRepository.saveAndFlush(
			PracticePriceSession.create(
				user.getId(), instrument.getId(), 555L, (short)PracticePriceGeneratorV1.VERSION,
				new BigDecimal("10000.00000000"), NOW));
		Long sessionId = session.getId();

		AtomicReference<Exception> exceptionA = new AtomicReference<>();
		AtomicReference<Exception> exceptionB = new AtomicReference<>();
		runConcurrently(
			() -> {
				try {
					practicePriceTickService.advanceTick(user.getId(), sessionId, 1);
				} catch (Exception ex) {
					exceptionA.set(ex);
				}
			},
			() -> {
				try {
					practicePriceTickService.advanceTick(user.getId(), sessionId, 1);
				} catch (Exception ex) {
					exceptionB.set(ex);
				}
			});

		boolean succeededA = exceptionA.get() == null;
		boolean succeededB = exceptionB.get() == null;
		assertThat(succeededA ^ succeededB).as("정확히 한쪽만 성공해야 한다").isTrue();

		Exception loserException = succeededA ? exceptionB.get() : exceptionA.get();
		assertThat(loserException).isInstanceOf(BusinessException.class);
		assertThat(((BusinessException)loserException).getErrorCode())
			.isEqualTo(ErrorCode.PRACTICE_PRICE_TICK_CONFLICT);

		PracticePriceSession afterRace = practicePriceSessionRepository.findById(sessionId).orElseThrow();
		assertThat(afterRace.getCurrentTick()).isEqualTo((short)1);
		assertThat(afterRace.getStatus()).isEqualTo(PracticePriceSessionStatus.ACTIVE);
	}

	@Test
	void advancingAllTicksToNinetyNineCompletesSessionAndRejectsFurtherTicks() {
		User user = createUser("tick-complete");
		Instrument instrument = createCryptoInstrument("tick-complete");
		createdUserId = user.getId();
		createdInstrumentId = instrument.getId();
		PracticePriceSession session = practicePriceSessionRepository.saveAndFlush(
			PracticePriceSession.create(
				user.getId(), instrument.getId(), 777L, (short)PracticePriceGeneratorV1.VERSION,
				new BigDecimal("10000.00000000"), NOW));
		Long sessionId = session.getId();

		PracticePriceSessionResponse last = null;
		for (int tick = 1; tick <= 99; tick++) {
			last = practicePriceTickService.advanceTick(user.getId(), sessionId, tick);
		}

		assertThat(last).isNotNull();
		assertThat(last.currentTick()).isEqualTo(99);
		assertThat(last.status()).isEqualTo(PracticePriceSessionStatus.COMPLETED);
		assertThat(last.completedAt()).isEqualTo(NOW);

		PracticePriceSessionResponse fetched = practicePriceSessionService.getSession(user.getId(), sessionId);
		assertThat(fetched.currentPrice()).isEqualByComparingTo(last.currentPrice());
		assertThat(fetched.status()).isEqualTo(PracticePriceSessionStatus.COMPLETED);

		assertThatThrownBy(() -> practicePriceTickService.advanceTick(user.getId(), sessionId, 100))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode())
					.isEqualTo(ErrorCode.PRACTICE_PRICE_SESSION_CLOSED));
	}

	private void runConcurrently(ThrowingRunnable actionA, ThrowingRunnable actionB) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<Void> futureA = executor.submit(toCallable(actionA, ready, start));
			Future<Void> futureB = executor.submit(toCallable(actionB, ready, start));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			futureA.get(15, TimeUnit.SECONDS);
			futureB.get(15, TimeUnit.SECONDS);
		} finally {
			start.countDown();
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}
	}

	private Callable<Void> toCallable(ThrowingRunnable action, CountDownLatch ready, CountDownLatch start) {
		return () -> {
			ready.countDown();
			start.await();
			action.run();
			return null;
		};
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void run() throws Exception;
	}

	private User createUser(String scenario) {
		return userRepository.saveAndFlush(
			User.create(uniqueEmail(scenario), "hash", uniqueNickname(scenario), NOW));
	}

	private Instrument createCryptoInstrument(String scenario) {
		String symbol = "PPT" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, scenario + "코인", new BigDecimal("0.00000001"), 0L, true, NOW));
	}

	private static String uniqueEmail(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "") + "@finplay.com";
	}

	private static String uniqueNickname(String scenario) {
		return scenario + "-" + UUID.randomUUID().toString().replace("-", "");
	}
}
