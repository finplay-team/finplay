// 생성된 커뮤니티 게시글 댓글의 공개 필드만 반환하는 응답 DTO
package com.finplay.api.domain.community.dto.response;

import com.finplay.api.domain.community.entity.PostComment;
import java.time.LocalDateTime;
import java.util.List;

public record PostCommentResponse(
	Long commentId,
	String authorNickname,
	String content,
	LocalDateTime createdAt,
	Long parentCommentId,
	List<PostCommentResponse> replies) {

	private static final String TOMBSTONED_CONTENT = "삭제된 댓글입니다";
	private static final String TOMBSTONED_AUTHOR_DISPLAY = "(삭제됨)";

	public PostCommentResponse {
		replies = List.copyOf(replies);
	}

	public static PostCommentResponse from(PostComment comment) {
		return from(comment, List.of());
	}

	public static PostCommentResponse from(PostComment comment, List<PostCommentResponse> replies) {
		boolean tombstoned = comment.isTombstoned();
		return new PostCommentResponse(
			comment.getId(),
			tombstoned ? TOMBSTONED_AUTHOR_DISPLAY : comment.getAuthor().getNickname(),
			tombstoned ? TOMBSTONED_CONTENT : comment.getContent(),
			comment.getCreatedAt(),
			comment.getParentComment() != null ? comment.getParentComment().getId() : null,
			replies);
	}
}
