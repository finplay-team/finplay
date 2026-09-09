package com.finplay.api.domain.education.entity;

import com.finplay.api.domain.auth.entity.User;
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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "practice_progresses", uniqueConstraints = @UniqueConstraint(name = "uk_practice_progresses_user_tutorial", columnNames = {
	"user_id", "tutorial_key"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PracticeProgress {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "tutorial_key", nullable = false, length = 50)
	private String tutorialKey;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PracticeProgressStatus status;

	@Column(name = "started_at", nullable = false)
	private LocalDateTime startedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	public void complete(LocalDateTime completedAt) {
		if (this.status == PracticeProgressStatus.COMPLETED) {
			throw new IllegalStateException("이미 완료된 실습 진행 상태는 다시 완료할 수 없습니다. id=" + this.id);
		}
		this.status = PracticeProgressStatus.COMPLETED;
		this.completedAt = completedAt;
	}
}
