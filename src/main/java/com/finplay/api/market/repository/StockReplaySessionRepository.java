// 주식 재생세션의 영속화와 서비스 날짜 기준 조회를 담당하는 JPA 리포지토리
package com.finplay.api.market.repository;

import com.finplay.api.market.domain.StockReplaySession;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockReplaySessionRepository extends JpaRepository<StockReplaySession, Long> {

	Optional<StockReplaySession> findByServiceDate(LocalDate serviceDate);

	Optional<StockReplaySession> findFirstByOrderByServiceDateDesc();
}
