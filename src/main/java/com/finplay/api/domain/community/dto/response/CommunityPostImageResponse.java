package com.finplay.api.domain.community.dto.response;

import com.finplay.api.domain.community.entity.CommunityPostImage;

public record CommunityPostImageResponse(
	Long imageId,
	String imageUrl) {

	public static CommunityPostImageResponse from(CommunityPostImage image) {
		return new CommunityPostImageResponse(image.getId(), toImageUrl(image.getId()));
	}

	public static String toImageUrl(Long imageId) {
		return "/api/community/posts/images/" + imageId + "/file";
	}
}
