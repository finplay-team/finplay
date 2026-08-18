// 게시물 목록 페이지네이션 조회의 커스텀 계약을 정의하는 인터페이스
package com.finplay.api.community.repository;

import com.finplay.api.community.domain.CommunityPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CommunityPostRepositoryCustom {

	// sort: "popular"(좋아요 내림차순, 동률은 최신순) | 그 외(기본 "latest", 최신순). 값 검증은 컨트롤러 책임.
	Page<CommunityPost> findPosts(Pageable pageable, Long instrumentId, String sort);
}
