// 게시물 목록과 페이지 메타데이터를 함께 노출하는 응답 DTO
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

	// content는 호출부(CommunityPostService)가 좋아요 여부(배치 조회, spec 045 plan.md)·sharedTrade(게시물별
	// tradeId 조회, TRADESHARE-002)까지 이미 채워 만든 응답 목록이다 — 이 record는 페이지 메타데이터만 덧붙인다.
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
