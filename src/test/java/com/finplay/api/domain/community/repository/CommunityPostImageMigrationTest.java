// V26 마이그레이션(community_post_images)의 FK·UNIQUE·CASCADE 제약을 순수 JDBC로 검증하는 슬라이스 테스트다.
// CommunityPostImage 엔티티가 아직 없으므로 JdbcTemplate만 사용한다(tasks.md COM-006 항목 1).
package com.finplay.api.domain.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.community.entity.CommunityPost;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class CommunityPostImageMigrationTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 12, 34, 56, 123456000);

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private CommunityPostRepository postRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanSharedTablesInForeignKeySafeOrder() {
		// V31: parent_comment_id FK가 ON DELETE RESTRICT라 단일 "delete from post_comments"는
		// 다른 테스트 컨텍스트가 남긴 부모+자식이 섞여 있으면 행 처리 순서 미보장으로 실패할 수 있다(이슈 #277).
		jdbcTemplate.update("delete from community_post_images");
		jdbcTemplate.update("delete from post_comments where parent_comment_id is not null");
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_posts");
	}

	@Test
	void migrationCreatesPostIdColumnAsNullable() {
		Map<String, Object> column = jdbcTemplate.queryForMap(
			"select is_nullable from information_schema.columns "
				+ "where table_schema = database() and table_name = 'community_post_images' "
				+ "and column_name = 'post_id'");

		assertThat(column.get("is_nullable")).isEqualTo("YES");
	}

	@Test
	void migrationCreatesUniqueConstraintOnPostId() {
		List<Map<String, Object>> indexColumns = jdbcTemplate.queryForList(
			"select column_name, non_unique from information_schema.statistics "
				+ "where table_schema = database() and table_name = 'community_post_images' "
				+ "and index_name = 'uq_community_post_images_post'");

		assertThat(indexColumns).hasSize(1);
		assertThat(indexColumns.get(0).get("column_name")).isEqualTo("post_id");
		assertThat(((Number)indexColumns.get(0).get("non_unique")).intValue()).isZero();
	}

	@Test
	void databaseRejectsUnknownUploaderForeignKey() {
		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into community_post_images"
				+ "(uploader_id,post_id,stored_filename,original_filename,content_type,size_bytes,created_at) "
				+ "values (?,?,?,?,?,?,?)",
			Long.MAX_VALUE, null, "stored.png", "original.png", "image/png", 1L, NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsUnknownPostForeignKey() {
		User uploader = createUser("unknown-post-fk");

		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into community_post_images"
				+ "(uploader_id,post_id,stored_filename,original_filename,content_type,size_bytes,created_at) "
				+ "values (?,?,?,?,?,?,?)",
			uploader.getId(), Long.MAX_VALUE, "stored.png", "original.png", "image/png", 1L, NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsSecondImageAttachedToSamePostViaUniqueConstraint() {
		User uploader = createUser("unique-post");
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(uploader, "title", "content", null, NOW));
		jdbcTemplate.update(
			"insert into community_post_images"
				+ "(uploader_id,post_id,stored_filename,original_filename,content_type,size_bytes,created_at) "
				+ "values (?,?,?,?,?,?,?)",
			uploader.getId(), post.getId(), "first.png", "first.png", "image/png", 1L, NOW);

		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into community_post_images"
				+ "(uploader_id,post_id,stored_filename,original_filename,content_type,size_bytes,created_at) "
				+ "values (?,?,?,?,?,?,?)",
			uploader.getId(), post.getId(), "second.png", "second.png", "image/png", 1L, NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingPostCascadesToItsImageAtDatabaseLevel() {
		User uploader = createUser("cascade-post");
		CommunityPost post = postRepository.saveAndFlush(CommunityPost.create(uploader, "title", "content", null, NOW));
		jdbcTemplate.update(
			"insert into community_post_images"
				+ "(uploader_id,post_id,stored_filename,original_filename,content_type,size_bytes,created_at) "
				+ "values (?,?,?,?,?,?,?)",
			uploader.getId(), post.getId(), "cascade.png", "cascade.png", "image/png", 1L, NOW);

		jdbcTemplate.update("delete from community_posts where id = ?", post.getId());

		List<Map<String, Object>> remaining = jdbcTemplate.queryForList(
			"select id from community_post_images where post_id = ?", post.getId());
		assertThat(remaining).isEmpty();
	}

	@Test
	void nullPostIdDoesNotCollideWithUniqueConstraint() {
		User firstUploader = createUser("null-post-1");
		User secondUploader = createUser("null-post-2");

		jdbcTemplate.update(
			"insert into community_post_images"
				+ "(uploader_id,post_id,stored_filename,original_filename,content_type,size_bytes,created_at) "
				+ "values (?,?,?,?,?,?,?)",
			firstUploader.getId(), null, "unassigned-1.png", "unassigned-1.png", "image/png", 1L, NOW);

		jdbcTemplate.update(
			"insert into community_post_images"
				+ "(uploader_id,post_id,stored_filename,original_filename,content_type,size_bytes,created_at) "
				+ "values (?,?,?,?,?,?,?)",
			secondUploader.getId(), null, "unassigned-2.png", "unassigned-2.png", "image/png", 1L, NOW);

		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
			"select id from community_post_images where post_id is null");
		assertThat(rows).hasSize(2);
	}

	private User createUser(String prefix) {
		String unique = UUID.randomUUID().toString().replace("-", "");
		return userRepository.saveAndFlush(
			User.create(prefix + "-" + unique + "@finplay.com", "hash", prefix + "-" + unique, NOW));
	}
}
