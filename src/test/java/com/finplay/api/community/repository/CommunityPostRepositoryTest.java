// 실제 MySQL에서 게시글 영속화와 작성자 외래 키 제약을 검증하는 JPA 슬라이스 테스트다.
package com.finplay.api.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.auth.domain.User;
import com.finplay.api.auth.repository.UserRepository;
import com.finplay.api.community.domain.CommunityPost;
import com.finplay.api.market.domain.Instrument;
import com.finplay.api.market.domain.Market;
import com.finplay.api.market.repository.InstrumentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

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
	private InstrumentRepository instrumentRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@BeforeEach
	void removePostsPersistedByOtherTestContexts() {
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_posts");
	}

	@Test
	void savePersistsAuthorTextAndMicrosecondTimestamps() {
		User author = userRepository.saveAndFlush(User.create("post@finplay.com", "hash", "poster", NOW));

		CommunityPost saved = repository.saveAndFlush(
			CommunityPost.create(author, "t".repeat(100), "c".repeat(5000), null, NOW));

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

	@Test
	void findPostsOrderByCreatedAtDescReturnsNewestFirst() {
		User author = userRepository.saveAndFlush(User.create("list@finplay.com", "hash", "lister", NOW));
		CommunityPost oldest = repository.saveAndFlush(
			CommunityPost.create(author, "oldest", "content", null, NOW.minusDays(2)));
		CommunityPost middle = repository.saveAndFlush(
			CommunityPost.create(author, "middle", "content", null, NOW.minusDays(1)));
		CommunityPost newest = repository.saveAndFlush(
			CommunityPost.create(author, "newest", "content", null, NOW));

		Page<CommunityPost> page = repository.findPostsOrderByCreatedAtDesc(PageRequest.of(0, 10));

		assertThat(page.getContent())
			.extracting(CommunityPost::getId)
			.containsExactly(newest.getId(), middle.getId(), oldest.getId());
	}

	@Test
	void findPostsOrderByCreatedAtDescBreaksTiesByIdDescendingForSameTimestamp() {
		User author = userRepository.saveAndFlush(User.create("tie@finplay.com", "hash", "tiebreaker", NOW));
		CommunityPost first = repository.saveAndFlush(CommunityPost.create(author, "first", "content", null, NOW));
		CommunityPost second = repository.saveAndFlush(CommunityPost.create(author, "second", "content", null, NOW));

		Page<CommunityPost> page = repository.findPostsOrderByCreatedAtDesc(PageRequest.of(0, 10));

		assertThat(page.getContent())
			.extracting(CommunityPost::getId)
			.containsExactly(second.getId(), first.getId());
	}

	@Test
	void findPostsOrderByCreatedAtDescFetchesAuthorsWithoutAdditionalQueries() {
		User author = userRepository.saveAndFlush(User.create("fetch@finplay.com", "hash", "fetcher", NOW));
		repository.saveAndFlush(CommunityPost.create(author, "first", "content", null, NOW.minusMinutes(1)));
		repository.saveAndFlush(CommunityPost.create(author, "second", "content", null, NOW));
		entityManager.clear();

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();

		Page<CommunityPost> page = repository.findPostsOrderByCreatedAtDesc(PageRequest.of(0, 1));
		page.getContent().forEach(post -> assertThat(post.getAuthor().getNickname()).isEqualTo("fetcher"));

		assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
	}

	@Test
	void findPostsOrderByCreatedAtDescPaginatesWithoutDuplicateOrMissingItemsAcrossPages() {
		User author = userRepository.saveAndFlush(User.create("page@finplay.com", "hash", "pager", NOW));
		List<Long> createdIds = List.of(
			repository.saveAndFlush(CommunityPost.create(author, "p1", "content", null, NOW.minusMinutes(4))).getId(),
			repository.saveAndFlush(CommunityPost.create(author, "p2", "content", null, NOW.minusMinutes(3))).getId(),
			repository.saveAndFlush(CommunityPost.create(author, "p3", "content", null, NOW.minusMinutes(2))).getId(),
			repository.saveAndFlush(CommunityPost.create(author, "p4", "content", null, NOW.minusMinutes(1))).getId(),
			repository.saveAndFlush(CommunityPost.create(author, "p5", "content", null, NOW)).getId());

		Page<CommunityPost> firstPage = repository.findPostsOrderByCreatedAtDesc(PageRequest.of(0, 3));
		Page<CommunityPost> secondPage = repository.findPostsOrderByCreatedAtDesc(PageRequest.of(1, 3));

		assertThat(firstPage.getTotalElements()).isEqualTo(5);
		assertThat(firstPage.getTotalPages()).isEqualTo(2);
		assertThat(firstPage.hasNext()).isTrue();
		assertThat(secondPage.hasNext()).isFalse();

		List<Long> combinedIds = Stream.concat(
			firstPage.getContent().stream().map(CommunityPost::getId),
			secondPage.getContent().stream().map(CommunityPost::getId))
			.toList();
		assertThat(combinedIds).hasSize(5).doesNotHaveDuplicates()
			.containsExactlyInAnyOrderElementsOf(createdIds);
	}

	@Test
	void findPostsOrderByCreatedAtDescReturnsEmptyPageWhenNoPostsExist() {
		Page<CommunityPost> page = repository.findPostsOrderByCreatedAtDesc(PageRequest.of(0, 10));

		assertThat(page.getContent()).isEmpty();
		assertThat(page.getTotalElements()).isEqualTo(0);
		assertThat(page.getTotalPages()).isEqualTo(0);
	}

	@Test
	void savePersistsInstrumentTagAndFindByIdFetchesItInOneAdditionalQuery() {
		User author = userRepository.saveAndFlush(User.create("tagger@finplay.com", "hash", "tagger", NOW));
		String symbol = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		Instrument instrument = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, symbol, "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW));
		CommunityPost saved = repository.saveAndFlush(
			CommunityPost.create(author, "tagged title", "tagged content", instrument, NOW));
		entityManager.clear();

		CommunityPost found = repository.findById(saved.getId()).orElseThrow();

		assertThat(found.getInstrument()).isNotNull();
		assertThat(found.getInstrument().getSymbol()).isEqualTo(symbol);
	}

	@Test
	void savePersistsNullInstrumentForUntaggedPostForBackwardCompatibility() {
		User author = userRepository.saveAndFlush(User.create("untagged@finplay.com", "hash", "untagged", NOW));

		CommunityPost saved = repository.saveAndFlush(
			CommunityPost.create(author, "untagged title", "untagged content", null, NOW));
		entityManager.clear();

		CommunityPost found = repository.findById(saved.getId()).orElseThrow();

		assertThat(found.getInstrument()).isNull();
	}

	@Test
	void databaseRejectsUnknownInstrumentForeignKey() {
		User author = userRepository.saveAndFlush(User.create("instrument-fk@finplay.com", "hash", "instr-fk", NOW));
		CommunityPost post = repository.saveAndFlush(
			CommunityPost.create(author, "title", "content", null, NOW));

		assertThatThrownBy(() -> jdbcTemplate.update(
			"update community_posts set instrument_id = ? where id = ?", Long.MAX_VALUE, post.getId()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void migrationCreatesInstrumentIdColumnAsNullable() {
		Map<String, Object> column = jdbcTemplate.queryForMap(
			"select is_nullable from information_schema.columns "
				+ "where table_schema = database() and table_name = 'community_posts' "
				+ "and column_name = 'instrument_id'");

		assertThat(column.get("is_nullable")).isEqualTo("YES");
	}

	@Test
	void migrationCreatesCompositeIndexOnInstrumentIdCreatedAtAndId() {
		List<Map<String, Object>> indexColumns = jdbcTemplate.queryForList(
			"select column_name, seq_in_index from information_schema.statistics "
				+ "where table_schema = database() and table_name = 'community_posts' "
				+ "and index_name = 'idx_community_posts_instrument_created' "
				+ "order by seq_in_index");

		assertThat(indexColumns).hasSize(3);
		assertThat(indexColumns.get(0).get("column_name")).isEqualTo("instrument_id");
		assertThat(indexColumns.get(1).get("column_name")).isEqualTo("created_at");
		assertThat(indexColumns.get(2).get("column_name")).isEqualTo("id");
	}
}
