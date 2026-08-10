// 사용자별 투자 실습 튜토리얼의 진행 상태를 보존하는 엔티티
package com.finplay.api.education.domain;

import com.finplay.api.auth.domain.User;
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

	// 완료는 불변이다(spec MKT-PRACTICE-009) — 호출부가 COMPLETED 여부를 먼저 걸러내는 것과 별개로, 엔티티
	// 스스로도 재완료를 막아 가드 없는 호출부가 completedAt을 조용히 덮어쓰는 것을 방지한다(PR #304 리뷰 권장).
	public void complete(LocalDateTime completedAt) {
		if (this.status == PracticeProgressStatus.COMPLETED) {
			throw new IllegalStateException("이미 완료된 실습 진행 상태는 다시 완료할 수 없습니다. id=" + this.id);
		}
		this.status = PracticeProgressStatus.COMPLETED;
		this.completedAt = completedAt;
	}
}
