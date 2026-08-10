// next-tick 진행의 비관 잠금 경합·tick 99 완료 전이·종료 후 거부를 실제 MySQL로 검증하는 통합 테스트다.
package com.finplay.api.education.priceruntime.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.common.BusinessException;
import com.finplay.api.common.ErrorCode;
import com.finplay.api.common.TestClock;
import com.finplay.api.common.TestClockConfig;
import com.finplay.api.education.priceruntime.domain.PracticePriceSession;
import com.finplay.api.education.priceruntime.domain.PracticePriceSessionStatus;
import com.finplay.api.education.priceruntime.dto.response.PracticePriceSessionResponse;
import com.finplay.api.education.priceruntime.repository.PracticePriceSessionRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
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

// LimitOrderConcurrencyIntegrationTest와 동일하게 @Transactional을 클래스에 두지 않는다 — runConcurrently가
// 두 스레드에서 각각 advanceTick(@Transactional)을 실제 별개 커넥션으로 실행해야 PESSIMISTIC_WRITE 락 경합을
// 실측할 수 있다.
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

	// createUser/createCryptoInstrument는 @Transactional 없이(동시성 검증을 위해, 클래스 상단 주석 참고) 실제
	// 커밋한다. 정리하지 않으면 education 패키지가 market 패키지보다 알파벳순으로 먼저 실행돼 InstrumentRepositoryTest의
	// "정확히 28건" 단정을 깨뜨린다(PracticeHoldingReflectionConcurrencyIntegrationTest와 동일 근거).
	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM practice_price_sessions WHERE user_id = ?", createdUserId);
		jdbcTemplate.update("DELETE FROM instruments WHERE id = ?", createdInstrumentId);
		jdbcTemplate.update("DELETE FROM users WHERE id = ?", createdUserId);
	}

	// 같은 세션에 두 스레드가 동시에 expectedTick=1로 advanceTick을 호출한다. 소유자 스코프 비관 잠금
	// (findByIdAndUserIdForUpdate)이 두 요청을 직렬화하므로, 먼저 커밋한 쪽은 currentTick=1로 성공하고 나중
	// 쪽은 갱신된 currentTick(=1)을 보고 expectedTick(=1)이 currentTick+1(=2)과 달라 409
	// PRACTICE_PRICE_TICK_CONFLICT로 실패해야 한다 — 데드락·타임아웃 없이 완료됨이 그 자체로 잠금이 안전하다는 증거다.
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

	// tick 1부터 99까지 순차 진행한 뒤 세션이 COMPLETED로 전이하고 completedAt이 고정 Clock 값과 일치하는지,
	// 그 이후 next-tick 요청이 PRACTICE_PRICE_SESSION_CLOSED로 거부되는지 검증한다. 재조회
	// (getSession→verifyPriceSeriesConsistency)로 저장된 currentPrice가 생성기 재계산과 일치함도 함께 확인한다.
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

	// LimitOrderConcurrencyIntegrationTest의 runConcurrently 관례를 재사용한다 — 두 액션을 준비 완료(ready) 후
	// 동시에 출발(start)시켜 실제 락 경합을 재현하고, 어느 쪽이든 예상치 못한 예외(데드락 등)를 던지면 그대로
	// 전파해 테스트를 실패시킨다.
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
