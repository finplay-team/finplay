package com.finplay.api.domain.community.repository;

import com.finplay.api.domain.community.entity.CommunityPost;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityPostRepository
	extends JpaRepository<CommunityPost, Long>, CommunityPostRepositoryCustom {

	@Override
	@EntityGraph(attributePaths = {"author", "instrument", "image"})
	Optional<CommunityPost> findById(Long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from CommunityPost p where p.id = :postId")
	Optional<CommunityPost> findByIdForUpdate(@Param("postId")
	Long postId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update CommunityPost p set p.likeCount = p.likeCount + 1 where p.id = :postId")
	void incrementLikeCount(@Param("postId")
	Long postId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update CommunityPost p set p.likeCount = p.likeCount - 1 where p.id = :postId and p.likeCount > 0")
	void decrementLikeCount(@Param("postId")
	Long postId);
}
