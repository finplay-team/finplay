// 회원 배지 영속과 사용자별·배지 카테고리별 조회를 담당하는 JPA 리포지터리
package com.finplay.api.badge.repository;

import com.finplay.api.badge.domain.BadgeType;
import com.finplay.api.badge.domain.MemberBadge;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberBadgeRepository extends JpaRepository<MemberBadge, Long> {

	List<MemberBadge> findByUserId(Long userId);

	Optional<MemberBadge> findByUserIdAndBadgeType(Long userId, BadgeType badgeType);

	// 커뮤니티 목록 응답의 authorBadges 배치 조회용 — 여러 회원의 배지를 N+1 없이 한 번에 조회한다.
	List<MemberBadge> findAllByUserIdIn(List<Long> userIds);
}
