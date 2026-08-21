// 튜토리얼 attempt·위험 스냅샷·주문 실행 귀속의 MySQL 영속 제약과 잠금 쿼리를 검증한다.
package com.finplay.api.domain.education.marketpractice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.account.entity.Account;
import com.finplay.api.domain.account.repository.AccountRepository;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.repository.InstrumentRepository;
import com.finplay.api.domain.order.entity.Order;
import com.finplay.api.domain.order.entity.OrderSide;
import com.finplay.api.domain.order.entity.OrderType;
import com.finplay.api.domain.order.entity.Trade;
import com.finplay.api.domain.order.repository.OrderRepository;
import com.finplay.api.domain.order.repository.TradeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class PracticeAttemptRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 14, 10, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private InstrumentRepository instrumentRepository;

	@Autowired
	private PracticeAttemptRepository practiceAttemptRepository;

	@Autowired
	private PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private TradeRepository tradeRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	private User user;
	private Account account;
	private Instrument instrument;
	private PracticeAttempt attempt;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("attempt@finplay.com", "hash", "attempt-user", NOW));
		account = accountRepository.saveAndFlush(
			Account.create(user, Market.CRYPTO, NOW));
		instrument = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, "ATTEMPT-CRYPTO", "attempt 테스트 코인", BigDecimal.ONE, 5_000L, true, NOW));
		attempt = practiceAttemptRepository.saveAndFlush(PracticeAttempt.create(user.getId(), Market.CRYPTO, NOW));
	}

	// 이슈 #491 — insertIfAbsent가 ON DUPLICATE KEY UPDATE라 "중복이면 기존 행을 한 글자도 바꾸지 않는다"가
	// 성립해야 한다. 이 PR이 실제로 회귀했던 지점이 정확히 이 구문의 semantics다(자매 리포지터리의
	// PracticeRepositoryTest.insertIfAbsentPreservesExistingCompletedStatusAndTimestamps와 같은 형태).
	@Test
	@DisplayName("insertIfAbsent는 행이 이미 있으면 기존 값·타임스탬프를 그대로 둔다")
	void insertIfAbsentPreservesExistingRow() {
		jdbcTemplate.update("""
			UPDATE practice_attempts SET run_number = 7, created_at = ?, updated_at = ?
			WHERE user_id = ? AND market = 'CRYPTO'
			""", NOW.minusDays(2), NOW.minusDays(1), user.getId());

		practiceAttemptRepository.insertIfAbsent(user.getId(), Market.CRYPTO.name(), NOW.plusDays(1));
		entityManager.flush();
		entityManager.clear();

		java.util.Map<String, Object> row = jdbcTemplate.queryForMap("""
			SELECT run_number, status, created_at, updated_at FROM practice_attempts
			WHERE user_id = ? AND market = 'CRYPTO'
			""", user.getId());
		assertThat(row.get("run_number")).isEqualTo(7L);
		assertThat(row.get("status")).isEqualTo("SELECTING_INSTRUMENT");
		assertThat(row.get("created_at")).isEqualTo(NOW.minusDays(2));
		assertThat(row.get("updated_at")).isEqualTo(NOW.minusDays(1));
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM practice_attempts WHERE user_id = ? AND market = 'CRYPTO'",
			Long.class, user.getId())).isEqualTo(1L);
	}

	@Test
	@DisplayName("insertIfAbsent는 행이 없으면 run 1·종목 미선택 상태로 만든다")
	void insertIfAbsentCreatesRowWhenAbsent() {
		practiceAttemptRepository.insertIfAbsent(user.getId(), Market.STOCK.name(), NOW);
		entityManager.flush();
		entityManager.clear();

		PracticeAttempt created = practiceAttemptRepository
			.findByUserIdAndMarket(user.getId(), Market.STOCK).orElseThrow();
		assertThat(created.getRunNumber()).isEqualTo(1L);
		assertThat(created.getStatus()).isEqualTo(PracticeAttemptStatus.SELECTING_INSTRUMENT);
		assertThat(created.getInstrument()).isNull();
	}

	@Test
	@DisplayName("같은 사용자는 시장별 attempt 하나만 가질 수 있다")
	void savingDuplicateUserAndMarketFailsWithUniqueConstraint() {
		PracticeAttempt stockAttempt = practiceAttemptRepository.saveAndFlush(
			PracticeAttempt.create(user.getId(), Market.STOCK, NOW.plusSeconds(1)));

		assertThat(stockAttempt.getId()).isNotNull();
		assertThat(practiceAttemptRepository.findByUserIdAndMarket(user.getId(), Market.CRYPTO))
			.contains(attempt);
		assertThatThrownBy(() -> practiceAttemptRepository.saveAndFlush(
			PracticeAttempt.create(user.getId(), Market.CRYPTO, NOW.plusSeconds(2))))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("사용자와 시장으로 attempt를 비관 잠금 조회한다")
	void findByUserIdAndMarketForUpdateReturnsAttempt() {
		entityManager.clear();

		var result = practiceAttemptRepository.findByUserIdAndMarketForUpdate(user.getId(), Market.CRYPTO);

		assertThat(result).isPresent();
		assertThat(result.get().getId()).isEqualTo(attempt.getId());
		assertThat(result.get().getRunNumber()).isEqualTo(1L);
	}

	// 기존 UNIQUE(attempt_id, run_number)는 삭제됐다. 같은 진입 순번의 중복을 막는 것은 이제
	// uk_practice_risk_snapshots_attempt_run_seq다 — 보호 범위가 같은지 확인한다.
	@Test
	@DisplayName("같은 attempt·실행 세대·진입 순번에는 위험 스냅샷 하나만 저장된다")
	void savingDuplicateEntrySequenceRiskSnapshotFailsWithUniqueConstraint() {
		Trade buyTrade = createBuyTrade("risk-snapshot-order");
		practiceRiskSnapshotRepository.saveAndFlush(createRiskSnapshot(buyTrade, NOW.plusSeconds(1)));

		assertThatThrownBy(() -> practiceRiskSnapshotRepository.saveAndFlush(
			createRiskSnapshot(buyTrade, NOW.plusSeconds(2))))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// 이 PR이 여는 것 — 재진입(손절 후 재매수)의 전제다.
	@Test
	@DisplayName("같은 실행 세대라도 진입 순번이 다르면 위험 스냅샷을 여럿 저장할 수 있다")
	void savingMultipleEntriesInSameRunSucceedsWhenEntrySequenceDiffers() {
		Trade firstBuy = createBuyTrade("reentry-first-order");
		Trade secondBuy = createBuyTrade("reentry-second-order");
		practiceRiskSnapshotRepository.saveAndFlush(createRiskSnapshot(firstBuy, NOW.plusSeconds(1)));
		PracticeRiskSnapshot reentry = createRiskSnapshot(secondBuy, NOW.plusSeconds(2));
		ReflectionTestUtils.setField(reentry, "entrySequence", 2);

		practiceRiskSnapshotRepository.saveAndFlush(reentry);
		entityManager.clear();

		assertThat(practiceRiskSnapshotRepository
			.countByAttemptIdAndRunNumber(attempt.getId(), attempt.getRunNumber())).isEqualTo(2L);
		var latest = practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(attempt.getId(), attempt.getRunNumber());
		var first = practiceRiskSnapshotRepository.findByAttemptIdAndRunNumberAndEntrySequence(
			attempt.getId(), attempt.getRunNumber(), PracticeRiskSnapshot.FIRST_ENTRY_SEQUENCE);
		assertThat(latest).isPresent();
		assertThat(latest.get().getEntrySequence()).isEqualTo(2);
		assertThat(first).isPresent();
		assertThat(first.get().getEntrySequence()).isEqualTo(PracticeRiskSnapshot.FIRST_ENTRY_SEQUENCE);
	}

	@Test
	@DisplayName("위험 스냅샷은 entry_sequence 기본값 1로 저장된다")
	void riskSnapshotIsStoredWithFirstEntrySequenceByDefault() {
		Trade buyTrade = createBuyTrade("entry-sequence-default-order");
		PracticeRiskSnapshot saved = practiceRiskSnapshotRepository.saveAndFlush(
			createRiskSnapshot(buyTrade, NOW.plusSeconds(1)));
		entityManager.clear();

		PracticeRiskSnapshot reloaded = practiceRiskSnapshotRepository.findById(saved.getId()).orElseThrow();

		assertThat(reloaded.getEntrySequence()).isEqualTo(PracticeRiskSnapshot.FIRST_ENTRY_SEQUENCE);
	}

	// 재진입이 도입되기 전이라 한 실행 세대의 진입은 아직 하나뿐이다(기존 UNIQUE가 둘째를 막는다).
	// 여기서는 두 조회가 같은 행을 가리키는 회귀만 잠근다 — 진입이 여럿일 때 갈라지는 것은
	// 기존 UNIQUE를 삭제한 뒤에야 검증할 수 있다.
	@Test
	@DisplayName("진입이 하나면 최신 진입 조회와 첫 진입 조회가 같은 스냅샷을 돌려준다")
	void latestAndFirstEntryLookupsReturnSameSnapshotWhenSingleEntry() {
		Trade buyTrade = createBuyTrade("entry-sequence-lookup-order");
		PracticeRiskSnapshot saved = practiceRiskSnapshotRepository.saveAndFlush(
			createRiskSnapshot(buyTrade, NOW.plusSeconds(1)));
		entityManager.clear();

		var latest = practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(attempt.getId(), attempt.getRunNumber());
		var first = practiceRiskSnapshotRepository.findByAttemptIdAndRunNumberAndEntrySequence(
			attempt.getId(), attempt.getRunNumber(), PracticeRiskSnapshot.FIRST_ENTRY_SEQUENCE);

		assertThat(latest).isPresent();
		assertThat(latest.get().getId()).isEqualTo(saved.getId());
		assertThat(first).isPresent();
		assertThat(first.get().getId()).isEqualTo(saved.getId());
		assertThat(practiceRiskSnapshotRepository
			.countByAttemptIdAndRunNumber(attempt.getId(), attempt.getRunNumber())).isEqualTo(1L);
	}

	@Test
	@DisplayName("entry_sequence가 0 이하면 CHECK 제약이 거부한다")
	void savingNonPositiveEntrySequenceFailsWithCheckConstraint() {
		Trade buyTrade = createBuyTrade("entry-sequence-check-order");
		PracticeRiskSnapshot invalid = createRiskSnapshot(buyTrade, NOW.plusSeconds(1));
		ReflectionTestUtils.setField(invalid, "entrySequence", 0);

		assertThatThrownBy(() -> practiceRiskSnapshotRepository.saveAndFlush(invalid))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("스냅샷이 없으면 두 조회 모두 비어 있고 개수는 0이다")
	void entryLookupsReturnEmptyWhenNoSnapshotExists() {
		assertThat(practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(attempt.getId(), attempt.getRunNumber()))
			.isEmpty();
		assertThat(practiceRiskSnapshotRepository.findByAttemptIdAndRunNumberAndEntrySequence(
			attempt.getId(), attempt.getRunNumber(), PracticeRiskSnapshot.FIRST_ENTRY_SEQUENCE)).isEmpty();
		assertThat(practiceRiskSnapshotRepository
			.countByAttemptIdAndRunNumber(attempt.getId(), attempt.getRunNumber())).isZero();
	}

	// 대본 위치는 벽시계에서 파생할 수 없다 — 대기 구간은 되감기고 재진입은 점프하므로 단조가 아니다.
	// 재기동 후에도 같은 위치가 나와야 하므로(041 SCENARIO-002) 영속과 재조회를 함께 확인한다.
	@Test
	@DisplayName("대본 위치와 진행 중 봉 3값이 영속되고 재조회된다")
	void scenarioProgressColumnsRoundTrip() {
		putScenarioProgress(attempt, 57L, "9800.00000000", "10180.00000000", "9750.00000000");
		practiceAttemptRepository.saveAndFlush(attempt);
		entityManager.clear();

		PracticeAttempt reloaded = practiceAttemptRepository.findById(attempt.getId()).orElseThrow();

		assertThat(reloaded.getScenarioStageId()).isEqualTo("ACT2_RUMOR");
		assertThat(reloaded.getScenarioStageElapsedSeconds()).isEqualTo(57L);
		assertThat(reloaded.getScenarioCandleOpen()).isEqualByComparingTo("9800.00000000");
		assertThat(reloaded.getScenarioCandleHigh()).isEqualByComparingTo("10180.00000000");
		assertThat(reloaded.getScenarioCandleLow()).isEqualByComparingTo("9750.00000000");
	}

	@Test
	@DisplayName("생성기 버전 1 attempt는 대본 컬럼이 NULL인 채로 저장된다")
	void versionOneAttemptKeepsScenarioColumnsNull() {
		entityManager.clear();

		PracticeAttempt reloaded = practiceAttemptRepository.findById(attempt.getId()).orElseThrow();

		assertThat(reloaded.getScenarioStageId()).isNull();
		assertThat(reloaded.getScenarioCandleOpen()).isNull();
	}

	@Test
	@DisplayName("진행 중 봉 3값 중 일부만 채우면 CHECK 제약이 거부한다")
	void savingPartialScenarioCandleFailsWithCheckConstraint() {
		putScenarioProgress(attempt, 3L, "9800.00000000", "10180.00000000", null);

		assertThatThrownBy(() -> practiceAttemptRepository.saveAndFlush(attempt))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("시가가 고가·저가 밖에 있으면 CHECK 제약이 거부한다")
	void savingScenarioCandleWithOpenOutsideRangeFailsWithCheckConstraint() {
		putScenarioProgress(attempt, 3L, "10500.00000000", "10180.00000000", "9750.00000000");

		assertThatThrownBy(() -> practiceAttemptRepository.saveAndFlush(attempt))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("구간 경과 초가 음수면 CHECK 제약이 거부한다")
	void savingNegativeScenarioElapsedSecondsFailsWithCheckConstraint() {
		putScenarioProgress(attempt, -1L, null, null, null);

		assertThatThrownBy(() -> practiceAttemptRepository.saveAndFlush(attempt))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// 대본 식별자는 커서만큼 내구적이어야 한다 — 재기동·재조회 뒤에도 같은 대본을 봐야 가격이 이어진다.
	@Test
	@DisplayName("대본 식별자가 영속되고 재조회된다")
	void scenarioScriptIdColumnRoundTrips() {
		selectScenarioInstrument(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		practiceAttemptRepository.saveAndFlush(attempt);
		entityManager.clear();

		PracticeAttempt reloaded = practiceAttemptRepository.findById(attempt.getId()).orElseThrow();

		assertThat(reloaded.scenarioScriptId()).isEqualTo(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		assertThat(readScriptIdColumn()).isEqualTo("CRYPTO_ORDER_BASICS_V1");
	}

	/**
	 * <b>파생 접근자로는 이 회귀를 잡을 수 없다.</b> {@code restart()}가 {@code generatorVersion}도 함께
	 * 지우므로, 컬럼에 값이 그대로 남아 있어도 {@code scenarioScriptId()}는 {@code null}을 돌려준다.
	 * 컬럼이 안 지워지면 재시작한 사용자가 다음 종목 선택에서 이전 대본을 물려받으므로 DB 값을 직접 읽는다.
	 */
	@Test
	@DisplayName("재시작하면 대본 식별자 컬럼이 DB에서 NULL이 된다")
	void restartNullsTheScenarioScriptIdColumnInTheDatabase() {
		selectScenarioInstrument(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		practiceAttemptRepository.saveAndFlush(attempt);
		assertThat(readScriptIdColumn()).isEqualTo("CRYPTO_ORDER_BASICS_V1");

		attempt.restart(NOW.plusMinutes(1));
		practiceAttemptRepository.saveAndFlush(attempt);
		entityManager.clear();

		assertThat(readScriptIdColumn()).isNull();
	}

	// 049 배포 순간 진행 중이던 행이다 — 백필하지 않기로 했으므로 이 조합이 실제로 DB에 남는다.
	@Test
	@DisplayName("버전 2 + 식별자 NULL 행은 041 대본으로 읽힌다")
	void scriptRunWithNullScriptIdColumnReadsAsTheStoryScript() {
		selectScenarioInstrument(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
		practiceAttemptRepository.saveAndFlush(attempt);
		entityManager.createNativeQuery(
			"UPDATE practice_attempts SET scenario_script_id = NULL, scenario_stage_id = 'ACT2_RUMOR' "
				+ "WHERE id = :attemptId")
			.setParameter("attemptId", attempt.getId())
			.executeUpdate();
		entityManager.clear();

		PracticeAttempt reloaded = practiceAttemptRepository.findById(attempt.getId()).orElseThrow();

		assertThat(readScriptIdColumn()).isNull();
		assertThat(reloaded.getScenarioStageId()).isEqualTo("ACT2_RUMOR");
		assertThat(reloaded.scenarioScriptId()).isEqualTo(TutorialScenarioScriptId.CRYPTO_STORY_V1);
	}

	// 열거형 이름이 곧 컬럼 값이다(VARCHAR(32)). 넘치는 이름을 더하면 그 대본을 쓰는 실행만 저장에서
	// 터지므로, 이름 길이를 스키마 폭과 함께 고정한다.
	@Test
	@DisplayName("모든 대본 식별자 이름이 VARCHAR(32) 안에 들어간다")
	void everyScriptIdNameFitsTheColumnWidth() {
		assertThat(TutorialScenarioScriptId.values())
			.allSatisfy(scriptId -> assertThat(scriptId.name().length()).isLessThanOrEqualTo(32));
	}

	private void selectScenarioInstrument(TutorialScenarioScriptId scriptId) {
		attempt.selectInstrument(
			instrument, NOW, NOW.toLocalDate(), 123_456_789L, (short)2, scriptId, NOW);
	}

	private String readScriptIdColumn() {
		return (String)entityManager
			.createNativeQuery("SELECT scenario_script_id FROM practice_attempts WHERE id = :attemptId")
			.setParameter("attemptId", attempt.getId())
			.getSingleResult();
	}

	private static void putScenarioProgress(
		PracticeAttempt target, long elapsedSeconds, String open, String high, String low) {
		ReflectionTestUtils.setField(target, "scenarioStageId", "ACT2_RUMOR");
		ReflectionTestUtils.setField(target, "scenarioStageElapsedSeconds", elapsedSeconds);
		ReflectionTestUtils.setField(target, "scenarioCandleOpen", open == null ? null : new BigDecimal(open));
		ReflectionTestUtils.setField(target, "scenarioCandleHigh", high == null ? null : new BigDecimal(high));
		ReflectionTestUtils.setField(target, "scenarioCandleLow", low == null ? null : new BigDecimal(low));
	}

	@Test
	@DisplayName("attempt 주문은 attempt ID와 실행 세대를 함께 저장한다")
	void practiceAttemptOrderStoresBothAttributionColumns() {
		Order order = orderRepository.saveAndFlush(Order.createForPracticeAttempt(
			user,
			account,
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			BigDecimal.ONE,
			attempt.getId(),
			attempt.getRunNumber(),
			"attributed-order",
			"a".repeat(64),
			NOW));
		entityManager.clear();

		Order saved = orderRepository.findById(order.getId()).orElseThrow();

		assertThat(saved.getPracticeAttemptId()).isEqualTo(attempt.getId());
		assertThat(saved.getPracticeAttemptRunNumber()).isEqualTo(attempt.getRunNumber());
	}

	@Test
	@DisplayName("attempt ID만 있는 주문은 DB check constraint가 거부한다")
	void orderWithAttemptIdOnlyFailsWithCheckConstraint() {
		Order ordinaryOrder = createOrdinaryOrder("attempt-id-only");

		assertThatThrownBy(() -> entityManager.createNativeQuery(
			"UPDATE orders SET practice_attempt_id = :attemptId WHERE id = :orderId")
			.setParameter("attemptId", attempt.getId())
			.setParameter("orderId", ordinaryOrder.getId())
			.executeUpdate())
			.isInstanceOf(PersistenceException.class);
	}

	@Test
	@DisplayName("실행 세대만 있는 주문은 DB check constraint가 거부한다")
	void orderWithRunNumberOnlyFailsWithCheckConstraint() {
		Order ordinaryOrder = createOrdinaryOrder("run-number-only");

		assertThatThrownBy(() -> entityManager.createNativeQuery(
			"UPDATE orders SET practice_attempt_run_number = 1 WHERE id = :orderId")
			.setParameter("orderId", ordinaryOrder.getId())
			.executeUpdate())
			.isInstanceOf(PersistenceException.class);
	}

	@Test
	@DisplayName("기존 일반 주문은 attempt 귀속 없이 저장된다")
	void ordinaryOrderRemainsCompatibleWithNullAttemptAttribution() {
		Order order = createOrdinaryOrder("ordinary-order");
		entityManager.clear();

		Order saved = orderRepository.findById(order.getId()).orElseThrow();

		assertThat(saved.getPracticeAttemptId()).isNull();
		assertThat(saved.getPracticeAttemptRunNumber()).isNull();
	}

	/**
	 * 진입 대조 조회가 매수 체결과 <b>그 주문까지</b> 쿼리 한 번으로 읽는지 본다(이슈 #503).
	 *
	 * <p>{@code buyOrderType}이 {@code snapshot.getBuyTrade().getOrder().getOrderType()}이라
	 * {@code @EntityGraph}가 빠지면 진입 하나마다 조회가 두 번씩 붙는다. 그런데 <b>그래프가 조용히
	 * 무시돼도 기능은 그대로 동작해서</b> 다른 어떤 테스트도 이것을 잡지 못한다 — 유일한 호출부의
	 * 단위 테스트는 리포지터리를 mock하고, 통합 테스트는 같은 트랜잭션이라 이미 1차 캐시에 올라와 있다.
	 * {@code BuyTradeJournalRepositoryTest}의 Statistics 선례를 따른다.
	 */
	@Test
	@DisplayName("진입 대조 조회는 매수 체결과 그 주문까지 쿼리 1회로 읽는다")
	void findByAttemptIdAndRunNumberOrderByEntrySequenceAscFetchesBuyTradeAndItsOrderInOneQuery() {
		for (int sequence = 1; sequence <= 3; sequence++) {
			practiceRiskSnapshotRepository.saveAndFlush(PracticeRiskSnapshot.create(
				attempt, attempt.getRunNumber(), sequence, ExitPreset.BALANCED,
				createBuyTrade("graph-idem-" + sequence), BigDecimal.valueOf(100),
				BigDecimal.valueOf(97), BigDecimal.valueOf(105), null, NOW));
		}
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();

		List<PracticeRiskSnapshot> snapshots = practiceRiskSnapshotRepository
			.findByAttemptIdAndRunNumberOrderByEntrySequenceAsc(attempt.getId(), attempt.getRunNumber());

		assertThat(snapshots)
			.hasSize(3)
			.allSatisfy(
				snapshot -> assertThat(snapshot.getBuyTrade().getOrder().getOrderType()).isEqualTo(OrderType.MARKET));
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
	}

	private Order createOrdinaryOrder(String idempotencyKey) {
		return orderRepository.saveAndFlush(Order.create(
			user,
			account,
			instrument,
			OrderSide.BUY,
			OrderType.MARKET,
			BigDecimal.ONE,
			idempotencyKey,
			"b".repeat(64),
			NOW));
	}

	private Trade createBuyTrade(String idempotencyKey) {
		Order order = createOrdinaryOrder(idempotencyKey);
		return tradeRepository.saveAndFlush(Trade.of(
			order,
			account,
			instrument,
			null,
			OrderSide.BUY,
			BigDecimal.valueOf(100),
			BigDecimal.ONE,
			100L,
			0L,
			null,
			NOW,
			NOW));
	}

	// 프리셋은 실행 세대의 선택값이고 snapshot의 프리셋은 그 진입에 확정된 값이다. 둘 다 nullable이며
	// null은 "미선택"으로 기본 프리셋과 같게 해석한다(042 EXITPRESET-002) — 그래서 백필하지 않는다.
	//
	// 세 값을 전부 실제로 저장한다. 하나만 넣으면 열거형에 값을 더하고 V51의 CHECK를 빠뜨린 변경이 런타임에야
	// 드러난다 — 값 집합을 스키마에서도 막기로 한 이상 그 대가를 테스트가 치러야 한다.
	@ParameterizedTest
	@EnumSource(ExitPreset.class)
	@DisplayName("attempt와 위험 스냅샷의 프리셋이 값마다 영속되고 재조회된다")
	void exitPresetColumnsRoundTrip(ExitPreset preset) {
		ReflectionTestUtils.setField(attempt, "exitPreset", preset);
		practiceAttemptRepository.saveAndFlush(attempt);
		Trade buyTrade = createBuyTrade("exit-preset-order-" + preset.name());
		PracticeRiskSnapshot snapshot = createRiskSnapshot(buyTrade, NOW.plusSeconds(1));
		ReflectionTestUtils.setField(snapshot, "exitPreset", preset);
		PracticeRiskSnapshot savedSnapshot = practiceRiskSnapshotRepository.saveAndFlush(snapshot);
		entityManager.clear();

		assertThat(practiceAttemptRepository.findById(attempt.getId()).orElseThrow().getExitPreset())
			.isEqualTo(preset);
		assertThat(practiceRiskSnapshotRepository.findById(savedSnapshot.getId()).orElseThrow().getExitPreset())
			.isEqualTo(preset);
	}

	@Test
	@DisplayName("프리셋을 고르지 않은 attempt와 기능 도입 전 스냅샷은 프리셋이 NULL인 채로 저장된다")
	void unselectedExitPresetStaysNull() {
		Trade buyTrade = createBuyTrade("exit-preset-null-order");
		// 042 4번부터 새로 만드는 스냅샷은 미선택 사용자도 기본 프리셋으로 채워진다. NULL이 남는 것은
		// 기능 도입 전에 만들어진 행뿐이며, 그 행이 계속 읽히는지 확인하려고 여기서만 null로 만든다.
		PracticeRiskSnapshot savedSnapshot = practiceRiskSnapshotRepository.saveAndFlush(
			createRiskSnapshot(buyTrade, NOW.plusSeconds(1), null));
		entityManager.clear();

		assertThat(practiceAttemptRepository.findById(attempt.getId()).orElseThrow().getExitPreset()).isNull();
		assertThat(practiceRiskSnapshotRepository.findById(savedSnapshot.getId()).orElseThrow().getExitPreset())
			.isNull();
	}

	// 열거형 밖의 값은 엔티티로는 만들 수 없으므로 네이티브 UPDATE로 스키마를 직접 찌른다. V51이 CHECK를
	// 두 테이블에 하나씩 만들었으므로 양쪽을 다 찌른다 — 한쪽만 보면 snapshot 쪽 CHECK를 빠뜨린 수정이
	// 초록으로 통과한다. 제약 이름까지 확인해 FK 같은 다른 이유로 실패한 것을 통과로 세지 않는다.
	@Test
	@DisplayName("정의 밖 프리셋 식별자는 attempt·스냅샷 양쪽에서 DB check constraint가 거부한다")
	void unknownExitPresetFailsWithCheckConstraint() {
		Trade buyTrade = createBuyTrade("exit-preset-check-order");
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository.saveAndFlush(
			createRiskSnapshot(buyTrade, NOW.plusSeconds(1)));

		assertThatThrownBy(() -> entityManager.createNativeQuery(
			"UPDATE practice_attempts SET exit_preset = 'AGGRESSIVE' WHERE id = :attemptId")
			.setParameter("attemptId", attempt.getId())
			.executeUpdate())
			.isInstanceOf(PersistenceException.class)
			.hasMessageContaining("chk_practice_attempts_exit_preset");
		assertThatThrownBy(() -> entityManager.createNativeQuery(
			"UPDATE practice_risk_snapshots SET exit_preset = 'AGGRESSIVE' WHERE id = :snapshotId")
			.setParameter("snapshotId", snapshot.getId())
			.executeUpdate())
			.isInstanceOf(PersistenceException.class)
			.hasMessageContaining("chk_practice_risk_snapshots_exit_preset");
	}

	private PracticeRiskSnapshot createRiskSnapshot(Trade buyTrade, LocalDateTime createdAt) {
		return createRiskSnapshot(buyTrade, createdAt, ExitPreset.BALANCED);
	}

	private PracticeRiskSnapshot createRiskSnapshot(
		Trade buyTrade, LocalDateTime createdAt, ExitPreset exitPreset) {
		return PracticeRiskSnapshot.create(
			attempt,
			attempt.getRunNumber(),
			PracticeRiskSnapshot.FIRST_ENTRY_SEQUENCE,
			exitPreset,
			buyTrade,
			new BigDecimal("100.00000000"),
			new BigDecimal("97.00000000"),
			new BigDecimal("105.00000000"),
			null,
			createdAt);
	}

	// 049 ORDERBASICS-023 — 진입이 열릴 때 대본 식별자를 스냅샷에 고정한다. 컬럼이 실제로 왕복하는지는
	// 서비스 단위 테스트가 아니라 여기서만 잡힌다(mock 리포지터리는 컬럼 매핑을 검증하지 않는다).
	@Test
	@DisplayName("위험 스냅샷의 대본 식별자 컬럼이 영속되고 재조회된다")
	void scenarioScriptIdColumnRoundTripsOnRiskSnapshot() {
		Trade buyTrade = createBuyTrade("risk-snapshot-script-id");
		PracticeRiskSnapshot snapshot = PracticeRiskSnapshot.create(
			attempt,
			attempt.getRunNumber(),
			PracticeRiskSnapshot.FIRST_ENTRY_SEQUENCE,
			ExitPreset.BALANCED,
			buyTrade,
			new BigDecimal("100.00000000"),
			new BigDecimal("97.00000000"),
			new BigDecimal("105.00000000"),
			TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1,
			NOW);
		Long snapshotId = practiceRiskSnapshotRepository.saveAndFlush(snapshot).getId();
		entityManager.clear();

		PracticeRiskSnapshot reloaded = practiceRiskSnapshotRepository.findById(snapshotId).orElseThrow();

		assertThat(reloaded.getScenarioScriptId()).isEqualTo(TutorialScenarioScriptId.CRYPTO_ORDER_BASICS_V1);
	}
}
