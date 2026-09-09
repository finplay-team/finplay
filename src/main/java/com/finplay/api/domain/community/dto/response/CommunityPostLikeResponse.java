package com.finplay.api.domain.community.dto.response;

public record CommunityPostLikeResponse(
	Long postId,
	long likeCount,
	boolean likedByMe) {
}
