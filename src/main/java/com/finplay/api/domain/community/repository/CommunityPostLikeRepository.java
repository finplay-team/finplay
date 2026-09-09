package com.finplay.api.domain.community.repository;

import com.finplay.api.domain.community.entity.CommunityPostLike;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityPostLikeRepository extends JpaRepository<CommunityPostLike, Long> {

	boolean existsByPost_IdAndUser_Id(Long postId, Long userId);

	Optional<CommunityPostLike> findByPost_IdAndUser_Id(Long postId, Long userId);

	@Query("select l.post.id from CommunityPostLike l where l.user.id = :userId and l.post.id in :postIds")
	List<Long> findLikedPostIds(@Param("userId")
	Long userId, @Param("postIds")
	List<Long> postIds);
}
