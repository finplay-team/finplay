// 튜토리얼 attempt·위험 스냅샷·주문 실행 귀속의 MySQL 영속 제약과 잠금 쿼리를 검증한다.
package com.finplay.api.education.marketpractice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.education.marketpractice.domain.ExitPreset;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.domain.PracticeRiskSnapshot;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.order.repository.OrderRepository;
import com.finplay.api.order.repository.TradeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

	private User user;
	private Account account;
	private Instrument instrument;
	private PracticeAttempt attempt;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("attempt@finplay.com", "hash", "attempt-user", NOW));
		account = accountRepository.saveAndFlush(
			Account.create(user, com.finplay.api.account.domain.Market.CRYPTO, NOW));
		instrument = instrumentRepository.saveAndFlush(Instrument.create(
			Market.CRYPTO, "ATTEMPT-CRYPTO", "attempt 테스트 코인", BigDecimal.ONE, 5_000L, true, NOW));
		attempt = practiceAttemptRepository.saveAndFlush(PracticeAttempt.create(user.getId(), Market.CRYPTO, NOW));
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
	@Test
	@DisplayName("attempt와 위험 스냅샷의 프리셋이 영속되고 재조회된다")
	void exitPresetColumnsRoundTrip() {
		ReflectionTestUtils.setField(attempt, "exitPreset", ExitPreset.CAUTIOUS);
		practiceAttemptRepository.saveAndFlush(attempt);
		Trade buyTrade = createBuyTrade("exit-preset-order");
		PracticeRiskSnapshot snapshot = createRiskSnapshot(buyTrade, NOW.plusSeconds(1));
		ReflectionTestUtils.setField(snapshot, "exitPreset", ExitPreset.RELAXED);
		PracticeRiskSnapshot savedSnapshot = practiceRiskSnapshotRepository.saveAndFlush(snapshot);
		entityManager.clear();

		assertThat(practiceAttemptRepository.findById(attempt.getId()).orElseThrow().getExitPreset())
			.isEqualTo(ExitPreset.CAUTIOUS);
		assertThat(practiceRiskSnapshotRepository.findById(savedSnapshot.getId()).orElseThrow().getExitPreset())
			.isEqualTo(ExitPreset.RELAXED);
	}

	@Test
	@DisplayName("프리셋을 고르지 않은 attempt와 기존 스냅샷은 프리셋이 NULL인 채로 저장된다")
	void unselectedExitPresetStaysNull() {
		Trade buyTrade = createBuyTrade("exit-preset-null-order");
		PracticeRiskSnapshot savedSnapshot = practiceRiskSnapshotRepository.saveAndFlush(
			createRiskSnapshot(buyTrade, NOW.plusSeconds(1)));
		entityManager.clear();

		assertThat(practiceAttemptRepository.findById(attempt.getId()).orElseThrow().getExitPreset()).isNull();
		assertThat(practiceRiskSnapshotRepository.findById(savedSnapshot.getId()).orElseThrow().getExitPreset())
			.isNull();
	}

	// 열거형 밖의 값은 엔티티로는 만들 수 없으므로 네이티브 UPDATE로 스키마를 직접 찌른다.
	@Test
	@DisplayName("정의 밖 프리셋 식별자는 DB check constraint가 거부한다")
	void unknownExitPresetFailsWithCheckConstraint() {
		assertThatThrownBy(() -> entityManager.createNativeQuery(
			"UPDATE practice_attempts SET exit_preset = 'AGGRESSIVE' WHERE id = :attemptId")
			.setParameter("attemptId", attempt.getId())
			.executeUpdate())
			.isInstanceOf(PersistenceException.class);
	}

	private PracticeRiskSnapshot createRiskSnapshot(Trade buyTrade, LocalDateTime createdAt) {
		return PracticeRiskSnapshot.create(
			attempt,
			attempt.getRunNumber(),
			buyTrade,
			new BigDecimal("100.00000000"),
			new BigDecimal("97.00000000"),
			new BigDecimal("105.00000000"),
			createdAt);
	}
}
