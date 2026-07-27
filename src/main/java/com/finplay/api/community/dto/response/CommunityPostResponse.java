// 생성된 커뮤니티 게시글과 작성자 닉네임을 노출하는 응답 DTO
package com.finplay.api.community.dto.response;

import com.finplay.api.community.domain.CommunityPost;
import java.time.LocalDateTime;

public record CommunityPostResponse(
	Long postId,
	String authorNickname,
	String title,
	String content,
	LocalDateTime createdAt,
	LocalDateTime updatedAt) {

	public static CommunityPostResponse from(CommunityPost post) {
		return new CommunityPostResponse(
			post.getId(),
			post.getAuthor().getNickname(),
			post.getTitle(),
			post.getContent(),
			post.getCreatedAt(),
			post.getUpdatedAt());
	}
}
