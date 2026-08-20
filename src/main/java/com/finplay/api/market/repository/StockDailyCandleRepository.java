// 주식 일봉 아카이브의 영속화와 종목별 최신 거래일·기간 조회를 담당하는 JPA 리포지토리
package com.finplay.api.market.repository;

import com.finplay.api.market.domain.StockDailyCandle;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockDailyCandleRepository extends JpaRepository<StockDailyCandle, Long> {

	// 수집기의 빈 구간 계산 전용 — 종목에 이미 저장된 가장 최근 거래일 하루만 확인한다(결정 1, plan.md).
	Optional<StockDailyCandle> findFirstByInstrumentIdOrderByTradingDateDesc(Long instrumentId);

	// 기간(구간, 양끝 포함) 조회 — 거래일 오름차순.
	List<StockDailyCandle> findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAsc(
		Long instrumentId, LocalDate from, LocalDate to);
}
