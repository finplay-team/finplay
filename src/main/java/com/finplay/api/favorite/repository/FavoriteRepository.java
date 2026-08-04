// 즐겨찾기의 영속화와 사용자·종목 조회·잠금을 담당하는 JPA 리포지토리
package com.finplay.api.favorite.repository;

import com.finplay.api.favorite.domain.Favorite;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
		select favorite
		from Favorite favorite
		where favorite.user.id = :userId
			and favorite.instrument.id = :instrumentId
		""")
	Optional<Favorite> findByUserIdAndInstrumentIdForUpdate(
		@Param("userId")
		Long userId,
		@Param("instrumentId")
		Long instrumentId);
}
