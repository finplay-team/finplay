// 주식 재생세션의 영속화와 서비스 날짜 기준 조회를 담당하는 JPA 리포지토리
package com.finplay.api.domain.market.repository;

import com.finplay.api.domain.market.entity.PreparationStatus;
import com.finplay.api.domain.market.entity.StockReplaySession;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockReplaySessionRepository extends JpaRepository<StockReplaySession, Long> {

	Optional<StockReplaySession> findByServiceDate(LocalDate serviceDate);

	Optional<StockReplaySession> findFirstByOrderByServiceDateDesc();

	// 오늘 이전 서비스 날짜의 READY 세션 중 가장 최근 것을 찾는다(QUOTE-HOLD-003) — 장 마감 폴백 시세·캔들의 원본 거래일 소스.
	Optional<StockReplaySession> findFirstByServiceDateBeforeAndPreparationStatusOrderByServiceDateDesc(
		LocalDate serviceDate, PreparationStatus preparationStatus);
}
