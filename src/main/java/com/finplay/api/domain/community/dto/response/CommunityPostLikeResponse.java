// 게시물 좋아요 표시·취소 결과(좋아요 총 개수·본인 표시 여부)를 담는 응답 DTO
package com.finplay.api.domain.community.dto.response;

public record CommunityPostLikeResponse(
	Long postId,
	long likeCount,
	boolean likedByMe) {
}
