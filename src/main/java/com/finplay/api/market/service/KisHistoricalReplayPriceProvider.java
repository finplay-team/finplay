// StockPriceProvider의 유일한 구현체 — 내부적으로 StockReplayService(과거 데이터 재생)에 위임한다.
// MVP는 실행 환경 전환 설정을 두지 않으므로(이슈 #19) 고를 대상이 하나뿐이라 @Service로 직접 등록한다.
package com.finplay.api.market.service;

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
		// 이슈 #143(013) 1단계 배관: 집계(1d·1w·1M) 위임은 아직 없다(항목 ③에서 StockReplayService에 추가 예정).
		// 지금은 1m만 기존 경로로 조회하고 그 외 interval은 빈 목록을 반환한다 — 조용히 잘못된 값을 섞지 않는다.
		if (interval.isAggregated()) {
			return List.of();
		}
		return stockReplayService.getRevealedCandles(instrumentId, from, to);
	}
}
