// 게시물 좋아요의 영속화·조회를 담당하는 JPA 리포지토리
package com.finplay.api.community.repository;

import com.finplay.api.community.domain.CommunityPostLike;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityPostLikeRepository extends JpaRepository<CommunityPostLike, Long> {

	boolean existsByPost_IdAndUser_Id(Long postId, Long userId);

	Optional<CommunityPostLike> findByPost_IdAndUser_Id(Long postId, Long userId);

	// 목록 응답의 likedByMe를 게시물마다 조회하지 않고 한 번에 조회하기 위한 배치 조회(N+1 방지).
	@Query("select l.post.id from CommunityPostLike l where l.user.id = :userId and l.post.id in :postIds")
	List<Long> findLikedPostIds(@Param("userId")
	Long userId, @Param("postIds")
	List<Long> postIds);
}
