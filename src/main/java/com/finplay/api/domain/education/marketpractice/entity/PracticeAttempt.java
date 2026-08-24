// 사용자·시장별 단일 튜토리얼 attempt와 현재 실행 세대의 선택·가격 생성 상태를 영속하는 엔티티
package com.finplay.api.domain.education.marketpractice.entity;

import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.entity.TutorialScenarioScriptId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "practice_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeAttempt {

	private static final long INITIAL_RUN_NUMBER = 1L;
	// market.service.TutorialPriceGenerator.VERSION_2와 같은 값이다. 그 상수를 직접 import하지 않는 이유는
	// 의존 방향 때문이다 — 도메인 엔티티가 다른 도메인의 서비스를 가리키면 보통의 service → domain 방향이
	// 거꾸로 선다(PR #474 리뷰). 두 값이 갈라지지 않는 것은 PracticeAttemptTest가 동등성으로 고정한다.
	private static final short SCENARIO_GENERATOR_VERSION = 2;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Market market;

	@Column(name = "run_number", nullable = false)
	private long runNumber;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private PracticeAttemptStatus status;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "instrument_id")
	private Instrument instrument;

	@Column(name = "anchor_at")
	private LocalDateTime anchorAt;

	@Column(name = "tutorial_date")
	private LocalDate tutorialDate;

	@Column(name = "price_seed")
	private Long priceSeed;

	@Column(name = "generator_version")
	private Short generatorVersion;

	// 이 실행이 쓰는 대본. 원본 필드에는 getter를 열지 않는다 — NULL 해석이 아래 파생 접근자 하나에만
	// 있어야 하고, 원본을 그대로 읽는 경로가 하나라도 생기면 그 경로만 다른 대본을 보기 때문이다.
	@Getter(AccessLevel.NONE)
	@Enumerated(EnumType.STRING)
	@Column(name = "scenario_script_id", length = 32)
	private TutorialScenarioScriptId scenarioScriptId;

	// 대본 위치 (구간 id, 구간 내 경과 실제 초). 생성기 버전 1 attempt에서는 계속 null이다.
	@Column(name = "scenario_stage_id", length = 32)
	private String scenarioStageId;

	@Column(name = "scenario_stage_elapsed_seconds")
	private Long scenarioStageElapsedSeconds;

	// 진행 중 봉의 누적 3값. 대본 위치가 단조가 아니라 지나온 경로를 복원할 수 없으므로 직접 들고 있는다.
	@Column(name = "scenario_candle_open", precision = 18, scale = 8)
	private BigDecimal scenarioCandleOpen;

	@Column(name = "scenario_candle_high", precision = 18, scale = 8)
	private BigDecimal scenarioCandleHigh;

	@Column(name = "scenario_candle_low", precision = 18, scale = 8)
	private BigDecimal scenarioCandleLow;

	// 대본 진행 계산이 delta를 재는 기준 시각. updated_at을 쓰지 않는 이유는 그것이 대본 진행과 무관한
	// 경로에서도 갱신돼 사용자가 실제로 기다린 시간을 0으로 만들기 때문이다(041 4번 판정, V52).
	@Column(name = "scenario_progress_updated_at")
	private LocalDateTime scenarioProgressUpdatedAt;

	// 현재 실행 세대의 손절·익절 프리셋 선택값. null이면 미선택이며 기본 프리셋으로 해석한다
	// (042 EXITPRESET-002). 값을 채우는 것은 선택 API(042 tasks 3번)이고 여기서는 매핑만 더한다.
	//
	// **052 이후 이 컬럼은 더 이상 정본이 아니다.** 적용될 비율의 정본은 아래 두 컬럼이고, 여기에는 그 비율이
	// 프리셋 3개 중 하나와 정확히 같을 때만 그 식별자가 들어간다(자유 조합이면 null). 지우지 않는 이유는
	// 프론트가 별도 배포라 프리셋 전환이 끝나기 전에 컬럼을 없애면 깨지기 때문이다(CLAUDE.md 규칙 8).
	@Enumerated(EnumType.STRING)
	@Column(name = "exit_preset", length = 20)
	private ExitPreset exitPreset;

	// 052 — 현재 실행 세대의 손절·익절 비율 자유 입력값(퍼센트 수, 손절도 양수). 둘 다 null이면 미선택이며
	// exit_preset → 기본값 순으로 해석한다(effectiveExitRates). **둘 중 하나만 null인 상태는 없다** —
	// 스키마의 CHECK와 아래 selectExitRates가 함께 막는다.
	@Column(name = "exit_stop_loss_rate", precision = 7, scale = 4)
	private BigDecimal exitStopLossRate;

	@Column(name = "exit_take_profit_rate", precision = 7, scale = 4)
	private BigDecimal exitTakeProfitRate;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	private PracticeAttempt(Long userId, Market market, LocalDateTime createdAt) {
		this.userId = userId;
		this.market = market;
		this.runNumber = INITIAL_RUN_NUMBER;
		this.status = PracticeAttemptStatus.SELECTING_INSTRUMENT;
		this.createdAt = createdAt;
		this.updatedAt = createdAt;
	}

	public static PracticeAttempt create(Long userId, Market market, LocalDateTime createdAt) {
		return new PracticeAttempt(userId, market, createdAt);
	}

	// scenarioScriptId는 대본을 쓰지 않는 실행에서 null이다. 어느 대본으로 시작하는지는 호출자가 정한다 —
	// 엔티티는 대본 목록을 볼 수 없다(049 plan §2의 "값이 정해지는 자리").
	public void selectInstrument(
		Instrument instrument,
		LocalDateTime anchorAt,
		LocalDate tutorialDate,
		long priceSeed,
		short generatorVersion,
		TutorialScenarioScriptId scenarioScriptId,
		LocalDateTime updatedAt) {
		if (this.status != PracticeAttemptStatus.SELECTING_INSTRUMENT) {
			throw new IllegalStateException("종목 선택 대기 상태에서만 종목을 선택할 수 있습니다.");
		}
		this.instrument = instrument;
		this.anchorAt = anchorAt;
		this.tutorialDate = tutorialDate;
		this.priceSeed = priceSeed;
		this.generatorVersion = generatorVersion;
		this.status = PracticeAttemptStatus.IN_PROGRESS;
		this.updatedAt = updatedAt;
		clearScenarioProgress();
		this.scenarioScriptId = scenarioScriptId;
	}

	public void restart(LocalDateTime updatedAt) {
		this.runNumber = Math.addExact(this.runNumber, 1L);
		this.status = PracticeAttemptStatus.SELECTING_INSTRUMENT;
		this.instrument = null;
		this.anchorAt = null;
		this.tutorialDate = null;
		this.priceSeed = null;
		this.generatorVersion = null;
		this.completedAt = null;
		this.updatedAt = updatedAt;
		// 프리셋 선택은 실행 세대에 귀속된다 — 재시작하면 기본값으로 되돌아간다(042 EXITPRESET-009).
		this.exitPreset = null;
		this.exitStopLossRate = null;
		this.exitTakeProfitRate = null;
		clearScenarioProgress();
	}

	// 현재 실행 세대의 손절·익절 기준을 고른다. 잠금 판정(순보유수량 0)은 호출자가 한다 — 엔티티가
	// holding 원장을 볼 수 없기 때문이다(042 EXITPRESET-003).
	//
	// 052 — 프리셋을 고른 경우에도 비율 두 컬럼을 함께 채운다. 적용될 비율의 정본이 비율 컬럼 하나로
	// 모여야 체결 경로가 "프리셋이면 이쪽, 자유 입력이면 저쪽" 두 갈래를 갖지 않는다.
	public void selectExitPreset(ExitPreset exitPreset, LocalDateTime updatedAt) {
		selectExitRates(ExitRates.of(exitPreset), updatedAt);
	}

	/**
	 * 052 — 현재 실행 세대의 손절·익절 비율을 자유 입력으로 고친다. 구간·소수 자릿수 검증은 요청 DTO가,
	 * 보유 중 잠금은 서비스가 한다(042 EXITPRESET-003의 판정을 그대로 승계) — 엔티티는 holding 원장을
	 * 볼 수 없다.
	 *
	 * <p>{@code exit_preset}에는 이 조합과 정확히 같은 프리셋이 있을 때만 그 식별자가 들어간다. 자유
	 * 조합이면 {@code null}이며, 그 자리에 "CUSTOM" 같은 값을 새로 만들지 않는다 — 그러면 V51이 스키마에
	 * 박아 둔 CHECK를 고쳐야 하고, 구버전 앱이 읽을 수 없는 값이 그 컬럼에 들어가 롤백 경로가 깨진다.
	 */
	public void selectExitRates(ExitRates exitRates, LocalDateTime updatedAt) {
		if (this.status != PracticeAttemptStatus.IN_PROGRESS
			&& this.status != PracticeAttemptStatus.SELECTING_INSTRUMENT) {
			throw new IllegalStateException("진행 중인 튜토리얼 attempt만 손절·익절 기준을 고칠 수 있습니다.");
		}
		this.exitStopLossRate = exitRates.stopLossRate();
		this.exitTakeProfitRate = exitRates.takeProfitRate();
		this.exitPreset = exitRates.matchingPreset();
		this.updatedAt = updatedAt;
	}

	/**
	 * 지금 이 실행 세대에 <b>실제로 적용될</b> 손절·익절 비율. 미선택이면 기본값(손절 3·익절 5)이라
	 * {@code null}이 되지 않는다(042 EXITPRESET-002 승계).
	 *
	 * <p><b>{@code exit_preset} 폴백을 남겨 두는 것은 형식이 아니라 필수다.</b> 자동 배포의 롤백은 앱만
	 * 되돌리고 스키마는 되돌리지 않으므로(ADR-0021 §결정 7), 052 배포 전후로 구버전 앱이 비율 컬럼 없이
	 * {@code exit_preset}만 쓴 행이 남을 수 있다. 그 행을 기본값으로 읽으면 사용자가 고른 기준이 조용히
	 * 바뀐다.
	 */
	public ExitRates effectiveExitRates() {
		if (exitStopLossRate != null && exitTakeProfitRate != null) {
			return ExitRates.of(exitStopLossRate, exitTakeProfitRate);
		}
		return exitPreset == null ? ExitRates.DEFAULT : ExitRates.of(exitPreset);
	}

	/**
	 * 이 실행에서 손절·익절 기준을 <b>직접 정했는가</b>(프리셋이든 자유 비율이든).
	 *
	 * <p>052가 프리셋 픽커를 화면에서 없애므로 판정을 프리셋 선택 여부로 두면 그 단계가 영영 미완으로
	 * 남는다({@code tutorialStageProgress.exitPresetSelected}, 이슈 #503).
	 */
	public boolean exitRatesSelected() {
		return exitStopLossRate != null || exitPreset != null;
	}

	// 이 실행이 저작 대본으로 가격을 만드는가. 대본은 커서가 시계를 정하므로 벽시계 마감(031 SANDBOX-008의
	// 5분 제한)이 성립하지 않는다 — 조회·복기·진행 계산이 모두 이 판정 하나로 갈린다(041 SCENARIO-014).
	public boolean usesScenarioScript() {
		return generatorVersion != null && generatorVersion == SCENARIO_GENERATOR_VERSION;
	}

	/**
	 * 이 실행이 지금 서 있는 대본. 대본을 쓰지 않는 실행은 {@code null}이다.
	 *
	 * <p><b>NULL 해석은 여기 한 곳에만 둔다</b>(049 plan §NULL 해석 규칙). 049 배포 순간 진행 중이던 실행은
	 * 이 컬럼이 {@code NULL}인데 {@code scenario_stage_id}에는 041 대본의 구간 id가 살아 있다. 그 조합을
	 * {@code CRYPTO_STORY_V1}로 읽어야 그 사용자가 "대본에 없는 구간입니다"로 500에 갇히지 않는다 —
	 * 회복 수단이 재시작뿐이기 때문이다. 조회부가 각자 {@code null}을 처리하면 한 곳을 놓쳤을 때 그
	 * 경로만 다른 대본을 본다.
	 *
	 * <p><b>⚠ 이 폴백은 시장을 보지 않는다. STOCK 대본(041 SCENARIO-024)을 저작하는 PR이 여기를 반드시
	 * 함께 고쳐야 한다.</b> 지금은 CRYPTO만 대본이 있어 {@code usesScenarioScript()}가 STOCK에서 참이 될
	 * 수 없으므로 도달 불가다. 그러나 STOCK 대본이 들어오는 순간 {@code generatorVersionFor(STOCK)}이 2가
	 * 되고, 컬럼이 {@code NULL}인 STOCK attempt가 이 폴백으로 <b>CRYPTO 대본</b>을 받는다 —
	 * {@code TutorialPriceGenerator.requireScriptMatches}가 "attempt의 시장과 다른 대본입니다"로 던져
	 * 그 사용자의 조회·tick·주문이 전부 500이 된다. 진입 경로는
	 * {@code PracticeAttemptService.scenarioScriptIdFor}가 시장 조건으로 이미 막았지만 <b>폴백은 안 막혔다</b>.
	 */
	public TutorialScenarioScriptId scenarioScriptId() {
		if (!usesScenarioScript()) {
			return null;
		}
		return scenarioScriptId == null ? TutorialScenarioScriptId.CRYPTO_STORY_V1 : scenarioScriptId;
	}

	// 049 ORDERBASICS-018 — 같은 run 안에서 2단계 대본을 3단계 대본으로 교체한다(전환은 재시작이 아니다).
	// run·exitPreset·계좌는 건드리지 않고 커서만 지운다 — 새 대본의 첫 tick이 그 대본의 첫 구간 0분으로
	// 커서를 다시 세운다(041 3번이 남긴 계약과 동일).
	public void advanceScenarioScript(TutorialScenarioScriptId scenarioScriptId, LocalDateTime updatedAt) {
		if (this.status != PracticeAttemptStatus.IN_PROGRESS) {
			throw new IllegalStateException("진행 중인 튜토리얼 attempt만 대본을 전환할 수 있습니다.");
		}
		clearScenarioProgress();
		this.scenarioScriptId = scenarioScriptId;
		this.updatedAt = updatedAt;
	}

	// 종목 선택과 재시작 양쪽에서 대본 위치를 지운다. 재시작이 빠뜨리면 재시작한 사용자가 이전 실행의 위치와
	// 봉을 그대로 물려받아 첫 화면에 지난 실행의 4막 저점이 노출된다(041 plan §재시작 시 초기화).
	//
	// 대본 식별자도 함께 지운다 — 빠뜨리면 3단계에서 재시작한 사용자가 2단계를 건너뛰고 041 대본으로 다시
	// 시작한다(049 plan §2). selectInstrument는 이 호출 뒤에 새 식별자를 박는다.
	private void clearScenarioProgress() {
		this.scenarioScriptId = null;
		this.scenarioStageId = null;
		this.scenarioStageElapsedSeconds = null;
		this.scenarioCandleOpen = null;
		this.scenarioCandleHigh = null;
		this.scenarioCandleLow = null;
		this.scenarioProgressUpdatedAt = null;
	}

	// 첫 tick이 대본의 첫 구간으로 커서를 세우고 진행 중 봉을 연다. 종목 선택·재시작은 다섯 컬럼을 전부
	// null로 지우므로(= 미시작) 대본 구간 id 리터럴이 엔티티에 들어오지 않는다(041 3번이 남긴 계약).
	public void startScenarioProgress(String stageId, BigDecimal openPrice, LocalDateTime progressUpdatedAt) {
		this.scenarioStageId = stageId;
		this.scenarioStageElapsedSeconds = 0L;
		this.scenarioCandleOpen = openPrice;
		this.scenarioCandleHigh = openPrice;
		this.scenarioCandleLow = openPrice;
		this.scenarioProgressUpdatedAt = progressUpdatedAt;
		this.updatedAt = progressUpdatedAt;
	}

	// 순회 도중에 커서를 실제로 민다. 지정가 체결이 canonical 가격을 이 커서에서 읽으므로 순회가 끝난 뒤
	// 한 번에 저장하면 건너뛴 분의 가격으로 정산할 수 없다(041 plan §`order` 인터페이스 변경).
	public void moveScenarioCursor(String stageId, long elapsedSeconds) {
		this.scenarioStageId = stageId;
		this.scenarioStageElapsedSeconds = elapsedSeconds;
	}

	// 진행 중 봉의 고가·저가만 넓힌다. 시가는 종목 선택 시점의 첫 가격으로 한 번 정하고 바꾸지 않는다
	// (041 plan §데이터 모델, chk_practice_attempts_scenario_candle이 low <= open <= high를 요구한다).
	public void extendScenarioCandle(BigDecimal price) {
		if (this.scenarioCandleHigh == null || this.scenarioCandleLow == null) {
			throw new IllegalStateException("진행 중 봉이 열리지 않은 attempt입니다.");
		}
		this.scenarioCandleHigh = this.scenarioCandleHigh.max(price);
		this.scenarioCandleLow = this.scenarioCandleLow.min(price);
	}

	public void markScenarioProgressed(LocalDateTime progressUpdatedAt) {
		this.scenarioProgressUpdatedAt = progressUpdatedAt;
		this.updatedAt = progressUpdatedAt;
	}

	public void complete(LocalDateTime completedAt) {
		if (this.status == PracticeAttemptStatus.COMPLETED) {
			throw new IllegalStateException("이미 완료된 튜토리얼 attempt입니다.");
		}
		if (this.status != PracticeAttemptStatus.IN_PROGRESS) {
			throw new IllegalStateException("진행 중인 튜토리얼 attempt만 완료할 수 있습니다.");
		}
		this.status = PracticeAttemptStatus.COMPLETED;
		this.completedAt = completedAt;
		this.updatedAt = completedAt;
	}

	public void reconcileCompletedReplay(
		Instrument instrument,
		LocalDateTime anchorAt,
		LocalDate tutorialDate,
		long priceSeed,
		short generatorVersion,
		LocalDateTime completedAt,
		LocalDateTime updatedAt) {
		if (this.status == PracticeAttemptStatus.COMPLETED) {
			return;
		}
		this.status = PracticeAttemptStatus.COMPLETED;
		this.instrument = instrument;
		this.anchorAt = anchorAt;
		this.tutorialDate = tutorialDate;
		this.priceSeed = priceSeed;
		this.generatorVersion = generatorVersion;
		this.completedAt = completedAt;
		this.updatedAt = updatedAt;
	}
}
