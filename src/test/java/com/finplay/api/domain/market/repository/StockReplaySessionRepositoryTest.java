// 실제 MySQL에서 stock_replay_sessions 저장·조회와 UNIQUE(service_date) 제약을 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.domain.market.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.market.entity.PreparationStatus;
import com.finplay.api.domain.market.entity.StockReplaySession;
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

	// 폴백 세션 조회(QUOTE-HOLD-003) 전용 픽스처 날짜 — 위 SERVICE_DATE 계열과 겹치지 않게 별도 구간을 쓴다.
	private static final LocalDate FALLBACK_TODAY = LocalDate.of(2026, 8, 3);
	private static final LocalDate FALLBACK_FAILED_DATE = LocalDate.of(2026, 8, 2);
	private static final LocalDate FALLBACK_CANDIDATE_DATE = LocalDate.of(2026, 8, 1);
	private static final LocalDate FALLBACK_OLDER_CANDIDATE_DATE = LocalDate.of(2026, 7, 31);
	private static final LocalDate FALLBACK_SOURCE_TRADING_DATE = LocalDate.of(2026, 7, 30);
	private static final LocalDate FALLBACK_FAR_PAST_CANDIDATE_DATE = LocalDate.of(2026, 5, 1);

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

	@Test
	void findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDescExcludesTodaysSession() {
		stockReplaySessionRepository.save(StockReplaySession.ready(
			FALLBACK_CANDIDATE_DATE, FALLBACK_SOURCE_TRADING_DATE, RESOLVED_AT, CREATED_AT));
		stockReplaySessionRepository.save(StockReplaySession.ready(
			FALLBACK_TODAY, FALLBACK_SOURCE_TRADING_DATE, RESOLVED_AT, CREATED_AT));

		Optional<StockReplaySession> fallback = stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(
				FALLBACK_TODAY, PreparationStatus.READY);

		assertThat(fallback).isPresent();
		assertThat(fallback.get().getServiceDate()).isEqualTo(FALLBACK_CANDIDATE_DATE);
	}

	@Test
	void findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDescOnlySelectsReadyStatus() {
		stockReplaySessionRepository.save(StockReplaySession.ready(
			FALLBACK_OLDER_CANDIDATE_DATE, FALLBACK_SOURCE_TRADING_DATE, RESOLVED_AT, CREATED_AT));
		stockReplaySessionRepository.save(StockReplaySession.failed(
			FALLBACK_FAILED_DATE, null, RESOLVED_AT, "원본 거래일 데이터를 찾지 못함", CREATED_AT));

		Optional<StockReplaySession> fallback = stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(
				FALLBACK_TODAY, PreparationStatus.READY);

		assertThat(fallback).isPresent();
		assertThat(fallback.get().getServiceDate()).isEqualTo(FALLBACK_OLDER_CANDIDATE_DATE);
		assertThat(fallback.get().getPreparationStatus()).isEqualTo(PreparationStatus.READY);
	}

	@Test
	void findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDescReturnsTheLatestCandidate() {
		stockReplaySessionRepository.save(StockReplaySession.ready(
			FALLBACK_OLDER_CANDIDATE_DATE, FALLBACK_SOURCE_TRADING_DATE, RESOLVED_AT, CREATED_AT));
		stockReplaySessionRepository.save(StockReplaySession.ready(
			FALLBACK_CANDIDATE_DATE, FALLBACK_SOURCE_TRADING_DATE, RESOLVED_AT, CREATED_AT));

		Optional<StockReplaySession> fallback = stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(
				FALLBACK_TODAY, PreparationStatus.READY);

		assertThat(fallback).isPresent();
		assertThat(fallback.get().getServiceDate()).isEqualTo(FALLBACK_CANDIDATE_DATE);
	}

	@Test
	void findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDescReturnsEmptyWhenNoCandidateExists() {
		Optional<StockReplaySession> fallback = stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(
				FALLBACK_TODAY, PreparationStatus.READY);

		assertThat(fallback).isEmpty();
	}

	// QUOTE-HOLD-006: 폴백 탐색에 날짜 상한이 없다 — 유일한 READY 후보가 몇 달 전이고, 그보다 가까운 세션은
	// PREPARING(아직 READY가 아님)뿐이어도 그 먼 과거 READY 세션을 찾아내야 한다.
	@Test
	void findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDescFindsCandidateWithNoUpperBoundOnHowFarBack() {
		stockReplaySessionRepository.save(StockReplaySession.ready(
			FALLBACK_FAR_PAST_CANDIDATE_DATE, FALLBACK_SOURCE_TRADING_DATE, RESOLVED_AT, CREATED_AT));
		stockReplaySessionRepository.save(
			StockReplaySession.preparing(FALLBACK_FAILED_DATE, null, CREATED_AT));

		Optional<StockReplaySession> fallback = stockReplaySessionRepository
			.findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(
				FALLBACK_TODAY, PreparationStatus.READY);

		assertThat(fallback).isPresent();
		assertThat(fallback.get().getServiceDate()).isEqualTo(FALLBACK_FAR_PAST_CANDIDATE_DATE);
	}
}
