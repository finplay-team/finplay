// 커뮤니티 게시글에 작성된 댓글(1단계 대댓글 포함)의 작성자, 본문, 생성 시각을 표현하는 엔티티
package com.finplay.api.domain.community.entity;

import com.finplay.api.domain.auth.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "post_comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostComment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "post_id", nullable = false)
	private CommunityPost post;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private User author;

	@Column(nullable = false, length = 1000)
	private String content;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "parent_comment_id")
	private PostComment parentComment;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;

	private PostComment(
		CommunityPost post,
		User author,
		String content,
		PostComment parentComment,
		LocalDateTime createdAt) {
		this.post = post;
		this.author = author;
		this.content = content;
		this.parentComment = parentComment;
		this.createdAt = createdAt;
	}

	public static PostComment create(
		CommunityPost post,
		User author,
		String content,
		PostComment parentComment,
		LocalDateTime createdAt) {
		return new PostComment(post, author, content, parentComment, createdAt);
	}

	public boolean isReply() {
		return parentComment != null;
	}

	public void tombstone(LocalDateTime deletedAt) {
		this.deletedAt = deletedAt;
	}

	public boolean isTombstoned() {
		return deletedAt != null;
	}
}
