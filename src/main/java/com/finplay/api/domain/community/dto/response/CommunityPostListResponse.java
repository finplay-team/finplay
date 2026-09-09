package com.finplay.api.domain.community.dto.response;

import com.finplay.api.domain.community.entity.CommunityPost;
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

	public static CommunityPostListResponse of(List<CommunityPostResponse> content, Page<CommunityPost> page) {
		return new CommunityPostListResponse(
			content,
			page.getNumber(),
			page.getSize(),
			page.getTotalElements(),
			page.getTotalPages(),
			page.hasNext());
	}
}
