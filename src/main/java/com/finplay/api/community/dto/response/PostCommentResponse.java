// 생성된 커뮤니티 게시글 댓글의 공개 필드만 반환하는 응답 DTO
package com.finplay.api.community.dto.response;

import com.finplay.api.community.domain.PostComment;
import java.time.LocalDateTime;

public record PostCommentResponse(
	Long commentId,
	String authorNickname,
	String content,
	LocalDateTime createdAt) {

	public static PostCommentResponse from(PostComment comment) {
		return new PostCommentResponse(
			comment.getId(),
			comment.getAuthor().getNickname(),
			comment.getContent(),
			comment.getCreatedAt());
	}
}
