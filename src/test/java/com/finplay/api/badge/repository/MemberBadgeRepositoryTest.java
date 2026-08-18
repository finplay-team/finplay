// 회원 배지 엔티티 저장·조회, raiseTier 하락 없음 불변식, 배치 조회 쿼리를 검증한다.
package com.finplay.api.badge.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.badge.domain.BadgeTier;
import com.finplay.api.badge.domain.BadgeType;
import com.finplay.api.badge.domain.MemberBadge;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class MemberBadgeRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 18, 10, 0);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private MemberBadgeRepository memberBadgeRepository;

	@Autowired
	private EntityManager entityManager;

	private User user;

	@BeforeEach
	void setUp() {
		user = userRepository.saveAndFlush(User.create("badge@finplay.com", "hash", "badge-user", NOW));
	}

	@Test
	@DisplayName("배지를 저장하고 사용자 ID와 카테고리로 조회한다")
	void savesAndFindsBadgeByUserIdAndBadgeType() {
		memberBadgeRepository.saveAndFlush(
			MemberBadge.create(user, BadgeType.TUTORIAL_COMPLETION, BadgeTier.BRONZE, NOW));
		entityManager.clear();

		var found = memberBadgeRepository.findByUserIdAndBadgeType(user.getId(), BadgeType.TUTORIAL_COMPLETION);

		assertThat(found).isPresent();
		assertThat(found.get().getTier()).isEqualTo(BadgeTier.BRONZE);
		assertThat(found.get().getUser().getId()).isEqualTo(user.getId());
		assertThat(found.get().getAchievedAt()).isEqualTo(NOW);
		assertThat(found.get().getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("raiseTier는 더 높은 등급으로만 갱신하고 updatedAt을 함께 갱신한다")
	void raiseTierUpdatesOnlyWhenNewTierIsHigher() {
		MemberBadge badge = memberBadgeRepository.saveAndFlush(
			MemberBadge.create(user, BadgeType.TUTORIAL_COMPLETION, BadgeTier.SILVER, NOW));

		badge.raiseTier(BadgeTier.GOLD, NOW.plusDays(1));

		assertThat(badge.getTier()).isEqualTo(BadgeTier.GOLD);
		assertThat(badge.getUpdatedAt()).isEqualTo(NOW.plusDays(1));
	}

	@Test
	@DisplayName("raiseTier는 더 낮은 등급으로는 내려가지 않는다(하락 없음 불변식)")
	void raiseTierDoesNotDowngradeToLowerTier() {
		MemberBadge badge = memberBadgeRepository.saveAndFlush(
			MemberBadge.create(user, BadgeType.TUTORIAL_COMPLETION, BadgeTier.GOLD, NOW));

		badge.raiseTier(BadgeTier.BRONZE, NOW.plusDays(1));

		assertThat(badge.getTier()).isEqualTo(BadgeTier.GOLD);
		assertThat(badge.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("raiseTier는 같은 등급으로는 갱신하지 않는다(하락 없음 불변식의 경계값)")
	void raiseTierDoesNotUpdateWhenNewTierIsEqual() {
		MemberBadge badge = memberBadgeRepository.saveAndFlush(
			MemberBadge.create(user, BadgeType.TUTORIAL_COMPLETION, BadgeTier.SILVER, NOW));

		badge.raiseTier(BadgeTier.SILVER, NOW.plusDays(1));

		assertThat(badge.getTier()).isEqualTo(BadgeTier.SILVER);
		assertThat(badge.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	@DisplayName("raiseTier로 낮춘 뒤 다시 저장해도 DB에는 하락한 값이 반영되지 않는다")
	void raiseTierInvariantHoldsAfterPersisting() {
		MemberBadge badge = memberBadgeRepository.saveAndFlush(
			MemberBadge.create(user, BadgeType.TUTORIAL_COMPLETION, BadgeTier.PLATINUM, NOW));

		badge.raiseTier(BadgeTier.BRONZE, NOW.plusDays(1));
		memberBadgeRepository.saveAndFlush(badge);
		entityManager.clear();

		var reloaded = memberBadgeRepository.findByUserIdAndBadgeType(user.getId(), BadgeType.TUTORIAL_COMPLETION);

		assertThat(reloaded).isPresent();
		assertThat(reloaded.get().getTier()).isEqualTo(BadgeTier.PLATINUM);
	}

	@Test
	@DisplayName("같은 회원·같은 카테고리는 한 행만 저장할 수 있다(uk_member_badges_user_type)")
	void savingDuplicateUserAndBadgeTypeFailsWithUniqueConstraint() {
		memberBadgeRepository.saveAndFlush(
			MemberBadge.create(user, BadgeType.TUTORIAL_COMPLETION, BadgeTier.BRONZE, NOW));

		assertThatThrownBy(() -> memberBadgeRepository.saveAndFlush(
			MemberBadge.create(user, BadgeType.TUTORIAL_COMPLETION, BadgeTier.GOLD, NOW.plusSeconds(1))))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("한 회원은 서로 다른 카테고리의 배지를 각각 가질 수 있다")
	void sameUserCanHaveBadgesInDifferentCategories() {
		memberBadgeRepository.saveAndFlush(
			MemberBadge.create(user, BadgeType.TUTORIAL_COMPLETION, BadgeTier.BRONZE, NOW));
		memberBadgeRepository.saveAndFlush(
			MemberBadge.create(user, BadgeType.REFLECTION_COUNT, BadgeTier.SILVER, NOW));
		entityManager.clear();

		List<MemberBadge> badges = memberBadgeRepository.findByUserId(user.getId());

		assertThat(badges).hasSize(2)
			.extracting(MemberBadge::getBadgeType)
			.containsExactlyInAnyOrder(BadgeType.TUTORIAL_COMPLETION, BadgeType.REFLECTION_COUNT);
	}

	@Test
	@DisplayName("findAllByUserIdIn은 여러 회원의 배지를 한 번에 배치 조회한다")
	void findAllByUserIdInReturnsBadgesForAllRequestedUsers() {
		User other = userRepository.saveAndFlush(User.create("badge2@finplay.com", "hash", "badge-user-2", NOW));
		memberBadgeRepository.saveAndFlush(
			MemberBadge.create(user, BadgeType.TUTORIAL_COMPLETION, BadgeTier.BRONZE, NOW));
		memberBadgeRepository.saveAndFlush(
			MemberBadge.create(other, BadgeType.REALIZED_PNL_STOCK, BadgeTier.GOLD, NOW));
		entityManager.clear();

		List<MemberBadge> badges = memberBadgeRepository.findAllByUserIdIn(List.of(user.getId(), other.getId()));

		assertThat(badges).hasSize(2)
			.extracting(b -> b.getUser().getId())
			.containsExactlyInAnyOrder(user.getId(), other.getId());
	}

	@Test
	@DisplayName("findAllByUserIdIn은 배지가 없는 회원은 결과에서 제외한다")
	void findAllByUserIdInExcludesUsersWithoutBadges() {
		User withoutBadge = userRepository.saveAndFlush(
			User.create("nobadge@finplay.com", "hash", "no-badge-user", NOW));
		memberBadgeRepository.saveAndFlush(
			MemberBadge.create(user, BadgeType.TUTORIAL_COMPLETION, BadgeTier.BRONZE, NOW));
		entityManager.clear();

		List<MemberBadge> badges = memberBadgeRepository.findAllByUserIdIn(List.of(user.getId(), withoutBadge.getId()));

		assertThat(badges).hasSize(1);
		assertThat(badges.get(0).getUser().getId()).isEqualTo(user.getId());
	}

	@Test
	@DisplayName("findAllByUserIdIn에 빈 목록을 넘기면 빈 결과를 반환한다")
	void findAllByUserIdInReturnsEmptyListForEmptyInput() {
		memberBadgeRepository.saveAndFlush(
			MemberBadge.create(user, BadgeType.TUTORIAL_COMPLETION, BadgeTier.BRONZE, NOW));

		List<MemberBadge> badges = memberBadgeRepository.findAllByUserIdIn(List.of());

		assertThat(badges).isEmpty();
	}

	@Test
	@DisplayName("배지가 없는 카테고리를 조회하면 빈 값을 반환한다")
	void findByUserIdAndBadgeTypeReturnsEmptyWhenNotAchieved() {
		var found = memberBadgeRepository.findByUserIdAndBadgeType(user.getId(), BadgeType.REALIZED_PNL_CRYPTO);

		assertThat(found).isEmpty();
	}
}
