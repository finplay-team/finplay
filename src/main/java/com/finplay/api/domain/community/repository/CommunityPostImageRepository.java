// 커뮤니티 게시물 첨부 이미지의 저장·조회를 담당하는 리포지토리
package com.finplay.api.domain.community.repository;

import com.finplay.api.domain.community.entity.CommunityPostImage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityPostImageRepository extends JpaRepository<CommunityPostImage, Long> {}
