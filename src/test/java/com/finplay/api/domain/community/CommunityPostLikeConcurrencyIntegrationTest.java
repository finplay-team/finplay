package com.finplay.api.domain.community;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.repository.CommunityPostRepository;
import com.finplay.api.domain.community.service.CommunityPostLikeService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CommunityPostLikeConcurrencyIntegrationTest {

	private static final int AWAIT_TIMEOUT_SECONDS = 30;

	@Autowired
	private CommunityPostLikeService communityPostLikeService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CommunityPostRepository postRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final List<Long> createdPostIds = new ArrayList<>();

	@AfterEach
	void removeDataCreatedByThisTestClass() {
		for (Long postId : createdPostIds) {
			jdbcTemplate.update("delete from community_post_likes where post_id = ?", postId);
			jdbcTemplate.update("delete from community_posts where id = ?", postId);
		}
		createdPostIds.clear();
	}

	@Test
	void concurrentLikesFromSameUserLeaveExactlyOneLikeWithoutError() throws Exception {
		User author = createUser("conc-like-author");
		User liker = createUser("conc-like-liker");
		Long postId = createPost(author);

		List<Throwable> failures = runConcurrently(2,
			index -> communityPostLikeService.likePost(postId, liker.getId()));

		assertThat(failures).isEmpty();
		assertThat(likeCountColumn(postId)).isEqualTo(1L);
		assertThat(countLikeRows(postId)).isEqualTo(1L);
	}

	@Test
	void concurrentUnlikesFromSameUserLeaveZeroLikesWithoutNegativeCountOrError() throws Exception {
		User author = createUser("conc-unlike-author");
		User liker = createUser("conc-unlike-liker");
		Long postId = createPost(author);
		communityPostLikeService.likePost(postId, liker.getId());
		assertThat(likeCountColumn(postId)).isEqualTo(1L);

		List<Throwable> failures = runConcurrently(2,
			index -> communityPostLikeService.unlikePost(postId, liker.getId()));

		assertThat(failures).isEmpty();
		assertThat(likeCountColumn(postId)).isZero();
		assertThat(countLikeRows(postId)).isZero();
	}

	@Test
	void concurrentLikesFromDifferentUsersCountEveryLikeWithoutLostUpdate() throws Exception {
		int likerCount = 5;
		User author = createUser("conc-multi-author");
		List<User> likers = new ArrayList<>();
		for (int i = 0; i < likerCount; i++) {
			likers.add(createUser("conc-multi-liker" + i));
		}
		Long postId = createPost(author);

		List<Throwable> failures = runConcurrently(likerCount,
			index -> communityPostLikeService.likePost(postId, likers.get(index).getId()));

		assertThat(failures).isEmpty();
		assertThat(likeCountColumn(postId)).isEqualTo(likerCount);
		assertThat(countLikeRows(postId)).isEqualTo(likerCount);
	}

	private List<Throwable> runConcurrently(int threadCount, IntConsumer action) throws InterruptedException {
		List<Throwable> failures = new CopyOnWriteArrayList<>();
		CountDownLatch ready = new CountDownLatch(threadCount);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threadCount);
		try {
			for (int i = 0; i < threadCount; i++) {
				int index = i;
				pool.submit(() -> {
					ready.countDown();
					try {
						start.await();
						action.accept(index);
					} catch (Throwable failure) {
						failures.add(failure);
					}
				});
			}
			assertThat(ready.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
		} finally {
			pool.shutdownNow();
		}
		return failures;
	}

	private Long likeCountColumn(Long postId) {
		return jdbcTemplate.queryForObject("select like_count from community_posts where id = ?", Long.class, postId);
	}

	private Long countLikeRows(Long postId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from community_post_likes where post_id = ?", Long.class, postId);
	}

	private Long createPost(User author) {
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, LocalDateTime.now()));
		createdPostIds.add(post.getId());
		return post.getId();
	}

	private User createUser(String prefix) {
		String unique = UUID.randomUUID().toString().replace("-", "");
		String nickname = prefix + "-" + unique;
		if (nickname.length() > 50) {
			nickname = nickname.substring(0, 50);
		}
		return userRepository.saveAndFlush(User.create(
			prefix + "-" + unique + "@finplay.com",
			"hash",
			nickname,
			LocalDateTime.now()));
	}
}
