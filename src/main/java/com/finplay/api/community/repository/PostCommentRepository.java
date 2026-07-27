// 커뮤니티 게시글 댓글의 영속화를 담당하는 JPA 리포지토리
package com.finplay.api.community.repository;

import com.finplay.api.community.domain.PostComment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

	void deleteByPost_Id(Long postId);

	@Query("""
		select comment
		from PostComment comment
		join fetch comment.author
		where comment.post.id = :postId
		order by comment.createdAt asc, comment.id asc
		""")
	List<PostComment> findAllByPostIdOrderByCreatedAtAscIdAsc(@Param("postId")
	Long postId);
}
