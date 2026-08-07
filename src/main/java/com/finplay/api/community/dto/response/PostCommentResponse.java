// 생성된 커뮤니티 게시글 댓글의 공개 필드만 반환하는 응답 DTO
package com.finplay.api.community.dto.response;

import com.finplay.api.community.domain.PostComment;
import java.time.LocalDateTime;
import java.util.List;

public record PostCommentResponse(
	Long commentId,
	String authorNickname,
	String content,
	LocalDateTime createdAt,
	Long parentCommentId,
	List<PostCommentResponse> replies) {

	public PostCommentResponse {
		replies = List.copyOf(replies);
	}

	public static PostCommentResponse from(PostComment comment) {
		return from(comment, List.of());
	}

	public static PostCommentResponse from(PostComment comment, List<PostCommentResponse> replies) {
		return new PostCommentResponse(
			comment.getId(),
			comment.getAuthor().getNickname(),
			comment.getContent(),
			comment.getCreatedAt(),
			comment.getParentComment() != null ? comment.getParentComment().getId() : null,
			replies);
	}
}
