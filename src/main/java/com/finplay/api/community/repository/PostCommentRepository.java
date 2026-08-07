// 커뮤니티 게시글 댓글의 영속화를 담당하는 JPA 리포지토리
package com.finplay.api.community.repository;

import com.finplay.api.community.domain.PostComment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

	// 파생 delete 쿼리(`deleteBy...`)는 엔티티를 SELECT한 뒤 하나씩 remove하므로 부모·자식 댓글이 각각 별도
	// DELETE문으로 나간다 — V25의 ON DELETE CASCADE와 겹치면 자식 행이 이미 사라진 뒤 그 자식을 다시
	// 지우려는 DELETE가 나가는 경합이 생긴다. 벌크 @Modifying 쿼리로 부모·자식을 한 statement에서 함께
	// 지워 그 경합 가능성 자체를 없앤다(PR #260 리뷰).
	@Modifying(clearAutomatically = true)
	@Query("delete from PostComment comment where comment.post.id = :postId")
	void deleteByPost_Id(@Param("postId")
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
