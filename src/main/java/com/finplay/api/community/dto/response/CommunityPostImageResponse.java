// 커뮤니티 게시물 첨부 이미지 업로드 결과(식별자·다운로드 URL)를 담는 응답 DTO
package com.finplay.api.community.dto.response;

import com.finplay.api.community.domain.CommunityPostImage;

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
