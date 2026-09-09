package com.finplay.api.domain.community.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.auth.entity.User;
import com.finplay.api.domain.market.entity.Instrument;
import com.finplay.api.domain.market.entity.Market;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class CommunityPostTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 6, 12, 0, 0);

	private User author() {
		return User.create("author@finplay.com", "hash", "author", NOW);
	}

	private Instrument instrument() {
		return Instrument.create(Market.STOCK, "005930", "삼성전자", BigDecimal.valueOf(100), 70000L, true, NOW);
	}

	@Test
	void createWithoutInstrumentLeavesInstrumentNullForBackwardCompatibility() {
		CommunityPost post = CommunityPost.create(author(), "title", "content", null, NOW);

		assertThat(post.getInstrument()).isNull();
		assertThat(post.getTitle()).isEqualTo("title");
		assertThat(post.getContent()).isEqualTo("content");
	}

	@Test
	void createWithInstrumentTagsThePost() {
		Instrument instrument = instrument();

		CommunityPost post = CommunityPost.create(author(), "title", "content", instrument, NOW);

		assertThat(post.getInstrument()).isEqualTo(instrument);
	}

	@Test
	void updateReplacesInstrumentWithGivenValue() {
		CommunityPost post = CommunityPost.create(author(), "title", "content", null, NOW);
		Instrument instrument = instrument();
		LocalDateTime updatedAt = NOW.plusMinutes(1);

		post.update("new title", "new content", instrument, updatedAt);

		assertThat(post.getInstrument()).isEqualTo(instrument);
		assertThat(post.getTitle()).isEqualTo("new title");
		assertThat(post.getContent()).isEqualTo("new content");
		assertThat(post.getUpdatedAt()).isEqualTo(updatedAt);
	}

	@Test
	void updateWithNullInstrumentClearsExistingTag() {
		CommunityPost post = CommunityPost.create(author(), "title", "content", instrument(), NOW);

		post.update("title", "content", null, NOW.plusMinutes(1));

		assertThat(post.getInstrument()).isNull();
	}
}
