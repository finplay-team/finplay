// 커뮤니티 게시글 댓글의 영속화를 담당하는 JPA 리포지토리
package com.finplay.api.domain.community.repository;

import com.finplay.api.domain.community.entity.PostComment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

	// 파생 delete 쿼리(`deleteBy...`)는 엔티티를 SELECT한 뒤 하나씩 remove하므로 여러 DELETE문으로 나간다
	// (PR #260 리뷰) — 그래서 벌크 @Modifying 쿼리를 쓴다. V31에서 parent_comment_id FK가
	// ON DELETE CASCADE에서 RESTRICT로 바뀌면서, 부모·자식을 한 statement로 함께 지우면 MySQL이 같은
	// DELETE 문 안에서 행 처리 순서를 보장하지 않아 부모가 자식보다 먼저 처리될 경우 FK 위반이 날 수
	// 있다(이슈 #277 회귀). 자식(대댓글)을 먼저 지우는 쿼리와 부모를 나중에 지우는 쿼리로 나눠 호출 측이
	// 순서를 명시적으로 보장하게 한다.
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
