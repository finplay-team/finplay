// 튜토리얼 attempt의 멱등 진입 조회와 현재 실행 종목 선택을 처리하는 서비스
package com.finplay.api.domain.education.marketpractice.service;

import com.finplay.api.domain.account.entity.TutorialAccount;
import com.finplay.api.domain.account.service.TutorialAccountService;
import com.finplay.api.global.exception.BusinessException;
import com.finplay.api.global.exception.ErrorCode;
import com.finplay.api.domain.education.marketpractice.entity.ExitPreset;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttempt;
import com.finplay.api.domain.education.marketpractice.entity.PracticeCompletion;
import com.finplay.api.domain.education.marketpractice.entity.PracticeAttemptStatus;
import com.finplay.api.domain.education.marketpractice.entity.PracticeRiskSnapshot;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeAttemptResponse;
import com.finplay.api.domain.education.marketpractice.dto.response.PracticeStageProgressResponse;
import com.finplay.api.domain.education.marketpractice.repository.PracticeAttemptRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeCompletionRepository;
import com.finplay.api.domain.education.marketpractice.repository.PracticeRiskSnapshotRepository;
import com.finplay.api.domain.education.repository.PracticeProgressRepository;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import com.finplay.api.domain.market.service.InstrumentService;
import com.finplay.api.domain.order.service.TradeService;
import com.finplay.api.domain.market.service.TutorialPriceGenerator;
import com.finplay.api.domain.market.service.TutorialScenarioScriptLoader;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PracticeAttemptService {

	// 041 5번에서 1 -> 2. 커서를 전진시키는 진행 계산(PracticeScenarioProgressService)이 생긴 지금이 그
	// 시점이다 — 그전에 올렸으면 새 사용자의 가격이 0막 0분에 고정되고 지정가 정산도 대본 위치를 못 읽었다.
	// 배포 시점에 진행 중이던 버전 1 attempt는 재시작을 강제하지 않고 그대로 버전 1로 재현한다
	// (041 plan §생성기 버전 2) — 새 attempt와 재시작 후 다시 종목을 고른 attempt만 버전 2를 받는다.
	//
	// **대본이 저작된 시장에서만 준다.** 041은 CRYPTO 대본 하나만 저작했고 STOCK 대본은 SCENARIO-024의
	// 후속이다. 시장을 가리지 않고 2를 주면 STOCK 튜토리얼이 모든 가격 조회에서 "대본이 저작되지 않은
	// 시장입니다"로 터진다(통합 테스트에서 재현). STOCK 대본이 들어오면 이 판정이 자동으로 따라간다.
	private static final short SCENARIO_GENERATOR_VERSION = TutorialPriceGenerator.VERSION_2;
	private static final short LEGACY_GENERATOR_VERSION = TutorialPriceGenerator.VERSION_1;
	// legacy completion만 있는 사용자에게 만들어 주는 읽기 전용 replay attempt는 대본 커서가 없고 tick도
	// 돌지 않는다. 버전 2를 주면 대본 첫 구간 0분에 고정된 평평한 차트가 되므로 기존 재현을 그대로 둔다.
	private static final short REPLAY_GENERATOR_VERSION = TutorialPriceGenerator.VERSION_1;

	private final PracticeAttemptRepository practiceAttemptRepository;
	private final PracticeCompletionRepository practiceCompletionRepository;
	private final PracticeRiskSnapshotRepository practiceRiskSnapshotRepository;
	private final PracticeProgressRepository practiceProgressRepository;
	private final InstrumentService instrumentService;
	private final TradeService tradeService;
	private final TutorialScenarioScriptLoader tutorialScenarioScriptLoader;
	private final TutorialAccountService tutorialAccountService;
	// 049 ORDERBASICS-015 — 프리셋 선택도 lockForOrder와 같은 판정식을 쓴다(#503 재사용).
	private final PracticeStageProgressCalculationService practiceStageProgressCalculationService;
	private final Clock clock;
	private final SecureRandom secureRandom = new SecureRandom();

	/**
	 * 튜토리얼 진입의 멱등 get-or-create다.
	 *
	 * <p><b>잠금을 먼저 잡고, 없을 때만 만든다(이슈 #491).</b> 예전에는 순서가 반대여서 매 진입마다
	 * {@code INSERT IGNORE}를 먼저 쏘고 같은 행을 {@code FOR UPDATE}로 다시 잠갔는데, 중복 키 검사가
	 * 유니크 인덱스 레코드에 남긴 공유 잠금(S)을 곧바로 배타 잠금(X)으로 승격하는 모양이라 같은 사용자·
	 * 시장의 동시 진입 2건이 서로의 S를 기다리는 교착이 됐다. 잠금 조회를 앞에 두면 흔한 경로(행이 이미
	 * 있는 경우)가 X 하나로 끝나 승격 자체가 없고, 진입마다 나가던 쓰기 한 번도 사라진다.
	 *
	 * <p><b>READ COMMITTED인 이유는 행이 아직 없는 첫 진입 때문이다.</b> REPEATABLE READ에서는 아무 행도
	 * 맞히지 못한 {@code FOR UPDATE}가 갭 잠금을 잡고, 동시 진입 2건이 각자 갭을 잡은 뒤 서로의 INSERT를
	 * 기다리는 <b>다른</b> 교착이 된다. READ COMMITTED는 갭 잠금을 쓰지 않아 이 경로가 아예 생기지 않는다.
	 * 이 트랜잭션의 정확성은 스냅숏 격리가 아니라 명시적 비관 잠금이 담당하므로 낮춰도 잃는 것이 없다 —
	 * ADR-0028이 체결 경로에 같은 판단을 적용했고, 여기서 읽는 값들은 모두 한 번씩만 읽는다.
	 *
	 * <p><b>이 메서드는 반드시 트랜잭션 시작점이어야 한다.</b> 이미 열린 트랜잭션에 {@code REQUIRED}로
	 * 합류하면 Spring이 안쪽 격리수준 선언을 조용히 무시하므로({@code validateExistingTransaction} 기본값이
	 * {@code false}다) 위의 갭 잠금 교착이 그대로 돌아온다. PR #514가 실제로 이 함정에 걸렸고 ADR-0028
	 * §정정이 그 사고를 기록했다. 현재 호출부는 {@link PracticeAttemptDeadlockRetryService} 하나뿐이고 그쪽은
	 * 트랜잭션을 열지 않는다 — 새 호출부를 만들 때 이 전제를 함께 확인해야 한다.
	 *
	 * <p>{@code inserted}는 "이 트랜잭션이 행을 만들었는가"이며 잠금 조회가 비어 있었는지로만 판정한다 —
	 * {@code insertIfAbsent}의 affected rows로 판정하면 안 되는 이유는 그 메서드 주석에 있다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PracticeAttemptResponse ensureAttempt(Long userId, Market market) {
		String tutorialKey = resolveTutorialKey(market);
		LocalDateTime now = LocalDateTime.now(clock);
		PracticeAttempt existing = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElse(null);
		boolean inserted = existing == null;
		PracticeAttempt attempt = existing;
		if (inserted) {
			practiceAttemptRepository.insertIfAbsent(userId, market.name(), now);
			// 같은 순간 다른 트랜잭션이 먼저 만들었다면 위 INSERT는 아무것도 하지 않고, 이 조회가 그 행을
			// 잠근 채 돌려준다. 그 경우 아래 completion 분기는 attempt.status로 갈리므로 이중 전환이 없다.
			attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
				.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR));
		}
		// PracticeAttempt 행 잠금이 이미 사용자·시장 조합을 직렬화하므로, 같은 트랜잭션 안에서 튜토리얼 계좌도
		// 함께 get-or-create한다(TUTORIAL-CASH-ISOL-001, 설계 판단 — 계좌 생성 시점). 그 결과를 응답에도 그대로
		// 실어 보낸다(TUTORIAL-CASH-ISOL-011).
		TutorialAccount tutorialAccount = tutorialAccountService.getOrCreateForUpdate(
			userId, market, now);
		practiceProgressRepository.findByUserIdAndTutorialKeyForUpdate(userId, tutorialKey);
		PracticeCompletion completion = practiceCompletionRepository
			.findByUserIdAndTutorialKey(userId, tutorialKey)
			.orElse(null);
		if (completion != null) {
			if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
				return toResponse(attempt, tutorialAccount);
			}
			if (inserted) {
				initializeCompletedReplay(userId, market, attempt, completion, now);
			}
			// completion evidence는 있지만 attempt가 COMPLETED가 아닌 기존 행은 재시작 후 진행 중인
			// 상태(TUTORIAL-RESTART-003)이므로 오류로 취급하지 않고 현재 상태를 그대로 반환한다.
		}
		return toResponse(attempt, tutorialAccount);
	}

	private void initializeCompletedReplay(
		Long userId, Market market, PracticeAttempt attempt, PracticeCompletion completion, LocalDateTime updatedAt) {
		Instrument instrument = requireCompletionInstrument(market, completion);
		LocalDateTime completedAt = completion.getCompletedAt();
		attempt.reconcileCompletedReplay(
			instrument,
			completedAt,
			completedAt.toLocalDate(),
			deterministicReplaySeed(userId, market, completion.getId(), instrument.getId()),
			REPLAY_GENERATOR_VERSION,
			completedAt,
			updatedAt);
	}

	private Instrument requireCompletionInstrument(Market market, PracticeCompletion completion) {
		Instrument instrument = completion.getReflection().getHolding().getInstrument();
		if (instrument.getMarket() != market) {
			throw new BusinessException(ErrorCode.PRACTICE_EVIDENCE_MISSING);
		}
		return instrument;
	}

	private long deterministicReplaySeed(Long userId, Market market, Long completionId, Long instrumentId) {
		long seed = 0xcbf29ce484222325L;
		seed = (seed ^ userId) * 0x100000001b3L;
		seed = (seed ^ market.ordinal()) * 0x100000001b3L;
		seed = (seed ^ completionId) * 0x100000001b3L;
		return (seed ^ instrumentId) * 0x100000001b3L;
	}

	private short generatorVersionFor(Market market) {
		return tutorialScenarioScriptLoader.hasScript(market) ? SCENARIO_GENERATOR_VERSION : LEGACY_GENERATOR_VERSION;
	}

	// 진입 대본은 그 시장의 첫 대본이다(049 tasks 5번 — 전환 엔드포인트가 생겨 2단계에서 3단계로 빠져나갈
	// 길이 있으므로 041 고정을 풀었다). 클라이언트가 대본을 고르지 못하게 하는 이유는 그래야 사용자가
	// 2단계를 건너뛸 수 없기 때문이다(plan §2·§7). 대본을 쓰지 않는 실행(생성기 버전 1)은 null이다.
	//
	// **시장을 반드시 함께 본다.** STOCK 대본(SCENARIO-024)이 저작되기 전까지는 STOCK에서
	// generatorVersionFor가 항상 1을 주므로 이 분기에 도달하지 않는다. 위 40~44행 주석이 기록한 것과 같은
	// 형태의 사고를 피하기 위해 market == CRYPTO 조건을 그대로 남긴다.
	private TutorialScenarioScriptId scenarioScriptIdFor(Market market, short generatorVersion) {
		return generatorVersion == SCENARIO_GENERATOR_VERSION && market == Market.CRYPTO
			? tutorialScenarioScriptLoader.firstScriptId(market)
			: null;
	}

	private String resolveTutorialKey(Market market) {
		return switch (market) {
			case STOCK -> "INVESTMENT_PRACTICE_V1";
			case CRYPTO -> "COIN_PRACTICE_V1";
		};
	}

	@Transactional
	public PracticeAttemptResponse selectInstrument(Long userId, Market market, Long instrumentId) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));
		Instrument instrument = instrumentService.getInstrumentEntity(instrumentId);
		validateTutorialInstrument(market, instrument);

		if (attempt.getStatus() == PracticeAttemptStatus.IN_PROGRESS) {
			if (attempt.getInstrument().getId().equals(instrumentId)) {
				return toResponse(attempt, tutorialAccountFor(userId, market));
			}
			throw new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED);
		}
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		if (attempt.getStatus() != PracticeAttemptStatus.SELECTING_INSTRUMENT) {
			throw new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED);
		}

		LocalDateTime now = LocalDateTime.now(clock);
		short generatorVersion = generatorVersionFor(market);
		attempt.selectInstrument(
			instrument,
			now,
			LocalDate.now(clock),
			secureRandom.nextLong(),
			generatorVersion,
			scenarioScriptIdFor(market, generatorVersion),
			now);
		return toResponse(attempt, tutorialAccountFor(userId, market));
	}

	/**
	 * 현재 실행 세대의 손절·익절 프리셋을 고른다(042 EXITPRESET-003).
	 *
	 * <p>잠금 기준은 "최초 매수 여부"가 아니라 <b>지금 들고 있는가</b>다. 손절을 겪은 사용자가 다음 진입의
	 * 기준을 다시 정하는 것은 이 기능이 훈련시키려는 판단 그 자체이고, 손실 중에 손절선을 내리는 사후
	 * 합리화는 039가 막으려 한 것이다 — 보유 중 잠금이 후자만 정확히 막는다. 이미 확정된 snapshot과 그
	 * snapshot으로 만들어진 예약은 어떤 경우에도 바뀌지 않으며 다음 진입에만 적용된다.
	 */
	@Transactional
	public PracticeAttemptResponse selectExitPreset(Long userId, Market market, ExitPreset preset) {
		PracticeAttempt attempt = practiceAttemptRepository.findByUserIdAndMarketForUpdate(userId, market)
			.orElseThrow(() -> new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED));
		if (attempt.getStatus() == PracticeAttemptStatus.COMPLETED) {
			throw new BusinessException(ErrorCode.PRACTICE_ALREADY_COMPLETED);
		}
		requireStageUnlockedForPresetSelection(attempt);
		if (exitPresetLocked(attempt)) {
			throw new BusinessException(ErrorCode.PRACTICE_STEP_LOCKED);
		}
		attempt.selectExitPreset(preset, LocalDateTime.now(clock));
		// 방금 잠금이 아님을 확인했으므로 다시 조회하지 않는다 — attempt를 잠근 트랜잭션 안이라 그 사이
		// 매수 체결이 끼어들 수 없다.
		return toResponse(attempt, tutorialAccountFor(userId, market), false);
	}

	/**
	 * 049 ORDERBASICS-015 — 지정가 왕복을 마치기 전 프리셋 선택을 409로 거부한다. 대본을 쓰지 않는 실행은
	 * 항상 통과한다. 보유 중 잠금(exitPresetLocked)보다 <b>앞에</b> 검사한다 — 두 조건이 동시에 걸릴 때
	 * "앞 단계를 먼저 마쳐야 합니다"가 사용자에게 더 정확한 안내다(plan §4).
	 */
	private void requireStageUnlockedForPresetSelection(PracticeAttempt attempt) {
		if (!attempt.usesScenarioScript()) {
			return;
		}
		PracticeStageProgressResponse progress = practiceStageProgressCalculationService.calculate(attempt);
		if (!progress.marketBuySellCompleted() || !progress.limitBuySellCompleted()) {
			throw new BusinessException(ErrorCode.PRACTICE_STAGE_LOCKED);
		}
	}

	// 042 EXITPRESET-003의 잠금 판정, EXITPRESET-020의 진입 가드, 041의 대기 구간 탈출 판정이 같은 산출식을
	// 써야 한다(042 plan §자동 예약 생성). 그 한 곳이 TradeService.netFilledQuantity다.
	private boolean exitPresetLocked(PracticeAttempt attempt) {
		if (attempt.getInstrument() == null) {
			return false;
		}
		return tradeService.netFilledQuantity(attempt.getId(), attempt.getRunNumber()).signum() > 0;
	}

	private void validateTutorialInstrument(Market market, Instrument instrument) {
		if (instrument.getMarket() != market || !instrument.isTutorialSample() || !instrument.isTradable()) {
			throw new BusinessException(ErrorCode.INSTRUMENT_NOT_TRADABLE);
		}
	}

	// 진입 응답(ensureAttempt)은 같은 트랜잭션에서 이미 get-or-create한 튜토리얼 계좌 값을 그대로 실어
	// 보낸다(TUTORIAL-CASH-ISOL-011) — 추가 조회 없이 진입 시점 잔고·손익을 정확히 노출한다.
	private PracticeAttemptResponse toResponse(PracticeAttempt attempt, TutorialAccount tutorialAccount) {
		return toResponse(attempt, tutorialAccount, exitPresetLocked(attempt));
	}

	private PracticeAttemptResponse toResponse(
		PracticeAttempt attempt, TutorialAccount tutorialAccount, boolean exitPresetLocked) {
		PracticeRiskSnapshot snapshot = practiceRiskSnapshotRepository
			.findTopByAttemptIdAndRunNumberOrderByEntrySequenceDesc(attempt.getId(), attempt.getRunNumber())
			.orElse(null);
		return PracticeAttemptResponse.from(
			attempt,
			snapshot,
			exitPresetLocked,
			tutorialAccount.getCashBalance(),
			tutorialAccount.getAvailableCash(),
			tutorialAccount.getRealizedPnl());
	}

	/**
	 * 종목 선택·프리셋 선택 응답이 실을 튜토리얼 계좌를 읽는다(이슈 #502).
	 *
	 * <p><b>잠금 없이 먼저 읽고, 없을 때만 get-or-create로 떨어진다.</b> 이 두 호출부는 계좌를 한 글자도
	 * 바꾸지 않으므로 X 잠금을 걸 이유가 없다 — 걸면 무변경 응답이 지정가 취소·정정이나 예약 청산 정산과
	 * 경합해 대기한다(attempt 잠금은 그 트랜잭션들을 직렬화하지 못한다). 폴백을 남겨 두는 것은 계좌가
	 * 아직 없는 예외 경우(계좌 도입 이전 attempt)에도 0원을 내려보내지 않기 위해서이고, 그 경로는 진입이
	 * 이미 계좌를 만들어 두므로 사실상 도달하지 않는다.
	 *
	 * <p>진입 경로와 잠금 순서(attempt → tutorial account)는 폴백에서도 같다 — 순서가 같아야 이슈 #491류
	 * 교착을 새로 만들지 않는다.
	 */
	private TutorialAccount tutorialAccountFor(Long userId, Market market) {
		return tutorialAccountService.find(userId, market)
			.orElseGet(() -> tutorialAccountService.getOrCreateForUpdate(
				userId, market, LocalDateTime.now(clock)));
	}
}
