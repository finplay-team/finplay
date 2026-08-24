// 시장가/지정가 매매 기반 실습 튜토리얼의 완료 판정 1건(튜토리얼당 1회)을 영속하는 엔티티
package com.finplay.api.domain.education.marketpractice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "practice_completions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeCompletion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "tutorial_key", nullable = false, length = 50)
	private String tutorialKey;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "reflection_id", nullable = false)
	private PracticeMarketReflection reflection;

	@Column(name = "completed_at", nullable = false)
	private LocalDateTime completedAt;

	private PracticeCompletion(
		Long userId, String tutorialKey, PracticeMarketReflection reflection, LocalDateTime completedAt) {
		this.userId = userId;
		this.tutorialKey = tutorialKey;
		this.reflection = reflection;
		this.completedAt = completedAt;
	}

	public static PracticeCompletion create(
		Long userId, String tutorialKey, PracticeMarketReflection reflection, LocalDateTime completedAt) {
		return new PracticeCompletion(userId, tutorialKey, reflection, completedAt);
	}
}
