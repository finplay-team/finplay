// 실제 MySQL에서 댓글의 연관관계, 본문 길이, 생성 시각 정밀도를 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.domain.PostComment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class PostCommentRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 12, 34, 56, 123456000);

	@Autowired
	private PostCommentRepository repository;

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
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_posts");
	}

	@Test
	void savePersistsPostAuthorContentAndMicrosecondTimestamp() {
		User author = userRepository.saveAndFlush(User.create("comment@finplay.com", "hash", "commenter", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));

		PostComment saved = repository.saveAndFlush(PostComment.create(post, author, "c".repeat(1000), null, NOW));
		PostComment found = repository.findById(saved.getId()).orElseThrow();

		assertThat(found.getPost().getId()).isEqualTo(post.getId());
		assertThat(found.getAuthor().getId()).isEqualTo(author.getId());
		assertThat(found.getContent()).hasSize(1000);
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void databaseRejectsUnknownPostForeignKey() {
		User author = userRepository.saveAndFlush(User.create("post-fk@finplay.com", "hash", "post-fk", NOW));

		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into post_comments(post_id,author_id,content,created_at) values (?,?,?,?)",
			Long.MAX_VALUE, author.getId(), "content", NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsUnknownAuthorForeignKey() {
		User postAuthor = userRepository.saveAndFlush(User.create("author-fk@finplay.com", "hash", "author-fk", NOW));
		CommunityPost post = postRepository
			.saveAndFlush(CommunityPost.create(postAuthor, "title", "content", null, NOW));

		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into post_comments(post_id,author_id,content,created_at) values (?,?,?,?)",
			post.getId(), Long.MAX_VALUE, "content", NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsContentBeyond1000Characters() {
		User author = userRepository.saveAndFlush(User.create("long@finplay.com", "hash", "long", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));

		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into post_comments(post_id,author_id,content,created_at) values (?,?,?,?)",
			post.getId(), author.getId(), "c".repeat(1001), NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void findAllByPostIdReturnsOnlyTargetPostCommentsInCreatedAtAndIdAscendingOrder() {
		User firstAuthor = createUser("first");
		User secondAuthor = createUser("second");
		CommunityPost target = postRepository
			.saveAndFlush(CommunityPost.create(firstAuthor, "target", "post", null, NOW));
		CommunityPost other = postRepository
			.saveAndFlush(CommunityPost.create(firstAuthor, "other", "post", null, NOW));
		PostComment oldest = repository.saveAndFlush(
			PostComment.create(target, firstAuthor, "oldest", null, NOW.minusMinutes(1)));
		PostComment firstTie = repository.saveAndFlush(PostComment.create(target, firstAuthor, "first tie", null, NOW));
		PostComment secondTie = repository
			.saveAndFlush(PostComment.create(target, secondAuthor, "second tie", null, NOW));
		repository.saveAndFlush(PostComment.create(other, secondAuthor, "other post", null, NOW.minusMinutes(2)));
		entityManager.clear();

		List<PostComment> comments = repository.findAllByPostIdOrderByCreatedAtAscIdAsc(target.getId());

		assertThat(comments)
			.extracting(PostComment::getId)
			.containsExactly(oldest.getId(), firstTie.getId(), secondTie.getId());
		assertThat(comments)
			.extracting(PostComment::getContent)
			.doesNotContain("other post");
	}

	@Test
	void findAllByPostIdFetchesAuthorsInOneQueryAfterPersistenceContextClear() {
		User firstAuthor = createUser("fetch-first");
		User secondAuthor = createUser("fetch-second");
		CommunityPost post = postRepository
			.saveAndFlush(CommunityPost.create(firstAuthor, "target", "post", null, NOW));
		repository.saveAndFlush(PostComment.create(post, firstAuthor, "first", null, NOW));
		repository.saveAndFlush(PostComment.create(post, secondAuthor, "second", null, NOW.plusMinutes(1)));
		entityManager.clear();
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();

		List<PostComment> comments = repository.findAllByPostIdOrderByCreatedAtAscIdAsc(post.getId());
		assertThat(comments)
			.extracting(comment -> comment.getAuthor().getNickname())
			.containsExactly(firstAuthor.getNickname(), secondAuthor.getNickname());

		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
	}

	@Test
	void migrationCreatesParentCommentIdColumnAsNullable() {
		Map<String, Object> column = jdbcTemplate.queryForMap(
			"select is_nullable from information_schema.columns "
				+ "where table_schema = database() and table_name = 'post_comments' "
				+ "and column_name = 'parent_comment_id'");

		assertThat(column.get("is_nullable")).isEqualTo("YES");
	}

	@Test
	void migrationCreatesIndexOnParentCommentId() {
		List<Map<String, Object>> indexColumns = jdbcTemplate.queryForList(
			"select column_name, seq_in_index from information_schema.statistics "
				+ "where table_schema = database() and table_name = 'post_comments' "
				+ "and index_name = 'idx_post_comments_parent' "
				+ "order by seq_in_index");

		assertThat(indexColumns).hasSize(1);
		assertThat(indexColumns.get(0).get("column_name")).isEqualTo("parent_comment_id");
	}

	@Test
	void databaseRejectsUnknownParentCommentForeignKey() {
		User author = userRepository.saveAndFlush(User.create("parent-fk@finplay.com", "hash", "parent-fk", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));

		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into post_comments(post_id,author_id,content,parent_comment_id,created_at) values (?,?,?,?,?)",
			post.getId(), author.getId(), "content", Long.MAX_VALUE, NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void savePersistsParentCommentAndFindByIdReturnsReplyMarkedAsReply() {
		User author = userRepository.saveAndFlush(User.create("reply@finplay.com", "hash", "replier", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));
		PostComment parent = repository.saveAndFlush(PostComment.create(post, author, "parent", null, NOW));

		PostComment reply = repository.saveAndFlush(
			PostComment.create(post, author, "reply", parent, NOW.plusMinutes(1)));
		entityManager.clear();

		PostComment found = repository.findById(reply.getId()).orElseThrow();
		assertThat(found.getParentComment().getId()).isEqualTo(parent.getId());
		assertThat(found.isReply()).isTrue();
	}

	@Test
	void deletingParentCommentCascadesToChildReplyAtDatabaseLevel() {
		User author = userRepository.saveAndFlush(User.create("cascade@finplay.com", "hash", "cascader", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));
		PostComment parent = repository.saveAndFlush(PostComment.create(post, author, "parent", null, NOW));
		PostComment reply = repository.saveAndFlush(
			PostComment.create(post, author, "reply", parent, NOW.plusMinutes(1)));
		entityManager.clear();

		jdbcTemplate.update("delete from post_comments where id = ?", parent.getId());

		assertThat(repository.findById(parent.getId())).isEmpty();
		assertThat(repository.findById(reply.getId())).isEmpty();
	}

	private User createUser(String prefix) {
		String unique = UUID.randomUUID().toString().replace("-", "");
		return userRepository.saveAndFlush(
			User.create(prefix + "-" + unique + "@finplay.com", "hash", prefix + "-" + unique, NOW));
	}
}
