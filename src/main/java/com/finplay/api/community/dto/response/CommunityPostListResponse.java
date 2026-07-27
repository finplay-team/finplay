// 게시물 목록과 페이지 메타데이터를 함께 노출하는 응답 DTO
package com.finplay.api.community.dto.response;

import com.finplay.api.community.domain.CommunityPost;
import java.util.List;
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

	public static CommunityPostListResponse from(Page<CommunityPost> page) {
		List<CommunityPostResponse> content = page.getContent().stream()
			.map(CommunityPostResponse::from)
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
