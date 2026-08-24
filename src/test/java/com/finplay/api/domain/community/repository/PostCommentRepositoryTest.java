// 실제 MySQL에서 댓글의 연관관계, 본문 길이, 생성 시각 정밀도를 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.domain.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.entity.PostComment;
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
		// V31: parent_comment_id FK가 ON DELETE RESTRICT라 단일 "delete from post_comments"는
		// 이전 테스트가 남긴 부모+자식이 섞여 있으면 행 처리 순서 미보장으로 실패할 수 있다(이슈 #277).
		jdbcTemplate.update("delete from post_comments where parent_comment_id is not null");
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

	// Issue #277(V31)로 부모 댓글의 자식 CASCADE 삭제가 RESTRICT로 전환됐다. 자식이 남아있는 부모를
	// 원시 SQL로 직접 지우려 하면 더 이상 조용히 성공(cascade)하지 않고 FK 제약 위반으로 거부돼야 한다 —
	// 이 테스트는 위 CASCADE 가정 테스트를 대체한다.
	@Test
	void databaseRejectsDeletingParentCommentThatHasChildReplyAfterFkChangedToRestrict() {
		User author = userRepository.saveAndFlush(User.create("restrict@finplay.com", "hash", "restrictor", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));
		PostComment parent = repository.saveAndFlush(PostComment.create(post, author, "parent", null, NOW));
		repository.saveAndFlush(PostComment.create(post, author, "reply", parent, NOW.plusMinutes(1)));
		entityManager.clear();

		assertThatThrownBy(() -> jdbcTemplate.update("delete from post_comments where id = ?", parent.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// tasks.md 항목 1의 "리포지토리 레벨에서 직접 delete()" 검증 — repository.delete()로 관리 엔티티를
	// 지우고 flush 시점에 같은 RESTRICT 제약 위반이 발생하는지 확인한다(원시 SQL이 아닌 JPA 경로).
	@Test
	void repositoryDeleteOnParentCommentWithChildReplyViolatesRestrictConstraintOnFlush() {
		User author = userRepository
			.saveAndFlush(User.create("jpa-restrict@finplay.com", "hash", "jpaRestrictor", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));
		PostComment parent = repository.saveAndFlush(PostComment.create(post, author, "parent", null, NOW));
		repository.saveAndFlush(PostComment.create(post, author, "reply", parent, NOW.plusMinutes(1)));
		entityManager.clear();
		PostComment reloadedParent = repository.findById(parent.getId()).orElseThrow();

		repository.delete(reloadedParent);

		assertThatThrownBy(repository::flush).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void migrationCreatesDeletedAtColumnAsNullable() {
		Map<String, Object> column = jdbcTemplate.queryForMap(
			"select is_nullable from information_schema.columns "
				+ "where table_schema = database() and table_name = 'post_comments' "
				+ "and column_name = 'deleted_at'");

		assertThat(column.get("is_nullable")).isEqualTo("YES");
	}

	// plan.md V31 "주의" 캐벗의 회귀 확인 — 원래 단일 벌크 DELETE(deleteByPost_Id)는 RESTRICT 제약 하에서
	// 부모가 자식보다 먼저 처리될 경우 FK 위반으로 실패함을 이전 테스트 실행에서 재현했다(별도 이슈 보고 대상).
	// implementer가 이를 자식 먼저(deleteByPost_IdAndParentCommentIsNotNull) → 부모 나중
	// (deleteByPost_IdAndParentCommentIsNull) 두 단계 호출로 고쳤다 — 이 테스트는 그 순서를 지키면
	// 부모+자식이 섞인 게시물 삭제가 실제로 성공하는지 리포지토리 레벨에서 재확인한다.
	@Test
	void deleteByPostIdTwoStepOrderSucceedsForPostWithMixedParentAndChildCommentsUnderRestrictFk() {
		User author = userRepository
			.saveAndFlush(User.create("bulk-restrict@finplay.com", "hash", "bulkRestrict", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));
		PostComment parent = repository.saveAndFlush(PostComment.create(post, author, "parent", null, NOW));
		repository.saveAndFlush(PostComment.create(post, author, "reply", parent, NOW.plusMinutes(1)));
		entityManager.clear();

		repository.deleteByPost_IdAndParentCommentIsNotNull(post.getId());
		repository.deleteByPost_IdAndParentCommentIsNull(post.getId());

		assertThat(repository.findAllByPostIdOrderByCreatedAtAscIdAsc(post.getId())).isEmpty();
	}

	// 순서를 반대로(부모 먼저) 호출하면 여전히 RESTRICT 위반으로 실패해야 한다 — 두 메서드로 나눈 것 자체가
	// 아니라 "자식 먼저 호출하는 순서"가 회귀 수정의 핵심임을 명확히 하는 대조 테스트.
	@Test
	void deleteByPostIdReversedOrderStillViolatesRestrictConstraintWhenParentDeletedBeforeChild() {
		User author = userRepository
			.saveAndFlush(User.create("reversed-order@finplay.com", "hash", "reversedOrder", NOW));
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));
		PostComment parent = repository.saveAndFlush(PostComment.create(post, author, "parent", null, NOW));
		repository.saveAndFlush(PostComment.create(post, author, "reply", parent, NOW.plusMinutes(1)));
		entityManager.clear();

		assertThatThrownBy(() -> repository.deleteByPost_IdAndParentCommentIsNull(post.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private User createUser(String prefix) {
		String unique = UUID.randomUUID().toString().replace("-", "");
		return userRepository.saveAndFlush(
			User.create(prefix + "-" + unique + "@finplay.com", "hash", prefix + "-" + unique, NOW));
	}
}
