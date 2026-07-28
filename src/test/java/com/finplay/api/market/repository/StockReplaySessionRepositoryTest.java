// 실제 MySQL에서 stock_replay_sessions 저장·조회와 UNIQUE(service_date) 제약을 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.market.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.market.domain.PreparationStatus;
import com.finplay.api.market.domain.StockReplaySession;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class StockReplaySessionRepositoryTest {

	@Autowired
	private StockReplaySessionRepository stockReplaySessionRepository;

	private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 7, 28);
	private static final LocalDate EARLIER_SERVICE_DATE = LocalDate.of(2026, 7, 27);
	private static final LocalDate LATER_SERVICE_DATE = LocalDate.of(2026, 7, 29);
	private static final LocalDate SOURCE_TRADING_DATE = LocalDate.of(2026, 7, 24);
	private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 7, 28, 8, 0, 0);
	private static final LocalDateTime RESOLVED_AT = LocalDateTime.of(2026, 7, 28, 8, 55, 0);

	@Test
	void databaseRejectsDuplicateServiceDate() {
		stockReplaySessionRepository.saveAndFlush(
			StockReplaySession.preparing(SERVICE_DATE, null, CREATED_AT));

		StockReplaySession duplicate = StockReplaySession.ready(
			SERVICE_DATE, SOURCE_TRADING_DATE, RESOLVED_AT, CREATED_AT);

		assertThatThrownBy(() -> stockReplaySessionRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void findByServiceDateReturnsTheSessionForThatDateOnly() {
		stockReplaySessionRepository.save(
			StockReplaySession.ready(SERVICE_DATE, SOURCE_TRADING_DATE, RESOLVED_AT, CREATED_AT));
		stockReplaySessionRepository.save(
			StockReplaySession.preparing(EARLIER_SERVICE_DATE, null, CREATED_AT.minusDays(1)));

		Optional<StockReplaySession> found = stockReplaySessionRepository.findByServiceDate(SERVICE_DATE);

		assertThat(found).isPresent();
		assertThat(found.get().getServiceDate()).isEqualTo(SERVICE_DATE);
		assertThat(found.get().getPreparationStatus()).isEqualTo(PreparationStatus.READY);
		assertThat(found.get().getSourceTradingDate()).isEqualTo(SOURCE_TRADING_DATE);
	}

	@Test
	void findByServiceDateReturnsEmptyWhenNoSessionExistsForThatDate() {
		Optional<StockReplaySession> found = stockReplaySessionRepository.findByServiceDate(SERVICE_DATE);

		assertThat(found).isEmpty();
	}

	@Test
	void findFirstByOrderByServiceDateDescReturnsTheLatestServiceDateSession() {
		stockReplaySessionRepository.save(
			StockReplaySession.preparing(EARLIER_SERVICE_DATE, null, CREATED_AT.minusDays(1)));
		stockReplaySessionRepository.save(
			StockReplaySession.ready(SERVICE_DATE, SOURCE_TRADING_DATE, RESOLVED_AT, CREATED_AT));
		stockReplaySessionRepository.save(
			StockReplaySession.preparing(LATER_SERVICE_DATE, null, CREATED_AT.plusDays(1)));

		Optional<StockReplaySession> latest = stockReplaySessionRepository.findFirstByOrderByServiceDateDesc();

		assertThat(latest).isPresent();
		assertThat(latest.get().getServiceDate()).isEqualTo(LATER_SERVICE_DATE);
	}
}
