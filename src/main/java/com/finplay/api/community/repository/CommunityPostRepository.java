// 커뮤니티 게시글의 영속화를 담당하는 JPA 리포지토리
package com.finplay.api.community.repository;

import com.finplay.api.community.domain.CommunityPost;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityPostRepository
	extends JpaRepository<CommunityPost, Long>, CommunityPostRepositoryCustom {}
