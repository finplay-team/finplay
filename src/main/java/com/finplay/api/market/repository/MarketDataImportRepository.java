// 수집 시도 이력의 영속화와 거래일 기준 조회를 담당하는 JPA 리포지토리
package com.finplay.api.market.repository;

import com.finplay.api.market.domain.MarketDataImport;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketDataImportRepository extends JpaRepository<MarketDataImport, Long> {

	List<MarketDataImport> findBySourceTradingDateOrderByCollectedAtDesc(LocalDate sourceTradingDate);

	// 로컬 실수집 트리거(StockDailyImportTriggerWriter) 전용 — source(배치 종류)로 좁혀 그 거래일의 가장 최근
	// 시도 1건만 확인한다. source를 넣지 않으면 같은 sourceTradingDate에 1분봉("KIS")·일봉("KIS_DAILY") 이력이
	// 섞여 나온다(둘 다 "직전 영업일"을 기준으로 삼기 때문).
	Optional<MarketDataImport> findFirstBySourceAndSourceTradingDateOrderByCollectedAtDesc(
		String source, LocalDate sourceTradingDate);
}
