package com.finplay.api.domain.community.repository;

import com.finplay.api.domain.community.entity.PostComment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

	@Modifying(clearAutomatically = true)
	@Query("delete from PostComment comment where comment.post.id = :postId and comment.parentComment is not null")
	void deleteByPost_IdAndParentCommentIsNotNull(@Param("postId")
	Long postId);

	@Modifying(clearAutomatically = true)
	@Query("delete from PostComment comment where comment.post.id = :postId and comment.parentComment is null")
	void deleteByPost_IdAndParentCommentIsNull(@Param("postId")
	Long postId);

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
