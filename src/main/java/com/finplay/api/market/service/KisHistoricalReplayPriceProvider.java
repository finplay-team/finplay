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
	public List<StockCandleDto> getCandles(Long instrumentId, LocalDateTime from, LocalDateTime to) {
		return stockReplayService.getRevealedCandles(instrumentId, from, to);
	}
}
