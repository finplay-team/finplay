// CommunityPostImage 엔티티로 V26 마이그레이션의 FK·UNIQUE(post_id) 제약을 실제 저장 흐름에서 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.community.domain.CommunityPostImage;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
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
class CommunityPostImageRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 12, 34, 56, 123456000);

	@Autowired
	private CommunityPostImageRepository repository;

	@Autowired
	private CommunityPostRepository postRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@BeforeEach
	void cleanSharedTablesInForeignKeySafeOrder() {
		jdbcTemplate.update("delete from community_post_images");
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_posts");
	}

	@Test
	void savePersistsUploaderAndUnassignedPostAsNull() {
		User uploader = createUser("save");

		CommunityPostImage saved = repository.saveAndFlush(
			CommunityPostImage.create(uploader, "stored.png", "original.png", "image/png", 123L, NOW));

		CommunityPostImage found = repository.findById(saved.getId()).orElseThrow();
		assertThat(found.getUploader().getId()).isEqualTo(uploader.getId());
		assertThat(found.getPost()).isNull();
		assertThat(found.isAssigned()).isFalse();
		assertThat(found.getStoredFilename()).isEqualTo("stored.png");
		assertThat(found.getOriginalFilename()).isEqualTo("original.png");
		assertThat(found.getContentType()).isEqualTo("image/png");
		assertThat(found.getSizeBytes()).isEqualTo(123L);
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
	}

	@Test
	void assignToPostPersistsPostAssociationAfterFlush() {
		User uploader = createUser("assign");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(uploader, "title", "content", null, NOW));
		CommunityPostImage image = repository.saveAndFlush(
			CommunityPostImage.create(uploader, "stored.png", "original.png", "image/png", 1L, NOW));

		image.assignToPost(post);
		repository.saveAndFlush(image);
		entityManager.clear();

		CommunityPostImage found = repository.findById(image.getId()).orElseThrow();
		assertThat(found.getPost().getId()).isEqualTo(post.getId());
		assertThat(found.isAssigned()).isTrue();
	}

	@Test
	void savingSecondImageAssignedToSamePostViolatesUniqueConstraint() {
		User uploader = createUser("unique-post");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(uploader, "title", "content", null, NOW));
		CommunityPostImage first = CommunityPostImage.create(
			uploader, "first.png", "first.png", "image/png", 1L, NOW);
		first.assignToPost(post);
		repository.saveAndFlush(first);

		CommunityPostImage second = CommunityPostImage.create(
			uploader, "second.png", "second.png", "image/png", 1L, NOW);
		second.assignToPost(post);

		assertThatThrownBy(() -> repository.saveAndFlush(second))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void twoUnassignedImagesDoNotViolateUniqueConstraintOnNullPostId() {
		User firstUploader = createUser("null-post-1");
		User secondUploader = createUser("null-post-2");

		repository.saveAndFlush(
			CommunityPostImage.create(firstUploader, "a.png", "a.png", "image/png", 1L, NOW));
		repository.saveAndFlush(
			CommunityPostImage.create(secondUploader, "b.png", "b.png", "image/png", 1L, NOW));

		assertThat(repository.findAll()).hasSize(2);
	}

	@Test
	void savingImageWithUnknownUploaderViolatesForeignKeyConstraint() {
		User uploader = createUser("fk-detached");
		CommunityPostImage image = CommunityPostImage.create(
			uploader, "stored.png", "original.png", "image/png", 1L, NOW);
		userRepository.delete(uploader);
		userRepository.flush();

		assertThatThrownBy(() -> repository.saveAndFlush(image))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingAssignedPostCascadesToItsImage() {
		User uploader = createUser("cascade");
		CommunityPost post = postRepository.saveAndFlush(
			CommunityPost.create(uploader, "title", "content", null, NOW));
		CommunityPostImage image = CommunityPostImage.create(
			uploader, "cascade.png", "cascade.png", "image/png", 1L, NOW);
		image.assignToPost(post);
		CommunityPostImage saved = repository.saveAndFlush(image);

		jdbcTemplate.update("delete from community_posts where id = ?", post.getId());
		entityManager.clear();

		assertThat(repository.findById(saved.getId())).isEmpty();
	}

	private User createUser(String prefix) {
		String unique = UUID.randomUUID().toString().replace("-", "");
		return userRepository.saveAndFlush(
			User.create(prefix + "-" + unique + "@finplay.com", "hash", prefix + "-" + unique, NOW));
	}
}
