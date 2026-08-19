// 사용자·시장별 단일 튜토리얼 attempt와 현재 실행 세대의 선택·가격 생성 상태를 영속하는 엔티티
package com.finplay.api.education.marketpractice.domain;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.service.TutorialPriceGenerator;
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
	@Enumerated(EnumType.STRING)
	@Column(name = "exit_preset", length = 20)
	private ExitPreset exitPreset;

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

	public void selectInstrument(
		Instrument instrument,
		LocalDateTime anchorAt,
		LocalDate tutorialDate,
		long priceSeed,
		short generatorVersion,
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
		clearScenarioProgress();
	}

	// 이 실행이 저작 대본으로 가격을 만드는가. 대본은 커서가 시계를 정하므로 벽시계 마감(031 SANDBOX-008의
	// 5분 제한)이 성립하지 않는다 — 조회·복기·진행 계산이 모두 이 판정 하나로 갈린다(041 SCENARIO-014).
	public boolean usesScenarioScript() {
		return generatorVersion != null && generatorVersion == TutorialPriceGenerator.VERSION_2;
	}

	// 종목 선택과 재시작 양쪽에서 대본 위치를 지운다. 재시작이 빠뜨리면 재시작한 사용자가 이전 실행의 위치와
	// 봉을 그대로 물려받아 첫 화면에 지난 실행의 4막 저점이 노출된다(041 plan §재시작 시 초기화).
	private void clearScenarioProgress() {
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
