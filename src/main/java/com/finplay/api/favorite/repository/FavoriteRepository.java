// 즐겨찾기의 영속화와 사용자·종목 중복 조회를 담당하는 JPA 리포지토리
package com.finplay.api.favorite.repository;

import com.finplay.api.favorite.domain.Favorite;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

	@Query("""
		select favorite
		from Favorite favorite
		join fetch favorite.instrument
		where favorite.user.id = :userId
		order by favorite.createdAt desc, favorite.id desc
		""")
	List<Favorite> findAllByUserIdOrderByCreatedAtDescIdDesc(@Param("userId")
	Long userId);

	boolean existsByUserIdAndInstrumentId(Long userId, Long instrumentId);
}
