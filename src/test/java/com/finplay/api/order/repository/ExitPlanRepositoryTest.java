// V35가 만든 exit_plans의 FK·nullable 조합과 (user_id, intention_instance_key) unique, 조회 메서드를 검증하는 슬라이스 테스트다 (021 plan "데이터 모델").
package com.finplay.api.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.account.domain.Account;
import com.finplay.api.account.repository.AccountRepository;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.education.marketpractice.domain.PracticeAttempt;
import com.finplay.api.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import com.finplay.api.order.domain.ExitPlan;
import com.finplay.api.order.domain.ExitPlanStatus;
import com.finplay.api.order.domain.ExitPriceType;
import com.finplay.api.order.domain.Order;
import com.finplay.api.order.domain.OrderSide;
import com.finplay.api.order.domain.OrderType;
import com.finplay.api.order.domain.Trade;
import com.finplay.api.portfolio.domain.Holding;
import com.finplay.api.portfolio.repository.HoldingRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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
class ExitPlanRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 12, 10, 0);
	private static final BigDecimal QUANTITY = new BigDecimal("1.23456789");
	private static final BigDecimal ENTRY_PRICE = new BigDecimal("100000.00000000");
	private static final BigDecimal STOP_LOSS_PRICE = new BigDecimal("95000.00000000");
	private static final BigDecimal TAKE_PROFIT_PRICE = new BigDecimal("110000.00000000");
	private static final BigDecimal BASELINE_PRICE = new BigDecimal("100500.00000000");

	@Autowired
	private ExitPlanRepository exitPlanRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AccountRepository accountRepository;
	@Autowired
	private InstrumentRepository instrumentRepository;
	@Autowired
	private HoldingRepository holdingRepository;
	@Autowired
	private PracticeAttemptRepository practiceAttemptRepository;
	@Autowired
	private EntityManager entityManager;

	private User user;
	private Account account;
	private Instrument instrument;
	private Holding holding;

	@BeforeEach
	void setUp() {
		user = createUser();
		account = createAccount(user);
		instrument = createInstrument();
		holding = createHolding(account, instrument);
	}

	@Test
	@DisplayName("일반 경로 plan은 intention·buyTrade 계열 컬럼이 모두 null인 채로 저장된다")
	void savesGeneralPathPlanWithNullIntentionAndBuyTradeColumns() {
		ExitPlan saved = exitPlanRepository.saveAndFlush(generalPlan(hash("a")));
		entityManager.clear();

		ExitPlan found = exitPlanRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getHolding().getId()).isEqualTo(holding.getId());
		assertThat(found.getUser().getId()).isEqualTo(user.getId());
		assertThat(found.getInstrument().getId()).isEqualTo(instrument.getId());
		assertThat(found.getIntentionId()).isNull();
		assertThat(found.getIntentionInstanceKey()).isNull();
		assertThat(found.getBuyTrade()).isNull();
		assertThat(found.getStopLossRate()).isNull();
		assertThat(found.getTakeProfitRate()).isNull();
		assertThat(found.getClosedAt()).isNull();
		assertThat(found.getTriggeredOrder()).isNull();
		assertThat(found.getReplaySession()).isNull();
		assertThat(found.getStatus()).isEqualTo(ExitPlanStatus.PENDING);
		assertThat(found.getExitPriceType()).isEqualTo(ExitPriceType.PRICE);
		assertThat(found.getQuantity()).isEqualByComparingTo(QUANTITY);
		assertThat(found.getEntryPrice()).isEqualByComparingTo(ENTRY_PRICE);
		assertThat(found.getStopLossPrice()).isEqualByComparingTo(STOP_LOSS_PRICE);
		assertThat(found.getTakeProfitPrice()).isEqualByComparingTo(TAKE_PROFIT_PRICE);
		assertThat(found.getBaselinePrice()).isEqualByComparingTo(BASELINE_PRICE);
		assertThat(found.getBaselineObservedAt()).isEqualTo(NOW);
		assertThat(found.getReservedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("PERCENT 경로의 rate 컬럼은 DECIMAL(7,4)·DECIMAL(8,4) 정밀도 그대로 저장된다")
	void savesPercentPathRatesWithDeclaredScale() {
		ExitPlan plan = ExitPlan.createGeneral(
			user,
			holding,
			instrument,
			QUANTITY,
			ENTRY_PRICE,
			ExitPriceType.PERCENT,
			new BigDecimal("0.0500"),
			new BigDecimal("0.1000"),
			STOP_LOSS_PRICE,
			TAKE_PROFIT_PRICE,
			BASELINE_PRICE,
			NOW,
			hash("p"),
			NOW);
		ExitPlan saved = exitPlanRepository.saveAndFlush(plan);
		entityManager.clear();

		ExitPlan found = exitPlanRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getStopLossRate()).isEqualByComparingTo("0.0500");
		assertThat(found.getTakeProfitRate()).isEqualByComparingTo("0.1000");
	}

	@Test
	@DisplayName("intention_instance_key가 null인 일반 경로 plan은 같은 사용자에게 여러 건 저장된다 — MySQL은 다중 NULL을 중복으로 보지 않는다")
	void allowsManyGeneralPathPlansForSameUserBecauseIntentionInstanceKeyIsNull() {
		Holding second = createHolding(account, createInstrument());
		Holding third = createHolding(account, createInstrument());

		exitPlanRepository.saveAndFlush(generalPlan(hash("a")));
		exitPlanRepository.saveAndFlush(generalPlanFor(second, hash("b")));
		exitPlanRepository.saveAndFlush(generalPlanFor(third, hash("c")));

		assertThat(exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(user.getId(), ExitPlanStatus.PENDING))
			.hasSize(3)
			.allSatisfy(plan -> assertThat(plan.getIntentionInstanceKey()).isNull());
	}

	@Test
	@DisplayName("같은 사용자가 같은 intention_instance_key로 두 번 저장하면 unique 제약에 걸린다")
	void rejectsDuplicateIntentionInstanceKeyForSameUser() {
		String instanceKey = UUID.randomUUID().toString();
		exitPlanRepository.saveAndFlush(educationalPlan(user, holding, instanceKey, hash("a")));

		Holding second = createHolding(account, createInstrument());
		ExitPlan duplicate = educationalPlan(user, second, instanceKey, hash("b"));

		assertThatThrownBy(() -> exitPlanRepository.saveAndFlush(duplicate))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("unique는 (user_id, intention_instance_key) 복합이므로 다른 사용자는 같은 key를 쓸 수 있다")
	void allowsSameIntentionInstanceKeyForDifferentUsers() {
		String instanceKey = UUID.randomUUID().toString();
		exitPlanRepository.saveAndFlush(educationalPlan(user, holding, instanceKey, hash("a")));

		User other = createUser();
		Account otherAccount = createAccount(other);
		Holding otherHolding = createHolding(otherAccount, createInstrument());

		ExitPlan saved = exitPlanRepository.saveAndFlush(educationalPlan(other, otherHolding, instanceKey, hash("b")));

		assertThat(saved.getId()).isNotNull();
	}

	@Test
	@DisplayName("holding 없이는 plan을 저장할 수 없다 — holding_id는 두 경로 공통 NOT NULL이다")
	void rejectsPlanWithoutHolding() {
		ExitPlan noHolding = ExitPlan.createGeneral(
			user,
			null,
			instrument,
			QUANTITY,
			ENTRY_PRICE,
			ExitPriceType.PRICE,
			null,
			null,
			STOP_LOSS_PRICE,
			TAKE_PROFIT_PRICE,
			BASELINE_PRICE,
			NOW,
			hash("a"),
			NOW);

		assertThatThrownBy(() -> exitPlanRepository.saveAndFlush(noHolding))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("교육 경로 plan은 intention·buyTrade snapshot을 함께 저장한다")
	void savesEducationalPathPlanWithIntentionAndBuyTradeSnapshot() {
		Trade buyTrade = createBuyTrade();
		String instanceKey = UUID.randomUUID().toString();
		ExitPlan plan = ExitPlan.createEducational(
			user,
			holding,
			42L,
			instanceKey,
			buyTrade,
			instrument,
			QUANTITY,
			ENTRY_PRICE,
			ExitPriceType.PRICE,
			null,
			null,
			STOP_LOSS_PRICE,
			TAKE_PROFIT_PRICE,
			BASELINE_PRICE,
			NOW,
			hash("e"),
			NOW);
		ExitPlan saved = exitPlanRepository.saveAndFlush(plan);
		entityManager.clear();

		ExitPlan found = exitPlanRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getIntentionId()).isEqualTo(42L);
		assertThat(found.getIntentionInstanceKey()).isEqualTo(instanceKey);
		assertThat(found.getBuyTrade().getId()).isEqualTo(buyTrade.getId());
		assertThat(found.getHolding().getId()).isEqualTo(holding.getId());
	}

	@Test
	@DisplayName("existsByHoldingIdAndStatus는 그 holding의 PENDING plan만 true로 본다")
	void existsByHoldingIdAndStatusSeesOnlyPendingPlanOfThatHolding() {
		Holding other = createHolding(account, createInstrument());
		assertThat(exitPlanRepository.existsByHoldingIdAndStatus(holding.getId(), ExitPlanStatus.PENDING)).isFalse();

		ExitPlan saved = exitPlanRepository.saveAndFlush(generalPlan(hash("a")));

		assertThat(exitPlanRepository.existsByHoldingIdAndStatus(holding.getId(), ExitPlanStatus.PENDING)).isTrue();
		assertThat(exitPlanRepository.existsByHoldingIdAndStatus(other.getId(), ExitPlanStatus.PENDING)).isFalse();

		closePlan(saved.getId(), ExitPlanStatus.CANCELLED);

		assertThat(exitPlanRepository.existsByHoldingIdAndStatus(holding.getId(), ExitPlanStatus.PENDING)).isFalse();
		assertThat(exitPlanRepository.existsByHoldingIdAndStatus(holding.getId(), ExitPlanStatus.CANCELLED)).isTrue();
	}

	@Test
	@DisplayName("종결된 plan이 있어도 같은 holding에 새 PENDING plan을 저장할 수 있다 — 1건 제약은 '다시는 불가'가 아니다")
	void allowsNewPendingPlanForHoldingAfterPreviousPlanIsClosed() {
		ExitPlan first = exitPlanRepository.saveAndFlush(generalPlan(hash("a")));
		closePlan(first.getId(), ExitPlanStatus.FILLED_TAKE_PROFIT);

		ExitPlan second = exitPlanRepository.saveAndFlush(generalPlan(hash("b")));

		assertThat(second.getId()).isNotNull();
		assertThat(exitPlanRepository.existsByHoldingIdAndStatus(holding.getId(), ExitPlanStatus.PENDING)).isTrue();
	}

	@Test
	@DisplayName("findByIdAndUserId는 타인 소유 plan의 존재를 숨긴다")
	void findByIdAndUserIdReturnsEmptyForAnotherOwner() {
		ExitPlan saved = exitPlanRepository.saveAndFlush(generalPlan(hash("a")));

		assertThat(exitPlanRepository.findByIdAndUserId(saved.getId(), user.getId())).isPresent();
		assertThat(exitPlanRepository.findByIdAndUserId(saved.getId(), user.getId() + 1)).isEmpty();
	}

	@Test
	@DisplayName("findByUserIdAndStatusOrderByIdDesc는 본인의 해당 상태 plan만 id 역순으로 반환한다")
	void findByUserIdAndStatusReturnsOnlyOwnPlansInDescendingIdOrder() {
		Holding second = createHolding(account, createInstrument());
		ExitPlan older = exitPlanRepository.saveAndFlush(generalPlan(hash("a")));
		ExitPlan newer = exitPlanRepository.saveAndFlush(generalPlanFor(second, hash("b")));
		closePlan(older.getId(), ExitPlanStatus.CANCELLED);

		User other = createUser();
		Account otherAccount = createAccount(other);
		Holding otherHolding = createHolding(otherAccount, createInstrument());
		exitPlanRepository.saveAndFlush(ExitPlan.createGeneral(
			other,
			otherHolding,
			otherHolding.getInstrument(),
			QUANTITY,
			ENTRY_PRICE,
			ExitPriceType.PRICE,
			null,
			null,
			STOP_LOSS_PRICE,
			TAKE_PROFIT_PRICE,
			BASELINE_PRICE,
			NOW,
			hash("c"),
			NOW));

		List<ExitPlan> pending = exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(user.getId(),
			ExitPlanStatus.PENDING);
		List<ExitPlan> cancelled = exitPlanRepository.findByUserIdAndStatusOrderByIdDesc(user.getId(),
			ExitPlanStatus.CANCELLED);

		assertThat(pending).extracting(ExitPlan::getId).containsExactly(newer.getId());
		assertThat(cancelled).extracting(ExitPlan::getId).containsExactly(older.getId());
	}

	@Test
	@DisplayName("findPendingExitPlansToFill은 현재가가 익절가 이상이면 익절 방향 후보로 반환한다 (PR #371 리뷰 권장)")
	void findPendingExitPlansToFillReturnsCandidateWhenPriceAtOrAboveTakeProfitPrice() {
		ExitPlan plan = exitPlanRepository.saveAndFlush(generalPlan(hash("a")));

		List<ExitPlan> result = exitPlanRepository.findPendingExitPlansToFill(instrument.getId(), TAKE_PROFIT_PRICE);

		assertThat(result).extracting(ExitPlan::getId).containsExactly(plan.getId());
	}

	@Test
	@DisplayName("findPendingExitPlansToFill은 현재가가 손절가 이하이면 손절 방향 후보로 반환한다 (PR #371 리뷰 권장)")
	void findPendingExitPlansToFillReturnsCandidateWhenPriceAtOrBelowStopLossPrice() {
		ExitPlan plan = exitPlanRepository.saveAndFlush(generalPlan(hash("a")));

		List<ExitPlan> result = exitPlanRepository.findPendingExitPlansToFill(instrument.getId(), STOP_LOSS_PRICE);

		assertThat(result).extracting(ExitPlan::getId).containsExactly(plan.getId());
	}

	@Test
	@DisplayName("findPendingExitPlansToFill은 손절가와 익절가 사이의 가격이면 후보에서 제외한다 (PR #371 리뷰 권장)")
	void findPendingExitPlansToFillExcludesPlanWhenPriceBetweenStopLossAndTakeProfit() {
		exitPlanRepository.saveAndFlush(generalPlan(hash("a")));

		List<ExitPlan> result = exitPlanRepository
			.findPendingExitPlansToFill(instrument.getId(), ENTRY_PRICE);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("findPendingExitPlansToFill은 PENDING이 아닌 plan을 제외한다 (PR #371 리뷰 권장)")
	void findPendingExitPlansToFillExcludesNonPendingPlan() {
		ExitPlan filled = exitPlanRepository.saveAndFlush(generalPlan(hash("a")));
		closePlan(filled.getId(), ExitPlanStatus.FILLED_TAKE_PROFIT);
		ExitPlan cancelled = exitPlanRepository.saveAndFlush(generalPlanFor(holding, hash("b")));
		closePlan(cancelled.getId(), ExitPlanStatus.CANCELLED);

		List<ExitPlan> result = exitPlanRepository.findPendingExitPlansToFill(instrument.getId(), TAKE_PROFIT_PRICE);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("findPendingExitPlansToFill은 다른 instrument의 plan을 제외한다 (PR #371 리뷰 권장)")
	void findPendingExitPlansToFillExcludesPlanOfAnotherInstrument() {
		Instrument otherInstrument = createInstrument();
		Holding otherHolding = createHolding(account, otherInstrument);
		exitPlanRepository.saveAndFlush(generalPlanFor(otherHolding, hash("a")));

		List<ExitPlan> result = exitPlanRepository.findPendingExitPlansToFill(instrument.getId(), TAKE_PROFIT_PRICE);

		assertThat(result).isEmpty();
	}

	@Test
	@DisplayName("findPendingExitPlansToFill은 reservedAt 오름차순, 동시각이면 id 오름차순으로 여러 후보를 정렬한다 (PR #371 리뷰 권장)")
	void findPendingExitPlansToFillSortedByReservedAtThenIdAscending() {
		// holding당 PENDING 1건 불변식은 앱 계층 검증이라 리포지터리 테스트는 우회해 같은 holding에 여러 PENDING plan을 직접 저장한다.
		ExitPlan older = exitPlanRepository.saveAndFlush(generalPlanAt(holding, hash("a"), NOW.minusMinutes(10)));
		ExitPlan sameTimeFirst = exitPlanRepository.saveAndFlush(generalPlanAt(holding, hash("b"), NOW));
		ExitPlan sameTimeSecond = exitPlanRepository.saveAndFlush(generalPlanAt(holding, hash("c"), NOW));

		List<ExitPlan> result = exitPlanRepository.findPendingExitPlansToFill(instrument.getId(), TAKE_PROFIT_PRICE);

		assertThat(result).extracting(ExitPlan::getId)
			.containsExactly(older.getId(), sameTimeFirst.getId(), sameTimeSecond.getId());
	}

	// PR #487 리뷰 QA 차단 회귀 — 이 조건이 빠지면 BithumbFeedSimulator가 SANDBOX_COIN_1에 3초마다 넣는
	// 합성 틱이 튜토리얼 예약을 tick 없이 즉시 체결시킨다. 튜토리얼 예약은 대본 canonical 가격을 넘기는
	// PracticeOrderSettlementService.settleCurrentRun만 체결해야 한다(042 EXITPRESET-013·014).
	@Test
	@DisplayName("findPendingExitPlansToFill은 튜토리얼 실행 세대에 귀속된 예약을 실시간 가격 후보에서 제외한다")
	void findPendingExitPlansToFillExcludesPracticeAttributedPlan() {
		PracticeAttempt attempt = practiceAttemptRepository.saveAndFlush(
			PracticeAttempt.create(user.getId(), Market.CRYPTO, NOW));
		ExitPlan practicePlan = generalPlan(hash("t"));
		ReflectionTestUtils.setField(practicePlan, "practiceAttemptId", attempt.getId());
		ReflectionTestUtils.setField(practicePlan, "practiceAttemptRunNumber", attempt.getRunNumber());
		exitPlanRepository.saveAndFlush(practicePlan);

		List<ExitPlan> atTakeProfit = exitPlanRepository
			.findPendingExitPlansToFill(instrument.getId(), TAKE_PROFIT_PRICE);
		List<ExitPlan> atStopLoss = exitPlanRepository
			.findPendingExitPlansToFill(instrument.getId(), STOP_LOSS_PRICE);

		assertThat(atTakeProfit).isEmpty();
		assertThat(atStopLoss).isEmpty();
	}

	@Test
	@DisplayName("findPendingExitPlansToFill은 같은 종목에 튜토리얼 예약이 섞여 있어도 일반 예약만 후보로 반환한다")
	void findPendingExitPlansToFillReturnsOnlyGeneralPlanWhenPracticePlanCoexists() {
		PracticeAttempt attempt = practiceAttemptRepository.saveAndFlush(
			PracticeAttempt.create(user.getId(), Market.CRYPTO, NOW));
		ExitPlan practicePlan = generalPlanAt(holding, hash("u"), NOW.minusMinutes(10));
		ReflectionTestUtils.setField(practicePlan, "practiceAttemptId", attempt.getId());
		ReflectionTestUtils.setField(practicePlan, "practiceAttemptRunNumber", attempt.getRunNumber());
		exitPlanRepository.saveAndFlush(practicePlan);
		ExitPlan generalPlan = exitPlanRepository.saveAndFlush(generalPlanAt(holding, hash("v"), NOW));

		List<ExitPlan> result = exitPlanRepository.findPendingExitPlansToFill(instrument.getId(), TAKE_PROFIT_PRICE);

		assertThat(result).extracting(ExitPlan::getId).containsExactly(generalPlan.getId());
	}

	// 튜토리얼 자동 예약은 실행 세대에 귀속된다(042 EXITPRESET-015) — tick 정산 대상 선별과 재시작 정리가
	// 이 두 컬럼으로 이뤄진다. 값을 채우는 것은 042 5번이라 여기서는 스키마와 매핑만 잠근다.
	@Test
	@DisplayName("튜토리얼 귀속 컬럼이 함께 채워진 예약은 저장되고 재조회된다")
	void savesPlanWithPracticeAttemptAttribution() {
		PracticeAttempt attempt = practiceAttemptRepository.saveAndFlush(
			PracticeAttempt.create(user.getId(), Market.CRYPTO, NOW));
		ExitPlan plan = generalPlan(hash("p"));
		ReflectionTestUtils.setField(plan, "practiceAttemptId", attempt.getId());
		ReflectionTestUtils.setField(plan, "practiceAttemptRunNumber", attempt.getRunNumber());
		ExitPlan saved = exitPlanRepository.saveAndFlush(plan);
		entityManager.clear();

		ExitPlan reloaded = exitPlanRepository.findById(saved.getId()).orElseThrow();

		assertThat(reloaded.getPracticeAttemptId()).isEqualTo(attempt.getId());
		assertThat(reloaded.getPracticeAttemptRunNumber()).isEqualTo(attempt.getRunNumber());
	}

	@Test
	@DisplayName("일반 경로 예약은 튜토리얼 귀속 컬럼이 둘 다 null인 채로 저장된다")
	void savesGeneralPathPlanWithoutPracticeAttemptAttribution() {
		ExitPlan saved = exitPlanRepository.saveAndFlush(generalPlan(hash("q")));
		entityManager.clear();

		ExitPlan reloaded = exitPlanRepository.findById(saved.getId()).orElseThrow();

		assertThat(reloaded.getPracticeAttemptId()).isNull();
		assertThat(reloaded.getPracticeAttemptRunNumber()).isNull();
	}

	@Test
	@DisplayName("attempt ID만 있는 예약은 DB check constraint가 거부한다")
	void planWithAttemptIdOnlyFailsWithCheckConstraint() {
		PracticeAttempt attempt = practiceAttemptRepository.saveAndFlush(
			PracticeAttempt.create(user.getId(), Market.CRYPTO, NOW));
		ExitPlan saved = exitPlanRepository.saveAndFlush(generalPlan(hash("r")));
		entityManager.flush();

		assertThatThrownBy(() -> entityManager.createNativeQuery(
			"UPDATE exit_plans SET practice_attempt_id = :attemptId WHERE id = :planId")
			.setParameter("attemptId", attempt.getId())
			.setParameter("planId", saved.getId())
			.executeUpdate())
			.isInstanceOf(PersistenceException.class);
	}

	@Test
	@DisplayName("실행 세대만 있는 예약은 DB check constraint가 거부한다")
	void planWithRunNumberOnlyFailsWithCheckConstraint() {
		ExitPlan saved = exitPlanRepository.saveAndFlush(generalPlan(hash("s")));
		entityManager.flush();

		assertThatThrownBy(() -> entityManager.createNativeQuery(
			"UPDATE exit_plans SET practice_attempt_run_number = 1 WHERE id = :planId")
			.setParameter("planId", saved.getId())
			.executeUpdate())
			.isInstanceOf(PersistenceException.class);
	}

	private void closePlan(Long planId, ExitPlanStatus status) {
		entityManager.flush();
		entityManager.createNativeQuery(
			"UPDATE exit_plans SET status = :status, closed_at = :closedAt WHERE id = :id")
			.setParameter("status", status.name())
			.setParameter("closedAt", NOW.plusMinutes(1))
			.setParameter("id", planId)
			.executeUpdate();
		entityManager.clear();
	}

	private ExitPlan generalPlan(String requestHash) {
		return generalPlanFor(holding, requestHash);
	}

	private ExitPlan generalPlanFor(Holding target, String requestHash) {
		return generalPlanAt(target, requestHash, NOW);
	}

	private ExitPlan generalPlanAt(Holding target, String requestHash, LocalDateTime reservedAt) {
		return ExitPlan.createGeneral(
			user,
			target,
			target.getInstrument(),
			QUANTITY,
			ENTRY_PRICE,
			ExitPriceType.PRICE,
			null,
			null,
			STOP_LOSS_PRICE,
			TAKE_PROFIT_PRICE,
			BASELINE_PRICE,
			NOW,
			requestHash,
			reservedAt);
	}

	private ExitPlan educationalPlan(User owner, Holding target, String instanceKey, String requestHash) {
		Trade buyTrade = createBuyTrade(owner, target.getAccount(), target.getInstrument());
		return ExitPlan.createEducational(
			owner,
			target,
			7L,
			instanceKey,
			buyTrade,
			target.getInstrument(),
			QUANTITY,
			ENTRY_PRICE,
			ExitPriceType.PRICE,
			null,
			null,
			STOP_LOSS_PRICE,
			TAKE_PROFIT_PRICE,
			BASELINE_PRICE,
			NOW,
			requestHash,
			NOW);
	}

	private Trade createBuyTrade() {
		return createBuyTrade(user, account, instrument);
	}

	private Trade createBuyTrade(User owner, Account forAccount, Instrument forInstrument) {
		Order order = Order.create(
			owner,
			forAccount,
			forInstrument,
			OrderSide.BUY,
			OrderType.MARKET,
			QUANTITY,
			UUID.randomUUID().toString(),
			hash("o-" + UUID.randomUUID()),
			NOW);
		entityManager.persist(order);
		Trade trade = Trade.of(
			order,
			forAccount,
			forInstrument,
			null,
			OrderSide.BUY,
			ENTRY_PRICE,
			QUANTITY,
			123_456L,
			0L,
			null,
			NOW,
			NOW);
		entityManager.persist(trade);
		entityManager.flush();
		return trade;
	}

	private User createUser() {
		String suffix = UUID.randomUUID().toString().replace("-", "");
		return userRepository.saveAndFlush(User.create("ep-" + suffix + "@finplay.com", "hash", "ep-" + suffix, NOW));
	}

	private Account createAccount(User owner) {
		return accountRepository.saveAndFlush(
			Account.create(owner, com.finplay.api.account.domain.Market.CRYPTO, NOW));
	}

	private Instrument createInstrument() {
		String symbol = "EP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
		return instrumentRepository.saveAndFlush(
			Instrument.create(Market.CRYPTO, symbol, "테스트코인", new BigDecimal("0.00000001"), 0L, true, NOW));
	}

	private Holding createHolding(Account owner, Instrument target) {
		Holding created = Holding.create(owner, target, NOW);
		created.applyBuy(new BigDecimal("10.00000000"), ENTRY_PRICE, NOW);
		return holdingRepository.saveAndFlush(created);
	}

	// request_hash는 CHAR(64) — SHA-256 hex 길이를 그대로 맞춘 더미 값을 만든다.
	private static String hash(String seed) {
		return (seed.repeat(64)).substring(0, 64);
	}
}
