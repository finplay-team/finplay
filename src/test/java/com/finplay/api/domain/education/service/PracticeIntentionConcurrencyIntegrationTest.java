package com.finplay.api.domain.education.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.dto.request.PracticeIntentionCreateRequest;
import com.finplay.api.domain.education.dto.response.PracticeIntentionResponse;
import com.finplay.api.domain.education.repository.PracticeIntentionRepository;
import com.finplay.api.domain.favorite.service.FavoriteService;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PracticeIntentionConcurrencyIntegrationTest {

	@Autowired
	private PracticeIntentionService intentionService;
	@Autowired
	private FavoriteService favoriteService;
	@Autowired
	private PracticeIntentionRepository intentionRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	private User user;
	private Instrument instrument;

	@BeforeEach
	void setUp() {
		LocalDateTime now = LocalDateTime.of(2026, 8, 4, 10, 0);
		user = userRepository.saveAndFlush(User.create(
			"practice-race-175@finplay.com", "hash", "practice-race-175", now));
		instrument = instrumentRepository.findByMarketOrderByIdAsc(Market.STOCK).get(0);
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM practice_progresses WHERE user_id = ?", user.getId());
		userRepository.deleteById(user.getId());
	}

	@Test
	void simultaneousFirstIntentionsConvergeToOneProgressAndTwoIntentionsWithoutUniqueException() throws Exception {
		favoriteService.createFavorite(user.getId(), instrument.getId());
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		Callable<PracticeIntentionResponse> request = () -> {
			ready.countDown();
			start.await();
			return intentionService.createIntention(user.getId(), request());
		};
		var executor = Executors.newFixedThreadPool(2);
		try {
			Future<PracticeIntentionResponse> first = executor.submit(request);
			Future<PracticeIntentionResponse> second = executor.submit(request);
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
				.extracting(PracticeIntentionResponse::intentionId).doesNotHaveDuplicates();
		} finally {
			start.countDown();
			shutdownAndAwait(executor);
		}
		assertThat(countProgress()).isEqualTo(1L);
		assertThat(intentionRepository.findByUserId(user.getId())).hasSize(2);
	}

	@Test
	void completedProgressIsNotOverwrittenAndCreatesNoIntention() {
		jdbcTemplate.update("""
			INSERT INTO practice_progresses (user_id, tutorial_key, status, started_at, completed_at)
			VALUES (?, ?, 'COMPLETED', NOW(6), NOW(6))
			""", user.getId(), PracticeIntentionService.TUTORIAL_KEY);
		favoriteService.createFavorite(user.getId(), instrument.getId());

		assertThatThrownBy(() -> intentionService.createIntention(user.getId(), request()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_ALREADY_COMPLETED));
		assertThat(jdbcTemplate.queryForObject("""
			SELECT status FROM practice_progresses WHERE user_id = ? AND tutorial_key = ?
			""", String.class, user.getId(), PracticeIntentionService.TUTORIAL_KEY)).isEqualTo("COMPLETED");
		assertThat(intentionRepository.findByUserId(user.getId())).isEmpty();
	}

	@Test
	void missingFavoriteFailsWithPracticeStepLockedAndLeavesNoProgressRowBehind() {
		assertThatThrownBy(() -> intentionService.createIntention(user.getId(), request()))
			.isInstanceOfSatisfying(BusinessException.class,
				exception -> assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PRACTICE_STEP_LOCKED));

		assertThat(countProgress()).isZero();
		assertThat(intentionRepository.findByUserId(user.getId())).isEmpty();
	}

	private long countProgress() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM practice_progresses WHERE user_id = ?",
			Long.class, user.getId());
	}

	private PracticeIntentionCreateRequest request() {
		return new PracticeIntentionCreateRequest(instrument.getId(), new BigDecimal("2.50000000"),
			new BigDecimal("90.00000000"), new BigDecimal("120.00000000"));
	}

	private void shutdownAndAwait(ExecutorService executor) throws InterruptedException {
		executor.shutdownNow();
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
	}
}
