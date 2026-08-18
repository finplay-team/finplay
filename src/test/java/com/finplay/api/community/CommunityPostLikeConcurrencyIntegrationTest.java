// 같은 게시물에 동시 좋아요·취소 요청이 몰릴 때 500·좋아요 수 오염이 없는지 실제 MySQL과 스레드로 검증하는 통합 테스트다.
package com.finplay.api.community;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.repository.CommunityPostRepository;
import com.finplay.api.community.service.CommunityPostLikeService;
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

// @Transactional을 붙이지 않는다 — 붙이면 테스트 스레드의 트랜잭션이 커밋되지 않아 작업 스레드가 게시물·사용자를
// 보지 못하고, 작업 스레드들이 각자 트랜잭션을 열어야 동시성 자체가 재현되기 때문이다.
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

	// 이 클래스가 만든 게시물만 지운다 — 다른 통합 테스트가 남긴 행까지 건드리지 않기 위해서다.
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

	// 모든 스레드가 준비된 뒤 시작 래치를 한 번에 풀어 실제 동시 요청을 만든다. 각 스레드에서 던져진 예외는
	// 삼키지 않고 모아 반환한다 — "500이 나지 않는다"를 단언으로 확인해야 하기 때문이다.
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
			// 락 대기·데드락으로 스레드가 멈추면 테스트가 영원히 걸리지 않도록 타임아웃을 실패로 처리한다.
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
