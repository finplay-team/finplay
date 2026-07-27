// 커뮤니티 게시글 댓글의 영속화를 담당하는 JPA 리포지토리
package com.finplay.api.community.repository;

import com.finplay.api.community.domain.PostComment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

	void deleteByPost_Id(Long postId);
}
