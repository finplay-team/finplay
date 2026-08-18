// 회원별 배지 카테고리마다 현재 달성한 최고 등급 하나만 영속하는 엔티티 (하락 없음, 이력 없음)
package com.finplay.api.badge.domain;

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
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_badges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberBadge {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(name = "badge_type", nullable = false)
	private BadgeType badgeType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private BadgeTier tier;

	@Column(name = "achieved_at", nullable = false)
	private LocalDateTime achievedAt;

	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	private MemberBadge(User user, BadgeType badgeType, BadgeTier tier, LocalDateTime now) {
		this.user = user;
		this.badgeType = badgeType;
		this.tier = tier;
		this.achievedAt = now;
		this.updatedAt = now;
	}

	public static MemberBadge create(User user, BadgeType badgeType, BadgeTier tier, LocalDateTime now) {
		return new MemberBadge(user, badgeType, tier, now);
	}

	// 새 등급이 기존 등급보다 높을 때만 갱신한다(하락 없음 규칙).
	public void raiseTier(BadgeTier newTier, LocalDateTime now) {
		if (newTier.ordinal() <= this.tier.ordinal()) {
			return;
		}
		this.tier = newTier;
		this.updatedAt = now;
	}
}
