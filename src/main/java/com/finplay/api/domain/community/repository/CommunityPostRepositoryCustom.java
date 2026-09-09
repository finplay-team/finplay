package com.finplay.api.domain.community.repository;

import com.finplay.api.domain.community.entity.CommunityPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CommunityPostRepositoryCustom {

	Page<CommunityPost> findPosts(Pageable pageable, Long instrumentId, String sort);
}
