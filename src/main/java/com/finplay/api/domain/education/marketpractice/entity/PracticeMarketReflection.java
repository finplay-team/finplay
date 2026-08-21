// 시장가/지정가 매매 기반 실습 3단계의 자유 복기 1건(튜토리얼당 1회 완료)을 영속하는 엔티티
package com.finplay.api.domain.education.marketpractice.entity;

import com.finplay.api.domain.portfolio.entity.Holding;
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
@Table(name = "practice_market_reflections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeMarketReflection {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "holding_id", nullable = false)
	private Holding holding;

	@Column(name = "tutorial_key", nullable = false, length = 50)
	private String tutorialKey;

	@Column(name = "prompt_version", nullable = false)
	private short promptVersion;

	@Column(name = "answer", nullable = false, length = 2000)
	private String answer;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	private PracticeMarketReflection(
		Long userId, Holding holding, String tutorialKey, short promptVersion, String answer,
		LocalDateTime createdAt) {
		this.userId = userId;
		this.holding = holding;
		this.tutorialKey = tutorialKey;
		this.promptVersion = promptVersion;
		this.answer = answer;
		this.createdAt = createdAt;
	}

	public static PracticeMarketReflection create(
		Long userId, Holding holding, String tutorialKey, short promptVersion, String answer,
		LocalDateTime now) {
		return new PracticeMarketReflection(userId, holding, tutorialKey, promptVersion, answer, now);
	}
}
