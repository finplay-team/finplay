// 실제 MySQL에서 게시글 영속화와 작성자 외래 키 제약을 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.community.domain.CommunityPost;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.stream.Stream;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class CommunityPostRepositoryTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 27, 12, 34, 56, 123456000);

	@Autowired
	private CommunityPostRepository repository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void savePersistsAuthorTextAndMicrosecondTimestamps() {
		User author = userRepository.saveAndFlush(User.create("post@finplay.com", "hash", "poster", NOW));

		CommunityPost saved = repository.saveAndFlush(
			CommunityPost.create(author, "t".repeat(100), "c".repeat(5000), NOW));

		CommunityPost found = repository.findById(saved.getId()).orElseThrow();
		assertThat(found.getAuthor().getId()).isEqualTo(author.getId());
		assertThat(found.getTitle()).hasSize(100);
		assertThat(found.getContent()).hasSize(5000);
		assertThat(found.getCreatedAt()).isEqualTo(NOW);
		assertThat(found.getUpdatedAt()).isEqualTo(NOW);
	}

	@Test
	void databaseRejectsUnknownAuthorForeignKey() {
		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into community_posts(author_id,title,content,created_at,updated_at) values (?,?,?,?,?)",
			Long.MAX_VALUE, "title", "content", NOW, NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("overlongText")
	void databaseRejectsTextBeyondSchemaLength(
		String scenario, String title, String content) {
		User author = userRepository.saveAndFlush(User.create(
			scenario + "@finplay.com", "hash", scenario, NOW));

		assertThatThrownBy(() -> jdbcTemplate.update(
			"insert into community_posts(author_id,title,content,created_at,updated_at) values (?,?,?,?,?)",
			author.getId(), title, content, NOW, NOW))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private static Stream<Arguments> overlongText() {
		return Stream.of(
			Arguments.of("title-overlong", "t".repeat(101), "content"),
			Arguments.of("content-overlong", "title", "c".repeat(5001)));
	}
}
