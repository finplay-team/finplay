// 게시물 목록과 페이지 메타데이터를 함께 노출하는 응답 DTO
package com.finplay.api.community.dto.response;

import com.finplay.api.community.domain.CommunityPost;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;

public record CommunityPostListResponse(
	List<CommunityPostResponse> content,
	int page,
	int size,
	long totalElements,
	int totalPages,
	boolean hasNext) {

	public CommunityPostListResponse {
		content = List.copyOf(content);
	}

	// likedPostIds: 이 페이지에 실린 게시물 중 요청자가 좋아요한 postId 집합(배치 조회 결과) — 게시물마다
	// 좋아요 여부를 따로 조회하지 않고 N+1을 방지한다(spec 045 plan.md).
	public static CommunityPostListResponse from(Page<CommunityPost> page, Set<Long> likedPostIds) {
		List<CommunityPostResponse> content = page.getContent().stream()
			.map(post -> CommunityPostResponse.from(post, likedPostIds.contains(post.getId())))
			.toList();
		return new CommunityPostListResponse(
			content,
			page.getNumber(),
			page.getSize(),
			page.getTotalElements(),
			page.getTotalPages(),
			page.hasNext());
	}
}
