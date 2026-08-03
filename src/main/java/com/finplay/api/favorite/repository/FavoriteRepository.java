// 즐겨찾기의 영속화와 사용자·종목 중복 조회를 담당하는 JPA 리포지토리
package com.finplay.api.favorite.repository;

import com.finplay.api.favorite.domain.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

	boolean existsByUserIdAndInstrumentId(Long userId, Long instrumentId);
}
