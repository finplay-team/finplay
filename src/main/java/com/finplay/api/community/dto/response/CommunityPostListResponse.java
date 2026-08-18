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

	// content는 호출부(CommunityPostService)가 이미 sharedTrade까지 채워 만든 응답 목록이다 — 게시물별로 다른
	// tradeId를 조회해야 해서(TRADESHARE-002) 이 record가 직접 CommunityPostResponse::from을 매핑하지 않는다.
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
