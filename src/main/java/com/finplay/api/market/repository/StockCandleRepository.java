// 주식 1분봉의 영속화와 거래일·시각 기준 조회를 담당하는 JPA 리포지토리
package com.finplay.api.market.repository;

import com.finplay.api.market.domain.StockCandle;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockCandleRepository extends JpaRepository<StockCandle, Long> {

	List<StockCandle> findByInstrumentIdAndTradingDateOrderByCandleTimeAsc(Long instrumentId, LocalDate tradingDate);

	Optional<StockCandle> findByInstrumentIdAndTradingDateAndCandleTime(
		Long instrumentId, LocalDate tradingDate, LocalTime candleTime);

	Optional<StockCandle> findFirstByInstrumentIdAndTradingDateOrderByCandleTimeAsc(
		Long instrumentId, LocalDate tradingDate);

	Optional<StockCandle> findFirstByInstrumentIdAndTradingDateAndCandleTimeLessThanEqualOrderByCandleTimeDesc(
		Long instrumentId, LocalDate tradingDate, LocalTime candleTime);

	// 캔들 API — 종목·거래일·분봉시각(from~to, 양끝 포함) 범위 조회
	List<StockCandle> findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc(
		Long instrumentId, LocalDate tradingDate, LocalTime from, LocalTime to);

	// 집계 캔들(1d·1w·1M) API 전용 — 종목·거래일(from~to, 양끝 포함) 범위의 분봉을 거래일 오름차순 →
	// 그 안에서 분봉시각 오름차순으로 조회한다(이슈 #143). 재생거래일 자체는 이 쿼리로 조회하지 않고
	// findByInstrumentIdAndTradingDateAndCandleTimeBetweenOrderByCandleTimeAsc로 컷오프까지만 별도 조회한다.
	List<StockCandle> findByInstrumentIdAndTradingDateBetweenOrderByTradingDateAscCandleTimeAsc(
		Long instrumentId, LocalDate from, LocalDate to);

	// StockReplaySessionScheduler 전용 — 후보 거래일에 실제로 저장된 분봉이 있는지 확인(종목 무관)
	boolean existsByTradingDate(LocalDate tradingDate);

	// KisHistoricalCandleCollector 전용 — 종목·거래일 단위 멱등 스킵 판정. 이미 수집된 날 최대 390행을 전부 로드하는
	// findByInstrumentIdAndTradingDateOrderByCandleTimeAsc 대신 존재 여부만 확인한다(PR #94 리뷰 권장사항).
	boolean existsByInstrumentIdAndTradingDate(Long instrumentId, LocalDate tradingDate);

	// 로컬 실수집 트리거 전용 — 그 거래일에 실제로 수집된 분봉 수를 보고한다(0이면 수집 실패).
	long countByTradingDateAndDataSource(LocalDate tradingDate, String dataSource);

	// 집계 캔들(1d·1w·1M) 조회 하한 좁히기 전용(이슈 #155) — 종목·거래일 범위(양끝 포함) 안의 서로 다른 거래일만
	// 최신순으로 가볍게 조회한다. 분봉(하루 최대 391행)이 아니라 거래일 단위(하루 최대 1행)만 읽으므로, 응답 200개
	// 버킷을 만드는 데 실제로 필요한 시작일을 먼저 알아내기 위해 전체 분봉을 읽지 않고 이 쿼리부터 쓴다.
	// 단일 필드(tradingDate) 프로젝션은 파생 쿼리 메서드 이름만으로는 만들 수 없어(엔티티 전체를 반환하려다
	// LocalDate로의 변환 실패) @Query로 직접 SELECT DISTINCT를 쓴다 — instrument는 연관관계라 JPQL에서는
	// instrument.id로 접근한다.
	@Query("SELECT DISTINCT c.tradingDate FROM StockCandle c "
		+ "WHERE c.instrument.id = :instrumentId AND c.tradingDate BETWEEN :from AND :to "
		+ "ORDER BY c.tradingDate DESC")
	List<LocalDate> findDistinctTradingDateByInstrumentIdAndTradingDateBetweenOrderByTradingDateDesc(
		@Param("instrumentId")
		Long instrumentId, @Param("from")
		LocalDate from, @Param("to")
		LocalDate to,
		Pageable pageable);
}
