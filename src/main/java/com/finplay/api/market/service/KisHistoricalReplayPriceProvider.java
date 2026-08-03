// StockPriceProvider의 유일한 구현체 — 내부적으로 StockReplayService(과거 데이터 재생)에 위임한다.
// MVP는 실행 환경 전환 설정을 두지 않으므로(이슈 #19) 고를 대상이 하나뿐이라 @Service로 직접 등록한다.
package com.finplay.api.market.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KisHistoricalReplayPriceProvider implements StockPriceProvider {

	private final StockReplayService stockReplayService;

	@Override
	public StockMarketStatus getMarketStatus() {
		return stockReplayService.getMarketStatus();
	}

	@Override
	public StockReplayPriceDto getCurrentPrice(Long instrumentId) {
		return stockReplayService.getCurrentPrice(instrumentId);
	}

	@Override
	public List<StockReplayPriceDto> getCurrentPrices(List<Long> instrumentIds) {
		return stockReplayService.getCurrentPrices(instrumentIds);
	}

	@Override
	public List<StockCandleDto> getCandles(
		Long instrumentId, CandleInterval interval, LocalDateTime from, LocalDateTime to) {
		// 이슈 #143(013) 항목 ③: 집계(1d·1w·1M)는 날짜 성분만 써서 StockReplayService.getRevealedAggregatedCandles로
		// 위임한다(CandleQueryService가 이미 날짜 기준 from>to 검증을 마쳤다 — 여기서는 LocalDate로 변환만 한다).
		if (interval.isAggregated()) {
			LocalDate fromDate = from != null ? from.toLocalDate() : null;
			LocalDate toDate = to != null ? to.toLocalDate() : null;
			return stockReplayService.getRevealedAggregatedCandles(instrumentId, interval, fromDate, toDate);
		}
		return stockReplayService.getRevealedCandles(instrumentId, from, to);
	}
}
