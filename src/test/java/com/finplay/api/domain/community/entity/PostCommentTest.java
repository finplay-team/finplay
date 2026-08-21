// PostComment 생성 시 부모 댓글(parentComment) 반영과 isReply() 판정을 검증하는 단위 테스트다.
package com.finplay.api.domain.community.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.finplay.api.domain.auth.entity.User;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class PostCommentTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 7, 12, 0, 0);

	private User author() {
		return User.create("author@finplay.com", "hash", "author", NOW);
	}

	private CommunityPost post(User author) {
		return CommunityPost.create(author, "title", "content", null, NOW);
	}

	@Test
	void createWithoutParentCommentLeavesParentCommentNullForBackwardCompatibility() {
		User author = author();
		PostComment comment = PostComment.create(post(author), author, "content", null, NOW);

		assertThat(comment.getParentComment()).isNull();
		assertThat(comment.isReply()).isFalse();
	}

	@Test
	void createWithParentCommentSetsParentCommentAndMarksAsReply() {
		User author = author();
		CommunityPost post = post(author);
		PostComment parent = PostComment.create(post, author, "parent content", null, NOW);

		PostComment reply = PostComment.create(post, author, "reply content", parent, NOW.plusMinutes(1));

		assertThat(reply.getParentComment()).isEqualTo(parent);
		assertThat(reply.isReply()).isTrue();
	}

	@Test
	void isReplyReturnsFalseForTopLevelComment() {
		User author = author();
		PostComment topLevel = PostComment.create(post(author), author, "content", null, NOW);

		assertThat(topLevel.isReply()).isFalse();
	}

	@Test
	void isTombstonedReturnsFalseBeforeTombstoneIsCalled() {
		User author = author();
		PostComment comment = PostComment.create(post(author), author, "content", null, NOW);

		assertThat(comment.isTombstoned()).isFalse();
	}

	@Test
	void tombstoneSetsDeletedAtAndIsTombstonedReturnsTrue() {
		User author = author();
		PostComment comment = PostComment.create(post(author), author, "content", null, NOW);
		LocalDateTime deletedAt = NOW.plusMinutes(5);

		comment.tombstone(deletedAt);

		assertThat(comment.isTombstoned()).isTrue();
		assertThat(comment.getDeletedAt()).isEqualTo(deletedAt);
	}
}
