// 사용자·시장별 단일 튜토리얼 attempt와 현재 실행 세대의 선택·가격 생성 상태를 영속하는 엔티티
package com.finplay.api.education.marketpractice.domain;

import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
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
