// 커뮤니티 게시글의 영속화를 담당하는 JPA 리포지토리
package com.finplay.api.community.repository;

import com.finplay.api.community.domain.CommunityPost;
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

	// 좋아요 표시·취소가 게시물 행을 먼저 잡아 락 획득 순서를 통일한다(PR #442 2차 리뷰: 통일 전에는
	// 같은 게시물 동시 요청이 유니크 인덱스와 행 락을 엇갈린 순서로 잡아 InnoDB 데드락 → 500이 났다).
	// findById와 달리 @EntityGraph를 붙이지 않는다 — 좋아요 응답에 author·instrument·image가 필요 없다.
	// (image는 @OneToOne(mappedBy) 역방향이라 바이트코드 인핸스먼트 없이는 지연되지 않아 보조 SELECT가 1회
	// 더 나간다. author·instrument는 실제로 지연된다.)
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from CommunityPost p where p.id = :postId")
	Optional<CommunityPost> findByIdForUpdate(@Param("postId")
	Long postId);

	// dirty checking(엔티티 읽기 → +1/-1 반영) 대신 원자적 UPDATE로 lost update를 막는다
	// (spec 045 plan.md "동시성" 참고). flushAutomatically=true가 없으면 clearAutomatically가
	// entityManager.clear()로 아직 flush되지 않은 같은 트랜잭션의 다른 변경(예: 좋아요 행 save/delete)을
	// 플러시 없이 버릴 수 있다 — CommunityPostLikeService.likePost/unlikePost가 좋아요 행 저장·삭제
	// 직후 이 메서드를 호출하는 순서에 의존하므로 반드시 먼저 flush한다.
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update CommunityPost p set p.likeCount = p.likeCount + 1 where p.id = :postId")
	void incrementLikeCount(@Param("postId")
	Long postId);

	// 감소는 findByIdForUpdate의 게시물 행 락으로 직렬화된 뒤에만 호출되지만, likeCount가 SORT-001 인기순
	// 정렬 기준이라 오염되면 정렬 신뢰성이 깨지므로 like_count > 0 하한 가드를 방어 심층화로 둔다
	// (PR #442 2차 리뷰: 락 도입 전에는 동시 취소로 -1까지 내려갔다).
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update CommunityPost p set p.likeCount = p.likeCount - 1 where p.id = :postId and p.likeCount > 0")
	void decrementLikeCount(@Param("postId")
	Long postId);
}
