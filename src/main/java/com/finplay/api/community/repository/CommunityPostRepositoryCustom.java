// 게시물 목록 페이지네이션 조회의 커스텀 계약을 정의하는 인터페이스
package com.finplay.api.community.repository;

import com.finplay.api.community.domain.CommunityPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CommunityPostRepositoryCustom {

	Page<CommunityPost> findPostsOrderByCreatedAtDesc(Pageable pageable, Long instrumentId);
}
