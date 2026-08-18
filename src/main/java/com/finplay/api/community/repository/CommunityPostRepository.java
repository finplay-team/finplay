// 커뮤니티 게시글의 영속화를 담당하는 JPA 리포지토리
package com.finplay.api.community.repository;

import com.finplay.api.community.domain.CommunityPost;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityPostRepository
	extends JpaRepository<CommunityPost, Long>, CommunityPostRepositoryCustom {

	@Override
	@EntityGraph(attributePaths = {"author", "instrument", "image"})
	Optional<CommunityPost> findById(Long id);

	// dirty checking(엔티티 읽기 → +1/-1 반영) 대신 원자적 UPDATE로 lost update를 막는다
	// (spec 045 plan.md "동시성" 참고). flushAutomatically=true가 없으면 clearAutomatically가
	// entityManager.clear()로 아직 flush되지 않은 같은 트랜잭션의 다른 변경(예: 좋아요 행 save/delete)을
	// 플러시 없이 버릴 수 있다 — CommunityPostLikeService.likePost/unlikePost가 좋아요 행 저장·삭제
	// 직후 이 메서드를 호출하는 순서에 의존하므로 반드시 먼저 flush한다.
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update CommunityPost p set p.likeCount = p.likeCount + 1 where p.id = :postId")
	void incrementLikeCount(@Param("postId")
	Long postId);

	// 감소는 항상 좋아요 행 존재를 먼저 확인한 뒤에만 호출되므로 하한 가드를 두지 않는다.
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update CommunityPost p set p.likeCount = p.likeCount - 1 where p.id = :postId")
	void decrementLikeCount(@Param("postId")
	Long postId);
}
