package com.finplay.api.domain.community.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finplay.api.TestcontainersConfiguration;
import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.auth.repository.UserRepository;
import com.finplay.api.domain.community.entity.CommunityPost;
import com.finplay.api.domain.community.entity.CommunityPostImage;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import com.finplay.api.domain.market.repository.InstrumentRepository;
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
	private CommunityPostImageRepository communityPostImageRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@BeforeEach
	void removePostsPersistedByOtherTestContexts() {
		jdbcTemplate.update("delete from post_comments where parent_comment_id is not null");
		jdbcTemplate.update("delete from post_comments");
		jdbcTemplate.update("delete from community_post_images");
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

		Page<CommunityPost> page = repository.findPosts(PageRequest.of(0, 10), null, "latest");

		assertThat(page.getContent())
			.extracting(CommunityPost::getId)
			.containsExactly(newest.getId(), middle.getId(), oldest.getId());
	}

	@Test
	void findPostsOrderByCreatedAtDescBreaksTiesByIdDescendingForSameTimestamp() {
		User author = userRepository.saveAndFlush(User.create("tie@finplay.com", "hash", "tiebreaker", NOW));
		CommunityPost first = repository.saveAndFlush(CommunityPost.create(author, "first", "content", null, NOW));
		CommunityPost second = repository.saveAndFlush(CommunityPost.create(author, "second", "content", null, NOW));

		Page<CommunityPost> page = repository.findPosts(PageRequest.of(0, 10), null, "latest");

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

		Page<CommunityPost> page = repository.findPosts(PageRequest.of(0, 1), null, "latest");
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

		Page<CommunityPost> firstPage = repository.findPosts(PageRequest.of(0, 3), null, "latest");
		Page<CommunityPost> secondPage = repository.findPosts(PageRequest.of(1, 3), null, "latest");

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
		Page<CommunityPost> page = repository.findPosts(PageRequest.of(0, 10), null, "latest");

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

	@Test
	void findPostsOrderByCreatedAtDescReturnsOnlyPostsTaggedWithGivenInstrumentId() {
		User author = userRepository.saveAndFlush(User.create("filter@finplay.com", "hash", "filterer", NOW));
		Instrument samsung = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, uniqueSymbol(), "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW));
		Instrument hynix = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, uniqueSymbol(), "SK하이닉스", BigDecimal.valueOf(100), 150000L, true, NOW));
		CommunityPost taggedSamsung = repository.saveAndFlush(
			CommunityPost.create(author, "samsung post", "content", samsung, NOW.minusMinutes(2)));
		repository.saveAndFlush(CommunityPost.create(author, "hynix post", "content", hynix, NOW.minusMinutes(1)));
		repository.saveAndFlush(CommunityPost.create(author, "untagged post", "content", null, NOW));

		Page<CommunityPost> page = repository.findPosts(PageRequest.of(0, 10), samsung.getId(), "latest");

		assertThat(page.getContent()).extracting(CommunityPost::getId).containsExactly(taggedSamsung.getId());
		assertThat(page.getTotalElements()).isEqualTo(1);
	}

	@Test
	void findPostsOrderByCreatedAtDescReturnsAllPostsWhenInstrumentIdIsNull() {
		User author = userRepository.saveAndFlush(User.create("nofilter@finplay.com", "hash", "nofilterer", NOW));
		Instrument samsung = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, uniqueSymbol(), "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW));
		CommunityPost tagged = repository.saveAndFlush(
			CommunityPost.create(author, "tagged", "content", samsung, NOW.minusMinutes(1)));
		CommunityPost untagged = repository.saveAndFlush(
			CommunityPost.create(author, "untagged", "content", null, NOW));

		Page<CommunityPost> page = repository.findPosts(PageRequest.of(0, 10), null, "latest");

		assertThat(page.getContent())
			.extracting(CommunityPost::getId)
			.containsExactly(untagged.getId(), tagged.getId());
		assertThat(page.getTotalElements()).isEqualTo(2);
	}

	@Test
	void findPostsOrderByCreatedAtDescFetchesInstrumentWithoutAdditionalQueriesWhenFilteringByInstrumentId() {
		User author = userRepository.saveAndFlush(User.create("fetchtag@finplay.com", "hash", "fetchtagger", NOW));
		Instrument samsung = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, uniqueSymbol(), "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW));
		repository.saveAndFlush(
			CommunityPost.create(author, "first", "content", samsung, NOW.minusMinutes(1)));
		repository.saveAndFlush(CommunityPost.create(author, "second", "content", samsung, NOW));
		entityManager.clear();

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();

		Page<CommunityPost> page = repository.findPosts(PageRequest.of(0, 10), samsung.getId(), "latest");
		page.getContent().forEach(post -> assertThat(post.getInstrument().getSymbol())
			.isEqualTo(samsung.getSymbol()));

		assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
	}

	@Test
	void findByIdFetchesAssignedImageWithoutAdditionalQuery() {
		User author = userRepository.saveAndFlush(User.create("imagefind@finplay.com", "hash", "imagefinder", NOW));
		CommunityPost post = repository.saveAndFlush(
			CommunityPost.create(author, "with image", "content", null, NOW));
		CommunityPostImage image = communityPostImageRepository.saveAndFlush(
			CommunityPostImage.create(author, "stored.png", "original.png", "image/png", 10L, NOW));
		image.assignToPost(post);
		communityPostImageRepository.saveAndFlush(image);
		entityManager.clear();

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();

		CommunityPost found = repository.findById(post.getId()).orElseThrow();

		assertThat(found.getImage()).isNotNull();
		assertThat(found.getImage().getStoredFilename()).isEqualTo("stored.png");
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
	}

	@Test
	void findByIdReturnsNullImageForUnattachedPost() {
		User author = userRepository.saveAndFlush(User.create("noimage@finplay.com", "hash", "noimage", NOW));
		CommunityPost post = repository.saveAndFlush(
			CommunityPost.create(author, "no image", "content", null, NOW));
		entityManager.clear();

		CommunityPost found = repository.findById(post.getId()).orElseThrow();

		assertThat(found.getImage()).isNull();
	}

	@Test
	void findPostsOrderByCreatedAtDescFetchesImagesWithoutAdditionalQueries() {
		User author = userRepository.saveAndFlush(User.create("imagelist@finplay.com", "hash", "imagelister", NOW));
		CommunityPost withImagePost = repository.saveAndFlush(
			CommunityPost.create(author, "with image", "content", null, NOW.minusMinutes(1)));
		CommunityPostImage image = communityPostImageRepository.saveAndFlush(
			CommunityPostImage.create(author, "stored.png", "original.png", "image/png", 10L, NOW));
		image.assignToPost(withImagePost);
		communityPostImageRepository.saveAndFlush(image);
		repository.saveAndFlush(CommunityPost.create(author, "without image", "content", null, NOW));
		entityManager.clear();

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();

		Page<CommunityPost> page = repository.findPosts(PageRequest.of(0, 10), null, "latest");

		assertThat(page.getContent()).hasSize(2);
		CommunityPost fetchedWithImage = page.getContent().stream()
			.filter(post -> post.getId().equals(withImagePost.getId()))
			.findFirst()
			.orElseThrow();
		assertThat(fetchedWithImage.getImage()).isNotNull();
		assertThat(fetchedWithImage.getImage().getStoredFilename()).isEqualTo("stored.png");
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
	}

	@Test
	void incrementLikeCountIncreasesLikeCountByOneAndPersists() {
		User author = userRepository.saveAndFlush(User.create("incr@finplay.com", "hash", "incrementer", NOW));
		CommunityPost post = repository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));

		repository.incrementLikeCount(post.getId());

		CommunityPost found = repository.findById(post.getId()).orElseThrow();
		assertThat(found.getLikeCount()).isEqualTo(1L);
	}

	@Test
	void incrementLikeCountAppliesRepeatedCallsCumulatively() {
		User author = userRepository.saveAndFlush(User.create("incrmulti@finplay.com", "hash", "incrmulti", NOW));
		CommunityPost post = repository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));

		repository.incrementLikeCount(post.getId());
		repository.incrementLikeCount(post.getId());
		repository.incrementLikeCount(post.getId());

		CommunityPost found = repository.findById(post.getId()).orElseThrow();
		assertThat(found.getLikeCount()).isEqualTo(3L);
	}

	@Test
	void decrementLikeCountDecreasesLikeCountByOneAndPersists() {
		User author = userRepository.saveAndFlush(User.create("decr@finplay.com", "hash", "decrementer", NOW));
		CommunityPost post = repository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));
		repository.incrementLikeCount(post.getId());
		repository.incrementLikeCount(post.getId());

		repository.decrementLikeCount(post.getId());

		CommunityPost found = repository.findById(post.getId()).orElseThrow();
		assertThat(found.getLikeCount()).isEqualTo(1L);
	}

	@Test
	void decrementLikeCountLeavesZeroUntouchedInsteadOfGoingNegative() {
		User author = userRepository.saveAndFlush(User.create("decrzero@finplay.com", "hash", "decrzero", NOW));
		CommunityPost post = repository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));

		repository.decrementLikeCount(post.getId());

		CommunityPost found = repository.findById(post.getId()).orElseThrow();
		assertThat(found.getLikeCount()).isZero();
	}

	@Test
	void findByIdForUpdateReturnsPostAndEmptyForMissingId() {
		User author = userRepository.saveAndFlush(User.create("lock@finplay.com", "hash", "locker", NOW));
		CommunityPost post = repository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));
		repository.incrementLikeCount(post.getId());

		CommunityPost locked = repository.findByIdForUpdate(post.getId()).orElseThrow();

		assertThat(locked.getId()).isEqualTo(post.getId());
		assertThat(locked.getLikeCount()).isEqualTo(1L);
		assertThat(repository.findByIdForUpdate(Long.MAX_VALUE)).isEmpty();
	}

	@Test
	void newlyCreatedPostStartsWithZeroLikeCount() {
		User author = userRepository.saveAndFlush(User.create("zero@finplay.com", "hash", "zeroer", NOW));

		CommunityPost post = repository.saveAndFlush(CommunityPost.create(author, "title", "content", null, NOW));

		assertThat(post.getLikeCount()).isEqualTo(0L);
	}

	@Test
	void findPostsOrdersByLikeCountDescendingForPopularSort() {
		User author = userRepository.saveAndFlush(User.create("popular@finplay.com", "hash", "popularer", NOW));
		CommunityPost fewLikes = repository.saveAndFlush(
			CommunityPost.create(author, "few likes", "content", null, NOW.minusMinutes(3)));
		CommunityPost manyLikes = repository.saveAndFlush(
			CommunityPost.create(author, "many likes", "content", null, NOW.minusMinutes(2)));
		CommunityPost midLikes = repository.saveAndFlush(
			CommunityPost.create(author, "mid likes", "content", null, NOW.minusMinutes(1)));
		repository.incrementLikeCount(fewLikes.getId());
		repository.incrementLikeCount(manyLikes.getId());
		repository.incrementLikeCount(manyLikes.getId());
		repository.incrementLikeCount(manyLikes.getId());
		repository.incrementLikeCount(midLikes.getId());
		repository.incrementLikeCount(midLikes.getId());

		Page<CommunityPost> page = repository.findPosts(PageRequest.of(0, 10), null, "popular");

		assertThat(page.getContent())
			.extracting(CommunityPost::getId)
			.containsExactly(manyLikes.getId(), midLikes.getId(), fewLikes.getId());
	}

	@Test
	void findPostsBreaksLikeCountTiesByCreatedAtDescendingForPopularSort() {
		User author = userRepository.saveAndFlush(User.create("tiepopular@finplay.com", "hash", "tiepopularer", NOW));
		CommunityPost older = repository.saveAndFlush(
			CommunityPost.create(author, "older", "content", null, NOW.minusDays(1)));
		CommunityPost newer = repository.saveAndFlush(
			CommunityPost.create(author, "newer", "content", null, NOW));
		repository.incrementLikeCount(older.getId());
		repository.incrementLikeCount(newer.getId());

		Page<CommunityPost> page = repository.findPosts(PageRequest.of(0, 10), null, "popular");

		assertThat(page.getContent())
			.extracting(CommunityPost::getId)
			.containsExactly(newer.getId(), older.getId());
	}

	@Test
	void findPostsPopularSortReturnsOnlyPostsTaggedWithGivenInstrumentId() {
		User author = userRepository.saveAndFlush(
			User.create("popularfilter@finplay.com", "hash", "popularfilterer", NOW));
		Instrument samsung = instrumentRepository.saveAndFlush(
			Instrument.create(Market.STOCK, uniqueSymbol(), "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW));
		CommunityPost taggedFewLikes = repository.saveAndFlush(
			CommunityPost.create(author, "tagged few likes", "content", samsung, NOW.minusMinutes(2)));
		CommunityPost taggedManyLikes = repository.saveAndFlush(
			CommunityPost.create(author, "tagged many likes", "content", samsung, NOW.minusMinutes(1)));
		CommunityPost untaggedManyLikes = repository.saveAndFlush(
			CommunityPost.create(author, "untagged many likes", "content", null, NOW));
		repository.incrementLikeCount(taggedFewLikes.getId());
		repository.incrementLikeCount(taggedManyLikes.getId());
		repository.incrementLikeCount(taggedManyLikes.getId());
		repository.incrementLikeCount(untaggedManyLikes.getId());
		repository.incrementLikeCount(untaggedManyLikes.getId());
		repository.incrementLikeCount(untaggedManyLikes.getId());

		Page<CommunityPost> page = repository.findPosts(PageRequest.of(0, 10), samsung.getId(), "popular");

		assertThat(page.getContent())
			.extracting(CommunityPost::getId)
			.containsExactly(taggedManyLikes.getId(), taggedFewLikes.getId());
	}

	private static String uniqueSymbol() {
		return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
	}
}
