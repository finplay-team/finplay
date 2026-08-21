// 실제 MySQL에서 게시물 좋아요의 유니크 제약, 존재·단건·배치 조회, 게시물 삭제 시 연쇄 삭제를 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.domain.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.entity.CommunityPostLike;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class CommunityPostLikeRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 12, 34, 56, 123456000);

	@Autowired
	private CommunityPostLikeRepository repository;

	@Autowired
	private CommunityPostRepository postRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@BeforeEach
	void cleanSharedTablesInForeignKeySafeOrder() {
		jdbcTemplate.update("delete from community_post_likes");
		jdbcTemplate.update("delete from post_comments where parent_comment_id is not null");
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_posts");
	}

	@Test
	void databaseRejectsDuplicateLikeForSamePostAndUser() {
		User author = userRepository.saveAndFlush(User.create("like-author@finplay.com", "hash", "likeauthor", NOW));
		User liker = userRepository.saveAndFlush(User.create("like-user@finplay.com", "hash", "liker", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));
		repository.saveAndFlush(CommunityPostLike.create(post, liker, NOW));

		assertThatThrownBy(() -> repository.saveAndFlush(CommunityPostLike.create(post, liker, NOW)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void existsByPostIdAndUserIdReturnsTrueWhenLikeExists() {
		User author = userRepository
			.saveAndFlush(User.create("exists-author@finplay.com", "hash", "existsauthor", NOW));
		User liker = userRepository.saveAndFlush(User.create("exists-user@finplay.com", "hash", "exister", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));
		repository.saveAndFlush(CommunityPostLike.create(post, liker, NOW));

		boolean exists = repository.existsByPost_IdAndUser_Id(post.getId(), liker.getId());

		assertThat(exists).isTrue();
	}

	@Test
	void existsByPostIdAndUserIdReturnsFalseWhenLikeDoesNotExist() {
		User author = userRepository.saveAndFlush(
			User.create("notexists-author@finplay.com", "hash", "notexistsauthor", NOW));
		User nonLiker = userRepository.saveAndFlush(
			User.create("notexists-user@finplay.com", "hash", "notexister", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));

		boolean exists = repository.existsByPost_IdAndUser_Id(post.getId(), nonLiker.getId());

		assertThat(exists).isFalse();
	}

	@Test
	void findByPostIdAndUserIdReturnsMatchingLike() {
		User author = userRepository.saveAndFlush(User.create("find-author@finplay.com", "hash", "findauthor", NOW));
		User liker = userRepository.saveAndFlush(User.create("find-user@finplay.com", "hash", "finder", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));
		CommunityPostLike saved = repository.saveAndFlush(CommunityPostLike.create(post, liker, NOW));

		Optional<CommunityPostLike> found = repository.findByPost_IdAndUser_Id(post.getId(), liker.getId());

		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(saved.getId());
	}

	@Test
	void findByPostIdAndUserIdReturnsEmptyWhenLikeDoesNotExist() {
		User author = userRepository.saveAndFlush(
			User.create("findempty-author@finplay.com", "hash", "findemptyauthor", NOW));
		User nonLiker = userRepository.saveAndFlush(
			User.create("findempty-user@finplay.com", "hash", "findempty", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));

		Optional<CommunityPostLike> found = repository.findByPost_IdAndUser_Id(post.getId(), nonLiker.getId());

		assertThat(found).isEmpty();
	}

	@Test
	void findLikedPostIdsReturnsOnlyPostsLikedByGivenUserAmongRequestedIds() {
		User author = userRepository.saveAndFlush(User.create("batch-author@finplay.com", "hash", "batchauthor", NOW));
		User liker = userRepository.saveAndFlush(User.create("batch-user@finplay.com", "hash", "batchliker", NOW));
		CommunityPost liked1 = postRepository.saveAndFlush(
			CommunityPost.create(author, "liked1", "content", null, NOW));
		CommunityPost notLiked = postRepository.saveAndFlush(
			CommunityPost.create(author, "notliked", "content", null, NOW));
		CommunityPost liked2 = postRepository.saveAndFlush(
			CommunityPost.create(author, "liked2", "content", null, NOW));
		repository.saveAndFlush(CommunityPostLike.create(liked1, liker, NOW));
		repository.saveAndFlush(CommunityPostLike.create(liked2, liker, NOW));

		List<Long> likedPostIds = repository.findLikedPostIds(
			liker.getId(), List.of(liked1.getId(), notLiked.getId(), liked2.getId()));

		assertThat(likedPostIds).containsExactlyInAnyOrder(liked1.getId(), liked2.getId());
	}

	@Test
	void findLikedPostIdsExcludesLikesFromOtherUsers() {
		User author = userRepository.saveAndFlush(
			User.create("otheruser-author@finplay.com", "hash", "otheruserauthor", NOW));
		User liker = userRepository.saveAndFlush(User.create("otheruser-liker@finplay.com", "hash", "otheruser1", NOW));
		User otherUser = userRepository.saveAndFlush(
			User.create("otheruser-other@finplay.com", "hash", "otheruser2", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));
		repository.saveAndFlush(CommunityPostLike.create(post, otherUser, NOW));

		List<Long> likedPostIds = repository.findLikedPostIds(liker.getId(), List.of(post.getId()));

		assertThat(likedPostIds).isEmpty();
	}

	// N+1 방지 근거: 여러 게시물의 좋아요 여부를 게시물마다 따로 쿼리하지 않고, findLikedPostIds
	// 하나의 IN 쿼리로 해결한다는 것을 실제 준비된 SQL 문 개수로 증명한다(spec 045 plan.md).
	@Test
	void findLikedPostIdsExecutesExactlyOneQueryRegardlessOfPostIdCount() {
		User author = userRepository.saveAndFlush(
			User.create("statcount-author@finplay.com", "hash", "statcountauthor", NOW));
		User liker = userRepository.saveAndFlush(User.create("statcount-user@finplay.com", "hash", "statcounter", NOW));
		CommunityPost post1 = postRepository.saveAndFlush(CommunityPost.create(author, "p1", "content", null, NOW));
		CommunityPost post2 = postRepository.saveAndFlush(CommunityPost.create(author, "p2", "content", null, NOW));
		CommunityPost post3 = postRepository.saveAndFlush(CommunityPost.create(author, "p3", "content", null, NOW));
		repository.saveAndFlush(CommunityPostLike.create(post1, liker, NOW));
		repository.saveAndFlush(CommunityPostLike.create(post3, liker, NOW));
		entityManager.clear();

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();

		List<Long> likedPostIds = repository.findLikedPostIds(
			liker.getId(), List.of(post1.getId(), post2.getId(), post3.getId()));

		assertThat(likedPostIds).containsExactlyInAnyOrder(post1.getId(), post3.getId());
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
	}

	@Test
	void deletingPostCascadesDeleteOfItsLikesAtDatabaseLevel() {
		User author = userRepository.saveAndFlush(
			User.create("cascade-author@finplay.com", "hash", "cascadeauthor", NOW));
		User liker = userRepository.saveAndFlush(User.create("cascade-user@finplay.com", "hash", "cascader", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));
		CommunityPostLike like = repository.saveAndFlush(CommunityPostLike.create(post, liker, NOW));

		// FK의 ON DELETE CASCADE(V41)를 검증하는 게 목적이므로, JPA delete()로 영속성 컨텍스트 내
		// 연관 엔티티(cascade 설정 없음)까지 얽히게 하지 않고 DB 레벨 삭제를 직접 실행한다.
		jdbcTemplate.update("delete from community_posts where id = ?", post.getId());
		entityManager.clear();

		assertThat(repository.findById(like.getId())).isEmpty();
	}
}
